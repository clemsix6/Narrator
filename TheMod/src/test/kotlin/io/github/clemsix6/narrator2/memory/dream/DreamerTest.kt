package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.memory.MemoryJobs
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.at
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.text
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a dream goes when its conversations fail: the steps go on, the reflections, the voice audit, the hygiene before it; the programme never decides. */
class DreamerTest {
    private var f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    /** The text of the first message of the [index]th request to the fake server. */
    private fun opening(index: Int): String = text(f.fake.requests[index].json.path("messages").first())

    @Test
    fun `a failed step does not stop the next ones, without its Grimoire the dream fails, and a player whose conversation failed is reflected on again`() = runBlocking<Unit> {
        f.evening()
        f.fake.enqueue(
            reply { text("Rien de neuf sur Bartok.") },
            httpError(400, message = "Mimosa's conversation breaks"),
            httpError(400, message = "the review breaks"),
            httpError(400, message = "the chronicle breaks"),
            reply { text("Pas de Grimoire aujourd'hui.") },
            httpError(400, message = "the programme breaks"),
            httpError(400, message = "the merges break"),
        )

        val outcome = f.dreamer.dream(30, 1)

        assertEquals(RunStatus.FAILED, outcome.status, outcome.report)
        assertNull(outcome.budget)
        assertEquals(6, outcome.lastStep, "every step was passed: a failed one is not done again")
        assertEquals(7, outcome.conversations.size)
        assertEquals(0, f.fake.pending)
        val report = outcome.report!!
        assertTrue(report.startsWith("step 1 failed: Bartok; failed: Mimosa ("), report)
        assertTrue(" · step 2 failed: " in report && " · step 3 failed: " in report, report)
        assertTrue(" · step 4 failed: the conversation ended without writing the Grimoire · step 5 failed: " in report, report)
        assertTrue(" · step 6 failed: " in report, report)
        assertTrue(report.endsWith(" · no Grimoire written: the dream failed"), report)
        assertTrue(report.length <= 500, report)
        assertEquals("6", f.state.state(MemoryJobs.stepKey(30)))

        // Bartok is reflected on, Mimosa is not: the next dream reads only her.
        assertNotNull(DreamInputs.Reflection.decode(f.state.state(DreamInputs.reflectedKey(f.bartok.uuid))))
        assertNull(f.state.state(DreamInputs.reflectedKey(f.mimosa.uuid)))
        assertEquals(listOf("Mimosa"), f.inputs.candidates(at("22:20"), 8).map { it.player.name })

        // The voice audit ran after step 4, over nothing written.
        val audit = f.rows("SELECT reason FROM engine_decisions WHERE decision = 'voice_audit'") { getString(1) }.single()
        assertEquals("0 texts of the run and the Grimoire, none with grandiose or dark words", audit)
        // The programme was the storyteller's, and its failure is recorded.
        assertTrue(opening(5).startsWith("<job kind=\"dream\" run=\"30\" step=\"5\" of=\"6\" task=\"programme\"/>\n<grimoire>"), opening(5).take(200))
        assertEquals("storyteller", f.rows("SELECT role FROM conversations WHERE label = 'Dream run 30, step 5: programme'") { getString(1) }.single())
        val programme = f.rows("SELECT reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'programme'") { getString(1) }.single()
        assertTrue(programme.startsWith("failed: 0 ideas written, 0 closed ("), programme)
        // The hygiene ran before step 1 and found the merge candidate the step 6 read.
        assertTrue(opening(6).startsWith("<job kind=\"dream\" run=\"30\" step=\"6\" of=\"6\" task=\"merges\"/>\n<candidates count=\"1\">\n<pair a=\"M-"), opening(6))
    }

