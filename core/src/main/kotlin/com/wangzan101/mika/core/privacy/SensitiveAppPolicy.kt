package com.wangzan101.mika.core.privacy

/**
 * 此刻生效的隐私名单:默认名单([SensitiveApps])减去用户放开的([allowed]),加上用户另加的([extra])。
 * Mika 自己从不算([SensitiveApps.neverBlock])。纯逻辑;设置随时改,调用方每次按此刻的设置新建或经 provider 取。
 */
class SensitiveAppPolicy(
    val allowed: Set<String> = emptySet(),
    val extra: Set<String> = emptySet(),
) {
    /** [packageName] 此刻不许 Mika 打开、看、操作。 */
    fun blocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank() || SensitiveApps.neverBlock(packageName)) return false
        if (packageName in extra) return true
        if (packageName in allowed) return false
        return SensitiveApps.match(packageName) != null
    }

    /**
     * 设置页的一行:[packageName]、[label] 显示名,[match] 默认名单上的依据(用户另加的、不在默认名单上的为 null),
     * [blocked] 此刻挡不挡,[userAdded] 是用户另加的。
     */
    data class Entry(val packageName: String, val label: String, val match: SensitiveApps.Match?, val blocked: Boolean, val userAdded: Boolean)

    /**
     * 设置页列出的:装着的 App([installed] 包名 → 显示名)里在默认名单上或用户另加的,按显示名排;
     * 另加了但没装的也列出(便于撤掉)。
     */
    fun entries(installed: Map<String, String>): List<Entry> {
        val names = installed.keys + extra
        return names.mapNotNull { pkg ->
            if (SensitiveApps.neverBlock(pkg)) return@mapNotNull null
            val m = SensitiveApps.match(pkg)
            val added = pkg in extra && m == null
            if (m == null && !added) return@mapNotNull null
            Entry(pkg, installed[pkg] ?: pkg, m, blocked(pkg), added)
        }.sortedWith(compareBy<Entry>({ it.label.lowercase() }, { it.packageName }))
    }

    /** 拨动 [packageName] 的开关后的设置([block] 为挡)。默认名单上的记在放开里,不在的记在另加里。 */
    fun toggled(packageName: String, block: Boolean): SensitiveAppPolicy {
        val default = SensitiveApps.match(packageName) != null
        return when {
            default && block -> SensitiveAppPolicy(allowed - packageName, extra - packageName)
            default -> SensitiveAppPolicy(allowed + packageName, extra - packageName)
            block -> SensitiveAppPolicy(allowed - packageName, extra + packageName)
            else -> SensitiveAppPolicy(allowed - packageName, extra - packageName)
        }
    }

    companion object {
        /** 默认:名单全挡。 */
        val DEFAULT = SensitiveAppPolicy()

        /** 动作被挡时的错误码。 */
        const val BLOCKED = "app_blocked_by_privacy"
    }
}
