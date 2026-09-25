package io.github.clemsix6.narrator2.agent

import com.anthropic.models.messages.ContentBlockParam

/**
 * How a turn of [AgentLoop.runTurn] ended.
 *
 * A turn commits each usable reply with the request that got it, so the history stays valid whatever
 * happens. When it stops on a request that got no usable reply ([Refused], [Truncated], [Empty],
 * [Failed]), that request's [Pending] is returned uncommitted: if it holds tool results, the history
 * ends with the tool calls they answer, so the next request on this conversation must start with
 * them (send this pending again, as is or with more blocks and notes after them).
 *
 * @property calls requests sent to Claude (one `llm_calls` row each).
 * @property toolCalls tool calls executed.
 * @property acted at least one `SPEECH`, `WORLD` or `TASK` tool call succeeded during the turn.
 */
sealed interface TurnOutcome {
    val calls: Int
    val toolCalls: Int
    val acted: Boolean

    /** The model ended its turn; [finalText] is the text of its last reply (a worker's report). */
    data class Ended(override val calls: Int, override val toolCalls: Int, override val acted: Boolean, val finalText: String) :
        TurnOutcome

    /** The model refused (`stop_reason: refusal`); [category] is `stop_details.category` when given. */
    data class Refused(val pending: Pending, val category: String?, override val calls: Int, override val toolCalls: Int, override val acted: Boolean) :
        TurnOutcome

    /** The reply was cut short (`max_tokens`, a tool call cut off); [reason] says how. */
    data class Truncated(val pending: Pending, val reason: String, override val calls: Int, override val toolCalls: Int, override val acted: Boolean) :
        TurnOutcome

    /** The model twice answered with nothing at all (no text, no tool call), the second time after an engine note. */
    data class Empty(val pending: Pending, override val calls: Int, override val toolCalls: Int, override val acted: Boolean) : TurnOutcome

    /**
     * The call failed once `ClaudeClient` had made its retries, or no client was available.
     *
     * @property requestId the gateway's id of the failed request, for its operator (`X-Cpa-Trace-Id` behind LLMGW).
     */
    data class Failed(
        val pending: Pending,
        val error: GatewayError,
        val requestId: String?,
        override val calls: Int,
        override val toolCalls: Int,
        override val acted: Boolean,
    ) : TurnOutcome

    /**
     * The model still called tools at the turn's hard step limit. Its reply was committed; [carry] holds
     * the `tool_result` blocks (not executed) that answer it: the next request must start with them.
     */
    data class StepLimit(val carry: List<ContentBlockParam>, override val calls: Int, override val toolCalls: Int, override val acted: Boolean) :
        TurnOutcome

    /**
     * The calling coroutine was cancelled (server stopping, task cancelled or timed out). The request in
     * flight committed nothing; when the cancellation came during the tools, the history ends with
     * unanswered tool calls: the conversation must not be used again.
     */
    data class Cancelled(override val calls: Int, override val toolCalls: Int, override val acted: Boolean) : TurnOutcome
}
