package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a wake-up costs (M2 review, findings 14 and 26), in virtual time against the strict fake server:
 * chat between players waits for a heartbeat, a heartbeat without the reflex model is worth what salient events wait,
 * the turns per hour have a ceiling, and a single player cannot spend the turns of everyone.
 */
class WakeBudgetScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val here = Pos("minecraft:overworld", 10, 60, 10)

    private fun DirectorHarness.decisions(kind: DecisionRecord.Kind, decision: String) =
        traces.decisions.filter { it.kind == kind && it.decision == decision }

    /** Without the reflex model, the reflexes' heartbeat verdict is the heuristic's: always 1.0. */
    private fun DirectorHarness.withoutReflexModel() {
        reflexes.heartbeat = { HeartbeatVerdict(worth = 1.0, hook = null, source = HeuristicReflexes.SOURCE, latencyMs = 0) }
    }

    private fun DirectorHarness.died(name: String, cause: String = "minecraft:lava") =
        publish(PlayerDied(DirectorHarness.ref(name), here, cause, null, null, null, 3, false, false))

    private fun DirectorHarness.mined(name: String, block: String) =
        publish(BlockBroken(DirectorHarness.ref(name), here, block, null, false, true, null))

    /** Pumps until no turn runs, the one a wake-up may just have started included. */
    private fun DirectorHarness.settle() = awaitUntil("no turn running") {
        director.status().turnId == null && director.status().state != DirectorState.RUNNING
    }

    /** The turns started so far, ended or not: a turn's row is written in virtual time, before its first request. */
    private fun DirectorHarness.startedTurns(): Int = traces.turns.map { it.turnId }.distinct().size

    @Test
    fun `chat between players never wakes the Director, it goes with the next heartbeat`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.withoutReflexModel()
        h.fake.enqueue(reply { text("…") })

        repeat(6) { i ->
            h.chat("Bartok", "on parle entre nous $i", toNarrator = 0.1)
            h.advance(10_000)
        }
        h.advance(100_000)
        assertEquals(0, h.startedTurns(), "160 s of chat between players, and no wake-up")

        h.advance(20_000) // the heartbeat, 3 minutes after the start
        h.awaitTurns(1)
        val wake = h.userText(0)
        (0 until 6).forEach { assertContains(wake, "CHAT Bartok (between players): \"on parle entre nous $it\"") }
        assertContains(wake, "<heartbeat now=\"21:35:00\" window=\"3m\">")
        assertEquals(TurnRecord.Trigger.HEARTBEAT, h.endedTurns().single().trigger)
        assertEquals("worth 0.75 by 6 salient items waiting, hook none", h.decisions(DecisionRecord.Kind.HEARTBEAT, "woke").single().reason)
        h.assertInvariants()
    }

    @Test
    fun `without the reflex model, a heartbeat is worth the salient events waiting, not the activity`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.withoutReflexModel()

        repeat(10) { h.mined("Bartok", "minecraft:stone") }
        h.advance(180_000)
        assertEquals(0, h.startedTurns(), "mining stone is activity, not a salient event")
        assertEquals("worth 0.00 by 0 salient items waiting < 0.50", h.decisions(DecisionRecord.Kind.HEARTBEAT, "skipped").single().reason)

        h.mined("Bartok", "minecraft:diamond_ore")
        h.advance(180_000)
        assertEquals(0, h.startedTurns(), "one diamond is not enough")
        assertEquals("worth 0.25 by 1 salient item waiting < 0.50", h.decisions(DecisionRecord.Kind.HEARTBEAT, "skipped").last().reason)

        h.mined("Bartok", "minecraft:deepslate_diamond_ore")
        h.fake.enqueue(reply { text("Deux diamants.") })
        h.advance(180_000)
        h.awaitTurns(1)
        assertEquals(TurnRecord.Trigger.HEARTBEAT, h.endedTurns().single().trigger)
        assertEquals("worth 0.50 by 2 salient items waiting, hook none", h.decisions(DecisionRecord.Kind.HEARTBEAT, "woke").single().reason)
        val wake = h.userText(0)
        assertContains(wake, "MINED Bartok diamond_ore")
        assertContains(wake, "MINED Bartok deepslate_diamond_ore")
        h.assertInvariants()
    }

    @Test
    fun `past the budget of turns per hour, only addressed chat and prayers wake the Director`() = DirectorHarness(dir, turnsPerHour = 2).use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        repeat(4) { h.fake.enqueue(reply { text("…") }) }
        val start = h.clock.millis()

        h.died("Bartok")
        h.advance(3_000)
        h.awaitTurns(1)
        h.died("Mimosa")
        h.advance(3_000)
        h.awaitTurns(2)

        val held = h.died("Bartok", "minecraft:fall")
        h.advance(240_000)
        assertEquals(2, h.startedTurns(), "a salient event no longer wakes the Director")
        assertEquals(2 to 2, h.director.status().let { it.turnsThisHour to it.turnsPerHour }, "/narrator status shows the budget spent")
        val throttled = h.decisions(DecisionRecord.Kind.SALIENCE, "throttled")
        assertEquals(
            "death; the budget of 2 turns per hour is spent: it waits for a turn to leave the hour",
            throttled.single { it.eventId == held.id }.reason,
        )
        assertEquals(
            "the budget of 2 turns per hour is spent: no heartbeat until a turn leaves the hour",
            throttled.single { it.subject == "heartbeat" }.reason,
        )

        val chat = h.chat("Mimosa", "narrateur, tu dors ?")
        h.advance(1_500)
        h.awaitTurns(3)
        assertEquals(listOf(chat.id, held.id).sorted(), h.endedTurns()[2].triggerEvents.sorted(), "the held death goes with the chat")

        // Once the first two turns have left the hour (they started 3 s and 6 s in), a salient event wakes the Director again.
        h.advance(start + 3_607_000 - h.clock.millis())
        assertEquals(3, h.startedTurns(), "nothing woke the Director meanwhile")
        assertEquals(1, h.director.status().turnsThisHour, "the first two turns left the hour")
        h.died("Mimosa", "minecraft:drown")
        h.advance(3_000)
        h.awaitTurns(4)
        assertEquals(TurnRecord.Trigger.SALIENT, h.endedTurns()[3].trigger)
        h.assertInvariants()
    }

    @Test
    fun `past the budget, a mechanic's priority news still wakes the Director, its other news wait (M4 §4-4)`() = DirectorHarness(dir, turnsPerHour = 1).use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        repeat(2) { h.fake.enqueue(reply { text("…") }) }

        h.died("Bartok")
        h.advance(3_000)
        h.awaitTurns(1)

        val joker = DirectorSignal.Mechanic(MechanicSignal.JOKER, "Bartok played a joker: slowness II lifted", h.clock.millis(), priority = false)
        h.director.post(joker)
        h.advance(240_000)
        assertEquals(1, h.startedTurns(), "a joker played waits for a turn to leave the hour")

        val won = DirectorSignal.Mechanic(MechanicSignal.CHALLENGE, "C-50 won by Mimosa in 9m12s (turtle_egg 1/1)", h.clock.millis(), priority = true)
        h.director.post(won)
        h.advance(3_000)
        h.awaitTurns(2)

        val wake = h.wakeText(h.fake.requests.size - 1)
        assertContains(wake, "[-4m03s] JOKER Bartok played a joker: slowness II lifted\n")
        assertContains(wake, "[-3s] CHALLENGE C-50 won by Mimosa in 9m12s (turtle_egg 1/1)\n")
        assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns()[1].trigger)
        assertEquals(2 to 1, h.director.status().let { it.turnsThisHour to it.turnsPerHour }, "the priority news is past the budget")
        h.assertInvariants()
    }

    /**
     * M4 review, finding 6: the players' offerings wake the Director past the budget, but 4 times an hour at most
     * for them all; beyond, an offering waits like a salient event, and goes with the next wake-up.
     */
    @Test
    fun `past the budget, the players' offerings wake the Director 4 times an hour, then wait`() = DirectorHarness(dir, turnsPerHour = 1).use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        repeat(7) { h.fake.enqueue(reply { text("…") }) }
        h.died("Bartok")
        h.advance(3_000)
        h.awaitTurns(1)

        fun offer(id: Int, who: String) =
            h.director.post(DirectorSignal.Mechanic(MechanicSignal.OFFERING, "O-$id $who offers 1x dirt: take it within 10m", h.clock.millis(), priority = true))
        listOf("Mimosa", "Bartok", "Mimosa", "Bartok").forEachIndexed { i, who ->
            offer(800 + i, who)
            h.advance(3_000)
            h.awaitTurns(2 + i)
            h.advance(60_000)
        }
        val woke = h.decisions(DecisionRecord.Kind.WAKE, "woke").drop(1).map { it.reason }
        assertEquals((1..4).map { "mechanic OFFERING O-${799 + it}; past the budget for the players' news ($it of 4 this hour)" }, woke)

        offer(804, "Mimosa")
        h.advance(600_000)
        assertEquals(5, h.startedTurns(), "the fifth offering of the hour waits")
        assertEquals(
            "the budget of 1 turn per hour is spent, and so are the 4 wake-ups past it for the players' news this hour: it waits for a turn to leave the hour",
            h.decisions(DecisionRecord.Kind.SALIENCE, "throttled").single { it.subject == "OFFERING O-804" }.reason,
        )

        // A prayer still wakes the Director; the offering goes with it, and no wake-up of the players' news is spent.
        h.chat("Mimosa", "narrateur, prends mon offrande", channel = ChatChannel.PRAY)
        h.advance(1_500)
        h.awaitTurns(6)
        assertContains(h.wakeText(h.fake.requests.size - 1), "OFFERING O-804 Mimosa offers 1x dirt")
        assertEquals(TurnRecord.Trigger.PRAY, h.endedTurns()[5].trigger)
        assertEquals(6 to 1, h.director.status().let { it.turnsThisHour to it.turnsPerHour }, "the 4 offerings' turns count in the hour")
        h.assertInvariants()
    }

    @Test
    fun `a player who prays every 10 s cannot spend the turns of everyone`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        repeat(80) { h.fake.enqueue(reply { text("…") }) }
        val start = h.clock.millis()

        repeat(180) { i -> // 30 minutes
            if (i == 150) h.chat("Mimosa", "narrateur, et moi ?")
            h.chat("Bartok", "pitié $i", channel = ChatChannel.PRAY)
            h.advance(5_000)
            h.settle()
            h.advance(5_000)
            h.settle()
        }
        val turnsAt = h.endedTurns().map { it.startedAt.toEpochMilli() - start }

        // 6 at once, then one per prayer grouped as a salient event while the budget of 30 turns lasts…
        assertEquals(30, turnsAt.count { it < 300_000 }, "turns in the first 5 minutes: $turnsAt")
        // …then Bartok only wakes the Director 6 times per 10 minutes, and Mimosa still gets hers.
        val later = turnsAt.filter { it >= 300_000 }
        assertEquals(13, later.size, "turns after the first 5 minutes: $later")
        (300_000L until 1_800_000L step 60_000L).forEach { from ->
            assertTrue(later.count { it in from until from + 600_000 } <= 7, "at most 6 of Bartok's and Mimosa's in 10 minutes from $from: $later")
        }
        val mimosa = h.traces.decisions.single { it.kind == DecisionRecord.Kind.SALIENCE && it.subject == "Mimosa" }
        assertEquals("immediate", mimosa.decision)
        assertTrue(h.decisions(DecisionRecord.Kind.SALIENCE, "throttled").all { it.subject == "Bartok" || it.subject == "heartbeat" })
        h.assertInvariants()
    }

    @Test
    fun `a player who calls the Narrator in a loop wakes it 6 times, then as a salient event within the budget`() =
        DirectorHarness(dir, turnsPerHour = 10).use { h ->
            h.online("Bartok")
            repeat(40) { h.fake.enqueue(reply { text("…") }) }

            repeat(300) { i -> // a message every 2 s for 10 minutes
                h.chat("Bartok", "narrateur ! $i")
                h.advance(2_000)
                h.settle()
            }
            h.advance(60_000)
            h.settle()

            val turns = h.endedTurns()
            assertEquals(10, turns.size, "the budget of 10 turns, the 6 immediate wake-ups included: ${turns.map { it.trigger }}")
            assertEquals(6, h.decisions(DecisionRecord.Kind.SALIENCE, "immediate").size)
            assertEquals(294, h.decisions(DecisionRecord.Kind.SALIENCE, "throttled").count { it.subject == "Bartok" })
            h.assertInvariants()
        }
}
