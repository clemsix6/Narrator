package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the mechanics engine's host (M4-02, M4 §21.2): its state lives in the world's global
 * attachment; an act of the engine runs a real tool through the Director's own path, under the guards, as
 * an act of its own; and the server's ticks drive the parts.
 *
 * Each test that writes the world's attachment puts it back within the same tick: the tests running side
 * by side and the server's engine never see it change. The other tests build their engine on a
 * store in memory, which never reconciles with the server's canon. The tests that set off explosions run in
 * their own batch ([MECHANICS_ENVIRONMENT]): the Observer tells an explosion with every player 32 blocks around, and other
 * batches count the explosions near their own player.
 */
class MechanicsEngineGameTests {

    /** The state set through the world's store is in the global attachment, read back by a new store, and survives the world's codec. */
    @GameTest
    fun theGlobalAttachmentKeepsTheState(helper: GameTestHelper) {
        val server = helper.level.server
        val global = server.globalAttachments()
        val before = global.getAttached(MechanicsState.TYPE)
        try {
            val store = StateStore(server)
            helper.assertTrue(store.inWorld, "the world's store")
            store.update { MechanicsSamples.state }
            helper.assertEquals(MechanicsSamples.state, global.getAttached(MechanicsState.TYPE), "the state set in the global attachment")

            val again = StateStore(server)
            helper.assertEquals(MechanicsSamples.state, again.get(), "the state read back by a new store")
            helper.assertEquals(MechanicsSamples.state, again.snapshot, "its snapshot")

            // What the world writes at its save, and reads back at its load.
            helper.assertTrue(MechanicsState.TYPE.isPersistent, "the attachment is saved with the world")
            val codec = MechanicsState.TYPE.persistenceCodec()!!
            val saved = codec.encodeStart(NbtOps.INSTANCE, MechanicsSamples.state).getOrThrow()
            helper.assertEquals(MechanicsSamples.state, codec.parse(NbtOps.INSTANCE, saved).getOrThrow(), "the state through the world's save")

            var offThread: Throwable? = null
            Thread { offThread = runCatching { store.get() }.exceptionOrNull() }.apply { start() }.join()
            helper.assertTrue(offThread is IllegalStateException, "never read off the server thread: $offThread")
        } finally {
            if (before != null) global.setAttached(MechanicsState.TYPE, before) else global.removeAttached(MechanicsState.TYPE)
        }
        helper.succeed()
    }

    /**
     * An act of the engine runs the real `apply_effect` and `explosion` under the guards: refused or spared
     * for a protected player, it hurts an unprotected one and opens the punishment in the act's own name; a
     * second act is refused while that punishment runs. Each call is a `mechanic action` decision, never a
     * `tool_calls` row.
     */
    @GameTest(environment = MECHANICS_ENVIRONMENT)
    fun anEngineActGoesThroughTheRealToolAndItsGuards(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        floor(helper)
        bob.standAt(BlockPos(4, 1, 4))
        bob.makeVulnerable()
        val runtime = helper.narrator()
        val guards = runtime.guards as GuardsImpl
        val sink = CapturingSink(runtime.trace)
        val services = object : NarratorServices by runtime {
            override val trace: TraceSink = sink
        }
        val actions = EngineActions(services, ToolCatalog.allTools(services))
        // A firecracker a block away: it hurts, never kills.
        val blast = buildJsonObject {
            put("player", bob.name)
            put("power", 1.0)
            put("offset", 1)
        }
        val slowness = buildJsonObject {
            putJsonArray("players") { add(bob.name) }
            put("effect", "slowness")
            put("seconds", 20)
        }

        guards.setProtected(bob.uuid, bob.name, on = true)
        try {
            val act = actions.open("fired", RULE, bob.name)
            val refused = runBlocking { actions.run(act, "apply_effect", slowness) }
            helper.assertTrue(refused.text.startsWith("DENIED (${Rules.PROTECTED}): "), refused.text)
            val spared = runBlocking { actions.run(act, "explosion", blast) }
            helper.assertTrue(spared.status == ToolStatus.OK && "nobody hurt" in spared.text, spared.text)
            helper.assertEquals(20f, bob.live.health, "the protected player is spared")
            helper.assertEquals(null, GuardStates.read(bob.live).episode, "no punishment of a protected player")
        } finally {
            guards.setProtected(bob.uuid, bob.name, on = false)
        }

        val act = actions.open("fired", RULE, bob.name)
        val hit = runBlocking { actions.run(act, "explosion", blast) }
        helper.assertTrue(hit.text.startsWith("ok: explosion of power 1, ") && "hurt: ${bob.name} " in hit.text, hit.text)
        helper.assertTrue(bob.live.health < 20f && bob.live.isAlive, "hurt: ${bob.live.health}")
        helper.assertEquals(-act.id, GuardStates.read(bob.live).episode?.act, "the punishment is the act's own, never a turn's")
        helper.assertTrue(guards.death.isArmed(bob.live), "the death guard is armed")

        val other = runBlocking { actions.run(actions.open("fired", RULE, bob.name), "explosion", blast) }
        helper.assertTrue(other.text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}): "), "another act may not pile on: ${other.text}")

