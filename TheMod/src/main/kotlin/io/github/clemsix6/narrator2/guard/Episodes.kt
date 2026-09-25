package io.github.clemsix6.narrator2.guard

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One punishment: the hostile actions of one Director turn against one player, until all their effects
 * are over (M1_M2 §10.3).
 *
 * @property turnId the turn that opened it; the hostile actions of that turn extend it. Null for a caller
 *   without a turn: such an episode is never extended, each of its hostile actions is a punishment.
 * @property until when its last effect ends, epoch milliseconds.
 */
data class Episode(val target: UUID, val turnId: Long?, val until: Long)

/**
 * The punishments running against each player, and when the recent ones began. An episode opens with the
 * first hostile action of a turn against a player; the hostile actions of the same turn extend it (one
 * punishment = several coordinated actions); it ends when its last effect ends. The guards rebuild it
 * from the player's guard state when they join after a restart.
 *
 * Kept in memory for the life of the server: a player who leaves and comes back finds their episode and
 * their recent punishments where they were. Any thread.
 */
class Episodes(private val clock: Clock) {
    private val running = ConcurrentHashMap<UUID, Episode>()

    /** When each punishment of the last hour began, per player, oldest first. */
    private val began = ConcurrentHashMap<UUID, List<Long>>()

    /** The episode still running against [target], if any. */
    fun active(target: UUID): Episode? {
        val episode = running[target] ?: return null
        if (episode.until > clock.millis()) return episode
        running.remove(target, episode)
        return null
    }

    /**
     * Opens the episode of [turnId] against [target], or extends it, until [until] (epoch milliseconds).
     * Only a new episode counts as a punishment in [recentPunishments] (an episode rebuilt at a join after
     * a restart counts again: in doubt, the player is spared).
     */
    fun open(target: UUID, turnId: Long?, until: Long) {
        val now = clock.millis()
        running.compute(target) { _, current ->
            val live = current?.takeIf { it.until > now }
            if (live != null && turnId != null && live.turnId == turnId) {
                live.copy(until = maxOf(live.until, until))
            } else {
                began.compute(target) { _, times -> recent(times, now) + now }
                // The guards never open an episode over another turn's; if it happens, the later end wins.
                Episode(target, turnId, maxOf(until, live?.until ?: until))
            }
        }
    }

    /** When the punishments of the last [WINDOW_MS] against [target] began, epoch milliseconds, oldest first. */
    fun recentPunishments(target: UUID): List<Long> {
        val now = clock.millis()
        return began.computeIfPresent(target) { _, times -> recent(times, now).ifEmpty { null } } ?: emptyList()
    }

    companion object {
        /** How long a punishment counts in [recentPunishments]: an hour. */
        const val WINDOW_MS = 3_600_000L

        private fun recent(times: List<Long>?, now: Long): List<Long> = times.orEmpty().filter { now - it < WINDOW_MS }
    }
}
