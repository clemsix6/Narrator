package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Journal whose database fails gets it back (M2 review §21). */
class JournalRecoveryTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)

    private fun joined(player: PlayerRef, session: Long) = PlayerJoined(player, here, session, firstTime = false, isOp = false)

    private fun left(player: PlayerRef, session: Long, seconds: Long) = PlayerLeft(player, here, session, seconds, LeaveReason.LEAVE)

    private fun broke(player: PlayerRef) = BlockBroken(player, here, "minecraft:stone", null, creative = false, correctTool = true, placedBy = null)

    private fun decision(id: Long) = DecisionRecord(id, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null)

    private fun waitUntil(what: String, seconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "still waiting after $seconds s: $what" }
            Thread.sleep(20)
        }
    }

    /**
     * The write connection is closed under the writer's feet: the database stops answering. Another
     * program (the DuckDB command line, in another JVM) takes the file as soon as the Journal lets go of
     * it, holds it while the Journal tries to open it again, then quits.
     *
     * The Journal's first attempt waits until the other program holds the file: left to the clock, the
     * Journal could open the file again before a slow child took it, and the outage would end at once.
     */
    @Test
    fun `a failed database is opened again, the operators are told once and the losses are recorded`() {
        val held = CountDownLatch(1)
        val attempts = AtomicInteger()
        val fixture = JournalFixture(file, tuning = JournalTuning(reopenIntervalMs = 500)) {
            attempts.incrementAndGet()
            check(held.await(60, TimeUnit.SECONDS)) { "the other program never took the file" }
        }
        val told = CopyOnWriteArrayList<String>()
        fixture.journal.tellOperators = { told += it }
        LogCapture().use { logs ->
            fixture.publish(joined(steve, 1))
            fixture.publish(broke(steve))
            fixture.sync()

            JournalChild.start("hold", file).use { cli ->
                cli.awaitLine(JournalChild.WAITING)
                fixture.db.writer.close()
                fixture.publish(broke(steve)) // in the batch that fails
                fixture.sync()
                assertFalse(fixture.journal.available)
                assertContains(logs.text, "the Journal stops recording")
                cli.awaitLine(JournalChild.READY)
                held.countDown()

                fixture.publish(joined(alex, 2)) // lost, but the session is still followed
                repeat(4) { fixture.publish(broke(steve)) }
                fixture.journal.decision(decision(fixture.journal.nextId(IdKind.DECISION)))
                waitUntil("two failed attempts to open the database again") { attempts.get() >= 3 } // the third one started
                assertContains(logs.text, "opening the database again failed (attempt 1)")
                assertFalse(fixture.journal.available, "the file is held by another program")
                assertEquals(1, told.size, "the operators are told once: $told")
                assertContains(told.single(), "The Journal failed and records nothing")
                cli.kill()
            }

            waitUntil("the Journal records again") { told.size == 2 }
            assertTrue(fixture.journal.available)
            assertContains(told[1], "The Journal records again")
            assertContains(told[1], "7 records were lost")
            assertContains(logs.text, "opened the database again")

            val (reason, detail) = fixture.rows(
                "SELECT reason, detail::VARCHAR FROM engine_decisions WHERE kind = 'journal_health' AND decision = 'reopened'",
            ) { getString(1) to getString(2) }.single()
            val json = Json.parseToJsonElement(detail).jsonObject
            assertEquals(7L, json.getValue("lost").jsonPrimitive.long, "1 fact in the failed batch, 5 facts and 1 trace while down: $reason")
            assertEquals(5L, json.getValue("lost_facts").jsonPrimitive.long)
            assertEquals(1L, json.getValue("lost_traces").jsonPrimitive.long)
            assertTrue(json.getValue("attempts").jsonPrimitive.long >= 3, detail)
            assertContains(reason, "Connection was closed")

            // Facts go on, with the sessions followed while the database was down.
            fixture.publish(broke(alex))
            fixture.publish(left(alex, 2, 30))
            assertEquals(listOf(2L), fixture.rows("SELECT session_id FROM events WHERE player = 'Alex' AND type = 'block_break'") { getLong(1) })
            assertEquals(listOf(2L), fixture.rows("SELECT session_id FROM sessions WHERE player = 'Alex' AND left_at IS NOT NULL") { getLong(1) })
            assertEquals(2L, fixture.count("events WHERE player = 'Steve'"), "the facts written before the failure are still there")
        }
        fixture.close()
    }

    /** Nothing to open again in memory: the Journal keeps trying, and the operators hear of it once. */
    @Test
    fun `a database in memory is never opened again, and the operators hear of it once`() {
        val fixture = JournalFixture(tuning = JournalTuning(reopenIntervalMs = 50))
        val told = CopyOnWriteArrayList<String>()
        fixture.journal.tellOperators = { told += it }
        LogCapture().use { logs ->
            fixture.db.writer.close()
            fixture.publish(broke(steve))
            fixture.sync()
            waitUntil("an attempt to open it again") { "opening the database again failed (attempt 1)" in logs.text }
            Thread.sleep(300)
            assertFalse(fixture.journal.available)
            assertEquals(1, told.size, "told once: $told")
            assertContains(logs.text, "a database in memory cannot be opened again")
        }
        fixture.close()
    }

    /** A query that spills to disk (`<db>.tmp/`) must not fill the disk the Journal writes to. */
    @Test
    fun `the query data spilled to disk is bounded`() {
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            val size = db.readers.first().createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('max_temp_directory_size')").use { it.next(); it.getString(1) }
            }
            assertEquals("1.0 GiB", size, "by default, DuckDB lets it take 90 % of the free disk space")
        }
    }
}
