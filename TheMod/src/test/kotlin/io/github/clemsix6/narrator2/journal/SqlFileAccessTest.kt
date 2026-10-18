package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.journal.SqlDataset.SECRET
import io.github.clemsix6.narrator2.journal.SqlDataset.bartok
import io.github.clemsix6.narrator2.journal.SqlDataset.mimosa
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The model's `sql` against DuckDB's own files, on a Journal **on disk** (M2 review, finding 19).
 *
 * With `enable_external_access = false`, DuckDB still lets SQL read the database file, its `.wal`, its
 * `.wal.checkpoint` and the `<db>.tmp/` folder where it spills blocks, and `current_setting('allowed_paths')`
 * says where they are. The private messages are written by the real Journal, then spilled to `<db>.tmp/`
 * by a model query that sorts more than the memory limit (allowed: sorting is no attack).
 *
 * Every attack is refused for the model, with neither a private message nor a path in the answer. The
 * counter-proof runs the same reads with the operator's access, which has none of the model's barriers:
 * they find the private messages and the paths, so each refused attack was real.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlFileAccessTest {
    private lateinit var dir: Path
    private lateinit var fixture: JournalFixture
    private lateinit var runner: SqlRunner

    /** The database file, the WAL, the spill folder and one spilled file, with forward slashes as a model would write them. */
    private lateinit var db: String
    private lateinit var wal: String
    private lateinit var tmp: String
    private lateinit var spilled: String

    /** Set in [open] when a raw read of a spilled file returned a private message: the leak is real. */
    private var spilledSecret: String? = null

    private val limits = SqlLimits(maxRows = 50, timeoutSeconds = 30)

    /** The operator's access, with room for whole paths and many rows. */
    private val wide = SqlLimits(maxRows = 200, timeoutSeconds = 30, maxCellChars = 2_000, maxChars = 100_000)

    @BeforeAll
    fun open(@TempDir dir: Path) {
        this.dir = dir
        // A low memory limit lets a small query spill without loading the shared test JVM.
        fixture = JournalFixture(
            file = dir.resolve(DuckDatabase.FILE_NAME),
            config = JournalConfig(queueCapacity = 1_048_576),
            options = DuckDatabase.Options(memoryLimitMb = 64, threads = 2, timeZone = java.time.ZoneId.of("UTC")),
        )
        repeat(WHISPERS) { fixture.publish(ChatMessage(bartok, ChatChannel.WHISPER, "$SECRET #$it", listOf(mimosa.name))) }
        fixture.publish(ChatMessage(bartok, ChatChannel.PUBLIC, "hello", emptyList()))
        fixture.sync()
        runner = SqlRunner(fixture.journal, fixture.clock)

        val file = fixture.db.file!!
        db = slashes(file.toString())
        wal = "$db.wal"
        tmp = "$db.tmp"
        // Grouping 3 million distinct keys cannot fit the 64 MB of the test database: the hash aggregate spills
        // its partitions, and under that pressure DuckDB evicts the recently written private_messages blocks to
        // <db>.tmp/, where their bytes stay after the query. A second read connection then reads the spilled
        // files' bytes back: that is where the private message leaked in the M2 review. (The aggregate returns
        // one row, so no large result is materialised; the whole thing stays within the memory limit.)
        fixture.db.readers[0].createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM (SELECT g, min(s) AS m FROM (SELECT range AS g, repeat('x', 50) AS s FROM range(3000000)) GROUP BY g)",
            ).use { rows -> while (rows.next()) rows.getObject(1) }
        }
        val files = Files.list(file.resolveSibling("${file.name}.tmp")).use { stream -> stream.filter { it.isRegularFile() }.toList() }
        assertTrue(files.isNotEmpty(), "DuckDB spilled nothing")
        spilled = slashes(files.maxBy { Files.size(it) }.toString())
        fixture.db.readers[1].createStatement().use { statement ->
            files.forEach { spill ->
                if (spilledSecret != null) return@forEach
                statement.executeQuery(
                    "SELECT regexp_extract(CAST(content AS VARCHAR), '$SECRET #[0-9]+') AS hit " +
                        "FROM read_blob('${slashes(spill.toString())}') WHERE hit <> '' LIMIT 1",
                ).use { rows -> if (rows.next()) spilledSecret = rows.getString(1) }
            }
        }

        // Views that read DuckDB's files: the plan check must stop them, even though the query names no file.
        runBlocking {
            fixture.journal.read(10_000) { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE VIEW spilled_blocks AS SELECT content FROM read_blob('$tmp/*')")
                    statement.execute("CREATE VIEW spill_files AS SELECT * FROM glob('$tmp/*')")
                    statement.execute("CREATE MACRO wal_text() AS TABLE SELECT content FROM read_text('$wal')")
                }
            }
        }
    }

    @AfterAll
    fun close() = fixture.close()

    private fun model(sql: String, limits: SqlLimits = this.limits) = runBlocking { runner.query(sql, limits) }

    private fun operator(sql: String) = runBlocking { runner.query(sql, wide, SqlAccess.OPERATOR) }

    private fun slashes(path: String) = path.replace('\\', '/')

    /** The attacks, with the real paths. */
    private fun attacks(): List<String> = listOf(
        // read_blob and read_text on the database, the WAL, its checkpoint and the spilled files.
        "SELECT content FROM read_blob('$db')",
        "SELECT content FROM read_blob('$wal')",
        "SELECT content FROM read_text('$wal')",
        "SELECT content FROM read_blob('$db.wal.checkpoint')",
        "SELECT content FROM read_blob('$tmp/*')",
        "SELECT content FROM read_text('$tmp/*')",
        "SELECT regexp_extract(CAST(content AS VARCHAR), 'the base[^\\\\]*') FROM read_blob('$spilled')",
        "SELECT content FROM read_blob('${spilled.replace('/', '\\')}')",
        // Where the files are.
        "SELECT * FROM glob('$tmp/*')",
        "SELECT * FROM glob('${slashes(dir.toString())}/**')",
        "SELECT current_setting('allowed_paths')",
        "SELECT unnest(current_setting('allowed_paths'))",
        "SELECT current_setting('temp_directory')",
        "SELECT json_serialize_plan('SELECT current_setting(''allowed_paths'')')",
        // Replacement scans: a path or a file name as a table.
        "SELECT * FROM '$db'",
        "SELECT * FROM \"$db\"",
        "FROM '$wal'",
        "SELECT * FROM '$spilled'",
        "DESCRIBE '$db'",
        "SUMMARIZE '$wal'",
        "SELECT * FROM 'narrator.duckdb'",
        // Other readers.
        "SELECT * FROM sniff_csv('$wal')",
        "SELECT * FROM read_csv('$spilled')",
        "SELECT * FROM read_duckdb('$db')",
        "SELECT * FROM parquet_metadata('$db')",
        "SELECT * FROM read_json_objects('$wal')",
        // Mixed case, quotes and qualified names.
        "SELECT CONTENT FROM READ_BLOB('$tmp/*')",
        "select content from Read_Text('$wal')",
        "SELECT * FROM GLOB('$tmp/*')",
        "SELECT Current_Setting('allowed_paths')",
        "SELECT * FROM \"read_blob\"('$wal')",
        "SELECT * FROM main.read_blob('$wal')",
        "SELECT * FROM system.main.read_blob('$wal')",
        // Subqueries and CTEs.
        "SELECT (SELECT content FROM read_blob('$wal')) AS c",
        "SELECT * FROM (SELECT content FROM read_blob('$tmp/*')) sub",
        "SELECT * FROM chat_log WHERE text IN (SELECT CAST(content AS VARCHAR) FROM read_blob('$tmp/*'))",
        "SELECT * FROM chat_log WHERE EXISTS (SELECT 1 FROM read_text('$wal'))",
        "SELECT * FROM chat_log, LATERAL (SELECT * FROM glob('$tmp/*')) g",
        "SELECT text FROM chat_log UNION ALL SELECT CAST(content AS VARCHAR) FROM read_blob('$tmp/*')",
        "WITH f AS (SELECT * FROM read_blob('$tmp/*')) SELECT content FROM f",
        "WITH p AS (SELECT current_setting('allowed_paths') AS p) SELECT * FROM p",
        "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 2) SELECT * FROM r, read_blob('$wal')",
        "SELECT * FROM query('SELECT content FROM read_blob(''$wal'')')",
        // Views and a macro that read the files: stopped by the plan check.
        "SELECT * FROM spilled_blocks",
        "SELECT count(*) FROM spill_files",
        "SELECT * FROM wal_text()",
    )

    /** No private message, no path, not even a spilled file's name. */
    private fun assertClean(outcome: SqlOutcome, sql: String) {
        val text = outcome.toString()
        val folder = slashes(dir.toString())
        listOf("behind the waterfall", folder, folder.replace('/', '\\'), "duckdb_temp_storage").forEach { secret ->
            assertFalse(secret in text || secret in slashes(text), "$sql → $outcome holds $secret")
        }
    }

    @Test
    fun `the model reads none of DuckDB's own files, and learns nowhere where they are`() {
        attacks().forEach { sql ->
            val outcome = model(sql)
            assertIs<SqlOutcome.Rejected>(outcome, "$sql → $outcome")
            assertClean(outcome, sql)
        }
    }

    @Test
    fun `the attacks are real, since without the model's barriers they reach the files, the paths and the private messages`() {
        // The paths, read plainly and in full.
        val paths = assertIs<SqlOutcome.Rows>(operator("SELECT unnest(current_setting('allowed_paths')) AS p")).text.lines().map(::slashes)
        assertTrue(db in paths && wal in paths, "$paths")
        assertContains(assertIs<SqlOutcome.Rows>(operator("SELECT * FROM glob('$tmp/*')")).text, "duckdb_temp_storage")

        // The same attacks, with the operator's access, are never stopped by a model barrier: DuckDB runs them
        // and either returns rows or fails on its own (the OS lock the Journal holds on the file, an OOM on a
        // large one), whereas the model gets a Rejected before anything runs. The barriers are the difference.
        attacks().forEach { sql ->
            assertIs<SqlOutcome.Rejected>(model(sql), "model $sql")
            assertFalse(operator(sql) is SqlOutcome.Rejected, "operator $sql was rejected too")
        }

        // The bytes: a read of a small spilled file in [open] returned a private message, so the leak the
        // model's barriers close is real (in the M2 review the private message came out of <db>.tmp/ this way).
        // Which blocks DuckDB evicts to the spill files is not deterministic (about one run in two in the unit
        // test JVM): when none held a private message, this last check is skipped rather than failed.
        assumeTrue(spilledSecret != null, "DuckDB spilled no private message in this run")
        assertContains(spilledSecret.orEmpty(), "$SECRET #", message = "no spilled file held a private message")
    }

    @Test
    fun `the model still reads the Journal, the spilled files do not stop it`() {
        val text = assertIs<SqlOutcome.Rows>(model("SELECT player, text FROM chat_log")).text
        assertContains(text, "Bartok|hello")
        assertFalse("behind the waterfall" in text)
    }

    private companion object {
        /** Private messages, enough that some of their blocks are evicted to the spill files under the 64 MB limit. */
        const val WHISPERS = 5_000
    }
}
