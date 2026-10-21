package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.module.ModuleBook
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * In-game tests of the built-in modules of the creatures (task M5-15, M5 §10, §16.3), in the Forge's batch. Each test
 * has a Forge of its own ([ForgeService], the real pipeline) that builds the module from the mod's jar as the server's
 * Forge does when it starts, and a modules' engine of its own ([Rig]: its state in memory, the real host, SDK, tools and
 * guards) ticked once a server tick, as the server's engine is: the chickens of `orbiting_chickens` really circle their
 * player at head height, tick after tick, then come down and vanish; the `parade` follows its player in single file,
 * and a marcher killed is summoned again. What an engine tick took goes to the log (`M5-15 in the server: …`).
 */
class ArsenalCreaturesGameTests {
    /**
     * `orbiting_chickens` for 10 s around Bob, watched at each tick: on a circle of 2.5 blocks around him, their feet 1.4
     * blocks above his, turning one way at a turn every four seconds; then, gravity back, down to the floor without a
     * drop, and gone with the module, which ended by itself.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun orbitingChickensCircleTheirPlayerAtHeadHeightThenComeDown(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper, "oc_").also { it.standAt(BlockPos(3, 1, 3)) }
        val rig = Rig(helper, ORBITING_CHICKENS)
        helper.onEachTick(rig::tick)
        helper.runScript(ASYNC_MAX_TICKS) {
            rig.running(bob) {
                // Each tick, after the engine's: where each chicken of the deployment is, from Bob's feet.
                val seen = mutableListOf<Seen>()
                rig.onServer { rig.afterTick = { seen += rig.ownCreatures().map { Seen.of(rig.ticks, it, bob.live.position()) } } }
                val view = rig.deploy(ORBITING_CHICKENS, """{"player": "${bob.name}", "chickens": 4, "seconds": 10}""")
                val ended = rig.awaitEnd(view.ref, CanonStatus.FULFILLED, ORBIT_END_TICKS)
                check(ended.resolution == LANDED) { "it ended by itself once landed: ${ended.resolution}" }
                check(rig.moduleLines().single().let { it.startsWith("${view.ref} orbiting_chickens ended by itself: « $LANDED » (") && it.endsWith(" acts, 0 faults)") }) {
                    "the Director is told: ${rig.moduleLines()}"
                }
                val samples = rig.onServer { seen.toList() }
                val chickens = samples.map { it.uuid }.distinct()
                check(chickens.size >= 2) { "its chickens, two by two (the server's other tests may hold the Narrator's cap): $chickens" }

                // The orbit: every chicken aloft, gravity off; the first second is its take-off.
                val aloftTicks = samples.groupBy { it.tick }.filterValues { at -> at.size == chickens.size && at.all { it.floating } }.keys.sorted()
                val orbit = aloftTicks.drop(TAKE_OFF_TICKS).toSet()
                check(orbit.size >= ORBIT_TICKS) { "it circled ${orbit.size} ticks, ${ORBIT_TICKS} at least" }
                val circling = samples.filter { it.tick in orbit }
                val onCircle = circling.count { abs(it.radius - RADIUS) < 0.5 && abs(it.dy - HEIGHT) < 0.45 }
                check(onCircle >= circling.size * 0.95) {
                    "on a circle of $RADIUS blocks at head height ($HEIGHT above the feet): ${circling.size - onCircle} of ${circling.size} off it, e.g. " +
                        circling.filterNot { abs(it.radius - RADIUS) < 0.5 && abs(it.dy - HEIGHT) < 0.45 }.take(3)
                }
                check(abs(median(circling.map { it.radius }) - RADIUS) < 0.2 && abs(median(circling.map { it.dy }) - HEIGHT) < 0.15) {
                    "median radius ${median(circling.map { it.radius })}, median height ${median(circling.map { it.dy })}"
                }
                for (chicken in chickens) {
                    val turns = circling.filter { it.uuid == chicken }.sortedBy { it.tick }.zipWithNext { a, b -> wrapped(b.angle - a.angle) }
                    val turned = turns.sum()
                    check(turned >= 0.8 * turns.size * TURN && turned >= 2 * PI) { "$chicken turned $turned radians in ${turns.size} ticks" }
                    check(turns.count { it > 0 } >= turns.size * 0.95) { "$chicken turns one way: ${turns.count { it <= 0 }} ticks back of ${turns.size}" }
                }

                // The landing: gravity back, each chicken down to the floor, gently.
                val landing = samples.filter { it.tick > aloftTicks.last() }
                for (chicken in chickens) {
                    val fall = landing.filter { it.uuid == chicken }.sortedBy { it.tick }
                    check(fall.isNotEmpty() && fall.none { it.floating }) { "$chicken has its gravity back" }
                    check(fall.minOf { it.dy } < 0.3) { "$chicken lands on the floor: ${fall.minOf { it.dy }} above Bob's feet at the lowest" }
                    val drop = fall.zipWithNext { a, b -> a.dy - b.dy }.maxOrNull() ?: 0.0
                    check(drop < 0.35) { "$chicken comes down gently: $drop blocks in one tick" }
                }
                rig.await("its chickens gone with it", GONE_TICKS) { rig.ownCreatures(view.ref).isEmpty() }
                check(bob.packets.filterIsInstance<ClientboundSetTitleTextPacket>().any { it.text().string == TITLE }) { "Bob saw the title « $TITLE »" }

                val ticks = rig.onServer { rig.tickNanos(orbit) }
                val slowest = rig.onServer { rig.slowestTicks() }
                Log.forge.info(
                    "M5-15 in the server: orbiting_chickens, ${chickens.size} chickens circling ticks ${orbit.min()} to ${orbit.max()}: an engine tick " +
                        "took ${micros(ticks, 0.5)} µs (median), ${micros(ticks, 0.95)} µs (95th percentile), ${micros(ticks, 1.0)} µs at most; " +
                        "its calls ${"%.2f".format(rig.slowestSecondMs)} ms in its slowest second; landed in ${landing.maxOf { it.tick } - aloftTicks.last()} " +
                        "ticks; slowest ticks of the whole run $slowest",
                )
            }
        }
    }

    /**
     * `parade` of three chickens behind Bob, who walks fourteen blocks east: the line follows him and closes up behind him
     * in single file, each chicken within [LINK] blocks of the one ahead, watched at every tick of three seconds once it
     * settled; a break (a chicken that wanders off, as chickens do between two steps of the parade) is mended within a
     * second ([MEND_TICKS]). Then one is killed, and a new one is summoned and joins the line. Stopped by an operator, they
     * go.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun theParadeFollowsItsPlayerAndAKilledMarcherIsSummonedAgain(helper: GameTestHelper) {
        for (x in 0 until FIELD_FLOOR) for (z in 0 until FIELD_FLOOR) helper.setBlock(x, 0, z, Blocks.STONE)
        val bob = TestPlayer(helper, "pa_").also { it.standAt(BlockPos(WALK_FROM, 1, LANE)) }
        val rig = Rig(helper, PARADE)
        helper.onEachTick(rig::tick)
        helper.runScript(ASYNC_MAX_TICKS) {
            rig.running(bob) {
                val view = rig.deploy(PARADE, """{"player": "${bob.name}", "creature": "chicken", "count": 3}""")
                val deployed = rig.onServer { rig.ticks }
                rig.await("its three chickens", SUMMON_TICKS, view.ref, PARADE_WAIT_MS) { rig.ownCreatures(view.ref).size == 3 }
                val first = rig.onServer { rig.ownCreatures(view.ref).associate { it.uuid to it.position() } }

                // Bob walks east, a block every half second; the parade steps twice a second.
                val walk = ArrayDeque((WALK_FROM + 1..WALK_TO).map { helper.absoluteVec(Vec3(it + 0.5, 1.0, LANE + 0.5)) })
                rig.onServer { rig.afterTick = { if (rig.ticks % WALK_TICKS == 0) walk.removeFirstOrNull()?.let { bob.live.teleportTo(it.x, it.y, it.z) } } }
                rig.await("Bob's walk", WALK_TICKS * (WALK_TO - WALK_FROM) + SLACK_TICKS, view.ref, PARADE_WAIT_MS) { walk.isEmpty() }
                val walked = rig.onServer { rig.ticks }
                // Once the line settled behind Bob, its longest link at each tick of WATCH_TICKS.
                val watched = mutableListOf<Double>()
                rig.onServer { rig.afterTick = { if (rig.ticks > walked + SETTLE_TICKS) watched += file(rig.ownCreatures(view.ref), bob.live.position()) } }
                rig.await("the line settled behind Bob, then watched", SETTLE_TICKS + WATCH_TICKS + SLACK_TICKS, view.ref, PARADE_WAIT_MS) {
                    rig.ticks >= walked + SETTLE_TICKS + WATCH_TICKS
                }
                val links = rig.onServer {
                    rig.afterTick = {}
                    watched.toList()
                }
                val (behind, end) = rig.onServer { rig.ownCreatures(view.ref) to bob.live.position() }
                val start = helper.absoluteVec(Vec3(WALK_FROM + 0.5, 1.0, LANE + 0.5))
                check(behind.map { it.uuid }.toSet() == first.keys) { "the same three chickens" }
                check(behind.all { it.position().distanceTo(end) < it.position().distanceTo(start) }) {
                    "they followed Bob from $start to $end: ${first.values} then ${behind.map { it.position() }}"
                }
                val broken = links.map { it > LINK }
                val longestBreak = broken.fold(0 to 0) { (run, longest), off -> (if (off) run + 1 else 0).let { it to maxOf(longest, it) } }.second
                check(links.size >= WATCH_TICKS && longestBreak < MEND_TICKS) {
                    "in single file behind Bob at $end, each within $LINK blocks of the one ahead, a break mended within $MEND_TICKS ticks: " +
                        "broken $longestBreak ticks in a row; the longest link at each of ${links.size} ticks: ${links.joinToString { "%.1f".format(it) }}"
                }

                // One is killed: Bob is told (the fact CreatureGone, « died »), and a new one is summoned and joins the line.
                val victim = behind[1]
                val killed = rig.onServer {
                    victim.kill(helper.level)
                    rig.ticks
                }
                check(victim.isDeadOrDying) { "the chicken is dead" }
                rig.await("a new chicken in the line", SUMMON_TICKS, view.ref, PARADE_WAIT_MS) {
                    val now = rig.ownCreatures(view.ref)
                    now.size == 3 && now.any { it.uuid !in first } && file(now, bob.live.position()) <= LINK
                }
                rig.await("Bob told of the fallen chicken", SLACK_TICKS, view.ref) { bob.chat().any { FALLEN in it.content().string } }

                rig.book.stop(view.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest"))
                rig.awaitEnd(view.ref, CanonStatus.CANCELLED, SLACK_TICKS)
                rig.await("its chickens gone", GONE_TICKS) { rig.ownCreatures(view.ref).isEmpty() }
                val ticks = rig.onServer { rig.tickNanos(null) }
                val slowest = rig.onServer { rig.slowestTicks() }
                Log.forge.info(
                    "M5-15 in the server: parade of 3 chickens, deployed at tick $deployed, Bob walked until tick $walked, the line behind him " +
                        "(its longest link ${"%.1f".format(links.min())} to ${"%.1f".format(links.max())} blocks over ${links.size} ticks, " +
                        "broken ${broken.count { it }} ticks, $longestBreak in a row at most), one killed at tick $killed and summoned again: " +
                        "an engine tick took ${micros(ticks, 0.5)} µs (median), " +
                        "${micros(ticks, 0.95)} µs (95th percentile), ${micros(ticks, 1.0)} µs at most over ${ticks.size} ticks; " +
                        "its calls ${"%.2f".format(rig.slowestSecondMs)} ms in its slowest second; slowest ticks $slowest",
                )
            }
        }
    }

    /** A chicken at one tick, from Bob's feet. */
    private data class Seen(val tick: Int, val uuid: UUID, val dx: Double, val dy: Double, val dz: Double, val floating: Boolean) {
        val radius: Double get() = hypot(dx, dz)
        val angle: Double get() = atan2(dz, dx)

