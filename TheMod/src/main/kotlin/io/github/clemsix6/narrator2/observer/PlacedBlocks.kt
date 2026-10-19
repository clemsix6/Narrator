package io.github.clemsix6.narrator2.observer

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.core.Log
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ColorCollection
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Optional
import java.util.UUID
import java.util.stream.IntStream

/**
 * The player-placed positions of one chunk (M4 §9.3): two small palettes, the players who placed blocks
 * there and the blocks they placed, and each position (packed by [PlacedBlocks.pack]) with its cell
 * `placer | block << 16`: `placer` is the placer's index in [placers], `block` the placed block's index
 * in [blocks] plus one, 0 when the block is unknown (an entry written before M4). A chunk rarely has
 * more than two or three builders and a few dozen kinds of blocks: 4 bytes per position.
 *
 * An entry answers only while the block is still the one placed ([PlacedBlocks.stillPlaced]); a stale
 * entry is removed when read. Mutable, changed in place: whoever changes it marks the chunk unsaved
 * (see [PlacedBlocks]). Server thread only.
 */
class ChunkPlacements {
    val placers: MutableList<UUID> = mutableListOf()
    val blocks: MutableList<Block> = mutableListOf()
    private val cells = Int2IntOpenHashMap().apply { defaultReturnValue(NONE) }

    /** The number of positions. */
    val size: Int get() = cells.size

    /**
     * Records that [placer] placed [block] at [packed]; null [block] for an entry whose block is unknown.
     * Returns whether the entry changed.
     */
    fun put(packed: Int, placer: UUID, block: Block?): Boolean {
        val cell = indexOf(placer, placers) or ((block?.let { indexOf(it, blocks) + 1 } ?: 0) shl BLOCK_SHIFT)
        return cells.put(packed, cell) != cell
    }

    /** The block placed at [packed], however it changed since; null when no player did, or for an entry from before M4. */
    fun blockAt(packed: Int): Block? = cells.get(packed).takeIf { it != NONE }?.let(::blockOf)

    /**
     * Who placed the block at [packed], if [current], the block there now, is still the one placed
     * ([PlacedBlocks.stillPlaced]). A stale entry is removed: compare [size] to know whether to save.
     */
    fun placerAt(packed: Int, current: BlockState): UUID? {
        val cell = cells.get(packed)
        if (cell == NONE) return null
        if (PlacedBlocks.stillPlaced(blockOf(cell), current)) return placers[cell and PLACER_MASK]
        cells.remove(packed)
        return null
    }

    /** Removes the entry at [packed]; returns its placer if [broken], the block just broken there, was still the one placed. */
    fun take(packed: Int, broken: BlockState): UUID? {
        val cell = cells.remove(packed)
        if (cell == NONE) return null
        return placers[cell and PLACER_MASK].takeIf { PlacedBlocks.stillPlaced(blockOf(cell), broken) }
    }

