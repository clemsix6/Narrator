package io.github.clemsix6.narrator2.tool.action

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture

/**
 * Safe teleports within the player's own dimension (M1_M2 §9.3, `ORACLE_ACTIONS_API.md` §4.6).
 *
 * The server thread never loads nor generates a chunk for a teleport: the destination is first loaded
 * by a `PORTAL` ticket ([requestLoad]), whose work runs between ticks and on the chunk workers, while
 * the tool waits off the server thread ([awaitLoaded]). The safe spot is then looked for in the loaded
 * column only ([landing]): a column that is not loaded has no safe spot. The ticket lapses by itself
 * (300 ticks); by then the player keeps the chunks loaded.
 */
object Teleports {
    /** How long the destination may take to load; past it the teleport gives up, and nothing happened. */
    const val LOAD_TIMEOUT_MS = 8_000L

    /** Radius, in chunks, loaded around the destination: the player's box may reach a neighbor. */
    private const val LOAD_RADIUS = 1

    /**
     * Asks [level] to load the chunks around column ([x], [z]); the future completes once they are loaded
     * (generated first if they never were, as `/tp` would). Server thread; the loading itself is not.
     */
    fun requestLoad(level: ServerLevel, x: Int, z: Int): CompletableFuture<*> =
        level.chunkSource.addTicketAndLoadWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos(x, 0, z)), LOAD_RADIUS)

    /**
     * Waits until [loading] completes; false when it takes longer than [LOAD_TIMEOUT_MS]. Never on the
     * server thread, which does the loading. Giving up leaves the loading itself alone.
     */
    suspend fun awaitLoaded(loading: CompletableFuture<*>): Boolean =
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            loading.copy().await()
            true
        } ?: false

    /**
     * A safe spot for [player] in column ([x], [z]) of [level], as the position of their feet; null when
     * there is none. Safe: a sturdy floor that is neither liquid nor dangerous (fire, lava, magma, cactus,
     * berry bush, wither rose, powder snow), and room for the player's standing box (their scale
     * included) with no liquid or dangerous block in it, inside the world border.
     *
     * Searched from [fromY] down to the bottom of the world, then up to the surface. Without [fromY]: from
     * the surface (the highest block that is not leaves), or from the player's own height in a dimension
     * with a ceiling (the Nether), never above the ceiling. Reads loaded chunks only. Server thread.
     */
    fun landing(level: ServerLevel, player: ServerPlayer, x: Int, z: Int, fromY: Int?): Vec3? {
        if (level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) == null) return null
        val dimension = level.dimensionType()
        val bottom = level.minY + 1
        val top = if (dimension.hasCeiling()) {
            level.minY + dimension.logicalHeight() - 1
        } else {
            minOf(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), level.maxY)
        }
        val start = (fromY ?: if (dimension.hasCeiling()) player.blockY else top).coerceIn(bottom, maxOf(bottom, top))
        val feet = BlockPos.MutableBlockPos()
        for (y in (start downTo bottom) + (start + 1..top)) {
            if (isSafe(level, player, feet.set(x, y, z))) return Vec3.atBottomCenterOf(feet)
        }
        return null
    }

    /** Moves [player] to [spot] in their own level, looking the same way, off their mount, with no fall to finish. Server thread. */
    fun move(player: ServerPlayer, spot: Vec3): Boolean {
        player.stopRiding()
        val moved = player.teleportTo(player.level(), spot.x, spot.y, spot.z, Relative.ROTATION, 0f, 0f, true)
        if (moved) player.resetFallDistance()
        return moved
    }

    private fun isSafe(level: ServerLevel, player: ServerPlayer, feet: BlockPos): Boolean {
        val below = feet.below()
        val floor = loadedState(level, below) ?: return false
        if (floor.isAir || !floor.fluidState.isEmpty || player.type.isBlockDangerous(floor)) return false
        if (!floor.isFaceSturdy(level, below, Direction.UP)) return false
        val box = player.getDimensions(Pose.STANDING).makeBoundingBox(Vec3.atBottomCenterOf(feet))
        if (!level.worldBorder.isWithinBounds(box)) return false
        for (pos in BlockPos.betweenClosed(box)) {
            val state = loadedState(level, pos) ?: return false
            if (!state.fluidState.isEmpty || player.type.isBlockDangerous(state)) return false
        }
        return level.noCollision(player, box)
    }

    /** The block at [pos] if its chunk is loaded and it is inside the world's height; never loads anything. */
    private fun loadedState(level: ServerLevel, pos: BlockPos): BlockState? {
        if (level.isOutsideBuildHeight(pos)) return null
        return level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))?.getBlockState(pos)
    }
}
