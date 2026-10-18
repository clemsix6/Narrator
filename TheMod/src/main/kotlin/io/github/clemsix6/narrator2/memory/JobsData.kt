package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A memory as the cues need it (M3 §10.5): its date, its text, whom it concerns, where it happened. */
data class CueMemory(
    val ref: Ref,
    val occurredFrom: Instant,
    val content: String,
    val players: List<UUID>,
    val importance: Int,
    val place: Pos?,
)

/**
 * What the memory jobs read and write in the Journal besides the store (M3 §6.6, §7.1, §8.1, §10.5,
 * §18): the `memory_runs` rows, the chapters waiting for their nap, the Narrator's activity in a window,
 * the canon come due, the memories the cues look for. [DuckJobsData] in production; a fake in tests.
 */
interface JobsData {
    /** Rows left `running` by a crash become `interrupted`; how many. */
    suspend fun interruptRunning(now: Instant): Int

    /** Inserts [run] as it is (a running job, or a code job already finished). */
    suspend fun insertRun(run: MemoryRun)

    /** Writes how [run] ended; its tokens are summed from the `llm_calls` of its conversations. */
    suspend fun finishRun(run: MemoryRun)

    /** The latest run of [kind] (any status when [statuses] is empty), of the Director's [chapter] when given. */
    suspend fun lastRun(kind: RunKind, statuses: Set<RunStatus> = emptySet(), chapter: String? = null): MemoryRun?

    /** When the first nap that ended `done` after [after] (ever when null) ended; null when there is none. */
    suspend fun firstNapDone(after: Instant?): Instant?

    /**
     * The Director's chapters closed for good without a `done` or `skipped` nap, and whose nap failed fewer
     * than [maxFailures] times (a 402 does not count), oldest first, [limit] at most.
     */
    suspend fun chaptersToNap(maxFailures: Int, limit: Int): List<ConversationRecord>

    /** The `conversations` row of [conversationId]; null when it is not written (yet). */
    suspend fun chapter(conversationId: String): ConversationRecord?

    /** Whether the Narrator said a line, or the Director of [chapter] called a tool that is not a read, in `]from, to]`. */
    suspend fun narratorActive(chapter: String, from: Instant, to: Instant): Boolean

    /** The `active` canon due at [now] or before and not signaled yet, by due time. */
    suspend fun dueCanon(now: Instant): List<Ref>

    /** Sets `canon.due_signaled_at`: [ref] is never signaled again. */
    suspend fun markDueSignaled(ref: Ref, at: Instant)

    /** The memories not archived, of importance [minImportance] or more, with a place, that happened before [before]. */
    suspend fun placedMemories(minImportance: Int, before: Instant): List<CueMemory>

    /** The memories not archived, of importance [minImportance] or more, that happened on one of [days] in [zone]. */
    suspend fun memoriesOn(days: List<LocalDate>, zone: ZoneId, minImportance: Int): List<CueMemory>

    /** The `memory` decisions `rejected` and `voice_warning` recorded since [since]. */
    suspend fun refusals(since: Instant): Refusals
}

/** Counts of the store's refusals and voice warnings (`/narrator memory status`). */
data class Refusals(val rejected: Int, val voiceWarnings: Int)

/** [JobsData] on the Journal: reads on its read connections, writes as `Journal.write` jobs. */
class DuckJobsData(private val journal: Journal) : JobsData {
    override suspend fun interruptRunning(now: Instant): Int = journal.write(WRITE_TIMEOUT_MS) { c ->
        c.update(
            "UPDATE memory_runs SET status = ?, ended_at = ?, error = coalesce(error, ?) WHERE status = ?",
            RunStatus.INTERRUPTED.id, now, "the server stopped without closing the run", RunStatus.RUNNING.id,
        )
    }

