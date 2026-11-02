package com.wangzan101.mika.board

import com.wangzan101.mika.core.queue.Capture
import com.wangzan101.mika.core.queue.RunDigest
import com.wangzan101.mika.core.queue.ScreenshotPicker
import com.wangzan101.mika.core.record.RunEvent

/**
 * 看板结果要的运行记录:步骤摘要从 `files/runs/<id>/events.jsonl` 来,截图从 `files/runs/<id>/screens/` 读原图。
 * 只读,不改运行记录。[events]、[screen] 在 App 里接 [com.wangzan101.mika.record.RunStore],测试里给假的。
 */
class RunArtifacts(
    private val events: (String) -> List<RunEvent>,
    private val screen: (String, Int) -> ByteArray?,
) {
    fun digest(runId: String?): RunDigest =
        runId?.let { runCatching { RunDigest.of(events(it)) }.getOrNull() } ?: RunDigest(emptyList(), 0, emptyList())

    /** 按任务的截图方式选好、读出原图;读不出来的跳过。说明写「第几步的画面」。 */
    fun shots(runId: String?, digest: RunDigest, capture: Capture, passed: Boolean, stepCount: Int): List<Pair<String, ByteArray>> {
        if (runId == null) return emptyList()
        val picked = ScreenshotPicker.pick(digest.screens, capture, passed, stepCount)
        val total = digest.screens.size
        return picked.mapNotNull { id ->
            val bytes = runCatching { screen(runId, id) }.getOrNull() ?: return@mapNotNull null
            val at = digest.screens.indexOf(id) + 1
            (if (id == digest.screens.lastOrNull()) "final" else "screen $at/$total") to bytes
        }
    }
}
