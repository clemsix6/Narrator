package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import java.util.UUID

/**
 * Merges the crafts of one tick (M1_M2 §7.2). A shift-click on a result slot takes the result again and
 * again while it stays the same item: sixteen calls for sixteen times four planks, all in one tick. They
 * become one [ItemCrafted] per player, item and station, with the total count.
 *
 * [flush] is called at the end of every server tick, before a player's departure (with the player),
 * and when the server stops (as a [Flushable]). Server thread only.
 */
class CraftCoalescer(private val publish: (GameEvent) -> Unit) : Flushable {
    private data class Key(val player: UUID, val item: String, val station: String)

    private class Pending(val player: PlayerRef, val pos: Pos, val item: String, val station: String, var count: Int)

    private val pending = LinkedHashMap<Key, Pending>()

    /** [player], at [pos], took [count] × [item] out of the result slot of [station]. */
    fun add(player: PlayerRef, pos: Pos, item: String, count: Int, station: String) {
        val merged = pending.getOrPut(Key(player.uuid, item, station)) { Pending(player, pos, item, station, 0) }
        merged.count += count
    }

    /** Publishes what [player] has pending: right before their departure, so that it comes first. */
    fun flush(player: UUID) {
        if (pending.isEmpty()) return
        publishWhere { it.player.uuid == player }
    }

    /** Publishes every pending craft, in the order they started. */
    override fun flush() {
        if (pending.isEmpty()) return
        publishWhere { true }
    }

    private inline fun publishWhere(matches: (Pending) -> Boolean) {
        val due = ArrayList<Pending>(pending.size)
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val craft = iterator.next()
            if (matches(craft)) {
                iterator.remove()
                due += craft
            }
        }
        due.forEach { publish(ItemCrafted(it.player, it.pos, it.item, it.count, it.station)) }
    }
}
