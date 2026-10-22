package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.memory.MemorySchema
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
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
import java.time.Instant
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
        "line_labels",
    ) + MemorySchema.TABLES + EventTypesTable.NAME

    private fun DuckDBConnection.strings(sql: String): List<String?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun DuckDBConnection.run(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun `creates the file and applies the migrations once`() {
        DuckDatabase.open(file, options).use { db ->
            assertEquals(5, db.schemaVersion)
            assertEquals(2, db.readers.size, "two for the Director and the memory; one more only with the dashboard (M6 §8.5)")
            assertEquals(tables.sorted(), db.writer.strings("SELECT table_name FROM duckdb_tables() ORDER BY 1"))
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
            db.writer.run("INSERT INTO events (id, ts, game_time, type) VALUES (1, now(), 0, 'join')")
        }
        assertTrue(Files.isRegularFile(file))

        DuckDatabase.open(file, options).use { db ->
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"), "not applied twice")
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
        // The failed opening let go of the file: the next one fails the same way, not as a file still open.
        assertContains(assertFailsWith<SQLException> { DuckDatabase.open(file, options) }.message.orEmpty(), "schema version 99")
    }

    @Test
    fun `a file still open in this process is refused before DuckDB sees it, until it is closed`() {
        // DuckDB would hand this second opening the database already open (its instance cache), shared, and refuse it
        // only by luck, for good: "Cannot change configuration option "TimeZone" - the configuration has been locked".
        val first = DuckDatabase.open(file, options)
        val refused = assertFailsWith<FileStillOpenException> { DuckDatabase.open(dir.resolve(".").resolve(DuckDatabase.FILE_NAME), options) }
        assertEquals("${file.toAbsolutePath().normalize()} is still open in this process: an earlier Journal has not closed it yet", refused.message)
        assertTrue(isTransientOpenFailure(refused), "tried again at the Journal's opening: the earlier Journal's writer ends")
        first.close()
        DuckDatabase.open(file, options).use { second ->
            first.close() // a second close of the first never lets go of the file the second holds
            assertFailsWith<FileStillOpenException> { DuckDatabase.open(file, options) }
            assertEquals(listOf("1"), second.writer.strings("SELECT 1"))
        }
        DuckDatabase.open(file, options).close()
        DuckDatabase.openInMemory(options).use { DuckDatabase.openInMemory(options).close() } // in memory: one each, no file
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
            "line_labels" to LineLabelsRecord::class,
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

    /** M6 §12.3: V4 comes on top of a world of M5, keeps its rows, and its views read the Journal as it was. */
    @Test
    fun `V4 applies to a world at version 3 and keeps its rows`() {
        DuckDatabase.open(file, options).use { db ->
            db.writer.run(
                """
                INSERT INTO chat (id, ts, channel, text, turn_id) VALUES (7, now(), 'narrator', 'Onze secondes. ONZE.', 40);
                DROP VIEW narrator_lines;
                DROP VIEW mem_ideas;
                DROP TABLE line_labels;
                DROP TABLE ideas;
                DELETE FROM schema_version WHERE version >= 4;
                """.trimIndent(),
            )
        }
        DuckDatabase.open(file, options).use { db ->
            assertEquals(5, db.schemaVersion)
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
            assertEquals(listOf("Onze secondes. ONZE. null"), db.writer.strings("SELECT text || ' ' || coalesce(register, 'null') FROM narrator_lines"))
            assertEquals(listOf("0"), db.writer.strings("SELECT count(*) FROM mem_ideas"))
        }
    }

    /** M7-CLARITY: V5 adds `obscure` to the labels of a world at version 4; the labels written before keep it NULL. */
    @Test
    fun `V5 applies to a world at version 4 and keeps its labels`() {
        DuckDatabase.open(file, options).use { db ->
            db.writer.run(
                """
                INSERT INTO chat (id, ts, channel, text, turn_id) VALUES (7, now(), 'narrator', 'Formulaire 12-T : crayon réintégré.', 40);
                DROP VIEW narrator_lines;
                ALTER TABLE line_labels DROP COLUMN obscure;
                INSERT INTO line_labels VALUES (7, now(), 'reflex:claude-haiku-5-5', 'divine_bureaucracy', 0, 0, 0, 0, 0.4, [], NULL, 900);
                DELETE FROM schema_version WHERE version = 5;
                """.trimIndent(),
            )
        }
        DuckDatabase.open(file, options).use { db ->
            assertEquals(5, db.schemaVersion)
            assertEquals(listOf("divine_bureaucracy null 0.4"), db.writer.strings("SELECT register || ' ' || coalesce(obscure::VARCHAR, 'null') || ' ' || repeats FROM narrator_lines"))
        }
    }

    /** M6 §3.3: a line's labels go through the Journal like the chat verdicts, and the Narrator's lines show the latest. */
    @Test
    fun `the line review's labels are appended with every column, and the view of the Narrator's lines reads them`() = JournalFixture().use { fixture ->
        val t0 = Instant.parse("2026-10-21T20:00:00Z")
        val reviewed = fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Onze secondes. ONZE. Gaspard remporte la Pioche.", null, 40, null))
        fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Pas encore relue.", null, 41, null))
        val labels = LineLabelsRecord(
            reviewed.id, t0, "reflex:claude-haiku-5-5", "sports_commentary", 0.0, 0.1, 0.2, 0.05, 0.7, listOf("céleste"),
            "Un commentaire sportif de fin de défi.", 1_400, 0.3,
        )
        fixture.journal.lineLabels(labels)
        fixture.journal.lineLabels(labels.copy(ts = t0.plusSeconds(1), register = "plain", crutches = emptyList(), reason = null, latencyMs = null))

        assertEquals(
            listOf(
                listOf(reviewed.id, t0, "reflex:claude-haiku-5-5", "sports_commentary", 0.0, 0.1, 0.2, 0.05, 0.7, listOf("céleste"), "Un commentaire sportif de fin de défi.", 1_400, 0.3),
                listOf(reviewed.id, t0.plusSeconds(1), "reflex:claude-haiku-5-5", "plain", 0.0, 0.1, 0.2, 0.05, 0.7, emptyList<String>(), null, null, 0.3),
            ),
            fixture.values("SELECT * FROM line_labels ORDER BY ts"),
        )
        assertEquals(
            listOf(
                listOf("Onze secondes. ONZE. Gaspard remporte la Pioche.", "plain", 0.3, 0.7, emptyList<String>()),
                listOf("Pas encore relue.", null, null, null, null),
            ),
            fixture.values("SELECT text, register, obscure, repeats, crutches FROM narrator_lines ORDER BY id"),
            "the latest labels of each line; none for a line not reviewed",
        )
    }

    private fun snakeCase(name: String) = name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }
}
