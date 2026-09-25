package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How chapters close and open (M1_M2 §12.10, §12.11, §17.2): S8 and S9, the hard limit, the empty
 * server, the reload that marks a chapter, the server that stops, and the closing request itself
 * (tools disabled, refused), against the strict fake Anthropic server, in virtual time.
 */
class ChapterScenariosTest {
    @TempDir
    lateinit var dir: Path

    private fun harness() = DirectorHarness(dir)

    private fun DirectorHarness.decisions(kind: DecisionRecord.Kind, decision: String) =
        traces.decisions.filter { it.kind == kind && it.decision == decision }

    private fun DirectorHarness.summaryPrompt(): String = services.settings.get().prompts.get("chapter_summary")

    /** The first block of the last user message of request [request]. */
    private fun DirectorHarness.firstBlock(request: Int): String = lastUser(request).path("content")[0].path("text").asText()

    // ── S8 ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 - past the soft limit the chapter closes at a quiet moment with its summary, and the next one opens with a briefing that holds it`() =
        harness().use { h ->
            h.online("Bartok")
            h.fake.enqueue(reply { usage(input = 1_000, cacheRead = 160_000); text("…") })
            h.fake.enqueue(reply { text(SUMMARY) })
            h.fake.enqueue(reply { text("Me revoilà.") })

            h.chat("Bartok", "narrateur ?")
            h.advance(1_500)
            h.awaitTurns(1)
            assertTrue(h.firstBlock(0).startsWith("<briefing chapter=\"1\" now=\"2026-09-25 21:32:01\">\n"), h.firstBlock(0))
            assertTrue(h.wakeText(0).startsWith("<events"), "the wake-up follows the briefing")
            val marked = h.decisions(DecisionRecord.Kind.CHAPTER, "marked").single()
            assertEquals("chapter 1, quiet_limit: the context reached 161k tokens, the limit is 150k", marked.reason)

            h.advance(19_999)
            assertEquals(1, h.fake.requests.size, "not quiet for 20 s yet")
            h.advance(1)
            h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }

            assertEquals(2, h.fake.requests.size)
            assertEquals(h.sessionOf(0), h.sessionOf(1), "the summary is asked in the chapter's own session: its cache is warm")
            val closing = h.messages(1)
            assertEquals("system", closing.last().path("role").asText())
            assertEquals("Engine: ${h.summaryPrompt()}", closing.last().path("content").asText(), "the summary prompt is kept whole")
            assertEquals("<chapter_end chapter=\"1\" now=\"21:32:21\"/>", h.userText(1))
            val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
            assertEquals(EndReason.QUIET_LIMIT, chapter.endReason)
            assertEquals(SUMMARY, chapter.summary)
            assertEquals(1, h.traces.llmCalls.count { it.purpose == LlmCallRecord.Purpose.CHAPTER_SUMMARY })

