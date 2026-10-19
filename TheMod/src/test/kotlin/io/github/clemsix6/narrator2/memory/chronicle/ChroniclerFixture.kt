package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.DuckJobsData
import io.github.clemsix6.narrator2.memory.DuckMemoryStore
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryAgents
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryRun
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.RunTrigger
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.llm.ClaudeClient
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Chronicler on the real Journal (DuckDB in memory, its writer thread, a real bus), the real store,
 * index and recall, and the strict fake Anthropic server: what a batch or a nap writes can be read back,
 * with the traces of its conversations. The Director's chapter is `director-c13`, chapter 13, from 20:00 on
 * 2026-10-01 (UTC). [scenario] writes the reference evening of the replay (M3 §7.2's example).
 */
internal class ChroniclerFixture : AutoCloseable {
    val clock = TestClock(at("20:00"), ZoneId.of("UTC"))
    val journal = JournalFixture(clock = clock)
    val fake = FakeAnthropic()
    private val config = testConfig(fake.baseUrl)
    private val client = ClaudeClient(config, TEST_API_KEY)
    private val index = SearchIndex()
    val playClock = FakeMemory.FakePlayClock()
    val store = DuckMemoryStore(journal.journal, index, playClock, clock)
    private val stub = FakeMemory(clock)
    lateinit var agents: MemoryAgents

    /** Run contexts set by a test that calls a tool directly, outside a conversation of [agents]. */
    val contexts = ConcurrentHashMap<String, RunContext>()
    val memory: Memory = object : Memory by stub {
        override val store: MemoryStore get() = this@ChroniclerFixture.store
        override val recall: Recall = HybridRecall(journal.journal, index, playClock, this@ChroniclerFixture.clock)
        override fun runContext(conversationId: String): RunContext? = contexts[conversationId] ?: agents.runContext(conversationId)
    }
    val director = FakeDirector(clock)
    private val dir = Files.createTempDirectory("narrator2-chronicler")
    val services = TestServices(
        clock = clock,
        dataDir = dir,
        settings = testSettings(config, client),
        journal = journal.journal,
        trace = journal.journal,
        transcripts = Transcripts(dir.resolve("logs"), enabled = true),
        director = director,
        memory = memory,
    )
    val chronicler: Chronicler

    init {
        agents = MemoryAgents(services)
        chronicler = Chronicler(services, agents)
    }

    val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
    val chapter = "director-c13"

    /** Publishes [event] at [time] (`HH:mm:ss` on 2026-10-01); returns its id (`e…`). */
    fun publish(time: String, event: GameEvent): Long {
        clock.now = at(time)
        return journal.publish(event).id
    }

    fun chat(time: String, player: PlayerRef, text: String, channel: ChatChannel = ChatChannel.PUBLIC, recipients: List<String> = emptyList()): Long =
        publish(time, ChatMessage(player, channel, text, recipients))

    fun narrator(time: String, text: String, to: List<PlayerRef>? = null, turn: Long? = 40, operator: String? = null): Long =
        publish(time, NarratorSpoke(if (operator == null) NarratorChannel.NARRATOR else NarratorChannel.OPERATOR, text, to, turn, operator))

    /** The triage of the message [eventId] (the reflex model unless [source] says otherwise). */
    fun signal(eventId: Long, time: String, player: PlayerRef, toNarrator: Double, tone: Tone?, confidence: Double, intent: ChatIntent?, source: String = "reflex:claude-haiku-5-5") =
        journal.journal.chatSignals(
            ChatSignalsRecord(
                eventId, at(time), player.uuid, source, toNarrator, tone, confidence, intent, 0.05,
                null, null, null, null, null, 3,
            ),
        )

    /** A tool call of the Director's chapter; returns its id (`t…`). */
    fun toolCall(
        time: String, tool: String, targets: List<String>, result: String, turn: Long = 40,
        status: ToolStatus = ToolStatus.OK, effect: ToolEffect = ToolEffect.WORLD, args: String = "{}",
    ): Long {
        val id = journal.journal.nextId(IdKind.TOOL_CALL)
        journal.journal.toolCall(
            ToolCallRecord(id, at(time), chapter, turn, null, "toolu_$id", Role.DIRECTOR, tool, args, status, result, 5, targets, effect),
        )
        return id
    }

    /** The guards allowed a hostile action on [player] in [turn] (T09a): a punishment. */
    fun guardAllowed(time: String, player: PlayerRef, turn: Long) = journal.journal.decision(
        DecisionRecord(
            journal.journal.nextId(IdKind.DECISION), at(time), DecisionRecord.Kind.GUARD, "allowed", player.name,
            "hostile action", null, chapter, turn, null,
        ),
    )

    /** The Director's chapter row: 13, from 20:00, closed at [ended] with [summary] when given. */
    fun chapterRow(ended: String? = null, summary: String? = null, reason: ConversationRecord.EndReason? = null): ConversationRecord {
        val record = ConversationRecord(chapter, Role.DIRECTOR, "Chapter 13", 13, null, at("20:00"), ended?.let(::at), reason, summary, null)
        journal.journal.conversation(record)
        return record
    }

    /** The `memory_runs` row of a job, as `MemoryJobs` writes it before calling the Chronicler. */
    fun runRow(runId: Long, kind: RunKind, from: String, to: String) = runBlocking {
        DuckJobsData(journal.journal).insertRun(
            MemoryRun(
                runId, kind, if (kind == RunKind.NAP) RunTrigger.CHAPTER_CLOSED else RunTrigger.TIMER, RunStatus.RUNNING, clock.instant(), null,
                at(from), at(to), chapter, emptyList(), 0, 0, null, null, null, null,
            ),
        )
    }

    /** A memory shown to the Director in the chapter ([channel]), as its `recalls` row says. */
    fun shown(ref: Ref, time: String, channel: RecallChannel = RecallChannel.REMINISCENCE, event: Long? = null) {
        clock.now = at(time)
        runBlocking { store.recorded(listOf(ref), channel, RecallContext(chapter, 40, event)) }
    }

    /**
     * Decodes [input] as the model wrote it and runs [tool] in the Chronicler's conversation [conversation]
     * of the job [context], as the tool runner does.
     */
    fun <A : Any> call(tool: NarratorTool<A>, input: String, context: RunContext, conversation: String = "chronicler-test"): ToolResult = runBlocking {
        contexts[conversation] = context
        val caller = Caller(Role.CHRONICLER, conversation, turnId = null, taskId = null)
        when (val decoded = ToolArgs.decode(tool.args, input)) {
            is ToolArgs.Decoded.Invalid -> ToolResult.invalid(decoded.problem)
            is ToolArgs.Decoded.Ok -> tool.run(ToolContext(services, caller, clock, tool.timeoutMs), decoded.args)
        }
    }

    fun <T> rows(sql: String, row: ResultSet.() -> T): List<T> = journal.rows(sql, row)

    fun count(table: String, where: String = "true"): Long = rows("SELECT count(*) FROM $table WHERE $where") { getLong(1) }.single()

    /** The ids of the reference evening ([scenario]). */
    class Evening(
        val joinBartok: Long, val joinMimosa: Long, val insult: Long, val sanction: Long, val slowness: Long, val death: Long,
        val prayer: Long, val granted: Long, val boots: Long, val slimes: Long, val plongeur: Long, val remembered: Long,
        val nickname: Ref, val threat: Ref, val lava: Ref, val prayed: Ref,
    )

    /**
     * The reference evening (M3 §7.2's example), in chapter 13: Bartok insults the Narrator and is slowed
     * (a punishment the guards allowed); Mimosa dies, prays, gets her boots back (a gift) with a threat the
     * Director remembers (C-…); the Narrator promises Bartok a rain of slimes (forgotten by the Director) and
     * calls him by his nickname « le Plongeur » (an older canon entry). A private message is sent and never
     * shows. Chat between 20:01 and 20:07.
     */
    fun scenario(): Evening {
        chapterRow()
        val joinBartok = publish("20:00:01", PlayerJoined(bartok, OW, sessionId = 1, firstTime = false, isOp = false))
        val joinMimosa = publish("20:00:02", PlayerJoined(mimosa, Pos("minecraft:overworld", -120, 64, 330), sessionId = 2, firstTime = false, isOp = false))
        journal.sync()
        clock.now = at("19:30")
        val older = Author(Source.DIRECTOR, turnId = 12, conversationId = "director-c12")
        val nickname = runBlocking {
            store.addCanon(CanonDraft(CanonKind.NICKNAME, "Le Narrateur a surnommé Bartok « le Plongeur ».", "le Plongeur", listOf(bartok.uuid)), older).row.ref
        }
        val lava = runBlocking {
            store.addMemory(
                MemoryDraft(
                    MemoryKind.EPISODE, "Mort dans la lave du Nether", "Mimosa est morte dans un lac de lave du Nether en creusant vers des débris antiques.",
                    listOf(mimosa.uuid), tags = listOf("death", "lava", "nether"), importance = 6,
                ),
                older,
            ).row.ref
        }
        val insult = chat("20:01:05", bartok, "narrateur t'es nul")
        signal(insult, "20:01:05", bartok, 0.92, Tone.HOSTILE, 0.81, ChatIntent.PROVOCATION)
        val sanction = narrator("20:01:09", "Ministère de l'Aviation Divine, guichet des insultes : plainte enregistrée. Sanction : une minute de lenteur.", turn = 41)
        guardAllowed("20:01:09", bartok, 41)
        val slowness = toolCall("20:01:09", "apply_effect", listOf("Bartok"), "ok: slowness II on Bartok for 1m", turn = 41)
        chat("20:01:30", bartok, "mimosa viens on se parle en privé", ChatChannel.WHISPER, listOf("Mimosa"))
        val death = publish("20:02:00", PlayerDied(mimosa, Pos("minecraft:overworld", -120, 64, 330), "minecraft:fall", null, null, "Mimosa fell", 3, false, false))
        journal.sync()
        shown(lava, "20:02:01", event = death)
        val prayer = chat("20:03:12", mimosa, "rends-moi mes affaires stp", ChatChannel.PRAY)
        signal(prayer, "20:03:12", mimosa, 0.97, Tone.RESPECTFUL, 0.9, ChatIntent.REQUEST)
        journal.sync()
        clock.now = at("20:03:40")
        val prayed = runBlocking {
            store.addInteraction(
                InteractionDraft(mimosa.uuid, InteractionKind.PRAYER, 1, "a prié le Narrateur de lui rendre ses affaires", evidenceEvents = listOf(prayer)),
                Author(Source.DIRECTOR, turnId = 42, conversationId = chapter),
            ).row.ref
        }
        val granted = narrator("20:03:20", "Accordé. Mais la prochaine fois, je garde les bottes. Promis.", to = listOf(mimosa), turn = 42)
        val boots = toolCall("20:03:21", "give_item", listOf("Mimosa"), "ok: gave 1x iron_boots to Mimosa", turn = 42)
        toolCall("20:03:22", "say", listOf("Mimosa"), "ok: said to Mimosa", turn = 42, effect = ToolEffect.SPEECH)
        toolCall("20:03:23", "teleport", listOf("Mimosa"), "Invalid arguments: x is required", turn = 42, status = ToolStatus.INVALID)
        val slimes = narrator("20:04:00", "Bartok, je te promets une pluie de slimes si tu recommences.", turn = 43)
        val plongeur = narrator("20:05:00", "Salut, le Plongeur ! La lave est chaude aujourd'hui.", turn = 44)
        journal.sync()
        clock.now = at("20:06:40")
        val threat = runBlocking {
            store.addCanon(
                CanonDraft(
                    CanonKind.THREAT, "Le Narrateur a menacé Mimosa de garder ses bottes la prochaine fois.", "la prochaine fois, je garde les bottes",
                    listOf(mimosa.uuid),
                ),
                Author(Source.DIRECTOR, turnId = 43, conversationId = chapter),
            ).row.ref
        }
        val remembered = toolCall("20:06:40", "remember", listOf("Mimosa"), "ok: remembered $threat (threat → Mimosa)", turn = 43, effect = ToolEffect.ENGINE)
        clock.now = at("20:10:05")
        journal.sync()
        return Evening(joinBartok, joinMimosa, insult, sanction, slowness, death, prayer, granted, boots, slimes, plongeur, remembered, nickname, threat, lava, prayed)
    }

    override fun close() {
        fake.close()
        services.scope.cancel()
        journal.close()
    }

    companion object {
        val OW = Pos("minecraft:overworld", 0, 64, 0)

        /** [time] (`HH:mm` or `HH:mm:ss`) on 2026-10-01, UTC. */
        fun at(time: String): Instant = Instant.parse("2026-10-01T${if (time.length == 5) "$time:00" else time}Z")
    }
}
