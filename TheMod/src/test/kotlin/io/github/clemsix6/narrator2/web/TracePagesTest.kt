package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TraceDataset
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trace pages of the dashboard (M6 §8.4, §8.5), rendered through the gate ([Routes]) on the Journal of
 * [TraceDataset]:
 * - **no private message** reaches a page or a transcript: private messages are seeded (one even cited by a turn, as if
 *   the engine had told it), every page is rendered, their text is nowhere, and no statement any page ran names
 *   `private_messages`, while the public chat shows;
 * - every read goes through the dashboard's read connection, each statement with a time limit, and lists are paged;
 * - a request a fallback model answered shows in yellow, a refusal in red, as `/narrator trace` shows them, at the price
 *   of the model that answered.
 */
class TracePagesTest {
    private val data = TraceDataset()
    private val journal = data.fixture.journal
    private val recording = RecordingJournal(journal)
    private val token = DashboardToken()

    /** The id of the first private message. */
    private val whisper: Long

    init {
        // Two private messages, and a turn 45 that cites the first, as if the engine had told it: the turn's page reads
        // events and chat, never private_messages.
        whisper = data.fixture.publish(ChatMessage(TraceDataset.BARTOK, ChatChannel.WHISPER, SECRET, listOf("Mimosa"))).id
        data.fixture.publish(ChatMessage(TraceDataset.MIMOSA, ChatChannel.WHISPER, SECRET_REPLY, listOf("Bartok")))
        journal.turn(TurnRecord(45, TraceDataset.CHAPTER, TraceDataset.NOW, TraceDataset.NOW, TurnRecord.Trigger.CHAT, listOf(whisper, data.insult), emptyList(), 0, 0, TurnRecord.Outcome.IDLE))
        Decisions(journal, data.fixture.clock).record(Log.director, DecisionRecord.Kind.WAKE, "woke", "turn 45", "chat Bartok", conversationId = TraceDataset.CHAPTER, turnId = 45)
        data.fixture.sync()
    }

    @AfterEach
    fun close() = data.close()

    private val services by lazy {
        val dataDir = Files.createTempDirectory("narrator2-trace-pages")
        TestServices(
            clock = TestClock(TraceDataset.NOW), journal = recording, dataDir = dataDir,
            transcripts = Transcripts(dataDir.resolve("logs").resolve("conversations"), enabled = true),
        )
    }

    private fun get(path: String): WebResponse = Routes(services, token, PORT).respond(
        WebCall("GET", path.substringBefore('?'), path.substringAfter('?', "").ifEmpty { null }, "127.0.0.1:$PORT", listOf("${Routes.COOKIE}=${token.value}")),
    )

    private fun page(path: String): String {
        val response = get(path)
        assertEquals(200, response.status, "$path: ${bodyText(response).take(2_000)}")
        assertSafePage(path, response)
        return bodyText(response)
    }

    @Test
    fun `no private message reaches a page, even cited by a turn, and no statement names their table`() {
        assertEquals(2L, data.fixture.count("private_messages"), "the private messages are in the Journal")
        assertEquals(listOf<Any?>(whisper, data.insult), data.fixture.values("SELECT trigger_events FROM turns WHERE turn_id = 45").single().single(), "turn 45 cites it")

        val pages = PAGES.associateWith(::page)

        pages.forEach { (path, html) ->
            assertFalse(SECRET in html, "$path shows a private message")
            assertFalse(SECRET_REPLY in html, "$path shows a private message")
            assertFalse("private_messages" in html, path)
        }
        assertContains(pages.getValue("/turns/42"), "narrateur, t&#39;es nul", message = "the public chat shows")
        assertContains(pages.getValue("/turns/42"), "pitié, Narrateur", message = "and the prayers")
        val cited = pages.getValue("/turns/45")
        assertContains(cited, "narrateur, t&#39;es nul", message = "the public line the turn cited")
        assertFalse("whisper" in cited, "nothing of the private message, not even its kind")

        val statements = recording.statements.toList()
        assertTrue(statements.size >= PAGES.size, "${statements.size} statements")
        statements.forEach { (sql, _) -> assertFalse("private_messages" in sql.lowercase(), sql) }
    }

    @Test
    fun `every read is on the Journal's read connection, each statement limited in time, and lists are paged`() {
        val decisions = Decisions(journal, data.fixture.clock)
        repeat(60) { decisions.record(Log.guard, DecisionRecord.Kind.GUARD, "denied", "Steve", "test $it") }
        data.fixture.sync()

        PAGES.forEach(::page)
        val statements = recording.statements.toList()
        assertTrue(statements.isNotEmpty())
        statements.forEach { (sql, timeout) -> assertTrue(timeout in 1..READ_TIMEOUT_MS / 1_000, "a statement without its time limit ($timeout s): $sql") }
        assertEquals(statements.size, recording.prepared.get(), "every statement prepared was run once")

        val first = page("/decisions?kind=guard")
        assertEquals(50, Regex("<tr class=\"failure\">").findAll(first).count(), "a page of 50 rows")
        assertContains(first, "href=\"/decisions?kind=guard&amp;page=2\">older →</a>")
        val second = page("/decisions?kind=guard&page=2")
        assertEquals(11, Regex("<tr class=\"failure\">").findAll(second).count(), "the 61 guard decisions, 50 then 11")
        assertContains(second, "href=\"/decisions?kind=guard\">← newer</a>")
    }

