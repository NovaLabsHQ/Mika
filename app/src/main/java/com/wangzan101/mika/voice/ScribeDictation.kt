package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.SpeechRecognizer
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.core.voice.VoiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 先录后认的听写:[AudioCapture] 录到话说完(按音量判断,或用户点「说完了」),再整段交给一个
 * [SpeechRecognizer](现在是 ElevenLabs Scribe,它自己认语言)。回调都在主线程。
 */
class ScribeDictation(
    private val recognizer: SpeechRecognizer,
    private val capture: AudioCapture = AudioCapture(),
    private val gate: () -> SpeechGate = { EndOfSpeechGate() },
) : Dictation {
    override val engine: VoiceEngineId get() = recognizer.id
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var stopRequested = false

    override fun start(listener: Dictation.Listener) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch {
            try {
                listener.onPhase(DictationPhase.Listening)
                val rec = capture.record(gate()) { stopRequested }
                // 一直没开口(门说没人说话,或点「说完了」时录到的不到半秒)就不发出去,省一次请求
                if (!rec.heardSpeech && (!stopRequested || rec.pcm.size < AudioCapture.SAMPLE_RATE / 2)) {
                    listener.onResult(""); return@launch
                }
                listener.onPhase(DictationPhase.Transcribing)
                listener.onResult(recognizer.transcribe(rec.pcm).text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceError) {
                listener.onError(e)
            } catch (e: Exception) {
                listener.onError(VoiceError.Unavailable(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    override fun finish() { stopRequested = true }

    override fun cancel() {
        job?.cancel(); job = null
        scope.cancel()
    }
}
