package io.github.clemsix6.narrator2.live

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCountTokensParams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.clemsix6.narrator2.config.DirectorConfig
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.director.Director
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorHarness
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.ChapterMemory
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryService
import io.github.clemsix6.narrator2.memory.Shown
import io.github.clemsix6.narrator2.memory.createMemory
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.ChatVerdict
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeReflexes
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.memory.memoryTools
import io.github.clemsix6.narrator2.tool.pacing.pacingTools
import io.github.clemsix6.narrator2.tool.speech.SayArgs
import io.github.clemsix6.narrator2.tool.sql.sqlTools
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.random.Random

/*
 * The bench of the memory's live tests (M3-19): the owner's LLMGW, what the calls cost, and a whole Narrator
 * without Minecraft (`LiveWorld`): the real Journal on a file, the real memory (`createMemory`), the real
 * Director with the real prompts, two or three fake players. Nothing here runs outside `gradlew liveTest`.
 */

/** The owner's LLMGW server, through the SSH tunnel (CLAUDE.md). */
internal object LiveGateway {
    const val BASE_URL = "http://localhost:8088"

    /** The LLMGW key, or skips the test without a key or a tunnel. Never printed. */
    fun key(): String {
        val key = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
            ?: System.getProperty("narrator2.live.keyFile")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }
                ?.let { Files.readString(it).trim().ifEmpty { null } }
        assumeTrue(key != null) { "no LLMGW key: set NARRATOR_LLMGW_KEY or create .secrets/llmgw.key" }
        val healthy = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI("$BASE_URL/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode() == 200
        }.getOrDefault(false)
        assumeTrue(healthy) { "LLMGW does not answer on $BASE_URL/healthz: open the SSH tunnel first" }
        return key!!
    }

    /**
     * The settings of a live test: the gateway, the default roles (the Chronicler's effort from
     * `NARRATOR2_LIVE_CHRONICLER_EFFORT` when set, for §21's measure at another effort), the Journal's zone, a
     * Director without heartbeats, and memory jobs that never start a batch or a dream by themselves during a
     * test (the test asks for them).
     */
    fun config(zone: ZoneId): NarratorConfig {
        val effort = System.getenv("NARRATOR2_LIVE_CHRONICLER_EFFORT")?.trim()?.ifEmpty { null }
        val chronicler = NarratorConfig.DEFAULT_ROLES.getValue(Role.CHRONICLER).let { if (effort == null) it else it.copy(effort = effort) }
        return NarratorConfig(
            gateway = GatewayConfig(baseUrl = BASE_URL, maxRetries = 2),
            roles = NarratorConfig.DEFAULT_ROLES + (Role.CHRONICLER to chronicler),
            journal = JournalConfig(timeZone = zone.id),
            director = DirectorConfig(heartbeatMinutes = listOf(60, 60)),
            memory = MemoryConfig(chronicleMinutes = 60, dreamAfterEmptyMinutes = 240),
        )
    }

    /**
     * The tokens of [text] as a lone `user` message for Opus 5.5, less those of an empty-ish message: the exact
     * count of a briefing (M3 §21.1), from the free `count_tokens` endpoint; null when the gateway does not relay it.
     */
    fun countTokens(key: String, text: String): Long? = runCatching {
        val client = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(BASE_URL).build()
        try {
            fun count(content: String) = client.messages().countTokens(
                MessageCountTokensParams.builder().model("claude-opus-5-5").addUserMessage(content).build(),
            ).inputTokens()
            count(text) - count(".")
        } finally {
            client.close()
        }
    }.onFailure { println("[live] count_tokens failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}") }.getOrNull()
}

/** Prices of Opus 5.5 per million tokens; cache writes at the 1 h rate (LLMGW places `ttl=1h` markers, T22). */
internal object LivePrices {
    const val INPUT = 4.0
    const val OUTPUT = 20.0
    const val CACHE_READ = 0.20
    const val CACHE_WRITE = 8.0

    fun dollars(input: Long, read: Long, write: Long, output: Long): Double =
        (input * INPUT + read * CACHE_READ + write * CACHE_WRITE + output * OUTPUT) / 1_000_000.0
}

