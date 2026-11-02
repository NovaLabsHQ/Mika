package com.wangzan101.mika.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 录音时每 20 毫秒问一次的「说完没有」:[AudioCapture] 一帧帧喂进 16 kHz 的采样,门说停就收尾。
 * 有按音量判断的 [EndOfSpeechGate] 和按 Silero VAD 判断的 [VadGate]。
 */
interface SpeechGate {
    /** 到目前为止听到过人说话没有。 */
    val heardSpeech: Boolean
    fun offer(frame: ShortArray, size: Int = frame.size): EndOfSpeechGate.Decision
}

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
) : SpeechGate {
    enum class Decision { Continue, Ended, NoSpeech, TooLong }

    override var heardSpeech = false
        private set
    private var elapsedSamples = 0L
    private var quietSamples = 0L
    private var floor = -1.0

    override fun offer(frame: ShortArray, size: Int): Decision {
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

/**
 * 按语音活动检测判断话说完没有:[speaking] 收一帧 [-1,1] 的浮点采样,答这会儿是不是有人在说话
 * (真机上是 sherpa-onnx 的 Silero VAD,单测里换成假的)。说过话以后连续 [silenceMs] 没人说话就算说完;
 * 其余规则和 [EndOfSpeechGate] 一样。
 */
class VadGate(
    private val speaking: (FloatArray) -> Boolean,
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 900,
    private val noSpeechMs: Int = 6_000,
    private val maxMs: Int = 60_000,
) : SpeechGate {
    override var heardSpeech = false
        private set
    private var elapsedSamples = 0L
    private var quietSamples = 0L

    override fun offer(frame: ShortArray, size: Int): EndOfSpeechGate.Decision {
        if (size <= 0) return EndOfSpeechGate.Decision.Continue
        elapsedSamples += size
        if (speaking(toFloat(frame, size))) { heardSpeech = true; quietSamples = 0 } else if (heardSpeech) quietSamples += size
        return when {
            heardSpeech && ms(quietSamples) >= silenceMs -> EndOfSpeechGate.Decision.Ended
            ms(elapsedSamples) >= maxMs -> EndOfSpeechGate.Decision.TooLong
            !heardSpeech && ms(elapsedSamples) >= noSpeechMs -> EndOfSpeechGate.Decision.NoSpeech
            else -> EndOfSpeechGate.Decision.Continue
        }
    }

    private fun ms(samples: Long) = samples * 1000 / sampleRate

    companion object {
        /** 16 位采样换成 sherpa-onnx 要的 [-1,1] 浮点。 */
        fun toFloat(pcm: ShortArray, size: Int = pcm.size): FloatArray = FloatArray(size) { pcm[it] / 32768f }
    }
}
