package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.time.Instant

/** What started a memory job (`memory_runs.trigger`, M3 §7.1, §8.1). */
enum class RunTrigger {
    /** The Chronicler's batch every `memory.chronicleMinutes`, the stats, the hygiene. */
    TIMER,

    /** A Director chapter closed: its nap. */
    CHAPTER_CLOSED,

    /** Nobody online for `memory.dreamAfterEmptyMinutes`: a dream. */
    SERVER_EMPTY,

    /** No dream for `memory.dreamMaxHours`. */
    DAILY,

    /** `/narrator memory chronicle` or `dream`. */
    OPERATOR,

    /** The catch-up after a start, then every 30 minutes. */
    STARTUP,
    ;

    val id: String get() = name.lowercase()
}

/** How a memory job stands or ended (`memory_runs.status`). */
enum class RunStatus {
    RUNNING,
    DONE,
    FAILED,

    /** The server stopped while it ran. */
    CANCELLED,

    /** Found unfinished after a crash. */
    INTERRUPTED,

    /** Nothing to do (no line nor tool call of the Narrator in the window). */
    SKIPPED,
    ;

    val id: String get() = name.lowercase()

    val finished: Boolean get() = this != RUNNING
}

/** A row of `memory_runs`. [conversations]: the model conversations of the run. */
data class MemoryRun(
    val runId: Long,
    val kind: RunKind,
    val trigger: RunTrigger,
    val status: RunStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val windowFrom: Instant?,
    val windowTo: Instant?,
    val chapter: String?,
    val conversations: List<String>,
    val writes: Int,
    val rejected: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val report: String?,
    val error: String?,
)

/**
 * The memory jobs at a glance, for `/narrator memory status` and `/narrator status`.
 *
 * @property paused `/narrator memory pause`: model jobs wait (the code's jobs go on).
 * @property blockedUntil the gateway's budget is spent (402): model jobs wait until then.
 * @property rejected24h writes the store refused within the last 24 hours; [voiceWarnings24h] the
 *   [VoiceGuard.warnings] counted in the same time.
 */
data class MemoryStatus(
    val paused: Boolean,
    val blockedUntil: Instant?,
    val running: MemoryRun?,
    val queued: List<RunKind>,
    val lastBatch: MemoryRun?,
    val lastNap: MemoryRun?,
    val lastDream: MemoryRun?,
    val nextDreamAt: Instant?,
    val worldPlaySeconds: Long,
    val rejected24h: Int,
    val voiceWarnings24h: Int,
)

/** What operators and commands can do with the memory jobs. Implemented by `MemoryJobs` (M3-12). */
interface MemoryJobsControl {
    fun status(): MemoryStatus

    /** Queues a catch-up of the Chronicler now; its run id, or why it cannot (English, for the operator). */
    fun requestChronicle(by: String): Result<Long>

    /** Queues a deep dream now; its run id, or why it cannot. */
    fun requestDream(by: String): Result<Long>

    /** Model jobs wait until [resume]; the code's jobs (stats, due times, clock) go on. */
    fun pause(by: String)

    fun resume(by: String)
}

/**
 * How a model job ended: [RunStatus.DONE], [RunStatus.FAILED], [RunStatus.SKIPPED] or
 * [RunStatus.CANCELLED]. [report]: the model's final text (≤ 500 characters, `memory_runs.report`) or
 * the error; [lastStep]: the last dream step that succeeded.
 */
data class RunOutcome(
    val status: RunStatus,
    val report: String?,
    val writes: Int,
    val rejected: Int,
    val conversations: List<String>,
    val lastStep: Int? = null,
)

/** The Chronicler (M3 §7), run by `MemoryJobs`. Implemented by `chronicle/Chronicler.kt` (M3-10). */
interface ChronicleRunner {
    /** One batch over the window [from]..[to] of the Director's chapter [chapter] (its conversation id). */
    suspend fun batch(runId: Long, chapter: String, from: Instant, to: Instant): RunOutcome

    /** The nap of a closed chapter, with or without a summary. */
    suspend fun nap(runId: Long, chapter: ConversationRecord): RunOutcome
}

/** The Dreamer (M3 §8), run by `MemoryJobs`. Implemented by `dream/Dreamer.kt` (M3-11). */
interface DreamRunner {
    /** A deep dream from step [fromStep] (1 for a new dream, later to resume an interrupted one). */
    suspend fun dream(runId: Long, fromStep: Int): RunOutcome
}
