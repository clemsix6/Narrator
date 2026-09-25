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
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.Transcripts

const val TEST_API_KEY = "test-key-3c9e7b"

/**
 * A configuration whose gateway is [baseUrl], with [maxRetries] retries a few milliseconds apart and
 * [roles] overriding the defaults.
 */
fun testConfig(
    baseUrl: String,
    maxRetries: Int = 0,
    cacheTtl: String = "1h",
    roles: Map<Role, RoleConfig> = emptyMap(),
): NarratorConfig = NarratorConfig(
    gateway = GatewayConfig(
        baseUrl = baseUrl,
        cacheTtl = cacheTtl,
        maxRetries = maxRetries,
        retryInitialDelayMs = 1,
        retryMaxDelayMs = 5,
    ),
    roles = NarratorConfig.DEFAULT_ROLES + roles,
)

/** A client of this fake server, with the test key. */
fun FakeAnthropic.client(maxRetries: Int = 0, cacheTtl: String = "1h", roles: Map<Role, RoleConfig> = emptyMap()): ClaudeClient =
    ClaudeClient(testConfig(baseUrl, maxRetries, cacheTtl, roles), TEST_API_KEY)

/** A conversation with a one-line system prompt and a first user message. */
fun testConversation(
    role: Role = Role.DIRECTOR,
    tools: List<ToolUnion> = emptyList(),
    user: String = "Ping?",
    label: String = "Test conversation",
    transcripts: Transcripts? = null,
    id: String? = null,
): Conversation {
    val system = listOf(TextBlockParam.builder().text("You are the Narrator.").build())
    val conversation = if (id == null) {
        Conversation(role, label, system, tools, transcripts)
    } else {
        Conversation(role, label, system, tools, transcripts, id)
    }
    return conversation.apply { appendUser(user) }
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
