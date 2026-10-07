package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.util.UUID

/** How soon an event should reach the Director. */
enum class Urgency {
    /** Wakes the Director after the chat debounce (addressed chat, prayers). */
    IMMEDIATE,

    /** Wakes the Director after a short grouping (deaths, arrivals, notable progress). */
    SOON,

    /** Never wakes the Director; delivered with the next wake-up or between two tool calls. */
    NEXT,

    /** Never delivered as such; summarized by the activity digest (heartbeats, situation). */
    BATCH,
}

/**
 * Everything the Director's inbox receives. The Director's coroutine is the only reader and the only
 * one to touch its state. Times are epoch milliseconds.
 */
sealed interface DirectorSignal {
    /**
     * A game event, classified by the salience.
     *
     * @property annotation what the reflexes said about a chat line, rendered after the speaker,
     *   e.g. `to you 0.92, hostile, provocation`.
     */
    data class Event(val envelope: Envelope, val urgency: Urgency, val annotation: String?) : DirectorSignal

    /** A delegated task ended ([TaskRecord.Status.DONE], `FAILED`, `CANCELLED` or `TIMEOUT`); [result] is its result or error. */
    data class TaskDone(val taskId: Long, val role: Role, val status: TaskRecord.Status, val result: String, val at: Long) : DirectorSignal

    /** A reminder set with `remind_me` is due. */
    data class ReminderDue(val id: Long, val note: String, val setAt: Long, val at: Long) : DirectorSignal

    /** `/narrator whisper`: an instruction from operator [from]. */
    data class OperatorWhisper(val from: String, val text: String, val at: Long) : DirectorSignal

    /**
     * A canon commitment is due (M3 §6.6): a promise, threat, challenge, prophecy or arc end. Urgency
     * [Urgency.SOON]; it wakes the Director even when the hour's turn budget is spent (M2 review, finding
     * 26). [line]: what follows `DUE ` in `<events>`, e.g. `C-45 promise → Mimosa, set 1h ago: « … »`
     * (French texts escaped). [at]: the due time, epoch milliseconds.
     */
    data class MemoryDue(val ref: Ref, val line: String, val at: Long) : DirectorSignal

    /**
     * A note of the memory for the Director (M3 §12.4): an incoherence, an expired commitment. Urgency
     * [Urgency.NEXT]: never wakes; told with the next wake-up. [text]: the note, escaped.
     */
    data class MemoryNote(val ref: Ref, val kind: NoteKind, val text: String, val at: Long) : DirectorSignal

    /**
     * A place return or an anniversary (M3 §10.5). Urgency [Urgency.NEXT]: never wakes. [player]: who
     * came back to the place, null for an anniversary; [line]: what follows `RETURN ` or `ANNIVERSARY `,
     * escaped.
     */
    data class MemoryCue(val kind: CueKind, val ref: Ref, val player: PlayerRef?, val line: String, val at: Long) : DirectorSignal

    /** `/narrator pause`: the running turn ends first, then the Director stays silent. */
    data object Pause : DirectorSignal

    /** `/narrator resume`. */
    data object Resume : DirectorSignal

    /** Close the current chapter (with a summary) at the next quiet moment. */
    data class CloseChapter(val reason: ConversationRecord.EndReason) : DirectorSignal

    /** `/narrator reload` installed new settings. */
    data class SettingsChanged(val old: Settings, val new: Settings) : DirectorSignal
}

/** What brought a memory back by itself (M3 §10.5). */
enum class CueKind {
    /** A player is back where a memory happened. */
    RETURN,

    /** A memory of the same day, a week, a month or a year ago. */
    ANNIVERSARY,
}

/** States of the Director. */
enum class DirectorState {
    /** No valid configuration or no LLMGW key. */
    DISABLED,

    /** Nothing to do. */
    IDLE,

    /** A wake-up is scheduled (events are being grouped). */
    WAITING,

    /** A turn is running. */
    RUNNING,

    /** The chapter is being summarized. */
    CLOSING,

    /** `/narrator pause`. */
    PAUSED,

    /** After a transient gateway failure. */
    BACKOFF,

    /** Budget spent (until it resets) or key refused (until a reload). */
    BLOCKED,

    /** The server is stopping. */
    STOPPED,
}

/** A reminder waiting to wake the Director; [at] in epoch milliseconds. */
data class PendingReminder(val id: Long, val note: String, val at: Long)

/**
 * The Director's state, for `/narrator status`, tools and the situation report.
 *
 * @property until end of the current wait: wake-up (`WAITING`), backoff or block; epoch milliseconds.
 * @property reason why the Director is disabled, paused, backing off or blocked.
 * @property chapter number of the open chapter; null when none is open.
 * @property contextTokens context size of the chapter's last request.
 * @property inbox signals waiting in the inbox.
 * @property droppedSignals signals dropped because the inbox was full.
 * @property turnId the running turn, if any.
 * @property turnsThisHour the turns started within the last hour, against [turnsPerHour]
 *   (`director.turnsPerHour`, M2 review, finding 26): once they are all used, only addressed chat,
 *   prayers and whispers wake the Director.
 */
data class DirectorStatus(
    val state: DirectorState,
    val until: Long?,
    val reason: String?,
    val chapter: Int?,
    val conversationId: String?,
    val contextTokens: Long,
    val inbox: Int,
    val droppedSignals: Long,
    val turnId: Long?,
    val operatorChannel: OperatorChannelMode,
    val reminders: List<PendingReminder>,
    val turnsThisHour: Int,
    val turnsPerHour: Int,
)

/** How tools and commands talk to the Director. Implemented by the Director; faked in tests. */
interface DirectorControl {
    /**
     * Puts [signal] in the inbox. Any thread; never blocks. The inbox is bounded in every state (M2 review,
     * finding 22): when it is full, the least pressing and oldest item goes, counted by kind and told to the
     * model at the next wake-up: an [Urgency.NEXT] event first, then a [Urgency.SOON] event that is not
     * chat, then chat (addressed chat and prayers too, as a last resort), then a task result, a reminder
     * or a whisper. Control signals are never dropped.
     */
    fun post(signal: DirectorSignal)

    /**
     * Schedules a [DirectorSignal.ReminderDue] in [inSeconds]; returns the reminder id. Fails (with an
     * English message for the model) when the Director cannot take it, e.g. it is stopping.
     */
    fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long>

    fun status(): DirectorStatus

    /**
     * One English line (at most 200 characters) on what [player] did in the last [minutes] minutes,
     * from the activity digest (M1_M2 §12.4), e.g. `mined 140 (3 diamond_ore y=-54..-51)`; null when
     * nothing is known. Read by `list_players` and the situation report. Any thread.
     */
    fun recentActivity(player: UUID, minutes: Int): String?
}
