package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.privacy.SensitiveAppPolicy
import com.wangzan101.mika.core.privacy.SensitiveApps
import com.wangzan101.mika.settings.LeaveAloneList
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 › 隐私与数据 › 敏感 App:上面一段说明,下面是这台手机上在默认名单里的 App(和用户另加的),
 * 每行写名称和为什么在名单上,开关开着就是挡住;关掉即放开这一个。最下面可以搜已安装的 App 另外挡住。
 */
@Composable
fun SensitiveAppsScreen(policy: SensitiveAppPolicy, onChange: (SensitiveAppPolicy) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { runCatching { launchableApps(context.packageManager, context.packageName) }.getOrDefault(emptyList()) }
    }
    var filter by remember { mutableStateOf("") }
    val installed = remember(apps) { apps.orEmpty().toMap() }
    PageScaffold(stringResource(R.string.settings_sensitive_apps), onBack) {
        FooterNote(stringResource(R.string.settings_sensitive_apps_foot))
        val all = apps ?: return@PageScaffold
        val entries = policy.entries(installed)
        GroupLabel(stringResource(R.string.settings_sensitive_apps_found))
        if (entries.isEmpty()) EmptyNote(stringResource(R.string.settings_sensitive_apps_none))
        else GroupCard {
            entries.forEachIndexed { i, e ->
                val toggle = { on: Boolean -> onChange(policy.toggled(e.packageName, block = on)) }
                ListRow(null, e.label, reason(e), first = i == 0, onClick = { toggle(!e.blocked) }) {
                    MikaSwitch(e.blocked, toggle, Modifier.testTag("sensitive-${e.packageName}"))
                }
            }
        }
        GroupLabel(stringResource(R.string.settings_sensitive_add_title))
        SearchField(filter, { filter = it }, stringResource(R.string.settings_sensitive_search), Modifier.testTag("sensitive-filter"))
        if (filter.isNotBlank()) {
            val listed = entries.map { it.packageName }.toSet()
            val shown = LeaveAloneList.visible(all, listed, filter)
            if (shown.isEmpty()) EmptyNote(stringResource(R.string.settings_leave_alone_empty))
            else GroupCard {
                shown.forEachIndexed { i, (pkg, label) ->
                    ListRow(null, label, pkg, first = i == 0, onClick = { onChange(policy.toggled(pkg, block = true)) }) {
                        MikaSwitch(false, { onChange(policy.toggled(pkg, block = true)) }, Modifier.testTag("sensitive-add-$pkg"))
                    }
                }
            }
        }
    }
}

/** 一行的副标题:哪一类、点名的还是包名里有关键词;用户另加的写「你加的」。 */
@Composable
private fun reason(e: SensitiveAppPolicy.Entry): String {
    val m = e.match ?: return stringResource(R.string.settings_sensitive_reason_added)
    val category = stringResource(
        when (m.category) {
            SensitiveApps.Category.Banking -> R.string.settings_sensitive_cat_banking
            SensitiveApps.Category.Finance -> R.string.settings_sensitive_cat_finance
            SensitiveApps.Category.Brokerage -> R.string.settings_sensitive_cat_brokerage
            SensitiveApps.Category.Crypto -> R.string.settings_sensitive_cat_crypto
            SensitiveApps.Category.Health -> R.string.settings_sensitive_cat_health
            SensitiveApps.Category.Medical -> R.string.settings_sensitive_cat_medical
            SensitiveApps.Category.Passwords -> R.string.settings_sensitive_cat_passwords
            SensitiveApps.Category.Authenticator -> R.string.settings_sensitive_cat_authenticator
        },
    )
    return when (m.reason) {
        SensitiveApps.Reason.Curated -> stringResource(R.string.settings_sensitive_reason_curated, category)
        SensitiveApps.Reason.Keyword -> stringResource(R.string.settings_sensitive_reason_keyword, category, m.keyword.orEmpty())
    }
}
