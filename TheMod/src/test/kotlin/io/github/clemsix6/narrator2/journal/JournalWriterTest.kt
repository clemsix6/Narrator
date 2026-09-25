package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A faulty row never takes the others with it (M1_M2 §5.6). */
class JournalWriterTest {
    private val db = DuckDatabase.openInMemory(JournalFixture.OPTIONS)
    private val writer = JournalWriter(db, TestClock(), flushIntervalMs = 1_000, queueCapacity = 1_000, JournalTuning(), {}, {})

    @AfterEach
    fun close() = db.close()

    private fun chat(id: Long, text: String?) =
        Append(AppendTable.CHAT, listOf(id, Instant.EPOCH, "public", null, null, null, text, null, null))

    private fun turn(id: Long, conversation: String?) =
        Replace(ReplaceTable.TURNS, listOf(id, conversation, Instant.EPOCH, null, "chat", emptyList<Long>(), emptyList<Long>(), null, null, null))

    private fun ids(sql: String): List<Long> = db.readers[0].createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
    }

    @Test
    fun `a faulty row is dropped alone, with its content in the log`() {
        LogCapture().use { logs ->
            // chat.text and turns.conversation_id are NOT NULL.
            val written = writer.writeRows(listOf(chat(1, "a"), chat(2, null), turn(1, "c"), chat(3, "c"), turn(2, null), turn(3, "c")))
            assertEquals(4, written)
            assertEquals(listOf(1L, 3L), ids("SELECT id FROM chat ORDER BY id"))
            assertEquals(listOf(1L, 3L), ids("SELECT turn_id FROM turns ORDER BY turn_id"))
            assertEquals(2L, writer.lost)
            assertTrue(writer.available, "a faulty row does not make the Journal unavailable")
            assertContains(logs.text, "[Journal] dropped chat(2, 1970-01-01T00:00:00Z, public, null, null, null, null, null, null)")
            assertContains(logs.text, "NOT NULL constraint failed: chat.text")
            assertContains(logs.text, "[Journal] dropped turns(2, null")
        }
    }

    @Test
    fun `whole rows keep the last record of each key, in-place updates run in order`() {
        val player = UUID(0, 1)
        val written = writer.writeRows(
            listOf(
                turn(1, "a"), turn(1, "b"), turn(2, "c"), turn(1, "d"),
                Execute(Upsert.PLAYER_JOINED, listOf(player, "Steve", Instant.EPOCH, Instant.EPOCH, false)),
                Execute(Upsert.PLAYER_JOINED, listOf(player, "Steve", Instant.EPOCH, Instant.EPOCH, true)),
                Execute(Upsert.PLAYER_NAME, listOf(player, null, Instant.EPOCH)), // name is NOT NULL
            ),
        )
        assertEquals(6, written)
        val turns = db.readers[0].createStatement().use { statement ->
            statement.executeQuery("SELECT turn_id, conversation_id FROM turns ORDER BY 1").use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1) to rows.getString(2)) }
            }
        }
        assertEquals(listOf(1L to "d", 2L to "c"), turns)
        assertEquals(listOf(2L), ids("SELECT sessions FROM players"))
        assertEquals(1L, writer.lost)
    }

    @Test
    fun `faulty rows among many are isolated, the good ones all written once`() {
        val bad = setOf(17L, 500L, 501L, 999L)
        val rows = (1L..1_000L).map { chat(it, if (it in bad) null else "line $it") }
        assertEquals(996, writer.writeRows(rows))
        assertEquals((1L..1_000L).filterNot { it in bad }, ids("SELECT id FROM chat ORDER BY id"))
        assertEquals(4L, writer.lost)
    }

    @Test
    fun `a record the mapping refuses is dropped, the rest of its batch is written`() {
        val live = JournalWriter(db, TestClock(), flushIntervalMs = 1_000, queueCapacity = 1_000, JournalTuning(), {}, {})
        live.start()
        LogCapture().use { logs ->
            live.submit("not a trace record")
            live.submit(DecisionRecord(1, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null))
            runBlocking { live.barrier() }
            assertContains(logs.text, "[Journal] cannot turn not a trace record into rows")
        }
        assertEquals(listOf(1L), ids("SELECT id FROM engine_decisions"))
        assertEquals(1L, live.lost)
        live.requestClose(System.nanoTime() + 5_000_000_000L, 1_000_000_000L)
        assertTrue(live.awaitStop(5_000))
    }
}
