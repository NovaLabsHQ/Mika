package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsLeaveAloneTest {
    @Test fun emptyByDefault() {
        assertTrue(FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).leaveAloneApps().isEmpty())
    }

    @Test fun listIsKeptTrimmedWithoutBlanks() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        FeatureFlags(prefs).setLeaveAloneApps(setOf(" com.instacart.shopper ", "com.google.android.youtube", " "))
        assertEquals(setOf("com.instacart.shopper", "com.google.android.youtube"), FeatureFlags(prefs).leaveAloneApps())
        FeatureFlags(prefs).setLeaveAloneApps(emptySet())
        assertTrue(FeatureFlags(prefs).leaveAloneApps().isEmpty())
    }
}
