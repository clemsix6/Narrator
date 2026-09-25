package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.SleepEnded
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.Visited
import io.github.clemsix6.narrator2.observer.VisitedPlaces
import io.github.clemsix6.narrator2.observer.blockId
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.observer.toPos
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PortalProcessor
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the Observer's Fabric events and position sampler (M1_M2 §17.3). Each test has its
 * own mock player ([Joined]), records the events published about it, and removes it at the end.
 *
 * The test world is flat (sea level -63, bottom at -64): nothing in it is underground by the rule of
 * §7.4, whose positive cases are in `SamplerRulesTest`.
 */
class ObserverFabricGameTests {

    @GameTest
    fun joinAndLeaveOpenAndCloseASession(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val join = joined.events<PlayerJoined>().single()
            helper.assertTrue(join.firstTime, "a new player's first join")
            helper.assertFalse(join.isOp, "not an operator")
            helper.assertValueEqual(helper.level.dimensionId, join.pos.dim, "joined in the overworld")
            helper.assertTrue(helper.narrator().players.get(joined.uuid)?.sessionId == join.sessionId, "the player states follow")

            helper.level.server.playerList.remove(joined.player)
            val leave = joined.events<PlayerLeft>().single()
            helper.assertValueEqual(join.sessionId, leave.sessionId, "session")
            helper.assertValueEqual(LeaveReason.LEAVE, leave.reason, "reason")
            helper.assertTrue(leave.sessionSeconds >= 0, "duration: ${leave.sessionSeconds}")
        }
        helper.succeed()
    }

    @GameTest
    fun breakingABlockPlacedByAnotherPlayerNamesItsPlacer(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val level = helper.level
            val pos = helper.absolutePos(BlockPos(2, 1, 1))
            val planks = Blocks.OAK_PLANKS.defaultBlockState()
            helper.setBlock(BlockPos(2, 1, 1), planks)
            val builder = UUID.randomUUID()
            PlacedBlocks.mark(level, pos, planks, builder)

            helper.assertTrue(joined.player.gameMode.destroyBlock(pos), "the mock player breaks the planks")
            val broken = joined.events<BlockBroken>().single()
            helper.assertValueEqual(pos.toPos(level), broken.pos, "position")
            helper.assertValueEqual("minecraft:oak_planks", broken.block, "block")
            helper.assertEquals(builder, broken.placedBy, "placed by")
            helper.assertEquals(null, broken.tool, "bare hand")
            helper.assertValueEqual(joined.player.isCreative, broken.creative, "creative")
            helper.assertEquals(null, PlacedBlocks.placerAt(level, pos), "the index forgets the broken block")
        }
        helper.succeed()
    }

    /** Hits of one damage type are merged for 5 seconds from the first; another type is merged apart. */
    @GameTest(maxTicks = DAMAGE_TICKS)
    fun damageOfOneTypeIsMergedForFiveSeconds(helper: GameTestHelper) {
        val joined = Joined(helper)
        helper.closingOnFailure(joined, DAMAGE_TICKS) {
            val player = joined.player
            val level = helper.level
            vulnerable(player)
            val sources = level.damageSources()
            val healthAfter = AtomicReference<Float>()
            fun taken() = joined.events<DamageTaken>()

            helper.assertTrue(player.hurtServer(level, sources.generic(), 2f), "first hit")
            helper.startSequence()
                .thenExecuteAfter(25) {
                    helper.assertTrue(player.hurtServer(level, sources.generic(), 3f), "second hit")
                    healthAfter.set(player.health)
                }
                .thenExecuteAfter(25) {
                    helper.assertTrue(player.hurtServer(level, sources.fall(), 1f), "a fall")
                    helper.assertTrue(taken().isEmpty(), "still merging after 50 ticks: ${taken()}")
                }
                .thenWaitUntil { helper.assertTrue(taken().isNotEmpty(), "the window of the first hit closes") }
                .thenExecute {
                    val generic = taken().single()
                    helper.assertValueEqual("minecraft:generic", generic.damageType, "type")
                    helper.assertValueEqual(5.0, generic.amount, "amount")
                    helper.assertValueEqual(2, generic.hits, "hits")
                    helper.assertValueEqual(healthAfter.get().toDouble(), generic.healthAfter, "health after the last hit")
                    helper.assertEquals(null, generic.attacker, "no attacker")
                }
                .thenWaitUntil { helper.assertValueEqual(2, taken().size, "the fall's window closes") }
                .thenExecute {
                    val fall = taken()[1]
                    helper.assertValueEqual("minecraft:fall", fall.damageType, "type")
                    helper.assertValueEqual(1, fall.hits, "hits")
                    joined.close()
                }
                .thenSucceed()
        }
    }

    /** A non-fatal hit on a mob is `DamageDealt`; the fatal one is only `EntityKilled`. */
    @GameTest
    fun hittingThenKillingATamedWolf(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val player = joined.player
            val level = helper.level
            val wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, BlockPos(2, 1, 2))
            wolf.setOwner(player)
            val attack = level.damageSources().playerAttack(player)

            helper.assertTrue(wolf.hurtServer(level, attack, 3f), "the hit lands")
            val dealt = joined.events<DamageDealt>().single()
            helper.assertValueEqual("minecraft:wolf", dealt.victim.type, "victim")
            helper.assertEquals(wolf.uuid, dealt.victim.uuid, "victim's uuid")
            helper.assertValueEqual(3.0, dealt.amount, "amount")
            helper.assertValueEqual("minecraft:player_attack", dealt.damageType, "type")
            helper.assertValueEqual(wolf.health.toDouble(), dealt.victimHealth, "victim's health")

            wolf.hurtServer(level, attack, 1_000f)
            helper.assertTrue(wolf.isDeadOrDying, "the wolf is dead")
            val kill = joined.events<EntityKilled>().single()
            helper.assertEquals(wolf.uuid, kill.victim.uuid, "victim")
            helper.assertFalse(kill.pvp, "not PvP")
            helper.assertTrue(kill.tamed, "someone's pet")
            helper.assertValueEqual(1, joined.events<DamageDealt>().size, "the fatal hit is not a DamageDealt")
        }
        helper.succeed()
    }

    @GameTest
    fun visitedPlacesSurviveTheirCodec(helper: GameTestHelper) {
        helper.assertTrue(VisitedPlaces.TYPE.isPersistent, "saved with the player")
        helper.assertTrue(VisitedPlaces.TYPE.copyOnDeath(), "copied to the player object that follows a death")
        val visited = Visited(setOf("minecraft:plains", "minecraft:desert"), setOf("minecraft:village_plains"))
        val saved = Visited.CODEC.encodeStart(NbtOps.INSTANCE, visited).getOrThrow()
        helper.assertValueEqual(visited, Visited.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow(), "round trip")
        helper.assertValueEqual(Visited(), Visited.CODEC.parse(NbtOps.INSTANCE, CompoundTag()).getOrThrow(), "an empty tag: nothing visited")
        helper.succeed()
    }

    /** A death is published with its message; the respawn too, and the visited places survive it. */
    @GameTest(maxTicks = SAMPLER_TICKS)
    fun deathAndRespawnKeepTheVisitedPlaces(helper: GameTestHelper) {
        val joined = Joined(helper)
        helper.closingOnFailure(joined, SAMPLER_TICKS) {
            val level = helper.level
            val biome = AtomicReference<String>()
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(joined.rows().isNotEmpty(), "first examination") }
                .thenExecute {
                    val player = joined.player
                    biome.set(level.getBiome(player.blockPosition()).registeredName)
                    helper.assertTrue(biome.get() in VisitedPlaces.of(player).biomes, "the first biome is visited")
                    vulnerable(player)
                    player.hurtServer(level, level.damageSources().generic(), 1_000f)

                    val died = joined.events<PlayerDied>().single()
                    helper.assertValueEqual("minecraft:generic", died.cause, "cause")
                    helper.assertTrue(!died.message.isNullOrBlank(), "a death message: ${died.message}")
                    helper.assertFalse(died.narrator, "not the Narrator's doing")
                    helper.assertValueEqual(level.gameRules.get(GameRules.KEEP_INVENTORY), died.keptInventory, "kept inventory")
                    helper.assertEquals(null, died.killer, "no killer")

                    val respawned = level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
                    helper.assertFalse(joined.events<PlayerRespawned>().single().alive, "a respawn after a death")
                    helper.assertTrue(biome.get() in VisitedPlaces.of(respawned).biomes, "the visited places are copied on death")
                }
                .thenWaitUntil {
                    helper.assertTrue(joined.rows().any { it.reason == SampleReason.RESPAWN }, "a respawn row: ${joined.rows().map { it.reason }}")
                }
                .thenExecute { joined.close() }
                .thenSucceed()
        }
    }

    /** Only the public chat: `/say` comes through the command hook (T06). */
    @GameTest
    fun publicChatIsPublishedAndSayIsNot(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val player = joined.player
            val players = helper.level.server.playerList
            players.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, "Is anyone there?"), player, ChatType.bind(ChatType.CHAT, player))
            players.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, "Hear me"), player, ChatType.bind(ChatType.SAY_COMMAND, player))

            val chat = joined.events<ChatMessage>().single()
            helper.assertValueEqual(ChatChannel.PUBLIC, chat.channel, "channel")
            helper.assertValueEqual("Is anyone there?", chat.text, "text")
        }
        helper.succeed()
    }

    @GameTest
    fun sleepStartAndEndArePublishedOnce(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val player = joined.player
            val level = helper.level
            val bed = Blocks.BED.red().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
            helper.setBlock(BlockPos(2, 1, 2), bed)
            val at = helper.absolutePos(BlockPos(2, 1, 2))

            player.startSleeping(at)
            val started = joined.events<SleepStarted>().single()
            helper.assertValueEqual(at.toPos(level), started.pos, "bed position")
            helper.assertValueEqual(bed.blockId, started.bed, "bed")

            player.stopSleeping()
            player.stopSleeping()
            val ended = joined.events<SleepEnded>().single()
            helper.assertEquals(false, ended.nightSkipped, "got up without a night skip")
        }
        helper.succeed()
    }

    /** A command-like travel to the Nether: `via = other`, from the last sampled position; then a `dimension` row. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun changingDimensionIsPublishedWithItsOrigin(helper: GameTestHelper) {
        val joined = Joined(helper)
        helper.closingOnFailure(joined, ASYNC_MAX_TICKS) {
            val nether = helper.level.server.getLevel(Level.NETHER)
            helper.assertTrue(nether != null, "the test world has a Nether")
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(joined.rows().isNotEmpty(), "sampled in the overworld") }
                .thenExecute {
                    val player = joined.player
                    val start = player.blockPosition().toPos(helper.level)
                    player.teleport(TeleportTransition(nether!!, Vec3(0.5, 8.0, 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
                    val change = joined.events<DimensionChanged>().single()
                    helper.assertValueEqual("other", change.via, "via")
                    helper.assertValueEqual(start, change.from, "from")
                    helper.assertValueEqual(nether.dimensionId, change.pos.dim, "arrival")
                }
                .thenAwait {
                    val last = joined.rows().last()
                    helper.assertValueEqual(SampleReason.DIMENSION, last.reason, "a dimension row")
                    helper.assertValueEqual(nether!!.dimensionId, last.pos.dim, "sampled in the Nether")
                }
                .thenExecute { joined.close() }
                .thenSucceed()
        }
    }

    /**
     * The portal countdown stays set on a player after a trip and while they stand in a portal: only a
     * countdown past the portal's delay makes a dimension change the portal's.
     */
    @GameTest
    fun aPortalTripIsToldApartFromACommandInAPortal(helper: GameTestHelper) {
        Joined(helper).use { joined ->
            val player = joined.player
            val overworld = helper.level
            val nether = overworld.server.getLevel(Level.NETHER)
            helper.assertTrue(nether != null, "the test world has a Nether")
            val portal = Blocks.NETHER_PORTAL as Portal
            val entry = player.blockPosition()

            // The countdown runs out, then the game teleports the player (what Entity.handlePortal does).
            val countdown = PortalProcessor(portal, entry)
            player.portalProcess = countdown
            while (!countdown.processPortalTeleportation(overworld, player, true)) countdown.setAsInsidePortalThisTick(true)
            player.teleport(TeleportTransition(nether!!, Vec3(0.5, 8.0, 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
            val trip = joined.events<DimensionChanged>().single()
            helper.assertValueEqual("nether_portal", trip.via, "through the portal")
            helper.assertValueEqual(entry.toPos(overworld), trip.from, "from the portal's entry")

            // A command run while standing in a portal, before its countdown is over.
            player.portalProcess = PortalProcessor(portal, player.blockPosition())
            player.teleport(TeleportTransition(overworld, Vec3.atBottomCenterOf(entry), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
            helper.assertValueEqual("other", joined.events<DimensionChanged>()[1].via, "a command, not the portal")
        }
        helper.succeed()
    }

    /**
     * The sampler: a join row, the first biome (a first visit, kept on the player), the live state,
     * then a move of 21 blocks across a chunk of the test (loaded, whatever the tests around).
     */
    @GameTest(maxTicks = SAMPLER_TICKS)
    fun aMoveOfTwentyBlocksIsSampled(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(0, 1, 0))
        val start = BlockPos(origin.x and -16, origin.y, origin.z and -16)
        val end = start.offset(15, 0, 15)
        val joined = Joined(helper, start)
        helper.closingOnFailure(joined, SAMPLER_TICKS) {
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(joined.rows().isNotEmpty(), "first examination") }
                .thenExecute {
                    val join = joined.rows().single()
                    helper.assertValueEqual(SampleReason.JOIN, join.reason, "reason")
                    helper.assertValueEqual(start.toPos(level), join.pos, "position")
                    val biome = level.getBiome(start).registeredName
                    helper.assertEquals(biome, join.biome, "biome")
                    val entered = joined.events<BiomeEntered>().single()
                    helper.assertValueEqual(biome, entered.biome, "biome entered")
                    helper.assertTrue(entered.firstVisit, "a new player's first biome")
                    helper.assertTrue(biome in VisitedPlaces.of(joined.player).biomes, "kept on the player")

                    val live = helper.narrator().players.get(joined.uuid)
                    helper.assertEquals(start.toPos(level), live?.pos, "live position")
                    helper.assertEquals(biome, live?.biome, "live biome")

                    joined.player.teleportTo(end.x + 0.5, end.y.toDouble(), end.z + 0.5)
                }
                .thenWaitUntil { helper.assertValueEqual(2, joined.rows().size, "a second row") }
                .thenExecute {
                    val moved = joined.rows()[1]
                    helper.assertValueEqual(SampleReason.MOVED, moved.reason, "reason")
                    helper.assertValueEqual(end.toPos(level), moved.pos, "position")
                    helper.assertTrue(joined.events<Teleported>().isEmpty(), "21 blocks is no teleport")
                    joined.close()
                }
                .thenSucceed()
        }
    }

    /** Under a roof, without sky light, but above the depth of §7.4: a house is not underground. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aRoofAboveSeaLevelIsNotUnderground(helper: GameTestHelper) {
        val level = helper.level
        val under = helper.absolutePos(BlockPos(2, 1, 2))
        for (dx in -1..1) for (dz in -1..1) helper.setBlock(BlockPos(2 + dx, 3, 2 + dz), Blocks.STONE)
        val joined = AtomicReference<Joined>()
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { joined.get()?.close() }
        helper.startSequence()
            .thenAwait { helper.assertFalse(level.canSeeSky(under), "the light engine sees the roof") }
            .thenExecute { joined.set(Joined(helper, under)) }
            .thenWaitUntil { helper.assertTrue(joined.get().rows().isNotEmpty(), "first examination") }
            .thenExecute {
                val row = joined.get().rows().single()
                helper.assertTrue(under.y >= level.seaLevel - 8, "above the depth of §7.4")
                helper.assertTrue((row.skyLight ?: 15) < 15, "no sky light: ${row.skyLight}")
                helper.assertFalse(row.underground, "a roof is not underground")
                helper.assertEquals(false, helper.narrator().players.get(joined.get().uuid)?.underground, "live state")
                joined.get().close()
            }
            .thenSucceed()
    }

    private companion object {
        /** Enough ticks for the sampler's first examination, the 3-second cap and a move. */
        const val SAMPLER_TICKS = 400

        /** Two damage windows of 100 ticks, 50 ticks apart. */
        const val DAMAGE_TICKS = 400

        /** A survival player whose client has loaded: the only kind that takes damage. */
        fun vulnerable(player: ServerPlayer) {
            player.setGameMode(GameType.SURVIVAL)
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        }
    }
}

/**
 * A mock player who joins for one test and stands at [at], and the events published about it from its
 * join on. [close] stops the recording and removes the player (the current object, after a respawn);
 * closing twice is harmless.
 */
private class Joined(private val helper: GameTestHelper, at: BlockPos = helper.absolutePos(BlockPos(1, 1, 1))) : AutoCloseable {
    private val runtime = helper.narrator()
    private val owner = "observer-fabric-test-${UUID.randomUUID()}"

    /** Every event about a player since this one joined, filtered on read: tests run side by side. */
    val recorded = CopyOnWriteArrayList<GameEvent>()
    val player: ServerPlayer

    init {
        runtime.bus.subscribeSync("recorder", owner, { it.player != null }) { recorded += it.event }
        // Deprecated in 26.3, but still the only helper that puts a ServerPlayer in the player list.
        @Suppress("DEPRECATION")
        player = helper.makeMockServerPlayerInLevel()
        player.teleportTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
    }

    val uuid: UUID get() = player.uuid

    inline fun <reified T : GameEvent> events(): List<T> = recorded.filter { it.player?.uuid == uuid }.filterIsInstance<T>()

    fun rows(): List<PositionSampled> = events()

    override fun close() {
        runtime.bus.unsubscribeOwner(owner)
        val players = helper.level.server.playerList
        players.getPlayer(uuid)?.let { players.remove(it) }
    }
}
