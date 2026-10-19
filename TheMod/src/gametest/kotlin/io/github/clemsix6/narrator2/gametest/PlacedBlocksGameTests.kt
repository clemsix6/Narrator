package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.observer.ChunkPlacements
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the index of the blocks placed by players (M4 §9.3), one per cause of change: what
 * replaces a placed block (an explosion, fire, a tree grown from a sapling, a piston) makes its entry
 * stale, removed when read; oxidized and waxed copper is still the block placed; a player's placement
 * and break go through the index; an entry written before M4 is still read.
 */
class PlacedBlocksGameTests {

    @GameTest
    fun anExplodedBlockIsNoLongerPlaced(helper: GameTestHelper) {
        val builder = UUID.randomUUID()
        val blown = BlockPos(3, 1, 3)
        val spared = BlockPos(7, 1, 7)
        placeAs(helper, blown, PLANKS, builder)
        placeAs(helper, spared, PLANKS, builder)

        val at = helper.absolutePos(blown)
        helper.level.explode(null, at.x + 0.5, at.y + 0.5, at.z + 0.5, 2f, Level.ExplosionInteraction.TNT)
        helper.killAllEntitiesOfClass(ItemEntity::class.java)
        helper.assertTrue(helper.getBlockState(blown).isAir, "the planks are blown up")
        helper.assertEquals(null, placerAt(helper, blown), "no longer placed")
        helper.assertFalse(hasEntry(helper, blown), "the stale entry is removed")
        helper.assertEquals(builder, placerAt(helper, spared), "the planks out of reach are still placed")
        helper.succeed()
    }

    /** Fire burns only near a player (game rule `fire_spread_radius_around_player`): one stands by. */
    @GameTest(maxTicks = FIRE_TICKS)
    fun aBurntBlockIsNoLongerPlaced(helper: GameTestHelper) {
        val witness = TestPlayer(helper)
        helper.closingOnFailure(witness, FIRE_TICKS) {
            witness.standAt(BlockPos(6, 1, 6))
            val wool = BlockPos(2, 1, 2)
            val fire = wool.above()
            val builder = UUID.randomUUID()
            helper.setBlock(wool.below(), Blocks.STONE)
            placeAs(helper, wool, Blocks.WOOL.white().defaultBlockState(), builder)
            helper.setBlock(fire, Blocks.FIRE)
            helper.succeedWhen {
                if (helper.getBlockState(wool).`is`(Blocks.WOOL.white())) {
                    if (helper.getBlockState(fire).isAir) helper.setBlock(fire, Blocks.FIRE) // put out by rain: lit again
                    helper.assertTrue(false, "the wool is not burnt yet")
                }
                helper.assertEquals(null, placerAt(helper, wool), "burnt: no longer placed")
                helper.assertFalse(hasEntry(helper, wool), "the stale entry is removed")
                putOut(helper)
                witness.close()
            }
        }
    }

