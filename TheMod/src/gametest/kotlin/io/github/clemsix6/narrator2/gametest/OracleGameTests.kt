package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.oracle.Enclosure
import io.github.clemsix6.narrator2.oracle.EnclosureProbe
import io.github.clemsix6.narrator2.oracle.OracleText
import io.github.clemsix6.narrator2.oracle.Ores
import io.github.clemsix6.narrator2.oracle.PlayerReport
import io.github.clemsix6.narrator2.oracle.PlayerSnapshot
import io.github.clemsix6.narrator2.oracle.StructureSearch
import io.github.clemsix6.narrator2.oracle.Structures
import io.github.clemsix6.narrator2.oracle.WorldReader
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.oracle.AreaInfoArgs
import io.github.clemsix6.narrator2.tool.oracle.AreaInfoTool
import io.github.clemsix6.narrator2.tool.oracle.InspectPlayerArgs
import io.github.clemsix6.narrator2.tool.oracle.InspectPlayerTool
import io.github.clemsix6.narrator2.tool.oracle.InspectSection
import io.github.clemsix6.narrator2.tool.oracle.ListPlayersTool
import io.github.clemsix6.narrator2.tool.oracle.LocateArgs
import io.github.clemsix6.narrator2.tool.oracle.LocateKind
import io.github.clemsix6.narrator2.tool.oracle.LocateTool
import io.github.clemsix6.narrator2.tool.oracle.ScanAreaArgs
import io.github.clemsix6.narrator2.tool.oracle.ScanAreaTool
import io.github.clemsix6.narrator2.tool.oracle.ScanFind
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.tags.BlockItemTags
import net.minecraft.tags.StructureTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.structure.BuiltinStructures
import net.minecraft.world.level.levelgen.structure.StructureStart
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the Oracle (M1_M2 §17.3): the cave heuristic on built cases (open sky, a tree, a
 * roof, a 1×2 tunnel, a cavern, a room built by a player, the Nether), the tools' answers on built
 * areas and players, structures read from the loaded chunks, a small `locate`, and what each reading
 * costs the server thread (logged as `[Tool] oracle cost: …`, and `[Tool] oracle <tool> (…)` by the tools).
 *
 * The built cases have their own empty structures (`oracle_room`, 16³, and `oracle_field`, 32×24×32,
 * under `data/narrator2-gametest/gametest/structure/`) and sky access: without it, the test is roofed
 * with barriers, which would be the surface of every column. Tests run at y = 4, far above the flat
 * ground, and players are named (`oracle_…`), since the helper's mock players all share one name.
 */
class OracleGameTests {

    // ── Enclosure ────────────────────────────────────────────────────────────────────────────────────

    /** Open sky over one spot; three layers of leaves high over another: sheltered, by the sky light. */
    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun openSkyAndATreeCanopy(helper: GameTestHelper) {
        val leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
        fill(helper, BlockPos(9, 8, 9), BlockPos(13, 10, 13), leaves)
        helper.startSequence()
            .thenAwait { expectEnclosure(helper, BlockPos(3, 1, 3), Enclosure.OPEN_SKY) }
            .thenAwait {
                val probe = expectEnclosure(helper, BlockPos(11, 1, 11), Enclosure.SHELTERED)
                helper.assertTrue(probe.depth!! > Enclosure.SHALLOW_DEPTH && probe.skyLight in Enclosure.OPEN_SKY_LIGHT until 15, "high leaves, sky light through: $probe")
            }
            .thenSucceed()
    }

    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun underARoofIsSheltered(helper: GameTestHelper) {
        fill(helper, BlockPos(4, 4, 4), BlockPos(10, 4, 10), Blocks.STONE.defaultBlockState())
        helper.startSequence()
            .thenAwait {
                val probe = expectEnclosure(helper, BlockPos(7, 1, 7), Enclosure.SHELTERED)
                helper.assertEquals(4, probe.depth, "a roof 3 blocks over the head")
            }
            .thenSucceed()
    }

    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aTunnelDeepInTheRockIsUnderground(helper: GameTestHelper) {
        rockWithTunnel(helper, Blocks.STONE.defaultBlockState())
        helper.startSequence()
            .thenAwait {
                val probe = expectEnclosure(helper, TUNNEL, Enclosure.UNDERGROUND)
                helper.assertTrue(probe.depth == 9 && probe.skyLight == 0, "9 blocks of rock over the feet, no sky light: $probe")
                helper.assertTrue(probe.volume == 24 && probe.tallCells == 0, "a 1x2 tunnel, 12 long: $probe")
            }
            .thenSucceed()
    }

    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aCavernIsACave(helper: GameTestHelper) {
        rockWithCavern(helper, Blocks.STONE.defaultBlockState())
        helper.startSequence()
            .thenAwait {
                val probe = expectEnclosure(helper, CAVERN, Enclosure.CAVE)
                helper.assertTrue(probe.volume == Enclosure.MAX_VOLUME && probe.builtShare == 0.0, "a large natural room: $probe")
            }
            .thenSucceed()
    }

