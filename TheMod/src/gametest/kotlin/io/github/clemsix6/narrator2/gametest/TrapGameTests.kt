package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.ChunkRestorations
import io.github.clemsix6.narrator2.mechanics.traps.TrapShapes
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-game tests of the [BlockRestorer] and the traps (M4 §9.1, §9.2, §21.2): blocks set only in the air no
 * entity is in, never in place of a block; the terrain a trap refuses; blocks taken away when due, but one a
 * player changed; and a chunk loaded again after its deadline, emptied the second after, never in the
 * `CHUNK_LOAD` call. Each test has its own player (a random UUID when no player is needed): the traps of the
 * tests running alongside are never touched.
 */
class TrapGameTests {

    /** A cage sets glass only in the air around the player, never in place of a block of theirs, then takes it away by itself. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aCageSetsGlassOnlyInTheAirAroundThePlayerThenGoesAway(helper: GameTestHelper) {
        floor(helper)
        // A wall block and a roof block a player built: the cage goes around them.
        helper.setBlock(3, 1, 2, Blocks.OAK_PLANKS)
        helper.setBlock(2, 3, 1, Blocks.OAK_PLANKS)
        val player = standing(helper, BlockPos(2, 1, 2))
        val cells = TrapShapes.cage(player).getOrThrow()
        helper.assertEquals(34 - 9 - 2, cells.size, "cells of the 3x3x4 shell but the stone floor and the two planks")
        val planks = listOf(BlockPos(3, 1, 2), BlockPos(2, 3, 1)).map(helper::absolutePos)
        helper.assertTrue(cells.none { it in planks }, "never in place of a block")
        helper.assertTrue(cells.all { helper.level.getBlockState(it).isAir }, "only air")

        val until = System.currentTimeMillis() + 1_500
        helper.assertEquals(cells.size, BlockRestorer.place(helper.level, cells, TrapShapes.cageBlock, until, player.uuid, -4_242L).getOrThrow(), "set")
        helper.assertTrue(cells.all { helper.level.getBlockState(it).`is`(Blocks.GLASS) }, "glass in every cell")
        helper.assertTrue(planks.all { helper.level.getBlockState(it).`is`(Blocks.OAK_PLANKS) }, "the planks stay")
        helper.assertTrue(helper.level.getBlockState(player.blockPosition()).isAir && helper.level.getBlockState(player.blockPosition().above()).isAir, "nothing where the player is")
        helper.assertEquals(listOf(ActiveEffect("trap", "${cells.size} minecraft:glass", until, harmful = true)), BlockRestorer.activeOn(player.uuid), "the trap holds them")
        helper.assertEquals(cells.size, savedFor(helper.level, cells, player.uuid), "a snapshot per block, in the chunks")

        helper.startSequence()
            .thenAwait {
                helper.assertTrue(cells.all { helper.level.getBlockState(it).isAir }, "the glass goes away by itself")
                helper.assertTrue(planks.all { helper.level.getBlockState(it).`is`(Blocks.OAK_PLANKS) }, "the planks still stay")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player.uuid), "nothing holds them any more")
                helper.assertEquals(0, savedFor(helper.level, cells, player.uuid), "no snapshot left")
            }
            .thenExecute { removePlayers(helper, player) }
            .thenSucceed()
    }

    /** Cobwebs go in the player's own cells and the four next to their feet, and go away by themselves: the player is free. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun cobwebsHoldThePlayerInTheirOwnCellsThenGoAway(helper: GameTestHelper) {
        floor(helper)
        val player = standing(helper, BlockPos(4, 1, 4))
        val cells = TrapShapes.web(player).getOrThrow()
        val feet = player.blockPosition()
        helper.assertEquals(6, cells.size, "feet, head and the four cells next to the feet")
        helper.assertTrue(feet in cells && feet.above() in cells, "the player's own cells: a cobweb has no collision")

        val until = System.currentTimeMillis() + 1_500
        helper.assertEquals(6, BlockRestorer.place(helper.level, cells, TrapShapes.webBlock, until, player.uuid, 42L).getOrThrow(), "set")
        helper.assertTrue(cells.all { helper.level.getBlockState(it).`is`(Blocks.COBWEB) }, "cobwebs in every cell")
        helper.startSequence()
            .thenAwait {
                helper.assertTrue(cells.all { helper.level.getBlockState(it).isAir }, "the cobwebs go away: the player is free")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player.uuid), "nothing holds them any more")
            }
            .thenExecute { removePlayers(helper, player) }
            .thenSucceed()
    }

    /**
     * A cell an entity is in is left out of the trap: a frame stays hooked, a creature is neither pushed nor
     * smothered, an item on the ground stays there. Asked for directly, such a cell refuses the whole call.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun cellsTakenByAnEntityAreLeftAlone(helper: GameTestHelper) {
        floor(helper)
        val level = helper.level
        val player = standing(helper, BlockPos(3, 1, 3))
        // A frame hanging in the west wall at head height, on a stone pillar outside the cage.
        helper.setBlock(1, 2, 3, Blocks.STONE)
        val framed = helper.absolutePos(BlockPos(2, 2, 3))
        val frame = ItemFrame(level, framed, Direction.EAST)
        helper.assertTrue(frame.survives() && level.addFreshEntity(frame), "the frame hangs")
        // A creature in the east wall, an item on the ground in the south wall.
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(4, 1, 3))
        val item = helper.spawnItem(Items.DIAMOND, Vec3(3.5, 1.1, 4.5))
        val taken = listOf(framed, helper.absolutePos(BlockPos(4, 1, 3)), helper.absolutePos(BlockPos(3, 1, 4)))

        val cells = TrapShapes.cage(player).getOrThrow()
        helper.assertEquals(34 - 9 - 3, cells.size, "the shell but the floor and the three cells taken")
        helper.assertTrue(cells.none { it in taken }, "no cell an entity is in")

        val until = System.currentTimeMillis() + 1_500
        val refused = BlockRestorer.place(level, listOf(cells.first(), framed), TrapShapes.cageBlock, until, player.uuid, null)
        helper.assertTrue(refused.exceptionOrNull()?.message?.contains("taken by minecraft:item_frame") == true, "the frame's cell refused: $refused")
        helper.assertTrue(level.getBlockState(cells.first()).isAir, "all or nothing: the other cell stays air")

        helper.assertEquals(cells.size, BlockRestorer.place(level, cells, TrapShapes.cageBlock, until, player.uuid, null).getOrThrow(), "set")
        helper.assertTrue(frame.isAlive && frame.survives(), "the frame stays hooked")
        helper.assertTrue(pig.isAlive && pig.health == pig.maxHealth, "the pig is neither pushed nor hurt")
        helper.assertTrue(item.isAlive, "the item stays on the ground")
        helper.assertTrue(taken.all { level.getBlockState(it).isAir }, "their cells stay air")
        helper.startSequence()
            .thenAwait { helper.assertTrue(cells.all { level.getBlockState(it).isAir }, "the glass goes away") }
            .thenExecute {
                helper.assertTrue(frame.isAlive && frame.survives(), "the frame is still hooked")
                listOf(frame, pig, item).forEach { it.discard() }
                removePlayers(helper, player)
            }
            .thenSucceed()
    }

    /**
     * No trap for a player in the air or flying; nor with water or lava in the shape's box or just above it,
     * fire in it, or sand over their head; nor in a pit with fewer than half the cells free. Nothing is set.
     */
    @GameTest
    fun noTrapInTheAirNextToLiquidsOrWithoutRoom(helper: GameTestHelper) {
        floor(helper)
        val player = standing(helper, BlockPos(3, 1, 3))
        try {
            player.setOnGround(false)
            refused(helper, TrapShapes.cage(player), "cannot trap ${player.plainTextName} in a glass cage: not on the ground")
            refused(helper, TrapShapes.web(player), "cannot trap ${player.plainTextName} in cobwebs: not on the ground")
            player.setOnGround(true)
            player.abilities.flying = true
            refused(helper, TrapShapes.cage(player), "glass cage: flying")
            player.abilities.flying = false

            helper.setBlock(4, 1, 3, Blocks.WATER)
            refused(helper, TrapShapes.cage(player), "glass cage: liquid at")
            refused(helper, TrapShapes.web(player), "cobwebs: liquid at")
            helper.setBlock(4, 1, 3, Blocks.AIR)
            // Lava just above the cage's roof: in the cage's box, above the cobwebs'.
            helper.setBlock(3, 4, 3, Blocks.LAVA)
            refused(helper, TrapShapes.cage(player), "glass cage: liquid at")
            helper.assertTrue(TrapShapes.web(player).isSuccess, "cobwebs do not reach that high")
            helper.setBlock(3, 4, 3, Blocks.AIR)
            helper.setBlock(2, 1, 2, Blocks.FIRE)
            refused(helper, TrapShapes.cage(player), "glass cage: fire at")
            helper.setBlock(2, 1, 2, Blocks.AIR)
            helper.setBlock(3, 3, 3, Blocks.SAND)
            refused(helper, TrapShapes.cage(player), "glass cage: a block that falls at")
            helper.setBlock(3, 3, 3, Blocks.AIR)
            helper.assertTrue(TrapShapes.cage(player).isSuccess, "room for a cage once all that is gone")

            // A 1x1 pit two blocks deep: the walls are stone, only the roof is free.
            for (dx in -1..1) for (dz in -1..1) {
                if (dx != 0 || dz != 0) for (y in 1..2) helper.setBlock(3 + dx, y, 3 + dz, Blocks.STONE)
            }
            refused(helper, TrapShapes.cage(player), "no room for a glass cage around ${player.plainTextName}: 9 of 34 cells free")
            refused(helper, TrapShapes.web(player), "no room for cobwebs around ${player.plainTextName}: 2 of 6 cells free")
            helper.forEveryBlockInStructure { pos ->
                val state = helper.level.getBlockState(pos)
                helper.assertFalse(state.`is`(Blocks.GLASS) || state.`is`(Blocks.COBWEB), "nothing set at $pos")
            }
        } finally {
            removePlayers(helper, player)
        }
        helper.succeed()
    }

