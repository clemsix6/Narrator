package io.github.clemsix6.narrator2.tool

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ServerStoppedException
import io.github.clemsix6.narrator2.core.ServerThreadTimeoutException
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

/**
 * Runs the tool calls of a reply (M1_M2 §8.4).
 *
 * - The calls run one after the other, in order: the guards' decisions are deterministic, and one
 *   `user` message returns every result.
 * - A tool the conversation does not have answers `INVALID`; while the server is not running, every
 *   call answers `FAILED: server is stopping` without running.
 * - Arguments are decoded and checked first ([ToolArgs]): `INVALID` with every problem. Arguments written
 *   as JSON text and read as the value it holds are a `tool args_coerced` decision (`DEBUG`).
 * - Each run is bounded by the tool's `timeoutMs`. Past it, a read answers `TIMEOUT`; any other tool
 *   answers `UNKNOWN`, as its action may still happen (a task already started on the server thread
 *   cannot be stopped).
 * - An exception answers `FAILED` with its message and is logged as an `ERROR` with its stack trace
 *   (a `WARN` without it when the server thread did not run the task, or has stopped).
 * - The model gets at most [MODEL_RESULT_MAX_CHARS] characters; anything but `OK` is an error.
 * - Each call is audited: one `tool_calls` row and one log line
 *   `[Tool] <role> <tool> <status> <ms>ms targets=[…] <result, first 80 characters>`.
 *
 * When the caller is cancelled (the server stops) during a call, that call is recorded as `UNKNOWN`
 * (or `FAILED` for a read) and the cancellation goes on: the calls after it do not run.
 */
class ToolRunner(private val services: NarratorServices, private val clock: Clock = services.clock) : ToolExecutor {
    private val log = Log.tool
    private val mapper = jsonMapper()

    override suspend fun runAll(calls: List<ToolUseBlock>, tools: ToolSet, caller: Caller, llmCallId: Long): List<ToolResultBlockParam> =
        calls.map { call -> resultBlock(call, run(call, tools, caller, llmCallId)) }

    private suspend fun run(call: ToolUseBlock, tools: ToolSet, caller: Caller, llmCallId: Long): ToolResult {
        val audit = Audit(call, caller, llmCallId)
        val tool = tools[call.name()]
        val result = when {
            tool == null -> ToolResult.invalid("unknown tool \"${call.name()}\"; your tools are: ${tools.names.joinToString()}")
            services.phase != Phase.RUNNING -> ToolResult.failed("server is stopping")
            else -> try {
                execute(tool, audit.input, caller)
            } catch (e: CancellationException) {
                audit.record(tool.effect, interrupted(tool))
                throw e
            }
        }
        audit.record(tool?.effect ?: ToolEffect.READ, result) // an unknown tool did nothing
        return result
    }

