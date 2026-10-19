package io.github.clemsix6.narrator2.guard

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A player the Narrator leaves alone for a while: saved from a death since [at], until [until] (epoch
 * milliseconds), because [reason] (`duel C-52: fatal blow stopped at 1 hp`).
 */
data class Shield(val at: Long, val until: Long, val reason: String)

/**
 * The players saved from a death lately (M4 review, finding 18): a rescue of the death guard, or a death the
 * engine refused (a duel's fatal blow). Until their shield ends, the guards refuse every hostile action against
 * them ([Rules.NO_PILE_ON], as after a real death) and the Narrator's creatures and explosions spare them.
 *
 * In memory: a player who leaves and comes back finds their shield, a restart forgets it (M4 §3.1: at worst a
 * player is punished sooner, as the hour's punishments). Any thread.
 */
class Shields(private val clock: Clock) {
    private val shields = ConcurrentHashMap<UUID, Shield>()

    /** Shields [target] until [until] because [reason]; a shield that ends later is kept. Returns the shield in force. */
    fun shield(target: UUID, until: Long, reason: String): Shield {
        val now = clock.millis()
        return shields.compute(target) { _, current ->
            if (current != null && current.until >= until) current else Shield(now, until, reason)
        }!!
    }

    /** The shield of [target] still running, if any. */
    fun active(target: UUID): Shield? {
        val shield = shields[target] ?: return null
        if (shield.until > clock.millis()) return shield
        shields.remove(target, shield)
        return null
    }
}
