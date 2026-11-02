package com.wangzan101.mika.settings

import com.wangzan101.mika.core.voice.ElevenLabsTts
import com.wangzan101.mika.core.voice.VoiceCatalog
import com.wangzan101.mika.core.voice.VoiceEngineId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 识别用哪个引擎。 */
enum class SpeechEngine(val id: VoiceEngineId) {
    PhoneRecognizer(VoiceEngineId.PhoneRecognizer),
    Parakeet3(VoiceEngineId.Parakeet3),
    ElevenLabsScribe(VoiceEngineId.ElevenLabsScribe),
}

/** 朗读回答用哪个引擎。 */
enum class ReplyEngine(val id: VoiceEngineId) {
    Supertonic3(VoiceEngineId.Supertonic3),
    ElevenLabs(VoiceEngineId.ElevenLabs),
}

/**
 * 语音设置。[hasElevenLabsKey] 只说有没有存 Key,Key 本身不进状态流,用时由 [VoiceSettings.elevenLabsKey] 现解密。
 * 默认识别用手机自带的,朗读用本机的 Supertonic 3(模型要先下载;没下载时不出声,不会自动花钱)。
 * 朗读只接了 Supertonic 3:[ReplyEngine.ElevenLabs] 留着是为了读得懂旧的设置文件,界面不再给这个选项。
 */
data class VoiceConfig(
    val speech: SpeechEngine = SpeechEngine.PhoneRecognizer,
    val reply: ReplyEngine = ReplyEngine.Supertonic3,
    /** Supertonic 3 的音色(sid 0..9,见 VoiceCatalog.supertonic3.voices)。 */
    val supertonicVoice: Int = 0,
    /** ElevenLabs 朗读用的音色 id。 */
    val voiceId: String = ElevenLabsTts.DEFAULT_VOICE,
    /** 本机模型只在 Wi-Fi 下下载。 */
    val wifiOnly: Boolean = true,
    val hasElevenLabsKey: Boolean = false,
    /** 不在语音对话里时,对话页开着、有了新回答也读出来。默认关;语音对话里总是读。 */
    val speakReplies: Boolean = false,
)

/**
 * 语音设置存两份:选择(voice.json,明文,不含秘密)和 ElevenLabs 的 Key(voice-keys.bin,用 [KeyCipher] 加密)。
 * 都是先写临时文件再原子替换;读不出来的按默认值,Key 读不出来的按没填。
 */
class VoiceSettings(private val dir: File, private val cipher: KeyCipher) {
    private val prefsFile = File(dir, "voice.json")
    private val keyFile = File(dir, "voice-keys.bin")
    private val mutable = MutableStateFlow(load())
    val config: StateFlow<VoiceConfig> = mutable.asStateFlow()

    @Synchronized fun setSpeech(engine: SpeechEngine) = savePrefs(config.value.copy(speech = engine))
    @Synchronized fun setReply(engine: ReplyEngine) = savePrefs(config.value.copy(reply = engine))
    @Synchronized fun setSupertonicVoice(sid: Int) {
        require(sid in SUPERTONIC_VOICES) { "Bad voice" }
        savePrefs(config.value.copy(supertonicVoice = sid))
    }
    @Synchronized fun setWifiOnly(on: Boolean) = savePrefs(config.value.copy(wifiOnly = on))
    @Synchronized fun setSpeakReplies(on: Boolean) = savePrefs(config.value.copy(speakReplies = on))
    @Synchronized fun setVoiceId(id: String) {
        require(VOICE_ID.matches(id)) { "Bad voice id" }
        savePrefs(config.value.copy(voiceId = id))
    }

    /** 存 ElevenLabs 的 Key(去掉首尾空白);空串等于清除。 */
    @Synchronized fun setElevenLabsKey(key: String) {
        val k = key.trim()
        if (k.isEmpty()) { clearElevenLabsKey(); return }
        require(k.length <= 200 && k.none { it.isWhitespace() || it.code < 0x20 }) { "Bad key" }
        val bytes = buildJsonObject { put("elevenlabs", k) }.toString().toByteArray(Charsets.UTF_8)
        try { atomicWrite(keyFile, cipher.encrypt(bytes)) } finally { bytes.fill(0) }
        mutable.value = config.value.copy(hasElevenLabsKey = true)
    }

    @Synchronized fun clearElevenLabsKey() {
        keyFile.delete()
        mutable.value = config.value.copy(hasElevenLabsKey = false)
    }

    /** 现解密的 Key;没填或解不开(换机、密钥被清)时为空。 */
    @Synchronized fun elevenLabsKey(): String? = runCatching {
        if (!keyFile.isFile) return null
        val bytes = cipher.decrypt(keyFile.readBytes())
        try { ((Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject)["elevenlabs"] as? JsonPrimitive)?.content }
        finally { bytes.fill(0) }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun load(): VoiceConfig {
        val o = runCatching { Json.parseToJsonElement(prefsFile.readText()) as JsonObject }.getOrNull()
        fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.content
        val d = VoiceConfig()
        return VoiceConfig(
            speech = SpeechEngine.entries.firstOrNull { it.name == str("speech") } ?: d.speech,
            reply = ReplyEngine.entries.firstOrNull { it.name == str("reply") } ?: d.reply,
            voiceId = str("voiceId")?.takeIf { VOICE_ID.matches(it) } ?: d.voiceId,
            supertonicVoice = str("supertonicVoice")?.toIntOrNull()?.takeIf { it in SUPERTONIC_VOICES } ?: d.supertonicVoice,
            wifiOnly = (o?.get("wifiOnly") as? JsonPrimitive)?.booleanOrNull ?: d.wifiOnly,
            hasElevenLabsKey = keyFile.isFile,
            speakReplies = (o?.get("speakReplies") as? JsonPrimitive)?.booleanOrNull ?: d.speakReplies,
        )
    }

    private fun savePrefs(c: VoiceConfig) {
        val json = buildJsonObject {
            put("speech", c.speech.name); put("reply", c.reply.name); put("voiceId", c.voiceId)
            put("supertonicVoice", c.supertonicVoice); put("wifiOnly", c.wifiOnly)
            put("speakReplies", c.speakReplies)
        }.toString()
        atomicWrite(prefsFile, json.toByteArray(Charsets.UTF_8))
        mutable.value = c
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { tmp.delete() }
    }

    private companion object {
        /** ElevenLabs 的音色 id 是一串字母数字;只收这种,免得拼进网址出事。 */
        val VOICE_ID = Regex("[A-Za-z0-9]{1,64}")
        val SUPERTONIC_VOICES = VoiceCatalog.supertonic3.voices.map { it.speakerId }.toSet()
    }
}
