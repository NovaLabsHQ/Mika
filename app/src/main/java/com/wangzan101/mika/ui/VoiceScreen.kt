package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import com.wangzan101.mika.core.voice.ModelSpec
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
import com.wangzan101.mika.voice.ModelPhase
import com.wangzan101.mika.voice.ModelProblem
import com.wangzan101.mika.voice.ModelState
import com.wangzan101.mika.voice.ParakeetRuntime
import com.wangzan101.mika.voice.VoiceEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 这一行能不能选:不用下载的引擎都能选;本机模型要已经下好、这台手机跑得动。
 * [models] 是各模型的下载状态(按模型编号),朗读模型(Supertonic 3)还没接上运行时,先一律不能选。
 */
internal fun voiceEngineReady(id: VoiceEngineId, models: Map<String, ModelState> = emptyMap(), supported: Boolean = true): Boolean =
    when {
        !id.needsModel -> true
        id == VoiceEngineId.Parakeet3 -> supported && models[VoiceCatalog.parakeet3.id]?.phase == ModelPhase.Ready
        else -> false
    }

/** 大小按十进制 MB 写,和发布页上的写法一致。 */
internal fun voiceBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

internal fun voiceModelSize(id: VoiceEngineId): String = VoiceCatalog.modelFor(id)?.let { voiceBytes(it.downloadBytes) }.orEmpty()

/** 模型的下载、暂停、删除;界面只管转给 [VoiceEngines]。 */
class VoiceModelActions(
    val download: (ModelSpec) -> Unit = {},
    val cancel: (ModelSpec) -> Unit = {},
    val remove: (ModelSpec) -> Unit = {},
)

/**
 * 设置 › 语音:「语音识别」「朗读回答」两组单选;Parakeet v3 下好模型后能选,下面一张卡片管下载
 * (进度、暂停、接着下、删除);朗读的本机模型先看得见、点不了(写「即将推出」)。
 * ElevenLabs 没填 Key 时那一行写「填入 API Key」,点了先弹出填 Key 的面板,存好再选上。
 */
