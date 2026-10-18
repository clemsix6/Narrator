package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.testing.LogCapture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ids never go back, even after a crash at the worst moment (M2 review §21). */
class IdReservationsTest {
    @TempDir
    lateinit var dir: Path

    private val windows = System.getProperty("os.name").startsWith("Windows")

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)
    private val idsFile: Path get() = dir.resolve(IdReservations.FILE_NAME)

    /** What `narrator.ids` holds now. */
    private fun onDisk(): Map<IdKind, Long> = Files.readAllLines(idsFile).filterNot { it.startsWith("#") }.associate { line ->
        val (kind, value) = line.split('=')
        IdKind.valueOf(kind) to value.toLong()
    }

    /**
     * The invariant that makes a crash harmless: when an id is handed out, the file already covers it.
     * The test holds [IdReservations.lock] from the moment it asks for an id until it has read the file:
     * no reservation in the background can cover the id in between, and none tries to replace the file
     * while the test has it open (a program that does is the next test).
     */
    @Test
    fun `an id is reserved on disk before it is handed out`() {
        val ids = IdReservations.open(idsFile, IdKind.entries.associateWith { 1L }, blocks = 4, eventBlock = 4)
        repeat(50) {
            synchronized(ids.lock) {
                val turn = ids.next(IdKind.TURN)
                assertTrue(onDisk().getValue(IdKind.TURN) > turn, "turn $turn handed out past the file: ${onDisk()}")
            }
        }
        // The bus hands out event ids itself; the Journal reserves each one as it goes by.
        (1L..50L).forEach { event ->
            synchronized(ids.lock) {
                ids.handedOut(IdKind.EVENT, event)
                assertTrue(onDisk().getValue(IdKind.EVENT) > event, "event $event handed out past the file: ${onDisk()}")
            }
        }
    }

    /**
     * On Windows, a file that another program has open (an antivirus, a backup, someone reading it)
     * cannot be replaced. Here a program has `narrator.ids` open when the next block is reserved, and
     * closes it once the first attempt to replace it has failed: the reservation tries again, and no id
     * goes out past the file. (Elsewhere, an open file is replaced all the same.)
     */
    @Test
    fun `a program that has the file open while it is replaced lets no id out past it`() {
        val reader = AtomicReference<FileChannel?>()
        val pauses = AtomicInteger()
        val ids = IdReservations.open(idsFile, IdKind.entries.associateWith { 1L }, blocks = 4, eventBlock = 4) { ms ->
            pauses.incrementAndGet()
            reader.getAndSet(null)?.close() // the other program is done with the file
            Thread.sleep(ms)
        }
        reader.set(FileChannel.open(idsFile, StandardOpenOption.READ))
        repeat(20) {
            synchronized(ids.lock) {
                val turn = ids.next(IdKind.TURN)
                assertTrue(onDisk().getValue(IdKind.TURN) > turn, "turn $turn handed out past the file: ${onDisk()}")
            }
        }
        if (windows) assertTrue(pauses.get() >= 1, "an attempt failed while the file was open, and was made again")
    }

    /**
     * A program keeps `narrator.ids` open: ids still go out (the error is in the log), and once it lets
     * go, the file is written again in the background and covers them all.
     */
    @Test
    fun `ids go on while the file cannot be replaced, and it covers them again once it can`() {
        val ids = IdReservations.open(idsFile, IdKind.entries.associateWith { 1L }, blocks = 4, eventBlock = 4)
        fun covered(): Long = synchronized(ids.lock) { onDisk().getValue(IdKind.TURN) }
        LogCapture().use { logs ->
            var last = 0L
            FileChannel.open(idsFile, StandardOpenOption.READ).use {
                repeat(20) { last = ids.next(IdKind.TURN) }
                if (windows) assertContains(logs.text, "cannot reserve ids")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (covered() <= last) {
                check(System.nanoTime() < deadline) { "turn $last is still past the file: ${onDisk()}" }
                last = ids.next(IdKind.TURN) // an id handed out tries again, at most once a second
                Thread.sleep(20)
            }
            if (windows) assertContains(logs.text, "ids reserved in $idsFile again")
        }
    }

    @Test
    fun `a clean close keeps exactly the ids handed out, and the next run starts there`() {
        val ids = IdReservations.open(idsFile, IdKind.entries.associateWith { 10L }, blocks = 1_000, eventBlock = 1_000)
        repeat(3) { ids.next(IdKind.DECISION) }
        ids.close()
        assertEquals(13L, onDisk().getValue(IdKind.DECISION))
        assertEquals(10L, onDisk().getValue(IdKind.TURN))
        val next = IdReservations.open(idsFile, IdKind.entries.associateWith { 1L }, blocks = 1_000, eventBlock = 1_000)
        assertEquals(13L, next.next(IdKind.DECISION), "no gap after a clean stop")
        assertEquals(10L, next.seed(IdKind.TURN))
    }

    /**
     * A real crash: a Journal in another JVM hands out ids of every kind (none of them written) and is
     * killed, three times in a row. Each run must start past every id the one before printed, and the
     * Journal opened afterwards past them all.
     */
    @Test
    fun `ids are never handed out again after a crash, whenever it happens`() {
        val highest = IdKind.entries.associateWith { 0L }.toMutableMap()
        repeat(3) { run ->
            JournalChild.start("crash", file).use { child ->
                child.awaitLine(JournalChild.READY)
                val first = checkNotNull(child.nextIds()) { "run $run printed no ids" }
                IdKind.entries.forEach { kind ->
                    assertTrue(first.getValue(kind) > highest.getValue(kind), "run $run hands out $kind ${first[kind]} again (up to ${highest[kind]} before)")
                }
                var ids: Map<IdKind, Long>? = first
                var lines = 0
                while (ids != null) {
                    ids.forEach { (kind, id) -> highest[kind] = maxOf(highest.getValue(kind), id) }
                    if (++lines == 200) child.kill() // then read what it printed before it died
                    ids = child.nextIds()
                }
                assertTrue(lines >= 200, "run $run printed $lines lines")
            }
        }
        JournalFixture(file).use { journal ->
            IdKind.entries.forEach { kind ->
                assertTrue(journal.journal.nextIdSeed(kind) > highest.getValue(kind), "$kind starts at ${journal.journal.nextIdSeed(kind)}, ${highest[kind]} was handed out")
            }
            assertEquals(9L, journal.count("events"), "only the three facts each run wrote reached the database")
        }
    }
}
