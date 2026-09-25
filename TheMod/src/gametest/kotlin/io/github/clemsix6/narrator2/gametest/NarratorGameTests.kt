package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.core.ServerThreadTimeoutException
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.observer.ChunkPlacements
import io.github.clemsix6.narrator2.observer.ObserverState
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.pos
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.reply
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * In-game tests of the runtime (M1_M2 §17.3), run headless by `gradlew runGameTest`: the server-lived
 * services, `/narrator reload` and ping, the index of placed blocks and the player states.
 */
class NarratorGameTests {

    @GameTest
    fun runtimeStartsWithTheServer(helper: GameTestHelper) {
        val runtime = helper.narrator()
        helper.assertValueEqual(Phase.RUNNING, runtime.phase, "phase")
        helper.assertTrue(
            runtime.journal.available || runtime.journal is DisabledJournal,
            "the Journal is open, or disabled with a reason: ${runtime.journal.health().lastError}",
        )
        helper.assertTrue(ObserverState.current() === runtime.observer, "the Observer's hooks reach this server's state")
        helper.assertTrue(abs(runtime.ticks.gameTime - helper.level.server.overworld().gameTime) <= 1, "the bus's game time follows the ticks")
        helper.assertTrue(Files.exists(NarratorRuntime.configFile), "no ${NarratorRuntime.configFile}")
        helper.assertTrue(Files.exists(NarratorRuntime.promptsDir.resolve(Prompts.README)), "no README for prompt overrides")
        helper.assertTrue(
            Prompts.NAMES.none { Files.exists(NarratorRuntime.promptsDir.resolve("$it.md")) },
            "prompts must not be copied out of the jar",
        )
        helper.succeed()
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun blockPlacedIsReadBackFromAnotherThread(helper: GameTestHelper) {
        val pos = BlockPos(1, 1, 1)
        helper.setBlock(pos, Blocks.DIAMOND_BLOCK)
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, pos)

        val seen = AtomicReference<Block>()
        val server = helper.level.server
        CoroutineScope(Dispatchers.Default).launch {
            seen.set(server.onServerThread { helper.getBlockState(pos).block })
        }
        helper.awaitAsync {
            helper.assertTrue(seen.get() == Blocks.DIAMOND_BLOCK, "block read through onServerThread: ${seen.get()}")
        }
    }

    /**
     * While the server thread is busy (here, blocked by the test itself), `onServerThread` times out
     * with an ordinary exception, and the block is skipped when the server thread gets to it.
     */
    @GameTest
    fun serverThreadTimeoutIsAnOrdinaryFailure(helper: GameTestHelper) {
        val server = helper.level.server
        val failure = AtomicReference<Throwable>()
        val ran = AtomicBoolean(false)
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                server.onServerThread(timeoutMs = 100) { ran.set(true) }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        runBlocking { withTimeout(5_000) { job.join() } }
        helper.assertTrue(failure.get() is ServerThreadTimeoutException, "expected a ServerThreadTimeoutException, got ${failure.get()}")
        helper.runAfterDelay(5) {
            helper.assertFalse(ran.get(), "the block ran after its timeout")
            helper.succeed()
        }
    }

