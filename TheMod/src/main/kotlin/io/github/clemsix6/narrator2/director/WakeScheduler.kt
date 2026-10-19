package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
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
 *   chat, prayers and operator whispers; and the items that wake it past the budget ([pastBudget]): a
 *   commitment that comes due ([DirectorSignal.MemoryDue], M3 §11.3), rare and binding, and a mechanic's
 *   priority news ([DirectorSignal.Mechanic.priority], M4 §4.4: a challenge or a duel that ends, a judged
 *   challenge to judge, an offering). The other items wait for a turn to leave the hour. A player's own
 *   news ([byPlayer]: an offering) wakes it past the budget [PLAYER_NEWS_PER_HOUR] times an hour at most, all
 *   players together (M4 review, finding 6): beyond, it waits like the others.
 * - A module's news ([byModule], M5 §9.2) never wakes it past the budget (no priority), and within it wakes it
 *   [MODULE_NEWS_PER_HOUR] times an hour at most, all modules together (`moduleNewsSpent`): beyond, it waits for the
 *   next wake-up, and is worth no more than a [Urgency.NEXT] event to a heartbeat, so that a talkative module cannot
 *   spend the owner's tokens.
 * - Heartbeats: a random time in `director.heartbeatMinutes` after the last turn ([nextHeartbeat]),
 *   worth what [heartbeatWorth] says.
 */
object WakeScheduler {
    const val IMMEDIATE_MAX_MS = 4_000L
    const val SOON_QUIET_MS = 3_000L
    const val SOON_MAX_MS = 10_000L

    /** What a chat line between players waiting adds to a heartbeat's worth: 4 of them wake the Director. */
    const val CHAT_WORTH = 0.125

    /**
     * What a chat line between players saying they are bored adds to a heartbeat's worth: one wakes the Director
     * (M4-24, finding 5: "je m'ennuie, on fait quoi ?" was worth a line of banter, and the heartbeat was skipped).
     */
    const val BORED_WORTH = ReflexThresholds.HEARTBEAT_WORTH

    /** What another [Urgency.NEXT] event waiting adds to a heartbeat's worth: 2 of them wake the Director. */
    const val NEXT_WORTH = 0.25

    /**
     * Wake-ups past the budget of turns that the players' own news ([byPlayer]) may cause in an hour, all players
     * together (M4 review, finding 6): one player offering a block of dirt every few minutes must not spend the
     * owner's tokens.
     */
    const val PLAYER_NEWS_PER_HOUR = 4

    /**
     * Wake-ups that the modules' news alone ([byModule]) may cause in an hour, all modules together, within the budget
     * of turns (M5 §9.2; past it they wake nothing, having no priority): a module that talks (`tellDirector`, once every
     * 5 minutes, up to `forge.maxActive` modules) must not make a turn of Opus of every line.
     */
    const val MODULE_NEWS_PER_HOUR = 6

    /**
     * When the Director should wake up for [items], never before [notBefore] (after a refused reply);
     * null when none of them wakes it. [throttled]: the budget of turns is spent, only
     * [Urgency.IMMEDIATE] items and those that wake it [pastBudget] do; [playerNewsSpent]: the players' news
     * spent their wake-ups past the budget too; [moduleNewsSpent]: the modules' news spent their wake-ups of the hour.
     */
    fun wakeAt(
        items: List<Item>,
        debounceMs: Long,
        notBefore: Long? = null,
        throttled: Boolean = false,
        playerNewsSpent: Boolean = false,
        moduleNewsSpent: Boolean = false,
    ): Long? {
        val waking = items.filter { it.waking }
        val immediate = waking.filter { it.urgency == Urgency.IMMEDIATE }.map { it.at }
        val soon = waking.filter {
            it.urgency == Urgency.SOON && (!throttled || pastBudget(it, playerNewsSpent)) && !(moduleNewsSpent && byModule(it.signal))
        }.map { it.at }
        val candidates = listOfNotNull(
            if (immediate.isEmpty()) null else minOf(immediate.max() + debounceMs, immediate.min() + IMMEDIATE_MAX_MS),
            if (soon.isEmpty()) null else minOf(soon.max() + SOON_QUIET_MS, soon.min() + SOON_MAX_MS),
        )
        val at = candidates.minOrNull() ?: return null
        return if (notBefore != null) maxOf(at, notBefore) else at
    }

