package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.ModelFile
import com.wangzan101.mika.core.voice.ModelSpec
import com.wangzan101.mika.core.voice.VoiceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 一个模型此刻的样子。 */
enum class ModelPhase { Absent, Downloading, Paused, Verifying, Unpacking, Ready, Failed }

/** 下载没成的原因。 */
enum class ModelProblem {
    Network,
    /** 下下来的文件和清单里的 SHA-256 对不上(已删掉,重下)。 */
    Checksum,
    /** 设了只用 Wi-Fi 下载,现在不在 Wi-Fi 上。 */
    NeedsWifi,
    /** 手机剩的空间不够。 */
    Storage,
    Other,
}

/** [bytes] 是已经下好的字节数(包括断点前的),[total] 是清单里的总数。 */
data class ModelState(val phase: ModelPhase, val bytes: Long, val total: Long, val problem: ModelProblem? = null) {
    val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    val busy: Boolean get() = phase == ModelPhase.Downloading || phase == ModelPhase.Verifying || phase == ModelPhase.Unpacking
}

/**
 * 下载本机语音模型(对哪个模型都一样,按 [ModelSpec] 的文件清单来)。文件放在 `[root]/<模型编号>/`
 * (安卓上 root 是 noBackupFilesDir/voice,不进云备份):
 * - 每个文件先写到 `.partial/<文件名>.part`,断了下次用 Range 接着下(服务器不认 Range 就从头来);
 * - 下完核对 SHA-256,对不上就删掉、报 [ModelProblem.Checksum];对上了改成正式名字,.tar.bz2 则解进模型目录;
 * - 全部下好写一个 `.ready`,里面是清单的指纹([ModelSpec.fingerprint]);清单换了文件就当没下载;
 * - [wifiOnly] 为真时开始前和下载途中(每几 MB)看一眼 [onWifi],离开 Wi-Fi 就停下(保留已下的,能接着下)。
 *
 * [cancel] 只是暂停(留着已下的部分),[remove] 才删。进度在 [states] 里,按模型编号查。
 * 下载跑在 [scope] 里,应用在前台时一直下;进程被系统收掉后要再点一次下载才接着下。
 */
