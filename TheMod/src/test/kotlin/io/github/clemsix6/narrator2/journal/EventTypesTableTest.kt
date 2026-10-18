package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/** The `event_types` table (M3 §13): the event catalog, queryable, recreated at every opening. */
class EventTypesTableTest {
    @TempDir
    lateinit var dir: Path

    private fun DuckDatabase.values(sql: String): List<List<Any?>> = writer.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getObject(it) }) }
        }
    }

    @Test
    fun `it holds every type of the events table, in catalog order, and nothing else`() {
        DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { db ->
            assertEquals(
                EventCatalog.types(EventTable.EVENTS).map { it.name },
                db.values("SELECT type FROM event_types").map { it.single() },
            )
            assertEquals(emptyList(), db.values("SELECT type FROM event_types WHERE type IN ('whisper', 'chat', 'position', 'stats_snapshot')"))
        }
    }

    @Test
    fun `each row says what the generic columns hold, quotes included`() {
        DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { db ->
            assertEquals(
                listOf(
                    listOf<Any?>(
                        "A player broke a block.", "block", null, "tool in the main hand (null: bare hand)",
                        "the tool harvests the block's drops", "UUID of the player who had placed this block, if any",
                    ),
                ),
                db.values(
                    "SELECT summary, subject, amount, other, detail ->> 'correct_tool', detail ->> 'placed_by' FROM event_types WHERE type = 'block_break'",
                ),
            )
            assertEquals(
                listOf(listOf<Any?>("A player got up.", null, null, null, """{"night_skipped":"the night was skipped"}""")),
                db.values("SELECT summary, subject, amount, other, CAST(detail AS VARCHAR) FROM event_types WHERE type = 'sleep_end'"),
            )
            assertEquals(listOf(listOf<Any?>(null)), db.values("SELECT detail FROM event_types WHERE type = 'end_portal_lit'"))
            // The keys of events.detail, in the order the Journal writes them.
            EventCatalog.types(EventTable.EVENTS).forEach { type ->
                val keys = db.values("SELECT CAST(json_keys(detail) AS VARCHAR[]) FROM event_types WHERE type = '${type.name}'").single().single()
                assertEquals(type.detail.keys.toList().ifEmpty { null }, (keys as java.sql.Array?)?.let { (it.array as Array<*>).toList() }, type.name)
            }
            assertEquals(
                listOf("type", "summary", "subject", "amount", "other", "detail"),
                db.values("SELECT name FROM columns_of('event_types')").map { it.single() },
            )
            assertEquals(
                listOf(listOf<Any?>(EventTypesTable.COMMENT)),
                db.values("SELECT comment FROM duckdb_tables() WHERE table_name = 'event_types'"),
            )
        }
    }

    @Test
    fun `it follows the catalog at every opening`() {
        val file = dir.resolve(DuckDatabase.FILE_NAME)
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            db.writer.createStatement().use { it.execute("DELETE FROM event_types WHERE type <> 'kill'; UPDATE event_types SET summary = 'stale'") }
            assertEquals(listOf(listOf<Any?>("kill", "stale")), db.values("SELECT type, summary FROM event_types"))
        }
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            assertEquals(EventCatalog.types(EventTable.EVENTS).size.toLong(), db.values("SELECT count(*) FROM event_types").single().single())
            assertEquals(listOf(listOf<Any?>("A player killed an entity.")), db.values("SELECT summary FROM event_types WHERE type = 'kill'"))
        }
    }
}