    private suspend fun <A : Any> execute(tool: NarratorTool<A>, input: String, caller: Caller): ToolResult = try {
        when (val decoded = ToolArgs.decode(tool.args, input)) {
            is ToolArgs.Decoded.Invalid -> ToolResult.invalid(decoded.problem)
            is ToolArgs.Decoded.Ok -> {
                if (decoded.coerced.isNotEmpty()) coerced(tool, caller, decoded.coerced)
                val context = ToolContext(services, caller, clock, tool.timeoutMs)
                withTimeoutOrNull(tool.timeoutMs) { tool.run(context, decoded.args) } ?: overtime(tool)
            }
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller was cancelled: let the cancellation go on
        failure(tool, e) // a cancellation from inside the tool
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        failure(tool, e) // an Error too (a TODO, a class missing after a game update): the call fails, not the turn
    }

    /** A `tool args_coerced` decision: arguments written as JSON text were read as the values they hold ([ToolArgs]). */
    private fun coerced(tool: NarratorTool<*>, caller: Caller, paths: List<String>) {
        Decisions(services.trace, clock).record(
            log,
            DecisionRecord.Kind.TOOL,
            "args_coerced",
            subject = tool.name,
            reason = "${paths.joinToString()} written as JSON text, read as the value it holds (${caller.role.id})",
            conversationId = caller.conversationId,
            turnId = caller.turnId,
            level = Log.Level.DEBUG,
        )
    }

    private fun overtime(tool: NarratorTool<*>): ToolResult {
        val what = "${tool.name} did not finish within ${ToolText.wait(tool.timeoutMs)}"
        return if (tool.effect == ToolEffect.READ) ToolResult.timeout(what) else ToolResult.unknown("$what; the action may or may not have happened")
    }

    private fun interrupted(tool: NarratorTool<*>): ToolResult =
        if (tool.effect == ToolEffect.READ) {
            ToolResult.failed("cancelled: the server is stopping")
        } else {
            ToolResult.unknown("cancelled while running, the server is stopping; the action may or may not have happened")
        }

    private fun failure(tool: NarratorTool<*>, e: Throwable): ToolResult {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        if (e is ServerThreadTimeoutException || e is ServerStoppedException) {
            log.warn("${tool.name} failed: $reason")
        } else {
            log.error("${tool.name} failed with an exception", e)
        }
        return ToolResult.failed(reason)
    }

    private fun resultBlock(call: ToolUseBlock, result: ToolResult): ToolResultBlockParam =
        ToolResultBlockParam.builder()
            .toolUseId(call.id())
            .content(truncate(result.text.ifBlank { result.status.id }, MODEL_RESULT_MAX_CHARS))
            .apply { if (result.isError) isError(true) }
            .build()

    /** The audit of one call: its `tool_calls` row and its log line. */
    private inner class Audit(private val call: ToolUseBlock, private val caller: Caller, private val llmCallId: Long) {
        private val ts = clock.instant()
        private val start = System.nanoTime()

        /** The arguments as JSON text, as the model sent them. */
        val input: String = mapper.writeValueAsString(call._input())

        fun record(effect: ToolEffect, result: ToolResult) {
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            val targets = result.targets.map { it.name }
            services.trace.toolCall(
                ToolCallRecord(
                    id = services.trace.nextId(IdKind.TOOL_CALL),
                    ts = ts,
                    conversationId = caller.conversationId,
                    turnId = caller.turnId,
                    llmCallId = llmCallId,
                    toolUseId = call.id(),
                    role = caller.role,
                    tool = call.name(),
                    args = input,
                    status = result.status,
                    result = truncate(result.text, ToolCallRecord.RESULT_MAX_CHARS),
                    durationMs = ms,
                    targets = targets,
                    effect = effect,
                ),
            )
            val summary = result.text.lineSequence().first().let { if (it.length <= 80) it else it.take(79) + "…" }
            val line = "${caller.role.id} ${call.name()} ${result.status.id} ${ms}ms targets=[${targets.joinToString()}] $summary"
            if (result.status in TROUBLE) log.warn(line) else log.info(line)
            log.debug { "${call.name()} ${call.id()} args: $input" }
        }
    }

    companion object {
        /** Longest result the model gets (M1_M2 §8.4); the audit keeps [ToolCallRecord.RESULT_MAX_CHARS]. */
        const val MODEL_RESULT_MAX_CHARS = 8_000

        /** Statuses logged as warnings: the call did not do what was asked, and it is not the model's fault. */
        private val TROUBLE = setOf(ToolStatus.FAILED, ToolStatus.TIMEOUT, ToolStatus.UNKNOWN)

        /** [text] cut to [max] characters, ending with `… (truncated, N chars)` when cut (N = the full length). */
        fun truncate(text: String, max: Int): String {
            if (text.length <= max) return text
            val suffix = "… (truncated, ${text.length} chars)"
            var end = max - suffix.length
            if (text[end - 1].isHighSurrogate()) end-- // never split a character in two
            return text.take(end) + suffix
        }
    }
}
