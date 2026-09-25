package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.tool.Caller
import java.time.Clock

/**
 * Who a hostile action may target (M1_M2 §10.3), after the guards have ruled out protected players and
 * chaos 0. In order:
 * 1. no piling on a player who is dead, or died less than [NO_PILE_ON_MINUTES] minutes ago
 *    ([Rules.NO_PILE_ON]);
 * 2. one punishment at a time: a punishment opened by another turn is still running
 *    ([Rules.ALREADY_PUNISHED]); the hostile actions of the turn that opened it are part of it;
 * 3. fairness: a new punishment against a player who already had [MAX_PUNISHMENTS_PER_HOUR] in the
 *    last hour is refused ([FAIR_SHARE]), so that no player becomes the Narrator's only target.
 *
 * Refusals say until when, in the time of day of [clock]'s zone.
 */
class TargetingPolicy(private val players: PlayerStates, private val clock: Clock) {
    /** Whether [caller] may act against [target] now, given the running [episodes]. */
    fun check(target: PlayerRef, caller: Caller, episodes: Episodes): Verdict {
        val now = clock.millis()
        val name = target.name
        val live = players.get(target.uuid)
        if (live != null && live.health <= 0f) {
            return Verdict.Denied(Rules.NO_PILE_ON, "$name is dead; no hostile action against them until $NO_PILE_ON_MINUTES minutes after their death.")
        }
        val died = live?.lastDeathAt
        if (died != null && now - died < NO_PILE_ON_MS) {
            val end = died + NO_PILE_ON_MS
            return Verdict.Denied(
                Rules.NO_PILE_ON,
                "$name died ${span(now - died)} ago; hostile actions against them are refused until ${at(end)} (in ${span(end - now)}).",
            )
        }
        val episode = episodes.active(target.uuid)
        if (episode != null && (caller.turnId == null || episode.turnId != caller.turnId)) {
            return Verdict.Denied(
                Rules.ALREADY_PUNISHED,
                "$name is already being punished (an earlier punishment runs until ${at(episode.until)}, in ${span(episode.until - now)}); one punishment at a time.",
            )
        }
        if (episode == null) {
            val recent = episodes.recentPunishments(target.uuid)
            if (recent.size >= MAX_PUNISHMENTS_PER_HOUR) {
                val next = recent[recent.size - MAX_PUNISHMENTS_PER_HOUR] + Episodes.WINDOW_MS
                return Verdict.Denied(
                    FAIR_SHARE,
                    "$name was punished ${recent.size} times in the last hour; the next punishment against them is allowed at ${at(next)} (in ${span(next - now)}). Mind the other players, or reward this one.",
                )
            }
        }
        return Verdict.Allowed
    }

    private fun at(epochMs: Long): String = clockTime(clock, epochMs)

    companion object {
        /** A player who died less than this many minutes ago is not targeted. */
        const val NO_PILE_ON_MINUTES = 5

        /** New punishments against one player in an hour ([Episodes.WINDOW_MS]), at most. */
        const val MAX_PUNISHMENTS_PER_HOUR = 4

        /** The rule of fairness: too many punishments against the same player in the last hour. */
        const val FAIR_SHARE = "fair_share"

        private const val NO_PILE_ON_MS = NO_PILE_ON_MINUTES * 60_000L

        /** A duration for the model: `45s`, `3m`, `4m30s`, `1h05m`; rounded up to the second. */
        internal fun span(ms: Long): String {
            val seconds = (maxOf(ms, 0L) + 999) / 1_000
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3_600 -> if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds / 60}m${two(seconds % 60)}s"
                else -> "${seconds / 3_600}h${two((seconds % 3_600) / 60)}m"
            }
        }

        private fun two(value: Long): String = value.toString().padStart(2, '0')
    }
}
