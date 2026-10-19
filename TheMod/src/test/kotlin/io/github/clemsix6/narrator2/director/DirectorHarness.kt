@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.clemsix6.narrator2.director

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.memory.DisabledMemory
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeReflexes
import io.github.clemsix6.narrator2.testing.FakeWorkers
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A whole Director without Minecraft (M1_M2 §17.2): the real bus, salience, inbox, `AgentLoop` and
 * `ToolRunner`, a `ClaudeClient` talking to a strict [FakeAnthropic], fake tools (`say`, `sql`, and
 * `slow_probe`, which waits for the test), fake reflexes, a fake game (world clock, action bar,
 * operators), a fake memory ([memory], M3), traces in memory and virtual time.
 *
 * Time only moves when the test says so ([advance]): the Director's coroutines run on a test
 * dispatcher whose clock is also the engine's [clock]. The fake server answers on real threads; the
 * Director resumes when the test pumps the dispatcher ([awaitUntil], [runCurrent]). Every request the
 * fake server got is checked by [assertInvariants]: nothing rejected by the strict server (append-only
 * against the last successful exchange, tool results in place, no empty text) and no empty assistant
 * turn.
 */
class DirectorHarness(
    dir: Path,
    mode: OperatorChannelMode = OperatorChannelMode.SYSTEM_MESSAGE,
    heartbeatMinutes: List<Int> = listOf(3, 3),
    turnsPerHour: Int = NarratorConfig().director.turnsPerHour,
    memoryOff: String? = null,
    /** The reminders saved by a previous server (M4 §15.1): another harness's [FakeGame.reminders] plays a restart. */
    reminders: InMemoryReminderStore = InMemoryReminderStore(),
    /** When set, the services have a `DisabledMechanics` with this reason instead of [mechanics]. */
    mechanicsOff: String? = null,
) : AutoCloseable {
    val scheduler = TestCoroutineScheduler()
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    val clock = SchedulerClock(scheduler, START)
    val fake = FakeAnthropic()
    val client: ClaudeClient
    val reflexes = FakeReflexes(chat = { FakeReflexes.verdict(toNarrator = 0.0) })
    val traces = MemoryTraceSink()
    val players = PlayerStates()
    val guards = FakeGuards()
    val workers = FakeWorkers()
    val game = FakeGame(reminders)
    val bus = EventBus(clock, { 0L }, 1)

    /** The mechanics engine (M4): a fake whose situation lines the test sets ([FakeMechanics.lines]). */
    val mechanics = FakeMechanics(clock)

    /**
     * The memory (M3): a fake that records what the Director asks of it and gives back what the test set;
     * the services have a `DisabledMemory` instead when the harness is made with `memoryOff`.
     */
    val memory = FakeMemory(clock)

    /** The tool environment of each chapter opened, in order. */
    val toolEnvs = CopyOnWriteArrayList<ToolEnv>()

    /** Texts said by the fake `say` tool, in order. */
    val said = CopyOnWriteArrayList<String>()

    /** What the fake `sql` tool answers. */
    @Volatile
    var sqlResult: String = "n\n0"

    /** The pending `slow_probe` call, released by the test. */
    val probe = Probe()

    private val base: TestServices
    val services: NarratorServices
    val director: Director

    init {
        val defaults = testConfig(fake.baseUrl)
        val config: NarratorConfig = defaults.copy(
            director = defaults.director.copy(heartbeatMinutes = heartbeatMinutes, operatorChannel = mode.id, turnsPerHour = turnsPerHour),
        )
        client = ClaudeClient(config, TEST_API_KEY)
        base = TestServices(
            clock = clock,
            scope = scope,
            dataDir = dir,
            settings = testSettings(config, client),
            bus = bus,
            trace = traces,
            transcripts = Transcripts(dir.resolve("transcripts"), enabled = true),
            players = players,
            guards = guards,
            reflexes = reflexes,
            workers = workers,
            memory = memoryOff?.let(::DisabledMemory) ?: memory,
            mechanics = mechanicsOff?.let { DisabledMechanics(it) } ?: mechanics,
        )
        services = object : NarratorServices by base {
            override val director get() = this@DirectorHarness.director
        }
        director = Director(services, game, { env -> toolEnvs += env; ToolSet.of(listOf(SayTool(), SqlTool(), SlowProbeTool())) }, Random(7))
        director.start()
        runCurrent()
    }

    // ── Players and events ──────────────────────────────────────────────────────────────────────────

    /** Puts [name] online, without a join event. */
    fun online(name: String, isOp: Boolean = false): PlayerRef {
        val ref = ref(name)
        val now = clock.millis()
        players.put(
            LivePlayer(
                ref = ref, sessionId = 1, joinedAt = now - 72 * 60_000L, isOp = isOp, gameMode = "survival", pos = Pos("minecraft:overworld", 12, -58, 40),
                biome = "minecraft:dripstone_caves", structure = null, underground = true, health = 14f, maxHealth = 20f, food = 12,
                vehicle = null, lastDeathAt = null, lastMoveAt = now, idle = false,
            ),
        )
        return ref
    }

    /** Publishes [event] on the bus and lets the salience handle it. */
    fun publish(event: GameEvent): Envelope {
        val envelope = bus.publish(event) ?: fail("the bus is closed")
        runCurrent()
        return envelope
    }

    /** A public message from [name]; the reflexes say it is addressed to the Narrator when [toNarrator] ≥ 0.6. */
    fun chat(name: String, text: String, toNarrator: Double = 0.92, channel: ChatChannel = ChatChannel.PUBLIC): Envelope {
        reflexes.chat = { FakeReflexes.verdict(toNarrator, Tone.HOSTILE, ChatIntent.PROVOCATION) }
        return publish(ChatMessage(ref(name), channel, text, emptyList()))
    }

    // ── Time and waiting ────────────────────────────────────────────────────────────────────────────

    /** Runs what is ready on the Director's dispatcher, without moving time. */
    fun runCurrent() = scheduler.runCurrent()

    /** Moves virtual time by [ms] and runs what is due. */
    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** Pumps the dispatcher until [condition] holds (the fake server answers on its own threads). */
    fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for $what: state ${director.status().state}, ${fake.requests.size} requests, turns ${traces.turns.map { it.outcome }}")
            }
            Thread.sleep(2) // the fake server answers on a real thread
        }
    }

    fun awaitRequests(count: Int) = awaitUntil("$count requests") { fake.requests.size >= count }

    /** Waits until [count] turns have ended and the Director waits again. */
    fun awaitTurns(count: Int) = awaitUntil("$count turns ended") {
        endedTurns().size >= count && director.status().turnId == null && director.status().state != DirectorState.RUNNING
    }

    /** The turns that ended, last version of each, in order. */
    fun endedTurns(): List<TurnRecord> = traces.turns.filter { it.outcome != null }

    /** The server stops: the phase changes and every coroutine of the runtime's scope is cancelled. */
    fun stopServer() {
        base.phase = Phase.STOPPING
        scope.coroutineContext.job.cancel()
        runCurrent()
    }

    // ── Requests ────────────────────────────────────────────────────────────────────────────────────

    fun messages(request: Int): JsonNode = fake.requests[request].json.path("messages")

    /** The last `user` message of request [request]. */
    fun lastUser(request: Int): JsonNode = messages(request).last { it.path("role").asText() == "user" }

    /** The text blocks of the last `user` message of request [request], joined. */
    fun userText(request: Int): String = blocks(lastUser(request)).filter { it.path("type").asText() == "text" }.joinToString("\n") { it.path("text").asText() }

    /** The last text block of the last `user` message of request [request]: the wake-up message, after a chapter's briefing. */
    fun wakeText(request: Int): String = blocks(lastUser(request)).last { it.path("type").asText() == "text" }.path("text").asText()

    /** The block types of the last `user` message of request [request]. */
    fun userBlocks(request: Int): List<String> = blocks(lastUser(request)).map { it.path("type").asText() }

    fun sessionOf(request: Int): String? = fake.requests[request].conversation

    /**
     * What every request must respect: nothing rejected by the strict fake server (append-only against
     * the last successful exchange, tool results first, no empty text), and no empty assistant turn.
     */
    fun assertInvariants() {
        assertTrue(fake.rejections.isEmpty(), "rejected by the strict fake server: ${fake.rejections}")
        fake.requests.forEachIndexed { index, request: RecordedRequest ->
            request.json.path("messages").forEach { message ->
                if (message.path("role").asText() == "assistant") {
                    val content = blocks(message).filterNot { it.path("type").asText() == "text" && it.path("text").asText().isBlank() }
                    assertTrue(content.isNotEmpty(), "request $index holds an empty assistant turn")
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        runCurrent()
        client.close()
        fake.close()
        bus.close()
    }

    // ── Fakes ───────────────────────────────────────────────────────────────────────────────────────

    /** A `slow_probe` call that waits until the test [release]s it. */
    class Probe {
        @Volatile
        private var gate = CompletableDeferred<Unit>()

        @Volatile
        var running = false
            private set

        suspend fun run() {
            running = true
            gate.await()
            running = false
            gate = CompletableDeferred()
        }

        fun release() {
            gate.complete(Unit)
        }
    }

    /**
     * The game: a fixed world clock, what was shown to players and told to operators, and the reminders kept
     * in memory ([reminders]: what the world would hold, M4 §15.1).
     */
    class FakeGame(override val reminders: InMemoryReminderStore = InMemoryReminderStore()) : GameView {
        @Volatile
        var world: WorldTime? = WorldTime(37, "night", "clear")
        val shown = CopyOnWriteArrayList<Pair<List<UUID>, String>>()
        val cleared = CopyOnWriteArrayList<List<UUID>>()
        val toldOperators = CopyOnWriteArrayList<String>()

        override suspend fun worldTime(): WorldTime = world ?: throw IllegalStateException("the server thread did not answer in time")

        /** What the briefing reads of the world: day 37, 20:00, clear, and no player (the briefing then uses the players' samples). */
        @Volatile
        var snapshot: WorldSnapshot? = WorldSnapshot(37, 14_000, "clear", emptyList())

        override suspend fun worldSnapshot(): WorldSnapshot = snapshot ?: throw IllegalStateException("the server thread did not answer in time")

        override fun actionbar(text: String, to: () -> Collection<UUID>) {
            val players = to().toList()
            if (players.isNotEmpty()) shown += players to text
        }

        override fun clearActionbar(players: Collection<UUID>) {
            cleared += players.toList()
        }

        override fun tellOperators(text: String) {
            toldOperators += text
        }
    }

    @Serializable
    data class SayArgs(@Doc("What to say.") val text: String, @Doc("Names of online players; everyone when left out.") val to: List<String>? = null)

    /** `say`: publishes the line on the bus like the real tool, and records it. */
    inner class SayTool : NarratorTool<SayArgs>("say", "Speak to the players in the chat.", SayArgs.serializer(), ToolEffect.SPEECH) {
        override suspend fun run(ctx: ToolContext, args: SayArgs): ToolResult {
            val targets = args.to?.map { name -> ctx.players.byName(name)?.ref ?: return ToolResult.invalid("$name is not online") }
            ctx.services.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, args.text, targets, ctx.caller.turnId, null))
            said += args.text
            return ToolResult.ok("ok: said to ${targets?.joinToString { it.name } ?: "everyone"}", targets ?: ctx.players.online.map { it.ref })
        }
    }

    @Serializable
    data class SqlArgs(@Doc("One SELECT.") val query: String)

    /** `sql`: answers [sqlResult]. */
    inner class SqlTool : NarratorTool<SqlArgs>("sql", "Reads the Journal.", SqlArgs.serializer(), ToolEffect.READ) {
        override suspend fun run(ctx: ToolContext, args: SqlArgs): ToolResult = ToolResult.ok(sqlResult)
    }

    /** `slow_probe`: waits for the test ([probe]). */
    inner class SlowProbeTool : NarratorTool<NoArgs>("slow_probe", "Takes its time.", NoArgs.serializer(), ToolEffect.READ, timeoutMs = 600_000) {
        override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult {
            probe.run()
            return ToolResult.ok("ok: probed")
        }
    }

    /** A clock that reads the test dispatcher's virtual time, from [start]. */
    class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val start: Instant, private val zone: ZoneId = ZoneOffset.UTC) :
        Clock() {
        override fun instant(): Instant = start.plusMillis(scheduler.currentTime)

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(scheduler, start, zone)
    }

    companion object {
        /** 21:32:00 UTC, the virtual time when a harness starts. */
        val START: Instant = Instant.parse("2026-09-25T21:32:00Z")

        private val MAPPER = ObjectMapper()

        /** A stable player for [name]. */
        fun ref(name: String) = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name)

        private fun blocks(message: JsonNode): List<JsonNode> {
            val content = message.path("content")
            return if (content.isTextual) listOf(MAPPER.createObjectNode().put("type", "text").put("text", content.asText())) else content.toList()
        }
    }
}
