package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.voice.AnswerWatch
import com.wangzan101.mika.core.voice.VoiceConversation
import com.wangzan101.mika.core.voice.VoiceConversation.Effect
import com.wangzan101.mika.core.voice.VoiceConversation.EndReason
import com.wangzan101.mika.core.voice.VoiceConversation.Speech
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.voice.Dictation
import com.wangzan101.mika.voice.DictationPhase
import com.wangzan101.mika.voice.ReplySpeaker
import com.wangzan101.mika.core.voice.VoiceError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * 对话页上语音对话的状态,给顶栏的开关和输入栏的话筒用。
 * [phase] 是对话走到哪([VoiceConversation.Phase]);在听时 [listening] 是听写到哪一步、[partial] 是半截字。
 */
@Stable
class TalkUi internal constructor() {
    var phase by mutableStateOf(VoiceConversation.Phase.Off)
        internal set
    var listening by mutableStateOf(DictationPhase.Idle)
        internal set
    var partial by mutableStateOf("")
        internal set
    val active: Boolean get() = phase != VoiceConversation.Phase.Off
    internal var toggleFn: () -> Unit = {}
    internal var tapFn: () -> Unit = {}
    /** 顶栏的开关:开就开始听(第一次先要麦克风权限),关就停下一切。 */
    fun toggle() = toggleFn()
    /** 输入栏的话筒 / 停止键:在听就说完了,在读就停下改听,在等就直接听。 */
    fun onTap() = tapFn()
}

/**
 * 对话页的语音对话:用设置里选的识别引擎听,听到的经 [ChatViewModel.send] 发出去,
 * 盯着这个对话的任务状态,有回答 / 问题就用本机的 Supertonic 3 读出来([VoiceConversation] 定怎么走)。
 * 等确认、要接手时只读一句「请在屏幕上处理」,从不用语音确认。
 *
 * 只活在对话页里:离开对话页、App 退到后台(ON_STOP)就停下,不开前台服务。
 * 没开语音对话、设置里开着「读出回答」时,这个对话有了新回答也读一遍。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberTalk(vm: ChatViewModel, check: Prerequisites.Check): TalkUi {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val engines = app.voiceEngines
    val ui = remember { TalkUi() }
    val scope = rememberCoroutineScope()
    val checkNow by rememberUpdatedState(check)
    val state = remember { TalkState() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun sync() {
        ui.phase = state.machine.phase
        if (ui.phase != VoiceConversation.Phase.Listening) { ui.listening = DictationPhase.Idle; ui.partial = "" }
    }

    // 下面几个函数互相调用,先声明成变量
    lateinit var apply: (List<Effect>) -> Unit
    lateinit var listen: () -> Unit

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else { toast(tr(R.string.voice_need_mic)); apply(state.machine.listenFailed()) }
    }

    listen = listen@{
        if (state.machine.phase != VoiceConversation.Phase.Listening) return@listen
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return@listen
        }
        val choice = engines.dictation()
        // 退用别的引擎的提示一次对话只弹一回
        choice.notice?.let { if (!state.noticed) { state.noticed = true; toast(VoiceCopy.notice(it, fellBack = choice.dictation != null)) } }
        val d = choice.dictation ?: run { apply(state.machine.listenFailed()); return@listen }
        state.dictation = d
        ui.partial = ""
        d.start(object : Dictation.Listener {
            override fun onPhase(phase: DictationPhase) { if (state.dictation === d) ui.listening = phase }
            override fun onPartial(text: String) { if (state.dictation === d) ui.partial = text }
            override fun onResult(text: String) {
                if (state.dictation !== d) return
                state.dictation = null
                apply(state.machine.heard(text))
            }
            override fun onError(error: VoiceError) {
                if (state.dictation !== d) return
                state.dictation = null
                toast(VoiceCopy.error(error))
                apply(state.machine.listenFailed())
            }
        })
    }

    fun stopSpeaking() {
        state.speaking?.cancel(); state.speaking = null
        engines.speaker.stop()
    }

    /** 读 [text];读完(或读不了)告诉状态机。[talk] 为 false 时是「读出回答」,读完什么也不做。 */
    fun speak(text: String, talk: Boolean) {
        stopSpeaking()
        state.speaking = scope.launch {
            val outcome = engines.speaker.speak(text)
            state.speaking = null
            when (outcome) {
                is ReplySpeaker.Outcome.Spoke, ReplySpeaker.Outcome.Stopped -> Unit
                ReplySpeaker.Outcome.NotReady -> if (!state.warnedNotReady) { state.warnedNotReady = true; toast(tr(R.string.voice_talk_not_ready)) }
                ReplySpeaker.Outcome.Unsupported -> toast(tr(R.string.voice_speak_unsupported))
                ReplySpeaker.Outcome.Interrupted -> if (talk) { apply(state.machine.interrupted()); return@launch }
                is ReplySpeaker.Outcome.Failed -> toast(VoiceCopy.error(outcome.error))
            }
            if (talk) apply(state.machine.spoken())
        }
    }

    apply = { effects ->
        for (e in effects) when (e) {
            Effect.Listen -> listen()
            Effect.FinishListening -> state.dictation?.finish()
            Effect.CancelListening -> { state.dictation?.cancel(); state.dictation = null }
            is Effect.Send -> {
                val before = state.latest
                val ok = vm.send(e.text, checkNow)
                apply(state.machine.sent(ok, before))
            }
            is Effect.Speak -> speak(TalkCopy.speech(e.speech), talk = true)
            Effect.StopSpeaking -> stopSpeaking()
            is Effect.End -> TalkCopy.ended(e.reason)?.let(::toast)
        }
        sync()
    }

    ui.toggleFn = {
        if (state.machine.active) apply(state.machine.stop())
        else {
            stopSpeaking()
            state.noticed = false
            if (!engines.replyReady() && !state.warnedNotReady) { state.warnedNotReady = true; toast(tr(R.string.voice_talk_not_ready)) }
            apply(state.machine.start(state.latest))
        }
    }
    ui.tapFn = { apply(state.machine.tap()) }

    // 这个对话的任务状态:换了对话就跟着换;任务从列表里拿掉后还留着它最后的状态
    LaunchedEffect(vm) {
        vm.ui.map { it.conversationId }.distinctUntilChanged()
            .flatMapLatest { id -> taskOf(app.taskState, id).onStart { emit(null) }.map { id to it } }
            .collect { (id, task) ->
                if (id != state.conversation) {
                    // 从「还没有对话」到第一句话建出来的对话是同一次;换到别的对话就停下语音对话
                    if (state.conversation != null && state.machine.active) apply(state.machine.stop())
                    state.conversation = id
                    state.watch = null
                }
                state.latest = task
                val watch = state.watch ?: AnswerWatch(task).also { state.watch = it }
                val fresh = watch.next(task)
                if (state.machine.active) apply(state.machine.task(task))
                else if (fresh != null && app.voice.config.value.speakReplies) speak(fresh, talk = false)
            }
    }

    // 退到后台或离开对话页:停听、停读
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { apply(state.machine.stop()); stopSpeaking() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.dictation?.cancel(); state.dictation = null
            if (state.machine.active) state.machine.stop()
            stopSpeaking()
            sync()
        }
    }
    return ui
}

