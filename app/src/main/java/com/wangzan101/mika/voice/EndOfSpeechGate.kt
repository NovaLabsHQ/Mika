package com.wangzan101.mika.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 按音量判断话说完没有(不碰安卓,好单测)。一帧一帧喂进 16 kHz 的采样:
 * 先用开头几帧估出底噪,比底噪响一截、又过了最低门槛的帧算在说话;说过话以后连续安静 [silenceMs] 就算说完。
 * 一直没开口到 [noSpeechMs] 就放弃;不管说没说完,录满 [maxMs] 就停,免得忘了关。
 */
class EndOfSpeechGate(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 1_200,
    private val noSpeechMs: Int = 6_000,
    private val maxMs: Int = 60_000,
    /** 最低门槛(16 位采样的均方根);再安静的环境也得比这个响才算说话。 */
    private val minLevel: Double = 300.0,
    /** 比底噪响几倍算说话。 */
    private val overFloor: Double = 3.0,
) {
    enum class Decision { Continue, Ended, NoSpeech, TooLong }

    var heardSpeech = false
        private set
    private var elapsedSamples = 0L
    private var quietSamples = 0L
    private var floor = -1.0

    fun offer(frame: ShortArray, size: Int = frame.size): Decision {
        if (size <= 0) return Decision.Continue
        val level = rms(frame, size)
        elapsedSamples += size
        // 底噪:没开口前跟着慢慢走,只往安静的方向快些靠
        floor = when {
            floor < 0 -> level
            heardSpeech -> floor
            level < floor -> level
            else -> floor * 0.95 + level * 0.05
        }
        val speaking = level >= max(minLevel, floor * overFloor)
        if (speaking) { heardSpeech = true; quietSamples = 0 } else if (heardSpeech) quietSamples += size
        return when {
            heardSpeech && ms(quietSamples) >= silenceMs -> Decision.Ended
            ms(elapsedSamples) >= maxMs -> Decision.TooLong
            !heardSpeech && ms(elapsedSamples) >= noSpeechMs -> Decision.NoSpeech
            else -> Decision.Continue
        }
    }

    private fun ms(samples: Long) = samples * 1000 / sampleRate

    companion object {
        fun rms(frame: ShortArray, size: Int = frame.size): Double {
            var sum = 0.0
            for (i in 0 until size) { val v = frame[i].toDouble(); sum += v * v }
            return sqrt(sum / size)
        }
    }
}
