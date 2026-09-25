package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A signal waiting in the Director's inbox: a game event, a task result, a reminder due or an
 * operator's whisper (M1_M2 §12.2).
 *
 * @property at when it happened, epoch milliseconds: the event's time, the task's end, the reminder's
 *   due time, the whisper's time. Wake-ups are grouped from these times, and lines are dated relative
 *   to them.
 * @property failures requests that carried it and got no usable reply (refused, cut off, rejected):
 *   from [DEMOTED] on it no longer wakes the Director (and a chat line is dropped), and from [DROPPED]
 *   on it is dropped. Transient gateway failures do not count.
 */
data class Item(val signal: DirectorSignal, val at: Long, val failures: Int = 0) {
    init {
        require(signal !is DirectorSignal.Pause && signal !is DirectorSignal.Resume && signal !is DirectorSignal.CloseChapter &&
            signal !is DirectorSignal.SettingsChanged) { "control signals are not inbox items: $signal" }
    }

    /** How soon it should reach the Director: task results and reminders are like [Urgency.SOON], whispers like [Urgency.IMMEDIATE]. */
    val urgency: Urgency
        get() = when (signal) {
            is DirectorSignal.Event -> signal.urgency
            is DirectorSignal.OperatorWhisper -> Urgency.IMMEDIATE
            else -> Urgency.SOON
        }

    /** The chat message it carries, if any. */
    val chat: ChatMessage? get() = (signal as? DirectorSignal.Event)?.envelope?.event as? ChatMessage

    /** A public message between players: it wakes nobody and goes with the next wake-up or heartbeat (M2 review, finding 26). */
    val isUnaddressedChat: Boolean get() = chat?.channel == ChatChannel.PUBLIC && urgency == Urgency.NEXT

    /** A message or a prayer addressed to the Narrator: its author waits for an answer. */
    val isAddressedChat: Boolean get() = chat != null && urgency == Urgency.IMMEDIATE

    /** The event id, for game events. */
    val eventId: Long? get() = (signal as? DirectorSignal.Event)?.envelope?.id

    /** The player waiting for an answer: the author of an addressed message or a prayer. */
    val waitingPlayer: UUID? get() = if (isAddressedChat) chat?.player?.uuid else null

    /** Whether it still wakes the Director by itself. */
    val waking: Boolean get() = failures < DEMOTED && urgency != Urgency.BATCH

    companion object {
        /** From this many failed requests, an item no longer wakes the Director; a chat line is dropped. */
        const val DEMOTED = 2

        /** From this many failed requests, an item is dropped. */
        const val DROPPED = 3

        /** The item of [signal], dated by the signal itself. */
        fun of(signal: DirectorSignal): Item = Item(signal, timeOf(signal))

        private fun timeOf(signal: DirectorSignal): Long = when (signal) {
            is DirectorSignal.Event -> signal.envelope.ts
            is DirectorSignal.TaskDone -> signal.at
            is DirectorSignal.ReminderDue -> signal.at
            is DirectorSignal.OperatorWhisper -> signal.at
            else -> error("not an inbox item: $signal")
        }
    }
}

/**
 * The Director's inbox (M1_M2 §12.2): the items waiting to be told to the model, and the control
 * signals (pause, resume, chapter close, reload) waiting to be applied. Anyone may [post] from any
 * thread without blocking; the Director's coroutine is the only one to take from it.
 *
 * It holds [capacity] items at most, whatever the Director's state (a backoff keeps every event, a
 * pause or a block only what someone waits for): beyond, one item is dropped, the least pressing and
 * oldest first — a [Urgency.NEXT] event, else a [Urgency.SOON] event that is not chat, else chat
 * (addressed chat and prayers included: a flood of them must not grow without bound), else a task
 * result, a reminder or a whisper. Control signals are never dropped. What is dropped is counted by
 * kind ([EventFormatter.kind]): the next wake-up or injection that [takeOverflow] tells the model how
 * many older events were left out, by kind (M2 review, finding 22).
 */