    override suspend fun insertRun(run: MemoryRun) {
        journal.write(WRITE_TIMEOUT_MS) { c ->
            c.prepareStatement(
                "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, " +
                    "conversations, writes, rejected, input_tokens, output_tokens, report, error) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                bind(
                    statement, c, run.runId, run.kind.id, run.trigger.id, run.status.id, run.startedAt, run.endedAt, run.windowFrom,
                    run.windowTo, run.chapter, run.conversations, run.writes, run.rejected, run.inputTokens, run.outputTokens,
                    run.report, run.error,
                )
                statement.executeUpdate()
            }
        }
    }

    override suspend fun finishRun(run: MemoryRun) {
        journal.barrier() // the llm_calls of its conversations
        journal.write(WRITE_TIMEOUT_MS) { c ->
            c.prepareStatement(
                "UPDATE memory_runs SET status = ?, ended_at = ?, window_from = ?, window_to = ?, conversations = ?, writes = ?, " +
                    "rejected = ?, report = ?, error = ? WHERE run_id = ?",
            ).use { statement ->
                bind(
                    statement, c, run.status.id, run.endedAt, run.windowFrom, run.windowTo, run.conversations, run.writes, run.rejected,
                    run.report, run.error, run.runId,
                )
                statement.executeUpdate()
            }
            if (run.conversations.isNotEmpty()) {
                c.update(
                    "UPDATE memory_runs SET " +
                        "input_tokens = (SELECT sum(coalesce(input_tokens, 0) + coalesce(cache_read_tokens, 0) + coalesce(cache_write_tokens, 0)) " +
                        "FROM llm_calls WHERE list_contains(memory_runs.conversations, conversation_id)), " +
                        "output_tokens = (SELECT sum(output_tokens) FROM llm_calls WHERE list_contains(memory_runs.conversations, conversation_id)) " +
                        "WHERE run_id = ?",
                    run.runId,
                )
            }
        }
    }

    override suspend fun lastRun(kind: RunKind, statuses: Set<RunStatus>, chapter: String?): MemoryRun? = journal.read(READ_TIMEOUT_MS) { c ->
        val status = if (statuses.isEmpty()) "" else " AND status IN (${statuses.joinToString { "'${it.id}'" }})"
        val ofChapter = if (chapter == null) "" else " AND chapter = ?"
        val parameters = listOfNotNull(kind.id, chapter).toTypedArray()
        c.rows("SELECT $RUN_COLUMNS FROM memory_runs WHERE kind = ?$status$ofChapter ORDER BY started_at DESC, run_id DESC LIMIT 1", *parameters) {
            run()
        }.firstOrNull()
    }

    override suspend fun firstNapDone(after: Instant?): Instant? = journal.read(READ_TIMEOUT_MS) { c ->
        c.rows(
            "SELECT min(ended_at) FROM memory_runs WHERE kind = ? AND status = ? AND ended_at > ?",
            RunKind.NAP.id, RunStatus.DONE.id, after ?: Instant.EPOCH,
        ) { instant(1) }.firstOrNull()
    }

    override suspend fun chaptersToNap(maxFailures: Int, limit: Int): List<ConversationRecord> {
        journal.barrier()
        return journal.read(READ_TIMEOUT_MS) { c ->
            c.rows(
                """
                SELECT $CONVERSATION_COLUMNS FROM conversations c
                WHERE c.role = 'director' AND c.ended_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM memory_runs r WHERE r.kind = 'nap' AND r.chapter = c.conversation_id
                                  AND r.status IN ('done', 'skipped', 'running'))
                  AND (SELECT count(*) FROM memory_runs r WHERE r.kind = 'nap' AND r.chapter = c.conversation_id
                       AND r.status = 'failed' AND coalesce(r.error, '') NOT LIKE '$BUDGET_PREFIX%') < ?
                ORDER BY c.ended_at, c.conversation_id LIMIT $limit
                """.trimIndent(),
                maxFailures,
            ) { conversation() }
        }
    }

    override suspend fun chapter(conversationId: String): ConversationRecord? = journal.read(READ_TIMEOUT_MS) { c ->
        c.rows("SELECT $CONVERSATION_COLUMNS FROM conversations c WHERE c.conversation_id = ?", conversationId) { conversation() }.firstOrNull()
    }

    override suspend fun narratorActive(chapter: String, from: Instant, to: Instant): Boolean {
        journal.barrier()
        return journal.read(READ_TIMEOUT_MS) { c ->
            c.rows(
                "SELECT EXISTS (SELECT 1 FROM chat WHERE channel IN ('narrator', 'operator') AND ts > ? AND ts <= ?) " +
                    "OR EXISTS (SELECT 1 FROM tool_calls WHERE conversation_id = ? AND effect <> 'read' AND ts > ? AND ts <= ?)",
                from, to, chapter, from, to,
            ) { getBoolean(1) }.first()
        }
    }

    override suspend fun dueCanon(now: Instant): List<Ref> = journal.read(READ_TIMEOUT_MS) { c ->
        c.rows(
            "SELECT id FROM canon WHERE status = ? AND NOT archived AND due_at <= ? AND due_signaled_at IS NULL ORDER BY due_at, id",
            CanonStatus.ACTIVE.id, now,
        ) { Ref(RefKind.CANON, getLong(1)) }
    }

    override suspend fun markDueSignaled(ref: Ref, at: Instant) {
        journal.write(WRITE_TIMEOUT_MS) { c -> c.update("UPDATE canon SET due_signaled_at = ? WHERE id = ?", at, ref.id) }
    }

    override suspend fun placedMemories(minImportance: Int, before: Instant): List<CueMemory> = journal.read(READ_TIMEOUT_MS) { c ->
        c.rows(
            "SELECT $CUE_COLUMNS FROM memories WHERE NOT archived AND importance >= ? AND dim IS NOT NULL AND occurred_from <= ? " +
                "ORDER BY id LIMIT $MAX_PLACED",
            minImportance, before,
        ) { cue() }
    }

    override suspend fun memoriesOn(days: List<LocalDate>, zone: ZoneId, minImportance: Int): List<CueMemory> {
        if (days.isEmpty()) return emptyList()
        val ranges = days.map { it.atStartOfDay(zone).toInstant() to it.plusDays(1).atStartOfDay(zone).toInstant() }
        val within = ranges.joinToString(" OR ") { "(occurred_from >= ? AND occurred_from < ?)" }
        val parameters = listOf<Any?>(minImportance) + ranges.flatMap { listOf(it.first, it.second) }
        return journal.read(READ_TIMEOUT_MS) { c ->
            c.rows("SELECT $CUE_COLUMNS FROM memories WHERE NOT archived AND importance >= ? AND ($within) ORDER BY id", *parameters.toTypedArray()) {
                cue()
            }
        }
    }

    override suspend fun refusals(since: Instant): Refusals = journal.read(READ_TIMEOUT_MS) { c ->
        c.rows(
            "SELECT count(*) FILTER (WHERE decision = 'rejected'), count(*) FILTER (WHERE decision = 'voice_warning') " +
                "FROM engine_decisions WHERE kind = 'memory' AND ts >= ?",
            since,
        ) { Refusals(getInt(1), getInt(2)) }.first()
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────────

    private fun ResultSet.run() = MemoryRun(
        runId = getLong(1),
        kind = enumOfId<RunKind>(getString(2)) ?: throw SQLException("unknown run kind ${getString(2)}"),
        trigger = enumOfId<RunTrigger>(getString(3)) ?: throw SQLException("unknown run trigger ${getString(3)}"),
        status = enumOfId<RunStatus>(getString(4)) ?: throw SQLException("unknown run status ${getString(4)}"),
        startedAt = instant(5)!!,
        endedAt = instant(6),
        windowFrom = instant(7),
        windowTo = instant(8),
        chapter = getString(9),
        conversations = strings(getArray(10)),
        writes = getInt(11),
        rejected = getInt(12),
        inputTokens = getObject(13)?.let { (it as Number).toLong() },
        outputTokens = getObject(14)?.let { (it as Number).toLong() },
        report = getString(15),
        error = getString(16),
    )

    private fun ResultSet.conversation() = ConversationRecord(
        conversationId = getString(1),
        role = Role.of(getString(2)) ?: throw SQLException("unknown role ${getString(2)}"),
        label = getString(3),
        chapter = getObject(4)?.let { (it as Number).toInt() },
        taskId = getObject(5)?.let { (it as Number).toLong() },
        startedAt = instant(6)!!,
        endedAt = instant(7),
        endReason = getString(8)?.let { id -> ConversationRecord.EndReason.entries.firstOrNull { it.id == id } },
        summary = getString(9),
        transcript = getString(10),
    )

    private fun ResultSet.cue() = CueMemory(
        ref = Ref(RefKind.MEMORY, getLong(1)),
        occurredFrom = instant(2)!!,
        content = getString(3),
        players = (getArray(4)?.array as? Array<*>).orEmpty().map { it as? UUID ?: UUID.fromString(it.toString()) },
        importance = getInt(5),
        place = getString(6)?.let { Pos(it, getInt(7), getInt(8), getInt(9)) },
    )

    private fun strings(array: java.sql.Array?): List<String> = (array?.array as? Array<*>).orEmpty().map { it.toString() }

    private fun ResultSet.instant(column: Int): Instant? = when (val value = getObject(column)) {
        null -> null
        is OffsetDateTime -> value.toInstant()
        is Timestamp -> value.toInstant()
        else -> throw SQLException("column $column is not a timestamp: ${value.javaClass.name}")
    }

    private fun <T> Connection.rows(sql: String, vararg parameters: Any?, row: ResultSet.() -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_S
            bind(statement, this, *parameters)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
        }

    private fun Connection.update(sql: String, vararg parameters: Any?): Int = prepareStatement(sql).use { statement ->
        bind(statement, this, *parameters)
        statement.executeUpdate()
    }

    private fun bind(statement: PreparedStatement, connection: Connection, vararg parameters: Any?) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                is Instant -> statement.setObject(index + 1, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                is List<*> -> statement.setArray(index + 1, connection.createArrayOf("VARCHAR", value.map { it.toString() }.toTypedArray()))
                else -> statement.setObject(index + 1, value)
            }
        }
    }

    companion object {
        /** How a run stopped by the gateway's 402 starts its `error` (M3 §7.1): such a failure is not counted against a nap. */
        const val BUDGET_PREFIX = "budget:"

        private const val RUN_COLUMNS = "run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, " +
            "conversations, writes, rejected, input_tokens, output_tokens, report, error"
        private const val CONVERSATION_COLUMNS =
            "c.conversation_id, c.role, c.label, c.chapter, c.task_id, c.started_at, c.ended_at, c.end_reason, c.summary, c.transcript"
        private const val CUE_COLUMNS = "id, occurred_from, content, players, importance, dim, x, y, z"

        /** Placed memories the cue grid holds at most. */
        private const val MAX_PLACED = 20_000
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L
        private const val QUERY_TIMEOUT_S = 20
    }
}
