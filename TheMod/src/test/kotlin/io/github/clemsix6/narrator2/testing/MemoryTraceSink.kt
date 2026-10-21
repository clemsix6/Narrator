package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.TurnRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A [TraceSink] that keeps every record in memory, in arrival order. Ids start at 1 for each kind.
 * Turns, conversations and tasks are recorded at each insert and update: [latestTurn] and friends give
 * the last version of a row.
 */
class MemoryTraceSink : TraceSink {
    private val ids = ConcurrentHashMap<IdKind, AtomicLong>()

    val llmCalls = CopyOnWriteArrayList<LlmCallRecord>()
    val toolCalls = CopyOnWriteArrayList<ToolCallRecord>()
    val decisions = CopyOnWriteArrayList<DecisionRecord>()
    val turns = CopyOnWriteArrayList<TurnRecord>()
    val conversations = CopyOnWriteArrayList<ConversationRecord>()
    val tasks = CopyOnWriteArrayList<TaskRecord>()
    val chatSignals = CopyOnWriteArrayList<ChatSignalsRecord>()
    val lineLabels = CopyOnWriteArrayList<LineLabelsRecord>()

    override fun nextId(kind: IdKind): Long = ids.computeIfAbsent(kind) { AtomicLong(1) }.getAndIncrement()

    override fun llmCall(r: LlmCallRecord) {
        llmCalls += r
    }

    override fun toolCall(r: ToolCallRecord) {
        toolCalls += r
    }

    override fun decision(r: DecisionRecord) {
        decisions += r
    }

    override fun turn(r: TurnRecord) {
        turns += r
    }

    override fun conversation(r: ConversationRecord) {
        conversations += r
    }

    override fun task(r: TaskRecord) {
        tasks += r
    }

    override fun chatSignals(r: ChatSignalsRecord) {
        chatSignals += r
    }

    override fun lineLabels(r: LineLabelsRecord) {
        lineLabels += r
    }

    /** The last version of turn [turnId]. */
    fun latestTurn(turnId: Long): TurnRecord? = turns.lastOrNull { it.turnId == turnId }

    /** The last version of conversation [conversationId]. */
    fun latestConversation(conversationId: String): ConversationRecord? = conversations.lastOrNull { it.conversationId == conversationId }

    /** The last version of task [taskId]. */
    fun latestTask(taskId: Long): TaskRecord? = tasks.lastOrNull { it.taskId == taskId }
}
