package io.github.clemsix6.narrator2.llm

import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.SseException
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.RefusalStopDetails
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.narratorConfig
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.streamError
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeClientTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val mapper = jsonMapper()

    @AfterEach
    fun closeFake() = fake.close()

    private fun send(conversation: Conversation, maxRetries: Int = 0): Reply =
        ClaudeClient(fake.narratorConfig(maxRetries), Transcripts(dir, enabled = true)).use { client ->
            runBlocking { client.send(conversation) }
        }

    private fun transcript(conversation: Conversation, extension: String): Path =
        Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.$extension" }.findFirst().orElseThrow() }

    @Test
    fun `sends model, effort, summarized adaptive thinking, automatic caching and the session header`() {
        fake.enqueue(reply { text("Pong.") })
        val conversation = testConversation(user = "Ping?")
        send(conversation)

        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.path)
        assertEquals(conversation.id, request.header("X-Session-ID"))
        assertEquals(TEST_API_KEY, request.header("X-Api-Key"))

        val body = request.json
        assertEquals("claude-opus-5-5", body.path("model").asText())
        assertEquals(32_000, body.path("max_tokens").asLong())
        assertTrue(body.path("stream").asBoolean())
        assertEquals("adaptive", body.path("thinking").path("type").asText())
        assertEquals("summarized", body.path("thinking").path("display").asText())
        assertEquals("medium", body.path("output_config").path("effort").asText())
        assertEquals("ephemeral", body.path("cache_control").path("type").asText())
        assertEquals("You are the Narrator.", body.path("system").path(0).path("text").asText())
        assertEquals("user", body.path("messages").path(0).path("role").asText())
        assertEquals("Ping?", body.path("messages").path(0).path("content").asText())
        assertTrue(body.path("tools").isMissingNode, "no tools are declared when the conversation has none")
        assertTrue(body.path("tool_choice").isMissingNode, "tool_choice is never forced")
    }

    @ParameterizedTest
    @CsvSource("director, medium, 32000", "forgeron, high, 64000", "analyste, low, 32000", "unknown, medium, 32000")
    fun `uses the model, effort and token budget of the conversation's role`(role: String, effort: String, maxTokens: Long) {
        fake.enqueue(reply { text("ok") })
        val reply = send(testConversation(role = role))

        val body = fake.requests.single().json
        assertEquals("claude-opus-5-5", body.path("model").asText())
        assertEquals(effort, body.path("output_config").path("effort").asText())
        assertEquals(maxTokens, body.path("max_tokens").asLong())
        assertEquals(effort, reply.stats.effort)
    }

    @Test
    fun `sends the conversation's tools and never forces tool_choice`() {
        fake.enqueue(reply { text("ok") })
        send(testConversation(tools = listOf(SAY_TOOL)))

        val body = fake.requests.single().json
        val tool = body.path("tools").single()
        assertEquals("say", tool.path("name").asText())
        assertEquals("string", tool.path("input_schema").path("properties").path("text").path("type").asText())
        assertEquals("text", tool.path("input_schema").path("required").path(0).asText())
        assertTrue(body.path("tool_choice").isMissingNode)
    }

    @Test
    fun `accumulates the stream, appends the reply to the conversation and measures the call`() {
        fake.enqueue(
            reply {
                thinking("The player is testing the line. Keep it short.")
                text("Pong, mortal.")
                toolUse("say", """{"text": "Hello everyone"}""")
                usage(input = 120, output = 45, cacheRead = 900, cacheWrite = 60)
            },
        )
        val conversation = testConversation(tools = listOf(SAY_TOOL))
        val reply = send(conversation)

        val content = reply.message.content()
        assertEquals(3, content.size)
        val thinking = content[0].thinking().orElseThrow()
        assertEquals("The player is testing the line. Keep it short.", thinking.thinking())
        assertTrue(thinking.signature().isNotEmpty(), "the thinking signature is kept")
        assertEquals("Pong, mortal.", reply.text)
        val call = content[2].toolUse().orElseThrow()
        assertEquals("say", call.name())
        assertEquals("Hello everyone", mapper.valueToTree<JsonNode>(call._input()).path("text").asText())
        assertEquals(StopReason.TOOL_USE, reply.stopReason)

        with(reply.stats) {
            assertEquals("claude-opus-5-5", model)
            assertEquals("medium", effort)
            assertEquals(120, inputTokens)
            assertEquals(900, cacheReadTokens)
            assertEquals(60, cacheWriteTokens)
            assertEquals(45, outputTokens)
            assertEquals(1_080, contextTokens)
            assertEquals("tool_use", stopReason)
            assertTrue(latencyMs >= 0)
            assertContains(summary(), "input 120 (+900 cache read, 60 cache write) · output 45 · tool_use")
        }
        assertEquals(2, conversation.size)
        assertEquals(MessageParam.Role.ASSISTANT, conversation.messages[1].role())
        assertEquals(1_080, conversation.contextTokens)
    }

    @Test
    fun `a tool round trip resends the history unchanged, thinking included`() {
        lateinit var toolUseId: String
        fake.enqueue(reply { thinking("Time to greet them."); toolUseId = toolUse("say", """{"text": "Hello"}""") })
        fake.enqueue(reply { text("Done.") })
        val conversation = testConversation(tools = listOf(SAY_TOOL))

        ClaudeClient(fake.narratorConfig(), Transcripts(dir, enabled = true)).use { client ->
            runBlocking {
                client.send(conversation)
                conversation.append(toolResult(toolUseId, "message sent"))
                assertEquals("Done.", client.send(conversation).text)
            }
        }

        val (first, second) = fake.requests
        assertEquals(first.json.path("system"), second.json.path("system"))
        assertEquals(first.json.path("tools"), second.json.path("tools"))
        assertEquals(first.header("X-Session-ID"), second.header("X-Session-ID"))

        val messages = second.json.path("messages")
        assertEquals(3, messages.size())
        assertEquals(first.json.path("messages").path(0), messages.path(0))
        val assistant = messages.path(1)
        assertEquals("assistant", assistant.path("role").asText())
        assertEquals(listOf("thinking", "tool_use"), assistant.path("content").map { it.path("type").asText() })
        assertEquals("Time to greet them.", assistant.path("content").path(0).path("thinking").asText())
        assertTrue(assistant.path("content").path(0).path("signature").asText().isNotEmpty())
        assertEquals(toolUseId, assistant.path("content").path(1).path("id").asText())
        assertEquals("Hello", assistant.path("content").path(1).path("input").path("text").asText())
        val result = messages.path(2).path("content").path(0)
        assertEquals("tool_result", result.path("type").asText())
        assertEquals(toolUseId, result.path("tool_use_id").asText())
        assertEquals(4, conversation.size)
    }

    @Test
    fun `a refusal is reported with its details`() {
        fake.enqueue(reply { refusal(category = "cyber", explanation = "Not happening.") })
        val reply = send(testConversation())

        assertEquals(StopReason.REFUSAL, reply.stopReason)
        assertEquals("refusal", reply.stats.stopReason)
        val details = reply.message.stopDetails().orElseThrow()
        assertEquals(RefusalStopDetails.Category.CYBER, details.category().orElseThrow())
        assertEquals("Not happening.", details.explanation().orElseThrow())
        assertEquals("", reply.text)
    }

    @ParameterizedTest
    @ValueSource(ints = [429, 500, 529])
    fun `an HTTP error propagates, leaves the conversation unchanged and is transcribed`(status: Int) {
        fake.enqueue(httpError(status))
        val conversation = testConversation()

        val error = assertFailsWith<AnthropicServiceException> { send(conversation) }
        assertEquals(status, error.statusCode())
        assertEquals(1, fake.requests.size, "no retry with maxRetries = 0")
        assertEquals(1, conversation.size, "the conversation is unchanged")

        val lines = Files.readAllLines(transcript(conversation, "jsonl")).map { mapper.readTree(it) }
        assertEquals(listOf("conversation", "message", "error"), lines.map { it.path("kind").asText() })
        assertEquals(describeFailure(error), lines.last().path("data").path("description").asText())
        assertContains(transcript(conversation, "md").readText(), "ERROR after")
    }

    @Test
    fun `describes failures for operators`() {
        fake.enqueue(httpError(401), httpError(529, message = "Overloaded, try later"))
        val unauthorized = assertFailsWith<AnthropicServiceException> { send(testConversation()) }
        assertEquals("key rejected by the gateway (HTTP 401)", describeFailure(unauthorized))
        val overloaded = assertFailsWith<AnthropicServiceException> { send(testConversation()) }
        assertContains(describeFailure(overloaded), "HTTP 529")
        assertContains(describeFailure(overloaded), "Overloaded, try later")
    }

    @Test
    fun `an overloaded gateway is retried`() {
        fake.enqueue(httpError(529), reply { text("Finally.") })
        val reply = send(testConversation(), maxRetries = 1)

        assertEquals("Finally.", reply.text)
        assertEquals(2, fake.requests.size)
        assertEquals("1", fake.requests[1].header("X-Stainless-Retry-Count"))
    }

    @Test
    fun `an error event in the stream fails the call`() {
        fake.enqueue(streamError("overloaded_error", "Overloaded mid-answer"))
        val conversation = testConversation()

        val error = assertFailsWith<SseException> { send(conversation) }
        assertContains(describeFailure(error), "error in the response stream")
        assertEquals(1, conversation.size)
    }

    @Test
    fun `a request with nothing scripted fails loudly`() {
        assertFailsWith<AnthropicServiceException> { send(testConversation()) }
        assertEquals(1, fake.unscripted)
    }
}
