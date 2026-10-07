package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `Journal.write` (M3 §4.1): the memory's write jobs, run by the Journal's writer between its batches. */
class JournalWriteTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)

    private fun broke() = BlockBroken(steve, here, "minecraft:stone", null, creative = false, correctTool = true, placedBy = null)

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.long(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

    private fun Connection.note(id: Long, text: String = "note $id") =
        run("INSERT INTO notes (id, created_at, kind, text, players, refs, status, source) VALUES ($id, now(), 'operator', '$text', [], [], 'open', 'operator')")

    private fun <T> DuckJournal.writeNow(timeoutMs: Long = 10_000, block: (Connection) -> T): T = runBlocking { write(timeoutMs, block) }

    private fun waitUntil(what: String, seconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "still waiting after $seconds s: $what" }
            Thread.sleep(20)
        }
    }

    /** A job that holds the writer until [release] opens; [started] opens once it runs. */
    private class Hold {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun block(connection: Connection): Connection {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "never released" }
            return connection
        }

        fun awaitStarted() = check(started.await(10, TimeUnit.SECONDS)) { "the held job never started" }
    }

    @Test
    fun `a job sees every fact published before it, once a barrier passed`() {
        JournalFixture().use { fixture ->
            fixture.publish(PlayerJoined(steve, here, 1, firstTime = true, isOp = false))
            repeat(10) { fixture.publish(broke()) }
            fixture.sync()
            val seen = fixture.journal.writeNow { connection ->
                connection.note(1)
                connection.long("SELECT count(*) FROM events")
            }
            assertEquals(11, seen)
            assertEquals(listOf("note 1"), fixture.rows("SELECT text FROM notes") { getString(1) }, "committed")
        }
    }

    @Test
    fun `a job that fails is rolled back, its exception reaches the caller, and the writer goes on`() {
        JournalFixture().use { fixture ->
            val thrown = assertFailsWith<IllegalStateException> {
                fixture.journal.writeNow { connection ->
                    connection.note(1)
                    error("the memory refused it")
                }
            }
            assertContains(thrown.message.orEmpty(), "the memory refused it")
            // A database error too: the second row breaks the primary key, the first goes with it.
            assertFailsWith<SQLException> {
                fixture.journal.writeNow { connection ->
                    connection.note(2)
                    connection.note(2)
                }
            }
            assertEquals(0, fixture.count("notes"), "both transactions rolled back")
            assertTrue(fixture.journal.available)

            fixture.publish(broke())
            assertEquals(1, fixture.count("events"), "the next batch is written")
            fixture.journal.writeNow { it.note(3) }
            assertEquals(listOf(3L), fixture.rows("SELECT id FROM notes") { getLong(1) }, "the next job too")
        }
    }

    @Test
    fun `a job that does not start in time times out and is dropped, one already running still commits`() {
        JournalFixture().use { fixture ->
            val hold = Hold()
            runBlocking {
                val held = async(Dispatchers.Default) {
                    runCatching { fixture.journal.write(200) { hold.block(it).also { connection -> connection.note(1) } } }
                }
                hold.awaitStarted()
                val late = assertFailsWith<JournalTimeoutException> { fixture.journal.write(100) { it.note(2) } }
                assertEquals("the Journal write did not finish within 100 ms", late.message)
                assertIs<JournalTimeoutException>(held.await().exceptionOrNull(), "the held job timed out too")
                hold.release.countDown()
            }
            assertEquals(listOf(1L), fixture.rows("SELECT id FROM notes") { getLong(1) }, "the running job committed, the waiting one was dropped")
            assertEquals(5L, fixture.journal.writeNow { it.note(5); 5L }, "the writer goes on")
        }
    }

    @Test
    fun `a closed Journal refuses jobs, jobs queued at the close run until its deadline, then fail`() {
        val first = JournalFixture()
        val scope = CoroutineScope(Dispatchers.Default)
        val queued = (1L..3L).map { id -> scope.async(start = CoroutineStart.UNDISPATCHED) { first.journal.write(10_000) { it.note(id) } } }
        first.close()
        runBlocking { queued.awaitAll() } // run before the close or while it drains
        val refused = assertFailsWith<JournalUnavailableException> { first.journal.writeNow { it.note(4) } }
        assertEquals("the Journal is unavailable: the Journal is closed", refused.message)

        val fixture = JournalFixture(file)
        val hold = Hold()
        runBlocking {
            val held = async(Dispatchers.Default) { fixture.journal.write(10_000) { hold.block(it).also { connection -> connection.note(1) } } }
            hold.awaitStarted()
            val waiting = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.journal.write(10_000) { it.note(2) } } }
            val closer = thread { fixture.journal.close(Duration.ofMillis(300)) }
            closer.join() // the writer is held past the deadline
            hold.release.countDown()
            held.await()
            val failed = assertIs<JournalUnavailableException>(waiting.await().exceptionOrNull())
            assertEquals("the Journal is unavailable: the Journal is closed", failed.message)
        }
        fixture.close() // waits for the writer, which closed the database

        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            assertEquals(1, db.writer.long("SELECT count(*) FROM notes"), "the held job committed, the queued one did not run")
        }
    }

    @Test
    fun `a failed database refuses jobs until it opens again, and jobs then run on the reopened connection`() {
        val fixture = JournalFixture(file, tuning = JournalTuning(reopenIntervalMs = 300))
        fixture.publish(PlayerJoined(steve, here, 1, firstTime = true, isOp = false))
        fixture.sync()
        assertSame(fixture.db.writer, fixture.journal.writeNow { it })

        fixture.db.writer.close()
        val failed = assertFailsWith<JournalUnavailableException> { fixture.journal.writeNow { it.note(1) } }
        assertIs<SQLException>(failed.cause, "the job's own failure is kept as the cause")
        assertFalse(fixture.journal.available)
        assertFailsWith<JournalUnavailableException> { fixture.journal.writeNow { it.note(2) } }

        waitUntil("the database opens again") { fixture.journal.available }
        val (connection, events) = fixture.journal.writeNow { connection ->
            connection.note(3)
            connection to connection.long("SELECT count(*) FROM events")
        }
        assertNotSame(fixture.db.writer, connection, "the reopened connection")
        assertEquals(1, events, "the facts written before the failure")
        assertEquals(listOf(3L), fixture.rows("SELECT id FROM notes") { getLong(1) })
        fixture.close()
    }

    @Test
    fun `a full write queue refuses a job at once`() {
        JournalFixture(tuning = JournalTuning(writeQueueCapacity = 3)).use { fixture ->
            val hold = Hold()
            runBlocking {
                val held = async(Dispatchers.Default) { fixture.journal.write(10_000) { hold.block(it) } }
                hold.awaitStarted()
                val queued = (1L..3L).map { id ->
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { fixture.journal.write(10_000) { it.note(id) } }
                }
                assertEquals(3, fixture.journal.health().queued)
                val refused = assertFailsWith<JournalUnavailableException> { fixture.journal.write(10_000) { it.note(4) } }
                assertEquals("the Journal is unavailable: the Journal's write queue is full", refused.message)
                hold.release.countDown()
                held.await()
                queued.awaitAll()
            }
            assertEquals(listOf(1L, 2L, 3L), fixture.rows("SELECT id FROM notes ORDER BY id") { getLong(1) })
        }
    }

    /**
     * A job every 100 events, without waiting for it; every tenth job after a barrier, waited for. Jobs
     * run in the order they came, each sees at least what the previous one saw, and those after a barrier
     * see exactly what was published before them. No event and no job is lost.
     */
    @Test
    fun `a thousand jobs interleaved with a hundred thousand events lose nothing and keep their order`() {
        JournalFixture(config = JournalConfig(queueCapacity = 1_048_576)).use { fixture ->
            val order = Collections.synchronizedList(ArrayList<Long>())
            val jobs = ArrayList<Deferred<Long>>()
            var published = 0L
            runBlocking {
                for (job in 0L until 1_000L) {
                    repeat(100) {
                        fixture.publish(broke())
                        published++
                    }
                    if (job % 10 == 0L) {
                        fixture.journal.barrier()
                        val seen = fixture.journal.write(10_000) { connection ->
                            order += job
                            connection.note(job + 1, "barrier")
                            connection.long("SELECT count(*) FROM events")
                        }
                        assertEquals(published, seen, "job $job after a barrier")
                    } else {
                        jobs += async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            fixture.journal.write(30_000) { connection ->
                                order += job
                                val seen = connection.long("SELECT count(*) FROM events")
                                connection.note(job + 1, "$seen")
                                seen
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
            assertEquals((0L until 1_000L).toList(), order.toList(), "jobs run in the order they came")
            val seen = fixture.rows("SELECT id, text FROM notes ORDER BY id") { getLong(1) to getString(2) }
            assertEquals(1_000, seen.size)
            var previous = 0L
            seen.forEach { (id, text) ->
                val count = if (text == "barrier") id * 100 else text.toLong()
                assertTrue(count >= previous, "job ${id - 1} saw $count events, the one before $previous")
                previous = count
            }
            assertEquals(100_000, fixture.count("events"))
            assertEquals(0, fixture.journal.health().droppedEvents)
        }
    }

    /** The ids of the memory start past every row and every citation, and past what was handed out (narrator.ids). */
    @Test
    fun `memory ids start past the rows, the ids only cited and the ids handed out, after a restart`() {
        JournalFixture(file).use { fixture ->
            val canon = fixture.journal.nextId(IdKind.MEMORY)
            assertEquals(1, canon)
            fixture.journal.writeNow { connection ->
                connection.run(
                    """
                    INSERT INTO canon (id, kind, created_at, statement, quote, players, importance, status, due_at, source, evidence_events, evidence_tools)
                    VALUES ($canon, 'promise', now(), 'The Narrator promised boots.', 'your boots', [], 6, 'active', now(), 'director', [], []);
                    INSERT INTO recalls VALUES (now(), 700, 'briefing', 'director-c1', 31, 4100);
                    INSERT INTO memory_log VALUES (now(), 'dreamer', 55, 32, 'memory_docs', 'grimoire:1', 'insert', NULL, '{}', NULL);
                    """.trimIndent(),
                )
            }
        }
        JournalFixture(file).use { fixture ->
            assertEquals(701, fixture.journal.nextId(IdKind.MEMORY), "past a memory id only cited by a recall")
            assertEquals(56, fixture.journal.nextId(IdKind.MEMORY_RUN), "past a run only cited by the audit")
            assertEquals(33, fixture.journal.nextId(IdKind.TURN), "past a turn only cited by the memory")
            repeat(99) { fixture.journal.nextId(IdKind.MEMORY) } // handed out, never written: up to 800
        }
        JournalFixture(file).use { fixture ->
            assertEquals(801, fixture.journal.nextId(IdKind.MEMORY), "past the ids handed out (narrator.ids)")
            assertEquals(57, fixture.journal.nextId(IdKind.MEMORY_RUN))
        }
    }

    /** M2 review: the writer thread dying (a bug) used to go unnoticed but for one log line. */
    @Test
    fun `a writer that dies turns the Journal unavailable, says so in the log and tells the operators`() {
        val armed = AtomicBoolean()
        val fixture = JournalFixture(tuning = JournalTuning(healthIntervalMs = 50), hookNanos = { if (armed.get()) throw AssertionError("a bug") else null })
        val told = CopyOnWriteArrayList<String>()
        fixture.journal.tellOperators = { told += it }
        LogCapture().use { logs ->
            fixture.journal.writeNow { it.note(1) }
            armed.set(true)
            waitUntil("the writer dies") { !fixture.journal.available }
            assertContains(logs.text, "The writer thread died (a bug, not a database failure)")
            assertContains(logs.text, "AssertionError: a bug")
            assertEquals(1, told.size, "$told")
            assertContains(told.single(), "The Journal stopped recording: its writer failed (a bug)")
            assertEquals("the writer failed: a bug", fixture.journal.health().lastError)
            val refused = assertFailsWith<JournalUnavailableException> { fixture.journal.writeNow { it.note(2) } }
            assertEquals("the Journal is unavailable: the writer failed: a bug", refused.message)
            fixture.sync() // a barrier returns at once
        }
        fixture.close()
    }
}
