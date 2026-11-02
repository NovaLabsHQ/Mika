package com.wangzan101.mika.core.queue

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryRecord
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.MemoryOutboxStore
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存里的看板:记下每次改动,够跑状态机。 */
class FakeQueue : TaskQueue {
    override val id = "github:o/r"
    val items = linkedMapOf<Int, QueueItem>()
    val comments = linkedMapOf<Int, MutableList<QueueComment>>()
    private var nextId = 100L
    var now = 1_000_000L
    var failNextComment = false

    fun issue(n: Int, author: QueueUser, body: String, labels: Set<String> = setOf(BoardLabels.TASK)) {
        items[n] = QueueItem(n, "Task $n", body, author, labels)
    }
    fun say(n: Int, author: QueueUser, body: String): Long {
        val id = nextId++
        comments.getOrPut(n) { mutableListOf() } += QueueComment(id, author, body, now)
        return id
    }
    fun labels(n: Int) = items.getValue(n).labels
    fun bodies(n: Int) = comments[n].orEmpty().map { it.body }

    override suspend fun queued() = Fetched.Changed(items.values.filter { it.open && BoardLabels.TASK in it.labels })
    override suspend fun inProgress() = items.values.filter { it.open && BoardLabels.IN_PROGRESS in it.labels }
    override suspend fun item(number: Int) = Fetched.Changed(items.getValue(number))
    override suspend fun comments(number: Int, sinceMs: Long?) = comments[number].orEmpty().filter { sinceMs == null || it.createdAtMs >= sinceMs }
    override suspend fun addComment(number: Int, body: String): Long {
        if (failNextComment) { failNextComment = false; say(number, ME, body); throw java.io.IOException("timeout after write") }
        return say(number, ME, body)
    }
    override suspend fun editComment(commentId: Long, body: String) {
        for (list in comments.values) list.replaceAll { if (it.id == commentId) it.copy(body = body) else it }
    }
    override suspend fun addLabels(number: Int, labels: Set<String>) { items[number] = items.getValue(number).let { it.copy(labels = it.labels + labels) } }
    override suspend fun removeLabel(number: Int, label: String) { items[number] = items.getValue(number).let { it.copy(labels = it.labels - label) } }
    override suspend fun ensureLabels() = Unit
    override fun itemUrl(number: Int) = "https://github.com/o/r/issues/$number"

    companion object { val ME = QueueUser(1, "owner") }
}

class QueueChannelTest {
    private val owner = FakeQueue.ME
    private val stranger = QueueUser(2, "stranger")
    private val spec = "```mika-task\napp: com.example.app\nsteps:\n - Open it\npass:\n - It opens\n```"