class ModelDownloads(
    private val root: File,
    private val http: OkHttpClient,
    private val wifiOnly: () -> Boolean,
    val onWifi: () -> Boolean,
    private val specs: List<ModelSpec> = VoiceCatalog.models,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 把清单里的地址换成实际请求的地址;只给单测换成本机的假服务器。 */
    private val resolve: (String) -> HttpUrl = { it.toHttpUrl() },
) {
    private val _states = MutableStateFlow(specs.associate { it.id to scan(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(spec: ModelSpec): ModelState = _states.value[spec.id] ?: scan(spec)

    /** 模型目录;文件都在这里,名字就是 [ModelFile.name](解包的除外)。 */
    fun dir(spec: ModelSpec): File = File(root, spec.id)

    /** 模型全部下好、核对过了(看 `.ready` 和文件大小,不重算 SHA-256)。 */
    fun isReady(spec: ModelSpec): Boolean {
        val dir = dir(spec)
        val marker = File(dir, READY)
        if (!marker.isFile || runCatching { marker.readText() }.getOrNull() != spec.fingerprint) return false
        return spec.files.all { it.unpack || File(dir, it.name).let { f -> f.isFile && f.length() == it.bytes } }
    }

    /** 开始或接着下载;已经在下或已经下好就什么也不做。 */
    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true || isReady(spec)) return
        val have = bytesOnDisk(spec)
        if (wifiOnly() && !onWifi()) { publish(spec, ModelState(ModelPhase.Failed, have, spec.downloadBytes, ModelProblem.NeedsWifi)); return }
        publish(spec, ModelState(ModelPhase.Downloading, have, spec.downloadBytes))
        jobs[spec.id] = scope.launch {
            try {
                fetchAll(spec)
                publish(spec, ModelState(ModelPhase.Ready, spec.downloadBytes, spec.downloadBytes))
            } catch (e: CancellationException) {
                publish(spec, scan(spec))
                throw e
            } catch (e: Problem) {
                publish(spec, ModelState(ModelPhase.Failed, bytesOnDisk(spec), spec.downloadBytes, e.problem))
            } catch (e: Exception) {
                publish(spec, ModelState(ModelPhase.Failed, bytesOnDisk(spec), spec.downloadBytes,
                    if (e is IOException) ModelProblem.Network else ModelProblem.Other))
            }
        }
    }

    /** 暂停:停下正在下的,已下的部分留着,下次接着下。 */
    fun cancel(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
    }

    /** 停下并删掉这个模型的所有文件。调用方先把用着它的引擎卸掉。 */
    suspend fun remove(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancelAndJoin()
        kotlinx.coroutines.withContext(Dispatchers.IO) { dir(spec).deleteRecursively() }
        publish(spec, ModelState(ModelPhase.Absent, 0, spec.downloadBytes))
    }

    private fun publish(spec: ModelSpec, state: ModelState) = _states.update { it + (spec.id to state) }

    /** 看磁盘上有什么,得出没在下载时的样子。 */
    private fun scan(spec: ModelSpec): ModelState {
        if (isReady(spec)) return ModelState(ModelPhase.Ready, spec.downloadBytes, spec.downloadBytes)
        val have = bytesOnDisk(spec)
        return ModelState(if (have > 0) ModelPhase.Paused else ModelPhase.Absent, have, spec.downloadBytes)
    }

    /** 已经下好(或下了一部分)的字节数。 */
    private fun bytesOnDisk(spec: ModelSpec): Long {
        val dir = dir(spec)
        val partial = File(dir, PARTIAL)
        return spec.files.sumOf { f ->
            when {
                !f.unpack && File(dir, f.name).let { it.isFile && it.length() == f.bytes } -> f.bytes
                f.unpack && File(partial, f.name + DONE).isFile -> f.bytes
                else -> File(partial, f.name + PART).let { if (it.isFile) minOf(it.length(), f.bytes) else 0L }
            }
        }
    }

    private suspend fun fetchAll(spec: ModelSpec) {
        val dir = dir(spec)
        val partial = File(dir, PARTIAL)
        if (!partial.isDirectory && !partial.mkdirs()) throw Problem(ModelProblem.Storage)
        File(dir, READY).delete()
        val total = spec.downloadBytes
        var done = bytesOnDisk(spec)
        // 解包的还要再放一份解开的(大小按压缩包的三倍估),再留 50 MB 余量
        val need = total - done + spec.files.filter { it.unpack }.sumOf { it.bytes * 3 } + 50L * 1024 * 1024
        if (root.usableSpace in 1 until need) throw Problem(ModelProblem.Storage)
        var lastPublished = 0L
        fun progress(delta: Long) {
            done += delta
            if (delta < 0 || done - lastPublished >= PUBLISH_EVERY) {
                lastPublished = done
                publish(spec, ModelState(ModelPhase.Downloading, done, total))
            }
        }
        for (f in spec.files) {
            val target = File(dir, f.name)
            if (!f.unpack && target.isFile && target.length() == f.bytes) continue
            val doneMarker = File(partial, f.name + DONE)
            if (f.unpack && doneMarker.isFile) continue
            val part = File(partial, f.name + PART)
            fetch(f, part, ::progress)
            publish(spec, ModelState(ModelPhase.Verifying, done, total))
            if (sha256(part) != f.sha256) {
                part.delete()
                throw Problem(ModelProblem.Checksum)
            }
            if (f.unpack) {
                publish(spec, ModelState(ModelPhase.Unpacking, done, total))
                try { unpackTarBz2(part, dir) } catch (e: IOException) { part.delete(); throw Problem(ModelProblem.Other) }
                part.delete()
                doneMarker.createNewFile()
            } else {
                target.delete()
                if (!part.renameTo(target)) throw Problem(ModelProblem.Storage)
            }
            publish(spec, ModelState(ModelPhase.Downloading, done, total))
        }
        partial.deleteRecursively()
        File(dir, READY).writeText(spec.fingerprint)
    }

    /** 把 [f] 下载(或接着下载)到 [part];下满清单里的大小才算完。 */
    private suspend fun fetch(f: ModelFile, part: File, progress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > f.bytes) { part.delete(); progress(-have); have = 0 }
        if (have == f.bytes) return
        val request = Request.Builder().url(resolve(f.url))
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = http.newCall(request)
        val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                val append = when {
                    have > 0 && resp.code == 206 && resp.header("Content-Range")?.startsWith("bytes $have-") == true -> true
                    resp.code == 200 -> false
                    // 断点比服务器上的文件还大,或服务器回了对不上的区间:删掉从头来,下次再点
                    resp.code == 416 || resp.code == 206 -> { part.delete(); progress(-have); throw Problem(ModelProblem.Network) }
                    resp.code in 500..599 || resp.code == 408 || resp.code == 429 -> throw Problem(ModelProblem.Network)
                    else -> throw Problem(ModelProblem.Other)
                }
                if (!append && have > 0) { progress(-have); have = 0 }
                val body = resp.body ?: throw Problem(ModelProblem.Network)
                var sinceWifiCheck = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (have + n > f.bytes) { out.close(); part.delete(); progress(-have); throw Problem(ModelProblem.Checksum) }
                            out.write(buf, 0, n)
                            have += n
                            progress(n.toLong())
                            sinceWifiCheck += n
                            if (sinceWifiCheck >= WIFI_CHECK_EVERY) {
                                sinceWifiCheck = 0
                                if (wifiOnly() && !onWifi()) throw Problem(ModelProblem.NeedsWifi)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw if (e.message?.contains("No space", ignoreCase = true) == true) Problem(ModelProblem.Storage) else Problem(ModelProblem.Network)
        } finally {
            cancelHook?.dispose()
        }
        if (have != f.bytes) throw Problem(ModelProblem.Network)
    }

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 下载不成的原因;只在这个类里抛、接住。 */
    private class Problem(val problem: ModelProblem) : Exception(problem.name)

    companion object {
        const val READY = ".ready"
        const val PARTIAL = ".partial"
        private const val PART = ".part"
        private const val DONE = ".done"
        private const val PUBLISH_EVERY = 512L * 1024
        private const val WIFI_CHECK_EVERY = 4L * 1024 * 1024

        /**
         * 把 .tar.bz2 解进 [into]:去掉包里最外一层目录(sherpa-onnx 的模型包都套着一层),
         * 只解普通文件和目录,跳过链接;名字带 `..`、绝对路径或解到 [into] 外面的直接报错。
         */
        fun unpackTarBz2(archive: File, into: File) {
            val base = into.canonicalFile
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.name.startsWith("/") || entry.name.contains('\\')) throw IOException("路径不对: ${entry.name}")
                    val parts = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
                    if (parts.any { it == ".." }) throw IOException("路径越界: ${entry.name}")
                    val rel = if (parts.size > 1) parts.drop(1) else if (entry.isDirectory) emptyList() else parts
                    if (rel.isEmpty()) continue
                    val out = File(base, rel.joinToString(File.separator)).canonicalFile
                    if (!out.path.startsWith(base.path + File.separator)) throw IOException("路径越界: ${entry.name}")
                    when {
                        entry.isDirectory -> out.mkdirs()
                        entry.isFile -> {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.copyTo(it) }
                        }
                        else -> Unit // 链接、设备文件之类不解
                    }
                }
            }
        }
    }
}