class Inbox(private val capacity: Int = CAPACITY) {
    private val lock = Any()
    private val items = ArrayList<Item>() // guarded by lock, in arrival order
    private val controls = ArrayList<DirectorSignal>() // guarded by lock
    private val overflow = LinkedHashMap<String, Int>() // guarded by lock: kinds of the items dropped and not told yet
    private var droppedCount = 0L // guarded by lock
    private val changes = Channel<Unit>(Channel.CONFLATED)

    /** Items and control signals waiting. */
    val size: Int get() = synchronized(lock) { items.size + controls.size }

    /** Items dropped because the inbox was full. */
    val dropped: Long get() = synchronized(lock) { droppedCount }

    /** Queues [signal]: an item, or a control signal. Any thread; never blocks. */
    fun post(signal: DirectorSignal) {
        synchronized(lock) {
            when (signal) {
                is DirectorSignal.Pause, is DirectorSignal.Resume, is DirectorSignal.CloseChapter, is DirectorSignal.SettingsChanged ->
                    controls += signal
                else -> {
                    items += Item.of(signal)
                    trim()
                }
            }
        }
        changed()
    }

    /** Wakes whoever [await]s: something changed (a signal, a reminder). Any thread. */
    fun changed() {
        changes.trySend(Unit)
    }

    /** Waits until something is posted or [changed], at most [timeoutMs] (forever when null). */
    suspend fun await(timeoutMs: Long?) {
        when {
            timeoutMs == null -> changes.receive()
            timeoutMs > 0 -> withTimeoutOrNull(timeoutMs) { changes.receive() }
        }
    }

    /** The control signals waiting, in order; they leave the inbox. */
    fun takeControls(): List<DirectorSignal> = synchronized(lock) { controls.toList().also { controls.clear() } }

    /** The items waiting, in arrival order; they stay in the inbox. */
    fun items(): List<Item> = synchronized(lock) { items.toList() }

    /** The items waiting, in arrival order; they leave the inbox. */
    fun takeAll(): List<Item> = synchronized(lock) { items.toList().also { items.clear() } }

    /** Puts [back] at the head of the inbox, before what arrived since they were taken; the capacity still holds. */
    fun putBack(back: List<Item>) {
        if (back.isEmpty()) return
        synchronized(lock) {
            items.addAll(0, back)
            trim()
        }
        changed()
    }

    /** Removes and returns the items that match [predicate]. */
    fun discard(predicate: (Item) -> Boolean): List<Item> = synchronized(lock) {
        val gone = items.filter(predicate)
        items.removeAll(gone.toSet())
        gone
    }

    /** How many items were dropped since the last call, by kind; the count starts again from zero. */
    fun takeOverflow(): Map<String, Int> = synchronized(lock) { LinkedHashMap(overflow).also { overflow.clear() } }

    /** Counts [counts] as dropped again: the request that told them got no usable reply. */
    fun restoreOverflow(counts: Map<String, Int>) {
        if (counts.isEmpty()) return
        synchronized(lock) { counts.forEach { (kind, count) -> overflow.merge(kind, count, Int::plus) } }
    }

    /** Drops items, the least pressing and oldest first, until the inbox holds [capacity] at most. Under [lock]. */
    private fun trim() {
        while (items.size > capacity) {
            val victim = items.firstOrNull { it.urgency == Urgency.NEXT || it.urgency == Urgency.BATCH }
                ?: items.firstOrNull { it.urgency == Urgency.SOON && it.signal is DirectorSignal.Event && it.chat == null }
                ?: items.firstOrNull { it.chat != null }
                ?: items.first()
            items.remove(victim)
            overflow.merge(EventFormatter.kind(victim), 1, Int::plus)
            droppedCount++
        }
    }

    companion object {
        /**
         * Items the inbox holds at most: what a wake-up tells at most, about 20,000 characters of lines. A
         * busy server fills it only when the Director could not wake for minutes (backoff, block, pause).
         */
        const val CAPACITY = 200
    }
}
