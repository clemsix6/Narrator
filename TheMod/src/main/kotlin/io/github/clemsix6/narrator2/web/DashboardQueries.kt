package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.trace.ModelTokens
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.trace.TurnSummary
import io.github.clemsix6.narrator2.trace.modelTokens
import io.github.clemsix6.narrator2.trace.turnSummary
import io.github.clemsix6.narrator2.web.pages.Where
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The reads of the dashboard's trace pages (M6 §8.4, §8.5): the Director's turns, the conversations, the tasks and the
 * engine's decisions, a page at a time and filtered; the calls and tokens behind the costs, summed by the database. The
 * details of one turn or one task come from [TraceQueries], as for `/narrator trace`, and so do the columns of a turn in
 * the list ([TraceQueries.TURN_SUMMARY]) and the calls by role ([TraceQueries.usage]).
 *
 * Runs on the dashboard's read connection ([PageContext.read]), one page's reads at a time; every statement stops after
 * [READ_TIMEOUT_MS] ([rows]). A list reads one row more than a page ([Paging.limit]); a sum covers a bounded period.
 * Read-only, and no statement names `private_messages` ([STATEMENTS], checked by a test): the chat shown comes from
 * `chat`, which never holds a private message.
 */
class DashboardQueries(private val connection: Connection) {

    /** A page of the Director's turns matching [filter], the newest first. */
    fun turns(filter: TurnFilter, paging: Paging): List<TurnSummary> {
        val where = Where()
        filter.trigger?.let { where.add(TURN_TRIGGER, it) }
        when (filter.outcome) {
            null -> Unit
            TurnFilter.RUNNING -> where.add(TURN_RUNNING)
            else -> where.add(TURN_OUTCOME, filter.outcome)
        }
        filter.chapter?.let { where.add(TURN_CHAPTER, it) }
        return connection.rows("${TraceQueries.TURN_SUMMARY} WHERE $where ORDER BY t.turn_id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) {
            turnSummary()
        }
    }

    /** A page of the conversations with Claude of [role] (all when null): chapters, tasks, memory jobs; the latest first. */
    fun conversations(role: String?, paging: Paging): List<ConversationRow> {
        val where = Where()
        role?.let { where.add(CONVERSATION_ROLE, it) }
        return connection.rows("$CONVERSATIONS WHERE $where ORDER BY c.started_at DESC, c.conversation_id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) {
            conversationRow()
        }
    }

    /** The conversation [conversationId]; null when there is none. */
    fun conversation(conversationId: String): ConversationRow? =
        connection.rows("$CONVERSATIONS WHERE c.conversation_id = ?", conversationId) { conversationRow() }.firstOrNull()

    /** What the calls of [conversationId] used, by the model that answered. */
    fun conversationTokens(conversationId: String): List<ModelTokens> =
        connection.rows(CONVERSATION_TOKENS, conversationId) { modelTokens(1) }

    /** A page of the workers' tasks of [role] and [status] (any when null), the latest first. */
    fun tasks(role: String?, status: String?, paging: Paging): List<TaskRow> {
        val where = Where()
        role?.let { where.add(TASK_ROLE, it) }
        status?.let { where.add(TASK_STATUS, it) }
        return connection.rows("$TASKS WHERE $where ORDER BY t.task_id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) {
            TaskRow(
                taskId = getLong(1), role = getString(2), brief = getString(3), status = getString(4), createdAt = instant(5)!!,
                startedAt = instant(6), finishedAt = instant(7), requestedByTurn = long(8), conversationId = getString(9),
                error = getString(10), calls = getLong(11),
            )
        }
    }

