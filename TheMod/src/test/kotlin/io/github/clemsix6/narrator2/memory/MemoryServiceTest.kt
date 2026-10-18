package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.Urgency
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory as `createMemory` assembles it (M3-18): the real pieces on DuckDB in memory, the real
 * scheduler and Chronicler, and the fake Anthropic server for the model. A batch of the Chronicler writes a
 * promise the Director forgot; recall, the sheet, the briefing and the wake-up then show it. At the stop,
 * the running job ends `cancelled` and the world play clock is saved.
 */
class MemoryServiceTest {
    private val clock = TestClock(at("20:00"), ZoneId.of("UTC"))
    private val journal = JournalFixture(clock = clock)
    private val fake = FakeAnthropic()
    private val config = testConfig(fake.baseUrl)
    private val client = ClaudeClient(config, TEST_API_KEY)
    private val players = PlayerStates().also { it.subscribe(journal.bus) { null } }
    private val fakeDirector = FakeDirector(clock)

    /** The Director's open chapter, 13: what the scheduler asks before a batch. */
    private val director = object : DirectorControl by fakeDirector {
        override fun status() = fakeDirector.status().copy(chapter = 13, conversationId = CHAPTER)
    }
    private val late = LateMemory()
    private val dir = Files.createTempDirectory("narrator2-memory-service")
    private val services = TestServices(
        clock = clock,
        dataDir = dir,
        settings = testSettings(config, client),
        bus = journal.bus,
        journal = journal.journal,
        trace = journal.journal,
        transcripts = Transcripts(dir.resolve("logs"), enabled = true),
        players = players,
        director = director,
        memory = late,
    )
    private val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    private val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")

    @AfterEach
    fun close() {
        runBlocking { if (late.started) late.memory.stop() }
        services.scope.cancel()
        fake.close()
        client.close()
        journal.close()
    }

    /** The server's start, as `NarratorRuntime.wire` does it: the memory is created while starting, then the server runs. */
    private fun start(): MemoryService {
        services.phase = Phase.STARTING
        val memory = assertIs<MemoryService>(createMemory(services))
        late.memory = memory
        services.phase = Phase.RUNNING
        return memory
    }

    /**
     * The evening of chapter 13: Bartok and Mimosa join, and the Narrator promises Mimosa her boots back in
     * an hour; the Director remembers nothing. The model's answers must be scripted before: the batch timer
     * may read the window as soon as it is written.
     */
    private fun evening() {
        journal.journal.conversation(ConversationRecord(CHAPTER, Role.DIRECTOR, "Chapter 13", 13, null, at("20:00"), null, null, null, null))
        publish("20:00:01", PlayerJoined(bartok, OW, sessionId = 1, firstTime = false, isOp = false))
        publish("20:00:02", PlayerJoined(mimosa, OW, sessionId = 2, firstTime = false, isOp = false))
        publish("20:04:00", NarratorSpoke(NarratorChannel.NARRATOR, "Mimosa, je te rends tes bottes dans une heure, promis.", null, 40, null))
        journal.sync()
        clock.now = at("20:10:05")
    }

    private fun publish(time: String, event: GameEvent) {
        clock.now = at(time)
        journal.publish(event)
    }

    /**
     * Asks for the batch of chapter 13, as an operator does. The scheduler's first pass may have queued it
     * already (its batch timer starts at once): either way, one batch reads the window.
     */
    private fun requestBatch(memory: MemoryService) {
        memory.jobs.requestChronicle("test").onFailure { assertTrue(it.message!!.startsWith("nothing to chronicle"), it.message) }
    }

    /** The batch of chapter 13 (`run_id`, `status`, `error`, `conversations`), once [done] accepts it. */
    private suspend fun awaitBatch(done: (String) -> Boolean): List<String?> {
        val end = System.currentTimeMillis() + 20_000
        while (true) {
            val row = journal.rows("SELECT run_id, status, error, conversations::VARCHAR FROM memory_runs WHERE kind = 'batch' AND chapter = '$CHAPTER'") {
                listOf(getLong(1).toString(), getString(2), getString(3), getString(4))
            }.singleOrNull()
            if (row != null && done(row[1]!!)) return row
            check(System.currentTimeMillis() < end) { "the batch of $CHAPTER: $row" }
            delay(50)
        }
    }

