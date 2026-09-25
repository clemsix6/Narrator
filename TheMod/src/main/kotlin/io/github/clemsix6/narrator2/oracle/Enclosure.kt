package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.observer.PlacedBlocks
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.state.BlockState
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * What surrounds a player: open sky, a shelter, a tunnel or a cave (ARCHITECTURE.md §5: "in a cave =
 * no visible sky and well below the surface"). Dimensions with a ceiling (the Nether) have their own two
 * values, so that the Narrator never speaks of a cave there.
 */
enum class Enclosure(val id: String) {
    /** Nothing solid overhead: in the open, on the water, on the Nether's roof. */
    OPEN_SKY("open_sky"),

    /** Something overhead, but close to the surface or open to the outside: a tree, a roof, an overhang, a building. */
    SHELTERED("sheltered"),

    /** Well below the surface in a tight or built space: a tunnel, a mine, a dug-out room, a bunker. */
    UNDERGROUND("underground"),

    /** Well below the surface in a natural open volume, or in a cave biome. */
    CAVE("cave"),

    /** Under a dimension's ceiling (the Nether), in a large open volume. */
    OPEN("open"),

    /** Under a dimension's ceiling (the Nether), in a tight space: a tunnel, a room, a hut. */
    ENCLOSED("enclosed"),
    ;

    companion object {
        /** At most this many blocks from the feet to the surface is still "near the surface". */
        const val SHALLOW_DEPTH = 5

        /** Sky light from this level up means a way out to the open is close (an overhang, a cave mouth, windows). */
        const val OPEN_SKY_LIGHT = 8

        /** A built space (a player placed at least this share of its walls) this close to the surface is a building. */
        const val BUILDING_DEPTH = 15

        /** A player placed at least this share of the walls: a building or a bunker, not a cave. */
        const val BUILT_SHARE = 0.25

        /** Open cells found from the head, at most: enough to pass [CAVE_VOLUME] and [OPEN_VOLUME] (the flood stops there). */
        const val MAX_VOLUME = 256

        /** The flood explores at most this far from the head along each axis. */
        const val FLOOD_RADIUS = 8

        /** A natural space this large (open cells), with enough headroom, is a cave… */
        const val CAVE_VOLUME = 120

        /** …in a cave biome (lush caves, dripstone, deep dark, sulfur), a smaller one is enough. */
        const val CAVE_BIOME_VOLUME = 40

        /** At least one open cell in this many has two more open cells above it (a dug 1×2 tunnel has none). */
        const val CAVE_TALL_RATIO = 6

        /** Under a ceiling, this many open cells is a large open volume. */
        const val OPEN_VOLUME = 150

        private val CAVE_BIOMES by lazy { listOf(Biomes.DEEP_DARK, Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.SULFUR_CAVES) }

        /**
         * The verdict for what [probe] measured. Pure: the thresholds are tested without the game
         * (`EnclosureTest`) and on built cases in game (`OracleGameTests`).
         */
        fun classify(probe: EnclosureProbe): Enclosure {
            if (probe.ceiling) {
                return when {
                    probe.aboveCeiling -> OPEN_SKY
                    probe.volume >= OPEN_VOLUME -> OPEN
                    else -> ENCLOSED
                }
            }
            val depth = probe.depth ?: return SHELTERED // not loaded: say the least
            if (depth <= 0 || probe.skyLight >= 15) return OPEN_SKY
            if (depth <= SHALLOW_DEPTH || probe.skyLight >= OPEN_SKY_LIGHT) return SHELTERED
            if (probe.builtShare >= BUILT_SHARE) return if (depth <= BUILDING_DEPTH) SHELTERED else UNDERGROUND
            if (!probe.skyDimension) return UNDERGROUND // the End has no caves: an end city, a tunnel
            val natural = probe.volume >= CAVE_VOLUME && probe.tallCells * CAVE_TALL_RATIO >= probe.volume
            return if (natural || (probe.caveBiome && probe.volume >= CAVE_BIOME_VOLUME)) CAVE else UNDERGROUND
        }

        /** `cave, 41 below surface`, `open_sky`, `enclosed`: the depth is told only for a deep verdict. */
        fun label(enclosure: Enclosure, depth: Int?): String {
            val below = depth?.takeIf { it > SHALLOW_DEPTH && (enclosure == UNDERGROUND || enclosure == CAVE) }
            return if (below == null) enclosure.id else "${enclosure.id}, $below below surface"
        }

        /** The label of what surrounds someone standing at [feet] (see [probe] and [label]). Server thread only. */
        fun describeAt(reader: WorldReader, feet: BlockPos): String {
            val probe = probe(reader, feet, feet.above())
            return label(classify(probe), probe.depth)
        }

        /**
         * Measures around a player whose feet are at [feet] and head at [head], through loaded chunks only:
         * at most about 2 000 block reads (a flood of [MAX_VOLUME] cells), a fraction of a millisecond.
         */
        fun probe(reader: WorldReader, feet: BlockPos, head: BlockPos): EnclosureProbe {
            val type = reader.level.dimensionType()
            val surface = reader.solidSurfaceY(head.x, head.z)
            val flood = Flood(reader)
            flood.run(if (flood.open(head.x, head.y, head.z)) head else feet)
            val biome = reader.level.getBiome(head)
            return EnclosureProbe(
                skyDimension = type.hasSkyLight(),
                ceiling = type.hasCeiling(),
                aboveCeiling = type.hasCeiling() && feet.y >= reader.level.minY + type.logicalHeight(),
                skyLight = if (type.hasSkyLight()) reader.skyLight(head) else 0,
                depth = if (type.hasCeiling() || surface == null) null else surface - feet.y,
                volume = flood.volume,
                tallCells = flood.tall,
                builtShare = flood.builtShare,
                caveBiome = CAVE_BIOMES.any { biome.`is`(it) },
            )
        }
    }

    /**
     * A flood fill of the open cells connected to a start, within [FLOOD_RADIUS] and up to [MAX_VOLUME]
     * cells. It also counts the cells with two more open cells above (headroom), and checks the walls it
     * meets for player-placed blocks ([PlacedBlocks]). Positions are packed longs and the verdict per
     * block state is remembered: a flood meets few kinds of blocks.
     */
    private class Flood(private val reader: WorldReader) {
        var volume = 0
            private set
        var tall = 0
            private set
        var builtShare = 0.0
            private set

        private val cursor = BlockPos.MutableBlockPos()
        private val openStates = IdentityHashMap<BlockState, Boolean>()

        /** Whether a player gets through: not a block that blocks motion (fluids are open); a missing chunk is closed. */
        fun open(x: Int, y: Int, z: Int): Boolean {
            val state = reader.stateOrNull(cursor.set(x, y, z)) ?: return false
            return openStates.getOrPut(state) { !state.`is`(BlockTags.BLOCKS_MOTION) }
        }

        fun run(start: BlockPos) {
            if (!open(start.x, start.y, start.z)) return
            val seen = LongOpenHashSet()
            val queue = LongArrayFIFOQueue()
            seen.add(start.asLong())
            queue.enqueue(start.asLong())
            var walls = 0
            var built = 0
            while (!queue.isEmpty && volume < MAX_VOLUME) {
                val cell = queue.dequeueLong()
                val x = BlockPos.getX(cell)
                val y = BlockPos.getY(cell)
                val z = BlockPos.getZ(cell)
                volume++
                if (open(x, y + 1, z) && open(x, y + 2, z)) tall++
                for (direction in DIRECTIONS) {
                    val nx = x + direction.stepX
                    val ny = y + direction.stepY
                    val nz = z + direction.stepZ
                    if (abs(nx - start.x) > FLOOD_RADIUS || abs(ny - start.y) > FLOOD_RADIUS || abs(nz - start.z) > FLOOD_RADIUS) continue
                    if (!seen.add(BlockPos.asLong(nx, ny, nz))) continue
                    if (open(nx, ny, nz)) {
                        queue.enqueue(BlockPos.asLong(nx, ny, nz))
                    } else if (reader.stateOrNull(cursor.set(nx, ny, nz)) != null) {
                        walls++
                        if (placed(cursor)) built++
                    }
                }
            }
            builtShare = if (walls == 0) 0.0 else built.toDouble() / walls
        }

        /** A player placed the block at [pos] (its chunk is loaded). */
        private fun placed(pos: BlockPos): Boolean {
            val chunk = reader.chunkAt(pos) ?: return false
            return chunk.getAttached(PlacedBlocks.TYPE)?.placerAt(PlacedBlocks.pack(chunk, pos)) != null
        }

        private companion object {
            val DIRECTIONS = Direction.entries.toTypedArray()
        }
    }
}

/**
 * What [Enclosure.probe] measures around a player's head.
 *
 * @property skyDimension the dimension has sky light (the Overworld): caves exist only there.
 * @property ceiling the dimension has a ceiling (the Nether): the surface and the sky mean nothing there.
 * @property aboveCeiling on top of the ceiling (the Nether's roof).
 * @property skyLight sky light at the head, 0-15; 0 in a dimension without sky light.
 * @property depth blocks from the feet up to the first free block above the highest solid block
 *   (leaves count, water does not); 0 or less with nothing solid overhead; null under a ceiling or when
 *   the chunk is not loaded.
 * @property volume open cells connected to the head, up to [Enclosure.MAX_VOLUME].
 * @property tallCells of which have two more open cells above.
 * @property builtShare share of the walls met that players placed.
 * @property caveBiome the head is in a cave biome.
 */
data class EnclosureProbe(
    val skyDimension: Boolean,
    val ceiling: Boolean,
    val aboveCeiling: Boolean,
    val skyLight: Int,
    val depth: Int?,
    val volume: Int,
    val tallCells: Int,
    val builtShare: Double,
    val caveBiome: Boolean,
)