    /** The same room, its walls placed by a player: a windowless hall, not a cave. */
    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aRoomBuiltByAPlayerIsNoCave(helper: GameTestHelper) {
        rockWithCavern(helper, Blocks.STONE.defaultBlockState())
        val builder = UUID.randomUUID()
        for (rel in BlockPos.betweenClosed(BlockPos(2, 1, 2), BlockPos(13, 11, 13))) {
            val pos = helper.absolutePos(rel)
            val state = helper.level.getBlockState(pos)
            if (!state.isAir) PlacedBlocks.mark(helper.level, pos, state, builder)
        }
        helper.startSequence()
            .thenAwait {
                val probe = expectEnclosure(helper, CAVERN, Enclosure.SHELTERED)
                helper.assertTrue(probe.builtShare >= Enclosure.BUILT_SHARE && probe.depth!! <= Enclosure.BUILDING_DEPTH, "built walls: $probe")
            }
            .thenSucceed()
    }

    /** Under the Nether's ceiling: a tunnel is enclosed, a large room open; neither is a cave. */
    @GameTest(dimension = "minecraft:the_nether", structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun theNetherHasNoCaves(helper: GameTestHelper) {
        val rock = Blocks.NETHERRACK.defaultBlockState()
        fill(helper, BlockPos(1, 0, 1), BlockPos(14, 13, 14), rock)
        fill(helper, BlockPos(2, 2, 3), BlockPos(13, 3, 3), Blocks.AIR.defaultBlockState())
        fill(helper, BlockPos(3, 6, 6), BlockPos(12, 12, 12), Blocks.AIR.defaultBlockState())
        helper.startSequence()
            .thenAwait {
                helper.assertValueEqual("minecraft:the_nether", helper.level.dimension().identifier().toString(), "dimension")
                expectEnclosure(helper, BlockPos(7, 2, 3), Enclosure.ENCLOSED)
                expectEnclosure(helper, BlockPos(7, 6, 9), Enclosure.OPEN)
            }
            .thenSucceed()
    }

    // ── Tools ────────────────────────────────────────────────────────────────────────────────────────

    /** A player in a tunnel with a zombie, a pickaxe and an effect: what list_players and inspect_player say. */
    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun listAndInspectDescribeAPlayerInATunnel(helper: GameTestHelper) {
        rockWithTunnel(helper, Blocks.STONE.defaultBlockState())
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(11, 5, 7))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { zombie.discard() }
        val named = NamedPlayer(helper, helper.absolutePos(TUNNEL), yRot = EAST)
        val player = named.player
        val efficiency = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY)
        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.DIAMOND_PICKAXE).apply { enchant(efficiency, 5) })
        player.inventory.add(ItemStack(Items.COBBLESTONE, 64))
        player.addEffect(MobEffectInstance(MobEffects.SPEED, 20 * 90, 1))
        val list = AtomicReference<CompletableFuture<ToolResult>>()
        val inspect = AtomicReference<CompletableFuture<ToolResult>>()
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }
        helper.startSequence()
            .thenAwait { expectEnclosure(helper, TUNNEL, Enclosure.UNDERGROUND) }
            .thenExecute {
                list.set(helper.call(ListPlayersTool(), NoArgs))
                inspect.set(helper.call(InspectPlayerTool(), InspectPlayerArgs(named.name, InspectSection.entries)))
            }
            .thenAwait { helper.assertTrue(list.get().isDone && inspect.get().isDone, "the tools answer") }
            .thenExecute {
                val listed = ok(helper, list.get())
                helper.assertTrue(Regex("""day \d+ · (day|dusk|night|dawn) \d\d:\d\d · (clear|rain|thunder) · \d+ players? online""").matches(listed.lines()[0]), "world line: ${listed.lines()[0]}")
                val line = listed.lines().single { it.startsWith("${named.name}: ") }
                Log.tool.info("oracle sample list_players: $line")
                val feet = helper.absolutePos(TUNNEL)
                val biome = OracleText.bare(helper.level.getBiome(feet).registeredName)
                expectContains(helper, line, "${named.name}: overworld ${feet.x} ${feet.y} ${feet.z} · $biome · underground, 9 below surface · hp 20/20 food 20")
                expectContains(helper, line, "· online ")

                val inspected = ok(helper, inspect.get())
                Log.tool.info("oracle sample inspect_player:\n$inspected")
                val lines = inspected.lines()
                helper.assertTrue(lines[0].startsWith("${named.name} · ") && "hp 20/20" in lines[0] && "xp level 0" in lines[0], "header: ${lines[0]}")
                expectContains(helper, inspected, "position: overworld ${feet.x} ${feet.y} ${feet.z} · biome $biome · underground, 9 below surface · surface y=${feet.y + 9} · light sky 0")
                expectContains(helper, inspected, "facing E; looking at stone 7 m E")
                // Other tests run side by side: their creatures and players may be within 24 blocks too.
                val around = lines.single { it.startsWith("around (24 blocks): ") }
                helper.assertTrue(Regex("""(zombie|\d+ zombie \(nearest) 4 m E""").containsMatchIn(around), "the zombie 4 blocks east: $around")
                expectContains(helper, inspected, "equipment: mainhand diamond_pickaxe (efficiency V; 100%)")
                expectContains(helper, inspected, "64 cobblestone")
                expectContains(helper, inspected, "effects: speed II 1:")
                expectContains(helper, inspected, "yours on them: none")
                expectContains(helper, inspected, "recent (Journal):")
                zombie.discard()
                named.close()
            }
            .thenSucceed()
    }

    /** Ores, containers, a spawner, lava, a bed that is a respawn point, a portal and a base, around a point. */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun scanAreaFindsWhatIsAround(helper: GameTestHelper) {
        val level = helper.level
        val named = NamedPlayer(helper, helper.absolutePos(BlockPos(28, 1, 28)))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }
        val stone = Blocks.STONE.defaultBlockState()
        set(helper, BlockPos(18, 4, 16), Blocks.DIAMOND_ORE.defaultBlockState())
        set(helper, BlockPos(16, 4, 24), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState())
        set(helper, BlockPos(10, 4, 16), Blocks.IRON_ORE.defaultBlockState())
        set(helper, BlockPos(16, 4, 12), Blocks.CHEST.defaultBlockState())
        (level.getBlockEntity(helper.absolutePos(BlockPos(16, 4, 12))) as ChestBlockEntity).apply {
            setItem(0, ItemStack(Items.DIAMOND, 5))
            setItem(1, ItemStack(Items.COBBLESTONE, 64))
        }
        set(helper, BlockPos(22, 4, 12), Blocks.CHEST.defaultBlockState())
        (level.getBlockEntity(helper.absolutePos(BlockPos(22, 4, 12))) as ChestBlockEntity).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
        set(helper, BlockPos(20, 4, 20), Blocks.BARREL.defaultBlockState())
        // A spawner of pigs: nothing can spawn from it on the desert's sand (a zombie spawner would feed the
        // tests next door with zombies as soon as one of their players came within 16 blocks).
        set(helper, SPAWNER, Blocks.SPAWNER.defaultBlockState())
        (level.getBlockEntity(helper.absolutePos(SPAWNER)) as SpawnerBlockEntity).setEntityId(EntityTypes.PIG, level.random)
        fill(helper, BlockPos(23, 3, 15), BlockPos(25, 4, 17), stone) // a basin: the lava cannot flow
        set(helper, BlockPos(24, 4, 16), Blocks.LAVA.defaultBlockState())
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { clearHazards(helper) }
        val bedHead = BlockPos(8, 4, 20)
        val bed = Blocks.BED.red().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
        set(helper, bedHead, bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD))
        set(helper, bedHead.south(), bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT))
        named.player.setRespawnPosition(ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), helper.absolutePos(bedHead), 0f, 0f), false), false)
        fill(helper, BlockPos(22, 4, 22), BlockPos(23, 6, 22), Blocks.NETHER_PORTAL.defaultBlockState())
        val planks = Blocks.OAK_PLANKS.defaultBlockState()
        for (rel in BlockPos.betweenClosed(BlockPos(10, 4, 18), BlockPos(15, 4, 22))) {
            set(helper, rel, planks)
            PlacedBlocks.mark(level, helper.absolutePos(rel), planks, named.player.uuid)
        }
        val center = helper.absolutePos(BlockPos(16, 4, 16))
        val scans = ScanFind.entries.associateWith { find ->
            helper.call(ScanAreaTool(), ScanAreaArgs(find, x = center.x, y = center.y, z = center.z, radius = 12))
        }
        val from = "within 12 blocks of overworld ${center.x} ${center.y} ${center.z}: "
        fun at(rel: BlockPos): String = helper.absolutePos(rel).let { "${it.x} ${it.y} ${it.z}" }
        helper.startSequence()
            .thenAwait { helper.assertTrue(scans.values.all { it.isDone }, "every scan answers") }
            .thenExecute {
                clearHazards(helper)
                val text = scans.mapValues { (_, future) -> ok(helper, future) }
                text.forEach { (find, answer) -> Log.tool.info("oracle sample scan_area ${find.name.lowercase()}:\n$answer") }
                expectLines(helper, text.getValue(ScanFind.DIAMONDS), "diamonds ${from}2", "diamond_ore ${at(BlockPos(18, 4, 16))} (2 m E)", "deepslate_diamond_ore ${at(BlockPos(16, 4, 24))} (8 m S)")
                expectContains(helper, text.getValue(ScanFind.ORES), "ores ${from}3 (")
                expectContains(helper, text.getValue(ScanFind.ORES), "iron_ore ${at(BlockPos(10, 4, 16))} (6 m W)")
                expectLines(
                    helper, text.getValue(ScanFind.CONTAINERS), "containers ${from}3",
                    "chest (2 stacks: 64 cobblestone, 5 diamond) ${at(BlockPos(16, 4, 12))} (4 m N)",
                    "barrel (empty) ${at(BlockPos(20, 4, 20))} (6 m SE)",
                    "chest (loot, never opened) ${at(BlockPos(22, 4, 12))} (7 m NE)",
                )
                expectLines(helper, text.getValue(ScanFind.SPAWNERS), "spawners ${from}1", "spawner (pig) ${at(SPAWNER)} (6 m NW)")
                expectLines(helper, text.getValue(ScanFind.LAVA), "lava ${from}1", "lava ${at(BlockPos(24, 4, 16))} (8 m E)")
                expectLines(helper, text.getValue(ScanFind.BEDS), "beds ${from}1", "red_bed (respawn point of ${named.name}) ${at(bedHead)} (9 m SW)")
                expectLines(helper, text.getValue(ScanFind.PORTALS), "portals ${from}1 portal", "nether_portal (6 blocks) ${at(BlockPos(22, 4, 22))} (8 m SE)")
                expectLines(helper, text.getValue(ScanFind.ANCIENT_DEBRIS), "ancient_debris ${from}none")
                val base = text.getValue(ScanFind.PLAYER_BLOCKS)
                expectContains(helper, base, "player blocks ${from}30 (${named.name} 30)\ndensest spots:\n")
                expectContains(helper, base, "blocks by ${named.name} (oak_planks)")
                named.close()
            }
            .thenSucceed()
    }

    /** Blocks by kind, the share placed by a player (and who), creatures and players, around a point. */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun areaInfoTellsTheShareOfPlayerBlocks(helper: GameTestHelper) {
        val named = NamedPlayer(helper, helper.absolutePos(BlockPos(18, 5, 15)))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }
        fill(helper, BlockPos(13, 4, 13), BlockPos(16, 4, 17), Blocks.STONE.defaultBlockState())
        val planks = Blocks.OAK_PLANKS.defaultBlockState()
        for (rel in BlockPos.betweenClosed(BlockPos(17, 4, 13), BlockPos(20, 4, 17))) {
            set(helper, rel, planks)
            PlacedBlocks.mark(helper.level, helper.absolutePos(rel), planks, named.player.uuid)
        }
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(14, 5, 15))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { cow.discard() }
        val center = helper.absolutePos(BlockPos(16, 6, 16))
        val answer = AtomicReference<CompletableFuture<ToolResult>>()
        helper.startSequence()
            .thenAwait { helper.assertTrue(helper.level.canSeeSky(center), "the light engine is done") }
            .thenExecute { answer.set(helper.call(AreaInfoTool(), AreaInfoArgs(x = center.x, y = center.y, z = center.z, radius = 6))) }
            .thenAwait { helper.assertTrue(answer.get().isDone, "area_info answers") }
            .thenExecute {
                val text = ok(helper, answer.get())
                Log.tool.info("oracle sample area_info:\n$text")
                expectLines(
                    helper, text,
                    "area within 6 blocks of overworld ${center.x} ${center.y} ${center.z}",
                    "biomes: ${OracleText.bare(helper.level.getBiome(center).registeredName)} (centre)",
                    "at the centre: open_sky, light sky 15, block 0",
                )
                expectContains(helper, text, "oak_planks 50%")
                expectContains(helper, text, "stone 50%")
                expectContains(helper, text, "creatures: creature 1 (cow 1); players: ${named.name}")
                expectContains(helper, text, "player blocks: 50% of the blocks (20: ${named.name} 20)")
                cow.discard()
                named.close()
            }
            .thenSucceed()
    }

    /** A structure start held by a loaded chunk: the player is in it, and it is seen from nearby. */
    @GameTest(structure = ROOM, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun structuresAreReadFromTheLoadedChunks(helper: GameTestHelper) {
        val level = helper.level
        val pos = helper.absolutePos(BlockPos(4, 1, 4))
        val treasure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.BURIED_TREASURE).value()
        val startChunk = ChunkPos.containing(pos)
        val chunk = level.getChunkAt(pos)
        chunk.setStartForStructure(treasure, StructureStart(treasure, startChunk, 0, PiecesContainer(listOf(BuriedTreasurePieces.BuriedTreasurePiece(pos)))))
        chunk.addReferenceForStructure(treasure, startChunk.pack())
        val named = NamedPlayer(helper, pos)
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }

        val reader = WorldReader(level)
        helper.assertEquals("minecraft:buried_treasure", Structures.at(reader, pos), "in the structure's piece")
        helper.assertEquals(null, Structures.at(reader, pos.above(5)), "above its only piece")
        val seen = Structures.nearby(reader, pos.east(10), 2).single { it.id == "minecraft:buried_treasure" }
        helper.assertTrue(seen.exact && seen.distance == 10, "seen from 10 blocks: $seen")
        helper.assertTrue("in buried_treasure" in PlayerSnapshot.capture(named.player).describe(), "the snapshot names it")

        val list = helper.call(ListPlayersTool(), NoArgs)
        helper.startSequence()
            .thenAwait { helper.assertTrue(list.isDone, "list_players answers") }
            .thenExecute {
                expectContains(helper, ok(helper, list).lines().single { it.startsWith("${named.name}: ") }, " · in buried_treasure · ")
                chunk.setStartForStructure(treasure, StructureStart.INVALID_START)
                named.close()
            }
            .thenSucceed()
    }

    /**
     * `locate` at a small radius, for a structure and biomes; the durations are logged by the tool. The test
     * world is flat, one biome, without structures (a world option): the structure search itself runs
     * here too, directly, and its cost is logged.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun locateAnswersQuicklyAtASmallRadius(helper: GameTestHelper) {
        val named = NamedPlayer(helper, helper.absolutePos(BlockPos(4, 1, 4)))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }
        val here = OracleText.bare(helper.level.getBiome(named.player.blockPosition()).registeredName)
        val calls = listOf(
            LocateArgs(named.name, LocateKind.STRUCTURE, "village", radius = 8),
            LocateArgs(named.name, LocateKind.BIOME, here, radius = 8),
            LocateArgs(named.name, LocateKind.BIOME, "cherry_grove", radius = 8),
            LocateArgs(named.name, LocateKind.STRUCTURE, "no_such_place", radius = 8),
        )
        val started = System.nanoTime()
        val answers = calls.map { helper.call(LocateTool(), it) }
        helper.startSequence()
            .thenAwait { helper.assertTrue(answers.all { it.isDone }, "locate answers") }
            .thenExecute {
                Log.tool.info("oracle cost: 4 locate calls answered in ${(System.nanoTime() - started) / 1_000_000} ms")
                val (village, _, absent, unknown) = answers.map { it.get() }
                calls.zip(answers).forEach { (args, answer) -> Log.tool.info("oracle sample locate ${args.target}: ${answer.get().text}") }
                helper.assertTrue(village.status == ToolStatus.OK, "village: ${village.text}")
                helper.assertTrue(
                    Regex("""nearest village_\w+ to ${named.name} .*|no #village within 8 cells of ${named.name} .*|#village does not generate in overworld|structures are not generated in this world""")
                        .matches(village.text),
                    "village: ${village.text}",
                )
                expectContains(helper, ok(helper, answers[1]), "nearest $here to ${named.name} (overworld ")
                helper.assertValueEqual(
                    "no cherry_grove within 1024 blocks of ${named.name} (overworld ${named.player.blockPosition().let { "${it.x} ${it.y} ${it.z}" }})",
                    absent.text, "a biome this world does not have",
                )
                helper.assertTrue(unknown.status == ToolStatus.INVALID && "unknown structure \"no_such_place\"" in unknown.text, "unknown: ${unknown.text}")

                // The search behind locate, without the world option that turns structures off.
                val villages = helper.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(StructureTags.VILLAGE).stream().toList()
                val search = StructureSearch(helper.level, villages, named.player.blockPosition(), 8)
                val deadline = System.nanoTime() + 2_000_000_000L
                var slices = 0
                val (_, searching) = timed {
                    while (!search.advance(4_000_000) && System.nanoTime() < deadline) slices++
                }
                Log.tool.info(
                    "oracle cost: structure search #village r8 in this world: impossible=${search.impossible}, done=${search.done}, " +
                        "${search.examined} places in ${slices + 1} slices, ${ms(searching)}; found ${search.best}",
                )
                // Again from 8 000 blocks away: the templates are loaded, the places are new.
                val again = StructureSearch(helper.level, villages, named.player.blockPosition().offset(8_000, 0, 8_000), 8)
                val (_, searchingAgain) = timed { while (!again.advance(4_000_000) && System.nanoTime() < deadline) Unit }
                Log.tool.info("oracle cost: the same search 8000 blocks away: ${again.examined} places, ${ms(searchingAgain)}; found ${again.best}")
                named.close()
            }
            .thenSucceed()
    }

    // ── Costs ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What the readings cost the server thread, warm (the median of 5 runs after one), on a worst case:
     * 32×16×32 blocks of deepslate a quarter of which are ores of every kind (every section holds a
     * wanted block), and a player in the open air above it. Logged; bounded loosely.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun oracleCostsOnTheServerThread(helper: GameTestHelper) {
        val level = helper.level
        val random = Random(42)
        val ores = listOf(
            Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        ).map { it.defaultBlockState() }
        val deepslate = Blocks.DEEPSLATE.defaultBlockState()
        // Written straight into the sections: fast, and the scan reads nothing else (no light, no heightmap).
        for (rel in BlockPos.betweenClosed(BlockPos(0, 0, 0), BlockPos(31, 15, 31))) {
            val pos = helper.absolutePos(rel)
            val state = if (random.nextInt(4) == 0) ores[random.nextInt(ores.size)] else deepslate
            level.getChunkAt(pos).getSection(level.getSectionIndex(pos.y)).setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state, false)
        }
        val named = NamedPlayer(helper, helper.absolutePos(BlockPos(16, 20, 16)))
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { named.close() }
        val center = helper.absolutePos(BlockPos(16, 8, 16))
        val reader = WorldReader(level)
        val isOre: (BlockState) -> Boolean = { it.`is`(BlockItemTags.ORES.block()) }
        val isDiamond: (BlockState) -> Boolean = { it.`is`(BlockItemTags.DIAMOND_ORES.block()) }

        val (copied48, copy48) = warm { Ores.copy(reader, center, 48, isOre) }
        val (found48, find48) = warm { Ores.find(copied48, center, 48, isOre, 8) }
        val (diamonds, diamondScan) = warm { Ores.find(Ores.copy(reader, center, 48, isDiamond), center, 48, isDiamond, 8) }
        val (copied32, copy32) = warm { Ores.copy(reader, center, 32, null) }
        val (_, histogram32) = warm { Ores.histogram(copied32, center, 32) }
        val air = named.player.blockPosition()
        val (openProbe, probeOpen) = warm { Enclosure.probe(reader, air, air.above()) }
        val (_, nearby) = warm { Structures.nearby(reader, center, 8) }
        val (_, snapshot) = warm { PlayerSnapshot.capture(named.player) }
        val report = PlayerReport(named.player)
        val (_, position) = warm { report.position() }
        val (_, surroundings) = warm { report.surroundings() }
        val (_, belongings) = warm { report.equipment() + report.inventory() + report.effects(emptyList(), 0) }
        val (_, world) = warm { WorldSnapshot.capture(level.server) }
        val sections = copied48.sections.size
        Log.tool.info(
            "oracle cost: ores r48: palette test of ${copied48.tested} sections and copy of $sections: ${ms(copy48)}; " +
                "scan of the copies: ${ms(find48)} (${found48.total} ores); diamonds r48, copy and scan: ${ms(diamondScan)} (${diamonds.total}); " +
                "all sections r32: copy of ${copied32.sections.size} ${ms(copy32)}, histogram ${ms(histogram32)}; " +
                "per full section: copy ${ms(copy48 / maxOf(1, sections))}, scan ${ms(find48 / maxOf(1, sections))}",
        )
        Log.tool.info(
            "oracle cost: enclosure flood of ${openProbe.volume} cells ${ms(probeOpen)}; structures nearby r8 chunks ${ms(nearby)}; " +
                "player snapshot ${ms(snapshot)}; inspect sections: position ${ms(position)}, surroundings ${ms(surroundings)}, " +
                "equipment + inventory + effects ${ms(belongings)}; world snapshot (${level.server.playerList.playerCount} players) ${ms(world)}",
        )
        helper.assertTrue(sections > 0 && found48.total > 0, "the ores are found: $sections sections, ${found48.total} ores")
        helper.assertTrue(copy48 < 100_000_000, "the server thread's part stays far below a tick: ${ms(copy48)}")

        val scan = helper.call(ScanAreaTool(), ScanAreaArgs(ScanFind.ORES, x = center.x, y = center.y, z = center.z, radius = 48))
        val area = helper.call(AreaInfoTool(), AreaInfoArgs(x = center.x, y = center.y, z = center.z, radius = 32))
        helper.startSequence()
            .thenAwait { helper.assertTrue(scan.isDone && area.isDone, "the tools answer") }
            .thenExecute {
                Log.tool.info("oracle sample scan_area ores r48 (worst case):\n${ok(helper, scan)}")
                Log.tool.info("oracle sample area_info r32 (worst case):\n${ok(helper, area)}")
                named.close()
            }
            .thenSucceed()
    }

    private companion object {
        const val ROOM = "narrator2-gametest:oracle_room"
        const val FIELD = "narrator2-gametest:oracle_field"

        /** Feet in the 1×2 tunnel of [rockWithTunnel], and in the room of [rockWithCavern]. */
        val TUNNEL = BlockPos(7, 5, 7)
        val CAVERN = BlockPos(7, 2, 7)

        /** The spawner of [scanAreaFindsWhatIsAround]. */
        val SPAWNER = BlockPos(12, 4, 12)

        /** Removes the spawner and the lava of [scanAreaFindsWhatIsAround], which outlive the test otherwise. */
        fun clearHazards(helper: GameTestHelper) {
            set(helper, SPAWNER, Blocks.AIR.defaultBlockState())
            set(helper, BlockPos(24, 4, 16), Blocks.AIR.defaultBlockState())
        }

        /** A player's yaw looking east (+x). */
        const val EAST = -90f

        /** Sets blocks without updating their neighbours or shapes (a portal without its frame stays). */
        const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

        fun set(helper: GameTestHelper, rel: BlockPos, state: BlockState) {
            helper.level.setBlock(helper.absolutePos(rel), state, FLAGS)
        }

        fun fill(helper: GameTestHelper, from: BlockPos, to: BlockPos, state: BlockState) {
            for (rel in BlockPos.betweenClosed(from, to)) set(helper, rel, state)
        }

        /** A block of rock 14 wide, 14 high, with a 1×2 tunnel 12 long through its middle, 9 blocks under its top. */
        fun rockWithTunnel(helper: GameTestHelper, rock: BlockState) {
            fill(helper, BlockPos(1, 0, 1), BlockPos(14, 13, 14), rock)
            fill(helper, BlockPos(2, 5, 7), BlockPos(13, 6, 7), Blocks.AIR.defaultBlockState())
        }

        /** The same block of rock, hollowed into a room of 10×9×10, 12 blocks under its top. */
        fun rockWithCavern(helper: GameTestHelper, rock: BlockState) {
            fill(helper, BlockPos(1, 0, 1), BlockPos(14, 13, 14), rock)
            fill(helper, BlockPos(3, 2, 3), BlockPos(12, 10, 12), Blocks.AIR.defaultBlockState())
        }

        /** Asserts the verdict at [rel] (the feet); returns what was measured. */
        fun expectEnclosure(helper: GameTestHelper, rel: BlockPos, expected: Enclosure): EnclosureProbe {
            val feet = helper.absolutePos(rel)
            val probe = Enclosure.probe(WorldReader(helper.level), feet, feet.above())
            val got = Enclosure.classify(probe)
            helper.assertTrue(got == expected, "at $rel: expected ${expected.id}, got ${got.id}: $probe")
            return probe
        }

        fun ok(helper: GameTestHelper, future: CompletableFuture<ToolResult>): String {
            val result = future.get()
            helper.assertTrue(result.status == ToolStatus.OK, "expected ok, got ${result.text}")
            return result.text
        }

        fun expectContains(helper: GameTestHelper, text: String, part: String) =
            helper.assertTrue(part in text, "expected \"$part\" in:\n$text")

        /** [text] starts with [lines], in order. */
        fun expectLines(helper: GameTestHelper, text: String, vararg lines: String) {
            val actual = text.lines().take(lines.size)
            helper.assertTrue(actual == lines.toList(), "expected:\n${lines.joinToString("\n")}\ngot:\n$text")
        }

        fun <T> timed(block: () -> T): Pair<T, Long> {
            val start = System.nanoTime()
            val value = block()
            return value to System.nanoTime() - start
        }

        /** [block]'s value and its median time over 5 runs, after a first run that warms the code up. */
        fun <T> warm(block: () -> T): Pair<T, Long> {
            block()
            val runs = List(5) { timed(block) }
            return runs.last().first to runs.map { it.second }.sorted()[2]
        }

        fun ms(nanos: Long): String = "%.3f ms".format(Locale.ROOT, nanos / 1e6)
    }
}

