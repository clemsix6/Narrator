package io.github.clemsix6.narrator2.memory

import com.anthropic.models.messages.TextBlockParam
import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.agent.Pending
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.agent.TurnOutcome
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * How one conversation of a memory job ended. [status] is [RunStatus.DONE] or [RunStatus.FAILED] (a
 * cancelled conversation throws instead); [report] is the model's final text when done, [error] why it
 * failed; [budget] is set when it stopped on the gateway's 402.
 */
data class AgentRun(
    val conversationId: String,
    val status: RunStatus,
    val report: String?,
    val error: String?,
    val budget: GatewayError.Budget?,
    val calls: Int,
)

/**
 * Runs the conversations of the memory jobs (M3 §7.6): the Chronicler's and the Dreamer's. The pattern of
 * the workers (`agent/Workers`), without anything sent back to the Director: a memory job never wakes it.
 *
 * - **One conversation per call** of [run]: `chronicler-<8 hex>` or `dreamer-<8 hex>`, with the role's
 *   prompt and tools (`ToolCatalog.toolSetFor`, the memory's tools shown), one turn of [AgentLoop] within
 *   its limits and its timeout, then its ending. Its `conversations` row (role, label, end reason
 *   `done`, `failed`, `timeout` or `cancelled`, the report as `summary`) and its transcript are written.
 * - **One engine for the whole memory**: one [AgentLoop] and one [ToolRunner]; the calls go to
 *   `llm_calls` with the purpose `chronicle` or `dream`.
 * - **The run behind the tools**: while a conversation runs, [runContext] gives its [RunContext] (job,
 *   window, chapter). `Memory.runContext` asks this, so that the tools write as the job's author and the
 *   store checks quotes against the job's window.
 * - **No retry.** A refusal, a cut-off or empty reply, a step limit, a timeout or a gateway error fails
 *   the conversation with its reason; the catch-up and the next dream come back later.
 *
 * @param toolSet the tools of a conversation: `ToolCatalog.toolSetFor` in production.
 */
