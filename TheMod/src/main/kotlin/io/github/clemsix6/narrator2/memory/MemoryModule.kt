package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.chronicle.Chronicler
import io.github.clemsix6.narrator2.memory.dream.Dreamer
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.memory.stats.StatsRefresher
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.ZoneId

private val log = Log.memory

/** The longest the startup's reads may hold the server thread, all together: the schema check, then the loading of the play clocks. */
private const val START_TIMEOUT_MS = 30_000L

/** The reads of the situation's `Favor` and `Attention` lines, every 60 s. */
private const val FAVOR_READ_MS = 5_000L

/**
 * What [prepareMemory] did ahead of [createMemory] (M6 §9): the memory off with its reason ([Off]), or the Journal's
 * schema checked and the play clocks loaded ([Loaded]).
 */
sealed interface PreparedMemory {
    /** The memory does not run: [memory] says why. */
    class Off internal constructor(internal val memory: Memory) : PreparedMemory

    /** The schema is there and the play clocks are loaded, in [zone] ([clock]: the services' clock in that zone). */
    class Loaded internal constructor(
        internal val zone: ZoneId,
        internal val clock: Clock,
        internal val state: DuckMemoryState,
        internal val playClock: DuckPlayClock,
    ) : PreparedMemory
}

/**
 * The part of the memory's start that waits for the Journal (M6 §9): it checks the schema and loads the play clocks (a
 * few reads, [START_TIMEOUT_MS] at most all together: [StartupReads]). [PreparedMemory.Off] when `memory.enabled` is
 * false, when the Journal is unavailable, when it lacks the memory's tables (`V2__memory.sql`), or when a read fails.
 * Needs the services' Journal, settings, clock and player states.
 *
 * On the server thread at `SERVER_STARTED`, before any player can join (ARCHITECTURE §17.8); on the restart's own thread
 * at `/narrator restart`, while the logins wait.
 */
fun prepareMemory(services: NarratorServices): PreparedMemory {
    if (!services.settings.get().config.memory.enabled) {
        log.info("the memory is off: memory.enabled is false")
        return PreparedMemory.Off(DisabledMemory("memory.enabled is false"))
    }
    if (!services.journal.available) return off("the Journal is unavailable: ${services.journal.health().lastError ?: "no reason given"}")
    return try {
        val reads = StartupReads()
        val problems = reads.run("reading the Journal's schema") { schemaProblems(services.journal) }
        if (problems.isNotEmpty()) {
            off("the Journal lacks the memory's schema (${MemorySchema.MIGRATION}): ${problems.joinToString("; ")}")
        } else {
            load(services, reads)
        }
    } catch (e: Exception) {
        log.error("the memory could not start: ${e.message}", e)
        off("it could not start: ${e.message ?: e.javaClass.simpleName}")
    }
}

/**
 * Creates the server's memory (M3-18), after the workers and before the Director, with what [prepared] read: a
 * [MemoryService] over the real pieces, or the [DisabledMemory] of [prepareMemory], or one that says why the memory
 * cannot start. A `/narrator reload` changes none of that: the memory is built once per start of the Narrator.
 *
 * Runs on the server thread, never waits: it follows the joins and the leaves, and starts the jobs in `services.scope`.
 * Nothing in them needs the Director before `Phase.RUNNING`, which comes once the Director exists. They stop at
 * `SERVER_STOPPING` ([Memory.stop], from the shutdown sequence), before the Journal closes.
 */
