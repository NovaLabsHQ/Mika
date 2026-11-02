package com.wangzan101.mika.voice

import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.wangzan101.mika.core.voice.SpeechRecognizer
import com.wangzan101.mika.core.voice.Transcript
import com.wangzan101.mika.core.voice.VoiceCatalog
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.core.voice.VoiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本机的 Parakeet v3 听写:[AudioCapture] 录 16 kHz 的话,Silero VAD 判断说完(VAD 起不来时退回按音量判断),
 * 再整段交给 sherpa-onnx 的 OfflineRecognizer(NeMo transducer)认成字。开始听的同时在后台把模型装进内存,
 * 说完时多半已经装好;闲着一阵后 [ParakeetRuntime] 自己卸掉。
 */
class SherpaParakeetDictation(
    private val runtime: ParakeetRuntime,
    capture: AudioCapture = AudioCapture(),
) : Dictation {
    private val inner = ScribeDictation(ParakeetRecognizer(runtime), capture, gate = { runtime.gate() })
    override val engine: VoiceEngineId get() = VoiceEngineId.Parakeet3

    override fun start(listener: Dictation.Listener) {
        runtime.preload()
        inner.start(listener)
    }

    override fun finish() = inner.finish()
    override fun cancel() = inner.cancel()
}

/** 把整段 16 kHz 采样交给 [ParakeetRuntime] 认成字。Parakeet v3 自己认语言,[language] 不用。 */
class ParakeetRecognizer(private val runtime: ParakeetRuntime) : SpeechRecognizer {
    override val id: VoiceEngineId get() = VoiceEngineId.Parakeet3
    override suspend fun transcribe(pcm16k: ShortArray, language: String?): Transcript =
        Transcript(runtime.transcribe(VadGate.toFloat(pcm16k)).trim())
}

/** sherpa-onnx 的配置,单独拿出来好在 JVM 上单测(只建配置对象,不碰 JNI)。 */
object ParakeetConfig {
    const val SAMPLE_RATE = 16_000

    fun recognizer(dir: File, threads: Int): OfflineRecognizerConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(dir, VoiceCatalog.Parakeet.ENCODER).path,
                decoder = File(dir, VoiceCatalog.Parakeet.DECODER).path,
                joiner = File(dir, VoiceCatalog.Parakeet.JOINER).path,
            ),
            tokens = File(dir, VoiceCatalog.Parakeet.TOKENS).path,
            numThreads = threads,
            provider = "cpu",
            debug = false,
            modelType = "nemo_transducer",
        ),
        decodingMethod = "greedy_search",
    )

    fun vad(dir: File): VadModelConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = File(dir, VoiceCatalog.Parakeet.VAD).path,
            threshold = 0.5f,
            minSilenceDuration = 0.25f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            // 只用它判断有没有在说话,不靠它切段;切段上限放宽,免得长句子中途被切开
            maxSpeechDuration = 30f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false,
    )

    /** 解码用几个线程:最多 4 个,留核给界面。 */
    fun threads(cpus: Int = Runtime.getRuntime().availableProcessors()): Int = cpus.coerceIn(1, 4)
}

/**
 * Parakeet v3 在内存里的那一份(约 0.7 GB):第一次用时从 [dir] 装进来,闲 [idleMs] 毫秒后卸掉;
 * [unload] 立刻卸(删模型前要先卸)。装载、识别、卸载由一把锁排队,不会在识别中途被卸掉。
 * sherpa-onnx 只打包了 arm64-v8a,别的 CPU 上 [supported] 为假。
 */
class ParakeetRuntime(
    private val dir: () -> File,
    private val idleMs: Long = 2 * 60_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var idleJob: Job? = null
    @Volatile private var vad: Vad? = null

    /** 在后台先装好模型(装不上时等识别时再报错)。 */
    fun preload() {
        idleJob?.cancel()
        scope.launch { runCatching { lock.withLock { loaded() } } }
    }

    /** 认一整段 [-1,1] 的 16 kHz 采样。 */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        lock.withLock {
            idleJob?.cancel()
            try {
                val rec = loaded()
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, ParakeetConfig.SAMPLE_RATE)
                    rec.decode(stream)
                    rec.getResult(stream).text
                } finally {
                    stream.release()
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    /**
     * 这一次听写的「说完没有」:用 Silero VAD 判断;VAD 起不来(没有 native 库、文件坏了)就退回按音量判断。
     */
    fun gate(): SpeechGate {
        if (!supported || !File(dir(), VoiceCatalog.Parakeet.VAD).isFile) return EndOfSpeechGate()
        val v = try { vad ?: Vad(null, ParakeetConfig.vad(dir())).also { vad = it } } catch (e: Throwable) { null }
            ?: return EndOfSpeechGate()
        v.reset()
        return VadGate({ frame ->
            // 模型被删掉、VAD 已释放时不再碰它
            if (vad !== v) return@VadGate false
            v.acceptWaveform(frame)
            // 只要「这会儿有没有人在说」,切出来的段不用,丢掉免得攒着
            while (!v.empty()) v.pop()
            v.isSpeechDetected()
        })
    }

    /** 立刻卸掉(删模型前调)。 */
    suspend fun unload() = withContext(NonCancellable) {
        lock.withLock {
            idleJob?.cancel()
            releaseAll()
        }
    }

    private fun loaded(): OfflineRecognizer {
        recognizer?.let { return it }
        if (!supported) throw VoiceError.Unavailable("native")
        val d = dir()
        val missing = listOf(VoiceCatalog.Parakeet.ENCODER, VoiceCatalog.Parakeet.DECODER, VoiceCatalog.Parakeet.JOINER,
            VoiceCatalog.Parakeet.TOKENS).any { !File(d, it).isFile }
        // 文件不全时 sherpa-onnx 可能直接让进程退出,先自己查
        if (missing) throw VoiceError.Unavailable("model")
        return try {
            OfflineRecognizer(null, ParakeetConfig.recognizer(d, ParakeetConfig.threads())).also { recognizer = it }
        } catch (e: UnsatisfiedLinkError) {
            throw VoiceError.Unavailable("native")
        }
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMs)
            // VAD 只有 2 MB 上下,闲着也留着;卸的是识别模型
            lock.withLock { recognizer?.release(); recognizer = null }
        }
    }

    private fun releaseAll() {
        recognizer?.release(); recognizer = null
        vad?.release(); vad = null
    }

    companion object {
        val supported: Boolean get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }
}
