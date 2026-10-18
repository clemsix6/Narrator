package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import java.util.Locale
import kotlin.random.Random

/**
 * When the Director wakes up (M1_M2 §12.3), from the items waiting in its inbox. Times are epoch
 * milliseconds; nothing here reads a clock, so it is tested with plain numbers.
 *
 * - [Urgency.IMMEDIATE] (addressed chat, prayers, whispers): `director.chatDebounceMs` after the last
 *   one, at most [IMMEDIATE_MAX_MS] after the first, to catch the follow-ups of a player who types in
 *   several messages.
 * - [Urgency.SOON] (deaths, arrivals, notable progress, task results, reminders): [SOON_QUIET_MS] after
 *   the last one, at most [SOON_MAX_MS] after the first.
 * - [Urgency.NEXT] items never wake the Director, chat between players included (M2 review, finding
 *   26): they go with the next wake-up, a heartbeat's included, or between two tool calls. Neither do
 *   items demoted after failed requests ([Item.waking]).
 * - Once the [TurnBudget] is spent (`throttled`), only [Urgency.IMMEDIATE] items wake it: addressed
 *   chat, prayers and operator whispers; and a commitment that comes due ([DirectorSignal.MemoryDue],
 *   M3 §11.3), rare and binding. The other items wait for a turn to leave the hour.
 * - Heartbeats: a random time in `director.heartbeatMinutes` after the last turn ([nextHeartbeat]),
 *   worth what [heartbeatWorth] says.
 */
object WakeScheduler {
    const val IMMEDIATE_MAX_MS = 4_000L
    const val SOON_QUIET_MS = 3_000L
    const val SOON_MAX_MS = 10_000L

    /** What a chat line between players waiting adds to a heartbeat's worth: 4 of them wake the Director. */
    const val CHAT_WORTH = 0.125

    /** What another [Urgency.NEXT] event waiting adds to a heartbeat's worth: 2 of them wake the Director. */
    const val NEXT_WORTH = 0.25

    /**
     * When the Director should wake up for [items], never before [notBefore] (after a refused reply);
     * null when none of them wakes it. [throttled]: the budget of turns is spent, only
     * [Urgency.IMMEDIATE] items and due commitments wake it.
     */
    fun wakeAt(items: List<Item>, debounceMs: Long, notBefore: Long? = null, throttled: Boolean = false): Long? {
        val waking = items.filter { it.waking }
        val immediate = waking.filter { it.urgency == Urgency.IMMEDIATE }.map { it.at }
        val soon = waking.filter { it.urgency == Urgency.SOON && (!throttled || it.signal is DirectorSignal.MemoryDue) }.map { it.at }
        val candidates = listOfNotNull(
            if (immediate.isEmpty()) null else minOf(immediate.max() + debounceMs, immediate.min() + IMMEDIATE_MAX_MS),
            if (soon.isEmpty()) null else minOf(soon.max() + SOON_QUIET_MS, soon.min() + SOON_MAX_MS),
        )
        val at = candidates.minOrNull() ?: return null
        return if (notBefore != null) maxOf(at, notBefore) else at
    }

    /** The next heartbeat: a random whole second in [minutes] after [from]. */
    fun nextHeartbeat(from: Long, minutes: IntRange, random: Random): Long =
        from + random.nextLong(minutes.first * 60L, minutes.last * 60L + 1) * 1_000L

    /**
     * What the salient items waiting since the last turn are worth to a heartbeat, from 0 to 1: a chat
     * line between players [CHAT_WORTH], another [Urgency.NEXT] event or a memory cue (a place return, an
     * anniversary: M3 §10.5) [NEXT_WORTH], an item that wakes the Director by itself 1. Items demoted
     * after failed requests count for nothing, and so do the memory's notes (an incoherence, an expired
     * commitment): they go with the next wake-up, they are no reason for one.
     */
    fun salience(items: List<Item>): Double = items.sumOf(::worth).coerceAtMost(1.0)

    private fun worth(item: Item): Double = when {
        item.failures >= Item.DEMOTED || item.urgency == Urgency.BATCH || item.signal is DirectorSignal.MemoryNote -> 0.0
        item.isUnaddressedChat -> CHAT_WORTH
        item.urgency == Urgency.NEXT -> NEXT_WORTH
        else -> 1.0
    }

    /**
     * What a heartbeat is worth, and why, for its decision (M2 review, finding 26). The heuristic judges
     * nothing (it always says 1.0): without a classifier, a heartbeat is worth the salient items waiting
     * since the last turn ([salience]), so that a quiet world costs no turn. A classifier's [verdict]
     * only reads the activity summary, not the chat: it counts, but never below what waits. Without a
     * verdict (the activity summary is empty), what waits alone.
     */
    fun heartbeatWorth(verdict: HeartbeatVerdict?, waiting: List<Item>): HeartbeatWorth {
        val salient = salience(waiting)
        val counted = waiting.count { worth(it) > 0.0 }
        val byWaiting = "worth ${p(salient)} by $counted salient ${if (counted == 1) "item" else "items"} waiting"
        return when {
            verdict == null || verdict.source == HeuristicReflexes.SOURCE -> HeartbeatWorth(salient, byWaiting)
            verdict.worth >= salient -> HeartbeatWorth(verdict.worth, "worth ${p(verdict.worth)} by ${verdict.source}")
            else -> HeartbeatWorth(salient, "$byWaiting (${verdict.source} said ${p(verdict.worth)})")
        }
    }

    /** A heartbeat's worth, from 0 to 1, and where it comes from: `worth 0.50 by 2 salient items waiting`. */
    data class HeartbeatWorth(val value: Double, val why: String)

    private fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

/**
 * The Director's budget of turns per hour (M2 review, finding 26), `director.turnsPerHour`: once that
 * many turns started within the last hour, only addressed chat, prayers, operator whispers and due
 * commitments wake the Narrator ([WakeScheduler.wakeAt]); salient events wait for a turn to leave the hour, or go with the
 * next wake-up, and heartbeats are skipped. The Director counts its turns ([started]); the salience
 * reads the budget to write its `salience throttled` decisions. Any thread.
 */
class TurnBudget(private val perHour: () -> Int) {
    private val starts = ArrayDeque<Long>() // guarded by itself, oldest first

    /** A turn started at [at], epoch milliseconds. */
    fun started(at: Long) {
        synchronized(starts) { starts.addLast(at) }
    }

    /** Whether [perHour] turns or more started within the hour before [now]. */
    fun spent(now: Long): Boolean = used(now) >= perHour()

    /** The turns started within the hour before [now]. */
    fun used(now: Long): Int = synchronized(starts) {
        while (starts.isNotEmpty() && starts.first() <= now - HOUR_MS) starts.removeFirst()
        starts.size
    }

    /** `the budget of 30 turns per hour is spent`, for decisions. */
    fun describe(): String {
        val turns = perHour()
        return "the budget of $turns ${if (turns == 1) "turn" else "turns"} per hour is spent"
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
