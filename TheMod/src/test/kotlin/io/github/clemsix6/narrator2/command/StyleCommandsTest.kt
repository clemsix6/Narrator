package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.StyleConfig
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.style.DisabledStyle
import io.github.clemsix6.narrator2.style.Diversity
import io.github.clemsix6.narrator2.style.StyleDataset
import io.github.clemsix6.narrator2.testing.FakeStyle
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import kotlinx.coroutines.runBlocking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/narrator style` (M6 §13): what runs, the watch's `Style:` line and diversity, the review of the Director's latest
 * lines and the latest tone flags from a real Journal ([StyleDataset]), the bus's losses; without a watch nor a
 * Journal; within one page of the chat; and the command tree.
 */
class StyleCommandsTest {
    private val watch = FakeStyle().apply {
        situation = "Style: crutch words « céleste » (2 of your last 30 lines)"
        measured = Diversity(50, 0.8, ComicRegister.DIVINE_BUREAUCRACY, 0.38, 0.71, listOf("céleste" to 6, "guichet" to 3), 3)
    }

    private fun report(services: TestServices): List<Component> = runBlocking { StyleCommands.report(services) }

    /** The lines as written, before the chat wrapped them: a continuation is indented 4 spaces more than the line it continues. */
    private fun List<Component>.unwrapped(): List<String> = map { it.string }.fold(mutableListOf()) { lines, line ->
        val previous = lines.lastOrNull()
        val continuation = previous?.let { " ".repeat(it.takeWhile { c -> c == ' ' }.length + 4) }
        if (continuation != null && line.startsWith(continuation) && line[continuation.length] != ' ') {
            lines[lines.lastIndex] = "$previous ${line.trim()}"
        } else {
            lines += line
        }
        lines
    }

    @Test
    fun `the watch, the review of the latest lines, the latest flags and the bus's losses`() {
        StyleDataset.journal().use { fixture ->
            val bus = EventBus(fixture.clock, { 0L }, 1)
            bus.subscribeAsync("style", "style", capacity = 1)
            repeat(3) { bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Ligne $it.", null, 1, null)) }
            ReflexClient(NarratorConfig(), "style-key-0c3d").use { reflex ->
                val services = TestServices(clock = fixture.clock, settings = testSettings(reflex = reflex), bus = bus, journal = fixture.journal, style = watch)
                assertEquals(
                    listOf(
                        "[Narrator] style of the Director",
                        "Watch: on · line review on · tone notes on · resting openers refused",
                        "Style: crutch words « céleste » (2 of your last 30 lines)",
                        "Latest 50 lines: openers distinct 0.80 · top register divine_bureaucracy 38% · register entropy 0.71 · crutch « céleste » in 6 lines · 3 tics",
                        "Review: 9 of the Director's latest 10 lines labeled",
                        "Tone flags, the latest 3 (a flaw at 0.7 or more):",
                        "  21:30:00 repeats 0.75: \"Le guichet céleste ferme ses portes, Bartok.\"",
                        "  21:20:00 flat 0.80: \"Effet lenteur appliqué à Bartok.\"",
                        "  20:40:00 dark 0.90: \"Ciel couvert sur Bartok, avec de fortes chances d'enclume.\"",
                        "Bus: lost to a full queue: style 2",
                    ),
                    report(services).unwrapped(),
                )
            }
        }
    }

    @Test
    fun `without a watch nor a Journal, what runs and why not`() {
        val config = NarratorConfig(style = StyleConfig(lineReview = false, toneNotes = false, refuseRestingOpeners = false))
        assertEquals(
            listOf(
                "[Narrator] style of the Director",
                "Watch: off (no Journal, or not wired: see the server log) · line review off (style.lineReview is false) · tone notes off · " +
                    "resting openers only named",
                "Review: unknown (the Journal is unavailable: unit test)",
                "Bus: no event lost to a full queue",
            ),
            report(TestServices(settings = testSettings(config), style = DisabledStyle)).unwrapped(),
        )
        val quiet = FakeStyle()
        val lines = report(TestServices(style = quiet)).unwrapped()
        assertEquals("Watch: on · line review off (no reflex model: see /narrator status) · tone notes on · resting openers refused", lines[1])
        assertEquals("Style: nothing at rest, no register overused", lines[2])
        assertEquals("Latest lines: none measured yet", lines[3])
    }

    @Test
    fun `a Director that has said nothing yet, and a watch with no label`() {
        JournalFixture().use { fixture ->
            val lines = report(TestServices(clock = TestClock(StyleDataset.NOW), journal = fixture.journal, style = watch)).unwrapped()
            assertEquals(listOf("Review: the Director has said nothing yet", "Tone flags: none (no flaw at 0.7 or more)"), lines.subList(4, 6))
        }
        assertEquals(
            "Latest 12 lines: openers distinct 0.92 · no line labeled · no crutch word · 1 tic",
            StyleCommands.diversityLine(Diversity(12, 0.92, null, 0.0, 0.0, emptyList(), 1)),
        )
    }

    @Test
    fun `the longest reply holds on one page of the chat`() {
        JournalFixture().use { fixture ->
            repeat(StyleCommands.FLAGS + 1) { i ->
                fixture.clock.now = StyleDataset.at("21:00").plusSeconds(60L * i)
                val line = fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Une réplique très longue et très sombre $i, " + "encore ".repeat(80), null, 10L + i, null))
                StyleDataset.label(fixture, line, "courtroom", dark = 0.9, cruel = 0.9, flat = 0.9, outOfCharacter = 0.9, repeats = 0.9)
            }
            fixture.sync()
            val bus = EventBus(fixture.clock, { 0L }, 1)
            listOf("style_watch", "line_review", "salience", "memory_events").forEach { bus.subscribeAsync(it, "test", capacity = 1) }
            repeat(3) { bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Ligne $it.", null, 1, null)) }
            val loud = FakeStyle().apply {
                situation = "Style: " + "openers at rest « Vingt secondes », « Bonsoir, mortels » · ".repeat(7).take(393)
                measured = Diversity(50, 0.8, ComicRegister.CLASSIFIED_ADS, 0.38, 0.71, listOf("anticonstitutionnellement" to 6), 13)
            }
            val config = NarratorConfig(style = StyleConfig(lineReview = true, toneNotes = false, refuseRestingOpeners = false))
            val reply = report(TestServices(clock = fixture.clock, settings = testSettings(config), bus = bus, journal = fixture.journal, style = loud))
            assertTrue(reply.size <= 1 + TraceText.MAX_LINES, "${reply.size} lines: ${reply.map { it.string }}")
            assertTrue(reply.all { it.string.length <= WRAP }, reply.joinToString("\n") { it.string })
            assertTrue(reply.any { it.string.startsWith("  21:05:00 dark 0.90 (+4): \"Une réplique très longue et très sombre 5, encore") }, reply.joinToString("\n") { it.string })
        }
    }

    @Test
    fun `the command parses under narrator`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(LiteralArgumentBuilder.literal<CommandSourceStack>("narrator").also(StyleCommands::attach))
        val result = dispatcher.parse("narrator style", null)
        assertTrue(!result.reader.canRead() && result.exceptions.isEmpty() && result.context.command != null, "${result.exceptions}")
        assertEquals(listOf("narrator", "style"), result.context.nodes.map { it.node.name })
    }
}
