package io.github.clemsix6.narrator2.oracle

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Reads [level] without ever loading or generating a chunk: every read goes through
 * `ServerChunkCache.getChunkNow`, which answers only for chunks already loaded (ORACLE_ACTIONS_API.md
 * §2.2). `Level.getBlockState` would load, or generate, a missing chunk on the server thread.
 *
 * As a [BlockGetter], it lets the game's own algorithms (the ray of [lookingAt]) run on loaded chunks
 * only: a block of a chunk that is not loaded reads as `void_air`, which rays go through.
 *
 * Server thread only. One reader per call: it remembers the last chunk read.
 */
class WorldReader(val level: ServerLevel) : BlockGetter {
    private var lastX = Int.MIN_VALUE
    private var lastZ = Int.MIN_VALUE
    private var lastChunk: LevelChunk? = null

    /** The loaded chunk at chunk coordinates [chunkX], [chunkZ]; null when it is not loaded. */
    fun chunk(chunkX: Int, chunkZ: Int): LevelChunk? {
        if (chunkX == lastX && chunkZ == lastZ) return lastChunk
        val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ)
        lastX = chunkX
        lastZ = chunkZ
        lastChunk = chunk
        return chunk
    }

    /** The loaded chunk holding [pos]; null when it is not loaded. */
    fun chunkAt(pos: BlockPos): LevelChunk? = chunk(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))

    /** The block at [pos]; null when its chunk is not loaded or [pos] is outside the build height. */
    fun stateOrNull(pos: BlockPos): BlockState? {
        if (level.isOutsideBuildHeight(pos.y)) return null
        return chunkAt(pos)?.getBlockState(pos)
    }

    override fun getBlockState(pos: BlockPos): BlockState = stateOrNull(pos) ?: Blocks.VOID_AIR.defaultBlockState()

    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = chunkAt(pos)?.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)

    override fun getHeight(): Int = level.height

    override fun getMinY(): Int = level.minY

    /**
     * The first free y above the highest block that blocks motion at [x], [z] (leaves count, fluids do
     * not: the ocean floor, not its surface); null when the chunk is not loaded.
     */
    fun solidSurfaceY(x: Int, z: Int): Int? {
        if (chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) == null) return null
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)
    }

    /** Sky light at [pos], 0-15, as stored by the light engine (the time of day does not change it). */
    fun skyLight(pos: BlockPos): Int = level.getBrightness(LightLayer.SKY, pos)

    /** Block light at [pos], 0-15 (torches, lava, glowstone…). */
    fun blockLight(pos: BlockPos): Int = level.getBrightness(LightLayer.BLOCK, pos)

    /** The biome id at [pos], e.g. `minecraft:plains`; never loads (a missing chunk's biome is computed from the noise). */
    fun biome(pos: BlockPos): String = level.getBiome(pos).registeredName

    /**
     * The block [entity] looks at within [distance] blocks, the way the game picks a block for the
     * crosshair, through loaded chunks only; null when it looks at nothing.
     */
    fun lookingAt(entity: Entity, distance: Double): BlockHitResult? {
        val from = entity.eyePosition
        val to: Vec3 = from.add(entity.getViewVector(1f).scale(distance))
        val hit = clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity))
        return hit.takeIf { it.type == HitResult.Type.BLOCK }
    }
}
