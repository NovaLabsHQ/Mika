package com.wangzan101.mika.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveAppPolicyTest {
    private val chase = "com.chase.sig.android"
    private val chrome = "com.android.chrome"

    @Test fun defaultsBlockTheWholeList() {
        val p = SensitiveAppPolicy.DEFAULT
        assertTrue(p.blocked(chase))
        assertTrue(p.blocked("com.x8bit.bitwarden"))
        assertFalse(p.blocked(chrome))
        assertFalse(p.blocked(null))
        assertFalse(p.blocked("com.wangzan101.mika.tester"))
    }

    @Test fun userCanAllowADefaultAndBlockAnExtraApp() {
        val p = SensitiveAppPolicy(allowed = setOf(chase), extra = setOf(chrome))
        assertFalse(p.blocked(chase))
        assertTrue(p.blocked(chrome))
    }

    @Test fun extraNeverBlocksMika() {
        assertFalse(SensitiveAppPolicy(extra = setOf("com.wangzan101.mika")).blocked("com.wangzan101.mika"))
    }

    @Test fun togglingRoundTrips() {
        val off = SensitiveAppPolicy.DEFAULT.toggled(chase, block = false)
        assertEquals(setOf(chase), off.allowed)
        assertFalse(off.blocked(chase))
        val on = off.toggled(chase, block = true)
        assertTrue(on.allowed.isEmpty()); assertTrue(on.extra.isEmpty())
        assertTrue(on.blocked(chase))
        val added = SensitiveAppPolicy.DEFAULT.toggled(chrome, block = true)
        assertEquals(setOf(chrome), added.extra)
        assertTrue(added.toggled(chrome, block = false).extra.isEmpty())
    }

    @Test fun entriesListInstalledSensitiveAppsWithTheirReason() {
        val installed = mapOf(chase to "Chase", chrome to "Chrome", "com.example.bank" to "Acme Bank", "com.github.android" to "GitHub")
        val p = SensitiveAppPolicy(allowed = setOf("com.example.bank"))
        val e = p.entries(installed)
        assertEquals(listOf("Acme Bank", "Chase"), e.map { it.label })
        val acme = e[0]
        assertFalse(acme.blocked)
        assertEquals(SensitiveApps.Reason.Keyword, acme.match?.reason)
        assertEquals("bank", acme.match?.keyword)
        assertEquals(SensitiveApps.Reason.Curated, e[1].match?.reason)
        assertTrue(e[1].blocked)
    }

    @Test fun userAddedAppsAreListedEvenWhenUninstalled() {
        val e = SensitiveAppPolicy(extra = setOf(chrome, "com.gone.app")).entries(mapOf(chrome to "Chrome"))
        assertEquals(listOf("Chrome", "com.gone.app"), e.map { it.label })
        assertTrue(e.all { it.userAdded && it.blocked })
        assertNull(e[0].match)
    }
}
