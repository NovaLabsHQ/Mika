package com.wangzan101.mika.cap.phone.a11y

/**
 * 主屏上的一条无障碍事件算不算用户亲手在动(判开到主屏上的是谁开的时用,见 [MainScreenSpill.userActed])。
 *
 * 算:点按、长按(用户点的);桌面 App 的窗口换了(回桌面、开最近任务);系统界面的窗口换了而且看得出是通知栏、快捷设置、
 * 最近任务被打开了(类名里有这些字样、事件文字整句是这类窗口的标题,或那个窗口盖住了大半块屏)。
 * 不算:Mika 自己的事件;Mika 刚自己挪过任务后一小会儿的窗口变化;冒出来的通知横幅(系统界面里一条窄窄的窗口,
 * 或刚发过通知、看不出是通知栏被拉下来的那一下)、音量条一类系统小窗;通知事件本身。
 * 以前系统界面的任何窗口变化都算用户在动,通知横幅一冒就挡掉挪回,这里分开。纯逻辑。
 */
internal object MainScreenInput {
    // 与 AccessibilityEvent 的常量同值:纯逻辑不依赖 Android 类
    const val TYPE_VIEW_CLICKED = 0x1
    const val TYPE_VIEW_LONG_CLICKED = 0x2
    const val TYPE_WINDOW_STATE_CHANGED = 0x20
    const val TYPE_NOTIFICATION_STATE_CHANGED = 0x40

    /** 系统界面的窗口盖住屏高的这么多以上,算通知栏(或最近任务)整个拉开了。 */
    const val EXPANDED_SHARE = 0.6f
    /** 刚发过通知后这么久内,看不出是通知栏的系统界面窗口变化当作通知横幅。 */
    const val HEADS_UP_MS = 2_000L

    /** 类名里看得出是通知栏、快捷设置、最近任务的字样(不分大小写)。 */
    private val SHADE_OR_RECENTS_CLASS = Regex("shade|notificationpanel|quicksettings|qspanel|recents|overview", RegexOption.IGNORE_CASE)

    /**
     * 事件文字整句是通知栏、快捷设置、最近任务的窗口标题(不分大小写)。只认整句:通知横幅的事件文字是通知内容,
     * 里面可能恰好有 overview 一类的词。
     */
    private val SHADE_OR_RECENTS_TITLE = Regex(
        "(notification shade|notifications|quick settings|recents|recent apps|overview|通知栏|通知欄|快捷设置|快速設定|最近任务|最近使用的应用|最近使用的應用程式)\\.?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 一条主屏事件:[type] 事件类型,[packageName] 所在的包,[className] 事件的类名,[text] 事件文字(窗口标题等),
     * [heightShare] 事件所在窗口占屏高的比例(读不到为 null),[sinceNotificationMs] 离最近一次有 App 发通知过了多久(没有为 null),
     * [sinceOwnMoveMs] 离 Mika 最近一次自己挪任务过了多久。
     */
    data class Event(
        val type: Int,
        val packageName: String?,
        val className: String? = null,
        val text: String? = null,
        val heightShare: Float? = null,
        val sinceNotificationMs: Long? = null,
        val sinceOwnMoveMs: Long = Long.MAX_VALUE,
    )

    /** [e] 是不是用户亲手在主屏上动:[launchers] 桌面 App,[systemUi] 系统界面的包,[own] Mika 自己,[ownMoveQuietMs] 自己挪任务后不算的时长。 */
    fun isUserInput(e: Event, launchers: Set<String>, systemUi: String, own: String, ownMoveQuietMs: Long): Boolean {
        val pkg = e.packageName
        if (pkg == own) return false
        return when (e.type) {
            TYPE_VIEW_CLICKED, TYPE_VIEW_LONG_CLICKED -> true
            TYPE_WINDOW_STATE_CHANGED -> when {
                pkg == null || e.sinceOwnMoveMs <= ownMoveQuietMs -> false
                pkg in launchers -> true
                pkg == systemUi -> userOpenedSystemUi(e)
                else -> false
            }
            else -> false
        }
    }

    /**
     * 系统界面的窗口换了,是不是用户拉开了通知栏、快捷设置或最近任务:字样对得上就算;
     * 刚发过通知([HEADS_UP_MS] 内)又看不出字样的,是通知横幅;否则窗口盖住大半块屏([EXPANDED_SHARE])才算。
     */
    fun userOpenedSystemUi(e: Event): Boolean {
        if (e.className?.let(SHADE_OR_RECENTS_CLASS::containsMatchIn) == true) return true
        if (e.text?.trim()?.let(SHADE_OR_RECENTS_TITLE::matches) == true) return true
        if (e.sinceNotificationMs != null && e.sinceNotificationMs <= HEADS_UP_MS) return false
        return (e.heightShare ?: 0f) >= EXPANDED_SHARE
    }
}
