package io.github.clemsix6.narrator2.testing

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolUseBlockParam
import com.anthropic.core.JsonValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The fake's own behaviour: strict mode, per-session scripts, usage. Driven with the bare SDK. */
class FakeAnthropicTest {
    private val fake = FakeAnthropic()
    private val sdk: AnthropicClient = AnthropicOkHttpClient.builder().baseUrl(fake.baseUrl).apiKey(TEST_API_KEY).maxRetries(0).build()

    @AfterEach
    fun close() {
        sdk.close()
        fake.close()
    }

    private fun user(text: String) = MessageParam.builder().role(MessageParam.Role.USER).content(text).build()

    private fun params(messages: List<MessageParam>, session: String = "session-1", configure: MessageCreateParams.Builder.() -> Unit = {}) =
        MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(100)
            .system("You are the Narrator.")
            .messages(messages)
            .putAdditionalHeader("X-Session-ID", session)
            .apply(configure)
            .build()

    /** Sends [params] and returns the answer's text. */
    private fun send(params: MessageCreateParams): String =
        sdk.messages().createStreaming(params).use { stream ->
            stream.stream().toList().mapNotNull { event -> event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null) }
        }.joinToString("")

    /** Sends [params], expecting a strict-mode 400, and returns the rejection's message. */
    private fun rejection(params: MessageCreateParams): String {
        val error = assertFailsWith<BadRequestException> { send(params) }
        val message = fake.rejections.single()
        assertContains(error.message.orEmpty(), message.take(12), message = "the client got the rejection's message")
        return message
    }

    @Test
    fun `serves the session's own script before the global one`() {
        fake.enqueue(reply { text("global") })
        fake.enqueueFor("session-2", reply { text("for session 2") })

        assertEquals("for session 2", send(params(listOf(user("Hi")), session = "session-2")))
        assertEquals("global", send(params(listOf(user("Hi")), session = "session-1")))
        assertEquals(0, fake.pending)
    }

    @Test
    fun `strict mode rejects an empty message`() {
        assertContains(rejection(params(listOf(user("")))), "messages.0: all messages must have non-empty content")
        assertEquals(0, fake.unscripted, "the script is not consumed by a rejected request")
    }

    @Test
    fun `strict mode rejects a tool_use without its tool_result`() {
        val call = ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id("toolu_1").name("say").input(JsonValue.from(mapOf("text" to "Hi"))).build())
        val assistant = MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(listOf(call)).build()
        assertContains(
            rejection(params(listOf(user("Hi"), assistant, user("And then?")))),
            "`tool_use` ids were found without `tool_result` blocks immediately after: toolu_1",
        )
    }

    @Test
    fun `strict mode rejects a forced tool_choice`() {
        val forced = params(listOf(user("Hi"))) { toolChoice(ToolChoiceAny.builder().build()) }
        assertContains(rejection(forced), "tool_choice: type \"tool\" and \"any\" are not supported")
    }

    @Test
    fun `strict mode rejects a cache marker that is not 1h, like LLMGW`() {
        val fiveMinutes = params(listOf(user("Hi"))) { cacheControl(CacheControlEphemeral.builder().build()) }
        assertContains(rejection(fiveMinutes), "ttl='5m' but the target block already has cache_control with ttl='1h'")
    }

    @Test
    fun `strict mode rejects a cache marker from the second user turn on, like LLMGW`() {
        val assistant = MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("First.").build()
        val oneHour = params(listOf(user("Hi"), assistant, user("More"))) {
            cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
        }
        assertContains(rejection(oneHour), "A maximum of 4 blocks with cache_control may be provided. Found 5.")
    }

    @Test
    fun `strict mode rejects a history that is not append-only within a session`() {
        fake.enqueue(reply { text("First.") }, reply { text("Second.") })
        send(params(listOf(user("Hi"))))
        send(params(listOf(user("Hi"), MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("First.").build(), user("More"))))

        assertContains(rejection(params(listOf(user("Hello"), user("More")))), "session session-1 is not append-only: messages.0 differs")
        assertEquals("Another session starts afresh.", run {
            fake.enqueue(reply { text("Another session starts afresh.") })
            send(params(listOf(user("Hello")), session = "session-3"))
        })
    }

    @Test
    fun `lenient mode lets everything through`() {
        FakeAnthropic(strict = false).use { lenient ->
            lenient.enqueue(reply { text("Accepted.") })
            val client = AnthropicOkHttpClient.builder().baseUrl(lenient.baseUrl).apiKey(TEST_API_KEY).maxRetries(0).build()
            try {
                val text = client.messages().createStreaming(params(listOf(user("")))).use { stream ->
                    stream.stream().toList().mapNotNull { event ->
                        event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                    }
                }.joinToString("")
                assertEquals("Accepted.", text)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `usage is complete in message_start and message_delta`() {
        fake.enqueue(reply { text("Counted."); usage(input = 11, output = 22, cacheRead = 33, cacheWrite = 44) })
        val events = sdk.messages().createStreaming(params(listOf(user("Hi")))).use { it.stream().toList() }

        val start = events.first { it.isMessageStart() }.asMessageStart().message().usage()
        assertEquals(11, start.inputTokens())
        assertEquals(33, start.cacheReadInputTokens().orElseThrow())
        assertEquals(44, start.cacheCreationInputTokens().orElseThrow())
        val delta = events.first { it.isMessageDelta() }.asMessageDelta().usage()
        assertEquals(22, delta.outputTokens())
        assertEquals(11, delta.inputTokens().orElseThrow())
        assertEquals(33, delta.cacheReadInputTokens().orElseThrow())
        assertEquals(44, delta.cacheCreationInputTokens().orElseThrow())
    }
}
