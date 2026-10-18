package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SQL of the memory jobs (M3 §6.6, §7.1, §18) on an in-memory Journal. */
class DuckJobsDataTest {
    private val fixture = StoreFixture()
    private val data = DuckJobsData(fixture.journal)
    private val start: Instant = fixture.clock.instant()

    @AfterEach
    fun close() = fixture.close()

    private fun at(minutes: Long): Instant = start + Duration.ofMinutes(minutes)

    private fun run(id: Long, kind: RunKind, status: RunStatus, startedAt: Instant, chapter: String? = null, error: String? = null,
                    windowTo: Instant? = null, conversations: List<String> = emptyList()) = MemoryRun(
        id, kind, RunTrigger.TIMER, status, startedAt, if (status == RunStatus.RUNNING) null else startedAt + Duration.ofMinutes(1),
        null, windowTo, chapter, conversations, 0, 0, null, null, null, error,
    )

    private fun chapter(id: String, number: Int, startedAt: Instant, endedAt: Instant?, reason: String? = "quiet_limit") = fixture.sql(
        "INSERT INTO conversations (conversation_id, role, label, chapter, started_at, ended_at, end_reason, summary) " +
            "VALUES (?, 'director', ?, ?, ?, ?, ?, ?)",
        id, "Chapter $number", number, startedAt, endedAt, reason, "summary $number",
    )

    private fun canon(id: Long, due: Instant?, status: String = "active", signaled: Instant? = null) = fixture.sql(
        "INSERT INTO canon (id, kind, created_at, statement, players, importance, status, due_at, due_signaled_at, source, " +
            "evidence_events, evidence_tools) VALUES (?, 'promise', ?, 'Le Narrateur a promis.', [], 5, ?, ?, ?, 'director', [], [])",
        id, start, status, due, signaled,
    )

    @Test
    fun `runs are inserted, finished with the tokens of their conversations, read back and interrupted after a crash`() = runBlocking<Unit> {
        data.insertRun(run(1, RunKind.BATCH, RunStatus.RUNNING, start, chapter = "director-c1"))
        fixture.sql(
            "INSERT INTO llm_calls (id, provider, purpose, conversation_id, role, model, started_at, ended_at, latency_ms, attempts, " +
                "input_tokens, cache_read_tokens, cache_write_tokens, output_tokens) VALUES " +
                "(1, 'anthropic', 'chronicle', 'chronicler-a', 'chronicler', 'm', ?, ?, 10, 1, 100, 1000, 10, 50), " +
                "(2, 'anthropic', 'chronicle', 'chronicler-a', 'chronicler', 'm', ?, ?, 10, 1, 200, 2000, 0, 70), " +
                "(3, 'anthropic', 'turn', 'director-c1', 'director', 'm', ?, ?, 10, 1, 999, 0, 0, 999)",
            start, start, start, start, start, start,
        )
        val ended = run(1, RunKind.BATCH, RunStatus.DONE, start, chapter = "director-c1", windowTo = at(10), conversations = listOf("chronicler-a"))
            .copy(writes = 3, rejected = 1, report = "two promises")
        data.finishRun(ended)

        val read = data.lastRun(RunKind.BATCH)!!
        assertEquals(ended.copy(inputTokens = 3_310, outputTokens = 120), read)
        assertEquals(read, data.lastRun(RunKind.BATCH, setOf(RunStatus.DONE), "director-c1"))
        assertNull(data.lastRun(RunKind.BATCH, setOf(RunStatus.FAILED)))
        assertNull(data.lastRun(RunKind.BATCH, chapter = "director-c2"))

        data.insertRun(run(2, RunKind.DREAM, RunStatus.RUNNING, at(5)))
        assertEquals(1, data.interruptRunning(at(9)))
        val dream = data.lastRun(RunKind.DREAM)!!
        assertEquals(RunStatus.INTERRUPTED, dream.status)
        assertEquals(at(9), dream.endedAt)
        assertEquals(RunStatus.DONE, data.lastRun(RunKind.BATCH)!!.status)
    }

    @Test
    fun `the first nap done after a time`() = runBlocking<Unit> {
        data.insertRun(run(1, RunKind.NAP, RunStatus.DONE, at(0)))
        data.insertRun(run(2, RunKind.NAP, RunStatus.FAILED, at(10)))
        data.insertRun(run(3, RunKind.NAP, RunStatus.DONE, at(20)))
        assertEquals(at(1), data.firstNapDone(null))
        assertEquals(at(21), data.firstNapDone(at(5)))
        assertNull(data.firstNapDone(at(30)))
    }

