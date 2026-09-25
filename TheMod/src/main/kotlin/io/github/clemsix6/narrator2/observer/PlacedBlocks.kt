package io.github.clemsix6.narrator2.observer

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.UUID
import java.util.stream.IntStream

/**
 * The player-placed positions of one chunk: a small palette of the players who placed blocks there,
 * and each position (packed by [PlacedBlocks.pack]) with the palette index of its placer. A chunk
 * rarely has more than two or three builders: 4 bytes per position, plus 16 per builder.
 *
 * Mutable, changed in place: whoever changes it marks the chunk unsaved (see [PlacedBlocks]).
 */
class ChunkPlacements(
    val placers: MutableList<UUID> = mutableListOf(),
    val cells: Int2IntOpenHashMap = Int2IntOpenHashMap(),
) {
    init {
        cells.defaultReturnValue(NONE)
    }

    /** Palette index of [placer], added to the palette when new. */
    fun indexOf(placer: UUID): Int {
        val index = placers.indexOf(placer)
        if (index >= 0) return index
        placers += placer
        return placers.size - 1
    }

    /** Who placed the block at [packed], if a player did. */
    fun placerAt(packed: Int): UUID? = cells.get(packed).takeIf { it != NONE }?.let { placers[it] }

    companion object {
        /** A position without placer (the map's default value). */
        const val NONE = -1

        /**
         * Saved as `{placers: [uuid…], positions: [int…], owners: [int…]}`, where `owners[i]` is the
         * palette index of the placer of `positions[i]`. Placers who have no position left are dropped.
         */
        val CODEC: Codec<ChunkPlacements> = RecordCodecBuilder.create<Saved> { instance ->
            instance.group(
                UUIDUtil.CODEC.listOf().fieldOf("placers").forGetter(Saved::placers),
                Codec.INT_STREAM.fieldOf("positions").forGetter { IntStream.of(*it.positions) },
                Codec.INT_STREAM.fieldOf("owners").forGetter { IntStream.of(*it.owners) },
            ).apply(instance) { placers, positions, owners -> Saved(placers, positions.toArray(), owners.toArray()) }
        }.flatXmap(Saved::restore) { DataResult.success(Saved.of(it)) }
    }

    /** The saved form: parallel arrays, placers compacted. */
    private class Saved(val placers: List<UUID>, val positions: IntArray, val owners: IntArray) {
        fun restore(): DataResult<ChunkPlacements> {
            if (positions.size != owners.size) {
                return DataResult.error { "placed blocks: ${positions.size} positions but ${owners.size} owners" }
            }
            if (owners.any { it !in placers.indices }) {
                return DataResult.error { "placed blocks: owner index out of the ${placers.size} placers" }
            }
            val cells = Int2IntOpenHashMap(positions.size)
            positions.forEachIndexed { i, position -> cells.put(position, owners[i]) }
            return DataResult.success(ChunkPlacements(placers.toMutableList(), cells))
        }

        companion object {
            fun of(placements: ChunkPlacements): Saved {
                val used = placements.cells.values.toIntArray().distinct().sorted()
                val remap = used.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
                val positions = IntArray(placements.cells.size)
                val owners = IntArray(placements.cells.size)
                var i = 0
                val entries = placements.cells.int2IntEntrySet().fastIterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    positions[i] = entry.intKey
                    owners[i] = remap.getValue(entry.intValue)
                    i++
                }
                return Saved(used.map { placements.placers[it] }, positions, owners)
            }
        }
    }
}

/**
 * Index of the blocks placed by players (M1_M2 §7.5), kept in a persistent chunk attachment
 * `narrator2:placed_blocks` and saved with the chunk. It answers "did a player place this block, and
 * who?" for the blocks of loaded chunks: grief detection when a block breaks, the share of player
 * blocks around a position, bases for the Oracle. The history itself is in the Journal.
 *
 * A block destroyed by anything but a player (explosion, fire, piston) keeps its entry: users check
 * that the block is not air. Server thread only.
 *
 * Changes are made in place, then the chunk is marked unsaved: `setAttached` only marks the chunk when
 * the new value is not `equals` to the old one, so an in-place change would otherwise never be saved
 * (OBSERVER_API.md §6.1).
 */
object PlacedBlocks {
    val TYPE: AttachmentType<ChunkPlacements> =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "placed_blocks")) { builder ->
            builder.persistent(ChunkPlacements.CODEC)
        }

    /** Records that [placer] placed [state] at [pos], and the other half of a door, bed or tall plant. */
    fun mark(level: ServerLevel, pos: BlockPos, state: BlockState, placer: UUID) {
        for (at in positionsOf(pos, state)) {
            val chunk = level.getChunkAt(at)
            val placements = chunk.getAttached(TYPE) ?: ChunkPlacements().also { chunk.setAttached(TYPE, it) }
            val index = placements.indexOf(placer)
            if (placements.cells.put(pack(chunk, at), index) != index) chunk.markUnsaved()
        }
    }

    /**
     * Forgets the block at [pos] (and the other half of a double block), broken by a player; returns
     * who had placed it, if a player did.
     */
    fun unmark(level: ServerLevel, pos: BlockPos, state: BlockState): UUID? {
        var placer: UUID? = null
        for (at in positionsOf(pos, state)) {
            val chunk = level.getChunkAt(at)
            val placements = chunk.getAttached(TYPE) ?: continue
            val packed = pack(chunk, at)
            val removed = placements.placerAt(packed) ?: continue
            if (at == pos) placer = removed
            placements.cells.remove(packed)
            if (placements.cells.isEmpty()) chunk.removeAttached(TYPE) else chunk.markUnsaved()
        }
        return placer
    }

    /** Who placed the block at [pos]; null when no player did or the chunk is not loaded (never loads it). */
    fun placerAt(level: ServerLevel, pos: BlockPos): UUID? {
        val chunk = level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))
            ?: return null
        return chunk.getAttached(TYPE)?.placerAt(pack(chunk, pos))
    }

    /** A position within its chunk: `((y - minY) shl 8) or ((z and 15) shl 4) or (x and 15)`. */
    fun pack(chunk: ChunkAccess, pos: BlockPos): Int = ((pos.y - chunk.minY) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    /** [pos], and the other half of a two-block block (door, tall plant, bed). */
    private fun positionsOf(pos: BlockPos, state: BlockState): List<BlockPos> {
        val other = when {
            state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) ->
                if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) pos.above() else pos.below()
            state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) -> {
                val facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                // The head is in front of the foot, in the bed's facing direction.
                if (state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) pos.relative(facing) else pos.relative(facing.opposite)
            }
            else -> null
        }
        return if (other == null) listOf(pos) else listOf(pos, other)
    }
}
