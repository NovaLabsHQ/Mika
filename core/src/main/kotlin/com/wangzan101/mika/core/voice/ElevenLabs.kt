package com.wangzan101.mika.core.voice

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ElevenLabs 的接口地址和共用的错误换算。文档:https://elevenlabs.io/docs/api-reference/speech-to-text/convert
 * 和 https://elevenlabs.io/docs/api-reference/text-to-speech/stream 。Key 放在 xi-api-key 头里。
 */
object ElevenLabsApi {
    const val BASE_URL = "https://api.elevenlabs.io"
    const val KEY_HEADER = "xi-api-key"

    internal fun errorOf(status: Int, retryAfter: String?, body: String): VoiceError {
        val detail = body.take(300)
        return when {
            status == 401 && body.contains("quota", ignoreCase = true) -> VoiceError.QuotaExceeded()
            status == 401 || status == 403 -> VoiceError.Auth()
            status == 429 -> VoiceError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 422 || status == 400 -> VoiceError.BadResponse(detail)
            else -> VoiceError.Server(status, detail)
        }
    }
}

/**
 * ElevenLabs Scribe 识别:整段录音一次发过去(multipart),拿回文字和判断出的语言。
 * 录音直接按 16 kHz 单声道 16 位小端 PCM 发(file_format=pcm_s16le_16),不用先编码成 WAV,延迟也更低。
 */
class ElevenLabsScribe(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: String = ElevenLabsApi.BASE_URL,
    private val modelId: String = MODEL,
) : SpeechRecognizer {
    override val id = VoiceEngineId.ElevenLabsScribe

    override suspend fun transcribe(pcm16k: ShortArray, language: String?): Transcript {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw VoiceError.NoKey()
        if (pcm16k.isEmpty()) return Transcript("")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", modelId)
            .addFormDataPart("file_format", "pcm_s16le_16")
            .addFormDataPart("tag_audio_events", "false")
            .apply { language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language_code", it) } }
            .addFormDataPart("file", "speech.pcm", Pcm16.toLittleEndian(pcm16k).toRequestBody(OCTET))
            .build()
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/speech-to-text")
            .header(ElevenLabsApi.KEY_HEADER, key)
            .post(form)
            .build()
        val raw = try { HttpSupport.execute(http, req) } catch (e: ModelError.Network) { throw VoiceError.Network(e.cause) }
        if (raw.status !in 200..299) throw ElevenLabsApi.errorOf(raw.status, raw.retryAfter, raw.body)
        val parsed = try { json.decodeFromString<ScribeResponse>(raw.body) } catch (e: Exception) { throw VoiceError.BadResponse(e.message.orEmpty()) }
        return Transcript(parsed.text.trim(), parsed.languageCode?.takeIf { it.isNotBlank() })
    }

    @Serializable
    private class ScribeResponse(
        val text: String = "",
        @kotlinx.serialization.SerialName("language_code") val languageCode: String? = null,
    )

    companion object {
        const val MODEL = "scribe_v2"
        private val OCTET = "application/octet-stream".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * ElevenLabs 合成:按句请求流式接口,要 24 kHz 的裸 PCM(output_format=pcm_24000),边收边交给播放。
 * [voiceId] 是 ElevenLabs 的音色编号;[modelId] 默认用延迟低、支持多语言和 language_code 的 Flash v2.5。
 */
class ElevenLabsTts(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val voiceId: () -> String = { DEFAULT_VOICE },
    private val baseUrl: String = ElevenLabsApi.BASE_URL,
    private val modelId: String = MODEL,
) : SpeechSynthesizer {
    override val id = VoiceEngineId.ElevenLabs

    override fun synthesize(text: String, language: String?): Flow<PcmChunk> = flow {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw VoiceError.NoKey()
        if (text.isBlank()) return@flow
        val body = buildJsonObject {
            put("text", text)
            put("model_id", modelId)
            language?.takeIf { it.isNotBlank() }?.let { put("language_code", it) }
        }.toString().toRequestBody(JSON)
        val voice = voiceId().ifBlank { DEFAULT_VOICE }
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/text-to-speech/$voice/stream?output_format=pcm_$SAMPLE_RATE")
            .header(ElevenLabsApi.KEY_HEADER, key)
            .header("Accept", "audio/pcm")
            .post(body)
            .build()
        val call = http.newCall(req)
        // 停止收集(用户打断朗读)时撤掉请求,阻塞着的读取随之结束
        val cancel = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val resp = try { call.execute() } catch (e: IOException) { throw VoiceError.Network(e) }
            resp.use { r ->
                if (r.code !in 200..299) throw ElevenLabsApi.errorOf(r.code, r.header("retry-after"), r.body?.string().orEmpty())
                val source = r.body?.source() ?: throw VoiceError.BadResponse()
                val decoder = Pcm16.Decoder()
                val buf = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = try { source.read(buf) } catch (e: IOException) { throw VoiceError.Network(e) }
                    if (n < 0) break
                    val samples = decoder.feed(buf, n)
                    if (samples.isNotEmpty()) emit(PcmChunk(samples, SAMPLE_RATE))
                }
            }
        } finally {
            cancel.dispose()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val MODEL = "eleven_flash_v2_5"
        const val SAMPLE_RATE = 24_000
        /** 文档示例里的音色(George);用户可以在设置里换成自己的。 */
        const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
        /** 一次读这么多字节交出去一块:24 kHz 下约 0.1 秒。 */
        private const val CHUNK_BYTES = 4800
        private val JSON = "application/json".toMediaType()
    }
}

/** 16 位小端 PCM 与采样数组的互转。 */
object Pcm16 {
    fun toLittleEndian(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = (v shr 8 and 0xff).toByte()
        }
        return out
    }

    /** 网络上分块到达的字节可能在一个采样中间断开:留住落单的那个字节,接到下一块前面。 */
    class Decoder {
        private var pending = -1

        fun feed(bytes: ByteArray, length: Int = bytes.size): ShortArray {
            var i = 0
            val total = length + if (pending >= 0) 1 else 0
            val out = ShortArray(total / 2)
            var o = 0
            if (pending >= 0 && length > 0) {
                out[o++] = ((bytes[0].toInt() shl 8) or pending).toShort()
                pending = -1
                i = 1
            }
            while (i + 1 < length) {
                out[o++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort()
                i += 2
            }
            if (i < length) pending = bytes[i].toInt() and 0xff
            return if (o == out.size) out else out.copyOf(o)
        }
    }
}
