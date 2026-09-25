package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.agent.Workers
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.journal.SqlDataset
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real task of the Analyst against the owner's LLMGW server (M1_M2 §13), run by `gradlew liveTest`
 * only: the real `Workers`, the real `analyst.md` prompt and the Analyst's real tools, on a small test
 * Journal (`SqlDataset`: Bartok's last hour, in memory). No player is online, so the Oracle is not
 * needed. Prints the report, the queries and the measurements; the transcript goes to
 * `build/live-transcripts/`. The key comes from `NARRATOR_LLMGW_KEY` or `.secrets/llmgw.key`, and is
 * never printed; without a key or a gateway, the test is skipped.
 */
@Tag("live")
class LiveAnalystTest {
    @Test
    fun `the Analyst answers a question on the Journal with a checked report`() {
        val key = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
            ?: System.getProperty("narrator2.live.keyFile")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }
                ?.let { Files.readString(it).trim().ifEmpty { null } }
        assumeTrue(key != null) { "live tests skipped: no LLMGW key" }
        assumeTrue(gatewayAnswers()) { "live tests skipped: LLMGW does not answer on $BASE_URL (open the SSH tunnel)" }

        // The test Journal reads its timestamps in UTC: the task's context must say the same time.
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = BASE_URL, maxRetries = 1), journal = JournalConfig(timeZone = "UTC"))
        val client = ClaudeClient(config, key!!)
        val dataset = SqlDataset.journal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = Clock.systemDefaultZone()
        val traces = MemoryTraceSink()
        val director = FakeDirector(clock)
        val services = TestServices(
            clock = clock,
            scope = scope,
            settings = testSettings(config, client),
            journal = dataset.journal,
            trace = traces,
            transcripts = Transcripts(Path.of("build", "live-transcripts").toAbsolutePath(), enabled = true),
            director = director,
        )
        val workers = Workers(services)
        try {
            val brief = "Qu'a fait Bartok pendant la dernière heure ? Où a-t-il miné (minerais, dimension, coordonnées), " +
                "qu'a-t-il tué, et comment est-il mort ? Je veux des faits précis pour le taquiner dans le chat."
            val started = System.nanoTime()
            val id = workers.submit(Role.ANALYST, brief, Caller(Role.DIRECTOR, "director-live", turnId = 1, taskId = null)).getOrThrow()
            val deadline = System.nanoTime() + Duration.ofSeconds(240).toNanos()
            while (director.signals.none { it is DirectorSignal.TaskDone } && System.nanoTime() < deadline) Thread.sleep(200)
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0

            val done = director.signals.filterIsInstance<DirectorSignal.TaskDone>().single()
            val calls = traces.llmCalls
            println("[live] analyst T-$id ${done.status.id} in ${"%.1f".format(seconds)} s · ${calls.size} requests · " +
                "input ${calls.sumOf { it.inputTokens ?: 0 }} · cache read ${calls.sumOf { it.cacheReadTokens ?: 0 }} · " +
                "cache write ${calls.sumOf { it.cacheWriteTokens ?: 0 }} · output ${calls.sumOf { it.outputTokens ?: 0 }} tokens")
            traces.toolCalls.forEach { println("[live] tool ${it.tool} ${it.status.id} ${it.durationMs} ms · ${it.args}") }
            println("[live] report:\n${done.result}")
            assertEquals(TaskRecord.Status.DONE, done.status, done.result)
            assertTrue(done.result.startsWith("Réponse"), "the report follows the prompt's form")
            assertTrue(traces.toolCalls.any { it.tool == "sql" }, "the Analyst read the Journal")
        } finally {
            scope.cancel()
            client.close()
            dataset.close()
        }
    }

    private fun gatewayAnswers(): Boolean = runCatching {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            .send(HttpRequest.newBuilder(URI("$BASE_URL/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode() == 200
    }.getOrDefault(false)

    private companion object {
        const val BASE_URL = "http://localhost:8088"
    }
}
