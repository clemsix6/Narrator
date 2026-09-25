package io.github.clemsix6.narrator2.testing

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.llm.Conversation

const val TEST_API_KEY = "test-key"

/** A configuration whose gateway is this fake server, with a test key and [maxRetries] SDK retries. */
fun FakeAnthropic.narratorConfig(maxRetries: Int = 0): NarratorConfig =
    NarratorConfig(gateway = GatewayConfig(baseUrl = baseUrl, apiKey = TEST_API_KEY, maxRetries = maxRetries))

/** A conversation with a one-line system prompt and a first user message. */
fun testConversation(
    role: String = NarratorConfig.DIRECTOR,
    tools: List<ToolUnion> = emptyList(),
    user: String = "Ping?",
    label: String = "Test conversation",
): Conversation {
    val system = listOf(TextBlockParam.builder().text("You are the Narrator.").build())
    return Conversation(role, label, system, tools).apply { appendUser(user) }
}

/** A `say(text)` tool, shaped like the Director's speech tools. */
val SAY_TOOL: ToolUnion = ToolUnion.ofTool(
    Tool.builder()
        .name("say")
        .description("Speak to the players in the chat.")
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty("text", JsonValue.from(mapOf("type" to "string")))
                        .build(),
                )
                .required(listOf("text"))
                .build(),
        )
        .build(),
)

/** The user turn that answers a `tool_use` block. */
fun toolResult(toolUseId: String, content: String): MessageParam =
    MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(
            listOf(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(toolUseId).content(content).build())),
        )
        .build()
