package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.AgentRun
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.DreamRunner
import io.github.clemsix6.narrator2.memory.Hygiene
import io.github.clemsix6.narrator2.memory.MemoryAgents
import io.github.clemsix6.narrator2.memory.MemoryJobs
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunOutcome
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.memory.jsonObjectOf
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * The Dreamer (M3 §8): during the dead times, it reads back what the memory gathered and consolidates it.
 * `MemoryJobs` runs it, one dream at a time, with the dream's `memory_runs` row already written. A dream is
 * five steps, each made of short conversations of [MemoryAgents] that get exactly what they need
 * ([DreamInputs]) and write through the Dreamer's tools (`tool/memory/DreamerTools.kt`):
 *
 * 1. **Players**: one conversation per player with something new since their last reflection, the most
 *    active first, `memory.dreamMaxPlayers` at most: traits, skills, relations, the written sections of the
 *    sheet, a dream hint. Once a player's conversation is done, the code stores their reflection
 *    (`memory_state` `reflected:<uuid>`: its time and their stats then).
 * 2. **Review** of the Narrator's play: tics and reactions computed by the code; the review, the freshness
 *    of the running gags.
 * 3. **Chronicle**: the sessions over since the last one, the season in progress rewritten (or closed at 12
 *    sessions or 30 days), the saga when a season closed.
 * 4. **Grimoire**, rewritten whole within its budget, and the arcs to come; then the code audits the voice
 *    of the dream's writes and of the Grimoire (decision `memory voice_audit`).
 * 5. **Merges** of the near duplicates the hygiene found.
 *
 * Before step 1 of a new dream, the code's part: the hygiene ([Hygiene.beforeDream]); the stats were
 * computed by `MemoryJobs`. After the dream, a `CHECKPOINT`.
 *
 * - **Resuming**: the last step passed is stored after each one (`MemoryJobs.stepKey`), so that a dream cut
 *   short (the server stops, the gateway's budget is spent) resumes at its step with a new run. Within step
 *   1, the players reflected on already have nothing new: they are not done twice.
 * - **A failed step does not stop the next ones.** A dream is done when its Grimoire was rewritten (step 4),
 *   failed otherwise, with the steps' results in its report. A 402 stops the dream at once, with the
 *   gateway's [GatewayError.Budget] and the last step passed ([RunOutcome.lastStep]).
 *
 * [RunOutcome.writes] counts the run's rows of `memory_log` (the hygiene's included); [RunOutcome.rejected]
 * its tool calls refused for their arguments.
 */
