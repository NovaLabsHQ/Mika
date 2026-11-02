package com.wangzan101.mika.voice

import com.wangzan101.mika.voice.EndOfSpeechGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSpeechGateTest {
    /** 20 毫秒一帧,幅度为 [amp] 的方波(均方根就是 amp)。 */
    private fun frame(amp: Int) = ShortArray(320) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun feed(gate: EndOfSpeechGate, amp: Int, ms: Int): Decision {
        var d = Decision.Continue
        repeat(ms / 20) { d = gate.offer(frame(amp)); if (d != Decision.Continue) return d }
        return d
    }

    @Test fun endsAfterSilenceFollowingSpeech() {
        val gate = EndOfSpeechGate(silenceMs = 1000)
        assertEquals(Decision.Continue, feed(gate, 50, 400))
        assertEquals(Decision.Continue, feed(gate, 3000, 1500))
        assertTrue(gate.heardSpeech)
        assertEquals(Decision.Continue, feed(gate, 50, 900))
        assertEquals(Decision.Ended, feed(gate, 50, 200))
    }

    @Test fun shortPausesDoNotEnd() {
        val gate = EndOfSpeechGate(silenceMs = 1000)
        feed(gate, 50, 200)
        repeat(5) {
            assertEquals(Decision.Continue, feed(gate, 3000, 500))
            assertEquals(Decision.Continue, feed(gate, 50, 600))
        }
    }

    @Test fun givesUpWhenNobodySpeaks() {
        val gate = EndOfSpeechGate(noSpeechMs = 2000)
        assertEquals(Decision.NoSpeech, feed(gate, 120, 3000))
        assertFalse(gate.heardSpeech)
    }

    @Test fun loudBackgroundIsNotSpeech() {
        // 底噪本来就响(比如风扇):只比它响一点不算说话
        val gate = EndOfSpeechGate(noSpeechMs = 2000)
        assertEquals(Decision.NoSpeech, feed(gate, 800, 3000))
    }

    @Test fun stopsAtTheCap() {
        val gate = EndOfSpeechGate(maxMs = 2000)
        assertEquals(Decision.TooLong, feed(gate, 3000, 3000))
    }

    @Test fun rmsOfSquareWave() {
        assertEquals(1000.0, EndOfSpeechGate.rms(frame(1000)), 0.001)
    }
}
