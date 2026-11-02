package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.a11y.AgentScreenGuard.Top
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScreenGuardTest {
    private val quiet = 2_500L
    private val pickers = setOf("android", "com.google.android.permissioncontroller")

    private fun AgentScreenGuard.check(
        top: Top?, now: Long = 100_000, targetTask: Int = 5, targetPackage: String? = "com.target",
        adopted: Set<Int> = emptySet(), leaveAlone: Set<String> = emptySet(),
    ) = intruder(now, top, targetTask, targetPackage, adopted, pickers, leaveAlone)

    @Test fun appThatCameUpOnItsOwnIsAnIntruder() {
        val g = AgentScreenGuard(quiet)
        g.actionStarted(90_000, 5); g.actionEnded(91_000, 5)
        assertTrue(g.check(Top(9, "com.video")))
    }

    @Test fun targetAdoptedKnownAndSystemPagesBelongHere() {
        val g = AgentScreenGuard(quiet)
        g.actionStarted(90_000, 5); g.actionEnded(91_000, 6)
        assertFalse(g.check(Top(5, "com.target")))
        // 目标 App 的另一个任务
        assertFalse(g.check(Top(11, "com.target")))
        assertFalse(g.check(Top(12, "com.chat"), adopted = setOf(12)))
        // 动作做完时就在最上面的:模型自己操作出来的
        assertFalse(g.check(Top(6, "com.share")))
        assertFalse(g.check(Top(13, "com.google.android.permissioncontroller")))
        assertFalse(g.check(null))
        assertFalse(g.check(Top(-1, "com.video")))
        assertFalse(g.check(Top(14, null)))
    }

    @Test fun nothingIsJudgedWhileAnActionRunsOrRightAfterIt() {
        val g = AgentScreenGuard(quiet)
        g.actionStarted(99_000, 5)
        assertFalse(g.check(Top(9, "com.video")))
        g.actionEnded(99_500, 5)
        assertFalse(g.check(Top(9, "com.video"), now = 99_500 + quiet - 1))
        assertTrue(g.check(Top(9, "com.video"), now = 99_500 + quiet))
    }

    @Test fun leaveAloneAppsAreReturnedEvenIfSeenBefore() {
        val g = AgentScreenGuard(quiet)
        g.actionStarted(90_000, 9); g.actionEnded(91_000, 9)
        assertFalse(g.check(Top(9, "com.shopper")))
        assertTrue(g.check(Top(9, "com.shopper"), leaveAlone = setOf("com.shopper")))
        // 也包括挪回来过的
        assertTrue(g.check(Top(9, "com.shopper"), adopted = setOf(9), leaveAlone = setOf("com.shopper")))
        // 目标 App 本身是模型明着打开的,不送走
        assertFalse(g.check(Top(5, "com.target"), leaveAlone = setOf("com.target")))
    }

    @Test fun returnedTaskIsReturnedAgainIfItComesBack() {
        val g = AgentScreenGuard(quiet)
        g.actionStarted(90_000, 9); g.actionEnded(91_000, 9)
        g.forget(9)
        assertTrue(g.check(Top(9, "com.video")))
    }
}
