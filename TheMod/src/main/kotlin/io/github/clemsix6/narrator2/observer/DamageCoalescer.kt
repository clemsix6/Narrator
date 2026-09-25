package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import java.util.UUID

/**
 * Merges the damage players take (M1_M2 §7.2): the hits of one damage type that land on a player
 * within [WINDOW_MS] of the first one become a single [DamageTaken], with their sum and count.
 * Continuous damage (fire, lava, drowning, poison) lands once or twice a second: one event per window
 * instead of dozens, and never more than [WINDOW_MS] late.
 *
 * A window is published when it is [WINDOW_MS] old ([tick]), before its player's death or departure
 * ([flush] with a player), and when the server stops (as a [Flushable]). Times are milliseconds of the
 * Observer's clock (server ticks × 50). Server thread only.
 */
class DamageCoalescer(private val publish: (GameEvent) -> Unit) : Flushable {
    private data class Key(val player: UUID, val damageType: String)

    /** The hits of one player and damage type since [openedAt]. */
    private class Window(
        val player: PlayerRef,
        val pos: Pos,
        val damageType: String,
        val openedAt: Long,
        var amount: Double,
        var hits: Int,
        var attacker: EntityRef?,
        var blocked: Boolean,
        var healthAfter: Double,
    ) {
        fun event() = DamageTaken(player, pos, damageType, amount, hits, attacker, blocked, healthAfter)
    }

    private val open = LinkedHashMap<Key, Window>()

    /**
     * [player], at [pos], took [amount] (before armor) of [damageType] from [attacker], [blocked] by a
     * shield or not, and has [healthAfter] left.
     */
    fun taken(player: PlayerRef, pos: Pos, damageType: String, amount: Double, attacker: EntityRef?, blocked: Boolean, healthAfter: Double, now: Long) {
        val key = Key(player.uuid, damageType)
        val window = open[key]?.takeIf { now - it.openedAt < WINDOW_MS }
        if (window == null) {
            open.remove(key)?.let { publish(it.event()) }
            open[key] = Window(player, pos, damageType, now, amount, 1, attacker, blocked, healthAfter)
            return
        }
        window.amount += amount
        window.hits++
        if (attacker != null) window.attacker = attacker
        window.blocked = window.blocked || blocked
        window.healthAfter = healthAfter
    }

    /** Publishes the windows opened [WINDOW_MS] ago or more. */
    fun tick(now: Long) {
        if (open.isEmpty()) return
        publishWhere { now - it.openedAt >= WINDOW_MS }
    }

    /** Publishes what [player] has pending: right before their death or departure, so that it comes first. */
    fun flush(player: UUID) {
        if (open.isEmpty()) return
        publishWhere { it.player.uuid == player }
    }

    /** Publishes every window. At the server stop. */
    override fun flush() {
        publishWhere { true }
    }

    /** Removes the matching windows, then publishes them in the order they were opened. */
    private inline fun publishWhere(matches: (Window) -> Boolean) {
        val due = ArrayList<Window>(0)
        val windows = open.values.iterator()
        while (windows.hasNext()) {
            val window = windows.next()
            if (matches(window)) {
                windows.remove()
                due += window
            }
        }
        due.forEach { publish(it.event()) }
    }

    companion object {
        /** How long hits of one type are merged, from the first. */
        const val WINDOW_MS = 5_000L
    }
}
