package com.wangzan101.mika.voice

import android.content.Context
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.settings.VoiceConfig
import com.wangzan101.mika.settings.VoiceSettings

/**
 * 语音引擎的登记处:按设置里的选择给出这一次听写用哪个引擎;选的那个用不了(没填 Key、模型还没接上、
 * 手机没有识别服务)时退到能用的,并说明为什么(界面据此提示一句)。选哪个的规则是纯逻辑 [VoicePick]。
 */
class VoiceEngines(private val context: Context, private val settings: VoiceSettings) {
    /** 这一次听写:[dictation] 为空表示一个能用的都没有;[notice] 不为空时界面要说一句为什么没用上选的那个。 */
    class Choice(val dictation: Dictation?, val notice: VoicePick.Notice?)

    fun dictation(): Choice {
        val pick = VoicePick.recognizer(settings.config.value, PhoneDictation.available(context), IMPLEMENTED)
        return Choice(pick.engine?.let(::create), pick.notice)
    }

    private fun create(id: VoiceEngineId): Dictation? = when (id) {
        VoiceEngineId.PhoneRecognizer -> PhoneDictation(context)
        else -> null
    }

    companion object {
        /** 这一版已经接好的识别引擎;本机模型(Parakeet v3)接上运行时后加进来。 */
        val IMPLEMENTED: Set<VoiceEngineId> = setOf(VoiceEngineId.PhoneRecognizer)
    }
}

/** 按设置挑识别引擎,不碰安卓,好单测。 */
object VoicePick {
    enum class Notice {
        /** 选了 ElevenLabs Scribe 但没填 Key。 */
        NoKey,
        /** 选的本机模型还不能用。 */
        NotReady,
        /** 手机没有识别服务。 */
        PhoneUnavailable,
    }

    class Pick(val engine: VoiceEngineId?, val notice: Notice?)

    fun recognizer(config: VoiceConfig, phoneAvailable: Boolean, implemented: Set<VoiceEngineId>): Pick {
        fun usable(id: VoiceEngineId) = id in implemented &&
            (id != VoiceEngineId.PhoneRecognizer || phoneAvailable) && (!id.needsKey || config.hasElevenLabsKey)
        val wanted = config.speech.id
        if (usable(wanted)) return Pick(wanted, null)
        val notice = when {
            wanted.needsKey && !config.hasElevenLabsKey -> Notice.NoKey
            wanted == VoiceEngineId.PhoneRecognizer && wanted in implemented -> Notice.PhoneUnavailable
            else -> Notice.NotReady
        }
        // 退的顺序:先手机自带(不花钱、不用下载),再云端
        val fallback = listOf(VoiceEngineId.PhoneRecognizer, VoiceEngineId.ElevenLabsScribe).firstOrNull { it != wanted && usable(it) }
        return Pick(fallback, notice)
    }
}
