package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.SchemaDoc
import io.github.clemsix6.narrator2.journal.SqlAccess
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import io.github.clemsix6.narrator2.journal.SqlDataset
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The memory schema (M3 §3.3): V2 on top of V1, every table commented, ids seeded past the memory's rows. */
class MemorySchemaTest {
    @TempDir
    lateinit var dir: Path

    private val options = DuckDatabase.Options(memoryLimitMb = 128, threads = 1, timeZone = ZoneId.of("UTC"))

    private fun DuckDBConnection.strings(sql: String): List<String?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun DuckDBConnection.run(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun `V1 and V2 apply, once, and the schema is at version 2`() {
        assertEquals(MemorySchema.MIGRATION, DuckDatabase.MIGRATIONS.last())
        val file = dir.resolve(DuckDatabase.FILE_NAME)
        DuckDatabase.open(file, options).use { db ->
            assertEquals(2, db.schemaVersion)
            assertEquals(listOf("1", "2"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
        }
        DuckDatabase.open(file, options).use { db ->
            assertEquals(listOf("1", "2"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"), "not applied twice")
        }
    }

    @Test
    fun `every memory table exists, with a comment`() {
        DuckDatabase.openInMemory(options).use { db ->
            val comments = db.writer.createStatement().use { statement ->
                statement.executeQuery("SELECT table_name, comment FROM duckdb_tables() WHERE schema_name = 'main'").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
            }
            val v1 = setOf(
                "schema_version", "players", "player_names", "sessions", "events", "positions", "chat", "private_messages",
                "chat_signals", "stat_totals", "conversations", "turns", "tasks", "llm_calls", "tool_calls", "engine_decisions",
            )
            assertEquals(MemorySchema.TABLES.toSet(), comments.keys - v1, "the tables V2 creates")
            MemorySchema.TABLES.forEach { table ->
                val comment = comments[table].orEmpty()
                assertTrue(comment.isNotBlank() && comment.length <= 120 && comment.none { it == '<' || it == '>' }, "comment on $table: $comment")
            }
            assertTrue(MemorySchema.TABLES.none { it in Journal.HIDDEN_FROM_MODEL })
        }
    }

    @Test
    fun `the rows the design describes fit the tables`() {
        DuckDatabase.openInMemory(options).use { db ->
            val bartok = "00000000-0000-0000-0000-00000000b471"
            db.writer.run(
                """
                INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags,
                    importance, valence, dim, x, y, z, stability_h, reinforced_play_s, source, run_id, evidence_events, evidence_tools)
                VALUES (120, 'episode', now(), now(), now(), 'Mort dans la lave', 'Bartok est mort dans la lave.', ['$bartok']::UUID[],
                    ['minecraft:lava'], ['death', 'lava'], 7, -2, 'minecraft:the_nether', -120, 14, 330, 118.0, 36000, 'chronicler', 7, [1240], [88]);
                INSERT INTO canon (id, kind, created_at, statement, quote, players, importance, status, due_at, source, evidence_events, evidence_tools)
                VALUES (45, 'promise', now(), 'Le Narrateur a promis des bottes.', 'je te rends tes bottes', [], 6, 'active', now(), 'director', [], []);
                INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, played_s, tool_calls, source, evidence_events)
                VALUES (512, now(), '$bartok', 'Bartok', 'insult', -2, true, true, 'a insulté le Narrateur', 219600, [88], 'director', []);
                INSERT INTO player_traits (player_uuid, trait, side, intensity, confidence, evidence, since, trend, set_at, set_played_s)
                VALUES ('$bartok', 'boldness', 1, 4, 0.85, [120, 512], now(), 1, now(), 219000);
                INSERT OR REPLACE INTO player_traits (player_uuid, trait, side, intensity, confidence, evidence, since, trend, set_at, set_played_s)
                VALUES ('$bartok', 'boldness', -1, 2, 0.5, [512], now(), -1, now(), 220000);
                INSERT INTO memory_docs VALUES ('grimoire', 1, '## Players', [], now(), 'dreamer', 8, NULL);
                INSERT INTO memory_log VALUES (now(), 'operator:Clement', NULL, NULL, 'memories', '120', 'archive', '{"archived": false}', '{"archived": true}', 'forget');
                INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, conversations) VALUES (7, 'batch', 'timer', 'running', now(), []);
                UPDATE memory_runs SET status = 'done', writes = 2 WHERE run_id = 7;
                """.trimIndent(),
            )
            assertEquals(listOf("-1"), db.writer.strings("SELECT side FROM player_traits"), "one row per axis and player")
            assertEquals(listOf("done"), db.writer.strings("SELECT status FROM memory_runs"))
            assertEquals(listOf("0"), db.writer.strings("SELECT recalls FROM memories"), "defaults")
        }
    }

    @Test
    fun `memory ids start past every row and every citation`() {
        DuckDatabase.openInMemory(options).use { db ->
            assertEquals(1L, DuckJournal.seeds(db.writer).getValue(IdKind.MEMORY))
            assertEquals(1L, DuckJournal.seeds(db.writer).getValue(IdKind.MEMORY_RUN))
            db.writer.run(
                """
                INSERT INTO notes (id, created_at, kind, text, players, refs, status, source) VALUES (9, now(), 'operator', 'x', [], [], 'open', 'operator');
                INSERT INTO recalls VALUES (now(), 700, 'briefing', 'director-c1', 31, 4100);
                INSERT INTO memory_log VALUES (now(), 'dreamer', 55, 32, 'memory_docs', 'grimoire:1', 'insert', NULL, '{}', NULL);
                """.trimIndent(),
            )
            val seeds = DuckJournal.seeds(db.writer)
            assertEquals(701L, seeds.getValue(IdKind.MEMORY), "past a memory id only cited by a recall")
            assertEquals(56L, seeds.getValue(IdKind.MEMORY_RUN), "past a run only cited by the audit")
            assertEquals(33L, seeds.getValue(IdKind.TURN), "past a turn only cited by the memory")
            assertEquals(4101L, seeds.getValue(IdKind.EVENT), "past an event only cited by a recall")
        }
    }

    @Test
    fun `the memory tables are readable by sql but not described to the model`() {
        MemorySchema.TABLES.forEach { table ->
            assertFalse(Regex("""\n$table[(\s]""").containsMatchIn(SchemaDoc.text), "$table is not described")
        }
        SqlDataset.journal().use { fixture ->
            val runner = SqlRunner(fixture.journal, fixture.clock)
            val outcome = runBlocking {
                runner.query("SELECT count(*) FROM canon UNION ALL SELECT count(*) FROM memories", SqlLimits(maxRows = 5, timeoutSeconds = 5), SqlAccess.MODEL)
            }
            assertIs<SqlOutcome.Rows>(outcome, "$outcome")
        }
    }
}
