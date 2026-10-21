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
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
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
 * Runs the conversations of the memory jobs (M3 §7.6): the Chronicler's, the Dreamer's, and the storyteller's step
 * of a dream, its programme (M6 §6.3). The pattern of the workers (`agent/Workers`), without anything sent back to
 * the Director: a memory job never wakes it.
 *
 * - **A conversation** ([Session], opened by [open]): `chronicler-<8 hex>`, `dreamer-<8 hex>` or `storyteller-<8 hex>`,
 *   with the role's prompt and tools (`ToolCatalog.toolSetFor`, the memory's tools shown, and `inspire` to the
 *   storyteller), and one turn of [AgentLoop] per job given to it, within the job's limits and timeout. Its
 *   `conversations` row (role, label, end reason `done`, `failed`, `timeout` or `cancelled`, the last report as
 *   `summary`) and its transcript are written. [run] is a conversation for one job; the Chronicler keeps one for
 *   its successive jobs (M6-FIX-12), append-only like the Director's chapter.
 * - **One engine for the whole memory**: one [AgentLoop] and one [ToolRunner]; the calls go to
 *   `llm_calls` with the purpose `chronicle`, `dream` or `programme`.
 * - **The run behind the tools**: while a conversation runs a job, [runContext] gives the job's [RunContext] (job,
 *   window, chapter, and the conversation's role). `Memory.runContext` asks this, so that the tools write as the
 *   job's author ([RunContext.author]) and the store checks quotes against the job's window.
 * - **No retry.** A refusal, a cut-off or empty reply, a step limit, a timeout or a gateway error fails
 *   the job with its reason, and ends its conversation (its history may end with tool calls left unanswered);
 *   the catch-up and the next dream come back later.
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
     * One conversation of [role] (`chronicler`, `dreamer`, `storyteller`) for the job [context] alone: [opening] as
     * its first message, one turn of at most [limits], within [timeout], then its end. Throws the cancellation of
     * the caller (the server stops) once the conversation is recorded as cancelled.
     */
    suspend fun run(role: Role, context: RunContext, label: String, opening: String, limits: TurnLimits, timeout: Duration): AgentRun {
        val session = open(role, label)
        return session.run(context, label, opening, limits, timeout).also { session.close("its job is over") }
    }

    /** A conversation of [role] (`chronicler`, `dreamer`, `storyteller`) named [label], for jobs to come ([Session.run]). */
    fun open(role: Role, label: String): Session {
        val purpose = PURPOSES[role] ?: throw IllegalArgumentException("${role.id} is not a memory role")
        return Session(role, label, purpose)
    }

    /**
     * A conversation of a memory role that successive jobs share, one turn each ([run]), append-only: its prompt and
     * tools are those of its first job, and each job's opening follows the previous jobs and their replies. The
     * gateway caches what came before (M6-FIX-12): a job pays for its own opening, not for the prompt again.
     *
     * It is created with its first job. A job that does not end well (refused, cut off, timed out, a gateway or an
     * engine error, cancelled) ends it with its reason: its history may end with tool calls left unanswered. Else it
     * lasts until [close], or until [stale] says it should not take another job. One job at a time.
     */
    inner class Session internal constructor(val role: Role, private val label: String, private val purpose: LlmCallRecord.Purpose) {
        val id = "${role.id}-${UUID.randomUUID().toString().take(8)}"
        private var opened: Opened? = null
        private var report: String? = null

        /** Jobs it ran. */
        var jobs = 0
            private set

        /** When its last job ended; null before its first. */
        var lastUsed: Instant? = null
            private set

        /** True once ended: it takes no more jobs. */
        @Volatile
        var closed = false
            private set

        /** The context of its latest request, in tokens (`Conversation.contextTokens`). */
        val contextTokens: Long get() = opened?.conversation?.contextTokens ?: 0

        /**
         * Why it should not take another job at [now], or null when it can: closed, idle for [maxIdle] (the gateway's
         * cache would be gone: the whole history would be written again), a context of [maxContext] tokens or more (a
         * new conversation reads less), or a prompt, tools, model or effort that changed since it began (its prefix is
         * frozen).
         */
        fun stale(now: Instant, maxIdle: Duration, maxContext: Long): String? {
            val current = opened ?: return if (closed) "it is closed" else null
            val idle = lastUsed?.let { Duration.between(it, now) } ?: Duration.ZERO
            val settings = services.settings.get()
            val config = settings.config.role(role)
            return when {
                closed -> "it is closed"
                idle >= maxIdle -> "idle for ${ToolText.duration(idle.toMillis())}"
                contextTokens >= maxContext -> "its context reached $contextTokens tokens"
                current.system != settings.prompts.get(role.id) -> "the prompt changed"
                current.tools != toolSet(role, env()).names -> "the tools changed"
                current.model != config.model || current.effort != config.effort -> "the model or the effort changed"
                else -> null
            }
        }

        /**
         * One turn for the job [context], named [job] in the transcript: [opening] as its message, at most [limits],
         * within [timeout]. Throws the cancellation of the caller (the server stops) once the conversation is
         * recorded as cancelled.
         */
        suspend fun run(context: RunContext, job: String, opening: String, limits: TurnLimits, timeout: Duration): AgentRun {
            check(!closed) { "$id is closed" }
            contexts[id] = context.copy(role = role)
            try {
                val current = opened ?: create(context)
                services.transcripts.note(current.conversation, "$job: ${context.kind.id} run ${context.runId}")
                log.info("run ${context.runId}: $id takes its job ${jobs + 1} ($job)")
                val caller = Caller(role, id, turnId = null, taskId = null)
                val outcome = withTimeoutOrNull(timeout.toMillis()) {
                    agent.runTurn(current.conversation, Pending.text(opening), caller, limits, purpose = purpose)
                }
                if (outcome is TurnOutcome.Cancelled) currentCoroutineContext().ensureActive() // the caller was cancelled: throws
                jobs++
                lastUsed = clock.instant()
                val ending = if (outcome == null) Ending(EndReason.TIMEOUT, null, "no end within ${ToolText.duration(timeout.toMillis())}", null) else endingOf(outcome)
                if (ending.reason == EndReason.DONE) report = ending.report else end(ending, "its job ${ending.reason.id}")
                return AgentRun(id, if (ending.reason == EndReason.DONE) RunStatus.DONE else RunStatus.FAILED, ending.report, ending.error, ending.budget, outcome?.calls ?: 0)
            } catch (e: CancellationException) {
                end(Ending(EndReason.CANCELLED, null, "the server stopped", null), "its job was cancelled")
                throw e
            } catch (e: Exception) {
                log.error("run ${context.runId}: $id failed on an engine error", e)
                val ending = Ending(EndReason.FAILED, null, "engine error: ${e.message ?: e.javaClass.simpleName}", null)
                end(ending, "an engine error")
                return AgentRun(id, RunStatus.FAILED, null, ending.error, null, 0)
            } finally {
                contexts.remove(id)
            }
        }

        /** Ends it, done, after its last job's report; [why] goes to its transcript and the log. Never throws; once. */
        fun close(why: String) = end(Ending(EndReason.DONE, report, null, null), why)

        /** The conversation, its row and its transcript, with the role's prompt and tools as they are now. */
        private fun create(context: RunContext): Opened {
            val settings = services.settings.get()
            val tools = toolSet(role, env())
            val system = settings.prompts.get(role.id)
            val config = settings.config.role(role)
            val conversation = Conversation(role, label, listOf(TextBlockParam.builder().text(system).build()), tools, services.transcripts, id)
            val row = ConversationRecord(id, role, label, null, null, clock.instant(), null, null, null, transcriptOf(conversation))
            return Opened(conversation, row, system, tools.names, config.model, config.effort).also {
                opened = it
                services.trace.conversation(row)
                services.transcripts.note(conversation, "${context.kind.id} run ${context.runId}, with ${tools.tools.size} tools: ${tools.names.joinToString()}")
                log.info("run ${context.runId}: $id started ($label), ${tools.tools.size} tools")
            }
        }

        private fun end(ending: Ending, why: String) {
            if (closed) return
            closed = true
            opened?.let { close(it, ending, jobs, why) }
        }
    }

    /** What a memory conversation is given: the journal, the memory, and `inspire` for the storyteller. */
    private fun Session.env() = ToolEnv(
        services.journal.available, workerRoles = emptySet(), memoryAvailable = services.memory.available,
        storytellerAvailable = role == Role.STORYTELLER,
    )

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

    /** Ends the `conversations` row and the transcript of [opened], after [jobs] jobs, because of [why]. Never throws. */
    private fun close(opened: Opened, ending: Ending, jobs: Int, why: String) {
        try {
            val ended = clock.instant()
            services.trace.conversation(opened.row.copy(endedAt = ended, endReason = ending.reason, summary = ending.report ?: ending.error))
            services.transcripts.note(opened.conversation, "${opened.row.label} ${ending.reason.id}" + (ending.error?.let { ": $it" } ?: "") + " ($why)")
            opened.conversation.close()
            val took = ToolText.duration(ended.toEpochMilli() - opened.row.startedAt.toEpochMilli())
            val head = "${opened.conversation.id} ${ending.reason.id} in $took after $jobs job${if (jobs == 1) "" else "s"} ($why)"
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

    /** A conversation created, with what it was created with: its prompt, its tools' names, its model and effort. */
    private class Opened(
        val conversation: Conversation, val row: ConversationRecord, val system: String, val tools: List<String>, val model: String, val effort: String,
    )

    private data class Ending(val reason: EndReason, val report: String?, val error: String?, val budget: GatewayError.Budget?)

    companion object {
        /** Longest report kept (`memory_runs.report`, `conversations.summary`), in characters. */
        const val REPORT_MAX_CHARS = 500

        /** The roles of the memory's conversations, with the purpose of their calls in `llm_calls`. */
        private val PURPOSES: Map<Role, LlmCallRecord.Purpose> = mapOf(
            Role.CHRONICLER to LlmCallRecord.Purpose.CHRONICLE,
            Role.DREAMER to LlmCallRecord.Purpose.DREAM,
            Role.STORYTELLER to LlmCallRecord.Purpose.PROGRAMME,
        )

        private val HOURS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        private fun report(text: String): String = if (text.length <= REPORT_MAX_CHARS) text else text.take(REPORT_MAX_CHARS - 1) + "…"
    }
}
