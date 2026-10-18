package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
 * @property maxCellChars a longer value is cut with `…(+N)`; 400 holds the longest memory texts (`memories.content`).
 * @property maxChars the whole text is cut at the end of a row, whatever the cell cap.
 */
data class SqlLimits(val maxRows: Int, val timeoutSeconds: Int, val maxCellChars: Int = 400, val maxChars: Int = 8_000) {
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
     * The model (`sql` tool). It reads the Journal's visible tables and views, `range()`, `unnest()` and
     * the macros, nothing else. Refused: the tables of [Journal.HIDDEN_FROM_MODEL] under any form, the
     * database catalog (it describes and measures every table), functions that run SQL given as text,
     * and any file or setting of the server: DuckDB keeps its own files (the database, its WAL, its
     * temporary files) readable from SQL even with external access disabled, and they hold the private
     * messages in raw bytes.
     */
    MODEL,

    /** An operator (`/narrator sql`): every table, the catalog and DuckDB's own files are readable. */
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
 * 5. for the model only ([SqlAccess.MODEL]), in the syntax tree:
 *    - no table of [Journal.HIDDEN_FROM_MODEL] is named, anywhere and in any case (`SUMMARIZE`,
 *      `DESCRIBE`, a string given to a function included);
 *    - no function runs SQL given as text ([SQL_FROM_TEXT]);
 *    - nothing of the database catalog is named ([CATALOG_PREFIXES], [CATALOG_SCHEMAS], `SHOW TABLES`);
 *    - no function reads a file or a setting ([FILE_FUNCTION_PREFIXES], [FILE_FUNCTIONS]);
 *    - every table, schema and catalog name is a plain name, and the schema is `main`: a quoted path
 *      or file name (`FROM '…/narrator.duckdb'`) would make DuckDB read the file (a replacement scan);
 *    - `FROM` calls no table function but [MODEL_TABLE_FUNCTIONS] and the Journal's own table macros;
 * 6. DuckDB prepares it (binding and planning, nothing runs): its errors are [SqlOutcome.Failed];
 * 7. for the model only, a second barrier on the plan (`EXPLAIN`), whatever view or macro the query goes
 *    through: a white list. Its scans read visible tables only, its table functions are [PLAN_FUNCTIONS]
 *    only, and every other operator is one of [PLAN_OPERATORS], none of which reads anything.
 *
 * Why the model's checks go that far: `enable_external_access = false` closes every file but DuckDB's
 * own (the database, its `.wal`, `.wal.checkpoint` and the `<db>.tmp/` folder of spilled blocks),
 * whose raw bytes hold the private messages, and `current_setting('allowed_paths')` tells where they are.
 *
 * Then the query runs unchanged (its `ORDER BY` holds) on a read connection of the Journal, with a
 * statement timeout ([SqlLimits.timeoutSeconds]); results are streamed: [SqlLimits.maxRows] rows are
 * read, the others only counted, up to [SqlFormat.COUNT_LIMIT]. The model's errors lose any path of the
 * server, and `<` `>` in them become `‹` `›` like in the cells ([SqlFormat]): an error may quote a value.
 */
class SqlRunner(private val journal: Journal, private val clock: Clock) {
    private val log = Log.sql

