package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.wangzan101.mika.core.voice.VoiceError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 用 AudioRecord 录一段 16 kHz、单声道、16 位的话,交给云端或本机识别。
 * 每 20 毫秒一帧问一次 [SpeechGate];门说停、[stop] 返回 true(用户点了「说完了」)或协程被取消时收尾。
 * 调用方要先确认有麦克风权限。
 */
class AudioCapture(private val sampleRate: Int = SAMPLE_RATE) {
    class Recording(val pcm: ShortArray, val heardSpeech: Boolean)

    @SuppressLint("MissingPermission")
    suspend fun record(gate: SpeechGate, stop: () -> Boolean): Recording = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw VoiceError.Unavailable("audio")
        val frame = ShortArray(sampleRate / 50)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame.size * 2 * 4))
        } catch (e: SecurityException) { throw VoiceError.Unavailable("permission") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw VoiceError.Unavailable("audio") }
        val out = PcmBuffer()
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw VoiceError.Unavailable("audio")
            while (true) {
                coroutineContext.ensureActive()
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) throw VoiceError.Unavailable("audio")
                out.add(frame, n)
                if (stop() || gate.offer(frame, n) != EndOfSpeechGate.Decision.Continue) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        Recording(out.toArray(), gate.heardSpeech)
    }

    /** 边录边长的采样缓冲。 */
    private class PcmBuffer {
        private var data = ShortArray(SAMPLE_RATE * 4)
        private var size = 0
        fun add(src: ShortArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(src, 0, data, size, n); size += n
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    companion object { const val SAMPLE_RATE = 16_000 }
}
