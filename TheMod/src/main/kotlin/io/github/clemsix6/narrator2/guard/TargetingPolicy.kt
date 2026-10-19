package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import java.time.Clock

/**
 * Who a hostile action may target (M1_M2 §10.3, M4 §6.1), after the guards have ruled out protected
 * players, chaos 0, the player's grace and a duel. In order:
 * 1. no piling on a player who is dead, or died less than [NO_PILE_ON_MINUTES] minutes ago, or was saved
 *    from a death lately (their [Shield]: a rescue, a duel's fatal blow stopped) ([Rules.NO_PILE_ON]);
 * 2. one punishment at a time: a punishment opened by another act is still running
 *    ([Rules.ALREADY_PUNISHED]); the hostile actions of the act that opened it are part of it. An act is a
 *    Director turn or an engine firing (`Caller.act`): turn 812 and firing 812 are two acts. And one `punish`
 *    of the catalogue per act and player (M4 review, finding 16): an act composes one, it never piles a second
 *    on it ([Rules.ALREADY_PUNISHED] too);
 * 3. fairness: a new punishment against a player who already had [MAX_PUNISHMENTS_PER_HOUR] in the
 *    last hour is refused ([FAIR_SHARE]), so that no player becomes the Narrator's only target;
 * 4. variety: an action of a category that another act struck the player in less than
 *    [Caps.categoryCooldownMinutes] ago is refused ([Rules.SAME_PUNISHMENT]); the actions of one act never
 *    stand in each other's way (a composed punishment), and [PunishmentCategory.OTHER] never rests.
 *
 * Refusals say until when, in the time of day of [clock]'s zone. [caps] is read at each check
 * (`/narrator reload` may change the chaos level).
 */
class TargetingPolicy(private val players: PlayerStates, private val clock: Clock, private val caps: () -> Caps) {
    /**
     * Whether [caller] may act against [target] now with an action of [category] (null: none), given the
     * running [episodes]. [diedAt] is the player's last death saved with them (`GuardState.lastDeathAt`,
     * epoch milliseconds), which holds across a reconnection when the session's own record does not;
     * [shield], their shield if one runs. [punishment]: the action is a `punish` of the catalogue.
     */
    fun check(
        target: PlayerRef,
        caller: Caller,
        episodes: Episodes,
        diedAt: Long? = null,
        category: PunishmentCategory? = null,
        shield: Shield? = null,
        punishment: PunishmentId? = null,
    ): Verdict {
        val now = clock.millis()
        val name = ModelText.name(target.name)
        val live = players.get(target.uuid)
        if (live != null && live.health <= 0f) {
            return Verdict.Denied(Rules.NO_PILE_ON, "$name is dead; no hostile action against them until $NO_PILE_ON_MINUTES minutes after their death.")
        }
        val died = listOfNotNull(live?.lastDeathAt, diedAt).maxOrNull()
        if (died != null && now - died < NO_PILE_ON_MS) {
            val end = died + NO_PILE_ON_MS
            return Verdict.Denied(
                Rules.NO_PILE_ON,
                "$name died ${ToolText.duration(now - died)} ago; hostile actions against them are refused until ${at(end)} (in ${ToolText.wait(end - now)}).",
            )
        }
        if (shield != null && shield.until > now) {
            return Verdict.Denied(
                Rules.NO_PILE_ON,
                "$name was saved from death ${ToolText.duration(now - shield.at)} ago (${ModelText.escape(shield.reason)}); " +
                    "hostile actions against them are refused until ${at(shield.until)} (in ${ToolText.wait(shield.until - now)}).",
            )
        }
        val episode = episodes.active(target.uuid)
        if (episode != null && (caller.act == null || episode.act != caller.act)) {
            return Verdict.Denied(
                Rules.ALREADY_PUNISHED,
                "$name is already being punished (an earlier punishment runs until ${at(episode.until)}, in ${ToolText.wait(episode.until - now)}); one punishment at a time.",
            )
        }
        if (punishment != null) {
            episodes.punishmentBy(target.uuid, caller.act)?.let { first ->
                return Verdict.Denied(
                    Rules.ALREADY_PUNISHED,
                    "this act already punished $name (${first.id}); one punish per act and player: compose it with the other tools, or let it run.",
                )
            }
        }
        if (episode == null) {
            val recent = episodes.recentPunishments(target.uuid, now)
            if (recent.size >= MAX_PUNISHMENTS_PER_HOUR) {
                val next = recent[recent.size - MAX_PUNISHMENTS_PER_HOUR] + Episodes.WINDOW_MS
                return Verdict.Denied(
                    FAIR_SHARE,
                    "$name was punished ${recent.size} times in the last hour; the next punishment against them is allowed at ${at(next)} (in ${ToolText.wait(next - now)}). Mind the other players, or reward this one.",
                )
            }
        }
        if (category != null && category.rests) {
            val strike = episodes.lastStrike(target.uuid, category)
            val next = strike?.let { it.at + caps().categoryCooldownMinutes * 60_000L }
            if (strike != null && next != null && next > now && (caller.act == null || strike.act != caller.act)) {
                return Verdict.Denied(
                    Rules.SAME_PUNISHMENT,
                    "$name was already ${category.phrase} ${ToolText.duration(now - strike.at)} ago (${category.id}); another ${category.id} " +
                        "punishment against them is allowed at ${at(next)} (in ${ToolText.wait(next - now)}). Vary the punishment.",
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

        /** [NO_PILE_ON_MINUTES] in milliseconds. */
        const val NO_PILE_ON_MS = NO_PILE_ON_MINUTES * 60_000L
    }
}
