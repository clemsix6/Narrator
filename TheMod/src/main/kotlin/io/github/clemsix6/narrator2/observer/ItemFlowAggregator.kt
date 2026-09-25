package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import java.util.UUID

/**
 * Sums the items players pick up (M1_M2 §7.2, OBSERVER_API.md §3.10). Every block mined drops an item
 * picked up a moment later: hundreds of pickups an hour, worth one line per item and window instead.
 *
 * A player's window opens at their first pickup and lasts [WINDOW_MS]: then one [ItemsPickedUp] per
 * item is published, at the position of the window's first pickup. The window is cut short before a
 * dimension change, a death or a departure ([flush] with the player), so that its pickups come before
 * those events and keep their place; and when the server stops ([flush]). Times are milliseconds of the
 * Observer's clock (server ticks × 50), read from [now]. Server thread only.
 */
class ItemFlowAggregator(private val publish: (GameEvent) -> Unit, private val now: () -> Long) : Flushable {
    private class Window(val player: PlayerRef, val pos: Pos, val openedAt: Long) {
        /** Item → count, in the order they were first picked up. */
        val items = LinkedHashMap<String, Int>()
    }

    private val open = LinkedHashMap<UUID, Window>()

    /** [player], at [pos], picked up [count] × [item]. */
    fun pickedUp(player: PlayerRef, pos: Pos, item: String, count: Int) {
        val window = open.getOrPut(player.uuid) { Window(player, pos, now()) }
        window.items.merge(item, count, Int::plus)
    }

    /** Publishes the windows opened [WINDOW_MS] ago or more. */
    fun tick() {
        if (open.isEmpty()) return
        val now = now()
        publishWhere(now) { now - it.openedAt >= WINDOW_MS }
    }

    /** Publishes [player]'s window now, whatever its age. */
    fun flush(player: UUID) {
        if (open.isEmpty()) return
        publishWhere(now()) { it.player.uuid == player }
    }

    /** Publishes every window. At the server stop. */
    override fun flush() {
        if (open.isEmpty()) return
        publishWhere(now()) { true }
    }

    private inline fun publishWhere(now: Long, matches: (Window) -> Boolean) {
        val due = ArrayList<Window>(0)
        val windows = open.values.iterator()
        while (windows.hasNext()) {
            val window = windows.next()
            if (matches(window)) {
                windows.remove()
                due += window
            }
        }
        for (window in due) {
            val seconds = windowSeconds(now - window.openedAt)
            window.items.forEach { (item, count) -> publish(ItemsPickedUp(window.player, window.pos, item, count, seconds)) }
        }
    }

    companion object {
        /** How long a player's pickups are summed, from the first one. */
        const val WINDOW_MS = 30_000L

        /** The window's length in whole seconds, rounded up: at least 1 (the pickups of a single moment). */
        fun windowSeconds(elapsedMs: Long): Int = ((elapsedMs.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(1).toInt()
    }
}
