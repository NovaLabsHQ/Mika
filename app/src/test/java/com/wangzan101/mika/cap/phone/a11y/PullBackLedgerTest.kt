package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.OnItsOwn
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.SelfLaunch
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
        // 第二次冒出来(哪怕是另一个任务)只报不挪:用户没动手又冒出来,是它自己开的,不说用户在用
        val again = arrival(task = 9)
        assertEquals(OnItsOwn(again, SelfLaunch.Known), l.limit(PullBack(again)))
        assertTrue("com.chat" in l.selfLaunchers())
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
        // 回归:以前报成 InUse(「用户正用着,交给用户」),它其实是自己开的
        val a = arrival("com.shopper")
        assertEquals(OnItsOwn(a, SelfLaunch.LeaveAlone), l.limit(PullBack(a)))
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

    @Test fun selfLaunchersAreNeverPulledBack() {
        val l = PullBackLedger()
        // 自己冒到 Mika 屏上被送回去过,或判过一次自己开的:这次运行里从不挪
        l.selfLaunched("com.shopper")
        assertFalse(l.allows("com.shopper"))
        val a = arrival("com.shopper")
        assertEquals(OnItsOwn(a, SelfLaunch.Known), l.limit(PullBack(a)))
        val b = arrival("com.other")
        val own = OnItsOwn(b, SelfLaunch.Notified)
        assertEquals(own, l.limit(own))
        assertTrue("com.other" in l.selfLaunchers())
        assertEquals(OnItsOwn(arrival("com.other", 9), SelfLaunch.Known), l.limit(PullBack(arrival("com.other", 9))))
    }

    @Test fun reportsAreLeftAsTheyAre() {
        val v = InUse(arrival())
        assertEquals(v, PullBackLedger { setOf("com.chat") }.limit(v))
    }
}
