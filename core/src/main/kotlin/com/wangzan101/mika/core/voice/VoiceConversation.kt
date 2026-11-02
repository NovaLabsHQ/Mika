package com.wangzan101.mika.core.voice

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest

/**
 * 语音对话的状态机(纯逻辑,不碰安卓):听 → 发出去 → 等任务 → 读回答 → 再听。
 * 驱动它的一方(对话页)把发生的事交给它([start]、[heard]、[sent]、[task]、[spoken]、[tap]、[stop]……),
 * 照它返回的 [Effect] 去做(开始听、发送、朗读、停下)。
 *
 * 任务的状态怎么处理:
 * - 完成([TaskState.Succeeded]):读回答,读完接着听;
 * - 等回答([TaskState.WaitingInput]):读问题(和选项),读完接着听,听到的就是回答;
 * - 等确认([TaskState.WaitingConfirmation])、要用户接手([TaskState.WaitingTakeover]):读一句「请在屏幕上处理」,
 *   然后等着,**从不用语音确认**;用户在屏幕上点完、任务接着跑,就回到等待;
 * - 失败、取消:结束对话。
 *
 * 发出去之后,发送那一刻的旧状态(比如上一个任务的「完成」)不算数,见到新的状态才算。
 * 听的时候任务有了回答不打断用户:先记着,用户什么也没说就读它,说了就把新的话发出去、记着的不读了。
 */
