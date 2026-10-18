package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.CueKind
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.AsyncSubscription
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the jobs set off without computing it themselves (M3 §19.2, M3-12); M3-18 binds each function to
 * its component.
 *
 * @property tickClock advances the world play clock (`DuckPlayClock.tick`); every pass, 30 s.
 * @property saveClock saves it (`DuckPlayClock.save`); every 5 minutes and at the stop.
 * @property worldPlaySeconds reads it (`PlayClock.worldPlaySeconds`); any thread, never waits.
 * @property tidy the code's housekeeping (`Hygiene.tidy`, with the run id for the authors); every 10 minutes.
 * @property refreshStats computes the sheets' statistics of these players again
 *   (`StatsRefresher.refresh(players, why)`): `timer` every `memory.statsMinutes` for the players online,
 *   `left` for a player who leaves, `dream` before a new dream.
 * @property activeSince the players online at some point since an instant (`StatsRefresher.activeSince`):
 *   those whose stats a dream needs, since the end of the last dream.
 * @property favorLines the `Favor` and `Attention` lines of the situation for the players online
 *   (`Favor.read` for each, then `Favor.situationLines`); the jobs add `Due soon` and cache them every 60 s.
 */
class JobHooks(
    val tickClock: (nowMillis: Long) -> Unit,
    val saveClock: suspend (nowMillis: Long) -> Unit,
    val worldPlaySeconds: (nowMillis: Long) -> Long,
    val tidy: suspend (now: Instant, runId: Long) -> HygieneReport,
    val refreshStats: suspend (players: Collection<UUID>, why: String) -> Unit,
    val activeSince: suspend (since: Instant) -> List<UUID>,
    val favorLines: suspend (online: List<PlayerRef>, now: Instant) -> List<String>,
)

/**
 * The memory's scheduler (M3 §1.1, §6.6, §7.1, §8.1, §10.5, §14), on its coroutine `narrator2-memory`.
 *
 * - **One model job at a time**, from a queue: the Chronicler's batches and naps, the Dreamer's dreams
 *   (naps first, then batches, then the dream). They wait while an operator paused them, while the
 *   gateway's budget is spent (402, until its reset), and never start outside [Phase.RUNNING]. Each one
 *   is a `memory_runs` row, inserted when it starts and updated when it ends.
 * - **Timers**, every 30 s: the world play clock, the due canon (`DUE`, once), the batch of the open
 *   chapter every `memory.chronicleMinutes` when the Narrator spoke or acted, the catch-up of the naps
 *   30 s after the start then every 30 minutes, the dream (server empty, or daily), the statistics, the
 *   housekeeping, the situation cache, the anniversary of the day.
 * - **The bus**: the position samples, checked against a grid of the placed memories (a player back
 *   where a memory happened); the departures, whose player's stats are computed again.
 *
 * The code's jobs (clock, due times, stats, housekeeping, cues) go on while the model jobs wait. Nothing
 * here touches the world; the server thread never waits for it ([chapterClosed], [status] and the
 * requests only take a lock).
 */