            h.chat("Bartok", "re")
            h.advance(1_500)
            h.awaitTurns(2)
            assertNotEquals(h.sessionOf(0), h.sessionOf(2), "a new chapter, a new session")
            val briefing = h.firstBlock(2)
            println(briefing)
            assertTrue(briefing.startsWith("<briefing chapter=\"2\""), briefing)
            assertContains(briefing, "#1 21:32–21:32 (quiet_limit):\n$SUMMARY\n")
            assertContains(h.wakeText(2), "CHAT Bartok (to you 0.92, hostile, provocation): \"re\"")
            h.assertInvariants()
        }

    // ── S9 ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 - from request 13 tool calls are refused, at request 14 the turn stops and the chapter closes with the refused results first`() =
        harness().use { h ->
            h.online("Bartok")
            val calls = List(14) { mutableListOf<String>() }
            repeat(14) { step -> h.fake.enqueue(reply { calls[step] += toolUse("sql", """{"query": "SELECT $step"}""") }) }
            h.fake.enqueue(reply { text(SUMMARY) })

            h.chat("Bartok", "narrateur, compte jusqu'à l'infini")
            h.advance(1_500)
            h.awaitTurns(1)
            h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }

            assertEquals(15, h.fake.requests.size)
            for (request in 1..11) {
                val result = h.lastUser(request).path("content")[0]
                assertFalse(result.path("is_error").asBoolean(false), "request ${request + 1} carries executed results")
            }
            for (request in 12..13) {
                val result = h.lastUser(request).path("content")[0]
                assertTrue(result.path("is_error").asBoolean(), "request ${request + 1}")
                assertEquals(AgentLoop.STEP_LIMIT_RESULT, result.path("content").asText())
            }
            assertEquals(TurnRecord.Outcome.STEP_LIMIT, h.endedTurns().single().outcome)

            // The summary request: the same session, the refused results of the last reply first.
            assertEquals(h.sessionOf(0), h.sessionOf(14))
            assertEquals(listOf("tool_result", "text"), h.userBlocks(14))
            val carried = h.lastUser(14).path("content")[0]
            assertEquals(calls[13].single(), carried.path("tool_use_id").asText())
            assertEquals(AgentLoop.STEP_LIMIT_RESULT, carried.path("content").asText())
            assertEquals("Engine: ${h.summaryPrompt()}", h.messages(14).last().path("content").asText())
            val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
            assertEquals(EndReason.RUNAWAY_TURN, chapter.endReason)
            assertEquals(SUMMARY, chapter.summary)
            h.assertInvariants()
        }

    // ── Other closings ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `past the hard limit the chapter closes at the end of the turn, without waiting for a quiet moment`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { usage(input = 1_000, cacheRead = 180_000); text("…") })
        h.fake.enqueue(reply { text(SUMMARY) })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitUntil("the chapter is closed") { h.traces.conversations.any { it.endReason == EndReason.TOKEN_LIMIT } }

        assertEquals(2, h.fake.requests.size)
        assertEquals(h.sessionOf(0), h.sessionOf(1))
        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.TOKEN_LIMIT, chapter.endReason)
        assertEquals(SUMMARY, chapter.summary)
        assertTrue(h.decisions(DecisionRecord.Kind.CHAPTER, "marked").isEmpty())
        h.assertInvariants()
    }

    @Test
    fun `a chapter closes with its summary once nobody has been online for 10 minutes`() = harness().use { h ->
        val bartok = h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text(SUMMARY) })

        h.chat("Bartok", "bonne nuit narrateur")
        h.advance(1_500)
        h.awaitTurns(1)
        h.players.remove(bartok.uuid)
        h.advance(9 * 60_000L)
        assertEquals(1, h.fake.requests.size, "not empty for 10 minutes yet")
        h.advance(2 * 60_000L)
        h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }

        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.SERVER_EMPTY, chapter.endReason)
        assertEquals(SUMMARY, chapter.summary)
        h.advance(3_600_000L)
        assertEquals(2, h.fake.requests.size, "nothing more while the server stays empty")
        h.assertInvariants()
    }

    @Test
    fun `a reload that changes the director prompt marks the chapter, which closes at the next quiet moment with its summary`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text(SUMMARY) })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        val old = h.services.settings.get()
        h.director.post(DirectorSignal.SettingsChanged(old, testSettings(old.config, h.client)))
        h.runCurrent()
        assertTrue(h.decisions(DecisionRecord.Kind.CHAPTER, "marked").isEmpty(), "nothing that matters changed")

        val overrides = Files.createDirectories(dir.resolve("prompts"))
        Files.writeString(overrides.resolve("director.md"), "Tu es un autre Narrateur.")
        val changed = Settings(old.config, Prompts.load(overrides), old.ui, h.client, jev = null)
        h.director.post(DirectorSignal.SettingsChanged(old, changed))
        h.runCurrent()
        assertEquals("chapter 1, reload: the director prompt changed", h.decisions(DecisionRecord.Kind.CHAPTER, "marked").single().reason)

        h.advance(20_000)
        h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }
        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.RELOAD, chapter.endReason)
        assertEquals(SUMMARY, chapter.summary)
        h.assertInvariants()
    }

    @Test
    fun `the server stopping closes the chapter without a summary`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.stopServer()
        h.awaitUntil("the chapter is closed") { h.traces.latestConversation(h.sessionOf(0)!!)?.endedAt != null }

        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.SHUTDOWN, chapter.endReason)
        assertNull(chapter.summary)
        assertEquals(1, h.fake.requests.size, "no summary request")
        h.assertInvariants()
    }

    @Test
    fun `the server stopping while the summary is written leaves the chapter closed shutdown, without a summary`() = harness().use { h ->
        h.online("Bartok")
        val hold = Hold()
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { hold(hold); text(SUMMARY) })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.director.post(DirectorSignal.CloseChapter(EndReason.OPERATOR))
        h.advance(20_000)
        assertTrue(hold.awaitReached())
        assertEquals(DirectorState.CLOSING, h.director.status().state)
        h.stopServer()
        h.awaitUntil("the chapter is closed") { h.traces.latestConversation(h.sessionOf(0)!!)?.endedAt != null }
        hold.release()
        assertTrue(hold.awaitFinished())

        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.SHUTDOWN, chapter.endReason)
        assertNull(chapter.summary)
        h.assertInvariants()
    }

    // ── The closing request ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools called while the chapter closes are refused, the demand goes again, and the summary is the answer`() = harness().use { h ->
        h.online("Bartok")
        lateinit var sayId: String
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { sayId = toolUse("say", """{"text": "Au revoir !"}""") })
        h.fake.enqueue(reply { text(SUMMARY) })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.director.post(DirectorSignal.CloseChapter(EndReason.OPERATOR))
        h.advance(20_000)
        h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }

        assertEquals(3, h.fake.requests.size)
        val refused = h.lastUser(2).path("content")[0]
        assertEquals(sayId, refused.path("tool_use_id").asText())
        assertTrue(refused.path("is_error").asBoolean())
        assertEquals(Chapters.TOOLS_DISABLED, refused.path("content").asText())
        assertEquals("Engine: ${Chapters.REPEAT_NOTE}", h.messages(2).last().path("content").asText())
        assertTrue(h.said.isEmpty(), "nothing was said to the players")
        assertEquals(SUMMARY, h.traces.latestConversation(h.sessionOf(0)!!)?.summary)
        h.assertInvariants()
    }

    @Test
    fun `a refused summary closes the chapter all the same, without a summary`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { refusal("cyber") })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.director.post(DirectorSignal.CloseChapter(EndReason.OPERATOR))
        h.advance(20_000)
        h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }

        val chapter = assertNotNull(h.traces.latestConversation(h.sessionOf(0)!!))
        assertEquals(EndReason.OPERATOR, chapter.endReason)
        assertNull(chapter.summary)
        assertContains(
            h.decisions(DecisionRecord.Kind.CHAPTER, "closed").single().reason.orEmpty(),
            "no summary: no usable answer (refused (cyber))",
        )
        h.assertInvariants()
    }

    @Test
    fun `a first message that got no usable answer gets its briefing again, and only the first message has one`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { refusal("cyber") })
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text("…") })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.advance(30_000)
        h.awaitTurns(2)
        assertTrue(h.firstBlock(0).startsWith("<briefing chapter=\"1\""))
        assertTrue(h.firstBlock(1).startsWith("<briefing chapter=\"1\""), "nothing was committed: the briefing comes again")

        h.chat("Bartok", "encore ?")
        h.advance(1_500)
        h.awaitTurns(3)
        assertEquals(h.sessionOf(1), h.sessionOf(2))
        assertEquals(listOf("text"), h.userBlocks(2))
        assertTrue(h.userText(2).startsWith("<events"), "a chapter has one briefing, at its start")
        h.assertInvariants()
    }

    @Test
    fun `a close asked while no chapter is open is ignored`() = harness().use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.director.post(DirectorSignal.CloseChapter(EndReason.OPERATOR))
        h.runCurrent()

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.advance(60_000)
        assertEquals(1, h.director.status().chapter, "the new chapter stays open")
        assertEquals(1, h.fake.requests.size)
        assertTrue(h.traces.conversations.none { it.endReason != null })
        h.assertInvariants()
    }

    private companion object {
        val SUMMARY = """
            Joueurs vus : Bartok.
            Faits marquants :
            - 21:32 Bartok interpelle le Narrateur.
            Engagements en cours :
            - menace — envers Bartok — « la prochaine insulte te coûtera ta pioche » — ce soir.
        """.trimIndent()
    }
}
