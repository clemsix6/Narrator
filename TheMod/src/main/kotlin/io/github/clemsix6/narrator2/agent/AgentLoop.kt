package io.github.clemsix6.narrator2.agent

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.ClientClosedException
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Exchange
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.llm.isEmptyReply
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolExecutor
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The limits of a turn, counted in requests to Claude. The tool calls of the replies to the first
 * `maxSteps - 1` requests run. From the reply to request [maxSteps] on, tool calls are answered
 * [AgentLoop.STEP_LIMIT_RESULT] (not executed) with an engine note asking the model to end its turn;
 * when the reply to request [hardSteps] still calls tools, the turn ends with [TurnOutcome.StepLimit].
 * With `director.maxStepsPerTurn = 12`: request 13 carries the first refused results, and a reply that
 * still calls tools at request 14 stops the turn.
 *
 * With [countdown], each request that answers tool calls run below the limit carries an engine note with the count
 * ([AgentLoop.countdownNote]): the messages used and left, a message counting once whatever its tool calls. A model told
 * « six messages at most » cannot count requests it never sees, and counted its tool calls instead (M6-FIX-14).
 */
data class TurnLimits(val maxSteps: Int, val hardSteps: Int = maxSteps + 2, val countdown: Boolean = false) {
    init {
        require(maxSteps >= 1 && hardSteps >= maxSteps) { "need 1 <= maxSteps <= hardSteps, got $maxSteps and $hardSteps" }
    }
}

/** What the caller plugs into a turn. Called on the turn's coroutine. */
interface TurnHooks {
    /**
     * Before each follow-up request: blocks placed after the tool results (e.g. the events that
     * arrived meanwhile, `<new_events …>`), and notes. The reply to the previous request was usable and
     * is committed.
     *
     * @param canAct whether the reply to that request may still run tools. False from the step limit
     *   on ([TurnLimits]): the model can no longer act in this turn, so what it is told then would be
     *   seen without any possible answer; the caller keeps it for its next turn instead.
     */
    suspend fun afterTools(canAct: Boolean): Pending = Pending.EMPTY

    /** After each reply, usable or not, with its measurements and its `llm_calls` row. */
    fun onReply(reply: Message, stats: CallStats, llmCallId: Long) {}

    companion object {
        val NONE: TurnHooks = object : TurnHooks {}
    }
}

/**
 * The conversation engine of the Director and the workers. [runTurn] sends a request, runs the tool
 * calls of the reply through [executor], sends their results, and so on until the model ends its
 * turn, a step limit is reached or a request gets no usable reply.
 *
 * The history stays valid whatever happens: each request is a [Pending] committed with its reply, and
 * only when the reply is usable (see `llm/Conversation`); the history never holds an empty assistant
 * turn, and tool results always come first in their message. Each reply, usable or not, and each
 * failed call is one `llm_calls` row, with the thinking summary. `ClaudeClient` has already retried
 * transient failures: a turn never retries a call itself, except to send the same pending again when
 * the client was replaced by `/narrator reload`, when the gateway rejected a system message (once, as
 * tags), or after an empty reply (once, with a note).
 *
 * The operator channel is `director.operatorChannel`, read at the start of each turn. When the
 * gateway rejects a system message (HTTP 400 about the `system` role), this instance switches to
 * [OperatorChannelMode.USER_TAG] for the rest of its life, with an `operator` decision.
 *
 * One instance can run turns of several conversations at once, one turn per conversation at a time.
 */
class AgentLoop(private val services: NarratorServices, private val executor: ToolExecutor) {
    @Volatile
    private var systemMessagesRejected = false

    /** The channel this instance uses when [configured] is set: [OperatorChannelMode.USER_TAG] once the gateway rejected system messages. */
    fun channelMode(configured: OperatorChannelMode): OperatorChannelMode =
        if (systemMessagesRejected) OperatorChannelMode.USER_TAG else configured

    /**
     * Runs one turn of [conversation], starting with [opening].
     *
     * @param conversation built with its `ToolSet`; one turn at a time.
     * @param opening the first request; not empty.
     * @param caller who calls the tools; its conversation id must be [conversation]'s.
     * @param purpose of the `llm_calls` rows: [LlmCallRecord.Purpose.WORKER] for a task, else a Director turn.
     * @return how the turn ended; a cancellation of the calling coroutine returns [TurnOutcome.Cancelled]
     *   (the coroutine stays cancelled: its next suspension throws).
     */
    suspend fun runTurn(
        conversation: Conversation,
        opening: Pending,
        caller: Caller,
        limits: TurnLimits,
        hooks: TurnHooks = TurnHooks.NONE,
        purpose: LlmCallRecord.Purpose = if (caller.taskId != null) LlmCallRecord.Purpose.WORKER else LlmCallRecord.Purpose.TURN,
    ): TurnOutcome {
        val tools = requireNotNull(conversation.toolSet) { "${conversation.id} only declares its tools: AgentLoop needs its ToolSet" }
        require(caller.conversationId == conversation.id) { "caller of ${caller.conversationId} for conversation ${conversation.id}" }
        require(!opening.isEmpty) { "${conversation.id}: the opening request is empty" }
        val configured = services.settings.get().config.director.channelMode
        return Turn(conversation, tools, caller, limits, hooks, purpose, configured).run(opening)
    }

