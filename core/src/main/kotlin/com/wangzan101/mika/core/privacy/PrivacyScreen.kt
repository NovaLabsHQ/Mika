package com.wangzan101.mika.core.privacy

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.visual.FrameGrid
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.SystemCommandResult
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.WheelOutcome

/**
 * 隐私名单的执行点:包在手机端口外面,隐私名单上的 App([blocked])Mika 打不开、看不到、动不了。
 *
 * - 打开、打开另一份、分享、指明 App 的链接、系统动作交给名单上的 App:不发出,报 [SensitiveAppPolicy.BLOCKED]。
 * - Mika 屏最上面是名单上的 App 时:读屏给一份空白的屏(只带 [ScreenSnapshot.blockedApp],不带任何元素),截图、OCR、
 *   格子图、读屏诊断一概为空,点按、打字、滑动、按键都不做,并先把它送回用户的主屏([ScreenPort.sendToUserScreen])。
 * - 任何动作做完后最上面成了名单上的 App(链接、点按跳了过去):送回用户的主屏,报 [SensitiveAppPolicy.BLOCKED],
 *   动作结果里的内容一并丢掉。
 * - 系统命令:Mika 屏或用户屏最上面是名单上的 App 时不执行读屏、截屏一类的命令;命令里点了名单上的包名的不执行。
 * - 事件:名单上 App 的通知、窗口事件只留类型和包名,文字去掉。
 *
 * [label] 取 App 显示名,写进提示。手机之外的端口(网页)不经这里。
 */
