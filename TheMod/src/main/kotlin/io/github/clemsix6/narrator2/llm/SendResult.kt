package io.github.clemsix6.narrator2.llm

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.trace.CallStats

/**
 * What [ClaudeClient.send] got back. Only a [Reply] joins the conversation; for the others, the
 * conversation is unchanged and the caller decides what to do. A server-side fallback ([fallbacks]) is
 * the API's own: the client never retries on another model.
 */
sealed class SendResult {
    abstract val message: Message
    abstract val stats: CallStats

    val stopReason: StopReason? get() = message.stopReason().orElse(null)

    /** Where a safety classifier handed the request to another model, in order; empty when none did. */
    val fallbacks: List<Fallback> get() = fallbacksOf(message)

    /** Concatenated visible text blocks (thinking excluded). */
    val text: String
        get() = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()

    /**
     * A usable reply (`end_turn`, `tool_use` with complete calls, `pause_turn` or `stop_sequence`), appended
     * as [replay].
     */
    class Reply internal constructor(override val message: Message, override val stats: CallStats, internal val replay: MessageParam) :
        SendResult() {
        /** The tool calls of the answer ([answerOf]): those of a declined model are not part of it. */
        val toolUses: List<ToolUseBlock> get() = answerOf(message).mapNotNull { it.toolUse().orElse(null) }
    }

    /**
     * The model refused (`stop_reason: refusal`), or a fallback model answered with neither text nor a
     * tool call. Not appended.
     */
    class Refused(override val message: Message, override val stats: CallStats) : SendResult() {
        /** `stop_details.category`, else the fallback's, e.g. `cyber`; null when the API gave none. */
        val category: String? get() = stats.refusalCategory
        val explanation: String? get() = message.stopDetails().flatMap { it.explanation() }.orElse(null)
    }

    /**
     * A reply cut short: `max_tokens`, a full context window, a tool call whose arguments did not arrive
     * whole, or a reply that cannot be sent back. Not appended: a truncated tool call cannot be answered.
     */
    class Incomplete(override val message: Message, override val stats: CallStats, val reason: String) : SendResult()
}