        helper.assertTrue(sink.toolCalls.isEmpty(), "no tool_calls row for the engine: ${sink.toolCalls}")
        val calls = sink.decisions.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == EngineActions.ACTION }
        helper.assertEquals(listOf("denied", "ok", "ok", "denied"), calls.map { it.detail!!.getValue("status").jsonPrimitive.content }, "one action per call")
        helper.assertEquals(bob.name, calls[2].subject, "the player the act affected")
        helper.assertEquals(-act.id, calls[2].detail!!.getValue("act").jsonPrimitive.long, "the act of the call")
        helper.assertEquals("mechanics:$RULE", calls[2].conversationId, "the rule's acts")
    }

    /**
     * A death just after an act of the engine is explained by that act (M4-04's `DeathReport` reads the
     * `mechanic action` decisions the acts write, M4 §4.3): the real `explosion`, run by the engine, then a
     * fall; the `death_guard died` decision names the explosion and the rule's act.
     */
    @GameTest(environment = MECHANICS_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aDeathAfterAnEngineActIsExplainedByIt(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val victim = TestPlayer(helper, "me_")
        val name = victim.name
        try {
            floor(helper)
            victim.standAt(BlockPos(4, 1, 4))
            victim.makeVulnerable()
            val actions = EngineActions(runtime, ToolCatalog.allTools(runtime))
            val blast = buildJsonObject {
                put("player", name)
                put("power", 1.0)
                put("offset", 1)
            }
            val hit = runBlocking { actions.run(actions.open("fired", DEATH_RULE, name), "explosion", blast) }
            helper.assertTrue(hit.status == ToolStatus.OK && "hurt: $name " in hit.text, hit.text)
            victim.live.hurtServer(helper.level, helper.level.damageSources().fall(), 1_000f)
            helper.assertTrue(victim.live.isDeadOrDying, "the player died")
        } finally {
            victim.close()
        }

        val reason = AtomicReference<String?>()
        CoroutineScope(Dispatchers.IO).launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (reason.get() == null && System.currentTimeMillis() < deadline) {
                delay(200)
                runtime.journal.barrier()
                reason.set(
                    runtime.journal.read(5_000) { connection ->
                        connection.prepareStatement("SELECT reason FROM engine_decisions WHERE kind = 'death_guard' AND decision = 'died' AND subject = ?").use { statement ->
                            statement.setString(1, name)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                        }
                    },
                )
            }
        }
        helper.awaitAsync {
            val sentence = reason.get()
            helper.assertTrue(sentence != null, "the death_guard died decision of $name")
            helper.assertTrue(
                Regex("the Narrator acted on $name in the last 1m: explosion \\d+s before \\(engine, mechanics:$DEATH_RULE\\)$").containsMatchIn(sentence!!),
                "the engine's act explains the death: $sentence",
            )
        }
    }

    /** The server's ticks reach the parts with the server; the second runs once a second of the engine's clock, then the situation. */
    @GameTest
    fun theServersTicksDriveTheParts(helper: GameTestHelper) {
        val server = helper.level.server
        val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening))
        val part = CountingPart()
        val engine = Engine(helper.narrator(), StateStore.inMemory(MechanicsSamples.state), listOf(part), clock)

        engine.start(server)
        repeat(9) {
            clock.advance(Duration.ofMillis(250))
            engine.tick(server)
        }
        engine.stop()

        helper.assertEquals(listOf(server), part.servers.distinct(), "the parts get the running server")
        helper.assertEquals(listOf(MechanicsSamples.evening), part.started, "started once, at the start")
        helper.assertEquals(9, part.ticks, "every tick")
        helper.assertEquals(listOf(1_000L, 2_000L).map { MechanicsSamples.evening + it }, part.seconds, "once a second of the engine's clock")
        helper.assertEquals(emptyList<String>(), engine.situationLines(), "nothing once stopped")
        helper.assertEquals(listOf("stopping"), part.calls, "stopped once")
        helper.succeed()
    }

    /** A part that counts what the engine gives it. */
    private class CountingPart : MechanicPart {
        val servers = CopyOnWriteArrayList<MinecraftServer>()
        val started = CopyOnWriteArrayList<Long>()
        val seconds = CopyOnWriteArrayList<Long>()
        val calls = CopyOnWriteArrayList<String>()
        var ticks = 0

        override fun started(server: MinecraftServer, now: Long) {
            servers += server
            started += now
        }

        override fun everyTick(server: MinecraftServer, now: Long) {
            servers += server
            ticks++
        }

        override fun everySecond(server: MinecraftServer, now: Long) {
            servers += server
            seconds += now
        }

        override fun situation(now: Long): List<String> = listOf("Challenges: at $now")

        override fun stopping() {
            calls += "stopping"
        }
    }

    /** A trace sink that keeps the decisions and tool calls it passes on to the Journal. */
    private class CapturingSink(private val inner: TraceSink) : TraceSink by inner {
        val decisions = CopyOnWriteArrayList<DecisionRecord>()
        val toolCalls = CopyOnWriteArrayList<ToolCallRecord>()

        override fun decision(r: DecisionRecord) {
            decisions += r
            inner.decision(r)
        }

        override fun toolCall(r: ToolCallRecord) {
            toolCalls += r
            inner.toolCall(r)
        }
    }

    private companion object {
        /** A rule of these tests, far from the ids of the server's canon. */
        val RULE = Ref(RefKind.CANON, 990_001)

        /** The rule whose act comes before a death. */
        val DEATH_RULE = Ref(RefKind.CANON, 990_002)

        /** The batch of the tests that set off explosions (`test_environment/mechanics.json`). */
        const val MECHANICS_ENVIRONMENT = "narrator2-gametest:mechanics"

        fun floor(helper: GameTestHelper) {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
        }
    }
}
