package io.github.clemsix6.narrator2.mechanics.traps

import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.observer.id
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import kotlin.math.floor

/**
 * The cells of a trap around a player, or why there is no room (M4 §9.2): a glass cage (`punish cage`) or
 * cobwebs (`punish web`), which the [BlockRestorer] then sets and takes away.
 *
 * A cell is kept only when the [BlockRestorer] would take it ([BlockRestorer.cellRefusal]: loaded air that
 * no entity is in, the trapped player aside for a cobweb): a trap never replaces a block, natural or built,
 * nor pushes, smothers or unhooks anything; the cells left out are skipped. The terrain is refused (nothing is
 * set, and `punish` opens no episode) when the player is not standing on the ground (flying, gliding, riding,
 * asleep, in the air); when a liquid or fire is in the shape's box or the layer above it, or a falling block
 * at or above their feet (sand, gravel, or one already falling); and when fewer than half of the shape's cells
 * are free. Nothing here changes the world. Server thread.
 */
object TrapShapes {
    /** What a cage is made of, and cobwebs. */
    val cageBlock: BlockState get() = Blocks.GLASS.defaultBlockState()
    val webBlock: BlockState get() = Blocks.COBWEB.defaultBlockState()

    /**
     * The cells of a glass cage around [player]: the air of the shell one cell around the cells their body is
     * in, floor and roof included (3×3×4 for a player standing in the middle of a block, larger when they stand
     * across two).
     */
    fun cage(player: ServerPlayer): Result<List<BlockPos>> {
        val (low, high) = occupied(player.boundingBox)
        return shape(player, "a glass cage", cageBlock, cageCells(low, high))
    }

    /** The cells of cobwebs on [player]: their own two (feet, head) and the four next to their feet. */
    fun web(player: ServerPlayer): Result<List<BlockPos>> = shape(player, "cobwebs", webBlock, webCells(player.blockPosition()))

    /** The lowest and highest cells [box] (a body) is in. */
    internal fun occupied(box: AABB): Pair<BlockPos, BlockPos> = Pair(
        BlockPos(cell(box.minX + EDGE), cell(box.minY + EDGE), cell(box.minZ + EDGE)),
        BlockPos(cell(box.maxX - EDGE), cell(box.maxY - EDGE), cell(box.maxZ - EDGE)),
    )

    /** The shell one cell thick around the cells from [low] to [high]: floor, walls and roof, without the inside. */
    internal fun cageCells(low: BlockPos, high: BlockPos): List<BlockPos> = buildList {
        for (y in low.y - 1..high.y + 1) {
            for (x in low.x - 1..high.x + 1) {
                for (z in low.z - 1..high.z + 1) {
                    val inside = y in low.y..high.y && x in low.x..high.x && z in low.z..high.z
                    if (!inside) add(BlockPos(x, y, z))
                }
            }
        }
    }

    /** [feet], the cell above it (the head), and the four cells next to [feet]. */
    internal fun webCells(feet: BlockPos): List<BlockPos> =
        listOf(feet, feet.offset(0, 1, 0), feet.offset(1, 0, 0), feet.offset(-1, 0, 0), feet.offset(0, 0, 1), feet.offset(0, 0, -1))

    /** Whether [free] cells of [total] are enough: at least half of them. */
    internal fun enoughRoom(free: Int, total: Int): Boolean = free * 2 >= total

    /** The box to search for liquids, fire and falling blocks: the one around [cells], and the layer above it. */
    internal fun hazardBox(cells: List<BlockPos>): Pair<BlockPos, BlockPos> = Pair(
        BlockPos(cells.minOf { it.x }, cells.minOf { it.y }, cells.minOf { it.z }),
        BlockPos(cells.maxOf { it.x }, cells.maxOf { it.y } + 1, cells.maxOf { it.z }),
    )

    private fun shape(player: ServerPlayer, what: String, block: BlockState, cells: List<BlockPos>): Result<List<BlockPos>> {
        val name = player.plainTextName
        val level = player.level()
        val refusal = stance(player) ?: hazard(level, cells, player.blockPosition().y)
        if (refusal != null) return Result.failure(IllegalStateException("cannot trap $name in $what: $refusal"))
        val free = cells.filter { BlockRestorer.cellRefusal(level, it, block, player.uuid) == null }
        if (!enoughRoom(free.size, cells.size)) {
            return Result.failure(IllegalStateException("no room for $what around $name: ${free.size} of ${cells.size} cells free"))
        }
        if (free.size > BlockRestorer.MAX_PER_CALL) {
            return Result.failure(IllegalStateException("no room for $what around $name: ${free.size} cells, ${BlockRestorer.MAX_PER_CALL} at most"))
        }
        return Result.success(free)
    }

    /** Why [player] cannot be trapped where they stand: not on the ground, or doing something a trap would turn deadly. */
    private fun stance(player: ServerPlayer): String? = when {
        player.isSpectator -> "a spectator"
        player.isSleeping -> "asleep"
        player.isPassenger -> "riding"
        player.abilities.flying || player.isFallFlying -> "flying"
        !player.onGround() -> "not on the ground"
        else -> null
    }

    /**
     * A liquid or fire in the box around [cells] or the layer above it, or a falling block there at or above
     * [feetY] (one below the feet is the ground the player stands on); null when there is none. A cell not
     * loaded counts as a danger: it cannot be looked at.
     */
    private fun hazard(level: ServerLevel, cells: List<BlockPos>, feetY: Int): String? {
        val (low, high) = hazardBox(cells)
        for (pos in BlockPos.betweenClosed(low, high)) {
            val chunk = level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return "${at(pos)} is not loaded"
            val state = chunk.getBlockState(pos)
            when {
                !state.fluidState.isEmpty -> return "liquid at ${at(pos)} (${state.block.id})"
                state.`is`(BlockTags.FIRE) -> return "fire at ${at(pos)} (${state.block.id})"
                state.block is FallingBlock && pos.y >= feetY -> return "a block that falls at ${at(pos)} (${state.block.id})"
            }
        }
        val box = AABB(low.x.toDouble(), low.y.toDouble(), low.z.toDouble(), high.x + 1.0, high.y + 1.0, high.z + 1.0)
        val falling = level.getEntitiesOfClass(FallingBlockEntity::class.java, box).firstOrNull()
        return falling?.let { "a falling block at ${at(it.blockPosition())}" }
    }

    private fun at(pos: BlockPos) = "${pos.x} ${pos.y} ${pos.z}"

    private fun cell(coordinate: Double): Int = floor(coordinate).toInt()

    /** How far inside a body's box its cells are taken: a box that only touches a cell's face is not in that cell. */
    private const val EDGE = 1.0e-4
}