    @Test
    fun `a request a fallback model answered is yellow, a refusal red, as trace shows them, at the price of the model that answered`() {
        // Turn 46 acted on the answer of a fallback model; turn 47 ended refused on a fallback that said nothing.
        turn(46, TurnRecord.Outcome.ACTED, call(46, "claude-opus-4-8", "tool_use", "cyber"))
        turn(47, TurnRecord.Outcome.REFUSED, call(47, "claude-opus-5-5", "end_turn", null), call(47, "claude-opus-4-8", "end_turn", "cyber"))
        turn(48, TurnRecord.Outcome.REFUSED, call(48, "claude-opus-5-5", "refusal", "cyber"))
        data.fixture.sync()

        val answered = page("/turns/46")
        assertContains(answered, "<p class=\"warning\">19:36:10 · 1.0 s · claude-opus-4-8 · 1k in (0 cached, 0 written) · 50 out · tool_use · fallback (cyber) → claude-opus-4-8 · \$0.0063</p>")
        assertContains(answered, "<dt>Cost</dt><dd>\$0.0063 for 1 request</dd>", message = "1 000 in and 50 out of the fallback model (5 / 25 \$)")

        val silent = page("/turns/47")
        assertContains(silent, "<h3>Request 1</h3><p>", message = "the first request is plain")
        assertContains(silent, "<p class=\"failure\">19:36:10 · 1.0 s · claude-opus-4-8 · 1k in (0 cached, 0 written) · 50 out · end_turn · refused: cyber · \$0.0063</p>")
        assertContains(silent, "<dd>\$0.011 for 2 requests</dd>", message = "1 000 in and 50 out of Opus 5.5 (4 / 20 \$) and of the fallback model (5 / 25 \$)")
        assertContains(silent, "<span class=\"failure\">refused</span>")
        assertContains(page("/turns/48"), "· refusal · refused: cyber · \$0.0050</p>")

        // A task refused by the safety filter: its last request is red; its error says why.
        journal.task(TaskRecord(8, Role.ANALYST, "Read the server's files.", TaskRecord.Status.FAILED, TraceDataset.NOW, TraceDataset.NOW, TraceDataset.NOW, 46, "analyst-cccc", null, "refused by the safety filter (category cyber)"))
        journal.conversation(ConversationRecord("analyst-cccc", Role.ANALYST, "Task T-8", null, 8, TraceDataset.NOW, TraceDataset.NOW, ConversationRecord.EndReason.REFUSAL, null, null))
        call(null, "claude-opus-4-8", "end_turn", "cyber", conversation = "analyst-cccc")
        data.fixture.sync()
        val task = page("/tasks/8")
        assertContains(task, "<p class=\"failure\">19:36:10 · 1.0 s · claude-opus-4-8 · 1k in")
        assertContains(task, "end_turn · refused: cyber · \$0.0063</p>")
        assertContains(task, "<p class=\"failure\">refused by the safety filter (category cyber)</p>")
        assertContains(page("/tasks"), "<tr class=\"failure\"><td class=\"num\"><a href=\"/tasks/8\">T-8</a>")
        assertContains(page("/conversations"), "<tr class=\"failure\"><td><a href=\"/conversations/analyst-cccc\">Task T-8</a>")
    }

