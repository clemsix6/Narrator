package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.trace.DecisionLine
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.trace.TraceDataset
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an operator reads after `/narrator trace`, `why` and `usage` (M1_M2 §14.4), rendered from the
 * Journal of [TraceDataset] through the real queries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceTextTest {
    private val data = TraceDataset()
    private val text = TraceText(ZoneOffset.UTC, TraceDataset.NOW.toEpochMilli())

    @AfterAll
    fun close() = data.close()

    private fun strings(lines: List<Component>) = lines.map { it.string }

    private fun colorOf(line: Component): TextColor? = (line.siblings.lastOrNull() ?: line).style.color

    private fun idle(state: DirectorState = DirectorState.IDLE, reason: String? = null) =
        DirectorStatus(state, null, reason, 3, TraceDataset.CHAPTER, 61_000, 0, 0, null, OperatorChannelMode.SYSTEM_MESSAGE, emptyList(), 0, 30)

    @Test
    fun `trace lists the last turns in one line each`() {
        assertEquals(
            listOf(
                "[Narrator] last 2 turns, newest first",
                "#43 19:35:00 heartbeat (1 active player) → idle · 1 call · 2.0 s · 62k ctx",
                "#42 19:30:03 chat Bartok + 1 item → acted · 3 calls · apply_effect ×2, say · 10 s · 62k ctx",
                "Details: /narrator trace turn <id> · /narrator why turn <id>",
            ),
            strings(text.page(text.turns(data.read { it.lastTurns(5) }), 1, null)),
        )
        assertEquals(listOf("[Narrator] no Director turn yet"), strings(text.page(text.turns(emptyList()), 1, null)))
    }

    @Test
    fun `trace turn shows everything, in pages with a link to the next`() {
        val turn = text.turn(data.read { it.turn(42) }!!)
        val first = text.page(turn, 1, "narrator trace turn 42")
        assertEquals(
            listOf(
                "[Narrator] turn #42 · chapter 3 · 19:30:03 · acted in 10 s · page 1/2",
                "Woke for: chat Bartok + 1 item (trigger chat)",
                "Told at wake-up:",
                "  19:30:00 CHAT Bartok: \"narrateur, t'es nul\" · immediate: addressed: to you 0.92",
                "  19:30:01 death Mimosa: fall (overworld 5 64 5) · soon: death",
                "Told between tool calls:",
                "  19:30:08 PRAY Mimosa: \"pitié, Narrateur\" · immediate: prayer: to you 0.99",
                "Request 1 · 19:29:57 · 5.2 s · 61k in (60k cached) · 300 out · tool_use",
                "  Thought: Bartok mocks me again; a slowness will teach him. Mimosa just died: leave her be.",
                "  → apply_effect {\"player\":\"Bartok\",\"effect\":\"slowness\",\"level\":2,\"seconds\":60} · ok 40 ms: ok:",
                "      slowness II on Bartok for 60 s",
                "  → apply_effect {\"player\":\"Mimosa\",\"effect\":\"weakness\"} · denied 40 ms: DENIED (no_pile_on):",
                "      Mimosa died 7s ago",
                "Request 2 · 19:30:06 · 2.1 s · 61k in (61k cached) · 80 out · tool_use",
                "  Thought: Now a jab for everyone.",
                "  → say {\"text\":\"Bartok, ta pioche pleure.\"} · ok 40 ms: ok: said to everyone (2 online)",
                "page 1/2 · next: /narrator trace turn 42 2",
            ),
            strings(first),
        )
        assertEquals(1 + PAGE_LINES + 1, first.size, "title, a full page, footer")
        val red = TextColor.fromLegacyFormat(ChatFormatting.RED)
        assertEquals(red, colorOf(first[11]), "a denied tool call is red")
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), colorOf(first[9]))
        assertTrue(first[8].style.isItalic, "thinking in italics")
        val next = first.last().siblings.first { it.string == "/narrator trace turn 42 2" }
        assertEquals(ClickEvent.RunCommand("/narrator trace turn 42 2"), next.style.clickEvent)

        val second = strings(text.page(turn, 2, "narrator trace turn 42"))
        assertEquals(
            listOf(
                "[Narrator] turn #42 · chapter 3 · 19:30:03 · acted in 10 s · page 2/2",
                "Request 3 · 19:30:10 · 0.9 s · 62k in (62k cached) · 12 out · end_turn",
                "  Thought: (no summary)",
                "Said to everyone: \"Bartok, ta pioche pleure.\"",
                "Engine: guard denied Mimosa: no_pile_on: Mimosa died 7s ago",
                "Transcript: ${TraceDataset.TRANSCRIPT}",
                "page 2/2 · previous: /narrator trace turn 42 1",
            ),
            second,
        )
        assertEquals(second, strings(text.page(turn, 9, "narrator trace turn 42")), "past the last page, the last")
    }

    @Test
    fun `why tells what woke the turn, what it thought, did and said`() {
        assertEquals(
            listOf(
                "[Narrator] why turn #42 · 19:30:03, 6m ago · acted",
                "Woke for: chat Bartok + 1 item",
                "  19:30:00 CHAT Bartok: \"narrateur, t'es nul\" · immediate: addressed: to you 0.92",
                "  19:30:01 death Mimosa: fall (overworld 5 64 5) · soon: death",
                "  then: 19:30:08 PRAY Mimosa: \"pitié, Narrateur\" · immediate: prayer: to you 0.99",
                "Thought: Bartok mocks me again; a slowness will teach him. Mimosa just died: leave her be.",
                "Thought: Now a jab for everyone.",
                "Did: apply_effect → Bartok (ok): ok: slowness II on Bartok for 60 s",
                "Did: apply_effect (denied): DENIED (no_pile_on): Mimosa died 7s ago",
                "Did: say → Bartok, Mimosa (ok): ok: said to everyone (2 online)",
                "Said to everyone: \"Bartok, ta pioche pleure.\"",
                "Engine: guard denied Mimosa: no_pile_on: Mimosa died 7s ago",
                "More: /narrator trace turn 42 · ${TraceDataset.TRANSCRIPT}",
            ),
            strings(text.capped(text.why(data.read { it.turn(42) }!!))),
        )
        assertEquals(
            listOf("Thought: Nothing worth a word.", "Did nothing: no speech, world or task tool was called"),
            strings(text.capped(text.why(data.read { it.turn(43) }!!))).filter { it.startsWith("Thought") || it.startsWith("Did") },
        )
    }

    @Test
    fun `a death guard's rescue is told in red, like the other refusals`() {
        val detail = data.read { it.turn(42) }!!
        val rescue = DecisionLine(detail.summary.startedAt, "death_guard", "rescued", "Mimosa", "minecraft:lava: the Narrator's quota of deaths (1 per played hour) is spent")
        val lines = text.capped(text.why(detail.copy(decisions = detail.decisions + rescue)))
        val told = lines.single { it.string.startsWith("Engine: death_guard rescued Mimosa: ") }
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), colorOf(told))
    }

    @Test
    fun `why of a player follows their last message to the turn that heard it, or says why none did`() {
        val bartok = data.read { it.lastMessage("Bartok") }!!
        val lines = strings(text.capped(text.fate("Bartok", bartok, data.read { it.turn(42) }, idle())))
        assertEquals(
            listOf(
                "[Narrator] why: Bartok's last message · 19:30:00, 6m ago",
                "Public chat: \"narrateur, t'es nul\"",
                "Reflexes: to the Narrator 0.92 · hostile · provocation · heuristic",
                "Salience: immediate: addressed: to you 0.92",
                "Told in turn #42 at wake-up, 19:30:03 → acted",
                "Thought: Bartok mocks me again; a slowness will teach him. Mimosa just died: leave her be.",
            ),
            lines.take(6),
        )
        val mimosa = data.read { it.lastMessage("Mimosa") }!!
        assertTrue("Told in turn #42 between two tool calls, 19:30:03 → acted" in strings(text.capped(text.fate("Mimosa", mimosa, null, idle()))))

        val steve = data.read { it.lastMessage("Steve") }!!
        val waiting = text.capped(text.fate("Steve", steve, null, idle()))
        assertEquals(
            listOf(
                "[Narrator] why: Steve's last message · 19:31:00, 5m ago",
                "Public chat: \"salut tout le monde\"",
                "Salience: next: between players: greeting",
                "Not told to the Director yet: it waits for the next wake-up (it does not wake the Director by",
                "    itself).",
            ),
            strings(waiting),
        )
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), colorOf(waiting.last()), "waiting is normal")
        val disabled = text.capped(text.fate("Steve", steve, null, idle(DirectorState.DISABLED, "no LLMGW key")))
        assertEquals("Not told to the Director: the Director is disabled (no LLMGW key); what it misses is not kept.", disabled.last().string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), colorOf(disabled.last()), "a Director that cannot hear is red")
    }

    @Test
    fun `usage per role, and its summary for the status`() {
        val usage = data.read { it.usage(0) }
        // Dollars at the default prices (M6 §8.7): the Director's 4 Opus calls 1 850 in, 244 800 cache read, 412 out;
        // the Analyst's 3 000 in, 200 out; the reflexes' Haiku 90 in, 30 out.
        val prices = Prices.of(NarratorConfig())
        assertEquals(
            listOf(
                "[Narrator] usage over the last 24 h",
                "director: 4 calls · 247k in (99% cached) · 412 out · 2.4 s avg · \$0.065",
                "reflex: 2 calls · 90 in (0% cached) · 30 out · 0.8 s avg · \$0.000024 · 1 failed",
                "analyst: 1 call · 3k in (0% cached) · 200 out · 3.0 s avg · \$0.016",
                "Total \$0.081 · at the API's prices: behind a subscription, an order of magnitude.",
            ),
            strings(text.page(text.usage(usage, 24, prices), 1, null)),
        )
        // Opus without a price: the Director's and the Analyst's calls cost `$ ?`, the reflexes' Haiku still has its price.
        val unpriced = text.usage(usage, 24, Prices(NarratorConfig().prices - "claude-opus-5-5"))
        assertEquals("director: 4 calls · 247k in (99% cached) · 412 out · 2.4 s avg · \$ ?", unpriced.lines.first().text)
        assertEquals("Total \$0.000024 + 5 calls at \$ ? · at the API's prices: behind a subscription, an order of magnitude.", unpriced.lines.last().text)
        assertEquals(listOf("no model call"), text.usage(emptyList(), 1, prices).lines.map { it.text })
        assertEquals("Usage (24 h): Claude 5 calls · 250k in (98% cached) · 612 out · reflexes 2 calls (1 failed)", text.usageSummary(usage, 24))
        assertEquals("Usage (1 h): no model call", text.usageSummary(emptyList(), 1))
    }

    @Test
    fun `task shows its brief, requests, result and transcript`() {
        assertEquals(
            listOf(
                "[Narrator] task T-7 · analyst · done in 21 s",
                "Queued 19:30:20 · started 19:30:20 · ended 19:30:41 · asked by turn #42",
                "Brief: \"Where does Bartok mine?\"",
                "Request 1 · 19:30:17 · 3.0 s · 3k in (0 cached) · 200 out · tool_use",
                "  Thought: A hotspot query answers this.",
                "  → sql {\"query\":\"SELECT * FROM hotspots('Bartok')\"} · ok 40 ms: dim|x|z|n",
                "      overworld|-1400|3100|72",
                "Result: \"Bartok mines around -1400 -40 3100 (72% of his ore).\"",
                "Transcript: logs/conversations/2026-09-25/${TraceDataset.ANALYST}.md",
            ),
            strings(text.page(text.task(data.read { it.task(7) }!!), 1, "narrator trace task 7")),
        )
    }

    @Test
    fun `a request a fallback model answered is a warning, a refusal without an answer stays a failure`() {
        val detail = data.read { it.turn(42) }!!
        val (first, second, third) = detail.calls
        assertEquals("claude-opus-5-5", first.model, "the model that answered, from the Journal")
        fun requests(report: Report) = report.lines.filter { it.text.startsWith("Request ") }
        fun color(line: Out) = colorOf(text.page(Report("t", listOf(line)), 1, null).last())
        val yellow = TextColor.fromLegacyFormat(ChatFormatting.YELLOW)
        val red = TextColor.fromLegacyFormat(ChatFormatting.RED)
        val gray = TextColor.fromLegacyFormat(ChatFormatting.GRAY)

        // The classifier handed request 1 to claude-opus-4-8, which answered with its tool calls; then a refusal of the API.
        val served = first.copy(refusalCategory = "cyber", model = "claude-opus-4-8")
        val refused = third.copy(stopReason = "refusal", refusalCategory = "cyber", outputTokens = 0)
        val lines = requests(text.turn(detail.copy(summary = detail.summary.copy(outcome = "refused"), calls = listOf(served, second, refused))))
        assertEquals(
            listOf(
                "Request 1 · 19:29:57 · 5.2 s · 61k in (60k cached) · 300 out · tool_use · fallback (cyber) → claude-opus-4-8",
                "Request 2 · 19:30:06 · 2.1 s · 61k in (61k cached) · 80 out · tool_use",
                "Request 3 · 19:30:10 · 0.9 s · 62k in (62k cached) · 0 out · refusal · refused: cyber",
            ),
            lines.map { it.text },
        )
        assertEquals(listOf(Look.WARNING, Look.DETAIL, Look.FAILURE), lines.map { it.look })
        assertEquals(listOf(yellow, gray, red), lines.map(::color))

        // A fallback that said nothing ends its turn refused: its last request, though its stop reason is end_turn.
        val silent = third.copy(refusalCategory = "cyber", model = "claude-opus-4-8")
        val endedRefused = requests(text.turn(detail.copy(summary = detail.summary.copy(outcome = "refused"), calls = listOf(served, second, silent))))
        assertEquals("Request 3 · 19:30:10 · 0.9 s · 62k in (62k cached) · 12 out · end_turn · refused: cyber", endedRefused.last().text)
        assertEquals(listOf(Look.WARNING, Look.DETAIL, Look.FAILURE), endedRefused.map { it.look })
        val answered = requests(text.turn(detail.copy(calls = listOf(served, second, silent))))
        assertEquals("Request 3 · 19:30:10 · 0.9 s · 62k in (62k cached) · 12 out · end_turn · fallback (cyber) → claude-opus-4-8", answered.last().text)
        assertEquals(Look.WARNING, answered.last().look, "the turn acted: the fallback answered")

        // A worker's task: done on a fallback's report, or refused by the safety filter when the fallback said nothing.
        val task = data.read { it.task(7) }!!
        val work = task.calls.single().copy(stopReason = "end_turn", refusalCategory = "cyber", model = "claude-opus-4-8")
        val done = requests(text.task(task.copy(calls = listOf(work)))).single()
        assertEquals("Request 1 · 19:30:17 · 3.0 s · 3k in (0 cached) · 200 out · end_turn · fallback (cyber) → claude-opus-4-8", done.text)
        assertEquals(Look.WARNING, done.look)
        val failed = requests(text.task(task.copy(status = "failed", result = null, error = "refused by the safety filter (category cyber)", calls = listOf(work)))).single()
        assertEquals("Request 1 · 19:30:17 · 3.0 s · 3k in (0 cached) · 200 out · end_turn · refused: cyber", failed.text)
        assertEquals(Look.FAILURE, failed.look)
    }

    @Test
    fun `long lines wrap at spaces, indented, and a word longer than a line is cut`() {
        val words = "  " + List(30) { "word$it" }.joinToString(" ")
        val wrapped = text.wrap(Out(words, Look.THOUGHT))
        assertTrue(wrapped.size > 1)
        assertTrue(wrapped.all { it.text.length <= WRAP && it.look == Look.THOUGHT }, wrapped.toString())
        assertTrue(wrapped.drop(1).all { it.text.startsWith("      ") }, "continuations are indented under the first line")
        assertEquals(words.trim().split(' '), wrapped.flatMap { it.text.trim().split(' ') })

        val long = "x".repeat(WRAP * 2 + 5)
        val cut = text.wrap(Out(long))
        assertEquals(listOf(WRAP, WRAP, 4 + 5 + 4), cut.map { it.text.length })
        assertEquals(long, cut.joinToString("") { it.text.trim() })
    }

    @Test
    fun `times, durations and token counts are short`() {
        assertEquals("19:30:03", text.time(TraceDataset.T0.plusSeconds(3)))
        assertEquals("09-24 21:00", text.time(Instant.parse("2026-09-24T21:00:00Z")))
        assertEquals(listOf("0s ago", "6m ago", "2h ago", "3d ago"), listOf(TraceDataset.NOW, TraceDataset.T0, TraceDataset.NOW.minusSeconds(7_200), TraceDataset.NOW.minusSeconds(3 * 86_400)).map(text::ago))
        assertEquals(listOf("812", "61k", "1.2M"), listOf(812L, 61_234L, 1_234_567L).map { TraceText.tokens(it) })
        assertEquals(listOf("8.1 s", "41 s", "5m05s", "1h05m"), listOf(8_100L, 41_000L, 305_000L, 3_900_000L).map { TraceText.duration(it) })
        assertEquals("sql ×2, say, locate", TraceText.toolList(listOf("sql", "say", "sql", "locate")))
        assertEquals("\"abc d…\"", TraceText.quote("abc  def\ngh", 6))
    }
}
