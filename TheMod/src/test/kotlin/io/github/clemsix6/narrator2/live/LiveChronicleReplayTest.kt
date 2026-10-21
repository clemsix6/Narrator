package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.DuckJobsData
import io.github.clemsix6.narrator2.memory.DuckMemoryStore
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryAgents
import io.github.clemsix6.narrator2.memory.MemoryRun
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunOutcome
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.RunTrigger
import io.github.clemsix6.narrator2.memory.chronicle.Chronicler
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.test.assertTrue

/**
 * The Chronicler's jobs of an archived evening, replayed with the real model (M6-FIX-12): what they cost and what
 * they wrote, to compare one version of the Chronicler with another on the same windows. Run by `gradlew liveTest`
 * only, skipped without a key, a tunnel or a Journal.
 *
 * - `NARRATOR2_CHRONICLE_JOURNAL`: the archived Journal (`narrator.duckdb`); a copy is replayed, never the file.
 * - `NARRATOR2_CHRONICLE_RUNS`: the evening's batches and naps to replay (their `memory_runs` ids, `8,10,11`); by
 *   default, every one that held a conversation.
 * - `NARRATOR2_CHRONICLE_MINUTES`: replays the chapters' batches at this cadence instead of the evening's (from the
 *   chapter's start, until its nap).
 * - `NARRATOR2_CHRONICLE_BUDGET`: the dollars the replay may spend (0.8 by default); it stops between two jobs past them.
 *
 * The copy is put back as it was before those jobs: their writes are reverted (newest first), then each job runs
 * again at the time it began, with a new run id and its window, on the real store and the real tools. The report
 * (`build/reports/liveTest/memory/chronicle-replay.md`) lists the calls, their tokens and dollars at the prices of
 * `trace/Prices`, and what each job wrote; the transcripts are kept next to the copy.
 */
