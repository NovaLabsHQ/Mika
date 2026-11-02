package com.wangzan101.mika.board

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.queue.BoardTask
import com.wangzan101.mika.core.queue.Capture
import com.wangzan101.mika.core.queue.CriterionResult
import com.wangzan101.mika.core.queue.RunDigest
import com.wangzan101.mika.core.queue.TesterTaskSpec
import com.wangzan101.mika.core.queue.Verdict
import com.wangzan101.mika.core.run.ChannelPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardReportsTest {
    private val spec = TesterTaskSpec(7, "Login", "com.example.shop", listOf("Open the app"), listOf("Email field visible", "No crash"), timeoutMin = 10)
    private val task = BoardTask(7, spec, ChannelPeer("github", "1", "gh-o-r-7"), "r1", 0L, "2.4")
    private val digest = RunDigest(listOf("open_app"), 3, listOf(1, 2))

    @Test fun passWhenEveryCriterionPasses() {
        val end = TaskState.Succeeded("VERDICT: PASS\nOBSERVED: fine\nCRITERION 1: PASS - shown\nCRITERION 2: PASS - ok")
        val r = BoardReports.report(task, end, BoardReports.Stop.None, digest, 5_000, "Pixel")
        assertEquals(Verdict.Pass, r.verdict)
        assertEquals(listOf(CriterionResult.Pass, CriterionResult.Pass), r.criteria.map { it.result })
        assertEquals("board-result-r1", r.deliveryId)
        assertEquals("2.4", r.appVersion)
        assertEquals(3, r.actions)
    }

    @Test fun claimedPassWithoutEveryCriterionIsUnverified() {
        val r = BoardReports.report(task, TaskState.Succeeded("VERDICT: PASS\nCRITERION 1: PASS"), BoardReports.Stop.None, digest, 1, "d")
        assertEquals(Verdict.Unverified, r.verdict)
    }

    @Test fun timeoutWinsOverEndState() {
        val r = BoardReports.report(task, TaskState.Cancelled, BoardReports.Stop.Timeout, digest, 1, "d")
        assertEquals(Verdict.Timeout, r.verdict)
        assertTrue(r.reason!!.contains("10"))
        assertTrue(r.criteria.all { it.result == CriterionResult.Unknown })
    }

    @Test fun failuresAndPhoneStopsAreErrors() {
        assertEquals(Verdict.Error, BoardReports.verdictOf(task, TaskState.Failed("model broke", FailKind.GaveUp), BoardReports.Stop.None).first)
        val (v, _, reason) = BoardReports.verdictOf(task, TaskState.Cancelled, BoardReports.Stop.Phone)
        assertEquals(Verdict.Error, v)
        assertTrue(reason!!.contains("phone"))
    }

    @Test fun artifactsPickFailureTailAndSkipUnreadable() {
        val art = RunArtifacts(events = { error("not used") }, screen = { _, id -> if (id == 2) null else byteArrayOf(id.toByte()) })
        val d = RunDigest(emptyList(), 0, listOf(1, 2, 3, 4))
        val shots = art.shots("r1", d, Capture.Failures, passed = false, stepCount = 1)
        assertEquals(listOf("screen 3/4", "final"), shots.map { it.first })
        assertTrue(art.shots("r1", d, Capture.Failures, passed = true, stepCount = 1).isEmpty())
        assertTrue(art.shots(null, d, Capture.Final, passed = false, stepCount = 1).isEmpty())
    }

    @Test fun artifactsDigestSurvivesBrokenRecord() {
        val art = RunArtifacts(events = { error("gone") }, screen = { _, _ -> null })
        assertEquals(0, art.digest("r1").actions)
        assertEquals(0, art.digest(null).steps.size)
    }

    @Test fun pacingAdaptsAndBacksOff() {
        val p = BoardPacing { 120_000L }
        assertEquals(120_000L, p.next(busy = false, waiting = false))
        assertEquals(BoardPacing.BUSY_MS, p.next(busy = true, waiting = false))
        assertEquals(BoardPacing.WAITING_MS, p.next(busy = true, waiting = true))
        val first = p.failed()
        val second = p.failed()
        assertTrue(second > first)
        repeat(20) { p.failed() }
        assertEquals(BoardPacing.MAX_BACKOFF_MS, p.failed())
        p.ok()
        assertEquals(5 * 60_000L, p.failed(retryAfterMs = 5 * 60_000L))
        assertEquals(BoardPacing.FLOOR_MS, BoardPacing { 1L }.next(false, false))
    }
}
