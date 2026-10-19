package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.trace.TaskRecord
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Grouping, the budget of turns and heartbeats, in plain milliseconds. */
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
    fun `chat between players never wakes the Director, however much of it waits`() {
        assertNull(wakeAt(between(0)))
        assertNull(wakeAt(*(0 until 50).map { between(it * 1_000L) }.toTypedArray()), "it waits for the next heartbeat")
        assertEquals(3_000, wakeAt(between(0), death(0)), "it goes with the next wake-up")
    }

    @Test
    fun `once the budget of turns is spent, only addressed chat, prayers and whispers wake the Director`() {
        assertNull(WakeScheduler.wakeAt(listOf(death(0)), debounce, throttled = true))
        assertNull(WakeScheduler.wakeAt(listOf(Item.of(DirectorSignal.TaskDone(1, Role.ANALYST, TaskRecord.Status.DONE, "done", 0))), debounce, throttled = true))
        assertEquals(1_500, WakeScheduler.wakeAt(listOf(death(0), addressed(0)), debounce, throttled = true))
        assertEquals(1_500, WakeScheduler.wakeAt(listOf(Item.of(DirectorSignal.OperatorWhisper("Clement", "Be nice.", 0))), debounce, throttled = true))
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(death(0)), debounce, throttled = false))
    }

    @Test
    fun `a due commitment wakes like a salient event, even once the budget of turns is spent`() {
        val due = Item.of(DirectorSignal.MemoryDue(Ref(RefKind.CANON, 45), "C-45 promise → Mimosa, set 1h ago: « je te rends tes bottes »", 0))
        assertEquals(Urgency.SOON, due.urgency)
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(due), debounce))
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(due), debounce, throttled = true))
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(death(0), due), debounce, throttled = true), "the death goes with it")
    }

    @Test
    fun `a mechanic's news wakes like a salient event, and its priority news even once the budget of turns is spent (M4 §4-4)`() {
        val won = Item.of(DirectorSignal.Mechanic(MechanicSignal.CHALLENGE, "C-50 won by Mimosa", 0, priority = true))
        val joker = Item.of(DirectorSignal.Mechanic(MechanicSignal.JOKER, "Bartok played a joker", 0, priority = false))
        assertEquals(Urgency.SOON, won.urgency)
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(joker), debounce))
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(won), debounce, throttled = true))
        assertNull(WakeScheduler.wakeAt(listOf(joker), debounce, throttled = true), "a joker played waits for a turn to leave the hour")
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(joker, won), debounce, throttled = true), "the joker goes with the challenge's end")
        assertEquals(listOf(true, false), listOf(won, joker).map(WakeScheduler::pastBudget))
        assertEquals(1.0, WakeScheduler.salience(listOf(joker)), "a mechanic's news is worth a heartbeat by itself")
    }

    /** M4 review, finding 6: an offering is a player's own news; past the budget, it wakes the Director only within a sub-budget. */
    @Test
    fun `past the budget, an offering wakes the Director only while the players' news have wake-ups left`() {
        val offering = Item.of(DirectorSignal.Mechanic(MechanicSignal.OFFERING, "O-812 Mimosa offers 1x dirt", 0, priority = true))
        val back = Item.of(DirectorSignal.Mechanic(MechanicSignal.OFFERING, "O-811 went back to Bartok", 0, priority = false))
        val won = Item.of(DirectorSignal.Mechanic(MechanicSignal.CHALLENGE, "C-50 won by Mimosa", 0, priority = true))
        val due = Item.of(DirectorSignal.MemoryDue(Ref(RefKind.CANON, 45), "C-45 promise → Mimosa", 0))
        assertEquals(listOf(true, false, false), listOf(offering, back, won).map { WakeScheduler.byPlayer(it.signal) })

        assertEquals(3_000, WakeScheduler.wakeAt(listOf(offering), debounce, throttled = true, playerNewsSpent = false))
        assertNull(WakeScheduler.wakeAt(listOf(offering), debounce, throttled = true, playerNewsSpent = true), "it waits for a turn to leave the hour")
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(offering), debounce, throttled = false, playerNewsSpent = true), "within the budget, it wakes like any news")
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(offering, won), debounce, throttled = true, playerNewsSpent = true), "it goes with a challenge's end")
        assertEquals(3_000, WakeScheduler.wakeAt(listOf(offering, due), debounce, throttled = true, playerNewsSpent = true), "and with a due commitment")

        assertTrue(WakeScheduler.wokenByPlayers(listOf(offering, back, death(0))), "the offering alone wakes it past the budget")
        assertFalse(WakeScheduler.wokenByPlayers(listOf(offering, won)), "the challenge's end would have woken it anyway")
        assertFalse(WakeScheduler.wokenByPlayers(listOf(offering, due)))
        assertFalse(WakeScheduler.wokenByPlayers(listOf(offering, addressed(0))))
        assertFalse(WakeScheduler.wokenByPlayers(listOf(back, death(0))), "no offering waits")
        assertFalse(WakeScheduler.wokenByPlayers(listOf(offering.copy(failures = Item.DEMOTED))), "a demoted offering wakes nobody")
    }

    @Test
    fun `the budget counts the wake-ups past it that the players' news alone caused, over a sliding hour`() {
        val budget = TurnBudget { 30 }
        repeat(WakeScheduler.PLAYER_NEWS_PER_HOUR - 1) { budget.started(it * 60_000L, byPlayers = true) }
        budget.started(200_000)
        assertEquals(WakeScheduler.PLAYER_NEWS_PER_HOUR - 1, budget.playerNewsUsed(300_000))
        assertFalse(budget.playerNewsSpent(300_000))
        budget.started(300_000, byPlayers = true)
        assertTrue(budget.playerNewsSpent(300_000))
        assertEquals(WakeScheduler.PLAYER_NEWS_PER_HOUR + 1, budget.used(300_000), "they are turns of the budget too")
        assertFalse(budget.playerNewsSpent(3_600_000), "the first one left the hour")
    }

    @Test
    fun `the memory's notes and cues never wake the Director, a cue is worth a heartbeat like a next event and a note nothing`() {
        val note = Item.of(DirectorSignal.MemoryNote(Ref(RefKind.NOTE, 9), NoteKind.COHERENCE, "le Narrateur a dit…", 0))
        val cue = Item.of(DirectorSignal.MemoryCue(CueKind.RETURN, Ref(RefKind.MEMORY, 77), steve, "Steve is where M-77 happened", 0))
        assertNull(WakeScheduler.wakeAt(listOf(note, cue), debounce))
        val heuristic = HeartbeatVerdict(1.0, null, HeuristicReflexes.SOURCE, 0)
        assertEquals(WakeScheduler.HeartbeatWorth(0.0, "worth 0.00 by 0 salient items waiting"), WakeScheduler.heartbeatWorth(heuristic, listOf(note, note)))
        assertEquals(WakeScheduler.HeartbeatWorth(0.5, "worth 0.50 by 2 salient items waiting"), WakeScheduler.heartbeatWorth(heuristic, listOf(cue, note, teleport(0))))
    }

    @Test
    fun `the budget counts the turns started within the last hour`() {
        var perHour = 3
        val budget = TurnBudget { perHour }
        budget.started(0)
        budget.started(1_000)
        assertFalse(budget.spent(2_000))
        budget.started(2_000)
        assertTrue(budget.spent(2_000))
        assertEquals("the budget of 3 turns per hour is spent", budget.describe())
        assertTrue(budget.spent(3_599_999), "the first turn is still within the hour")
        assertFalse(budget.spent(3_600_000), "the first turn left the hour")
        assertEquals(2, budget.used(3_600_000))
        perHour = 2
        assertTrue(budget.spent(3_600_000), "the setting is read each time")
    }

    @Test
    fun `without a classifier a heartbeat is worth the salient items waiting, not the activity`() {
        val heuristic = HeartbeatVerdict(1.0, null, HeuristicReflexes.SOURCE, 0)
        assertEquals(WakeScheduler.HeartbeatWorth(0.0, "worth 0.00 by 0 salient items waiting"), WakeScheduler.heartbeatWorth(heuristic, emptyList()))
        assertEquals(0.25, WakeScheduler.heartbeatWorth(heuristic, listOf(teleport(0))).value)
        assertEquals(
            WakeScheduler.HeartbeatWorth(0.5, "worth 0.50 by 2 salient items waiting"),
            WakeScheduler.heartbeatWorth(heuristic, listOf(teleport(0), teleport(1_000))),
        )
        assertEquals(0.375, WakeScheduler.heartbeatWorth(heuristic, listOf(between(0), between(1), between(2))).value, "3 chat lines between players")
        assertEquals(0.5, WakeScheduler.heartbeatWorth(heuristic, listOf(between(0), between(1), between(2), between(3))).value, "4 of them wake it")
        assertEquals(1.0, WakeScheduler.heartbeatWorth(null, listOf(death(0), teleport(0))).value, "capped at 1; no verdict: what waits alone")
        assertEquals(0.0, WakeScheduler.heartbeatWorth(heuristic, listOf(teleport(0).copy(failures = Item.DEMOTED))).value, "demoted items count for nothing")
    }

    /**
     * M4-24, finding 5, the evening of the acceptance: three lines between players, one of them bored, were worth 0.38
     * (the model said 0.15 of the activity) and the heartbeat was skipped. A bored line alone wakes the Director now.
     */
    @Test
    fun `a player who says they are bored makes the heartbeat worth a wake-up`() {
        val bored = HeuristicReflexes.forLanguage("fr")::bored
        fun says(text: String) = event(0, Urgency.NEXT, ChatMessage(steve, ChatChannel.PUBLIC, text, emptyList()))
        val evening = listOf(says("ah ça y est, j'ai fini ma maison"), says("Moi je m'ennuie un peu, on fait quoi après ?"), says("pareil"))
        val model = HeartbeatVerdict(0.15, "other", "reflex:claude-haiku-5-5", 40)

        val worth = WakeScheduler.heartbeatWorth(model, evening, bored)
        assertEquals(
            WakeScheduler.HeartbeatWorth(0.75, "worth 0.75 by 3 salient items waiting (1 saying a player is bored) (reflex:claude-haiku-5-5 said 0.15)"),
            worth,
        )
        assertTrue(worth.value >= ReflexThresholds.HEARTBEAT_WORTH, "over the threshold")
        assertEquals(0.5, WakeScheduler.heartbeatWorth(null, listOf(says("c'est mort ici")), bored).value, "one is enough")
        assertEquals(0.375, WakeScheduler.heartbeatWorth(model, evening).value, "without the boredom words: banter, as before")
        val addressed = event(0, Urgency.IMMEDIATE, ChatMessage(steve, ChatChannel.PUBLIC, "narrateur je m'ennuie", emptyList()))
        assertEquals(1.0, WakeScheduler.salience(listOf(addressed), bored), "addressed: it wakes the Director by itself anyway")
    }

    @Test
    fun `a classifier's verdict counts, never below what waits`() {
        val model = HeartbeatVerdict(0.31, "comedy", "reflex:claude-haiku-5-5", 40)
        assertEquals(WakeScheduler.HeartbeatWorth(0.31, "worth 0.31 by reflex:claude-haiku-5-5"), WakeScheduler.heartbeatWorth(model, listOf(teleport(0))))
        assertEquals(
            WakeScheduler.HeartbeatWorth(0.5, "worth 0.50 by 2 salient items waiting (reflex:claude-haiku-5-5 said 0.31)"),
            WakeScheduler.heartbeatWorth(model, listOf(teleport(0), teleport(1))),
        )
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
