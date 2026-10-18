package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/**
 * What the memory jobs read and write in the Journal besides the store (M3 §6.6, §7.1, §8.1, §18): the
 * `memory_runs` rows, the chapters waiting for their nap, the Narrator's activity in a window, the canon
 * come due, the refusals of the day, the `memory_state` keys done with. [DuckJobsData] in production; a
 * fake in tests.
 */
interface JobsData {
    /** Rows left `running` by a crash become `interrupted`; how many. */
    suspend fun interruptRunning(now: Instant): Int

    /** Inserts [run] as it is (a running job, or a code job already finished). */
    suspend fun insertRun(run: MemoryRun)

    /** Writes how [run] ended; its tokens are summed from the `llm_calls` of its conversations. */
    suspend fun finishRun(run: MemoryRun)

    /** The latest run of [kind], whatever its status. */
    suspend fun lastRun(kind: RunKind): MemoryRun?

    /**
     * Where the batches of the Director's [chapter] got to: the end of its latest window done, skipped or
     * passed (a failed run whose `error` starts with [DuckJobsData.PASSED_PREFIX]); null when there is none.
     */
    suspend fun batchEnd(chapter: String): Instant?

    /** Whether a batch of [chapter] other than [runId] failed over a window starting at [from] (a 402 does not count). */
    suspend fun batchFailedFrom(chapter: String, from: Instant, runId: Long): Boolean

    /** When the first nap that ended `done` after [after] (ever when null) ended; null when there is none. */
    suspend fun firstNapDone(after: Instant?): Instant?

    /**
     * The Director's chapters closed for good at [closedSince] or later without a `done` or `skipped` nap,
     * and whose nap failed fewer than [maxFailures] times (a 402 does not count), oldest first, [limit] at most.
     */
    suspend fun chaptersToNap(maxFailures: Int, limit: Int, closedSince: Instant): List<ConversationRecord>

    /** How many Director chapters closed before [closedBefore] have no `done` or `skipped` nap: the catch-up never naps them. */
    suspend fun chaptersNeverNapped(closedBefore: Instant): Int

    /** The `conversations` row of [conversationId]; null when it is not written (yet). */
    suspend fun chapter(conversationId: String): ConversationRecord?

    /** Whether the Narrator said a line, or the Director of [chapter] called a tool that is not a read, in `]from, to]`. */
    suspend fun narratorActive(chapter: String, from: Instant, to: Instant): Boolean

    /** The `active` canon due at [now] or before and not signaled yet, by due time. */
    suspend fun dueCanon(now: Instant): List<Ref>

    /** Sets `canon.due_signaled_at`: [ref] is never signaled again. */
    suspend fun markDueSignaled(ref: Ref, at: Instant)

    /** The `memory` decisions `rejected` and `voice_warning` recorded since [since]. */
    suspend fun refusals(since: Instant): Refusals

    /** Removes the `memory_state` row [key], done with (the step of a dream run once the dream is over). */
    suspend fun deleteState(key: String)
}

/** Counts of the store's refusals and voice warnings (`/narrator memory status`). */
data class Refusals(val rejected: Int, val voiceWarnings: Int)

/**
 * [JobsData] on the Journal: reads on its read connections, writes as `Journal.write` jobs (with the SQL
 * plumbing of the store, `MemoryRows.kt`). A read that must see facts just published (the Narrator's
 * lines, the tokens of a conversation that just ended) passes a `barrier()` first.
 *
 * `canon.due_signaled_at` is written here, not through [MemoryStore]: it is the engine's bookkeeping of a
 * signal, not a change of what the memory holds (no audit, like `sheet_stats`).
 */
class DuckJobsData(private val journal: Journal) : JobsData {
    override suspend fun interruptRunning(now: Instant): Int = journal.write(WRITE_TIMEOUT_MS) { c ->
        c.update(
            "UPDATE memory_runs SET status = ?, ended_at = ?, error = coalesce(error, ?) WHERE status = ?",
            RunStatus.INTERRUPTED.id, now, "the server stopped without closing the run", RunStatus.RUNNING.id,
        )
    }

