package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.AgentRun
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.ChronicleRunner
import io.github.clemsix6.narrator2.memory.MemoryAgents
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Names
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunOutcome
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.instantOrNull
import io.github.clemsix6.narrator2.memory.intOrNull
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Chronicler (M3 §7): it reads the Journal behind the Director and records what the Director did not.
 * `MemoryJobs` runs it, one job at a time, with the job's `memory_runs` row already written (`running`,
 * its window, its chapter): the store checks quotes against that window.
 *
 * - **A batch** ([batch]) over a window of an open chapter: the exchange log of the window ([ExchangeLog],
 *   cut into blocks of 24 000 characters, one conversation each) with what the memory already knows
 *   (`<known>`). The model records the commitments the Director forgot, settles what was settled, notes
 *   the interactions that matter and the incoherences; then the code covers the punishments and benefits
 *   left unnoted ([Sanctions]). A window with no line of chat and no tool call is skipped.
 * - **A nap** ([nap]) when a chapter closed, with or without a summary: the code turns the summary into the
 *   chapter's chronicle entry (`H-`, source `director`); a last batch covers the rest of the window; then
 *   one conversation consolidates the whole chapter (episodes, statuses, `write_chapter` when there is no
 *   summary, `since_last` for the players who left, `mark_used`, notes), and the code covers the punishments
 *   and benefits of the whole chapter.
 * - **No retry**: a conversation that fails (refusal, cut-off, empty replies, step limit, timeout, gateway
 *   error) ends the job `failed` with its reason, and with the gateway's [GatewayError.Budget] on a 402; the
 *   next batch or the catch-up comes back to it. What the tools wrote before stays: the store keeps a second
 *   pass from writing twice. A cancellation (the server stops) goes through to `MemoryJobs`.
 *
 * [RunOutcome.writes] counts the job's rows of `memory_log` (its writes, the code's included);
 * [RunOutcome.rejected] its tool calls refused for their arguments, the store's refusals among them.
 */
class Chronicler(private val services: NarratorServices, private val agents: MemoryAgents) : ChronicleRunner {
    private val log = Log.memory
    private val clock = services.clock
    private val sanctions = Sanctions(services)
    private val exchange = ExchangeLog(services, sanctions)

    override suspend fun batch(runId: Long, chapter: String, from: Instant, to: Instant): RunOutcome {
        val work = Work(runId)
        val name = chapterName(chapter)
        val context = RunContext(runId, RunKind.BATCH, from, to, chapter)
        log.info("batch run $runId of chapter $name: ${exchange.time(from)}–${exchange.time(to)}")
        when (chronicle(work, context, name, from, to, last = false)) {
            Step.NOTHING -> return work.outcome(RunStatus.SKIPPED, "nothing to note: no line of chat nor tool call in the window")
            Step.FAILED -> return work.outcome(RunStatus.FAILED)
            Step.DONE -> Unit
        }
        sanctions.cover(chapter, from, to, runId)
        return work.outcome(RunStatus.DONE)
    }

    override suspend fun nap(runId: Long, chapter: ConversationRecord): RunOutcome {
        val work = Work(runId)
        val end = chapter.endedAt ?: clock.instant()
        val (from, to) = window(runId) ?: (chapter.startedAt to end)
        val name = chapter.chapter?.toString() ?: chapter.conversationId
        val context = RunContext(runId, RunKind.NAP, from, to, chapter.conversationId)
        log.info("nap run $runId of chapter $name (${chapter.endReason?.id ?: "open"}): rest ${exchange.time(from)}–${exchange.time(to)}")
        val entry = chapterEntry(runId, chapter, end)
        if (chronicle(work, context, name, from, to, last = true) == Step.FAILED) return work.outcome(RunStatus.FAILED)
        val opening = napOpening(runId, chapter, end, name, entry)
            ?: return work.outcome(RunStatus.SKIPPED, "nothing happened in the chapter")
        val run = agents.run(Role.CHRONICLER, context, "Nap of chapter $name (run $runId)", opening, TurnLimits(NAP_STEPS), TIMEOUT)
        work.add(run)
        if (run.status != RunStatus.DONE) return work.outcome(RunStatus.FAILED)
        sanctions.cover(chapter.conversationId, chapter.startedAt, end, runId)
        return work.outcome(RunStatus.DONE)
    }

    private enum class Step { DONE, NOTHING, FAILED }

