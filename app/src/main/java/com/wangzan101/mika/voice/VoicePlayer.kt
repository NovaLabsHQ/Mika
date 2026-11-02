package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.core.voice.PcmChunk
import com.wangzan101.mika.core.voice.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 把合成好的 16 位单声道 PCM 边到边放([AudioTrack] 流模式)。放的时候要一次「短暂、可压低别人」的音频焦点:
 * 音乐等压低音量让我们说完;来电、别的 App 抢走焦点时停下(由 [play] 的调用方决定接下来做什么)。
 * 同一时间只放一段:新的 [play] 先停掉旧的。[stop] 随时可调(界面线程也行)。
 */
class VoicePlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val _playing = MutableStateFlow(false)
    /** 此刻在出声(含合成第一句的那一下)。 */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var current: Job? = null
    @Volatile private var track: AudioTrack? = null
    private val lock = Any()

    /** 上一段没拿到声音、或被别的声音打断(焦点丢了)停下的;[play] 返回 null 时调用方据此区分「用户停的」。 */
    @Volatile var interrupted = false
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { interrupted = true; stop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.setVolume(DUCKED)
            AudioManager.AUDIOFOCUS_GAIN -> track?.setVolume(1f)
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    /**
     * 放一段:[block] 往给它的 [PcmSink] 里写。拿不到焦点(正在通话)、或被 [stop] / 焦点丢失停下时返回 null
     * (调用方的协程不受影响,接着往下走);调用方自己被取消时照常抛 CancellationException。
     */
    suspend fun <T> play(block: suspend (PcmSink) -> T): T? = coroutineScope {
        interrupted = false
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            interrupted = true
            return@coroutineScope null
        }
        val sink = TrackSink()
        val job = async { block(sink) }
        synchronized(lock) {
            current?.cancel()
            current = job
            _playing.value = true
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            // 自己被取消就接着抛;只是这一段被停下就当没放完
            ensureActive()
            null
        } finally {
            sink.release()
            audio.abandonAudioFocusRequest(focusRequest)
            synchronized(lock) { if (current === job) { current = null; _playing.value = false } }
        }
    }

    /** 停下正在放的(连同还没合成的句子);没在放就什么也不做。 */
    fun stop() {
        val j = synchronized(lock) { current }
        // 先让卡在 write 里的线程出来,再取消协程
        track?.let { runCatching { it.pause(); it.flush() } }
        j?.cancel()
    }

    /** 暂停 / 接着放(合成照常往后攒,攒满了就等)。 */
    fun pause() { track?.let { runCatching { it.pause() } } }
    fun resume() { track?.let { runCatching { it.play() } } }

    private inner class TrackSink : PcmSink {
        private var t: AudioTrack? = null
        private var rate = 0
        private var written = 0L

        override suspend fun write(chunk: PcmChunk) {
            currentCoroutineContext().ensureActive()
            if (chunk.samples.isEmpty()) return
            if (t == null || rate != chunk.sampleRate) open(chunk.sampleRate)
            val tr = t ?: return
            withContext(Dispatchers.IO) {
                var off = 0
                while (off < chunk.samples.size) {
                    ensureActive()
                    val n = tr.write(chunk.samples, off, chunk.samples.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) { if (tr.playState == AudioTrack.PLAYSTATE_PAUSED) delay(20) else break } else off += n
                }
            }
            written += chunk.samples.size
        }

        override suspend fun drain() {
            val tr = t ?: return
            // 放到写进去的最后一个采样;放不动了(被暂停太久之外)最多再等一会儿
            var stalled = 0
            var last = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val head = runCatching { tr.playbackHeadPosition }.getOrDefault(Int.MAX_VALUE)
                if (head.toLong() and 0xffffffffL >= written) break
                if (tr.playState == AudioTrack.PLAYSTATE_PLAYING) { if (head == last) stalled++ else stalled = 0 }
                if (stalled > 50) break
                last = head
                delay(20)
            }
        }

        private fun open(sampleRate: Int) {
            release()
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                // 半秒的缓冲:够平滑,停下时也不会多响太久
                .setBufferSizeInBytes(maxOf(min, sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            tr.play()
            t = tr; rate = sampleRate; written = 0
            track = tr
        }

        fun release() {
            t?.let { runCatching { it.pause(); it.flush(); it.release() } }
            if (track === t) track = null
            t = null
        }
    }

    private companion object {
        /** 别的声音要我们让一让时的音量。 */
        const val DUCKED = 0.3f
    }
}
