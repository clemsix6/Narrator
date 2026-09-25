@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.clemsix6.narrator2.agent

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.WorkersConfig
import io.github.clemsix6.narrator2.director.DirectorHarness.SchedulerClock
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.TestTool
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.tool.task.DelegateTool
import io.github.clemsix6.narrator2.tool.task.TasksTool
import io.github.clemsix6.narrator2.tool.toolUse
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The workers (M1_M2 §13) and the delegation tools (§9.6) against the strict fake Anthropic server: a
 * task per conversation and session, the limit of tasks at once and the line, the timeout, the
 * cancellation, the failures (402 budget, refusal, empty report), and the report posted to the Director.
 * Virtual time, except where the test needs real threads.
 */
class WorkersTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val rigs = CopyOnWriteArrayList<Rig>()

    @AfterEach
    fun close() {
        rigs.forEach { it.close() }
        fake.close()
    }

    @Serializable
    data class QueryArgs(val query: String)

    /**
     * Workers wired to the fake server, whose tasks have one tool, a fake `sql` that answers `n\n42`, and
     * a [FakeDirector] that records what they post. [virtualTime]: the tasks run on a test dispatcher
     * that only moves when the test pumps it ([await], [advance]); else on real threads.
     */
    private inner class Rig(
        maxConcurrent: Int = 3,
        maxSteps: Int = 15,
        val timeoutSeconds: Int = 180,
        journalAvailable: Boolean = true,
        virtualTime: Boolean = true,
    ) : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + if (virtualTime) StandardTestDispatcher(scheduler) else Dispatchers.Default)
        val clock = if (virtualTime) SchedulerClock(scheduler, START) else TestClock(START)
        val traces = MemoryTraceSink()
        val director = FakeDirector(clock)
        val client: ClaudeClient
        val base: TestServices
        val services: NarratorServices
        val workers: Workers

        /** Queries the fake `sql` got, in order. */
        val queries = CopyOnWriteArrayList<String>()

        init {
            val defaults = testConfig(fake.baseUrl)
            val config = defaults.copy(workers = WorkersConfig(maxConcurrent, maxSteps, timeoutSeconds))
            client = ClaudeClient(config, TEST_API_KEY)
            val disabled = DisabledJournal("unit test", clock)
            val journal: Journal = if (journalAvailable) object : Journal by disabled { override val available = true } else disabled
            base = TestServices(
                clock = clock,
                scope = scope,
                dataDir = dir,
                settings = testSettings(config, client),
                journal = journal,
                trace = traces,
                transcripts = Transcripts(dir.resolve("logs"), enabled = true),
                director = director,
            )
            services = object : NarratorServices by base {
                override val workers get() = this@Rig.workers
            }
            val sql = TestTool("sql", QueryArgs.serializer(), body = { _, args -> queries += args.query; ToolResult.ok("n\n42") })
            workers = Workers(services) { _, _ -> ToolSet.of(listOf(sql)) }
            rigs += this
        }

        fun submit(brief: String = "Where does Bartok mine these days? Coordinates please."): Long =
            workers.submit(Role.ANALYST, brief, DIRECTOR_CALLER).getOrThrow()

        fun task(id: Long): TaskRecord = workers.list().firstOrNull { it.taskId == id } ?: fail("T-$id is not listed")

        fun session(id: Long): String = task(id).conversationId ?: fail("T-$id has no conversation yet")

        /** The final `TaskDone` signals posted to the Director. */
        fun done(): List<DirectorSignal.TaskDone> = director.signals.filterIsInstance<DirectorSignal.TaskDone>()

        /** Pumps the test dispatcher until [condition] holds: the fake server answers on real threads. */
        fun await(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                scheduler.runCurrent()
                if (condition()) return
                if (System.nanoTime() > deadline) fail("timed out waiting for $what: ${workers.list().map { "T-${it.taskId} ${it.status.id}" }}")
                Thread.sleep(2)
            }
        }

        fun awaitStatus(id: Long, status: Status) = await("T-$id $status") { task(id).status == status }

        /** Waits until every task's coroutine has completed: the conversation rows are ended. */
        fun awaitSettled(id: Long) = await("T-$id settled") { traces.conversations.any { it.taskId == id && it.endReason != null } || task(id).conversationId == null }

        fun advance(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }

        fun requestsOf(id: Long): List<RecordedRequest> = fake.requests.filter { it.header("X-Session-ID") == task(id).conversationId }

        /** Runs [tool] with [input] as the Director would, through the real `ToolRunner`. */
        fun <A : Any> call(tool: NarratorTool<A>, input: String): JsonNode {
            val block = runBlocking { ToolRunner(services).runAll(listOf(toolUse("toolu_1", tool.name, input)), ToolSet.of(listOf(tool)), DIRECTOR_CALLER, 1) }.single()
            return jsonMapper().valueToTree(block)
        }

        override fun close() {
            scope.cancel()
            scheduler.runCurrent()
            client.close()
        }
    }

    private fun analystReport(text: String = "Réponse : Bartok mine en overworld vers 100 -40 200.") = reply { thinking("Two queries settle it."); text(text) }

    private fun sqlCall(query: String = "SELECT count(*) AS n FROM mining WHERE player = 'Bartok'") = reply { toolUse("sql", """{"query": "$query"}""") }

    private fun held(hold: Hold, text: String) = reply { hold(hold); text(text) }

    private fun enqueueFor(session: String, vararg responses: FakeResponse) = fake.enqueueFor(session, *responses)

    // ── Delegate returns at once ────────────────────────────────────────────────────────────────────

    @Test
    fun `delegate returns at once while the worker is still waiting for its first reply`() {
        val rig = Rig(virtualTime = false)
        val hold = Hold()
        fake.enqueue(held(hold, "Réponse : 42."))

        val result = rig.call(DelegateTool(), """{"role": "analyst", "brief": "Where does Bartok mine these days? Coordinates please."}""")

        assertEquals("ok: task T-1 analyst started; its report will arrive as a TASK event", result.path("content").asText())
        assertTrue(result.path("is_error").isMissingNode)
        assertTrue(hold.awaitReached(), "the worker is talking to Claude")
        assertEquals(Status.RUNNING, rig.task(1).status, "the Director got its answer while the task runs")
        assertTrue(rig.done().isEmpty())
        val row = rig.traces.toolCalls.single()
        assertEquals("delegate", row.tool)
        assertEquals(ToolEffect.TASK, row.effect)

        hold.release()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (rig.done().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        val done = rig.done().single()
        assertEquals(DirectorSignal.TaskDone(1, Role.ANALYST, Status.DONE, "Réponse : 42.", done.at), done)
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    // ── A conversation and a session per task ───────────────────────────────────────────────────────

    @Test
    fun `each task is its own conversation, with its own session, prompt, tools and opening`() {
        val rig = Rig()
        rig.base.players.put(online("Bartok"))
        val first = rig.submit()
        val second = rig.submit("Who killed the most zombies tonight? Counts per player.")
        val sessions = listOf(rig.session(first), rig.session(second))
        enqueueFor(sessions[0], sqlCall(), sqlCall("SELECT 2"), analystReport("Réponse : T-1."))
        enqueueFor(sessions[1], analystReport("Réponse : T-2."))

        rig.await("both reports") { rig.done().size == 2 }

        assertNotEquals(sessions[0], sessions[1])
        assertTrue(sessions.all { it.startsWith("analyst-") }, "$sessions")
        assertEquals(listOf(3, 1), sessions.map { session -> fake.requests.count { it.header("X-Session-ID") == session } })
        val opening = rig.requestsOf(first).first().json.path("messages")[0].path("content")[0].path("text").asText()
        assertEquals(
            "<task id=\"T-1\" from=\"director\">\nWhere does Bartok mine these days? Coordinates please.\n</task>\n" +
                "<context now=\"2026-09-25 21:32:00\" players_online=\"Bartok\"/>",
            opening,
        )
        val request = rig.requestsOf(second).single().json
        assertEquals(listOf("sql"), request.path("tools").map { it.path("name").asText() })
        assertContains(request.path("system")[0].path("text").asText(), "Tu es l'Analyste du Narrateur")
        assertEquals("claude-opus-5-5", request.path("model").asText())

        assertEquals(listOf("SELECT count(*) AS n FROM mining WHERE player = 'Bartok'", "SELECT 2"), rig.queries)
        assertEquals(setOf("Réponse : T-1.", "Réponse : T-2."), rig.done().map { it.result }.toSet())
        for ((id, session) in listOf(first, second).zip(sessions)) {
            val conversation = assertNotNull(rig.traces.latestConversation(session))
            assertEquals(ConversationRecord.EndReason.DONE, conversation.endReason)
            assertEquals(id, conversation.taskId)
            assertEquals("Task T-$id (analyst)", conversation.label)
            assertEquals(Role.ANALYST, conversation.role)
            assertEquals(rig.task(id).result, conversation.summary)
            assertTrue(conversation.transcript!!.endsWith("$session.md"), conversation.transcript)
        }
        assertEquals(3, rig.traces.llmCalls.count { it.purpose == LlmCallRecord.Purpose.WORKER && it.conversationId == sessions[0] })
        assertTrue(rig.traces.toolCalls.all { it.role == Role.ANALYST && it.conversationId == sessions[0] && it.turnId == null })
        assertEquals(listOf(Status.QUEUED, Status.RUNNING, Status.DONE), rig.traces.tasks.filter { it.taskId == first }.map { it.status })
        val row = rig.traces.latestTask(first)!!
        assertEquals(41L, row.requestedByTurn)
        assertEquals(sessions[0], row.conversationId)
        assertEquals("Réponse : T-1.", row.result)
        assertNull(row.error)
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    // ── At most maxConcurrent at once; the others wait in line ─────────────────────────────────────

    @Test
    fun `at most maxConcurrent tasks run at once, the others wait in line, first come first served`() {
        val rig = Rig(maxConcurrent = 2)
        val holds = List(3) { Hold() }
        val ids = List(4) { rig.submit("Question number ${it + 1}: where does Bartok mine?") }
        enqueueFor(rig.session(ids[0]), held(holds[0], "Réponse : 1."))
        enqueueFor(rig.session(ids[1]), held(holds[1], "Réponse : 2."))
        fake.enqueue(held(holds[2], "Réponse : 3."), analystReport("Réponse : 4."))

        rig.await("two tasks at Claude") { holds[0].awaitReached(0) && holds[1].awaitReached(0) }
        assertEquals(listOf(Status.RUNNING, Status.RUNNING, Status.QUEUED, Status.QUEUED), ids.map { rig.task(it).status })
        assertNull(rig.task(ids[2]).conversationId)
        assertEquals(2, fake.requests.size)
        val listed = rig.call(TasksTool(), """{"action": "list"}""").path("content").asText()
        assertContains(listed, "tasks: 2 running, 2 queued, 0 ended in the last 30 minutes\n")
        assertContains(listed, "T-3 analyst queued for 0s: \"Question number 3: where does Bartok mine?\"")

        holds[0].release()
        rig.await("T-3 at Claude") { holds[2].awaitReached(0) }
        assertEquals(listOf(Status.DONE, Status.RUNNING, Status.RUNNING, Status.QUEUED), ids.map { rig.task(it).status })
        assertEquals(rig.session(ids[2]), fake.requests[2].header("X-Session-ID"), "the first in line starts first")

        holds[1].release()
        rig.awaitStatus(ids[3], Status.DONE)
        holds[2].release()
        rig.awaitStatus(ids[2], Status.DONE)
        assertEquals(listOf("Réponse : 1.", "Réponse : 2.", "Réponse : 4.", "Réponse : 3."), rig.done().map { it.result })
        assertTrue(rig.task(ids[2]).startedAt!! >= rig.task(ids[0]).finishedAt!!)
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    // ── Timeout ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a task past workers_timeoutSeconds times out, its stream is closed, and the next task starts`() {
        val rig = Rig(maxConcurrent = 1, timeoutSeconds = 180)
        val hold = Hold()
        val slow = rig.submit()
        val next = rig.submit("Who died the most today, and to what?")
        enqueueFor(rig.session(slow), held(hold, "Réponse : trop tard."))
        fake.enqueue(analystReport("Réponse : Mimosa, 3 fois."))
        rig.await("the slow task at Claude") { hold.awaitReached(0) }

        rig.advance(179_999)
        assertEquals(Status.RUNNING, rig.task(slow).status)
        rig.advance(1)
        rig.awaitStatus(slow, Status.TIMEOUT)

        with(rig.task(slow)) {
            assertEquals("no report within 180 s", error)
            assertNull(result)
            assertEquals(START.plusSeconds(180), finishedAt)
        }
        assertEquals(DirectorSignal.TaskDone(slow, Role.ANALYST, Status.TIMEOUT, "no report within 180 s", START.plusSeconds(180).toEpochMilli()), rig.done().first())
        rig.awaitSettled(slow)
        assertEquals(ConversationRecord.EndReason.TIMEOUT, rig.traces.latestConversation(rig.session(slow))!!.endReason)
        hold.release()
        assertTrue(hold.awaitFinished())
        assertTrue(hold.clientGone, "the timed-out call's stream was closed")
        rig.awaitStatus(next, Status.DONE)
        assertEquals("Réponse : Mimosa, 3 fois.", rig.done().last().result)
    }

    // ── Cancellation ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `tasks cancel stops a running task at once, posts nothing, and frees its place`() {
        val rig = Rig(maxConcurrent = 1)
        val hold = Hold()
        val running = rig.submit()
        val queued = rig.submit("Who died the most today, and to what?")
        enqueueFor(rig.session(running), held(hold, "Réponse : jamais lue."))
        fake.enqueue(analystReport("Réponse : Mimosa."))
        rig.await("the task at Claude") { hold.awaitReached(0) }

        val result = rig.call(TasksTool(), """{"action": "cancel", "task_id": $running}""")

        assertEquals("ok: task T-1 cancelled; its report will never come", result.path("content").asText())
        assertEquals(Status.CANCELLED, rig.task(running).status, "listed as cancelled at once")
        assertEquals("cancelled by the Director", rig.task(running).error)
        rig.awaitStatus(queued, Status.DONE)
        rig.awaitSettled(running)
        hold.release()
        assertTrue(hold.awaitFinished())
        assertTrue(hold.clientGone, "the cancelled call's stream was closed")
        assertEquals(listOf(queued), rig.done().map { it.taskId }, "the Director cancelled it: nothing is posted for it")
        assertEquals(listOf(Status.QUEUED, Status.RUNNING, Status.CANCELLED), rig.traces.tasks.filter { it.taskId == running }.map { it.status })
        assertEquals(ConversationRecord.EndReason.CANCELLED, rig.traces.latestConversation(rig.session(running))!!.endReason)
    }

    @Test
    fun `a queued task cancelled never starts, and a task cannot be cancelled twice nor when unknown`() {
        val rig = Rig(maxConcurrent = 1)
        val hold = Hold()
        val running = rig.submit()
        val queued = rig.submit("Who died the most today, and to what?")
        enqueueFor(rig.session(running), held(hold, "Réponse : 1."))
        rig.await("the task at Claude") { hold.awaitReached(0) }

        val result = rig.call(TasksTool(), """{"action": "cancel", "task_id": $queued}""")
        assertEquals("ok: task T-2 cancelled before it started; its report will never come", result.path("content").asText())
        assertEquals("cancelled by the Director before it started", rig.task(queued).error)
        hold.release()
        rig.awaitStatus(running, Status.DONE)
        rig.advance(60_000)
        assertEquals(1, fake.requests.size, "the cancelled task never called Claude")
        assertNull(rig.task(queued).conversationId)
        assertEquals(listOf(running), rig.done().map { it.taskId })

        val twice = rig.call(TasksTool(), """{"action": "cancel", "task_id": $queued}""")
        assertTrue(twice.path("is_error").asBoolean())
        assertEquals("Invalid arguments: task T-2 already ended (cancelled)", twice.path("content").asText())
        assertEquals("no task T-9; no task is running or queued", rig.workers.cancel(9).exceptionOrNull()!!.message)
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a 402 fails the task with budget, and the Director is told`() {
        val rig = Rig()
        val id = rig.submit()
        enqueueFor(rig.session(id), sqlCall(), budgetExceeded(resetAt = "2026-09-25T22:00:00Z"))

        rig.awaitStatus(id, Status.FAILED)

        assertEquals("budget: the LLMGW budget is spent until 22:00", rig.task(id).error)
        val done = rig.done().single()
        assertEquals(Status.FAILED, done.status)
        assertEquals("budget: the LLMGW budget is spent until 22:00", done.result)
        rig.awaitSettled(id)
        assertEquals(ConversationRecord.EndReason.FAILED, rig.traces.latestConversation(rig.session(id))!!.endReason)
        assertEquals(402, rig.traces.llmCalls.last().httpStatus)
        assertEquals(2, fake.requests.size, "no retry")
    }

    @Test
    fun `a refusal, an empty report and a runaway task fail with their reason`() {
        val rig = Rig(maxSteps = 3)
        val refused = rig.submit()
        val empty = rig.submit("Who died the most today, and to what?")
        val runaway = rig.submit("How many blocks did Mimosa place this week?")
        enqueueFor(rig.session(refused), reply { refusal("cyber") })
        enqueueFor(rig.session(empty), reply { thinking("Nothing to say.") })
        enqueueFor(rig.session(runaway), *Array(5) { sqlCall("SELECT $it") })

        rig.await("three failures") { rig.done().size == 3 }

        assertEquals("refused by the safety filter (category cyber)", rig.task(refused).error)
        assertEquals("empty report: the last reply had no text", rig.task(empty).error)
        assertEquals("step limit: still calling tools after 5 requests, no report", rig.task(runaway).error)
        assertEquals(listOf("SELECT 0", "SELECT 1"), rig.queries, "calls past the step limit are not run")
        assertTrue(rig.done().all { it.status == Status.FAILED })
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a report longer than 3000 characters is cut`() {
        val rig = Rig()
        val id = rig.submit()
        enqueueFor(rig.session(id), analystReport("x".repeat(3_500)))

        rig.awaitStatus(id, Status.DONE)

        val result = rig.task(id).result!!
        assertEquals(Workers.RESULT_MAX_CHARS, result.length)
        assertTrue(result.endsWith("x…"))
        assertEquals(result, rig.done().single().result)
    }

    // ── What is offered, and the server that stops ─────────────────────────────────────────────────

    @Test
    fun `the Analyst takes tasks only with the Journal and while the server runs`() {
        val closed = Rig(journalAvailable = false)
        assertEquals(emptySet(), closed.workers.roles)
        assertEquals("no analyst can take a task: the Journal is unavailable", closed.workers.submit(Role.ANALYST, "x".repeat(30), DIRECTOR_CALLER).exceptionOrNull()!!.message)
        assertEquals("no scout can take a task: there is no scout yet", closed.workers.submit(Role.SCOUT, "x".repeat(30), DIRECTOR_CALLER).exceptionOrNull()!!.message)

        val rig = Rig()
        assertEquals(setOf(Role.ANALYST), rig.workers.roles)
        rig.base.phase = Phase.STOPPING
        assertEquals(emptySet(), rig.workers.roles)
        val refused = rig.call(DelegateTool(), """{"role": "analyst", "brief": "Where does Bartok mine these days?"}""")
        assertEquals("FAILED: server is stopping", refused.path("content").asText())
        assertEquals("the server is stopping: no task can start", rig.workers.submit(Role.ANALYST, "x".repeat(30), DIRECTOR_CALLER).exceptionOrNull()!!.message)
        assertTrue(rig.workers.list().isEmpty())
    }

    @Test
    fun `the server that stops cancels the running and queued tasks, and nothing is posted`() {
        val rig = Rig(maxConcurrent = 1)
        val hold = Hold()
        val running = rig.submit()
        val queued = rig.submit("Who died the most today, and to what?")
        enqueueFor(rig.session(running), held(hold, "Réponse : jamais lue."))
        rig.await("the task at Claude") { hold.awaitReached(0) }

        rig.base.phase = Phase.STOPPING
        rig.scope.coroutineContext.job.cancel()
        rig.scheduler.runCurrent()
        rig.await("both cancelled") { rig.traces.latestTask(queued)?.status == Status.CANCELLED && rig.traces.latestTask(running)?.status == Status.CANCELLED }

        assertEquals("the server stopped", rig.traces.latestTask(running)!!.error)
        assertEquals("the server stopped before it started", rig.traces.latestTask(queued)!!.error)
        assertEquals(ConversationRecord.EndReason.SHUTDOWN, rig.traces.latestConversation(rig.traces.latestTask(running)!!.conversationId!!)!!.endReason)
        assertTrue(rig.done().isEmpty())
        hold.release()
        assertTrue(hold.awaitFinished())
        assertTrue(hold.clientGone)
    }

    @Test
    fun `tasks that ended leave the list after 30 minutes`() {
        val rig = Rig()
        val id = rig.submit()
        enqueueFor(rig.session(id), analystReport())
        rig.awaitStatus(id, Status.DONE)

        rig.advance(Workers.KEEP_ENDED_MS)
        assertEquals(listOf(id), rig.workers.list().map { it.taskId })
        rig.advance(1)
        assertTrue(rig.workers.list().isEmpty())
        assertEquals("no task T-1; no task is running or queued", rig.workers.cancel(id).exceptionOrNull()!!.message)
    }

    private fun online(name: String) = LivePlayer(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name), sessionId = 1, joinedAt = START.toEpochMilli(), isOp = false,
        gameMode = "survival", pos = Pos("minecraft:overworld", 100, -40, 200), biome = "minecraft:dripstone_caves", structure = null,
        underground = true, health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = START.toEpochMilli(),
        idle = false,
    )

    private companion object {
        val START: Instant = Instant.parse("2026-09-25T21:32:00Z")
        val DIRECTOR_CALLER = Caller(Role.DIRECTOR, "director-test", turnId = 41, taskId = null)
    }
}
