package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The acts of a deployment (M5 §4.4, task M5-07): one act of the engine per call of the module, in order, within the
 * SDK's limits; outcomes by callbacks, each in a call of its own; dropped at the end; the module's objects never
 * touched outside its own calls (design review, finding 8).
 */
class ActQueueTest {
    private val clock = TestClock()
    private val c70 = MechanicsSamples.canon(70)
    private val services = TestServices(clock = clock)
    private val host = Host()
    private val runner = Runner()

    /** The host of the module (M5-05's `ModuleHost`): armed while the module's own code runs. */
    private class Host : ModuleCall {
        @Volatile
        var armed = false
        val calls = CopyOnWriteArrayList<String>()

        override fun run(what: String, body: () -> Unit) {
            calls += what
            armed = true
            try {
                body()
            } finally {
                armed = false
            }
        }
    }

    /** The engine's acts: what was opened and run, and what each tool answers ([answer]); [gate] holds every run until completed. */
    private class Runner : ActRunner {
        val opened = CopyOnWriteArrayList<Pair<String?, String>>()
        val ran = CopyOnWriteArrayList<Triple<Long, String, JsonObject>>()
        var answer: (String, JsonObject) -> ToolResult = { tool, _ -> ToolResult.ok("ok: $tool") }
        var gate: CompletableDeferred<Unit>? = null
        private val ids = AtomicLong()

        override fun open(ref: Ref, subject: String?, reason: String): Firing {
            val id = ids.incrementAndGet()
            opened += subject to reason
            return Firing(id, ref, Caller(Role.DIRECTOR, "mechanics:$ref", null, null, id))
        }

        override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult {
            gate?.await()
            ran += Triple(firing.id, tool, args)
            return answer(tool, args)
        }
    }

    private class Setup(val queue: ActQueue, val acts: ModuleActs, val ground: GroundView, val creatures: CreatureControl)

    /** [dimensions]: the players' dimensions, each read recorded in [reads] (a lookup on the server thread in the game). */
    private fun TestScope.setup(dimensions: Map<String, String> = emptyMap(), reads: MutableList<String> = mutableListOf()): Setup {
        val queue = ActQueue(c70, runner, backgroundScope, clock, host, Executor { it.run() })
        val creatures = CreatureControl(services, c70.id, queue)
        val dimensionOf = { player: String -> dimensions[player].also { reads += player } }
        return Setup(queue, ModuleActs(queue, creatures, dimensionOf), GroundView(queue), creatures)
    }

    /** A callback of the module that records what it got, and whether its host was armed then. */
    private inner class Callback : Consumer<Outcome> {
        val got = CopyOnWriteArrayList<Pair<Outcome, Boolean>>()

        override fun accept(outcome: Outcome) {
            got += outcome to host.armed
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `the callbacks it may still call are the memory gauge's roots, until called or the end`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val first = Callback()
        val second = Callback()
        queue.invocation("start") {
            acts.say("Un.").then(first)
            acts.say("Deux.").then(second)
            acts.say("Trois.")
        }
        assertEquals(2, queue.callbacks().size)
        assertTrue(queue.callbacks().all { it === first || it === second })
        runCurrent()
        queue.deliver()
        assertEquals(emptyList(), queue.callbacks(), "called: no longer held")
        queue.invocation("timer") { acts.say("Quatre.").then(first) }
        assertEquals(1, queue.callbacks().size)
        queue.close()
        assertEquals(emptyList(), queue.callbacks(), "the end lets them go")
    }

    @Test
    fun `the acts of one call leave together, in order, as one act of the engine aimed at its first player`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        queue.invocation("start") {
            acts.say("Ça commence.")
            acts.punish("Bartok", "tiny", 1, "a bougé quand le Narrateur regardait")
            acts.sound(null, "block.bell.use")
        }
        assertTrue(runner.ran.isEmpty(), "nothing is done inline")
        runCurrent()

        assertEquals(listOf("say", "punish", "play_sound"), runner.ran.map { it.second }, "in order")
        assertEquals(setOf(1L), runner.ran.map { it.first }.toSet(), "one act of the engine")
        assertEquals("Bartok", runner.opened.single().first, "aimed at its first player")
        assertEquals("C-70: 3 acts of its module (say, punish, play_sound)", runner.opened.single().second)

        queue.invocation("timer") { acts.sound("Bartok", "entity.chicken.egg") }
        runCurrent()
        assertEquals(2L, runner.ran.last().first, "another call is another act of the engine")
        assertEquals(4, queue.asked)
        queue.close()
    }

