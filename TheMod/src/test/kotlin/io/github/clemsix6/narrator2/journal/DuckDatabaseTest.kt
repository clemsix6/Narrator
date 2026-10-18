package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.memory.MemorySchema
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.ZoneId
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuckDatabaseTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)
    private val options = DuckDatabase.Options(memoryLimitMb = 256, threads = 2, timeZone = ZoneId.of("Asia/Tokyo"))

    private val tables = listOf(
        "schema_version", "players", "player_names", "sessions", "events", "positions", "chat", "private_messages",
        "chat_signals", "stat_totals", "conversations", "turns", "tasks", "llm_calls", "tool_calls", "engine_decisions",
    ) + MemorySchema.TABLES + EventTypesTable.NAME

    private fun DuckDBConnection.strings(sql: String): List<String?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun DuckDBConnection.run(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun `creates the file and applies the migrations once`() {
        DuckDatabase.open(file, options).use { db ->
            assertEquals(2, db.schemaVersion)
            assertEquals(2, db.readers.size)
            assertEquals(tables.sorted(), db.writer.strings("SELECT table_name FROM duckdb_tables() ORDER BY 1"))
            assertEquals(listOf("1", "2"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
            db.writer.run("INSERT INTO events (id, ts, game_time, type) VALUES (1, now(), 0, 'join')")
        }
        assertTrue(Files.isRegularFile(file))

        DuckDatabase.open(file, options).use { db ->
            assertEquals(listOf("1", "2"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"), "not applied twice")
            assertEquals(listOf("1"), db.readers[0].strings("SELECT count(*) FROM events"), "data kept")
        }
    }

    @Test
    fun `SQL cannot reach the file system`() {
        val csv = dir.resolve("secret.csv")
        Files.writeString(csv, "a,b\n1,2\n")
        val path = csv.toAbsolutePath().toString().replace('\\', '/')
        DuckDatabase.open(file, options).use { db ->
            (db.readers + db.writer).forEach { connection ->
                listOf("SELECT * FROM read_csv('$path')", "SELECT * FROM glob('${dir.toString().replace('\\', '/')}/*')").forEach { sql ->
                    val error = assertFailsWith<SQLException>(sql) { connection.strings(sql) }
                    assertContains(error.message.orEmpty(), "file system operations are disabled")
                }
                val attach = assertFailsWith<SQLException> { connection.run("ATTACH '${path}.db' AS other") }
                assertContains(attach.message.orEmpty(), "disabled")
            }
        }
    }

    @Test
    fun `the configuration is locked on every connection`() {
        DuckDatabase.open(file, options).use { db ->
            (db.readers + db.writer).forEach { connection ->
                listOf("SET threads = 8", "SET enable_external_access = true", "SET TimeZone = 'UTC'", "RESET lock_configuration").forEach { sql ->
                    val error = assertFailsWith<SQLException>(sql) { connection.run(sql) }
                    assertContains(error.message.orEmpty(), "the configuration has been locked")
                }
            }
        }
    }

    @Test
    fun `readers inherit the time zone and the limits`() {
        DuckDatabase.open(file, options).use { db ->
            (db.readers + db.writer).forEach { connection ->
                assertEquals(listOf("Asia/Tokyo"), connection.strings("SELECT current_setting('TimeZone')"))
                assertEquals(listOf("2"), connection.strings("SELECT current_setting('threads')"))
                assertEquals(listOf("false"), connection.strings("SELECT current_setting('enable_external_access')"))
                assertEquals(listOf("false"), connection.strings("SELECT current_setting('autoload_known_extensions')"))
            }
            val limit = db.writer.strings("SELECT current_setting('memory_limit')").single().orEmpty()
            assertContains(limit, "MiB", message = "memory limit $limit")
            db.writer.run("INSERT INTO chat (id, ts, channel, text) VALUES (5, TIMESTAMPTZ '2026-09-25 12:00:00+00', 'public', 'hi')")
            assertEquals(listOf("2026-09-25 21:00:00+09"), db.readers[1].strings("SELECT ts::VARCHAR FROM chat"))
        }
    }

    @Test
    fun `the time zone defaults to the server's`() {
        assertEquals(ZoneId.systemDefault(), DuckDatabase.Options.of(JournalConfig()).timeZone)
        assertEquals(ZoneId.of("Europe/Paris"), DuckDatabase.Options.of(JournalConfig(timeZone = "Europe/Paris")).timeZone)
        assertEquals(DuckDatabase.Options(memoryLimitMb = 512, threads = 2), DuckDatabase.Options.of(JournalConfig()))
    }

    @Test
    fun `a database from a newer version of the mod is refused`() {
        DuckDatabase.open(file, options).use { it.writer.run("INSERT INTO schema_version VALUES (99, now())") }
        val error = assertFailsWith<SQLException> { DuckDatabase.open(file, options) }
        assertContains(error.message.orEmpty(), "schema version 99")
        assertContains(error.message.orEmpty(), "use a newer version of the mod")
    }

    @Test
    fun `migrations are bundled and numbered in order`() {
        assertEquals((1..DuckDatabase.MIGRATIONS.size).toList(), DuckDatabase.MIGRATIONS.map { DuckDatabase.versionOf(it) })
        DuckDatabase.MIGRATIONS.forEach { name ->
            assertTrue(DuckDatabase::class.java.getResource("${DuckDatabase.SCRIPTS}$name") != null, "$name is bundled")
        }
        assertEquals(DuckDatabase.MIGRATIONS.size, DuckDatabase.SCHEMA_VERSION)
    }

    @Test
    fun `trace records follow their tables column for column`() {
        val records = mapOf(
            "llm_calls" to LlmCallRecord::class,
            "tool_calls" to ToolCallRecord::class,
            "engine_decisions" to DecisionRecord::class,
            "turns" to TurnRecord::class,
            "conversations" to ConversationRecord::class,
            "tasks" to TaskRecord::class,
            "chat_signals" to ChatSignalsRecord::class,
        )
        DuckDatabase.openInMemory(options).use { db ->
            records.forEach { (table, record) ->
                val columns = db.writer.strings(
                    "SELECT column_name FROM duckdb_columns() WHERE table_name = '$table' ORDER BY column_index",
                )
                val properties = record.primaryConstructor!!.parameters.map { snakeCase(it.name!!) }
                assertEquals(columns, properties, "${record.simpleName} vs $table")
            }
        }
    }

    @Test
    fun `every event table exists, and the tables the model may read are commented`() {
        DuckDatabase.openInMemory(options).use { db ->
            val comments = db.writer.createStatement().use { statement ->
                statement.executeQuery("SELECT table_name, comment FROM duckdb_tables()").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
            }
            EventTable.entries.forEach { assertTrue(it.sqlName in comments, "table ${it.sqlName}") }
            tables.filter { it != "schema_version" }.forEach { table ->
                assertTrue(!comments[table].isNullOrBlank(), "comment on $table")
            }
            assertEquals(listOf("id", "ts", "player_uuid", "player", "recipients", "text"),
                db.writer.strings("SELECT column_name FROM duckdb_columns() WHERE table_name = 'private_messages' ORDER BY column_index"))
        }
    }

    private fun snakeCase(name: String) = name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }
}