/** Runs [tool] as the Director would, off the server thread; the future completes with its answer. */
private fun <A : Any> GameTestHelper.call(tool: NarratorTool<A>, args: A): CompletableFuture<ToolResult> {
    val runtime = narrator()
    val context = ToolContext(runtime, Caller(Role.DIRECTOR, "oracle-gametest", turnId = null, taskId = null), runtime.clock, tool.timeoutMs)
    return CoroutineScope(Dispatchers.Default).future { tool.run(context, args) }
}

/**
 * A player with a name of its own (`oracle_…`), joined as a real client would (`placeNewPlayer`) and
 * standing at [at]. [close] removes them; closing twice is harmless.
 */
private class NamedPlayer(helper: GameTestHelper, at: BlockPos, yRot: Float = 0f) : AutoCloseable {
    private val server = helper.level.server
    val name = "oracle_" + UUID.randomUUID().toString().take(8)
    val player: ServerPlayer

    init {
        val profile = GameProfile(UUID.randomUUID(), name)
        val cookie = CommonListenerCookie.createInitial(profile, false)
        player = ServerPlayer(server, helper.level, profile, cookie.clientInformation())
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, cookie)
        player.teleportTo(helper.level, at.x + 0.5, at.y.toDouble(), at.z + 0.5, emptySet(), yRot, 0f, true)
    }

    override fun close() {
        server.playerList.getPlayer(player.uuid)?.let { server.playerList.remove(it) }
    }
}
