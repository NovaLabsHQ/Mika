package com.wangzan101.mika.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.core.voice.VoiceError

/**
 * 用手机自带的识别服务听写(系统的 [SpeechRecognizer],一次只认一种语言,默认跟系统语言)。
 * 录音、判断说完都由识别服务做,这里只转一下回调。必须在主线程上调用。
 */
class PhoneDictation(private val context: Context, private val language: () -> String? = { null }) : Dictation {
    override val engine = VoiceEngineId.PhoneRecognizer
    private var recognizer: SpeechRecognizer? = null
    private var listener: Dictation.Listener? = null

    override fun start(listener: Dictation.Listener) {
        cancel()
        if (!available(context)) { listener.onError(VoiceError.Unavailable("no recognition service")); return }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrElse {
            listener.onError(VoiceError.Unavailable(it.message ?: "create failed")); return
        }
        recognizer = r
        this.listener = listener
        r.setRecognitionListener(Callbacks())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        language()?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        listener.onPhase(DictationPhase.Listening)
        r.startListening(intent)
    }

    override fun finish() {
        recognizer?.stopListening()
        listener?.onPhase(DictationPhase.Transcribing)
    }

    override fun cancel() {
        listener = null
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
    }

    /** 回一次结果或错误就收摊,之后识别服务再来的回调都不理。 */
    private fun end(block: (Dictation.Listener) -> Unit) {
        val l = listener ?: return
        cancel()
        l.onPhase(DictationPhase.Idle)
        block(l)
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { listener?.onPhase(DictationPhase.Transcribing) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults)?.let { listener?.onPartial(it) }
        }
        override fun onResults(results: Bundle?) { val t = first(results).orEmpty(); end { it.onResult(t) } }
        override fun onError(error: Int) {
            when (error) {
                // 没听清、没说话:不算错,当作什么都没听到
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> end { it.onResult("") }
                else -> { val e = errorOf(error); end { it.onError(e) } }
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        internal fun errorOf(code: Int): VoiceError = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                VoiceError.Network(null)
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceError.RateLimited(null)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.Unavailable("permission")
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.Unavailable("language")
            else -> VoiceError.Unavailable("recognizer error $code")
        }
    }
}
