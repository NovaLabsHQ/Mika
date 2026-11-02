package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTextTest {
    @Test fun appendsWithTheRightGlue() {
        assertEquals("hello", DictationText.append("", " hello "))
        assertEquals("book a table for two", DictationText.append("book a table ", "for two"))
        assertEquals("帮我订明天的票", DictationText.append("帮我订", "明天的票"))
        assertEquals("keep", DictationText.append("keep", "   "))
    }
}
