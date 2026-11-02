package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class VoiceSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = VoiceSettings(tmp.root, AesGcmKeyCipher { key })

    @Test fun defaultsNeedNoKeyAndNoDownload() {
        val c = settings().config.value
        assertEquals(SpeechEngine.PhoneRecognizer, c.speech)
        assertFalse(c.hasElevenLabsKey)
        assertTrue(c.wifiOnly)
        // 朗读默认用本机的 Supertonic 3,第一个音色
        assertEquals(ReplyEngine.Supertonic3, c.reply)
        assertEquals(0, c.supertonicVoice)
    }

    @Test fun speakRepliesDefaultsOffAndPersists() {
        val s = settings()
        assertEquals(false, s.config.value.speakReplies)
        s.setSpeakReplies(true)
        assertEquals(true, s.config.value.speakReplies)
        assertEquals(true, settings().config.value.speakReplies)
        // 其他设置不受影响
        assertEquals(ReplyEngine.Supertonic3, settings().config.value.reply)
        s.setSpeakReplies(false)
        assertEquals(false, settings().config.value.speakReplies)
        // 写坏的值按默认
        tmp.root.resolve("voice.json").writeText("""{"speakReplies":"maybe"}""")
        assertEquals(false, settings().config.value.speakReplies)
    }

    @Test fun supertonicVoicePersistsAndIsChecked() {
        val s = settings()
        s.setSupertonicVoice(7)
        assertEquals(7, settings().config.value.supertonicVoice)
        assertThrows(IllegalArgumentException::class.java) { s.setSupertonicVoice(10) }
        tmp.root.resolve("voice.json").writeText("""{"supertonicVoice":"42"}""")
        assertEquals(0, settings().config.value.supertonicVoice)
    }

    @Test fun choicesPersistAndKeyIsEncrypted() {
        val s = settings()
        s.setElevenLabsKey("  sk_test_123  ")
        s.setSpeech(SpeechEngine.ElevenLabsScribe)
        s.setReply(ReplyEngine.ElevenLabs)
        s.setWifiOnly(false)
        tmp.root.listFiles()!!.forEach { assertFalse(it.name, it.readText(Charsets.ISO_8859_1).contains("sk_test_123")) }
        val again = settings()
        assertEquals(SpeechEngine.ElevenLabsScribe, again.config.value.speech)
        assertFalse(again.config.value.wifiOnly)
        assertTrue(again.config.value.hasElevenLabsKey)
        assertEquals("sk_test_123", again.elevenLabsKey())
    }

    @Test fun clearingKeyAndBadInput() {
        val s = settings()
        s.setElevenLabsKey("abc")
        s.setElevenLabsKey("   ")
        assertNull(s.elevenLabsKey())
        assertFalse(s.config.value.hasElevenLabsKey)
        assertThrows(IllegalArgumentException::class.java) { s.setVoiceId("../x") }
        assertThrows(IllegalArgumentException::class.java) { s.setElevenLabsKey("a b") }
    }

    @Test fun unreadableFilesFallBackToDefaults() {
        tmp.root.resolve("voice.json").writeText("{not json")
        tmp.root.resolve("voice-keys.bin").writeBytes(byteArrayOf(1, 2, 3))
        val s = settings()
        assertEquals(VoiceConfig(hasElevenLabsKey = true), s.config.value)
        assertNull(s.elevenLabsKey())
    }
}