    /**
     * The only test that replaces the settings. Against the fake Anthropic server: `/narrator reload`
     * installs new settings and keeps every service; `status` shows them; a `ping` is held by the
     * gateway, a second one is refused, and a second reload meanwhile does not cut it: the ping's client
     * answers, then closes. The key comes from the environment and never shows up in a command output
     * or a log line.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun reloadKeepsTheServicesAndSwapsTheSettings(helper: GameTestHelper) {
        val logs = LogCapture()
        val fake = FakeAnthropic()
        helper.closingOnFailure(fake, ASYNC_MAX_TICKS) {
            helper.closingOnFailure(logs, ASYNC_MAX_TICKS) { reloadAndPing(helper, fake, logs) }
        }
    }

    private fun reloadAndPing(helper: GameTestHelper, fake: FakeAnthropic, logs: LogCapture) {
        val key = System.getenv(KEY_ENV)
        helper.assertTrue(!key.isNullOrEmpty(), "$KEY_ENV is not set by the gameTest run")
        val runtime = helper.narrator()
        val bus = runtime.bus
        val journal = runtime.journal
        val players = runtime.players
        val director = runtime.director
        val before = runtime.settings.get()

        val hold = Hold()
        fake.enqueue(reply { hold(hold); thinking("An operator is checking the line."); text("Pong. I hear you, alas."); usage(input = 80, output = 12) })
        Files.writeString(
            NarratorRuntime.configFile,
            """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 } }""",
        )
        val output = CommandOutput()
        val source = helper.level.server.createCommandSourceStack().withSource(output)
        val commands = helper.level.server.commands
        fun reloads() = output.lines.count { "configuration and prompts reloaded (key present)" in it }
        val pingClient = AtomicReference<ClaudeClient>()

        commands.performPrefixedCommand(source, "narrator reload")
        helper.startSequence()
            .thenAwait { helper.assertValueEqual(1, reloads(), "first reload: ${output.lines}") }
            .thenExecute {
                helper.assertTrue(NarratorRuntime.current === runtime, "the runtime is not rebuilt")
                helper.assertTrue(runtime.bus === bus && runtime.journal === journal, "same bus and Journal")
                helper.assertTrue(runtime.players === players && runtime.director === director, "same player states and Director")
                val after = runtime.settings.get()
                helper.assertTrue(after !== before, "the settings are replaced")
                helper.assertTrue(after.claude != null, "a client is built with the key")

                output.lines.clear()
                commands.performPrefixedCommand(source, "narrator status")
                helper.assertTrue(output.lines.any { "Phase: running · settings: loaded" in it }, "status: ${output.lines}")
                helper.assertTrue(output.lines.any { fake.baseUrl in it && "cache marker off" in it }, "status: ${output.lines}")
                helper.assertTrue(output.lines.any { "Key: present (environment variable $KEY_ENV)" in it }, "status: ${output.lines}")
                helper.assertTrue(output.lines.any { "director: claude-opus-5-5 · effort medium" in it }, "status: ${output.lines}")

                output.lines.clear()
                pingClient.set(after.claude)
                commands.performPrefixedCommand(source, "narrator ping Are you there?")
                helper.assertTrue(output.lines.any { "ping sent to Claude" in it }, "ping: ${output.lines}")
                helper.assertTrue(hold.awaitReached(), "the ping reached the gateway")
                commands.performPrefixedCommand(source, "narrator ping Again?")
                helper.assertTrue(output.lines.any { "a ping is already waiting for Claude" in it }, "second ping: ${output.lines}")
                commands.performPrefixedCommand(source, "narrator reload")
            }
            .thenAwait { helper.assertValueEqual(1, reloads(), "reload during the ping: ${output.lines}") }
            .thenExecute {
                helper.assertTrue(runtime.settings.get().claude !== pingClient.get(), "the second reload installed a new client")
                helper.assertFalse(pingClient.get().isClosed, "the ping's client stays open while the ping waits")
                hold.release()
            }
            .thenAwait {
                helper.assertTrue(output.lines.any { "Pong. I hear you, alas." in it }, "ping answer: ${output.lines}")
                helper.assertTrue(pingClient.get().isClosed, "the replaced client closed once the ping was over")
            }
            .thenExecute {
                helper.assertTrue(output.lines.any { "input 80 (+0 cache read, 0 cache write) · output 12" in it }, "ping stats: ${output.lines}")
                val request = fake.requests.single()
                helper.assertValueEqual("Are you there?", request.json.path("messages").path(0).path("content").asText(), "ping message")
                helper.assertValueEqual(key, request.header("X-Api-Key").orEmpty(), "API key header")
                helper.assertTrue(output.lines.none { key in it }, "a command output shows the key")
                helper.assertTrue(key !in logs.text, "a log line shows the key")
                logs.close()
                fake.close()
            }
            .thenSucceed()
    }

    @GameTest
    fun placedBlocksSurviveTheirCodec(helper: GameTestHelper) {
        val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
        val gone = UUID.fromString("00000000-0000-4000-8000-0000000000c0")
        val placements = ChunkPlacements(
            mutableListOf(gone, alice, bob),
            Int2IntOpenHashMap().apply {
                put(0x1_2_3, 1)
                put(0x40_F_F, 2)
                put(0x7F_0_0, 1)
            },
        )
        helper.assertTrue(PlacedBlocks.TYPE.isPersistent, "the index is saved with the chunk")

        val saved = ChunkPlacements.CODEC.encodeStart(NbtOps.INSTANCE, placements).getOrThrow()
        val restored = ChunkPlacements.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow()
        helper.assertValueEqual(listOf(alice, bob), restored.placers.toList(), "placers without positions are dropped")
        helper.assertEquals(alice, restored.placerAt(0x1_2_3), "0x123")
        helper.assertEquals(bob, restored.placerAt(0x40_F_F), "0x40ff")
        helper.assertEquals(alice, restored.placerAt(0x7F_0_0), "0x7f00")
        helper.assertEquals(null, restored.placerAt(0x5), "a position nobody placed")

        val broken = (saved.copy() as CompoundTag).apply { put("owners", IntArrayTag(intArrayOf(0))) }
        helper.assertTrue(ChunkPlacements.CODEC.parse(NbtOps.INSTANCE, broken).isError, "mismatched arrays are refused")
        helper.succeed()
    }

    /**
     * Marking a block changes the chunk's attachment in place, so the chunk must be marked unsaved by
     * hand (OBSERVER_API.md §6.1); marking the same block again for the same player changes nothing.
     */
    @GameTest
    fun markingABlockMarksItsChunkUnsaved(helper: GameTestHelper) {
        val level = helper.level
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val stone = Blocks.STONE.defaultBlockState()
        val pos = helper.absolutePos(BlockPos(1, 1, 1))
        val chunk = level.getChunkAt(pos)

        chunk.tryMarkSaved()
        PlacedBlocks.mark(level, pos, stone, alice)
        helper.assertTrue(chunk.isUnsaved, "a new mark makes the chunk unsaved")
        helper.assertEquals(alice, PlacedBlocks.placerAt(level, pos), "placer")

        chunk.tryMarkSaved()
        PlacedBlocks.mark(level, pos, stone, alice)
        helper.assertFalse(chunk.isUnsaved, "the same mark again changes nothing")

        PlacedBlocks.mark(level, pos, stone, bob)
        helper.assertTrue(chunk.isUnsaved, "another placer is a change")
        helper.assertEquals(bob, PlacedBlocks.placerAt(level, pos), "new placer")

        chunk.tryMarkSaved()
        helper.assertEquals(bob, PlacedBlocks.unmark(level, pos, stone), "unmark returns the placer")
        helper.assertTrue(chunk.isUnsaved, "an unmark is a change")
        helper.assertEquals(null, PlacedBlocks.placerAt(level, pos), "forgotten")
        helper.assertEquals(null, PlacedBlocks.unmark(level, pos, stone), "nothing left to unmark")
        helper.succeed()
    }