/** One `llm_calls` row of Anthropic. */
internal data class LiveCall(
    val id: Long, val purpose: String, val conversation: String?, val role: String, val effort: String?, val started: Instant,
    val latencyMs: Int, val input: Long, val read: Long, val write: Long, val output: Long, val stop: String?, val error: String?,
) {
    val dollars: Double get() = LivePrices.dollars(input, read, write, output)
    val context: Long get() = input + read + write

    companion object {
        /** The Anthropic calls of [journal] that [where] picks (SQL on `llm_calls`), in order. */
        fun read(journal: Journal, where: String = "true"): List<LiveCall> = rows(
            journal,
            "SELECT id, purpose, conversation_id, role, effort, started_at, latency_ms, coalesce(input_tokens, 0), coalesce(cache_read_tokens, 0), " +
                "coalesce(cache_write_tokens, 0), coalesce(output_tokens, 0), stop_reason, error FROM llm_calls WHERE provider = 'anthropic' AND ($where) ORDER BY id",
        ) {
            LiveCall(
                getLong(1), getString(2), getString(3), getString(4), getString(5), getObject(6, java.time.OffsetDateTime::class.java).toInstant(),
                getInt(7), getLong(8), getLong(9), getLong(10), getLong(11), getString(12), getString(13),
            )
        }

        /** `12 calls · $0.412 · input 1.2k, cache read 120k, cache write 30k, output 9k`. */
        fun summary(calls: List<LiveCall>): String =
            "${calls.size} calls · $%.3f · input %d, cache read %d, cache write %d, output %d".format(
                Locale.ROOT, calls.sumOf { it.dollars }, calls.sumOf { it.input }, calls.sumOf { it.read }, calls.sumOf { it.write }, calls.sumOf { it.output },
            )

        /** A Markdown table of [calls]. */
        fun table(calls: List<LiveCall>): String = buildString {
            append("| # | purpose | conversation | effort | latency | input | cache read | cache write | output | stop | $ |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
            calls.forEach {
                append("| ${it.id} | ${it.purpose} | ${it.conversation} | ${it.effort} | ${"%.1f".format(Locale.ROOT, it.latencyMs / 1000.0)} s | ${it.input} | ${it.read} | ")
                append("${it.write} | ${it.output} | ${it.stop ?: it.error} | ${"%.4f".format(Locale.ROOT, it.dollars)} |\n")
            }
        }
    }
}

/** Every row of [sql] on [journal], each read by [row], once what came before is written. */
internal fun <T> rows(journal: Journal, sql: String, row: ResultSet.() -> T): List<T> = runBlocking {
    journal.barrier()
    journal.read(30_000) { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.row()) } }
        }
    }
}

/** Where the live tests of the memory write their reports, for a human to read (M3 §15.2). */
internal object LiveReports {
    val dir: Path = Path.of("build/reports/liveTest/memory").toAbsolutePath().also { Files.createDirectories(it) }

    /** The running total of every live test of the memory, across runs. */
    private val ledger: Path = dir.resolve("costs.tsv")

    /** The budget of the live tests (`NARRATOR2_LIVE_BUDGET`, 8 dollars by default), across runs. */
    val budget: Double = System.getenv("NARRATOR2_LIVE_BUDGET")?.toDoubleOrNull() ?: 8.0

    /** Dollars spent so far by the live tests of the memory, from the ledger. */
    fun spent(): Double = if (!Files.isRegularFile(ledger)) 0.0 else Files.readAllLines(ledger).drop(1).sumOf { it.split('\t').getOrNull(4)?.toDoubleOrNull() ?: 0.0 }

    /** Skips the test when [needed] more dollars would go over the budget. */
    fun requireBudget(needed: Double) {
        val spent = spent()
        assumeTrue(spent + needed <= budget) { "the live budget is spent: $%.2f of $%.2f, $%.2f needed".format(Locale.ROOT, spent, budget, needed) }
    }

    /** Adds what [calls] cost to the ledger, as [test] / [part]. */
    fun record(test: String, part: String, calls: List<LiveCall>) {
        if (!Files.isRegularFile(ledger)) Files.writeString(ledger, "when\ttest\tpart\tcalls\tdollars\tinput\tcache_read\tcache_write\toutput\n")
        val line = listOf(
            Instant.now().toString(), test, part, calls.size.toString(), "%.4f".format(Locale.ROOT, calls.sumOf { it.dollars }),
            calls.sumOf { it.input }.toString(), calls.sumOf { it.read }.toString(), calls.sumOf { it.write }.toString(), calls.sumOf { it.output }.toString(),
        ).joinToString("\t")
        Files.writeString(ledger, line + "\n", StandardOpenOption.APPEND)
    }

