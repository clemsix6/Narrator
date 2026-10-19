package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.director.DirectorHarness
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenarios S-M1 and S-M4 of M4 §21.3: the Director, against the strict fake Anthropic server, calls the real
 * `start_challenge` and `end_challenge` as the model writes them; the engine is a fake (the real one is assembled by
 * M4-23), which records what the tools hand it and gives back its lines.
 */
class ChallengeScenariosTest {
    @TempDir
    lateinit var dir: Path

    private fun harness(turnsPerHour: Int = 30) =
        DirectorHarness(dir, turnsPerHour = turnsPerHour, tools = listOf(StartChallengeTool(), EndChallengeTool()))

    /** S-M1: a heartbeat → `start_challenge` → the next situation shows the challenge → its end wakes the Director past his budget. */
    @Test
    fun `S-M1 - a heartbeat starts a challenge, the situation shows it, its end wakes the Director even past the budget`() = harness(turnsPerHour = 2).use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        repeat(3) { h.publish(BlockBroken(DirectorHarness.ref("Bartok"), Pos("minecraft:overworld", 10, 60, 10), "minecraft:stone", null, false, true, null)) }
        h.reflexes.heartbeat = { HeartbeatVerdict(worth = 0.8, hook = "idle", source = "fake", latencyMs = 0) }
        lateinit var startId: String
        h.fake.enqueue(
            reply {
                startId = toolUse(
                    "start_challenge",
                    """{"title": "Œuf de tortue", "statement": "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue en quinze minutes.",
                       "goal": "obtain", "item": "turtle_egg", "players": ["Bartok", "Mimosa"], "minutes": 15,
                       "reward": {"kind": "item", "item": "golden_apple"}, "penalty": {"punishment": "tiny", "severity": 2}}""",
                )
            },
        )
        h.fake.enqueue(reply { text("Défi lancé.") })

        h.advance(180_000)
        h.awaitTurns(1)

        val first = h.endedTurns().single()
        assertEquals(TurnRecord.Trigger.HEARTBEAT, first.trigger)
        val (spec, caller) = h.mechanics.challenges.started.single()
        assertEquals(listOf(GoalKind.OBTAIN, "turtle_egg", 15), listOf(spec.goal, spec.item, spec.minutes))
        assertEquals(RewardKind.ITEM to PunishmentId.TINY, spec.reward!!.kind to spec.penalty!!.punishment)
        assertEquals(first.turnId to first.conversationId, caller.turnId to caller.conversationId, "started by the turn")
        val result = h.lastUser(1).path("content")[0]
        assertEquals(startId, result.path("tool_use_id").asText())
        assertEquals(
            "ok: challenge C-1 started: obtain 1 turtle_egg « Œuf de tortue » · Bartok, Mimosa · ends 21:50 (in 15m) · reward 1x golden_apple · " +
                "penalty tiny (2); boss bars shown",
            result.path("content").asText(),
        )

        // The engine's line of the challenge, in the next situation.
        h.mechanics.lines = listOf("Challenges: C-1 obtain 1 turtle_egg « Œuf de tortue » · Bartok 0/1, Mimosa 0/1 · ends in 14m")
        h.fake.enqueue(reply { text("Toujours rien ?") })
        h.chat("Bartok", "narrateur, c'est quoi un œuf de tortue ?")
        h.advance(1_500)
        h.awaitTurns(2)
        assertContains(h.userText(2), "\nChallenges: C-1 obtain 1 turtle_egg « Œuf de tortue » · Bartok 0/1, Mimosa 0/1 · ends in 14m")

        // Its end: the budget of 2 turns is spent, the result still wakes him.
        h.mechanics.lines = emptyList()
        h.fake.enqueue(reply { text("Bravo, Mimosa.") })
        val line = "C-1 won by Mimosa in 9m12s (turtle_egg 1/1) · reward: Mimosa got 1x golden_apple · penalty: Bartok tiny (2) until 21:47"
        h.director.post(DirectorSignal.Mechanic(MechanicSignal.CHALLENGE, line, h.clock.millis(), priority = true))
        h.advance(3_000)
        h.awaitTurns(3)

        assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns()[2].trigger)
        assertContains(h.wakeText(3), "[-3s] CHALLENGE $line\n")
        assertEquals(3 to 2, h.director.status().let { it.turnsThisHour to it.turnsPerHour }, "past the budget")
        h.assertInvariants()
    }

    /** S-M4: a `JUDGE` → `end_challenge` with the winner the Director chose, and his note. */
    @Test
    fun `S-M4 - a JUDGE event is answered with end_challenge, the winner named and the note kept`() = harness().use { h ->
        h.online("Bartok")
        h.online("Mimosa")
        val view = ChallengeView(
            MechanicsSamples.canon(53), MechanicsSamples.statue, null, ChallengeStatus.JUDGING, h.clock.millis(),
            listOf("Bartok", "Mimosa").map { Participant(FakeChallenges.playerId(it).toString(), it) }, 0,
            "C-53 won by Mimosa (ended by the Director); note: « Mimosa a sculpté la plus belle statue. » · reward: Mimosa got 1x diamond_pickaxe (named)",
        )
        h.mechanics.challenges.views += view
        lateinit var endId: String
        h.fake.enqueue(
            reply {
                endId = toolUse(
                    "end_challenge",
                    """{"id": "C-53", "outcome": "won", "winners": ["Mimosa"], "note": "Mimosa a sculpté la plus belle statue."}""",
                )
            },
        )
        h.fake.enqueue(reply { text("Mimosa l'emporte.") })

        h.director.post(MechanicsSamples.judge.copy(at = h.clock.millis()))
        h.advance(3_000)
        h.awaitTurns(1)

        val turn = h.endedTurns().single()
        assertEquals(TurnRecord.Trigger.MECHANIC, turn.trigger)
        assertContains(h.wakeText(0), "JUDGE C-53 « La plus belle statue » ended: judge it now with end_challenge C-53 (winners)")
        assertEquals(
            FakeChallenges.Ended(
                MechanicsSamples.canon(53), ChallengeOutcome.WON, listOf(FakeChallenges.playerId("Mimosa")), "Mimosa a sculpté la plus belle statue.",
                Author(Source.DIRECTOR, turnId = turn.turnId, conversationId = turn.conversationId),
            ),
            h.mechanics.challenges.ended.single(),
        )
        val result = h.lastUser(1).path("content")[0]
        assertEquals(endId, result.path("tool_use_id").asText())
        assertEquals("ok: ${view.line}", result.path("content").asText(), "the tool answers with the line of the end")
        h.assertInvariants()
    }
}
