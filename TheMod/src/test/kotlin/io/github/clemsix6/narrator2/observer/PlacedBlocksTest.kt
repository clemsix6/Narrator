package io.github.clemsix6.narrator2.observer

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.SharedConstants
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The index of the blocks placed by players (M4 §9.3): an entry answers only while its block is still there. */
class PlacedBlocksTest {
    private val bartok = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val mimosa = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val gone = UUID.fromString("00000000-0000-4000-8000-0000000000c0")

    private val here = 0x1_2_3
    private val there = 0x40_F_F

    private val Block.state: BlockState get() = defaultBlockState()

    private fun placed(block: Block?): ChunkPlacements = ChunkPlacements().apply { put(here, bartok, block) }

    private fun stillPlaced(placed: Block, now: Block): Boolean = PlacedBlocks.stillPlaced(placed, now.state)

    @Test
    fun `an entry answers while its block is still there, and is removed once the block is replaced`() {
        val placements = placed(Blocks.OAK_PLANKS)
        assertEquals(bartok, placements.placerAt(here, Blocks.OAK_PLANKS.state))
        assertEquals(Blocks.OAK_PLANKS, placements.blockAt(here))
        assertEquals(1, placements.size, "a valid entry stays")

        assertNull(placements.placerAt(here, Blocks.AIR.state), "blown up, burnt, pushed away: air now")
        assertEquals(0, placements.size, "the stale entry is removed")
        assertNull(placements.placerAt(here, Blocks.OAK_PLANKS.state), "even if the same block comes back")

        val sapling = placed(Blocks.OAK_SAPLING)
        assertNull(sapling.placerAt(here, Blocks.OAK_LOG.state), "a tree grown from a placed sapling is not placed")
        assertEquals(0, sapling.size)
    }

    @Test
    fun `a block that changes its state stays placed`() {
        val door = placed(Blocks.OAK_DOOR)
        val open = Blocks.OAK_DOOR.state.setValue(DoorBlock.OPEN, true)
        assertEquals(bartok, door.placerAt(here, open), "an open door is the same block")
    }

    @Test
    fun `copper stays placed as it oxidizes, is waxed or scraped`() {
        val copper = Blocks.COPPER_BLOCK
        for (now in copper.asList()) {
            assertTrue(stillPlaced(copper.weathering().unaffected(), now), "copper_block, then $now")
            assertTrue(stillPlaced(copper.waxed().weathered(), now), "waxed_weathered_copper, then $now")
        }
        assertEquals(copper.weathering().unaffected(), WeatheringCopper.getFirst(copper.weathering().oxidized()))
        for (now in Blocks.LIGHTNING_ROD.asList()) assertTrue(stillPlaced(Blocks.LIGHTNING_ROD.weathering().unaffected(), now), "lightning rod, then $now")
        assertTrue(!stillPlaced(copper.weathering().unaffected(), Blocks.CUT_COPPER.weathering().unaffected()), "cut copper is another block")
        assertTrue(!stillPlaced(copper.weathering().unaffected(), Blocks.STONE), "stone is another block")
    }

    @Test
    fun `dirt stays placed as grass covers it, a hoe tills it or a shovel flattens it`() {
        val dirts = listOf(
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.FARMLAND, Blocks.DIRT_PATH,
        )
        for (placed in dirts) for (now in dirts) assertTrue(stillPlaced(placed, now), "$placed, then $now")
        assertTrue(!stillPlaced(Blocks.DIRT, Blocks.MUD), "mud is another block")
        assertTrue(!stillPlaced(Blocks.GRASS_BLOCK, Blocks.AIR))
    }

    @Test
    fun `cauldrons, anvils, concrete and stripped wood keep their family`() {
        for (now in listOf(Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON, Blocks.POWDER_SNOW_CAULDRON)) {
            assertTrue(stillPlaced(Blocks.CAULDRON, now), "cauldron, then $now")
        }
        var anvil = Blocks.ANVIL.state
        while (true) {
            assertTrue(PlacedBlocks.stillPlaced(Blocks.ANVIL, anvil), "anvil, then $anvil")
            anvil = AnvilBlock.damage(anvil) ?: break
        }
        assertTrue(stillPlaced(Blocks.CONCRETE_POWDER.white(), Blocks.CONCRETE.white()), "powder set by water")
        assertTrue(!stillPlaced(Blocks.CONCRETE_POWDER.white(), Blocks.CONCRETE.red()), "another colour")
        assertTrue(stillPlaced(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG), "an axe strips a log")
        assertTrue(stillPlaced(Blocks.STRIPPED_CHERRY_WOOD, Blocks.CHERRY_WOOD))
        assertTrue(!stillPlaced(Blocks.OAK_LOG, Blocks.STRIPPED_BIRCH_LOG), "another wood")
        assertTrue(!stillPlaced(Blocks.OAK_LOG, Blocks.OAK_WOOD), "a log is not a wood block")
    }

    @Test
    fun `an entry without block, written before M4, keeps the old rule`() {
        val placements = placed(null)
        assertNull(placements.blockAt(here))
        assertEquals(bartok, placements.placerAt(here, Blocks.STONE.state), "any block")
        for (now in listOf(Blocks.WATER, Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.AIR, Blocks.CAVE_AIR)) {
            assertNull(placed(null).placerAt(here, now.state), "$now is no block of a player")
        }
    }