class PrivacyScreen(
    private val base: ScreenPort,
    private val blocked: (String) -> Boolean,
    private val label: suspend (String) -> String? = { null },
) : ScreenPort by base {

    /** 此刻 Mika 屏最上面的、在名单上的 App;没有为 null。 */
    private suspend fun blockedTop(): String? = runCatching { base.foregroundPackage() }.getOrNull()?.takeIf(blocked)

    private suspend fun name(pkg: String): String = label(pkg)?.takeIf { it.isNotBlank() }?.let { "$it ($pkg)" } ?: pkg

    /** 动不得 [pkg] 的失败;[movedAway] 它已被送回用户的主屏。 */
    private suspend fun refused(pkg: String, movedAway: Boolean = false): ActionResult.Failed =
        ActionResult.Failed(SensitiveAppPolicy.BLOCKED, hint(pkg, movedAway))

    private suspend fun hint(pkg: String, movedAway: Boolean): String =
        "Blocked app: ${name(pkg)}." + if (movedAway) " It was sent back to the user's own screen; Mika's screen shows what was there before." else ""

    /** 最上面是名单上的 App:送回用户屏,返回它的包名;不是为 null。 */
    private suspend fun evictBlockedTop(): String? {
        val pkg = blockedTop() ?: return null
        runCatching { base.sendToUserScreen(pkg) }
        return pkg
    }

    /** 动作前后的检查:前面挡着就不做;做完成了名单上的 App 就送走、报失败。 */
    private suspend fun guarded(action: suspend () -> ActionResult): ActionResult {
        evictBlockedTop()?.let { return refused(it, movedAway = true) }
        val r = action()
        evictBlockedTop()?.let { return refused(it, movedAway = true) }
        // 开到了用户的主屏上、留在那里没挪过来的名单上的 App
        (r as? ActionResult.Ok)?.opened?.takeIf { it.blocked }?.let {
            return ActionResult.Failed(SensitiveAppPolicy.BLOCKED, "Blocked app: ${name(it.packageName)}. It opened on the user's own screen and was left there.")
        }
        return r
    }

    private suspend fun launching(pkg: String?, action: suspend () -> ActionResult): ActionResult {
        if (pkg != null && blocked(pkg)) return refused(pkg)
        return guarded(action)
    }

    /** 给模型的空白屏:只说最上面是名单上的哪个 App。 */
    private fun blank(s: ScreenSnapshot, pkg: String) = ScreenSnapshot(
        snapshotId = s.snapshotId, packageName = pkg, elements = emptyList(), width = s.width, height = s.height,
        hasWindow = true, blockedApp = pkg,
    )

    private fun ScreenSnapshot.shows(): String? =
        listOfNotNull(packageName, unreadableApp).firstOrNull(blocked) ?: windows.mapNotNull { it.packageName }.firstOrNull(blocked)

    private suspend fun screened(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val s = read()
        val pkg = s.shows() ?: blockedTop() ?: return s
        // 看见了就送走:下一次读屏读到的是 Mika 原来那一屏
        runCatching { base.sendToUserScreen(pkg) }
        return blank(s, pkg)
    }

    override suspend fun snapshot(): ScreenSnapshot = screened { base.snapshot() }
    override suspend fun peek(): ScreenSnapshot = screened { base.peek() }

    override suspend fun screenshotPng(): ByteArray? = if (blockedTop() != null) null else base.screenshotPng().takeIf { blockedTop() == null }
    override suspend fun readVisibleText(): List<VisibleTextLine>? = if (blockedTop() != null) null else base.readVisibleText().takeIf { blockedTop() == null }
    override suspend fun frameGrid(): FrameGrid? = if (blockedTop() != null) null else base.frameGrid().takeIf { blockedTop() == null }
    override suspend fun checkScreen(): ScreenCheck? = if (blockedTop() != null) null else base.checkScreen()
    override suspend fun isProtected(): Boolean = blockedTop() != null || base.isProtected()
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean =
        if (blockedTop() != null) false else base.waitFor(condition, value, timeoutMs) && blockedTop() == null

    override suspend fun tapRef(ref: String, snapshotId: String) = guarded { base.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = guarded { base.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = guarded { base.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = guarded { base.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = guarded { base.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = guarded { base.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = guarded { base.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = guarded { base.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = guarded { base.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = guarded { base.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = guarded { base.gesture(paths, durationMs) }
    override suspend fun back() = guarded { base.back() }
    override suspend fun pressKey(keyCode: Int) = guarded { base.pressKey(keyCode) }
    override suspend fun idleScroll(fraction: Double): Boolean = if (blockedTop() != null) false else base.idleScroll(fraction)

    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome {
        evictBlockedTop()?.let { return WheelOutcome(false, null, null, 0, "none", error = SensitiveAppPolicy.BLOCKED) }
        val r = base.setWheel(ref, snapshotId, value)
        evictBlockedTop()?.let { return WheelOutcome(false, null, null, 0, "none", error = SensitiveAppPolicy.BLOCKED) }
        return r
    }

    override suspend fun openApp(packageName: String, cold: Boolean) = launching(packageName) { base.openApp(packageName, cold) }
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean) = launching(packageName) { base.openAppCopy(packageName, userId, cold) }
    override suspend fun shareText(packageName: String, text: String) = launching(packageName) { base.shareText(packageName, text) }
    override suspend fun openLink(action: String?, uri: String?, packageName: String?) = launching(packageName) { base.openLink(action, uri, packageName) }

    override suspend fun systemAction(action: SystemAction): SystemActionResult {
        evictBlockedTop()?.let { return SystemActionResult(refused(it, movedAway = true)) }
        val r = base.systemAction(action)
        r.handledBy?.takeIf(blocked)?.let { pkg ->
            evictBlockedTop()
            return SystemActionResult(refused(pkg, movedAway = true), handledBy = pkg)
        }
        evictBlockedTop()?.let { return SystemActionResult(refused(it, movedAway = true), handledBy = r.handledBy) }
        return r
    }

    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        if (packageName != null && blocked(packageName)) return null
        return base.nextEvent(kind, packageName, timeoutMs)?.let(::redact)
    }

    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        if (packageName != null && blocked(packageName)) return emptyList()
        return base.watchEvents(kind, packageName, durationMs, maxEvents).map(::redact)
    }

    private fun redact(e: ScreenEvent): ScreenEvent = if (e.packageName != null && blocked(e.packageName)) e.copy(text = null) else e

    override suspend fun systemCommand(command: String): SystemCommandResult? {
        commandTarget(command)?.let { return SystemCommandResult(SystemCommandResult.BLOCKED_BY_PRIVACY, hint(it, false)) }
        val top = blockedTop() ?: base.mainScreenPackage()?.takeIf(blocked)
        if (top != null && readsScreen(command)) return SystemCommandResult(SystemCommandResult.BLOCKED_BY_PRIVACY, hint(top, false))
        if (blockedTop() != null) evictBlockedTop()?.let { return SystemCommandResult(SystemCommandResult.BLOCKED_BY_PRIVACY, hint(it, true)) }
        val r = base.systemCommand(command)
        evictBlockedTop()?.let { return SystemCommandResult(SystemCommandResult.BLOCKED_BY_PRIVACY, hint(it, true)) }
        return r
    }

    /** 命令里点了名的、在名单上的包;没有为 null。 */
    internal fun commandTarget(command: String): String? =
        PACKAGE_TOKEN.findAll(command).map { it.value.substringBefore('/') }.firstOrNull(blocked)

    companion object {
        /** 像包名的一段(可带 /组件)。 */
        private val PACKAGE_TOKEN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+(/[A-Za-z0-9_.$]+)?")

        /** 读屏、截屏、录屏、看界面的命令。 */
        private val SCREEN_READERS = Regex("\\b(screencap|screenrecord|uiautomator|dumpsys\\s+(window|activity|SurfaceFlinger|accessibility|notification))\\b")

        internal fun readsScreen(command: String): Boolean = SCREEN_READERS.containsMatchIn(command)
    }
}
