package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSensitiveAppsTest {
    @Test fun wholeListIsBlockedByDefault() {
        val p = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).sensitiveApps()
        assertTrue(p.allowed.isEmpty() && p.extra.isEmpty())
        assertTrue(p.blocked("com.chase.sig.android"))
        assertTrue(p.blocked("com.x8bit.bitwarden"))
        assertFalse(p.blocked("com.android.chrome"))
    }

    @Test fun togglesAreSavedAndReadBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        flags.setSensitiveApps(flags.sensitiveApps().toggled("com.chase.sig.android", block = false).toggled("com.android.chrome", block = true))
        val p = FeatureFlags(prefs).sensitiveApps()
        assertEquals(setOf("com.chase.sig.android"), p.allowed)
        assertEquals(setOf("com.android.chrome"), p.extra)
        assertFalse(p.blocked("com.chase.sig.android"))
        assertTrue(p.blocked("com.android.chrome"))
        flags.setSensitiveApps(p.toggled("com.chase.sig.android", block = true).toggled("com.android.chrome", block = false))
        assertTrue(FeatureFlags(prefs).sensitiveApps().blocked("com.chase.sig.android"))
        assertFalse(FeatureFlags(prefs).sensitiveApps().blocked("com.android.chrome"))
    }
}
