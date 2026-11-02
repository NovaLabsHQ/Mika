package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.ModelFile
import com.wangzan101.mika.core.voice.ModelSpec
import com.wangzan101.mika.core.voice.VoiceEngineId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ModelDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val files = mutableMapOf<String, ByteArray>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var honourRange = true
    @Volatile private var throttle = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = files[request.path!!.removePrefix("/")] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
                val resp = if (range != null && honourRange) {
                    if (range >= body.size) return MockResponse().setResponseCode(416)
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $range-${body.size - 1}/${body.size}")
                        .setBody(Buffer().write(body, range, body.size - range))
                } else MockResponse().setBody(Buffer().write(body))
                if (throttle) resp.throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS)
                return resp
            }
        }
        server.start()
    }

    @After fun stop() { scope.cancel(); server.shutdown() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(name: String, bytes: ByteArray, sha: String = sha(bytes), unpack: Boolean = false): ModelFile {
        files[name] = bytes
        return ModelFile(name, "https://models.example/$name", sha, bytes.size.toLong(), unpack)
    }

    private fun spec(vararg f: ModelFile) = ModelSpec(VoiceEngineId.Parakeet3, "test-model", f.toList(), "MIT", "https://example/license")

    private fun downloads(spec: ModelSpec, wifiOnly: Boolean = false, onWifi: () -> Boolean = { true }) = ModelDownloads(
        tmp.root, http, { wifiOnly }, onWifi, listOf(spec), scope,
        resolve = { server.url("/" + it.substringAfterLast('/')) },
    )

    private fun ModelDownloads.await(spec: ModelSpec, p: (ModelState) -> Boolean): ModelState = runBlocking {
        withTimeout(20_000) { states.first { it[spec.id]?.let(p) == true }[spec.id]!! }
    }

    private fun ModelDownloads.settle(spec: ModelSpec) =
        await(spec) { it.phase == ModelPhase.Ready || it.phase == ModelPhase.Failed || it.phase == ModelPhase.Paused }

    @Test fun downloadsVerifiesAndMarksReady() {
        val a = Random(1).nextBytes(300_000)
        val b = "tokens".toByteArray()
        val s = spec(file("a.onnx", a), file("tokens.txt", b))
        val d = downloads(s)
        assertEquals(ModelPhase.Absent, d.state(s).phase)
        d.download(s)
        val st = d.settle(s)
        assertEquals(ModelPhase.Ready, st.phase)
        assertEquals(s.downloadBytes, st.bytes)
        assertArrayEquals(a, File(d.dir(s), "a.onnx").readBytes())
        assertArrayEquals(b, File(d.dir(s), "tokens.txt").readBytes())
        assertFalse(File(d.dir(s), ModelDownloads.PARTIAL).exists())
        assertTrue(d.isReady(s))
        // 重新打开也认得下好了
        assertEquals(ModelPhase.Ready, downloads(s).state(s).phase)
    }

    @Test fun resumesWithRange() {
        val a = Random(2).nextBytes(200_000)
        val s = spec(file("a.onnx", a))
        val part = File(tmp.root, "test-model/${ModelDownloads.PARTIAL}/a.onnx.part")
        part.parentFile!!.mkdirs(); part.writeBytes(a.copyOf(120_000))
        val d = downloads(s)
        assertEquals(ModelPhase.Paused, d.state(s).phase)
        assertEquals(120_000L, d.state(s).bytes)
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        assertEquals("bytes=120000-", requests.single().getHeader("Range"))
        assertArrayEquals(a, File(d.dir(s), "a.onnx").readBytes())
    }

    @Test fun restartsWhenServerIgnoresRange() {
        honourRange = false
        val a = Random(3).nextBytes(100_000)
        val s = spec(file("a.onnx", a))
        val part = File(tmp.root, "test-model/${ModelDownloads.PARTIAL}/a.onnx.part")
        part.parentFile!!.mkdirs(); part.writeBytes(a.copyOf(40_000))
        val d = downloads(s)
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        assertArrayEquals(a, File(d.dir(s), "a.onnx").readBytes())
    }

    @Test fun checksumMismatchDeletesAndFails() {
        val s = spec(file("a.onnx", Random(4).nextBytes(10_000), sha = "0".repeat(64)))
        val d = downloads(s)
        d.download(s)
        val st = d.settle(s)
        assertEquals(ModelPhase.Failed, st.phase)
        assertEquals(ModelProblem.Checksum, st.problem)
        assertEquals(0L, st.bytes)
        assertFalse(File(d.dir(s), "a.onnx").exists())
        assertFalse(d.isReady(s))
    }

    @Test fun cancelPausesAndKeepsWhatWasDownloaded() {
        throttle = true
        val a = Random(5).nextBytes(2_000_000)
        val s = spec(file("a.onnx", a))
        val d = downloads(s)
        d.download(s)
        d.await(s) { it.phase == ModelPhase.Downloading && it.bytes > 0 }
        d.cancel(s)
        val paused = d.await(s) { it.phase == ModelPhase.Paused }
        assertTrue(paused.bytes in 1 until a.size)
        throttle = false
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        assertArrayEquals(a, File(d.dir(s), "a.onnx").readBytes())
        assertTrue(requests.last().getHeader("Range")!!.startsWith("bytes="))
    }

    @Test fun wifiOnlyBlocksBeforeStarting() {
        val s = spec(file("a.onnx", ByteArray(10)))
        val d = downloads(s, wifiOnly = true, onWifi = { false })
        d.download(s)
        val st = d.state(s)
        assertEquals(ModelPhase.Failed, st.phase)
        assertEquals(ModelProblem.NeedsWifi, st.problem)
        assertTrue(requests.isEmpty())
    }

    @Test fun leavingWifiStopsTheDownload() {
        val a = Random(6).nextBytes(6 * 1024 * 1024)
        val s = spec(file("a.onnx", a))
        var calls = 0
        val d = downloads(s, wifiOnly = true, onWifi = { calls++ == 0 })
        d.download(s)
        val st = d.settle(s)
        assertEquals(ModelPhase.Failed, st.phase)
        assertEquals(ModelProblem.NeedsWifi, st.problem)
        assertTrue(st.bytes in 1 until a.size)
    }

    @Test fun removeDeletesEverything() {
        val s = spec(file("a.onnx", ByteArray(1000) { it.toByte() }))
        val d = downloads(s)
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        runBlocking { d.remove(s) }
        assertFalse(d.dir(s).exists())
        assertEquals(ModelPhase.Absent, d.state(s).phase)
    }

    @Test fun changedSpecIsNotReady() {
        val bytes = ByteArray(1000) { it.toByte() }
        val s = spec(file("a.onnx", bytes))
        val d = downloads(s)
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        val other = spec(file("a.onnx", bytes), file("b.onnx", ByteArray(5)))
        assertFalse(downloads(other).isReady(other))
    }

    @Test fun unpacksTarBz2StrippingTheTopDirectory() {
        val archive = tarBz2("pkg/" to null, "pkg/model.onnx" to "model".toByteArray(), "pkg/espeak/voices.txt" to "v".toByteArray())
        val s = spec(file("pkg.tar.bz2", archive, unpack = true))
        val d = downloads(s)
        d.download(s)
        assertEquals(ModelPhase.Ready, d.settle(s).phase)
        assertEquals("model", File(d.dir(s), "model.onnx").readText())
        assertEquals("v", File(d.dir(s), "espeak/voices.txt").readText())
        assertFalse(File(d.dir(s), "pkg.tar.bz2").exists())
        assertTrue(d.isReady(s))
    }

    @Test fun rejectsPathTraversalInArchives() {
        val archive = tarBz2("pkg/../../evil.txt" to "x".toByteArray())
        val s = spec(file("pkg.tar.bz2", archive, unpack = true))
        val d = downloads(s)
        d.download(s)
        val st = d.settle(s)
        assertEquals(ModelPhase.Failed, st.phase)
        assertEquals(ModelProblem.Other, st.problem)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test fun missingFileOnServerFails() {
        val s = spec(ModelFile("gone.onnx", "https://models.example/gone.onnx", "a".repeat(64), 10))
        val d = downloads(s)
        d.download(s)
        val st = d.settle(s)
        assertEquals(ModelPhase.Failed, st.phase)
        assertEquals(ModelProblem.Other, st.problem)
        assertNull(files["gone.onnx"])
    }

    private fun tarBz2(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, data) in entries) {
                val e = TarArchiveEntry(name, true)
                if (data != null) e.size = data.size.toLong()
                tar.putArchiveEntry(e)
                if (data != null) tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
