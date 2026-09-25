@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.clemsix6.narrator2.agent

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.director.Director
import io.github.clemsix6.narrator2.director.DirectorHarness.Companion.START
import io.github.clemsix6.narrator2.director.DirectorHarness.FakeGame
import io.github.clemsix6.narrator2.director.DirectorHarness.SchedulerClock
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.SqlDataset
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.FakeReflexes
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Scenario S11 (M1_M2 §17.2): the whole delegation, in virtual time, against the strict fake Anthropic
 * server. A real Director with every tool of `ToolCatalog` delegates to the Analyst and ends its turn
 * while the Analyst still waits for its first reply; the real `Workers` run the Analyst with its real
 * tools, whose two `sql` calls read a real Journal (`SqlDataset`); its report comes back as a `TaskDone`
 * that wakes the Director with `TASK T-1 analyst done`.
 */
class DelegationScenarioTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `S11 - delegate to the Analyst, two sql steps in its own session, and the report wakes the Director`() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val clock = SchedulerClock(scheduler, START)
        val fake = FakeAnthropic()
        val client = ClaudeClient(testConfig(fake.baseUrl), TEST_API_KEY)
        val dataset = SqlDataset.journal()
        val traces = MemoryTraceSink()
        val game = FakeGame()
        lateinit var workers: Workers
        lateinit var director: Director
        val base = TestServices(
            clock = clock,
            scope = scope,
            dataDir = dir,
            settings = testSettings(testConfig(fake.baseUrl), client),
            journal = dataset.journal,
            trace = traces,
            transcripts = Transcripts(dir.resolve("logs"), enabled = true),
            reflexes = FakeReflexes(chat = { FakeReflexes.verdict(0.93, Tone.MOCKING_PLAYFUL, ChatIntent.QUESTION) }),
        )
        val services = object : NarratorServices by base {
            override val workers get() = workers
            override val director get() = director
        }
        workers = Workers(services)
        director = Director(services, game, { env -> ToolCatalog.toolSetFor(Role.DIRECTOR, env, services) }, Random(7))

        // Each side has its own script: requests of the two sessions interleave.
        val directorScript = ConcurrentLinkedQueue<FakeResponse>()
        val analystScript = ConcurrentLinkedQueue<FakeResponse>()
        repeat(12) {
            fake.enqueue { request ->
                val script = if (request.session().startsWith("analyst-")) analystScript else directorScript
                script.poll() ?: httpError(400, message = "unscripted request of ${request.session()}")
            }
        }
        val brief = "Where has Bartok mined in the last hours? Ores, dimension and coordinates: I want to mock his diamond."
        directorScript += reply { thinking("The Analyst can dig while I answer."); toolUse("delegate", """{"role": "analyst", "brief": "$brief"}""") }
        directorScript += reply { text("Delegated; I will answer when the report comes.") }
        directorScript += reply { thinking("Now I know where he mines."); text("Report received.") }
        val firstReply = Hold()
        analystScript += reply { hold(firstReply); thinking("Ores first."); toolUse("sql", """{"query": "SELECT block, count(*) AS n FROM mining WHERE player = 'Bartok' AND ore GROUP BY block ORDER BY n DESC"}""") }
        analystScript += reply { toolUse("sql", """{"query": "SELECT dim, x, y, z FROM mining WHERE player = 'Bartok' AND block = 'diamond_ore'"}""") }
        val report = "Réponse : Bartok a extrait 1 diamant, en overworld à 100 -40 200.\nFaits :\n- 1 diamond_ore et 1 deepslate_iron_ore [R1].\n" +
            "- Diamant : overworld 100 -40 200 [R2].\nLimites : aucune.\nRequêtes :\nR1 sql : …\nR2 sql : …"
        analystScript += reply { text(report) }

        fun await(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (true) {
                scheduler.runCurrent()
                if (condition()) return
                if (System.nanoTime() > deadline) fail("timed out waiting for $what: ${director.status().state}, ${fake.requests.map { it.session() }}")
                Thread.sleep(2)
            }
        }

        try {
            director.start()
            scheduler.runCurrent()
            base.players.put(live("Bartok"))
            base.bus.publish(ChatMessage(bartok, ChatChannel.PUBLIC, "narrateur, tu sais même pas où je mine", emptyList()))
            scheduler.advanceTimeBy(1_500)

            // The Director delegates and ends its turn; the Analyst's first reply is held until the test releases it.
            await("the Director's first turn") { traces.turns.any { it.outcome != null } && director.status().turnId == null }
            await("the Analyst at Claude") { firstReply.awaitReached(0) }
            val directorSession = fake.requests[0].session()
            val directorRequests = { fake.requests.filter { it.session() == directorSession } }
            assertEquals(2, directorRequests().size, "the Director went on without waiting for the Analyst")
            val delegated = directorRequests()[1].lastUser().path("content")[0]
            assertEquals("tool_result", delegated.path("type").asText())
            assertEquals("ok: task T-1 analyst started; its report will arrive as a TASK event", delegated.path("content").asText())
            assertEquals(TaskRecord.Status.RUNNING, workers.list().single().status)
            assertTrue(listOf("delegate", "tasks").all { it in fake.requests[0].toolNames() }, "the Director has the delegation tools")

            // The Analyst works in its own session: two sql steps on the real Journal, then its report.
            firstReply.release()
            await("the report") { traces.latestTask(1)?.status == TaskRecord.Status.DONE }
            val analystSession = workers.list().single().conversationId!!
            assertNotEquals(directorSession, analystSession)
            val analystRequests = fake.requests.filter { it.session() == analystSession }
            assertEquals(3, analystRequests.size)
            assertEquals(
                listOf("area_info", "inspect_player", "list_players", "locate", "player_stats", "scan_area", "sql"),
                analystRequests[0].toolNames(),
            )
            val opening = analystRequests[0].json.path("messages")[0].path("content")[0].path("text").asText()
            assertTrue(opening.startsWith("<task id=\"T-1\" from=\"director\">\n$brief\n</task>\n<context now=\"2026-09-25 21:32:0"), opening)
            assertContains(opening, "players_online=\"Bartok\"/>")
            val ores = analystRequests[1].lastUser().path("content")[0].path("content").asText()
            assertContains(ores, "block|n")
            assertContains(ores, "diamond_ore|1")
            val diamond = analystRequests[2].lastUser().path("content")[0].path("content").asText()
            assertContains(diamond, "overworld|100|-40|200")
            assertEquals(report, traces.latestTask(1)!!.result)

            // The report wakes the Director, as a grouped (SOON) signal.
            scheduler.advanceTimeBy(3_000)
            await("the Director's second turn") { traces.turns.count { it.outcome != null } == 2 && director.status().turnId == null }
            val wake = directorRequests()[2].lastUser().path("content").joinToString("\n") { it.path("text").asText() }
            assertContains(wake, "] TASK T-1 analyst done in ")
            assertContains(wake, "    Réponse : Bartok a extrait 1 diamant, en overworld à 100 -40 200.\n    Faits :")
            val turns = traces.turns.filter { it.outcome != null }
            assertEquals(listOf(TurnRecord.Trigger.CHAT, TurnRecord.Trigger.TASK), turns.map { it.trigger })
            assertEquals(TurnRecord.Outcome.ACTED, turns[0].outcome, "delegate is an acting tool")
            assertEquals(DirectorState.IDLE, director.status().state)

            // Traces: the task, its conversation, and the calls of each side.
            assertEquals(listOf(TaskRecord.Status.QUEUED, TaskRecord.Status.RUNNING, TaskRecord.Status.DONE), traces.tasks.map { it.status })
            assertEquals(turns[0].turnId, traces.latestTask(1)!!.requestedByTurn)
            val conversation = traces.latestConversation(analystSession)!!
            assertEquals(1L, conversation.taskId)
            assertEquals(report, conversation.summary)
            assertEquals(3, traces.llmCalls.count { it.purpose == LlmCallRecord.Purpose.WORKER && it.conversationId == analystSession })
            assertEquals(3, traces.llmCalls.count { it.purpose == LlmCallRecord.Purpose.TURN && it.conversationId == directorSession })
            assertEquals(listOf("delegate", "sql", "sql"), traces.toolCalls.map { it.tool })
            assertEquals(listOf(Role.DIRECTOR, Role.ANALYST, Role.ANALYST), traces.toolCalls.map { it.role })
            assertTrue(fake.rejections.isEmpty(), "rejected by the strict fake server: ${fake.rejections}")
            assertEquals(0, fake.unscripted)
        } finally {
            scope.cancel()
            scheduler.runCurrent()
            client.close()
            fake.close()
            base.bus.close()
            dataset.close()
        }
    }

    private fun RecordedRequest.session(): String = header("X-Session-ID") ?: fail("a request without X-Session-ID")

    private fun RecordedRequest.lastUser(): JsonNode = json.path("messages").last { it.path("role").asText() == "user" }

    private fun RecordedRequest.toolNames(): List<String> = json.path("tools").map { it.path("name").asText() }

    private val bartok = PlayerRef(UUID.nameUUIDFromBytes("Bartok".toByteArray()), "Bartok")

    private fun live(name: String) = LivePlayer(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name), sessionId = 1, joinedAt = START.toEpochMilli() - 3_600_000,
        isOp = false, gameMode = "survival", pos = Pos("minecraft:overworld", 100, -40, 200), biome = "minecraft:dripstone_caves",
        structure = null, underground = true, health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null,
        lastMoveAt = START.toEpochMilli(), idle = false,
    )
}