    @Test
    fun `a dream with nothing to do skips its steps, and writes the Grimoire still`() = runBlocking<Unit> {
        val grimoire = GrimoireFormat.SECTIONS.joinToString("\\n") { "## $it\\n- aucun" }
        f.fake.enqueue(
            reply { toolUse("write_grimoire", """{"text": "$grimoire"}""") },
            reply { text("Un premier Grimoire, vide.") },
        )

        val outcome = f.dreamer.dream(31, 1)

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        assertEquals(
            "step 1 skipped: no player has anything new since their last reflection · step 2 skipped: nothing to review: no line of the Narrator, " +
                "no gag, no review · step 3 skipped: nothing to chronicle: no chapter waits for a session that is over · step 4 done: Grimoire version 1 · " +
                "step 5 skipped: no player played in the last 7 days · step 6 skipped: no merge candidate",
            outcome.report,
        )
        assertEquals(1, outcome.conversations.size)
        assertEquals(
            listOf(
                "<job kind=\"dream\" run=\"31\" step=\"4\" of=\"6\" task=\"grimoire\"/>", "<grimoire>(none yet: write the first one)</grimoire>",
                "<players count=\"0\" order=\"latest seen first\">(no player yet)</players>", "<review>(none yet)</review>", "<session>(none yet)</session>",
                "<canon count=\"0\">(no open entry)</canon>", "<arcs count=\"0\">(no arc open)</arcs>", "<notes count=\"0\">(no open note)</notes>",
            ),
            opening(0).lines(),
        )
        assertEquals(0L, f.count("engine_decisions", "decision = 'programme'"), "a programme skipped leaves no decision")
    }

    /**
     * M6 §6.3: the programme is never decisive. Cut by the gateway's budget, the dream stops as at any step and resumes at
     * step 5, done since its Grimoire was rewritten; with `memory.programme` false, the step is skipped.
     */
    @Test
    fun `a programme cut by the budget resumes at step 5, and memory programme false skips it`() = runBlocking<Unit> {
        f.evening()
        val grimoire = GrimoireFormat.SECTIONS.joinToString("\\n") { "## $it\\n- aucun" }
        f.fake.enqueue(
            reply { text("Rien de neuf sur Bartok.") },
            reply { text("Rien de neuf sur Mimosa.") },
            reply { text("Rien à revoir.") },
            reply { text("Rien à écrire dans la chronique.") },
            reply { toolUse("write_grimoire", """{"text": "$grimoire"}""") },
            reply { text("Grimoire écrit.") },
            budgetExceeded(resetAt = "2026-10-01T23:00:00Z"),
        )

        val cut = f.dreamer.dream(40, 1)

        assertEquals(RunStatus.FAILED, cut.status)
        assertNotNull(cut.budget)
        assertEquals(4, cut.lastStep, "the Grimoire is passed; the programme to do again")
        assertEquals("4", f.state.state(MemoryJobs.stepKey(40)))
        assertTrue(cut.report!!.endsWith("step 4 done: Grimoire version 1 · step 5 budget: budget: the LLMGW budget is spent until 23:00"), cut.report)

        f.fake.enqueue(
            reply { text("Aucune idée ce soir.") },
            reply { text("Aucun doublon.") },
        )
        val resumed = f.dreamer.dream(41, 5)

        assertEquals(RunStatus.DONE, resumed.status, resumed.report)
        assertEquals("resumed at step 5 · step 5 done: 0 ideas written, 0 closed · step 6 done: 1 pair read", resumed.report)
        assertEquals(6, resumed.lastStep)
        assertTrue(opening(7).startsWith("<job kind=\"dream\" run=\"41\" step=\"5\" of=\"6\" task=\"programme\"/>"), opening(7).take(200))

        // Without the programme: the same evening dreams in five conversations.
        f.close()
        f = DreamerFixture(programme = false)
        f.evening()
        f.fake.enqueue(
            reply { text("Rien de neuf sur Bartok.") },
            reply { text("Rien de neuf sur Mimosa.") },
            reply { text("Rien à revoir.") },
            reply { text("Rien à écrire dans la chronique.") },
            reply { toolUse("write_grimoire", """{"text": "$grimoire"}""") },
            reply { text("Grimoire écrit.") },
            reply { text("Aucun doublon.") },
        )
        val without = f.dreamer.dream(42, 1)
        assertEquals(RunStatus.DONE, without.status, without.report)
        assertTrue(" · step 5 skipped: memory.programme is false · step 6 done: 1 pair read" in without.report!!, without.report)
        assertEquals(0L, f.count("conversations", "role = 'storyteller'"))
    }
}