    @GameTest
    fun doubleBlocksAreMarkedWhole(helper: GameTestHelper) {
        val level = helper.level
        val alice = UUID.randomUUID()
        val door = helper.absolutePos(BlockPos(0, 1, 0))
        val lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
        val upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)

        PlacedBlocks.mark(level, door, lower, alice)
        helper.assertEquals(alice, PlacedBlocks.placerAt(level, door.above()), "upper half of the door")
        helper.assertEquals(alice, PlacedBlocks.unmark(level, door.above(), upper), "breaking the upper half")
        helper.assertEquals(null, PlacedBlocks.placerAt(level, door), "the lower half goes with it")

        val foot = helper.absolutePos(BlockPos(2, 1, 0))
        val bed = Blocks.BED.red().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
        PlacedBlocks.mark(level, foot, bed, alice)
        helper.assertEquals(alice, PlacedBlocks.placerAt(level, foot.relative(Direction.NORTH)), "head of the bed")
        helper.succeed()
    }

    /** The player states follow the joins, deaths, respawns and leaves published on the bus. */
    @GameTest
    fun playerStatesFollowJoinsAndDeaths(helper: GameTestHelper) {
        val runtime = helper.narrator()
        // Deprecated in 26.3, but still the only helper that puts a ServerPlayer in the player list.
        @Suppress("DEPRECATION")
        val player = helper.makeMockServerPlayerInLevel()
        try {
            val uuid = player.uuid
            runtime.bus.publish(PlayerJoined(player.ref(), player.pos(), sessionId = 9_901, firstTime = false, isOp = false))
            val joined = runtime.players.get(uuid)
            helper.assertTrue(joined != null, "the player is online")
            helper.assertValueEqual(9_901L, joined!!.sessionId, "session")
            helper.assertValueEqual(player.gameMode().serializedName, joined.gameMode, "game mode read from the live player")
            helper.assertValueEqual(player.health, joined.health, "health read from the live player")
            helper.assertTrue(runtime.players.online.any { it.ref.uuid == uuid }, "in the snapshot")

            val deathAt = player.pos()
            runtime.bus.publish(
                PlayerDied(player.ref(), deathAt, "minecraft:fall", killer = null, weapon = null, message = null, xpLevel = 0, keptInventory = false, narrator = false),
            )
            val dead = runtime.players.get(uuid)!!
            helper.assertTrue(dead.lastDeathAt != null, "the death is dated")
            helper.assertValueEqual(0f, dead.health, "health after death")

            runtime.bus.publish(PlayerRespawned(player.ref(), player.pos(), alive = false))
            helper.assertValueEqual(player.health, runtime.players.get(uuid)!!.health, "health after respawn")
            helper.assertEquals(dead.lastDeathAt, runtime.players.get(uuid)!!.lastDeathAt, "the death stays known")

            runtime.bus.publish(PlayerLeft(player.ref(), player.pos(), sessionId = 9_901, sessionSeconds = 1, reason = LeaveReason.LEAVE))
            helper.assertTrue(runtime.players.get(uuid) == null, "the player left")
        } finally {
            helper.level.server.playerList.remove(player)
        }
        helper.succeed()
    }
}
