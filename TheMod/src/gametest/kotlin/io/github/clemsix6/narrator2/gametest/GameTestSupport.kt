package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.observer.ref
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.delay
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestSequence
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Helpers shared by every in-game test class. Each task has its own test class, listed in
 * `src/gametest/resources/fabric.mod.json`; tests of one batch run side by side, so a test that listens
 * to the bus subscribes with its own owner and filters on its own mock player, and only
 * `NarratorGameTests.reloadKeepsTheServicesAndSwapsTheSettings` replaces the settings.
 */

/** Tick budget of the tests that use [awaitAsync] or [thenAwait]: at least 10 s of wall-clock time. */
const val ASYNC_MAX_TICKS = 10_000

/** The fake LLMGW key of the in-game tests, set in the environment of the `gameTest` run (build.gradle.kts). */
const val KEY_ENV = "NARRATOR2_GAMETEST_KEY"

/** The running Narrator of this test's server; fails the test when there is none. */
fun GameTestHelper.narrator(): NarratorRuntime {
    val runtime = NarratorRuntime.current
    assertTrue(runtime != null && runtime.server === level.server, "the Narrator does not run on this server")
    return runtime!!
}

/** Like `assertValueEqual`, for values that may be null (which `assertValueEqual` does not accept from Kotlin). */
fun GameTestHelper.assertEquals(expected: Any?, actual: Any?, what: String) =
    assertTrue(expected == actual, "$what: expected $expected, got $actual")

/**
 * Succeeds once [assertions] pass, waiting on work done by other threads (network, coroutines).
 *
 * The GameTest server runs its ticks back to back, thousands per second, so a tick budget alone
 * cannot bound such a wait. Each failed check therefore sleeps 1 ms, which turns the test's
 * `maxTicks` into at least as many milliseconds. A passing test still ends as soon as it succeeds.
 */
fun GameTestHelper.awaitAsync(assertions: () -> Unit) = succeedWhen(slowRetries(assertions))

/** Like [awaitAsync], as a step of a sequence: the next step runs once [assertions] pass. */
fun GameTestSequence.thenAwait(assertions: () -> Unit): GameTestSequence = thenWaitUntil(slowRetries(assertions))

private fun slowRetries(assertions: () -> Unit) = Runnable {
    try {
        assertions()
    } catch (e: GameTestAssertException) {
        Thread.sleep(1)
        throw e
    }
}

/**
 * Waits until [condition] holds, checking every 50 ms, for what ticks bring about: [played] counts the ticks so far (of an
 * engine of the test's own, say). It fails only once [ticks] of them have passed since the wait began **and** [timeoutMs]
 * of the wall clock (FLAKY-5). A batch of thirty tests slows the server down to a dozen ticks a second, and a wait counted
 * in seconds alone ran out before the ticks it waited for; a server alone runs hundreds of ticks a second, and the tools'
 * coroutines still have their seconds. Never off by more than a check: the ticks are counted before the condition is.
 */
suspend fun awaitTicks(what: String, ticks: Int, played: suspend () -> Int, timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
    val from = played()
    val began = System.currentTimeMillis()
    while (true) {
        val passed = played() - from
        if (condition()) return
        val elapsed = System.currentTimeMillis() - began
        check(passed < ticks || elapsed < timeoutMs) { "timed out waiting for: $what ($passed ticks and $elapsed ms; $ticks ticks and $timeoutMs ms allowed)" }
        delay(50)
    }
}

/**
 * Closes [resource] when the test ends, however it ends: right away if the synchronous part of the
 * test throws, else at the last tick before the timeout (a test that succeeds closes it itself).
 */
fun <T : AutoCloseable, R> GameTestHelper.closingOnFailure(resource: T, maxTicks: Int, block: (T) -> R): R {
    runAfterDelay((maxTicks - 1).toLong()) { resource.close() }
    return try {
        block(resource)
    } catch (e: Throwable) {
        resource.close()
        throw e
    }
}