    /**
     * Whether [item] wakes the Director even when the hour's turns are spent (M2 review, finding 26): a
     * commitment that comes due, and a mechanic's priority news (M4 §4.4). Both are rare: a duel's start, a
     * rule's note or a joker played wait, and the Director starts the challenges and duels whose end wakes it.
     * A player's own news ([byPlayer]) no longer does once [playerNewsSpent].
     */
    fun pastBudget(item: Item, playerNewsSpent: Boolean = false): Boolean = when (val signal = item.signal) {
        is DirectorSignal.MemoryDue -> true
        is DirectorSignal.Mechanic -> signal.priority && !(playerNewsSpent && byPlayer(signal))
        else -> false
    }

    /**
     * Whether [signal] is a priority news that a player sends by an act of their own (M4 review, finding 6): an
     * offering (`/offer`). Its wake-ups past the budget are counted ([TurnBudget.playerNewsSpent]).
     */
    fun byPlayer(signal: DirectorSignal): Boolean =
        signal is DirectorSignal.Mechanic && signal.priority && signal.kind == MechanicSignal.OFFERING

    /**
     * Whether a wake-up for [items] while the budget of turns is spent is owed to the players' own news alone
     * ([byPlayer]): one of them wakes the Director, and nothing else among [items] would (addressed chat, a prayer,
     * a whisper, a due commitment, the engine's other priority news). Such a wake-up counts against
     * [PLAYER_NEWS_PER_HOUR].
     */
    fun wokenByPlayers(items: List<Item>): Boolean {
        val waking = items.filter { it.waking }
        return waking.any { byPlayer(it.signal) } &&
            waking.none { it.urgency == Urgency.IMMEDIATE || (it.urgency == Urgency.SOON && pastBudget(it) && !byPlayer(it.signal)) }
    }

    /** Whether [signal] is a module's news (M5 §9.2): its end, or what it tells the Director. Its wake-ups are counted ([TurnBudget.moduleNewsSpent]). */
    fun byModule(signal: DirectorSignal): Boolean = signal is DirectorSignal.Mechanic && signal.kind == MechanicSignal.MODULE

    /**
     * Whether a wake-up for [items] within the budget of turns is owed to the modules' news alone ([byModule]): one of
     * them wakes the Director, and nothing else among [items] would (addressed chat, a prayer, a whisper, any other
     * salient item). Such a wake-up counts against [MODULE_NEWS_PER_HOUR].
     */
    fun wokenByModules(items: List<Item>): Boolean {
        val waking = items.filter { it.waking }
        return waking.any { byModule(it.signal) } &&
            waking.none { it.urgency == Urgency.IMMEDIATE || (it.urgency == Urgency.SOON && !byModule(it.signal)) }
    }

    /** The next heartbeat: a random whole second in [minutes] after [from]. */
    fun nextHeartbeat(from: Long, minutes: IntRange, random: Random): Long =
        from + random.nextLong(minutes.first * 60L, minutes.last * 60L + 1) * 1_000L

    /**
     * What the salient items waiting since the last turn are worth to a heartbeat, from 0 to 1: a chat
     * line between players [CHAT_WORTH] ([BORED_WORTH] when [bored] says its player is bored), another
     * [Urgency.NEXT] event, a memory cue (a place return, an anniversary: M3 §10.5) or a module's news ([byModule]:
     * it woke the Director by itself while it could, and past [MODULE_NEWS_PER_HOUR] it must not make every heartbeat
     * a turn) [NEXT_WORTH], an item that wakes the Director by itself 1. Items demoted after failed requests count for
     * nothing, and so do the memory's notes (an incoherence, an expired commitment): they go with the next wake-up,
     * they are no reason for one.
     */
    fun salience(items: List<Item>, bored: (String) -> Boolean = { false }): Double = items.sumOf { worth(it, bored) }.coerceAtMost(1.0)

    private fun worth(item: Item, bored: (String) -> Boolean): Double = when {
        item.failures >= Item.DEMOTED || item.urgency == Urgency.BATCH || item.signal is DirectorSignal.MemoryNote -> 0.0
        item.isUnaddressedChat -> if (isBored(item, bored)) BORED_WORTH else CHAT_WORTH
        item.urgency == Urgency.NEXT || byModule(item.signal) -> NEXT_WORTH
        else -> 1.0
    }