    @Test
    fun `chapters to nap are the closed Director chapters without a nap done, failed fewer times than the limit`() = runBlocking<Unit> {
        chapter("director-c1", 1, at(0), at(30))
        chapter("director-c2", 2, at(30), at(60), "shutdown")
        chapter("director-c3", 3, at(60), at(90), "interrupted")
        chapter("director-c4", 4, at(90), at(120))
        chapter("director-c5", 5, at(120), null)
        fixture.sql(
            "INSERT INTO conversations (conversation_id, role, label, started_at, ended_at, end_reason) " +
                "VALUES ('analyst-1', 'analyst', 'Task T-1', ?, ?, 'done')",
            at(0), at(1),
        )
        data.insertRun(run(1, RunKind.NAP, RunStatus.DONE, at(31), "director-c1"))
        repeat(3) { data.insertRun(run(2L + it, RunKind.NAP, RunStatus.FAILED, at(61L + it), "director-c3", "empty replies")) }
        repeat(4) { data.insertRun(run(10L + it, RunKind.NAP, RunStatus.FAILED, at(121L + it), "director-c4", "budget: the LLMGW budget is spent")) }
        data.insertRun(run(20, RunKind.NAP, RunStatus.FAILED, at(130), "director-c2", "empty replies"))

        val chapters = data.chaptersToNap(maxFailures = 3, limit = 10)

        assertEquals(listOf("director-c2", "director-c4"), chapters.map { it.conversationId })
        assertEquals(
            ConversationRecord("director-c2", Role.DIRECTOR, "Chapter 2", 2, null, at(30), at(60), ConversationRecord.EndReason.SHUTDOWN, "summary 2", null),
            chapters.first(),
        )
        assertEquals(listOf("director-c2"), data.chaptersToNap(3, 1).map { it.conversationId })
        assertEquals(at(120), data.chapter("director-c5")!!.startedAt)
        assertNull(data.chapter("director-c9"))
    }

    @Test
    fun `the Narrator is active in a window when it spoke or its Director called a tool that is not a read`() = runBlocking<Unit> {
        fixture.chat(1, at(5), "public", "salut", fixture.bartok.uuid)
        assertFalse(data.narratorActive("director-c13", at(0), at(10)))
        fixture.sql(
            "INSERT INTO tool_calls (id, ts, conversation_id, tool_use_id, role, tool, status, duration_ms, effect) " +
                "VALUES (1, ?, 'director-c13', 'tu', 'director', 'sql', 'ok', 5, 'read')",
            at(6),
        )
        assertFalse(data.narratorActive("director-c13", at(0), at(10)))
        fixture.toolCall(2, at(7), "apply_effect")
        assertTrue(data.narratorActive("director-c13", at(0), at(10)))
        assertFalse(data.narratorActive("director-c14", at(0), at(10)), "another chapter's tool call")
        assertFalse(data.narratorActive("director-c13", at(7), at(10)), "the window starts after")
        fixture.chat(3, at(12), "operator", "Mimosa, je te rends tes bottes.")
        assertTrue(data.narratorActive("director-c14", at(10), at(12)))
    }

    @Test
    fun `due canon is active, due and not signaled yet, and is signaled once`() = runBlocking<Unit> {
        canon(1, at(10))
        canon(2, at(5))
        canon(3, at(30))
        canon(4, at(1), status = "fulfilled")
        canon(5, at(1), signaled = at(2))
        canon(6, null)
        assertEquals(listOf(Ref(RefKind.CANON, 2), Ref(RefKind.CANON, 1)), data.dueCanon(at(10)))
        data.markDueSignaled(Ref(RefKind.CANON, 2), at(10))
        assertEquals(listOf(Ref(RefKind.CANON, 1)), data.dueCanon(at(10)))
        assertEquals(at(10), fixture.rows("SELECT due_signaled_at FROM canon WHERE id = 2") { getObject(1, java.time.OffsetDateTime::class.java).toInstant() }.single())
    }

    @Test
    fun `refusals and voice warnings of the last day`() = runBlocking<Unit> {
        listOf("rejected" to at(-2000), "rejected" to at(-10), "rejected" to at(-5), "voice_warning" to at(-1), "written" to at(-1)).forEachIndexed { i, (decision, ts) ->
            fixture.sql("INSERT INTO engine_decisions (id, ts, kind, decision) VALUES (?, ?, 'memory', ?)", i.toLong(), ts, decision)
        }
        fixture.sql("INSERT INTO engine_decisions (id, ts, kind, decision) VALUES (99, ?, 'guard', 'rejected')", at(-1))
        assertEquals(Refusals(rejected = 2, voiceWarnings = 1), data.refusals(at(0) - Duration.ofHours(24)))
    }
}
