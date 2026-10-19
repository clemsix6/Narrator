package io.github.clemsix6.narrator2.memory.dream

import com.fasterxml.jackson.databind.JsonNode
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
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DuckMemoryState
import io.github.clemsix6.narrator2.memory.DuckMemoryStore
import io.github.clemsix6.narrator2.memory.DuckPlayClock
import io.github.clemsix6.narrator2.memory.DuckPlayerSheets
import io.github.clemsix6.narrator2.memory.Hygiene
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryAgents
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.PlayClock
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.memory.stats.StatsRefresher
import io.github.clemsix6.narrator2.observer.PlayerStates
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
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Dreamer on the real Journal (DuckDB in memory, its writer thread, a real bus), the real store, index,
 * recall, sheets and hygiene, and the strict fake Anthropic server: what a dream writes can be read back,
 * with the traces of its conversations. [evening] writes the reference evening of 2026-10-01 (UTC): Bartok
 * and Mimosa play from 20:00 to 21:45, chapter 13 runs until 21:40; the clock is then at 22:10, the server
 * empty for 25 minutes.
 */
internal class DreamerFixture : AutoCloseable {
    val clock = TestClock(at("19:00"), ZoneId.of("UTC"))
    val journal = JournalFixture(clock = clock)
    val fake = FakeAnthropic()
    private val config = testConfig(fake.baseUrl)
    private val client = ClaudeClient(config, TEST_API_KEY)
    private val index = SearchIndex()
    val playClock = FakeMemory.FakePlayClock()
    val store = DuckMemoryStore(journal.journal, index, playClock, clock)
    val state = DuckMemoryState(journal.journal, clock)
    val players = PlayerStates()
    val sheets: PlayerSheets = DuckPlayerSheets(journal.journal, playClock, players, ZoneId.of("UTC"))
    private val stub = FakeMemory(clock)

    /** Run contexts set by a test that calls a tool directly, outside a conversation of [agents]. */
    val contexts = ConcurrentHashMap<String, RunContext>()
    val memory: Memory = object : Memory by stub {
        override val store: MemoryStore get() = this@DreamerFixture.store
        override val recall: Recall = HybridRecall(journal.journal, index, playClock, this@DreamerFixture.clock)
        override val sheets: PlayerSheets get() = this@DreamerFixture.sheets
        override val clock: PlayClock get() = playClock
        override fun runContext(conversationId: String): RunContext? = contexts[conversationId] ?: agents.runContext(conversationId)
    }
    private val dir = Files.createTempDirectory("narrator2-dreamer")
    val services = TestServices(
        clock = clock,
        dataDir = dir,
        settings = testSettings(config, client),
        journal = journal.journal,
        trace = journal.journal,
        transcripts = Transcripts(dir.resolve("logs"), enabled = true),
        players = players,
        director = FakeDirector(clock),
        memory = memory,
    )
    val hygiene = Hygiene(
        store, journal.journal, DuckPlayClock(state, journal.journal, players), index, Decisions(journal.journal, clock), ZoneId.of("UTC"),
    )
    val agents = MemoryAgents(services)
    val dreamer = Dreamer(services, agents, hygiene, state)
    val inputs = DreamInputs(services)

    val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
    val chapter = "director-c13"

    /** Publishes [event] at [time] (`HH:mm:ss` on 2026-10-01); returns its id (`e…`). */
    fun publish(time: String, event: GameEvent): Long {
        clock.now = at(time)
        return journal.publish(event).id
    }

    fun chat(time: String, player: PlayerRef, text: String, channel: ChatChannel = ChatChannel.PUBLIC): Long =
        publish(time, ChatMessage(player, channel, text, emptyList()))

    fun narrator(time: String, text: String, to: List<PlayerRef>? = null): Long =
        publish(time, NarratorSpoke(NarratorChannel.NARRATOR, text, to, 40, null))

    fun join(time: String, player: PlayerRef, session: Long) =
        publish(time, PlayerJoined(player, OW, sessionId = session, firstTime = false, isOp = false))

    fun leave(time: String, player: PlayerRef, session: Long, seconds: Long) =
        publish(time, PlayerLeft(player, OW, session, seconds, LeaveReason.LEAVE))

