package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.config.ModelPrice
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.trace.TraceDataset
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reads of the dashboard's trace pages (M6 §8.5), on the Journal of [TraceDataset] written through the real Journal:
 * each list filtered and paged (one row more than a page, the newest first, no row lost or repeated between pages), the
 * sums behind the costs, and the dollars they make at the configuration's prices, `$ ?` for a model without a price.
 */
class DashboardQueriesTest {
    private val data = TraceDataset()
    private val journal = data.fixture.journal

    @AfterEach
    fun close() = data.close()

    private fun <T> read(block: (DashboardQueries) -> T): T = runBlocking { journal.read(10_000) { block(DashboardQueries(it)) } }

    private val first = Paging(1)

    @Test
    fun `turns come newest first, as trace lists them, filtered by trigger, outcome and chapter`() {
        journal.turn(TurnRecord(44, TraceDataset.CHAPTER, TraceDataset.NOW, null, TurnRecord.Trigger.PRAY, emptyList(), emptyList(), null, null, null))
        data.fixture.sync()

        val all = read { it.turns(TurnFilter(), first) }
        assertEquals(listOf(44L, 43L, 42L), all.map { it.turnId })
        assertEquals(data.read { it.lastTurns(10) }, all, "the same summaries as /narrator trace")
        assertEquals(listOf("heartbeat (1 active player)", "chat Bartok + 1 item"), all.drop(1).map { it.woke })
        assertEquals(listOf("apply_effect", "apply_effect", "say"), all.last().tools)

        assertEquals(listOf(42L), read { it.turns(TurnFilter(trigger = "chat"), first) }.map { it.turnId })
        assertEquals(listOf(43L), read { it.turns(TurnFilter(outcome = "idle"), first) }.map { it.turnId })
        assertEquals(listOf(44L), read { it.turns(TurnFilter(outcome = TurnFilter.RUNNING), first) }.map { it.turnId })
        assertEquals(listOf(44L, 43L, 42L), read { it.turns(TurnFilter(chapter = 3), first) }.map { it.turnId })
        assertEquals(emptyList(), read { it.turns(TurnFilter(chapter = 4), first) })
        assertEquals(listOf(42L), read { it.turns(TurnFilter(trigger = "chat", outcome = "acted", chapter = 3), first) }.map { it.turnId }, "filters add up")
    }

    @Test
    fun `a list reads one row more than its page, and its pages neither lose nor repeat a row`() {
        val decisions = Decisions(journal, data.fixture.clock)
        repeat(120) { i ->
            data.fixture.clock.now = TraceDataset.NOW.plusSeconds(i.toLong())
            decisions.record(Log.guard, DecisionRecord.Kind.GUARD, "denied", "Steve", "test $i")
        }
        data.fixture.sync()
        val filter = DecisionFilter(kind = "guard", subject = "steve")

        val pages = (1..3).map { page -> Paging(page).let { paging -> paging.rows(read { it.decisions(filter, paging) }) } }
        assertEquals(listOf(50, 50, 20), pages.map { it.first.size })
        assertEquals(listOf(true, true, false), pages.map { it.second }, "a next page exists")
        val reasons = pages.flatMap { it.first }.map { it.reason }
        assertEquals((119 downTo 0).map { "test $it" }, reasons, "the latest first, each once")
        assertEquals(51, read { it.decisions(filter, Paging(1)) }.size, "the limit is a page and one row")
    }

