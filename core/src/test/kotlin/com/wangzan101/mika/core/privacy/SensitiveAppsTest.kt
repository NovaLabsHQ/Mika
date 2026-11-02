package com.wangzan101.mika.core.privacy

import com.wangzan101.mika.core.privacy.SensitiveApps.Category
import com.wangzan101.mika.core.privacy.SensitiveApps.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveAppsTest {
    private fun assertBlocked(pkg: String, category: Category, reason: Reason) {
        val m = SensitiveApps.match(pkg)
        assertEquals(pkg, category, m?.category)
        assertEquals(pkg, reason, m?.reason)
    }

    @Test fun curatedAppsMatchByExactPackage() {
        assertBlocked("com.chase.sig.android", Category.Banking, Reason.Curated)
        assertBlocked("com.paypal.android.p2pmobile", Category.Finance, Reason.Curated)
        assertBlocked("com.robinhood.android", Category.Brokerage, Reason.Curated)
        assertBlocked("com.coinbase.android", Category.Crypto, Reason.Curated)
        assertBlocked("epic.mychart.android", Category.Medical, Reason.Curated)
        assertBlocked("com.x8bit.bitwarden", Category.Passwords, Reason.Curated)
        assertBlocked("com.google.android.apps.authenticator2", Category.Authenticator, Reason.Curated)
        assertBlocked("COM.CHASE.SIG.ANDROID", Category.Banking, Reason.Curated)
    }

    @Test fun keywordsMatchWholeSegmentsOnly() {
        assertNull("embankment 不是整段 bank", SensitiveApps.match("com.example.embankment"))
        assertNull("healthy 不是整段 health", SensitiveApps.match("com.recipes.healthy"))
        assertBlocked("com.example.bank", Category.Banking, Reason.Keyword)
        assertBlocked("de.some_bank.app", Category.Banking, Reason.Keyword)
        assertBlocked("com.acme.crypto-wallet", Category.Crypto, Reason.Keyword)
        assertBlocked("org.keepass.android", Category.Passwords, Reason.Keyword)
        assertEquals("bank", SensitiveApps.match("com.example.bank")?.keyword)
    }

    @Test fun everydayAppsAreNotBlocked() {
        for (pkg in listOf(
            "com.wangzan101.mika", "com.wangzan101.mika.tester", "com.android.chrome", "com.github.android",
            "com.google.android.gm", "com.instacart.client", "com.instacart.shopper", "com.google.android.youtube",
            "com.whatsapp", "com.android.settings", "com.google.android.apps.maps", "com.android.vending",
            "com.google.android.gms", "com.google.android.apps.messaging", "com.android.systemui",
            "com.example.bankruptcy", "com.google.android.apps.photos", "com.amazon.mShop.android.shopping",
            "com.spotify.music", "com.ubercab", "com.android.camera",
        )) assertNull(pkg, SensitiveApps.match(pkg))
    }

    @Test fun mikaIsNeverBlockedEvenIfAKeywordWouldMatch() {
        assertTrue(SensitiveApps.neverBlock("com.wangzan101.mika.bank"))
        assertNull(SensitiveApps.match("com.wangzan101.mika.bank"))
    }

    @Test fun everyCuratedPackageAndKeywordIsLowercaseAndUnique() {
        for (p in SensitiveApps.curated.keys) assertEquals(p, p.trim(), p)
        for (k in SensitiveApps.keywords.keys) assertEquals(k, k.lowercase(), k)
    }
}
