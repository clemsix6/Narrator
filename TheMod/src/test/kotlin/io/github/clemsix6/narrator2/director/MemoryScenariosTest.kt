package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.memory.BriefingSection
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.NoteStatus
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Shown
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Director with its memory (M3 §11, task M3-15), against the strict fake Anthropic server and a fake
 * memory, in virtual time: the sheet of a player who joins, a commitment that comes due, a note that
 * waits for a wake-up, the memories brought back during a turn, the chapters told to the memory, and a
 * Director without a memory that runs as in M2.
 */
class MemoryScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val here = Pos("minecraft:overworld", 1, 64, 3)

    private fun joined(name: String) = PlayerJoined(DirectorHarness.ref(name), here, 1, firstTime = false, isOp = false)

    private fun died(name: String) = PlayerDied(DirectorHarness.ref(name), here, "minecraft:lava", null, null, null, 3, false, false)

    private fun due(h: DirectorHarness) =
        DirectorSignal.MemoryDue(Ref(RefKind.CANON, 45), "C-45 promise → Mimosa, set 1h ago: « je te rends tes bottes demain, promis »", h.clock.millis())

    @Test
    fun `a player who joins - the wake-up holds the memory block with the sheet, after the events and the briefing`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        var briefedFirst = false
        h.memory.attach = { signals ->
            briefedFirst = h.memory.briefed.isNotEmpty()
            val join = signals.any { (it as? DirectorSignal.Event)?.envelope?.event is PlayerJoined }
            if (join) "<memory>\nSHEET Bartok (joined; away 2d 03h, last session 10-01 20:10–21:52):\n  Recap: explorateur tête baissée.\n</memory>" else null
        }
        h.memory.briefingSections = listOf(BriefingSection("canon", "", listOf("C-45 promise → Mimosa, due 21:00")))
        h.fake.enqueue(reply { text("…") })

        val join = h.publish(joined("Bartok"))
        h.advance(3_000)
        h.awaitTurns(1)

        val wake = h.wakeText(0)
        assertContains(wake, "[-3s] JOIN Bartok (overworld 1 64 3)\n</events>\n<memory>\nSHEET Bartok (joined; away 2d 03h, last session 10-01 20:10–21:52):\n  Recap: explorateur tête baissée.\n</memory>\n<situation ")
        assertTrue(briefedFirst, "the briefing, which shows the sheets of the players online, comes before the attachments")
        assertContains(h.userText(0), "<canon>\nC-45 promise → Mimosa, due 21:00\n</canon>\n")
        assertEquals(listOf(join.id), h.memory.attached.single().map { (it as DirectorSignal.Event).envelope.id })
        assertEquals(h.memory.briefed.single().conversationId, h.sessionOf(0))
        assertTrue(h.toolEnvs.single().memoryAvailable, "the memory tools are wired with the memory")
        h.assertInvariants()
    }

    @Test
    fun `a commitment due wakes the Director with a due trigger, even once the budget of turns is spent`() = DirectorHarness(dir, turnsPerHour = 1).use { h ->
        h.online("Mimosa")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text("…") })
        h.chat("Mimosa", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        assertEquals(1, h.director.status().turnsThisHour)

        h.publish(died("Mimosa"))
        h.advance(30_000)
        assertEquals(1, h.fake.requests.size, "the budget is spent: a death waits")
        h.director.post(due(h))
        h.advance(2_999)
        assertEquals(1, h.fake.requests.size)
        h.advance(1)
        h.awaitTurns(2)

        val wake = h.wakeText(1)
        assertContains(wake, "[-3s] DUE C-45 promise → Mimosa, set 1h ago: « je te rends tes bottes demain, promis »\n")
        assertContains(wake, "[-33s] DEATH Mimosa: lava", message = "the death that waited goes with it")
        val turn = h.endedTurns().last()
        assertEquals(TurnRecord.Trigger.DUE, turn.trigger)
        assertTrue(h.traces.decisions.any { it.decision == "woke" && it.reason?.startsWith("due C-45") == true }, "${h.traces.decisions.map { it.reason }}")
        h.assertInvariants()
    }

    @Test
    fun `a memory note and a cue wake nobody, and go with the next wake-up`() = DirectorHarness(dir).use { h ->
        h.director.post(DirectorSignal.MemoryNote(Ref(RefKind.NOTE, 9), NoteKind.COHERENCE, "le Narrateur a dit que les lois ne valent pas la nuit ; C-25 ne prévoit pas d'exception.", h.clock.millis()))
        h.director.post(DirectorSignal.MemoryCue(CueKind.ANNIVERSARY, Ref(RefKind.MEMORY, 12), null, "M-12, one month ago today: « clemsix6 a vaincu l'Ender Dragon seul… »", h.clock.millis()))
        h.advance(600_000)
        assertEquals(0, h.fake.requests.size, "a note and a cue never wake the Director")

        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)

        val wake = h.wakeText(0)
        assertContains(wake, "\n[-10m] NOTE N-9 coherence: le Narrateur a dit que les lois ne valent pas la nuit ; C-25 ne prévoit pas d'exception.\n")
        assertContains(wake, "\n[-10m] ANNIVERSARY M-12, one month ago today: « clemsix6 a vaincu l'Ender Dragon seul… »\n")
        assertEquals(TurnRecord.Trigger.CHAT, h.endedTurns().single().trigger)
        h.assertInvariants()
    }

    @Test
    fun `the memories an event brings back during a turn follow its new events`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.memory.attach = { signals ->
            if (signals.any { (it as? DirectorSignal.Event)?.envelope?.event is PlayerDied }) {
                "<memory>\nRECALLED for DEATH Bartok: M-120 (3w ago, ★7): « Bartok est mort dans un lac de lave du Nether… »\n</memory>"
            } else {
                null
            }
        }
        h.fake.enqueue(reply { toolUse("slow_probe") })
        h.fake.enqueue(reply { text("Encore la lave.") })

        h.chat("Bartok", "narrateur, regarde")
        h.advance(1_500)
        h.awaitUntil("the probe runs") { h.probe.running }
        h.publish(died("Bartok"))
        h.probe.release()
        h.awaitTurns(1)

        assertFalse("<memory>" in h.wakeText(0), "nothing to attach to the chat")
        assertEquals(listOf("tool_result", "text"), h.userBlocks(1))
        val injected = h.userText(1)
        assertTrue(injected.startsWith("<new_events now=\"21:32:01\">\n[-0s] DEATH Bartok: lava"), injected)
        assertContains(injected, "</new_events>\n<memory>\nRECALLED for DEATH Bartok: M-120 (3w ago, ★7): « Bartok est mort dans un lac de lave du Nether… »\n</memory>")
        assertEquals(2, h.memory.attached.size)
        h.advance(60_000)
        assertEquals(2, h.fake.requests.size, "the death was told: it wakes nobody")
        h.assertInvariants()
    }

    @Test
    fun `a wake-up that fails gives the memory back what it showed, and the sheet goes again`() = DirectorHarness(dir).use { h ->
        val bartok = h.online("Bartok")
        h.memory.attach = { signals ->
            // As the memory does (M3 §11.2): a sheet once per chapter, marked in the chapter's memory.
            val chapter = h.memory.briefed.last()
            val join = signals.any { (it as? DirectorSignal.Event)?.envelope?.event is PlayerJoined }
            if (join && bartok.uuid !in chapter.sheetsShownAt) {
                chapter.sheetsShownAt[bartok.uuid] = h.clock.millis()
                chapter.shownRefs += Ref(RefKind.MEMORY, 120)
                "<memory>\nSHEET Bartok (joined)\n</memory>"
            } else {
                null
            }
        }
        h.fake.enqueue(httpError(529))
        h.fake.enqueue(reply { text("…") })

        h.publish(joined("Bartok"))
        h.advance(3_000)
        h.awaitTurns(1)
        assertEquals(DirectorState.BACKOFF, h.director.status().state)
        h.advance(30_000)
        h.awaitTurns(2)

        assertContains(h.wakeText(0), "<memory>\nSHEET Bartok (joined)\n</memory>")
        assertContains(h.wakeText(1), "<memory>\nSHEET Bartok (joined)\n</memory>", message = "the failed request told the model nothing")
        assertEquals(setOf(Ref(RefKind.MEMORY, 120)), h.memory.briefed.last().shownRefs)
        h.assertInvariants()
    }

    @Test
    fun `what the memory showed is recorded once its request is accepted, never for a request that failed`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        val sheet = Ref(RefKind.INTERACTION, 512)
        h.memory.brief = { chapter ->
            chapter.shownRefs += Ref(RefKind.CANON, 45)
            chapter.unrecorded += Shown(listOf(Ref(RefKind.CANON, 45)), RecallChannel.BRIEFING, RecallContext(chapter.conversationId, null, null), listOf(Ref(RefKind.NOTE, 9)))
        }
        h.memory.attach = { signals ->
            val chapter = h.memory.briefed.last()
            val join = signals.filterIsInstance<DirectorSignal.Event>().firstOrNull { it.envelope.event is PlayerJoined }
            if (join != null && sheet !in chapter.shownRefs) {
                chapter.shownRefs += sheet
                chapter.unrecorded += Shown(listOf(sheet), RecallChannel.JOIN, RecallContext(chapter.conversationId, null, join.envelope.id))
                "<memory>\nSHEET Bartok (joined)\n</memory>"
            } else {
                null
            }
        }
        h.memory.store.rows[Ref(RefKind.NOTE, 9)] = Note(Ref(RefKind.NOTE, 9), h.clock.instant(), NoteKind.COHERENCE, "…", emptyList(), emptyList(), NoteStatus.OPEN, 0)
        h.fake.enqueue(httpError(529))
        h.fake.enqueue(reply { text("…") })

        h.publish(joined("Bartok"))
        h.advance(3_000)
        h.awaitTurns(1)
        assertTrue(h.memory.store.recalls.isEmpty() && h.memory.store.writes.isEmpty(), "the failed request told the model nothing: nothing is recorded")
        h.advance(30_000)
        h.awaitTurns(2)
        h.awaitUntil("what the accepted request showed is recorded") { h.memory.store.recalls.size >= 2 }

        assertEquals(2, h.memory.briefed.size, "the briefing was built again")
        assertEquals(listOf(Ref(RefKind.CANON, 45) to RecallChannel.BRIEFING, sheet to RecallChannel.JOIN), h.memory.store.recalls.map { it.first to it.second }, "each once")
        assertEquals(listOf("markNote N-9 by engine"), h.memory.store.writes)
        assertTrue(h.memory.briefed.last().unrecorded.isEmpty())
        h.assertInvariants()
    }

    @Test
    fun `a memory too slow to attach showed nothing, and the sheet it would have shown is named`() = DirectorHarness(dir).use { h ->
        val bartok = h.online("Bartok")
        h.memory.attach = { _ ->
            val chapter = h.memory.briefed.last()
            chapter.sheetsShownAt[bartok.uuid] = h.clock.millis()
            chapter.shownRefs += Ref(RefKind.INTERACTION, 512)
            chapter.unrecorded += Shown(listOf(Ref(RefKind.INTERACTION, 512)), RecallChannel.JOIN, RecallContext(chapter.conversationId, null, null))
            delay(10_000) // the writer is busy with a checkpoint
            "<memory>\nSHEET Bartok (joined)\n</memory>"
        }
        h.fake.enqueue(reply { text("…") })

        h.publish(joined("Bartok"))
        h.advance(3_000)
        h.advance(Director.ATTACH_TIMEOUT_MS)
        h.awaitTurns(1)

        assertContains(h.wakeText(0), "</events>\n<memory>\n(the sheet of Bartok could not be read in time: player_profile Bartok)\n</memory>\n<situation ")
        val chapter = h.memory.briefed.last()
        assertTrue(chapter.shownRefs.isEmpty() && chapter.sheetsShownAt.isEmpty(), "the chapter's memory is put back as it was: the sheet comes at the next join")
        h.runCurrent()
        assertTrue(h.memory.store.recalls.isEmpty(), "what was never shown is never recorded")
        h.assertInvariants()
    }

    @Test
    fun `a memory too slow to brief showed nothing, and its sections say so`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.memory.brief = { chapter ->
            chapter.shownRefs += Ref(RefKind.CANON, 45)
            chapter.unrecorded += Shown(listOf(Ref(RefKind.CANON, 45)), RecallChannel.BRIEFING, RecallContext(chapter.conversationId, null, null))
            delay(60_000)
        }
        h.fake.enqueue(reply { text("…") })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.advance(Briefing.MEMORY_TIMEOUT_MS)
        h.awaitTurns(1)

        assertContains(h.userText(0), "<canon>\n(unavailable: the memory did not answer within ")
        assertTrue(h.memory.briefed.single().shownRefs.isEmpty(), "the chapter's memory is put back as it was")
        h.runCurrent()
        assertTrue(h.memory.store.recalls.isEmpty())
        h.assertInvariants()
    }

    @Test
    fun `a memory that fails to attach leaves the events without a memory block`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.memory.attach = { throw IllegalStateException("the index is locked") }
        h.fake.enqueue(reply { text("…") })

        h.publish(joined("Bartok"))
        h.advance(3_000)
        h.awaitTurns(1)

        val wake = h.wakeText(0)
        assertContains(wake, "JOIN Bartok")
        assertFalse("<memory>" in wake, wake)
        assertEquals(TurnRecord.Outcome.IDLE, h.endedTurns().single().outcome)
        h.assertInvariants()
    }

    @Test
    fun `every chapter that closes is told to the memory once, with or without its summary`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text(EMPTY_SUMMARY) })
        h.fake.enqueue(reply { text("…") })

        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)
        h.director.post(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR))
        h.advance(20_000)
        h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }
        val first = h.memory.closed.single()
        assertEquals(h.sessionOf(0), first.conversationId)
        assertEquals(ConversationRecord.EndReason.OPERATOR, first.endReason)
        assertEquals(EMPTY_SUMMARY, first.summary)

        h.chat("Bartok", "re")
        h.advance(1_500)
        h.awaitTurns(2)
        h.stopServer()

        assertEquals(listOf(h.sessionOf(0), h.sessionOf(2)), h.memory.closed.map { it.conversationId })
        assertEquals(ConversationRecord.EndReason.SHUTDOWN, h.memory.closed.last().endReason)
        assertNull(h.memory.closed.last().summary)
        h.assertInvariants()
    }

    @Test
    fun `the memory's lines end the situation`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.memory.situation = listOf("Favor: Bartok -31 ↘", "Due soon: C-45 promise → Mimosa in 12m")
        h.fake.enqueue(reply { text("…") })
        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(1)

        assertTrue(h.wakeText(0).endsWith("Favor: Bartok -31 ↘\nDue soon: C-45 promise → Mimosa in 12m\n</situation>"), h.wakeText(0))
    }

    @Test
    fun `without a memory the Director runs as in M2 - no memory block, no memory lines, no memory tools`() =
        DirectorHarness(dir, memoryOff = "memory.enabled is false").use { h ->
            h.online("Bartok")
            h.memory.attach = { "<memory>\nSHEET Bartok\n</memory>" }
            h.memory.situation = listOf("Favor: Bartok -31")
            h.fake.enqueue(reply { text("…") })

            h.publish(joined("Bartok"))
            h.advance(3_000)
            h.awaitTurns(1)

            val wake = h.wakeText(0)
            assertFalse("<memory>" in wake, wake)
            assertFalse("Favor:" in wake, wake)
            assertTrue(wake.endsWith("Your last actions: none\n</situation>"), wake)
            assertContains(h.userText(0), "<grimoire>\n(memory unavailable: memory.enabled is false)\n</grimoire>\n")
            assertFalse(h.toolEnvs.single().memoryAvailable)
            assertTrue(h.memory.attached.isEmpty() && h.memory.briefed.isEmpty(), "the fake memory is not the services' one")
            assertEquals(TurnRecord.Trigger.SALIENT, h.endedTurns().single().trigger)
            h.assertInvariants()
        }
}

/** The shortest summary the format allows (`chapter_summary.md`): its seven sections, nothing in them. */
private const val EMPTY_SUMMARY =
    "Joueurs vus : Bartok.\nFaits marquants : aucun.\nInterventions du Narrateur : aucun.\nEngagements en cours : aucun.\n" +
        "Fils ouverts : aucun.\nRépertoire utilisé : aucun.\nÉtat des joueurs : aucun."
