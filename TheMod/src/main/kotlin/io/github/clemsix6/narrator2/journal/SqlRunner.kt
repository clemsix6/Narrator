package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Caps of one query (M1_M2 §6.3).
 *
 * @property maxRows rows shown; the others are only counted, up to [SqlFormat.COUNT_LIMIT].
 * @property timeoutSeconds the query is interrupted after this delay.
 * @property maxCellChars a longer value is cut with `…(+N)`.
 * @property maxChars the whole text is cut at the end of a row.
 */
data class SqlLimits(val maxRows: Int, val timeoutSeconds: Int, val maxCellChars: Int = 120, val maxChars: Int = 8_000) {
    init {
        require(maxRows >= 1) { "maxRows must be at least 1" }
        require(timeoutSeconds >= 1) { "timeoutSeconds must be at least 1" }
        require(maxCellChars >= 8) { "maxCellChars must be at least 8" }
        require(maxChars >= 200) { "maxChars must be at least 200" }
    }

    companion object {
        /** `/narrator sql`. */
        val OPERATOR = SqlLimits(maxRows = 20, timeoutSeconds = 10)

        /** Rows the `sql` tool shows by default, and at most (the model may ask for more, up to this cap). */
        const val MODEL_DEFAULT_ROWS = 50
        const val MODEL_MAX_ROWS = 200

        /** Time limit of the Analyst's queries; the Director's is `journal.sqlTimeoutSeconds`. */
        const val ANALYST_TIMEOUT_SECONDS = 15
    }
}

/** Who a query is run for. */
enum class SqlAccess {
    /**
     * The model (`sql` tool). Refused: the tables of [Journal.HIDDEN_FROM_MODEL] under any form, the
     * database catalog (it describes and measures every table), and functions that run SQL given as text.
     */
    MODEL,

    /** An operator (`/narrator sql`): every table and the catalog are readable. */
    OPERATOR,
}

/** How a query ended. */
sealed interface SqlOutcome {
    /**
     * The result as compact text ([SqlFormat]).
     *
     * @property shown rows in [text].
     * @property total rows the query returned; null when more than [SqlFormat.COUNT_LIMIT].
     */
    data class Rows(val text: String, val shown: Int, val total: Long?, val elapsedMs: Long) : SqlOutcome

    /** Refused by validation: nothing was executed. [reason] says what to change. */
    data class Rejected(val reason: String) : SqlOutcome

    /** DuckDB refused the query (binding, conversion…) or the Journal is unavailable; [message] is DuckDB's. */
    data class Failed(val message: String) : SqlOutcome

    /** The query ran longer than its limit and was interrupted. */
    data class TimedOut(val seconds: Int) : SqlOutcome
}

/**
 * Read-only SQL on the Journal (M1_M2 §6.1), for the `sql` tool and `/narrator sql`.
 *
 * Validation, before anything runs (a failure is [SqlOutcome.Rejected]):
 * 1. the text is not empty and has at most [MAX_QUERY_CHARS] characters; a final `;` is dropped;
 * 2. DuckDB parses it, as a parameter of `json_serialize_sql` (never concatenated), which only
 *    serializes `SELECT` statements: `INSERT`, `PRAGMA`, `COPY`, `ATTACH`, `SET`… fail there;
 * 3. it holds exactly one statement;
 * 4. its syntax tree calls no function that changes the database or reads raw memory ([UNSAFE_FUNCTIONS]);
 * 5. for the model only ([SqlAccess.MODEL]): the tree names no table of [Journal.HIDDEN_FROM_MODEL],
 *    anywhere and in any case (`SUMMARIZE`, `DESCRIBE`, a string given to a function included), no
 *    part of the database catalog ([CATALOG_PREFIXES], [CATALOG_SCHEMAS], `SHOW TABLES`), and no
 *    function that runs SQL given as text ([SQL_FROM_TEXT]);
 * 6. DuckDB prepares it (binding and planning, nothing runs): its errors are [SqlOutcome.Failed];
 * 7. for the model only, a second barrier: the plan (`EXPLAIN`) scans no hidden table, whatever view
 *    or macro the query goes through.
 *
 * Then the query runs unchanged (its `ORDER BY` holds) on a read connection of the Journal, with a
 * statement timeout ([SqlLimits.timeoutSeconds]); results are streamed: [SqlLimits.maxRows] rows are
 * read, the others only counted, up to [SqlFormat.COUNT_LIMIT].
 */