    @Test
    fun `a batch of the Chronicler writes a promise, then recall, the sheet, the briefing and the wake-up show it`() = runBlocking<Unit> {
        val memory = start()
        assertTrue(memory.available)
        assertNull(memory.unavailableReason)
        fake.enqueue(
            reply {
                toolUse(
                    "remember",
                    """{"kind": "promise", "what": "Le Narrateur a promis à Mimosa de lui rendre ses bottes dans une heure.",
                       "quote": "je te rends tes bottes dans une heure", "players": ["Mimosa"], "due_in_minutes": 60, "importance": 6}""",
                )
            },
            reply { text("Promesse faite à Mimosa enregistrée.") },
        )
        evening()

        requestBatch(memory)
        val run = awaitBatch { it != "running" }
        val runId = run[0]!!.toLong()

        assertEquals("done", run[1], "the batch: $run")
        assertEquals(emptyList(), fake.rejections)
        assertEquals("ok", journal.rows("SELECT status FROM tool_calls WHERE tool = 'remember'") { getString(1) }.single())
        val (id, row) = journal.rows("SELECT id, kind, quote, source, run_id, due_at FROM canon") {
            getLong(1) to listOf(getString(2), getString(3), getString(4), getLong(5).toString(), getObject(6)?.let { "due" })
        }.single()
        assertEquals(listOf("promise", "je te rends tes bottes dans une heure", "chronicler", runId.toString(), "due"), row, "written by the batch, as its run")
        val ref = Ref(RefKind.CANON, id)
        val conversation = run[3]!!.removeSurrounding("[", "]")
        assertTrue(conversation.startsWith("chronicler-"), conversation)
        assertNull(memory.runContext(conversation), "the run context lives as long as its conversation")
        val now = clock.millis()

        val hits = memory.recall.search(RecallQuery(text = "les bottes de Mimosa", purpose = RecallPurpose.TOOL))
        assertEquals(ref, hits.first().ref, "recall: $hits")
        assertTrue("$ref · promise → Mimosa" in hits.first().line, hits.first().line)

        val sheet = assertNotNull(memory.sheets.sheet(mimosa.uuid, now))
        assertEquals(listOf(ref), sheet.openCanon.map { it.ref })
        val full = memory.sheets.render(sheet, SheetDetail.FULL, now)
        assertTrue("$ref promise" in full, full)

        val chapter = ChapterMemory("director-c14", 14)
        val sections = memory.briefing(chapter, now)
        assertEquals(BriefingSection.TAGS, sections.map { it.tag })
        val canon = sections.single { it.tag == "canon" }.lines
        assertTrue(canon.any { "$ref promise → Mimosa" in it && "« je te rends tes bottes dans une heure »" in it }, canon.joinToString("\n"))
        assertTrue(sections.single { it.tag == "sheets" }.lines.any { it.startsWith("Mimosa") }, "the sheets of the players online")
        assertTrue(ref in chapter.shownRefs)
        assertEquals(setOf(bartok.uuid, mimosa.uuid), chapter.sheetsShownAt.keys)

        val join = journal.publish(PlayerJoined(PlayerRef(UUID.randomUUID(), "Kelvin"), OW, sessionId = 3, firstTime = true, isOp = false))
        val block = memory.attachments(listOf(DirectorSignal.Event(join, Urgency.SOON, null)), chapter, now)
        assertEquals("<memory>\nSHEET Kelvin (first time on this server)\n</memory>", block)
    }

    @Test
    fun `stopping cancels the running job and saves the world play clock`() = runBlocking<Unit> {
        val memory = start()
        val hold = Hold()
        fake.enqueue(reply { hold(hold) })
        evening()
        try {
            requestBatch(memory)
            assertTrue(hold.awaitReached(), "the batch's request reached the model")
            val runId = awaitBatch { it == "running" }[0]
            clock.now = at("20:12:00")

            memory.stop()

            val run = journal.rows("SELECT status, error FROM memory_runs WHERE run_id = $runId") { listOf(getString(1), getString(2)) }.single()
            assertEquals(listOf("cancelled", "the server stopped"), run)
            assertEquals(listOf("cancelled"), journal.rows("SELECT end_reason FROM conversations WHERE role = 'chronicler'") { getString(1) })
            val state = journal.rows("SELECT key, value FROM memory_state WHERE key LIKE 'world_play%'") { getString(1) to getString(2) }.toMap()
            assertEquals(at("20:12:00").toEpochMilli().toString(), state[DuckPlayClock.SAVED_AT_KEY], "saved at the stop")
            assertEquals(memory.clock.worldPlaySeconds(at("20:12:00").toEpochMilli()).toString(), state[DuckPlayClock.WORLD_KEY])
            val refused = memory.jobs.requestDream("test").exceptionOrNull()?.message
            assertEquals("the server is stopping: no memory job can start", refused)
        } finally {
            hold.release()
        }
    }

    @Test
    fun `the memory stays off, and says why, when disabled, without a Journal, or without its tables`() {
        val off = createMemory(TestServices(settings = testSettings(NarratorConfig(memory = MemoryConfig(enabled = false))), journal = journal.journal))
        assertIs<DisabledMemory>(off)
        assertEquals("memory.enabled is false", off.unavailableReason)

        assertEquals("the Journal is unavailable: unit test", createMemory(TestServices()).unavailableReason)

        runBlocking {
            journal.journal.write(5_000) { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM schema_version WHERE version = 2")
                    it.execute("DROP TABLE search_postings")
                }
            }
        }
        val broken = createMemory(TestServices(journal = journal.journal))
        assertFalse(broken.available)
        assertEquals("the Journal lacks the memory's schema (V2__memory.sql): schema version 1; tables missing: search_postings", broken.unavailableReason)
    }

    /** `services.memory` before the memory exists, as the runtime's `lateinit` is; [memory] once created. */
    private class LateMemory : Memory {
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

        override fun situationLines(now: Long) = memory.situationLines(now)

        override fun chapterClosed(record: ConversationRecord) = memory.chapterClosed(record)

        override fun runContext(conversationId: String) = memory.runContext(conversationId)

        override suspend fun stop() = memory.stop()
    }

    private companion object {
        const val CHAPTER = "director-c13"
        val OW = Pos("minecraft:overworld", 0, 64, 0)

        /** [time] (`HH:mm` or `HH:mm:ss`) on 2026-10-01, UTC. */
        fun at(time: String): Instant = Instant.parse("2026-10-01T${if (time.length == 5) "$time:00" else time}Z")
    }
}
