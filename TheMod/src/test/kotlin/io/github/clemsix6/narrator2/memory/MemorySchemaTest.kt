package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.journal.EventTypesTable
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
    fun `V1 to V5 apply, once, and the schema is at version 5`() {
        // V5 (M7-CLARITY) only adds a column of line_labels: the memory still needs V4.
        assertEquals(MemorySchema.MIGRATION, DuckDatabase.MIGRATIONS[3])
        val file = dir.resolve(DuckDatabase.FILE_NAME)
        DuckDatabase.open(file, options).use { db ->
            assertEquals(5, db.schemaVersion)
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
        }
        DuckDatabase.open(file, options).use { db ->
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"), "not applied twice")
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
            val traces = setOf(
                "schema_version", "players", "player_names", "sessions", "events", "positions", "chat", "private_messages",
                "chat_signals", "stat_totals", "conversations", "turns", "tasks", "llm_calls", "tool_calls", "engine_decisions",
                "line_labels",
            )
            // event_types is no migration's: DuckDatabase recreates it at every opening (M3-08).
            assertEquals(MemorySchema.TABLES.toSet(), comments.keys - traces - EventTypesTable.NAME, "the tables V2 and V4 create for the memory")
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
    fun `V3 adds what the engine tracks and the arcs' themes to the canon, commented, and a plan fits`() {
        DuckDatabase.openInMemory(options).use { db ->
            val comments = db.writer.createStatement().use { statement ->
                statement.executeQuery("SELECT column_name, comment FROM duckdb_columns() WHERE table_name = 'canon' AND column_name IN ('mechanic', 'theme')")
                    .use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
            }
            assertEquals(setOf("mechanic", "theme"), comments.keys)
            comments.values.forEach { assertTrue(!it.isNullOrBlank() && it.length <= 120 && it.none { c -> c == '<' || c == '>' }, "comment: $it") }
            db.writer.run(
                """
                INSERT INTO canon (id, kind, created_at, statement, players, importance, status, source, evidence_events, evidence_tools, mechanic)
                VALUES (61, 'plan', now(), 'Le Narrateur a prévu un comité d''accueil pour Bartok.', [], 5, 'active', 'director', [], [], 'rule');
                INSERT INTO canon (id, kind, created_at, statement, players, importance, status, source, evidence_events, evidence_tools, theme)
                VALUES (70, 'arc', now(), 'La vengeance du Warden.', [], 7, 'planned', 'dreamer', [], [], 'vengeance_du_warden');
                """.trimIndent(),
            )
            assertEquals(listOf("rule", null), db.writer.strings("SELECT mechanic FROM canon ORDER BY id"))
            assertEquals(listOf(null, "vengeance_du_warden"), db.writer.strings("SELECT theme FROM canon ORDER BY id"))
        }
    }

    @Test
    fun `no comment of the schema names Jev any more - V3 says the reflexes are a Claude role`() {
        DuckDatabase.openInMemory(options).use { db ->
            val comments = db.writer.strings(
                "SELECT comment FROM duckdb_tables() WHERE comment IS NOT NULL UNION ALL SELECT comment FROM duckdb_columns() WHERE comment IS NOT NULL",
            )
            assertEquals(emptyList(), comments.filter { it.orEmpty().contains("jev", ignoreCase = true) && "older than" !in it.orEmpty() })
            assertEquals(
                listOf("One row per Claude call, role reflex included"),
                db.writer.strings("SELECT comment FROM duckdb_tables() WHERE table_name = 'llm_calls'"),
            )
            val reflex = db.writer.strings(
                "SELECT comment FROM duckdb_columns() WHERE (table_name = 'llm_calls' AND column_name = 'role') OR (table_name = 'chat_signals' AND column_name = 'source')",
            )
            assertEquals(2, reflex.size)
            reflex.forEach { assertTrue(it.orEmpty().contains("reflex") && it.orEmpty().none { c -> c == '<' || c == '>' }, "comment: $it") }
        }
    }

    @Test
    fun `V3 and V4 apply to a world at version 2 whose views already exist, and keep its rows`() {
        val file = dir.resolve(DuckDatabase.FILE_NAME)
        DuckDatabase.open(file, options).use { db ->
            // Back to a world of M3: version 2, without the columns nor the tables of V3 and V4, its views created by an earlier opening.
            db.writer.run(
                """
                INSERT INTO canon (id, kind, created_at, statement, players, importance, status, source, evidence_events, evidence_tools)
                VALUES (45, 'promise', now(), 'Le Narrateur a promis des bottes.', [], 6, 'active', 'director', [], []);
                ALTER TABLE canon DROP COLUMN mechanic;
                ALTER TABLE canon DROP COLUMN theme;
                DROP VIEW mem_ideas;
                DROP VIEW narrator_lines;
                DROP TABLE ideas;
                DROP TABLE line_labels;
                DELETE FROM schema_version WHERE version >= 3;
                """.trimIndent(),
            )
        }
        DuckDatabase.open(file, options).use { db ->
            assertEquals(5, db.schemaVersion)
            assertEquals(listOf("1", "2", "3", "4", "5"), db.writer.strings("SELECT version FROM schema_version ORDER BY 1"))
            assertEquals(listOf("C-45 promise active"), db.writer.strings("SELECT ref || ' ' || kind || ' ' || status FROM mem_canon"), "the view still reads the canon")
            assertEquals(listOf(null), db.writer.strings("SELECT mechanic FROM canon WHERE id = 45"))
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

    /** M6 §6.2, §12.3: an idea is a memory row, on the memory's id sequence, written by a run of the memory. */
    @Test
    fun `an idea fits its table, and the memory's ids and runs start past it`() {
        DuckDatabase.openInMemory(options).use { db ->
            val bartok = "00000000-0000-0000-0000-00000000b471"
            db.writer.run(
                """
                INSERT INTO ideas (id, created_at, kind, title, pitch, fit, players, register, novelty, fun, fairness, refs, status,
                    expires_play_s, source, run_id, conversation_id)
                VALUES (900, now(), 'challenge', 'La course au pic', 'Une course au sommet du pic enneigé au nord de la base.',
                    'Bartok et Mimosa en ligne', ['$bartok']::UUID[], 'game_show', 4, 4, 5, [45], 'open', 360000, 'storyteller', 60,
                    'storyteller-1a2b3c4d');
                UPDATE ideas SET dealt = dealt + 1, last_dealt_at = now() WHERE id = 900;
                """.trimIndent(),
            )
            assertEquals(listOf("1 open"), db.writer.strings("SELECT dealt || ' ' || status FROM ideas"))
            assertEquals(listOf("P-900 challenge La course au pic"), db.writer.strings("SELECT ref || ' ' || kind || ' ' || title FROM mem_ideas"))
            val seeds = DuckJournal.seeds(db.writer)
            assertEquals(901L, seeds.getValue(IdKind.MEMORY))
            assertEquals(61L, seeds.getValue(IdKind.MEMORY_RUN))
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
