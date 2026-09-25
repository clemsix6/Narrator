package io.github.clemsix6.narrator2.llm

import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolUnion
import java.util.UUID

/**
 * An append-only conversation with Claude.
 *
 * Earlier turns are never edited, and the system prompt and tool list are frozen for the
 * conversation's lifetime: Opus 5.5 binds its thinking to the exact conversation prefix, and the
 * prompt cache depends on the same prefix. Anything that changes over time is appended instead.
 *
 * @property role configuration role (model and effort), e.g. `director`, `forgeron`.
 * @property label human-readable name used in transcripts and logs.
 */
class Conversation(
    val role: String,
    val label: String,
    val system: List<TextBlockParam>,
    val tools: List<ToolUnion> = emptyList(),
    val id: String = "${role}-${UUID.randomUUID().toString().substring(0, 8)}",
) {
    private val history = mutableListOf<MessageParam>()

    val messages: List<MessageParam>
        get() = synchronized(history) { history.toList() }

    val size: Int
        get() = synchronized(history) { history.size }

    /** Context size (input tokens, cached or not) of the latest request. */
    @Volatile
    var contextTokens: Long = 0
        internal set

    fun append(message: MessageParam) {
        synchronized(history) { history += message }
    }

    fun appendUser(text: String) = append(MessageParam.builder().role(MessageParam.Role.USER).content(text).build())
}
