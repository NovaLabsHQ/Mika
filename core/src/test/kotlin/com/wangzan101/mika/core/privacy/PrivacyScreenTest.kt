package com.wangzan101.mika.core.privacy

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.SystemCommandResult
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyScreenTest {
    private val bank = "com.chase.sig.android"

    /** 最上面是 [top] 的假端口;动作可以把最上面换成 [after]。 */
    private class Phone(var top: String = "com.x") : ScreenPort {
        val calls = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var after: String? = null
        var result: ActionResult = ActionResult.Ok()
        private fun did(c: String): ActionResult { calls += c; after?.let { top = it }; return result }
        private val el = UiElement("@e1", "text", "Balance \$12,345", 540, 120, listOf(0, 0, 999, 100), "a:id/b", true, false, false)
        override suspend fun snapshot() = ScreenSnapshot("s1", top, listOf(el), 1080, 2400)
        override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        override suspend fun readVisibleText() = listOf(VisibleTextLine("Balance", listOf(0, 0, 1, 1)))
        override suspend fun tapRef(ref: String, snapshotId: String) = did("tap $ref")
        override suspend fun tapXY(x: Int, y: Int) = did("tapXY")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = did("type $text")
        override suspend fun clearText(ref: String?, snapshotId: String?) = did("clear")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = did("scroll")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = did("swipe")
        override suspend fun back() = did("back")
        override suspend fun pressKey(keyCode: Int) = did("key")
        override suspend fun openApp(packageName: String, cold: Boolean) = did("open $packageName")
        override suspend fun openLink(action: String?, uri: String?, packageName: String?) = did("link $uri")
        override suspend fun shareText(packageName: String, text: String) = did("share $packageName")
        override suspend fun systemAction(action: SystemAction) = SystemActionResult(did("system"), handledBy = after)
        override suspend fun systemCommand(command: String): SystemCommandResult { calls += "cmd $command"; return SystemCommandResult(0, "out") }
        override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) = ScreenEvent("notification", "com.chase.sig.android", "Balance low")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = true
        override suspend fun listApps() = listOf("com.x" to "X", "com.chase.sig.android" to "Chase")
        override suspend fun foregroundPackage() = top
        override suspend fun sendToUserScreen(packageName: String): Boolean { sent += packageName; top = "com.x"; return true }
    }

    private fun privacy(p: Phone) = PrivacyScreen(p, SensitiveAppPolicy.DEFAULT::blocked) { if (it == bank) "Chase" else null }
    private fun ActionResult.code() = (this as? ActionResult.Failed)?.reason

    @Test fun launchingABlockedAppIsRefusedBeforeAnythingHappens() = runTest {
        val p = Phone(); val s = privacy(p)
        val r = s.openApp(bank)
        assertEquals(SensitiveAppPolicy.BLOCKED, r.code())
        assertTrue((r as ActionResult.Failed).hint!!.contains("Chase"))
        assertEquals(SensitiveAppPolicy.BLOCKED, s.shareText(bank, "hi").code())
        assertEquals(SensitiveAppPolicy.BLOCKED, s.openLink(null, "https://chase.com", bank).code())
        assertEquals(SensitiveAppPolicy.BLOCKED, s.openAppCopy(bank, 10).code())
        assertTrue(p.calls.isEmpty())
    }

    @Test fun everydayAppsPassThrough() = runTest {
        val p = Phone(); val s = privacy(p)
        assertTrue(s.openApp("com.android.chrome") is ActionResult.Ok)
        assertTrue(s.tapRef("@e1", "s1") is ActionResult.Ok)
        assertEquals("com.x", s.snapshot().packageName)
        assertEquals(1, s.snapshot().elements.size)
        assertTrue(s.screenshotPng() != null)
        assertEquals(listOf("open com.android.chrome", "tap @e1"), p.calls)
    }

    @Test fun aBlockedAppInFrontIsNeitherShownNorOperated() = runTest {
        val p = Phone(top = bank); val s = privacy(p)
        assertEquals(SensitiveAppPolicy.BLOCKED, s.tapRef("@e1", "s1").code())
        assertTrue(p.calls.isEmpty())
        assertEquals(listOf(bank), p.sent)
        p.top = bank
        assertNull(s.screenshotPng())
        assertNull(s.readVisibleText())
        assertTrue(s.isProtected())
        assertFalse(s.waitFor("appear", "Balance", 100))
        val snap = s.snapshot()
        assertEquals(bank, snap.blockedApp)
        assertTrue(snap.elements.isEmpty())
    }

    @Test fun anActionThatLandsOnABlockedAppSendsItAwayAndFails() = runTest {
        val p = Phone().apply { after = bank }; val s = privacy(p)
        val r = s.tapRef("@e1", "s1")
        assertEquals(SensitiveAppPolicy.BLOCKED, r.code())
        assertEquals(listOf(bank), p.sent)
        assertEquals("com.x", p.top)
    }

    @Test fun aSystemActionHandledByABlockedAppFails() = runTest {
        val p = Phone().apply { after = bank }; val s = privacy(p)
        val r = s.systemAction(SystemAction.ShowAlarms)
        assertEquals(SensitiveAppPolicy.BLOCKED, r.result.code())
        assertEquals(bank, r.handledBy)
    }

    @Test fun aBlockedAppLeftOnTheUsersScreenIsReported() = runTest {
        val p = Phone().apply { result = ActionResult.Ok(opened = OpenedElsewhere(bank, "Chase", pulledBack = false, blocked = true)) }
        assertEquals(SensitiveAppPolicy.BLOCKED, privacy(p).tapXY(1, 1).code())
    }

    @Test fun eventsFromBlockedAppsLoseTheirText() = runTest {
        val e = privacy(Phone()).nextEvent("notification", null, 100)!!
        assertEquals(bank, e.packageName)
        assertNull(e.text)
        assertNull(privacy(Phone()).nextEvent("notification", bank, 100))
    }

    @Test fun shellCommandsNamingABlockedAppOrReadingItsScreenAreRefused() = runTest {
        val p = Phone(); val s = privacy(p)
        assertEquals(SystemCommandResult.BLOCKED_BY_PRIVACY, s.systemCommand("am start -n $bank/.Main")!!.exitCode)
        assertEquals(SystemCommandResult.BLOCKED_BY_PRIVACY, s.systemCommand("pm clear $bank")!!.exitCode)
        assertEquals(0, s.systemCommand("pm list packages com.android.chrome")!!.exitCode)
        p.top = bank
        assertEquals(SystemCommandResult.BLOCKED_BY_PRIVACY, s.systemCommand("screencap -p /sdcard/x.png")!!.exitCode)
        assertEquals(listOf("cmd pm list packages com.android.chrome"), p.calls)
        assertTrue(PrivacyScreen.readsScreen("uiautomator dump"))
        assertFalse(PrivacyScreen.readsScreen("settings get global airplane_mode_on"))
    }

    @Test fun theModelGetsTheErrorAndABlankObservation() = runTest {
        val d = toolDispatcher(privacy(Phone()))
        val launch = d.execute(Part.ToolCall("c1", "phone_launch_app", """{"package":"$bank"}"""))
        assertEquals("app_blocked_by_privacy", launch.content["error"]!!.jsonPrimitive.content)
        val msg = launch.content.toString()
        assertTrue(msg, msg.contains("privacy list") && msg.contains("Chase") && msg.contains("Do not retry"))

        val d2 = toolDispatcher(privacy(Phone(top = bank)))
        val obs = d2.execute(Part.ToolCall("c2", "phone_observe", "{}"))
        assertEquals("true", obs.content["blockedByPrivacy"]!!.jsonPrimitive.content)
        assertTrue(obs.content["elements"]!!.jsonArray.isEmpty())
        assertFalse(obs.content.toString().contains("Balance"))
        assertNull(obs.image); assertNull(obs.frame)
    }
}
