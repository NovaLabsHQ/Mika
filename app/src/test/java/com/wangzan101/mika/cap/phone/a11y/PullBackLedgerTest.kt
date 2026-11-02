package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullBackLedgerTest {
    private fun arrival(pkg: String = "com.chat", task: Int = 7) =
        MainScreenSpill.Arrival(task, pkg, 0, 10_500, MainScreenSpill.NEW, false, true, MainScreenSpill.MAIN)

    @Test fun eachAppIsPulledBackAtMostOncePerRun() {
        val l = PullBackLedger()
        val a = arrival()
        assertEquals(PullBack(a), l.limit(PullBack(a)))
        l.pulled("com.chat", 7)
        // 第二次冒出来(哪怕是另一个任务)只报不挪
        val again = arrival(task = 9)
        assertEquals(InUse(again), l.limit(PullBack(again)))
        // 别的 App 不受影响
        assertTrue(l.allows("com.other"))
    }

    @Test fun appThatLeftMikasScreenIsNeverPulledBackAgain() {
        val l = PullBackLedger()
        l.left("com.video")
        assertFalse(l.allows("com.video"))
        assertEquals(InUse(arrival("com.video")), l.limit(PullBack(arrival("com.video"))))
    }

    @Test fun leaveAloneAppsAreNeverPulledBack() {
        var list = setOf("com.shopper")
        val l = PullBackLedger { list }
        assertFalse(l.allows("com.shopper"))
        // 设置随时改,按此刻的名单算
        list = emptySet()
        assertTrue(l.allows("com.shopper"))
    }

    @Test fun watchedListsPulledTasksUntilTheyLeave() {
        val l = PullBackLedger()
        l.pulled("com.chat", 7)
        l.pulled("com.chat", 8)
        assertEquals(mapOf("com.chat" to 7), l.watched())
        l.left("com.chat")
        assertTrue(l.watched().isEmpty())
    }

    @Test fun reportsAreLeftAsTheyAre() {
        val v = InUse(arrival())
        assertEquals(v, PullBackLedger { setOf("com.chat") }.limit(v))
    }
}