    /**
     * Runs [sql] for [access] within [limits]. Never throws, except the caller's cancellation.
     *
     * The facts of the last second may still wait for the writer (it writes about once a second): a
     * `barrier()` first makes them readable, so that the model, reacting to a fresh fact, finds it. The
     * barrier is given [BARRIER_TIMEOUT_MS] at most; past that, the query runs on what is written.
     */
    suspend fun query(sql: String, limits: SqlLimits, access: SqlAccess = SqlAccess.MODEL): SqlOutcome {
        val query = sql.trim().removeSuffix(";").trimEnd()
        val outcome = when {
            query.isEmpty() -> SqlOutcome.Rejected("the query is empty")
            query.length > MAX_QUERY_CHARS -> SqlOutcome.Rejected("the query is longer than $MAX_QUERY_CHARS characters (got ${query.length})")
            else -> try {
                if (withTimeoutOrNull(BARRIER_TIMEOUT_MS) { journal.barrier() } == null) {
                    log.debug { "the barrier took more than $BARRIER_TIMEOUT_MS ms: the query may miss the last facts" }
                }
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
        return if (access == SqlAccess.MODEL) forModel(outcome) else outcome
    }

    /** Validation, the plan check and the query itself, on one read connection. */
    private fun run(connection: Connection, query: String, limits: SqlLimits, access: SqlAccess): SqlOutcome {
        val tree = when (val parsed = parse(connection, query, limits)) {
            is Parsed.Refused -> return parsed.outcome
            is Parsed.Tree -> parsed.statement
        }
        refusal(connection, tree, access)?.let { return SqlOutcome.Rejected(it) }
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
    private fun refusal(connection: Connection, tree: JsonElement, access: SqlAccess): String? {
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
        val tables = nodes.mapNotNull { it.string("table_name") }.map(::identifier)
        tables.firstOrNull(::isCatalogName)?.let { return catalogReason(it) }
        val schemas = (nodes.mapNotNull { it.string("schema_name") } + nodes.mapNotNull { it.string("schema") }).map(::identifier)
        schemas.firstOrNull { it in CATALOG_SCHEMAS }?.let { return catalogReason(it) }
        // SHOW TABLES, SHOW ALL TABLES, SHOW DATABASES: a SHOW without a query lists the catalog.
        if (nodes.any { it.string("type") == "SHOW_REF" && it["query"] is JsonNull }) return catalogReason("SHOW")

        // DuckDB's own files stay readable from SQL: no file function, no setting, no path or file name as a table.
        functions.firstOrNull(::readsFiles)?.let { return fileReason("$it()") }
        val catalogs = (nodes.mapNotNull { it.string("catalog_name") } + nodes.mapNotNull { it.string("catalog") }).map(::identifier)
        if ((tables + schemas + catalogs).any { it.isNotEmpty() && !PLAIN_NAME.matches(it) }) return fileReason("a path or a file name")
        schemas.firstOrNull { it.isNotEmpty() && it != "main" }?.let { return "$it is not a schema of the Journal: name its tables and views alone" }
        val tableFunctions = nodes.filter { it.string("type") == "TABLE_FUNCTION" }
            .mapNotNull { (it["function"] as? JsonObject)?.string("function_name") }.map(::identifier)
            .filterNot { it in MODEL_TABLE_FUNCTIONS }
        if (tableFunctions.isNotEmpty()) {
            val macros = tableMacros(connection)
            tableFunctions.firstOrNull { it !in macros }?.let { return readReason("$it()") }
        }
        return null
    }

    /** The table macros of the Journal (`views.sql`): `recent`, `hotspots`, `columns_of`… */
    private fun tableMacros(connection: Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT function_name FROM duckdb_functions() " +
                "WHERE function_type = 'table_macro' AND database_name = current_database() AND schema_name = 'main'",
        ).use { rows -> buildSet { while (rows.next()) add(rows.getString(1).lowercase()) } }
    }

    /**
     * Why the plan of [query] may not run for the model, whatever views or macros it goes through;
     * null when it may run. A white list: every scan reads a visible table, every table function is one
     * of [PLAN_FUNCTIONS], and every other operator is one of [PLAN_OPERATORS], none of which reads
     * anything. A plan that cannot be read cannot be checked, so it is refused too.
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
        val operators = objects(tree).filter { it.containsKey("name") && it.containsKey("children") }.toList()
        if (operators.isEmpty()) return "the plan of this query could not be checked"
        operators.forEach { operator ->
            val name = operator.string("name").orEmpty().trim().uppercase()
            val info = operator["extra_info"] as? JsonObject
            val table = info?.string("Table")
            // A table function scans with its name in `Function`; an in-out one (`FROM unnest((SELECT …))`) in `Name`.
            val function = info?.string("Function") ?: info?.string("Name")?.takeIf { name == "INOUT_FUNCTION" }
            when {
                table != null -> Journal.HIDDEN_FROM_MODEL.firstOrNull { it in table.lowercase() }?.let { return hiddenReason(it) }
                function != null -> if (function.uppercase() !in PLAN_FUNCTIONS) return readReason(function.lowercase())
                name !in PLAN_OPERATORS -> return readReason(name.lowercase())
            }
        }
        return null
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
         * its scans in `EXPLAIN`; `json_serialize_plan` binds its text (a file function would read its
         * file) and returns the plan with its constants folded (`current_setting('allowed_paths')`).
         */
        val SQL_FROM_TEXT: Set<String> = Journal.SQL_FROM_STRING_FUNCTIONS + "json_execute_serialized_sql" + "json_serialize_plan"

        /**
         * Name prefixes of the functions that read files, refused in model queries: `read_blob`,
         * `read_text`, `read_csv`, `read_duckdb`, `parquet_metadata`… External access is disabled, but
         * DuckDB's own files stay readable, the private messages in their raw bytes.
         */
        val FILE_FUNCTION_PREFIXES: List<String> = listOf("read_", "parquet_")

        /**
         * Other functions refused in model queries: `glob` and `sniff_csv` read the disk, and
         * `current_setting` tells where DuckDB's files are (`allowed_paths`, `temp_directory`).
         */
        val FILE_FUNCTIONS: Set<String> = setOf("glob", "sniff_csv", "current_setting")

        /** The table functions a model query may call in `FROM`, besides the Journal's table macros. */
        val MODEL_TABLE_FUNCTIONS: Set<String> = setOf("range", "unnest")

        /**
         * The table functions a model query's plan may hold: `range`, `unnest`, and `duckdb_columns`,
         * which only `columns_of()` reaches (the name itself is refused in the query, as the catalog).
         */
        val PLAN_FUNCTIONS: Set<String> = setOf("RANGE", "UNNEST", "DUCKDB_COLUMNS")

        /**
         * The operators a model query's plan may hold besides its scans: they transform what the scans
         * read, and read nothing themselves (VALUES, `DESCRIBE` and intermediate results included).
         * Anything else, such as a file reader behind a view, is refused.
         */
        val PLAN_OPERATORS: Set<String> = setOf(
            "PROJECTION", "FILTER", "ORDER_BY", "TOP_N", "LIMIT", "STREAMING_LIMIT", "LIMIT_PERCENT",
            "HASH_GROUP_BY", "PERFECT_HASH_GROUP_BY", "PARTITIONED_AGGREGATE", "UNGROUPED_AGGREGATE",
            "WINDOW", "STREAMING_WINDOW", "UNNEST", "PIVOT", "RESERVOIR_SAMPLE", "STREAMING_SAMPLE",
            "HASH_JOIN", "NESTED_LOOP_JOIN", "BLOCKWISE_NL_JOIN", "PIECEWISE_MERGE_JOIN", "IE_JOIN", "ASOF_JOIN",
            "CROSS_PRODUCT", "POSITIONAL_JOIN", "POSITIONAL_SCAN", "LEFT_DELIM_JOIN", "RIGHT_DELIM_JOIN", "DELIM_JOIN",
            "DELIM_SCAN", "UNION", "CTE", "CTE_SCAN", "REC_CTE", "REC_CTE_SCAN", "REC_REC_CTE_SCAN", "REC_KEY_CTE",
            "RECURSIVE_CTE", "RECURSIVE_CTE_SCAN", "RECURSIVE_RECURRING_CTE_SCAN", "RECURSIVE_KEY_CTE",
            "DUMMY_SCAN", "EMPTY_RESULT", "COLUMN_DATA_SCAN", "CHUNK_SCAN", "EXPRESSION_SCAN",
        )

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

        /** The longest a query waits for the facts still queued to be written ([Journal.barrier]). */
        const val BARRIER_TIMEOUT_MS = 500L

        private fun hiddenReason(table: String) =
            "this query names $table, which is never readable: private messages between players stay private"

        private fun catalogReason(what: String) =
            "the database catalog ($what) is not readable here: the tables, views and macros are in the tool " +
                "description, and columns_of('<table>') lists the columns of one"

        private fun fileReason(what: String) =
            "$what is not allowed: no file or setting of the server is readable, only the Journal's tables and views"

        private fun readReason(what: String) =
            "this query reads through $what, which is not allowed: only the Journal's tables and views, range(), " +
                "unnest() and the macros of the description"

        private fun isCatalogName(name: String) = CATALOG_PREFIXES.any { name.startsWith(it) }

        private fun readsFiles(function: String) = function in FILE_FUNCTIONS || FILE_FUNCTION_PREFIXES.any { function.startsWith(it) }

        /** A table, schema or catalog name that DuckDB cannot take for a file: letters, digits and `_`. */
        private val PLAIN_NAME = Regex("""[\p{L}_][\p{L}\p{N}_]*""")

        /**
         * An absolute path in an error: `C:\…`, `C:/…`, `\\host\…`, or `/a/…` (neither a division such as
         * `a/b/c` nor a web address). DuckDB names its files in some errors (a full temporary folder, a
         * Journal that cannot open), and none of the server's paths is the model's business.
         */
        private val PATH = Regex("""(?:(?<![A-Za-z])[A-Za-z]:[\\/]|\\\\|(?<![\w.:/])/(?=[^\s/"']+/))[^\s"'`]*""")

        /** What the model reads of [outcome]: errors without paths, and `<` `>` escaped like in the cells. */
        private fun forModel(outcome: SqlOutcome): SqlOutcome = when (outcome) {
            is SqlOutcome.Failed -> SqlOutcome.Failed(ModelText.escape(outcome.message.replace(PATH, "[path]")))
            is SqlOutcome.Rejected -> SqlOutcome.Rejected(ModelText.escape(outcome.reason))
            else -> outcome
        }

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
