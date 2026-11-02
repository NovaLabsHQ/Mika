package com.wangzan101.mika.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechLanguageTest {
    @Test fun scriptsMapToLanguages() {
        assertEquals("ko", SpeechLanguage.pick("안녕하세요, 반갑습니다", "en"))
        assertEquals("ja", SpeechLanguage.pick("今日は良い天気ですね", "en"))
        assertEquals("ar", SpeechLanguage.pick("مرحبا بالعالم", "en"))
        assertEquals("el", SpeechLanguage.pick("Καλημέρα κόσμε", "en"))
        assertEquals("hi", SpeechLanguage.pick("नमस्ते दुनिया", "en"))
    }

    @Test fun chineseIsNotSpoken() {
        assertNull(SpeechLanguage.pick("今天天气很好", "zh"))
        assertNull(SpeechLanguage.pick("已经帮你打开 Chrome 了", "zh-Hant"))
    }

    @Test fun latinFollowsPreferredLanguage() {
        assertEquals("fr", SpeechLanguage.pick("Bonjour tout le monde", "fr-FR"))
        assertEquals("pt", SpeechLanguage.pick("Olá mundo", "pt_BR"))
        assertEquals("en", SpeechLanguage.pick("Hello world", "zh"))
        assertEquals("en", SpeechLanguage.pick("Hello world", null))
    }

    @Test fun cyrillicGuessesUkrainianLetters() {
        assertEquals("ru", SpeechLanguage.pick("Привет, мир", "en"))
        assertEquals("uk", SpeechLanguage.pick("Привіт, світе", "en"))
        assertEquals("bg", SpeechLanguage.pick("Здравей, свят", "bg"))
    }

    @Test fun digitsOnlyUsePreferredOrEnglish() {
        assertEquals("de", SpeechLanguage.pick("42", "de"))
        assertEquals("en", SpeechLanguage.pick("42", "zh"))
    }

    @Test fun baseTag() {
        assertEquals("zh", SpeechLanguage.base("zh-Hant-TW"))
        assertNull(SpeechLanguage.base(""))
    }
}

class SpeechPlanTest {
    @Test fun markdownBecomesSentences() {
        val md = "## Done\n\nI opened **Settings** and turned on [Wi-Fi](https://x.y).\n\n```\ncode()\n```\n- It is connected now."
        assertEquals(listOf("Done I opened Settings and turned on Wi-Fi.", "It is connected now."), SpeechPlan.sentences(md))
    }

    @Test fun dropsPiecesWithoutWords() {
        assertEquals(emptyList<String>(), SpeechPlan.sentences("---\n\n```\nx\n```"))
    }

    @Test fun floatToPcm16Clamps() {
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767, -32767), FloatPcm.toPcm16(floatArrayOf(0f, 1f, -1f, 2f, -3f)))
    }
}

class ReplyReaderTest {
    private class FakeSynth(val gate: CompletableDeferred<Unit>? = null, val failOn: String? = null) : SpeechSynthesizer {
        override val id = VoiceEngineId.Supertonic3
        val asked = mutableListOf<Pair<String, String?>>()
        override fun synthesize(text: String, language: String?): Flow<PcmChunk> = flow {
            asked += text to language
            if (text == failOn) throw VoiceError.Unavailable("boom")
            // 第二句等放第一句时才合成完
            if (asked.size == 2) gate?.await()
            emit(PcmChunk(ShortArray(text.length), 44_100))
        }
    }

    private class Recorder(val onWrite: (PcmChunk) -> Unit = {}) : PcmSink {
        val written = mutableListOf<Int>()
        var drained = false
        override suspend fun write(chunk: PcmChunk) { written += chunk.samples.size; onWrite(chunk) }
        override suspend fun drain() { drained = true }
    }

    @Test fun firstSentencePlaysBeforeTheRestIsSynthesized() = runTest {
        val gate = CompletableDeferred<Unit>()
        val synth = FakeSynth(gate)
        val sink = Recorder { if (!gate.isCompleted) gate.complete(Unit) }
        val r = ReplyReader(synth) { "en" }.read("The first sentence is here. And the second one follows.", sink)
        assertEquals(ReplyReader.Result(2, 0), r)
        assertEquals(listOf("The first sentence is here.".length, "And the second one follows.".length), sink.written)
        assertTrue(sink.drained)
    }

    @Test fun unsupportedSentencesAreSkipped() = runTest {
        val synth = FakeSynth()
        val sink = Recorder()
        val r = ReplyReader(synth) { SpeechLanguage.pick(it, "en") }.read("这一句是中文的内容,而且足够长,不会被并到下一句里。This sentence is English.", sink)
        assertEquals(ReplyReader.Result(1, 1), r)
        assertEquals(listOf("This sentence is English." to "en"), synth.asked)
    }

    @Test fun allUnsupportedSpeaksNothing() = runTest {
        val sink = Recorder()
        val r = ReplyReader(FakeSynth()) { null }.read("今天天气很好。", sink)
        assertEquals(ReplyReader.Result(0, 1), r)
        assertTrue(sink.written.isEmpty() && !sink.drained)
    }

    @Test fun synthesisErrorReachesTheCaller() = runTest {
        val sink = Recorder()
        try { ReplyReader(FakeSynth(failOn = "Second sentence fails here.")) { "en" }.read("First sentence is fine. Second sentence fails here.", sink); fail() } catch (e: VoiceError.Unavailable) { assertEquals("boom", e.message) }
        assertTrue(!sink.drained)
    }
}
