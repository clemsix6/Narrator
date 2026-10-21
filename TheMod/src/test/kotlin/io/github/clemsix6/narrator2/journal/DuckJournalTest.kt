package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckJournalTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)

    private fun joined(player: PlayerRef, session: Long, isOp: Boolean = false) = PlayerJoined(player, here, session, firstTime = false, isOp = isOp)

    private fun left(player: PlayerRef, session: Long, seconds: Long, pos: Pos = here) = PlayerLeft(player, pos, session, seconds, LeaveReason.LEAVE)

    private fun broke(player: PlayerRef) = BlockBroken(player, here, "minecraft:stone", null, creative = false, correctTool = true, placedBy = null)

    private fun earned(player: PlayerRef) = AdvancementEarned(player, here, "minecraft:story/root", "Minecraft", "task")

    private fun position(player: PlayerRef) =
        PositionSampled(player, here, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED)

    // ── Sessions and players ────────────────────────────────────────────────────────────────────────

    @Test
    fun `sessions and players follow joins and leaves, and facts carry their session`() = JournalFixture().use { journal ->
        val start = journal.clock.now
        val steve2 = PlayerRef(steve.uuid, "Steve2")
        journal.publish(joined(steve, 1))
        journal.publish(broke(steve))
        journal.publish(broke(alex)) // Alex has no session
        journal.clock.advance(Duration.ofHours(1))
        journal.publish(left(steve, 1, 3_600, here.copy(x = 7)))
        journal.publish(broke(steve)) // after the leave: no session
        journal.clock.advance(Duration.ofHours(2))
        journal.publish(joined(steve2, 2, isOp = true)) // renamed, now an operator
        journal.publish(position(steve2))
        journal.clock.advance(Duration.ofMinutes(10))
        journal.publish(left(steve2, 2, 600))

        assertEquals(
            listOf(listOf("join", 1L), listOf("block_break", 1L), listOf("block_break", null), listOf("leave", 1L),
                listOf("block_break", null), listOf("join", 2L), listOf("leave", 2L)),
            journal.values("SELECT type, session_id FROM events ORDER BY id"),
        )
        assertEquals(listOf(listOf(2L)), journal.values("SELECT session_id FROM positions"))
        val left1 = start.plus(Duration.ofHours(1))
        val joined2 = start.plus(Duration.ofHours(3))
        val left2 = joined2.plus(Duration.ofMinutes(10))
        assertEquals(
            listOf(
                listOf(1L, steve.uuid, "Steve", start, left1, 3_600, "minecraft:overworld", 120, 64, -330, "minecraft:overworld", 7, 64, -330, "leave"),
                listOf(2L, steve.uuid, "Steve2", joined2, left2, 600, "minecraft:overworld", 120, 64, -330, "minecraft:overworld", 120, 64, -330, "leave"),
            ),
            journal.values("SELECT * FROM sessions ORDER BY session_id"),
        )
        assertEquals(
            listOf(listOf(steve.uuid, "Steve2", start, left2, 2, 4_200L, true)),
            journal.values("SELECT * FROM players"),
        )
        assertEquals(
            listOf(listOf("Steve", start), listOf("Steve2", joined2)),
            journal.values("SELECT name, first_seen FROM player_names ORDER BY first_seen"),
        )
    }

    /**
     * Fabric publishes the join at the end of the connection, after what the connection set off in the
     * same tick (T07: an advancement unlocked by the reloaded inventory): the session that opens adopts
     * those facts, whether they are still in the writer's batch or already written. Other facts without
     * a session stay so.
     */
    @Test
    fun `facts published in the tick of a join, just before it, belong to the session it opens`() = JournalFixture().use { journal ->
        val carl = PlayerRef(UUID.randomUUID(), "Carl")
        journal.publish(broke(steve)) // long before: no session
        journal.clock.advance(Duration.ofHours(1))
        journal.publish(earned(steve)) // during the connection
        journal.publish(joined(steve, 1))
        journal.publish(earned(alex))
        journal.sync() // Alex's advancement is written before his join comes
        journal.clock.advance(Duration.ofMillis(300))
        journal.publish(joined(alex, 2))
        journal.publish(broke(alex))
        journal.publish(earned(carl))
        journal.gameTime += 1 // the next tick: not set off by Carl's connection
        journal.publish(joined(carl, 3))
        journal.clock.advance(Duration.ofMinutes(5))
        journal.publish(earned(PlayerRef(UUID.randomUUID(), "Ghost"))) // never joins

        assertEquals(
            listOf(
                listOf("Steve", "block_break", null), listOf("Steve", "advancement", 1L), listOf("Steve", "join", 1L),
                listOf("Alex", "advancement", 2L), listOf("Alex", "join", 2L), listOf("Alex", "block_break", 2L),
                listOf("Carl", "advancement", null), listOf("Carl", "join", 3L), listOf("Ghost", "advancement", null),
            ),
            journal.values("SELECT player, type, session_id FROM events ORDER BY id"),
        )
    }

    @Test
    fun `a session whose departure was lost ends at its last fact`() = JournalFixture().use { journal ->
        val start = journal.clock.now
        journal.publish(joined(steve, 1))
        journal.clock.advance(Duration.ofMinutes(5))
        journal.publish(broke(steve))
        journal.clock.advance(Duration.ofHours(1))
        journal.publish(joined(steve, 2)) // the leave of session 1 never came
        assertEquals(
            listOf(listOf(1L, start.plus(Duration.ofMinutes(5)), 300, "recovered"), listOf(2L, null, null, null)),
            journal.values("SELECT session_id, left_at, duration_s, end_reason FROM sessions ORDER BY 1"),
        )
        val players = journal.values("SELECT sessions, playtime_s FROM players").single()
        assertEquals(listOf<Any?>(2, 300L), players, "types: ${players.map { it?.javaClass?.simpleName }}")
    }

    @Test
    fun `a departure whose join was lost still records the session`() = JournalFixture().use { journal ->
        val start = journal.clock.now
        journal.publish(left(alex, 9, 120))
        assertEquals(
            listOf(listOf(9L, "Alex", start.minusSeconds(120), start, 120, "leave")),
            journal.values("SELECT session_id, player, joined_at, left_at, duration_s, end_reason FROM sessions"),
        )
        assertEquals(listOf(listOf("Alex", 1, 120L)), journal.values("SELECT name, sessions, playtime_s FROM players"))
    }

    @Test
    fun `sessions still open at the close end with server_stop`() {
        val fixture = JournalFixture(file)
        val start = fixture.clock.now
        fixture.use { journal ->
            journal.publish(joined(steve, 1))
            journal.publish(joined(alex, 2))
            journal.publish(left(alex, 2, 60))
            journal.clock.advance(Duration.ofMinutes(30))
        }
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            db.writer.createStatement().use { statement ->
                statement.executeQuery("SELECT session_id, left_at, duration_s, end_reason FROM sessions ORDER BY 1").use { rows ->
                    rows.next()
                    assertEquals(listOf(1L, start.plus(Duration.ofMinutes(30)), 1_800, "server_stop"),
                        (1..4).map { JournalFixture.plain(rows.getObject(it)) })
                    rows.next()
                    assertEquals("leave", rows.getString(4))
                }
                statement.executeQuery("SELECT playtime_s FROM players WHERE name = 'Steve'").use { rows ->
                    rows.next()
                    assertEquals(1_800L, rows.getLong(1))
                }
            }
        }
    }

    // ── Traces ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `turns, conversations and tasks are inserted, then updated in place`() = JournalFixture().use { journal ->
        val t0 = Instant.parse("2026-09-25T20:00:00Z")
        val t1 = t0.plusSeconds(8)
        val turn = TurnRecord(42, "director-1a2b3c4d", t0, null, TurnRecord.Trigger.CHAT, listOf(7L, 8L), emptyList(), null, null, null)
        val chapter = ConversationRecord("director-1a2b3c4d", Role.DIRECTOR, "Chapter 3", 3, null, t0, null, null, null, "logs/c.md")
        val task = TaskRecord(5, Role.ANALYST, "who mined the diamonds?", TaskRecord.Status.QUEUED, t0, null, null, 42, null, null, null)
        journal.journal.turn(turn)
        journal.journal.conversation(chapter)
        journal.journal.task(task)
        journal.sync()
        assertEquals(listOf(listOf(null, null)), journal.values("SELECT outcome, ended_at FROM turns"))

        journal.journal.task(task.copy(status = TaskRecord.Status.RUNNING, startedAt = t0, conversationId = "analyst-9"))
        journal.journal.turn(turn.copy(endedAt = t1, injectedEvents = listOf(9L), llmCalls = 3, toolCalls = 2, outcome = TurnRecord.Outcome.ACTED))
        journal.journal.conversation(
            chapter.copy(endedAt = t1, endReason = ConversationRecord.EndReason.QUIET_LIMIT, summary = "Bartok mined diamonds."),
        )
        journal.journal.task(task.copy(status = TaskRecord.Status.DONE, startedAt = t0, finishedAt = t1, conversationId = "analyst-9", result = "Bartok, 41."))

        assertEquals(
            listOf(listOf(42L, "director-1a2b3c4d", t0, t1, "chat", listOf(7L, 8L), listOf(9L), 3, 2, "acted")),
            journal.values("SELECT * FROM turns"),
        )
        assertEquals(
            listOf(listOf("director-1a2b3c4d", "director", "Chapter 3", 3, null, t0, t1, "quiet_limit", "Bartok mined diamonds.", "logs/c.md")),
            journal.values("SELECT * FROM conversations"),
        )
        assertEquals(
            listOf(listOf(5L, "analyst", "who mined the diamonds?", "done", t0, t0, t1, 42L, "analyst-9", "Bartok, 41.", null)),
            journal.values("SELECT * FROM tasks"),
        )
    }

    @Test
    fun `model calls, tool calls, decisions and chat verdicts are appended with every column`() = JournalFixture().use { journal ->
        val t0 = Instant.parse("2026-09-25T20:00:00Z")
        val llm = LlmCallRecord(
            1, LlmCallRecord.Provider.ANTHROPIC, LlmCallRecord.Purpose.TURN, "director-1", 42, "director", "claude-opus-5-5", "medium",
            t0, t0.plusMillis(2_400), 2_400, 800, 2, "trace-1", "msg_1", 120, 900, 60, 45, "tool_use", null, null, null, "x".repeat(5_000),
        )
        val tool = ToolCallRecord(
            1, t0, "director-1", 42, 1, "toolu_1", Role.DIRECTOR, "apply_effect", """{"player":"Bartok","effect":"slowness"}""",
            ToolStatus.OK, "ok: slowness II on Bartok", 12, listOf("Bartok"), ToolEffect.WORLD,
        )
        val badArgs = tool.copy(id = 2, toolUseId = "toolu_2", args = "{broken", result = "y".repeat(3_000), targets = emptyList())
        val decision = DecisionRecord(
            1, t0, DecisionRecord.Kind.GUARD, "denied", "Bartok", "died 2m ago", 77, "director-1", 42,
            buildJsonObject { put("rule", "no_pile_on") },
        )
        val signals = ChatSignalsRecord(
            77, t0, steve.uuid, "reflex:claude-haiku-5-5", 0.92, Tone.MOCKING_PLAYFUL, 0.8, ChatIntent.PROVOCATION, 0.1,
            0.2, 0.3, 0.4, 0.5, 0.6, 350,
        )
        journal.journal.llmCall(llm)
        journal.journal.toolCall(tool)
        journal.journal.toolCall(badArgs)
        journal.journal.decision(decision)
        journal.journal.chatSignals(signals)

        assertEquals(
            listOf(
                listOf(
                    1L, "anthropic", "turn", "director-1", 42L, "director", "claude-opus-5-5", "medium", t0, t0.plusMillis(2_400),
                    2_400, 800, 2.toShort(), "trace-1", "msg_1", 120, 900, 60, 45, "tool_use", null, null, null, "x".repeat(4_000),
                ),
            ),
            journal.values("SELECT * FROM llm_calls"),
        )
        assertEquals(
            listOf(
                listOf(1L, t0, "director-1", 42L, 1L, "toolu_1", "director", "apply_effect", """{"player":"Bartok","effect":"slowness"}""",
                    "ok", "ok: slowness II on Bartok", 12, listOf("Bartok"), "world"),
                listOf(2L, t0, "director-1", 42L, 1L, "toolu_2", "director", "apply_effect", "\"{broken\"",
                    "ok", "y".repeat(2_000), 12, emptyList<String>(), "world"),
            ),
            journal.values("SELECT id, ts, conversation_id, turn_id, llm_call_id, tool_use_id, role, tool, args::VARCHAR, status, result, duration_ms, targets, effect FROM tool_calls ORDER BY id"),
        )
        assertEquals(
            listOf(listOf(1L, t0, "guard", "denied", "Bartok", "died 2m ago", 77L, "director-1", 42L, """{"rule":"no_pile_on"}""")),
            journal.values("SELECT id, ts, kind, decision, subject, reason, event_id, conversation_id, turn_id, detail::VARCHAR FROM engine_decisions"),
        )
        assertEquals(
            listOf(listOf(77L, t0, steve.uuid, "reflex:claude-haiku-5-5", 0.92, "mocking_playful", 0.8, "provocation", 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 350)),
            journal.values("SELECT * FROM chat_signals"),
        )
    }

    // ── Barrier, reads, checkpoint ──────────────────────────────────────────────────────────────────

    @Test
    fun `a barrier returns once everything handed over before it is readable`() = JournalFixture(
        config = JournalConfig(flushIntervalMs = 3_600_000),
    ).use { journal ->
        repeat(1_000) { journal.publish(broke(steve)) }
        journal.journal.decision(DecisionRecord(1, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null))
        runBlocking { journal.journal.barrier() }
        // Read without a second barrier: the hour-long flush interval never came.
        val counts = runBlocking {
            journal.journal.read(5_000) { connection ->
                connection.createStatement().use { statement ->
                    listOf("events", "engine_decisions").map { table ->
                        statement.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getLong(1) }
                    }
                }
            }
        }
        assertEquals(listOf(1_000L, 1L), counts)
    }

    @Test
    fun `reads run off the caller's thread, fail with the query's error, and time out`() = JournalFixture().use { journal ->
        val caller = Thread.currentThread().name
        val thread = runBlocking { journal.journal.read(5_000) { Thread.currentThread().name } }
        assertTrue(thread.startsWith("narrator2-sql-") && thread != caller, thread)

        assertFailsWith<SQLException> {
            runBlocking { journal.journal.read(5_000) { it.createStatement().use { s -> s.executeQuery("SELECT * FROM nowhere") } } }
        }

        val release = CountDownLatch(1)
        assertFailsWith<JournalTimeoutException> {
            runBlocking { journal.journal.read(100) { release.await(10, TimeUnit.SECONDS) } }
        }
        release.countDown()
        assertEquals(2L, runBlocking { journal.journal.read(5_000) { 2L } }, "the pool still works after a timeout")
    }

    @Test
    fun `a read's watch hears its end before the caller resumes, and may refuse it`() = JournalFixture().use { journal ->
        // A slow end: a caller that resumed before it would find it not heard yet.
        val watch = object : ReadWatch {
            @Volatile
            var ended = false

            override fun start(): String? = null

            override fun end() {
                Thread.sleep(300)
                ended = true
            }
        }
        assertEquals(1, runBlocking(watch) { journal.journal.read(5_000) { 1 } })
        assertTrue(watch.ended, "the connection is back and the end heard before the caller resumes")

        val ran = AtomicBoolean()
        val refusing = object : ReadWatch {
            override fun start(): String = "another read of this work runs"

            override fun end() {
                error("a refused read never ends")
            }
        }
        val refused = assertFailsWith<IllegalStateException> { runBlocking(refusing) { journal.journal.read(5_000) { ran.set(true) } } }
        assertEquals("another read of this work runs", refused.message, "the watch's reason, not a timeout")
        assertFalse(ran.get(), "a refused query never runs")
        assertEquals(2L, runBlocking { journal.journal.read(5_000) { 2L } }, "its connection went back to the pool")
    }

    @Test
    fun `checkpointAsync checkpoints after the next batch`() = JournalFixture(file).use { journal ->
        repeat(100) { journal.publish(broke(steve)) }
        journal.sync()
        assertTrue(journal.db.walBytes() > 0, "the rows are in the WAL")
        journal.journal.checkpointAsync()
        journal.sync()
        journal.sync() // the checkpoint runs right after the batch that released the first barrier
        assertEquals(0L, journal.db.walBytes(), "the WAL is folded into the file")
        assertEquals(100L, journal.count("events"))
    }

    /**
     * T07: after the GameTests' last read, every `CHECKPOINT` failed ("other write transactions active").
     * The read stopped at its first row; DuckDB kept its query and read transaction open on the
     * connection, and the writes that followed, which updated rows, could not let go of the checkpoint.
     */
    @Test
    fun `a read that stops before the end of its result does not hold the checkpoint back`() = JournalFixture(file).use { journal ->
        journal.publish(joined(steve, 1))
        repeat(100) { journal.publish(broke(steve)) }
        journal.sync()
        val first = runBlocking {
            journal.journal.read(5_000) { connection ->
                connection.prepareStatement("SELECT type FROM events WHERE player = ?").use { statement ->
                    statement.setString(1, "Steve")
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
        }
        assertTrue(first != null)
        LogCapture().use { logs ->
            journal.publish(left(steve, 1, 60)) // updates sessions and players in place
            journal.sync()
            journal.journal.checkpointAsync()
            journal.sync()
            journal.sync()
            assertFalse("CHECKPOINT failed" in logs.text, logs.text)
        }
        assertEquals(0L, journal.db.walBytes(), "the WAL is folded into the file")
    }

    // ── Close, restart ──────────────────────────────────────────────────────────────────────────────

    /** Whatever left a query open on a read connection (a read still running, say), the close ends it before its checkpoint. */
    @Test
    fun `the close checkpoints even when a query was left open on a read connection`() {
        val fixture = JournalFixture(file)
        LogCapture().use { logs ->
            fixture.publish(joined(steve, 1))
            repeat(100) { fixture.publish(broke(steve)) }
            fixture.sync()
            // Straight on the connection, where the pool cannot end it.
            fixture.db.readers.first().createStatement().use { statement ->
                statement.executeQuery("SELECT type FROM events WHERE player = 'Steve'").use { rows -> assertTrue(rows.next()) }
            }
            fixture.publish(left(steve, 1, 60))
            fixture.sync()
            fixture.close()
            assertFalse("CHECKPOINT failed" in logs.text, logs.text)
            assertContains(logs.text, "[Journal] closing: checkpointed")
        }
    }

    @Test
    fun `close writes what is queued within its timeout, then checkpoints`() {
        val fixture = JournalFixture(file, config = JournalConfig(flushIntervalMs = 3_600_000))
        LogCapture().use { logs ->
            repeat(20_000) { fixture.publish(broke(steve)) }
            repeat(500) { fixture.journal.decision(DecisionRecord(it + 1L, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null)) }
            val start = System.nanoTime()
            fixture.close()
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 10_000, "closed in $ms ms")
            assertContains(logs.text, "[Journal] closing: checkpointed")
            assertFalse(fixture.journal.available)
            assertEquals(0L, fixture.journal.health().droppedEvents)
        }
        assertFalse(Files.exists(file.resolveSibling("${file.fileName}.wal")), "no WAL left after the checkpoint")
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            db.writer.createStatement().use { statement ->
                statement.executeQuery("SELECT (SELECT count(*) FROM events), (SELECT count(*) FROM engine_decisions)").use {
                    it.next()
                    assertEquals(listOf(20_000L, 500L), listOf(it.getLong(1), it.getLong(2)))
                }
            }
        }
    }

    @Test
    fun `what is still queued when the close timeout is reached is counted as lost`() {
        val fixture = JournalFixture(config = JournalConfig(flushIntervalMs = 3_600_000), tuning = JournalTuning(pollMs = 3_600_000))
        LogCapture().use { logs ->
            fixture.sync()
            repeat(50) { fixture.publish(broke(steve)) }
            fixture.bus.close()
            fixture.journal.close(Duration.ZERO) // nothing can be written in no time
            fixture.journal.close(Duration.ofSeconds(10)) // waits for the writer to stop
            assertEquals(50L, fixture.journal.health().droppedEvents)
            assertContains(logs.text, "50 records still queued are lost")
        }
        runBlocking { fixture.journal.barrier() } // returns at once once closed
        assertFailsWith<JournalUnavailableException> { runBlocking { fixture.journal.read(1_000) { 1 } } }
    }

    /**
     * Constat 4 of the M6 review, part 2: a writer still running when the close's time runs out keeps the database open.
     * The Journal says so ([Journal.holdsDatabase]) until the writer ends and closes the database itself; a new close
     * waits for it again; then the file opens again in this JVM.
     */
    @Test
    fun `a writer still running at the close's timeout holds the database until it ends, then the file opens again`() {
        val fixture = JournalFixture(file)
        assertTrue(fixture.journal.holdsDatabase, "open")
        val running = CountDownLatch(1)
        val held = CountDownLatch(1)
        val job = Thread {
            runCatching { runBlocking { fixture.journal.write(60_000) { running.countDown(); held.await(60, TimeUnit.SECONDS) } } }
        }.apply { start() }
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS), "the writer runs the job")
            LogCapture().use { logs ->
                fixture.bus.close()
                fixture.journal.close(Duration.ofMillis(300))
                assertTrue(fixture.journal.holdsDatabase, "its writer still runs: the database stays open")
                assertContains(logs.text, "the writer did not stop within 300 ms: the database stays open until the writer ends")
                fixture.journal.close(Duration.ofMillis(300))
                assertTrue(fixture.journal.holdsDatabase, "a new close waited for it again, in vain")
            }
        } finally {
            held.countDown()
        }
        job.join(10_000)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (fixture.journal.holdsDatabase) {
            check(System.nanoTime() < end) { "the writer never closed the database" }
            Thread.sleep(5)
        }
        fixture.journal.close(Duration.ofSeconds(10)) // returns at once
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            db.writer.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM sessions").use { assertTrue(it.next()) } }
        }
    }

    @Test
    fun `after a restart, ids start past every id used before`() {
        JournalFixture(file).use { journal ->
            assertEquals(1L, journal.journal.nextIdSeed(IdKind.EVENT))
            IdKind.entries.forEach { assertEquals(1L, journal.journal.nextIdSeed(it), "empty database: $it starts at 1") }
            repeat(10) { journal.publish(broke(steve)) }
            journal.publish(ChatMessage(steve, ChatChannel.WHISPER, "psst", listOf("Alex"))) // event id 11, in private_messages
            journal.publish(joined(steve, journal.journal.nextId(IdKind.SESSION)))
            val turn = journal.journal.nextId(IdKind.TURN)
            journal.journal.turn(
                TurnRecord(turn, "director-1", Instant.EPOCH, Instant.EPOCH, TurnRecord.Trigger.CHAT, emptyList(), emptyList(), 0, 0, TurnRecord.Outcome.IDLE),
            )
            // A decision citing an event and a turn whose rows never made it.
            journal.journal.decision(
                DecisionRecord(journal.journal.nextId(IdKind.DECISION), Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, 500, null, 90, null),
            )
            journal.journal.llmCall(
                LlmCallRecord(
                    journal.journal.nextId(IdKind.LLM_CALL), LlmCallRecord.Provider.ANTHROPIC, LlmCallRecord.Purpose.CHAT_TRIAGE, null, null,
                    "reflex", "claude-haiku-5-5", "low", Instant.EPOCH, Instant.EPOCH, 300, null, 1, null, null, null, null, null, null, null, null, null, null, null,
                ),
            )
        }
        JournalFixture(file).use { journal ->
            assertEquals(501L, journal.journal.nextIdSeed(IdKind.EVENT), "past the event cited by a decision")
            assertEquals(2L, journal.journal.nextId(IdKind.SESSION))
            assertEquals(91L, journal.journal.nextId(IdKind.TURN), "past the turn cited by a decision")
            assertEquals(2L, journal.journal.nextId(IdKind.DECISION))
            assertEquals(2L, journal.journal.nextId(IdKind.LLM_CALL))
            assertEquals(1L, journal.journal.nextId(IdKind.TOOL_CALL))
            assertEquals(1L, journal.journal.nextId(IdKind.TASK))
            assertEquals(501L, journal.publish(broke(steve)).id)
        }
    }

    @Test
    fun `every id kind has its seed`() {
        DuckDatabase.openInMemory(JournalFixture.OPTIONS).use { db ->
            assertEquals(IdKind.entries.toSet(), DuckJournal.seeds(db.writer).keys)
        }
    }

    // ── Losses and failures ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a full queue loses records and counts them`() = JournalFixture(
        config = JournalConfig(flushIntervalMs = 3_600_000, queueCapacity = 4),
        tuning = JournalTuning(pollMs = 3_600_000),
    ).use { journal ->
        journal.sync() // the writer now sleeps until the next barrier
        LogCapture().use { logs ->
            repeat(100) { journal.publish(broke(steve)) }
            repeat(10) { journal.journal.decision(DecisionRecord(it + 1L, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null)) }
            journal.sync()
            val written = journal.count("events") + journal.count("engine_decisions")
            val lost = journal.journal.health().droppedEvents
            assertEquals(110L, written + lost, "every record is either written or counted as lost")
            assertTrue(lost >= 90, "lost $lost")
            assertTrue(journal.journal.available, "losses do not make the Journal unavailable")
            assertContains(logs.text, "[Bus] journal (journal): queue full (4)")
            assertContains(logs.text, "[Journal] trace queue full (4)")
        }
    }

    @Test
    fun `a broken database turns the Journal unavailable, and what follows is counted as lost`() {
        val fixture = JournalFixture()
        LogCapture().use { logs ->
            fixture.publish(broke(steve))
            fixture.sync()
            fixture.db.writer.close() // the database stops answering
            fixture.publish(broke(steve))
            fixture.sync()
            assertFalse(fixture.journal.available)
            assertTrue(fixture.journal.health().lastError != null)
            assertContains(logs.text, "the Journal stops recording")
            fixture.journal.decision(DecisionRecord(1, Instant.EPOCH, DecisionRecord.Kind.WAKE, "woke", null, null, null, null, null, null))
            assertEquals(2L, fixture.journal.health().droppedEvents)
            assertFailsWith<JournalUnavailableException> { runBlocking { fixture.journal.read(1_000) { 1 } } }
            fixture.close()
        }
    }

    @Test
    fun `the health report is logged and recorded`() = JournalFixture(
        tuning = JournalTuning(healthIntervalMs = 0),
        hookNanos = { 5_000_000 },
    ).use { journal ->
        LogCapture().use { logs ->
            journal.publish(broke(steve))
            journal.sync()
            journal.sync()
            val reasons = journal.rows("SELECT decision, reason FROM engine_decisions WHERE kind = 'journal_health'") { getString(1) to getString(2) }
            assertTrue(reasons.isNotEmpty())
            assertEquals("healthy", reasons.first().first)
            assertContains(reasons.first().second, "rows/min")
            assertContains(logs.text, "[Journal] healthy: ")
        }
        val health = journal.journal.health()
        assertTrue(health.available)
        assertNull(health.lastError)
        assertEquals(0L, health.droppedEvents)
    }

    /** On a server, the Journal opens before the Observer: its hook time must still be reported (T07). */
    @Test
    fun `the health report tells the time of an Observer started after the Journal`() {
        val observer = AtomicReference<Long?>(null)
        JournalFixture(tuning = JournalTuning(healthIntervalMs = 0), hookNanos = { observer.get() }).use { journal ->
            LogCapture().use { logs ->
                observer.set(3_000_000)
                journal.publish(broke(steve))
                journal.sync()
                journal.sync()
                assertContains(logs.text, "Observer hooks ")
                val details = journal.rows("SELECT detail::VARCHAR FROM engine_decisions WHERE kind = 'journal_health'") { getString(1) }
                assertTrue(details.any { "hook_ms_per_minute" in it }, "hook time in the decision: $details")
            }
        }
    }
}
