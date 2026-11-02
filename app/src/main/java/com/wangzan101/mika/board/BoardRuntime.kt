package com.wangzan101.mika.board

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.queue.BoardOwners
import com.wangzan101.mika.core.queue.BoardTask
import com.wangzan101.mika.core.queue.QueueChannel
import com.wangzan101.mika.core.queue.QueueException
import com.wangzan101.mika.core.queue.ResultFormatter
import com.wangzan101.mika.core.queue.github.FileEtagStore
import com.wangzan101.mika.core.queue.github.GitHubApi
import com.wangzan101.mika.core.queue.github.GitHubArtifacts
import com.wangzan101.mika.core.queue.github.GitHubBoard
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.AllowedUserJson
import com.wangzan101.mika.settings.BoardConfig
import com.wangzan101.mika.settings.BoardSettings
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 任务看板(GitHub issues)在手机上的一份:设置、看板渠道([QueueChannel])和网关([ChannelGateway])。
 * 轮询由 [BoardService] 按门槛和间隔调 [poll];领到的任务经 [TaskService.startChannel] 跑(来源记成渠道、有人可回),
 * 收尾时 [TaskService] 调 [ended],这里整理成结果评论(带截图链接)经发件箱([com.wangzan101.mika.core.run.Outbox])发出去。
 * 令牌只在发请求时从 [BoardSettings] 读,不进日志。
 */