        companion object {
            fun of(tick: Int, mob: Mob, feet: Vec3) = Seen(tick, mob.uuid, mob.x - feet.x, mob.y - feet.y, mob.z - feet.z, mob.isNoGravity)
        }
    }

    /**
     * The modules' engine of one test on the server's runtime: its state in memory, its own clock (50 ms a tick), its
     * own Forge, Director and speech limits, the real host, SDK, tools and guards. The test's [GameTestHelper.onEachTick]
     * ticks it once a server tick, as the server ticks its own engine; [afterTick] then looks at the world. Its waits
     * count its ticks ([await]): what it waits for comes with them.
     */
    private class Rig(val helper: GameTestHelper, private val module: String) {
        val runtime = helper.narrator()
        val clock = TestClock(Instant.ofEpochMilli(runtime.clock.millis()), runtime.clock.zone)
        val director = FakeDirector(clock)
        val forge = ForgeService(runtime, ArsenalStore(Files.createTempDirectory("narrator2-gametest-arsenal").resolve(ForgeService.ARSENAL_DIR), runtime.clock, builtInNames = listOf(module)))

        /**
         * Speech limits of its own, on its clock (FLAKY-5): the server's are shared by the batch, whose other tests spent
         * the engine's lines of the minute, and the parade's word to Bob about its fallen chicken was refused.
         */
        private val limits = ToolLimits(clock)