    @Test
    fun `each act is written as the tool takes it`() = runTest {
        val (queue, acts, ground) = setup().let { Triple(it.queue, it.acts, it.ground) }
        val at = Place("minecraft:overworld", 10.7, 64.0, -3.2)
        queue.invocation("start") {
            acts.smite("Bartok", 2)
            acts.explosion(at, 2.0, false)
            acts.teleportRandom("Mimosa", 100)
            acts.title(null, "Top", null)
        }
        queue.invocation("timer") {
            acts.spawn("chicken", "Bartok", Spawn.of(6).withHeight(3).withName("Poulette"))
            ground.place(listOf(Cell("overworld", 1, 70, 2), Cell("overworld", 1, 71, 2)), "red_stained_glass", 30)
            acts.effect("Mimosa", "speed", 30, 2)
            acts.hud(null, "10 s")
        }
        runCurrent()

        val args = runner.ran.map { it.second to it.third }
        assertEquals(
            listOf(
                "lightning" to json("""{"player":"Bartok","damage_hearts":2,"offset":0}"""),
                "explosion" to json("""{"x":10,"y":64,"z":-4,"dimension":"overworld","power":2.0,"hurts":false,"offset":0}"""),
                "teleport" to json("""{"player":"Mimosa","random_radius":100}"""),
                "show_title" to json("""{"title":"Top"}"""),
                "spawn" to json(
                    """{"entity":"chicken","count":6,"near":"Bartok","distance":8,"name":"Poulette","target":false,"lifetime_minutes":5,"height":3,"baby":false}""",
                ),
                "place_blocks" to json(
                    """{"dimension":"overworld","cells":[{"x":1,"y":70,"z":2},{"x":1,"y":71,"z":2}],"block":"red_stained_glass","seconds":30}""",
                ),
                "apply_effect" to json("""{"players":["Mimosa"],"effect":"speed","seconds":30,"level":2}"""),
                "hud" to json("""{"text":"10 s"}"""),
            ),
            args,
        )
        queue.close()
    }

    @Test
    fun `beyond four acts in one call, the act is denied and nothing goes`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val fifth = Callback()
        queue.invocation("start") {
            repeat(ForgeLimits.ACTS_PER_CALL) { acts.sound("Bartok", "block.bell.use") }
            acts.punish("Bartok", "tiny", 1, "de trop").then(fifth)
        }
        runCurrent()
        queue.deliver()

