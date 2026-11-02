package com.wangzan101.mika.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.wangzan101.mika.core.voice.FloatPcm
import com.wangzan101.mika.core.voice.PcmChunk
import com.wangzan101.mika.core.voice.SpeechSynthesizer
import com.wangzan101.mika.core.voice.VoiceCatalog
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.core.voice.VoiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Supertonic 3 的 sherpa-onnx 配置,单独拿出来好在 JVM 上单测(只建配置对象,不碰 JNI)。 */
object SupertonicConfig {
    /** 去噪的步数:上游默认 5,步数越多越慢、音质略好。 */
    const val STEPS = 5
    /** 上游默认的语速。 */
    const val SPEED = 1.05f

    fun tts(dir: File, threads: Int): OfflineTtsConfig = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = File(dir, VoiceCatalog.Supertonic.DURATION_PREDICTOR).path,
                textEncoder = File(dir, VoiceCatalog.Supertonic.TEXT_ENCODER).path,
                vectorEstimator = File(dir, VoiceCatalog.Supertonic.VECTOR_ESTIMATOR).path,
                vocoder = File(dir, VoiceCatalog.Supertonic.VOCODER).path,
                ttsJson = File(dir, VoiceCatalog.Supertonic.TTS_JSON).path,
                unicodeIndexer = File(dir, VoiceCatalog.Supertonic.UNICODE_INDEXER).path,
                voiceStyle = File(dir, VoiceCatalog.Supertonic.VOICES).path,
            ),
            numThreads = threads,
            debug = false,
            provider = "cpu",
        ),
        // 一次只给它一句(切句在 ReplyReader 里做),它自己不再并句
        maxNumSentences = 1,
    )

    /** 一句话的合成参数:[sid] 选音色,语言经 extra["lang"] 交给引擎(Supertonic 只认这个)。 */
    fun generation(sid: Int, lang: String): GenerationConfig = GenerationConfig(
        speed = SPEED,
        sid = sid,
        numSteps = STEPS,
        extra = mapOf("lang" to lang),
    )

    /** 合成用几个线程:最多 4 个,留核给界面和放音。 */
    fun threads(cpus: Int = Runtime.getRuntime().availableProcessors()): Int = cpus.coerceIn(1, 4)
}

/**
 * Supertonic 3 在内存里的那一份:第一次读时从 [dir] 装进来,闲 [idleMs] 毫秒后卸掉;[unload] 立刻卸(删模型前要先卸)。
 * 装载、合成、卸载由一把锁排队。只有 arm64-v8a 有 native 库([ParakeetRuntime.supported])。
 */
class SupertonicRuntime(
    private val dir: () -> File,
    private val idleMs: Long = 2 * 60_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var idleJob: Job? = null

    /** 在后台先装好(进入语音对话、按「试听」时调;装不上等合成时再报错)。 */
    fun preload() {
        idleJob?.cancel()
        scope.launch { runCatching { lock.withLock { loaded() } } }
    }

    /**
     * 合成一句,合成好的一段段交给 [onSamples]([-1,1] 浮点、采样率);它返回 false 表示不要了。
     * Supertonic 在 sherpa-onnx 里一句只回调一次、也不看回调的返回值,所以停只能停在句子之间。
     */
    suspend fun generate(text: String, sid: Int, lang: String, onSamples: (FloatArray, Int) -> Boolean) = withContext(Dispatchers.Default) {
        lock.withLock {
            idleJob?.cancel()
            try {
                coroutineContext.ensureActive()
                val t = loaded()
                val rate = t.sampleRate()
                val voice = sid.coerceIn(0, (t.numSpeakers() - 1).coerceAtLeast(0))
                t.generateWithConfigAndCallback(text, SupertonicConfig.generation(voice, lang)) { s ->
                    if (onSamples(s, rate)) 1 else 0
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    suspend fun unload() = withContext(NonCancellable) {
        lock.withLock {
            idleJob?.cancel()
            tts?.release(); tts = null
        }
    }

    private fun loaded(): OfflineTts {
        tts?.let { return it }
        if (!ParakeetRuntime.supported) throw VoiceError.Unavailable("native")
        val d = dir()
        // 文件不全时 sherpa-onnx 可能直接让进程退出,先自己查
        if (VoiceCatalog.Supertonic.REQUIRED.any { !File(d, it).isFile }) throw VoiceError.Unavailable("model")
        return try {
            OfflineTts(null, SupertonicConfig.tts(d, SupertonicConfig.threads())).also { tts = it }
        } catch (e: UnsatisfiedLinkError) {
            throw VoiceError.Unavailable("native")
        }
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMs)
            lock.withLock { tts?.release(); tts = null }
        }
    }
}

/**
 * 用本机 Supertonic 3 读一句话:声音以 16 位 PCM 块交付(44.1 kHz,以模型 tts.json 里写的为准)。
 * [voice] 每次读时现取(设置里换了音色马上生效)。[language] 为空时按英文读。
 */
class SherpaSupertonicSynthesizer(
    private val runtime: SupertonicRuntime,
    private val voice: () -> Int,
) : SpeechSynthesizer {
    override val id: VoiceEngineId get() = VoiceEngineId.Supertonic3

    override fun synthesize(text: String, language: String?): Flow<PcmChunk> = channelFlow {
        runtime.generate(text, voice(), language ?: "en") { samples, rate ->
            // 收的那边不要了(取消、播放停了)就别再塞
            trySendBlocking(PcmChunk(FloatPcm.toPcm16(samples), rate)).isSuccess
        }
    }.buffer(4).flowOn(Dispatchers.Default)
}