    private class Harness(val queue: FakeQueue, deviceId: String = "dev1", installed: String? = "1.0") {
        val owners = BoardOwners(listOf(AllowedUser("owner", 1)))
        val channel = QueueChannel(queue, owners, deviceId, "Pixel", { installed },
            newRunId = { "run${++runs}" }, clock = { queue.now }, progressIntervalMs = 0)
        var runs = 0
        val log = mutableListOf<String>()
        val gateway = ChannelGateway(owners, { channel.openAsk(it) }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { log += "new:${peer.threadId}" }
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean { log += "answer:$text"; return true }
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean { log += "confirm"; return true }
            override suspend fun stop(runId: RunId) { log += "stop:${runId.value}" }
            override suspend fun cancelAsk(threadId: String) { log += "cancelAsk" }
        })
        init { gateway.start(channel) }
    }

    private fun harness(queue: FakeQueue = FakeQueue(), installed: String? = "1.0") =
        Harness(queue, installed = installed)

    @Test fun claimsAskAnswersAndFinishes() = runBlocking {
        val h = harness()
        h.queue.issue(5, owner, spec)
        assertEquals(QueueChannel.Poll.Started, h.channel.pollOnce(canStart = true))
        assertEquals(listOf("new:gh-o-r-5"), h.log)
        assertTrue(BoardLabels.IN_PROGRESS in h.queue.labels(5))
        assertFalse(BoardLabels.TASK in h.queue.labels(5))
        val peer = h.channel.active!!.peer

        val ask = Delivery.Ask("ask-d1", peer.threadId, "a1", Delivery.Ask.Kind.Input, "Which account?")
        val outbox = Outbox()
        assertEquals(DeliveryState.Delivered, outbox.deliver(h.channel, peer, ask).state)
        assertTrue(BoardLabels.NEEDS_INPUT in h.queue.labels(5))
        assertEquals(QueueChannel.Poll.Waiting, h.channel.pollOnce(true))
        // 不在名单上的人回答不算
        h.queue.say(5, stranger, "use mine")
        assertEquals(QueueChannel.Poll.Waiting, h.channel.pollOnce(true))
        h.queue.say(5, owner, "/answer test@example.com")
        assertEquals(QueueChannel.Poll.Answered, h.channel.pollOnce(true))
        assertEquals("answer:test@example.com", h.log.last())
        assertFalse(BoardLabels.NEEDS_INPUT in h.queue.labels(5))

        val text = ResultFormatter.simple(Verdict.Pass, "Task 5", "ok", "run1", "res-1", "Pixel")
        outbox.deliver(h.channel, peer, Delivery.Result("res-1", peer.threadId, text))
        assertTrue(BoardLabels.DONE in h.queue.labels(5))
        assertFalse(BoardLabels.IN_PROGRESS in h.queue.labels(5))
        assertNull(h.channel.active)
    }

    @Test fun ignoresStrangersAndRejectsBadSpecs() = runBlocking {
        val h = harness(installed = null)
        h.queue.issue(1, stranger, spec)
        h.queue.issue(2, owner, "no block")
        h.queue.issue(3, owner, spec)
        assertEquals(QueueChannel.Poll.Rejected, h.channel.pollOnce(true))
        assertEquals(emptyList<String>(), h.queue.bodies(1))
        assertEquals(setOf(BoardLabels.TASK), h.queue.labels(1))
        assertTrue(BoardLabels.FAILED in h.queue.labels(2))
        assertEquals(QueueChannel.Poll.Rejected, h.channel.pollOnce(true))
        assertTrue(h.queue.bodies(3).single().contains("not installed"))
        assertEquals(QueueChannel.Poll.Idle, h.channel.pollOnce(true))
        assertTrue(h.log.isEmpty())
    }

    @Test fun doesNotStartWhenGated() = runBlocking {
        val h = harness()
        h.queue.issue(1, owner, spec)
        assertEquals(QueueChannel.Poll.Idle, h.channel.pollOnce(canStart = false))
        assertTrue(h.queue.bodies(1).isEmpty())
    }

    @Test fun closingTheIssueStopsTheRun() = runBlocking {
        val h = harness()
        h.queue.issue(1, owner, spec)
        h.channel.pollOnce(true)
        h.queue.items[1] = h.queue.items.getValue(1).copy(open = false)
        assertEquals(QueueChannel.Poll.Cancelled, h.channel.pollOnce(true))
        assertEquals("stop:run1", h.log.last())
        // 取消后的结果不再评论
        val peer = h.channel.active!!.peer
        val before = h.queue.bodies(1).size
        h.channel.send(peer, Delivery.Result("res", peer.threadId, "late"))
        assertEquals(before, h.queue.bodies(1).size)
        assertNull(h.channel.active)
    }

    @Test fun secondDeviceBacksOff() = runBlocking {
        val q = FakeQueue()
        q.issue(1, owner, spec, setOf(BoardLabels.TASK))
        // 另一台手机先留了领取标记
        q.say(1, owner, "picked up\n\n" + Markers.claim("other", "runX"))
        val h = Harness(q)
        assertEquals(QueueChannel.Poll.BackedOff, h.channel.pollOnce(true))
        assertTrue(h.log.isEmpty())
        assertTrue(q.bodies(1).last().contains("backed off"))
    }

    @Test fun resultIsPostedOnceAndReconciled() = runBlocking {
        val h = harness()
        h.queue.issue(1, owner, spec)
        h.channel.pollOnce(true)
        val peer = h.channel.active!!.peer
        val store = MemoryOutboxStore()
        val outbox = Outbox(store)
        val text = ResultFormatter.simple(Verdict.Fail, "Task 1", "no", "run1", "res-9", "Pixel")
        // 写进去了但回包丢了:记成结果不明
        h.queue.failNextComment = true
        assertEquals(DeliveryState.Uncertain, outbox.deliver(h.channel, peer, Delivery.Result("res-9", peer.threadId, text)).state)
        val n = h.queue.bodies(1).count { Markers.deliveryOf(it) == "res-9" }
        assertEquals(1, n)
        val after = h.channel.reconcile(outbox)
        assertEquals(DeliveryState.Delivered, after.single().state)
        assertEquals(1, h.queue.bodies(1).count { Markers.deliveryOf(it) == "res-9" })
        assertTrue(BoardLabels.FAILED in h.queue.labels(1))
        // 同一条投递再发一次也不重复评论
        h.channel.send(peer, Delivery.Notice("n1", peer.threadId, "hello"))
        h.channel.send(peer, Delivery.Notice("n1", peer.threadId, "hello"))
        assertEquals(1, h.queue.bodies(1).count { Markers.deliveryOf(it) == "n1" })
    }

    @Test fun reconcileResendsMissingResult() = runBlocking {
        val q = FakeQueue()
        q.issue(1, owner, spec, setOf(BoardLabels.IN_PROGRESS))
        val h = Harness(q)
        val peer = ChannelPeer(h.channel.id, "1", h.channel.threadOf(1))
        val store = MemoryOutboxStore()
        val text = ResultFormatter.simple(Verdict.Pass, "Task 1", "ok", "r", "res-2", "Pixel")
        store.save(DeliveryRecord(peer, Delivery.Result("res-2", peer.threadId, text), DeliveryState.Attempting))
        store.save(DeliveryRecord(peer, Delivery.Progress("p", peer.threadId, "x"), DeliveryState.Uncertain))
        val out = h.channel.reconcile(Outbox(store)).associate { it.delivery.id to it.state }
        assertEquals(DeliveryState.Delivered, out["res-2"])
        assertEquals(DeliveryState.Failed, out["p"])
        assertTrue(BoardLabels.DONE in q.labels(1))
    }

    @Test fun orphanFromThisDeviceIsClosedOut() = runBlocking {
        val q = FakeQueue()
        q.issue(1, owner, spec, setOf(BoardLabels.IN_PROGRESS))
        q.say(1, owner, Markers.claim("dev1", "old"))
        q.issue(2, owner, spec, setOf(BoardLabels.IN_PROGRESS))
        q.say(2, owner, Markers.claim("other", "x"))
        val h = Harness(q)
        assertEquals(listOf(1), h.channel.recoverOrphans())
        assertTrue(BoardLabels.FAILED in q.labels(1))
        assertTrue(BoardLabels.IN_PROGRESS in q.labels(2))
        assertEquals(1, q.bodies(1).count { Markers.resultOf(it) != null })
    }

    @Test fun allowlistByIdAndBots() {
        val owners = BoardOwners(listOf(AllowedUser("ci-bot[bot]", 9), AllowedUser("alice", 3)))
        assertTrue(owners.allows(QueueUser(3, "alice-renamed")))
        assertFalse(owners.allows(QueueUser(4, "alice")))
        assertTrue(owners.allows(QueueUser(9, "ci-bot[bot]", isBot = true)))
        assertFalse(owners.allows(QueueUser(3, "evil[bot]", isBot = true)))
    }

    @Test fun dailyCap() = runBlocking {
        val q = FakeQueue()
        val ch = QueueChannel(q, BoardOwners(listOf(AllowedUser("owner", 1))), "d", "P", { "1" }, clock = { q.now }, dailyCap = 1)
        ch.start { }
        q.issue(1, owner, spec); q.issue(2, owner, spec)
        assertEquals(QueueChannel.Poll.Started, ch.pollOnce(true))
        val peer = ch.active!!.peer
        ch.send(peer, Delivery.Result("r", peer.threadId, ResultFormatter.simple(Verdict.Pass, "t", "ok", "x", "r", "P")))
        assertEquals(QueueChannel.Poll.Capped, ch.pollOnce(true))
    }
}
