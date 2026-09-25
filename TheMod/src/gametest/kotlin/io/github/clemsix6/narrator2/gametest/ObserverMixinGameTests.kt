package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.AnimalTamed
import io.github.clemsix6.narrator2.event.AnvilUsed
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.ContainerOpened
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.GrindstoneUsed
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.ItemsDropped
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.MountChanged
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.RespawnPointSet
import io.github.clemsix6.narrator2.event.StatAwarded
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.StatsImport
import io.github.clemsix6.narrator2.observer.blockId
import io.github.clemsix6.narrator2.observer.toPos
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.GrindstoneMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText

/**
 * In-game tests of the Observer's mixins and statistics (M1_M2 §17.3). Starting the test server already
 * proves that every mixin found its target (`defaultRequire: 1`); these tests drive the game code that
 * reaches each hook and check what is published. Each test has its own mock player ([Watched]) and
 * records the events about it; mock players all share one name, so filters use the UUID.
 */
class ObserverMixinGameTests {

    @GameTest
    fun placingABlockIsPublishedAndIndexed(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val level = helper.level
            // The placement reads the stack in the player's hand, as in the game.
            watched.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_PLANKS))
            helper.placeAt(watched.player, watched.player.mainHandItem, BlockPos(2, 0, 2), Direction.UP)
            val placed = watched.events<BlockPlaced>().single()
            helper.assertValueEqual(PlaceKind.BLOCK, placed.kind, "kind")
            helper.assertValueEqual("minecraft:oak_planks", placed.block, "block")
            helper.assertValueEqual("minecraft:oak_planks", placed.item, "item")
            val at = BlockPos(placed.pos.x, placed.pos.y, placed.pos.z)
            helper.assertValueEqual("minecraft:oak_planks", level.getBlockState(at).blockId, "the block is where the event says")
            helper.assertEquals(watched.uuid, PlacedBlocks.placerAt(level, at), "indexed with its placer")
        }
        helper.succeed()
    }

    /** A shift-click crafts again and again in one tick: one event with the total. */
    @GameTest(maxTicks = TICKS)
    fun craftsOfOneTickAreMerged(helper: GameTestHelper) {
        val watched = Watched(helper)
        helper.closingOnFailure(watched, TICKS) {
            repeat(3) { ItemStack(Items.OAK_PLANKS, 4).onCraftedBy(watched.player, 4) }
            helper.assertTrue(watched.events<ItemCrafted>().isEmpty(), "merged until the end of the tick")
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(watched.events<ItemCrafted>().isNotEmpty(), "the tick ended") }
                .thenExecute {
                    val craft = watched.events<ItemCrafted>().single()
                    helper.assertValueEqual("minecraft:oak_planks", craft.item, "item")
                    helper.assertValueEqual(12, craft.count, "the three takes summed")
                    helper.assertValueEqual("minecraft:inventory", craft.station, "the inventory's grid")
                    watched.close()
                }
                .thenSucceed()
        }
    }

    @GameTest
    fun eatingIsPublishedWithTheValuesBefore(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val player = watched.player
            player.foodData.foodLevel = 10
            ItemStack(Items.COOKED_BEEF, 3).finishUsingItem(helper.level, player)
            val eaten = watched.events<ItemConsumed>().single()
            helper.assertValueEqual("minecraft:cooked_beef", eaten.item, "item")
            helper.assertValueEqual(3, eaten.countBefore, "count before")
            helper.assertEquals(8, eaten.nutrition, "nutrition")
            helper.assertValueEqual(10, eaten.foodBefore, "food before")
            helper.assertValueEqual(player.maxHealth.toDouble(), eaten.healthBefore, "health before")
        }
        helper.succeed()
    }

    /** The output slot of the grindstone is an anonymous class: fragile across game versions. */
    @GameTest
    fun grindstoneResultIsObserved(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val menu = GrindstoneMenu(1, watched.player.inventory)
            val output = menu.getSlot(2)
            helper.assertValueEqual("net.minecraft.world.inventory.GrindstoneMenu$4", output.javaClass.name, "the mixin's target is the output slot")
            val sharpness = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
            val sword = ItemStack(Items.IRON_SWORD).apply { enchant(sharpness, 2) }
            menu.getSlot(0).set(sword)
            val result = output.item
            helper.assertFalse(result.isEmpty, "the grindstone disenchants the sword")
            output.onTake(watched.player, result)
            helper.assertValueEqual("minecraft:iron_sword", watched.events<GrindstoneUsed>().single().result, "result")
        }
        helper.succeed()
    }

    @GameTest
    fun anvilRenameIsObserved(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val menu = AnvilMenu(1, watched.player.inventory)
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(ItemStack(Items.IRON_SWORD))
            helper.assertTrue(menu.setItemName("Excalibur"), "the name is valid")
            val result = menu.getSlot(AnvilMenu.RESULT_SLOT).item
            helper.assertFalse(result.isEmpty, "a renamed sword")
            val cost = menu.cost
            menu.getSlot(AnvilMenu.RESULT_SLOT).onTake(watched.player, result)
            val used = watched.events<AnvilUsed>().single()
            helper.assertValueEqual(AnvilUsed(used.player, used.pos, "minecraft:iron_sword", "minecraft:iron_sword", null, cost, "Excalibur"), used, "anvil")
        }
        helper.succeed()
    }

    /** An advancement with a display is told once; a recipe (no display) is not. */
    @GameTest
    fun advancementsAreToldAndRecipesAreNot(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val manager = helper.level.server.advancements
            val stoneAge = manager.get(Identifier.withDefaultNamespace("story/mine_stone"))
            helper.assertTrue(stoneAge != null, "the vanilla advancement exists")
            val recipe = manager.allAdvancements.first { it.value().display().isEmpty }
            val advancements = watched.player.advancements
            for (holder in listOf(stoneAge!!, recipe)) {
                holder.value().criteria().keys.forEach { advancements.award(holder, it) }
                holder.value().criteria().keys.forEach { advancements.award(holder, it) }
            }
            val earned = watched.events<AdvancementEarned>().single()
            helper.assertValueEqual("minecraft:story/mine_stone", earned.advancement, "advancement")
            helper.assertValueEqual("task", earned.frame, "frame")
            helper.assertTrue(earned.title.isNotBlank(), "a title: ${earned.title}")
        }
        helper.succeed()
    }

    /**
     * A departure: the pending pickups come before `PlayerLeft` (summed per item), the stats snapshot
     * after it (with what changed, the stats of this test included). Drops and notable stats are
     * published at once.
     */
    @GameTest
    fun aDepartureFlushesPickupsBeforeAndSnapshotsStatsAfter(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val player = watched.player
            player.awardStat(Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE), 3)
            player.awardStat(Stats.ITEM_PICKED_UP.get(Items.DIRT), 1)
            player.awardStat(Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE), 4)
            player.awardStat(Stats.ITEM_DROPPED.get(Items.DIRT), 1)
            player.awardStat(Stats.BELL_RING)
            player.awardStat(Stats.JUMP)
            player.awardStat(Stats.OPEN_CHEST)
            helper.assertTrue(watched.events<ItemsPickedUp>().isEmpty(), "pickups wait for their window")
            helper.assertValueEqual(1, watched.events<ItemsDropped>().size, "a drop is told at once")
            helper.assertValueEqual(listOf("minecraft.custom:minecraft.bell_ring"), watched.events<StatAwarded>().map { it.stat }, "only the notable stat")

            helper.level.server.playerList.remove(player)
            val order = watched.recorded.filter { it.player?.uuid == watched.uuid }.map { it.type }
            val left = order.indexOf("leave")
            helper.assertTrue(left > 0, "a departure: $order")
            helper.assertTrue(order.lastIndexOf("pickup") < left, "pickups before the departure: $order")
            helper.assertValueEqual(left + 1, order.indexOf("stats_snapshot"), "the snapshot right after the departure: $order")

            val pickups = watched.events<ItemsPickedUp>().associate { it.item to it.count }
            helper.assertValueEqual(mapOf("minecraft:cobblestone" to 7, "minecraft:dirt" to 1), pickups, "summed per item")
            val snapshot = watched.events<StatsSnapshot>().single().changed
            helper.assertEquals(1L, snapshot["minecraft.custom:minecraft.jump"], "jumps in the snapshot")
            helper.assertEquals(7L, snapshot["minecraft.picked_up:minecraft.cobblestone"], "pickups in the totals")

            // The departure saved the player's stats file: the import reads it under the same names.
            val file = helper.level.server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve("${watched.uuid}.json")
            val imported = StatsImport.parseStats(file.readText())
            val differences = snapshot.filter { (stat, value) -> imported[stat] != value }
            helper.assertTrue(differences.isEmpty(), "the file agrees with the snapshot: $differences, file $imported")
        }
        helper.succeed()
    }

    @GameTest(maxTicks = TICKS)
    fun anExplosionNearAPlayerTellsThePlayerBlocksItHit(helper: GameTestHelper) {
        val watched = Watched(helper)
        helper.closingOnFailure(watched, TICKS) {
            val level = helper.level
            val planks = Blocks.OAK_PLANKS.defaultBlockState()
            helper.setBlock(BlockPos(2, 1, 2), planks)
            val at = helper.absolutePos(BlockPos(2, 1, 2))
            PlacedBlocks.mark(level, at, planks, watched.uuid)
            level.explode(null, at.x + 0.5, at.y + 0.5, at.z + 0.5, 1.5f, Level.ExplosionInteraction.BLOCK)
            fun mine() = watched.recorded.filterIsInstance<ExplosionHappened>().filter { e -> e.nearby.any { it.uuid == watched.uuid } }
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(mine().isNotEmpty(), "published at the end of the tick") }
                .thenExecute {
                    val explosion = mine().single()
                    helper.assertValueEqual(at.toPos(level), explosion.pos, "position")
                    helper.assertEquals(null, explosion.source, "no source entity")
                    helper.assertTrue(explosion.blocksHit >= 1, "blocks hit: ${explosion.blocksHit}")
                    helper.assertValueEqual(1, explosion.playerBlocksHit, "the planks placed by the player")
                    helper.assertFalse(explosion.narrator, "not the Narrator's")
                    watched.close()
                }
                .thenSucceed()
        }
    }

    /**
     * A private message: the command's text is withheld, the message goes to the `whisper` channel.
     * A redacted root keeps its text out too; any other command is recorded as typed.
     */
    @GameTest(maxTicks = TICKS)
    fun privateMessagesAndRedactedCommandsKeepTheirTextOut(helper: GameTestHelper) {
        val watched = Watched(helper)
        val friend = Watched(helper)
        helper.closingOnFailure(watched, TICKS) {
            helper.closingOnFailure(friend, TICKS) {
                val connection = watched.player.connection
                connection.handleChatCommand(ServerboundChatCommandPacket("msg ${friend.player.plainTextName} meet me at the portal"))
                connection.handleChatCommand(ServerboundChatCommandPacket("login hunter2"))
                connection.handleChatCommand(ServerboundChatCommandPacket("list uuids"))
                helper.startSequence()
                    .thenWaitUntil { helper.assertValueEqual(3, watched.events<CommandIssued>().size, "three commands") }
                    .thenExecute {
                        val commands = watched.events<CommandIssued>().associate { it.root to it.text }
                        helper.assertValueEqual(mapOf("msg" to null, "login" to null, "list" to "list uuids"), commands, "texts")
                        val whisper = watched.events<ChatMessage>().single()
                        helper.assertValueEqual(ChatChannel.WHISPER, whisper.channel, "channel")
                        helper.assertValueEqual("meet me at the portal", whisper.text, "the message alone")
                        helper.assertValueEqual(listOf(friend.player.plainTextName), whisper.recipients, "recipients")
                        friend.close()
                        watched.close()
                    }
                    .thenSucceed()
            }
        }
    }

    /**
     * The full death message is read at the head of `die`, while the combat tracker still knows the
     * fall: "fell from a high place", where the end of `die` only gives "hit the ground too hard".
     */
    @GameTest
    fun theDeathMessageKeepsTheFallContext(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val player = watched.player
            player.setGameMode(GameType.SURVIVAL)
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            player.fallDistance = 30.0
            player.hurtServer(helper.level, helper.level.damageSources().fall(), 1_000f)
            val died = watched.events<PlayerDied>().single()
            helper.assertTrue(died.message?.contains("fell from a high place") == true, "the fall's context: ${died.message}")
        }
        helper.succeed()
    }

    /** A chest locates itself; a crafting table is located by the use that opened it. */
    @GameTest
    fun openedMenusAreLocated(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val player = watched.player
            val level = helper.level
            helper.setBlock(BlockPos(3, 1, 3), Blocks.CHEST)
            val chestAt = helper.absolutePos(BlockPos(3, 1, 3))
            player.openMenu(level.getBlockEntity(chestAt) as ChestBlockEntity)
            player.closeContainer()
            helper.setBlock(BlockPos(1, 1, 3), Blocks.CRAFTING_TABLE)
            val tableAt = helper.absolutePos(BlockPos(1, 1, 3))
            player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(tableAt), Direction.UP, tableAt, false))
            player.closeContainer()

            val (chest, table) = watched.events<ContainerOpened>().also { helper.assertValueEqual(2, it.size, "two menus: $it") }
            helper.assertValueEqual(ContainerOpened(chest.player, chestAt.toPos(level), "minecraft:generic_9x3", null, "minecraft:chest", false), chest, "chest")
            helper.assertValueEqual(ContainerOpened(table.player, tableAt.toPos(level), "minecraft:crafting", null, "minecraft:crafting_table", false), table, "crafting table")
        }
        helper.succeed()
    }

    /** A new point, the same point again (not told), then the point cleared. */
    @GameTest
    fun respawnPointChangesAreTold(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val player = watched.player
            val level = helper.level
            val bed = helper.absolutePos(BlockPos(2, 1, 2))
            val config = ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), bed, 0f, 0f), true)
            player.setRespawnPosition(config, false)
            player.setRespawnPosition(config, false)
            player.setRespawnPosition(null, false)
            val (set, cleared) = watched.events<RespawnPointSet>().also { helper.assertValueEqual(2, it.size, "set, then cleared: $it") }
            helper.assertEquals(bed.toPos(level), set.point, "point")
            helper.assertTrue(set.forced && !set.cleared, "forced, not cleared")
            helper.assertTrue(cleared.cleared && cleared.point == null, "cleared")
        }
        helper.succeed()
    }

    @GameTest
    fun mountingAndDismountingAreTold(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(2, 1, 2))
            helper.assertTrue(watched.player.startRiding(pig, true, true), "the player rides the pig")
            watched.player.stopRiding()
            val changes = watched.events<MountChanged>()
            helper.assertValueEqual(listOf(true, false), changes.map { it.mounted }, "on, then off")
            helper.assertTrue(changes.all { it.vehicle.type == "minecraft:pig" && it.vehicle.uuid == pig.uuid }, "the pig: $changes")
        }
        helper.succeed()
    }

    /** At the head of `notifyTrade` the uses are not counted yet: this trade is use 1. */
    @GameTest
    fun aTradeIsTold(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BlockPos(2, 1, 2))
            val offer = MerchantOffer(ItemCost(Items.EMERALD, 2), ItemStack(Items.BREAD, 6), 12, 1, 0.05f)
            villager.tradingPlayer = watched.player
            villager.notifyTrade(offer)
            villager.tradingPlayer = null
            val trade = watched.events<Traded>().single()
            helper.assertValueEqual(ItemCount("minecraft:bread", 6), trade.result, "received")
            helper.assertValueEqual(ItemCount("minecraft:emerald", 2), trade.costA, "paid")
            helper.assertEquals(null, trade.costB, "no second payment")
            helper.assertEquals(villager.uuid, trade.villager.uuid, "villager")
            helper.assertValueEqual(1, trade.uses, "uses after this trade")
            helper.assertValueEqual(12, trade.maxUses, "max uses")
        }
        helper.succeed()
    }

    @GameTest
    fun tamingIsTold(helper: GameTestHelper) {
        Watched(helper).use { watched ->
            val wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, BlockPos(2, 1, 2))
            wolf.tame(watched.player)
            helper.assertValueEqual("minecraft:wolf", watched.events<AnimalTamed>().single().animal, "animal")
        }
        helper.succeed()
    }

    /** A snowball thrown by the player lands on the floor of the test. */
    @GameTest(maxTicks = TICKS)
    fun aProjectileLandingIsTold(helper: GameTestHelper) {
        val watched = Watched(helper)
        helper.closingOnFailure(watched, TICKS) {
            val level = helper.level
            val above = helper.absoluteVec(Vec3(2.5, 3.0, 2.5))
            val snowball = Snowball(level, above.x, above.y, above.z, ItemStack(Items.SNOWBALL))
            snowball.owner = watched.player
            snowball.deltaMovement = Vec3(0.0, -1.0, 0.0)
            level.addFreshEntity(snowball)
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(watched.events<ProjectileHit>().isNotEmpty(), "the snowball lands") }
                .thenExecute {
                    val hit = watched.events<ProjectileHit>().first()
                    helper.assertValueEqual("minecraft:snowball", hit.projectile, "projectile")
                    helper.assertEquals(watched.uuid, hit.shooter?.uuid, "shot by the player")
                    helper.assertEquals(level.getBlockState(BlockPos(hit.pos.x, hit.pos.y, hit.pos.z)).blockId, hit.block, "the block hit")
                    watched.close()
                }
                .thenSucceed()
        }
    }

    private companion object {
        /** Enough for a few ticks of waiting, and for the commands run between ticks. */
        const val TICKS = 200
    }
}

/**
 * A mock player who joins for one test, and every event published from its join on. Filters read the
 * UUID ([events]); [close] stops the recording and removes the player. Closing twice is harmless.
 */
private class Watched(private val helper: GameTestHelper) : AutoCloseable {
    private val runtime = helper.narrator()
    private val owner = "observer-mixin-test-${UUID.randomUUID()}"

    /** Every event since this player joined, whoever it is about: tests run side by side. */
    val recorded = CopyOnWriteArrayList<GameEvent>()
    val player: ServerPlayer

    init {
        runtime.bus.subscribeSync("recorder", owner) { recorded += it.event }
        // Deprecated in 26.3, but still the only helper that puts a ServerPlayer in the player list.
        @Suppress("DEPRECATION")
        player = helper.makeMockServerPlayerInLevel()
        val at = helper.absolutePos(BlockPos(1, 1, 1))
        player.teleportTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
    }

    val uuid: UUID get() = player.uuid

    inline fun <reified T : GameEvent> events(): List<T> = recorded.filter { it.player?.uuid == uuid }.filterIsInstance<T>()

    override fun close() {
        runtime.bus.unsubscribeOwner(owner)
        val players = helper.level.server.playerList
        players.getPlayer(uuid)?.let { players.remove(it) }
    }
}
