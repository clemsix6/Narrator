package io.github.clemsix6.narrator2.llm

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.trace.CallStats

/**
 * What [ClaudeClient.send] got back. Only a [Reply] joins the conversation; for the others, the
 * conversation is unchanged and the caller decides what to do (there is no automatic fallback).
 */
sealed class SendResult {
    abstract val message: Message
    abstract val stats: CallStats

    val stopReason: StopReason? get() = message.stopReason().orElse(null)

    /** Concatenated visible text blocks (thinking excluded). */
    val text: String
        get() = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()

    /** A usable reply (`end_turn`, `tool_use` with complete calls, `pause_turn` or `stop_sequence`), appended. */
    class Reply(override val message: Message, override val stats: CallStats) : SendResult() {
        val toolUses: List<ToolUseBlock> get() = message.content().mapNotNull { it.toolUse().orElse(null) }
    }

    /** The model refused (`stop_reason: refusal`). Not appended. */
    class Refused(override val message: Message, override val stats: CallStats) : SendResult() {
        /** `stop_details.category`, e.g. `cyber`; null when the API gave none. */
        val category: String? get() = stats.refusalCategory
        val explanation: String? get() = message.stopDetails().flatMap { it.explanation() }.orElse(null)
    }

    /**
     * A reply cut short: `max_tokens`, a full context window, or a tool call whose arguments did not
     * arrive whole. Not appended: a truncated tool call cannot be answered.
     */
    class Incomplete(override val message: Message, override val stats: CallStats, val reason: String) : SendResult()
}
