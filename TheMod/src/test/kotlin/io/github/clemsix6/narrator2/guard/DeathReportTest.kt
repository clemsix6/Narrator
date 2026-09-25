package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TraceQueries
import kotlinx.coroutines.runBlocking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The death guard's report of a death (M2 review, finding 18): its sentence, its `detail`, the Narrator's
 * actions read from a real Journal, and what `/narrator why <player>` shows of it.
 */
class DeathReportTest {
    private val died = Instant.parse("2026-09-25T19:35:10Z").toEpochMilli()

    /** Bartok falls 12 s of play after the guard ran out; the Narrator made him levitate 40 s before. */
    private val report = DeathReport(
        player = "Bartok", at = died, cause = "minecraft:fall", narrator = false, keptInventory = false, isProtected = false,
        playTime = 200_240, armedUntil = 200_000, deathsLeft = 1, deathsPerHour = 1,
        actions = listOf(NarratorAction(81, died - 40_000, "apply_effect", 42, "ok", "ok: levitation II on Bartok for 30 s")),
    )

    @Test
    fun `the decision's sentence ties the death to what the Narrator did just before`() {
        assertEquals(
            "minecraft:fall, not the Narrator's (guard ran out 12 s of play before), inventory dropped; " +
                "1 of 1 Narrator deaths left this played hour; " +
                "the Narrator acted on Bartok in the last 60 s: apply_effect 40 s before (turn 42)",
            report.reason(),
        )
        assertEquals("armed for 5 s of play more", report.copy(armedUntil = 200_340).guard())
        assertEquals("not armed", report.copy(armedUntil = 0).guard())
        assertEquals("protected by the operators", report.copy(isProtected = true, deathsLeft = 0).quota())
        assertEquals("no world action of the Narrator on Bartok in the last 60 s", report.copy(actions = emptyList()).actionsBefore())
        assertEquals(
            "the Narrator's actions in the last 60 s are unknown (the Journal is unavailable)",
            report.copy(actions = null, actionsUnknown = "the Journal is unavailable").actionsBefore(),
        )
    }

    @Test
    fun `the detail holds the whole report and reads back`() {
        val detail = report.detail()
        assertEquals("200000", detail["armed_until"].toString())
        assertEquals("1", detail["deaths_left"].toString())
        assertEquals(report, DeathReport.parse(detail.toString()))
        assertNull(DeathReport.parse("{\"not\": \"a report\"}"))
    }

    @Test
    fun `the Narrator's world actions on the player within the minute before, from the Journal`() = JournalFixture().use { fixture ->
        val journal = fixture.journal
        fun tool(secondsBefore: Long, tool: String, status: ToolStatus, targets: List<String>, effect: ToolEffect = ToolEffect.WORLD): Long {
            val id = journal.nextId(IdKind.TOOL_CALL)
            val ts = Instant.ofEpochMilli(died - secondsBefore * 1_000)
            journal.toolCall(ToolCallRecord(id, ts, "director-aaaa", 42, null, "toolu_$id", Role.DIRECTOR, tool, "{}", status, "$tool: done", 40, targets, effect))
            return id
        }
        tool(90, "launch", ToolStatus.OK, listOf("Bartok")) // more than a minute before
        val levitation = tool(40, "apply_effect", ToolStatus.OK, listOf("Bartok"))
        tool(35, "say", ToolStatus.OK, listOf("Bartok"), ToolEffect.SPEECH) // words do not kill
        tool(30, "apply_effect", ToolStatus.DENIED, emptyList()) // refused: nothing happened
        tool(20, "teleport", ToolStatus.OK, listOf("Mimosa")) // someone else
        val maybe = tool(10, "explosion", ToolStatus.UNKNOWN, listOf("Mimosa", "Bartok"))
        tool(-5, "lightning", ToolStatus.OK, listOf("Bartok")) // after the death
        fixture.sync()

        val actions = runBlocking { journal.read(10_000) { DeathReport.actionsOn(it, "Bartok", died - DeathReport.WINDOW_MS, died) } }
        assertEquals(listOf(levitation to "apply_effect", maybe to "explosion"), actions.map { it.id to it.tool })
        assertEquals(NarratorAction(maybe, died - 10_000, "explosion", 42, "unknown", "explosion: done"), actions[1])
    }

    @Test
    fun `why player shows the last death, in red when the Narrator acted just before a death it was not blamed for`() = JournalFixture().use { fixture ->
        fixture.clock.now = Instant.ofEpochMilli(died + 2_000)
        val decisions = Decisions(fixture.journal, fixture.clock)
        decisions.record(Log.guard, DecisionRecord.Kind.DEATH_GUARD, "died", "Bartok", report.copy(at = died - 600_000, cause = "minecraft:lava").reason())
        decisions.record(Log.guard, DecisionRecord.Kind.DEATH_GUARD, "died", "Bartok", report.reason(), detail = report.detail())
        decisions.record(Log.guard, DecisionRecord.Kind.DEATH_GUARD, "counted", "Bartok", "another decision")
        fixture.sync()

        val line = runBlocking { fixture.journal.read(10_000) { TraceQueries(it).lastDeath("bartok") } }!!
        assertEquals(report, line.detail?.let(DeathReport::parse))
        assertNull(runBlocking { fixture.journal.read(10_000) { TraceQueries(it).lastDeath("Mimosa") } })

        val text = TraceText(ZoneOffset.UTC, died + 60_000)
        val lines = text.capped(text.death(line))
        assertEquals(
            listOf(
                "[Narrator] why: Bartok's last death · 19:35:10, 1m ago",
                "Cause: minecraft:fall · not the Narrator's · inventory dropped",
                "Death guard: ran out 12 s of play before · 1 of 1 Narrator deaths left this played hour",
                "The Narrator acted on Bartok in the 60 s before:",
                "  -40 s apply_effect (turn #42) ok: ok: levitation II on Bartok for 30 s",
                "More: /narrator why turn 42",
            ),
            lines.map { it.string },
        )
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), (lines[1].siblings.lastOrNull() ?: lines[1]).style.color)
        val quiet = text.capped(text.death(line.copy(detail = report.copy(actions = emptyList()).detail().toString())))
        assertEquals("No world action of the Narrator on Bartok in the 60 s before.", quiet.last().string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), (quiet[1].siblings.lastOrNull() ?: quiet[1]).style.color)
    }
}
