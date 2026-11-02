package com.wangzan101.mika.core.voice

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.voice.VoiceConversation.Effect
import com.wangzan101.mika.core.voice.VoiceConversation.EndReason
import com.wangzan101.mika.core.voice.VoiceConversation.Phase
import com.wangzan101.mika.core.voice.VoiceConversation.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationTest {
    private val running = TaskState.Running(1, "thinking")
    private fun done(a: String) = TaskState.Succeeded(a)
    private val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "Alice", "hi", null, "Messages"))

    /** 开始、说一句、发出去。 */
    private fun sentOne(c: VoiceConversation, before: TaskState? = null) {
        assertEquals(listOf(Effect.Listen), c.start(before))
        assertEquals(listOf(Effect.Send("weather today")), c.heard("  weather today "))
        assertEquals(emptyList<Effect>(), c.sent(true, before))
    }

    @Test fun fullLoopSpeaksAnswerThenListensAgain() {
        val c = VoiceConversation()
        sentOne(c)
        assertEquals(Phase.Waiting, c.phase)
        assertEquals(emptyList<Effect>(), c.task(running))
        assertEquals(listOf(Effect.Speak(Speech.Answer("Sunny."))), c.task(done("Sunny.")))
        assertEquals(Phase.Speaking, c.phase)
        assertEquals(listOf(Effect.Listen), c.spoken())
        assertEquals(Phase.Listening, c.phase)
    }

    @Test fun staleStateFromBeforeSendIsIgnored() {
        val old = done("old answer")
        val c = VoiceConversation()
        sentOne(c, before = old)
        // 发出去后还没变的旧状态不读
        assertEquals(emptyList<Effect>(), c.task(old))
        assertEquals(Phase.Waiting, c.phase)
        assertEquals(emptyList<Effect>(), c.task(running))
        // 新任务完成了同样的话也要读
        assertEquals(listOf(Effect.Speak(Speech.Answer("old answer"))), c.task(done("old answer")))
    }

    @Test fun sameStateIsHandledOnce() {
        val c = VoiceConversation()
        sentOne(c)
        c.task(done("A"))
        assertEquals(emptyList<Effect>(), c.task(done("A")))
    }

    @Test fun questionIsSpokenThenListensForTheAnswer() {
        val c = VoiceConversation()
        sentOne(c)
        val q = TaskState.WaitingInput("Which size?", listOf("Small", "Large"))
        assertEquals(listOf(Effect.Speak(Speech.Question("Which size?", listOf("Small", "Large")))), c.task(q))
        assertEquals(listOf(Effect.Listen), c.spoken())
        assertEquals(listOf(Effect.Send("large")), c.heard("large"))
    }

    @Test fun confirmationIsSpokenButNeverConfirmedByVoice() {
        val c = VoiceConversation()
        sentOne(c)
        val effects = c.task(confirm)
        assertEquals(listOf(Effect.Speak(Speech.Confirm(confirm.request))), effects)
        // 读完不听,等用户在屏幕上处理;状态机从不产生发送/确认
        assertEquals(emptyList<Effect>(), c.spoken())
        assertEquals(Phase.Holding, c.phase)
        // 用户在屏幕上确认后任务接着跑,回到等待,最后读回答
        assertEquals(emptyList<Effect>(), c.task(running))
        assertEquals(Phase.Waiting, c.phase)
        assertEquals(listOf(Effect.Speak(Speech.Answer("Sent."))), c.task(done("Sent.")))
    }

    @Test fun takeoverIsSpokenThenHolds() {
        val c = VoiceConversation()
        sentOne(c)
        val t = TaskState.WaitingTakeover("captcha", "Please solve the captcha")
        assertEquals(listOf(Effect.Speak(Speech.Takeover("Please solve the captcha"))), c.task(t))
        c.spoken()
        assertEquals(Phase.Holding, c.phase)
    }

    @Test fun failureAndCancelEndTheConversation() {
        val c = VoiceConversation()
        sentOne(c)
        assertEquals(listOf(Effect.End(EndReason.Failed)), c.task(TaskState.Failed("x", FailKind.GaveUp)))
        assertEquals(Phase.Off, c.phase)
        assertFalse(c.active)

        val d = VoiceConversation()
        sentOne(d)
        d.task(done("A"))
        // 正在读时被取消:先停读
        assertEquals(listOf(Effect.StopSpeaking, Effect.End(EndReason.Cancelled)), d.task(TaskState.Cancelled))
    }

    @Test fun tapWhileSpeakingStopsAndListens() {
        val c = VoiceConversation()
        sentOne(c)
        c.task(done("long answer"))
        assertEquals(listOf(Effect.StopSpeaking, Effect.Listen), c.tap())
        assertEquals(Phase.Listening, c.phase)
        // 停下后的「读完」回调不再起作用
        assertEquals(emptyList<Effect>(), c.spoken())
    }

    @Test fun tapWhileListeningFinishesAndWhileWaitingListens() {
        val c = VoiceConversation()
        c.start(null)
        assertEquals(listOf(Effect.FinishListening), c.tap())
        c.heard("hello"); c.sent(true, null)
        assertEquals(listOf(Effect.Listen), c.tap())
        assertEquals(Phase.Listening, c.phase)
    }

    @Test fun silenceRetriesThenEnds() {
        val c = VoiceConversation(maxEmptyListens = 2)
        c.start(null)
        assertEquals(listOf(Effect.Listen), c.heard(""))
        assertEquals(listOf(Effect.End(EndReason.Silence)), c.heard("  "))
        assertEquals(Phase.Off, c.phase)
    }

    @Test fun answerArrivingWhileListeningWaitsForTheUser() {
        val c = VoiceConversation()
        sentOne(c)
        c.tap() // 插话:改听
        assertEquals(emptyList<Effect>(), c.task(done("A")))
        assertEquals(Phase.Listening, c.phase)
        // 用户什么也没说:读刚才的回答
        assertEquals(listOf(Effect.Speak(Speech.Answer("A"))), c.heard(""))

        val d = VoiceConversation()
        sentOne(d)
        d.tap()
        d.task(done("A"))
        // 用户说了新的话:发出去,刚才的回答不读
        assertEquals(listOf(Effect.Send("next")), d.heard("next"))
        d.sent(true, done("A"))
        assertEquals(emptyList<Effect>(), d.task(done("A")))
    }

    @Test fun notSentEnds() {
        val c = VoiceConversation()
        c.start(null)
        c.heard("hi")
        assertEquals(listOf(Effect.End(EndReason.NotSent)), c.sent(false, null))
    }

    @Test fun startWhileTaskRunsWaitsAndWhileConfirmingHolds() {
        val c = VoiceConversation()
        assertEquals(emptyList<Effect>(), c.start(running))
        assertEquals(Phase.Waiting, c.phase)
        assertEquals(listOf(Effect.Speak(Speech.Answer("A"))), c.task(done("A")))

        val d = VoiceConversation()
        assertEquals(emptyList<Effect>(), d.start(confirm))
        assertEquals(Phase.Holding, d.phase)
        // 开始时就在的确认请求不再读一遍
        assertEquals(emptyList<Effect>(), d.task(confirm))
    }

    @Test fun startWithOldAnswerDoesNotReadIt() {
        val c = VoiceConversation()
        assertEquals(listOf(Effect.Listen), c.start(done("old")))
        assertEquals(emptyList<Effect>(), c.task(done("old")))
    }

    @Test fun stopCancelsWhateverIsGoing() {
        val c = VoiceConversation()
        c.start(null)
        assertEquals(listOf(Effect.CancelListening, Effect.End(EndReason.User)), c.stop())
        assertEquals(emptyList<Effect>(), c.stop())
        assertEquals(emptyList<Effect>(), c.task(done("A")))
    }

    @Test fun listenErrorAndInterruptionEnd() {
        val c = VoiceConversation()
        c.start(null)
        assertEquals(listOf(Effect.End(EndReason.ListenError)), c.listenFailed())
        val d = VoiceConversation()
        sentOne(d)
        d.task(done("A"))
        assertEquals(listOf(Effect.StopSpeaking, Effect.End(EndReason.Interrupted)), d.interrupted())
    }
}

class AnswerWatchTest {
    @Test fun readsOnlyNewAnswers() {
        val w = AnswerWatch(TaskState.Succeeded("old"))
        assertNull(w.next(TaskState.Succeeded("old")))
        assertNull(w.next(TaskState.Running(1, "")))
        assertEquals("new", w.next(TaskState.Succeeded("new")))
        assertNull(w.next(TaskState.Succeeded("new")))
        assertNull(w.next(TaskState.Succeeded(" ")))
        assertNull(w.next(null))
        assertTrue(w.next(TaskState.Succeeded("again")) == "again")
    }
}
