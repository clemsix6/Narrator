package io.github.clemsix6.narrator2.llm

import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.streamError
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Retries, timeouts, cancellation and errors of [ClaudeClient]. */
class ClaudeClientFailureTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val mapper = jsonMapper()

    @AfterEach
    fun closeFake() = fake.close()

    private fun send(conversation: Conversation, maxRetries: Int = 0, roles: Map<Role, RoleConfig> = emptyMap()): SendResult =
        fake.client(maxRetries = maxRetries, roles = roles).use { client -> runBlocking { client.send(conversation) } }

    private fun transcriptKinds(conversation: Conversation): List<String> {
        val file = Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.jsonl" }.findFirst().orElseThrow() }
        return Files.readAllLines(file).map { mapper.readTree(it).path("kind").asText() }
    }

    private fun timeouts(read: Long, total: Long) = mapOf(Role.DIRECTOR to RoleConfig(readTimeoutSeconds = read, totalTimeoutSeconds = total))

    @ParameterizedTest
    @ValueSource(ints = [429, 500, 503, 529])
    fun `a transient HTTP error is retried`(status: Int) {
        fake.enqueue(httpError(status), reply { text("Finally.") })
        val conversation = testConversation(transcripts = Transcripts(dir, enabled = true))

        val result = assertIs<SendResult.Reply>(send(conversation, maxRetries = 3))
        assertEquals("Finally.", result.text)
        assertEquals(2, result.stats.attempts)
        assertEquals(2, fake.requests.size)
        assertEquals(fake.requests[0].json, fake.requests[1].json, "the retry sends the same request")
        assertEquals(2, conversation.size, "the reply is appended once")
        assertEquals(listOf("conversation", "message", "request", "retry", "response"), transcriptKinds(conversation))
    }

    @Test
    fun `retries stop after gateway maxRetries, then the error propagates, logged with its stack trace`() {
        fake.enqueue(httpError(529), httpError(529), httpError(529), reply { text("Never read.") })
        val conversation = testConversation(transcripts = Transcripts(dir, enabled = true))
        val logs = LogCapture()

        val error = logs.use { assertFailsWith<AnthropicServiceException> { send(conversation, maxRetries = 2) } }
        assertEquals(529, error.statusCode())
        assertEquals(3, fake.requests.size)
        assertEquals(1, conversation.size, "the conversation is unchanged")
        assertEquals(listOf("conversation", "message", "request", "retry", "retry", "error"), transcriptKinds(conversation))
        assertEquals(2, logs.messages.count { "failed (transient HTTP error), retrying in" in it && "HTTP 529: overloaded_error" in it })
        val failure = logs.messages.single { "· failed ·" in it }
        assertContains(failure, "3 attempts")
        assertContains(failure, "at io.github.clemsix6.narrator2", message = "stack trace")
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 413])
    fun `a client error is never retried`(status: Int) {
        fake.enqueue(httpError(status), reply { text("Never read.") })
        val error = assertFailsWith<AnthropicServiceException> { send(testConversation(), maxRetries = 3) }
        assertEquals(status, error.statusCode())
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `an exhausted LLMGW budget fails at once with its reset time`() {
        fake.enqueue(budgetExceeded(resetAt = "2026-09-26T00:00:00Z", dimension = "cost", window = "day"), reply { text("Never read.") })
        val conversation = testConversation()

        val error = assertFailsWith<BudgetExceededException> { send(conversation, maxRetries = 3) }
        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), error.resetAt)
        assertContains(describeFailure(error), "cost per day")
        assertContains(describeFailure(error), "2026-09-26T00:00:00Z")
        assertEquals(1, fake.requests.size, "a spent budget is not retried")
        assertEquals(1, conversation.size)
    }

    @Test
    fun `retry-after-ms from the gateway replaces the backoff`() {
        fake.enqueue(httpError(529, retryAfterMs = 20), reply { text("Quick.") })
        val config = testConfig(fake.baseUrl, maxRetries = 1).let {
            it.copy(gateway = it.gateway.copy(retryInitialDelayMs = 60_000, retryMaxDelayMs = 60_000))
        }
        val started = System.nanoTime()
        val result = ClaudeClient(config, TEST_API_KEY).use { client -> runBlocking { client.send(testConversation()) } }

        assertEquals("Quick.", result.text)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "waited 20 ms, not the 60 s backoff")
    }

    @Test
    fun `retry-after in seconds is honoured`() {
        fake.enqueue(httpError(429, retryAfterMs = null, retryAfterSeconds = 1), reply { text("After a second.") })
        val started = System.nanoTime()
        val result = send(testConversation(), maxRetries = 1)

        assertEquals("After a second.", result.text)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1_000, "waited the second asked by retry-after")
    }

    @Test
    fun `a stream cut before any content is retried`() {
        fake.enqueue(reply { cut() }, reply { text("Whole answer.") })
        val result = send(testConversation(), maxRetries = 1)

        assertEquals("Whole answer.", result.text)
        assertEquals(2, result.stats.attempts)
    }

    @Test
    fun `a stream cut after content fails without a retry`() {
        fake.enqueue(reply { text("Half an ans"); cut() }, reply { text("Never read.") })
        val conversation = testConversation()

        val error = assertFailsWith<TruncatedStreamException> { send(conversation, maxRetries = 3) }
        assertContains(describeFailure(error), "after any content")
        assertEquals(1, fake.requests.size)
        assertEquals(1, conversation.size)
    }

    @Test
    fun `an error event before any content is retried`() {
        fake.enqueue(streamError("overloaded_error", "Overloaded mid-answer"), reply { text("Recovered.") })
        val result = send(testConversation(), maxRetries = 1)

        assertEquals("Recovered.", result.text)
        assertEquals(2, result.stats.attempts)
    }

    @Test
    fun `an unreachable gateway is retried, then reported as unreachable`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val conversation = testConversation()
        val logs = LogCapture()

        val error = logs.use {
            ClaudeClient(testConfig("http://127.0.0.1:$closedPort", maxRetries = 2), TEST_API_KEY).use { client ->
                assertFails { runBlocking { client.send(conversation) } }
            }
        }
        assertContains(describeFailure(error), "gateway unreachable")
        assertEquals(2, logs.messages.count { "failed (connection error" in it })
        assertContains(logs.messages.single { "· failed ·" in it }, "3 attempts")
        assertEquals(1, conversation.size)
    }

    @Test
    fun `a gateway silent for longer than the read timeout is retried`() {
        val hold = Hold()
        fake.enqueue(reply { hold(hold); text("Never read.") }, reply { text("Second try.") })
        try {
            val result = send(testConversation(), maxRetries = 1, roles = timeouts(read = 1, total = 30))
            assertEquals("Second try.", result.text)
            assertEquals(2, result.stats.attempts)
        } finally {
            hold.release()
        }
    }

    @Test
    fun `the total timeout bounds the whole call`() {
        val hold = Hold()
        fake.enqueue(reply { hold(hold); text("Never read.") }, reply { text("Never read either.") })
        val started = System.nanoTime()
        try {
            val error = assertFails { send(testConversation(), maxRetries = 3, roles = timeouts(read = 30, total = 1)) }
            assertContains(describeFailure(error), "total timeout")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
            assertEquals(1, fake.requests.size, "no retry past the deadline")
        } finally {
            hold.release()
        }
    }

    @Test
    fun `cancelling the caller closes the stream at once and leaves the conversation unchanged`() {
        val hold = Hold()
        fake.enqueue(reply { hold(hold); text("Too late.") })
        val conversation = testConversation(transcripts = Transcripts(dir, enabled = true))
        val logs = LogCapture()

        logs.use {
            fake.client().use { client ->
                runBlocking {
                    val job = launch(Dispatchers.Default) { client.send(conversation) }
                    assertTrue(withContext(Dispatchers.IO) { hold.awaitReached() }, "the reply reached its hold")
                    assertNotNull(withTimeoutOrNull(2_000) { job.cancelAndJoin() }, "send returned without waiting for the reply")
                    assertTrue(job.isCancelled)
                }
                hold.release()
                assertTrue(hold.awaitFinished())
                assertTrue(hold.clientGone, "the client had closed the connection")
            }
        }
        assertEquals(1, conversation.size, "nothing appended")
        conversation.appendUser("The conversation is free again.")
        assertContains(logs.text, "cancelled after")
        assertFalse("unreachable" in logs.text, "a cancellation is not a network failure:\n${logs.text}")
        assertContains(transcriptKinds(conversation), "note")
    }
}
