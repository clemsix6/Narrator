package io.github.clemsix6.narrator2.relics

import com.anthropic.models.messages.TextBlockParam
import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.agent.Pending
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.agent.TurnOutcome
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What one conversation of the armorer is asked to make. Its tools find it by their conversation ([Armorer.job]) and
 * refuse what another job asks for; what they made is counted here.
 */
sealed class ArmorerJob(val label: String) {
    /** The last refusal of its tools, for the report of a job that made nothing. */
    @Volatile
    var lastRefusal: String? = null

    /** A champion for [player], as the Director (or an operator, [by]) asked in [brief]. */
    class Champion(val player: PlayerRef, val brief: String, val by: String) : ArmorerJob("champion for ${player.name}") {
        /** Its champion, once it stands. */
        @Volatile
        var champion: LiveChampion? = null
    }

    /** A relic for [killer], who slew [champion]; [fight] says how. */
    class Relic(val killer: PlayerRef, val champion: LiveChampion, val fight: String) : ArmorerJob("relic for ${killer.name}") {
        /** What [killer] got, described; null until then. */
        @Volatile
        var given: String? = null
    }

    /** Objects for the chests' reserves: [wanted] of each kind. */
    class Restock(val wanted: Map<StockKind, Int>) : ArmorerJob("restock") {
        val made = ConcurrentHashMap<StockKind, Int>()

        fun left(kind: StockKind): Int = (wanted[kind] ?: 0) - (made[kind] ?: 0)
    }
}

/** How a job's conversation ended: [done] with the model's report, or not, with why. */
data class ArmorerEnding(val done: Boolean, val text: String, val conversationId: String)

/**
 * Runs the armorer's conversations (M7-RELICS), one per job, on the engine of the workers ([AgentLoop]): `armorer.md`,
 * the armorer's tools (`ToolCatalog`, role `armorer`), a first message that holds what the job needs, one turn within
 * its messages (the engine counts them for the model, `TurnLimits.countdown`) and its time. Its `conversations` row and
 * its transcript are written like a worker's; its calls are `llm_calls` of the role `armorer`, purpose `worker`. Nothing
 * goes back to the Director from here: the service tells him what was made.
 */
class Armorer(private val services: NarratorServices) {
    private val agent = AgentLoop(services, ToolRunner(services))
    private val jobs = ConcurrentHashMap<String, ArmorerJob>()

    /** The job the conversation [conversationId] works for, while it runs. */
    fun job(conversationId: String): ArmorerJob? = jobs[conversationId]

    /** Jobs running now. */
    fun running(): List<ArmorerJob> = jobs.values.toList()

    /**
     * Runs [job]: [opening] as the first message, at most [steps] messages, within [timeoutMs]. Throws the cancellation
     * of the caller (the server stops) once the conversation is recorded as cancelled.
     */
    suspend fun run(job: ArmorerJob, opening: String, steps: Int, timeoutMs: Long): ArmorerEnding {
        val settings = services.settings.get()
        val id = "${Role.ARMORER.id}-${UUID.randomUUID().toString().take(8)}"
        if (settings.claude == null) return ArmorerEnding(false, "no LLMGW key: the armorer cannot work", id)
        val env = ToolEnv(services.journal.available, workerRoles = emptySet(), memoryAvailable = services.memory.available, relicsAvailable = true)
        val tools = ToolCatalog.toolSetFor(Role.ARMORER, env, services)
        val system = listOf(TextBlockParam.builder().text(settings.prompts.get(Role.ARMORER.id)).build())
        val conversation = Conversation(Role.ARMORER, "Armorer: ${job.label}", system, tools, services.transcripts, id)
        val row = ConversationRecord(id, Role.ARMORER, conversation.label, null, null, services.clock.instant(), null, null, null, transcriptOf(conversation))
        services.trace.conversation(row)
        services.transcripts.note(conversation, "${job.label}, with ${tools.tools.size} tools: ${tools.names.joinToString()}")
        log.info("$id started: ${job.label}, ${tools.tools.size} tools")
        jobs[id] = job
        var ending = ArmorerEnding(false, "cancelled", id)
        var reason = EndReason.CANCELLED
        try {
            val caller = Caller(Role.ARMORER, id, turnId = null, taskId = null)
            val outcome = withTimeoutOrNull(timeoutMs) {
                agent.runTurn(conversation, Pending.text(opening), caller, TurnLimits(steps, countdown = true), purpose = LlmCallRecord.Purpose.WORKER)
            }
            ending = when (outcome) {
                null -> ArmorerEnding(false, "no end within ${ToolText.duration(timeoutMs)}", id)
                is TurnOutcome.Ended -> ArmorerEnding(true, outcome.finalText.trim().ifEmpty { "(no report)" }, id)
                is TurnOutcome.Failed -> ArmorerEnding(false, outcome.error.description, id)
                is TurnOutcome.Refused -> ArmorerEnding(false, "refused by the safety filter", id)
                is TurnOutcome.Truncated -> ArmorerEnding(false, "reply cut off: ${outcome.reason}", id)
                is TurnOutcome.Empty -> ArmorerEnding(false, "empty replies", id)
                is TurnOutcome.StepLimit -> ArmorerEnding(false, "step limit: still calling tools after ${outcome.calls} requests", id)
                is TurnOutcome.Cancelled -> ArmorerEnding(false, "cancelled", id)
            }
            reason = when {
                outcome == null -> EndReason.TIMEOUT
                outcome is TurnOutcome.Cancelled -> EndReason.CANCELLED
                ending.done -> EndReason.DONE
                else -> EndReason.FAILED
            }
            return ending
        } catch (e: CancellationException) {
            reason = EndReason.SHUTDOWN
            throw e
        } catch (e: Exception) {
            log.error("$id failed on an engine error", e)
            reason = EndReason.FAILED
            ending = ArmorerEnding(false, "engine error: ${e.message ?: e.javaClass.simpleName}", id)
            return ending
        } finally {
            jobs.remove(id)
            close(conversation, row, reason, ending)
        }
    }

    /** Ends the `conversations` row and the transcript of [conversation]. Never throws. */
    private fun close(conversation: Conversation, row: ConversationRecord, reason: EndReason, ending: ArmorerEnding) {
        try {
            val ended = services.clock.instant()
            services.trace.conversation(row.copy(endedAt = ended, endReason = reason, summary = ending.text.take(SUMMARY_MAX)))
            services.transcripts.note(conversation, "${row.label} ${reason.id}" + if (ending.done) "" else ": ${ending.text}")
            conversation.close()
            val took = ToolText.duration(ended.toEpochMilli() - row.startedAt.toEpochMilli())
            if (ending.done) log.info("${conversation.id} done in $took") else log.warn("${conversation.id} ${reason.id} in $took: ${ending.text}")
        } catch (e: Exception) {
            log.error("${conversation.id}: its end was not recorded", e)
        }
    }

    /** Where the conversation's Markdown transcript is, relative to the data folder; null without transcripts. */
    private fun transcriptOf(conversation: Conversation): String? {
        val transcripts = services.transcripts
        if (!transcripts.enabled) return null
        val file = transcripts.root.resolve(LocalDate.now(services.clock).toString()).resolve("${conversation.id}.md")
        return if (file.startsWith(services.dataDir)) services.dataDir.relativize(file).toString().replace('\\', '/') else file.toString()
    }

    private companion object {
        val log = RelicsService.log
        const val SUMMARY_MAX = 500
    }
}
