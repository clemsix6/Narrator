package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Scheduled
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.api.Subscription
import io.github.clemsix6.narrator2.forge.api.test.Bench
import io.github.clemsix6.narrator2.forge.compile.Diagnostic
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.ActRunner
import io.github.clemsix6.narrator2.forge.sdk.CreatureControl
import io.github.clemsix6.narrator2.forge.sdk.GroundView
import io.github.clemsix6.narrator2.forge.sdk.ModuleActs
import io.github.clemsix6.narrator2.forge.sdk.ModuleCall
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bench (M5 §6.2, §7), with modules written in Kotlin: no compiler, no rewriting, no meter, but each call on a
 * module thread of the test's host ([KotlinHost]), as in the game. Each part of the simulated world and of the SDK
 * (the game's own pieces where they need no server), the smoke run (memory retained included), the dry checks
 * through the real tools, the scenarios, the report's budget, an abandoned call; and the witness module of M5-01
 * (compiled by the tests' ECJ) passes its own scenarios.
 */
class BenchTest {
    @BeforeEach
    fun reset() = Scripted.reset()

    // ── Time, facts, timers ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tick is 50 ms of the module's clock, and timers run when due, again when they repeat, never once cancelled`() {
        val seen = CopyOnWriteArrayList<String>()
        var every: Scheduled? = null
        Scripted.script = { ctx ->
            val start = ctx.now()
            ctx.timers().after(3) { seen += "after at ${(ctx.now() - start) / 50}" }
            every = ctx.timers().every(2) { seen += "every at ${(ctx.now() - start) / 50}" }
            ctx.timers().after(5) { every!!.cancel() }
        }
        val bench = bench()
        bench.start()
        bench.run(8)
        assertEquals(listOf("every at 2", "after at 3", "every at 4"), seen)
        assertFalse(every!!.active())
    }

    @Test
    fun `a fact comes the tick after, to the subscriptions of its class only, and no more once cancelled`() {
        val seen = CopyOnWriteArrayList<String>()
        var chat: Subscription? = null
        Scripted.script = { ctx -> chat = ctx.events().on(GameFact.Chat::class.java) { seen += "${it.player()}: ${it.text()}" } }
        val bench = bench()
        bench.player("Mimosa", at(0.5))
        bench.start()
        bench.fact(GameFact.Chat("Mimosa", at(0.5), "salut"))
        bench.fact(GameFact.BlockBroken("Mimosa", at(0.5), "stone", false))
        assertEquals(emptyList(), seen, "not before the next tick")
        bench.run(1)
        assertEquals(listOf("Mimosa: salut"), seen)
        val subscription = assertNotNull(chat)
        subscription.cancel()
        bench.fact(GameFact.Chat("Mimosa", at(0.5), "encore"))
        bench.run(2)
        assertEquals(listOf("Mimosa: salut"), seen)
        assertFalse(subscription.active())
    }

    // ── The world ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the world reads what the scenario poses of its players, and their comings and goings are facts`() {
        val facts = CopyOnWriteArrayList<String>()
        Scripted.script = { ctx ->
            ctx.events().on(GameFact.Joined::class.java) { facts += "joined ${it.player()}" }
            ctx.events().on(GameFact.Left::class.java) { facts += "left ${it.player()}" }
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.start()
        bench.move("Bartok", at(3.5))
        bench.pose("bartok", true, false, -45f)
        bench.hold("Bartok", "minecraft:diamond_sword")
        bench.give("Bartok", "bread", 3)
        bench.health("Bartok", 12f)
        val world = Scripted.context!!.world()
        val bartok = assertNotNull(world.player("BARTOK"))
        assertEquals(at(3.5), bartok.at())
        assertTrue(bartok.sneaking())
        assertFalse(bartok.onGround())
        assertEquals(-45f, bartok.pitch())
        assertEquals("diamond_sword", bartok.heldItem())
        assertEquals(12f, bartok.health())
        assertEquals(3, world.count("Bartok", "minecraft:bread"))
        assertEquals(1, world.count("Bartok", "diamond_sword"))
        assertEquals(-1, world.count("Zed", "bread"))

        bench.player("Mimosa", at(8.5))
        bench.leave("Mimosa")
        bench.run(1)
        assertEquals(listOf("joined Mimosa", "left Mimosa"), facts)
        assertNull(world.player("Mimosa"))
        assertEquals(listOf("Bartok"), world.players().map { it.name() })
        assertFailsWith<UnsupportedOperationException> { (world.players() as MutableList).clear() }
        assertFailsWith<ForgeRefusal> { bench.move("Zed", at(0.5)) }
    }

    @Test
    fun `the blocks are air from y 64 up and stone below, but for what the scenario puts, and the surface follows them`() {
        val bench = bench()
        bench.start()
        val world = Scripted.context!!.world()
        assertTrue(world.block(Cell("overworld", 0, 64, 0)).air())
        assertEquals("stone", world.block(Cell("minecraft:overworld", 0, 63, 0)).block())
        assertTrue(world.block(Cell("overworld", 0, 63, 0)).solid())
        assertEquals(64, world.surface("overworld", 0, 0))
        bench.block(Cell("overworld", 0, 64, 0), "minecraft:oak_planks")
        bench.block(Cell("overworld", 0, 65, 0), "oak_planks")
        assertEquals("oak_planks", world.block(Cell("overworld", 0, 64, 0)).block())
        assertFalse(world.block(Cell("overworld", 0, 64, 0)).placedByPlayer())
        assertEquals(66, world.surface("minecraft:overworld", 0, 0))
        bench.block(Cell("overworld", 0, 70, 0), "glass")
        assertEquals(71, world.surface("overworld", 0, 0))
        assertEquals(64, world.surface("overworld", 5, 5))
        bench.run(100)
        assertEquals(6_100L, world.dayTime())
        assertEquals("clear", world.weather())
        assertEquals("plains", world.biome(at(0.5)))
    }

    // ── Creatures ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `spawn makes the module's own creatures around the player, which its motion moves each tick and moveTo walks`() {
        var spawned: Outcome? = null
        Scripted.script = { ctx -> ctx.acts().spawn("minecraft:chicken", "Bartok", Spawn.of(4).withDistance(5).withHeight(3).withName("Poulette")).then { spawned = it } }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.start()
        assertEquals(listOf("spawn chicken x4 near Bartok"), bench.acts())
        bench.run(1)
        val outcome = assertNotNull(spawned)
        assertTrue(outcome.ok())
        assertEquals(4, outcome.creatures().size)
        val creatures = bench.creatures()
        assertEquals(outcome.creatures(), creatures.map { it.uuid() })
        creatures.forEach {
            assertEquals(5.0, it.at().offset(0.0, -3.0, 0.0).distance(at(0.5)), 1e-9)
            assertEquals("Poulette", it.name())
            assertTrue(it.own())
        }

        val ctx = Scripted.context!!
        val chicken = assertNotNull(ctx.creatures().get(outcome.creatures().first()))
        val from = chicken.info()!!.at()
        chicken.velocity(0.5, 0.0, 0.0)
        bench.run(2)
        assertEquals(from.offset(1.0, 0.0, 0.0), chicken.info()!!.at())
        chicken.push(0.0, 0.25, 0.0)
        bench.run(1)
        assertEquals(from.offset(1.5, 0.25, 0.0), chicken.info()!!.at())
        val target = chicken.info()!!.at().offset(0.0, 0.0, 1.0)
        chicken.moveTo(target, 1.0)
        bench.run(3)
        assertEquals(0.4, chicken.info()!!.at().distance(target), 1e-9, "0.2 × speed a tick, straight, and the motion stopped")
        bench.run(3)
        assertEquals(target, chicken.info()!!.at(), "there, and it stays")

        assertFailsWith<ForgeRefusal> { chicken.velocity(3.0, 0.0, 0.0) }
        assertFailsWith<ForgeRefusal> { chicken.moveTo(target, 3.0) }
        assertFailsWith<ForgeRefusal> { chicken.name("x".repeat(33)) }
        assertFailsWith<ForgeRefusal> { chicken.attribute("scale", 3.0) }
        assertFailsWith<ForgeRefusal> { chicken.attribute("luck", 1.0) }
        assertFailsWith<ForgeRefusal> { chicken.effect("speed", 0, 1) }
        chicken.release()
        assertFalse(chicken.alive())
        assertNull(chicken.info())
        assertEquals(3, bench.creatures().size, "a summoned creature released vanishes")
        assertFailsWith<ForgeRefusal> { chicken.glow(true) }
    }

    @Test
    fun `a summoned creature vanishes at the end of its lifetime, and the module hears it`() {
        val gone = CopyOnWriteArrayList<String>()
        Scripted.script = { ctx ->
            ctx.events().on(GameFact.CreatureGone::class.java) { gone += "${it.creature().type()} ${it.why()}" }
            ctx.acts().spawn("chicken", "Bartok", Spawn.of(1).withLifetimeMinutes(1))
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.start()
        bench.run(1_201)
        assertEquals(emptyList(), gone, "summoned at tick 1, gone at tick 1201, heard the tick after")
        bench.run(1)
        assertEquals(listOf("chicken vanished"), gone)
        assertEquals(emptyList(), bench.creatures())
    }

    @Test
    fun `a module claims the scenario's creatures near a player only, never a boss, and a claimed one goes back where it was`() {
        val bench = bench()
        bench.player("Bartok", at(0.5))
        val near = bench.creature("zombie", at(10.5))
        val far = bench.creature("zombie", at(200.5))
        val warden = bench.creature("warden", at(20.5))
        bench.start()
        val ctx = Scripted.context!!
        assertNull(ctx.creatures().claim(far))
        assertNull(ctx.creatures().claim(warden))
        assertNull(ctx.creatures().claim("no such creature"))
        val zombie = assertNotNull(ctx.creatures().claim(near))
        assertNull(ctx.creatures().claim(near), "its own already")
        assertEquals(listOf(near), ctx.creatures().own().map { it.uuid() })
        assertEquals(listOf(true, false), ctx.world().creatures(at(0.5), 48.0, null).map { it.own() })
        assertEquals(1, ctx.world().creatures(at(0.5), 48.0, "minecraft:zombie").size)
        assertFailsWith<ForgeRefusal> { ctx.world().creatures(at(0.5), 49.0, null) }
        zombie.name("Gaston")
        zombie.velocity(1.0, 0.0, 0.0)
        bench.run(3)
        zombie.release()
        val back = bench.world.creatures.getValue(near)
        assertEquals(at(10.5), back.at)
        assertFalse(back.own)
        assertNull(back.name)
        assertEquals(emptyList(), bench.creatures())
    }

    // ── Acts ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `acts are recorded, never done, answer ok at the next tick, but for the refusal a scenario imposed`() {
        val outcomes = CopyOnWriteArrayList<String>()
        var secondThen: Throwable? = null
        Scripted.script = { ctx ->
            ctx.acts().say("Ça commence.").then { outcomes += "say ${it.status()}" }
            val punished = ctx.acts().punish("Bartok", "tiny", 2, "a bougé")
            punished.then { outcomes += "punish ${it.status()}: ${it.text()}" }
            secondThen = runCatching { punished.then { } }.exceptionOrNull()
            ctx.acts().teleport("Bartok", Place("overworld", 100.0, 64.0, 100.0))
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.refuseNext("punish", "DENIED (already_punished): Bartok was punished by an earlier act")
        bench.start()
        assertEquals(emptyList(), outcomes, "the acts go after the call, their callbacks at the next tick")
        assertIs<ForgeRefusal>(secondThen)
        bench.run(1)
        assertEquals(listOf("say: Ça commence.", "punish Bartok tiny 2 → denied", "teleport Bartok to 100 64 100"), bench.acts())
        assertEquals(at(0.5), bench.world.player("Bartok")!!.at, "a teleport is not done")
        assertEquals(listOf("say ok", "punish denied: DENIED (already_punished): Bartok was punished by an earlier act"), outcomes)
        assertFailsWith<ForgeRefusal> { bench.refuseNext("smite", "DENIED (protected): no") }
        assertFailsWith<ForgeRefusal> { bench.refuseNext("punish", " ") }
    }

    @Test
    fun `the SDK's own limits deny acts beyond 4 a call, 30 a minute and 4 lines a minute, the hud tool one line a second, and a player offline is invalid`() {
        val place = Place("overworld", 0.5, 66.0, 0.5)
        Scripted.script = { ctx ->
            repeat(5) { ctx.acts().particles(place, "heart", 3) }
            ctx.timers().every(1) { ctx.acts().say("Encore.") }
            ctx.timers().after(1) {
                ctx.acts().hud("Bartok", "10 s")
                ctx.acts().hud(null, "9 s")
                ctx.acts().sayTo("Zed", "Où es-tu ?")
            }
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.start()
        bench.run(5)
        assertEquals(List(4) { "particles heart x3 at 0.5 66 0.5" } + "particles heart x3 at 0.5 66 0.5 → denied", bench.acts().take(5))
        assertEquals(
            listOf(
                "say: Encore.", "hud Bartok: 10 s", "hud everyone: 9 s → denied", "say to Zed: Où es-tu ? → invalid",
                "say: Encore.", "say: Encore.", "say: Encore. → denied", "say: Encore. → denied",
            ),
            bench.acts().drop(5),
            "four lines a minute (the one to a player offline counts: the tool answered it), one action bar a second",
        )
        bench.run(1_200)
        assertEquals(List(4) { "say: Encore." } + "say: Encore. → denied", bench.acts().takeLast(5), "a minute later, four lines again")
    }

    @Test
    fun `thirty acts a minute at most`() {
        Scripted.script = { ctx -> ctx.timers().every(1) { ctx.acts().lightning(at(0.5)) } }
        val bench = bench()
        bench.start()
        bench.run(32)
        assertEquals(List(30) { "lightning at 0.5 64 0.5" } + List(2) { "lightning at 0.5 64 0.5 → denied" }, bench.acts())
    }

    @Test
    fun `a module referees the scenario's judged challenge and names its winners among its players, once`() {
        var game: Game? = null
        Scripted.script = { ctx ->
            game = ctx.game()
            ctx.timers().after(1) { ctx.game()!!.win(listOf("Zed"), "Zed ?") }
            ctx.timers().after(2) { ctx.game()!!.win(listOf("Mimosa"), "Mimosa a gagné").then { ctx.end("Mimosa a gagné") } }
            ctx.timers().after(4) { ctx.game()!!.lose("trop tard") }
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.player("Mimosa", at(4.5))
        bench.challenge("Bartok", "Mimosa")
        bench.start()
        val refereed = assertNotNull(game)
        assertEquals("C-2", refereed.id())
        assertEquals(listOf("Bartok", "Mimosa"), refereed.players())
        assertTrue(refereed.running())
        bench.run(5)
        assertEquals(listOf("game won: Zed → invalid", "game won: Mimosa"), bench.acts())
        assertEquals("Mimosa a gagné", bench.ended(), "won, then ended from the win's callback")
        assertFailsWith<ForgeRefusal> { refereed.running() }
        assertFailsWith<ForgeRefusal> { bench.challenge("Bartok") }

        val alone = bench()
        alone.start()
        assertNull(Scripted.context!!.game())
    }

    // ── Ends, restarts, notes ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ctx end stops the module after its call, and the acts of that call and of stop are dropped, as in the game`() {
        Scripted.script = { ctx ->
            ctx.acts().spawn("chicken", "Bartok", Spawn.of(2))
            ctx.creatures().claim(ctx.world().creatures(at(0.5), 48.0, "zombie").first().uuid())
            ctx.timers().after(10) {
                ctx.acts().say("C'est fini.")
                ctx.end("Mimosa a gagné")
            }
        }
        Scripted.onStop = { ctx -> ctx.acts().say("Adieu.") }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        val zombie = bench.creature("zombie", at(12.5))
        bench.start()
        bench.run(5)
        val ctx = Scripted.context!!
        ctx.creatures().own().first().velocity(1.0, 0.0, 0.0)
        bench.run(5)
        assertEquals("Mimosa a gagné", bench.ended())
        assertEquals(listOf(StopReason.ENDED), Scripted.stops)
        assertEquals(listOf("spawn chicken x2 near Bartok", "say: C'est fini. → dropped", "say: Adieu. → dropped"), bench.acts())
        assertEquals(emptyList(), bench.creatures())
        assertEquals(setOf(zombie), bench.world.creatures.keys, "the summoned vanished, the claimed one stays, wild again")
        assertEquals(at(12.5), bench.world.creatures.getValue(zombie).at)
        assertFailsWith<ForgeRefusal> { ctx.acts() }
        assertFailsWith<ForgeRefusal> { ctx.storage() }
        bench.run(10)
        assertEquals(listOf(StopReason.ENDED), Scripted.stops)
    }

    @Test
    fun `a deployment expires after its minutes`() {
        val bench = bench()
        bench.start()
        bench.run(BenchImpl.SCENARIO_MINUTES * 1_200 - 1)
        assertNull(bench.ended())
        bench.run(1)
        assertEquals("its time ran out", bench.ended())
        assertEquals(listOf(StopReason.EXPIRED), Scripted.stops)
    }

    @Test
    fun `a restart stops the module for the server, then a new instance starts again with its storage and creatures, not its timers`() {
        var ticks = 0
        val restarted = CopyOnWriteArrayList<Boolean>()
        Scripted.script = { ctx ->
            ctx.storage().putInt("starts", ctx.storage().getInt("starts", 0) + 1)
            restarted += ctx.restarted()
            ctx.timers().every(1) { ticks++ }
            if (!ctx.restarted()) ctx.acts().spawn("chicken", "Bartok", Spawn.of(1))
        }
        val bench = bench()
        bench.player("Bartok", at(0.5))
        bench.start()
        bench.run(5)
        val before = Scripted.context!!
        val storage = before.storage()
        bench.restart()
        assertEquals(listOf(StopReason.SERVER_STOPPING), Scripted.stops)
        assertEquals(listOf(false, true), restarted)
        assertEquals(2, Scripted.made)
        val after = Scripted.context!!
        assertEquals(2, after.storage().getInt("starts", 0))
        assertEquals(1, after.creatures().own().size)
        bench.run(5)
        assertEquals(10, ticks, "the timer of the first instance is gone")
        assertFailsWith<ForgeRefusal> { before.storage() }
        assertFailsWith<ForgeRefusal> { storage.get("starts") }
        assertNull(bench.ended())
    }

    @Test
    fun `log keeps 20 lines a minute and tellDirector one note every five minutes, the latest of those held`() {
        Scripted.script = { ctx ->
            repeat(25) { ctx.log("ligne $it") }
            ctx.tellDirector("un")
            ctx.tellDirector("deux")
            ctx.tellDirector("trois")
            ctx.log("x".repeat(300))
        }
        val bench = bench()
        bench.start()
        assertEquals(List(20) { "log: ligne $it" } + "director: un", bench.notes())
        assertEquals(6, bench.deployment!!.logsDropped)
        bench.run(5 * 1_200)
        assertEquals("director: trois (+1 earlier notes dropped)", bench.notes().last())
    }

    @Test
    fun `the storage is the game's, within its limits`() {
        val bench = bench()
        bench.start()
        val storage = Scripted.context!!.storage()
        storage.put("loup", "Bartok")
        storage.putInt("tour", 3)
        storage.putDouble("rayon", 2.5)
        assertEquals(listOf("loup", "tour", "rayon"), storage.keys().toList())
        assertEquals(3, storage.getInt("tour", 0))
        assertEquals(2.5, storage.getDouble("rayon", 0.0))
        assertEquals(7, storage.getInt("loup", 7))
        storage.put("loup", null)
        assertNull(storage.get("loup"))
        assertFailsWith<ForgeRefusal> { storage.put("k".repeat(41), "v") }
        assertFailsWith<ForgeRefusal> { storage.put("k", "v".repeat(513)) }
        repeat(62) { storage.put("key$it", "v") }
        assertEquals(64, storage.keys().size)
        assertFailsWith<ForgeRefusal> { storage.put("one too many", "v") }
        storage.remove("key0")
        val refusal = assertFailsWith<ForgeRefusal> { repeat(20) { storage.put("key${it + 1}", "v".repeat(512)) } }
        assertContains(refusal.message!!, "8192 characters at most")
    }

    @Test
    fun `the parameters are read from their text and checked as deploy_module checks them`() {
        val specs = listOf(
            spec("player", ParamType.PLAYER),
            spec("players", ParamType.PLAYERS, min = 2.0, max = 3.0),
            spec("chickens", ParamType.INTEGER, required = false, fallback = "6", min = 3.0, max = 8.0),
            spec("speed", ParamType.NUMBER, min = 0.5, max = 2.0),
            spec("mood", ParamType.TEXT, required = false, fallback = "taquin", choices = listOf("taquin", "sévère")),
            spec("loud", ParamType.BOOLEAN, required = false, fallback = "false"),
            spec("spot", ParamType.PLACE),
            spec("rival", ParamType.PLAYER, required = false),
        )
        fun bench3(): BenchImpl = bench(specs = specs).apply {
            player("Bartok", at(0.5))
            player("Mimosa", at(6.5))
            player("Josiane", at(12.5))
        }
        val bench = bench3()
        bench.start("player=bartok", "players=Bartok, Mimosa", "speed=1.5", "spot=Mimosa")
        val args = Scripted.context!!.args()
        assertEquals("Bartok", args.player("player"))
        assertEquals(listOf("Bartok", "Mimosa"), args.players("players"))
        assertEquals(6, args.integer("chickens"))
        assertEquals(6.0, args.number("chickens"))
        assertEquals(1.5, args.number("speed"))
        assertEquals("taquin", args.text("mood"))
        assertFalse(args.bool("loud"))
        assertEquals(at(6.5), args.place("spot"))
        assertNull(args.player("rival"))
        assertContains(assertFailsWith<ForgeRefusal> { args.integer("player") }.message!!, "read it with args().player(\"player\")")
        assertFailsWith<ForgeRefusal> { args.text("nope") }

        val given = listOf("player=Bartok", "players=Bartok,Mimosa", "speed=1", "spot=1 2 3 the_nether")
        bench3().start(*given.toTypedArray())
        assertEquals(Place("the_nether", 1.0, 2.0, 3.0), Scripted.context!!.args().place("spot"))
        fun refused(vararg params: String): String = assertFailsWith<ForgeRefusal> { bench3().start(*params) }.message!!
        assertEquals("params.chickens must be between 3 and 8 (got 12)", refused(*(given + "chickens=12").toTypedArray()))
        assertContains(refused("player=Bartok", "players=Bartok,Mimosa", "spot=Bartok"), "params.speed is required")
        assertContains(refused(*(given.drop(1) + "player=Zed").toTypedArray()), "Zed is not a player of the bench")
        assertContains(refused(*(given.drop(2) + listOf("player=Bartok", "players=Bartok")).toTypedArray()), "params.players must name between 2 and 3 players (got 1)")
        assertContains(refused(*(given + "mood=gai").toTypedArray()), "params.mood must be one of")
        assertContains(refused(*(given.dropLast(1) + "spot=1 2").toTypedArray()), "params.spot must be")
        assertContains(refused(*(given + "nope=1").toTypedArray()), "params.nope is not a parameter of this module")
        assertContains(refused(*(given + "player").toTypedArray()), "is not name=value")
        assertContains(refused(*(given + "player=Mimosa").toTypedArray()), "params.player is given twice")
    }

    @Test
    fun `the SDK refuses subscriptions and timers past its own limits`() {
        val refusals = CopyOnWriteArrayList<String>()
        fun refused(attempt: () -> Unit) {
            refusals += runCatching(attempt).exceptionOrNull().let { assertIs<ForgeRefusal>(it) }.message!!
        }
        Scripted.script = { ctx ->
            repeat(16) { ctx.events().on(GameFact.Chat::class.java) { } }
            refused { ctx.events().on(GameFact.Chat::class.java) { } }
            refused { ctx.events().on(GameFact::class.java) { } }
            repeat(16) { ctx.timers().every(100) { } }
            refused { ctx.timers().every(100) { } }
            repeat(48) { ctx.timers().after(100) { } }
            refused { ctx.timers().after(100) { } }
            refused { ctx.timers().after(0) { } }
            refused { ctx.timers().after(72_001) { } }
        }
        val bench = bench()
        bench.start()
        assertNull(bench.failure, "${bench.failure}")
        listOf("16 subscriptions", "GameFact's records", "16 timers repeat", "64 timers wait", "got 0", "got 72001").forEachIndexed { i, said ->
            assertContains(refusals[i], said)
        }
    }

    @Test
    fun `the bench never calls a module's object outside its calls`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Touchy::class.java), emptyList(), listOf("touchy"), { _, _ -> null }).run()
        assertTrue(report.ok, report.text(null))
        assertTrue(Touchy.ran > 10, "its timer, handler and callbacks ran")
    }

    @Test
    fun `a call the host abandoned fails the bench, and its thread is refused by the SDK from then on`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Stuck::class.java) { TimeUnit.MILLISECONDS.toNanos(200) }, emptyList(), emptyList(), { _, _ -> null }).run()
        val problem = report.problems.single()
        assertEquals("smoke, timer 1 at tick 1", problem.where)
        assertContains(problem.message, "abandoned")
        Stuck.gate.countDown()
        assertTrue(Stuck.done.await(5, TimeUnit.SECONDS))
        assertIs<ForgeRefusal>(Stuck.late, "the abandoned call, going on, asks an act: refused")
    }

    // ── The smoke run ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the smoke run deploys the module with example parameters, gives it a fact of each class it hears, a minute, then stops it`() = runBlocking<Unit> {
        Quiet.heard.clear()
        Quiet.stops.clear()
        val report = BenchRun(KotlinHost(Quiet::class.java), Quiet.SPECS, listOf("speaks"), { _, _ -> null }).run()
        assertTrue(report.ok, report.text(null))
        assertEquals(listOf("speaks"), report.scenariosPassed)
        assertTrue(report.summary.startsWith("smoke: start ok, 16 facts, 1,200 ticks, acts say ×1 (checked), slowest call "), report.summary)
        assertTrue(report.summary.endsWith(" MiB; scenarios 1/1"), report.summary)
        assertEquals(GameFact::class.java.permittedSubclasses.take(16).map { it.simpleName }, Quiet.heard)
        assertEquals(listOf(StopReason.EXPIRED), Quiet.stops, "the smoke run stops it; its scenario does not")
        assertEquals(
            listOf("player Bartok", "players Bartok,Mimosa", "count 3", "rate 0.5", "mood taquin", "loud false", "spot 8 64 0", "extra 6"),
            Quiet.read,
        )
    }

    @Test
    fun `the smoke run knows an example of each fact`() {
        val bench = bench()
        SmokeRun.PLAYERS.forEach { (name, at) -> bench.player(name, at) }
        for (type in GameFact::class.java.permittedSubclasses) {
            val fact = assertNotNull(SmokeRun.sample(type, bench, "00000000-0000-4000-8000-000000000001"), type.simpleName)
            assertTrue(type.isInstance(fact))
        }
    }

    @Test
    fun `the example parameters are within their bounds`() {
        val specs = listOf(
            spec("p", ParamType.PLAYER),
            spec("ps", ParamType.PLAYERS, min = 3.0, max = 8.0),
            spec("one", ParamType.PLAYERS, max = 1.0),
            spec("n", ParamType.INTEGER, min = 2.5, max = 8.0),
            spec("x", ParamType.NUMBER, max = 0.25),
            spec("t", ParamType.TEXT, min = 10.0),
            spec("c", ParamType.TEXT, choices = listOf("a", "b")),
            spec("b", ParamType.BOOLEAN),
            spec("at", ParamType.PLACE),
            spec("opt", ParamType.INTEGER, required = false, fallback = "6"),
        )
        assertEquals(
            listOf("p=Bartok", "ps=Bartok,Mimosa,Josiane", "one=Bartok", "n=3", "x=0.25", "t=bonjour!!!", "c=a", "b=false", "at=8 64 0"),
            SmokeRun.examples(specs),
        )
    }

    @Test
    fun `a fault in the smoke run fails it, with the call, the tick and the module's line`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Faulty::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        assertFalse(report.ok)
        val problem = report.problems.single()
        assertEquals(Diagnostic.Kind.FAULT, problem.kind)
        assertEquals("smoke, timer 1 at tick 20", problem.where)
        assertTrue(problem.message.startsWith("IllegalStateException: le loup s'est perdu at BenchTest.kt:"), problem.message)
        assertTrue(problem.line > 0)
        assertTrue(report.summary.startsWith("smoke: failed at tick 20, 0 facts, 20 ticks"), report.summary)
        assertTrue(report.summary.endsWith("; no scenarios"), report.summary)
        assertTrue(report.text(null).startsWith(report.summary + "\n[1] fault smoke, timer 1 at tick 20: IllegalStateException: le loup s'est perdu"), report.text(null))
    }

    @Test
    fun `a call over 20 ms fails the smoke run`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Slow::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        val problem = report.problems.single()
        assertEquals("smoke, timer 1 at tick 5", problem.where)
        assertTrue(problem.message.startsWith("took "), problem.message)
        assertContains(problem.message, "the most is 20 ms a call")
    }

    @Test
    fun `the bench judges a call's own work, never the wall clock a busy machine stretches`() = runBlocking<Unit> {
        // As the real host reports a call (M5-LOAD): its wall time, and its own work apart (here its thread's CPU time).
        fun walled(module: Class<*>): BenchHost {
            val host = KotlinHost(module)
            return object : BenchHost by host {
                override fun call(budgetNanos: Long, body: () -> Unit): HostCall {
                    val began = System.nanoTime()
                    val call = host.call(budgetNanos, body)
                    return call.copy(nanos = System.nanoTime() - began, work = call.nanos)
                }
            }
        }
        val held = BenchRun(walled(Sleepy::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        assertTrue(held.ok, "40 ms held back, no work: ${held.text(null)}")
        val slow = BenchRun(walled(Slow::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        assertTrue(slow.problems.single().message.startsWith("took "), slow.text(null))
    }

    @Test
    fun `the smoke run fails a module that retains more than 4 MiB, or whose memory still grows`() = runBlocking<Unit> {
        val fat = BenchRun(KotlinHost(Fat::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        assertEquals(1, fat.problems.size, fat.text(null))
        assertTrue(fat.problems.single().message.startsWith("it retains 5.15 MiB after a minute, the most is 4 MiB"), fat.text(null))

        val hoarder = BenchRun(KotlinHost(Hoarder::class.java), emptyList(), emptyList(), { _, _ -> null }).run()
        assertEquals(2, hoarder.problems.size, hoarder.text(null))
        assertTrue(hoarder.problems[0].message.startsWith("it retains 11."), hoarder.text(null))
        assertTrue(hoarder.problems[1].message.startsWith("what it retains grew by 5.7"), hoarder.text(null))
        assertContains(hoarder.problems[1].message, "over the second half of the minute, the most is 1 MiB")
    }

    // ── Dry checks by the real tools ───────────────────────────────────────────────────────────────

    @Test
    fun `every act the SDK asks is one the real tools' dry checks read and pass`() = runBlocking<Unit> {
        val actions = actions()
        val checked = CopyOnWriteArrayList<String>()
        val report = BenchRun(KotlinHost(EveryAct::class.java), emptyList(), emptyList(), { tool, args ->
            checked += tool
            actions.check(tool, args, MechanicsSamples.director)
        }).run()
        assertTrue(report.ok, report.text(null))
        assertContains(report.summary, "(checked)")
        assertEquals(
            setOf(
                "say", "show_title", "hud", "play_sound", "particles", "fireworks", "lightning", "explosion", "apply_effect", "set_attribute",
                "launch", "teleport", "spawn", "punish", "place_blocks", "creature_attack", "mob_effect", "mob_attribute",
            ),
            checked.toSet(),
        )
        assertEquals(21, checked.size, "each tool and arguments once: two of say, lightning and teleport; the last batch asks nothing new")
    }

    @Test
    fun `an act the real tools refuse dry, or the SDK refuses for good, is a problem that says why and where`() = runBlocking<Unit> {
        val actions = actions()
        val report = BenchRun(KotlinHost(Harsh::class.java), emptyList(), emptyList(), { tool, args -> actions.check(tool, args, MechanicsSamples.director) }).run()
        assertFalse(report.ok)
        val (severity, wither) = report.problems
        assertEquals(Diagnostic.Kind.REFUSED, severity.kind)
        assertEquals("act \"punish Bartok tiny 5\"", severity.where)
        assertTrue(severity.message.startsWith("Invalid arguments: at chaos 3 "), severity.message)
        assertContains(severity.message, "(asked 5)")
        assertTrue(severity.line > 0, "the module's line that asked it")
        assertEquals("act \"effect Bartok wither 30s level 1\"", wither.where)
        assertTrue(wither.message.startsWith("DENIED (forbidden_effect): "), wither.message)
        assertContains(report.summary, "(2 refused by the dry checks)")
    }

    @Test
    fun `the bench asks each act as the game's SDK does`() = runBlocking<Unit> {
        val runner = Recorder()
        val ref = Ref(RefKind.CANON, 70)
        val queue = ActQueue(ref, runner, CoroutineScope(Dispatchers.Unconfined), TestClock(), ModuleCall { _, body -> body() }, Executor { it.run() })
        val creatures = CreatureControl(TestServices(), ref.id, queue)
        val game = ModuleActs(queue, creatures) { "overworld" }
        Translated.BATCHES.forEach { batch -> queue.invocation("batch") { batch(game, GroundView(queue)) } }

        val bench = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val report = BenchRun(KotlinHost(Translated::class.java), emptyList(), emptyList(), { tool, args ->
            bench += tool to args
            null
        }).run()
        assertEquals(listOf("act \"effect Bartok wither 30s level 1\""), report.problems.map { it.where }, "refused by the SDK itself: sent by neither")
        assertEquals(runner.ran.toSet(), bench.toSet())
        assertEquals(22, bench.size)
    }

    // ── Scenarios, the report ──────────────────────────────────────────────────────────────────────

    @Test
    fun `each scenario runs on a new bench, and a failure says which scenario, what and where`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Sentinel::class.java), emptyList(), Sentinel.SCENARIOS + "ghost", { _, _ -> null }).run()
        assertEquals(listOf("seesTheSneaker", "aNewBench"), report.scenariosPassed)
        assertFalse(report.ok)
        val problems = report.problems.map { "${it.where}: ${it.message}" }
        assertEquals(5, problems.size, report.text(null))
        assertEquals("scenario checksWrongly: check failed: nobody hides", problems[0])
        assertTrue(report.problems[0].line > 0)
        assertTrue(problems[1].matches(Regex("""scenario throws: IllegalStateException: le scénario a trébuché at BenchTest\.kt:\d+""")), problems[1])
        assertTrue(problems[2].startsWith("scenario startsTwice: ForgeRefusal: the module is started already"), problems[2])
        assertTrue(
            problems[3].matches(Regex("""scenario timerThrows, timer 1 \(every 20 ticks\) at tick 20: ArithmeticException: / by zero at BenchTest\.kt:\d+""")),
            problems[3],
        )
        assertEquals("scenario ghost: Scenarios has no method public void ghost(Bench b)", problems[4])
        assertTrue(report.summary.endsWith("; scenarios 2/7"), report.summary)
    }

    @Test
    fun `the report to the forger stays short whatever the module does`() = runBlocking<Unit> {
        val refusal = ToolResult.invalid("x".repeat(1_000))
        val report = BenchRun(KotlinHost(Verbose::class.java), emptyList(), Verbose.SCENARIOS, { _, _ -> refusal }).run()
        assertTrue(report.problems.size > 100, "${report.problems.size}")
        val text = report.text(null)
        assertTrue(text.length <= BenchReport.MAX_CHARS, "${text.length}")
        assertTrue(text.lines().first().length <= BenchReport.SUMMARY_CHARS)
        assertContains(text, "more not shown")
        assertTrue(text.none { it == '<' || it == '>' }, "the module's texts are escaped")
    }

    @Test
    fun `the bench stops at the end of its time, and the scenarios left are not run`() = runBlocking<Unit> {
        val report = BenchRun(KotlinHost(Endless::class.java), emptyList(), listOf("forever", "never"), { _, _ -> null }, budgetMs = 1_000).run()
        assertFalse(report.ok)
        val problems = report.problems.map { "${it.where}: ${it.message}" }
        assertEquals(2, problems.size, report.text(null))
        assertTrue(problems[0].matches(Regex("""scenario forever at tick \d+: the bench ran out of its 1 s: .*""")), problems[0])
        assertEquals("scenario never: not run: the bench ran out of time", problems[1])
    }

    @Test
    fun `cancelling the bench's coroutine stops it at once`() = runBlocking<Unit> {
        val run = async(Dispatchers.Default) { BenchRun(KotlinHost(Endless::class.java), emptyList(), listOf("forever"), { _, _ -> null }).run() }
        delay(300)
        run.cancel()
        withTimeout(5_000) { assertFailsWith<CancellationException> { run.await() } }
    }

    @Test
    fun `the witness module of M5-01 passes its smoke run and its own scenarios`() = runBlocking<Unit> {
        val loader = BytesLoader(ForgeSamples.classes)
        val host = KotlinHost(loader.loadClass(ForgeSamples.CLASS))
        val manifest = ForgeSamples.manifest
        val report = BenchRun(host, manifest.params, manifest.scenarios, { _, _ -> null }).run()
        assertTrue(report.ok, report.text(null))
        assertEquals(manifest.scenarios, report.scenariosPassed)
        assertTrue(report.summary.startsWith("smoke: start ok, 2 facts, 1,200 ticks, acts say ×1 (checked)"), report.summary)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private fun bench(type: Class<*> = Scripted::class.java, specs: List<ParamSpec> = emptyList()): BenchImpl =
        BenchImpl(KotlinHost(type), specs, System.nanoTime() + BUDGET_MS * 1_000_000, BUDGET_MS, "scenario test", BenchImpl.SCENARIO_MINUTES) { false }

    private fun spec(
        name: String,
        type: ParamType,
        required: Boolean = true,
        fallback: String? = null,
        min: Double? = null,
        max: Double? = null,
        choices: List<String> = emptyList(),
    ) = ParamSpec(name, type, "The $name.", required, fallback, min, max, choices)

    /** The engine's acts through the real tools of the rules, the stakes and the modules, on services without a server, chaos 3. */
    private fun actions(): EngineActions {
        val services = TestServices()
        return EngineActions(
            services,
            speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) + ruleOnlyTools() + moduleOnlyTools(),
        )
    }

    /** The game's acts as they reach the Narrator's tools. */
    private class Recorder : ActRunner {
        val ran = CopyOnWriteArrayList<Pair<String, JsonObject>>()

        override fun open(ref: Ref, subject: String?, reason: String): Firing = Firing(1, ref, Caller(Role.DIRECTOR, "mechanics:$ref", null, null, 1))

        override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult {
            ran += tool to args
            return ToolResult.ok("ok")
        }
    }

    /** Defines the classes ECJ compiled for the tests (not rewritten), the SDK from the tests' own loader. */
    private class BytesLoader(private val classes: Map<String, ByteArray>) : ClassLoader(BenchTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    // ── Modules, written in Kotlin ─────────────────────────────────────────────────────────────────

    /** A module whose start is the test's [script]. */
    class Scripted : ForgeModule {
        init {
            made++
        }

        override fun start(ctx: ModuleContext) {
            context = ctx
            script(ctx)
        }

        override fun stop(ctx: ModuleContext, why: StopReason) {
            stops += why
            onStop(ctx)
        }

        companion object {
            @Volatile
            var script: (ModuleContext) -> Unit = {}

            @Volatile
            var onStop: (ModuleContext) -> Unit = {}
            val stops = CopyOnWriteArrayList<StopReason>()

            @Volatile
            var context: ModuleContext? = null

            @Volatile
            var made = 0

            fun reset() {
                script = {}
                onStop = {}
                stops.clear()
                context = null
                made = 0
            }
        }
    }

    /** A quiet module: a parameter of each type, 16 classes of facts heard, one line said, a timer that reads the world. */
    class Quiet : ForgeModule {
        override fun start(ctx: ModuleContext) {
            val args = ctx.args()
            read = listOf(
                "player ${args.player("player")}", "players ${args.players("players").joinToString(",")}", "count ${args.integer("count")}",
                "rate ${args.number("rate")}", "mood ${args.text("mood")}", "loud ${args.bool("loud")}",
                "spot ${args.place("spot")!!.let { "${it.x().toInt()} ${it.y().toInt()} ${it.z().toInt()}" }}", "extra ${args.integer("extra")}",
            )
            for (type in GameFact::class.java.permittedSubclasses.take(16)) {
                @Suppress("UNCHECKED_CAST")
                ctx.events().on(type as Class<GameFact>) { heard += it.javaClass.simpleName }
            }
            ctx.acts().say("Le Narrateur vous observe.")
            ctx.timers().every(20) { ctx.world().players().size }
        }

        override fun stop(ctx: ModuleContext, why: StopReason) {
            stops += why
            ctx.log("fin")
        }

        class Scenarios {
            fun speaks(b: Bench) {
                SmokeRun.PLAYERS.forEach { (name, at) -> b.player(name, at) }
                b.start(*SmokeRun.examples(SPECS).toTypedArray())
                b.check(b.acts() == listOf("say: Le Narrateur vous observe."), "it speaks once")
            }
        }

        companion object {
            val heard = CopyOnWriteArrayList<String>()
            val stops = CopyOnWriteArrayList<StopReason>()

            @Volatile
            var read = emptyList<String>()
            val SPECS = listOf(
                ParamSpec("player", ParamType.PLAYER, "Who.", true, null, null, null, emptyList()),
                ParamSpec("players", ParamType.PLAYERS, "Who plays.", true, null, 2.0, 8.0, emptyList()),
                ParamSpec("count", ParamType.INTEGER, "How many.", true, null, 3.0, 8.0, emptyList()),
                ParamSpec("rate", ParamType.NUMBER, "How fast.", true, null, 0.5, 2.0, emptyList()),
                ParamSpec("mood", ParamType.TEXT, "How it speaks.", true, null, null, null, listOf("taquin", "sévère")),
                ParamSpec("loud", ParamType.BOOLEAN, "Loud.", true, null, null, null, emptyList()),
                ParamSpec("spot", ParamType.PLACE, "Where.", true, null, null, null, emptyList()),
                ParamSpec("extra", ParamType.INTEGER, "More.", false, "6", 1.0, 9.0, emptyList()),
            )
        }
    }

    /** A module whose timer throws at its first run. */
    class Faulty : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().after(20) { error("le loup s'est perdu") }
        }
    }

    /** A module whose timer burns 30 ms of the processor. */
    /** Held back 40 ms by the machine (a sleep: no work) in a timer. */
    class Sleepy : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().after(5) { Thread.sleep(40) }
        }
    }

    class Slow : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().after(5) {
                val threads = java.lang.management.ManagementFactory.getThreadMXBean()
                val until = threads.currentThreadCpuTime + 30_000_000L
                var spin = 0L
                while (threads.currentThreadCpuTime < until) spin++
            }
        }
    }

    /** A module whose timer waits for the test, far past its budget, then asks an act. */
    class Stuck : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().after(1) {
                gate.await()
                late = runCatching { ctx.acts().say("Trop tard.") }.exceptionOrNull()
                done.countDown()
            }
        }

        companion object {
            val gate = CountDownLatch(1)
            val done = CountDownLatch(1)

            @Volatile
            var late: Throwable? = null
        }
    }

    /** A module that keeps 5.15 MiB from its start on, and no more. */
    class Fat : ForgeModule {
        private var ballast = ""

        override fun start(ctx: ModuleContext) {
            ballast = "x".repeat(2_700_000)
        }
    }

    /** A module that piles 5,000 characters up at each tick. */
    class Hoarder : ForgeModule {
        private val pile = ArrayList<String>()

        override fun start(ctx: ModuleContext) {
            ctx.timers().every(1) { pile += "x".repeat(5_000) }
        }
    }

    /** Every act of the SDK once, with arguments the real tools accept at chaos 3, four a call at most. */
    class EveryAct : ForgeModule {
        override fun start(ctx: ModuleContext) {
            val acts = ctx.acts()
            val place = Place("overworld", 3.5, 65.0, 3.5)
            val batches: List<() -> Unit> = listOf(
                {
                    acts.say("Bonjour.")
                    acts.sayTo("Mimosa", "Psst.")
                    acts.title("Bartok", "Attention", "le sol brûle")
                    acts.sound(null, "entity.chicken.egg")
                },
                {
                    acts.particles(place, "heart", 10)
                    acts.fireworks(place, 3)
                    acts.lightning(place)
                    acts.smite("Bartok", 1)
                },
                {
                    acts.explosion(place, 1.0, false)
                    acts.effect("Bartok", "minecraft:speed", 30, 1)
                    acts.attribute("Bartok", "scale", 0.5, 30)
                    acts.launch("Bartok", "up", 1.0)
                },
                {
                    acts.teleport("Bartok", Place("overworld", 10.5, 64.0, 10.5))
                    acts.teleportRandom("Bartok", 30)
                    acts.punish("Bartok", "tiny", 2, "a bougé quand le Narrateur regardait")
                    acts.hud("Bartok", "10 s")
                },
                {
                    acts.spawn("chicken", "Bartok", Spawn.of(3)).then { outcome ->
                        val chicken = ctx.creatures().get(outcome.creatures().first())!!
                        chicken.effect("slowness", 30, 1)
                        chicken.attribute("movement_speed", 1.5)
                        chicken.attack("Mimosa")
                    }
                    ctx.ground().place(listOf(Cell("overworld", 3, 70, 3)), "glass", 10)
                },
                {
                    acts.say("Bonjour.")
                    acts.teleport("Bartok", Place("overworld", 10.5, 64.0, 10.5))
                    acts.lightning(place)
                },
            )
            batches.forEachIndexed { i, batch -> ctx.timers().after(i + 1) { batch() } }
        }
    }

    /** A punishment too severe for chaos 3, and an effect a module never gives. */
    class Harsh : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.acts().punish("Bartok", "tiny", 5, "a bougé")
            ctx.acts().effect("Bartok", "wither", 30, 1)
        }
    }

    /** The same acts as [BATCHES] give, one batch a tick: the bench's translation, against the game's. */
    class Translated : ForgeModule {
        override fun start(ctx: ModuleContext) {
            BATCHES.forEachIndexed { i, batch -> ctx.timers().after(i + 1) { batch(ctx.acts(), ctx.ground()) } }
        }

        companion object {
            private val place = Place("overworld", 3.5, 65.0, -3.5)

            /** Every act's form, four a call; the effect that is refused is never sent, by either. */
            val BATCHES: List<(Acts, Ground) -> Unit> = listOf(
                { acts, _ ->
                    acts.say("Bonjour.")
                    acts.sayTo("Mimosa", "Psst.")
                    acts.title("Bartok", "Attention", "le sol brûle")
                    acts.title(null, "Tous", null)
                },
                { acts, _ ->
                    acts.hud("Bartok", "10 s")
                    acts.hud(null, "9 s")
                    acts.sound("Bartok", "entity.chicken.egg")
                    acts.sound(null, "block.bell.use")
                },
                { acts, _ ->
                    acts.particles(place, "heart", 10)
                    acts.fireworks(place, 3)
                    acts.lightning(Place("the_nether", 0.5, 70.0, 0.5))
                    acts.smite("Bartok", 2)
                },
                { acts, _ ->
                    acts.explosion(place, 1.5, true)
                    acts.effect("Bartok", "speed", 30, 2)
                    acts.effect("Bartok", "minecraft:wither", 30, 1)
                    acts.attribute("Bartok", "gravity", 0.5, 60)
                },
                { acts, _ ->
                    acts.launch("Bartok", "forward", 1.5)
                    acts.teleport("Bartok", Place("overworld", -10.5, 70.0, 10.5))
                    acts.teleportRandom("Bartok", 40)
                    acts.punish("Bartok", "moon_gravity", 2, "a ri")
                },
                { acts, ground ->
                    acts.spawn("zombie", "Bartok", Spawn.of(2).withName("Gaston").withHeight(3).withTarget(true).withBaby(true))
                    acts.spawn("chicken", "Mimosa", null)
                    ground.place(listOf(Cell("overworld", 3, 70, 3), Cell("overworld", 4, 70, 3)), "red_stained_glass", 10)
                },
            )
        }
    }

    /** It says who sneaks, once a second; a player called Zero makes it throw. */
    class Sentinel : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().every(20) {
                for (player in ctx.world().players().filter { it.sneaking() }) {
                    if (player.name() == "Zero") throw ArithmeticException("/ by zero")
                    ctx.acts().say("${player.name()} se cache.")
                }
            }
        }

        class Scenarios {
            fun seesTheSneaker(b: Bench) {
                b.player("Bartok", Place("overworld", 0.5, 64.0, 0.5))
                b.start()
                b.pose("Bartok", true, true, 0f)
                b.run(20)
                b.check(b.acts().contains("say: Bartok se cache."), "Bartok is seen")
            }

            fun checksWrongly(b: Bench) {
                b.player("Bartok", Place("overworld", 0.5, 64.0, 0.5))
                b.start()
                b.run(20)
                b.check(b.acts().isNotEmpty(), "nobody hides")
            }

            fun throws(b: Bench) {
                b.start()
                error("le scénario a trébuché")
            }

            fun startsTwice(b: Bench) {
                b.start()
                b.start()
            }

            fun timerThrows(b: Bench) {
                b.player("Zero", Place("overworld", 0.5, 64.0, 0.5))
                b.pose("Zero", true, true, 0f)
                b.start()
                b.run(40)
            }

            fun aNewBench(b: Bench) {
                b.check(b.acts().isEmpty() && b.ended() == null && b.creatures().isEmpty(), "nothing left of the others")
            }
        }

        companion object {
            val SCENARIOS = listOf("seesTheSneaker", "checksWrongly", "throws", "startsTwice", "timerThrows", "aNewBench")
        }
    }

    /** Talks at each tick with new words, and fails its scenarios with long messages full of brackets. */
    class Verbose : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.timers().every(1) { ctx.acts().say("<b>Tic ${ctx.now()}</b>") }
        }

        class Scenarios {
            fun one(b: Bench) = b.check(false, "<" + "x".repeat(2_000) + ">")

            fun two(b: Bench) = b.check(false, "<" + "y".repeat(2_000) + ">")

            fun three(b: Bench): Unit = throw IllegalStateException("<" + "z".repeat(2_000) + ">")
        }

        companion object {
            val SCENARIOS = listOf("one", "two", "three")
        }
    }

    /** Its scenario runs ticks without end. */
    class Endless : ForgeModule {
        override fun start(ctx: ModuleContext) {}

        class Scenarios {
            fun forever(b: Bench) {
                b.start()
                b.run(Int.MAX_VALUE)
            }

            fun never(b: Bench) = b.start()
        }
    }

    /**
     * A module whose objects handed to the SDK (a timer's task, handlers, callbacks, a list of cells) fail when their
     * `hashCode`, `equals`, `toString` or methods run outside one of its calls (M5 §4.1, review finding 8).
     */
    class Touchy : ForgeModule {
        override fun start(ctx: ModuleContext) {
            ctx.events().on(GameFact.Chat::class.java, PricklyConsumer { ran++ })
            ctx.timers().every(10, PricklyRunnable {
                ran++
                ctx.acts().particles(Place("overworld", 0.5, 66.0, 0.5), "heart", 1).then(PricklyConsumer { ran++ })
            })
            ctx.ground().place(PricklyCells(listOf(Cell("overworld", 0, 70, 0))), "glass", 5)
        }

        abstract class Prickly {
            protected fun touched(): Int {
                check(KotlinHost.inCall) { "a module's object was touched outside its calls" }
                return 7
            }

            override fun hashCode(): Int = touched()

            override fun equals(other: Any?): Boolean = touched() == 0

            override fun toString(): String = "prickly ${touched()}"
        }

        class PricklyRunnable(private val body: () -> Unit) : Prickly(), Runnable {
            override fun run() {
                touched()
                body()
            }
        }

        class PricklyConsumer<T>(private val body: (T) -> Unit) : Prickly(), Consumer<T> {
            override fun accept(item: T) {
                touched()
                body(item)
            }
        }

        class PricklyCells(private val cells: List<Cell>) : AbstractList<Cell>() {
            override val size: Int get() = cells.size.also { check(KotlinHost.inCall) { "a module's list was read outside its calls" } }

            override fun get(index: Int): Cell = cells[index].also { check(KotlinHost.inCall) { "a module's list was read outside its calls" } }

            override fun hashCode(): Int = error("a module's list is never a key")
        }

        class Scenarios {
            fun touchy(b: Bench) {
                b.player("Bartok", Place("overworld", 0.5, 64.0, 0.5))
                b.start()
                b.fact(GameFact.Chat("Bartok", null, "salut"))
                b.run(25)
                b.check(ran >= 3, "its handler, timer and callbacks ran")
            }
        }

        companion object {
            @Volatile
            var ran = 0
        }
    }

    companion object {
        private const val BUDGET_MS = 30_000L

        private fun at(x: Double, z: Double = 0.5) = Place("overworld", x, 64.0, z)

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