    @Test
    fun `decisions filter by kind, decision, words of the subject, period and turn`() {
        fun reasons(filter: DecisionFilter) = read { it.decisions(filter, first) }.map { "${it.kind} ${it.decision} ${it.subject}" }

        assertEquals(7, read { it.decisions(DecisionFilter(), first) }.size)
        assertEquals(4, reasons(DecisionFilter(kind = "salience")).size)
        assertEquals(listOf("salience immediate Bartok"), reasons(DecisionFilter(subject = "BART")), "the subject's words, ignoring case")
        assertEquals(listOf("guard denied Mimosa"), reasons(DecisionFilter(decision = "denied")))
        assertEquals(listOf("wake woke turn 43"), reasons(DecisionFilter(since = TraceDataset.T0.plusSeconds(250).toEpochMilli())))
        assertEquals(listOf("guard denied Mimosa", "wake woke turn 42"), reasons(DecisionFilter(turn = 42)))
        assertEquals(emptyList(), reasons(DecisionFilter(kind = "guard", subject = "Bartok")))

        val guard = read { it.decisions(DecisionFilter(kind = "guard"), first) }.single()
        assertEquals(42L, guard.turnId)
        assertEquals(TraceDataset.CHAPTER, guard.conversationId)
        assertEquals("no_pile_on: Mimosa died 7s ago", guard.reason)
        val greeting = read { it.decisions(DecisionFilter(kind = "salience", subject = "steve"), first) }.single()
        assertEquals(data.smallTalk, greeting.eventId)
    }

    @Test
    fun `conversations, with their requests, turns and tokens by model`() {
        val all = read { it.conversations(null, first) }
        assertEquals(listOf(TraceDataset.ANALYST, TraceDataset.CHAPTER), all.map { it.conversationId }, "the latest first")
        assertEquals(listOf(TraceDataset.CHAPTER), read { it.conversations("director", first) }.map { it.conversationId })

        val chapter = read { it.conversation(TraceDataset.CHAPTER) }!!
        assertEquals(3, chapter.chapter)
        assertEquals(4L, chapter.calls)
        assertEquals(2L, chapter.turns)
        assertEquals(TraceDataset.TRANSCRIPT, chapter.transcript)
        val task = read { it.conversation(TraceDataset.ANALYST) }!!
        assertEquals(7L, task.taskId)
        assertEquals(1L, task.calls)
        assertEquals(0L, task.turns)
        assertNull(read { it.conversation("nope") })

        assertEquals(
            listOf(ModelTokens("claude-opus-5-5", calls = 4, failed = 0, input = 1_850, cacheRead = 244_800, cacheWrite = 0, output = 412)),
            read { it.conversationTokens(TraceDataset.CHAPTER) },
        )
    }

    @Test
    fun `tasks filter by role and status`() {
        val task = read { it.tasks(null, null, first) }.single()
        assertEquals(7L, task.taskId)
        assertEquals("analyst", task.role)
        assertEquals("done", task.status)
        assertEquals(42L, task.requestedByTurn)
        assertEquals(1L, task.calls)
        assertEquals(listOf(7L), read { it.tasks("analyst", "done", first) }.map { it.taskId })
        assertEquals(emptyList(), read { it.tasks("scout", null, first) })
        assertEquals(emptyList(), read { it.tasks(null, "failed", first) })
    }

    @Test
    fun `the calls of a period are summed by role, purpose and model, with their latencies, and by hour`() {
        val kinds = read { it.callsByKind(0) }.associateBy { "${it.role}/${it.purpose}/${it.tokens.model}" }
        assertEquals(setOf("director/turn/claude-opus-5-5", "analyst/worker/claude-opus-5-5", "reflex/chat_triage/claude-haiku-5-5"), kinds.keys)
        val director = kinds.getValue("director/turn/claude-opus-5-5")
        assertEquals(ModelTokens("claude-opus-5-5", 4, 0, 1_850, 244_800, 0, 412), director.tokens)
        // Latencies 900, 1 500, 2 100 and 5 200 ms: the median between the middle two, the 95th percentile interpolated.
        assertEquals(1_800, director.p50Ms)
        assertEquals(4_735, director.p95Ms)
        assertEquals(ModelTokens("claude-haiku-5-5", 2, 1, 90, 0, 0, 30), kinds.getValue("reflex/chat_triage/claude-haiku-5-5").tokens)

        val hour = TraceDataset.T0.toEpochMilli() / 3_600_000
        val byHour = read { it.callsByHour(0) }
        assertEquals(setOf(hour), byHour.map { it.hour }.toSet(), "every call of the dataset is in the hour of 19:00")
        assertEquals(mapOf("claude-opus-5-5" to 5L, "claude-haiku-5-5" to 2L), byHour.associate { it.tokens.model to it.tokens.calls })

        assertEquals(emptyList(), read { it.callsByKind(TraceDataset.NOW.toEpochMilli()) }, "none since now")
        assertEquals(emptyList(), read { it.callsByHour(TraceDataset.NOW.toEpochMilli()) })
    }

