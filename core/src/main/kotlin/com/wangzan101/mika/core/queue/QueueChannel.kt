package com.wangzan101.mika.core.queue

import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryRecord
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.OwnerIdentity
import com.wangzan101.mika.core.run.Reconcile
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** 允许交任务、回答追问的看板用户:按数字编号认人;机器人账号要连登录名一起列出才认。 */
data class AllowedUser(val login: String, val id: Long)

class BoardOwners(val allowed: List<AllowedUser>) : OwnerIdentity {
    private val ids = allowed.map { it.id }.toSet()

    fun allows(user: QueueUser): Boolean {
        val listed = allowed.firstOrNull { it.id == user.id } ?: return false
        return !user.isBot || listed.login.equals(user.login, ignoreCase = true)
    }

    override fun isOwner(peer: ChannelPeer): Boolean = peer.peerId.toLongOrNull()?.let { it in ids } == true
}

/** 正在跑的那条看板任务(一台手机同时只跑一条)。 */
data class BoardTask(
    val number: Int,
    val spec: TesterTaskSpec,
    val peer: ChannelPeer,
    val runId: String,
    val startedAtMs: Long,
    val installedVersion: String?,
)

/**
 * 把一个 [TaskQueue] 当成一个渠道([Channel]):轮询时把新任务、追问的回答、取消变成 [ChannelInput] 交给网关,
 * 发出的提问、结果、进度变成评论和标签。它不能确认付款([confirms] = false),确认一律在评论里说明已拒绝。
 *
 * 轮询由外面(手机上的前台服务)按门槛和间隔调 [pollOnce];所有对看板的请求排着一个个发,不并发。
 * 每条评论带 `<!-- mika:delivery <id> -->`,重发前先查,发过的不再发。
 */
