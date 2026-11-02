package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.core.voice.VoiceError

/** 听写此刻在哪一步:没在听、在听、录完了在变成字。 */
enum class DictationPhase { Idle, Listening, Transcribing }

/**
 * 对话页的一次听写。[start] 后由引擎自己判断说完(停顿),用户也可以再点一下话筒 [finish] 提前收尾;
 * [cancel] 丢掉这次,不再回调。回调都在主线程上,一次听写最后只回一次 [Listener.onResult] 或 [Listener.onError]。
 */
interface Dictation {
    val engine: VoiceEngineId
    fun start(listener: Listener)
    fun finish()
    fun cancel()

    interface Listener {
        fun onPhase(phase: DictationPhase)
        /** 边说边出的半截字,只用来显示;最后以 [onResult] 为准。 */
        fun onPartial(text: String) {}
        /** 听到的字;什么都没听清时是空串。 */
        fun onResult(text: String)
        fun onError(error: VoiceError)
    }
}

/** 听写出来的字怎么接到输入框里已有的字后面。 */
object DictationText {
    /** 两边有一边是中日韩文字时直接接上,不然隔一个空格;输入框是空的就只放听到的。 */
    fun append(existing: String, heard: String): String {
        val h = heard.trim()
        if (h.isEmpty()) return existing
        val e = existing.trimEnd()
        if (e.isEmpty()) return h
        val glue = if (cjk(e.last()) || cjk(h.first())) "" else " "
        return e + glue + h
    }

    private fun cjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    ) || c in "，。！？、；：（）「」『』《》"
}
