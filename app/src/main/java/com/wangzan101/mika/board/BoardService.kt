package com.wangzan101.mika.board

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.queue.QueueChannel
import com.wangzan101.mika.core.queue.QueueException
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.BoardStatus
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 任务看板打开期间的前台服务:按间隔轮询 GitHub([BoardRuntime.poll])。
 * 领新任务的门槛:手机空闲(没有别的任务)、解锁且亮屏(可关)、不按流量计费的网络(可关);
 * 看板上已有任务在跑时只看它(回答、取消),不领新的。令牌被拒就停下,等用户在设置里换。
 * 关掉开关或点通知上的「暂停」就退出。
 */
class BoardService : Service() {
    private val runtime get() = (application as MikaApp).board
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
        }.onFailure { Log.w(TAG, "board service could not enter the foreground", it) }.isSuccess
        if (intent?.action == ACTION_PAUSE) runtime.settings.update { it.copy(enabled = false) }
        if (!foreground || !runtime.enabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val pacing = BoardPacing { runtime.settings.config.value.pollMinutes * 60_000L }
        while (scope.isActive && runtime.enabled) {
            val wait = try {
                if (!networkOk()) { status(false, tr(R.string.board_status_no_network)); pacing.next(false, false) }
                else {
                    val r = runtime.poll(canStart = canStart())
                    pacing.ok()
                    if (r == null) status(false, tr(R.string.board_status_not_ready))
                    else status(true, tr(R.string.board_status_ok, r.name))
                    pacing.next(runtime.busy, runtime.waitingForAnswer)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: QueueException) {
                status(false, BoardRuntime.describe(e))
                // 令牌坏了:不再试,等用户换(换了令牌、重开开关会重新起)
                if (e.kind == QueueException.Kind.Auth) break
                pacing.failed(e.retryAfterMs)
            } catch (e: Exception) {
                Log.w(TAG, "board poll failed: ${e::class.simpleName}")
                status(false, BoardRuntime.describe(e))
                pacing.failed()
            }
            delay(wait)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun status(ok: Boolean, message: String) =
        runtime.settings.setStatus(BoardStatus(System.currentTimeMillis(), ok, message))

    /** 能领新任务:手机上没有别的任务;要求解锁时得亮屏且没锁。 */
    private fun canStart(): Boolean {
        val app = application as MikaApp
        if (app.taskState.busy.value) return false
        if (runtime.settings.config.value.unlockedOnly) {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (power?.isInteractive != true || keyguard?.isDeviceLocked != false) return false
        }
        return true
    }

    private fun networkOk(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // 看板上已有任务在跑时不看网络类型,免得追问、取消收不到
        if (!runtime.settings.config.value.wifiOnly || runtime.busy) return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val c = runtime.settings.config.value
        val pause = PendingIntent.getService(this, 2, Intent(this, BoardService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.board_notif_title))
            .setContentText(tr(R.string.board_notif_body, "${c.owner}/${c.repo}"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.board_pause), pause).build())
            .build()
    }

    companion object {
        private const val TAG = "MikaBoard"
        /** 和任务通知、电脑连接(30_001)错开。 */
        private const val NOTIFICATION_ID = 30_002
        const val ACTION_PAUSE = "com.wangzan101.mika.board.PAUSE"

        /** 开关开着就起(已经在跑就不动);从后台起不了时记下,等下次打开界面再起。 */
        fun ensureRunning(context: Context) {
            val app = context.applicationContext as MikaApp
            if (!app.board.enabled) { context.stopService(Intent(context, BoardService::class.java)); return }
            runCatching { context.startForegroundService(Intent(context, BoardService::class.java)) }
                .onFailure { Log.w(TAG, "could not start board service: ${it::class.simpleName}") }
        }
    }
}
