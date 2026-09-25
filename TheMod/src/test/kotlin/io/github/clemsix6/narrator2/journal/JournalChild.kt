package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBDriver
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Clock
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A Journal in another JVM, for what a test cannot do in its own: crash for real (the process is
 * killed), or hold the database file open as another program would. [start] runs it; [main] is the
 * child's side. It prints [READY] once set up.
 */
internal object JournalChild {
    const val READY = "READY"
    const val WAITING = "WAITING"
    private const val IDS = "IDS "

    /** The child JVM, and what it printed. */
    class Child(private val process: Process) : AutoCloseable {
        private val output: BufferedReader = process.inputReader()
        private val seen = StringBuilder()

        /** Reads lines until one starts with [prefix], and returns it; fails when the child ends first. */
        fun awaitLine(prefix: String): String {
            while (true) {
                val line = output.readLine() ?: error("the child ended before printing '$prefix':\n$seen")
                seen.appendLine(line)
                if (line.startsWith(prefix)) return line
            }
        }

        /** The next ids the child printed (`IDS EVENT=12 SESSION=3 …`), or null once it ended. */
        fun nextIds(): Map<IdKind, Long>? {
            while (true) {
                val line = output.readLine() ?: return null
                // The last line of a killed child may be cut short.
                if (line.startsWith(IDS)) parseIds(line)?.let { return it }
                seen.appendLine(line)
            }
        }

        /** Kills the child at once, as a crash would, and waits until it is gone. */
        fun kill() {
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "the child did not end" }
        }

        override fun close() {
            if (process.isAlive) kill()
        }
    }

    /** Starts the child in [mode] (`hold` or `crash`) on the database [file], in the file's folder. */
    fun start(mode: String, file: Path): Child {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}",
            "-cp", classpathJar(file.parent).toString(), JournalChild::class.java.name, mode, file.toString(),
        )
            .directory(file.parent.toFile()) // its log files stay out of the test's
            .redirectErrorStream(true)
            .start()
        return Child(process)
    }

    /**
     * A jar that only lists the test's class path in its manifest: the class path itself is too long
     * for a Windows command line, and its non-ASCII folders survive as URLs.
     */
    private fun classpathJar(dir: Path): Path {
        val jar = dir.resolve("classpath.jar")
        if (Files.exists(jar)) return jar
        val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
            .joinToString(" ") { Path.of(it).toUri().toASCIIString() }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).close()
        return jar
    }

    /** Every kind with its id; null when the line was cut short. */
    private fun parseIds(line: String): Map<IdKind, Long>? {
        val ids = line.removePrefix(IDS).split(' ').mapNotNull { pair ->
            val parts = pair.split('=')
            val kind = IdKind.entries.firstOrNull { it.name == parts[0] }
            val id = parts.getOrNull(1)?.toLongOrNull()
            if (kind == null || id == null) null else kind to id
        }.toMap()
        return ids.takeIf { it.size == IdKind.entries.size }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args[1])
        when (args[0]) {
            "hold" -> hold(file)
            "crash" -> crash(file)
            else -> error("unknown mode ${args[0]}")
        }
    }

    /**
     * Opens the database read-only, as the DuckDB command line would, as soon as the Journal lets go of
     * it (it prints [WAITING] while it tries), and holds it until killed.
     */
    private fun hold(file: Path) {
        val properties = Properties().apply { setProperty(DuckDBDriver.DUCKDB_READONLY_PROPERTY, "true") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        println(WAITING)
        System.out.flush()
        while (true) {
            try {
                DuckDBDriver().connect("jdbc:duckdb:$file", properties)
                break
            } catch (e: SQLException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(5) // the Journal still has it
            }
        }
        println(READY)
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }

    /**
     * Opens a Journal that writes three facts, then hands out ids of every kind forever, printing them
     * (`IDS EVENT=… SESSION=…`). The writer never writes them (it waits an hour between batches): when
     * the child is killed, whenever it is, the ids it handed out last never reached the database.
     * Small id blocks: ids are reserved on disk all the time, and a kill may land in the middle.
     */
    private fun crash(file: Path) {
        val clock = Clock.systemUTC()
        val journal = DuckJournal.open(
            DuckDatabase.open(file, JournalFixture.OPTIONS), JournalConfig(flushIntervalMs = 3_600_000), clock,
            tuning = JournalTuning(pollMs = 3_600_000, idBlock = 8, eventIdBlock = 8),
        )
        val bus = EventBus(clock, { 0 }, journal.nextIdSeed(IdKind.EVENT))
        journal.attach(bus)
        val fact = BlockBroken(steve, here, "minecraft:stone", null, creative = false, correctTool = true, placedBy = null)
        repeat(3) { bus.publish(fact) }
        runBlocking { journal.barrier() }
        println(READY)
        while (true) {
            val ids = IdKind.entries.joinToString(" ", prefix = IDS) { kind ->
                val id = if (kind == IdKind.EVENT) checkNotNull(bus.publish(fact)).id else journal.nextId(kind)
                "$kind=$id"
            }
            println(ids)
            System.out.flush()
        }
    }
}
