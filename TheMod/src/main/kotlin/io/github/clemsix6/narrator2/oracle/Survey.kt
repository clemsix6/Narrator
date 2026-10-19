package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.id
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * A flat, open square found by [Survey.find]: where to stand at its centre ([x], [y] the first free block
 * above the ground, [z]), the height difference of its ground ([spread], 0 or 1), whether only the sky is
 * above it ([openSky]; else leaves too), and its most common ground block ([ground], e.g. `grass_block`).
 */
data class SurveySpot(val x: Int, val y: Int, val z: Int, val spread: Int, val openSky: Boolean, val ground: String)

/**
 * The columns of a square area, read once on the server thread ([Survey.read]) and searched off it
 * ([Survey.find]). The column at `x`, `z` is at index `(z - minZ) * side + (x - minX)`.
 *
 * @property tops the y of each column's ground: its highest block that blocks motion, leaves aside
 *   (`Heightmap.Types.MOTION_BLOCKING_NO_LEAVES`).
 * @property flags what rules a column out ([Survey.UNLOADED], [Survey.UNFIT]) or marks it ([Survey.LEAVES]).
 * @property grounds each fit column's ground block, an index in [palette] (`grass_block`, `sand`…); -1 otherwise.
 * @property chunks the chunks the area covers, [loadedChunks] of them loaded (the others were not read).
 */
class SurveyGrid(
    val minX: Int,
    val minZ: Int,
    val side: Int,
    val tops: IntArray,
    val flags: ByteArray,
    val grounds: IntArray,
    val palette: List<String>,
    val chunks: Int,
    val loadedChunks: Int,
) {
    init {
        require(tops.size == side * side && flags.size == side * side && grounds.size == side * side) { "a grid of $side × $side columns" }
    }

    fun index(x: Int, z: Int): Int = (z - minZ) * side + (x - minX)
}

/**
 * The scout's survey (M4 §14): flat, open squares of ground around a point, for an arena or a scene. A square
 * of `size × size` columns fits when every column is loaded and fit, and its ground varies by one block at
 * most. A column is fit when it has ground (not the void), neither liquid nor dangerous (magma, fire, a berry
 * bush…), its ground and what stands on it not placed by a player ([PlacedBlocks]), and nothing but the sky or
 * leaves above it: the ground is the top of `MOTION_BLOCKING_NO_LEAVES`, so only leaves can be higher.
 *
 * Candidate centres lie on a grid of [STEP] blocks within the radius; the nearest fit squares are kept, at
 * most [MAX_SPOTS], none overlapping another. A dimension with a ceiling (the Nether) has its roof for
 * ground: the tool refuses it before reading.
 */
object Survey {
    /** Between two candidate centres, in blocks. */
    const val STEP = 2

    /** Spots given at most. */
    const val MAX_SPOTS = 5

    /** Largest height difference within a fit square. */
    const val MAX_SPREAD = 1

    /** The column's chunk is not loaded: it was not read. */
    const val UNLOADED: Byte = 1

    /** No ground to stand on there: void, liquid, danger, or a player's block. */
    const val UNFIT: Byte = 2

    /** Leaves above the ground: under a tree, not under the open sky. */
    const val LEAVES: Byte = 4

    /**
     * Reads the columns within [radius] of [centerX], [centerZ], plus half a square around, from the loaded
     * chunks of [reader] only (never loads one). Server thread only.
     */
    fun read(reader: WorldReader, centerX: Int, centerZ: Int, radius: Int, size: Int): SurveyGrid {
        val reach = radius + size / 2
        val side = 2 * reach + 1
        val minX = centerX - reach
        val minZ = centerZ - reach
        val maxX = minX + side - 1
        val maxZ = minZ + side - 1
        val tops = IntArray(side * side)
        val flags = ByteArray(side * side) { UNLOADED }
        val grounds = IntArray(side * side) { -1 }
        val palette = ArrayList<String>()
        val indexOf = IdentityHashMap<Block, Int>()
        val pos = BlockPos.MutableBlockPos()
        var chunks = 0
        var loaded = 0
        for (chunkX in SectionPos.blockToSectionCoord(minX)..SectionPos.blockToSectionCoord(maxX)) {
            for (chunkZ in SectionPos.blockToSectionCoord(minZ)..SectionPos.blockToSectionCoord(maxZ)) {
                chunks++
                val chunk = reader.chunk(chunkX, chunkZ) ?: continue
                loaded++
                val built = chunk.hasAttached(PlacedBlocks.TYPE)
                for (z in maxOf(minZ, chunkZ shl 4)..minOf(maxZ, (chunkZ shl 4) + 15)) {
                    for (x in maxOf(minX, chunkX shl 4)..minOf(maxX, (chunkX shl 4) + 15)) {
                        val i = (z - minZ) * side + (x - minX)
                        val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                        tops[i] = top
                        flags[i] = column(reader, chunk, pos, x, top, z, built)
                        if (flags[i] != UNFIT) {
                            val block = chunk.getBlockState(pos.set(x, top, z)).block
                            grounds[i] = indexOf.getOrPut(block) { palette += OracleText.bare(block.id); palette.size - 1 }
                        }
                    }
                }
            }
        }
        return SurveyGrid(minX, minZ, side, tops, flags, grounds, palette, chunks, loaded)
    }

