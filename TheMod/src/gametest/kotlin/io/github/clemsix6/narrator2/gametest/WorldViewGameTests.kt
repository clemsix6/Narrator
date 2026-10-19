package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.sdk.CheckedParams
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.Facts
import io.github.clemsix6.narrator2.forge.sdk.ParamCheck
import io.github.clemsix6.narrator2.forge.sdk.ServerParamLookup
import io.github.clemsix6.narrator2.forge.sdk.WorldView
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.tool.Caller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * In-game tests of the SDK's reads and facts (task M5-06, M5 §16.3), in the Forge's batch:
 *
 * - `World`: the players (spectators left out), the creatures near a place, the nearest first, blocks, the surface, the
 *   items a player carries, the time and the weather; nothing in a chunk that is not loaded, which stays unloaded; the
 *   server thread only.
 * - `Facts`: facts translated from real facts of the bus (a join, a block broken, a public chat line, a departure), never
 *   from a private message; a wild creature that appears, one that is gone.
 * - `ServerParamLookup`: a player's name checked against the Journal, a place given by a player's name resolved where
 *   they stand.
 */
class WorldViewGameTests {

    @GameTest(environment = FORGE_ENVIRONMENT)
    fun theWorldReadsPlayersCreaturesBlocksAndItemsInLoadedChunksOnly(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val level = helper.level
        val ownTag = "wv_own_${UUID.randomUUID().toString().take(8)}"
        val world = WorldView(level.server, runtime.players) { ownTag in it.entityTags() }
        val player = TestPlayer(helper, "wv_")
        val spectator = TestPlayer(helper, "ws_")
        try {
            player.standAt(BlockPos(1, 1, 1))
            val live = player.live
            live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_LOG, 3))
            live.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
            live.inventory.add(ItemStack(Items.DIRT, 5))
            spectator.live.setGameMode(GameType.SPECTATOR)

            // Players: real ones online, not spectators, by name ignoring case.
            val info = world.player(player.name.uppercase())
            helper.assertTrue(info != null, "the player, by name ignoring case")
            helper.assertEquals(player.name, info!!.name(), "name")
            helper.assertEquals(live.stringUUID, info.uuid(), "uuid")
            helper.assertEquals(Place(level.dimension().identifier().toString(), live.x, live.y, live.z), info.at(), "where their feet are")
            helper.assertEquals("oak_log", info.heldItem(), "the item in their hand")
            helper.assertEquals(live.gameMode().serializedName, info.gameMode(), "game mode")
            helper.assertEquals(live.health, info.health(), "health")
            helper.assertEquals(live.foodData.foodLevel, info.food(), "food")
            helper.assertTrue(world.players().any { it.name() == player.name }, "among the players")
            helper.assertTrue(world.players().none { it.name() == spectator.name }, "a spectator is no player of a module")
            helper.assertEquals(null, world.player(spectator.name), "nor by name")
            helper.assertEquals(null, world.player("nobody_${UUID.randomUUID().toString().take(6)}"), "a player offline")

            // Items: the inventory, the armor, the hands; an id or a tag.
            helper.assertEquals(5, world.count(player.name, "dirt"), "five dirt")
            helper.assertEquals(3, world.count(player.name, "#minecraft:logs"), "a tag")
            helper.assertEquals(1, world.count(player.name, "minecraft:iron_helmet"), "the armor counts")
            helper.assertEquals(0, world.count(player.name, "diamond"), "none")
            helper.assertEquals(-1, world.count(spectator.name, "dirt"), "a spectator is not online for a module")
            refused(helper, "World.count: unknown item") { world.count(player.name, "dirtt") }
            refused(helper, "World.count: unknown item tag") { world.count(player.name, "#no_such_tag") }

