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
import io.github.clemsix6.narrator2.trace.TaskRecord
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Grouping and heartbeats, in plain milliseconds. */
class WakeSchedulerTest {
    private val debounce = 1_500L
    private var ids = 1L

    private fun event(at: Long, urgency: Urgency, event: GameEvent) = Item(DirectorSignal.Event(Envelope(ids++, at, 0, event), urgency, null), at)

    private fun addressed(at: Long) = event(at, Urgency.IMMEDIATE, ChatMessage(steve, ChatChannel.PUBLIC, "narrateur ?", emptyList()))

    private fun between(at: Long) = event(at, Urgency.NEXT, ChatMessage(steve, ChatChannel.PUBLIC, "salut", emptyList()))

    private fun death(at: Long) = event(at, Urgency.SOON, PlayerDied(steve, here, "minecraft:fall", null, null, null, 0, false, false))

    private fun teleport(at: Long) = event(at, Urgency.NEXT, Teleported(steve, here, here, 100.0))

    private fun wakeAt(vararg items: Item, notBefore: Long? = null) = WakeScheduler.wakeAt(items.toList(), debounce, notBefore)

    @Test
    fun `nothing waiting, no wake-up`() {
        assertNull(wakeAt())
    }

    @Test
    fun `addressed chat wakes the debounce after the last message, at most 4 s after the first`() {
        assertEquals(1_500, wakeAt(addressed(0)))
        assertEquals(3_500, wakeAt(addressed(0), addressed(1_000), addressed(2_000)))
        assertEquals(4_000, wakeAt(addressed(0), addressed(1_000), addressed(2_000), addressed(3_000)))
    }

    @Test
    fun `a whisper wakes like addressed chat`() {
        assertEquals(1_500, wakeAt(Item.of(DirectorSignal.OperatorWhisper("Clement", "Be nice.", 0))))
    }

    @Test
    fun `salient events wake 3 s after the last one, at most 10 s after the first`() {
        assertEquals(3_000, wakeAt(death(0)))
        assertEquals(7_000, wakeAt(death(0), death(4_000)))
        assertEquals(10_000, wakeAt(death(0), death(2_500), death(5_000), death(7_500), death(9_000)))
    }

    @Test
    fun `task results and reminders wake like salient events`() {
        assertEquals(3_000, wakeAt(Item.of(DirectorSignal.TaskDone(1, Role.ANALYST, TaskRecord.Status.DONE, "done", 0))))
        assertEquals(3_000, wakeAt(Item.of(DirectorSignal.ReminderDue(1, "check", -60_000, 0))))
    }

    @Test
    fun `chat between players waits for company, 45 s at most`() {
        assertEquals(45_000, wakeAt(between(0)))
        assertEquals(45_000, wakeAt(between(0), between(1_000), between(2_000)))
        assertEquals(6_000, wakeAt(between(0), between(1_000), between(2_000), between(3_000)), "the 4th message counts as salient")
        assertEquals(12_000, wakeAt(between(0), between(1_000), between(2_000), between(3_000), between(5_000), between(9_000)))
        assertEquals(13_000, wakeAt(between(0), between(1_000), between(2_000), between(3_000), between(6_000), between(11_000)))
    }

    @Test
    fun `other next events never wake the Director`() {
        assertNull(wakeAt(teleport(0), teleport(1_000)))
        assertEquals(1_500, wakeAt(teleport(0), addressed(0)))
    }

    @Test
    fun `the earliest wake-up wins`() {
        assertEquals(1_500, wakeAt(death(0), addressed(0)))
        assertEquals(3_000, wakeAt(death(0), addressed(2_500)))
    }

    @Test
    fun `never before notBefore, after a refused reply`() {
        assertEquals(30_000, wakeAt(addressed(0), notBefore = 30_000))
        assertEquals(1_500, wakeAt(addressed(0), notBefore = 1_000))
    }

    @Test
    fun `items that failed twice no longer wake the Director`() {
        assertNull(wakeAt(death(0).copy(failures = Item.DEMOTED)))
        assertEquals(3_000, wakeAt(death(0).copy(failures = 1)))
    }

    @Test
    fun `heartbeats fall at a whole second within the configured minutes`() {
        val random = Random(3)
        repeat(200) {
            val at = WakeScheduler.nextHeartbeat(1_000, 3..8, random)
            assertTrue(at in 1_000 + 180_000..1_000 + 480_000, "$at")
            assertEquals(0, (at - 1_000) % 1_000)
        }
        assertEquals(1_000 + 180_000, WakeScheduler.nextHeartbeat(1_000, 3..3, random))
    }
}
