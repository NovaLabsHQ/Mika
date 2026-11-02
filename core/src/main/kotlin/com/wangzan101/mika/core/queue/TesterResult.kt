package com.wangzan101.mika.core.queue

import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次测试的总判定;只有 Agent 明说通过、每条标准都通过时才是 [Pass]。 */
enum class Verdict(val key: String, val header: String) {
    Pass("pass", "✅ PASS"),
    Fail("fail", "❌ FAIL"),
    Unverified("unverified", "⚠️ UNVERIFIED"),
    Timeout("timeout", "⏱️ TIMEOUT"),
    Error("error", "💥 ERROR"),
    ;

    /** 结果评论之后贴的标签:只有通过算办成。 */
    val passed get() = this == Pass
}

enum class CriterionResult { Pass, Fail, Unknown }

data class CriterionOutcome(val text: String, val result: CriterionResult, val note: String = "")

/** 从 Agent 最后的回答里读出来的判定。 */
data class TesterVerdict(val verdict: Verdict, val observed: String, val criteria: List<CriterionOutcome>) {
    companion object {
        // 只在一行之内认(不跨行吞下一条);行首、冒号前后允许 Markdown 的 ** 之类
        private val VERDICT = Regex("""(?im)^[^\w\n]*VERDICT[^\w\n]*:[^\w\n]*(PASS|FAIL)\b""")
        private val OBSERVED = Regex("""(?im)^[^\w\n]*OBSERVED[^\w\n]*:[ \t*]*(.+)$""")
        private val CRITERION = Regex("""(?im)^[^\w\n]*CRITERION[ \t]+(\d+)[^\w\n]*:[^\w\n]*(PASS|FAIL|UNKNOWN)\b[ \t*]*[-–—:]?[ \t]*(.*)$""")

        /**
         * 认格式,不猜:没有 VERDICT 行、标准缺了或不确定时,不会是通过。
         * 任一标准不通过就算没通过,哪怕 Agent 写了 PASS。
         */
        fun parse(answer: String, pass: List<String>): TesterVerdict {
            val said = VERDICT.findAll(answer).lastOrNull()?.groupValues?.get(1)?.uppercase()
            val observed = OBSERVED.findAll(answer).lastOrNull()?.groupValues?.get(1)?.trim()
                ?: answer.lines().filterNot { VERDICT.containsMatchIn(it) || CRITERION.containsMatchIn(it) }.joinToString(" ").trim().take(600)
            val found = HashMap<Int, Pair<CriterionResult, String>>()
            for (m in CRITERION.findAll(answer)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                found[n] = CriterionResult.valueOf(m.groupValues[2].lowercase().replaceFirstChar(Char::uppercase)) to m.groupValues[3].trim()
            }
            val criteria = pass.mapIndexed { i, text ->
                val (r, note) = found[i + 1] ?: (CriterionResult.Unknown to "not reported")
                CriterionOutcome(text, r, note)
            }
            val verdict = when {
                said == null -> Verdict.Unverified
                said == "FAIL" || criteria.any { it.result == CriterionResult.Fail } -> Verdict.Fail
                criteria.all { it.result == CriterionResult.Pass } -> Verdict.Pass
                else -> Verdict.Unverified
            }
            return TesterVerdict(verdict, observed, criteria)
        }
    }
}

/** 结果评论里链接的一张截图。 */
data class ScreenshotLink(val label: String, val url: String)

/** 结果评论的全部材料;[ResultFormatter] 只排版。 */
data class TesterReport(
    val runId: String,
    val spec: TesterTaskSpec,
    val verdict: Verdict,
    val observed: String,
    val criteria: List<CriterionOutcome>,
    val steps: List<String>,
    val actions: Int,
    val durationMs: Long,
    val appVersion: String?,
    val device: String,
    val screenshots: List<ScreenshotLink> = emptyList(),
    val screenshotNote: String? = null,
    val reason: String? = null,
    val deliveryId: String,
)

/**
 * 结果评论([docs/tester-design.md] §5.3):头一行判定,标准表,看到的,步骤(打进去的字不写),截图,设备一行,
 * 最后一行隐藏的 JSON 给机器读。所有自由文本先经 [Redactor.text]。
 */
object ResultFormatter {
    const val MAX_CHARS = 60_000

