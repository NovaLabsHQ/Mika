package com.wangzan101.mika.settings

/**
 * 「别动这些 App」设置页的纯逻辑:手填包名的校验、按搜索词筛已安装 App、已选的排序。
 * 列表里的 App 从不被拉回 Mika 的屏;出现在 Mika 屏上时整个任务挪回主屏,不强停。
 */
object LeaveAloneList {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** 手填的包名去掉首尾空白后像不像一个包名(至少两段,每段字母开头)。 */
    fun normalize(input: String): String? = input.trim().takeIf { it.length <= 255 && PACKAGE.matches(it) }

    /** 已安装 App([apps] 为 包名 to 名称)里按 [query] 筛:名称或包名含搜索词,不分大小写;已选的不再列出。 */
    fun visible(apps: List<Pair<String, String>>, chosen: Set<String>, query: String): List<Pair<String, String>> {
        val q = query.trim()
        return apps.filter { (pkg, label) ->
            pkg !in chosen && (q.isEmpty() || label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true))
        }
    }

    /** 已选的按显示名排;没装(找不到名称)的直接显示包名,排在后面。 */
    fun chosenRows(chosen: Set<String>, labels: Map<String, String>): List<Pair<String, String>> =
        chosen.map { it to (labels[it] ?: it) }.sortedWith(compareBy({ it.first !in labels }, { it.second.lowercase() }))
}