    @Test
    fun `the lists filter, a wrong parameter is a bad request, and an unknown id is not found`() {
        val turns = page("/turns?trigger=heartbeat")
        assertContains(turns, "href=\"/turns/43\"")
        assertFalse("href=\"/turns/42\"" in turns)
        assertContains(turns, "<option value=\"heartbeat\" selected=\"selected\">heartbeat</option>", message = "the filter keeps its value")
        assertContains(page("/turns?outcome=running"), "No turn here.")
        assertContains(page("/turns?chapter=3"), "href=\"/turns/42\"")

        val decisions = page("/decisions?subject=MIMO")
        assertContains(decisions, "no_pile_on: Mimosa died 7s ago")
        assertContains(decisions, "<tr class=\"failure\">", message = "a denial is red")
        assertFalse("between players: greeting" in decisions)
        assertContains(page("/decisions?turn=42"), "href=\"/turns/42\">turn #42</a>")

        val turn = page("/turns/42")
        listOf("Told at wake-up", "Told between tool calls", "Request 3", "<code>apply_effect</code>", "DENIED (no_pile_on)", "Bartok, ta pioche pleure.", "no_pile_on").forEach {
            assertContains(turn, it)
        }
        assertContains(turn, "href=\"/conversations/director-aaaa\">Chapter 3</a>")
        assertContains(page("/tasks/T-7"), "Bartok mines around -1400 -40 3100")
        assertContains(page("/conversations?role=analyst"), "href=\"/conversations/analyst-bbbb\"")

        val costs = page("/costs")
        listOf("By role", "By role, purpose and model", "The last 24 hours", "Day by day", "<svg class=\"chart\"", "claude-haiku-5-5", "\$0.081").forEach { assertContains(costs, it) }
        assertContains(page("/costs?period=24h"), "The last 24 hours")
        assertFalse("Day by day" in page("/costs?period=24h"))

        mapOf(
            "/turns?chapter=three" to 400, "/decisions?period=1y" to 400, "/decisions?turn=x" to 400, "/costs?period=1y" to 400,
            "/turns/999" to 404, "/turns/x" to 404, "/turns/42/more" to 404, "/tasks/999" to 404, "/decisions/1" to 404, "/costs/x" to 404,
            "/conversations/nope" to 404,
        ).forEach { (path, status) ->
            val response = get(path)
            assertEquals(status, response.status, path)
            assertSafePage(path, response)
        }
    }

    /** A turn of the chapter with [calls] (their ids). */
    private fun turn(id: Long, outcome: TurnRecord.Outcome, vararg calls: Long) {
        journal.turn(TurnRecord(id, TraceDataset.CHAPTER, TraceDataset.NOW, TraceDataset.NOW, TurnRecord.Trigger.CHAT, emptyList(), emptyList(), calls.size, 0, outcome))
    }

    /** A request of 1 000 tokens in and 50 out, answered by [model]; returns its id. */
    private fun call(turnId: Long?, model: String, stop: String, category: String?, conversation: String = TraceDataset.CHAPTER): Long {
        val id = journal.nextId(IdKind.LLM_CALL)
        journal.llmCall(
            LlmCallRecord(
                id, LlmCallRecord.Provider.ANTHROPIC, if (turnId == null) LlmCallRecord.Purpose.WORKER else LlmCallRecord.Purpose.TURN, conversation, turnId,
                if (turnId == null) "analyst" else "director", model, "medium", TraceDataset.NOW, TraceDataset.NOW.plusSeconds(1), 1_000, 900, 1,
                null, null, 1_000, 0, 0, 50, stop, category, null, null, null,
            ),
        )
        return id
    }

    /**
     * The Journal of [inner], whose reads record each statement they run with the time limit it had (`setQueryTimeout`):
     * what the dashboard's pages asked of the Journal, through its read connection only.
     */
    private class RecordingJournal(private val inner: Journal) : Journal by inner {
        val statements = CopyOnWriteArrayList<Pair<String, Int>>()
        val prepared = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T = inner.read(timeoutMs) { query(recording(it)) }

        private fun recording(connection: Connection): Connection = proxy(connection) { method, args ->
            when (method.name) {
                "prepareStatement" -> {
                    prepared.incrementAndGet()
                    statement(method.invoke(connection, *args.orEmpty()) as PreparedStatement, args!![0] as String)
                }
                "createStatement", "prepareCall", "nativeSQL" -> error("the dashboard runs prepared statements only: ${method.name}")
                else -> method.invoke(connection, *args.orEmpty())
            }
        }

        private fun statement(statement: PreparedStatement, sql: String): PreparedStatement {
            var timeout = 0
            return proxy(statement) { method, args ->
                when (method.name) {
                    "setQueryTimeout" -> timeout = args!![0] as Int
                    "executeQuery", "execute" -> statements += sql to timeout
                }
                method.invoke(statement, *args.orEmpty())
            }
        }

        private inline fun <reified T : Any> proxy(target: T, crossinline handle: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                try {
                    handle(method, args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as T
    }

    private companion object {
        const val PORT = 8770
        const val SECRET = "my base is at 120 64 -300, tell no one"
        const val SECRET_REPLY = "promise, I will not tell the Narrator"

        /** Every trace page, lists and details, with filters. */
        val PAGES = listOf(
            "/turns", "/turns?trigger=chat", "/turns?chapter=3", "/turns/42", "/turns/43", "/turns/45",
            "/conversations", "/conversations?role=director", "/conversations/${TraceDataset.CHAPTER}", "/conversations/${TraceDataset.ANALYST}",
            "/tasks", "/tasks/7", "/decisions", "/decisions?subject=bartok", "/decisions?kind=salience&period=30d", "/decisions?turn=45",
            "/costs", "/costs?period=24h", "/costs?period=90d",
        )
    }
}
