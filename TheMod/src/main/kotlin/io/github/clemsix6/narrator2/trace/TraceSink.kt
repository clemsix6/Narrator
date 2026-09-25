package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.journal.IdKind

/**
 * Where the engine's traces go: model calls, tool calls, decisions, turns, conversations, tasks and
 * chat verdicts. Implemented by the Journal, which queues every record for its writer thread (a call
 * never writes on the caller's thread and never blocks); tests use an in-memory sink.
 *
 * Records are whole rows. For turns, conversations and tasks, the first record with a given id inserts
 * the row and each later one replaces it: send the full, updated record each time.
 */
interface TraceSink {
    /**
     * A new id of [kind], strictly greater than every id handed out before, restarts included, even
     * after a crash that lost the rows of the last ids: the Journal reserves ids on disk before handing
     * them out (`journal/IdReservations`). An id exists before its row is written, so a tool result can
     * cite `turn 42` and a decision the event that caused it.
     */
    fun nextId(kind: IdKind): Long

    fun llmCall(r: LlmCallRecord)

    fun toolCall(r: ToolCallRecord)

    fun decision(r: DecisionRecord)

    /** Inserts the turn, or updates the row with the same `turn_id`. */
    fun turn(r: TurnRecord)

    /** Inserts the conversation, or updates the row with the same `conversation_id`. */
    fun conversation(r: ConversationRecord)

    /** Inserts the task, or updates the row with the same `task_id`. */
    fun task(r: TaskRecord)

    fun chatSignals(r: ChatSignalsRecord)
}