    fun format(r: TesterReport): String = buildString {
        val mins = r.durationMs / 60_000; val secs = (r.durationMs / 1000) % 60
        append("## ").append(r.verdict.header).append(" — ").append(clean(r.spec.title.ifBlank { r.spec.app })).append("\n\n")
        append("**App:** `").append(r.spec.app).append('`')
        r.appVersion?.let { append(" ").append(clean(it)) }
        append(" · **Run:** `").append(r.runId).append("` · ").append(mins).append("m ").append(secs).append("s · ").append(r.actions).append(" actions\n\n")
        r.reason?.let { append("> ").append(clean(it).replace("\n", "\n> ")).append("\n\n") }
        if (r.criteria.isNotEmpty()) {
            append("| # | Criterion | Result | Note |\n|---|---|---|---|\n")
            r.criteria.forEachIndexed { i, c ->
                val mark = when (c.result) { CriterionResult.Pass -> "✅"; CriterionResult.Fail -> "❌"; CriterionResult.Unknown -> "❔" }
                append("| ").append(i + 1).append(" | ").append(cell(c.text)).append(" | ").append(mark).append(" | ").append(cell(c.note)).append(" |\n")
            }
            append('\n')
        }
        if (r.observed.isNotBlank()) append("**Observed:** ").append(clean(r.observed)).append("\n\n")
        if (r.steps.isNotEmpty()) {
            append("<details><summary>Steps taken (").append(r.steps.size).append(")</summary>\n\n")
            r.steps.forEachIndexed { i, s -> append(i + 1).append(". ").append(clean(s)).append('\n') }
            append("\n</details>\n\n")
        }
        if (r.screenshots.isNotEmpty()) {
            append("**Screenshots:** ")
            append(r.screenshots.joinToString(" · ") { "[${it.label}](${it.url})" }).append("\n\n")
        }
        r.screenshotNote?.let { append("_").append(clean(it)).append("_\n\n") }
        append("<sub>").append(clean(r.device)).append("</sub>\n")
        val body = toString()
        setLength(0)
        append(if (body.length > MAX_CHARS) body.take(MAX_CHARS) + "\n…(truncated)\n" else body)
        append(hidden(r)).append('\n')
        append(Markers.delivery(r.deliveryId))
    }

    /** 没跑成(读不懂、没装、中断)时的短结果:同样带判定头、设备一行和隐藏 JSON。 */
    fun simple(verdict: Verdict, title: String, reason: String, runId: String, deliveryId: String, device: String): String = buildString {
        append("## ").append(verdict.header).append(" — ").append(clean(title.ifBlank { "Mika task" })).append("\n\n")
        append(clean(reason)).append("\n\n")
        append("<sub>").append(clean(device)).append(" · run `").append(runId).append("`</sub>\n")
        append(Markers.result(buildJsonObject {
            put("v", 1); put("run", runId); put("verdict", verdict.key)
            put("criteria", JsonArray(emptyList())); put("delivery", deliveryId)
        }.toString())).append('\n')
        append(Markers.delivery(deliveryId))
    }

    /** 隐藏的机器可读一行;OpenBot 只读这一行就够。 */
    fun hidden(r: TesterReport): String {
        val json = buildJsonObject {
            put("v", 1)
            put("run", r.runId)
            put("verdict", r.verdict.key)
            put("criteria", JsonArray(r.criteria.map { JsonPrimitive(it.result.name.lowercase()) }))
            put("delivery", r.deliveryId)
        }
        return Markers.result(json.toString())
    }

    private fun clean(s: String) = Redactor.text(s).replace("<!--", "&lt;!--").replace("-->", "--&gt;")
    private fun cell(s: String) = clean(s).replace("|", "\\|").replace("\n", " ").take(300)
}

/** 评论里的隐藏标记:认得哪条是 Mika 发的、发过没有(重试时不重复发)。 */
object Markers {
    fun delivery(id: String) = "<!-- mika:delivery $id -->"
    fun claim(device: String, run: String) = "<!-- mika:claim device=$device run=$run -->"
    fun ask(id: String, run: String) = "<!-- mika:ask id=$id run=$run -->"
    fun result(json: String) = "<!-- mika:result $json -->"
    const val STATUS = "<!-- mika:status -->"

    private val DELIVERY = Regex("""<!-- mika:delivery (\S+) -->""")
    private val CLAIM = Regex("""<!-- mika:claim device=(\S+) run=(\S+) -->""")
    private val RESULT = Regex("""<!-- mika:result (\{.*\}) -->""")

    fun isMika(body: String) = body.contains("<!-- mika:")
    fun deliveryOf(body: String): String? = DELIVERY.find(body)?.groupValues?.get(1)
    fun claimOf(body: String): Pair<String, String>? = CLAIM.find(body)?.let { it.groupValues[1] to it.groupValues[2] }
    fun resultOf(body: String): String? = RESULT.find(body)?.groupValues?.get(1)
}
