package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.a11y.MainScreenInput.Event
import com.wangzan101.mika.cap.phone.a11y.MainScreenInput.TYPE_NOTIFICATION_STATE_CHANGED
import com.wangzan101.mika.cap.phone.a11y.MainScreenInput.TYPE_VIEW_CLICKED
import com.wangzan101.mika.cap.phone.a11y.MainScreenInput.TYPE_VIEW_LONG_CLICKED
import com.wangzan101.mika.cap.phone.a11y.MainScreenInput.TYPE_WINDOW_STATE_CHANGED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenInputTest {
    private val launchers = setOf("com.home")
    private val ui = "com.android.systemui"
    private val own = "com.wangzan101.mika"

    private fun user(e: Event) = MainScreenInput.isUserInput(e, launchers, ui, own, ownMoveQuietMs = 800)

    @Test fun tapsAreUserInput() {
        assertTrue(user(Event(TYPE_VIEW_CLICKED, "com.chat")))
        assertTrue(user(Event(TYPE_VIEW_LONG_CLICKED, "com.chat")))
        // 系统界面里的点按(点通知、点快捷开关)也是用户亲手点的
        assertTrue(user(Event(TYPE_VIEW_CLICKED, ui, sinceNotificationMs = 100)))
    }

    @Test fun homeAndRecentsAreUserInput() {
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, "com.home")))
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "com.android.systemui.recents.RecentsActivity")))
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, text = "Recent apps")))
    }

    @Test fun shadeOpenedByTheUserIsUserInput() {
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "com.android.systemui.shade.NotificationShadeWindowView")))
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, text = "Notification shade.")))
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, text = "Quick settings")))
        // 刚来过通知,但用户随即把通知栏整个拉了下来
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", text = "Notification shade", sinceNotificationMs = 300)))
        // 看不出字样,但窗口盖住了大半块屏(拉开的通知栏)
        assertTrue(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", heightShare = 1f)))
    }

    @Test fun headsUpNotificationIsNotUserInput() {
        // 回归:以前系统界面的任何窗口变化都算用户在动,通知横幅一冒就挡掉了挪回
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", text = "New batch available", heightShare = 0.15f, sinceNotificationMs = 200)))
        // 读不到窗口大小,但刚来过通知
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", sinceNotificationMs = 200)))
        // 通知内容里恰好有 overview 一类的词也不算
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", text = "Your weekly overview is ready", heightShare = 0.15f, sinceNotificationMs = 200)))
        // 通知事件本身不算
        assertFalse(user(Event(TYPE_NOTIFICATION_STATE_CHANGED, "com.instacart.shopper")))
    }

    @Test fun smallSystemWindowsAreNotUserInput() {
        // 音量条、电量提示一类的系统小窗
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, className = "android.widget.FrameLayout", text = "Volume", heightShare = 0.3f)))
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui)))
    }

    @Test fun mikasOwnEventsAndOwnMovesAreNotUserInput() {
        assertFalse(user(Event(TYPE_VIEW_CLICKED, own)))
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, "com.home", sinceOwnMoveMs = 300)))
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, ui, text = "Recent apps", sinceOwnMoveMs = 300)))
    }

    @Test fun otherAppsWindowChangesAreNotUserInput() {
        // App 自己换页面不算(用户在主屏上点开的已经由点按记下)
        assertFalse(user(Event(TYPE_WINDOW_STATE_CHANGED, "com.instacart.shopper")))
    }
}