class QueueChannel(
    private val queue: TaskQueue,
    private val owners: BoardOwners,
    private val deviceId: String,
    private val deviceLabel: String,
    private val installedVersion: (String) -> String?,
    private val newRunId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val dailyCap: Int = DAILY_CAP,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
    private val ownPackages: Set<String> = TesterTaskSpec.OWN_PACKAGES,
) : Channel {
    override val id: String get() = queue.id
    override val confirms: Boolean = false

    private class OpenAsk(val delivery: Delivery.Ask, val commentId: Long, val sinceMs: Long) {
        @Volatile var answered = false
    }

    private class Live(val task: BoardTask) {
        @Volatile var ask: OpenAsk? = null
        @Volatile var cancelled = false
        @Volatile var finishing = false
        @Volatile var statusCommentId: Long? = null
        @Volatile var lastProgressMs = 0L
    }

    enum class Poll { Idle, Started, Rejected, BackedOff, Running, Waiting, Answered, Cancelled, Capped }

    private val io = Mutex()
    @Volatile private var sink: ChannelInputSink? = null
    @Volatile private var live: Live? = null
    private var lastQueued: List<QueueItem> = emptyList()
    private val ignored = HashSet<Int>()
    private var day = -1L
    private var startedToday = 0

    private val threadPrefix = queue.id.replace("github:", "gh-").replace('/', '-').replace(':', '-') + "-"

    fun threadOf(number: Int) = threadPrefix + number
    fun numberOf(threadId: String): Int? = threadId.takeIf { it.startsWith(threadPrefix) }?.removePrefix(threadPrefix)?.toIntOrNull()

    /** 正在跑的任务;网关开新任务时据此知道运行编号和规格。 */
    val active: BoardTask? get() = live?.task
    fun task(threadId: String): BoardTask? = live?.task?.takeIf { it.peer.threadId == threadId }
    val cancelled: Boolean get() = live?.cancelled == true

    /** 网关查「这个对话在等哪个回答」。 */
    fun openAsk(threadId: String): Delivery.Ask? =
        live?.takeIf { it.task.peer.threadId == threadId }?.ask?.takeIf { !it.answered }?.delivery

    val waitingForAnswer: Boolean get() = live?.ask?.answered == false

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    override fun isAlive(peer: ChannelPeer): Boolean = numberOf(peer.threadId) != null

    private suspend fun <T> io(block: suspend () -> T): T = io.withLock { block() }

    /**
     * 轮询一次。有任务在跑时只看它(被关掉、取消了没有,追问有没有人答);
     * 没有任务且 [canStart](手机空闲、门槛都满足)时才去领新的。网络和限流错误照样抛给调用方去退避。
     */
    suspend fun pollOnce(canStart: Boolean): Poll {
        val s = sink ?: return Poll.Idle
        live?.let { return pollLive(it, s) }
        if (!canStart) return Poll.Idle
        val today = clock() / DAY_MS
        if (today != day) { day = today; startedToday = 0 }
        if (startedToday >= dailyCap) return Poll.Capped
        val items = when (val f = io { queue.queued() }) {
            is Fetched.Changed -> f.value.also { lastQueued = it }
            Fetched.Unchanged -> lastQueued
        }
        for (item in items) {
            if (item.number in ignored || BoardLabels.IN_PROGRESS in item.labels) continue
            // 不在名单上的人交的任务不碰:不评论、不改标签(免得被人刷)
            if (!owners.allows(item.author)) { ignored += item.number; continue }
            return claim(item, s)
        }
        return Poll.Idle
    }

    private suspend fun claim(item: QueueItem, s: ChannelInputSink): Poll {
        val n = item.number
        val runId = newRunId()
        val spec = when (val p = TesterTaskSpec.parse(n, item.title, item.body, ownPackages)) {
            is SpecParse.Invalid -> return reject(n, runId, item.title, "The task could not be read:", p.errors)
            is SpecParse.Ok -> p.spec
        }
        val version = installedVersion(spec.app)
        if (version == null) return reject(n, runId, item.title, "The app is not installed on the test phone:", listOf(spec.app))
        if (spec.minVersion != null && !TesterTaskSpec.versionAtLeast(version, spec.minVersion)) {
            return reject(n, runId, item.title, "The installed app is too old:", listOf("${spec.app} $version < ${spec.minVersion}"))
        }
        val claimedAt = clock()
        val claimId = io {
            queue.addLabels(n, setOf(BoardLabels.IN_PROGRESS))
            queue.removeLabel(n, BoardLabels.TASK)
            queue.addComment(n, "🤖 Mika picked this up on $deviceLabel (run `$runId`).\n\n${Markers.claim(deviceId, runId)}")
        }
        // 两台手机同时领了同一条:更早的那条领取标记不是自己的,就悄悄退出
        val earlier = io { queue.comments(n, claimedAt - CLAIM_WINDOW_MS) }
            .filter { it.id < claimId }
            .mapNotNull { Markers.claimOf(it.body) }
            .any { it.first != deviceId }
        if (earlier) {
            io { queue.editComment(claimId, "Another device claimed this first; this phone backed off.\n\n${Markers.claim(deviceId, "backed-off")}") }
            return Poll.BackedOff
        }
        startedToday++
        val peer = ChannelPeer(id, item.author.id.toString(), threadOf(n))
        val task = BoardTask(n, spec, peer, runId, claimedAt, version)
        live = Live(task)
        s.receive(ChannelInput.Text(peer, "gh-$n-$runId", spec.goal(version)))
        return Poll.Started
    }

    private suspend fun reject(n: Int, runId: String, title: String, why: String, details: List<String>): Poll {
        val text = ResultFormatter.simple(Verdict.Error, title, why + "\n" + details.joinToString("\n") { "- $it" }, runId, "reject-$n-$runId", deviceLabel)
        io {
            queue.addComment(n, text)
            queue.addLabels(n, setOf(BoardLabels.FAILED))
            queue.removeLabel(n, BoardLabels.TASK)
        }
        return Poll.Rejected
    }

    private suspend fun pollLive(l: Live, s: ChannelInputSink): Poll {
        val n = l.task.number
        if (l.cancelled) return Poll.Cancelled
        val changed = io { queue.item(n) }
        if (changed is Fetched.Changed && !l.finishing) {
            val item = changed.value
            if (!item.open || BoardLabels.IN_PROGRESS !in item.labels) {
                l.cancelled = true
                s.receive(ChannelInput.Stop(l.task.peer, RunId(l.task.runId)))
                io { queue.addComment(n, "🛑 Cancelled: the issue was ${if (!item.open) "closed" else "unlabelled"}, so Mika stopped run `${l.task.runId}`.\n\n${Markers.delivery("cancel-${l.task.runId}")}") }
                return Poll.Cancelled
            }
        }
        val ask = l.ask ?: return Poll.Running
        if (ask.answered) return Poll.Running
        val reply = io { queue.comments(n, ask.sinceMs - CLOCK_SKEW_MS) }
            .firstOrNull { it.id > ask.commentId && !Markers.isMika(it.body) && owners.allows(it.author) }
            ?: return Poll.Waiting
        val text = reply.body.trim().removePrefix("/answer").trim()
        s.receive(ChannelInput.Text(ChannelPeer(id, reply.author.id.toString(), l.task.peer.threadId), "gh-comment-${reply.id}", text, replyToAskId = ask.delivery.askId))
        ask.answered = true
        io { queue.removeLabel(n, BoardLabels.NEEDS_INPUT) }
        return Poll.Answered
    }

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        val n = numberOf(peer.threadId) ?: return SendOutcome.Failed
        val l = live?.takeIf { it.task.number == n }
        val runId = l?.task?.runId ?: "-"
        when (delivery) {
            is Delivery.Ask -> when (delivery.kind) {
                Delivery.Ask.Kind.Input -> {
                    val minutes = l?.task?.spec?.askTimeoutMin ?: TesterTaskSpec.DEFAULT_ASK_TIMEOUT_MIN
                    val body = "**Mika needs input:** ${clean(delivery.text)}\n\n" +
                        "Reply with a comment on this issue (you may start it with `/answer`). Mika waits up to $minutes min, then carries on without an answer.\n\n" +
                        Markers.ask(delivery.askId, runId) + "\n" + Markers.delivery(delivery.id)
                    val commentId = existing(n, delivery.id, l) ?: io { queue.addComment(n, body) }
                    io { queue.addLabels(n, setOf(BoardLabels.NEEDS_INPUT)) }
                    l?.ask = OpenAsk(delivery, commentId, clock())
                }
                Delivery.Ask.Kind.Confirm -> comment(n, delivery.id, l,
                    "⛔ Mika reached a step that needs a confirmation (${clean(delivery.text)}). Board tasks cannot confirm anything, so it was declined.")
                Delivery.Ask.Kind.Takeover -> comment(n, delivery.id, l,
                    "🙋 Mika needs a person at the phone: ${clean(delivery.text)}")
            }
            is Delivery.Result -> {
                if (l != null) {
                    if (l.cancelled) { finish(l); return SendOutcome.Delivered }
                    l.finishing = true
                }
                comment(n, delivery.id, l, delivery.text, raw = true)
                resultLabels(n, delivery.text)
                l?.let(::finish)
            }
            is Delivery.Progress -> {
                if (l == null || l.finishing || l.cancelled) return SendOutcome.Delivered
                val now = clock()
                if (now - l.lastProgressMs < progressIntervalMs) return SendOutcome.Delivered
                l.lastProgressMs = now
                val body = "${Markers.STATUS}\n⏳ ${clean(delivery.text)}"
                val sid = l.statusCommentId
                if (sid == null) l.statusCommentId = io { queue.addComment(n, body) } else io { queue.editComment(sid, body) }
            }
            is Delivery.Notice -> comment(n, delivery.id, l, clean(delivery.text))
        }
        return SendOutcome.Delivered
    }

    private fun finish(l: Live) { if (live === l) live = null }

    /** 这条投递发过没有:查本次任务开始以来的评论里有没有它的标记。 */
    private suspend fun existing(n: Int, deliveryId: String, l: Live?): Long? =
        io { queue.comments(n, l?.task?.startedAtMs?.minus(CLOCK_SKEW_MS)) }.firstOrNull { Markers.deliveryOf(it.body) == deliveryId }?.id

    private suspend fun comment(n: Int, deliveryId: String, l: Live?, text: String, raw: Boolean = false) {
        if (existing(n, deliveryId, l) != null) return
        val body = if (raw && Markers.deliveryOf(text) == deliveryId) text else text + "\n\n" + Markers.delivery(deliveryId)
        io { queue.addComment(n, body) }
    }

    /** 按结果评论里隐藏的判定换标签:通过贴 done,其余贴 failed;进行中、等回答都摘掉。 */
    private suspend fun resultLabels(n: Int, text: String) {
        val verdict = Markers.resultOf(text)?.let { runCatching { Json.parseToJsonElement(it).jsonObject["verdict"]?.jsonPrimitive?.content }.getOrNull() }
        io {
            queue.addLabels(n, setOf(if (verdict == Verdict.Pass.key) BoardLabels.DONE else BoardLabels.FAILED))
            queue.removeLabel(n, BoardLabels.IN_PROGRESS)
            queue.removeLabel(n, BoardLabels.NEEDS_INPUT)
        }
    }

    /**
     * 开始轮询前调一次:进行中却没人在跑、领取标记是本机的,是上次进程没了留下的,记成出错交代一声。
     * 别的手机领的不碰。
     */
    suspend fun recoverOrphans(): List<Int> {
        val mine = mutableListOf<Int>()
        for (item in io { queue.inProgress() }) {
            if (live?.task?.number == item.number) continue
            val claim = io { queue.comments(item.number) }.mapNotNull { Markers.claimOf(it.body) }.lastOrNull() ?: continue
            if (claim.first != deviceId || claim.second == "backed-off") continue
            val text = ResultFormatter.simple(Verdict.Error, item.title, "The run was interrupted (Mika stopped or the phone restarted). Re-add `${BoardLabels.TASK}` to try again.",
                claim.second, "orphan-${item.number}-${claim.second}", deviceLabel)
            comment(item.number, "orphan-${item.number}-${claim.second}", null, text, raw = true)
            resultLabels(item.number, text)
            mine += item.number
        }
        return mine
    }

    /**
     * 上次没弄清发没发出去的投递:评论里有标记的记成已送达(结果的标签再贴一遍);
     * 没有的,结果和告知重发一次,提问和进度(任务已不在)作废。
     */
    suspend fun reconcile(outbox: Outbox): List<DeliveryRecord> = outbox.reconcile(this) { r ->
        val n = numberOf(r.peer.threadId) ?: return@reconcile Reconcile.Drop
        val found = io { queue.comments(n) }.any { Markers.deliveryOf(it.body) == r.delivery.id }
        when {
            found -> { (r.delivery as? Delivery.Result)?.let { resultLabels(n, it.text) }; Reconcile.Delivered }
            r.delivery is Delivery.Result || r.delivery is Delivery.Notice -> Reconcile.Resend
            else -> Reconcile.Drop
        }
    }

    private fun clean(s: String) = s.replace("<!--", "&lt;!--").replace("-->", "--&gt;")

    companion object {
        const val DAILY_CAP = 30
        const val PROGRESS_INTERVAL_MS = 60_000L
        /** 领取后回头看这段时间内的领取标记。 */
        const val CLAIM_WINDOW_MS = 10 * 60_000L
        /** 手机和 GitHub 的钟差一点也不漏评论。 */
        const val CLOCK_SKEW_MS = 2 * 60_000L
        private const val DAY_MS = 86_400_000L
    }
}
