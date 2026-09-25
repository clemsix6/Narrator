package io.github.clemsix6.narrator2.tool.pacing

import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.PendingReminder
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/** `remind_me` (M1_M2 §9.5) against a fake Director: what it schedules, and its cap of 5 pending reminders. */
class PacingToolsTest {
    private val clock = TestClock() // 19:30:00 UTC

    /** A Director that would take any number of reminders: the cap under test is the tool's own. */
    private val director = FakeDirector(clock, maxReminders = 100)
    private val services = TestServices(clock = clock, director = director)
    private val caller = Caller(Role.DIRECTOR, "director-1", turnId = 12, taskId = null)
    private val tool = pacingTools(services).single() as RemindMeTool

    private fun remind(inSeconds: Int, note: String, services: TestServices = this.services): ToolResult =
        runBlocking { tool.run(ToolContext(services, caller, clock, 5_000), RemindMeArgs(inSeconds, note)) }

    @Test
    fun `it schedules a reminder with the Director and says when`() {
        val result = remind(600, "  check that Bartok gave back Mimosa's diamonds  ")

        assertEquals(ToolStatus.OK, result.status, result.text)
        assertEquals("ok: reminder R-1 at 19:40:00 (in 10m)", result.text)
        assertEquals(
            listOf(PendingReminder(1, "check that Bartok gave back Mimosa's diamonds", clock.millis() + 600_000)),
            director.reminders,
        )
        assertEquals("remind_me", tool.name)
        assertEquals(ToolEffect.ENGINE, tool.effect)
        assertEquals("ok: reminder R-2 at 19:30:45 (in 45s)", remind(45, "second").text)
        assertEquals("ok: reminder R-3 at 19:32:30 (in 2m30s)", remind(150, "third").text)
    }

    @Test
    fun `at most 5 reminders wait at once`() {
        repeat(RemindMeTool.MAX_PENDING) { remind(60 * (it + 1), "reminder ${it + 1}") }

        val sixth = remind(30, "one too many")

        assertEquals(ToolStatus.DENIED, sixth.status, sixth.text)
        assertEquals(
            "DENIED (reminder_limit): 5 reminders are already waiting (R-1 at 19:31:00, R-2 at 19:32:00, R-3 at 19:33:00, " +
                "R-4 at 19:34:00, R-5 at 19:35:00); a new one can be set when one of them comes due",
            sixth.text,
        )
        assertEquals(5, director.reminders.size, "nothing more was scheduled")

        // One comes due and leaves the Director's list: there is room again.
        clock.advance(Duration.ofMinutes(1))
        director.reminders.removeAt(0)
        assertEquals(ToolStatus.OK, remind(30, "now it fits").status)
        assertEquals(5, director.reminders.size)
    }

    @Test
    fun `a Director that cannot take it is a failure`() {
        val stopped = object : DirectorControl by director {
            override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long> =
                Result.failure(IllegalStateException("the server is stopping"))
        }

        val result = remind(60, "never", TestServices(clock = clock, director = stopped))

        assertEquals(ToolStatus.FAILED, result.status, result.text)
        assertEquals("FAILED: no reminder set: the server is stopping", result.text)
    }

    @Test
    fun `an empty note or a delay out of bounds is refused`() {
        assertEquals("Invalid arguments: note is empty: say what to do when the reminder comes", remind(60, "   ").text)
        assertEquals(
            ToolArgs.Decoded.Invalid("in_seconds must be between 30 and 3600 (got 10)"),
            ToolArgs.decode(RemindMeArgs.serializer(), """{"in_seconds":10,"note":"too soon"}"""),
        )
        assertEquals(
            ToolArgs.Decoded.Invalid("note is longer than 200 characters (got 201)"),
            ToolArgs.decode(RemindMeArgs.serializer(), """{"in_seconds":60,"note":"${"a".repeat(201)}"}"""),
        )
        assertEquals(emptyList(), director.reminders)
    }
}
