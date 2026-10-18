package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.memory.MemoryJobs
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.at
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a dream goes when its conversations fail: the steps go on, the reflections, the voice audit, the hygiene before it. */
class DreamerTest {
    private val f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `a failed step does not stop the next ones, without its Grimoire the dream fails, and a player whose conversation failed is reflected on again`() = runBlocking<Unit> {
        f.evening()
        f.fake.enqueue(
            reply { text("Rien de neuf sur Bartok.") },
            httpError(400, message = "Mimosa's conversation breaks"),
            httpError(400, message = "the review breaks"),
            httpError(400, message = "the chronicle breaks"),
            reply { text("Pas de Grimoire aujourd'hui.") },
            httpError(400, message = "the merges break"),
        )

        val outcome = f.dreamer.dream(30, 1)

        assertEquals(RunStatus.FAILED, outcome.status, outcome.report)
        assertNull(outcome.budget)
        assertEquals(5, outcome.lastStep, "every step was passed: a failed one is not done again")
        assertEquals(6, outcome.conversations.size)
        assertEquals(0, f.fake.pending)
        val report = outcome.report!!
        assertTrue(report.startsWith("step 1 failed: Bartok; failed: Mimosa ("), report)
        assertTrue(" · step 2 failed: " in report && " · step 3 failed: " in report, report)
        assertTrue(" · step 4 failed: the conversation ended without writing the Grimoire · step 5 failed: " in report, report)
        assertTrue(report.endsWith(" · no Grimoire written: the dream failed"), report)
        assertTrue(report.length <= 500, report)
        assertEquals("5", f.write { state(MemoryJobs.stepKey(30)) })

        // Bartok is reflected on, Mimosa is not: the next dream reads only her.
        assertNotNull(DreamInputs.Reflection.decode(f.write { state(DreamInputs.reflectedKey(f.bartok.uuid)) }))
        assertNull(f.write { state(DreamInputs.reflectedKey(f.mimosa.uuid)) })
        assertEquals(listOf("Mimosa"), f.inputs.candidates(at("22:20"), 8).map { it.player.name })

        // The voice audit ran after step 4, over nothing written.
        val audit = f.rows("SELECT reason FROM engine_decisions WHERE decision = 'voice_audit'") { getString(1) }.single()
        assertEquals("0 texts of the run and the Grimoire, none with grandiose or dark words", audit)
        // The hygiene ran before step 1 and found the merge candidate the step 5 read.
        val merges = f.fake.requests.last().json.path("messages").first().path("content").let { if (it.isTextual) it.asText() else it.first().path("text").asText() }
        assertTrue(merges.startsWith("<job kind=\"dream\" run=\"30\" step=\"5\" of=\"5\" task=\"merges\"/>\n<candidates count=\"1\">\n<pair a=\"M-"), merges)
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
                "step 5 skipped: no merge candidate",
            outcome.report,
        )
        assertEquals(1, outcome.conversations.size)
        val opening = f.fake.requests.first().json.path("messages").first().path("content").let { if (it.isTextual) it.asText() else it.first().path("text").asText() }
        assertEquals(
            listOf(
                "<job kind=\"dream\" run=\"31\" step=\"4\" of=\"5\" task=\"grimoire\"/>", "<grimoire>(none yet: write the first one)</grimoire>",
                "<players count=\"0\" order=\"latest seen first\">(no player yet)</players>", "<review>(none yet)</review>", "<session>(none yet)</session>",
                "<canon count=\"0\">(no open entry)</canon>", "<arcs count=\"0\">(no arc open)</arcs>", "<notes count=\"0\">(no open note)</notes>",
            ),
            opening.lines(),
        )
    }
}