@Composable
fun VoiceScreen(settings: VoiceSettings, engines: VoiceEngines, onBack: () -> Unit) {
    val config by settings.config.collectAsState()
    val models by engines.models.states.collectAsState()
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
    val actions = remember(engines) {
        VoiceModelActions(
            download = { engines.models.download(it) },
            cancel = { engines.models.cancel(it) },
            remove = { spec ->
                scope.launch {
                    engines.removeModel(spec)
                    // 模型没了,用着它的退回手机自带
                    if (settings.config.value.speech.id == spec.engine) withContext(Dispatchers.IO) {
                        runCatching { settings.setSpeech(SpeechEngine.PhoneRecognizer) }
                    }
                }
            },
        )
    }

    PageScaffold(stringResource(R.string.voice_title), onBack) {
        VoiceContent(
            config,
            onSpeech = { e -> if (e.id.needsKey) needKeyThen { settings.setSpeech(e) } else change { settings.setSpeech(e) } },
            onReply = { e -> if (e.id.needsKey) needKeyThen { settings.setReply(e) } else change { settings.setReply(e) } },
            onWifiOnly = { on -> change { settings.setWifiOnly(on) } },
            onKey = { afterKey = null; keySheet = true },
            models = models,
            supported = ParakeetRuntime.supported,
            // 每次重画时看一眼,够提示用
            onWifi = engines.models.onWifi(),
            actions = actions,
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
    models: Map<String, ModelState> = emptyMap(),
    /** 这台手机能不能跑本机模型(sherpa-onnx 只打包了 arm64-v8a)。 */
    supported: Boolean = true,
    onWifi: Boolean = true,
    actions: VoiceModelActions = VoiceModelActions(),
) {
    val soon = stringResource(R.string.voice_coming_soon)
    val addKey = stringResource(R.string.voice_add_key)

    GroupLabel(stringResource(R.string.voice_group_speech))
    GroupCard {
        SpeechEngine.entries.forEachIndexed { i, e ->
            val ready = voiceEngineReady(e.id, models, supported)
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
            ) {
                if (!ready) RowValue(
                    when {
                        e != SpeechEngine.Parakeet3 -> soon
                        !supported -> stringResource(R.string.voice_model_unsupported)
                        else -> stringResource(R.string.voice_model_needed)
                    },
                )
            }
        }
    }
    if (supported) {
        val spec = VoiceCatalog.parakeet3
        ModelCard(spec, models[spec.id] ?: ModelState(ModelPhase.Absent, 0, spec.downloadBytes), config.wifiOnly, onWifi, actions)
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
}

/** 一个本机模型的下载卡片:现在什么样、进度条、按钮;不在 Wi-Fi 上时提醒一句。 */
@Composable
private fun ModelCard(spec: ModelSpec, state: ModelState, wifiOnly: Boolean, onWifi: Boolean, actions: VoiceModelActions) {
    val c = MikaTheme.colors
    val total = voiceBytes(spec.downloadBytes)
    val progress = stringResource(R.string.voice_model_downloading, voiceBytes(state.bytes), total)
    val line = when (state.phase) {
        ModelPhase.Absent -> stringResource(R.string.voice_model_absent, total)
        ModelPhase.Downloading -> progress
        ModelPhase.Paused -> stringResource(R.string.voice_model_paused, progress)
        ModelPhase.Verifying -> stringResource(R.string.voice_model_verifying)
        ModelPhase.Unpacking -> stringResource(R.string.voice_model_unpacking)
        ModelPhase.Ready -> stringResource(R.string.voice_model_ready, total)
        ModelPhase.Failed -> when (state.problem) {
            ModelProblem.Network -> stringResource(R.string.voice_model_failed_network)
            ModelProblem.Checksum -> stringResource(R.string.voice_model_failed_checksum)
            ModelProblem.NeedsWifi -> stringResource(R.string.voice_model_failed_wifi)
            ModelProblem.Storage -> stringResource(R.string.voice_model_failed_storage, total)
            ModelProblem.Other, null -> stringResource(R.string.voice_model_failed_other)
        }
    }
    GroupCard(Modifier.testTag("voice-model-card")) {
        ListRow(
            null, stringResource(R.string.voice_model_parakeet), subtitle = line, first = true,
            detail = stringResource(R.string.voice_model_license, spec.license),
        )
        val showBar = state.busy || state.phase == ModelPhase.Paused || (state.phase == ModelPhase.Failed && state.bytes > 0)
        if (showBar) {
            val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp).testTag("voice-model-progress")
            if (state.phase == ModelPhase.Verifying || state.phase == ModelPhase.Unpacking) {
                LinearProgressIndicator(bar, color = c.accent, trackColor = c.fill)
            } else {
                LinearProgressIndicator(progress = { state.fraction }, modifier = bar, color = c.accent, trackColor = c.fill)
            }
        }
        RowActions {
            when {
                state.busy -> MikaButton(stringResource(R.string.voice_model_cancel), { actions.cancel(spec) },
                    Modifier.testTag("voice-model-cancel"), kind = ButtonKind.Tonal, size = ButtonSize.Small)
                state.phase == ModelPhase.Ready -> MikaButton(stringResource(R.string.voice_model_remove), { actions.remove(spec) },
                    Modifier.testTag("voice-model-remove"), kind = ButtonKind.Tonal, size = ButtonSize.Small)
                else -> {
                    MikaButton(
                        stringResource(if (state.bytes > 0) R.string.voice_model_resume else R.string.voice_model_download),
                        { actions.download(spec) }, Modifier.testTag("voice-model-download"), size = ButtonSize.Small,
                    )
                    if (state.bytes > 0) MikaButton(stringResource(R.string.voice_model_remove), { actions.remove(spec) },
                        Modifier.testTag("voice-model-remove"), kind = ButtonKind.Tonal, size = ButtonSize.Small)
                }
            }
        }
    }
    when {
        state.phase == ModelPhase.Ready -> Unit
        !onWifi && wifiOnly -> FooterNote(stringResource(R.string.voice_model_failed_wifi), color = c.warn)
        !onWifi -> FooterNote(stringResource(R.string.voice_model_mobile_warning, total), color = c.warn)
        else -> FooterNote(stringResource(R.string.voice_download_note))
    }
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