    override suspend fun insertRun(run: MemoryRun) {
        journal.write(WRITE_TIMEOUT_MS) { c ->
            c.update(
                "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, " +
                    "conversations, writes, rejected, input_tokens, output_tokens, report, error) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.runId, run.kind.id, run.trigger.id, run.status.id, run.startedAt, run.endedAt, run.windowFrom, run.windowTo,
                run.chapter, stringArray(run.conversations), run.writes, run.rejected, run.inputTokens, run.outputTokens, run.report,
                run.error,
            )
        }
    }

    override suspend fun finishRun(run: MemoryRun) {
        journal.barrier() // the llm_calls of its conversations
        journal.write(WRITE_TIMEOUT_MS) { c ->
            c.update(
                "UPDATE memory_runs SET status = ?, ended_at = ?, window_from = ?, window_to = ?, conversations = ?, writes = ?, " +
                    "rejected = ?, report = ?, error = ? WHERE run_id = ?",
                run.status.id, run.endedAt, run.windowFrom, run.windowTo, stringArray(run.conversations), run.writes, run.rejected,
                run.report, run.error, run.runId,
            )
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

    override suspend fun lastRun(kind: RunKind): MemoryRun? = journal.read(READ_TIMEOUT_MS) { c ->
        c.query("SELECT $RUN_COLUMNS FROM memory_runs WHERE kind = ? ORDER BY started_at DESC, run_id DESC LIMIT 1", kind.id) { run() }.firstOrNull()
    }

    override suspend fun batchEnd(chapter: String): Instant? = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT max(window_to) FROM memory_runs WHERE kind = 'batch' AND chapter = ? " +
                "AND (status IN ('done', 'skipped') OR (status = 'failed' AND coalesce(error, '') LIKE '$PASSED_PREFIX%'))",
            chapter,
        ) { instantOrNull(1) }.firstOrNull()
    }

