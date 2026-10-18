package io.github.clemsix6.narrator2.memory

import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * The tables whose changes `memory_log` keeps, with their key columns: `row_key` is the key's values
 * joined by `:` (`812`, `b1f…:boldness`, `grimoire:12`). [ref] is the kind of citable row it holds.
 */
internal enum class Audited(val table: String, val keys: List<String>, val ref: RefKind? = null) {
    MEMORIES("memories", listOf("id"), RefKind.MEMORY),
    CANON("canon", listOf("id"), RefKind.CANON),
    INTERACTIONS("interactions", listOf("id"), RefKind.INTERACTION),
    CHRONICLE("chronicle", listOf("id"), RefKind.CHRONICLE),
    NOTES("notes", listOf("id"), RefKind.NOTE),
    TRAITS("player_traits", listOf("player_uuid", "trait")),
    SKILLS("player_skills", listOf("player_uuid", "skill")),
    RELATIONS("player_relations", listOf("player_uuid", "other_uuid")),
    DOCS("memory_docs", listOf("key", "version")),
    ;

    companion object {
        fun of(kind: RefKind): Audited = entries.first { it.ref == kind }

        fun of(table: String): Audited? = entries.firstOrNull { it.table == table }
    }
}

/**
 * The audit of the memory (M3 §2.3, §4.2): one `memory_log` row per change, with the row's columns
 * before and after it (JSON, [snapshot]), the author and its job; and [revert], which undoes a job from it.
 */
internal object MemoryLog {
    /** Appends one change. [before] null for an insert; [after] null when the row is gone (a reverted insert). */
    fun append(
        connection: Connection,
        at: Instant,
        author: Author,
        table: Audited,
        rowKey: String,
        op: LogOp,
        before: String?,
        after: String?,
        reason: String? = null,
        runId: Long? = author.runId,
    ) {
        connection.update(
            "INSERT INTO memory_log VALUES (?, ?, ?, ?, ?, ?, ?, ?::JSON, ?::JSON, ?)",
            at, author.actor, runId, author.turnId, table.table, rowKey, op.id, before, after, reason,
        )
    }

    /** The changes of [ref]'s row, oldest first. */
    fun of(connection: Connection, ref: Ref): List<MemoryLogRow> = connection.query(
        "SELECT ts, actor, run_id, turn_id, table_name, row_key, op, before::VARCHAR, after::VARCHAR, reason FROM memory_log " +
            "WHERE table_name = ? AND row_key = ? ORDER BY rowid",
        ref.kind.table, ref.id.toString(),
    ) {
        MemoryLogRow(
            ts = instant(1), actor = getString(2), runId = longOrNull(3), turnId = longOrNull(4), table = getString(5),
            rowKey = getString(6), op = enumOfId<LogOp>(getString(7)) ?: error("unknown log op ${getString(7)}"),
            before = jsonObjectOf(getString(8)), after = jsonObjectOf(getString(9)), reason = getString(10),
        )
    }

    /** A change of a job, in log order ([seq]: its `rowid`). */
    private class Change(val seq: Long, val table: Audited, val rowKey: String, val op: LogOp, val before: String?)

    /**
     * One row a job touched: its first change in the job (what to go back to), its last one, and how many
     * changes the job made to it.
     */
    private class Touched(val table: Audited, val rowKey: String, val firstOp: LogOp, val before: String?, val lastSeq: Long, val writes: Int)

    /** A row the job changed and that changed again afterwards. */
    class Conflict(val table: String, val rowKey: String, val actor: String, val at: Instant)

    /** What [revert] did: the rows put back and the writes undone; or the later changes that prevent it; or [already] reverted. */
    class Reverted(val rows: List<Pair<Audited, String>>, val writes: Int, val conflicts: List<Conflict>, val already: Boolean = false)