        val services: NarratorServices = object : NarratorServices by runtime {
            override val director: DirectorControl get() = this@Rig.director
            override val forge: Forge get() = this@Rig.forge
            override val toolLimits: ToolLimits get() = limits
        }
        val store = StateStore.inMemory()
        private val actions = EngineActions(services, speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) + moduleOnlyTools())
        private val threads = ModuleThreads("gametest-arsenal", forge.lostThreads)
        private val area: AABB = helper.bounds.inflate(16.0)
        val modules = ModuleEngine(
            services, store, threads, EngineActRunner(actions), runtime.mechanics.challenges, clock = clock,
            noteFault = { run, fault -> forge.noteFault(run.module, run.version, fault.text) },
            area = { entity -> area.contains(entity.position()) && ModuleCreatures.ownerOf(entity).let { it == null || store.snapshot.module(it) != null } },
        )
        val book = ModuleBook(services, store, modules, clock)
        private val engine = Engine(services, store, listOf(modules), clock)

        /** Whether [tick] ticks the engine: from its start to its stop. Server thread. */
        private var started = false

        /** Engine ticks so far, and what each one took. Server thread; [ticks] read from any. */
        @Volatile
        var ticks = 0
            private set
        private val nanos = mutableListOf<Long>()

        /** The most its deployments' calls took in one second, in milliseconds. */
        @Volatile
        var slowestSecondMs = 0.0
            private set

