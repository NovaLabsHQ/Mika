package com.wangzan101.mika.voice

import android.content.Context
import android.content.res.Resources
import com.wangzan101.mika.core.voice.ReplyReader
import com.wangzan101.mika.core.voice.SpeechLanguage
import com.wangzan101.mika.core.voice.SpeechSynthesizer
import com.wangzan101.mika.core.voice.VoiceError
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.CancellationException

/**
 * 把回答读出来:用 [VoiceEngines.synthesizer] 给的引擎(现在只有本机的 Supertonic 3),经 [VoicePlayer] 放。
 * 读哪种语言按句子自己的文字定([SpeechLanguage]),拉丁字母的句子再看界面语言/手机语言。
 */
class ReplySpeaker(private val context: Context, private val engines: VoiceEngines, val player: VoicePlayer) {
    sealed interface Outcome {
        /** 读完了(或读了能读的那部分);[skipped] 句因为语言不支持没读。 */
        data class Spoke(val skipped: Int) : Outcome
        /** 一句都不能读(比如整段是中文)。 */
        data object Unsupported : Outcome
        /** 朗读模型还没下载、或这台手机跑不了。 */
        data object NotReady : Outcome
        /** 拿不到声音(正在通话之类),或读到一半被别的声音打断。 */
        data object Interrupted : Outcome
        /** 用户让它别读了([stop])。 */
        data object Stopped : Outcome
        data class Failed(val error: VoiceError) : Outcome
    }

    /** 读 [markdown];协程被取消时照常抛 CancellationException。 */
    suspend fun speak(markdown: String, synth: SpeechSynthesizer? = engines.synthesizer()): Outcome {
        synth ?: return Outcome.NotReady
        val preferred = preferredLanguage()
        val reader = ReplyReader(synth) { SpeechLanguage.pick(it, preferred) }
        return try {
            val r = player.play { sink -> reader.read(markdown, sink) }
                ?: return if (player.interrupted) Outcome.Interrupted else Outcome.Stopped
            if (r.spoken == 0) Outcome.Unsupported else Outcome.Spoke(r.skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceError) {
            Outcome.Failed(e)
        } catch (e: Exception) {
            Outcome.Failed(VoiceError.Unavailable(e.javaClass.simpleName))
        }
    }

    fun stop() = player.stop()

    /** 界面明确选了英文就按英文;否则看手机的语言(界面只有中英文,法语手机上的法语回答要按法语读)。 */
    private fun preferredLanguage(): String? =
        if (AppLanguage.current(context) == AppLanguage.English) "en"
        else Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
}
