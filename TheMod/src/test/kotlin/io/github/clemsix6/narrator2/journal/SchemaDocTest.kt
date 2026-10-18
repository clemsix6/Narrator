package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.memory.MemorySchema
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The schema description given to the model (M1_M2 §6.5, M3 §13): budgets, completeness, privacy, stability. */
class SchemaDocTest {
    private val doc = SchemaDoc.text

    private val sections = DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { SchemaDoc.sections(it.writer) }

    private fun names(db: DuckDatabase, sql: String): List<String> = db.writer.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    @Test
    fun `it fits in 2500 tokens, each section within its budget`() {
        // No tokenizer offline: 3 characters a token is pessimistic for this English-and-identifiers text.
        val tokens = ceil(doc.length / 3.0).toInt()
        val measured = sections.entries.joinToString { (section, text) -> "${section.name.lowercase()} ${text.length}/${section.budget}" }
        println("SchemaDoc: ${doc.length} characters, about $tokens tokens ($measured)")
        assertTrue(tokens <= 2_500 && doc.length <= SchemaDoc.MAX_CHARS, "about $tokens tokens (${doc.length} characters):\n$doc")
        assertEquals(SchemaDoc.Section.entries, sections.keys.toList(), "every section, in order")
        sections.forEach { (section, text) -> assertTrue(text.length <= section.budget, "${section.name}: ${text.length} > ${section.budget}:\n$text") }
        assertTrue(SchemaDoc.Section.entries.sumOf { it.budget } <= SchemaDoc.MAX_CHARS, "the budgets fit in the whole")
        assertEquals(doc, sections.values.joinToString("\n"))
    }

    @Test
    fun `it lists every table, view and macro the model may use`() {
        DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { db ->
            val main = "database_name = current_database() AND schema_name = 'main' AND NOT internal"
            // The memory's tables are read through its own views and tools (M3 §13).
            val tables = names(db, "SELECT table_name FROM duckdb_tables() WHERE $main") - Journal.HIDDEN_FROM_MODEL - "schema_version" -
                MemorySchema.TABLES.toSet()
            val views = names(db, "SELECT view_name FROM duckdb_views() WHERE $main")
            val macros = names(db, "SELECT function_name FROM duckdb_functions() WHERE $main AND function_type IN ('macro', 'table_macro')")
            assertEquals(15, tables.size, "$tables")
            assertEquals(24, views.size, "$views")
            assertEquals(8, macros.size, "$macros")
            (views + macros).forEach { name -> assertTrue("\n$name(" in doc, "$name is described") }
            // Game tables with their columns; the engine's traces by name, their columns through columns_of().
            tables.forEach { name -> assertTrue("\n$name(" in doc || "\n$name — " in doc, "$name is described") }
            listOf("players", "sessions", "events", "positions", "chat", "stat_totals", "event_types").forEach { name ->
                assertTrue("\n$name(" in doc, "$name comes with its columns")
            }
        }
        assertTrue("\nevents(id, ts, game_time, type, player_uuid, player, session_id, dim, x, y, z, subject, amount, other, detail JSON) — " in doc)
        assertTrue("\nhotspots(p, t, cell) — " in doc)
    }

    /** M3 §13: the event types are a table the model queries, no longer a list in the description. */
    @Test
    fun `the event types are a table, not a list`() {
        val line = "event_types(type, summary, subject, amount, other, detail JSON) — What each events.type means and stores in subject, amount, other and detail"
        assertTrue(line.endsWith(EventTypesTable.COMMENT))
        // Right under the events it describes.
        val tables = sections.getValue(SchemaDoc.Section.TABLES).lines()
        assertEquals(line, tables[tables.indexOfFirst { it.startsWith("events(") } + 1], tables.joinToString("\n"))
        assertFalse("EVENT TYPES" in doc)
        EventCatalog.types(EventTable.EVENTS).forEach { type ->
            assertFalse("\n${type.name}:" in doc || "\n${type.name}\n" in doc, "${type.name} is no longer listed")
            assertFalse(type.summary in doc, type.summary)
        }
    }

    /** M3 §13: six views of the memory, in their own section, and none of the memory's tables. */
    @Test
    fun `the memory is described through its six views`() {
        val memory = sections.getValue(SchemaDoc.Section.MEMORY)
        assertEquals(
            listOf(
                "mem_memories(ref, kind, occurred, title, content, players[], tags[], importance, valence, strength)",
                "mem_canon(ref, kind, status, statement, quote, players[], due_at, resolved_at, resolution, created_at)",
                "mem_interactions(ref, ts, player, kind, valence, disrespect, summary, quote)",
                "mem_favor(player, favor, trend, disrespect)",
                "mem_stats(player, stat, value, detail JSON)",
                "mem_chronicle(ref, level, period_from, period_to, title, summary)",
            ),
            memory.lines().drop(1).map { it.substringBefore(" — ") },
        )
        assertTrue(memory.lines().drop(1).all { " — " in it }, "every memory view has its comment:\n$memory")
        assertTrue(memory.startsWith("MEMORY (") && "M-812, C-45" in memory.lines().first(), memory)
        assertFalse("mem_" in sections.getValue(SchemaDoc.Section.VIEWS), "the memory's views are only in their section")
        MemorySchema.TABLES.forEach { table -> assertFalse(Regex("""\n$table[(\s]""").containsMatchIn(doc), "$table is not described") }
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

    /** M2 review, finding 15: the Narrator's lines are told by `speaker`, never by a player name a player could take. */
    @Test
    fun `it tells the Narrator's lines by their speaker`() {
        assertTrue("\nchat_log(id, ts, channel, speaker, player, recipients[], text) — " in doc, doc)
        assertTrue("speaker narrator or operator, player NULL" in doc)
        assertFalse("NARRATOR" in doc)
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
            assertTrue(SchemaDoc.examples.any { "mem_" in it }, "an example reads the memory")
            SchemaDoc.examples.forEach { sql ->
                assertTrue("\n$sql" in doc, sql)
                val outcome = runBlocking { runner.query(sql, SqlLimits(maxRows = 50, timeoutSeconds = 5)) }
                assertTrue(assertIs<SqlOutcome.Rows>(outcome, "$sql → $outcome").total!! > 0, sql)
            }
        }
    }
}