    /**
     * Undoes every change of the job [runId] (M3 §14), the most recent row first: a row the job inserted is
     * deleted (its content stays in `memory_log`), a row it changed gets back the columns it had before
     * the job's first change. Each row put back gets a `revert` row (`run_id` = [runId]: a second revert
     * finds it and stops) and is re-indexed. Nothing is done when a row the job touched was changed
     * since by someone else: the conflicts are returned instead.
     */
    fun revert(connection: Connection, runId: Long, author: Author, at: Instant, indexer: Indexer): Reverted {
        val reverted = connection.query("SELECT count(*) FROM memory_log WHERE run_id = ? AND op = 'revert'", runId) { getLong(1) }.single()
        if (reverted > 0) return Reverted(emptyList(), 0, emptyList(), already = true)
        val changes = connection.query(
            "SELECT rowid, table_name, row_key, op, before::VARCHAR FROM memory_log WHERE run_id = ? ORDER BY rowid",
            runId,
        ) {
            Audited.of(getString(2))?.let { table ->
                Change(getLong(1), table, getString(3), enumOfId<LogOp>(getString(4)) ?: error("unknown log op ${getString(4)}"), getString(5))
            }
        }.filterNotNull()
        val touched = LinkedHashMap<Pair<Audited, String>, Touched>()
        for (change in changes) {
            val key = change.table to change.rowKey
            val first = touched[key]
            touched[key] = if (first == null) {
                Touched(change.table, change.rowKey, change.op, change.before, change.seq, 1)
            } else {
                Touched(first.table, first.rowKey, first.firstOp, first.before, change.seq, first.writes + 1)
            }
        }
        val conflicts = touched.values.flatMap { row ->
            connection.query(
                "SELECT actor, ts FROM memory_log WHERE table_name = ? AND row_key = ? AND rowid > ? AND (run_id IS NULL OR run_id <> ?) ORDER BY rowid",
                row.table.table, row.rowKey, row.lastSeq, runId,
            ) { Conflict(row.table.table, row.rowKey, getString(1), instant(2)) }
        }
        if (conflicts.isNotEmpty()) return Reverted(emptyList(), 0, conflicts)
        val done = touched.values.sortedByDescending { it.lastSeq }.map { row ->
            putBack(connection, row, author, at, runId, indexer)
            row.table to row.rowKey
        }
        return Reverted(done, touched.values.sumOf { it.writes }, emptyList())
    }

    private fun putBack(connection: Connection, row: Touched, author: Author, at: Instant, runId: Long, indexer: Indexer) {
        val where = row.table.keys.joinToString(" AND ") { "$it = ?" }
        val current = jsonObjectOf(lastAfter(connection, row)) ?: error("${row.table.table} ${row.rowKey} has no recorded state")
        val keyValues = row.table.keys.map { key -> (current[key] as JsonPrimitive).content }
        val typed = row.table.keys.zip(keyValues).map { (key, value) -> typedKey(connection, row.table, key, value) }
        val now = connection.snapshot(row.table.table, where, *typed.toTypedArray())
        val ref = row.table.ref?.let { Ref(it, keyValues.single().toLong()) }
        if (row.firstOp == LogOp.INSERT || row.before == null) {
            connection.update("DELETE FROM ${row.table.table} WHERE $where", *typed.toTypedArray())
            if (ref != null && ref.kind in RefKind.searchable) indexer.remove(connection, ref)
            append(connection, at, author, row.table, row.rowKey, LogOp.REVERT, now, null, "revert of run $runId", runId)
            return
        }
        connection.update(
            "INSERT OR REPLACE INTO ${row.table.table} BY NAME SELECT unnest(from_json(?::JSON, '${structure(connection, row.table)}'))",
            row.before,
        )
        if (ref != null && ref.kind in RefKind.searchable) {
            val restored = connection.readRow(ref) ?: error("$ref vanished while it was put back")
            indexer.remove(connection, ref)
            indexText(restored)?.let { indexer.index(connection, ref, it) }
        }
        val after = connection.snapshot(row.table.table, where, *typed.toTypedArray())
        append(connection, at, author, row.table, row.rowKey, LogOp.REVERT, now, after, "revert of run $runId", runId)
    }

    /** The row as the log last recorded it (the `after` of its latest change, else the `before`): where its key is. */
    private fun lastAfter(connection: Connection, row: Touched): String? = connection.query(
        "SELECT coalesce(after, before)::VARCHAR FROM memory_log WHERE table_name = ? AND row_key = ? ORDER BY rowid DESC LIMIT 1",
        row.table.table, row.rowKey,
    ) { getString(1) }.singleOrNull()

    /** The key value [value] of column [column], typed for a `WHERE`. */
    private fun typedKey(connection: Connection, table: Audited, column: String, value: String): Any = when (columnType(connection, table, column)) {
        "BIGINT" -> value.toLong()
        "INTEGER" -> value.toInt()
        "UUID" -> UUID.fromString(value)
        else -> value
    }

    private fun columnType(connection: Connection, table: Audited, column: String): String = connection.query(
        "SELECT data_type FROM duckdb_columns() WHERE schema_name = 'main' AND table_name = ? AND column_name = ?",
        table.table, column,
    ) { getString(1) }.single()

    /** The `from_json` structure of [table]'s rows: `{"id":"BIGINT","players":"UUID[]",…}`, from the catalog. */
    private fun structure(connection: Connection, table: Audited): String = connection.query(
        "SELECT column_name, data_type FROM duckdb_columns() WHERE schema_name = 'main' AND table_name = ? ORDER BY column_index",
        table.table,
    ) { "\"${getString(1)}\":\"${getString(2)}\"" }.joinToString(",", "{", "}")
}