class BoardRuntime(private val app: MikaApp, val settings: BoardSettings, private val clock: () -> Long = System::currentTimeMillis) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Session(
        val config: BoardConfig, val api: GitHubApi, val board: GitHubBoard, val artifacts: GitHubArtifacts,
        val channel: QueueChannel, val inbound: AutoCloseable,
    ) {
        @Volatile var reconciled = false
    }

    @Volatile private var session: Session? = null
    /** 超时被这里停掉的、在手机上被停的运行编号:收尾时判成超时 / 手机上停的。 */
    private val timedOut = HashSet<String>()
    /** 已经交给任务服务的运行编号和交出的时刻;交出去很久都没登记上的按没开始成算。 */
    private val handedOff = HashMap<String, Long>()
    private val runArtifacts by lazy {
        val store = RunStore(app)
        RunArtifacts(store::events) { id, n -> store.screen(id, n)?.readBytes() }
    }

    val enabled: Boolean get() = BuildConfig.TESTER_BOARD && settings.config.value.enabled

    val device: String get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · Mika ${BuildConfig.VERSION_NAME}"

    @Synchronized private fun session(): Session? {
        val c = settings.config.value
        val current = session
        if (!c.ready || settings.token() == null) {
            if (current?.channel?.active == null) { current?.inbound?.close(); session = null }
            return session
        }
        if (current != null && (current.config == c || current.channel.active != null)) return current
        current?.inbound?.close()
        return build(c).also { session = it }
    }

    private fun build(c: BoardConfig): Session {
        val api = GitHubApi(token = settings::token, etags = FileEtagStore(File(app.filesDir, "board/etags.json")))
        val board = GitHubBoard(api, c.owner, c.repo)
        val artifacts = GitHubArtifacts(api, c.artifactsOwner, c.artifactsName, BitmapShrinker)
        val channel = QueueChannel(board, BoardOwners(c.users), c.deviceId, device, ::installedVersion)
        val gateway = ChannelGateway(BoardOwners(c.users), channel::openAsk, route(channel))
        return Session(c, api, board, artifacts, channel, gateway.start(channel))
    }

    private fun route(channel: QueueChannel) = object : ChannelGateway.Route {
        override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) {
            val task = channel.task(peer.threadId) ?: return
            synchronized(this@BoardRuntime) { handedOff[task.runId] = clock() }
            try { TaskService.startChannel(app, text, task.runId, task.spec.attendance) }
            catch (e: Exception) {
                Log.w(TAG, "could not start board task", e)
                ended(task.runId, TaskState.Failed("Mika could not start the task on the phone.", FailKind.Environment), null)
            }
        }

        override suspend fun answer(threadId: String, askId: String, text: String): Boolean {
            val task = channel.task(threadId) ?: return false
            val run = app.taskState.runs.value.firstOrNull { it.boardExternalId == task.runId } ?: return false
            run.answer(text) {}
            return true
        }

        // 看板不能确认(渠道 confirms = false,网关不会走到这里)
        override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false

        override suspend fun stop(runId: RunId) {
            if (app.taskState.runs.value.none { it.boardExternalId == runId.value }) ended(runId.value, TaskState.Cancelled, null)
            else TaskService.cancelChannel(app, runId.value)
        }

        override suspend fun cancelAsk(threadId: String) = Unit
    }

    private fun installedVersion(pkg: String): String? = try {
        app.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** 有任务在看板上跑着(等回答、等收尾)。轮询服务据此缩短间隔、手机忙也接着看。 */
    val busy: Boolean get() = session?.channel?.active != null
    val waitingForAnswer: Boolean get() = session?.channel?.waitingForAnswer == true

    /**
     * 轮询一次。[canStart]:门槛都满足、手机空闲,可以领新任务。没配好返回 null。
     * 网络、限流、令牌错误照样抛出(QueueException),由服务退避或停下。
     */
    suspend fun poll(canStart: Boolean): QueueChannel.Poll? {
        val s = session() ?: return null
        if (!s.reconciled) {
            s.channel.recoverOrphans()
            s.channel.reconcile(app.outbox)
            s.reconciled = true
        }
        watchdog(s)
        val r = s.channel.pollOnce(canStart)
        if (r == QueueChannel.Poll.Started) s.reconciled = true
        return r
    }

    /** 跑过了 timeout_min 的停掉;交出去两分钟还没登记上的按没开始成收尾。 */
    private fun watchdog(s: Session) {
        val t = s.channel.active ?: return
        val now = clock()
        val running = app.taskState.runs.value.any { it.boardExternalId == t.runId }
        if (now - t.startedAtMs > t.spec.timeoutMin * 60_000L) {
            val first = synchronized(this) { timedOut.add(t.runId) }
            if (first) {
                if (running) TaskService.cancelChannel(app, t.runId)
                else ended(t.runId, TaskState.Cancelled, null)
            }
            return
        }
        val handed = synchronized(this) { handedOff[t.runId] }
        if (!running && handed != null && now - handed > LOST_MS) {
            synchronized(this) { handedOff.remove(t.runId) }
            ended(t.runId, TaskState.Failed("The task never started on the phone.", FailKind.Environment), null)
        }
    }

    /** 提问同时在手机上和 issue 里出现,哪边先答算哪边。不是看板任务返回 null。 */
    fun interaction(runId: String, local: Interaction): Interaction? {
        val s = session ?: return null
        val task = s.channel.active?.takeIf { it.runId == runId } ?: return null
        val remote = ChannelInteraction(app.outbox, s.channel, task.peer)
        return object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                val here = local.publish(ask)
                val there = try { remote.publish(ask) } catch (e: CancellationException) { throw e } catch (_: Exception) { DeliveryState.Uncertain }
                return if (there == DeliveryState.Delivered) there else here
            }
            override fun resolved(askId: String) { local.resolved(askId); remote.resolved(askId) }
        }
    }

    fun askTimeoutMs(runId: String): Long? =
        session?.channel?.active?.takeIf { it.runId == runId }?.spec?.askTimeoutMin?.let { it * 60_000L }

    /** 任务收尾([TaskService] 调;[recordRunId] 是运行记录的编号,排队时就取消的没有)。在后台整理、上传、发评论。 */
    fun ended(runId: String, end: TaskState, recordRunId: String?) {
        val s = session ?: return
        val task = s.channel.active?.takeIf { it.runId == runId } ?: return
        val stop = synchronized(this) {
            handedOff.remove(runId)
            when {
                timedOut.remove(runId) -> BoardReports.Stop.Timeout
                end is TaskState.Cancelled && !s.channel.cancelled -> BoardReports.Stop.Phone
                else -> BoardReports.Stop.None
            }
        }
        scope.launch {
            runCatching { deliver(s, task, end, stop, recordRunId) }.onFailure {
                if (it is CancellationException) throw it
                // 没发成:下次轮询先对账(发件箱里记着),再发一次
                s.reconciled = false
                Log.w(TAG, "board result not delivered: ${it::class.simpleName}")
            }
        }
    }

    private suspend fun deliver(s: Session, task: BoardTask, end: TaskState, stop: BoardReports.Stop, recordRunId: String?) {
        val digest = runArtifacts.digest(recordRunId)
        val draft = BoardReports.report(task, end, stop, digest, clock() - task.startedAtMs, device)
        var links = emptyList<com.wangzan101.mika.core.queue.ScreenshotLink>()
        var note: String? = null
        if (!s.channel.cancelled) {
            val shots = runArtifacts.shots(recordRunId, digest, task.spec.capture, draft.verdict.passed, task.spec.steps.size)
            if (shots.isNotEmpty()) try {
                val up = s.artifacts.upload(task.number, task.runId, shots)
                links = up.links
                if (up.skipped > 0) note = "${up.skipped} screenshot(s) skipped (size cap)."
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                note = "Screenshots could not be uploaded (${(e as? QueueException)?.kind ?: e::class.simpleName})."
            }
        }
        val text = ResultFormatter.format(draft.copy(screenshots = links, screenshotNote = note))
        val record = app.outbox.deliver(s.channel, task.peer, Delivery.Result(BoardReports.deliveryId(task.runId), task.peer.threadId, text, title = task.spec.title))
        if (record.state != DeliveryState.Delivered) s.reconciled = false
    }

    /** 「测试连接」:名单换成数字编号存下、仓库和 issues 读得到、标签补齐、截图仓库能推。返回给人看的一段话(英文,不含令牌)。 */
    suspend fun testConnection(): Result<String> = runCatching {
        val c = settings.config.value
        require(BoardConfig.validName(c.owner) && BoardConfig.validName(c.repo)) { tr(R.string.board_err_repo) }
        require(settings.token() != null) { tr(R.string.board_err_token) }
        val logins = c.logins()
        require(logins.isNotEmpty()) { tr(R.string.board_err_users) }
        val api = GitHubApi(token = settings::token)
        val users = GitHubBoard.resolveUsers(api, logins)
        settings.update { it.copy(allowedUsers = users.map { u -> AllowedUserJson(u.login, u.id) }) }
        val board = GitHubBoard(api, c.owner, c.repo)
        val info = board.check()
        require(info.hasIssues) { tr(R.string.board_err_issues, info.fullName) }
        board.ensureLabels()
        val art = GitHubArtifacts(api, c.artifactsOwner, c.artifactsName, BitmapShrinker)
        val push = runCatching { art.check() }.getOrNull()
        buildString {
            val shots = "${c.artifactsOwner}/${c.artifactsName}"
            append(tr(if (info.private) R.string.board_test_ok_private else R.string.board_test_ok_public,
                info.fullName, users.joinToString { "${it.login} (#${it.id})" }))
            append(' ').append(tr(when (push) { true -> R.string.board_shots_ok; false -> R.string.board_shots_denied; null -> R.string.board_shots_unknown }, shots))
            info.tokenExpiration?.let { append(' ').append(tr(R.string.board_token_expires, it)) }
        }
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw IllegalStateException(describe(e), e)
    }

    companion object {
        private const val TAG = "MikaBoard"
        private const val LOST_MS = 2 * 60_000L

        /** 错误换成给人看的话;不带令牌、不带响应正文。 */
        fun describe(e: Throwable): String = when ((e as? QueueException)?.kind) {
            QueueException.Kind.Auth -> tr(R.string.board_err_auth)
            QueueException.Kind.Forbidden -> tr(R.string.board_err_forbidden)
            QueueException.Kind.NotFound -> tr(R.string.board_err_not_found)
            QueueException.Kind.RateLimited -> tr(R.string.board_err_rate)
            QueueException.Kind.Network -> tr(R.string.board_err_network)
            QueueException.Kind.Http -> tr(R.string.board_err_http, (e as QueueException).status?.toString() ?: "?")
            null -> e.message ?: e::class.simpleName.orEmpty()
        }
    }
}