class MemoryAgents(
    private val services: NarratorServices,
    private val toolSet: (Role, ToolEnv) -> ToolSet = { role, env -> ToolCatalog.toolSetFor(role, env, services) },
) {
    private val log = Log.memory
    private val clock = services.clock
    private val agent = AgentLoop(services, ToolRunner(services))
    private val contexts = ConcurrentHashMap<String, RunContext>()

    /** The job the conversation [conversationId] works for, while it runs; null otherwise. */
    fun runContext(conversationId: String): RunContext? = contexts[conversationId]

    /**
     * One conversation of [role] (`chronicler`, `dreamer`) for the job [context]: [opening] as its first
     * message, one turn of at most [limits], within [timeout]. Throws the cancellation of the caller (the
     * server stops) once the conversation is recorded as cancelled.
     */
    suspend fun run(role: Role, context: RunContext, label: String, opening: String, limits: TurnLimits, timeout: Duration): AgentRun {
        require(role == Role.CHRONICLER || role == Role.DREAMER) { "${role.id} is not a memory role" }
        val id = Conversation.newId(role)
        val started = clock.instant()
        var opened: Opened? = null
        contexts[id] = context
        try {
            val settings = services.settings.get()
            val tools = toolSet(role, ToolEnv(services.journal.available, workerRoles = emptySet(), memoryAvailable = services.memory.available))
            val system = listOf(TextBlockParam.builder().text(settings.prompts.get(role.id)).build())
            val conversation = Conversation(role, label, system, tools, services.transcripts, id)
            val row = ConversationRecord(id, role, label, null, null, started, null, null, null, transcriptOf(conversation))
            opened = Opened(conversation, row)
            services.trace.conversation(row)
            services.transcripts.note(conversation, "${context.kind.id} run ${context.runId}, with ${tools.tools.size} tools: ${tools.names.joinToString()}")
            log.info("run ${context.runId}: $id started ($label), ${tools.tools.size} tools")
            val caller = Caller(role, id, turnId = null, taskId = null)
            val purpose = if (role == Role.DREAMER) LlmCallRecord.Purpose.DREAM else LlmCallRecord.Purpose.CHRONICLE
            val outcome = withTimeoutOrNull(timeout.toMillis()) {
                agent.runTurn(conversation, Pending.text(opening), caller, limits, purpose = purpose)
            }
            if (outcome is TurnOutcome.Cancelled) currentCoroutineContext().ensureActive() // the caller was cancelled: throws
            val ending = if (outcome == null) Ending(EndReason.TIMEOUT, null, "no end within ${ToolText.duration(timeout.toMillis())}", null) else endingOf(outcome)
            close(opened, ending)
            return AgentRun(id, if (ending.reason == EndReason.DONE) RunStatus.DONE else RunStatus.FAILED, ending.report, ending.error, ending.budget, outcome?.calls ?: 0)
        } catch (e: CancellationException) {
            opened?.let { close(it, Ending(EndReason.CANCELLED, null, "the server stopped", null)) }
            throw e
        } catch (e: Exception) {
            log.error("run ${context.runId}: $id failed on an engine error", e)
            val ending = Ending(EndReason.FAILED, null, "engine error: ${e.message ?: e.javaClass.simpleName}", null)
            opened?.let { close(it, ending) }
            return AgentRun(id, RunStatus.FAILED, null, ending.error, null, 0)
        } finally {
            contexts.remove(id)
        }
    }

    /** What a turn's [outcome] makes of the conversation. */
    private fun endingOf(outcome: TurnOutcome): Ending = when (outcome) {
        is TurnOutcome.Ended -> Ending(EndReason.DONE, outcome.finalText.trim().takeIf { it.isNotEmpty() }?.let(::report), null, null)
        is TurnOutcome.Failed -> Ending(EndReason.FAILED, null, reasonOf(outcome.error), outcome.error as? GatewayError.Budget)
        is TurnOutcome.Refused -> Ending(EndReason.FAILED, null, "refused by the safety filter" + (outcome.category?.let { " (category $it)" } ?: ""), null)
        is TurnOutcome.Truncated -> Ending(EndReason.FAILED, null, "reply cut off: ${outcome.reason}", null)
        is TurnOutcome.Empty -> Ending(EndReason.FAILED, null, "empty replies", null)
        is TurnOutcome.StepLimit -> Ending(EndReason.FAILED, null, "step limit: still calling tools after ${outcome.calls} requests", null)
        is TurnOutcome.Cancelled -> Ending(EndReason.CANCELLED, null, "cancelled", null)
    }

    private fun reasonOf(error: GatewayError): String = when (error) {
        is GatewayError.Budget -> "budget: the LLMGW budget is spent" + (error.resetAt?.let { " until ${HOURS.withZone(clock.zone).format(it)}" } ?: "")
        else -> error.description
    }

    /** Ends the `conversations` row and the transcript of [opened]. Never throws. */
    private fun close(opened: Opened, ending: Ending) {
        try {
            val ended = clock.instant()
            services.trace.conversation(opened.row.copy(endedAt = ended, endReason = ending.reason, summary = ending.report ?: ending.error))
            services.transcripts.note(opened.conversation, "${opened.row.label} ${ending.reason.id}" + (ending.error?.let { ": $it" } ?: ""))
            opened.conversation.close()
            val took = ToolText.duration(ended.toEpochMilli() - opened.row.startedAt.toEpochMilli())
            val head = "${opened.conversation.id} ${ending.reason.id} in $took"
            if (ending.reason == EndReason.DONE || ending.reason == EndReason.CANCELLED) log.info("$head${ending.error?.let { ": $it" }.orEmpty()}") else log.warn("$head: ${ending.error}")
        } catch (e: Exception) {
            log.error("${opened.conversation.id}: its end was not recorded", e)
        }
    }

    /** Where the conversation's Markdown transcript is, relative to the data folder; null without transcripts. */
    private fun transcriptOf(conversation: Conversation): String? {
        val transcripts = services.transcripts
        if (!transcripts.enabled) return null
        val file = transcripts.root.resolve(LocalDate.now(clock).toString()).resolve("${conversation.id}.md")
        return if (file.startsWith(services.dataDir)) services.dataDir.relativize(file).toString().replace('\\', '/') else file.toString()
    }

    private class Opened(val conversation: Conversation, val row: ConversationRecord)

    private data class Ending(val reason: EndReason, val report: String?, val error: String?, val budget: GatewayError.Budget?)

    companion object {
        /** Longest report kept (`memory_runs.report`, `conversations.summary`), in characters. */
        const val REPORT_MAX_CHARS = 500

        private val HOURS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        private fun report(text: String): String = if (text.length <= REPORT_MAX_CHARS) text else text.take(REPORT_MAX_CHARS - 1) + "…"
    }
}