    @Test
    fun `usage costs each role's tokens at the price of the model that answered, the fallback model's included`() {
        val fallback = journal.nextId(IdKind.LLM_CALL)
        journal.llmCall(
            LlmCallRecord(
                fallback, LlmCallRecord.Provider.ANTHROPIC, LlmCallRecord.Purpose.TURN, TraceDataset.CHAPTER, 43, "director", "claude-opus-4-8", "medium",
                TraceDataset.NOW, TraceDataset.NOW, 1_000, 900, 1, null, null, 500, 0, 0, 50, "end_turn", "cyber", null, null, null,
            ),
        )
        data.fixture.sync()
        val prices = Prices.of(NarratorConfig())

        val costs = read { it.tokensByRole(0) }.mapValues { (_, tokens) -> prices.costOf(tokens) }
        assertEquals(setOf("director", "analyst", "reflex"), costs.keys)
        // Opus 5.5 at 4 / 20 / 0.20 / 8 $ per million: (1 850 × 4 + 244 800 × 0.20 + 412 × 20) / 1 M;
        // the fallback model, Opus 4.8, at 5 / 25 $: (500 × 5 + 50 × 25) / 1 M.
        assertEquals(0.0646 + 0.00375, costs.getValue("director").dollars, 1e-9)
        assertEquals(Cost(costs.getValue("director").dollars, priced = 5, unpriced = 0), costs.getValue("director"))
        assertEquals("\$0.068", costs.getValue("director").text, "the fallback model at its own price")
        assertEquals("\$0.016", costs.getValue("analyst").text)
        assertEquals("\$0.000024", costs.getValue("reflex").text, "Haiku: (90 × 0.10 + 30 × 0.50) / 1 M")
        assertTrue(read { it.tokensByRole(TraceDataset.NOW.plusSeconds(1).toEpochMilli()) }.isEmpty())
    }

    @Test
    fun `a cost is in dollars, $ ? when no call has a price, and nothing is guessed from a name`() {
        val prices = Prices(mapOf("claude-x" to ModelPrice(input = 1.0, output = 2.0, cacheRead = 0.1, cacheWrite = 1.25)))
        val x = ModelTokens("claude-x", 3, 0, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
        val unknown = ModelTokens("claude-x-2", 2, 1, 1_000, 0, 0, 10)
        assertEquals(Cost(4.35, 3, 0), prices.costOf(listOf(x)))
        assertEquals("\$4.35", prices.costOf(listOf(x)).text)
        assertEquals("\$ ?", prices.costOf(listOf(unknown)).text)
        assertEquals("\$4.35 + 2 calls at \$ ?", prices.costOf(listOf(x, unknown)).text)
        assertEquals("\$0", prices.costOf(emptyList()).text)
        assertEquals("\$0", (Cost.NONE + Cost.NONE).text)
        assertEquals(ModelTokens("all", 5, 1, 1_001_000, 1_000_000, 1_000_000, 1_000_010), ModelTokens.sum("all", listOf(x, unknown)))
        assertEquals(3_000_000, x.read)
    }

    @Test
    fun `no statement names a private table, and each only reads`() {
        assertTrue(DashboardQueries.STATEMENTS.isNotEmpty())
        DashboardQueries.STATEMENTS.forEach { sql ->
            assertFalse("private_messages" in sql.lowercase(), sql)
            listOf("insert", "update", "delete", "create", "drop", "attach", "copy", "pragma").forEach { word ->
                assertFalse(Regex("\\b$word\\b").containsMatchIn(sql.lowercase()), "$word in $sql")
            }
        }
    }
}
