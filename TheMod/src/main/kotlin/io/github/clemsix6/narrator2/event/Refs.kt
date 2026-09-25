package io.github.clemsix6.narrator2.event

import java.util.UUID

/*
 * Plain values that events carry instead of game objects. An event outlives the hook that built it,
 * while stacks, entities and players are mutated or replaced right after (a ServerPlayer is a new
 * object after each respawn), so events never hold a reference to a game object.
 * Helpers that build these from game objects live in `observer/GameIds.kt`.
 */

/**
 * A player, identified by [uuid]. [name] is the name at the time of the event: names can change, so
 * everything is indexed by UUID.
 */
data class PlayerRef(val uuid: UUID, val name: String)

/**
 * A block position in a dimension.
 *
 * @property dim full dimension id, e.g. `minecraft:overworld`, `minecraft:the_nether`.
 */
data class Pos(val dim: String, val x: Int, val y: Int, val z: Int)

/**
 * Another entity: a mob or a player.
 *
 * @property type full entity type id, e.g. `minecraft:zombie`, `minecraft:player`.
 * @property uuid the entity's UUID; null when unknown.
 * @property name the player's name, or a mob's custom name; null for an unnamed mob.
 * @property player set when the entity is a player.
 */
data class EntityRef(val type: String, val uuid: UUID?, val name: String?, val player: PlayerRef? = null) {
    /** Hosts `EntityRef.of(entity)`, defined next to the other game-object helpers in `observer/GameIds.kt`. */
    companion object
}

/** A stack, as an item id and a count, e.g. `minecraft:emerald` × 12. */
data class ItemCount(val item: String, val count: Int)