    /**
     * What `place` refuses, all or nothing: a cell that is not air, a block other than glass or cobwebs, too many
     * cells, the same cell twice, a cell that already holds a trap block (here broken by a player: it is air).
     */
    @GameTest
    fun placeRefusesAllOrNothing(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val until = System.currentTimeMillis() + 600_000
        val air = helper.absolutePos(BlockPos(1, 3, 1))
        helper.setBlock(2, 3, 1, Blocks.STONE)
        val stone = helper.absolutePos(BlockPos(2, 3, 1))
        val glass = TrapShapes.cageBlock

        refusedPlace(helper, BlockRestorer.place(level, listOf(air, stone), glass, until, player, null), "is not air (minecraft:stone)")
        refusedPlace(helper, BlockRestorer.place(level, listOf(air), Blocks.STONE.defaultBlockState(), until, player, null), "only glass and cobwebs")
        val many = (0 until BlockRestorer.MAX_PER_CALL + 1).map { helper.absolutePos(BlockPos(it % 8, 4 + it / 64, it / 8 % 8)) }
        refusedPlace(helper, BlockRestorer.place(level, many, glass, until, player, null), "65 blocks: a trap sets 64 at most")
        refusedPlace(helper, BlockRestorer.place(level, listOf(air, air), glass, until, player, null), "the same cell is given twice")
        helper.assertTrue(level.getBlockState(air).isAir && many.all { level.getBlockState(it).isAir }, "nothing set by a refused call")

        helper.assertEquals(1, BlockRestorer.place(level, listOf(air), glass, until, player, null).getOrThrow(), "set")
        helper.setBlock(1, 3, 1, Blocks.AIR) // a player broke it
        refusedPlace(helper, BlockRestorer.place(level, listOf(air), glass, until, player, null), "already holds a trap block")
        helper.assertEquals(0, BlockRestorer.restoreFor(player), "nothing to take away: the glass is gone")
        helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "its snapshot is gone too")
        helper.setBlock(2, 3, 1, Blocks.AIR)
        helper.succeed()
    }

    /** A pardon takes a player's traps away at once, and only theirs. */
    @GameTest
    fun aPardonTakesThePlayersTrapsAwayAtOnce(helper: GameTestHelper) {
        val level = helper.level
        val bartok = UUID.randomUUID()
        val mimosa = UUID.randomUUID()
        val until = System.currentTimeMillis() + 600_000
        val his = listOf(BlockPos(1, 3, 1), BlockPos(2, 3, 1)).map(helper::absolutePos)
        val hers = helper.absolutePos(BlockPos(4, 3, 1))
        helper.assertEquals(2, BlockRestorer.place(level, his, TrapShapes.cageBlock, until, bartok, null).getOrThrow(), "his")
        helper.assertEquals(1, BlockRestorer.place(level, listOf(hers), TrapShapes.webBlock, until, mimosa, null).getOrThrow(), "hers")

        helper.assertEquals(2, BlockRestorer.restoreFor(bartok), "his blocks taken away")
        helper.assertTrue(his.all { level.getBlockState(it).isAir }, "at once")
        helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(bartok), "nothing holds him")
        helper.assertTrue(level.getBlockState(hers).`is`(Blocks.COBWEB), "hers stays")
        helper.assertEquals(listOf(ActiveEffect("trap", "1 minecraft:cobweb", until, harmful = true)), BlockRestorer.activeOn(mimosa), "and still holds her")
        helper.assertEquals(1, BlockRestorer.restoreFor(mimosa), "hers taken away")
        helper.succeed()
    }

    /** A block a player put in place of the glass stays when the trap ends; the others go. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aBlockChangedSinceItWasSetStays(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val changed = helper.absolutePos(BlockPos(5, 2, 5))
        val kept = helper.absolutePos(BlockPos(6, 2, 5))
        helper.assertEquals(2, BlockRestorer.place(level, listOf(changed, kept), TrapShapes.cageBlock, System.currentTimeMillis() + 1_000, player, null).getOrThrow(), "set")
        helper.setBlock(5, 2, 5, Blocks.OAK_PLANKS) // a player broke the glass and put their own block
        helper.startSequence()
            .thenAwait {
                helper.assertTrue(level.getBlockState(kept).isAir, "the glass goes away")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "both snapshots gone")
            }
            .thenExecute {
                helper.assertTrue(level.getBlockState(changed).`is`(Blocks.OAK_PLANKS), "the player's block stays")
                helper.setBlock(5, 2, 5, Blocks.AIR)
            }
            .thenSucceed()
    }

    /**
     * A chunk unloaded with a trap block in it, and loaded again after the block's deadline: the `CHUNK_LOAD`
     * call only indexes it (the block is still there when the call ends), and the second after takes it away.
     * The chunk is far from every test and player, forced loaded and let go; its unload saves it, so its
     * reload reads the snapshot back from the chunk's data, as after a restart. The deadline passes while it is
     * unloaded: it is written in the past to the chunk as it is saved, while the index of the loaded chunks
     * keeps the first one, so nothing is taken away before the unload.
     */
    @GameTest(maxTicks = CHUNK_MAX_TICKS)
    fun aChunkLoadedAfterTheDeadlineIsEmptiedTheSecondAfterNeverWhileLoading(helper: GameTestHelper) {
        val level = helper.level
        val chunk = ChunkPos(FAR_CHUNK + ThreadLocalRandom.current().nextInt(10_000), FAR_CHUNK + ThreadLocalRandom.current().nextInt(10_000))
        val pos = BlockPos(chunk.minBlockX + 8, level.maxY - 2, chunk.minBlockZ + 8)
        val player = UUID.randomUUID()
        val watch = ChunkWatch(level.dimension(), chunk, pos, player).also(::watch)
        level.setChunkForced(chunk.x(), chunk.z(), true)
        helper.startSequence()
            .thenAwait { helper.assertTrue(level.chunkSource.getChunkNow(chunk.x(), chunk.z()) != null, "the far chunk loads") }
            .thenExecute {
                helper.assertEquals(1, BlockRestorer.place(level, listOf(pos), TrapShapes.cageBlock, System.currentTimeMillis() + 600_000, player, null).getOrThrow(), "set")
                val loaded = level.chunkSource.getChunkNow(chunk.x(), chunk.z())!!
                val entries = loaded.getAttached(BlockRestorer.TYPE)!!.entries
                loaded.setAttached(BlockRestorer.TYPE, ChunkRestorations(entries.map { it.copy(until = System.currentTimeMillis() - 1) }))
                level.setChunkForced(chunk.x(), chunk.z(), false)
            }
            .thenAwait { helper.assertTrue(watch.unloads > 0, "the chunk unloads, saved") }
            .thenExecute {
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "forgotten with the chunk")
                level.setChunkForced(chunk.x(), chunk.z(), true)
            }
            .thenAwait { helper.assertTrue(watch.loads == 2 && level.chunkSource.getChunkNow(chunk.x(), chunk.z()) != null, "the chunk loads again") }
            .thenExecute {
                helper.assertTrue(watch.indexedAtLoad, "indexed in the CHUNK_LOAD call")
                helper.assertTrue(watch.stateAtLoad?.`is`(Blocks.GLASS) == true, "no block changed in the CHUNK_LOAD call: ${watch.stateAtLoad}")
            }
            .thenAwait {
                val loaded = level.chunkSource.getChunkNow(chunk.x(), chunk.z())
                helper.assertTrue(loaded != null && loaded.getBlockState(pos).isAir, "taken away the second after")
                helper.assertTrue(loaded!!.getAttached(BlockRestorer.TYPE) == null, "no snapshot left in the chunk")
            }
            .thenExecute {
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "nothing pending")
                level.setChunkForced(chunk.x(), chunk.z(), false)
                watches.remove(watch)
            }
            .thenSucceed()
    }

    /** What a test sees of one chunk's loads and unloads. */
    private class ChunkWatch(val dimension: ResourceKey<Level>, val chunk: ChunkPos, val pos: BlockPos, val player: UUID) {
        @Volatile var loads = 0

        @Volatile var unloads = 0

        /** The block at [pos] when the last `CHUNK_LOAD` call reached the test, after the restorer's: unchanged. */
        @Volatile var stateAtLoad: BlockState? = null

        /** Whether the restorer had indexed [player]'s block by then. */
        @Volatile var indexedAtLoad = false
    }

    companion object {
        /** Ticks of the far chunk's test: generation, unload, reload and a pass, waiting at least 1 ms a tick. */
        const val CHUNK_MAX_TICKS = 40_000

        /** Chunk coordinates far from every test structure and player. */
        private const val FAR_CHUNK = 20_000

        private val watches = CopyOnWriteArrayList<ChunkWatch>()
        private val hooked = AtomicBoolean()

        /**
         * Watches [watch]'s chunk. The hooks, registered once and never taken off, come after the restorer's
         * (registered at mod initialization): they see the chunk as the restorer's call left it.
         */
        private fun watch(watch: ChunkWatch) {
            if (hooked.compareAndSet(false, true)) {
                ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ ->
                    for (w in watches) {
                        if (w.dimension != level.dimension() || w.chunk != chunk.pos) continue
                        w.stateAtLoad = chunk.getBlockState(w.pos)
                        w.indexedAtLoad = BlockRestorer.activeOn(w.player).isNotEmpty()
                        w.loads++
                    }
                }
                ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk ->
                    for (w in watches) if (w.dimension == level.dimension() && w.chunk == chunk.pos) w.unloads++
                }
            }
            watches += watch
        }

        /** A survival player standing on the ground in the middle of [at]. */
        fun standing(helper: GameTestHelper, at: BlockPos): ServerPlayer = mockPlayer(helper, at).also { it.setOnGround(true) }

        /** The snapshots of [player] that the chunks of [cells] hold. */
        fun savedFor(level: ServerLevel, cells: List<BlockPos>, player: UUID): Int =
            cells.map { ChunkPos.containing(it) }.distinct().sumOf { pos ->
                level.chunkSource.getChunkNow(pos.x(), pos.z())?.getAttached(BlockRestorer.TYPE)?.entries.orEmpty().count { it.player == player }
            }

        private fun refused(helper: GameTestHelper, result: Result<List<BlockPos>>, expected: String) {
            val message = result.exceptionOrNull()?.message
            helper.assertTrue(message != null && expected in message, "refused with « $expected »: ${result.getOrNull() ?: message}")
        }

        private fun refusedPlace(helper: GameTestHelper, result: Result<Int>, expected: String) {
            val message = result.exceptionOrNull()?.message
            helper.assertTrue(message != null && expected in message, "refused with « $expected »: ${result.getOrNull() ?: message}")
        }
    }
}