    /** A page of the engine's decisions matching [filter], the latest first. */
    fun decisions(filter: DecisionFilter, paging: Paging): List<DecisionRow> {
        val where = Where()
        filter.kind?.let { where.add(DECISION_KIND, it) }
        filter.decision?.let { where.add(DECISION_DECISION, it) }
        filter.subject?.let { where.add(DECISION_SUBJECT, it) }
        filter.since?.let { where.add(DECISION_SINCE, it) }
        filter.turn?.let { where.add(DECISION_TURN, it) }
        filter.module?.let { where.add(DECISION_MODULE, it, "$it v") }
        return connection.rows("$DECISIONS WHERE $where ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) {
            DecisionRow(
                id = getLong(1), ts = instant(2)!!, kind = getString(3), decision = getString(4), subject = getString(5),
                reason = getString(6), eventId = long(7), conversationId = getString(8), turnId = long(9), detail = getString(10),
            )
        }
    }

    /** The model calls since [since] (epoch milliseconds), by role, purpose and the model that answered, with their latencies. */
    fun callsByKind(since: Long): List<KindCalls> = connection.rows(CALLS_BY_KIND, since) {
        KindCalls(role = getString(1), purpose = getString(2), tokens = modelTokens(3), p50Ms = getLong(10), p95Ms = getLong(11))
    }

    /** The model calls since [since] (epoch milliseconds), by hour ([HourCalls.hour], hours since the epoch) and by model. */
    fun callsByHour(since: Long): List<HourCalls> = connection.rows(CALLS_BY_HOUR, since) { HourCalls(getLong(1), modelTokens(2)) }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────────

    private fun ResultSet.conversationRow() = ConversationRow(
        conversationId = getString(1), role = getString(2), label = getString(3), chapter = int(4), taskId = long(5),
        startedAt = instant(6)!!, endedAt = instant(7), endReason = getString(8), summary = getString(9), transcript = getString(10),
        calls = getLong(11), turns = getLong(12),
    )

    private fun ResultSet.instant(column: Int): Instant? = when (val value = getObject(column)) {
        null -> null
        is OffsetDateTime -> value.toInstant()
        is Timestamp -> value.toInstant()
        is Instant -> value
        else -> error("column $column is not a timestamp: ${value.javaClass}")
    }

    private fun ResultSet.long(column: Int): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.int(column: Int): Int? = getInt(column).takeUnless { wasNull() }

    internal companion object {
        /** The model's columns, after a model column ([modelTokens]): calls, failed, input, cache read, cache write, output. */
        private const val TOKENS = TraceQueries.TOKEN_SUMS

        /** Since a time in epoch milliseconds, the parameter. */
        private const val SINCE = "to_timestamp(CAST(? AS DOUBLE) / 1000)"

        /** The conditions of the list of turns, after the columns of `/narrator trace` ([TraceQueries.TURN_SUMMARY]). */
        private const val TURN_TRIGGER = "t.trigger = ?"
        private const val TURN_RUNNING = "t.outcome IS NULL"
        private const val TURN_OUTCOME = "t.outcome = ?"
        private const val TURN_CHAPTER = "c.chapter = ?"

        private val CONVERSATIONS = """
            SELECT c.conversation_id, c.role, c.label, c.chapter, c.task_id, c.started_at, c.ended_at, c.end_reason, c.summary, c.transcript,
                   (SELECT count(*) FROM llm_calls l WHERE l.conversation_id = c.conversation_id),
                   (SELECT count(*) FROM turns t WHERE t.conversation_id = c.conversation_id)
            FROM conversations c
        """.trimIndent()
        private const val CONVERSATION_ROLE = "c.role = ?"
        private const val CONVERSATION_TOKENS = "SELECT model, $TOKENS FROM llm_calls WHERE conversation_id = ? GROUP BY model ORDER BY model"

        private val TASKS = """
            SELECT t.task_id, t.role, t.brief, t.status, t.created_at, t.started_at, t.finished_at, t.requested_by_turn,
                   t.conversation_id, t.error,
                   (SELECT count(*) FROM llm_calls l WHERE l.conversation_id = t.conversation_id)
            FROM tasks t
        """.trimIndent()
        private const val TASK_ROLE = "t.role = ?"
        private const val TASK_STATUS = "t.status = ?"

        private const val DECISIONS =
            "SELECT id, ts, kind, decision, subject, reason, event_id, conversation_id, turn_id, CAST(detail AS VARCHAR) FROM engine_decisions"
        private const val DECISION_KIND = "kind = ?"
        private const val DECISION_DECISION = "decision = ?"
        private const val DECISION_SUBJECT = "contains(lower(subject), lower(?))"
        private const val DECISION_SINCE = "ts >= $SINCE"
        private const val DECISION_TURN = "turn_id = ?"
        private const val DECISION_MODULE = "(subject = ? OR starts_with(subject, ?))"

        private const val CALLS_BY_KIND = """
            SELECT role, purpose, model, $TOKENS,
                   CAST(quantile_cont(latency_ms, 0.5) AS BIGINT), CAST(quantile_cont(latency_ms, 0.95) AS BIGINT)
            FROM llm_calls WHERE started_at >= $SINCE
            GROUP BY role, purpose, model
        """

        private const val CALLS_BY_HOUR = """
            SELECT CAST(floor(epoch(started_at) / 3600) AS BIGINT), model, $TOKENS
            FROM llm_calls WHERE started_at >= $SINCE
            GROUP BY 1, 2
        """

        /** Every statement and condition of this file, for the test that none names a private table. */
        val STATEMENTS: List<String> = listOf(
            TraceQueries.TURN_SUMMARY, TURN_TRIGGER, TURN_RUNNING, TURN_OUTCOME, TURN_CHAPTER,
            CONVERSATIONS, CONVERSATION_ROLE, CONVERSATION_TOKENS,
            TASKS, TASK_ROLE, TASK_STATUS,
            DECISIONS, DECISION_KIND, DECISION_DECISION, DECISION_SUBJECT, DECISION_SINCE, DECISION_TURN, DECISION_MODULE,
            CALLS_BY_KIND, CALLS_BY_HOUR,
        )
    }
}

