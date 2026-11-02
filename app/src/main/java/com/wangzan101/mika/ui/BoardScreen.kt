package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.wangzan101.mika.R
import com.wangzan101.mika.board.BoardRuntime
import com.wangzan101.mika.board.BoardService
import com.wangzan101.mika.settings.BoardConfig
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置 › 任务看板(只在 Mika Tester 包里有):GitHub 仓库、截图仓库、允许交任务的人、令牌(只写不显示,和 MCP 令牌一样加密存)、
 * 轮询间隔和门槛、「测试连接」、上一次轮询的结果。默认关着;没测试连接成功(名单没换成编号)前打不开。
 */
@Composable
fun BoardScreen(runtime: BoardRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by runtime.settings.config.collectAsState()
    val status by runtime.settings.status.collectAsState()
    var owner by remember { mutableStateOf(config.owner) }
    var repo by remember { mutableStateOf(config.repo) }
    var artifacts by remember { mutableStateOf(config.artifactsRepo) }
    var logins by remember { mutableStateOf(config.allowedLogins) }
    var minutes by remember { mutableStateOf(config.pollMinutes.toString()) }
    var tokenInput by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(runtime.settings.hasToken) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun save() {
        runtime.settings.update { c ->
            val loginsChanged = c.allowedLogins != logins
            c.copy(owner = owner.trim(), repo = repo.trim(), artifactsRepo = artifacts.trim(), allowedLogins = logins,
                pollMinutes = minutes.toIntOrNull() ?: c.pollMinutes,
                // 名单改了要重新测试连接才换成编号;在那之前不认任何人
                allowedUsers = if (loginsChanged) emptyList() else c.allowedUsers)
        }
        if (tokenInput.isNotBlank()) { runtime.settings.setToken(tokenInput); tokenInput = ""; hasToken = true }
    }

    fun setEnabled(on: Boolean) {
        save()
        runtime.settings.update { it.copy(enabled = on && it.ready && runtime.settings.hasToken) }
        BoardService.ensureRunning(context)
    }

    PageScaffold(stringResource(R.string.board_title), onBack) {
        GroupCard {
            ListRow(MikaIcons.Link, stringResource(R.string.board_enable), stringResource(
                if (config.ready && hasToken) R.string.board_enable_sub else R.string.board_enable_needs_test), first = true,
                onClick = { setEnabled(!config.enabled) }) {
                MikaSwitch(config.enabled, { setEnabled(it) }, Modifier.testTag("board-enabled"))
            }
        }
        FooterNote(stringResource(R.string.board_foot))

        GroupLabel(stringResource(R.string.board_repo_group))
        GroupCard {
            InputRow(stringResource(R.string.board_owner), owner, { owner = it }, "octocat", first = true)
            InputRow(stringResource(R.string.board_repo), repo, { repo = it }, "mika-tasks")
            InputRow(stringResource(R.string.board_artifacts), artifacts, { artifacts = it }, stringResource(R.string.board_artifacts_hint))
            InputRow(stringResource(R.string.board_users), logins, { logins = it }, stringResource(R.string.board_users_hint))
        }
        FooterNote(stringResource(R.string.board_users_foot, config.allowedUsers.joinToString { "${it.login} #${it.id}" }.ifEmpty { "—" }))

        GroupLabel(stringResource(R.string.board_token_group))
        GroupCard {
            InputRow(stringResource(R.string.board_token), tokenInput, { tokenInput = it },
                stringResource(if (hasToken) R.string.board_token_saved else R.string.board_token_hint), first = true, secret = true)
            if (hasToken) RowActions {
                MikaButton(stringResource(R.string.board_token_clear), {
                    runtime.settings.setToken(""); hasToken = false
                    runtime.settings.update { it.copy(enabled = false) }
                    BoardService.ensureRunning(context)
                }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
            }
        }
        FooterNote(stringResource(R.string.board_token_foot))

        GroupLabel(stringResource(R.string.board_poll_group))
        GroupCard {
            InputRow(stringResource(R.string.board_interval), minutes, { minutes = it.filter(Char::isDigit).take(2) },
                BoardConfig.DEFAULT_POLL_MINUTES.toString(), first = true, keyboard = KeyboardType.Number)
            ListRow(MikaIcons.Globe, stringResource(R.string.board_wifi_only), onClick = { runtime.settings.update { it.copy(wifiOnly = !it.wifiOnly) } }) {
                MikaSwitch(config.wifiOnly, { on -> runtime.settings.update { it.copy(wifiOnly = on) } })
            }
            ListRow(MikaIcons.Key, stringResource(R.string.board_unlocked_only), onClick = { runtime.settings.update { it.copy(unlockedOnly = !it.unlockedOnly) } }) {
                MikaSwitch(config.unlockedOnly, { on -> runtime.settings.update { it.copy(unlockedOnly = on) } })
            }
        }
        FooterNote(stringResource(R.string.board_poll_foot))

        GroupCard {
            RowActions {
                MikaButton(stringResource(R.string.board_save), { save() }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                MikaButton(stringResource(if (testing) R.string.board_testing else R.string.board_test), {
                    save()
                    testing = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runtime.testConnection() }
                        testResult = r.fold({ true to it }, { false to (it.message ?: "") })
                        testing = false
                    }
                }, size = ButtonSize.Small, enabled = !testing, modifier = Modifier.testTag("board-test"))
            }
            testResult?.let { (ok, text) ->
                ListRow(if (ok) MikaIcons.Check else MikaIcons.Exclaim, text, tone = if (ok) TileTone.Ok else TileTone.Bad)
            }
        }

        GroupLabel(stringResource(R.string.board_status_group))
        GroupCard {
            val s = status
            if (s == null) ListRow(MikaIcons.Info, stringResource(R.string.board_status_none), first = true)
            else ListRow(if (s.ok) MikaIcons.Check else MikaIcons.Exclaim, s.message, stringResource(R.string.board_status_at, clock(s.atMs)),
                first = true, tone = if (s.ok) TileTone.Ok else TileTone.Bad)
        }
    }
}

private fun clock(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
