package com.wangzan101.mika.core.queue

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 从运行记录(events.jsonl)整理出结果评论要的步骤、动作数和截图编号。 */
data class RunDigest(val steps: List<String>, val actions: Int, val screens: List<Int>) {
    companion object {
        const val MAX_STEPS = 40
        /** 这些参数的值是打进去的字或可能含个人信息:只写「(已隐藏)」。 */
        private val HIDDEN = setOf("text", "value", "input", "password", "content", "body", "message", "keys")
        private val SHOWN = listOf("package", "app", "target", "label", "element", "id", "direction", "key", "url", "query", "x", "y", "seconds")

        fun of(events: List<RunEvent>): RunDigest {
            val args = events.filterIsInstance<RunEvent.ModelReply>().filter { it.acted && it.tool != null }.associateBy { it.step to it.tool }
            val results = events.filterIsInstance<RunEvent.ToolResult>()
            val lines = results.map { r -> describe(r.tool, args[r.step to r.tool]?.argsJson) }
            val shown = if (lines.size > MAX_STEPS) lines.take(MAX_STEPS) + "… ${lines.size - MAX_STEPS} more" else lines
            return RunDigest(shown, results.size, results.mapNotNull { it.screenshotId }.distinct())
        }

        fun describe(tool: String, argsJson: String?): String {
            val obj = argsJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
            val parts = mutableListOf<String>()
            for ((k, v) in obj) {
                if (k.lowercase() in HIDDEN) parts += "$k=(hidden)"
                else if (k in SHOWN && v is JsonPrimitive) parts += "$k=${Redactor.text(v.content).take(80)}"
            }
            return if (parts.isEmpty()) tool else "$tool(${parts.joinToString(", ")})"
        }
    }
}

/** 按任务的截图方式选哪几张传;都按时间顺序,最后一张总在。 */
object ScreenshotPicker {
    const val MAX = 40
    const val FAILURE_TAIL = 3

    fun pick(screens: List<Int>, capture: Capture, passed: Boolean, stepCount: Int): List<Int> {
        if (screens.isEmpty()) return emptyList()
        return when (capture) {
            Capture.None -> emptyList()
            Capture.Final -> listOf(screens.last())
            Capture.Failures -> if (passed) emptyList() else screens.takeLast(FAILURE_TAIL)
            Capture.Steps -> sample(screens, (stepCount + 1).coerceIn(1, MAX))
            Capture.EveryAction -> sample(screens, MAX)
        }
    }

    /** 均匀取 [n] 张,首尾都在。 */
    fun sample(screens: List<Int>, n: Int): List<Int> {
        if (screens.size <= n) return screens
        if (n == 1) return listOf(screens.last())
        return (0 until n).map { i -> screens[(i.toLong() * (screens.size - 1) / (n - 1)).toInt()] }.distinct()
    }
}