    @Test
    fun `unmark compares the entry to the block broken, not to the world`() {
        val planks = placed(Blocks.OAK_PLANKS)
        assertEquals(bartok, planks.take(here, Blocks.OAK_PLANKS.state), "the planks placed")
        assertEquals(0, planks.size, "forgotten")
        assertNull(planks.take(here, Blocks.OAK_PLANKS.state), "nothing left to take")

        val copper = placed(Blocks.COPPER_BLOCK.weathering().unaffected())
        assertEquals(bartok, copper.take(here, Blocks.COPPER_BLOCK.waxed().oxidized().state), "the same copper, aged and waxed")

        val replaced = placed(Blocks.OAK_PLANKS)
        assertNull(replaced.take(here, Blocks.STONE.state), "an enderman put stone there: not Bartok's")
        assertEquals(0, replaced.size, "the stale entry goes too")
    }

    @Test
    fun `a new mark replaces the entry and says whether it changed`() {
        val placements = ChunkPlacements()
        assertTrue(placements.put(here, bartok, Blocks.STONE), "new")
        assertTrue(!placements.put(here, bartok, Blocks.STONE), "the same mark again")
        assertTrue(placements.put(here, mimosa, Blocks.STONE), "another placer")
        assertTrue(placements.put(here, mimosa, Blocks.GRANITE), "another block")
        assertEquals(mimosa, placements.placerAt(here, Blocks.GRANITE.state))
        assertEquals(1, placements.size)
    }

    @Test
    fun `a chunk saved before M4 reads back, its blocks unknown`() {
        val old = JsonObject().apply {
            add("placers", UUIDUtil.CODEC.listOf().encodeStart(JsonOps.INSTANCE, listOf(gone, bartok)).getOrThrow())
            add("positions", JsonArray().apply { add(here); add(there) })
            add("owners", JsonArray().apply { add(1); add(1) })
        }
        val placements = ChunkPlacements.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow()
        assertEquals(2, placements.size)
        assertNull(placements.blockAt(here))
        assertEquals(bartok, placements.placerAt(here, Blocks.COBBLESTONE.state), "the old rule")
        assertNull(placements.placerAt(there, Blocks.AIR.state), "the old rule: air is not placed")

        val saved = ChunkPlacements.CODEC.encodeStart(JsonOps.INSTANCE, placements).getOrThrow().asJsonObject
        assertEquals(1, saved.getAsJsonArray("placers").size(), "the placer without positions is dropped")
        assertEquals(-1, saved.getAsJsonArray("kinds")[0].asInt, "still unknown")
    }

    @Test
    fun `the blocks go to the chunk and back, both palettes compacted`() {
        val placements = ChunkPlacements().apply {
            put(here, gone, Blocks.DIRT)
            put(here, mimosa, Blocks.OAK_PLANKS) // gone and dirt have no position left
            put(there, bartok, Blocks.STONE)
            put(0x7F_0_0, mimosa, null)
            put(0x5, bartok, Blocks.OAK_PLANKS)
        }
        val nbt = ChunkPlacements.CODEC.encodeStart(NbtOps.INSTANCE, placements).getOrThrow()
        val restored = ChunkPlacements.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow()
        assertEquals(listOf(mimosa, bartok), restored.placers.toList(), "placers without positions are dropped, in order")
        assertEquals(listOf(Blocks.OAK_PLANKS, Blocks.STONE), restored.blocks.toList(), "blocks without positions are dropped, in order")
        assertEquals(4, restored.size)
        assertEquals(Blocks.OAK_PLANKS, restored.blockAt(here))
        assertEquals(Blocks.STONE, restored.blockAt(there))
        assertNull(restored.blockAt(0x7F_0_0), "unknown stays unknown")
        assertEquals(mimosa, restored.placerAt(here, Blocks.OAK_PLANKS.state))
        assertEquals(bartok, restored.placerAt(there, Blocks.STONE.state))
        assertEquals(mimosa, restored.placerAt(0x7F_0_0, Blocks.GLASS.state))
        assertEquals(bartok, restored.placerAt(0x5, Blocks.OAK_PLANKS.state))

        val json = ChunkPlacements.CODEC.encodeStart(JsonOps.INSTANCE, placements).getOrThrow().asJsonObject
        assertEquals(listOf("minecraft:oak_planks", "minecraft:stone"), json.getAsJsonArray("blocks").map { it.asString })
    }

    @Test
    fun `a block the game no longer knows reads as unknown, broken arrays are refused`() {
        fun parse(json: String) = ChunkPlacements.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
        val placers = UUIDUtil.CODEC.listOf().encodeStart(JsonOps.INSTANCE, listOf(bartok)).getOrThrow()

        val removed = parse("""{"placers":$placers,"positions":[1,2],"owners":[0,0],"blocks":["narrator2:gone","not an id!","minecraft:stone"],"kinds":[0,2]}""").getOrThrow()
        assertNull(removed.blockAt(1), "a block removed from the game")
        assertEquals(Blocks.STONE, removed.blockAt(2))
        assertEquals(listOf(Blocks.STONE), removed.blocks.toList())

        assertTrue(parse("""{"placers":$placers,"positions":[1,2],"owners":[0]}""").isError, "owners")
        assertTrue(parse("""{"placers":$placers,"positions":[1,2],"owners":[0,1]}""").isError, "owner index")
        assertTrue(parse("""{"placers":$placers,"positions":[1,2],"owners":[0,0],"blocks":["minecraft:stone"],"kinds":[0]}""").isError, "kinds")
        assertTrue(parse("""{"placers":$placers,"positions":[1,2],"owners":[0,0],"blocks":["minecraft:stone"],"kinds":[0,1]}""").isError, "kind index")
    }

    companion object {
        /** Blocks and their families need the game's registries. */
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
