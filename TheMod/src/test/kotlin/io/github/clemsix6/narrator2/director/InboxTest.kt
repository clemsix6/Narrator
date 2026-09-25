package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxTest {
    private var ids = 1L

    private fun event(urgency: Urgency, event: GameEvent) = DirectorSignal.Event(Envelope(ids++, ids * 10, 0, event), urgency, null)

    private fun chat() = event(Urgency.IMMEDIATE, ChatMessage(steve, ChatChannel.PUBLIC, "narrateur ?", emptyList()))

    private fun death() = event(Urgency.SOON, PlayerDied(steve, here, "minecraft:fall", null, null, null, 0, false, false))

    private fun teleport() = event(Urgency.NEXT, Teleported(steve, here, here, 100.0))

    @Test
    fun `items and control signals are kept apart`() {
        val inbox = Inbox()
        val chat = chat()
        inbox.post(chat)
        inbox.post(DirectorSignal.Pause)
        inbox.post(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR))
        assertEquals(3, inbox.size)
        assertEquals(listOf(DirectorSignal.Pause, DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR)), inbox.takeControls())
        assertEquals(listOf(chat), inbox.takeAll().map { it.signal })
        assertEquals(0, inbox.size)
    }

    @Test
    fun `items are dated by their signal`() {
        val chat = chat()
        assertEquals(chat.envelope.ts, Item.of(chat).at)
        assertEquals(42, Item.of(DirectorSignal.TaskDone(1, Role.ANALYST, TaskRecord.Status.DONE, "ok", 42)).at)
        assertEquals(42, Item.of(DirectorSignal.ReminderDue(1, "note", 0, 42)).at)
        assertEquals(42, Item.of(DirectorSignal.OperatorWhisper("Clement", "hi", 42)).at)
    }

    @Test
    fun `items put back go first, before what arrived since`() {
        val inbox = Inbox()
        val first = chat()
        inbox.post(first)
        val taken = inbox.takeAll()
        val later = death()
        inbox.post(later)
        inbox.putBack(taken.map { it.copy(failures = 1) })
        assertEquals(listOf(first to 1, later to 0), inbox.items().map { it.signal to it.failures })
    }

    @Test
    fun `a full inbox drops the oldest next event, then the oldest salient event, never what someone waits for`() {
        val inbox = Inbox(capacity = 3)
        val chat = chat()
        val death = death()
        val teleport = teleport()
        inbox.post(teleport)
        inbox.post(chat)
        inbox.post(death)
        val task = DirectorSignal.TaskDone(1, Role.ANALYST, TaskRecord.Status.DONE, "ok", 0)
        inbox.post(task)
        assertEquals(listOf(chat, death, task), inbox.items().map { it.signal })
        val whisper = DirectorSignal.OperatorWhisper("Clement", "hi", 0)
        inbox.post(whisper)
        assertEquals(listOf(chat, task, whisper), inbox.items().map { it.signal })
        val prayer = event(Urgency.IMMEDIATE, ChatMessage(steve, ChatChannel.PRAY, "pitié", emptyList()))
        inbox.post(prayer)
        assertEquals(listOf(chat, task, whisper, prayer), inbox.items().map { it.signal }, "over capacity rather than losing them")
        assertEquals(2, inbox.dropped)
    }

    @Test
    fun `await returns when something is posted, or after its timeout`() = runTest {
        val inbox = Inbox()
        inbox.await(1_000)
        assertEquals(1_000, testScheduler.currentTime)
        inbox.post(chat())
        inbox.await(null)
        assertEquals(1_000, testScheduler.currentTime, "a signal was waiting")
        inbox.changed()
        inbox.await(5_000)
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test
    fun `discard removes what matches`() {
        val inbox = Inbox()
        val chat = chat()
        inbox.post(chat)
        inbox.post(teleport())
        val gone = inbox.discard { it.urgency == Urgency.NEXT }
        assertEquals(1, gone.size)
        assertEquals(listOf(chat), inbox.items().map { it.signal })
        assertTrue(inbox.discard { false }.isEmpty())
    }
}
