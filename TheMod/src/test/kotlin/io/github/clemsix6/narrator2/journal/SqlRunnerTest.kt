package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.journal.SqlDataset.SECRET
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Validation, privacy, caps and timeouts of [SqlRunner] (M1_M2 §6.1), on the real Journal. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlRunnerTest {
    private val fixture = SqlDataset.journal()
    private val runner = SqlRunner(fixture.journal, fixture.clock)
    private val limits = SqlLimits(maxRows = 50, timeoutSeconds = 5)

    @AfterAll
    fun close() = fixture.close()

    private fun model(sql: String, limits: SqlLimits = this.limits) = runBlocking { runner.query(sql, limits) }

    private fun operator(sql: String) = runBlocking { runner.query(sql, SqlLimits.OPERATOR, SqlAccess.OPERATOR) }

    private fun rows(outcome: SqlOutcome, sql: String = ""): SqlOutcome.Rows = assertIs<SqlOutcome.Rows>(outcome, "$sql → $outcome")

    private fun rejected(outcome: SqlOutcome, sql: String = ""): SqlOutcome.Rejected = assertIs<SqlOutcome.Rejected>(outcome, "$sql → $outcome")

    // ── Validation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only a single read-only SELECT passes, and nothing refused is executed`() {
        val notASelect = "only a single read-only SELECT is allowed"
        val refused = listOf(
            "INSERT INTO chat (id, ts, channel, text) VALUES (999, now(), 'public', 'forged')" to notASelect,
            "UPDATE players SET name = 'Forged'" to notASelect,
            "DELETE FROM events" to notASelect,
            "DROP TABLE events" to notASelect,
            "CREATE TABLE forged (a INTEGER)" to notASelect,
            "CREATE VIEW forged AS SELECT * FROM private_messages" to notASelect,
            "PRAGMA table_info('chat')" to notASelect,
            "SET threads = 8" to notASelect,
            "RESET lock_configuration" to notASelect,
            "COPY chat TO 'chat.csv'" to notASelect,
            "ATTACH 'other.duckdb' AS other" to notASelect,
            "EXPLAIN SELECT 1" to notASelect,
            "CALL pragma_version()" to notASelect,
            "CHECKPOINT" to notASelect,
            "SELECT 1; DROP TABLE events" to notASelect,
            "SELECT 1; SELECT 2" to "one SELECT per call (got 2 statements)",
            "SELECT nextval('forged')" to "nextval() is not allowed: queries are read-only",
            "SELECT * FROM chat WHERE id = setval('forged', 3)" to "setval() is not allowed",
            "SELECT * FROM checkpoint()" to "checkpoint() is not allowed",
            "SELECT * FROM enable_logging()" to "enable_logging() is not allowed",
            "SELECT * FROM arrow_scan(0, 0, 0)" to "arrow_scan() is not allowed",
            "SELEC 1" to "Parser Error: syntax error at or near \"SELEC\" (at character 1)",
            "SELECT FROM WHERE" to "Parser Error",
            "" to "the query is empty",
            "  ;  " to "the query is empty",
            "-- nothing but a comment" to "the query is empty",
            "SELECT 1 AS " + "a".repeat(SqlRunner.MAX_QUERY_CHARS) to "the query is longer than 4000 characters",
        )
        refused.forEach { (sql, reason) -> assertContains(rejected(model(sql), sql).reason, reason, message = sql) }
        // The operator's access relaxes privacy, never read-only.
        refused.forEach { (sql, reason) -> assertContains(rejected(operator(sql), sql).reason, reason, message = sql) }

        assertEquals(listOf(listOf<Any?>(1L)), fixture.values("SELECT count(*) FROM events WHERE type = 'join' AND player = 'Mimosa'"))
        assertEquals(listOf(listOf<Any?>(0L)), fixture.values("SELECT count(*) FROM chat WHERE text = 'forged'"))
        assertEquals(listOf(listOf<Any?>(0L)), fixture.values("SELECT count(*) FROM players WHERE name = 'Forged'"))
    }

    @Test
    fun `a final semicolon and blanks are accepted`() {
        assertEquals(listOf("answer", "42"), rows(model("  select 42 as answer ;  ")).text.lines().drop(1))
    }

    @Test
    fun `DESCRIBE and SUMMARIZE of what the model may read are allowed`() {
        assertContains(rows(model("DESCRIBE mining")).text, "\nore|BOOLEAN|")
        assertContains(rows(model("SUMMARIZE SELECT y FROM mining")).text, "\ny|INTEGER|-41|64|")
        assertContains(rows(operator("DESCRIBE private_messages")).text, "\ntext|VARCHAR|")
        assertEquals(34L, rows(operator("SHOW TABLES")).total, "16 tables and 18 views")
    }

    @Test
    fun `files stay out of reach`() {
        listOf("SELECT * FROM read_csv('secret.csv')", "SELECT * FROM glob('*')", "SELECT * FROM read_text('C:/Windows/win.ini')").forEach { sql ->
            val outcome = model(sql)
            assertIs<SqlOutcome.Failed>(outcome, sql)
            assertContains(outcome.message, "disabled", message = sql)
        }
    }

    @Test
    fun `DuckDB's errors reach the reader without the driver's prefix`() {
        val outcome = assertIs<SqlOutcome.Failed>(model("SELECT nope FROM chat_log"))
        assertTrue(outcome.message.startsWith("Binder Error: Referenced column \"nope\" not found"), outcome.message)
        assertContains(outcome.message, "LINE 1: SELECT nope FROM chat_log")
        assertFalse("Attempting to execute" in outcome.message)

        val conversion = assertIs<SqlOutcome.Failed>(model("SELECT CAST('abc' AS INTEGER)"))
        assertTrue(conversion.message.startsWith("Conversion Error: Could not convert string 'abc'"), conversion.message)
    }

    // ── Privacy ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `private messages never reach the model, whatever the query`() {
        val privacy = "private messages between players stay private"
        val catalog = "the database catalog"
        val attacks = listOf(
            "SELECT * FROM private_messages" to privacy,
            "SELECT text FROM Private_Messages" to privacy,
            "SELECT text FROM PRIVATE_MESSAGES" to privacy,
            "SELECT text FROM \"private_messages\"" to privacy,
            "SELECT text FROM \"Private_Messages\"" to privacy,
            "SELECT pm.text FROM private_messages AS pm" to privacy,
            "SELECT p.text FROM main.private_messages p" to privacy,
            "SELECT text FROM memory.main.private_messages" to privacy,
            "FROM private_messages" to privacy,
            "FROM private_messages SELECT text" to privacy,
            "WITH x AS (SELECT * FROM private_messages) SELECT text FROM x" to privacy,
            "WITH x AS MATERIALIZED (SELECT * FROM private_messages) SELECT * FROM x" to privacy,
            "WITH RECURSIVE r(t) AS (SELECT text FROM private_messages UNION ALL SELECT t FROM r WHERE false) SELECT * FROM r" to privacy,
            "SELECT * FROM (SELECT text FROM private_messages) sub" to privacy,
            "SELECT * FROM chat_log WHERE text IN (SELECT text FROM private_messages)" to privacy,
            "SELECT (SELECT text FROM private_messages LIMIT 1) AS t" to privacy,
            "SELECT * FROM chat_log WHERE EXISTS (SELECT 1 FROM private_messages WHERE text LIKE 'the%')" to privacy,
            "SELECT * FROM chat, LATERAL (SELECT text FROM private_messages) x" to privacy,
            "SELECT text FROM chat UNION ALL SELECT text FROM private_messages" to privacy,
            "SELECT c.text FROM chat c JOIN private_messages m ON m.player = c.player" to privacy,
            "SELECT * FROM chat POSITIONAL JOIN private_messages" to privacy,
            "SELECT private_messages.text FROM chat, private_messages" to privacy,
            "SUMMARIZE private_messages" to privacy,
            "SUMMARIZE SELECT * FROM private_messages" to privacy,
            "DESCRIBE private_messages" to privacy,
            "SHOW private_messages" to privacy,
            "SELECT * FROM (UNPIVOT private_messages ON text INTO NAME k VALUE v)" to privacy,
            "SELECT * FROM private_messages AT (VERSION => 1)" to privacy,
            "SELECT * FROM query('SELECT text FROM private_messages')" to privacy,
            "SELECT * FROM query('SELECT text FROM private_' || 'messages')" to "query() is not allowed: write the query itself",
            "SELECT * FROM QUERY_TABLE('private_messages')" to privacy,
            "SELECT * FROM query_table('private_' || 'messages')" to "query_table() is not allowed",
            "SELECT * FROM \"query_table\"('priv' || 'ate_messages')" to "query_table() is not allowed",
            "SELECT * FROM main.query_table(lower('PRIVATE_MESSAGES'))" to privacy,
            "SELECT * FROM json_execute_serialized_sql(json_serialize_sql('SELECT text FROM private_' || 'messages'))" to
                "json_execute_serialized_sql() is not allowed",
            "SELECT * FROM pragma_storage_info('private_' || 'messages')" to catalog,
            "SELECT * FROM pragma_table_info('chat')" to catalog,
            "SELECT table_name, estimated_size FROM duckdb_tables()" to catalog,
            "SELECT * FROM duckdb_tables" to catalog,
            "SELECT * FROM duckdb_columns() WHERE table_name LIKE 'priv%'" to catalog,
            "SELECT * FROM system.main.duckdb_tables" to catalog,
            "SELECT * FROM information_schema.tables" to catalog,
            "SELECT * FROM information_schema.columns WHERE table_name LIKE 'priv%'" to catalog,
            "SELECT relname, reltuples FROM pg_class" to catalog,
            "SELECT * FROM pg_catalog.pg_class" to catalog,
            "SELECT * FROM sqlite_master" to catalog,
            "SHOW TABLES" to catalog,
            "SHOW ALL TABLES" to catalog,
            "SELECT * FROM chat_log WHERE text = 'private_messages'" to privacy,
        )
        attacks.forEach { (sql, reason) ->
            val outcome = model(sql)
            assertContains(rejected(outcome, sql).reason, reason, message = sql)
            assertFalse(SECRET in outcome.toString(), sql)
        }

        // A macro given the name in pieces answers, but lists nothing: it leaves hidden tables out itself.
        val columns = rows(model("SELECT * FROM columns_of('private_' || 'messages')"))
        assertEquals(0L, columns.total)
        // What the views show never includes the private message.
        listOf("SELECT * FROM chat_log", "SELECT * FROM timeline", "SELECT * FROM recent('Bartok', 120)").forEach { sql ->
            assertFalse(SECRET in rows(model(sql), sql).text, sql)
        }
    }

    @Test
    fun `the plan check stops a view or a macro that reads a hidden table`() {
        val db = DuckDatabase.openInMemory(JournalFixture.OPTIONS)
        db.writer.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO private_messages VALUES (1, now(), '${SqlDataset.bartok.uuid}', 'Bartok', ['Mimosa'], '$SECRET');
                CREATE VIEW innocent AS SELECT text FROM private_messages;
                CREATE MACRO innocent_macro() AS TABLE SELECT * FROM private_messages;
                """.trimIndent(),
            )
        }
        val journal = DuckJournal.open(db, JournalConfig(), TestClock())
        try {
            val runner = SqlRunner(journal, TestClock())
            listOf("SELECT * FROM innocent", "SELECT upper(text) FROM innocent", "SELECT * FROM innocent_macro()").forEach { sql ->
                val outcome = runBlocking { runner.query(sql, limits) }
                assertContains(rejected(outcome, sql).reason, "this query names private_messages", message = sql)
            }
            val operator = runBlocking { runner.query("SELECT * FROM innocent", SqlLimits.OPERATOR, SqlAccess.OPERATOR) }
            assertContains(rows(operator).text, SECRET)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `operators read everything, the private messages and the catalog included`() {
        assertContains(rows(operator("SELECT text FROM private_messages")).text, SECRET)
        assertContains(rows(operator("SELECT table_name FROM duckdb_tables() ORDER BY 1")).text, "private_messages")
        assertContains(rows(operator("SELECT * FROM query_table('private_' || 'messages')")).text, SECRET)
    }

    @Test
    fun `every view and macro passes the model's checks`() {
        val queries = listOf(
            "timeline", "chat_log", "narrator_actions", "mining", "building", "deaths", "kills", "damage", "trades", "meals",
            "crafts", "advancements", "visits", "travels", "griefs", "play_sessions", "playtime", "player_stats",
        ).map { "SELECT * FROM $it" } + listOf(
            "SELECT * FROM recent('Bartok', 60)",
            "SELECT * FROM hotspots('Bartok', 'block_break', 32)",
            "SELECT * FROM kill_stats('Bartok', now() - INTERVAL 1 DAY)",
            "SELECT * FROM sleep_spots('Bartok')",
            "SELECT * FROM trail('Bartok', 120)",
            "SELECT * FROM columns_of('mining')",
            "SELECT ago(ts), bare(block) FROM mining",
        )
        queries.forEach { sql ->
            val result = rows(model(sql), sql)
            assertTrue(result.total!! > 0, "$sql returns rows")
            assertFalse(SECRET in result.text, sql)
        }
    }

    // ── Execution ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a query past its time limit is interrupted, and the connection serves the next one`() {
        val started = System.nanoTime()
        val outcome = model("SELECT sum(a.range + b.range) FROM range(100000000) a, range(100000000) b", SqlLimits(maxRows = 5, timeoutSeconds = 1))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(SqlOutcome.TimedOut(1), outcome)
        assertTrue(elapsedMs in 900..4_000, "interrupted after $elapsedMs ms")
        // Both read connections still work.
        repeat(4) { assertEquals(listOf("n", "1"), rows(model("SELECT 1 AS n")).text.lines().drop(1)) }
    }

    @Test
    fun `rows beyond the cap are counted, and the query's order is kept`() {
        val capped = rows(model("SELECT range AS n FROM range(100) ORDER BY n DESC", SqlLimits(maxRows = 10, timeoutSeconds = 5)))
        assertEquals(10, capped.shown)
        assertEquals(100L, capped.total)
        val lines = capped.text.lines()
        assertContains(lines.first(), " · 100 rows · ")
        assertEquals(listOf("n") + (99 downTo 90).map { "$it" }, lines.subList(1, 12))
        assertEquals("10 of 100 rows shown — aggregate or add LIMIT", lines.last())

        val exact = rows(model("SELECT range FROM range(10000)", SqlLimits(maxRows = 3, timeoutSeconds = 5)))
        assertEquals(10_000L, exact.total)

        val huge = rows(model("SELECT range FROM range(50000)", SqlLimits(maxRows = 5, timeoutSeconds = 5)))
        assertNull(huge.total)
        assertEquals(5, huge.shown)
        assertContains(huge.text.lines().first(), " · >10000 rows · ")
        assertEquals("5 of >10000 rows shown — aggregate or add LIMIT", huge.text.lines().last())

        val all = rows(model("SELECT range FROM range(4)"))
        assertEquals(4, all.shown)
        assertEquals(4L, all.total)
        assertEquals(6, all.text.lines().size, "no footer when everything is shown")
    }

    @Test
    fun `the text stays within its character budget`() {
        val limits = SqlLimits(maxRows = 200, timeoutSeconds = 5, maxCellChars = 40, maxChars = 1_000)
        val result = rows(model("SELECT range AS n, repeat('x', 60) AS filler FROM range(200)", limits))
        assertTrue(result.text.length <= 1_000, "${result.text.length} chars")
        assertTrue(result.shown in 5..20, "${result.shown} rows shown")
        assertEquals("${result.shown} of 200 rows shown — aggregate or add LIMIT", result.text.lines().last())
        assertEquals("${"x".repeat(40)}…(+20)", result.text.lines()[2].substringAfter('|'))
    }

    @Test
    fun `timestamps are shown in the Journal's time zone`() {
        val db = DuckDatabase.openInMemory(DuckDatabase.Options(memoryLimitMb = 128, threads = 1, timeZone = ZoneId.of("Asia/Tokyo")))
        val journal = DuckJournal.open(db, JournalConfig(), TestClock())
        try {
            val clock = TestClock(Instant.parse("2026-09-25T12:30:00Z"))
            val outcome = runBlocking {
                SqlRunner(journal, clock).query(
                    "SELECT TIMESTAMPTZ '2026-09-25 12:00:00+00' AS today, TIMESTAMPTZ '2025-12-31 20:00:00+00' AS new_year, " +
                        "TIMESTAMPTZ '2025-06-01 00:00:00+00' AS last_year",
                    limits,
                )
            }
            val lines = rows(outcome).text.lines()
            assertTrue(lines[0].startsWith("now 09-25 21:30:00 · 1 row · "), lines[0])
            assertEquals("09-25 21:00:00|01-01 05:00:00|2025-06-01 09:00:00", lines[2], "2025-12-31 20:00 UTC is already 2026 in Tokyo")
        } finally {
            journal.close()
        }
    }

    @Test
    fun `values are written compactly`() {
        val result = rows(
            model(
                "SELECT 2.0 / 3 AS third, 1.5::DECIMAL(10, 2) AS dec, 10.0::DOUBLE AS ten, [1, 2] AS list, ['Alex', NULL] AS names, " +
                    "{'a': 1, 'b': 'x'} AS struct, MAP {'k': 1} AS map, '{\"a\": [1, 2], \"b\": \"c d\"}'::JSON AS json, " +
                    "'a|b' || chr(10) || 'c' AS text, NULL AS nothing, true AS yes, DATE '2026-09-25' AS day, " +
                    "INTERVAL 90 MINUTE AS span, '00000000-0000-4000-8000-0000000000b1'::UUID AS id",
            ),
        )
        assertEquals(
            listOf(
                "third|dec|ten|list|names|struct|map|json|text|nothing|yes|day|span|id",
                "0.67|1.5|10|[1, 2]|[Alex, NULL]|{a: 1, b: x}|{k: 1}|{\"a\":[1,2],\"b\":\"c d\"}|a¦b⏎c|NULL|true|2026-09-25|01:30:00|" +
                    "00000000-0000-4000-8000-0000000000b1",
            ),
            result.text.lines().drop(1),
        )
    }

    @Test
    fun `an unavailable Journal fails the query with its reason`() {
        val outcome = runBlocking { SqlRunner(DisabledJournal("disk full"), TestClock()).query("SELECT 1", limits) }
        assertEquals(SqlOutcome.Failed("the Journal is unavailable: disk full"), outcome)
    }
}
