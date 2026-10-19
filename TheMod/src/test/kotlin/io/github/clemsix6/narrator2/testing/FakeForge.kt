package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.forge.Arsenal
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ForgeStatus
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.TestReport
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Forge for tests (design M5 §11): it records what it is asked and answers what the test set. [arsenal] holds the
 * entries and sources the test puts there ([FakeArsenal.add]); [test] answers [report]; [save] adds the next version
 * of the name, `ready` (or `pending` when [approval] is false); [build] answers the classes of [builtClasses] for the
 * entry asked; [review] changes a pending entry. Each throws [rejection] when it is set. Set [available] false (and
 * [reason]) to test what happens without the Forge.
 */
class FakeForge(private val clock: Clock = TestClock()) : Forge {
    /** One call to [save]. */
    data class Saved(val name: String, val source: String, val note: String, val by: String)

    /** One call to [review]. */
    data class Review(val name: String, val version: Int?, val approve: Boolean, val operator: String)

    @Volatile
    override var available: Boolean = true

    @Volatile
    var reason: String = "fake forge off"

    override val unavailableReason: String? get() = if (available) null else reason

    override val arsenal = FakeArsenal()

    /** Thrown by [test], [save], [build] and [review] when set. */
    @Volatile
    var rejection: ForgeRejected? = null

    /** What [test] answers. */
    @Volatile
    var report: TestReport = TestReport(ok = true, text = "ok: compiled, guard clean, smoke run passed; scenarios 1/1", scenariosPassed = listOf("itWorks"), manifest = null)

    /** False: a saved version is `pending` (`forge.approval = operator`). */
    @Volatile
    var approval: Boolean = true

    /** What [build] gives a module, by name: its classes. */
    val builtClasses = mutableMapOf<String, Map<String, ByteArray>>()

    /** What [status] answers. */
    @Volatile
    var status: ForgeStatus = ForgeStatus(ecj = "3.46.100", compiler = "idle")

    val tested = CopyOnWriteArrayList<Pair<String, String>>()
    val saved = CopyOnWriteArrayList<Saved>()
    val built = CopyOnWriteArrayList<Pair<String, Int?>>()
    val reviews = CopyOnWriteArrayList<Review>()

    @Volatile
    var stopped: Boolean = false

    override suspend fun test(name: String, source: String): TestReport {
        rejection?.let { throw it }
        tested += name to source
        return report
    }

    override suspend fun save(name: String, source: String, note: String, by: String): ArsenalEntry {
        rejection?.let { throw it }
        saved += Saved(name, source, note, by)
        val version = (arsenal.list().filter { it.name == name }.maxOfOrNull { it.version } ?: 0) + 1
        val entry = (report.manifest ?: ForgeSamples.manifest).copy(
            name = name, version = version, status = if (approval) ModuleStatus.READY else ModuleStatus.PENDING, builtIn = false,
            savedAt = clock.instant(), by = by, note = note,
        )
        arsenal.add(entry, source)
        return entry
    }

    override suspend fun build(name: String, version: Int?): Built {
        rejection?.let { throw it }
        built += name to version
        val entry = arsenal.entry(name, version) ?: throw ForgeRejected(null, "no module $name${version?.let { " v$it" }.orEmpty()} in the arsenal")
        return Built(entry, mainClass(name), builtClasses[name].orEmpty())
    }

    override suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry {
        rejection?.let { throw it }
        reviews += Review(name, version, approve, operator)
        val entry = arsenal.list().firstOrNull { it.name == name && (version == null || it.version == version) && it.status == ModuleStatus.PENDING }
            ?: throw ForgeRejected(null, "$name has no version waiting for approval")
        val reviewed = entry.copy(status = if (approve) ModuleStatus.READY else ModuleStatus.REJECTED)
        arsenal.replace(reviewed)
        return reviewed
    }

    override fun status(): ForgeStatus = status

    override fun stop() {
        stopped = true
    }

    companion object {
        /** The class of module [name]: `orbiting_chickens` → `OrbitingChickens`. */
        fun mainClass(name: String): String = name.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }
}

/** The arsenal of [FakeForge]: the entries and sources a test puts there, the newest first. */
class FakeArsenal : Arsenal {
    private val entries = CopyOnWriteArrayList<ArsenalEntry>()
    private val sources = mutableMapOf<Pair<String, Int>, String>()

    /** Adds [entry], and its [source] when given. */
    fun add(entry: ArsenalEntry, source: String? = null) {
        entries.add(0, entry)
        if (source != null) synchronized(sources) { sources[entry.name to entry.version] = source }
    }

    /** Replaces the entry of the same name and version. */
    fun replace(entry: ArsenalEntry) {
        val index = entries.indexOfFirst { it.name == entry.name && it.version == entry.version }
        if (index >= 0) entries[index] = entry
    }

    override fun list(): List<ArsenalEntry> = entries.toList()

    override fun entry(name: String, version: Int?): ArsenalEntry? = if (version == null) {
        entries.filter { it.name == name && it.status == ModuleStatus.READY }.maxByOrNull { it.version }
    } else {
        entries.firstOrNull { it.name == name && it.version == version }
    }

    override suspend fun source(name: String, version: Int): String? = synchronized(sources) { sources[name to version] }

    override fun file(name: String, version: Int): Path? =
        entries.firstOrNull { it.name == name && it.version == version && !it.builtIn }?.let { Path.of("world", "narrator2", "forge", name, "v$version.java") }
}
