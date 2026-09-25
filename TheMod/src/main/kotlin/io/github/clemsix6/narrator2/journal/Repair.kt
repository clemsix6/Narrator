package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What [Repair.run] closed. */
internal data class RepairReport(val sessions: Int, val turns: Int, val conversations: Int, val tasks: Int) {
    val total: Int get() = sessions + turns + conversations + tasks

    override fun toString() = "$sessions sessions, $turns turns, $conversations conversations, $tasks tasks"
}

/**
 * Closes what a crash left open (M1_M2 §2.4), when the Journal opens and before its writer starts, in
 * one transaction:
 * - sessions without `left_at`: `recovered`, ended at the last fact of that session (else when it
 *   opened), their time added to the player's play time;
 * - turns without an outcome: `interrupted`, ended at their last model or tool call, calls counted;
 * - conversations without an end: `interrupted`, ended at their last turn or call;
 * - tasks still queued or running: `cancelled`, the reason in `error`.
 *
 * After a normal stop nothing is open: the writer closes the sessions (`server_stop`), and the
 * Director and the workers record the end of their turns and conversations while the Journal is open.
 */
internal object Repair {
    fun run(connection: Connection): RepairReport {
        connection.autoCommit = false
        try {
            val report = RepairReport(
                sessions = sessions(connection),
                turns = turns(connection),
                conversations = conversations(connection),
                tasks = tasks(connection),
            )
            connection.commit()
            return report
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun sessions(connection: Connection): Int {
        val open = connection.rows("SELECT session_id, player_uuid, joined_at FROM sessions WHERE left_at IS NULL") {
            Triple(getLong(1), getObject(2) as UUID, instant(3)!!)
        }
        open.forEach { (id, player, joinedAt) ->
            val last = connection.instant(
                "SELECT max(ts) FROM (SELECT ts FROM events WHERE session_id = ? UNION ALL SELECT ts FROM positions WHERE session_id = ?)",
                id, id,
            )
            val end = maxOf(last ?: joinedAt, joinedAt)
            val seconds = Duration.between(joinedAt, end).seconds
            connection.update(
                "UPDATE sessions SET left_at = ?, duration_s = ?, end_reason = ? WHERE session_id = ?",
                end, seconds.toInt(), JournalMapping.RECOVERED, id,
            )
            connection.update(
                "UPDATE players SET playtime_s = playtime_s + ?, last_seen = greatest(last_seen, ?) WHERE player_uuid = ?",
                seconds, end, player,
            )
        }
        return open.size
    }

    private fun turns(connection: Connection): Int {
        val open = connection.rows("SELECT turn_id, started_at FROM turns WHERE outcome IS NULL OR ended_at IS NULL") {
            getLong(1) to instant(2)!!
        }
        open.forEach { (id, startedAt) ->
            val (llmCalls, lastLlmCall) = connection.countAndLast("SELECT count(*), max(ended_at) FROM llm_calls WHERE turn_id = ?", id)
            val (toolCalls, lastToolCall) = connection.countAndLast("SELECT count(*), max(ts) FROM tool_calls WHERE turn_id = ?", id)
            val end = listOfNotNull(startedAt, lastLlmCall, lastToolCall).max()
            connection.update(
                "UPDATE turns SET ended_at = coalesce(ended_at, ?), outcome = coalesce(outcome, ?), " +
                    "llm_calls = coalesce(llm_calls, ?), tool_calls = coalesce(tool_calls, ?) WHERE turn_id = ?",
                end, TurnRecord.Outcome.INTERRUPTED.id, llmCalls, toolCalls, id,
            )
        }
        return open.size
    }

    private fun conversations(connection: Connection): Int {
        val open = connection.rows("SELECT conversation_id, started_at FROM conversations WHERE ended_at IS NULL") {
            getString(1) to instant(2)!!
        }
        open.forEach { (id, startedAt) ->
            val end = listOfNotNull(
                startedAt,
                connection.instant("SELECT max(coalesce(ended_at, started_at)) FROM turns WHERE conversation_id = ?", id),
                connection.instant("SELECT max(ended_at) FROM llm_calls WHERE conversation_id = ?", id),
                connection.instant("SELECT max(ts) FROM tool_calls WHERE conversation_id = ?", id),
            ).max()
            connection.update(
                "UPDATE conversations SET ended_at = ?, end_reason = coalesce(end_reason, ?) WHERE conversation_id = ?",
                end, ConversationRecord.EndReason.INTERRUPTED.id, id,
            )
        }
        return open.size
    }

    private fun tasks(connection: Connection): Int {
        val open = connection.rows(
            "SELECT t.task_id, t.status, coalesce(c.ended_at, t.started_at, t.created_at) FROM tasks t " +
                "LEFT JOIN conversations c ON c.conversation_id = t.conversation_id WHERE t.status IN (?, ?)",
            TaskRecord.Status.QUEUED.id, TaskRecord.Status.RUNNING.id,
        ) { Triple(getLong(1), getString(2), instant(3)!!) }
        open.forEach { (id, status, end) ->
            connection.update(
                "UPDATE tasks SET status = ?, finished_at = ?, error = coalesce(error, ?) WHERE task_id = ?",
                TaskRecord.Status.CANCELLED.id, end, "interrupted: the server stopped while the task was $status", id,
            )
        }
        return open.size
    }

    // ── JDBC helpers ────────────────────────────────────────────────────────────────────────────────

    private fun <T> Connection.rows(sql: String, vararg parameters: Any?, row: ResultSet.() -> T): List<T> =
        prepareStatement(sql).use { statement ->
            bind(statement, parameters)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
        }

    private fun Connection.instant(sql: String, vararg parameters: Any?): Instant? = rows(sql, *parameters) { instant(1) }.firstOrNull()

    private fun Connection.countAndLast(sql: String, vararg parameters: Any?): Pair<Int, Instant?> =
        rows(sql, *parameters) { getInt(1) to instant(2) }.first()

    private fun Connection.update(sql: String, vararg parameters: Any?) {
        prepareStatement(sql).use { statement ->
            bind(statement, parameters)
            statement.executeUpdate()
        }
    }

    private fun bind(statement: java.sql.PreparedStatement, parameters: Array<out Any?>) {
        parameters.forEachIndexed { index, value ->
            statement.setObject(index + 1, if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value)
        }
    }

    /** A TIMESTAMPTZ column as an instant. */
    internal fun ResultSet.instant(column: Int): Instant? = when (val value = getObject(column)) {
        null -> null
        is OffsetDateTime -> value.toInstant()
        is Timestamp -> value.toInstant()
        else -> throw SQLException("column $column is not a timestamp: ${value.javaClass.name}")
    }
}
