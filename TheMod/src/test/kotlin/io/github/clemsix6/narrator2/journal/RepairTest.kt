package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** What a crash leaves open is closed when the Journal opens again (M1_M2 §2.4). */
class RepairTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)
    private val t0 = Instant.parse("2026-09-25T18:00:00Z")

    private fun at(seconds: Long) = "TIMESTAMPTZ '${t0.plusSeconds(seconds)}'"

    /** The state a crash leaves: rows written, ends never recorded. */
    private fun crashedDatabase() = DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
        db.writer.createStatement().use { sql ->
            listOf(
                // Steve: session 1 open, last fact at +1500 s (a position); Alex: session 2 closed normally.
                "INSERT INTO players VALUES ('${steve.uuid}', 'Steve', ${at(0)}, ${at(0)}, 1, 7200, false)",
                "INSERT INTO players VALUES ('${alex.uuid}', 'Alex', ${at(0)}, ${at(600)}, 1, 600, false)",
                "INSERT INTO sessions (session_id, player_uuid, player, joined_at) VALUES (1, '${steve.uuid}', 'Steve', ${at(0)})",
                "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, end_reason) " +
                    "VALUES (2, '${alex.uuid}', 'Alex', ${at(0)}, ${at(600)}, 600, 'leave')",
                "INSERT INTO events (id, ts, game_time, type, player_uuid, player, session_id) VALUES (1, ${at(0)}, 0, 'join', '${steve.uuid}', 'Steve', 1)",
                "INSERT INTO events (id, ts, game_time, type, player_uuid, player, session_id) VALUES (2, ${at(900)}, 0, 'block_break', '${steve.uuid}', 'Steve', 1)",
                "INSERT INTO positions (ts, game_time, player_uuid, player, session_id, dim, x, y, z, underground, health, food, reason) " +
                    "VALUES (${at(1500)}, 0, '${steve.uuid}', 'Steve', 1, 'minecraft:overworld', 0, 64, 0, false, 20, 20, 'interval')",
                // Session 3: opened, no fact at all.
                "INSERT INTO sessions (session_id, player_uuid, player, joined_at) VALUES (3, '${alex.uuid}', 'Alex', ${at(2000)})",
                // A chapter and its turn in progress; an older turn that ended.
                "INSERT INTO conversations (conversation_id, role, label, chapter, started_at) VALUES ('director-1', 'director', 'Chapter 1', 1, ${at(100)})",
                "INSERT INTO turns VALUES (10, 'director-1', ${at(100)}, ${at(110)}, 'chat', NULL, NULL, 1, 0, 'idle')",
                "INSERT INTO turns (turn_id, conversation_id, started_at, trigger) VALUES (11, 'director-1', ${at(1000)}, 'salient')",
                "INSERT INTO llm_calls (id, provider, purpose, conversation_id, turn_id, role, model, started_at, ended_at, latency_ms, attempts) " +
                    "VALUES (1, 'anthropic', 'turn', 'director-1', 11, 'director', 'm', ${at(1000)}, ${at(1004)}, 4000, 1)",
                "INSERT INTO llm_calls (id, provider, purpose, conversation_id, turn_id, role, model, started_at, ended_at, latency_ms, attempts) " +
                    "VALUES (2, 'anthropic', 'turn', 'director-1', 11, 'director', 'm', ${at(1005)}, ${at(1009)}, 4000, 1)",
                "INSERT INTO tool_calls (id, ts, conversation_id, turn_id, tool_use_id, role, tool, status, duration_ms, effect) " +
                    "VALUES (1, ${at(1012)}, 'director-1', 11, 't1', 'director', 'say', 'ok', 5, 'speech')",
                // A task running in its own conversation, and one still queued.
                "INSERT INTO tasks (task_id, role, brief, status, created_at, started_at, conversation_id) " +
                    "VALUES (1, 'analyst', 'b', 'running', ${at(1000)}, ${at(1001)}, 'analyst-1')",
                "INSERT INTO conversations (conversation_id, role, label, task_id, started_at) VALUES ('analyst-1', 'analyst', 'Task T-1', 1, ${at(1001)})",
                "INSERT INTO llm_calls (id, provider, purpose, conversation_id, role, model, started_at, ended_at, latency_ms, attempts) " +
                    "VALUES (3, 'anthropic', 'worker', 'analyst-1', 'analyst', 'm', ${at(1001)}, ${at(1030)}, 29000, 1)",
                "INSERT INTO tasks (task_id, role, brief, status, created_at) VALUES (2, 'analyst', 'b2', 'queued', ${at(1020)})",
            ).forEach { sql.execute(it) }
        }
    }

    @Test
    fun `sessions, turns, conversations and tasks left open are closed`() {
        crashedDatabase()
        LogCapture().use { logs ->
            JournalFixture(file).use { journal ->
                assertEquals(
                    listOf(
                        listOf(1L, t0.plusSeconds(1500), 1500, "recovered"),
                        listOf(2L, t0.plusSeconds(600), 600, "leave"),
                        listOf(3L, t0.plusSeconds(2000), 0, "recovered"),
                    ),
                    journal.values("SELECT session_id, left_at, duration_s, end_reason FROM sessions ORDER BY 1"),
                )
                assertEquals(
                    listOf(listOf("Alex", 600L, t0.plusSeconds(2000)), listOf("Steve", 8_700L, t0.plusSeconds(1500))),
                    journal.values("SELECT name, playtime_s, last_seen FROM players ORDER BY name"),
                )
                assertEquals(
                    listOf(
                        listOf(10L, t0.plusSeconds(110), 1, 0, "idle"),
                        listOf(11L, t0.plusSeconds(1012), 2, 1, "interrupted"),
                    ),
                    journal.values("SELECT turn_id, ended_at, llm_calls, tool_calls, outcome FROM turns ORDER BY 1"),
                )
                assertEquals(
                    listOf(
                        listOf("analyst-1", t0.plusSeconds(1030), "interrupted"),
                        listOf("director-1", t0.plusSeconds(1012), "interrupted"),
                    ),
                    journal.values("SELECT conversation_id, ended_at, end_reason FROM conversations ORDER BY 1"),
                )
                assertEquals(
                    listOf(
                        listOf(1L, "cancelled", t0.plusSeconds(1030), "interrupted: the server stopped while the task was running"),
                        listOf(2L, "cancelled", t0.plusSeconds(1020), "interrupted: the server stopped while the task was queued"),
                    ),
                    journal.values("SELECT task_id, status, finished_at, error FROM tasks ORDER BY 1"),
                )
                assertEquals(
                    listOf(listOf("repaired", """{"sessions":2,"turns":1,"conversations":2,"tasks":2}""")),
                    journal.values("SELECT decision, detail::VARCHAR FROM engine_decisions WHERE kind = 'journal_health'"),
                )
                assertEquals(4L, journal.journal.nextIdSeed(IdKind.SESSION))
            }
            assertContains(logs.text, "[Journal] repaired: closed what the previous run left open")
        }
    }

    @Test
    fun `a Journal that stopped normally needs no repair`() {
        JournalFixture(file).use { it.publish(io.github.clemsix6.narrator2.event.PlayerJoined(steve, io.github.clemsix6.narrator2.testing.SampleEvents.here, 1, true, false)) }
        JournalFixture(file).use { journal ->
            assertEquals(0L, journal.count("engine_decisions"))
            assertEquals(listOf(listOf("server_stop")), journal.values("SELECT end_reason FROM sessions"))
        }
    }
}
