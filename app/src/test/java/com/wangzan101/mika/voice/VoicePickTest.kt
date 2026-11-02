package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.settings.SpeechEngine
import com.wangzan101.mika.settings.VoiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePickTest {
    private val all = setOf(VoiceEngineId.PhoneRecognizer, VoiceEngineId.ElevenLabsScribe)

    @Test fun usesTheChosenEngineWhenItWorks() {
        val p = VoicePick.recognizer(VoiceConfig(speech = SpeechEngine.ElevenLabsScribe, hasElevenLabsKey = true), true, all)
        assertEquals(VoiceEngineId.ElevenLabsScribe, p.engine)
        assertNull(p.notice)
    }

    @Test fun scribeWithoutKeyFallsBackToPhone() {
        val p = VoicePick.recognizer(VoiceConfig(speech = SpeechEngine.ElevenLabsScribe), true, all)
        assertEquals(VoiceEngineId.PhoneRecognizer, p.engine)
        assertEquals(VoicePick.Notice.NoKey, p.notice)
    }

    @Test fun localModelNotReadyFallsBackToPhone() {
        val p = VoicePick.recognizer(VoiceConfig(speech = SpeechEngine.Parakeet3), true, all)
        assertEquals(VoiceEngineId.PhoneRecognizer, p.engine)
        assertEquals(VoicePick.Notice.NotReady, p.notice)
    }

    @Test fun phoneMissingUsesScribeIfKeyElseNothing() {
        val withKey = VoicePick.recognizer(VoiceConfig(hasElevenLabsKey = true), false, all)
        assertEquals(VoiceEngineId.ElevenLabsScribe, withKey.engine)
        assertEquals(VoicePick.Notice.PhoneUnavailable, withKey.notice)
        val none = VoicePick.recognizer(VoiceConfig(), false, all)
        assertNull(none.engine)
        assertEquals(VoicePick.Notice.PhoneUnavailable, none.notice)
    }

    @Test fun downloadedParakeetIsUsedAndIsTheSecondFallback() {
        val withModel = all + VoiceEngineId.Parakeet3
        val chosen = VoicePick.recognizer(VoiceConfig(speech = SpeechEngine.Parakeet3), true, withModel)
        assertEquals(VoiceEngineId.Parakeet3, chosen.engine)
        assertNull(chosen.notice)
        // 手机没有识别服务时先退到下好的本机模型,不花 ElevenLabs 的钱
        val noPhone = VoicePick.recognizer(VoiceConfig(hasElevenLabsKey = true), false, withModel)
        assertEquals(VoiceEngineId.Parakeet3, noPhone.engine)
        assertEquals(VoicePick.Notice.PhoneUnavailable, noPhone.notice)
    }
}
