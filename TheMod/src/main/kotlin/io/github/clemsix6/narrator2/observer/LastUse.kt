package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.Pos
import java.util.UUID

/**
 * The last block or entity each player used, and the tick when they did (OBSERVER_API.md §3.9). A menu
 * opened by a provider that is neither a block entity nor an entity (a crafting table, an anvil, an
 * ender chest, a double chest, a villager's trades) does not say where it is: it was opened by the use
 * of the same tick. Plain values only. Server thread only.
 */
class LastUse {
    /**
     * A use: [pos] of the block or entity, and [entity] when an entity was used (else a block, read
     * from the world when needed).
     */
    class Use(val tick: Int, val pos: Pos, val entity: EntityRef?)

    private val uses = HashMap<UUID, Use>()

    /** [player] used the block at [pos] during [tick]. */
    fun block(player: UUID, pos: Pos, tick: Int) {
        uses[player] = Use(tick, pos, null)
    }

    /** [player] used [entity], standing at [pos], during [tick]. */
    fun entity(player: UUID, entity: EntityRef, pos: Pos, tick: Int) {
        uses[player] = Use(tick, pos, entity)
    }

    /** What [player] used during [tick]; null when they used nothing that tick. */
    fun at(player: UUID, tick: Int): Use? = uses[player]?.takeIf { it.tick == tick }

    /** Forgets [player], who left. */
    fun forget(player: UUID) {
        uses.remove(player)
    }
}