    private fun isBored(item: Item, bored: (String) -> Boolean): Boolean = item.isUnaddressedChat && bored(item.chat!!.text)

    /**
     * What a heartbeat is worth, and why, for its decision (M2 review, finding 26). The heuristic judges
     * nothing (it always says 1.0): without a classifier, a heartbeat is worth the salient items waiting
     * since the last turn ([salience], with [bored] telling the chat lines of bored players), so that a quiet
     * world costs no turn. A classifier's [verdict] only reads the activity summary, not the chat: it counts,
     * but never below what waits. Without a verdict (the activity summary is empty), what waits alone.
     */
    fun heartbeatWorth(verdict: HeartbeatVerdict?, waiting: List<Item>, bored: (String) -> Boolean = { false }): HeartbeatWorth {
        val salient = salience(waiting, bored)
        val counted = waiting.count { worth(it, bored) > 0.0 }
        val boredLines = waiting.count { isBored(it, bored) }
        val byWaiting = "worth ${p(salient)} by $counted salient ${if (counted == 1) "item" else "items"} waiting" +
            if (boredLines > 0) " ($boredLines saying a player is bored)" else ""
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
 * many turns started within the last hour, only addressed chat, prayers, operator whispers, due commitments
 * and the mechanics' priority news wake the Narrator ([WakeScheduler.wakeAt]); salient events wait for a turn to leave the hour, or go with the
 * next wake-up, and heartbeats are skipped. The players' own news (an offering) wake it past the budget
 * [WakeScheduler.PLAYER_NEWS_PER_HOUR] times an hour at most ([playerNewsSpent], M4 review, finding 6); the
 * modules' news wake it [WakeScheduler.MODULE_NEWS_PER_HOUR] times an hour at most within it ([moduleNewsSpent],
 * M5 §9.2). The Director counts its turns ([started]); the salience reads the budget to write its
 * `salience throttled` decisions. Any thread.
 */
class TurnBudget(private val perHour: () -> Int) {
    private val starts = ArrayDeque<Long>() // guarded by itself, oldest first
    private val byPlayers = ArrayDeque<Long>() // guarded by starts, oldest first: the turns past the budget for the players' news
    private val byModules = ArrayDeque<Long>() // guarded by starts, oldest first: the turns the modules' news alone woke

    /**
     * A turn started at [at], epoch milliseconds; [byPlayers]: past the budget, for the players' own news alone
     * ([WakeScheduler.wokenByPlayers]); [byModules]: for the modules' news alone ([WakeScheduler.wokenByModules]).
     */
    fun started(at: Long, byPlayers: Boolean = false, byModules: Boolean = false) {
        synchronized(starts) {
            starts.addLast(at)
            if (byPlayers) this.byPlayers.addLast(at)
            if (byModules) this.byModules.addLast(at)
        }
    }

    /** Whether [perHour] turns or more started within the hour before [now]. */
    fun spent(now: Long): Boolean = used(now) >= perHour()

    /** The turns started within the hour before [now]. */
    fun used(now: Long): Int = synchronized(starts) {
        while (starts.isNotEmpty() && starts.first() <= now - HOUR_MS) starts.removeFirst()
        starts.size
    }

    /** Whether the players' news woke the Director past the budget [WakeScheduler.PLAYER_NEWS_PER_HOUR] times within the hour before [now]. */
    fun playerNewsSpent(now: Long): Boolean = playerNewsUsed(now) >= WakeScheduler.PLAYER_NEWS_PER_HOUR

    /** The turns started past the budget for the players' news within the hour before [now]. */
    fun playerNewsUsed(now: Long): Int = synchronized(starts) {
        while (byPlayers.isNotEmpty() && byPlayers.first() <= now - HOUR_MS) byPlayers.removeFirst()
        byPlayers.size
    }

    /** Whether the modules' news alone woke the Director [WakeScheduler.MODULE_NEWS_PER_HOUR] times within the hour before [now]. */
    fun moduleNewsSpent(now: Long): Boolean = moduleNewsUsed(now) >= WakeScheduler.MODULE_NEWS_PER_HOUR

    /** The turns the modules' news alone woke within the hour before [now]. */
    fun moduleNewsUsed(now: Long): Int = synchronized(starts) {
        while (byModules.isNotEmpty() && byModules.first() <= now - HOUR_MS) byModules.removeFirst()
        byModules.size
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
