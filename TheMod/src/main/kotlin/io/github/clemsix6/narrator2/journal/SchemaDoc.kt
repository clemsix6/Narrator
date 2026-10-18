package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.memory.MemorySchema
import java.sql.Connection
import java.time.ZoneId

/**
 * The description of the Journal given to the model: the body of the `sql` tool's description
 * (M1_M2 §6.5, M3 §13). It is generated from the database itself (tables, views, macros and their
 * comments, in `duckdb_tables()`, `duckdb_views()`, `duckdb_columns()`, `duckdb_functions()`). The tables
 * of [Journal.HIDDEN_FROM_MODEL] never appear in it.
 *
 * It only says what it takes to write a query, within [MAX_CHARS]: reference data (the event types) is
 * a table the model queries ([EventTypesTable]), and each [Section] has its own budget. A new view must fit
 * in its section's budget, or replace another one.
 *
 * It is part of the tools, hence of the prompt prefix the cache keeps: it must be the same bytes from
 * one conversation to the next. [text] is therefore generated once per JVM, from an in-memory database
 * built by the same code as the Journal ([DuckDatabase.MIGRATIONS], [DuckDatabase.VIEWS] and
 * [EventTypesTable]): the same schema as the Journal's by construction, available even when the Journal
 * is not, and never a read on the Journal's connections.
 */
object SchemaDoc {
    /** The parts of the description, in order, each with its budget in characters (M3 §13), checked by `SchemaDocTest`. */
    enum class Section(val budget: Int) {
        HEADER(700),
        VIEWS(2_300),
        MACROS(900),
        TABLES(1_800),
        MEMORY(1_200),
        EXAMPLES(500),
    }

    /** The whole description's budget: 2 500 tokens, at a pessimistic 3 characters a token. */
    const val MAX_CHARS = 7_500

    /** Views of the memory (M3 §13), described in their own section. */
    private const val MEMORY_PREFIX = "mem_"

    /**
     * Tables left out of the description: `schema_version`, which the model has no use for, and the
     * memory's tables ([MemorySchema.TABLES]), still readable by `sql`: the model reads the memory through
     * its own views and tools (M3 §13).
     */
    private val PLUMBING = setOf("schema_version") + MemorySchema.TABLES

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

    private const val MEMORY =
        "MEMORY (what the Narrator remembers: ref as the memory tools take it, M-812, C-45…; players by current name; forgotten rows left out)"

    private val EXAMPLES = listOf(
        "SELECT block, count(*) FROM mining WHERE player = 'Bartok' AND ore AND ts > now() - INTERVAL 1 DAY GROUP BY 1 ORDER BY 2 DESC",
        "SELECT * FROM hotspots('Mimosa', 'block_break', 32)",
        "SELECT victim, count(*) FROM kills WHERE player = 'Bartok' GROUP BY 1 ORDER BY 2 DESC",
        "SELECT ago(ts), speaker, player, text FROM chat_log ORDER BY ts DESC, id DESC LIMIT 20",
        "SELECT ref, statement, due_at FROM mem_canon WHERE status = 'active' AND list_contains(players, 'Mimosa')",
    )

    /** The description of the schema [connection] sees. Deterministic: same schema, same text. */
    fun generate(connection: Connection): String = sections(connection).values.joinToString("\n")

    /** The description's sections, in order, each its lines joined by `\n`. */
    fun sections(connection: Connection): Map<Section, String> {
        val columns = columns(connection)
        val (memory, views) = relations(connection, "SELECT view_name, comment FROM duckdb_views() WHERE $MAIN AND NOT internal ORDER BY view_oid")
            .map { (name, comment) -> name to signature(name, columns) + describe(comment) }
            .partition { (name, _) -> name.startsWith(MEMORY_PREFIX) }
        val (types, tables) = relations(connection, "SELECT table_name, comment FROM duckdb_tables() WHERE $MAIN AND NOT internal ORDER BY table_oid")
            .filter { (name, _) -> name !in Journal.HIDDEN_FROM_MODEL && name !in PLUMBING }
            .map { (name, comment) -> name to (if (name in TRACES) name else signature(name, columns)) + describe(comment) }
            .partition { (name, _) -> name == EventTypesTable.NAME }
        // event_types, recreated at every opening, is the newest table: it goes right under the events it describes.
        val tableLines = tables.flatMap { (name, line) -> listOf(line) + if (name == "events") types.map { it.second } else emptyList() }
        return linkedMapOf(
            Section.HEADER to HEADER,
            Section.VIEWS to lines("VIEWS", views.map { it.second }),
            Section.MACROS to lines("MACROS", macros(connection)),
            Section.TABLES to lines("TABLES (traces: see columns_of)", tableLines),
            Section.MEMORY to lines(MEMORY, memory.map { it.second }),
            Section.EXAMPLES to lines("EXAMPLES", EXAMPLES),
        )
    }

    /** The examples of the description, which must run as they are (checked by `SchemaDocTest`). */
    internal val examples: List<String> get() = EXAMPLES

    private const val MAIN = "database_name = current_database() AND schema_name = 'main'"

    private fun lines(title: String, lines: List<String>) = (listOf(title) + lines).joinToString("\n")

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
}
