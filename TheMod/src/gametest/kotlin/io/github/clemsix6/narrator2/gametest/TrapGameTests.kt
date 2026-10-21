package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.ChunkRestorations
import io.github.clemsix6.narrator2.mechanics.traps.TrapShapes
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the [BlockRestorer] and the traps (M4 §9.1, §9.2, §21.2): blocks set only in the air no
 * entity is in, never in place of a block; the terrain a trap refuses; blocks taken away when due, but one a
 * player changed, put back or pushed in place of a trap block; a trap block a player breaks drops nothing;
 * and a chunk loaded again after its deadline, emptied the second after, never in the `CHUNK_LOAD` call. Each
 * test has its own player (a random UUID when no player is needed): the traps of the tests running alongside
 * are never touched.
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
     * cells, the same cell twice, a cell that already holds a trap block (here gone within the tick, before the
     * restorer could see it: it is air).
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
        helper.setBlock(1, 3, 1, Blocks.AIR) // gone without a player, who would make the restorer forget the cell
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
        val listed = BlockRestorer.byPlayer()
        helper.assertEquals(listOf(ActiveEffect("trap", "2 minecraft:glass", until, harmful = true)), listed[bartok], "his, listed")
        helper.assertEquals(listOf(ActiveEffect("trap", "1 minecraft:cobweb", until, harmful = true)), listed[mimosa], "hers, listed")

        helper.assertEquals(2, BlockRestorer.restoreFor(bartok), "his blocks taken away")
        helper.assertTrue(his.all { level.getBlockState(it).isAir }, "at once")
        helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(bartok), "nothing holds him")
        helper.assertTrue(level.getBlockState(hers).`is`(Blocks.COBWEB), "hers stays")
        helper.assertEquals(listOf(ActiveEffect("trap", "1 minecraft:cobweb", until, harmful = true)), BlockRestorer.activeOn(mimosa), "and still holds her")
        helper.assertEquals(1, BlockRestorer.restoreFor(mimosa), "hers taken away")
        helper.succeed()
    }

    /** A block put in place of the glass stays when the trap ends (it is forgotten at the end of the tick); the others go. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aBlockChangedSinceItWasSetStays(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val changed = helper.absolutePos(BlockPos(5, 2, 5))
        val kept = helper.absolutePos(BlockPos(6, 2, 5))
        helper.assertEquals(2, BlockRestorer.place(level, listOf(changed, kept), TrapShapes.cageBlock, System.currentTimeMillis() + 1_000, player, null).getOrThrow(), "set")
        helper.setBlock(5, 2, 5, Blocks.OAK_PLANKS) // the glass replaced by planks, whatever did it
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
     * Bartok breaks a pane of his cage with a silk touch pickaxe, and Mimosa puts her own glass in its place, by
     * a real break and a real placement: at the end of the trap her glass stays, and only the pane still in
     * place goes (M4 review, finding 2).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aGlassAPlayerPutsInPlaceOfABrokenPaneStays(helper: GameTestHelper) {
        floor(helper)
        val level = helper.level
        val bartok = TestPlayer(helper)
        val mimosa = TestPlayer(helper)
        val pane = BlockPos(3, 1, 2)
        val cells = listOf(pane, BlockPos(3, 1, 4)).map(helper::absolutePos)
        bartok.standAt(BlockPos(2, 1, 2))
        bartok.makeVulnerable()
        mimosa.standAt(BlockPos(5, 1, 2))
        mimosa.makeVulnerable()
        helper.assertEquals(2, BlockRestorer.place(level, cells, TrapShapes.cageBlock, System.currentTimeMillis() + 1_500, bartok.uuid, null).getOrThrow(), "set")

        breakWith(bartok, cells[0], silkTouchPickaxe(helper))
        helper.assertTrue(level.getBlockState(cells[0]).isAir, "Bartok breaks the pane")
        mimosa.live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.GLASS))
        helper.placeAt(mimosa.live, mimosa.live.mainHandItem, pane.below(), Direction.UP)
        helper.assertTrue(level.getBlockState(cells[0]).`is`(Blocks.GLASS), "Mimosa puts her glass in its place")
        helper.assertEquals(mimosa.uuid, PlacedBlocks.placerAt(level, cells[0]), "placed by her")
        helper.startSequence()
            .thenAwait { helper.assertTrue(level.getBlockState(cells[1]).isAir, "the pane still in place goes") }
            .thenExecute {
                helper.assertTrue(level.getBlockState(cells[0]).`is`(Blocks.GLASS), "her glass stays")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(bartok.uuid), "nothing holds him")
                helper.killAllEntitiesOfClass(ItemEntity::class.java)
                helper.setBlock(pane, Blocks.AIR)
                bartok.close()
                mimosa.close()
            }
            .thenSucceed()
    }

    /**
     * A pane gone without a player (blown up, say) and a player's glass put in its place within the same tick,
     * before the restorer could see the cell empty: the index of the blocks placed says whose glass it is, and
     * it stays (M4 review, finding 2).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aGlassAPlayerPlacedInPlaceOfAPaneWithinTheTickStays(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val pane = BlockPos(2, 2, 2)
        val cells = listOf(pane, BlockPos(4, 2, 2)).map(helper::absolutePos)
        helper.assertEquals(2, BlockRestorer.place(level, cells, TrapShapes.cageBlock, System.currentTimeMillis() + 1_500, player, null).getOrThrow(), "set")
        helper.setBlock(pane, Blocks.AIR)
        helper.setBlock(pane, Blocks.GLASS)
        PlacedBlocks.mark(level, cells[0], Blocks.GLASS.defaultBlockState(), UUID.randomUUID())
        helper.startSequence()
            .thenAwait { helper.assertTrue(level.getBlockState(cells[1]).isAir, "the pane still in place goes") }
            .thenExecute {
                helper.assertTrue(level.getBlockState(cells[0]).`is`(Blocks.GLASS), "the player's glass stays")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "nothing pending")
                helper.setBlock(pane, Blocks.AIR)
            }
            .thenSucceed()
    }

    /**
     * A piston pushes a player's glass in place of a pane, and the pane one cell further: at the end of the trap
     * the player's glass stays (M4 review, finding 2). The pushed pane stays too, for good: the restorer knows
     * only the cells it set (finding 39).
     *
     * The redstone block makes the piston push at the block events of the level's next tick, and the trap is set at
     * the start of that very tick ([atNextLevelTick]): the push always comes before a pass of the restorer (at the end
     * of the server's tick) can see the pane due. Set from here with its 1.5 s, it was taken away before the push
     * whenever the rest of this tick, the batch's other tests, took longer (FLAKY-5).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aGlassPushedInPlaceOfAPaneByAPistonStays(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val piston = BlockPos(1, 2, 3)
        val theirs = piston.east()
        val pane = theirs.east()
        val pushedTo = pane.east()
        val cells = listOf(pane, BlockPos(3, 2, 6)).map(helper::absolutePos)
        helper.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST))
        helper.setBlock(theirs, Blocks.GLASS)
        PlacedBlocks.mark(level, helper.absolutePos(theirs), Blocks.GLASS.defaultBlockState(), UUID.randomUUID())
        helper.setBlock(piston.west(), Blocks.REDSTONE_BLOCK)
        val set = AtomicReference<Result<Int>>()
        atNextLevelTick(level) { set.set(runCatching { BlockRestorer.place(level, cells, TrapShapes.cageBlock, System.currentTimeMillis() + 1_500, player, null).getOrThrow() }) }
        helper.startSequence()
            .thenAwait { helper.assertEquals(2, set.get()?.getOrThrow(), "set, before the piston pushes") }
            .thenAwait {
                helper.assertTrue(helper.getBlockState(theirs).`is`(Blocks.PISTON_HEAD), "the piston pushes")
                helper.assertTrue(helper.getBlockState(pane).`is`(Blocks.GLASS) && helper.getBlockState(pushedTo).`is`(Blocks.GLASS), "both glass blocks moved")
            }
            .thenAwait { helper.assertTrue(level.getBlockState(cells[1]).isAir, "the pane left in place goes") }
            .thenExecute {
                helper.assertTrue(helper.getBlockState(pane).`is`(Blocks.GLASS), "the player's glass stays")
                helper.assertTrue(helper.getBlockState(pushedTo).`is`(Blocks.GLASS), "the pushed pane stays, for good")
                helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "nothing pending")
                for (rel in listOf(piston.west(), piston, theirs, pane, pushedTo)) helper.setBlock(rel, Blocks.AIR)
            }
            .thenSucceed()
    }

    /**
     * A trap block a player breaks drops nothing, and is forgotten: no glass with a silk touch pickaxe, no
     * cobweb with shears, no string with a sword. An ordinary cobweb still drops (M4 review, finding 39).
     */
    @GameTest
    fun aTrapBlockAPlayerBreaksDropsNothing(helper: GameTestHelper) {
        val level = helper.level
        val player = UUID.randomUUID()
        val breaker = TestPlayer(helper)
        try {
            breaker.standAt(BlockPos(1, 1, 1))
            breaker.makeVulnerable()
            val glass = helper.absolutePos(BlockPos(3, 2, 3))
            val webs = listOf(BlockPos(5, 2, 3), BlockPos(3, 2, 5)).map(helper::absolutePos)
            val until = System.currentTimeMillis() + 600_000
            helper.assertEquals(1, BlockRestorer.place(level, listOf(glass), TrapShapes.cageBlock, until, player, null).getOrThrow(), "glass set")
            helper.assertEquals(2, BlockRestorer.place(level, webs, TrapShapes.webBlock, until, player, null).getOrThrow(), "cobwebs set")

            breakWith(breaker, glass, silkTouchPickaxe(helper))
            breakWith(breaker, webs[0], ItemStack(Items.SHEARS))
            breakWith(breaker, webs[1], ItemStack(Items.IRON_SWORD))
            helper.assertTrue((webs + glass).all { level.getBlockState(it).isAir }, "the three are broken")
            helper.assertEquals(emptyList<ItemEntity>(), helper.getEntities(EntityTypes.ITEM), "nothing dropped")
            helper.assertEquals(emptyList<ActiveEffect>(), BlockRestorer.activeOn(player), "forgotten")

            helper.setBlock(5, 2, 5, Blocks.COBWEB)
            breakWith(breaker, helper.absolutePos(BlockPos(5, 2, 5)), ItemStack(Items.SHEARS))
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM).any { it.item.`is`(Items.COBWEB) }, "an ordinary cobweb drops")
            helper.killAllEntitiesOfClass(ItemEntity::class.java)
        } finally {
            breaker.close()
        }
        helper.succeed()
    }

    /** A trap block already gone when its trap ends (here, gone within the tick) is said gone, not changed (M4 review, finding 39). */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aTrapBlockAlreadyGoneIsSaidGone(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val player = UUID.randomUUID()
        val pane = BlockPos(2, 2, 2)
        helper.assertEquals(1, BlockRestorer.place(helper.level, listOf(helper.absolutePos(pane)), TrapShapes.cageBlock, System.currentTimeMillis() + 600_000, player, null).getOrThrow(), "set")
        helper.setBlock(pane, Blocks.AIR)
        helper.assertEquals(0, BlockRestorer.restoreFor(player), "nothing to take away")
        val reasons = skippedReasons(runtime, player.toString())
        helper.awaitAsync { helper.assertEquals(listOf("1 block already gone (lifted)"), reasons.get(), "the decision") }
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

        /** What runs at the start of a level's next tick, once: [atNextLevelTick]. */
        private val nextLevelTick = ConcurrentLinkedQueue<Pair<ServerLevel, () -> Unit>>()
        private val levelTickHooked = AtomicBoolean()

        /**
         * Runs [task] at the start of [level]'s next tick, before its block events, once. Called from a test, which runs
         * after the levels' ticks: the block events queued by the test run in that same next tick, after [task]. The hook,
         * registered once and never taken off, never throws into the server's tick: [task] reports what it did itself.
         */
        private fun atNextLevelTick(level: ServerLevel, task: () -> Unit) {
            if (levelTickHooked.compareAndSet(false, true)) {
                ServerTickEvents.START_LEVEL_TICK.register { ticking ->
                    val due = nextLevelTick.filter { it.first === ticking }
                    nextLevelTick.removeAll(due.toSet())
                    for ((_, run) in due) runCatching(run).onFailure { Log.of("GameTest").error("a task of a trap test at the start of a level tick failed", it) }
                }
            }
            nextLevelTick += level to task
        }

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

        /** [player] breaks the block at [pos] (absolute) with [tool] in hand, as the game does once mining is done. */
        private fun breakWith(player: TestPlayer, pos: BlockPos, tool: ItemStack) {
            player.live.setItemInHand(InteractionHand.MAIN_HAND, tool)
            player.live.gameMode.destroyBlock(pos)
        }

        private fun silkTouchPickaxe(helper: GameTestHelper): ItemStack {
            val silkTouch = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH)
            return ItemStack(Items.DIAMOND_PICKAXE).apply { enchant(silkTouch, 1) }
        }

        /** The reasons of [subject]'s `trap skipped` decisions, read from the Journal once it has written them (or why the read failed). */
        private fun skippedReasons(runtime: NarratorRuntime, subject: String): AtomicReference<Any> {
            val reasons = AtomicReference<Any>()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runtime.journal.barrier()
                    reasons.set(
                        runtime.journal.read(5_000) { connection ->
                            connection.prepareStatement(
                                "SELECT reason FROM engine_decisions WHERE kind = 'guard' AND decision = 'trap skipped' AND subject = ? ORDER BY id",
                            ).use { statement ->
                                statement.setString(1, subject)
                                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                            }
                        },
                    )
                } catch (e: Exception) {
                    reasons.set("read failed: $e")
                }
            }
            return reasons
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
