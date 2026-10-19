package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.config.ReflexConfig
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.director.PendingReminder
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.TaskRecord
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lines of `/narrator status` that come from memory (M1_M2 §14.4). */
class StatusLinesTest {
    private val clock = TestClock()
    private val now = clock.millis()

    private fun lines(services: TestServices) = AdminCommands.statusLines(services, Path.of("config", "narrator2")).map { it.string }

    @Test
    fun `a quiet server without key nor Journal`() {
        val lines = lines(TestServices(clock = clock))
        assertEquals("[Narrator] status", lines[0])
        assertEquals("Phase: running · settings: loaded", lines[1])
        assertEquals("Key: missing (environment variable NARRATOR_LLMGW_KEY)", lines[3])
        assertEquals("Director: idle · turns this hour 0 of 30 (30 left) · inbox 0 · reminders none", lines[4])
        assertEquals("Chapter: none open · operator channel system_message", lines[5])
        assertEquals("Tasks: none running", lines[6])
        assertEquals("Journal: unavailable (unit test) · 0 lost", lines[7])
        assertEquals("Reflexes: heuristic (no LLMGW key in the environment variable NARRATOR_LLMGW_KEY)", lines[8])
        assertEquals("Guards: chaos 3 · creatures 0 of 16 · protected online: nobody", lines[9])
        assertTrue(lines.any { it.startsWith("  director: claude-opus-5-5 · effort medium") }, lines.toString())
        assertTrue(lines.size <= 22, "status stays short: ${lines.size} lines")
    }

    @Test
    fun `a busy Director, a running task, the reflex model disabled and a protected player online`() {
        val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
        val players = PlayerStates().apply {
            put(LivePlayer(mimosa, 1, now, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20, null, null, now, false))
        }
        val guards = FakeGuards(chaos = 4).apply { protectedPlayers += mimosa.uuid }
        val director = object : io.github.clemsix6.narrator2.director.DirectorControl by FakeDirector(clock) {
            override fun status() = DirectorStatus(
                state = DirectorState.WAITING, until = now + 4_000, reason = null, chapter = 3, conversationId = "director-aaaa",
                contextTokens = 61_234, inbox = 3, droppedSignals = 1, turnId = 42, operatorChannel = OperatorChannelMode.USER_TAG,
                reminders = listOf(PendingReminder(3, "check Bob's village", now + 600_000)), turnsThisHour = 12, turnsPerHour = 30,
            )
        }
        val workers = object : WorkersControl {
            override val roles = setOf(Role.ANALYST)
            override fun submit(role: Role, brief: String, caller: Caller) = Result.failure<Long>(UnsupportedOperationException())
            override fun list() = listOf(
                TaskRecord(12, Role.ANALYST, "a", TaskRecord.Status.DONE, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null, "done", null),
                TaskRecord(13, Role.ANALYST, "b", TaskRecord.Status.RUNNING, clock.instant(), clock.instant().minusSeconds(12), null, 42, null, null, null),
                TaskRecord(14, Role.ANALYST, "c", TaskRecord.Status.QUEUED, clock.instant(), null, null, 42, null, null, null),
            )
            override fun cancel(taskId: Long) = Result.success(Unit)
        }
        val settings = testSettings(NarratorConfig(chaos = 4, reflex = ReflexConfig(enabled = false)))
        val lines = lines(TestServices(clock = clock, settings = settings, players = players, guards = guards, director = director, workers = workers))
        assertEquals(
            "Director: waiting until 19:30:04 · turn #42 running · turns this hour 12 of 30 (18 left) · inbox 3 (1 dropped) · reminders R-3 19:40:00",
            lines[4],
        )
        assertEquals("Chapter: #3 · context 61k tokens · operator channel user_tag", lines[5])
        assertEquals("Tasks: T-13 analyst running 12 s, T-14 analyst queued", lines[6])
        assertEquals("Reflexes: heuristic (reflex.enabled is false)", lines[8])
        assertEquals("Guards: chaos 4 · creatures 0 of 24 · protected online: Mimosa", lines[9])
    }

    @Test
    fun `the reflex model in force, with its state`() {
        val config = NarratorConfig()
        ReflexClient(config, "status-key-7a1e").use { reflex ->
            assertEquals("Reflexes: claude-haiku-5-5, active", lines(TestServices(clock = clock, settings = testSettings(config, reflex = reflex)))[8])
        }
    }

    @Test
    fun `a spent budget of turns says who may still wake the Director`() {
        val director = object : io.github.clemsix6.narrator2.director.DirectorControl by FakeDirector(clock) {
            override fun status() = FakeDirector(clock).status().copy(turnsThisHour = 31, turnsPerHour = 30)
        }
        assertEquals(
            "Director: idle · turns this hour 31 of 30 (none left: only addressed chat, prayers, whispers and due commitments wake it) · inbox 0 · reminders none",
            lines(TestServices(clock = clock, director = director))[4],
        )
    }
}