fun createMemory(services: NarratorServices, prepared: PreparedMemory = prepareMemory(services)): Memory = when (prepared) {
    is PreparedMemory.Off -> prepared.memory
    is PreparedMemory.Loaded -> try {
        assemble(services, prepared)
    } catch (e: Exception) {
        log.error("the memory could not start: ${e.message}", e)
        disabled("it could not start: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun disabled(reason: String): Memory {
    log.warn("the memory is off: $reason")
    return DisabledMemory(reason)
}

private fun off(reason: String) = PreparedMemory.Off(disabled(reason))

/** The play clocks, read within what is left of [reads]. Times are shown in the Journal's zone. */
private fun load(services: NarratorServices, reads: StartupReads): PreparedMemory.Loaded {
    val journal = services.journal
    val zone = services.settings.get().config.journal.timeZone.ifBlank { null }?.let(ZoneId::of) ?: services.clock.zone
    val clock = if (zone == services.clock.zone) services.clock else services.clock.withZone(zone)
    val state = DuckMemoryState(journal, clock)
    val playClock = DuckPlayClock(state, journal, services.players)
    reads.run("loading the play clocks") { playClock.load(clock.millis()) }
    return PreparedMemory.Loaded(zone, clock, state, playClock)
}

/**
 * The pieces, in the order they need each other (the handoffs of M3-02 to M3-17). One instance of each:
 * the index serves the store, the hygiene and the recall; the play clock serves everything that dates or
 * decays; one [MemoryAgents] runs the conversations of the Chronicler and the Dreamer; one [Reminiscence]
 * finds the reminiscences of the wake-ups ([WakeAttachments]) and the cues of the jobs ([JobHooks]); one
 * [Hygiene] serves the jobs and the Dreamer.
 */
private fun assemble(services: NarratorServices, prepared: PreparedMemory.Loaded): MemoryService {
    val journal = services.journal
    val zone = prepared.zone
    val clock = prepared.clock
    val state = prepared.state
    val playClock = prepared.playClock
    val settings = { services.settings.get().config.memory }

    val index = SearchIndex()
    val store = DuckMemoryStore(journal, index, playClock, clock, services.trace)

    val recall = HybridRecall(journal, index, playClock, clock)
    val sheets = DuckPlayerSheets(journal, playClock, services.players, zone)
    val hygiene = Hygiene(store, journal, playClock, index, Decisions(services.trace, clock), zone)
    val refresher = StatsRefresher(journal, clock)
    val reminiscence = Reminiscence(recall, store, state, journal, playClock, settings, clock, services.trace)
    val agents = MemoryAgents(services)
    val hooks = JobHooks(
        tickClock = playClock::tick,
        saveClock = { playClock.save(it) },
        worldPlaySeconds = playClock::worldPlaySeconds,
        tidy = { now, runId -> hygiene.tidy(now, runId) },
        refreshStats = { players, why -> refresher.refresh(players, why) },
        activeSince = refresher::activeSince,
        favorLines = { online, now ->
            val readings = journal.read(FAVOR_READ_MS) { c -> online.mapNotNull { p -> Favor.read(c, p.uuid, now)?.let { p to it } } }
            Favor.situationLines(readings, settings().attentionMinutes)
        },
        placeReturn = reminiscence::placeReturn,
        anniversary = reminiscence::anniversary,
    )
    val jobs = MemoryJobs(services, store, state, DuckJobsData(journal), Chronicler(services, agents), Dreamer(services, agents, hygiene, state), hooks)
    val memory = MemoryService(
        store, recall, sheets, playClock, jobs, agents,
        MemoryBriefing(store, recall, sheets, journal, { services.players.online.map { it.ref } }, zone),
        WakeAttachments(JoinBrief(store, sheets, journal, zone), reminiscence),
        services.scope,
    )

    playClock.subscribe(services.bus) // after the load: from now on, the joins and the leaves move it
    jobs.start()
    log.info("the memory is on: ${ToolText.duration(playClock.worldPlaySeconds(clock.millis()) * 1_000)} of world play, times in $zone, jobs started")
    return memory
}

/** What the Journal lacks of the memory's schema: an older schema version, missing tables; empty when nothing. */
private suspend fun schemaProblems(journal: Journal): List<String> = journal.read(START_TIMEOUT_MS) { connection ->
    val version = connection.query("SELECT coalesce(max(version), 0) FROM schema_version") { getInt(1) }.single()
    val tables = connection.query("SELECT table_name FROM duckdb_tables() WHERE schema_name = 'main'") { getString(1) }.toSet()
    val missing = MemorySchema.TABLES.filterNot { it in tables }
    listOfNotNull(
        "schema version $version".takeIf { version < DuckDatabase.versionOf(MemorySchema.MIGRATION) },
        "tables missing: ${missing.joinToString()}".takeIf { missing.isNotEmpty() },
    )
}

/**
 * The startup's reads, on the calling thread (the server thread before the first tick, or a restart's): [totalMs] at most
 * all together, whatever their number. [nanoTime] is the monotonic clock that measures them.
 */
internal class StartupReads(private val totalMs: Long = START_TIMEOUT_MS, private val nanoTime: () -> Long = System::nanoTime) {
    private val deadline = nanoTime() + totalMs * 1_000_000

    /** Runs [block] within what is left of the bound; [what] names it in the error. */
    fun <T : Any> run(what: String, block: suspend () -> T): T {
        val left = (deadline - nanoTime()) / 1_000_000
        val result = if (left > 0) runBlocking { withTimeoutOrNull(left) { block() } } else null
        return result ?: throw IllegalStateException("$what went over the ${ToolText.duration(totalMs)} the startup's reads may take")
    }
}