    /** What one loaded column is: [UNFIT], [LEAVES], or fit under the open sky (0). [built]: its chunk has blocks placed by players. */
    private fun column(reader: WorldReader, chunk: LevelChunk, pos: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int, built: Boolean): Byte {
        if (top < reader.level.minY) return UNFIT // the void: no ground at all
        val ground = chunk.getBlockState(pos.set(x, top, z))
        if (!ground.fluidState.isEmpty || EntityTypes.PLAYER.isBlockDangerous(ground)) return UNFIT
        if (built && PlacedBlocks.placerAt(reader.level, pos) != null) return UNFIT
        val above = chunk.getBlockState(pos.set(x, top + 1, z))
        if (!above.isAir) {
            if (EntityTypes.PLAYER.isBlockDangerous(above)) return UNFIT
            if (built && PlacedBlocks.placerAt(reader.level, pos) != null) return UNFIT
        }
        return if (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > top) LEAVES else 0
    }

    /**
     * The fit squares of [size] × [size] columns of [grid] whose centre lies within [radius] of [centerX],
     * [centerZ] on the grid of [STEP]: the nearest first (the flatter first at the same distance), none
     * overlapping a nearer one, [max] at most. Any thread.
     */
    fun find(grid: SurveyGrid, centerX: Int, centerZ: Int, radius: Int, size: Int, max: Int = MAX_SPOTS): List<SurveySpot> {
        val before = (size - 1) / 2 // columns of a square before its centre; size - 1 - before after it
        val reach = radius / STEP * STEP
        val fits = ArrayList<Fit>()
        for (dz in -reach..reach step STEP) {
            for (dx in -reach..reach step STEP) {
                if (dx * dx + dz * dz > radius * radius) continue
                square(grid, centerX + dx - before, centerZ + dz - before, size)?.let { (spread, openSky) -> fits += Fit(dx, dz, spread, openSky) }
            }
        }
        fits.sortWith(compareBy<Fit>({ it.dx * it.dx + it.dz * it.dz }, { it.spread }))
        val kept = ArrayList<Fit>()
        for (fit in fits) {
            if (kept.size == max) break
            if (kept.none { abs(it.dx - fit.dx) < size && abs(it.dz - fit.dz) < size }) kept += fit
        }
        return kept.map { fit ->
            val x = centerX + fit.dx
            val z = centerZ + fit.dz
            SurveySpot(x, grid.tops[grid.index(x, z)] + 1, z, fit.spread, fit.openSky, ground(grid, x - before, z - before, size))
        }
    }

    /** A fit candidate, by its offset from the centre. */
    private class Fit(val dx: Int, val dz: Int, val spread: Int, val openSky: Boolean)

    /** The spread and open sky of the square from [x0], [z0], or null when it does not fit. */
    private fun square(grid: SurveyGrid, x0: Int, z0: Int, size: Int): Pair<Int, Boolean>? {
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var openSky = true
        for (z in z0 until z0 + size) {
            var i = grid.index(x0, z)
            repeat(size) {
                val flag = grid.flags[i].toInt()
                if (flag and (UNLOADED.toInt() or UNFIT.toInt()) != 0) return null
                if (flag and LEAVES.toInt() != 0) openSky = false
                val top = grid.tops[i]
                if (top < low) low = top
                if (top > high) high = top
                if (high - low > MAX_SPREAD) return null
                i++
            }
        }
        return (high - low) to openSky
    }

    /** The most common ground block of the square from [x0], [z0]. */
    private fun ground(grid: SurveyGrid, x0: Int, z0: Int, size: Int): String {
        val counts = IntArray(grid.palette.size)
        for (z in z0 until z0 + size) for (x in x0 until x0 + size) counts[grid.grounds[grid.index(x, z)]]++
        return grid.palette[counts.indices.maxBy { counts[it] }]
    }
}
