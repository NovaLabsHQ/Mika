package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.voice.VoiceError
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.voice.Dictation
import com.wangzan101.mika.voice.DictationPhase
import com.wangzan101.mika.voice.VoicePick

/** 对话页语音输入的文字。 */
object VoiceCopy {
    fun notice(n: VoicePick.Notice, fellBack: Boolean): String = when {
        !fellBack && n == VoicePick.Notice.PhoneUnavailable -> tr(R.string.voice_notice_phone_unavailable)
        !fellBack -> tr(R.string.voice_notice_none)
        n == VoicePick.Notice.NoKey -> tr(R.string.voice_notice_no_key)
        else -> tr(R.string.voice_notice_not_ready)
    }

    fun error(e: VoiceError): String = when (e) {
        is VoiceError.NoKey -> tr(R.string.voice_error_no_key)
        is VoiceError.Auth -> tr(R.string.voice_error_auth)
        is VoiceError.RateLimited -> tr(R.string.voice_error_rate)
        is VoiceError.QuotaExceeded -> tr(R.string.voice_error_quota)
        is VoiceError.Network -> tr(R.string.voice_error_network)
        is VoiceError.Server, is VoiceError.BadResponse -> tr(R.string.voice_error_server)
        is VoiceError.Unavailable -> when (e.message) {
            "permission" -> tr(R.string.voice_need_mic)
            "model" -> tr(R.string.voice_error_model)
            "native" -> tr(R.string.voice_model_unsupported)
            else -> tr(R.string.voice_error_unavailable)
        }
    }
}

/** 输入栏右边话筒的状态:[phase] 是听写到哪一步,[partial] 是边说边出的半截字(放在输入框的灰字里)。 */
@Stable
class DictationUi internal constructor() {
    var phase by mutableStateOf(DictationPhase.Idle)
        internal set
    var partial by mutableStateOf("")
        internal set
    internal var tap: () -> Unit = {}
    /** 点话筒:没在听就开始(第一次先要麦克风权限),在听就收尾。 */
    fun onTap() = tap()
}

/**
 * 对话页的听写:按设置挑引擎([MikaApp.voiceEngines]),第一次点话筒时才要麦克风权限;
 * 听到的字交给 [onHeard](由对话页接到输入框里),出错、退用别的引擎时弹一句。离开对话页就丢掉这次听写。
 */
@Composable
fun rememberDictation(onHeard: (String) -> Unit): DictationUi {
    val context = LocalContext.current
    val ui = remember { DictationUi() }
    val heard by rememberUpdatedState(onHeard)
    var current by remember { mutableStateOf<Dictation?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun begin() {
        val choice = (context.applicationContext as MikaApp).voiceEngines.dictation()
        choice.notice?.let { toast(VoiceCopy.notice(it, fellBack = choice.dictation != null)) }
        val d = choice.dictation ?: return
        current = d
        ui.partial = ""
        d.start(object : Dictation.Listener {
            override fun onPhase(phase: DictationPhase) { if (current === d) ui.phase = phase }
            override fun onPartial(text: String) { if (current === d) ui.partial = text }
            override fun onResult(text: String) {
                if (current !== d) return
                current = null; ui.phase = DictationPhase.Idle; ui.partial = ""
                if (text.isBlank()) toast(tr(R.string.voice_heard_nothing)) else heard(text)
            }
            override fun onError(error: VoiceError) {
                if (current !== d) return
                current = null; ui.phase = DictationPhase.Idle; ui.partial = ""
                toast(VoiceCopy.error(error))
            }
        })
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else toast(tr(R.string.voice_need_mic))
    }
    ui.tap = {
        when (ui.phase) {
            DictationPhase.Idle -> if (context.hasMic()) begin() else permission.launch(Manifest.permission.RECORD_AUDIO)
            DictationPhase.Listening -> current?.finish()
            DictationPhase.Transcribing -> Unit
        }
    }
    DisposableEffect(Unit) {
        onDispose { current?.cancel(); current = null; ui.phase = DictationPhase.Idle }
    }
    return ui
}

private fun Context.hasMic(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