class MemoryJobs(
    private val services: NarratorServices,
    private val store: MemoryStore,
    private val data: JobsData,
    private val chronicler: ChronicleRunner,
    private val dreamer: DreamRunner,
    private val hooks: JobHooks,
) : MemoryJobsControl {
    private val log = Log.memory
    private val decisions = Decisions(services.trace, services.clock)
    private val clock get() = services.clock
    private val config: MemoryConfig get() = services.settings.get().config.memory

    private val lock = Any()
    private val queue = ArrayList<QueuedJob>() // guarded by lock
    private var current: QueuedJob? = null // guarded by lock
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var currentRun: MemoryRun? = null

    @Volatile
    private var paused = false

    @Volatile
    private var blockedUntil: Instant? = null

    @Volatile
    private var root: Job? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var situationCache: List<String> = emptyList()

    @Volatile
    private var summary = Summary()

    @Volatile
    private var grid = CueGrid(emptyList())

    // Touched by the timers' coroutine only.
    private var emptySince: Long? = null
    private var runningSince: Long? = null
    private var nextCatchUpAt = Long.MAX_VALUE
    private var nextBatchAt = 0L
    private var nextStatsAt = 0L
    private var nextTidyAt = 0L
    private var nextSaveAt = 0L
    private var nextGridAt = 0L
    private var anniversaryDay: LocalDate? = null

    /** Also set to 0 by the worker after a run, for a refresh at the next pass. */
    @Volatile
    private var nextRefreshAt = 0L

    /** The last failure of each step, logged once until it works again. */
    private val failures = ConcurrentHashMap<String, String>()

    /** When each memory was last cued to each player, in seconds of world play. */
    private val cued = ConcurrentHashMap<Pair<Long, UUID>, Long>()

    /**
     * Starts the coroutine `narrator2-memory` in `services.scope`: closes the runs a crash left
     * `running`, reads the pause and the day's anniversary back, then runs the worker, the cues and the
     * timers. Once.
     */
    fun start() {
        check(root == null) { "the memory jobs are already started" }
        val events = services.bus.subscribeAsync(EVENTS_SUBSCRIBER, OWNER, EVENTS_CAPACITY) { it is PositionSampled || it is PlayerLeft }
        root = services.scope.launch(CoroutineName(COROUTINE)) {
            try {
                recover()
                launch { work() }
                launch { follow(events) }
                timers()
            } finally {
                events.close()
            }
        }
        log.info("memory jobs started")
    }

    /**
     * Stops everything (`STOPPING`, M3-18): the running job ends `cancelled` (a dream keeps its step to
     * resume), the queue is dropped, the world play clock is saved. Waits for that, a few seconds at most.
     */
    suspend fun stop() {
        stopping = true
        root?.cancelAndJoin()
        withTimeoutOrNull(STOP_WRITE_MS) {
            runCatching { hooks.saveClock(clock.millis()) }.onFailure { log.warn("could not save the world play clock: ${it.message}") }
        }
        val dropped = synchronized(lock) { queue.size.also { queue.clear() } }
        log.info("memory jobs stopped${if (dropped > 0) ", $dropped queued job(s) dropped" else ""}")
    }

    /** A Director chapter closed for good: its nap is queued (and the batch waiting for it dropped). Any thread, never blocks. */
    fun chapterClosed(record: ConversationRecord) {
        val runId = synchronized(lock) {
            queue.removeAll { it.kind == RunKind.BATCH && it.chapter == record.conversationId }
            if (jobFor(RunKind.NAP, record.conversationId) != null) return
            QueuedJob.Nap(newRunId(), RunTrigger.CHAPTER_CLOSED, record).also { queue += it }.runId
        }
        log.info("nap of chapter ${record.chapter ?: "?"} (${record.conversationId}) queued as run $runId")
        wake.trySend(Unit)
    }

    /** The memory lines of the Director's situation, from the cache the timers refresh every 60 s. */
    fun situationLines(): List<String> = situationCache

    override fun status(): MemoryStatus {
        val now = clock.instant()
        val queued = synchronized(lock) { queue.sortedWith(ORDER).map { it.kind } }
        val known = summary
        return MemoryStatus(
            paused = paused,
            blockedUntil = blockedUntil?.takeIf { it.isAfter(now) },
            running = currentRun,
            queued = queued,
            lastBatch = known.lastBatch,
            lastNap = known.lastNap,
            lastDream = known.lastDream,
            nextDreamAt = known.nextDreamAt,
            worldPlaySeconds = hooks.worldPlaySeconds(now.toEpochMilli()),
            rejected24h = known.refusals.rejected,
            voiceWarnings24h = known.refusals.voiceWarnings,
        )
    }

    override fun requestChronicle(by: String): Result<Long> {
        refuseWhenStopping()?.let { return Result.failure(it) }
        val open = services.director.status().conversationId
        val ids = synchronized(lock) {
            val naps = summary.chaptersToNap.filter { jobFor(RunKind.NAP, it.conversationId) == null }
                .map { QueuedJob.Nap(newRunId(), RunTrigger.OPERATOR, it) }
            val batch = open?.takeIf { jobFor(RunKind.BATCH, it) == null && jobFor(RunKind.NAP, it) == null }
                ?.let { QueuedJob.Batch(newRunId(), RunTrigger.OPERATOR, it) }
            queue += naps
            if (batch != null) queue += batch
            naps.map { it.runId } + listOfNotNull(batch?.runId)
        }
        if (ids.isEmpty()) {
            return Result.failure(IllegalStateException("nothing to chronicle: no closed chapter waits for its nap and the open chapter is already queued, or there is none"))
        }
        log.info("$by asked for the Chronicler: run(s) ${ids.joinToString()} queued")
        wake.trySend(Unit)
        return Result.success(ids.first())
    }

    override fun requestDream(by: String): Result<Long> {
        refuseWhenStopping()?.let { return Result.failure(it) }
        val runId = synchronized(lock) {
            jobFor(RunKind.DREAM, null)?.let { existing ->
                val where = if (existing === current) "running" else "queued"
                return Result.failure(IllegalStateException("a dream is already $where (run ${existing.runId}): never two at a time"))
            }
            QueuedJob.Dream(newRunId(), RunTrigger.OPERATOR).also { queue += it }.runId
        }
        log.info("$by asked for a dream: run $runId queued")
        wake.trySend(Unit)
        return Result.success(runId)
    }

    override fun pause(by: String) {
        if (paused) return
        paused = true
        decisions.record(log, DecisionRecord.Kind.MEMORY, "paused", by, "model jobs wait until /narrator memory resume")
        remember(PAUSED_KEY, "true")
    }

    override fun resume(by: String) {
        if (!paused) return
        paused = false
        decisions.record(log, DecisionRecord.Kind.MEMORY, "resumed", by)
        remember(PAUSED_KEY, "false")
        wake.trySend(Unit)
    }

    private fun refuseWhenStopping(): Exception? = when {
        stopping || services.phase == Phase.STOPPING || services.phase == Phase.STOPPED ->
            IllegalStateException("the server is stopping: no memory job can start")
        root == null -> IllegalStateException("the memory jobs are not started")
        else -> null
    }

    /**
     * Whether a signal posted now reaches the Director: the server runs and the Director is not disabled
     * (§6.6: a due commitment is then signaled at a later pass). A [cue] also needs a Director that is not
     * paused nor blocked: it drops cues while muted (M3-15), and the cue would be spent for nothing.
     */
    private fun directorListens(cue: Boolean = false): Boolean {
        if (stopping || services.phase != Phase.RUNNING) return false
        val state = services.director.status().state
        return state !in (if (cue) MUTED else setOf(DirectorState.DISABLED, DirectorState.STOPPED))
    }

    private fun remember(key: String, value: String) {
        services.scope.launch(CoroutineName(COROUTINE)) {
            runCatching { store.setState(key, value) }.onFailure { log.warn("could not save $key: ${it.message}") }
        }
    }

    private fun newRunId(): Long = services.trace.nextId(IdKind.MEMORY_RUN)

    /** The job of [kind] (for [chapter] when given) queued or running. Under [lock]. */
    private fun jobFor(kind: RunKind, chapter: String?): QueuedJob? =
        (listOfNotNull(current) + queue).firstOrNull { it.kind == kind && (chapter == null || it.chapter == chapter) }

    // ── Start ───────────────────────────────────────────────────────────────────────────────────────

    private suspend fun recover() {
        val now = clock.instant()
        attempt("recover") {
            val interrupted = data.interruptRunning(now)
            if (interrupted > 0) log.info("$interrupted memory run(s) found running after a crash: marked interrupted")
            if (store.state(PAUSED_KEY) == "true" && !paused) {
                paused = true
                log.info("the memory jobs stay paused (/narrator memory resume)")
            }
            anniversaryDay = store.state(ANNIVERSARY_KEY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
    }

    // ── The model jobs ──────────────────────────────────────────────────────────────────────────────

    private suspend fun work() {
        while (true) {
            val job = take()
            if (job == null) {
                wake.receive()
                continue
            }
            try {
                execute(job)
            } finally {
                synchronized(lock) { current = null }
                currentRun = null
            }
        }
    }

    /** The next job that may start now, made [current]; null when none may. */
    private fun take(): QueuedJob? {
        if (paused || stopping || services.phase != Phase.RUNNING) return null
        val blocked = blockedUntil
        if (blocked != null) {
            if (clock.instant().isBefore(blocked)) return null
            blockedUntil = null
            log.info("the LLMGW budget should be available again: the memory jobs go on")
        }
        return synchronized(lock) {
            queue.minWithOrNull(ORDER)?.also {
                queue.remove(it)
                current = it
            }
        }
    }

    private suspend fun execute(job: QueuedJob) {
        val run = try {
            prepare(job, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("${job.kind.id} run ${job.runId} could not start: ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return
        currentRun = run
        try {
            data.insertRun(run)
            log.info("${job.kind.id} run ${job.runId} started (${job.trigger.id}${run.chapter?.let { ", chapter $it" } ?: ""}" +
                "${run.windowFrom?.let { ", window ${it}..${run.windowTo}" } ?: ""})")
            val outcome = when (job) {
                is QueuedJob.Batch -> chronicler.batch(job.runId, job.chapter, run.windowFrom!!, run.windowTo!!)
                is QueuedJob.Nap -> chronicler.nap(job.runId, job.record)
                is QueuedJob.Dream -> {
                    if (job.fromStep == 1) attempt("stats before a dream") { refreshBeforeDream() }
                    dreamer.dream(job.runId, job.fromStep)
                }
            }
            finish(job, run, outcome)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(STOP_WRITE_MS) {
                    val ended = run.copy(status = RunStatus.CANCELLED, endedAt = clock.instant(), error = "the server stopped")
                    attempt("cancel run ${job.runId}") { data.finishRun(ended) }
                }
            }
            log.info("${job.kind.id} run ${job.runId} cancelled: the server stopped")
            throw e
        } catch (e: Exception) {
            log.error("${job.kind.id} run ${job.runId} failed: ${e.message}", e)
            val ended = run.copy(status = RunStatus.FAILED, endedAt = clock.instant(), error = "engine error: ${e.message}")
            attempt("close run ${job.runId}") { data.finishRun(ended) }
            if (job is QueuedJob.Dream) endDream(job.runId, null, forGood = true)
            refreshSoon()
        }
    }

    /** The run row of [job] as it starts; null when there is nothing to do (an empty batch window). */
    private suspend fun prepare(job: QueuedJob, started: Instant): MemoryRun? {
        var windowFrom: Instant? = null
        var windowTo: Instant? = null
        when (job) {
            is QueuedJob.Batch -> {
                windowFrom = batchStart(job.chapter) ?: return skip(job, "the chapter is not in the Journal yet")
                windowTo = started - BATCH_LAG
                if (!windowTo.isAfter(windowFrom)) return skip(job, "empty window")
            }
            is QueuedJob.Nap -> {
                windowFrom = batchStart(job.record.conversationId) ?: job.record.startedAt
                windowTo = job.record.endedAt
            }
            is QueuedJob.Dream -> {
                val pending = store.state(DREAM_PENDING_KEY)?.toLongOrNull()
                val done = pending?.let { store.state(stepKey(it))?.toIntOrNull() } ?: 0
                job.fromStep = done + 1
                store.setState(DREAM_PENDING_KEY, job.runId.toString())
                store.setState(stepKey(job.runId), done.toString())
                if (pending != null) log.info("dream run ${job.runId} resumes dream run $pending at step ${job.fromStep}")
            }
        }
        return MemoryRun(
            runId = job.runId, kind = job.kind, trigger = job.trigger, status = RunStatus.RUNNING, startedAt = started, endedAt = null,
            windowFrom = windowFrom, windowTo = windowTo, chapter = job.chapter, conversations = emptyList(), writes = 0, rejected = 0,
            inputTokens = null, outputTokens = null, report = null, error = null,
        )
    }

    private fun skip(job: QueuedJob, why: String): MemoryRun? {
        log.debug { "${job.kind.id} run ${job.runId} not started: $why" }
        return null
    }

    /** §8.2: before a new dream, the stats of every player online since the last dream ended. */
    private suspend fun refreshBeforeDream() {
        val since = store.state(DREAM_END_KEY)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
        hooks.refreshStats(hooks.activeSince(since), "dream")
    }

    /** Where the next batch of [chapter] starts: the end of its last successful batch, else the chapter's start. */
    private suspend fun batchStart(chapter: String): Instant? =
        data.lastRun(RunKind.BATCH, SUCCESS, chapter)?.windowTo ?: data.chapter(chapter)?.startedAt

    private suspend fun finish(job: QueuedJob, run: MemoryRun, outcome: RunOutcome) {
        val now = clock.instant()
        val budget = outcome.budget
        val failed = outcome.status == RunStatus.FAILED || outcome.status == RunStatus.CANCELLED
        val text = outcome.report?.let { ModelText.cut(it, REPORT_MAX_CHARS) }
        val error = when {
            budget != null -> budgetError(budget)
            failed -> text ?: "no reason given"
            else -> null
        }
        val ended = run.copy(
            status = outcome.status, endedAt = now, conversations = outcome.conversations, writes = outcome.writes,
            rejected = outcome.rejected, report = if (failed) null else text, error = error,
        )
        attempt("close run ${job.runId}") { data.finishRun(ended) }
        val took = ToolText.duration(Duration.between(run.startedAt, now).toMillis())
        decisions.record(
            log, DecisionRecord.Kind.MEMORY, job.kind.id, "run ${job.runId}",
            "${outcome.status.id} in $took (${job.trigger.id}): ${outcome.writes} write(s), ${outcome.rejected} refused" +
                (error?.let { ": $it" } ?: ""),
        )
        if (job is QueuedJob.Dream) endDream(job.runId, outcome.lastStep, forGood = budget == null && outcome.status != RunStatus.CANCELLED)
        if (job is QueuedJob.Nap && outcome.status in SUCCESS) {
            summary = summary.copy(chaptersToNap = summary.chaptersToNap.filter { it.conversationId != job.chapter })
        }
        if (budget != null) block(job, budget, now)
        refreshSoon()
    }

    /** A dream run ended: its step is kept; a dream ended [forGood] (done or failed) is no longer pending. */
    private suspend fun endDream(runId: Long, lastStep: Int?, forGood: Boolean) {
        attempt("end dream run $runId") {
            if (lastStep != null) store.setState(stepKey(runId), lastStep.toString())
            if (forGood) {
                store.setState(DREAM_PENDING_KEY, "")
                store.setState(DREAM_END_KEY, clock.millis().toString())
            }
        }
    }

    /** The gateway's budget is spent: every model job waits until it resets; [job] goes back to the queue unless it is a batch. */
    private fun block(job: QueuedJob, budget: GatewayError.Budget, now: Instant) {
        val until = budget.resetAt?.takeIf { it.isAfter(now) } ?: (now + BUDGET_WAIT)
        blockedUntil = until
        decisions.record(log, DecisionRecord.Kind.MEMORY, "budget", "run ${job.runId}", "model jobs wait until $until")
        val again = when (job) {
            is QueuedJob.Batch -> null // its window comes back with the next batch
            is QueuedJob.Nap -> QueuedJob.Nap(newRunId(), job.trigger, job.record)
            is QueuedJob.Dream -> QueuedJob.Dream(newRunId(), job.trigger)
        }
        if (again != null) synchronized(lock) { queue.add(0, again) }
    }

    private fun budgetError(budget: GatewayError.Budget): String {
        val until = budget.resetAt?.let { " until ${TIME.format(it.atZone(clock.zone))}" } ?: ""
        return "${DuckJobsData.BUDGET_PREFIX} the LLMGW budget is spent$until"
    }

    // ── Timers ──────────────────────────────────────────────────────────────────────────────────────

    private suspend fun timers() {
        while (true) {
            pass(clock.millis())
            wake.trySend(Unit)
            delay(PASS_MS)
        }
    }

    private suspend fun pass(now: Long) {
        val settings = config
        attempt("clock tick") { hooks.tickClock(now) }
        emptySince = if (services.players.online.isEmpty()) emptySince ?: now else null
        if (now >= nextSaveAt) {
            nextSaveAt = now + SAVE_MS
            attempt("clock save") { hooks.saveClock(now) }
        }
        if (now >= nextRefreshAt) {
            nextRefreshAt = now + REFRESH_MS
            attempt("situation") { situationCache = situation(Instant.ofEpochMilli(now)) }
            attempt("status") { refresh(now, settings) }
        }
        if (now >= nextGridAt) {
            nextGridAt = now + GRID_MS
            attempt("cue grid") { grid = CueGrid(data.placedMemories(PLACE_MIN_IMPORTANCE, Instant.ofEpochMilli(now) - CUE_MIN_AGE)) }
        }
        attempt("due") { signalDue(Instant.ofEpochMilli(now)) }
        if (now >= nextTidyAt) {
            nextTidyAt = now + TIDY_MS
            attempt("hygiene") { tidy(Instant.ofEpochMilli(now)) }
        }
        val online = services.players.online
        if (online.isNotEmpty() && now >= nextStatsAt) {
            nextStatsAt = now + settings.statsMinutes * 60_000L
            attempt("stats") { hooks.refreshStats(online.map { it.ref.uuid }, "timer") }
        }
        if (online.isNotEmpty()) attempt("anniversary") { anniversary(now, online.map { it.ref }) }
        if (services.phase != Phase.RUNNING || stopping) return
        if (runningSince == null) {
            runningSince = now
            nextCatchUpAt = now + CATCH_UP_DELAY_MS
        }
        if (now >= nextCatchUpAt) {
            nextCatchUpAt = now + CATCH_UP_MS
            attempt("catch-up") { catchUp(RunTrigger.STARTUP) }
        }
        if (now >= nextBatchAt) {
            nextBatchAt = now + settings.chronicleMinutes * 60_000L
            attempt("batch") { batch(Instant.ofEpochMilli(now)) }
        }
        attempt("dream") { dream(now, settings) }
    }

    /** Runs [block], logging a failure once until it succeeds again (a broken Journal would say it every 30 s). */
    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
            if (failures.remove(what) != null) log.info("memory job '$what' works again")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "${e.javaClass.simpleName}: ${e.message}"
            if (failures.put(what, message) != message) log.warn("memory job '$what' failed: $message")
        }
    }

    private fun refreshSoon() {
        nextRefreshAt = 0
    }

    private suspend fun refresh(now: Long, settings: MemoryConfig) {
        val at = Instant.ofEpochMilli(now)
        val lastEnd = store.state(DREAM_END_KEY)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        val firstNap = data.firstNapDone(lastEnd)
        val nextDream = firstNap?.let {
            val daily = (lastEnd ?: it) + Duration.ofHours(settings.dreamMaxHours.toLong())
            val empty = emptySince?.let { since -> Instant.ofEpochMilli(since) + Duration.ofMinutes(settings.dreamAfterEmptyMinutes.toLong()) }
            listOfNotNull(daily, empty).min()
        }
        summary = Summary(
            lastBatch = data.lastRun(RunKind.BATCH),
            lastNap = data.lastRun(RunKind.NAP),
            lastDream = data.lastRun(RunKind.DREAM),
            nextDreamAt = nextDream,
            refusals = data.refusals(at - Duration.ofHours(24)),
            chaptersToNap = data.chaptersToNap(MAX_NAP_FAILURES, CATCH_UP_MAX),
        )
    }

    /** §6.6: every active canon come due is told to the Director once (not while it is disabled). */
    private suspend fun signalDue(now: Instant) {
        if (!directorListens()) return
        for (ref in data.dueCanon(now)) {
            val entry = store.canon(ref) ?: continue
            val line = dueLine(entry, now)
            services.director.post(DirectorSignal.MemoryDue(ref, line, (entry.dueAt ?: now).toEpochMilli()))
            data.markDueSignaled(ref, now)
            decisions.record(log, DecisionRecord.Kind.MEMORY, "due", ref.toString(), "DUE $line")
        }
    }

    private fun dueLine(entry: CanonEntry, now: Instant): String {
        val set = ToolText.duration(Duration.between(entry.createdAt, now).toMillis())
        return "${entry.ref} ${entry.kind.id} → ${who(entry)}, set $set ago: « ${ModelText.escape(ModelText.cut(entry.quote ?: entry.statement, CUE_TEXT_MAX))} »"
    }

    private suspend fun tidy(now: Instant) {
        val runId = newRunId()
        val report = try {
            hooks.tidy(now, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            data.insertRun(codeRun(runId, RunKind.HYGIENE, now, RunStatus.FAILED, null, "${e.javaClass.simpleName}: ${e.message}"))
            throw e
        }
        if (report.expired.isEmpty() && report.worn.isEmpty()) return
        data.insertRun(codeRun(runId, RunKind.HYGIENE, now, RunStatus.DONE, report.summary(), null))
        log.info("hygiene run $runId: ${report.summary()}")
    }

    private fun codeRun(runId: Long, kind: RunKind, startedAt: Instant, status: RunStatus, report: String?, error: String?) = MemoryRun(
        runId = runId, kind = kind, trigger = RunTrigger.TIMER, status = status, startedAt = startedAt, endedAt = clock.instant(),
        windowFrom = null, windowTo = null, chapter = null, conversations = emptyList(), writes = 0, rejected = 0, inputTokens = null,
        outputTokens = null, report = report, error = error,
    )

    /**
     * The memory lines of the situation (M3 §11.3): `Favor`, then `Due soon` (open commitments due within
     * the hour, the soonest first), then `Attention`. A line without anyone is left out.
     */
    private suspend fun situation(now: Instant): List<String> {
        val (favor, others) = hooks.favorLines(services.players.online.map { it.ref }, now).partition { it.startsWith("Favor:") }
        val soon = store.canon(CanonFilter(kinds = DUE_KINDS, open = true, limit = DUE_SCAN))
            .filter { it.status == CanonStatus.ACTIVE && it.dueAt?.let { due -> due.isAfter(now) && !due.isAfter(now + DUE_SOON) } == true }
            .sortedBy { it.dueAt }
            .take(DUE_SOON_MAX)
        val due = if (soon.isEmpty()) emptyList() else listOf(
            "Due soon: " + soon.joinToString(" · ") {
                val ends = if (it.kind == CanonKind.CHALLENGE) "ends " else ""
                "${it.ref} ${it.kind.id} → ${who(it)} ${ends}in ${ToolText.wait(Duration.between(now, it.dueAt).toMillis())}"
            },
        )
        return favor + due + others
    }

    private fun who(entry: CanonEntry) = entry.players.joinToString { ModelText.name(it.name) }.ifEmpty { "all" }

    /** §7.1: the naps of the chapters closed without one, oldest first (a failed nap is retried, [MAX_NAP_FAILURES] times). */
    private suspend fun catchUp(trigger: RunTrigger) {
        val chapters = data.chaptersToNap(MAX_NAP_FAILURES, CATCH_UP_MAX)
        val queued = synchronized(lock) {
            chapters.filter { jobFor(RunKind.NAP, it.conversationId) == null }
                .map { QueuedJob.Nap(newRunId(), trigger, it) }
                .also { queue += it }
        }
        if (queued.isNotEmpty()) log.info("catch-up: nap(s) of ${queued.joinToString { "chapter ${it.record.chapter ?: "?"}" }} queued")
    }

    /** §7.1: a batch of the open chapter when the Narrator spoke or acted since the last successful one. */
    private suspend fun batch(now: Instant) {
        val status = services.director.status()
        val chapter = status.conversationId ?: return
        if (status.state == DirectorState.CLOSING || status.state == DirectorState.DISABLED) return
        if (synchronized(lock) { jobFor(RunKind.BATCH, chapter) ?: jobFor(RunKind.NAP, chapter) } != null) return
        val from = batchStart(chapter) ?: return
        val to = now - BATCH_LAG
        if (!to.isAfter(from)) return
        if (!data.narratorActive(chapter, from, to)) {
            log.debug { "no batch: the Narrator said and did nothing in chapter $chapter since $from" }
            return
        }
        val runId = synchronized(lock) { QueuedJob.Batch(newRunId(), RunTrigger.TIMER, chapter).also { queue += it }.runId }
        log.debug { "batch run $runId of chapter $chapter queued" }
    }

    /**
     * §8.1: a dream once nobody has played for `dreamAfterEmptyMinutes`, or `dreamMaxHours` after the last
     * one, provided a nap was done since the last dream ended. Never two; a dream cut short resumes.
     */
    private suspend fun dream(now: Long, settings: MemoryConfig) {
        if (synchronized(lock) { jobFor(RunKind.DREAM, null) } != null) return
        val lastEnd = store.state(DREAM_END_KEY)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        val firstNap = data.firstNapDone(lastEnd) ?: return
        val at = Instant.ofEpochMilli(now)
        val empty = emptySince?.let { now - it >= settings.dreamAfterEmptyMinutes * 60_000L } == true
        val trigger = when {
            empty -> RunTrigger.SERVER_EMPTY
            !at.isBefore((lastEnd ?: firstNap) + Duration.ofHours(settings.dreamMaxHours.toLong())) -> RunTrigger.DAILY
            else -> return
        }
        val runId = synchronized(lock) { QueuedJob.Dream(newRunId(), trigger).also { queue += it }.runId }
        log.info("dream run $runId queued (${trigger.id})")
    }

    // ── Cues (M3 §10.5) ─────────────────────────────────────────────────────────────────────────────

    /** The bus: a position sample may be a return to a place; a departure has its player's stats computed again. */
    private suspend fun follow(subscription: AsyncSubscription) {
        for (envelope in subscription.events) {
            when (val event = envelope.event) {
                is PositionSampled -> attempt("place cue") { placeCue(event, envelope.ts) }
                is PlayerLeft -> attempt("stats of a departure") { hooks.refreshStats(listOf(event.player.uuid), "left") }
                else -> Unit
            }
        }
    }

    /** A player within 16 blocks of a placed memory that concerns them or someone online: once per memory and player every 6 h of world play. */
    private fun placeCue(sample: PositionSampled, at: Long) {
        val near = grid.near(sample.pos)
        if (near.isEmpty()) return
        if (!directorListens(cue = true)) return
        val online = services.players.online.map { it.ref.uuid }.toSet() + sample.player.uuid
        val world = hooks.worldPlaySeconds(at)
        val oldest = Instant.ofEpochMilli(at) - CUE_MIN_AGE
        val memory = near.filter { it.occurredFrom <= oldest && (it.players.isEmpty() || it.players.any { p -> p in online }) }
            .filter { m -> cued[m.ref.id to sample.player.uuid]?.let { world - it >= PLACE_COOLDOWN_S } ?: true }
            .maxWithOrNull(compareBy<CueMemory> { it.importance }.thenBy { it.occurredFrom })
            ?: return
        cued[memory.ref.id to sample.player.uuid] = world
        val age = ago(Duration.between(memory.occurredFrom, Instant.ofEpochMilli(at)))
        val line = "${ModelText.name(sample.player.name)} is where ${memory.ref} happened ($age ago): « ${text(memory)} »"
        services.director.post(DirectorSignal.MemoryCue(CueKind.RETURN, memory.ref, sample.player, line, at))
        decisions.record(log, DecisionRecord.Kind.MEMORY, "cue", memory.ref.toString(), "RETURN $line")
    }

    /** At the first pass of a day with someone online: a strong memory of the same day a week (first month), months or years ago. One a day. */
    private suspend fun anniversary(now: Long, online: List<PlayerRef>) {
        val zone = clock.zone
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (anniversaryDay == today) return
        if (!directorListens(cue = true)) return
        anniversaryDay = today
        store.setState(ANNIVERSARY_KEY, today.toString())
        val days = anniversaryDays(today)
        val players = online.associateBy { it.uuid }
        val memory = data.memoriesOn(days.keys.toList(), zone, ANNIVERSARY_MIN_IMPORTANCE)
            .filter { it.players.isEmpty() || it.players.any { p -> p in players } }
            .maxWithOrNull(compareBy<CueMemory> { it.importance }.thenByDescending { it.occurredFrom })
            ?: return
        val day = memory.occurredFrom.atZone(zone).toLocalDate()
        val line = "${memory.ref}, ${days.getValue(day)} ago today: « ${text(memory)} »"
        val player = memory.players.firstNotNullOfOrNull { players[it] }
        services.director.post(DirectorSignal.MemoryCue(CueKind.ANNIVERSARY, memory.ref, player, line, now))
        decisions.record(log, DecisionRecord.Kind.MEMORY, "cue", memory.ref.toString(), "ANNIVERSARY $line")
    }

    private fun text(memory: CueMemory) = ModelText.escape(ModelText.cut(memory.content, CUE_TEXT_MAX))

    // ── Types ───────────────────────────────────────────────────────────────────────────────────────

    /** A model job in the queue. [runId] is its `memory_runs` id, drawn when it is queued. */
    private sealed class QueuedJob {
        abstract val runId: Long
        abstract val trigger: RunTrigger
        abstract val kind: RunKind

        /** The Director's conversation the job is about (batch, nap). */
        open val chapter: String? get() = null

        class Batch(override val runId: Long, override val trigger: RunTrigger, override val chapter: String) : QueuedJob() {
            override val kind get() = RunKind.BATCH
        }

        class Nap(override val runId: Long, override val trigger: RunTrigger, val record: ConversationRecord) : QueuedJob() {
            override val kind get() = RunKind.NAP
            override val chapter: String get() = record.conversationId
        }

        class Dream(override val runId: Long, override val trigger: RunTrigger) : QueuedJob() {
            override val kind get() = RunKind.DREAM

            /** Set when it starts: 1, or the step after the last one done by the dream it resumes. */
            var fromStep = 1
        }
    }

    /** What [status] tells besides the live state, refreshed every 60 s and after each run. */
    private data class Summary(
        val lastBatch: MemoryRun? = null,
        val lastNap: MemoryRun? = null,
        val lastDream: MemoryRun? = null,
        val nextDreamAt: Instant? = null,
        val refusals: Refusals = Refusals(0, 0),
        val chaptersToNap: List<ConversationRecord> = emptyList(),
    )

    /** The placed memories in cells of [CELL] blocks per dimension. */
    private class CueGrid(memories: List<CueMemory>) {
        private val cells: Map<String, Map<Long, List<CueMemory>>> = memories.filter { it.place != null }
            .groupBy { it.place!!.dim }
            .mapValues { (_, list) -> list.groupBy { key(cell(it.place!!.x), cell(it.place.z)) } }

        /** The memories within [PLACE_RADIUS] blocks of [pos]. */
        fun near(pos: Pos): List<CueMemory> {
            val dim = cells[pos.dim] ?: return emptyList()
            val cx = cell(pos.x)
            val cz = cell(pos.z)
            return (cx - 1..cx + 1).flatMap { x -> (cz - 1..cz + 1).flatMap { z -> dim[key(x, z)].orEmpty() } }.filter {
                val p = it.place!!
                val dx = (p.x - pos.x).toLong()
                val dy = (p.y - pos.y).toLong()
                val dz = (p.z - pos.z).toLong()
                dx * dx + dy * dy + dz * dz <= PLACE_RADIUS.toLong() * PLACE_RADIUS
            }
        }

        private fun cell(coordinate: Int) = Math.floorDiv(coordinate, CELL)

        private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
    }

    companion object {
        const val COROUTINE = "narrator2-memory"
        const val EVENTS_SUBSCRIBER = "memory-jobs"
        const val OWNER = "memory"

        /** `memory_state` keys: the operator's pause, the dream to resume and its steps, the end of the last dream, the anniversary day. */
        const val PAUSED_KEY = "jobs:paused"
        const val DREAM_PENDING_KEY = "dream:pending"
        const val DREAM_END_KEY = "dream:last_end"
        const val ANNIVERSARY_KEY = "cue:anniversary_day"

        /** The `memory_state` key of the last step dream run [runId] finished (the Dreamer writes it after each step). */
        fun stepKey(runId: Long) = "dream:$runId:step"

        const val PASS_MS = 30_000L
        const val REFRESH_MS = 60_000L
        const val SAVE_MS = 5 * 60_000L
        const val TIDY_MS = 10 * 60_000L
        const val GRID_MS = 10 * 60_000L
        const val CATCH_UP_DELAY_MS = 30_000L
        const val CATCH_UP_MS = 30 * 60_000L

        /** A batch's window ends this long before it starts: the last facts are being journaled. */
        val BATCH_LAG: Duration = Duration.ofSeconds(5)

        /** How long the model jobs wait after a 402 that did not say when the budget resets. */
        val BUDGET_WAIT: Duration = Duration.ofMinutes(15)

        /** Failed naps of a chapter after which the catch-up gives up (a 402 does not count). */
        const val MAX_NAP_FAILURES = 3

        /** Naps one catch-up queues at most. */
        const val CATCH_UP_MAX = 20

        const val PLACE_RADIUS = 16
        const val CELL = 64
        const val PLACE_MIN_IMPORTANCE = 6
        const val PLACE_COOLDOWN_S = 6 * 3_600L
        val CUE_MIN_AGE: Duration = Duration.ofHours(24)
        const val ANNIVERSARY_MIN_IMPORTANCE = 8

        /** `Due soon` in the situation: open commitments due within this time, [DUE_SOON_MAX] at most. */
        val DUE_SOON: Duration = Duration.ofHours(1)
        const val DUE_SOON_MAX = 5
        const val CUE_TEXT_MAX = 200
        const val REPORT_MAX_CHARS = 500

        private const val EVENTS_CAPACITY = 256
        private const val DUE_SCAN = 200
        private val DUE_KINDS = CanonKind.entries.filter { it.dueAllowed }.toSet()
        private const val STOP_WRITE_MS = 5_000L
        private val SUCCESS = setOf(RunStatus.DONE, RunStatus.SKIPPED)
        private val PRIORITY = mapOf(RunKind.NAP to 0, RunKind.BATCH to 1, RunKind.DREAM to 2)
        private val ORDER = compareBy<QueuedJob> { PRIORITY[it.kind] ?: 3 }
        private val MUTED = setOf(DirectorState.DISABLED, DirectorState.STOPPED, DirectorState.PAUSED, DirectorState.BLOCKED)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * The days whose memories are anniversaries on [today], with how long ago: 1 to 4 weeks, 1 to 11
         * months, 1 to 10 years (a day a month or a year back that does not exist, Feb 30, is skipped).
         */
        fun anniversaryDays(today: LocalDate): Map<LocalDate, String> {
            val days = LinkedHashMap<LocalDate, String>()
            for (weeks in 1..4) days.putIfAbsent(today.minusWeeks(weeks.toLong()), if (weeks == 1) "one week" else "$weeks weeks")
            for (months in 1..11) {
                val day = today.minusMonths(months.toLong())
                if (day.dayOfMonth == today.dayOfMonth) days.putIfAbsent(day, if (months == 1) "one month" else "$months months")
            }
            for (years in 1..10) {
                val day = today.minusYears(years.toLong())
                if (day.dayOfMonth == today.dayOfMonth) days.putIfAbsent(day, if (years == 1) "one year" else "$years years")
            }
            return days
        }

        /** A coarse age: `5h12m`, `3d`, `4w`, `1 month`, `4 months`, `1 year`, `2 years`. */
        fun ago(age: Duration): String {
            val days = age.toDays()
            return when {
                days < 2 -> ToolText.duration(age.toMillis())
                days < 14 -> "${days}d"
                days < 45 -> "${days / 7}w"
                days < 365 -> (days / 30).let { if (it == 1L) "1 month" else "$it months" }
                else -> (days / 365).let { if (it == 1L) "1 year" else "$it years" }
            }
        }
    }
}