    /**
     * The batch over `]from, to]` of [context]'s chapter: one conversation per block of its exchange log, the
     * known rows read again before each one (a block sees what the previous ones wrote). [last]: the batch
     * that closes a nap.
     */
    private suspend fun chronicle(work: Work, context: RunContext, name: String, from: Instant, to: Instant, last: Boolean): Step {
        val chapter = checkNotNull(context.chapter)
        services.journal.barrier()
        val lines = exchange.lines(chapter, from, to)
        if (lines.none { it.kind != ExchangeLog.Kind.FACT }) return Step.NOTHING
        val blocks = exchange.blocks(lines, name, from, to, clock.instant())
        for ((index, block) in blocks.withIndex()) {
            val known = exchange.known(chapter, from, clock.instant(), block.players)
            val job = "<job kind=\"batch\" run=\"${context.runId}\" chapter=\"${ModelText.attribute(name)}\"${if (last) " closes=\"chapter\"" else ""}/>"
            val part = if (blocks.size > 1) ", part ${index + 1} of ${blocks.size}" else ""
            val label = "Batch of chapter $name (run ${context.runId}$part)"
            val run = agents.run(Role.CHRONICLER, context, label, listOf(job, block.text, known).joinToString("\n"), TurnLimits(BATCH_STEPS), TIMEOUT)
            work.add(run)
            if (run.status != RunStatus.DONE) return Step.FAILED
        }
        return Step.DONE
    }

    /**
     * The chronicle entry of the closed [chapter] (M3 §7.4): already there, or written now from its summary,
     * as it is (source `director`, for the job [runId]); null when there is none yet: no summary, or one the
     * store refused (the nap then asks the model for `write_chapter`).
     */
    private suspend fun chapterEntry(runId: Long, chapter: ConversationRecord, end: Instant): String? {
        val existing = services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(CHAPTER_ENTRY_SQL, chapter.conversationId) { getLong(1) }.firstOrNull()
        }
        if (existing != null) return "H-$existing"
        val summary = chapter.summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = chapter.chapter?.let { "Chapter $it" } ?: "Chapter of ${DAY_TIME.withZone(exchange.zone).format(chapter.startedAt)}"
        val draft = ChronicleDraft(
            ChronicleLevel.CHAPTER, chapter.startedAt, end, title, ModelText.cut(ModelText.lines(summary), ChronicleLevel.CHAPTER.maxChars),
            conversationId = chapter.conversationId,
        )
        return try {
            services.memory.store.addChronicle(draft, Author(Source.DIRECTOR, runId = runId, conversationId = chapter.conversationId)).ref.toString()
        } catch (e: MemoryRejected) {
            log.warn("nap run $runId: the summary of ${chapter.conversationId} is not a chronicle entry: ${e.message}; the Chronicler writes it")
            null
        }
    }

    /**
     * The first message of the nap's consolidation (M3 §7.4): the summary, what the memory got during the
     * chapter, its notable facts, its exchange log when it has no summary (three blocks at most), the players
     * who left and the memories the Director was shown. Null when nothing at all happened.
     */
    private suspend fun napOpening(runId: Long, chapter: ConversationRecord, end: Instant, name: String, entry: String?): String? {
        services.journal.barrier()
        val now = clock.instant()
        val zone = exchange.zone
        val start = chapter.startedAt
        val summary = chapter.summary?.trim()?.takeIf { it.isNotEmpty() }
        val facts = exchange.lines(chapter.conversationId, start, end, chat = false)
        val all = if (summary == null) exchange.lines(chapter.conversationId, start, end) else emptyList()
        val (written, left, shown) = services.journal.read(READ_TIMEOUT_MS) { connection ->
            Triple(
                exchange.written(connection, Names.of(connection), start, now, zone),
                exchange.left(connection, start, end.plus(LEFT_GRACE), zone),
                exchange.shown(connection, chapter.conversationId, start, end),
            )
        }
        if (summary == null && facts.isEmpty() && all.isEmpty() && written.isEmpty() && left.isEmpty()) return null
        val chronicle = entry ?: "none: write it with write_chapter"
        val head = "<job kind=\"nap\" run=\"$runId\" chapter=\"${ModelText.attribute(name)}\" day=\"${DAY.withZone(zone).format(start)}\" " +
            "from=\"${exchange.time(start)}\" to=\"${exchange.time(end)}\" end_reason=\"${chapter.endReason?.id ?: "open"}\" chronicle=\"$chronicle\"/>"
        val parts = mutableListOf(head)
        parts += "<summary>\n" + (summary?.let(ModelText::lines) ?: "(no summary: ${chapter.endReason?.id ?: "the chapter is open"})") + "\n</summary>"
        parts += ExchangeLog.fit(listOf("<written>") + written.map { "- ${it.line}" }.ifEmpty { listOf("(nothing)") } + "</written>", WRITTEN_MAX)
        parts += ExchangeLog.fit(listOf("<notable>") + facts.map { it.text }.ifEmpty { listOf("(nothing)") } + "</notable>", NOTABLE_MAX)
        if (summary == null && all.isNotEmpty()) {
            val blocks = exchange.blocks(all, name, start, end, now)
            parts += if (blocks.size <= MAX_NAP_BLOCKS) {
                blocks.map { it.text }
            } else {
                listOf("(the exchange log of the chapter takes ${blocks.size} blocks of ${ExchangeLog.MAX_CHARS} characters: too long; the notable facts and what was written stand for it)")
            }
        }
        parts += ExchangeLog.fit(listOf("<left>") + left.map { "- ${it.line}" }.ifEmpty { listOf("(nobody)") } + "</left>", LEFT_MAX)
        parts += ExchangeLog.fit(listOf("<shown>") + shown.map { "- ${it.line(zone, withText = true)}" }.ifEmpty { listOf("(nothing)") } + "</shown>", SHOWN_MAX)
        return parts.joinToString("\n")
    }

    /** The window of the job [runId] as `MemoryJobs` wrote it; null when its row is missing (then no quote can be checked). */
    private suspend fun window(runId: Long): Pair<Instant, Instant>? {
        val row = services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query("SELECT window_from, window_to FROM memory_runs WHERE run_id = ?", runId) { instantOrNull(1) to instantOrNull(2) }.singleOrNull()
        }
        val from = row?.first
        val to = row?.second
        if (from == null || to == null) {
            log.warn("run $runId has no window in memory_runs: the whole chapter is read, and every quote will be refused")
            return null
        }
        return from to to
    }

    /** The chapter's number, as its header shows it; its conversation id when the Journal does not have it. */
    private suspend fun chapterName(chapter: String): String = services.journal.read(READ_TIMEOUT_MS) { connection ->
        connection.query("SELECT chapter FROM conversations WHERE conversation_id = ?", chapter) { intOrNull(1) }.firstOrNull()
    }?.toString() ?: chapter

    /** What a job did so far: its conversations, their reports, and why it failed. */
    private inner class Work(val runId: Long) {
        private val conversations = mutableListOf<String>()
        private val reports = mutableListOf<String>()
        private var error: String? = null
        private var budget: GatewayError.Budget? = null

        fun add(run: AgentRun) {
            conversations += run.conversationId
            run.report?.let { reports += it }
            if (run.status != RunStatus.DONE) {
                error = run.error
                budget = run.budget
            }
        }

        /** The job's outcome; its report is the models' reports, or [report], or why it failed. */
        suspend fun outcome(status: RunStatus, report: String? = null): RunOutcome {
            val (writes, rejected) = counts()
            val text = if (status == RunStatus.FAILED) error ?: "failed" else report ?: reports.joinToString(" / ").ifEmpty { null }
            log.info("run $runId ${status.id}: $writes write(s), $rejected refused, ${conversations.size} conversation(s)" + (error?.let { ": $it" } ?: ""))
            return RunOutcome(status, text?.let { ModelText.cut(it, MemoryAgents.REPORT_MAX_CHARS) }, writes, rejected, conversations.toList(), budget = budget)
        }

        /** The job's writes (its `memory_log` rows) and its tool calls refused for their arguments. */
        private suspend fun counts(): Pair<Int, Int> {
            services.journal.barrier() // the tool calls of its conversations
            return services.journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query(COUNTS_SQL, runId, stringArray(conversations)) { getInt(1) to getInt(2) }.single()
            }
        }
    }

    companion object {
        /** Requests of a batch's turn (M3 §7.3). */
        const val BATCH_STEPS = 12

        /** Requests of a nap's turn (M3 §7.4). */
        const val NAP_STEPS = 16

        /** Longest conversation of the Chronicler. */
        val TIMEOUT: Duration = Duration.ofMinutes(10)

        /** Blocks of the exchange log a nap without summary reads at most; beyond, its notable facts only. */
        const val MAX_NAP_BLOCKS = 3

        /** Budgets of the nap's blocks, in characters. */
        const val WRITTEN_MAX = 8_000
        const val NOTABLE_MAX = 8_000
        const val LEFT_MAX = 3_000
        const val SHOWN_MAX = 4_000

        /** Sessions closed this long after the chapter (the server stopping) still left during it. */
        private val LEFT_GRACE: Duration = Duration.ofMinutes(1)

        private const val READ_TIMEOUT_MS = 10_000L

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

        private const val CHAPTER_ENTRY_SQL = "SELECT id FROM chronicle WHERE level = 'chapter' AND conversation_id = ? AND superseded_by IS NULL"

        private const val COUNTS_SQL = "SELECT (SELECT count(*) FROM memory_log WHERE run_id = ?)::INTEGER, " +
            "(SELECT count(*) FROM tool_calls WHERE list_contains(?, conversation_id) AND status = 'invalid')::INTEGER"
    }
}
