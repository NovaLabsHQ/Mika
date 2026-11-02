package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeaveAloneListTest {
    private val apps = listOf(
        "com.instacart.shopper" to "Instacart Shopper",
        "com.google.android.youtube" to "YouTube",
        "com.android.settings" to "Settings",
    )

    @Test
    fun manualPackageNeedsTwoSegments() {
        assertEquals("com.instacart.shopper", LeaveAloneList.normalize("  com.instacart.shopper "))
        assertNull(LeaveAloneList.normalize("youtube"))
        assertNull(LeaveAloneList.normalize("com..youtube"))
        assertNull(LeaveAloneList.normalize("1com.youtube"))
        assertNull(LeaveAloneList.normalize(""))
    }

    @Test
    fun searchMatchesLabelOrPackageAndHidesChosen() {
        assertEquals(listOf("com.google.android.youtube"), LeaveAloneList.visible(apps, emptySet(), "tube").map { it.first })
        assertEquals(listOf("com.instacart.shopper"), LeaveAloneList.visible(apps, emptySet(), "INSTACART").map { it.first })
        assertEquals(listOf("com.android.settings"), LeaveAloneList.visible(apps, emptySet(), "android.set").map { it.first })
        assertEquals(2, LeaveAloneList.visible(apps, setOf("com.android.settings"), "").size)
    }

    @Test
    fun chosenSortedByLabelUninstalledLast() {
        val labels = apps.toMap()
        val rows = LeaveAloneList.chosenRows(setOf("org.gone.app", "com.google.android.youtube", "com.instacart.shopper"), labels)
        assertEquals(listOf("Instacart Shopper", "YouTube", "org.gone.app"), rows.map { it.second })
    }
}