    override suspend fun batchFailedFrom(chapter: String, from: Instant, runId: Long): Boolean = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT EXISTS (SELECT 1 FROM memory_runs WHERE kind = 'batch' AND chapter = ? AND window_from = ? AND run_id <> ? " +
                "AND status = 'failed' AND coalesce(error, '') NOT LIKE '$BUDGET_PREFIX%')",
            chapter, from, runId,
        ) { getBoolean(1) }.first()
    }

    override suspend fun firstNapDone(after: Instant?): Instant? = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT min(ended_at) FROM memory_runs WHERE kind = ? AND status = ? AND ended_at > ?",
            RunKind.NAP.id, RunStatus.DONE.id, after ?: Instant.EPOCH,
        ) { instantOrNull(1) }.firstOrNull()
    }

    override suspend fun chaptersToNap(maxFailures: Int, limit: Int, closedSince: Instant): List<ConversationRecord> {
        journal.barrier()
        return journal.read(READ_TIMEOUT_MS) { c ->
            c.query(
                """
                SELECT $CONVERSATION_COLUMNS FROM conversations c
                WHERE c.role = 'director' AND c.ended_at >= ?
                  AND NOT EXISTS (SELECT 1 FROM memory_runs r WHERE r.kind = 'nap' AND r.chapter = c.conversation_id
                                  AND r.status IN ('done', 'skipped', 'running'))
                  AND (SELECT count(*) FROM memory_runs r WHERE r.kind = 'nap' AND r.chapter = c.conversation_id
                       AND r.status = 'failed' AND coalesce(r.error, '') NOT LIKE '$BUDGET_PREFIX%') < ?
                ORDER BY c.ended_at, c.conversation_id LIMIT $limit
                """.trimIndent(),
                closedSince, maxFailures,
            ) { conversation() }
        }
    }

    override suspend fun chaptersNeverNapped(closedBefore: Instant): Int = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT count(*)::INTEGER FROM conversations c WHERE c.role = 'director' AND c.ended_at < ? AND NOT EXISTS " +
                "(SELECT 1 FROM memory_runs r WHERE r.kind = 'nap' AND r.chapter = c.conversation_id AND r.status IN ('done', 'skipped'))",
            closedBefore,
        ) { getInt(1) }.first()
    }

    override suspend fun chapter(conversationId: String): ConversationRecord? = journal.read(READ_TIMEOUT_MS) { c ->
        c.query("SELECT $CONVERSATION_COLUMNS FROM conversations c WHERE c.conversation_id = ?", conversationId) { conversation() }.firstOrNull()
    }

    override suspend fun narratorActive(chapter: String, from: Instant, to: Instant): Boolean {
        journal.barrier()
        return journal.read(READ_TIMEOUT_MS) { c ->
            c.query(
                "SELECT EXISTS (SELECT 1 FROM chat WHERE channel IN ('narrator', 'operator') AND ts > ? AND ts <= ?) " +
                    "OR EXISTS (SELECT 1 FROM tool_calls WHERE conversation_id = ? AND effect <> 'read' AND ts > ? AND ts <= ?)",
                from, to, chapter, from, to,
            ) { getBoolean(1) }.first()
        }
    }

    override suspend fun dueCanon(now: Instant): List<Ref> = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT id FROM canon WHERE status = ? AND NOT archived AND due_at <= ? AND due_signaled_at IS NULL ORDER BY due_at, id",
            CanonStatus.ACTIVE.id, now,
        ) { Ref(RefKind.CANON, getLong(1)) }
    }

    override suspend fun markDueSignaled(ref: Ref, at: Instant) {
        journal.write(WRITE_TIMEOUT_MS) { c -> c.update("UPDATE canon SET due_signaled_at = ? WHERE id = ?", at, ref.id) }
    }

    override suspend fun refusals(since: Instant): Refusals = journal.read(READ_TIMEOUT_MS) { c ->
        c.query(
            "SELECT count(*) FILTER (WHERE decision = 'rejected'), count(*) FILTER (WHERE decision = 'voice_warning') " +
                "FROM engine_decisions WHERE kind = 'memory' AND ts >= ?",
            since,
        ) { Refusals(getInt(1), getInt(2)) }.first()
    }

    override suspend fun deleteState(key: String) {
        journal.write(WRITE_TIMEOUT_MS) { c -> c.update("DELETE FROM memory_state WHERE key = ?", key) }
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────────

    private fun ResultSet.run() = MemoryRun(
        runId = getLong(1),
        kind = enumOfId<RunKind>(getString(2)) ?: throw SQLException("unknown run kind ${getString(2)}"),
        trigger = enumOfId<RunTrigger>(getString(3)) ?: throw SQLException("unknown run trigger ${getString(3)}"),
        status = enumOfId<RunStatus>(getString(4)) ?: throw SQLException("unknown run status ${getString(4)}"),
        startedAt = instant(5),
        endedAt = instantOrNull(6),
        windowFrom = instantOrNull(7),
        windowTo = instantOrNull(8),
        chapter = getString(9),
        conversations = (getArray(10)?.array as? Array<*>).orEmpty().map { it.toString() },
        writes = getInt(11),
        rejected = getInt(12),
        inputTokens = longOrNull(13),
        outputTokens = longOrNull(14),
        report = getString(15),
        error = getString(16),
    )

    private fun ResultSet.conversation() = ConversationRecord(
        conversationId = getString(1),
        role = Role.of(getString(2)) ?: throw SQLException("unknown role ${getString(2)}"),
        label = getString(3),
        chapter = intOrNull(4),
        taskId = longOrNull(5),
        startedAt = instant(6),
        endedAt = instantOrNull(7),
        endReason = getString(8)?.let { id -> ConversationRecord.EndReason.entries.firstOrNull { it.id == id } },
        summary = getString(9),
        transcript = getString(10),
    )

    companion object {
        /** How a run stopped by the gateway's 402 starts its `error` (M3 §7.1): such a failure is not counted against a nap. */
        const val BUDGET_PREFIX = "budget:"

        /**
         * How a failed batch whose window is passed starts its `error`: it is the second batch of its chapter to
         * fail from that start, and the next one starts at its end ([batchEnd]).
         */
        const val PASSED_PREFIX = "passed:"

        private const val RUN_COLUMNS = "run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, " +
            "conversations, writes, rejected, input_tokens, output_tokens, report, error"
        private const val CONVERSATION_COLUMNS =
            "c.conversation_id, c.role, c.label, c.chapter, c.task_id, c.started_at, c.ended_at, c.end_reason, c.summary, c.transcript"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L
    }
}
