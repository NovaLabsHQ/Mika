package com.wangzan101.mika.board

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.queue.BoardTask
import com.wangzan101.mika.core.queue.CriterionOutcome
import com.wangzan101.mika.core.queue.CriterionResult
import com.wangzan101.mika.core.queue.RunDigest
import com.wangzan101.mika.core.queue.ScreenshotLink
import com.wangzan101.mika.core.queue.TesterReport
import com.wangzan101.mika.core.queue.TesterVerdict
import com.wangzan101.mika.core.queue.Verdict

/** 任务的终态换成结果评论([TesterReport]);不碰网络和 Android,单独测。 */
object BoardReports {
    /** 怎么收的尾:正常、超时被停、手机上有人停了。看板上关掉 issue 的不走这里(渠道自己交代过)。 */
    enum class Stop { None, Timeout, Phone }

    fun verdictOf(task: BoardTask, end: TaskState, stop: Stop): Triple<Verdict, TesterVerdict?, String?> = when {
        stop == Stop.Timeout -> Triple(Verdict.Timeout, null, "Stopped after ${task.spec.timeoutMin} min (`timeout_min`).")
        end is TaskState.Succeeded -> {
            val v = TesterVerdict.parse(end.answer, task.spec.pass)
            Triple(v.verdict, v, if (v.verdict == Verdict.Unverified) "Mika's answer did not report every criterion as PASS." else null)
        }
        end is TaskState.Failed -> Triple(Verdict.Error, null, end.reason)
        end is TaskState.Cancelled -> Triple(Verdict.Error, null, if (stop == Stop.Phone) "Stopped on the phone." else "The run was cancelled.")
        else -> Triple(Verdict.Error, null, "The run ended unexpectedly.")
    }

    fun report(
        task: BoardTask, end: TaskState, stop: Stop, digest: RunDigest, durationMs: Long, device: String,
        screenshots: List<ScreenshotLink> = emptyList(), screenshotNote: String? = null,
    ): TesterReport {
        val (verdict, parsed, reason) = verdictOf(task, end, stop)
        val criteria = parsed?.criteria ?: task.spec.pass.map { CriterionOutcome(it, CriterionResult.Unknown, "not checked") }
        return TesterReport(
            runId = task.runId, spec = task.spec, verdict = verdict,
            observed = parsed?.observed.orEmpty(), criteria = criteria,
            steps = digest.steps, actions = digest.actions, durationMs = durationMs,
            appVersion = task.installedVersion, device = device,
            screenshots = screenshots, screenshotNote = screenshotNote, reason = reason,
            deliveryId = deliveryId(task.runId),
        )
    }

    fun deliveryId(runId: String) = "board-result-$runId"
}