    fun write(name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }
}

/** A clock that follows real time, moved by [offset]: a test places past evenings, or skips the time a server stays empty. */
internal class LiveClock(private val zone: ZoneId, @Volatile var offset: Duration = Duration.ZERO) : Clock() {
    override fun instant(): Instant = Instant.now().plus(offset)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = Shared(this, zone)

    private class Shared(private val base: LiveClock, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = base.instant()

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = Shared(base, zone)
    }
}

/** `services.memory` before the memory exists, as the runtime's `lateinit` is; [memory] once created. */
internal class LateMemory : Memory {
    lateinit var memory: Memory
    val started: Boolean get() = this::memory.isInitialized

    override val available get() = memory.available
    override val unavailableReason get() = memory.unavailableReason
    override val store get() = memory.store
    override val recall get() = memory.recall
    override val sheets get() = memory.sheets
    override val clock get() = memory.clock
    override val jobs get() = memory.jobs

    override suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean) = memory.briefing(chapter, now, reduced)

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long) = memory.attachments(signals, chapter, now)

    override suspend fun recordShown(shown: List<Shown>) = memory.recordShown(shown)

    override fun situationLines(now: Long) = memory.situationLines(now)

    override fun chapterClosed(record: ConversationRecord) = memory.chapterClosed(record)

    override fun runContext(conversationId: String) = memory.runContext(conversationId)

    override suspend fun stop() = memory.stop()
}

/** `services.director` before the Director exists: disabled until [director] is set. */
internal class LateDirector(private val clock: Clock) : DirectorControl {
    @Volatile
    var director: DirectorControl? = null
    private val asleep = FakeDirector(clock).also { it.state = DirectorState.DISABLED }

    private val current: DirectorControl get() = director ?: asleep

    override fun post(signal: DirectorSignal) = current.post(signal)

    override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller) = current.scheduleReminder(inSeconds, note, caller)

    override fun status() = current.status()

    override fun recentActivity(player: UUID, minutes: Int) = current.recentActivity(player, minutes)
}

/** A line the Narrator said through the fake `say`. */
internal data class Said(val at: Instant, val turnId: Long?, val to: List<String>?, val text: String)

/**
 * A whole Narrator without Minecraft, against the real LLMGW (M3-19, the bench of `LiveMemoryCycleTest` and of
 * E6): the real Journal on a file under `build/`, the real bus and player states, the real memory
 * (`createMemory`, its jobs included), the real Director with the real prompts, and fake players.
 *
 * - **Tools of the Director**: `say` (the real arguments; the line is journaled as the real tool does, nobody
 *   reads it), the real `sql`, `remind_me` and the five memory tools. The world's tools need a server: they are
 *   not there, and the model only sees tools that work.
 * - **The reflexes** answer what the test says of each message ([chat]), as Jev would; no heartbeat.
 * - **The clock** is real time, moved by [clock]'s offset: history placed in the past, or the twenty minutes
 *   of an empty server skipped before a dream.
 */
internal class LiveWorld(val name: String, key: String, val zone: ZoneId = PARIS) : AutoCloseable {
    val dir: Path = Path.of("build/live-memory/$name-${STAMP.format(Instant.now())}").toAbsolutePath().also { Files.createDirectories(it) }
    val clock = LiveClock(zone)
    val config = LiveGateway.config(zone)
    val client = ClaudeClient(config, key)
    /** As `openJournal` opens it, without the Observer's hook timer (it needs a running server). */
    val journal: Journal = dir.resolve("narrator2").let { folder ->
        Files.createDirectories(folder)
        DuckJournal.open(DuckDatabase.open(folder.resolve(DuckDatabase.FILE_NAME), DuckDatabase.Options.of(config.journal)), config.journal, clock)
    }

    @Volatile
    var gameTime = 1_000L
    val bus = EventBus(clock, { gameTime }, journal.nextIdSeed(IdKind.EVENT)).also { journal.attach(it) }
    val players = PlayerStates().also { it.subscribe(bus) { null } }
    private val verdicts = ConcurrentHashMap<String, ChatVerdict>()
    private val reflexes = FakeReflexes(chat = { input -> verdicts[input.message.text] ?: verdict(0.1, Tone.NEUTRAL_NO_TARGET, ChatIntent.BANTER_BETWEEN_PLAYERS) })
    val game = DirectorHarness.FakeGame()
    private val lateMemory = LateMemory()
    private val lateDirector = LateDirector(clock)
    val transcripts = Transcripts(dir.resolve("transcripts"), enabled = true)
    val services = TestServices(
        clock = clock, dataDir = dir, settings = testSettings(config, client), bus = bus, journal = journal, trace = journal,
        transcripts = transcripts, players = players, reflexes = reflexes, director = lateDirector, memory = lateMemory,
    )
    lateinit var memory: MemoryService
        private set
    lateinit var director: Director
        private set