/** The filters of `/turns`: [trigger], [outcome] ([RUNNING] for a turn not ended) and [chapter]; null for any. */
data class TurnFilter(val trigger: String? = null, val outcome: String? = null, val chapter: Int? = null) {
    companion object {
        /** The outcome of a turn still running: it has none yet. */
        const val RUNNING = "running"
    }
}

/**
 * The filters of `/decisions`: [kind], [decision], words of the [subject] (ignoring case), [since] (epoch milliseconds),
 * [turn]; and, for a module's page of the Forge, [module]: the decisions about it, whose subject is `name` or `name vN`.
 * Null for any.
 */
data class DecisionFilter(
    val kind: String? = null,
    val decision: String? = null,
    val subject: String? = null,
    val since: Long? = null,
    val turn: Long? = null,
    val module: String? = null,
)

/**
 * A conversation with Claude (row of `conversations`), with its [calls] and the Director [turns] it held.
 *
 * @property transcript where its Markdown transcript is, relative to `world/narrator2/`; the `.jsonl` is next to it.
 */
data class ConversationRow(
    val conversationId: String,
    val role: String,
    val label: String,
    val chapter: Int?,
    val taskId: Long?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val endReason: String?,
    val summary: String?,
    val transcript: String?,
    val calls: Long,
    val turns: Long,
)

/** A worker's task in a list (row of `tasks`), with the requests of its conversation. */
data class TaskRow(
    val taskId: Long,
    val role: String,
    val brief: String,
    val status: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val requestedByTurn: Long?,
    val conversationId: String?,
    val error: String?,
    val calls: Long,
)

/** A decision of the engine (row of `engine_decisions`), as every page shows it; [detail] is its JSON text. */
data class DecisionRow(
    val id: Long,
    val ts: Instant,
    val kind: String,
    val decision: String,
    val subject: String?,
    val reason: String?,
    val eventId: Long?,
    val conversationId: String?,
    val turnId: Long?,
    val detail: String?,
)

/** The calls of one role and purpose to one model over a period, and their latencies (median and 95th percentile). */
data class KindCalls(val role: String, val purpose: String, val tokens: ModelTokens, val p50Ms: Long, val p95Ms: Long)

/** The calls to one model within one [hour], counted in hours since the epoch. */
data class HourCalls(val hour: Long, val tokens: ModelTokens)
