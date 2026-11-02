package com.wangzan101.mika.cap.phone.a11y

/**
 * 运行期间看着 Mika 的屏:最上面冒出一个不该在这里的 App(用户手上的 App 被系统带了过来、App 自己从后台起来),
 * 就把它送回用户的主屏(见 AndroidScreen.returnIntruder)。该在这里的:目标 App 及其任务,挪回来的([adopted]),
 * 动作前后看到过的最上面的任务([known],模型自己操作出来的),系统的选择器、权限框([allowed]),Mika 自己和桌面。
 * Agent 正在做动作、或刚做完不到 [quietMs] 时不判:动作开出来的东西由动作自己的核实去认。
 * 设置里「别动这些 App」([leaveAlone])的不管怎么来的都送回去(目标 App 本身除外:那是模型明着打开的)。
 * 纯逻辑,线程安全。时间用同一个时钟(uptime)。
 */
internal class AgentScreenGuard(private val quietMs: Long = QUIET_MS) {
    /** Mika 屏上最上面的任务。 */
    data class Top(val taskId: Int, val packageName: String?)

    private val known = HashSet<Int>()
    private var acting = 0
    private var lastAction = Long.MIN_VALUE / 2

    /** 动作开始:那时最上面的任务算模型知道的。 */
    @Synchronized fun actionStarted(now: Long, top: Int) {
        acting++
        lastAction = now
        if (top >= 0) known += top
    }

    /** 动作做完:这时最上面的任务是动作带出来的。 */
    @Synchronized fun actionEnded(now: Long, top: Int) {
        if (acting > 0) acting--
        lastAction = now
        if (top >= 0) known += top
    }

    /** 送回去了:它再回来还要再送。 */
    @Synchronized fun forget(taskId: Int) { known -= taskId }

    /** 最上面的 [top] 该不该送回用户的主屏。 */
    @Synchronized fun intruder(
        now: Long,
        top: Top?,
        targetTask: Int,
        targetPackage: String?,
        adopted: Set<Int>,
        allowed: Set<String>,
        leaveAlone: Set<String>,
    ): Boolean {
        if (top == null || top.taskId < 0) return false
        val pkg = top.packageName ?: return false
        if (acting > 0 || now - lastAction < quietMs) return false
        if (top.taskId == targetTask || pkg == targetPackage) return false
        if (pkg in leaveAlone) return true
        if (pkg in allowed || top.taskId in adopted || top.taskId in known) return false
        return true
    }

    companion object {
        /** 动作做完后多久没动静才看:模型读屏、想下一步的时间远比它长。 */
        const val QUIET_MS = 2_500L
    }
}
