package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.journal.IdKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reads of `/narrator trace`, `why` and `usage`, on a Journal written by the real writer ([TraceDataset]); `usage`
 * priced as `/narrator usage` and the dashboard's overview price it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceQueriesTest {
    private val data = TraceDataset()

    @AfterAll
    fun close() = data.close()

    @Test
    fun `the last turns come newest first, with what woke them, their tools and their context`() {
        val turns = data.read { it.lastTurns(5) }
        assertEquals(listOf(43L, 42L), turns.map { it.turnId })
        val acted = turns[1]
        assertEquals(TraceDataset.CHAPTER, acted.conversationId)
        assertEquals(3, acted.chapter)
        assertEquals("chat", acted.trigger)
        assertEquals("acted", acted.outcome)
        assertEquals("chat Bartok + 1 item", acted.woke)
        assertEquals(listOf("apply_effect", "apply_effect", "say"), acted.tools)
        assertEquals(3, acted.llmCalls)
        assertEquals(61_600L, acted.contextTokens, "input + cache read + cache write of the last answered request")
        assertEquals(TraceDataset.T0.plusSeconds(3), acted.startedAt)
        assertEquals(TraceDataset.T0.plusMillis(13_100), acted.endedAt)
        assertEquals(listOf("idle", "heartbeat (1 active player)"), listOf(turns[0].outcome, turns[0].woke))
        assertEquals(listOf(43L), data.read { it.lastTurns(1) }.map { it.turnId })
    }

    @Test
    fun `a turn in full - events told, requests with their thinking and tools, lines said, engine decisions`() {
        val turn = assertNotNull(data.read { it.turn(42) })
        assertEquals(
            listOf(
                ToldEvent(data.insult, TraceDataset.T0, "chat:public", "Bartok", "narrateur, t'es nul", null, "immediate: addressed: to you 0.92"),
                ToldEvent(data.death, TraceDataset.T0.plusSeconds(1), "death", "Mimosa", "fall", "overworld 5 64 5", "soon: death"),
            ),
            turn.woken,
        )
        assertEquals(
            listOf(ToldEvent(data.prayer, TraceDataset.T0.plusSeconds(8), "chat:pray", "Mimosa", "pitié, Narrateur", null, "immediate: prayer: to you 0.99")),
            turn.injected,
        )
        assertEquals(3, turn.calls.size)
        val first = turn.calls[0]
        assertEquals("Bartok mocks me again; a slowness will teach him. Mimosa just died: leave her be.", first.thinking)
        assertEquals(listOf(1_200, 60_000, 0, 300), listOf(first.inputTokens, first.cacheReadTokens, first.cacheWriteTokens, first.outputTokens))
        assertEquals(listOf("tool_use", "turn"), listOf(first.stopReason, first.purpose))
        assertEquals(listOf("ok", "denied"), first.tools.map { it.status })
        assertEquals("""{"player":"Bartok","effect":"slowness","level":2,"seconds":60}""", first.tools[0].args)
        assertEquals(listOf("Bartok"), first.tools[0].targets)
        assertEquals(emptyList(), first.tools[1].targets)
        assertEquals(listOf("say"), turn.calls[1].tools.map { it.tool })
        assertEquals(listOf("Bartok", "Mimosa"), turn.calls[1].tools.single().targets)
        assertNull(turn.calls[2].thinking)
        assertEquals(emptyList(), turn.unmatchedTools)
        assertEquals(listOf(SaidLine(TraceDataset.T0.plusSeconds(11), "narrator", null, "Bartok, ta pioche pleure.", null)), turn.said)
        assertEquals(listOf("guard denied Mimosa"), turn.decisions.map { "${it.kind} ${it.decision} ${it.subject}" }, "the wake-up is in the summary, not here")
        assertEquals(TraceDataset.TRANSCRIPT, turn.transcript)
        assertNull(data.read { it.turn(999) })
    }

    @Test
    fun `why picks the latest turn with a speech or world tool that succeeded`() {
        assertEquals(42L, data.read { it.lastActingTurnId() })
    }

    @Test
    fun `a task with its requests, tools, result and transcript`() {
        val task = assertNotNull(data.read { it.task(7) })
        assertEquals(listOf("analyst", "done", "Where does Bartok mine?"), listOf(task.role, task.status, task.brief))
        assertEquals(42L, task.requestedByTurn)
        assertEquals("Bartok mines around -1400 -40 3100 (72% of his ore).", task.result)
        assertEquals("logs/conversations/2026-09-25/${TraceDataset.ANALYST}.md", task.transcript)
        assertEquals(TraceDataset.T0.plusSeconds(41), task.finishedAt)
        val call = task.calls.single()
        assertEquals("worker", call.purpose)
        assertEquals("""{"query":"SELECT * FROM hotspots('Bartok')"}""", call.tools.single().args)
        assertNull(data.read { it.task(8) })
    }

    @Test
    fun `the fate of a message - told at wake-up, injected, or waiting`() {
        val insult = assertNotNull(data.read { it.lastMessage("bartok") }, "names ignore case")
        assertEquals(data.insult, insult.message.id)
        assertEquals("immediate: addressed: to you 0.92", insult.message.salience)
        assertEquals(Triage(0.92, "hostile", "provocation", 0.1, "heuristic"), insult.triage)
        assertEquals(42L, insult.turn?.turnId)
        assertTrue(insult.atWake)

        val prayer = assertNotNull(data.read { it.lastMessage("Mimosa") })
        assertEquals(listOf("chat:pray", "pitié, Narrateur"), listOf(prayer.message.kind, prayer.message.what))
        assertEquals(42L, prayer.turn?.turnId)
        assertFalse(prayer.atWake, "told between two tool calls")
        assertNull(prayer.triage)

        val smallTalk = assertNotNull(data.read { it.lastMessage("Steve") })
        assertEquals("next: between players: greeting", smallTalk.message.salience)
        assertNull(smallTalk.turn)
        assertNull(smallTalk.dropped)

        assertNull(data.read { it.lastMessage("Nobody") })
    }

    @Test
    fun `usage per provider and role since a moment, with the calls of each model`() {
        val usage = data.read { it.usage(TraceDataset.T0.minusSeconds(3_600).toEpochMilli()) }
        assertEquals(
            listOf(
                Usage("anthropic", "director", 4, 0, 1_850, 244_800, 0, 412, 2_425, listOf(ModelTokens("claude-opus-5-5", 4, 0, 1_850, 244_800, 0, 412))),
                Usage("anthropic", "reflex", 2, 1, 90, 0, 0, 30, 800, listOf(ModelTokens("claude-haiku-5-5", 2, 1, 90, 0, 0, 30))),
                Usage("anthropic", "analyst", 1, 0, 3_000, 0, 0, 200, 3_000, listOf(ModelTokens("claude-opus-5-5", 1, 0, 3_000, 0, 0, 200))),
            ),
            usage,
        )
        assertEquals(emptyList(), data.read { it.usage(TraceDataset.NOW.toEpochMilli()) })
    }

    @Test
    fun `usage prices each role's calls at the price of the model that answered, the fallback model's included`() {
        TraceDataset().use { data ->
            val journal = data.fixture.journal
            journal.llmCall(
                LlmCallRecord(
                    journal.nextId(IdKind.LLM_CALL), LlmCallRecord.Provider.ANTHROPIC, LlmCallRecord.Purpose.TURN, TraceDataset.CHAPTER, 43, "director",
                    "claude-opus-4-8", "medium", TraceDataset.NOW, TraceDataset.NOW, 1_200, 900, 1, null, null, 500, 0, 0, 50, "end_turn", "cyber", null, null, null,
                ),
            )
            data.fixture.sync()
            val prices = Prices.of(NarratorConfig())

            val usage = data.read { it.usage(0) }.associateBy { it.role }
            val director = usage.getValue("director")
            assertEquals(
                listOf(ModelTokens("claude-opus-4-8", 1, 0, 500, 0, 0, 50), ModelTokens("claude-opus-5-5", 4, 0, 1_850, 244_800, 0, 412)),
                director.models,
            )
            assertEquals(listOf(5, 2_350, 462), listOf(director.calls, director.inputTokens.toInt(), director.outputTokens.toInt()), "the role sums its models")
            assertEquals(2_180L, director.averageLatencyMs, "(900 + 1 500 + 2 100 + 5 200 + 1 200) / 5")
            // Opus 5.5 at 4 / 20 / 0.20 / 8 $ per million: (1 850 × 4 + 244 800 × 0.20 + 412 × 20) / 1 M;
            // the fallback model, Opus 4.8, at 5 / 25 $: (500 × 5 + 50 × 25) / 1 M.
            val cost = prices.costOf(director.models)
            assertEquals(0.0646 + 0.00375, cost.dollars, 1e-9)
            assertEquals(Cost(cost.dollars, priced = 5, unpriced = 0), cost)
            assertEquals("\$0.068", cost.text, "the fallback model at its own price")
            assertEquals("\$0.016", prices.costOf(usage.getValue("analyst").models).text)
            assertEquals("\$0.000024", prices.costOf(usage.getValue("reflex").models).text, "Haiku: (90 × 0.10 + 30 × 0.50) / 1 M")
        }
    }
}