class SqlRunner(private val journal: Journal, private val clock: Clock) {
    private val log = Log.sql

    /** Runs [sql] for [access] within [limits]. Never throws, except the caller's cancellation. */
    suspend fun query(sql: String, limits: SqlLimits, access: SqlAccess = SqlAccess.MODEL): SqlOutcome {
        val query = sql.trim().removeSuffix(";").trimEnd()
        val outcome = when {
            query.isEmpty() -> SqlOutcome.Rejected("the query is empty")
            query.length > MAX_QUERY_CHARS -> SqlOutcome.Rejected("the query is longer than $MAX_QUERY_CHARS characters (got ${query.length})")
            else -> try {
                journal.read(limits.timeoutSeconds * 1_000L + READ_GRACE_MS) { connection -> run(connection, query, limits, access) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: JournalTimeoutException) {
                SqlOutcome.TimedOut(limits.timeoutSeconds)
            } catch (e: JournalUnavailableException) {
                SqlOutcome.Failed(e.message.orEmpty())
            } catch (e: SQLException) {
                if (isInterrupt(e)) SqlOutcome.TimedOut(limits.timeoutSeconds) else SqlOutcome.Failed(clean(e.message))
            } catch (e: Exception) {
                log.error("query failed unexpectedly: ${short(query)}", e)
                SqlOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
        report(query, access, outcome)
        return outcome
    }

    /** Validation, the plan check and the query itself, on one read connection. */
    private fun run(connection: Connection, query: String, limits: SqlLimits, access: SqlAccess): SqlOutcome {
        val tree = when (val parsed = parse(connection, query, limits)) {
            is Parsed.Refused -> return parsed.outcome
            is Parsed.Tree -> parsed.statement
        }
        refusal(tree, access)?.let { return SqlOutcome.Rejected(it) }
        // Preparing binds and plans the query without running it: DuckDB's errors come with their line and caret.
        return connection.prepareStatement(query).use { statement ->
            if (access == SqlAccess.MODEL) planRefusal(connection, query, limits)?.let { return SqlOutcome.Rejected(it) }
            statement.queryTimeout = limits.timeoutSeconds
            execute(statement, zoneOf(connection), limits)
        }
    }

    private sealed interface Parsed {
        class Tree(val statement: JsonElement) : Parsed

        class Refused(val outcome: SqlOutcome) : Parsed
    }

    /** The syntax tree of the single statement of [query], from `json_serialize_sql`. */
    private fun parse(connection: Connection, query: String, limits: SqlLimits): Parsed {
        val json = connection.prepareStatement("SELECT json_serialize_sql(CAST(? AS VARCHAR))").use { statement ->
            statement.queryTimeout = limits.timeoutSeconds
            statement.setString(1, query)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return Parsed.Refused(SqlOutcome.Failed("the query could not be analysed"))
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            val message = root["error_message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val position = root["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            return Parsed.Refused(
                SqlOutcome.Rejected(
                    when {
                        message.startsWith("Only SELECT") -> NOT_A_SELECT
                        position != null -> "Parser Error: $message (at character ${position + 1})"
                        else -> "Parser Error: $message"
                    },
                ),
            )
        }
        val statements = root["statements"]?.jsonArray.orEmpty()
        return when (statements.size) {
            1 -> Parsed.Tree(statements.single())
            0 -> Parsed.Refused(SqlOutcome.Rejected("the query is empty"))
            else -> Parsed.Refused(SqlOutcome.Rejected("one SELECT per call (got ${statements.size} statements)"))
        }
    }

    /** Why [tree] may not run for [access]; null when it may. */
    private fun refusal(tree: JsonElement, access: SqlAccess): String? {
        val nodes = objects(tree).toList()
        val functions = nodes.mapNotNull { it.string("function_name") }.map(::identifier)
        functions.firstOrNull { it in UNSAFE_FUNCTIONS }?.let { return "$it() is not allowed: queries are read-only" }
        if (access == SqlAccess.OPERATOR) return null

        // Any string of the tree: table names, aliases, qualified columns, and strings given to functions.
        strings(tree).map { it.lowercase() }.forEach { text ->
            Journal.HIDDEN_FROM_MODEL.firstOrNull { it in text }?.let { return hiddenReason(it) }
        }
        functions.firstOrNull { it in SQL_FROM_TEXT }?.let { return "$it() is not allowed: write the query itself" }
        functions.firstOrNull(::isCatalogName)?.let { return catalogReason("$it()") }
        nodes.mapNotNull { it.string("table_name") }.map(::identifier).firstOrNull(::isCatalogName)?.let { return catalogReason(it) }
        (nodes.mapNotNull { it.string("schema_name") } + nodes.mapNotNull { it.string("schema") })
            .map(::identifier).firstOrNull { it in CATALOG_SCHEMAS }?.let { return catalogReason(it) }
        // SHOW TABLES, SHOW ALL TABLES, SHOW DATABASES: a SHOW without a query lists the catalog.
        if (nodes.any { it.string("type") == "SHOW_REF" && it["query"] is JsonNull }) return catalogReason("SHOW")
        return null
    }

    /**
     * Why the plan of [query] may not run for the model: it scans a hidden table, whatever views or
     * macros lead to it, or it cannot be read (then it cannot be checked); null when it may run.
     */
    private fun planRefusal(connection: Connection, query: String, limits: SqlLimits): String? {
        val plan = connection.createStatement().use { statement ->
            statement.queryTimeout = limits.timeoutSeconds
            statement.executeQuery("EXPLAIN (FORMAT JSON) $query").use { rows ->
                buildString { while (rows.next()) append(rows.getString(2)) }
            }
        }
        val tree = try {
            Json.parseToJsonElement(plan)
        } catch (_: SerializationException) {
            return "the plan of this query could not be checked"
        }
        return objects(tree)
            .mapNotNull { node -> (node["extra_info"] as? JsonObject)?.string("Table")?.lowercase() }
            .firstNotNullOfOrNull { table -> Journal.HIDDEN_FROM_MODEL.firstOrNull { it in table } }
            ?.let(::hiddenReason)
    }

    /** Runs the prepared query and reads its result: [SqlLimits.maxRows] rows, the others counted. */
    private fun execute(statement: PreparedStatement, zone: ZoneId, limits: SqlLimits): SqlOutcome.Rows {
        val now = clock.instant()
        val context = SqlFormat.Context.at(now, zone)
        val started = System.nanoTime()
        return statement.executeQuery().use { rows ->
            val meta = rows.metaData
            val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            val kept = mutableListOf<List<String>>()
            var count = 0L
            while (count <= SqlFormat.COUNT_LIMIT && rows.next()) {
                if (kept.size < limits.maxRows) {
                    kept += columns.indices.map { SqlFormat.cell(rows.getObject(it + 1), context, limits.maxCellChars) }
                }
                count++
            }
            val total = count.takeIf { it <= SqlFormat.COUNT_LIMIT }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val rendered = SqlFormat.render(columns, kept, total, now, zone, elapsedMs, limits)
            SqlOutcome.Rows(rendered.text, rendered.shown, total, elapsedMs)
        }
    }

    /** The Journal's time zone (`SET TimeZone` by `DuckDatabase`): JDBC hands timestamps in the JVM's. */
    private fun zoneOf(connection: Connection): ZoneId {
        val name = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_setting('TimeZone')").use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return try {
            name?.let(ZoneId::of) ?: clock.zone
        } catch (_: DateTimeException) {
            clock.zone
        }
    }

    private fun report(query: String, access: SqlAccess, outcome: SqlOutcome) {
        val who = access.name.lowercase()
        when (outcome) {
            is SqlOutcome.Rows -> log.debug { "$who query: ${outcome.total ?: ">${SqlFormat.COUNT_LIMIT}"} rows in ${outcome.elapsedMs} ms: ${short(query)}" }
            is SqlOutcome.Rejected -> log.info("$who query refused (${outcome.reason}): ${short(query)}")
            is SqlOutcome.Failed -> log.debug { "$who query failed (${outcome.message.lineSequence().first()}): ${short(query)}" }
            is SqlOutcome.TimedOut -> log.warn("$who query interrupted after ${outcome.seconds} s: ${short(query)}")
        }
    }

    companion object {
        /** Longest query accepted, in characters. */
        const val MAX_QUERY_CHARS = 4_000

        /**
         * Functions refused in every query: they change the database (sequences, checkpoints, logging and
         * profiling switches) or read raw memory (`arrow_scan` takes pointers).
         */
        val UNSAFE_FUNCTIONS: Set<String> = setOf(
            "nextval", "setval", "checkpoint", "force_checkpoint",
            "enable_logging", "disable_logging", "enable_profiling", "disable_profiling",
            "arrow_scan", "arrow_scan_dumb",
        )

        /**
         * Functions that run SQL given as text, refused in model queries: the text could name a hidden
         * table in pieces (`'private_' || 'messages'`). `json_execute_serialized_sql` does not even show
         * its scans in `EXPLAIN`.
         */
        val SQL_FROM_TEXT: Set<String> = Journal.SQL_FROM_STRING_FUNCTIONS + "json_execute_serialized_sql"

        /**
         * Name prefixes of the database catalog's functions and views (`duckdb_tables()`,
         * `pragma_storage_info()`, `pg_class`, `sqlite_master`…), refused in model queries: they describe
         * and measure every table, hidden ones included (row counts, even min and max values).
         */
        val CATALOG_PREFIXES: List<String> = listOf("duckdb_", "pragma_", "pg_", "sqlite_")

        /** Schemas of the database catalog, refused in model queries. */
        val CATALOG_SCHEMAS: Set<String> = setOf("information_schema", "pg_catalog")

        private const val NOT_A_SELECT = "only a single read-only SELECT is allowed (not INSERT, UPDATE, DELETE, CREATE, PRAGMA, " +
            "SET, COPY, ATTACH, EXPLAIN…)"

        /** Time given to `Journal.read` on top of the statement timeout: waiting for a read connection. */
        private const val READ_GRACE_MS = 2_000L

        private fun hiddenReason(table: String) =
            "this query names $table, which is never readable: private messages between players stay private"

        private fun catalogReason(what: String) =
            "the database catalog ($what) is not readable here: the tables, views and macros are in the tool " +
                "description, and columns_of('<table>') lists the columns of one"

        private fun isCatalogName(name: String) = CATALOG_PREFIXES.any { name.startsWith(it) }

        /** An identifier as DuckDB resolves it: without quotes, in lower case. */
        private fun identifier(name: String) = name.replace("\"", "").lowercase()

        private fun isInterrupt(e: SQLException) = e.message.orEmpty().contains("INTERRUPT", ignoreCase = true)

        /**
         * DuckDB's message without the driver's prefix ("Invalid Input Error: Attempting to execute an
         * unsuccessful or closed pending query result\nError: "), line and caret kept, cut to 1 000 characters.
         */
        internal fun clean(message: String?): String {
            val text = message.orEmpty().trim()
            val useful = text.substringAfter("\nError: ", text)
            return if (useful.length <= 1_000) useful else useful.take(1_000) + "…"
        }

        private fun short(query: String): String {
            val flat = query.replace(Regex("\\s+"), " ")
            return if (flat.length <= 300) flat else flat.take(300) + "…"
        }

        /** Every object of [element], depth first. */
        private fun objects(element: JsonElement): Sequence<JsonObject> = sequence {
            when (element) {
                is JsonObject -> {
                    yield(element)
                    element.values.forEach { yieldAll(objects(it)) }
                }
                is JsonArray -> element.forEach { yieldAll(objects(it)) }
                else -> Unit
            }
        }

        /** Every string value of [element]. */
        private fun strings(element: JsonElement): Sequence<String> = sequence {
            when (element) {
                is JsonObject -> element.values.forEach { yieldAll(strings(it)) }
                is JsonArray -> element.forEach { yieldAll(strings(it)) }
                is JsonPrimitive -> if (element.isString) yield(element.content)
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
