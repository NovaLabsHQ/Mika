package com.wangzan101.mika.cap.phone.a11y

/**
 * 一次运行里从用户主屏挪回来的 App 的账:同一个 App 最多挪一次;挪回来(或 Mika 屏上本来有)的任务后来离开了 Mika 的屏
 * (用户在自己屏上打开了它、接管),这个 App 这次运行里就不再挪。设置里「别动这些 App」([leaveAlone])的从来不挪。
 * 不许挪的照样报给模型([MainScreenSpill.InUse]),只是留在用户屏上。「别动这些 App」里的、这次运行里认出会自己开出来的
 * ([selfLaunched])报成 [MainScreenSpill.OnItsOwn]:告诉模型它是自己开的、接着做,不说用户在用。纯逻辑,线程安全。
 */
internal class PullBackLedger(private val leaveAlone: () -> Set<String> = { emptySet() }) {
    private val pulled = HashMap<String, Int>()
    private val left = HashSet<String>()
    private val selfLaunchers = HashSet<String>()

    /** 这个 App 此刻还能挪回 Mika 的屏。 */
    @Synchronized fun allows(pkg: String): Boolean = pkg !in pulled && pkg !in left && pkg !in selfLaunchers && pkg !in leaveAlone()

    /** [pkg] 会自己开出来(自己冒到过 Mika 的屏上,或判过一次 [MainScreenSpill.OnItsOwn]):这次运行里从不挪它。 */
    @Synchronized fun selfLaunched(pkg: String) { selfLaunchers += pkg }

    /** 这次运行里已知会自己开出来的 App。 */
    @Synchronized fun selfLaunchers(): Set<String> = selfLaunchers.toSet()

    /** 已把 [pkg] 的任务 [taskId] 挪回来。 */
    @Synchronized fun pulled(pkg: String, taskId: Int) { pulled.putIfAbsent(pkg, taskId) }

    /** [pkg] 的任务离开了 Mika 的屏:这个 App 这次运行里不再挪。 */
    @Synchronized fun left(pkg: String) { left += pkg }

    /** 挪回来过、还该看着它在不在 Mika 屏上的任务(包 → 任务)。 */
    @Synchronized fun watched(): Map<String, Int> = pulled.filterKeys { it !in left }

    /**
     * 判出要挪的,按账改成只报不挪:「别动这些 App」里的、已知会自己开出来的、挪回来过一次又自己冒出来的报成自己开的
     * ([MainScreenSpill.OnItsOwn]);离开过 Mika 屏的(用户拿走了)报成用户在用([MainScreenSpill.InUse])。判成自己开的,记下来,这次运行里不再挪。
     */
    fun limit(v: MainScreenSpill.Verdict): MainScreenSpill.Verdict {
        if (v is MainScreenSpill.OnItsOwn) { v.arrival.packageName?.let(::selfLaunched); return v }
        if (v !is MainScreenSpill.PullBack) return v
        val pkg = v.arrival.packageName ?: return v
        return when {
            pkg in leaveAlone() -> MainScreenSpill.OnItsOwn(v.arrival, MainScreenSpill.SelfLaunch.LeaveAlone)
            // 挪回来过一次又自己冒出来(判出 PullBack 说明用户没动手、也没在用):是自己会开出来的,记下来
            synchronized(this) { pkg in selfLaunchers || (pkg in pulled && pkg !in left) } -> {
                selfLaunched(pkg)
                MainScreenSpill.OnItsOwn(v.arrival, MainScreenSpill.SelfLaunch.Known)
            }
            allows(pkg) -> v
            else -> MainScreenSpill.InUse(v.arrival)
        }
    }
}
