package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the Journal's ids growing across restarts, crashes included (`TraceSink.nextId`).
 *
 * The database alone (`DuckJournal.seeds`) misses the ids handed out in the last moments before a
 * crash: cited in a tool result, a log line or a transcript, their rows never reached the disk, and the
 * next run would hand them out again. So every id is reserved on disk before it is handed out: [file]
 * (`narrator.ids`, next to the database) holds, for each kind, an id past every id handed out so far,
 * and the next run starts past it and past the database.
 *
 * - Ids are reserved by blocks. When half of a block is used, the next one is reserved in the
 *   background, so the caller (often the server thread) does not wait for the disk; it only does when
 *   a burst uses up the rest of the block first.
 * - The file is written whole to a temporary file forced to the disk, then moved over the old one: a
 *   crash while it is written leaves the previous one, never a missing or partial file.
 * - On Windows, a file that another program has open (an antivirus, a backup, someone reading it)
 *   cannot be replaced until it is closed: the move is tried again for up to 63 ms ([replace]).
 * - The reservations in memory only follow what the file holds: a write that fails reserves nothing.
 * - A clean close ([close]) writes exactly what was handed out: no gap after a normal restart. After a
 *   crash, the unused part of each last block is skipped.
 * - [IdKind.EVENT] ids are handed out by the bus; [handedOut] reserves them, from a synchronous
 *   subscriber that is first on the bus: no other subscriber sees an event before its id is reserved.
 * - When the file still cannot be written (disk full, read-only, held open for longer), ids are handed
 *   out anyway, the error is logged, and the file is written again in the background at most once a
 *   second. Until it succeeds, a crash could hand out some ids again.
 * - Without a file (a database in memory, in tests), nothing is reserved.
 */
