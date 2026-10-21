package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.config.ForgeConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.BlockInfo
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.host.Fault
import io.github.clemsix6.narrator2.forge.host.Hosts
import io.github.clemsix6.narrator2.forge.host.LeakWatch
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.ActRunner
import io.github.clemsix6.narrator2.forge.sdk.ParamLookup
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.ModuleRun
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.minecraft.world.entity.Entity
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertFailsWith

/**
 * The modules' engine and book on their own (M5 §6.4-6.7), for the unit tests: real modules compiled, guarded and
 * rewritten ([Hosts.build]), run by the real host on its module thread, with the SDK's real pieces; a fake Forge (its
 * arsenal and built classes), memory, Director and challenges; a fake server ([Server]: the test's thread is the
 * server thread, the world empty) and a fake act runner ([Runner]) for the Narrator's tools. The clock only moves when
 * the test ticks ([tick], [second]).
 */
internal class ModuleFixture(
    initial: MechanicsState = MechanicsState.EMPTY,
    maxActive: Int = ForgeConfig().maxActive,
    val clock: TestClock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening)),
    val forge: FakeForge = FakeForge(clock),
    val fakeMemory: FakeMemory = FakeMemory(clock),
) : AutoCloseable {
    /** The canon drafts written, in order (the fake store does not keep their mechanic). */
    val drafts = CopyOnWriteArrayList<CanonDraft>()
    private val canonStore: MemoryStore = object : MemoryStore by fakeMemory.store {
        override suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry> {
            drafts += draft
            return fakeMemory.store.addCanon(draft, author)
        }

        /**
         * As the real store with the engine's ends: a row closed already, a status its kind does not close with, and a note
         * in the first or second person (`VoiceGuard`) are refused.
         */
        override suspend fun resolveCanon(ref: Ref, status: CanonStatus, note: String, winners: List<UUID>, author: Author): CanonEntry {
            val row = fakeMemory.store.canon(ref) ?: throw MemoryRejected("no canon entry $ref")
            if (!row.status.open) throw MemoryRejected("$ref is already ${row.status.id}; remember a new entry instead")
            if (!row.kind.allows(row.status, status)) throw MemoryRejected("a ${row.kind.id} cannot be ${status.id}")
            VoiceGuard.check(note, "note", MemoryLimits.RESOLUTION, boundQuotes = false)?.let { throw MemoryRejected(it) }
            return fakeMemory.store.resolveCanon(ref, status, note, winners, author)
        }
    }
    val memory: Memory = object : Memory by fakeMemory {
        override val store: MemoryStore get() = canonStore
    }
    val director = FakeDirector(clock)
    val challenges = FakeChallenges(clock, AtomicLong(75))
    val services = TestServices(
        clock = clock, memory = memory, director = director, forge = forge,
        settings = testSettings(NarratorConfig(forge = ForgeConfig(maxActive = maxActive))),
    )
    val store = StateStore.inMemory(initial)

    /** The test's thread is the server thread: at its priority (8, `MinecraftServer.spin`), which the module thread takes (M5-LOAD). */
    private val testPriority = Thread.currentThread().priority.also { Thread.currentThread().priority = SERVER_PRIORITY }
    val threads = ModuleThreads("module-engine-test")
    val runner = Runner()
    val server = Server()

    /** The garbage collections the leak watch counts: the test moves them. */
    @Volatile
    var collections = 0L
    val leakWatch = LeakWatch(clock) { collections }
    val faults = CopyOnWriteArrayList<Pair<ModuleRun, Fault>>()
    val engine = ModuleEngine(services, store, threads, runner, challenges, server, clock, leakWatch, { run, fault -> faults += run to fault })
    val book = ModuleBook(services, store, engine)
    val caller: Caller = MechanicsSamples.director

    /** Starts the engine on its state, as the server starts. */
    fun start() = engine.begin(null, clock.millis())

    /**
     * One tick of 50 ms; then the calls given up in it (a busy machine held the module thread back past the caller's
     * deadline, M5-LOAD) are waited for and settled, so that what the test reads next does not depend on the machine.
     */
    fun tick() {
        clock.advance(Duration.ofMillis(TICK_MS))
        engine.tick(clock.millis())
        settle()
    }

    /** Waits until no call of a module given up still runs (each settled: its meter's fault, or nobody's). */
    fun settle() {
        val until = System.nanoTime() + 300_000_000_000L
        while (engine.settle()) {
            check(System.nanoTime() < until) { "a call given up never ended" }
            Thread.sleep(2)
        }
    }

    fun ticks(n: Int) = repeat(n) { tick() }

    /** One second: its last tick, then the second's work. */
    fun second() {
        clock.advance(Duration.ofMillis(1_000 - TICK_MS))
        tick()
        engine.second(clock.millis())
    }

    /** Module [name] in the arsenal, version [version], built from [source] (its class is the name in UpperCamelCase). */
    fun module(
        name: String,
        source: String,
        params: List<ParamSpec> = emptyList(),
        version: Int = 1,
        status: ModuleStatus = ModuleStatus.READY,
        problem: String? = null,
    ): ArsenalEntry {
        val entry = ForgeSamples.manifest.copy(
            name = name, version = version, status = status, params = params, hash = "sha256:$name-v$version", problem = problem, scenarios = emptyList(),
        )
        forge.arsenal.add(entry, source)
        if (name !in forge.builtClasses) forge.builtClasses[name] = Hosts.build(FakeForge.mainClass(name), source)
        return entry
    }

    /** What `deploy_module` asks for [module]. */
    fun spec(module: String, params: String = "{}", kind: RuleKind = RuleKind.LAW, minutes: Int = 10, version: Int? = null, challenge: Ref? = null) = DeploySpec(
        module = module, version = version, params = Json.parseToJsonElement(params) as JsonObject, kind = kind,
        statement = "Le Narrateur a lancé le module $module.", quote = if (kind == RuleKind.LAW) "Que le jeu commence !" else null,
        minutes = minutes, challenge = challenge,
    )

    fun deploy(spec: DeploySpec): ModuleView = runBlocking { book.deploy(spec, caller) }

    /** What `deploy_module` answers to [spec], refused: as the tool shows it. */
    fun refusal(spec: DeploySpec): String = runBlocking { assertFailsWith<MechanicRejected> { book.deploy(spec, caller) }.toResult().text }

    fun canon(ref: Ref): CanonEntry = fakeMemory.store.rows.getValue(ref) as CanonEntry

    fun decisions(name: String, kind: DecisionRecord.Kind = DecisionRecord.Kind.MECHANIC): List<DecisionRecord> =
        services.traces.decisions.filter { it.kind == kind && it.decision == name }

    /** The `MODULE` lines the Director got. */
    fun moduleLines(): List<String> = director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.MODULE }.map { it.line }

    /**
     * Waits until [condition] holds (the coroutines run meanwhile: acts, canon, builds), five seconds at most; with
     * [ticking], the engine ticks meanwhile (without moving the clock), its modules' timers too.
     */
    fun await(what: String, ticking: Boolean = true, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out waiting for: $what" }
            Thread.sleep(5)
            if (ticking) engine.tick(clock.millis())
        }
    }

    override fun close() {
        engine.stopping()
        threads.close()
        Thread.currentThread().priority = testPriority
    }

    /**
     * The server for the engine: the test's thread is its thread; an empty world; Bartok, Mimosa and Josiane have played
     * here. [heldMillis]: how long its thread is held back while it reads the world's time for a module (a busy machine);
     * [heldOnce]: as long, for the next read only.
     */
    class Server : ModuleServer {
        @Volatile
        var heldMillis = 0L

        @Volatile
        var heldOnce = 0L

        override fun execute(task: Runnable) = task.run()

        override suspend fun <T> onServer(block: () -> T): T = block()

        override fun world(own: (Entity) -> Boolean): World = object : World by EmptyWorld {
            override fun dayTime(): Long {
                val held = maxOf(heldMillis, heldOnce)
                heldOnce = 0
                if (held > 0) Thread.sleep(held)
                return 0
            }
        }

        override fun dimensionOf(name: String): String? = null

        override fun params(caller: Caller): ParamLookup = object : ParamLookup {
            override val dimensions = setOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")

            override suspend fun players(names: List<String>, field: String): List<PlayerRef> = names.map { name ->
                KNOWN.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: throw MechanicRejected(null, "$field: no player named $name has ever played here")
            }

            override suspend fun standing(name: String): Place? = null
        }
    }

    /** The engine's acts: what was opened and run, each answered `ok`. */
    class Runner : ActRunner {
        val opened = CopyOnWriteArrayList<Pair<String?, String>>()
        val ran = CopyOnWriteArrayList<Triple<Long, String, JsonObject>>()
        private val ids = AtomicLong()

        override fun open(ref: Ref, subject: String?, reason: String): Firing {
            val id = -ids.incrementAndGet()
            opened += subject to reason
            return Firing(id, ref, Caller(Role.DIRECTOR, "mechanics:$ref", null, null, id))
        }

        override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult {
            ran += Triple(firing.id, tool, args)
            return ToolResult.ok("ok: $tool")
        }
    }

    /** A world with nobody in it. */
    private object EmptyWorld : World {
        override fun players(): List<PlayerInfo> = emptyList()

        override fun player(name: String?): PlayerInfo? = null

        override fun creatures(around: Place?, radius: Double, type: String?): List<CreatureInfo> = emptyList()

        override fun block(cell: Cell?): BlockInfo? = null

        override fun surface(dimension: String?, x: Int, z: Int): Int = Int.MIN_VALUE

        override fun biome(at: Place?): String? = null

        override fun count(player: String?, item: String?): Int = -1

        override fun dayTime(): Long = 0

        override fun weather(): String = "clear"
    }

    companion object {
        const val TICK_MS = 50L

        /** The server thread's priority on a machine of more than four cores (`MinecraftServer.spin`, javap). */
        const val SERVER_PRIORITY = 8

        /** The players the server knows. */
        val KNOWN = listOf(MechanicsSamples.bartok, MechanicsSamples.mimosa, MechanicsSamples.josiane)

        /** A module `[name]`'s source: [members], and [body] as its `start(ModuleContext m)`. */
        fun source(name: String, members: String = "", body: String = ""): String = Hosts.source(FakeForge.mainClass(name), members, body)
    }
}
