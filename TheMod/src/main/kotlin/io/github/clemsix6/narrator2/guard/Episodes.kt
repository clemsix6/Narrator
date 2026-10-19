package io.github.clemsix6.narrator2.guard

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One punishment: the hostile actions of one act against one player, until all their effects are over
 * (M1_M2 §10.3). An act is a Director turn or one of the engine's firings (`Caller.act`, M4 §4.3).
 *
 * @property act the act that opened it; the hostile actions of that act extend it. Null for a caller
 *   without an act (a worker's task): such an episode is never extended, each of its hostile actions is a
 *   punishment.
 * @property until when its last effect ends, epoch milliseconds.
 */
data class Episode(val target: UUID, val act: Long?, val until: Long)

/**
 * The last hostile action of one category against a player (M4 §6.1): [act] struck at [at], epoch
 * milliseconds. Another act may strike in the same category once `Caps.categoryCooldownMinutes` have passed.
 */
data class Strike(val act: Long?, val at: Long)

/**
 * The punishments running against each player, when the recent ones began, and the categories they were
 * struck in. An episode opens with the first hostile action of an act against a player; the hostile actions
 * of the same act extend it (one punishment = several coordinated actions); it ends when its last effect
 * ends, or at a pardon ([close]). The guards rebuild it from the player's guard state when they join after a
 * restart.
 *
 * Kept in memory for the life of the server: a player who leaves and comes back finds their episode, their
 * recent punishments and their resting categories where they were; a restart forgets the last two (M4 §3.1:
 * at worst a player is punished sooner). Any thread.
 */
class Episodes(private val clock: Clock) {
    private val running = ConcurrentHashMap<UUID, Episode>()

    /** When each punishment of the last hour began, per player, oldest first. */
    private val began = ConcurrentHashMap<UUID, List<Long>>()

    /** The last strike of each category, per player; strikes older than [STRIKES_KEPT_MS] are dropped. */
    private val struck = ConcurrentHashMap<UUID, Map<PunishmentCategory, Strike>>()

    /** The episode still running against [target], if any. */
    fun active(target: UUID): Episode? {
        val episode = running[target] ?: return null
        if (episode.until > clock.millis()) return episode
        running.remove(target, episode)
        return null
    }

    /**
     * Opens the episode of [act] against [target], or extends it, until [until] (epoch milliseconds), and
     * records a strike in [category] when it rests ([PunishmentCategory.rests]). Only a new episode counts as
     * a punishment in [recentPunishments] (an episode rebuilt at a join after a restart counts again: in
     * doubt, the player is spared).
     */
    fun open(target: UUID, act: Long?, until: Long, category: PunishmentCategory? = null) {
        val now = clock.millis()
        running.compute(target) { _, current ->
            val live = current?.takeIf { it.until > now }
            if (live != null && act != null && live.act == act) {
                live.copy(until = maxOf(live.until, until))
            } else {
                began.compute(target) { _, times -> recent(times, now) + now }
                // The guards never open an episode over another act's; if it happens, the later end wins.
                Episode(target, act, maxOf(until, live?.until ?: until))
            }
        }
        if (category != null && category.rests) {
            struck.compute(target) { _, strikes ->
                strikes.orEmpty().filterValues { now - it.at < STRIKES_KEPT_MS } + (category to Strike(act, now))
            }
        }
    }

    /** Ends the episode running against [target] now (a pardon), and returns it; null when none ran. */
    fun close(target: UUID): Episode? = running.remove(target)?.takeIf { it.until > clock.millis() }

    /**
     * When the punishments of the last [WINDOW_MS] before [now] against [target] began, epoch milliseconds,
     * oldest first. A read only: the older ones leave at the player's next punishment.
     */
    fun recentPunishments(target: UUID, now: Long = clock.millis()): List<Long> = recent(began[target], now)

    /** The last strike in [category] against [target], if one is still kept. */
    fun lastStrike(target: UUID, category: PunishmentCategory): Strike? = struck[target]?.get(category)

    /**
     * The categories resting against [target] at [now] for a rest of [restMs] after their last strike, with
     * when another act may strike in them again (epoch milliseconds), soonest first.
     */
    fun resting(target: UUID, restMs: Long, now: Long = clock.millis()): Map<PunishmentCategory, Long> =
        struck[target].orEmpty().mapValues { (_, strike) -> strike.at + restMs }.filterValues { it > now }
            .entries.sortedBy { it.value }.associate { it.key to it.value }

    companion object {
        /** How long a punishment counts in [recentPunishments]: an hour. */
        const val WINDOW_MS = 3_600_000L

        /** How long a strike is kept: the longest rest of a category, at any chaos level. */
        val STRIKES_KEPT_MS: Long = (Caps.MIN_CHAOS..Caps.MAX_CHAOS).maxOf { Caps.of(it).categoryCooldownMinutes } * 60_000L

        private fun recent(times: List<Long>?, now: Long): List<Long> = times.orEmpty().filter { now - it < WINDOW_MS }
    }
}