    /** Runs [action] on each entry: its position, its placer's index in [placers], and its block (null when unknown). */
    fun forEach(action: (packed: Int, placer: Int, block: Block?) -> Unit) {
        val entries = cells.int2IntEntrySet().fastIterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            action(entry.intKey, entry.intValue and PLACER_MASK, blockOf(entry.intValue))
        }
    }

    private fun blockOf(cell: Int): Block? = (cell ushr BLOCK_SHIFT).takeIf { it != 0 }?.let { blocks[it - 1] }

    private fun <T> indexOf(value: T, palette: MutableList<T>): Int {
        val index = palette.indexOf(value)
        if (index >= 0) return index
        palette += value
        return palette.size - 1
    }

    companion object {
        /** A position without entry (the map's default value). */
        private const val NONE = -1
        private const val BLOCK_SHIFT = 16
        private const val PLACER_MASK = 0xFFFF

        /** Palette sizes the cell holds: 16 bits for the placer, 15 for the block (plus one). */
        private const val MAX_PLACERS = 0x10000
        private const val MAX_BLOCKS = 0x7FFF

        /**
         * Saved as `{placers: [uuid…], positions: [int…], owners: [int…], blocks: [id…], kinds: [int…]}`:
         * `owners[i]` is the index in `placers` of the placer of `positions[i]`, `kinds[i]` the index in
         * `blocks` of the block placed there, -1 when unknown. `blocks` and `kinds` are optional: a chunk
         * saved before M4 has neither, and its entries keep the old rule (see [PlacedBlocks.stillPlaced]).
         * A block id the game no longer knows reads as unknown. Unused placers and blocks are dropped.
         */
        val CODEC: Codec<ChunkPlacements> = RecordCodecBuilder.create<Saved> { instance ->
            instance.group(
                UUIDUtil.CODEC.listOf().fieldOf("placers").forGetter(Saved::placers),
                Codec.INT_STREAM.fieldOf("positions").forGetter { IntStream.of(*it.positions) },
                Codec.INT_STREAM.fieldOf("owners").forGetter { IntStream.of(*it.owners) },
                Codec.STRING.listOf().optionalFieldOf("blocks", emptyList()).forGetter(Saved::blocks),
                Codec.INT_STREAM.optionalFieldOf("kinds").forGetter { saved -> saved.kinds?.let { Optional.of(IntStream.of(*it)) } ?: Optional.empty() },
            ).apply(instance) { placers, positions, owners, blocks, kinds ->
                Saved(placers, positions.toArray(), owners.toArray(), blocks, kinds.map { it.toArray() }.orElse(null))
            }
        }.flatXmap(Saved::restore) { DataResult.success(Saved.of(it)) }
    }

    /** The saved form: parallel arrays, palettes compacted; [kinds] null when the blocks are unknown (before M4). */
    private class Saved(val placers: List<UUID>, val positions: IntArray, val owners: IntArray, val blocks: List<String>, val kinds: IntArray?) {
        fun restore(): DataResult<ChunkPlacements> {
            if (positions.size != owners.size) {
                return DataResult.error { "placed blocks: ${positions.size} positions but ${owners.size} owners" }
            }
            if (placers.size > MAX_PLACERS || owners.any { it !in placers.indices }) {
                return DataResult.error { "placed blocks: owner index out of the ${placers.size} placers" }
            }
            if (kinds != null && kinds.size != positions.size) {
                return DataResult.error { "placed blocks: ${positions.size} positions but ${kinds.size} kinds" }
            }
            if (blocks.size > MAX_BLOCKS || kinds != null && kinds.any { it < -1 || it >= blocks.size }) {
                return DataResult.error { "placed blocks: block index out of the ${blocks.size} blocks" }
            }
            val known = blocks.map { id -> Identifier.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) } }
            val placements = ChunkPlacements()
            placements.placers += placers
            placements.blocks += known.filterNotNull().distinct()
            positions.forEachIndexed { i, position ->
                placements.put(position, placers[owners[i]], kinds?.get(i)?.takeIf { it >= 0 }?.let { known[it] })
            }
            return DataResult.success(placements)
        }

        companion object {
            /** The palettes keep their order, without the placers and blocks no position uses any more. */
            fun of(placements: ChunkPlacements): Saved {
                val usedPlacers = HashSet<Int>()
                val usedBlocks = HashSet<Block>()
                placements.forEach { _, placer, block ->
                    usedPlacers += placer
                    if (block != null) usedBlocks += block
                }
                val placers = placements.placers.indices.filter { it in usedPlacers }
                val placerIndex = placers.withIndex().associate { (index, old) -> old to index }
                val blocks = placements.blocks.filter { it in usedBlocks }
                val blockIndex = blocks.withIndex().associate { (index, block) -> block to index }
                val positions = IntArray(placements.size)
                val owners = IntArray(placements.size)
                val kinds = IntArray(placements.size)
                var i = 0
                placements.forEach { packed, placer, block ->
                    positions[i] = packed
                    owners[i] = placerIndex.getValue(placer)
                    kinds[i] = block?.let { blockIndex.getValue(it) } ?: -1
                    i++
                }
                return Saved(
                    placers.map { placements.placers[it] },
                    positions,
                    owners,
                    blocks.map { BuiltInRegistries.BLOCK.getKey(it).toString() },
                    kinds,
                )
            }
        }
    }
}

/**
 * Index of the blocks placed by players (M1_M2 §7.5, M4 §9.3), kept in a persistent chunk attachment
 * `narrator2:placed_blocks` and saved with the chunk. It answers "did a player place this block, and
 * who?" for the blocks of loaded chunks: grief detection when a block breaks, the share of player
 * blocks around a position, bases for the Oracle, the `grief` condition of the rules, `survey`.
 *
 * Each entry keeps the block placed, and answers only while the block there is still that one, or one
 * of its family ([stillPlaced]). Whatever replaced it (an explosion, fire, a piston, an enderman, a
 * tree grown from a placed sapling, a liquid, a falling block) makes the entry stale, without a hook
 * for each: the reader finds it out and removes it. The index knows only the blocks placed since the
 * mod runs: it says nothing of the older ones.
 *
 * Changes are made in place, then the chunk is marked unsaved: `setAttached` only marks the chunk when
 * the new value is not `equals` to the old one, so an in-place change would otherwise never be saved
 * (OBSERVER_API.md §6.1). Server thread only, but [stillPlaced] and [familyOf], which any thread may call.
 */
object PlacedBlocks {
    private val log = Log.observer