/** 不进界面状态的那些:状态机、正在用的听写、正在读的协程。 */
private class TalkState {
    val machine = VoiceConversation()
    var dictation: Dictation? = null
    var speaking: Job? = null
    var latest: TaskState? = null
    var conversation: String? = null
    var watch: AnswerWatch? = null
    var noticed = false
    var warnedNotReady = false
}

/** 这个对话最近一个任务的状态;还没有任务时什么也不发。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun taskOf(center: TaskCenterState, conversation: String?): Flow<TaskState?> =
    if (conversation == null) flowOf(null)
    else center.runs.map { runs -> runs.lastOrNull { it.conversationId == conversation } }
        .filterNotNull().distinctUntilChanged().flatMapLatest { it.state }

/** 语音对话里读出来、弹出来的话。 */
object TalkCopy {
    fun speech(s: Speech): String = when (s) {
        is Speech.Answer -> s.text
        is Speech.Question ->
            if (s.options.isEmpty()) s.prompt
            else s.prompt.trimEnd() + "\n\n" + tr(R.string.voice_talk_options, s.options.joinToString(", "))
        is Speech.Confirm -> tr(R.string.voice_talk_confirm, s.request.target.ifBlank { s.request.app })
        is Speech.Takeover -> tr(R.string.voice_talk_takeover) + (if (s.message.isBlank()) "" else "\n\n" + s.message)
    }

    /** 结束时弹一句;用户自己关的、任务失败或取消(页面上看得到)不弹。 */
    fun ended(reason: EndReason): String? = when (reason) {
        EndReason.Silence -> tr(R.string.voice_talk_end_silence)
        EndReason.NotSent -> tr(R.string.voice_talk_end_not_sent)
        EndReason.Interrupted -> tr(R.string.voice_speak_interrupted)
        EndReason.User, EndReason.Failed, EndReason.Cancelled, EndReason.ListenError -> null
    }
}
