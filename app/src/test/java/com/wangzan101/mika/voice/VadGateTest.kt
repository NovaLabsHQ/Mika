package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {
    private val frame = ShortArray(320) // 20 ms

    private fun run(gate: VadGate, frames: Int): EndOfSpeechGate.Decision {
        var d = EndOfSpeechGate.Decision.Continue
        repeat(frames) { d = gate.offer(frame); if (d != EndOfSpeechGate.Decision.Continue) return d }
        return d
    }

    @Test fun endsAfterSilenceFollowingSpeech() {
        var speaking = true
        val gate = VadGate({ speaking }, silenceMs = 1000)
        assertEquals(EndOfSpeechGate.Decision.Continue, run(gate, 50))
        assertTrue(gate.heardSpeech)
        speaking = false
        assertEquals(EndOfSpeechGate.Decision.Continue, run(gate, 49))
        assertEquals(EndOfSpeechGate.Decision.Ended, gate.offer(frame))
    }

    @Test fun givesUpWhenNobodySpeaks() {
        val gate = VadGate({ false }, noSpeechMs = 2000)
        assertEquals(EndOfSpeechGate.Decision.NoSpeech, run(gate, 1000))
        assertFalse(gate.heardSpeech)
    }

    @Test fun stopsAtTheLimit() {
        val gate = VadGate({ true }, maxMs = 2000)
        assertEquals(EndOfSpeechGate.Decision.TooLong, run(gate, 1000))
    }

    @Test fun feedsFloatSamples() {
        var seen: FloatArray? = null
        val gate = VadGate({ seen = it; false })
        gate.offer(shortArrayOf(Short.MIN_VALUE, 0, 16384), 2)
        assertEquals(2, seen!!.size)
        assertEquals(-1f, seen!![0], 0f)
        assertEquals(0.5f, VadGate.toFloat(shortArrayOf(16384))[0], 0f)
    }
}