        /** Runs after each tick of the engine. Server thread. */
        var afterTick: () -> Unit = {}

        /** One server tick: the engine's, measured, then [afterTick]. Server thread. */
        fun tick() {
            if (!started) return
            clock.advance(TICK)
            val began = System.nanoTime()
            engine.tick(helper.level.server)
            nanos += System.nanoTime() - began
            ticks++
            modules.views(clock.millis()).maxOfOrNull { it.msPerSecond }?.let { slowestSecondMs = maxOf(slowestSecondMs, it) }
            afterTick()
        }

        /** Runs [test] once the Forge has built [module] from the jar and the engine runs; everything closed however it ends. */
        suspend fun running(bob: TestPlayer, test: suspend () -> Unit) {
            try {
                forge.start().join()
                val entry = forge.arsenal.entry(module, 0)
                check(entry != null && entry.builtIn && entry.status == ModuleStatus.READY) { "$module built from the mod's jar when the Forge started: $entry" }
                onServer {
                    engine.start(helper.level.server)
                    started = true
                }
                test()
            } finally {
                for (view in book.list()) runCatching { book.stop(view.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest")) }
                onServer {
                    started = false
                    afterTick = {}
                    if (modules.running) engine.stop()
                }
                threads.close()
                forge.stop()
                onServer { bob.close() }
            }
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /**
         * Waits until [condition] holds on the server thread, the engine ticking with the server meanwhile: [ticks] of the
         * engine's ticks, and [timeoutMs] of the wall clock for the acts' tools, both spent before it fails ([awaitTicks]:
         * a busy batch slows the server down, never the module's ticks). Fails at once, with its reason, when the
         * deployment [alive] ends before.
         */
        suspend fun await(what: String, ticks: Int, alive: Ref? = null, timeoutMs: Long = 15_000, condition: () -> Boolean) =
            awaitTicks(what, ticks, { this.ticks }, timeoutMs) {
                val (met, gone) = onServer { condition() to (alive != null && store.snapshot.module(alive.id) == null) }
                if (!met && gone) {
                    awaitCondition("$alive resolved") { runtime.memory.store.canon(alive!!)?.status != CanonStatus.ACTIVE }
                    error("$alive ended while waiting for $what: « ${runtime.memory.store.canon(alive!!)?.resolution} »")
                }
                met
            }

        suspend fun deploy(module: String, params: String): ModuleView = book.deploy(
            DeploySpec(
                module = module, version = null, params = Json.parseToJsonElement(params) as JsonObject, kind = RuleKind.PLAN,
                statement = "Le Narrateur a lancé le module $module.", quote = null, minutes = MINUTES, challenge = null,
            ),
            DIRECTOR,
        )

        /** The creatures of the rig's deployments (of [ref] alone when given) in the test's area, alive. Server thread. */
        fun ownCreatures(ref: Ref? = null): List<Mob> = helper.level.getEntitiesOfClass(Mob::class.java, area) { mob ->
            val owner = ModuleCreatures.ownerOf(mob)
            mob.isAlive && owner != null && (if (ref == null) store.snapshot.module(owner) != null else owner == ref.id)
        }

        /** [ref] ended within [ticks] of the engine: its canon row closed [status] by the engine (another end fails with its reason). */
        suspend fun awaitEnd(ref: Ref, status: CanonStatus, ticks: Int): CanonEntry {
            await("$ref closed $status", ticks) { store.snapshot.module(ref.id) == null }
            awaitCondition("$ref closed $status") { runtime.memory.store.canon(ref)?.status != CanonStatus.ACTIVE }
            val canon = runtime.memory.store.canon(ref)!!
            check(canon.status == status) { "$ref closed ${canon.status}, not $status: « ${canon.resolution} »" }
            return canon
        }

        fun moduleLines(): List<String> = director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.MODULE }.map { it.line }

        /** What the engine's ticks took: those of [only] when given. Server thread. */
        fun tickNanos(only: Set<Int>?): List<Long> = nanos.filterIndexed { i, _ -> only == null || (i + 1) in only }.sorted()

        /** The five slowest ticks, `tick: µs`. Server thread. */
        fun slowestTicks(): String = nanos.withIndex().sortedByDescending { it.value }.take(5).joinToString { "${it.index + 1}: ${it.value / 1_000}" }
    }

    private companion object {
        const val ORBITING_CHICKENS = "orbiting_chickens"
        const val PARADE = "parade"
        const val MINUTES = 10
        val TICK: Duration = Duration.ofMillis(50)
        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)
        const val FIELD = "narrator2-gametest:oracle_field"

        // orbiting_chickens, as its source says (RADIUS, HEIGHT, TURN, LANDED).
        const val RADIUS = 2.5
        const val HEIGHT = 1.4
        const val TURN = 2 * PI / 80
        const val LANDED = "Les poules sont redescendues sur terre."
        const val TITLE = "Mise en orbite"

        // The parade, as its source says.
        const val FALLEN = "Une poule a quitté les rangs."

        /** Ticks of the orbit not judged: the chickens fly from where they were summoned to the circle. */
        const val TAKE_OFF_TICKS = 20

        /** Ticks of orbit at least: 10 s asked, the take-off left out. */
        const val ORBIT_TICKS = 150

        /**
         * Engine ticks the orbit gets to end by itself: its 10 s (200 ticks) once its chickens are in, its landing (60), its
         * puff; about 270 ticks measured (FLAKY-5), twice that allowed.
         */
        const val ORBIT_END_TICKS = 600

        /** Engine ticks for anything that follows within a tick or two of an act's answer: a word said, an end, a creature gone. */
        const val SLACK_TICKS = 100

        /** Engine ticks for a module's creatures to go with it, released as it ends. */
        const val GONE_TICKS = SLACK_TICKS

        /**
         * Engine ticks for the parade to summon its marchers and see them in line: a summoning refused (the cap of the
         * Narrator's creatures is the server's, shared with the batch) pauses the next one for 30 s of the game, 600 ticks.
         */
        const val SUMMON_TICKS = 1_200

        // The parade: a floor of the field, Bob's lane, his walk.
        const val FIELD_FLOOR = 24
        const val LANE = 12
        const val WALK_FROM = 5
        const val WALK_TO = 19
        const val WALK_TICKS = 10

        /**
         * Blocks at most from Bob to the first chicken, and from each chicken to the next: their places are a block
         * apart, a marcher walks again only beyond two blocks, moveTo stops about a block short (a chicken may stay
         * 2.4 blocks off for seconds), and the line steps twice a second.
         */
        const val LINK = 3.5

        /** Ticks the line has to close up behind Bob once he stops: five seconds of the game. */
        const val SETTLE_TICKS = 100

        /** Ticks the line is watched then, each one: three seconds, six steps of the parade. */
        const val WATCH_TICKS = 60

        /**
         * Ticks a break of the line lasts at most: a second, two steps of the parade. Between two steps a chicken wanders
         * as chickens do (`RandomStrollGoal`), and the next step sends it back once it is two blocks off: measured under
         * load (M5-FIX-1), a break lasted 7 ticks at most, 0 to 10 ticks in a run.
         */
        const val MEND_TICKS = 20

        /** Wall-clock time the parade's waits leave the acts' tools, besides their ticks ([Rig.await]). */
        const val PARADE_WAIT_MS = 30_000L

        /**
         * The longest link of the single file [chickens] make behind [feet] at best: over the orders they may walk in, Bob
         * to the first and each to the next, the order whose longest link is the shortest. Whatever order the module keeps
         * them in (the order they became its own, which the test does not see), its file has a link no shorter; taking
         * the chicken nearest to the one ahead instead fails a file whose last one stands a step in front of Bob.
         */
        fun file(chickens: List<Mob>, feet: Vec3): Double =
            orders(chickens.map { it.position() }).minOf { order -> (listOf(feet) + order).zipWithNext { a, b -> a.distanceTo(b) }.maxOrNull() ?: 0.0 }

        /** Every order of [items]: 6 for the parade's three chickens. */
        fun <T> orders(items: List<T>): List<List<T>> =
            if (items.size <= 1) listOf(items) else items.indices.flatMap { i -> orders(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }

        fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

        /** An angle's change brought within -π and π. */
        fun wrapped(delta: Double): Double = (delta + 3 * PI) % (2 * PI) - PI

        fun micros(sorted: List<Long>, at: Double): Long = if (sorted.isEmpty()) -1 else sorted[((sorted.size - 1) * at).toInt()] / 1_000
    }
}
