package io.github.clemsix6.narrator2.oracle

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import java.util.PriorityQueue
import kotlin.math.max

/**
 * A copy of the blocks of one loaded 16×16×16 section, at section coordinates [x], [y], [z]. The copy
 * is the game's own palette container, duplicated on the server thread: reading it on another thread
 * is safe (reading a live section there is not: `LevelChunkSection.acquire`).
 */
class SectionCopy(val x: Int, val y: Int, val z: Int, val states: PalettedContainer<BlockState>)

/**
 * The loaded sections around a point, copied on the server thread to be read off it.
 *
 * @property tested sections whose palette was tested.
 * @property chunks chunks the area covers; [loadedChunks] of them were loaded (the others were skipped).
 */
class SectionCopies(val sections: List<SectionCopy>, val tested: Int, val chunks: Int, val loadedChunks: Int) {
    private val bySection: Map<Long, SectionCopy> by lazy { sections.associateBy { SectionPos.asLong(it.x, it.y, it.z) } }

    /** The block at a position from the copies; null when its section was not copied. Any thread. */
    fun stateAt(x: Int, y: Int, z: Int): BlockState? {
        val section = bySection[SectionPos.asLong(x shr 4, y shr 4, z shr 4)] ?: return null
        return section.states.get(x and 15, y and 15, z and 15)
    }
}

/** A block found by [Ores.find]. */
data class BlockHit(val pos: BlockPos, val state: BlockState, val distanceSq: Int)

/**
 * What [Ores.find] found: [total] matching blocks, the [nearest] ones (closest first), and the count of
 * each kind of block among them all ([byBlock], most frequent first).
 */
class Found(val total: Int, val nearest: List<BlockHit>, val byBlock: List<Pair<Block, Int>>)

/**
 * Scans loaded chunks for blocks, section by section, cheaply (ORACLE_ACTIONS_API.md §3.5):
 * 1. on the server thread, [copy] tests each section's palette (`maybeHas`: most sections are skipped
 *    without reading a block) and copies only the sections that may hold a wanted block;
 * 2. on any thread, [find] and [histogram] read the copies.
 *
 * The server thread never reads 4 096 blocks per section: a scan of radius 48 around a player in
 * deepslate full of ores (the worst case, every section copied) costs it a fraction of a millisecond;
 * the block by block reading happens off it. Chunks that are not loaded are skipped, never loaded.
 */
object Ores {
    /**
     * Copies the loaded non-empty sections within [radius] blocks of [center] (a sphere) that may hold a
     * block matching [wanted], or all of them when [wanted] is null. Server thread only.
     */
    fun copy(reader: WorldReader, center: BlockPos, radius: Int, wanted: ((BlockState) -> Boolean)?): SectionCopies {
        val level = reader.level
        val copies = ArrayList<SectionCopy>()
        var tested = 0
        var chunks = 0
        var loaded = 0
        val minSectionY = SectionPos.blockToSectionCoord(max(center.y - radius, level.minY))
        val maxSectionY = SectionPos.blockToSectionCoord(minOf(center.y + radius, level.maxY))
        for (cx in SectionPos.blockToSectionCoord(center.x - radius)..SectionPos.blockToSectionCoord(center.x + radius)) {
            for (cz in SectionPos.blockToSectionCoord(center.z - radius)..SectionPos.blockToSectionCoord(center.z + radius)) {
                if (!reaches(center, radius, cx, null, cz)) continue
                chunks++
                val chunk = reader.chunk(cx, cz) ?: continue
                loaded++
                for (sy in minSectionY..maxSectionY) {
                    if (!reaches(center, radius, cx, sy, cz)) continue
                    val section = chunk.getSection(level.getSectionIndexFromSectionY(sy))
                    if (section.hasOnlyAir()) continue
                    tested++
                    if (wanted != null && !section.maybeHas { wanted(it) }) continue
                    copies += SectionCopy(cx, sy, cz, section.states.copy())
                }
            }
        }
        return SectionCopies(copies, tested, chunks, loaded)
    }

    /**
     * The blocks of [copies] matching [wanted] within [radius] blocks of [center]: how many, the [max]
     * nearest, and the count per block. Any thread.
     */
    fun find(copies: SectionCopies, center: BlockPos, radius: Int, wanted: (BlockState) -> Boolean, max: Int): Found {
        val radiusSq = radius * radius
        // The farthest of the nearest hits on top, to be replaced by a nearer one.
        val nearest = PriorityQueue<BlockHit>(max + 1, compareByDescending { it.distanceSq })
        val perBlock = HashMap<Block, Int>()
        var total = 0
        forEachBlock(copies, center, radiusSq) { x, y, z, state, distanceSq ->
            if (!wanted(state)) return@forEachBlock
            total++
            perBlock.merge(state.block, 1, Int::plus)
            if (nearest.size < max || distanceSq < nearest.peek().distanceSq) {
                nearest += BlockHit(BlockPos(x, y, z), state, distanceSq)
                if (nearest.size > max) nearest.poll()
            }
        }
        val sorted = nearest.sortedBy { it.distanceSq }
        return Found(total, sorted, perBlock.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    /** How many blocks of each kind lie within [radius] blocks of [center] (air included). Any thread. */
    fun histogram(copies: SectionCopies, center: BlockPos, radius: Int): Map<Block, Int> {
        val counts = HashMap<Block, Int>()
        forEachBlock(copies, center, radius * radius) { _, _, _, state, _ -> counts.merge(state.block, 1, Int::plus) }
        return counts
    }

    private inline fun forEachBlock(
        copies: SectionCopies,
        center: BlockPos,
        radiusSq: Int,
        action: (x: Int, y: Int, z: Int, state: BlockState, distanceSq: Int) -> Unit,
    ) {
        for (section in copies.sections) {
            val baseX = section.x shl 4
            val baseY = section.y shl 4
            val baseZ = section.z shl 4
            for (ly in 0..15) {
                val dy = baseY + ly - center.y
                for (lz in 0..15) {
                    val dz = baseZ + lz - center.z
                    for (lx in 0..15) {
                        val dx = baseX + lx - center.x
                        val distanceSq = dx * dx + dy * dy + dz * dz
                        if (distanceSq > radiusSq) continue
                        action(baseX + lx, baseY + ly, baseZ + lz, section.states.get(lx, ly, lz), distanceSq)
                    }
                }
            }
        }
    }

    /** Whether the section (or the whole column when [sectionY] is null) comes within [radius] of [center]. */
    private fun reaches(center: BlockPos, radius: Int, chunkX: Int, sectionY: Int?, chunkZ: Int): Boolean {
        val dx = gap(center.x, chunkX shl 4)
        val dz = gap(center.z, chunkZ shl 4)
        val dy = if (sectionY == null) 0 else gap(center.y, sectionY shl 4)
        return dx.toLong() * dx + dy.toLong() * dy + dz.toLong() * dz <= radius.toLong() * radius
    }

    /** Distance from [value] to the span of 16 starting at [start]; 0 inside it. */
    private fun gap(value: Int, start: Int): Int = when {
        value < start -> start - value
        value > start + 15 -> value - (start + 15)
        else -> 0
    }
}