class VoiceConversation(
    /** 连着几次什么也没听到就结束。 */
    private val maxEmptyListens: Int = 2,
) {
    enum class Phase {
        Off,
        /** 在听用户说。 */
        Listening,
        /** 话发出去了,等任务给回答。 */
        Waiting,
        /** 在读。 */
        Speaking,
        /** 等用户在屏幕上确认或接手;不听也不读。 */
        Holding,
    }

    /** 要读的内容;文字怎么写由界面定(要翻译)。 */
    sealed interface Speech {
        data class Answer(val text: String) : Speech
        data class Question(val prompt: String, val options: List<String>) : Speech
        data class Confirm(val request: ConfirmationRequest) : Speech
        data class Takeover(val message: String) : Speech
    }

    enum class EndReason {
        /** 用户关掉了。 */
        User,
        /** 任务失败。 */
        Failed,
        /** 任务被取消。 */
        Cancelled,
        /** 话没发出去(条件不满足之类)。 */
        NotSent,
        /** 连着几次没听到话。 */
        Silence,
        /** 听写出错(没有麦克风权限、没网……)。 */
        ListenError,
        /** 别的声音(来电之类)把朗读打断了。 */
        Interrupted,
    }

    sealed interface Effect {
        /** 开始听(用设置里选的识别引擎)。 */
        data object Listen : Effect
        /** 用户说完了:让识别收尾,结果随后经 [heard] 回来。 */
        data object FinishListening : Effect
        /** 不要这次听写的结果了。 */
        data object CancelListening : Effect
        data class Send(val text: String) : Effect
        data class Speak(val speech: Speech) : Effect
        data object StopSpeaking : Effect
        data class End(val reason: EndReason) : Effect
    }

    var phase: Phase = Phase.Off
        private set

    /** 读完之后去哪(听,或等用户在屏幕上处理)。 */
    private var afterSpeech = Phase.Listening
    /** 发送那一刻(或开始那一刻)的任务状态;见到和它不同的状态之前,同样的状态不算数。 */
    private var snapshot: TaskState? = null
    private var fresh = true
    /** 上一个已经处理过的状态,同一个状态不处理两次。 */
    private var handled: TaskState? = null
    private var empties = 0
    /** 听的时候到了的回答,等这次听完再说。 */
    private var pending: Speech? = null

    val active: Boolean get() = phase != Phase.Off

    /** 打开语音对话。[current] 是此刻这个对话里任务的状态:在跑就先等它,等确认就先等用户,别的先听。 */
    fun start(current: TaskState?): List<Effect> {
        if (active) return emptyList()
        snapshot = current; fresh = false; handled = null; empties = 0; pending = null
        return when (current) {
            is TaskState.Running, is TaskState.Held -> { phase = Phase.Waiting; emptyList() }
            is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> { phase = Phase.Holding; emptyList() }
            else -> listen()
        }
    }

    /** 听到了 [text](什么也没听清时是空串)。 */
    fun heard(text: String): List<Effect> {
        if (phase != Phase.Listening) return emptyList()
        if (text.isBlank()) {
            pending?.let { pending = null; return speak(it) }
            empties++
            // 这次听写已经结束,不用再取消
            return if (empties >= maxEmptyListens) { phase = Phase.Waiting; end(EndReason.Silence) } else listen()
        }
        empties = 0
        pending = null
        phase = Phase.Waiting
        return listOf(Effect.Send(text.trim()))
    }

    /** 听写出错。 */
    fun listenFailed(): List<Effect> = if (phase == Phase.Listening) { phase = Phase.Waiting; end(EndReason.ListenError) } else emptyList()

    /** [Effect.Send] 做完了:[ok] 是发出去没有,[before] 是发送前一刻任务的状态。 */
    fun sent(ok: Boolean, before: TaskState?): List<Effect> {
        if (phase != Phase.Waiting) return emptyList()
        if (!ok) return end(EndReason.NotSent)
        snapshot = before; fresh = false; handled = null
        return emptyList()
    }

    /** 这个对话里任务的状态变了。 */
    fun task(state: TaskState?): List<Effect> {
        if (!active || state == null) return emptyList()
        if (!fresh) {
            val live = state is TaskState.Running || state is TaskState.Held
            if (state == snapshot && !live) return emptyList()
            fresh = true
        }
        if (state == handled) return emptyList()
        handled = state
        return when (state) {
            is TaskState.Succeeded -> deliver(Speech.Answer(state.answer), then = Phase.Listening)
            is TaskState.WaitingInput -> deliver(Speech.Question(state.prompt, state.options.orEmpty()), then = Phase.Listening)
            is TaskState.WaitingConfirmation -> deliver(Speech.Confirm(state.request), then = Phase.Holding)
            is TaskState.WaitingTakeover -> deliver(Speech.Takeover(state.message), then = Phase.Holding)
            is TaskState.Failed -> end(EndReason.Failed)
            TaskState.Cancelled -> end(EndReason.Cancelled)
            // 用户在屏幕上确认了、任务接着跑:回到等回答
            is TaskState.Running, is TaskState.Held -> { if (phase == Phase.Holding) phase = Phase.Waiting; emptyList() }
            TaskState.Idle -> emptyList()
        }
    }

    /** 读完了(或这段读不了,比如整段是中文):接着听,或等用户在屏幕上处理。 */
    fun spoken(): List<Effect> {
        if (phase != Phase.Speaking) return emptyList()
        return if (afterSpeech == Phase.Listening) listen() else { phase = Phase.Holding; emptyList() }
    }

    /** 朗读被别的声音打断(来电之类):结束对话。 */
    fun interrupted(): List<Effect> = if (phase == Phase.Speaking) end(EndReason.Interrupted) else emptyList()

    /** 点了话筒 / 停止键:在听就说完了;在读就停下、改听;在等就直接听(插话、回答)。 */
    fun tap(): List<Effect> = when (phase) {
        Phase.Off -> emptyList()
        Phase.Listening -> listOf(Effect.FinishListening)
        Phase.Speaking -> listOf(Effect.StopSpeaking) + listen()
        Phase.Waiting, Phase.Holding -> listen()
    }

    /** 关掉语音对话。 */
    fun stop(): List<Effect> = if (active) end(EndReason.User) else emptyList()

    private fun listen(): List<Effect> {
        phase = Phase.Listening
        return listOf(Effect.Listen)
    }

    private fun deliver(speech: Speech, then: Phase): List<Effect> {
        afterSpeech = then
        return when (phase) {
            // 不打断正在说话的用户
            Phase.Listening -> { pending = speech; emptyList() }
            Phase.Speaking -> listOf(Effect.StopSpeaking) + speak(speech)
            else -> speak(speech)
        }
    }

    private fun speak(speech: Speech): List<Effect> {
        phase = Phase.Speaking
        return listOf(Effect.Speak(speech))
    }

    private fun end(reason: EndReason): List<Effect> {
        val wasListening = phase == Phase.Listening
        val wasSpeaking = phase == Phase.Speaking
        phase = Phase.Off
        pending = null
        return buildList {
            if (wasListening) add(Effect.CancelListening)
            if (wasSpeaking) add(Effect.StopSpeaking)
            add(Effect.End(reason))
        }
    }
}

/**
 * 不在语音对话里、开着「读出回答」时:这个对话里有了新的回答就读一遍。
 * 打开对话页那一刻已经在的回答不读;同一个回答只读一次。
 */
class AnswerWatch(initial: TaskState?) {
    private var last: TaskState? = initial

    /** 状态变成 [state];要读的回答,没有为 null。 */
    fun next(state: TaskState?): String? {
        if (state == null || state == last) return null
        last = state
        return (state as? TaskState.Succeeded)?.answer?.takeIf { it.isNotBlank() }
    }
}