/**
 * A player with a unique name, who joins for one test and records the packets the server sends them
 * that [keep] picks (by default: chat, sounds, titles, motion and teleports; not the chunks). Mock players
 * are all called `test-mock-player`, and the tools address players by name. The player joins as a
 * real one does (`placeNewPlayer`: the join events, `PlayerStates`), at the level's spawn, in the
 * default game mode; [close] removes them.
 *
 * @param prefix start of the name, 3 characters at most (the name is 16 characters at most).
 */
class TestPlayer(
    private val helper: GameTestHelper,
    prefix: String = "tp_",
    keep: (Packet<*>) -> Boolean = ::isOfInterest,
) : AutoCloseable {
    val name = prefix + UUID.randomUUID().toString().replace("-", "").take(16 - prefix.length)
    private val connection = PacketRecordingConnection(keep)
    val player: ServerPlayer

    init {
        require(prefix.length <= 3) { "a prefix of 3 characters at most" }
        val server = helper.level.server
        val profile = GameProfile(UUID.randomUUID(), name)
        player = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false))
        forget()
    }

    val uuid: UUID get() = player.uuid
    val ref: PlayerRef get() = player.ref()
    val packets: List<Packet<*>> get() = connection.recorded

    /** The live player object: a new one after a respawn. */
    val live: ServerPlayer get() = helper.level.server.playerList.getPlayer(uuid) ?: player

    /** Forgets what was received so far (the "joined the game" lines, for instance). */
    fun forget() = connection.recorded.clear()

    /** The chat lines received (not the lines above the hotbar). */
    fun chat(): List<ClientboundSystemChatPacket> = packets.filterIsInstance<ClientboundSystemChatPacket>().filterNot { it.overlay() }

    /** Moves the player to [pos], relative to the test, standing at the bottom center of the block. */
    fun standAt(pos: BlockPos) {
        val at = helper.absolutePos(pos)
        live.teleportTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
    }

    /** Survival, and the client has loaded: the only kind of player that takes damage (`handoff/T05.md`). */
    fun makeVulnerable() {
        live.setGameMode(GameType.SURVIVAL)
        live.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
    }

    override fun close() {
        val players = helper.level.server.playerList
        players.getPlayer(uuid)?.let { players.remove(it) }
    }

    private companion object {
        /** The packets tests look at; chunks and the like would only fill the memory. */
        fun isOfInterest(packet: Packet<*>): Boolean =
            packet is ClientboundSystemChatPacket || packet is ClientboundSoundPacket || packet is ClientboundSetTitlesAnimationPacket ||
                packet is ClientboundSetSubtitleTextPacket || packet is ClientboundSetTitleTextPacket ||
                packet is ClientboundSetEntityMotionPacket || packet is ClientboundPlayerPositionPacket
    }
}

/** A connection that keeps the packets [keep] picks, and sends everything on as usual. */
private class PacketRecordingConnection(private val keep: (Packet<*>) -> Boolean) : Connection(PacketFlow.SERVERBOUND) {
    val recorded = CopyOnWriteArrayList<Packet<*>>()

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        if (keep(packet)) recorded += packet
        super.send(packet, listener, flush)
    }
}

/** Runs [test] with [count] new [TestPlayer]s, removed however it ends; the test succeeds when [test] returns. */
fun withTestPlayers(helper: GameTestHelper, count: Int, test: (List<TestPlayer>) -> Unit) {
    val players = mutableListOf<TestPlayer>()
    try {
        repeat(count) { players += TestPlayer(helper) }
        players.forEach { it.forget() } // each one got the next ones' "joined the game"
        test(players)
    } finally {
        players.forEach { it.close() }
    }
    helper.succeed()
}

/** A command source that records what commands send back, to run `/narrator …` in a test. */
class CommandOutput : CommandSource {
    val lines = CopyOnWriteArrayList<String>()

    override fun sendSystemMessage(message: Component) {
        lines += message.string
    }

    override fun acceptsSuccess() = true

    override fun acceptsFailure() = true

    override fun shouldInformAdmins() = false
}
