package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun okObj(b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("ok", true); b() }
internal fun ok(b: JsonObjectBuilder.() -> Unit) = ToolExecution(okObj(b))

internal fun fail(error: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
    put("ok", false); put("error", error); put("message", message); put("recoverable", recoverable)
})

internal fun invalid(e: ArgError) = fail("invalid_arguments", e.message ?: "invalid arguments")

/** 按错误码给的现成说明。 */
internal fun failCode(code: String) = fail(code, FAILURE_MESSAGES.getValue(code))

/** 前台换成了 [pkg]:目标 App 自己打开的、之前从用户主屏挪回来的([pulledBack],不一定是目标 App 打开的)、外来打断,各说各的。 */
internal fun switchHint(pkg: String, byTarget: Boolean, pulledBack: Boolean = false) = when {
    byTarget -> Feedback.format("switch.by_target", "pkg" to pkg)
    pulledBack -> Feedback.format("switch.pulled_back", "pkg" to pkg)
    else -> Feedback.format("switch.interruption", "pkg" to pkg)
}

/** 没经这个任务打开、冒到 Mika 屏上又送回用户主屏的 App;没有为 null。 */
internal fun returnedHint(packages: List<String>): String? =
    packages.distinct().takeIf { it.isNotEmpty() }?.let { Feedback.format("intruder.returned", "pkg" to it.joinToString(", ")) }

internal val HINT_TAP_UNCHANGED: String get() = Feedback["tap_unchanged"]
internal val HINT_PROTECTED: String get() = Feedback["protected"]
internal val HINT_PAYMENT_TAKEOVER: String get() = Feedback["payment_takeover"]
internal val HINT_UNSUPPORTED_BROWSER: String get() = Feedback["unsupported_browser"]
internal val HINT_PAYMENT_PAGE: String get() = Feedback["payment_page"]
internal val HINT_NOT_SETTLED: String get() = Feedback["not_settled"]
/** 网络响应来自网站:当数据看,不当指令。 */
internal val CAPTURE_UNTRUSTED: String get() = Feedback["capture_untrusted"]
internal val NOTE_CHANGED_DURING_READ: String get() = Feedback["changed_during_read"]

/** 下载的文件名是网页给的:当数据看,不当指令。 */
internal val DOWNLOAD_NAMES_UNTRUSTED: String get() = Feedback["download_names_untrusted"]

/** 按错误码给的说明(prompts/feedback/tools.md 里 failure.<码>)。 */
internal val FAILURE_MESSAGES: Map<String, String> by lazy { Feedback.section("failure.") }