    /** Lines said through `say`, in order. */
    val said = CopyOnWriteArrayList<Said>()
    private val sessions = ConcurrentHashMap<String, Pair<Long, Instant>>()

    /** Starts the memory, then the Director, as `NarratorRuntime.wire` does: the memory while starting, the server then runs. */
    fun start() {
        services.phase = Phase.STARTING
        val created = createMemory(services)
        check(created is MemoryService) { "the memory did not start: ${created.unavailableReason}" }
        memory = created
        lateMemory.memory = created
        director = Director(services, game, ::toolSet, Random(19))
        lateDirector.director = director
        services.phase = Phase.RUNNING
        director.start()
    }

    /** The Director's tools in this world (see the class). */
    private fun toolSet(env: ToolEnv): ToolSet {
        val tools = listOf<NarratorTool<*>>(LiveSayTool()) + sqlTools(services) + pacingTools(services) + memoryTools(services)
        return ToolCatalog.toolSetFor(Role.DIRECTOR, env, tools, Decisions(services.trace, clock))
    }

    // ── The game ────────────────────────────────────────────────────────────────────────────────────

    fun ref(name: String) = PlayerRef(UUID.nameUUIDFromBytes("narrator2-live-$name".toByteArray()), name)

    fun publish(event: GameEvent): Envelope = bus.publish(event) ?: error("the bus is closed")

    /** Runs [block] with the clock at [time], then puts it back on real time: for the history of past evenings. */
    fun <T> at(time: Instant, block: () -> T): T {
        val before = clock.offset
        clock.offset = Duration.between(Instant.now(), time)
        try {
            return block()
        } finally {
            clock.offset = before
        }
    }

    fun join(name: String, pos: Pos = SPAWN, firstTime: Boolean = false): Long {
        val session = journal.nextId(IdKind.SESSION)
        sessions[name] = session to clock.instant()
        return publish(PlayerJoined(ref(name), pos, session, firstTime, isOp = false)).id
    }

    fun leave(name: String, pos: Pos = SPAWN): Long {
        val (session, since) = sessions.remove(name) ?: error("$name is not online")
        val seconds = Duration.between(since, clock.instant()).seconds
        return publish(PlayerLeft(ref(name), pos, session, seconds, LeaveReason.LEAVE)).id
    }

    /** A public message (or a prayer) of [name], which the reflexes judge as given. */
    fun chat(
        name: String, text: String, toNarrator: Double = 0.92, tone: Tone = Tone.NEUTRAL_NO_TARGET,
        intent: ChatIntent = ChatIntent.QUESTION, channel: ChatChannel = ChatChannel.PUBLIC,
    ): Long {
        verdicts[text] = verdict(toNarrator, tone, intent)
        return publish(ChatMessage(ref(name), channel, text, emptyList())).id
    }

    fun pray(name: String, text: String) = chat(name, text, 0.97, Tone.RESPECTFUL, ChatIntent.REQUEST, ChatChannel.PRAY)

    /** A line said by an operator in the Narrator's name (`/narrator say`). */
    fun operatorSays(operator: String, text: String): Long = publish(NarratorSpoke(NarratorChannel.OPERATOR, text, null, null, operator)).id

    // ── Waiting ─────────────────────────────────────────────────────────────────────────────────────

    /** Waits until the Director has no turn running nor scheduled, twice 1.5 s apart (a result may arrive just after a turn). */
    fun awaitQuiet(what: String, timeoutMs: Long = 180_000) {
        val end = System.currentTimeMillis() + timeoutMs
        var calm = 0
        while (calm < 2) {
            val status = director.status()
            calm = if (status.turnId == null && status.state == DirectorState.IDLE) calm + 1 else 0
            check(System.currentTimeMillis() < end) { "timed out waiting for a quiet Director ($what): $status" }
            Thread.sleep(if (calm == 1) 1_500 else 300)
        }
    }

