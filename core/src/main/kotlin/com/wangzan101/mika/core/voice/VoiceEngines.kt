package com.wangzan101.mika.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * 语音的引擎接口:识别(说话 → 文字)和朗读(文字 → 声音)。核心只认这两个接口,
 * 具体引擎(手机自带的识别、本机模型、ElevenLabs)由 App 按设置挑一个装上;主循环、任务和对话都不知道有语音这回事,
 * 识别出的字和用户打的字走同一条路(进输入框、发出去)。
 */
interface SpeechRecognizer {
    val id: VoiceEngineId

    /**
     * 把一段录好的话识别成文字。[pcm16k] 是 16 kHz、单声道、16 位的采样;[language] 是 ISO-639-1 语言代码,
     * 为空时由引擎自己判断(能判断的话)。取消协程会撤掉进行中的请求。失败抛 [VoiceError]。
     */
    suspend fun transcribe(pcm16k: ShortArray, language: String? = null): Transcript
}

/** 识别结果:[text] 去掉了首尾空白,什么也没听出来时是空串;[language] 是引擎判断出的语言,不知道时为空。 */
data class Transcript(val text: String, val language: String? = null)

interface SpeechSynthesizer {
    val id: VoiceEngineId

    /**
     * 把一段话读出来,声音按块陆续交付(先到先播,不必等整段合成完)。[language] 为空时由引擎自己判断。
     * 停止收集就撤掉请求;失败抛 [VoiceError]。传进来的应当是一句可以读的话(见 [SpeakableText]、[SentenceSplitter])。
     */
    fun synthesize(text: String, language: String? = null): Flow<PcmChunk>
}

/** 一块 16 位单声道 PCM;[sampleRate] 是这块的采样率(同一个引擎每块一样)。 */
class PcmChunk(val samples: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/** 用户能选的语音引擎。[kind] 是识别还是朗读;[onDevice] 为真的在手机上跑;[needsKey] 为真的要先填服务商的 Key。 */
enum class VoiceEngineId(val kind: Kind, val onDevice: Boolean, val needsKey: Boolean) {
    /** 安卓系统自带的语音识别(android.speech):不用下载,一次只认一种语言。 */
    PhoneRecognizer(Kind.Recognition, onDevice = true, needsKey = false),
    /** NVIDIA Parakeet TDT 0.6B v3:在手机上跑,自己判断说的是哪种语言(25 种欧洲语言)。要先下载模型。 */
    Parakeet3(Kind.Recognition, onDevice = true, needsKey = false),
    /** ElevenLabs Scribe:把录音发到 ElevenLabs 识别,要填 Key。 */
    ElevenLabsScribe(Kind.Recognition, onDevice = false, needsKey = true),
    /** Supertonic 3:在手机上合成。要先下载模型。 */
    Supertonic3(Kind.Synthesis, onDevice = true, needsKey = false),
    /** ElevenLabs 的语音合成:把文字发到 ElevenLabs,要填 Key。 */
    ElevenLabs(Kind.Synthesis, onDevice = false, needsKey = true);

    enum class Kind { Recognition, Synthesis }

    /** 要先下载模型才能用的(本机模型);手机自带的识别不用下载。 */
    val needsModel: Boolean get() = onDevice && this != PhoneRecognizer

    companion object {
        val recognizers: List<VoiceEngineId> get() = entries.filter { it.kind == Kind.Recognition }
        val synthesizers: List<VoiceEngineId> get() = entries.filter { it.kind == Kind.Synthesis }
    }
}

/** 语音引擎的错误;界面按种类给用户一句话。 */
sealed class VoiceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 选的引擎要 Key,还没填。 */
    class NoKey : VoiceError("没有填写语音服务的 API Key")
    class Auth : VoiceError("语音服务的 API Key 无效或没有权限")
    class RateLimited(val retryAfterMs: Long?) : VoiceError("语音服务请求太频繁,请稍后再试")
    class QuotaExceeded : VoiceError("语音服务的额度已用完")
    class Network(cause: Throwable? = null) : VoiceError("网络连接失败", cause)
    class Server(val status: Int, val detail: String = "") : VoiceError("语音服务出错(HTTP $status)")
    class BadResponse(detail: String = "") : VoiceError(if (detail.isEmpty()) "语音服务返回了无法解析的内容" else "语音服务返回了无法解析的内容: $detail")
    /** 本机的引擎还不能用(模型没下载、系统没有识别服务、没给麦克风权限)。 */
    class Unavailable(detail: String) : VoiceError(detail)
}
