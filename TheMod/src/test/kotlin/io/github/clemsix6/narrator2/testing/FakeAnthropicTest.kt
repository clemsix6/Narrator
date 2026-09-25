package io.github.clemsix6.narrator2.testing

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.anthropic.core.JsonValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    private fun params(
        messages: List<MessageParam>,
        session: String = "session-1",
        gateway: String = "narrator2-director",
        configure: MessageCreateParams.Builder.() -> Unit = {},
    ) =
        MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(100)
            .system("You are the Narrator.")
            .messages(messages)
            .putAdditionalHeader("X-Session-ID", gateway)
            .putAdditionalHeader("X-Narrator-Conversation", session)
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
    fun `conversations that share a gateway session are each append-only on their own`() {
        fake.enqueue(reply { text("First.") }, reply { text("Other.") }, reply { text("Second.") }, reply { text("Other again.") })
        // Every chapter of the Director goes to the same upstream account (X-Session-ID), each its own conversation.
        send(params(listOf(user("Hi")), session = "director-1"))
        assertEquals("Other.", send(params(listOf(user("A new chapter")), session = "director-2")), "not an edit of director-1")
        assertEquals("Second.", send(params(listOf(user("Hi"), assistant("First."), user("More")), session = "director-1")))
        assertEquals("Other again.", send(params(listOf(user("A new chapter"), assistant("Other."), user("More")), session = "director-2")))
        assertContains(
            rejected(params(listOf(user("Hi"), assistant("Other."), user("More")), session = "director-1")),
            "session director-1 is not append-only: messages.1 differs",
        )
    }

    @Test
    fun `strict mode rejects a conversation that moves to another gateway session`() {
        fake.enqueue(reply { text("First.") })
        send(params(listOf(user("Hi")), session = "analyst-1", gateway = "narrator2-analyst"))
        assertContains(
            rejected(params(listOf(user("Hi"), assistant("First."), user("More")), session = "analyst-1", gateway = "analyst-1")),
            "conversation analyst-1 moved from gateway session narrator2-analyst to analyst-1",
        )
    }

    private fun assistant(text: String) = MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(text).build()

    private fun system(text: String) = MessageParam.builder().role(MessageParam.Role.SYSTEM).content(text).build()

    /** Sends [params] expecting a strict-mode 400; returns the latest rejection's message. */
    private fun rejected(params: MessageCreateParams): String {
        assertFailsWith<BadRequestException> { send(params) }
        return fake.rejections.last()
    }

    @Test
    fun `strict mode checks append-only against the last successful exchange, its reply included`() {
        fake.enqueue(reply { text("First.") }, reply { refusal() }, reply { text("Second.") })
        send(params(listOf(user("Hi"))))
        send(params(listOf(user("Hi"), assistant("First."), user("Something refused"))))

        assertEquals("Second.", send(params(listOf(user("Hi"), assistant("First."), user("Something else")))), "a refused request commits nothing")
        val next = listOf(user("Hi"), assistant("First."), user("Something else"))
        assertContains(rejected(params(next + user("More"))), "messages.3 must be the reply of the last successful exchange, unchanged")
        assertContains(rejected(params(next + assistant("Second, edited.") + user("More"))), "messages.3 must be the reply of the last successful exchange")
        assertContains(rejected(params(listOf(user("Hi"), assistant("Edited."), user("Something else")))), "messages.1 differs from the last successful exchange")
        fake.enqueue(reply { text("Third.") })
        assertEquals("Third.", send(params(next + assistant("Second.") + user("More"))))
        assertEquals(3, fake.rejections.size)
    }

    @Test
    fun `strict mode accepts a system message after a user message, last or before an assistant turn`() {
        fake.enqueue(reply { text("Fine.") }, reply { text("Still fine.") })
        assertEquals("Fine.", send(params(listOf(user("Hi"), system("Engine: be brief.")))))
        assertEquals("Still fine.", send(params(listOf(user("Hi"), system("Engine: be brief."), assistant("Fine."), user("More")))))
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `strict mode rejects a misplaced system message`() {
        assertContains(rejected(params(listOf(system("Engine: first."), user("Hi")), session = "a")), "messages.0: a system message must follow a user message")
        assertContains(
            rejected(params(listOf(user("Hi"), system("Engine: note."), user("More")), session = "b")),
            "messages.1: a system message must be the last message or be followed by an assistant message",
        )
        assertContains(
            rejected(params(listOf(user("Hi"), system("One."), system("Two.")), session = "c")),
            "messages.1: a system message must be the last message or be followed by an assistant message",
        )
    }

    @Test
    fun `strict mode rejects a tool_result after other content, and blank text`() {
        val call = ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id("toolu_1").name("say").input(JsonValue.from(mapOf("text" to "Hi"))).build())
        val assistant = MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(listOf(call)).build()
        val result = ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId("toolu_1").content("ok").build())
        val late = MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(listOf(ContentBlockParam.ofText("Here:"), result)).build()
        assertContains(rejected(params(listOf(user("Hi"), assistant, late), session = "a")), "messages.2.content.1: tool_result blocks must come first")

        assertContains(rejected(params(listOf(user(" \n")), session = "b")), "messages.0: text content blocks must contain non-whitespace text")
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