class Dreamer(
    private val services: NarratorServices,
    private val agents: MemoryAgents,
    private val hygiene: Hygiene,
) : DreamRunner {
    private val log = Log.memory
    private val clock = services.clock
    private val inputs = DreamInputs(services)
    private val decisions = Decisions(services.trace, clock)
    private val store get() = services.memory.store

    override suspend fun dream(runId: Long, fromStep: Int): RunOutcome {
        val dream = Dream(runId, fromStep)
        val lastEnd = store.state(MemoryJobs.DREAM_END_KEY)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        log.info("dream run $runId: from step $fromStep" + (lastEnd?.let { ", last dream ended $it" } ?: ", first dream"))
        if (fromStep == 1) beforeDream(runId, lastEnd)
        for (step in fromStep..DreamInputs.STEPS) {
            val result = when (step) {
                1 -> players(dream)
                2 -> review(dream)
                3 -> chronicle(dream)
                4 -> grimoire(dream)
                else -> merges(dream, lastEnd)
            }
            dream.results[step] = result
            log.info("dream run $runId: step $step ${result.status.id}${result.note?.let { ": $it" } ?: ""}")
            if (result.status == Status.BUDGET) return dream.outcome(lastStep = step - 1, lastEnd)
            store.setState(MemoryJobs.stepKey(runId), step.toString())
            if (step == GRIMOIRE_STEP) audit(runId)
        }
        services.journal.checkpointAsync()
        return dream.outcome(lastStep = DreamInputs.STEPS, lastEnd)
    }

    /** The code's part before a new dream (M3 §8.6): expirations, archival, notes. A failure is logged; the dream goes on. */
    private suspend fun beforeDream(runId: Long, lastEnd: Instant?) {
        try {
            hygiene.beforeDream(clock.instant(), lastEnd, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("dream run $runId: the hygiene before the dream failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── The steps ───────────────────────────────────────────────────────────────────────────────────

    private suspend fun players(dream: Dream): StepResult {
        val max = services.settings.get().config.memory.dreamMaxPlayers
        val candidates = inputs.candidates(clock.instant(), max)
        if (candidates.isEmpty()) return StepResult(Status.SKIPPED, "no player has anything new since their last reflection")
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (candidate in candidates) {
            val input = inputs.player(dream.runId, candidate, clock.instant())
            val run = dream.converse(1, "Dream run ${dream.runId}, step 1: ${candidate.player.name}", input.text, PLAYER_STEPS, PLAYER_TIMEOUT)
            when {
                run.budget != null -> return StepResult(Status.BUDGET, "budget spent at ${candidate.player.name}")
                run.status == RunStatus.DONE -> {
                    store.setState(DreamInputs.reflectedKey(candidate.player.uuid), input.reflection.encode())
                    done += candidate.player.name
                }
                else -> failed += "${candidate.player.name} (${run.error?.let { ModelText.cut(it, ERROR_MAX_CHARS) }})"
            }
        }
        val note = listOfNotNull(
            done.takeIf { it.isNotEmpty() }?.joinToString(", "),
            failed.takeIf { it.isNotEmpty() }?.let { "failed: ${it.joinToString(", ")}" },
        ).joinToString("; ")
        return StepResult(if (failed.isEmpty()) Status.DONE else Status.FAILED, note)
    }

    private suspend fun review(dream: Dream): StepResult {
        val opening = inputs.review(dream.runId, clock.instant())
            ?: return StepResult(Status.SKIPPED, "nothing to review: no line of the Narrator, no gag, no review")
        return dream.step(2, "review", opening, REVIEW_STEPS, REVIEW_TIMEOUT)
    }

    private suspend fun chronicle(dream: Dream): StepResult {
        val opening = inputs.chronicle(dream.runId, clock.instant())
            ?: return StepResult(Status.SKIPPED, "nothing to chronicle: no chapter waits for a session that is over")
        return dream.step(3, "chronicle", opening, CHRONICLE_STEPS, CHRONICLE_TIMEOUT)
    }

    /** Done only when the conversation rewrote the Grimoire. */
    private suspend fun grimoire(dream: Dream): StepResult {
        val started = clock.instant()
        val result = dream.step(GRIMOIRE_STEP, "grimoire", inputs.grimoire(dream.runId, started), GRIMOIRE_STEPS, GRIMOIRE_TIMEOUT)
        if (result.status != Status.DONE) return result
        val doc = store.doc(DocKey.Grimoire.key)
        return if (doc != null && doc.source == Source.DREAMER && !doc.writtenAt.isBefore(started)) {
            StepResult(Status.DONE, "Grimoire version ${doc.version}")
        } else {
            StepResult(Status.FAILED, "the conversation ended without writing the Grimoire")
        }
    }

    /** The merge candidates among the memories written since the last dream ended. */
    private suspend fun merges(dream: Dream, lastEnd: Instant?): StepResult {
        val candidates = hygiene.mergeCandidates(lastEnd)
        val opening = inputs.merges(dream.runId, candidates) ?: return StepResult(Status.SKIPPED, "no merge candidate")
        return dream.step(5, "merges", opening, MERGE_STEPS, MERGE_TIMEOUT).let { if (it.status == Status.DONE) it.copy(note = "${candidates.size} pairs read") else it }
    }

    // ── The voice audit (M3 §12.3) ──────────────────────────────────────────────────────────────────

    /**
     * After step 4: [VoiceGuard.warnings] over every text the run wrote (the `after` of its `memory_log`
     * rows) and the current Grimoire; decision `memory voice_audit` with the counts. Never fails the dream.
     */
    private suspend fun audit(runId: Long) {
        try {
            val rows = services.journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query(AUDIT_SQL, runId) { "${getString(1)} ${getString(2)}" to getString(3) }
            }.toMap()
            val grimoire = store.doc(DocKey.Grimoire.key)
            val texts = rows.mapValues { (_, after) -> texts(after) } +
                listOfNotNull(grimoire?.let { "memory_docs grimoire:${it.version}" to listOf(it.content) }).filter { it.first !in rows }
            val found = texts.mapValues { (_, values) -> values.flatMap(VoiceGuard::warnings).distinct() }.filterValues { it.isNotEmpty() }
            val reason = "${texts.size} texts of the run and the Grimoire, " + if (found.isEmpty()) {
                "none with grandiose or dark words"
            } else {
                "${found.size} with grandiose or dark words: " + found.entries.take(AUDIT_SHOWN).joinToString("; ") { (row, words) -> "${words.joinToString(", ")} ($row)" }
            }
            decisions.record(
                log, DecisionRecord.Kind.MEMORY, "voice_audit", "run $runId", reason,
                detail = buildJsonObject {
                    put("texts", texts.size)
                    put("with_warnings", found.size)
                    put("warnings", found.values.sumOf { it.size })
                },
                level = if (found.isEmpty()) Log.Level.INFO else Log.Level.WARN,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("dream run $runId: the voice audit failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The texts a model wrote in a row of the audit ([TEXT_COLUMNS] of its `after` JSON). */
    private fun texts(after: String): List<String> {
        val row = jsonObjectOf(after) ?: return emptyList()
        return TEXT_COLUMNS.mapNotNull { (row[it] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
    }

    // ── A dream run ─────────────────────────────────────────────────────────────────────────────────

    private enum class Status {
        DONE, SKIPPED, FAILED, BUDGET;

        val id: String get() = name.lowercase()
    }

    private data class StepResult(val status: Status, val note: String?)

    /** What a dream run did so far: its conversations, the result of each step, and the gateway's budget if it stopped on it. */
    private inner class Dream(val runId: Long, val fromStep: Int) {
        val results = sortedMapOf<Int, StepResult>()
        private val conversations = mutableListOf<String>()
        private var budget: GatewayError.Budget? = null
        private val context = RunContext(runId, RunKind.DREAM, null, null, null)

        /** One conversation of [step]. */
        suspend fun converse(step: Int, label: String, opening: String, steps: Int, timeout: Duration): AgentRun {
            log.debug { "dream run $runId: step $step opening of ${opening.length} characters" }
            val run = agents.run(Role.DREAMER, context, label, opening, TurnLimits(steps), timeout)
            conversations += run.conversationId
            run.budget?.let { budget = it }
            return run
        }

        /** A step made of one conversation ([name] in its label). */
        suspend fun step(step: Int, name: String, opening: String, steps: Int, timeout: Duration): StepResult {
            val run = converse(step, "Dream run $runId, step $step: $name", opening, steps, timeout)
            val error = run.error?.let { ModelText.cut(it, ERROR_MAX_CHARS) }
            return when {
                run.budget != null -> StepResult(Status.BUDGET, error)
                run.status == RunStatus.DONE -> StepResult(Status.DONE, null)
                else -> StepResult(Status.FAILED, error)
            }
        }

        /**
         * The run's outcome after [lastStep]: done when the Grimoire was rewritten, by this run or by the
         * attempt it resumes ([lastEnd]: the end of the previous dream).
         */
        suspend fun outcome(lastStep: Int, lastEnd: Instant?): RunOutcome {
            val grimoire = results[GRIMOIRE_STEP]?.let { it.status == Status.DONE } ?: (lastStep >= GRIMOIRE_STEP && grimoireSince(lastEnd))
            val status = if (budget == null && grimoire) RunStatus.DONE else RunStatus.FAILED
            val steps = results.entries.joinToString(" · ") { (step, result) -> "step $step ${result.status.id}" + (result.note?.let { ": $it" } ?: "") }
            val resumed = if (fromStep > 1) "resumed at step $fromStep" + if (results.isEmpty()) "" else " · " else ""
            val missing = if (budget == null && !grimoire) " · no Grimoire written: the dream failed" else ""
            val report = ModelText.cut(resumed + steps, MemoryAgents.REPORT_MAX_CHARS - missing.length) + missing
            val (writes, rejected) = counts()
            log.info("dream run $runId ${status.id}: $writes write(s), $rejected refused, ${conversations.size} conversation(s): $report")
            return RunOutcome(status, report, writes, rejected, conversations.toList(), lastStep, budget)
        }

        /** Whether the current Grimoire was written by the Dreamer after [since] (the earlier attempts of this dream). */
        private suspend fun grimoireSince(since: Instant?): Boolean {
            val doc = store.doc(DocKey.Grimoire.key) ?: return false
            return doc.source == Source.DREAMER && (since == null || doc.writtenAt.isAfter(since))
        }

        /** The run's writes (its `memory_log` rows) and its tool calls refused for their arguments. */
        private suspend fun counts(): Pair<Int, Int> {
            services.journal.barrier() // the tool calls of its conversations
            return services.journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query(COUNTS_SQL, runId, stringArray(conversations)) { getInt(1) to getInt(2) }.single()
            }
        }
    }

    companion object {
        private const val GRIMOIRE_STEP = 4

        /** Requests and duration of a conversation, step by step (M3 §8.2). */
        const val PLAYER_STEPS = 10
        val PLAYER_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val REVIEW_STEPS = 8
        val REVIEW_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val CHRONICLE_STEPS = 8
        val CHRONICLE_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val GRIMOIRE_STEPS = 6
        val GRIMOIRE_TIMEOUT: Duration = Duration.ofMinutes(15)
        const val MERGE_STEPS = 6
        val MERGE_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** The columns of the memory's tables that hold a text a model wrote. */
        private val TEXT_COLUMNS = listOf("title", "content", "statement", "stakes", "resolution", "summary", "label", "note", "rationale", "text")

        /** Longest reason of a failed conversation in the dream's report: the conversation's row has it whole. */
        private const val ERROR_MAX_CHARS = 80

        /** Rows shown in the reason of the voice audit. */
        private const val AUDIT_SHOWN = 5

        private const val READ_TIMEOUT_MS = 10_000L

        /** The rows a run wrote, each with its latest state. */
        private const val AUDIT_SQL = "SELECT table_name, row_key, after::VARCHAR FROM memory_log " +
            "WHERE run_id = ? AND after IS NOT NULL AND op IN ('insert', 'update', 'merge') ORDER BY rowid"

        private const val COUNTS_SQL = "SELECT (SELECT count(*) FROM memory_log WHERE run_id = ?)::INTEGER, " +
            "(SELECT count(*) FROM tool_calls WHERE list_contains(?, conversation_id) AND status = 'invalid')::INTEGER"
    }
}