    @GameTest
    fun aPushedBlockIsNoLongerPlaced(helper: GameTestHelper) {
        val builder = UUID.randomUUID()
        val piston = BlockPos(1, 1, 3)
        val block = piston.east()
        val pushedTo = block.east()
        helper.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST))
        placeAs(helper, block, PLANKS, builder)
        helper.setBlock(piston.west(), Blocks.REDSTONE_BLOCK)
        helper.succeedWhen {
            helper.assertTrue(helper.getBlockState(pushedTo).`is`(Blocks.OAK_PLANKS), "the planks are pushed")
            helper.assertTrue(helper.getBlockState(block).`is`(Blocks.PISTON_HEAD), "the piston's head took their place")
            helper.assertEquals(null, placerAt(helper, block), "where they were: the piston's head")
            helper.assertFalse(hasEntry(helper, block), "the stale entry is removed")
            helper.assertEquals(null, placerAt(helper, pushedTo), "where they are: nobody placed them there")
        }
    }

    /** A real placement by a player: the index keeps the sapling, until it grows into a tree nobody placed. */
    @GameTest(structure = TALL_ROOM)
    fun aSaplingPlacedByAPlayerIsPlacedUntilItGrowsIntoATree(helper: GameTestHelper) {
        val gardener = TestPlayer(helper)
        try {
            gardener.standAt(BlockPos(2, 1, 2))
            val soil = BlockPos(8, 0, 8)
            val sapling = soil.above()
            helper.setBlock(soil, Blocks.DIRT)
            val player = gardener.live
            // The placement reads the stack in the player's hand, as in the game.
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BIRCH_SAPLING))
            helper.placeAt(player, player.mainHandItem, soil, Direction.UP)
            helper.assertTrue(helper.getBlockState(sapling).`is`(Blocks.BIRCH_SAPLING), "planted")
            helper.assertEquals(gardener.uuid, placerAt(helper, sapling), "placed by the player")
            helper.assertEquals(Blocks.BIRCH_SAPLING, blockAt(helper, sapling), "the block placed is kept")

            val grown = helper.getBlockState(sapling).setValue(SaplingBlock.STAGE, 1)
            (Blocks.BIRCH_SAPLING as SaplingBlock).advanceTree(helper.level, helper.absolutePos(sapling), grown, helper.level.random)
            helper.assertTrue(helper.getBlockState(sapling).`is`(Blocks.BIRCH_LOG), "a birch grew")
            helper.assertEquals(null, placerAt(helper, sapling), "the tree is not placed")
            helper.assertFalse(hasEntry(helper, sapling), "the stale entry is removed")
        } finally {
            gardener.close()
        }
        helper.succeed()
    }

    /**
     * A player breaks the builder's copper, oxidized and waxed since (still the block placed), and stone
     * that replaced the builder's planks (not placed): only the first names the builder.
     */
    @GameTest
    fun aBlockBrokenByAPlayerNamesItsPlacerOnlyWhileItIsTheBlockPlaced(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val owner = "placed-blocks-test-${UUID.randomUUID()}"
        val broken = CopyOnWriteArrayList<BlockBroken>()
        runtime.bus.subscribeSync("recorder", owner, { it is BlockBroken }) { broken += it.event as BlockBroken }
        val breaker = TestPlayer(helper)
        try {
            breaker.standAt(BlockPos(1, 1, 1))
            val builder = UUID.randomUUID()
            val copper = BlockPos(3, 1, 3)
            val replaced = BlockPos(5, 1, 5)
            placeAs(helper, copper, Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState(), builder)
            helper.setBlock(copper, waxed(oxidized(helper.getBlockState(copper))))
            placeAs(helper, replaced, PLANKS, builder)
            helper.setBlock(replaced, Blocks.STONE) // an enderman, a mod or a piston put stone there
            helper.assertEquals(builder, placerAt(helper, copper), "oxidized and waxed: still placed")

            for (rel in listOf(copper, replaced)) {
                helper.assertTrue(breaker.live.gameMode.destroyBlock(helper.absolutePos(rel)), "the player breaks $rel")
            }
            val mine = broken.filter { it.player.uuid == breaker.uuid }
            helper.assertValueEqual(2, mine.size, "breaks")
            helper.assertEquals(builder, mine.single { it.block == "minecraft:waxed_oxidized_copper" }.placedBy, "the builder's copper")
            helper.assertEquals(null, mine.single { it.block == "minecraft:stone" }.placedBy, "stone nobody placed")
            for (rel in listOf(copper, replaced)) helper.assertFalse(hasEntry(helper, rel), "forgotten: $rel")
        } finally {
            runtime.bus.unsubscribeOwner(owner)
            breaker.close()
        }
        helper.succeed()
    }

    /** Dev worlds of M1 to M3 saved no block: such an entry keeps the old rule (anything but air, a liquid or fire). */
    @GameTest
    fun anEntryFromBeforeM4IsStillRead(helper: GameTestHelper) {
        val builder = UUID.randomUUID()
        val stone = BlockPos(1, 1, 1)
        val air = BlockPos(3, 1, 1)
        val water = BlockPos(5, 1, 5)
        helper.setBlock(stone, Blocks.STONE)
        for (rel in listOf(water.below(), water.north(), water.south(), water.east(), water.west())) helper.setBlock(rel, Blocks.STONE)
        helper.setBlock(water, Blocks.WATER) // a basin: it cannot flow
        for (rel in listOf(stone, air, water)) markAsBeforeM4(helper, rel, builder)

        helper.assertEquals(null, blockAt(helper, stone), "the block placed is unknown")
        helper.assertEquals(builder, placerAt(helper, stone), "a block: still placed")
        helper.assertEquals(null, placerAt(helper, air), "air: not placed")
        helper.assertEquals(null, placerAt(helper, water), "water: not placed")
        helper.assertTrue(hasEntry(helper, stone), "kept")
        helper.assertFalse(hasEntry(helper, air) || hasEntry(helper, water), "the stale entries are removed")
        helper.succeed()
    }

    private companion object {
        /** Ticks run back to back on the test server: fire has thousands of chances in a few seconds. */
        const val FIRE_TICKS = 4_000

        /** The Oracle's empty 16³ room (T13): room for a tree. */
        const val TALL_ROOM = "narrator2-gametest:oracle_room"

        val PLANKS: BlockState = Blocks.OAK_PLANKS.defaultBlockState()

        /** Puts [state] at [rel], as if [placer] had placed it. */
        fun placeAs(helper: GameTestHelper, rel: BlockPos, state: BlockState, placer: UUID) {
            helper.setBlock(rel, state)
            PlacedBlocks.mark(helper.level, helper.absolutePos(rel), state, placer)
        }

        fun placerAt(helper: GameTestHelper, rel: BlockPos): UUID? = PlacedBlocks.placerAt(helper.level, helper.absolutePos(rel))

        /** The block the index keeps at [rel], however it changed since. */
        fun blockAt(helper: GameTestHelper, rel: BlockPos): Block? = withEntries(helper, rel) { placements, packed -> placements.blockAt(packed) }

        /** Whether the index has an entry at [rel], stale or not (without the check of [PlacedBlocks.placerAt]). */
        fun hasEntry(helper: GameTestHelper, rel: BlockPos): Boolean = withEntries(helper, rel) { placements, packed ->
            var found = false
            placements.forEach { position, _, _ -> if (position == packed) found = true }
            found
        } ?: false

        fun <T> withEntries(helper: GameTestHelper, rel: BlockPos, read: (ChunkPlacements, Int) -> T): T? {
            val pos = helper.absolutePos(rel)
            val chunk = helper.level.getChunkAt(pos)
            return chunk.getAttached(PlacedBlocks.TYPE)?.let { read(it, PlacedBlocks.pack(chunk, pos)) }
        }

        /** Adds an entry at [rel] written as M1 to M3 saved it (no block), read back by the codec, to its chunk's index. */
        fun markAsBeforeM4(helper: GameTestHelper, rel: BlockPos, placer: UUID) {
            val pos = helper.absolutePos(rel)
            val chunk = helper.level.getChunkAt(pos)
            val saved = CompoundTag().apply {
                put("placers", UUIDUtil.CODEC.listOf().encodeStart(NbtOps.INSTANCE, listOf(placer)).getOrThrow())
                putIntArray("positions", intArrayOf(PlacedBlocks.pack(chunk, pos)))
                putIntArray("owners", intArrayOf(0))
            }
            val old = ChunkPlacements.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow()
            // Copied into the live index: other tests may have entries in this chunk.
            val live = chunk.getAttached(PlacedBlocks.TYPE) ?: ChunkPlacements().also { chunk.setAttached(PlacedBlocks.TYPE, it) }
            old.forEach { packed, owner, block -> live.put(packed, old.placers[owner], block) }
            chunk.markUnsaved()
        }

        /** [state] fully oxidized, one step after the other, as random ticks do. */
        fun oxidized(state: BlockState): BlockState {
            var now = state
            while (true) now = (now.block as WeatheringCopper).getNext(now).orElse(null) ?: return now
        }

        /** [state] waxed, as a player does with honeycomb. */
        fun waxed(state: BlockState): BlockState = HoneycombItem.getWaxed(state).orElseThrow()

        /** Puts out the fire left in the test's area. */
        fun putOut(helper: GameTestHelper) {
            for (rel in BlockPos.betweenClosed(BlockPos(0, 0, 0), BlockPos(7, 7, 7))) {
                if (helper.getBlockState(rel).`is`(Blocks.FIRE)) helper.setBlock(rel.immutable(), Blocks.AIR)
            }
        }
    }
}
