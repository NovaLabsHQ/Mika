package com.wangzan101.mika.cap.phone.a11y

/**
 * 一次运行里从用户主屏挪回来的 App 的账:同一个 App 最多挪一次;挪回来(或 Mika 屏上本来有)的任务后来离开了 Mika 的屏
 * (用户在自己屏上打开了它、接管),这个 App 这次运行里就不再挪。设置里「别动这些 App」([leaveAlone])的从来不挪。
 * 不许挪的照样报给模型([MainScreenSpill.InUse]),只是留在用户屏上。纯逻辑,线程安全。
 */
internal class PullBackLedger(private val leaveAlone: () -> Set<String> = { emptySet() }) {
    private val pulled = HashMap<String, Int>()
    private val left = HashSet<String>()

    /** 这个 App 此刻还能挪回 Mika 的屏。 */
    @Synchronized fun allows(pkg: String): Boolean = pkg !in pulled && pkg !in left && pkg !in leaveAlone()

    /** 已把 [pkg] 的任务 [taskId] 挪回来。 */
    @Synchronized fun pulled(pkg: String, taskId: Int) { pulled.putIfAbsent(pkg, taskId) }

    /** [pkg] 的任务离开了 Mika 的屏:这个 App 这次运行里不再挪。 */
    @Synchronized fun left(pkg: String) { left += pkg }

    /** 挪回来过、还该看着它在不在 Mika 屏上的任务(包 → 任务)。 */
    @Synchronized fun watched(): Map<String, Int> = pulled.filterKeys { it !in left }

    /** 判出要挪的,按账改成只报不挪:这个 App 挪过了、离开过 Mika 的屏,或在「别动这些 App」里。 */
    fun limit(v: MainScreenSpill.Verdict): MainScreenSpill.Verdict {
        if (v !is MainScreenSpill.PullBack) return v
        val pkg = v.arrival.packageName ?: return v
        return if (allows(pkg)) v else MainScreenSpill.InUse(v.arrival)
    }
}
