package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.event.EventType
import java.sql.Connection
import java.time.ZoneId

/**
 * The description of the Journal given to the model: the body of the `sql` tool's description
 * (M1_M2 §6.5). It is generated from the database itself (tables, views, macros and their comments, in
 * `duckdb_tables()`, `duckdb_views()`, `duckdb_columns()`, `duckdb_functions()`) and from [EventCatalog]
 * for the event types. The tables of [Journal.HIDDEN_FROM_MODEL] never appear in it.
 *
 * It is part of the tools, hence of the prompt prefix the cache keeps: it must be the same bytes from
 * one conversation to the next. [text] is therefore generated once per JVM, from an in-memory database
 * built by the same scripts as the Journal ([DuckDatabase.MIGRATIONS] and [DuckDatabase.VIEWS]): the
 * same schema as the Journal's by construction, available even when the Journal is not, and never a
 * read on the Journal's connections.
 */
object SchemaDoc {
    /** Tables the model has no use for. */
    private val PLUMBING = setOf("schema_version")

    /**
     * The engine's traces, listed without their columns to keep the description within its budget
     * (2 500 tokens): the model rarely reads them, and `columns_of()` gives their columns.
     */
    private val TRACES = setOf("chat_signals", "conversations", "turns", "tasks", "llm_calls", "tool_calls", "engine_decisions")

    /** The description, generated on first use (about 100 ms: an in-memory DuckDB with the schema). */
    val text: String by lazy {
        DuckDatabase.openInMemory(DuckDatabase.Options(memoryLimitMb = 64, threads = 1, timeZone = ZoneId.of("UTC"), readers = 0))
            .use { generate(it.writer) }
    }

    private const val HEADER =
        "DuckDB SQL, read-only, one SELECT per call; results are capped: aggregate rather than list. " +
            "Times are TIMESTAMPTZ in server time: ts > now() - INTERVAL 2 HOUR; ago(ts) says how long ago. " +
            "Rows may share a ts: ORDER BY ts, id. " +
            "Players are matched by name, case-sensitive: player = 'Bartok' (macros ignore case). " +
            "Views and macros drop the minecraft: prefix (diamond_ore, overworld); tables keep it. " +
            "JSON: (detail ->> 'key') is text; keep the parentheses. " +
            "columns_of('<table>') lists columns with comments. The catalog (duckdb_*, information_schema, SHOW) is closed."

    private val EXAMPLES = listOf(
        "SELECT block, count(*) FROM mining WHERE player = 'Bartok' AND ore AND ts > now() - INTERVAL 1 DAY GROUP BY 1 ORDER BY 2 DESC",
        "SELECT * FROM hotspots('Mimosa', 'block_break', 32)",
        "SELECT victim, count(*) FROM kills WHERE player = 'Bartok' GROUP BY 1 ORDER BY 2 DESC",
        "SELECT ago(ts), channel, player, text FROM chat_log ORDER BY ts DESC, id DESC LIMIT 20",
    )

    /** The description of the schema [connection] sees. Deterministic: same schema, same text. */
    fun generate(connection: Connection): String {
        val columns = columns(connection)
        return buildString {
            append(HEADER).append('\n')
            append("VIEWS\n")
            relations(connection, "SELECT view_name, comment FROM duckdb_views() WHERE $MAIN AND NOT internal ORDER BY view_oid")
                .forEach { (name, comment) -> append(signature(name, columns)).append(describe(comment)).append('\n') }
            append("MACROS\n")
            macros(connection).forEach { append(it).append('\n') }
            append("TABLES (traces without columns: see columns_of)\n")
            relations(connection, "SELECT table_name, comment FROM duckdb_tables() WHERE $MAIN AND NOT internal ORDER BY table_oid")
                .filter { (name, _) -> name !in Journal.HIDDEN_FROM_MODEL && name !in PLUMBING }
                .forEach { (name, comment) ->
                    append(if (name in TRACES) name else signature(name, columns)).append(describe(comment)).append('\n')
                }
            append("EVENT TYPES (events.type: subject; amount; other; detail keys)\n")
            EventCatalog.types(EventTable.EVENTS).forEach { append(eventType(it)).append('\n') }
            append("EXAMPLES\n")
            EXAMPLES.forEach { append(it).append('\n') }
        }.trimEnd()
    }

    /** The examples of the description, which must run as they are (checked by `SchemaDocTest`). */
    internal val examples: List<String> get() = EXAMPLES

    private const val MAIN = "database_name = current_database() AND schema_name = 'main'"

    /** `name(col, col JSON, col[])`: types are only shown where they matter for writing a query. */
    private fun signature(name: String, columns: Map<String, List<Pair<String, String>>>): String =
        columns[name].orEmpty().joinToString(", ", "$name(", ")") { (column, type) ->
            when {
                type == "JSON" -> "$column JSON"
                type.endsWith("[]") -> "$column[]"
                else -> column
            }
        }

    private fun describe(comment: String?) = if (comment.isNullOrBlank()) "" else " — $comment"

    /** Name and comment of each relation of [sql], in the order of [sql]. */
    private fun relations(connection: Connection, sql: String): List<Pair<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
        }

    /** Relation name → its columns (name, type) in order. */
    private fun columns(connection: Connection): Map<String, List<Pair<String, String>>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT table_name, column_name, data_type FROM duckdb_columns() WHERE $MAIN ORDER BY table_oid, column_index",
            ).use { rows ->
                val byRelation = LinkedHashMap<String, MutableList<Pair<String, String>>>()
                while (rows.next()) byRelation.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getString(2) to rows.getString(3)
                byRelation
            }
        }

    /** `name(params) — comment` for each macro of the schema, scalar and table, in creation order. */
    private fun macros(connection: Connection): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT function_name, parameters, comment FROM duckdb_functions() " +
                "WHERE $MAIN AND NOT internal AND function_type IN ('macro', 'table_macro') ORDER BY function_oid",
        ).use { rows ->
            buildList {
                while (rows.next()) {
                    val parameters = (rows.getArray(2).array as Array<*>).joinToString(", ")
                    add("${rows.getString(1)}($parameters)${describe(rows.getString(3))}")
                }
            }
        }
    }

    /** `block_break: block; -; tool in the main hand; creative, correct_tool, placed_by`, trailing `-` left out. */
    private fun eventType(type: EventType): String {
        val parts = (listOf(type.subject, type.amount, type.other) + type.detail.keys.joinToString(", ").ifEmpty { null })
            .map { it ?: "-" }
            .dropLastWhile { it == "-" }
        return if (parts.isEmpty()) type.name else "${type.name}: ${parts.joinToString("; ")}"
    }
}
