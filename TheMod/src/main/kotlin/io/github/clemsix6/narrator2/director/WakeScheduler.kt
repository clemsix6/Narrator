package io.github.clemsix6.narrator2.director

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
 * - Chat between players ([Urgency.NEXT]) waits for company: from the [CHAT_PILE]th message waiting it
 *   counts as [Urgency.SOON], and the oldest waits [CHAT_WAIT_MS] at most, so that the Narrator can join
 *   a conversation.
 * - Other [Urgency.NEXT] items never wake the Director: they go with the next wake-up, or between two
 *   tool calls. Neither do items demoted after failed requests ([Item.waking]).
 * - Heartbeats: a random time in `director.heartbeatMinutes` after the last turn ([nextHeartbeat]).
 */
object WakeScheduler {
    const val IMMEDIATE_MAX_MS = 4_000L
    const val SOON_QUIET_MS = 3_000L
    const val SOON_MAX_MS = 10_000L
    const val CHAT_PILE = 4
    const val CHAT_WAIT_MS = 45_000L

    /**
     * When the Director should wake up for [items], never before [notBefore] (after a refused reply);
     * null when none of them wakes it.
     */
    fun wakeAt(items: List<Item>, debounceMs: Long, notBefore: Long? = null): Long? {
        val waking = items.filter { it.waking }
        val immediate = waking.filter { it.urgency == Urgency.IMMEDIATE }.map { it.at }
        val chat = waking.filter { it.isUnaddressedChat }.map { it.at }.sorted()
        val soon = waking.filter { it.urgency == Urgency.SOON }.map { it.at } + chat.drop(CHAT_PILE - 1)
        val candidates = listOfNotNull(
            if (immediate.isEmpty()) null else minOf(immediate.max() + debounceMs, immediate.min() + IMMEDIATE_MAX_MS),
            if (soon.isEmpty()) null else minOf(soon.max() + SOON_QUIET_MS, soon.min() + SOON_MAX_MS),
            chat.firstOrNull()?.plus(CHAT_WAIT_MS),
        )
        val at = candidates.minOrNull() ?: return null
        return if (notBefore != null) maxOf(at, notBefore) else at
    }

    /** The next heartbeat: a random whole second in [minutes] after [from]. */
    fun nextHeartbeat(from: Long, minutes: IntRange, random: Random): Long =
        from + random.nextLong(minutes.first * 60L, minutes.last * 60L + 1) * 1_000L
}