    /** One run of [runTurn]: its counters and its steps. */
    private inner class Turn(
        val conversation: Conversation,
        val tools: ToolSet,
        val caller: Caller,
        val limits: TurnLimits,
        val hooks: TurnHooks,
        val purpose: LlmCallRecord.Purpose,
        val configured: OperatorChannelMode,
    ) {
        private val log = if (caller.role == Role.DIRECTOR) Log.director else Log.worker
        private var calls = 0
        private var toolCalls = 0
        private var acted = false

        suspend fun run(opening: Pending): TurnOutcome {
            var pending = opening
            var nudged = false
            try {
                while (true) {
                    val sent = when (val step = send(pending)) {
                        is Sent.Failed -> return TurnOutcome.Failed(pending, step.error, step.requestId, calls, toolCalls, acted)
                        is Sent.Answered -> step
                    }
                    when (val result = sent.result) {
                        is SendResult.Refused -> return TurnOutcome.Refused(pending, result.category, calls, toolCalls, acted)
                        is SendResult.Incomplete -> when {
                            isEmptyReply(result.message) && !nudged -> {
                                nudged = true
                                warn("empty reply, sending the same request again with a note")
                                pending += OperatorNote.engine(EMPTY_REPLY_NOTE)
                            }
                            isEmptyReply(result.message) -> return TurnOutcome.Empty(pending, calls, toolCalls, acted)
                            result.stopReason == StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED ->
                                return TurnOutcome.Failed(pending, GatewayError.ContextExceeded, result.stats.requestId, calls, toolCalls, acted)
                            else -> return TurnOutcome.Truncated(pending, result.reason, calls, toolCalls, acted)
                        }
                        is SendResult.Reply -> {
                            nudged = false
                            val uses = result.toolUses
                            if (uses.isEmpty()) return TurnOutcome.Ended(calls, toolCalls, acted, result.text)
                            if (calls >= limits.hardSteps) {
                                warn("hard step limit: turn stopped at request $calls with ${uses.size} tool call(s) not executed")
                                return TurnOutcome.StepLimit(notExecuted(uses), calls, toolCalls, acted)
                            }
                            val results: List<ContentBlockParam>
                            val notes: List<OperatorNote>
                            if (calls >= limits.maxSteps) {
                                warn("step limit: ${uses.size} tool call(s) not executed at request $calls of ${limits.maxSteps}")
                                results = notExecuted(uses)
                                notes = listOf(OperatorNote.engine(STEP_LIMIT_NOTE))
                            } else {
                                results = runTools(uses, sent.llmCallId)
                                notes = if (limits.countdown) listOf(OperatorNote.engine(countdownNote(calls, limits.maxSteps))) else emptyList()
                            }
                            // The reply to the next request runs its tool calls only below the step limit.
                            val extra = hooks.afterTools(canAct = calls + 1 < limits.maxSteps)
                            pending = Pending(results + extra.blocks, extra.notes + notes)
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) throw e
                return TurnOutcome.Cancelled(calls, toolCalls, acted)
            }
        }

        /**
         * Sends [pending] with the client in force; each request that reached the gateway is counted and
         * gets its `llm_calls` row.
         */
        private suspend fun send(pending: Pending): Sent {
            var replacedClients = 0
            var resentAsTags = false
            while (true) {
                val claude = services.settings.get().claude
                    ?: return Sent.Failed(GatewayError.Unavailable("no LLMGW client: the key is missing"), null)
                val messages = OperatorChannel.render(pending, channelMode(configured))
                val exchange = try {
                    claude.exchange(conversation, messages)
                } catch (e: ClientClosedException) {
                    // Replaced by /narrator reload between reading the settings and sending: nothing was sent.
                    if (++replacedClients > MAX_REPLACED_CLIENTS) {
                        return Sent.Failed(GatewayError.Unavailable("the LLMGW client is closed and was not replaced"), null)
                    }
                    log.info("${conversation.id} · client replaced by a reload, sending with the new one")
                    continue
                }
                calls++
                when (exchange) {
                    is Exchange.Answered -> {
                        val result = exchange.result
                        val id = record(result.stats, result.message, HTTP_OK)
                        hooks.onReply(result.message, result.stats, id)
                        return Sent.Answered(result, id)
                    }
                    is Exchange.Failed -> {
                        record(exchange.stats, null, httpStatusOf(exchange.error))
                        val error = GatewayError.of(exchange.error)
                        if (error is GatewayError.SystemRoleRejected) {
                            fallBackToTags(error)
                            if (!resentAsTags && messages.any { it.role() == MessageParam.Role.SYSTEM }) {
                                resentAsTags = true
                                continue
                            }
                        }
                        return Sent.Failed(error, exchange.stats.requestId)
                    }
                }
            }
        }

        /** Runs [uses] and returns one `tool_result` per call, in order, whatever the executor does. */
        private suspend fun runTools(uses: List<ToolUseBlock>, llmCallId: Long): List<ContentBlockParam> {
            val results = try {
                executor.runAll(uses, tools, caller, llmCallId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("${conversation.id} · the tool executor failed on ${uses.joinToString { it.name() }}", e)
                emptyList()
            }
            val ordered = if (results.map { it.toolUseId() } == uses.map { it.id() }) {
                results
            } else {
                if (results.isNotEmpty()) log.error("${conversation.id} · the tool executor returned results out of order or missing; fixed")
                uses.map { use -> results.firstOrNull { it.toolUseId() == use.id() } ?: errorResult(use.id(), EXECUTOR_FAILED_RESULT) }
            }
            toolCalls += uses.size
            acted = acted || uses.zip(ordered).any { (use, result) ->
                val effect = tools[use.name()]?.effect
                effect != null && effect in ACTING && !result.isError().orElse(false)
            }
            return ordered.map(ContentBlockParam::ofToolResult)
        }

        private fun notExecuted(uses: List<ToolUseBlock>): List<ContentBlockParam> =
            uses.map { ContentBlockParam.ofToolResult(errorResult(it.id(), STEP_LIMIT_RESULT)) }

        /** Writes the `llm_calls` row of a call and returns its id. A trace that fails never breaks the turn. */
        private fun record(stats: CallStats, reply: Message?, httpStatus: Int?): Long {
            val id = services.trace.nextId(IdKind.LLM_CALL)
            try {
                services.trace.llmCall(LlmCallRecord.anthropic(id, stats, purpose, caller.turnId, reply?.let(::thinkingOf), httpStatus))
            } catch (e: Exception) {
                log.error("${conversation.id} · llm_calls row $id not written", e)
            }
            return id
        }

        private fun fallBackToTags(error: GatewayError.SystemRoleRejected) {
            if (systemMessagesRejected) return
            systemMessagesRejected = true
            Decisions(services.trace, services.clock).record(
                log,
                DecisionRecord.Kind.OPERATOR,
                "switched",
                subject = "operator channel",
                reason = "the gateway rejected a system message (${error.message}); notes now go in <operator> and <engine> tags",
                conversationId = conversation.id,
                turnId = caller.turnId,
                level = Log.Level.WARN,
            )
            conversation.transcripts?.note(conversation, "operator channel switched to ${OperatorChannelMode.USER_TAG.id}: the gateway rejected a system message")
        }

        private fun warn(what: String) {
            log.warn("${conversation.id} · $what")
            conversation.transcripts?.note(conversation, what)
        }
    }

    /** A request that reached the gateway, or why none did. */
    private sealed interface Sent {
        class Answered(val result: SendResult, val llmCallId: Long) : Sent

        class Failed(val error: GatewayError, val requestId: String?) : Sent
    }

    companion object {
        /** Result of a tool call refused because the turn reached its step limit. */
        const val STEP_LIMIT_RESULT = "not executed: turn step limit reached; end your turn now"

        /** Engine note sent with [STEP_LIMIT_RESULT]. */
        const val STEP_LIMIT_NOTE = "Turn step limit reached: your last tool calls were not executed. End your turn now, without calling any tool."

        /**
         * The engine note of a turn with [TurnLimits.countdown], once the reply to request [used] of [max] ran its tools
         * ([used] < [max]): how many messages are left, or that the next one is the report, its tool calls refused.
         */
        fun countdownNote(used: Int, max: Int): String = if (used + 1 < max) {
            "$used of your $max messages used (a message counts once, whatever its tool calls): ${max - used} left, the report included."
        } else {
            "$used of your $max messages used: the next one is your report, without tool calls (they would not run)."
        }

        /** Engine note sent with a request again after an empty reply. */
        const val EMPTY_REPLY_NOTE = "Your previous reply was empty. Reply with a short text to end your turn, or call a tool."

        /** Result of a tool call the executor gave no result for. */
        const val EXECUTOR_FAILED_RESULT = "FAILED: the engine could not run this tool call"

        private const val MAX_REPLACED_CLIENTS = 2
        private const val HTTP_OK = 200
        private val ACTING = setOf(ToolEffect.SPEECH, ToolEffect.WORLD, ToolEffect.TASK)

        private fun errorResult(toolUseId: String, text: String): ToolResultBlockParam =
            ToolResultBlockParam.builder().toolUseId(toolUseId).content(text).isError(true).build()

        /** The thinking summaries of [reply], or null when it has none. */
        private fun thinkingOf(reply: Message): String? =
            reply.content().mapNotNull { block -> block.thinking().map { it.thinking() }.orElse(null) }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifEmpty { null }

        private fun httpStatusOf(error: Throwable): Int? =
            generateSequence(error) { it.cause }.filterIsInstance<AnthropicServiceException>().firstOrNull()?.statusCode()
    }
}