@Tag("live")
class LiveChronicleReplayTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    /** One job to replay: a batch over [from]..[to] of [chapter], or the nap of [chapter] whose rest starts at [from]. */
    private data class Job(val kind: RunKind, val at: Instant, val chapter: String, val from: Instant, val to: Instant, val original: Long?)

    @Test
    fun `the Chronicler's jobs of an archived evening, replayed`() {
        val source = System.getenv("NARRATOR2_CHRONICLE_JOURNAL")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
        assumeTrue(source != null && source.isRegularFile()) { "no archived Journal: set NARRATOR2_CHRONICLE_JOURNAL" }
        val key = LiveGateway.key()
        val budget = System.getenv("NARRATOR2_CHRONICLE_BUDGET")?.toDoubleOrNull() ?: 0.8
        val minutes = System.getenv("NARRATOR2_CHRONICLE_MINUTES")?.toLongOrNull()
        val wanted = System.getenv("NARRATOR2_CHRONICLE_RUNS")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.ifEmpty { null }

        val dir = Path.of("build/live-memory/chronicle-replay-${STAMP.format(Instant.now())}").toAbsolutePath()
        val file = dir.resolve("narrator2").resolve(DuckDatabase.FILE_NAME)
        Files.createDirectories(file.parent)
        Files.copy(source!!, file, StandardCopyOption.REPLACE_EXISTING)
        val undone = undo(file, wanted)
        val config = LiveGateway.config(zone)
        val clock = TestClock(Instant.now(), zone)
        val journal = DuckJournal.open(DuckDatabase.open(file, DuckDatabase.Options.of(config.journal)), config.journal, clock)
        val report = StringBuilder("# The Chronicler's jobs, replayed (M6-FIX-12)\n\n")
        report.append("- Journal: `$source` (a copy in `$dir`)\n- prices: ${LivePrices.line()}\n- undone in the copy: $undone\n")
        try {
            val originals = rows(journal, "SELECT run_id, kind, started_at, window_from, window_to, chapter FROM memory_runs WHERE kind IN ('batch', 'nap') AND len(conversations) > 0 ORDER BY run_id") {
                Job(RunKind.valueOf(getString(2).uppercase()), instantOf(3), getString(6), instantOf(4), instantOf(5), getLong(1))
            }.filter { wanted == null || it.original in wanted }
            assumeTrue(originals.isNotEmpty()) { "no batch nor nap to replay" }
            val jobs = if (minutes == null) originals else recut(journal, originals, Duration.ofMinutes(minutes))
            report.append("- jobs of the evening: ${originals.joinToString { "${it.kind.name.lowercase()} run ${it.original} at ${time(it.at)}" }}\n")
            report.append("- replayed${minutes?.let { " with a batch every $it min" } ?: " as they ran"}: ${jobs.joinToString { "${it.kind.name.lowercase()} at ${time(it.at)} (${time(it.from)}–${time(it.to)})" }}\n\n")
            replay(journal, clock, key, config, dir, originals, jobs, budget, report)
        } finally {
            journal.close(Duration.ofSeconds(10))
            report.append("\nCopy and transcripts kept in `$dir`.\n")
            LiveReports.write("chronicle-replay.md", report.toString())
            println(report)
        }
    }

    private fun replay(
        journal: Journal, clock: TestClock, key: String, config: io.github.clemsix6.narrator2.config.NarratorConfig, dir: Path,
        originals: List<Job>, jobs: List<Job>, budget: Double, report: StringBuilder,
    ) {
        val client = ClaudeClient(config, key)
        val index = SearchIndex()
        val store = DuckMemoryStore(journal, index, FakeMemory.FakePlayClock(), clock)
        lateinit var agents: MemoryAgents
        val stub = FakeMemory(clock)
        val memory: Memory = object : Memory by stub {
            override val store: MemoryStore get() = store
            override val recall: Recall = HybridRecall(journal, index, FakeMemory.FakePlayClock(), clock)
            override fun runContext(conversationId: String): RunContext? = agents.runContext(conversationId)
        }
        val services = TestServices(
            clock = clock, dataDir = dir, settings = testSettings(config, client), journal = journal, trace = journal,
            transcripts = Transcripts(dir.resolve("transcripts"), enabled = true), director = FakeDirector(clock), memory = memory,
        )
        try {
            agents = MemoryAgents(services)
            val chronicler = Chronicler(services, agents)
            val firstCall = rows(journal, "SELECT coalesce(max(id), 0) FROM llm_calls") { getLong(1) }.single()
            val replayed = mutableListOf<Pair<Long, Job>>()
            for (job in jobs) {
                val spent = LiveCall.read(journal, "id > $firstCall").sumOf { it.dollars }
                if (spent >= budget) {
                    report.append("\n**Stopped**: $%.3f spent, budget $%.2f.\n".format(Locale.ROOT, spent, budget))
                    break
                }
                clock.now = job.at
                val runId = journal.nextId(IdKind.MEMORY_RUN)
                runBlocking {
                    DuckJobsData(journal).insertRun(
                        MemoryRun(
                            runId, job.kind, if (job.kind == RunKind.NAP) RunTrigger.CHAPTER_CLOSED else RunTrigger.TIMER, RunStatus.RUNNING, job.at, null,
                            job.from, job.to, job.chapter, emptyList(), 0, 0, null, null, null, null,
                        ),
                    )
                }
                val started = System.nanoTime()
                val outcome: RunOutcome = runBlocking {
                    if (job.kind == RunKind.BATCH) chronicler.batch(runId, job.chapter, job.from, job.to) else chronicler.nap(runId, chapter(journal, job.chapter))
                }
                replayed += runId to job
                report.append(
                    "- **${job.kind.name.lowercase()} run $runId** (for run ${job.original ?: "-"}, ${time(job.from)}–${time(job.to)}): ${outcome.status.id} in " +
                        "${(System.nanoTime() - started) / 1_000_000_000} s, ${outcome.writes} write(s), ${outcome.rejected} refused, conversations ${outcome.conversations}: " +
                        "${outcome.report?.replace('\n', ' ')}\n",
                )
            }
            val calls = LiveCall.read(journal, "id > $firstCall AND purpose = 'chronicle'")
            LiveReports.record("LiveChronicleReplayTest", "replay", calls)
            report.append("\n## Calls\n\n${LiveCall.summary(calls)}\n\n").append(LiveCall.table(calls))
            report.append("\nBy conversation:\n")
            calls.groupBy { it.conversation }.forEach { (conversation, its) ->
                report.append("- $conversation: ${LiveCall.summary(its)}\n")
            }
            report.append("\n## What the jobs wrote\n\n").append(writes(journal, replayed.map { it.first }))
            assertTrue(calls.none { it.error != null }, "a call failed: ${calls.filter { it.error != null }.map { it.error }}")
        } finally {
            services.scope.cancel()
            client.close()
        }
    }

    /**
     * Puts the copy [file] back as it was before the Chronicler's jobs [runs] (all of its batches and naps when null),
     * before the Journal opens it: the rows they inserted deleted, the canon they changed given back the uses, the
     * status and the resolution it had before their first change, their audit dropped. `revertRun` refuses this
     * (a later job changed the same canon); a copy for a replay may. Returns what was undone.
     */
    private fun undo(file: Path, runs: List<Long>?): String {
        java.sql.DriverManager.getConnection("jdbc:duckdb:$file").use { connection ->
            connection.createStatement().use { statement ->
                val picked = runs?.joinToString() ?: "SELECT run_id FROM memory_runs WHERE kind IN ('batch', 'nap') AND len(conversations) > 0"
                val log = "SELECT row_key FROM memory_log WHERE run_id IN ($picked) AND op = 'insert' AND table_name"
                val counts = TABLES.associateWith { table -> statement.executeUpdate("DELETE FROM $table WHERE id IN (SELECT TRY_CAST(row_key AS BIGINT) FROM ($log = '$table'))") }
                val docs = statement.executeUpdate("DELETE FROM memory_docs WHERE (key || ':' || version) IN ($log = 'memory_docs')")
                val canon = statement.executeUpdate(
                    "UPDATE canon SET uses = b.uses, last_used_at = b.last_used_at, status = b.status, resolved_at = b.resolved_at, resolution = b.resolution " +
                        "FROM (SELECT TRY_CAST(row_key AS BIGINT) AS id, arg_min((before ->> 'uses')::INTEGER, rowid) AS uses, " +
                        "arg_min(TRY_CAST(before ->> 'last_used_at' AS TIMESTAMPTZ), rowid) AS last_used_at, arg_min(before ->> 'status', rowid) AS status, " +
                        "arg_min(TRY_CAST(before ->> 'resolved_at' AS TIMESTAMPTZ), rowid) AS resolved_at, arg_min(before ->> 'resolution', rowid) AS resolution " +
                        "FROM memory_log WHERE run_id IN ($picked) AND op = 'update' AND table_name = 'canon' GROUP BY 1) b WHERE canon.id = b.id",
                )
                val audit = statement.executeUpdate("DELETE FROM memory_log WHERE run_id IN ($picked)")
                return counts.entries.joinToString { "${it.value} ${it.key}" } + ", $docs memory_docs, $canon canon given back, $audit audit rows"
            }
        }
    }

    /**
     * The batches of [originals]' chapters again, one every [every] from the chapter's start (the lag of `MemoryJobs`
     * kept), then each chapter's nap with its rest from the last batch; the jobs of chapters without a nap are kept.
     */
    private fun recut(journal: Journal, originals: List<Job>, every: Duration): List<Job> = originals.groupBy { it.chapter }.flatMap { (chapter, its) ->
        val nap = its.singleOrNull { it.kind == RunKind.NAP } ?: return@flatMap its
        val start = rows(journal, "SELECT started_at FROM conversations WHERE conversation_id = '$chapter'") { instantOf(1) }.single()
        val batches = generateSequence(start + every) { it + every }.takeWhile { it < nap.at }.toList()
        val ends = batches.map { it - LAG }
        batches.mapIndexed { i, at -> Job(RunKind.BATCH, at, chapter, if (i == 0) start else ends[i - 1], ends[i], null) } +
            nap.copy(from = ends.lastOrNull() ?: start, original = nap.original)
    }.sortedBy { it.at }

    /** The Director's chapter [id], as the nap reads it. */
    private fun chapter(journal: Journal, id: String): ConversationRecord = rows(
        journal, "SELECT label, chapter, started_at, ended_at, end_reason, summary FROM conversations WHERE conversation_id = '$id'",
    ) {
        ConversationRecord(
            id, Role.DIRECTOR, getString(1), getObject(2)?.let { getInt(2) }, null, instantOf(3), instantOf(4),
            getString(5)?.let { ConversationRecord.EndReason.valueOf(it.uppercase()) }, getString(6), null,
        )
    }.single()

    /** What the replayed jobs [runIds] wrote: canon, interactions, memories and notes, and the canon they touched. */
    private fun writes(journal: Journal, runIds: List<Long>): String {
        if (runIds.isEmpty()) return "nothing\n"
        val ids = runIds.joinToString()
        val lines = mutableListOf<String>()
        lines += rows(journal, "SELECT run_id, id, kind, source, coalesce(quote, ''), statement FROM canon WHERE run_id IN ($ids) ORDER BY id") {
            "run ${getLong(1)} C-${getLong(2)} ${getString(3)} (${getString(4)}): « ${getString(5)} » ${getString(6)}"
        }
        lines += rows(journal, "SELECT run_id, id, player, kind, valence, source, summary FROM interactions WHERE run_id IN ($ids) ORDER BY id") {
            "run ${getLong(1)} I-${getLong(2)} ${getString(3)} ${getString(4)} ${getInt(5)} (${getString(6)}): ${getString(7)}"
        }
        lines += rows(journal, "SELECT run_id, id, title, importance FROM memories WHERE run_id IN ($ids) ORDER BY id") {
            "run ${getLong(1)} M-${getLong(2)} « ${getString(3)} » (${getInt(4)})"
        }
        lines += rows(journal, "SELECT run_id, table_name, row_key, op FROM memory_log WHERE run_id IN ($ids) AND op = 'update' ORDER BY rowid") {
            "run ${getLong(1)} ${getString(4)} ${getString(2)} ${getString(3)}"
        }
        lines += rows(journal, "SELECT run_id, table_name, row_key FROM memory_log WHERE run_id IN ($ids) AND op = 'insert' AND table_name NOT IN ('canon', 'interactions', 'memories') ORDER BY rowid") {
            "run ${getLong(1)} insert ${getString(2)} ${getString(3)}"
        }
        return lines.joinToString("") { "- $it\n" }
    }

    private fun java.sql.ResultSet.instantOf(column: Int): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

    private fun time(at: Instant): String = TIME.withZone(zone).format(at)

    private companion object {
        /** The tables whose rows a job inserts, by id. */
        val TABLES = listOf("canon", "interactions", "memories", "notes", "chronicle")
        val LAG: Duration = Duration.ofSeconds(5)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault())
    }
}
