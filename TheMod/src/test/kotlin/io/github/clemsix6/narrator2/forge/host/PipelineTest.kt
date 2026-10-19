package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.arsenal.Manifest
import io.github.clemsix6.narrator2.forge.compile.EcjCompiler
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sdk.ArgsView
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.Scheduler
import io.github.clemsix6.narrator2.forge.sdk.StorageMap
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Random
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A module from its source to its unloading (M5 §5.4, §3.5, §3.7): the witness compiled by ECJ on its own thread,
 * clean to the guard, its manifest read, rewritten, loaded by its own loader, made and started by the host on its
 * module thread with the SDK's real pieces (its parameters, facts, timers and state), run for 25 seconds of ticks, its
 * facts delivered and its timers fired as the engine does, stopped, unloaded and collected.
 */
class PipelineTest {
    @Test
    fun `the witness module runs from its source to its unloading`() = runBlocking<Unit> {
        val compiler = EcjCompiler()
        val compiled = try {
            compiler.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE, ForgeSamples.NAME)
        } finally {
            compiler.stop()
        }
        assertTrue(compiled.ok, "${compiled.problems}")
        assertEquals(emptyList(), BytecodeGuard().check(compiled.classes))
        val manifest = Manifest.read(ForgeSamples.NAME, compiled.classes)
        val classes = Instrumenter.rewrite(ForgeSamples.CLASS, compiled.classes)

        val threads = ModuleThreads("pipeline-module")
        val faults = mutableListOf<Fault>()
        val world = Players()
        val context = Context(ArgsView(manifest.params, ForgeSamples.params), world)
        val host = ModuleHost(ForgeSamples.CLASS, classes, threads, "C-70 ${ForgeSamples.NAME}") { faults += it }
        try {
            val started = System.nanoTime()
            host.start(context)
            val startup = (System.nanoTime() - started) / 1e6
            assertNull(host.disabled, "started")
            assertEquals(setOf("wolf"), context.storage.snapshot().keys)
            assertTrue(context.acts.single().startsWith("say: « "), "${context.acts}")

            var now = 0L
            for (tick in 1..500) {
                now += 50
                if (tick == 100) context.events.offer(GameFact.Chat("Mimosa", world.place, "score"))
                if (tick == 200) context.events.offer(GameFact.BlockBroken("Bartok", world.place, "diamond_ore", false))
                if (tick == 300) world.close = true
                for (delivery in context.events.drain(now)) if (delivery.live) host.call("fact") { delivery.deliver() }
                context.timers.advance()
                while (true) {
                    val due = context.timers.next() ?: break
                    host.call("timer") { due.run() }
                }
                context.callbacks.removeFirstOrNull()?.let { callback -> host.call("callback") { callback.accept(Outcome("ok", "ok", emptyList())) } }
            }
            context.events.stopping()
            context.timers.stopping()
            assertNull(host.stop(StopReason.EXPIRED)?.fault)
            assertEquals(emptyList(), faults)
            assertTrue(context.acts.any { it.startsWith("sayTo Mimosa: ") }, "the score asked by chat: ${context.acts}")
            assertTrue(context.acts.any { it.startsWith("hud") }, "a touch shown: ${context.acts}")
            assertTrue(context.logs.any { "the round ran out" in it }, "${context.logs}")
            assertTrue(host.msPerSecond >= 0)
            println("[host] the witness from source to stop: startup (constructor and start) %.1f ms, %d acts, %d log lines".format(startup, context.acts.size, context.logs.size))
        } finally {
            context.events.close()
            context.timers.close()
            threads.close()
        }
        val unloaded = host.unload()
        assertTrue(Hosts.collected(listOf(unloaded)), "the module's loader is collected")
    }

    /** Bartok and Mimosa online, far apart, then [close] together. */
    private class Players {
        val place = Place("overworld", 0.5, 64.0, 0.5)
        var close = false

        fun info(name: String): PlayerInfo? {
            val at = when (name) {
                "Bartok" -> place
                "Mimosa" -> if (close) place.offset(1.0, 0.0, 0.0) else place.offset(30.0, 0.0, 0.0)
                else -> return null
            }
            return PlayerInfo(name, "uuid-$name", at, 20f, 20f, 20, "survival", true, false, false, false, false, 0f, 0f, "air", false)
        }
    }

    /** The real SDK pieces of a deployment (M5-06), and acts recorded (M5-07's are tested with the game). */
    private class Context(private val arguments: ArgsView, private val players: Players) : ModuleContext {
        val events = FactQueue()
        val timers = Scheduler()
        val storage = StorageMap()
        val acts = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val callbacks = ArrayDeque<Consumer<Outcome>>()
        private val random = Random(7)

        override fun id() = "C-70"

        override fun restarted() = false

        override fun now() = 0L

        override fun endsAt() = 600_000L

        override fun random() = random

        override fun args(): Args = arguments

        override fun events(): Events = events

        override fun timers(): Timers = timers

        override fun world(): World = fake(World::class.java) { method, arguments -> if (method.name == "player") players.info(arguments[0] as String) else null }

        override fun acts(): Acts = fake(Acts::class.java) { method, arguments ->
            acts += when (method.name) {
                "say" -> "say: ${arguments[0]}"
                "sayTo" -> "sayTo ${arguments[0]}: ${arguments[1]}"
                else -> "${method.name} ${arguments.firstOrNull()}"
            }
            fake(Pending::class.java) { _, given ->
                @Suppress("UNCHECKED_CAST")
                callbacks += given[0] as Consumer<Outcome>
                null
            }
        }

        override fun creatures(): Creatures = fake(Creatures::class.java) { _, _ -> null }

        override fun ground(): Ground = fake(Ground::class.java) { _, _ -> null }

        override fun storage(): Storage = storage

        override fun game(): Game? = null

        override fun log(line: String) {
            logs += line
        }

        override fun tellDirector(note: String) {
            logs += "director: $note"
        }

        override fun end(why: String) {
            logs += "end: $why"
        }

        private fun <T : Any> fake(type: Class<T>, answer: (java.lang.reflect.Method, Array<Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, given -> answer(method, given ?: arrayOf()) })
    }
}
