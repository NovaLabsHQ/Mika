package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.voice.VoiceCatalog
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.settings.ReplyEngine
import com.wangzan101.mika.settings.SpeechEngine
import com.wangzan101.mika.settings.VoiceConfig
import com.wangzan101.mika.settings.VoiceSettings
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
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 本机模型还没接上运行时;接上后改这里,两组里的本机那一行就能选了。 */
internal fun voiceEngineReady(id: VoiceEngineId): Boolean = !id.needsModel

/** 下载大小按十进制 MB 写,和发布页上的写法一致。 */
internal fun voiceModelSize(id: VoiceEngineId): String =
    VoiceCatalog.modelFor(id)?.let { String.format(Locale.ROOT, "%.1f MB", it.downloadBytes / 1_000_000.0) }.orEmpty()

/**
 * 设置 › 语音:「语音识别」「朗读回答」两组单选,本机模型的行先看得见、点不了(写「即将推出」);
 * ElevenLabs 没填 Key 时那一行写「填入 API Key」,点了先弹出填 Key 的面板,存好再选上。
 */
@Composable
fun VoiceScreen(settings: VoiceSettings, onBack: () -> Unit) {
    val config by settings.config.collectAsState()
    val scope = rememberCoroutineScope()
    var keySheet by remember { mutableStateOf(false) }
    // 填完 Key 后要选上的那一项(从哪一行点进来的)
    var afterKey by remember { mutableStateOf<(() -> Unit)?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun change(action: () -> Unit) {
        failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
    }
    fun needKeyThen(action: () -> Unit) {
        if (config.hasElevenLabsKey) change(action) else { afterKey = action; keySheet = true }
    }

    PageScaffold(stringResource(R.string.voice_title), onBack) {
        VoiceContent(
            config,
            onSpeech = { e -> if (e.id.needsKey) needKeyThen { settings.setSpeech(e) } else change { settings.setSpeech(e) } },
            onReply = { e -> if (e.id.needsKey) needKeyThen { settings.setReply(e) } else change { settings.setReply(e) } },
            onWifiOnly = { on -> change { settings.setWifiOnly(on) } },
            onKey = { afterKey = null; keySheet = true },
        )
        if (failed) FooterNote(stringResource(R.string.voice_save_failed), color = MikaTheme.colors.bad)
    }

    if (keySheet) VoiceKeySheet(
        hasKey = config.hasElevenLabsKey,
        onSave = { key ->
            val then = afterKey
            change { settings.setElevenLabsKey(key); then?.invoke() }
            keySheet = false; afterKey = null
        },
        onRemove = {
            change {
                settings.clearElevenLabsKey()
                // Key 没了,用着 ElevenLabs 的退回不要 Key 的选择
                if (settings.config.value.speech.id.needsKey) settings.setSpeech(SpeechEngine.PhoneRecognizer)
            }
            keySheet = false; afterKey = null
        },
        onDismiss = { keySheet = false; afterKey = null },
    )
}

/** 语音页的内容(不含填 Key 的面板),单独拿出来好在界面测试里直接画。 */
@Composable
fun VoiceContent(
    config: VoiceConfig,
    onSpeech: (SpeechEngine) -> Unit,
    onReply: (ReplyEngine) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onKey: () -> Unit,
) {
    val soon = stringResource(R.string.voice_coming_soon)
    val addKey = stringResource(R.string.voice_add_key)

    GroupLabel(stringResource(R.string.voice_group_speech))
    GroupCard {
        SpeechEngine.entries.forEachIndexed { i, e ->
            val ready = voiceEngineReady(e.id)
            val subtitle = when (e) {
                SpeechEngine.PhoneRecognizer -> stringResource(R.string.voice_phone_detail)
                SpeechEngine.Parakeet3 -> stringResource(R.string.voice_parakeet_detail, voiceModelSize(e.id))
                SpeechEngine.ElevenLabsScribe -> if (config.hasElevenLabsKey) stringResource(R.string.voice_key_ready) else addKey
            }
            RadioRow(
                stringResource(
                    when (e) {
                        SpeechEngine.PhoneRecognizer -> R.string.voice_phone
                        SpeechEngine.Parakeet3 -> R.string.voice_parakeet
                        SpeechEngine.ElevenLabsScribe -> R.string.voice_scribe
                    },
                ),
                selected = config.speech == e, onClick = { if (config.speech != e) onSpeech(e) },
                subtitle = subtitle, first = i == 0, enabled = ready,
            ) { if (!ready) RowValue(soon) }
        }
    }

    GroupLabel(stringResource(R.string.voice_group_reply))
    GroupCard {
        ReplyEngine.entries.forEachIndexed { i, e ->
            val ready = voiceEngineReady(e.id)
            val subtitle = when (e) {
                ReplyEngine.Supertonic3 -> stringResource(R.string.voice_supertonic_detail, stringResource(R.string.voice_female_1), voiceModelSize(e.id))
                ReplyEngine.ElevenLabs -> if (config.hasElevenLabsKey) stringResource(R.string.voice_reply_key_ready) else addKey
            }
            RadioRow(
                stringResource(if (e == ReplyEngine.Supertonic3) R.string.voice_supertonic else R.string.voice_elevenlabs),
                selected = config.reply == e, onClick = { if (config.reply != e) onReply(e) },
                subtitle = subtitle, first = i == 0, enabled = ready,
            ) { if (!ready) RowValue(soon) }
        }
    }
    FooterNote(stringResource(R.string.voice_reply_foot))

    GroupCard {
        ListRow(MikaIcons.Key, stringResource(R.string.voice_key_row), first = true, onClick = onKey) {
            RowValue(
                stringResource(if (config.hasElevenLabsKey) R.string.voice_key_saved else R.string.voice_key_missing),
                if (config.hasElevenLabsKey) ValueTone.Ok else ValueTone.Plain,
            )
            RowChevron()
        }
        ListRow(null, stringResource(R.string.voice_wifi_only)) {
            MikaSwitch(config.wifiOnly, onWifiOnly, Modifier.testTag("voice-wifi-only"))
        }
    }
    FooterNote(stringResource(R.string.voice_download_note))
}

/** 填 ElevenLabs Key 的面板。Key 不进可恢复状态,也不回显已存的那个。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceKeySheet(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    var key by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("voice-key-sheet")) {
            Text(stringResource(R.string.voice_key_row), Modifier.padding(horizontal = 16.dp), style = MikaType.Body, color = c.ink)
            Text(
                stringResource(R.string.voice_key_sheet_sub), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                style = MikaType.Caption, color = c.ink3,
            )
            InputRow(
                stringResource(R.string.voice_key_label), key, { key = it.take(200) }, stringResource(R.string.voice_key_hint),
                first = true, keyboard = KeyboardType.Password, secret = true, modifier = Modifier.testTag("voice-key-input"),
            )
            RowActions {
                MikaButton(stringResource(R.string.voice_key_save), { onSave(key); key = "" }, size = ButtonSize.Small, enabled = key.isNotBlank())
                if (hasKey) MikaButton(stringResource(R.string.voice_key_remove), onRemove, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                MikaButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Tonal, size = ButtonSize.Small)
            }
        }
    }
}
