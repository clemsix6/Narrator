package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The schema description given to the model (M1_M2 §6.5): budget, completeness, privacy, stability. */
class SchemaDocTest {
    private val doc = SchemaDoc.text

    private fun names(db: DuckDatabase, sql: String): List<String> = db.writer.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    @Test
    fun `it fits in 2500 tokens`() {
        // No tokenizer offline: 3 characters a token is pessimistic for this English-and-identifiers text.
        val tokens = ceil(doc.length / 3.0).toInt()
        assertTrue(tokens <= 2_500, "about $tokens tokens (${doc.length} characters):\n$doc")
    }

    @Test
    fun `it lists every table, view, macro and event type the model may use`() {
        DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { db ->
            val main = "database_name = current_database() AND schema_name = 'main' AND NOT internal"
            val tables = names(db, "SELECT table_name FROM duckdb_tables() WHERE $main") - Journal.HIDDEN_FROM_MODEL - "schema_version"
            val views = names(db, "SELECT view_name FROM duckdb_views() WHERE $main")
            val macros = names(db, "SELECT function_name FROM duckdb_functions() WHERE $main AND function_type IN ('macro', 'table_macro')")
            assertEquals(14, tables.size, "$tables")
            assertEquals(18, views.size, "$views")
            assertEquals(8, macros.size, "$macros")
            (views + macros).forEach { name -> assertTrue("\n$name(" in doc, "$name is described") }
            // Game tables with their columns; the engine's traces by name, their columns through columns_of().
            tables.forEach { name -> assertTrue("\n$name(" in doc || "\n$name — " in doc, "$name is described") }
            listOf("players", "sessions", "events", "positions", "chat", "stat_totals").forEach { name ->
                assertTrue("\n$name(" in doc, "$name comes with its columns")
            }
        }
        EventCatalog.types(EventTable.EVENTS).forEach { type ->
            assertTrue(Regex("""\n${type.name}(:|\n)""").containsMatchIn(doc), "event type ${type.name} is described")
        }
        assertTrue("\nevents(id, ts, game_time, type, player_uuid, player, session_id, dim, x, y, z, subject, amount, other, detail JSON) — " in doc)
        assertTrue("\nblock_break: block; -; tool in the main hand (null: bare hand); creative, correct_tool, placed_by\n" in doc)
        assertTrue("\nhotspots(p, t, cell) — " in doc)
    }

    /** Facts of one tick share their millisecond (T07): the model is told to order by `ts, id`, and every view of facts has `id`. */
    @Test
    fun `it tells how to order facts that share a timestamp`() {
        assertTrue("Rows may share a ts: ORDER BY ts, id." in doc)
        listOf(
            "timeline", "chat_log", "narrator_actions", "mining", "building", "deaths", "kills", "damage", "trades", "meals", "crafts",
            "advancements", "visits", "travels", "griefs",
        ).forEach { view -> assertTrue("\n$view(id, ts, " in doc, "$view starts with id, ts") }
    }

    @Test
    fun `it never mentions a hidden table`() {
        Journal.HIDDEN_FROM_MODEL.forEach { table -> assertFalse(table in doc.lowercase(), table) }
        EventCatalog.all.filter { !it.table.visibleToModel }.forEach { type -> assertFalse(type.name in doc, type.name) }
    }

    @Test
    fun `it is the same text every time`() {
        val again = DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { SchemaDoc.generate(it.writer) }
        assertEquals(doc, again)
        // Generated on a Journal that has lived (rows, reopened): the schema is what counts.
        SqlDataset.journal().use { fixture ->
            assertEquals(doc, runBlocking { fixture.journal.read(10_000) { SchemaDoc.generate(it) } })
        }
    }

    @Test
    fun `its examples run as they are`() {
        SqlDataset.journal().use { fixture ->
            val runner = SqlRunner(fixture.journal, fixture.clock)
            SchemaDoc.examples.forEach { sql ->
                assertTrue("\n$sql" in doc, sql)
                val outcome = runBlocking { runner.query(sql, SqlLimits(maxRows = 50, timeoutSeconds = 5)) }
                assertTrue(assertIs<SqlOutcome.Rows>(outcome, "$sql → $outcome").total!! > 0, sql)
            }
        }
    }
}