    /** Lazy, like the other attachment types: a unit test may use the codec without Fabric's registry. */
    val TYPE: AttachmentType<ChunkPlacements> by lazy {
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "placed_blocks")) { builder ->
            builder.persistent(ChunkPlacements.CODEC)
        }
    }

    /** Records that [placer] placed [state] at [pos], and the other half of a door, bed or tall plant. Air is not recorded. */
    fun mark(level: ServerLevel, pos: BlockPos, state: BlockState, placer: UUID) {
        if (state.isAir) return
        for (at in positionsOf(pos, state)) {
            val chunk = level.getChunkAt(at)
            val placements = chunk.getAttached(TYPE) ?: ChunkPlacements().also { chunk.setAttached(TYPE, it) }
            if (placements.put(pack(chunk, at), placer, state.block)) chunk.markUnsaved()
        }
    }

    /**
     * Forgets the block at [pos] (and the other half of a double block), which a player just broke:
     * [state] is the block broken, the world already holds air. Returns who had placed it, if a player
     * did and it was still the block placed.
     */
    fun unmark(level: ServerLevel, pos: BlockPos, state: BlockState): UUID? {
        var placer: UUID? = null
        for (at in positionsOf(pos, state)) {
            val chunk = level.getChunkAt(at)
            val placements = chunk.getAttached(TYPE) ?: continue
            val size = placements.size
            val removed = placements.take(pack(chunk, at), state)
            if (at == pos) placer = removed
            if (placements.size != size) changed(chunk, placements)
        }
        return placer
    }

    /** Who placed the block at [pos]; null when no player did, it is no longer that block, or the chunk is not loaded (never loads it). */
    fun placerAt(level: ServerLevel, pos: BlockPos): UUID? {
        val chunk = level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))
            ?: return null
        return placerAt(chunk, pos)
    }

    /** Who placed the block at [pos] of [chunk], a loaded chunk; null when no player did or it is no longer that block (the entry is removed). */
    fun placerAt(chunk: LevelChunk, pos: BlockPos): UUID? {
        val placements = chunk.getAttached(TYPE) ?: return null
        val size = placements.size
        val current = chunk.getBlockState(pos)
        val placer = placements.placerAt(pack(chunk, pos), current)
        if (placements.size != size) {
            log.debug { "placed block at ${pos.x} ${pos.y} ${pos.z} is now ${BuiltInRegistries.BLOCK.getKey(current.block)}: stale entry removed" }
            changed(chunk, placements)
        }
        return placer
    }

    /**
     * Whether [current] is still the block placed as [placed]: the same block or one of its family
     * ([familyOf]). An entry from before M4 ([placed] null) keeps the old rule: anything but air, a
     * liquid or fire. Any thread.
     */
    fun stillPlaced(placed: Block?, current: BlockState): Boolean {
        if (placed == null) return !current.isAir && current.block !is LiquidBlock && current.block !is BaseFireBlock
        return familyOf(placed) === familyOf(current.block)
    }

    /**
     * The first block of [block]'s family, [block] itself when it has none: a placed block that turns
     * into another one of its family, without anybody replacing it, is still the player's. The families:
     * copper that oxidizes, is waxed or scraped (`WeatheringCopper.getFirst`, `HoneycombItem.WAX_OFF_BY_BLOCK`);
     * dirt that grass covers, a hoe tills or a shovel flattens; a cauldron that fills; an anvil that chips;
     * concrete powder that water sets; a log or wood an axe strips. Any thread.
     */
    fun familyOf(block: Block): Block = families[block] ?: block

    /** A position within its chunk: `((y - minY) shl 8) or ((z and 15) shl 4) or (x and 15)`. */
    fun pack(chunk: ChunkAccess, pos: BlockPos): Int = ((pos.y - chunk.minY) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    /** Built once the blocks are all registered (the first read happens in a running game). */
    private val families: Map<Block, Block> by lazy {
        val families = HashMap<Block, Block>()
        val waxed = HoneycombItem.WAX_OFF_BY_BLOCK.get()
        for (block in BuiltInRegistries.BLOCK) {
            val first = WeatheringCopper.getFirst(waxed[block] ?: block)
            if (first !== block) families[block] = first
            val id = BuiltInRegistries.BLOCK.getKey(block)
            if (id.path.startsWith(STRIPPED)) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(id.namespace, id.path.removePrefix(STRIPPED)))
                    .ifPresent { families[block] = it }
            }
        }
        for (dirt in listOf(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.FARMLAND, Blocks.DIRT_PATH)) {
            families[dirt] = Blocks.DIRT
        }
        for (cauldron in listOf(Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON, Blocks.POWDER_SNOW_CAULDRON)) families[cauldron] = Blocks.CAULDRON
        for (anvil in listOf(Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL)) families[anvil] = Blocks.ANVIL
        ColorCollection.zipApply(Blocks.CONCRETE_POWDER, Blocks.CONCRETE) { powder, concrete -> families[powder] = concrete }
        families
    }

    private const val STRIPPED = "stripped_"

    /** Saves [chunk] with [placements] changed in place, or drops the attachment when it is empty. */
    private fun changed(chunk: ChunkAccess, placements: ChunkPlacements) {
        if (placements.size == 0) chunk.removeAttached(TYPE) else chunk.markUnsaved()
    }

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