            // Creatures: the nearest first, of a type, its own ones.
            val zombie = helper.spawn(EntityTypes.ZOMBIE, BlockPos(3, 1, 1))
            val cow = helper.spawn(EntityTypes.COW, BlockPos(5, 1, 1))
            cow.addTag(ownTag)
            val near = world.creatures(info.at(), 6.0, null).filter { it.uuid() == zombie.stringUUID || it.uuid() == cow.stringUUID }
            helper.assertEquals(listOf(zombie.stringUUID, cow.stringUUID), near.map { it.uuid() }, "the nearest first")
            helper.assertEquals(listOf("zombie", "cow"), near.map { it.type() }, "types")
            helper.assertEquals(listOf(false, true), near.map { it.own() }, "its own")
            helper.assertEquals(cow.maxHealth, near[1].maxHealth(), "health")
            val cows = world.creatures(info.at(), 6.0, "minecraft:cow").map { it.uuid() }
            helper.assertTrue(cow.stringUUID in cows && zombie.stringUUID !in cows, "a type: $cows")
            helper.assertTrue(world.creatures(info.at(), 1.0, null).none { it.uuid() == cow.stringUUID }, "within the radius only")
            refused(helper, "a radius is 0 to 48 blocks") { world.creatures(info.at(), 48.5, null) }
            refused(helper, "unknown creature type \"zombi\"; closest: zombie") { world.creatures(info.at(), 6.0, "zombi") }
            refused(helper, "a player is not a creature") { world.creatures(info.at(), 6.0, "player") }

            // Blocks: what is there, whether a player placed it.
            val stone = helper.absolutePos(BlockPos(2, 1, 3))
            helper.setBlock(BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState())
            helper.setBlock(BlockPos(3, 1, 3), Blocks.WATER.defaultBlockState())
            val dimension = "minecraft:overworld"
            val stoneInfo = world.block(Cell(dimension, stone.x, stone.y, stone.z))!!
            helper.assertTrue(stoneInfo.block() == "stone" && stoneInfo.solid() && !stoneInfo.air() && !stoneInfo.liquid(), "stone: $stoneInfo")
            helper.assertTrue(!stoneInfo.placedByPlayer(), "nobody placed it")
            PlacedBlocks.mark(level, stone, Blocks.STONE.defaultBlockState(), live.uuid)
            helper.assertTrue(world.block(Cell(dimension, stone.x, stone.y, stone.z))!!.placedByPlayer(), "placed by a player")
            val water = world.block(Cell("overworld", stone.x + 1, stone.y, stone.z))!!
            helper.assertTrue(water.block() == "water" && water.liquid() && !water.solid() && !water.air(), "water: $water")
            val air = world.block(Cell("overworld", stone.x, stone.y + 2, stone.z))!!
            helper.assertTrue(air.air() && !air.solid(), "air: $air")

