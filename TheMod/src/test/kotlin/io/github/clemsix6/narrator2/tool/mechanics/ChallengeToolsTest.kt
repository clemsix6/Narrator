package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `start_challenge` and `end_challenge` (M4 §5, §12, M4-17) against a fake engine: they are tools of the engine,
 * their descriptions are the design's, they hand the engine what the model wrote and answer with what it says,
 * its refusals included. What the engine does with them is `ChallengeStakesGameTests`.
 */
class ChallengeToolsTest {
    private val clock = TestClock(Instant.parse("2026-10-19T18:00:00Z"))
    private val mechanics = FakeMechanics(clock)
    private val services = TestServices(clock = clock, mechanics = mechanics)
    private val caller = Caller(Role.DIRECTOR, "director-c13", turnId = 42, taskId = null)
    private val tools = challengeTools(services).associateBy { it.name }

    private fun run(name: String, json: String): ToolResult = runBlocking { call(tools.getValue(name), json) }

    private suspend fun <A : Any> call(tool: NarratorTool<A>, json: String): ToolResult {
        val decoded = ToolArgs.decode(tool.args, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "$json: $decoded")
        return tool.run(ToolContext(services, caller, clock, tool.timeoutMs), decoded.args)
    }

    @Test
    fun `the two tools are the engine's, change the world, and say what the design says`() {
        assertEquals(listOf("start_challenge", "end_challenge"), challengeTools(services).map { it.name })
        for (tool in tools.values) {
            assertIs<MechanicsTool<*>>(tool)
            assertEquals(ToolEffect.WORLD, tool.effect)
            assertTrue(!tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet())), "${tool.name} hidden without the engine")
            assertTrue(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet(), mechanicsAvailable = true)))
        }
        assertEquals(
            "Starts a challenge the engine tracks: each participant sees a boss bar with their own progress; the engine detects who wins " +
                "(obtain an item, reach a place, kill creatures, survive, answer in the chat; judged: you name the winners when it ends), pays " +
                "the reward to the winners and the penalty to the losers under the engine's rules, settles the players' bets, and records it in " +
                "your canon (C-…). A CHALLENGE event tells you how it ended. Announce it yourself (say), with the stakes.",
            tools.getValue("start_challenge").description,
        )
        assertEquals(
            "Ends one of your challenges or duels now: won (name the winners: they get the reward, the others the penalty), lost (nobody won: " +
                "every participant gets the penalty) or cancelled (no stakes, bets go back). A judged challenge needs it when its JUDGE event " +
                "comes; the others end by themselves.",
            tools.getValue("end_challenge").description,
        )
    }

    // ── start_challenge ──

    @Test
    fun `start_challenge hands the engine the challenge as the model wrote it, and says what started`() {
        val json = """{"title": "Œuf de tortue", "statement": "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue.",
            "goal": "obtain", "item": "turtle_egg", "players": ["Bartok", "Mimosa"], "minutes": 15,
            "reward": {"kind": "item", "item": "golden_apple"}, "penalty": {"punishment": "tiny", "severity": 2}, "bets": true}"""

        val result = run("start_challenge", json)

        assertEquals(
            "ok: challenge C-1 started: obtain 1 turtle_egg « Œuf de tortue » · Bartok, Mimosa · ends 18:15 (in 15m) · reward 1x golden_apple · " +
                "penalty tiny (2) · bets open until 18:05; boss bars shown",
            result.text,
        )
        val (spec, by) = mechanics.challenges.started.single()
        assertEquals(caller, by)
        assertEquals(MechanicsSamples.turtleEgg.copy(statement = spec.statement, quote = null, importance = 5), spec)
        assertTrue(result.targets.isEmpty(), "the stakes are the engine's acts, never the Director's")
    }

    @Test
    fun `a short challenge keeps its bets open a minute at least, and no bets says nothing of them`() {
        val short = run(
            "start_challenge",
            """{"title": "Vite", "statement": "Le Narrateur a lancé un défi éclair.", "goal": "judged", "players": ["Bartok"], "minutes": 2, "bets": true}""",
        )
        assertEquals("ok: challenge C-1 started: judged « Vite » · Bartok · ends 18:02 (in 2m) · bets open until 18:01; boss bars shown", short.text)
        val none = run("start_challenge", """{"title": "Sans pari", "statement": "Le Narrateur a lancé un défi.", "goal": "judged", "players": ["Bartok"], "minutes": 30}""")
        assertEquals("ok: challenge C-2 started: judged « Sans pari » · Bartok · ends 18:30 (in 30m); boss bars shown", none.text)
    }

    @Test
    fun `the engine's refusals come back as they are, and its absence is a failure`() {
        val json = """{"title": "Trop tôt", "statement": "Le Narrateur a lancé un défi.", "goal": "judged", "minutes": 5}"""
        mechanics.challenges.rejection = MechanicRejected("challenge_pace", "the last challenge started 4m ago; the next may start at 20:10 (in 6m)")
        assertEquals("DENIED (challenge_pace): the last challenge started 4m ago; the next may start at 20:10 (in 6m)", run("start_challenge", json).text)
        mechanics.challenges.rejection = MechanicRejected(null, "reward: give_item refuses 1x netherite_block: …")
        assertEquals("Invalid arguments: reward: give_item refuses 1x netherite_block: …", run("start_challenge", json).text)

        mechanics.available = false
        mechanics.reason = "not wired yet"
        assertEquals("FAILED: the mechanics are unavailable: not wired yet", run("start_challenge", json).text)
        assertEquals(
            "FAILED: the mechanics are unavailable: not wired yet",
            run("end_challenge", """{"id": "C-1", "outcome": "cancelled", "note": "Le défi a été annulé."}""").text,
        )
    }

    // ── end_challenge ──

    /** C-53, the statue being judged, between Bartok and Mimosa. */
    private fun judging(): ChallengeView {
        val view = ChallengeView(
            MechanicsSamples.canon(53), MechanicsSamples.statue, null, ChallengeStatus.JUDGING, clock.millis(),
            listOf("Bartok", "Mimosa").map { Participant(FakeChallenges.playerId(it).toString(), it) }, 0,
            "C-53 won by Mimosa (ended by the Director); note: « Mimosa a sculpté la plus belle statue. » · reward: Mimosa got 1x diamond_pickaxe (named)",
        )
        mechanics.challenges.views += view
        return view
    }

    @Test
    fun `end_challenge names the winners among the participants, as the Director, and answers with the line of the end`() {
        val view = judging()

        val result = run("end_challenge", """{"id": "c53", "outcome": "won", "winners": ["mimosa", "Mimosa"], "note": "Mimosa a sculpté la plus belle statue."}""")

        assertEquals("ok: ${view.line}", result.text)
        assertEquals(
            FakeChallenges.Ended(
                MechanicsSamples.canon(53), ChallengeOutcome.WON, listOf(FakeChallenges.playerId("Mimosa")), "Mimosa a sculpté la plus belle statue.",
                Author(Source.DIRECTOR, turnId = 42, conversationId = "director-c13"),
            ),
            mechanics.challenges.ended.single(),
        )
    }

    @Test
    fun `end_challenge refuses a winner who took no part, winners without won, and an id that is no challenge`() {
        judging()
        assertEquals(
            "Invalid arguments: won needs winners among the participants: Bartok, Mimosa",
            run("end_challenge", """{"id": "C-53", "outcome": "won", "winners": ["Josiane"], "note": "Josiane a gagné."}""").text,
        )
        assertEquals(
            "Invalid arguments: winners only go with won: leave them out",
            run("end_challenge", """{"id": "C-53", "outcome": "lost", "winners": ["Bartok"], "note": "Personne n'a gagné."}""").text,
        )
        assertEquals(
            "Invalid arguments: id is one of your challenges or duels, like C-50 (got \"M-53\")",
            run("end_challenge", """{"id": "M-53", "outcome": "cancelled", "note": "Annulé."}""").text,
        )
        assertTrue(mechanics.challenges.ended.isEmpty(), "nothing reached the engine")

        val lost = run("end_challenge", """{"id": "53", "outcome": "lost", "note": "Personne n'a bâti de statue."}""")
        assertTrue(lost.text.startsWith("ok: C-53 "), lost.text)
        assertEquals(listOf(ChallengeOutcome.LOST to emptyList<Any>()), mechanics.challenges.ended.map { it.outcome to it.winners })
        assertEquals(
            "Invalid arguments: C-53 is not one of your running challenges",
            run("end_challenge", """{"id": "C-53", "outcome": "won", "winners": ["Mimosa"], "note": "Trop tard."}""").text,
            "a challenge that ended is the engine's to refuse",
        )
    }
}
