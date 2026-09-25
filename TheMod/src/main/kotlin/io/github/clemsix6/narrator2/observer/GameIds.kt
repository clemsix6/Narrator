package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/*
 * Full ids (`minecraft:diamond_ore`) and plain values from game objects, for building events.
 * Server thread only: they read live game objects.
 */

/** Registry id of a block, e.g. `minecraft:stone`. */
val Block.id: String get() = BuiltInRegistries.BLOCK.getKey(this).toString()

/** Registry id of the block of this state. */
val BlockState.blockId: String get() = block.id

/** Registry id of an item, e.g. `minecraft:diamond`. */
val Item.id: String get() = BuiltInRegistries.ITEM.getKey(this).toString()

/** Registry id of the stack's item; `minecraft:air` for an empty stack. */
val ItemStack.id: String get() = typeHolder().registeredName

/** Registry id of the stack's item; null for an empty stack (an empty hand). */
val ItemStack.idOrNull: String? get() = if (isEmpty) null else id

/** Registry id of an entity's type, e.g. `minecraft:zombie`. */
val Entity.typeId: String get() = EntityType.getKey(type).toString()

/** Full id of a level's dimension, e.g. `minecraft:the_nether`. */
val Level.dimensionId: String get() = dimension().identifier().toString()

/** The player as an event reference, with their current name. */
fun ServerPlayer.ref(): PlayerRef = PlayerRef(uuid, plainTextName)

/** Where the player stands. */
fun ServerPlayer.pos(): Pos = blockPosition().toPos(level())

/** This position in [level]'s dimension. */
fun BlockPos.toPos(level: Level): Pos = Pos(level.dimensionId, x, y, z)

/** A reference to [entity]: a player (with its [PlayerRef]) or a mob (with its custom name, if any). */
fun EntityRef.Companion.of(entity: Entity): EntityRef =
    if (entity is ServerPlayer) {
        EntityRef(entity.typeId, entity.uuid, entity.plainTextName, entity.ref())
    } else {
        EntityRef(entity.typeId, entity.uuid, entity.customName?.string)
    }