    /** Waits for a `memory_runs` row of [kind] after [afterRun] that is no longer `running`; its run id and status. */
    fun awaitRun(kind: String, afterRun: Long = 0, timeoutMs: Long = 20 * 60_000L): Pair<Long, String> {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val row = rows("SELECT run_id, status FROM memory_runs WHERE kind = '$kind' AND run_id > $afterRun ORDER BY run_id LIMIT 1") { getLong(1) to getString(2) }
                .firstOrNull()
            if (row != null && row.second != "running") return row
            check(System.currentTimeMillis() < end) { "timed out waiting for a $kind run after $afterRun: $row" }
            Thread.sleep(2_000)
        }
    }

    fun <T> rows(sql: String, row: ResultSet.() -> T): List<T> = rows(journal, sql, row)

    /** The calls to Anthropic of the conversations [conversations]. */
    fun calls(conversations: Collection<String>): List<LiveCall> =
        if (conversations.isEmpty()) emptyList() else LiveCall.read(journal, "conversation_id IN (${conversations.joinToString { "'$it'" }})")

    /** The text of the first `user` message of [conversation] (a chapter's briefing and its first wake-up), from its transcript. */
    fun firstUserText(conversation: String): String = transcriptMessages(conversation).first { it.path("role").asText() == "user" }.let(::textOf)

    /** The messages of [conversation]'s transcript, as sent or appended, in order. */
    fun transcriptMessages(conversation: String): List<JsonNode> {
        val file = Files.walk(transcripts.root).use { paths -> paths.filter { it.isRegularFile() && it.name == "$conversation.jsonl" }.findFirst().orElse(null) }
            ?: error("no transcript for $conversation")
        return Files.readAllLines(file).map { MAPPER.readTree(it) }
            .filter { it.path("kind").asText() == "sent" || it.path("kind").asText() == "message" }
            .map { it.path("data") }
    }

    override fun close() {
        runBlocking { if (lateMemory.started) withTimeoutOrNull(15_000) { memory.stop() } }
        services.phase = Phase.STOPPING
        services.scope.cancel()
        bus.close()
        journal.close(Duration.ofSeconds(20))
        client.close()
    }

    /** `say`, as the real tool: same name and arguments; the line goes to the bus (hence the Journal), and to [said]. */
    private inner class LiveSayTool : NarratorTool<SayArgs>(
        name = "say",
        description = "Speaks in the chat as the Narrator: to everyone online, or privately to some players (only they see it). " +
            "The chat is narrow and players read while playing: keep it short. At most 4 lines per turn and 8 per minute.",
        args = SayArgs.serializer(),
        effect = ToolEffect.SPEECH,
    ) {
        override suspend fun run(ctx: ToolContext, args: SayArgs): ToolResult {
            val text = args.text.trim()
            if (text.isEmpty()) return ToolResult.invalid("text is empty")
            val targets = args.to?.map { name -> ctx.players.byName(name)?.ref ?: return ToolResult.invalid("$name is not online") }
            if (targets == null && ctx.players.online.isEmpty()) return ToolResult.failed("nobody heard it: nobody is online")
            ctx.services.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, text, targets, ctx.caller.turnId, null))
            said += Said(ctx.clock.instant(), ctx.caller.turnId, targets?.map { it.name }, text)
            return if (targets == null) {
                ToolResult.ok("ok: said to everyone (${ctx.players.online.size} online)", ctx.players.online.map { it.ref })
            } else {
                ToolResult.ok("ok: whispered to ${targets.joinToString { it.name }}", targets)
            }
        }
    }

    companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val SPAWN = Pos("minecraft:overworld", 120, 68, -240)
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd-HHmmss").withZone(ZoneId.systemDefault())
        private val MAPPER = ObjectMapper()

        /** A verdict of the reflexes, as Jev gives it. */
        fun verdict(toNarrator: Double, tone: Tone, intent: ChatIntent, manipulation: Double = 0.0) =
            ChatVerdict(toNarrator, tone, 0.85, manipulation, intent, personality = null, source = "jev:jev-1.13.0", latencyMs = 120)

        /** The text blocks of a message, joined; a string content as it is. */
        fun textOf(message: JsonNode): String {
            val content = message.path("content")
            return if (content.isTextual) content.asText() else content.filter { it.path("type").asText() == "text" }.joinToString("\n") { it.path("text").asText() }
        }
    }
}
