package com.wangzan101.mika.cap.phone.a11y

/**
 * 判 Agent 的一个动作是不是让一个 App 开到了用户的主屏上。App 不是从自己在 Mika 屏上的页面、而是从后台
 * (服务、通知的跳转、系统的选择器、跨用户的转交)打开另一个 App 时,系统按默认放到主屏;Mika 的屏上看不出变化,
 * 模型会以为没点上、一遍遍再点。认出来就挪回 Mika 的屏(见 AndroidScreen)。
 *
 * 只看动作开始之后一小段时间里主屏最上面换上来的任务(内置助手记下的,见 MainScreenLog)。不算:
 * 不在这段时间里的;桌面、最近任务一类的页面,Mika 自己,桌面 App([excluded]);从别的屏挪过来的(接管、别的任务的接管);
 * 此刻已不在主屏上的;这段时间里用户在主屏上点过东西(说不清是谁打开的,当作用户自己开的);
 * 动作之前就在主屏最上面的那个任务又回到最上面(挪走开出来的那个后露出来的)。
 * 动作之前主屏最上面的任务里换了页面:是用户正在用的那个 App 被打开了,开在了用户眼前,不去动它([InUse])。
 * 冒出来的任务用户已经在点了(点的那个窗口属于它):也是用户在用,不挪。
 * 用户在主屏上回了桌面、开了最近任务或下拉了通知栏(桌面、最近任务一类的任务冒上来,或 [userTaps] 里记下的那类窗口变化):
 * 也算用户在动手,之后冒出来的当作用户自己开的。
 * 本来就在用户主屏后台的任务被拉到前台([from] 为 [MAIN],这段时间里没有作为新任务出现过):是用户手上的 App,
 * 不把它从用户眼前抽走,只报 [InUse];这种情况只认动作开始后 [backgroundWindowMs] 之内的(更晚的多半是用户自己切过去的)。
 * 本该挪回的,先按 [selfLaunched] 看是不是 App 自己开出来的(Instacart Shopper 一类自己弹出新窗口的):是就不挪,
 * 判 [OnItsOwn],告诉模型它是自己开的、接着做。
 * 纯逻辑。
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** 主屏冒到最上面的一个任务;[from] 上一次看到它时在哪块屏(之前没见过为 [NEW]),[now] 此刻在哪块屏(不在了为 -1)。 */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** 用户在主屏上点了一下:[at] 时刻,[packageName] 是点的那一下所在的包(读不到为 null)。 */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** 动作开到主屏上的:挪回 Mika 的屏。 */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** 开到了用户正在主屏上用着的那个 App 里:不动它,告诉模型。 */
    data class InUse(override val arrival: Arrival) : Verdict
    /** 不是这个动作开的,是 App 自己开出来的([why] 凭什么认的):不挪,告诉模型它自己开的、接着做,不用交给用户。 */
    data class OnItsOwn(override val arrival: Arrival, val why: SelfLaunch) : Verdict

    /** 认出「App 自己开出来」的依据,按 [selfLaunched] 里的先后。 */
    enum class SelfLaunch {
        /** 设置里「别动这些 App」里的(由 [PullBackLedger.limit] 判)。 */
        LeaveAlone,
        /** 这次运行里已知会自己开出来的:自己冒到过 Mika 的屏上被送回去,或之前已判过一次自己开的。 */
        Known,
        /** 冒出来之前不久(或同时)它发过通知:全屏通知、来单提醒一类,App 自己把页面拉起来。 */
        Notified,
        /** 这个动作是打开、分享给另一个 App:冒出来的既不是那个 App,也不是系统的。 */
        NotTheTarget,
        /** 动作做完之后过了 [SelfSignals.lateMs] 才冒出来(动作自己开出来的,任务在一两秒内就上来)。 */
        AfterTheAction,
    }

    /**
     * 判「App 自己开出来」要的信号(都可以没有):[expected] 这个动作要打开、分享给的 App(点按等不知道开谁的为 null);
     * [known] 这次运行里已知会自己开出来的 App;[notified] 各 App 最近一次发通知的时刻;[actionEnded] 动作做完的时刻;
     * [system] 是不是系统的包(系统的选择器、弹窗另有处理,从不按这里判)。
     */
    data class SelfSignals(
        val expected: String? = null,
        val known: Set<String> = emptySet(),
        val notified: Map<String, Long> = emptyMap(),
        val actionEnded: Long? = null,
        val system: (String) -> Boolean = { false },
        val notifyLeadMs: Long = NOTIFY_LEAD_MS,
        val notifySlackMs: Long = NOTIFY_SLACK_MS,
        val lateMs: Long = LATE_MS,
    ) {
        companion object { val NONE = SelfSignals() }
    }

    /** 冒出来之前多久内发过通知算它自己拉起来的。 */
    const val NOTIFY_LEAD_MS = 10_000L
    /** 通知可能比页面晚一点到:冒出来之后这么久内的也算。 */
    const val NOTIFY_SLACK_MS = 1_500L
    /** 动作做完之后过了这么久才冒出来的,当作自己开的。 */
    const val LATE_MS = 3_000L

    /**
     * 本该挪回的 [a] 是不是 App 自己开出来的(不是这个动作开的);不是为 null。系统的包从不算(选择器、系统弹窗另有处理)。
     * 依次看:这次运行里已知会自己开([SelfLaunch.Known]);冒出来前 [SelfSignals.notifyLeadMs] 到之后 [SelfSignals.notifySlackMs]
     * 内发过通知([SelfLaunch.Notified]);动作要开的是另一个 App([SelfLaunch.NotTheTarget]);
     * 动作做完 [SelfSignals.lateMs] 之后才冒出来([SelfLaunch.AfterTheAction])。用户动过手的、用户正用着的在这之前就判掉了。
     */
    fun selfLaunched(a: Arrival, s: SelfSignals): SelfLaunch? {
        val pkg = a.packageName ?: return null
        if (s.system(pkg)) return null
        if (pkg in s.known) return SelfLaunch.Known
        s.notified[pkg]?.let { t -> if (t in a.at - s.notifyLeadMs..a.at + s.notifySlackMs) return SelfLaunch.Notified }
        if (s.expected != null && pkg != s.expected) return SelfLaunch.NotTheTarget
        if (s.actionEnded != null && a.at > s.actionEnded + s.lateMs) return SelfLaunch.AfterTheAction
        return null
    }

    /**
     * [since] 动作开始的时刻,[windowMs] 之后冒出来的不算这个动作的;[topBefore] 动作开始时主屏最上面的任务;
     * [userTaps] 用户在主屏上点东西(或回桌面、开最近任务、亲手拉下通知栏;冒出来的通知横幅不算,见 [MainScreenInput])的时刻和包。
     * [self] 判「App 自己开出来」的信号(见 [selfLaunched])。同一个任务只出一条(取最后一次),按先后排。
     * [backgroundInUse]:本来就在主屏后台的任务被拉到前台时只报不挪(见类说明);用户在选择框里选中的那个([picked])不走这条。
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        backgroundWindowMs: Long = windowMs,
        backgroundInUse: Boolean = true,
        self: SelfSignals = SelfSignals.NONE,
    ): List<Verdict> {
        val out = LinkedHashMap<Int, Verdict>()
        val sorted = arrivals.sortedBy { it.at }
        // 这段时间里作为新任务冒出来过的:之后再回到最上面仍是这次动作开出来的那个,不算用户后台里的
        val fresh = HashSet<Int>()
        for (a in sorted) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps, sorted, fresh, backgroundWindowMs, backgroundInUse, self)
            if (a.from == NEW && a.at in since..since + windowMs) fresh += a.taskId
            v ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    /**
     * 用户在 [since] 到 [until] 之间在主屏上动了手:点过东西(含回桌面、开最近任务、下拉通知栏的窗口变化),
     * 或桌面、最近任务一类的任务冒到了最上面(动作之前就在最上面的 [topBefore] 又露出来不算:那是挪走别的之后露出来的)。
     */
    fun userActed(since: Long, until: Long, userTaps: List<Tap>, arrivals: List<Arrival>, topBefore: Int): Boolean =
        userTaps.any { it.at in since..until } ||
            arrivals.any { !it.standard && it.taskId != topBefore && it.at >= since && it.at < until }

    enum class Handling {
        /** 挪回 Mika 的屏(只挪任务,打开它的仍是原来那个 App)。 */
        Move,
        /** 系统在问用户打开哪一个:不挪,留给用户在自己屏上选,任务等着。 */
        LeaveForUser,
    }

    /**
     * 开到主屏上的这个怎么办:系统包的窗口,而且是系统的选择器、或窗口里的选项对得上装了两份的 App([optionsMatch]),
     * 就是在问打开哪一个,留给用户;其余挪回。Mika 从不替别的 App 重新打开它。
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * 用户在主屏的选择框里选完(选择框在 [gone] 时已经不在)后开出来的那个:选择框之后冒到主屏上的(不算选择框自己 [chooserTask]
     * 和它出来前主屏最上面的 [topBefore]);用户刚点过选项,不按「用户点过」排除。看选择框关没关是隔一会儿看一次的,
     * 关掉之前 [slackMs] 内冒出来的也算。没有为 null。
     */
    fun picked(arrivals: List<Arrival>, chooserTask: Int, gone: Long, slackMs: Long, waitMs: Long, topBefore: Int, excluded: Set<String>): Arrival? =
        // 选中的就是用户本来那份后台任务时也要挪回来:是用户刚为这一步选的
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList(), backgroundInUse = false)
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * 选出来的 App 要用户亲手处理:窗口里有密码框,或树里读不出东西而主屏截图报防截屏窗口(付款、输密码一类页面常这样)。
     * 只是树读不出(有的 App 树本来就很少)不算。
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    private fun verdict(
        a: Arrival, since: Long, windowMs: Long, topBefore: Int, excluded: Set<String>, userTaps: List<Tap>,
        arrivals: List<Arrival>, fresh: Set<Int>, backgroundWindowMs: Long, backgroundInUse: Boolean, self: SelfSignals,
    ): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        if (userActed(since, a.at, userTaps, arrivals, topBefore)) return null
        // 用户已经在点它了(出现之后点在这一个 App 的窗口上):留给用户,不挪
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        // 用户主屏后台本来就有的任务被拉到前台:那是用户手上的 App,不从用户眼前抽走
        if (backgroundInUse && a.from == MAIN && a.taskId !in fresh) {
            return if (a.at > since + backgroundWindowMs) null else InUse(a)
        }
        return selfLaunched(a, self)?.let { OnItsOwn(a, it) } ?: PullBack(a)
    }
}