internal class IdReservations private constructor(
    private val file: Path?,
    starts: Map<IdKind, Long>,
    private val blocks: Map<IdKind, Long>,
    /** Waits between two attempts to replace [file], in milliseconds. */
    private val pause: (Long) -> Unit,
) {
    /** The next id of each kind not handed out yet. */
    private val counters: Map<IdKind, AtomicLong> = IdKind.entries.associateWith { AtomicLong(starts.getValue(it)) }

    /** Ids below these are reserved on disk (in memory: all of them). Raised only once the file says so. */
    private val reserved: Map<IdKind, AtomicLong> =
        IdKind.entries.associateWith { AtomicLong(if (file == null) Long.MAX_VALUE else starts.getValue(it)) }

    /** Held while the file is written and [reserved] changes; a test holds it to read the file between two writes. */
    internal val lock = Any()
    private val topUpQueued = AtomicBoolean()

    /** When the last write of the file failed (`System.nanoTime()`); null once it is written again. */
    @Volatile
    private var failedAt: Long? = null

    /** The first id of [kind] not handed out yet. */
    fun seed(kind: IdKind): Long = counters.getValue(kind).get()

    /** Hands out the next id of [kind], once it is reserved on disk. */
    fun next(kind: IdKind): Long {
        val id = counters.getValue(kind).getAndIncrement()
        cover(kind, id)
        return id
    }

    /** [id] of [kind] was handed out elsewhere (the bus); returns once it is reserved on disk. */
    fun handedOut(kind: IdKind, id: Long) {
        counters.getValue(kind).accumulateAndGet(id + 1, ::maxOf)
        cover(kind, id)
    }

    /**
     * A clean close: the file keeps exactly the ids handed out, so that the next run has no gap. An id
     * handed out afterwards is reserved again as usual.
     */
    fun close() {
        if (file == null) return
        synchronized(lock) {
            val before = reserved.mapValues { it.value.get() }
            // From here on, every id handed out waits for the lock, then is reserved past what is written now.
            reserved.values.forEach { it.set(Long.MIN_VALUE) }
            val exact = counters.mapValues { it.value.get() }
            val written = write(exact)
            (if (written) exact else before).forEach { (kind, value) -> reserved.getValue(kind).set(value) }
        }
    }

    private fun cover(kind: IdKind, id: Long) {
        val failed = failedAt
        val ceiling = reserved.getValue(kind).get()
        when {
            // The file cannot be written: the id goes out anyway; one more attempt a second, off the caller's thread.
            failed != null -> if (System.nanoTime() - failed >= RETRY_NANOS) topUpLater()
            id >= ceiling -> synchronized(lock) { if (id >= reserved.getValue(kind).get() && failedAt == null) reserveAhead() }
            ceiling - id <= blocks.getValue(kind) / 2 -> topUpLater()
        }
    }

    /** Reserves the next blocks in the background, off the caller's thread. */
    private fun topUpLater() {
        if (!topUpQueued.compareAndSet(false, true)) return
        CompletableFuture.runAsync {
            try {
                synchronized(lock) { reserveAhead() }
            } catch (e: Exception) {
                log.error("could not reserve the next ids", e)
            } finally {
                topUpQueued.set(false)
            }
        }
    }

    /**
     * Gives every kind with half a block or less left a new block past the ids handed out, and writes
     * the file (also when its last write failed). [reserved] follows only once the file is written. Under [lock].
     */
    private fun reserveAhead() {
        val ceilings = IdKind.entries.associateWith { kind ->
            val ceiling = reserved.getValue(kind).get()
            val used = counters.getValue(kind).get()
            val block = blocks.getValue(kind)
            if (ceiling - used <= block / 2) used + block else ceiling
        }
        if (failedAt == null && ceilings.all { (kind, value) -> value == reserved.getValue(kind).get() }) return
        if (write(ceilings)) ceilings.forEach { (kind, value) -> reserved.getValue(kind).set(value) }
    }

    /** Writes [ceilings] to [file], replacing it at once; false (and logged) when it fails. */
    private fun write(ceilings: Map<IdKind, Long>): Boolean {
        val file = file ?: return true
        val text = buildString {
            appendLine("# The Journal's ids: every id handed out is below these. Written before ids are handed out.")
            ceilings.forEach { (kind, value) -> appendLine("${kind.name}=$value") }
        }
        return try {
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                it.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)))
                it.force(true)
            }
            replace(temporary, file)
            if (failedAt != null) log.info("ids reserved in $file again")
            failedAt = null
            true
        } catch (e: IOException) {
            if (failedAt == null) {
                log.error("cannot reserve ids in $file, a crash now could hand some out again (tried again at most once a second): $e")
            }
            failedAt = System.nanoTime()
            false
        }
    }

    /**
     * Moves [temporary] over [file] in one step. On Windows the move fails while another program has
     * [file] open, even only to read it: it is tried again after 1, 2, 4… up to [REPLACE_MAX_WAIT_MS] ms
     * (63 ms in all), which outlasts a scan or a copy of so small a file.
     */
    private fun replace(temporary: Path, file: Path) {
        var wait = 1L
        while (true) {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (wait > 1) log.debug { "replaced $file after ${wait - 1} ms of waiting for another program to close it" }
                return
            } catch (e: IOException) {
                if (wait > REPLACE_MAX_WAIT_MS) throw e
                try {
                    pause(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                wait *= 2
            }
        }
    }

    companion object {
        /** File name of the reservations, next to the database. */
        const val FILE_NAME = "narrator.ids"

        private val RETRY_NANOS = TimeUnit.SECONDS.toNanos(1)

        /** The longest wait between two attempts to replace the file; the waits double from 1 ms. */
        private const val REPLACE_MAX_WAIT_MS = 32L
        private val log = Log.journal

        /**
         * Starts each kind past [seeds] (the database) and past [file], then reserves a first block of
         * each on disk: [blocks] ids, or [eventBlock] for [IdKind.EVENT]. [pause] waits between two
         * attempts to replace the file (a test steps in there).
         */
        fun open(
            file: Path?,
            seeds: Map<IdKind, Long>,
            blocks: Long,
            eventBlock: Long,
            pause: (Long) -> Unit = { Thread.sleep(it) },
        ): IdReservations {
            val onDisk = file?.let(::read).orEmpty()
            val starts = IdKind.entries.associateWith { maxOf(seeds.getValue(it), onDisk[it] ?: 0) }
            val sizes = IdKind.entries.associateWith { if (it == IdKind.EVENT) eventBlock else blocks }
            require(sizes.values.all { it >= 2 }) { "an id block holds 2 ids at least" }
            val reservations = IdReservations(file, starts, sizes, pause)
            if (file != null) synchronized(reservations.lock) { reservations.reserveAhead() }
            return reservations
        }

        /** The ids in [file]; empty when there is none. An unreadable file is logged and ignored. */
        private fun read(file: Path): Map<IdKind, Long> {
            if (!Files.exists(file)) return emptyMap()
            return try {
                Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
                    val (kind, value) = line.split('=', limit = 2)
                    IdKind.valueOf(kind.trim()) to value.trim().toLong()
                }
            } catch (e: Exception) {
                log.error("cannot read the reserved ids in $file, starting past the database only: $e")
                emptyMap()
            }
        }
    }
}
