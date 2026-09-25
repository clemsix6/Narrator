package io.github.clemsix6.narrator2.agent

import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Exchange
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.streamError
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testConversation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How the failures `ClaudeClient` gives up on are classified, from the gateway's real answers (faked). */
class GatewayErrorTest {
    private val fake = FakeAnthropic()

    @AfterEach
    fun closeFake() = fake.close()

    /** The failure of one call answered by [response], without retries, classified. */
    private fun classify(response: FakeResponse): GatewayError {
        fake.enqueue(response)
        val exchange = fake.client().use { client -> runBlocking { client.exchange(testConversation()) } }
        return GatewayError.of(assertIs<Exchange.Failed>(exchange).error)
    }

    @Test
    fun `402 budget_exceeded carries its reset time`() {
        val error = assertIs<GatewayError.Budget>(classify(budgetExceeded(resetAt = "2026-09-26T00:00:00Z")))
        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), error.resetAt)
        assertContains(error.description, "2026-09-26T00:00:00Z")
    }

    @Test
    fun `401 and 403 are a refused key`() {
        assertEquals(GatewayError.Auth, classify(httpError(401)))
        assertEquals(GatewayError.Auth, classify(httpError(403)))
    }

    @Test
    fun `a 400 about the system role is told apart from other bad requests`() {
        val system = classify(
            httpError(400, message = "messages: Unexpected role \"system\". The Messages API accepts a top-level `system` parameter"),
        )
        assertContains(assertIs<GatewayError.SystemRoleRejected>(system).message, "Unexpected role")

        val other = assertIs<GatewayError.BadRequest>(classify(httpError(400, message = "messages.0: all messages must have non-empty content")))
        assertEquals(400, other.status)
        assertContains(other.message, "non-empty content")
        assertEquals(413, assertIs<GatewayError.BadRequest>(classify(httpError(413))).status)
        assertEquals(404, assertIs<GatewayError.BadRequest>(classify(httpError(404))).status)
    }

    @Test
    fun `a prompt too long is a context exceeded`() {
        assertEquals(GatewayError.ContextExceeded, classify(httpError(400, message = "prompt is too long: 212000 tokens > 200000 maximum")))
    }

    @Test
    fun `429, 529, 5xx and broken streams are transient`() {
        assertEquals("rate_limited", assertIs<GatewayError.Transient>(classify(httpError(429))).kind)
        assertEquals("overloaded", assertIs<GatewayError.Transient>(classify(httpError(529))).kind)
        assertEquals("server_error", assertIs<GatewayError.Transient>(classify(httpError(503))).kind)
        assertEquals("stream", assertIs<GatewayError.Transient>(classify(streamError())).kind)
        assertEquals("stream", assertIs<GatewayError.Transient>(classify(reply { text("Half an ans"); cut() })).kind)
    }

    @Test
    fun `an unreachable gateway is a network failure`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val exchange = ClaudeClient(testConfig("http://127.0.0.1:$closedPort"), TEST_API_KEY).use { client ->
            runBlocking { client.exchange(testConversation()) }
        }
        assertEquals("network", assertIs<GatewayError.Transient>(GatewayError.of(assertIs<Exchange.Failed>(exchange).error)).kind)
    }

    @Test
    fun `anything unforeseen is an unexpected transient failure`() {
        val error = assertIs<GatewayError.Transient>(GatewayError.of(IllegalStateException("no content block at index 3")))
        assertEquals("unexpected", error.kind)
        assertContains(error.message, "no content block at index 3")
    }
}
