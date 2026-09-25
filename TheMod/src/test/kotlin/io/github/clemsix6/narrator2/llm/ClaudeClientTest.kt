package io.github.clemsix6.narrator2.llm

import com.anthropic.core.jsonMapper
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeClientTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val mapper = jsonMapper()

    @AfterEach
    fun closeFake() = fake.close()

    private fun send(conversation: Conversation, roles: Map<Role, RoleConfig> = emptyMap(), cacheTtl: String = "1h"): SendResult =
        fake.client(roles = roles, cacheTtl = cacheTtl).use { client -> runBlocking { client.send(conversation) } }

    private fun transcriptLines(conversation: Conversation): List<JsonNode> {
        val file = Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.jsonl" }.findFirst().orElseThrow() }
        return Files.readAllLines(file).map { mapper.readTree(it) }
    }

    @Test
    fun `sends model, effort, summarized adaptive thinking, a 1h cache marker, the session header and the key`() {
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
        assertEquals("1h", body.path("cache_control").path("ttl").asText())
        assertEquals("You are the Narrator.", body.path("system").path(0).path("text").asText())
        assertEquals("user", body.path("messages").path(0).path("role").asText())
        assertEquals("Ping?", body.path("messages").path(0).path("content").asText())
        assertTrue(body.path("tools").isMissingNode, "no tools are declared when the conversation has none")
        assertTrue(body.path("tool_choice").isMissingNode, "tool_choice is never forced")
    }

    @Test
    fun `the cache TTL comes from the configuration, and the gateway rejects 5m`() {
        val conversation = testConversation()
        val error = assertFailsWith<BadRequestException> { send(conversation, cacheTtl = "5m") }

        assertEquals("5m", fake.requests.single().json.path("cache_control").path("ttl").asText())
        assertContains(error.message.orEmpty(), "ttl='5m'")
        assertEquals(1, conversation.size)
    }

    @ParameterizedTest
    @EnumSource(Role::class)
    fun `uses the model, effort and token budget of the conversation's role`(role: Role) {
        fake.enqueue(reply { text("ok") })
        val result = send(testConversation(role = role))

        val expected = NarratorConfig.DEFAULT_ROLES.getValue(role)
        val body = fake.requests.single().json
        assertEquals(expected.model, body.path("model").asText())
        assertEquals(expected.effort, body.path("output_config").path("effort").asText())
        assertEquals(expected.maxTokens, body.path("max_tokens").asLong())
        assertEquals(expected.effort, result.stats.effort)
        assertEquals(role, result.stats.role)
        assertTrue(fake.requests.single().header("X-Session-ID")!!.startsWith("${role.id}-"))
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
        val result = assertIs<SendResult.Reply>(send(conversation))

        val content = result.message.content()
        assertEquals(3, content.size)
        val thinking = content[0].thinking().orElseThrow()
        assertEquals("The player is testing the line. Keep it short.", thinking.thinking())
        assertTrue(thinking.signature().isNotEmpty(), "the thinking signature is kept")
        assertEquals("Pong, mortal.", result.text)
        val call = result.toolUses.single()
        assertEquals("say", call.name())
        assertEquals("Hello everyone", mapper.valueToTree<JsonNode>(call._input()).path("text").asText())
        assertEquals(StopReason.TOOL_USE, result.stopReason)

        with(result.stats) {
            assertEquals(conversation.id, conversationId)
            assertEquals(Role.DIRECTOR, role)
            assertEquals("claude-opus-5-5", model)
            assertEquals("medium", effort)
            assertEquals(1, attempts)
            assertTrue(requestId!!.startsWith("req_fake_"), "request id from the response header: $requestId")
            assertTrue(messageId!!.startsWith("msg_fake_"), "message id: $messageId")
            assertNotNull(timeToFirstTokenMs)
            assertTrue(durationMs >= timeToFirstTokenMs)
            assertEquals(120, inputTokens)
            assertEquals(900, cacheReadTokens)
            assertEquals(60, cacheWriteTokens)
            assertEquals(45, outputTokens)
            assertEquals(1_080, contextTokens)
            assertEquals("tool_use", stopReason)
            assertEquals(null, refusalCategory)
            assertEquals(null, error)
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

        fake.client().use { client ->
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
        assertTrue(fake.rejections.isEmpty(), "strict mode accepted both requests: ${fake.rejections}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["end_turn", "pause_turn", "stop_sequence"])
    fun `a usable reply is appended`(stopReason: String) {
        fake.enqueue(reply { text("Fine."); stopReason(stopReason) })
        val conversation = testConversation()

        val result = assertIs<SendResult.Reply>(send(conversation))
        assertEquals(stopReason, result.stats.stopReason)
        assertEquals(2, conversation.size)
    }

    @Test
    fun `a refusal is not appended, and is reported and transcribed with its category`() {
        fake.enqueue(reply { thinking("No."); refusal(category = "cyber", explanation = "Not happening.") })
        val conversation = testConversation(transcripts = Transcripts(dir, enabled = true))

        val result = assertIs<SendResult.Refused>(send(conversation))
        assertEquals(StopReason.REFUSAL, result.stopReason)
        assertEquals("cyber", result.category)
        assertEquals("Not happening.", result.explanation)
        assertEquals("refusal", result.stats.stopReason)
        assertEquals("cyber", result.stats.refusalCategory)
        assertEquals(1, conversation.size, "a refusal is not appended")

        val response = transcriptLines(conversation).last { it.path("kind").asText() == "response" }.path("data")
        assertEquals(false, response.path("narrator2_appended").asBoolean())
        assertEquals("cyber", response.path("narrator2_stats").path("refusal_category").asText())
        assertEquals("cyber", response.path("stop_details").path("category").asText())
    }

    @Test
    fun `a tool call cut off by max_tokens is not appended`() {
        fake.enqueue(
            reply {
                thinking("Let me answer.")
                truncatedToolUse("say", """{"text": "Hello, every""")
            },
        )
        val conversation = testConversation(tools = listOf(SAY_TOOL), transcripts = Transcripts(dir, enabled = true))

        val result = assertIs<SendResult.Incomplete>(send(conversation))
        assertEquals(StopReason.MAX_TOKENS, result.stopReason)
        assertContains(result.reason, "tool call was cut off")
        assertEquals(1, conversation.size, "the truncated call is not appended")
        val response = transcriptLines(conversation).last().path("data")
        assertEquals(false, response.path("narrator2_appended").asBoolean())
    }

    @Test
    fun `a reply cut off by max_tokens is not appended`() {
        fake.enqueue(reply { text("Once upon a"); stopReason("max_tokens") })
        val conversation = testConversation()

        val result = assertIs<SendResult.Incomplete>(send(conversation))
        assertContains(result.reason, "max_tokens")
        assertEquals("Once upon a", result.text)
        assertEquals(1, conversation.size)
    }

    @Test
    fun `logs the start and the end of each call`() {
        fake.enqueue(reply { thinking("Hm."); text("Pong."); usage(input = 7, output = 3, cacheRead = 50) })
        val conversation = testConversation()
        val logs = LogCapture()
        val result = logs.use { send(conversation) }

        val start = logs.messages.single { "· call ·" in it }
        listOf(conversation.id, "role director", "claude-opus-5-5", "effort medium", "1 messages").forEach { assertContains(start, it) }
        val end = logs.messages.single { "first token" in it }
        listOf(
            conversation.id, "request ${result.stats.requestId}", "message ${result.stats.messageId}", "1 attempt",
            "input 7 (+50 cache read, 0 cache write) · output 3 · end_turn",
        ).forEach { assertContains(end, it) }
    }
}
