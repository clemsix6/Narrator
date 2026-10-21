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
import java.time.ZoneId

private val log = Log.memory

/** The longest the startup's reads may hold the server thread, all together: the schema check, then the loading of the play clocks. */
private const val START_TIMEOUT_MS = 30_000L

/** The reads of the situation's `Favor` and `Attention` lines, every 60 s. */
private const val FAVOR_READ_MS = 5_000L

/**
 * Creates the server's memory (M3-18), after the workers and before the Director: a [MemoryService] over
 * the real pieces, or a [DisabledMemory] that says why when `memory.enabled` is false, when the Journal is
 * unavailable, when the Journal lacks the memory's tables (`V2__memory.sql`), or when the memory cannot
 * start. A `/narrator reload` changes none of that: the memory is built once per server start.
 *
 * Runs on the server thread at `SERVER_STARTED`, before any player can join. It checks the schema and
 * loads the play clocks (a few reads, [START_TIMEOUT_MS] at most all together: [StartupReads]), then
 * follows the joins and the leaves, and starts the jobs in `services.scope`. Nothing in them needs the
 * Director before `Phase.RUNNING`, which comes once the Director exists. They stop at `SERVER_STOPPING`
 * ([Memory.stop], from the shutdown sequence), before the Journal closes.
 */
fun createMemory(services: NarratorServices): Memory {
    if (!services.settings.get().config.memory.enabled) {
        log.info("the memory is off: memory.enabled is false")
        return DisabledMemory("memory.enabled is false")
    }
    if (!services.journal.available) return disabled("the Journal is unavailable: ${services.journal.health().lastError ?: "no reason given"}")
    return try {
        val reads = StartupReads()
        val problems = reads.run("reading the Journal's schema") { schemaProblems(services.journal) }
        if (problems.isNotEmpty()) {
            disabled("the Journal lacks the memory's schema (${MemorySchema.MIGRATION}): ${problems.joinToString("; ")}")
        } else {
            assemble(services, reads)
        }
    } catch (e: Exception) {
        log.error("the memory could not start: ${e.message}", e)
        disabled("it could not start: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun disabled(reason: String): Memory {
    log.warn("the memory is off: $reason")
    return DisabledMemory(reason)
}

/**
 * The pieces, in the order they need each other (the handoffs of M3-02 to M3-17). One instance of each:
 * the index serves the store, the hygiene and the recall; the play clock serves everything that dates or
 * decays; one [MemoryAgents] runs the conversations of the Chronicler and the Dreamer; one [Reminiscence]
 * finds the reminiscences of the wake-ups ([WakeAttachments]) and the cues of the jobs ([JobHooks]); one
 * [Hygiene] serves the jobs and the Dreamer. Times are shown in the Journal's zone.
 */
private fun assemble(services: NarratorServices, reads: StartupReads): MemoryService {
    val journal = services.journal
    val zone = services.settings.get().config.journal.timeZone.ifBlank { null }?.let(ZoneId::of) ?: services.clock.zone
    val clock = if (zone == services.clock.zone) services.clock else services.clock.withZone(zone)
    val settings = { services.settings.get().config.memory }

    val index = SearchIndex()
    val state = DuckMemoryState(journal, clock)
    val playClock = DuckPlayClock(state, journal, services.players)
    reads.run("loading the play clocks") { playClock.load(clock.millis()) }
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
 * The startup's reads, on the calling thread (the server thread, before the first tick): [totalMs] at most
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