    /** The reflex model's triage of the message [eventId]. */
    fun signal(eventId: Long, time: String, player: PlayerRef, tone: Tone?, intent: ChatIntent?) = journal.journal.chatSignals(
        ChatSignalsRecord(eventId, at(time), player.uuid, "reflex:claude-haiku-5-5", 0.9, tone, 0.85, intent, 0.05, null, null, null, null, null, 3),
    )

    /** An author of the past: the Director in chapter 13. */
    val director = Author(Source.DIRECTOR, turnId = 40, conversationId = chapter)

    fun <T> write(block: suspend MemoryStore.() -> T): T = runBlocking { store.block() }

    /** The ids of the reference evening ([evening]). */
    class Evening(
        val insult: Long, val laugh: Long, val sanction: Long, val prayer: Long, val granted: Long,
        val nickname: Ref, val gag: Ref, val lava: Ref, val lavaAgain: Ref, val fall: Ref,
        val insulted: Ref, val provoked: Ref, val prayed: Ref, val chapterEntry: Ref,
    )

    /**
     * The reference evening: Bartok insults the Narrator and laughs at its answer; Mimosa dies, prays and is
     * granted her things back. The memory holds a nickname of Bartok and a running gag (older), two memories
     * of Bartok's death in the lava that tell the same moment (a merge candidate), Mimosa's fall, three
     * interactions, the chapter's chronicle entry. The stats are computed. Then the clock is at 22:10.
     */
    fun evening(): Evening {
        join("19:55:00", bartok, 1)
        publish("19:55:01", PositionSampled(bartok, OW, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        join("20:00:02", mimosa, 2)
        publish("20:00:03", PositionSampled(mimosa, Pos("minecraft:overworld", 5, 64, 5), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        journal.sync()
        clock.now = at("19:56")
        val older = Author(Source.DIRECTOR, turnId = 12, conversationId = "director-c12")
        val nickname = write { addCanon(CanonDraft(CanonKind.NICKNAME, "Le Narrateur a surnommé Bartok « le Plongeur ».", "le Plongeur", listOf(bartok.uuid)), older).row.ref }
        val gag = write {
            addCanon(CanonDraft(CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok comme un plongeon olympique.", players = listOf(bartok.uuid)), older).row.ref
        }
        val insult = chat("20:01:05", bartok, "narrateur t'es nul")
        signal(insult, "20:01:05", bartok, Tone.HOSTILE, ChatIntent.PROVOCATION)
        val sanction = narrator("20:01:09", "Bartok, guichet des insultes : plainte enregistrée. Sanction : une minute de lenteur.")
        val laugh = chat("20:01:30", bartok, "mdr ok")
        publish("20:02:00", PlayerDied(mimosa, Pos("minecraft:overworld", -120, 64, 330), "minecraft:fall", null, null, "Mimosa fell", 3, false, false))
        val prayer = chat("20:03:12", mimosa, "rends-moi mes affaires stp", ChatChannel.PRAY)
        signal(prayer, "20:03:12", mimosa, Tone.RESPECTFUL, ChatIntent.REQUEST)
        val granted = narrator("20:03:20", "Accordé, Mimosa. Mais la prochaine fois, je garde les bottes.", to = listOf(mimosa))
        publish("20:30:00", PlayerDied(bartok, Pos("minecraft:the_nether", 40, 31, -80), "minecraft:lava", null, null, "Bartok burned", 10, false, false))
        journal.sync()
        clock.now = at("21:00")
        val insulted = write {
            addInteraction(
                InteractionDraft(bartok.uuid, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul » dans le chat", "narrateur t'es nul", evidenceEvents = listOf(insult)),
                director,
            ).row.ref
        }
        val provoked = write {
            addInteraction(InteractionDraft(bartok.uuid, InteractionKind.PROVOCATION, -1, "a ri de la sanction du Narrateur", evidenceEvents = listOf(laugh)), director).row.ref
        }
        val prayed = write {
            addInteraction(InteractionDraft(mimosa.uuid, InteractionKind.PRAYER, 1, "a prié le Narrateur de lui rendre ses affaires", evidenceEvents = listOf(prayer)), director).row.ref
        }
        val lava = write {
            addMemory(
                MemoryDraft(
                    MemoryKind.EPISODE, "Mort dans la lave du Nether", "Bartok est mort dans un lac de lave du Nether en creusant vers des débris antiques.",
                    listOf(bartok.uuid, mimosa.uuid), tags = listOf("death", "lava", "nether"), importance = 6, valence = -1,
                ),
                director,
            ).row.ref
        }
        val lavaAgain = write {
            addMemory(
                MemoryDraft(
                    MemoryKind.EPISODE, "Mort dans la lave du Nether", "Bartok est mort dans un lac de lave du Nether en creusant vers des débris antiques, devant Mimosa.",
                    listOf(bartok.uuid), tags = listOf("death", "lava"), importance = 5, valence = -1,
                ),
                Author(Source.CHRONICLER, conversationId = "chronicler-x"),
            ).row.ref
        }
        val fall = write {
            addMemory(
                MemoryDraft(MemoryKind.EPISODE, "La chute de Mimosa", "Mimosa est tombée d'une falaise et a tout perdu, puis a prié le Narrateur.", listOf(mimosa.uuid), tags = listOf("death", "fall", "prayer"), importance = 4),
                director,
            ).row.ref
        }
        leave("21:45:00", bartok, 1, 6_600)
        leave("21:45:05", mimosa, 2, 6_303)
        journal.sync()
        val record = ConversationRecord(chapter, Role.DIRECTOR, "Chapter 13", 13, null, at("20:00"), at("21:40"), ConversationRecord.EndReason.DONE, "Bartok a insulté le Narrateur.", null)
        journal.journal.conversation(record)
        clock.now = at("21:50")
        val chapterEntry = write {
            addChronicle(
                ChronicleDraft(
                    ChronicleLevel.CHAPTER, at("20:00"), at("21:40"), "Chapter 13",
                    "Bartok a insulté le Narrateur et a été ralenti une minute. Mimosa est tombée, a prié et a récupéré ses affaires. Bartok est mort dans la lave du Nether.",
                    conversationId = chapter,
                ),
                Author(Source.DIRECTOR, runId = 3, conversationId = chapter),
            ).ref
        }
        playClock.players[bartok.uuid] = 6_600
        playClock.players[mimosa.uuid] = 6_303
        playClock.world = 6_605
        runBlocking { StatsRefresher(journal.journal, clock).refresh(listOf(bartok.uuid, mimosa.uuid), "dream") }
        clock.now = at("22:10")
        journal.sync()
        return Evening(insult, laugh, sanction, prayer, granted, nickname, gag, lava, lavaAgain, fall, insulted, provoked, prayed, chapterEntry)
    }

    /** Decodes [input] as the model wrote it and runs [tool] in the Dreamer's conversation of the dream [runId], as the tool runner does. */
    fun <A : Any> call(tool: NarratorTool<A>, input: String, runId: Long = 12): ToolResult = runBlocking {
        val conversation = "dreamer-test-$runId"
        contexts[conversation] = RunContext(runId, RunKind.DREAM, null, null, null)
        val caller = Caller(Role.DREAMER, conversation, turnId = null, taskId = null)
        when (val decoded = ToolArgs.decode(tool.args, input)) {
            is ToolArgs.Decoded.Invalid -> ToolResult.invalid(decoded.problem)
            is ToolArgs.Decoded.Ok -> tool.run(ToolContext(services, caller, clock, tool.timeoutMs), decoded.args)
        }
    }

    fun <T> rows(sql: String, row: ResultSet.() -> T): List<T> = journal.rows(sql, row)

    fun count(table: String, where: String = "true"): Long = rows("SELECT count(*) FROM $table WHERE $where") { getLong(1) }.single()

    override fun close() {
        fake.close()
        services.scope.cancel()
        journal.close()
    }

    companion object {
        val OW = Pos("minecraft:overworld", 0, 64, 0)

        /** [time] (`HH:mm` or `HH:mm:ss`) on 2026-10-01, UTC. */
        fun at(time: String): Instant = Instant.parse("2026-10-01T${if (time.length == 5) "$time:00" else time}Z")

        /** The text of a message, whether its content is a string or blocks. */
        fun text(message: JsonNode): String {
            val content = message.path("content")
            return if (content.isTextual) content.asText() else content.joinToString("\n") { it.path("text").asText() }
        }

        /** The text of the last `tool_result` of a request: what the model just read from its last call. */
        fun lastToolResult(request: JsonNode): String {
            val content = request.path("messages").last().path("content")
            val result = content.lastOrNull { it.path("type").asText() == "tool_result" } ?: return ""
            val inner = result.path("content")
            return if (inner.isTextual) inner.asText() else inner.joinToString("\n") { it.path("text").asText() }
        }
    }
}
