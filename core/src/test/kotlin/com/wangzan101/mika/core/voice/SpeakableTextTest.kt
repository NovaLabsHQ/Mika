package com.wangzan101.mika.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextTest {
    @Test fun stripsMarkdown() {
        val md = """
            # 标题
            **Bold** and *italic* and `code` with [a link](https://x.y/z).
            ![pic](a.png)
            > quoted
            - item one
            1. item two
            ```kotlin
            val x = 1
            ```
            See https://example.com now.
        """.trimIndent()
        val s = SpeakableText.from(md)
        assertEquals("标题\nBold and italic and code with a link.\nquoted\nitem one\nitem two\n\nSee now.", s)
        assertFalse(s.contains("val x"))
    }

    @Test fun tableBecomesPauses() {
        val s = SpeakableText.from("| 名字 | 价格 |\n|---|---|\n| 苹果 | 3 元 |")
        assertEquals("名字, 价格\n苹果, 3 元", s)
    }

    @Test fun unclosedFenceDropsRest() {
        assertEquals("before", SpeakableText.from("before\n```\ncode never closed"))
    }
}

class SentenceSplitterTest {
    @Test fun splitsLatinAndCjk() {
        val parts = SentenceSplitter(minChars = 4).split("Hello world. Pi is 3.14 exactly! 你好呀。今天天气不错！Done")
        assertEquals(listOf("Hello world.", "Pi is 3.14 exactly!", "你好呀。", "今天天气不错！", "Done"), parts)
    }

    @Test fun mergesShortPieces() {
        assertEquals(listOf("Hi. Ok. Then we go on."), SentenceSplitter(minChars = 12).split("Hi. Ok. Then we go on."))
    }

    @Test fun cutsLongRunsAtCommas() {
        val long = (1..40).joinToString(", ") { "word$it" }
        val parts = SentenceSplitter(minChars = 4, maxChars = 60).split(long)
        assertTrue(parts.all { it.length <= 60 })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test fun incrementalMatchesWhole() {
        val text = "First sentence here. Second one comes now! 第三句话在这里。还有最后一句"
        val whole = SentenceSplitter().split(text)
        val inc = SentenceSplitter().Incremental()
        val streamed = text.chunked(3).flatMap { inc.push(it) } + inc.flush()
        assertEquals(whole, streamed)
    }

    @Test fun waitsForSpaceAfterLatinPeriod() {
        val inc = SentenceSplitter(minChars = 2).Incremental()
        assertEquals(emptyList<String>(), inc.push("Version 3."))
        assertEquals(emptyList<String>(), inc.push("1 is out"))
        assertEquals(listOf("Version 3.1 is out"), inc.flush())
    }
}

class VoiceCatalogTest {
    @Test fun everyLocalEngineHasAModel() {
        for (e in VoiceEngineId.entries) assertEquals(e.name, e.needsModel, VoiceCatalog.modelFor(e) != null)
        assertTrue(VoiceCatalog.supertonic3.voices.isNotEmpty())
    }

    @Test fun enginesSplitByKind() {
        assertEquals(VoiceEngineId.entries.size, VoiceEngineId.recognizers.size + VoiceEngineId.synthesizers.size)
        assertTrue(VoiceEngineId.recognizers.contains(VoiceEngineId.PhoneRecognizer))
    }
}