            // The surface: the first free block above the highest that blocks movement.
            val column = helper.absolutePos(BlockPos(6, 4, 6))
            helper.setBlock(BlockPos(6, 4, 6), Blocks.STONE.defaultBlockState())
            val surface = world.surface("overworld", column.x, column.z)
            helper.assertEquals(level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.x, column.z), surface, "the game's MOTION_BLOCKING height")
            helper.assertTrue(surface >= column.y + 1, "above the block put there: $surface")
            helper.assertEquals(level.getBiome(live.blockPosition()).registeredName.removePrefix("minecraft:"), world.biome(info.at()), "the biome")
            helper.assertTrue(world.dayTime() in 0..23_999 && world.dayTime() == Math.floorMod(level.server.overworld().defaultClockTime, 24_000L), "day time")
            helper.assertTrue(world.weather() in setOf("clear", "rain", "thunder"), "weather")

            // Nothing in a chunk that is not loaded, and it stays so.
            val far = 1_000_000
            helper.assertTrue(level.chunkSource.getChunkNow(far shr 4, far shr 4) == null, "a chunk far away is not loaded")
            helper.assertEquals(null, world.block(Cell("overworld", far, 64, far)), "no block")
            helper.assertEquals(Int.MIN_VALUE, world.surface("overworld", far, far), "no surface")
            helper.assertEquals(null, world.biome(Place("overworld", far + 0.5, 64.0, far + 0.5)), "no biome")
            helper.assertTrue(world.creatures(Place("overworld", far + 0.5, 64.0, far + 0.5), 10.0, null).isEmpty(), "no creature")
            helper.assertTrue(level.chunkSource.getChunkNow(far shr 4, far shr 4) == null, "still not loaded")
            refused(helper, "unknown dimension \"moon\"; dimensions: overworld") { world.surface("moon", 0, 0) }

            // The server thread only; nothing once the deployment ended.
            val elsewhere = AtomicReference<Throwable>()
            Thread { elsewhere.set(runCatching { world.players() }.exceptionOrNull()) }.apply { start() }.join()
            helper.assertTrue((elsewhere.get() as? ForgeRefusal)?.message?.contains("server thread only") == true, "off the server thread: ${elsewhere.get()}")
            world.close()
            refused(helper, "has ended") { world.weather() }
        } finally {
            player.close()
            spectator.close()
        }
        helper.succeed()
    }

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun factsAreTranslatedFromRealFactsOfTheBusAndNeverFromAPrivateMessage(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val level = helper.level
        val queue = FactQueue()
        val got = CopyOnWriteArrayList<GameFact>()
        for (type in listOf(GameFact.Joined::class.java, GameFact.BlockBroken::class.java, GameFact.Chat::class.java, GameFact.Left::class.java)) {
            @Suppress("UNCHECKED_CAST")
            queue.on(type as Class<GameFact>, Consumer { got += it })
        }
        val heard = CopyOnWriteArrayList<GameEvent>()
        val owner = "world-view-test-${UUID.randomUUID()}"
        val secret = "s" + UUID.randomUUID().toString().replace("-", "").take(12)
        val ours = mutableSetOf<UUID>()
        runtime.bus.subscribeSync("facts", owner) { envelope ->
            if (envelope.event.player?.uuid in ours || envelope.event.player?.name?.startsWith("wf") == true) {
                heard += envelope.event
                Facts.of(envelope.event, runtime.players)?.let(queue::offer)
            }
        }
        val player = TestPlayer(helper, "wfa")
        val friend = TestPlayer(helper, "wfb")
        player.standAt(BlockPos(1, 1, 1))
        ours += setOf(player.uuid, friend.uuid)
        val cleanup = AutoCloseable {
            runtime.bus.unsubscribeOwner(owner)
            player.close()
            friend.close()
        }
        helper.closingOnFailure(cleanup, ASYNC_MAX_TICKS) {
            val pos = helper.absolutePos(BlockPos(2, 1, 1))
            val planks = Blocks.OAK_PLANKS.defaultBlockState()
            helper.setBlock(BlockPos(2, 1, 1), planks)
            PlacedBlocks.mark(level, pos, planks, friend.uuid)
            helper.assertTrue(player.live.gameMode.destroyBlock(pos), "the player breaks the planks")
            level.server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, "Bonsoir"), player.live, ChatType.bind(ChatType.CHAT, player.live))
            player.live.connection.handleChatCommand(ServerboundChatCommandPacket("msg ${friend.name} $secret"))
            helper.startSequence()
                .thenAwait {
                    helper.assertTrue(
                        heard.any { it is ChatMessage && it.channel == ChatChannel.WHISPER && secret in it.text },
                        "the private message went through the bus: ${heard.map { it.type }}",
                    )
                }
                .thenExecute {
                    player.close()
                    deliver(queue)
                    val name = player.name
                    val facts = got.filter { it.player() == name }
                    helper.assertEquals(
                        listOf(
                            GameFact.Joined::class.java, GameFact.BlockBroken::class.java, GameFact.Chat::class.java, GameFact.Left::class.java,
                        ),
                        facts.map { it.javaClass },
                        "the facts of the player, in order",
                    )
                    helper.assertEquals(GameFact.BlockBroken(name, Place("overworld", pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), "oak_planks", true), facts[1], "the planks")
                    helper.assertEquals("Bonsoir", (facts[2] as GameFact.Chat).text(), "the public line")
                    helper.assertTrue(got.none { secret in it.toString() }, "no fact carries the private message: $got")
                    cleanup.close()
                }
                .thenSucceed()
        }
    }

    @GameTest(environment = FORGE_ENVIRONMENT)
    fun aWildCreatureAppearsAndAModulesCreatureIsGone(helper: GameTestHelper) {
        val level = helper.level
        val player = TestPlayer(helper, "wc_")
        try {
            player.standAt(BlockPos(1, 1, 1))
            val wild = helper.spawn(EntityTypes.ZOMBIE, BlockPos(3, 1, 3))
            val appeared = Facts.appeared(wild, level)
            helper.assertTrue(appeared != null, "a wild zombie near a player appears")
            helper.assertEquals("zombie", appeared!!.creature().type(), "its type")
            helper.assertEquals(wild.stringUUID, appeared.creature().uuid(), "its uuid")
            helper.assertTrue(level.server.playerList.getPlayerByName(appeared.player()) != null, "the nearest player online: ${appeared.player()}")
            helper.assertEquals(Place("overworld", wild.blockX + 0.5, wild.blockY.toDouble(), wild.blockZ + 0.5), appeared.at(), "where")

            val named = helper.spawn(EntityTypes.ZOMBIE, BlockPos(4, 1, 3)).apply { setCustomName(Component.literal("Gérard")) }
            helper.assertEquals(null, Facts.appeared(named, level), "a named creature is not wild")
            val narrators = helper.spawn(EntityTypes.ZOMBIE, BlockPos(5, 1, 3)).apply { addTag("narrator2") }
            helper.assertEquals(null, Facts.appeared(narrators, level), "nor one of the Narrator's")

            wild.discard()
            val vanished = Facts.gone(wild)
            helper.assertEquals(Facts.VANISHED, vanished.why(), "discarded")
            helper.assertEquals(null, vanished.player(), "no player")
            helper.assertEquals(wild.stringUUID, vanished.creature().uuid(), "the creature")
            named.kill(level)
            helper.assertEquals(Facts.DIED, Facts.gone(named).why(), "killed")
            narrators.discard()
        } finally {
            player.close()
        }
        helper.succeed()
    }

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aDeploymentsParametersAreCheckedAgainstTheJournalAndWhereAPlayerStands(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val player = TestPlayer(helper, "wp_")
        player.standAt(BlockPos(2, 1, 2))
        val live = player.live
        val specs = listOf(
            ParamSpec("wolf", ParamType.PLAYER, "Who is the wolf.", true, null, null, null, emptyList()),
            ParamSpec("arena", ParamType.PLACE, "Where it happens.", true, null, null, null, emptyList()),
        )
        val given = JsonObject(mapOf("wolf" to JsonPrimitive(player.name.lowercase()), "arena" to JsonPrimitive(player.name)))
        val checked = AtomicReference<CheckedParams>()
        val failure = AtomicReference<Throwable>()
        val lookup = ServerParamLookup(runtime, Caller(Role.DIRECTOR, "world-view-test", null, null))
        CoroutineScope(Dispatchers.Default).launch {
            try {
                checked.set(ParamCheck.check(specs, given, lookup))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        helper.closingOnFailure(player, ASYNC_MAX_TICKS) {
            helper.awaitAsync {
                helper.assertTrue(failure.get() == null, "the check failed: ${failure.get()}")
                val result = checked.get()
                helper.assertTrue(result != null, "checking")
                helper.assertEquals(listOf(player.ref), result!!.players, "the player as the Journal knows them")
                helper.assertEquals(JsonPrimitive(player.name), result.params["wolf"], "their name")
                helper.assertEquals(ParamCheck.json(Place("overworld", live.x, live.y, live.z)), result.params["arena"], "where they stand")
                helper.assertTrue("minecraft:the_nether" in lookup.dimensions, "the server's dimensions: ${lookup.dimensions}")
                player.close()
            }
        }
    }

    /** [block] throws a [ForgeRefusal] whose message holds [part]. */
    private fun refused(helper: GameTestHelper, part: String, block: () -> Any?) {
        val refusal = runCatching(block).exceptionOrNull()
        helper.assertTrue(refusal is ForgeRefusal && part in refusal.message.orEmpty(), "a refusal with \"$part\": $refusal")
    }

    /** The facts queued, delivered as the engine does at the next tick. */
    private fun deliver(queue: FactQueue) = queue.drain(System.currentTimeMillis()).forEach { it.deliver() }
}