        assertEquals(List(ForgeLimits.ACTS_PER_CALL) { "play_sound" }, runner.ran.map { it.second })
        val (outcome, armed) = fifth.got.single()
        assertEquals("denied", outcome.status())
        assertEquals("DENIED (module_acts): 4 acts in one call already, the most: act on the next call", outcome.text())
        assertTrue(armed, "in a call of its own")
        queue.close()
    }

    @Test
    fun `thirty acts a minute, then denied until the minute is over, hud never counted in it`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val late = Callback()
        repeat(ForgeLimits.ACTS_PER_MINUTE / 2) { queue.invocation("timer") { repeat(2) { acts.sound(null, "block.bell.use") } } }
        queue.invocation("timer") {
            acts.sound(null, "block.bell.use").then(late)
            acts.hud("Bartok", "10 s")
        }
        runCurrent()
        queue.deliver()
        assertEquals("denied", late.got.single().first.status())
        assertTrue(late.got.single().first.text().startsWith("DENIED (module_acts): 30 acts in the last minute already, the most; the next is possible in "), late.got.toString())
        assertEquals(ForgeLimits.ACTS_PER_MINUTE + 1, runner.ran.size, "the 30 acts and the hud")
        assertEquals("hud", runner.ran.last().second)

        clock.advance(Duration.ofSeconds(61))
        queue.invocation("timer") { acts.sound(null, "block.bell.use") }
        runCurrent()
        assertEquals(ForgeLimits.ACTS_PER_MINUTE + 2, runner.ran.size, "a minute later")
        queue.close()
    }

    @Test
    fun `four lines a minute, say, sayTo and title together`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val fifth = Callback()
        queue.invocation("timer") {
            acts.say("Un")
            acts.sayTo("Bartok", "Deux")
        }
        queue.invocation("timer") {
            acts.title("Mimosa", "Trois", null)
            acts.say("Quatre")
        }
        queue.invocation("timer") { acts.say("Cinq").then(fifth) }
        runCurrent()
        queue.deliver()
        assertEquals(listOf("say", "say", "show_title", "say"), runner.ran.map { it.second })
        assertTrue(fifth.got.single().first.text().startsWith("DENIED (module_acts): 4 lines (say, sayTo, title) in the last minute already"), fifth.got.toString())
        queue.close()
    }

    @Test
    fun `outcomes come back by the callbacks, each in a call of its own, at the tick after the act`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        runner.answer = { tool, _ -> if (tool == "punish") ToolResult.denied("protected", "Bartok is protected") else ToolResult.ok("ok: $tool") }
        val said = Callback()
        val punished = Callback()
        lateinit var pending: io.github.clemsix6.narrator2.forge.api.Pending
        queue.invocation("start") {
            acts.say("Bonjour").then(said)
            pending = acts.punish("Bartok", "tiny", 1, "a bougé")
        }
        queue.deliver()
        assertTrue(said.got.isEmpty(), "not before the act was done")
        runCurrent()
        queue.deliver()

        assertEquals(listOf("ok" to true), said.got.map { it.first.status() to it.second }, "in a call of its own")
        assertEquals(listOf("start", "callback"), host.calls)
        assertFailsWith<ForgeRefusal> { pending.then(punished).also { pending.then(punished) } }
        queue.deliver()
        assertEquals("DENIED (protected): Bartok is protected", punished.got.single().first.text(), "a callback given after the outcome, at the next tick")
        assertTrue(punished.got.single().second)
        queue.close()
    }

    @Test
    fun `a callback that acts opens its own act of the engine`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        queue.invocation("start") { acts.say("Un").then { acts.punish("Bartok", "tiny", 1, "a parlé") } }
        runCurrent()
        queue.deliver()
        runCurrent()
        assertEquals(listOf(1L to "say", 2L to "punish"), runner.ran.map { it.first to it.second })
        assertEquals(listOf(null, "Bartok"), runner.opened.map { it.first })
        queue.close()
    }

    @Test
    fun `the rest of a call given up and resumed by its host acts all the same, together, before the next call's acts`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val then = Callback()
        // The call is given up after its first act; its host resumes it between two invocations (FORGE-ABANDON).
        queue.invocation("fact") { acts.say("Trouvé.") }
        acts.sayTo("Bartok", "Bravo.").then(then)
        acts.sound("Bartok", "entity.player.levelup")
        runCurrent()
        assertEquals(listOf("say"), runner.ran.map { it.second }, "the rest waits for the call's end")

        queue.invocation("timer") { acts.hud(null, "1 s") }
        runCurrent()
        assertEquals(listOf(1L to "say", 2L to "say", 2L to "play_sound", 3L to "hud"), runner.ran.map { it.first to it.second }, "in order, the rest one act of the engine")
        assertEquals("C-70: 2 acts of its module (say, play_sound)", runner.opened[1].second)
        queue.deliver()
        assertEquals(listOf("ok"), then.got.map { it.first.status() }, "its callback, in a call of its own")
        assertEquals(listOf("fact", "timer", "callback"), host.calls.toList())

        acts.say("Encore.")
        queue.close()
        runCurrent()
        assertEquals(4, runner.ran.size, "what the rest asked when its deployment ended is dropped")
        assertEquals(1, queue.dropped)
    }

    @Test
    fun `what is still queued when the deployment ends is dropped, and no callback is called again`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val gate = CompletableDeferred<Unit>()
        runner.gate = gate
        val first = Callback()
        val second = Callback()
        queue.invocation("start") { acts.say("Un").then(first) }
        queue.invocation("timer") { acts.say("Deux").then(second) }
        runCurrent()

        queue.stopping()
        val during = Callback()
        queue.invocation("stop") { acts.say("Adieu").then(during) }
        queue.close()
        gate.complete(Unit)
        runCurrent()
        queue.deliver()

        assertEquals(listOf("say"), runner.ran.map { it.second }, "only the act already running went")
        assertTrue(first.got.isEmpty() && second.got.isEmpty() && during.got.isEmpty(), "no callback once ended")
        assertEquals(2, queue.dropped, "the second call's act and the one asked during stop")
    }

    @Test
    fun `an act asked while the module stops is dropped at once`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        queue.stopping()
        val during = Callback()
        queue.invocation("stop") { acts.say("Adieu").then(during) }
        queue.deliver()
        runCurrent()
        assertEquals(listOf("dropped" to true), during.got.map { it.first.status() to it.second })
        assertTrue(runner.ran.isEmpty())
        queue.close()
    }

    @Test
    fun `the module's objects are never called outside its own calls`() = runTest {
        val setup = setup()
        val challenges = FakeChallenges(clock, AtomicLong(75))
        challenges.views += challengeView()
        val game = ModuleGame(MechanicsSamples.canon(75), c70, challenges, setup.queue)
        val cells = Spy(listOf(Cell("overworld", 1, 70, 2)))
        val winners = Spy(listOf("Mimosa"))
        val callback = Callback()
        setup.queue.invocation("start") {
            setup.ground.place(cells, "glass", 10).then(callback)
            game.win(winners, "Mimosa a gagné")
        }
        runCurrent()
        setup.queue.deliver()
        runCurrent()
        setup.queue.deliver()

        assertTrue(cells.calls.isNotEmpty() && winners.calls.isNotEmpty(), "the lists were read")
        assertEquals(emptyList(), (cells.calls + winners.calls).filter { !it.second }, "every call of the module's lists while it was armed")
        assertTrue(cells.calls.none { it.first in setOf("hashCode", "equals", "toString") }, "never hashed, compared nor logged: ${cells.calls}")
        assertEquals(listOf(true), callback.got.map { it.second }, "its callback in a call of its own")
        setup.queue.close()
    }

    @Test
    fun `a module never gives wither, poison, instant damage nor levitation`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        val outcomes = Callback()
        queue.invocation("start") {
            acts.effect("Bartok", "wither", 10, 1).then(outcomes)
            acts.effect("Bartok", "minecraft:levitation", 10, 1).then(outcomes)
            acts.effect("Bartok", "Poison", 10, 1).then(outcomes)
        }
        queue.invocation("timer") { acts.effect("Bartok", "instant_damage", 1, 1).then(outcomes) }
        runCurrent()
        queue.deliver()
        assertTrue(runner.ran.isEmpty(), "nothing goes: ${runner.ran}")
        assertEquals(
            listOf(
                "DENIED (forbidden_effect): a module never gives wither: an effect that wounds is the Director's to give, one call at a time",
                "DENIED (forbidden_effect): a module never gives levitation: an effect that ends in a fall is the Director's to give, one call at a time",
                "DENIED (forbidden_effect): a module never gives poison: an effect that wounds is the Director's to give, one call at a time",
                "DENIED (forbidden_effect): a module never gives instant_damage: an effect that wounds is the Director's to give, one call at a time",
            ),
            outcomes.got.map { it.first.text() },
        )
        queue.close()
    }

    @Test
    fun `a module's summonings stay within its own creatures, and never a creature that blasts everyone around`() = runTest {
        val setup = setup()
        val (queue, acts) = setup.queue to setup.acts
        runner.answer = { tool, _ -> ToolResult.failed("$tool: no free ground") }
        val outcomes = Callback()
        queue.invocation("start") {
            repeat(3) { acts.spawn("zombie", "Bartok", Spawn.of(12)).then(outcomes) }
            acts.spawn("creeper", "Bartok", null).then(outcomes)
        }
        runCurrent()
        queue.deliver()
        assertEquals(listOf("spawn", "spawn"), runner.ran.map { it.second }, "two summonings of 12, the third over 32")
        assertEquals(
            listOf(
                "FAILED: spawn: no free ground",
                "FAILED: spawn: no free ground",
                "DENIED (module_creatures): 0 creatures of its own, 24 on their way, and 12 more asked: 32 at most; release some first",
                "DENIED (forbidden_creature): minecraft:creeper is never a module's: its blast hits every player around, not only its target",
            ),
            outcomes.got.map { it.first.text() }.sortedBy { !it.startsWith("FAILED") },
        )
        assertEquals(0, setup.creatures.count)
        assertTrue(setup.creatures.reserve(ForgeLimits.OWN_CREATURES), "the summonings that brought nothing gave their room back")
        queue.close()
    }

    @Test
    fun `a module moves a player within their own dimension only`() = runTest {
        val reads = mutableListOf<String>()
        val (queue, acts) = setup(mapOf("Bartok" to "overworld"), reads).let { it.queue to it.acts }
        val outcomes = Callback()
        queue.invocation("start") {
            acts.teleport("Bartok", Place("nether", 0.0, 64.0, 0.0)).then(outcomes)
            acts.teleport("Bartok", Place("overworld", 100.5, 70.0, -20.5)).then(outcomes)
            acts.teleport("Bartok", null).then(outcomes)
        }
        assertEquals(listOf("Bartok", "Bartok"), reads, "the dimension read once a move, refused or not, and not without a place (M5 review, finding 7)")
        runCurrent()
        queue.deliver()
        assertEquals(
            "Invalid arguments: a module moves a player within their own dimension: Bartok is in overworld, not the_nether",
            outcomes.got.first().first.text(),
        )
        assertEquals(listOf("teleport" to json("""{"player":"Bartok","x":100,"y":70,"z":-21}""")), runner.ran.map { it.second to it.third })
        queue.close()
    }

    @Test
    fun `a judged challenge's end is asked like an act and done by the engine, its winners among its participants`() = runTest {
        val setup = setup()
        val challenges = FakeChallenges(clock, AtomicLong(75))
        challenges.views += challengeView()
        val c75 = MechanicsSamples.canon(75)
        val game = ModuleGame(c75, c70, challenges, setup.queue)
        assertEquals("C-75", game.id())
        assertEquals(listOf("Mimosa", "Bartok"), game.players())
        assertTrue(game.running())
        challenges.views[0] = challengeView().copy(status = ChallengeStatus.JUDGING)
        assertTrue(game.running(), "past its end, it waits for a verdict the module may still give")

        val outcomes = Callback()
        setup.queue.invocation("timer") {
            game.win(listOf("Josiane"), "Josiane a gagné").then(outcomes)
            game.win(listOf("mimosa"), "Mimosa a été le moins longtemps loup").then(outcomes)
        }
        runCurrent()
        setup.queue.deliver()

        assertEquals("Invalid arguments: Josiane not among the participants of C-75: Mimosa, Bartok", outcomes.got[0].first.text())
        assertEquals("ok", outcomes.got[1].first.status())
        val ended = challenges.ended.single()
        assertEquals(
            FakeChallenges.Ended(c75, ChallengeOutcome.WON, listOf(FakeChallenges.playerId("Mimosa")), "Mimosa a été le moins longtemps loup", Author(Source.ENGINE)),
            ended,
        )
        assertTrue(runner.opened.isEmpty(), "no tool, no act of the engine")
        assertTrue(!game.running(), "over")
        setup.queue.close()
    }

    @Test
    fun `then needs a callback`() = runTest {
        val (queue, acts) = setup().let { it.queue to it.acts }
        queue.invocation("start") { assertFailsWith<ForgeRefusal> { acts.say("Un").then(null) } }
        queue.close()
    }

    private fun challengeView() = ChallengeView(
        MechanicsSamples.canon(75), null, null, ChallengeStatus.RUNNING, MechanicsSamples.evening + 600_000, listOf(
            Participant(FakeChallenges.playerId("Mimosa").toString(), "Mimosa"),
            Participant(FakeChallenges.playerId("Bartok").toString(), "Bartok"),
        ),
        0, "C-75 judged",
    )

    /** A list as a module may write one: each call recorded, with whether the module's host was armed then. */
    private inner class Spy<T>(private val items: List<T>) : java.util.AbstractList<T>() {
        val calls = CopyOnWriteArrayList<Pair<String, Boolean>>()

        override fun get(index: Int): T {
            calls += "get" to host.armed
            return items[index]
        }

        override val size: Int
            get() {
                calls += "size" to host.armed
                return items.size
            }

        override fun hashCode(): Int {
            calls += "hashCode" to host.armed
            return 0
        }

        override fun equals(other: Any?): Boolean {
            calls += "equals" to host.armed
            return false
        }

        override fun toString(): String {
            calls += "toString" to host.armed
            return "spy"
        }
    }
}
