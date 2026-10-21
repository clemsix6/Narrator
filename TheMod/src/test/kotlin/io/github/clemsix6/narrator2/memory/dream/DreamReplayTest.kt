package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.memory.MemoryJobs
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.lastToolResult
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.text
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DreamReplayTest` (M3 §15.1, M6 §6.3): a whole dream over the reference evening, with the Dreamer's and the
 * storyteller's scripted answers (fake Anthropic server): the order of the six steps and what each one writes, an
 * invalid Grimoire refused then corrected, the voice audit after step 4, the programme's ideas and planned arc; and a
 * dream cut short by the gateway's budget that resumes at its step.
 */
class DreamReplayTest {
    private val f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `a dream goes through its six steps in order, an invalid Grimoire is refused then corrected, and the storyteller writes the programme`() = runBlocking<Unit> {
        val e = f.evening()
        f.fake.enqueue(
            // Step 1, Bartok first: the most active.
            reply {
                toolUse(
                    "set_trait",
                    """{"player": "Bartok", "trait": "deference", "side": "high", "intensity": 3, "confidence": "medium",
                       "evidence": ["${e.insulted}", "${e.provoked}"], "note": "insulte le Narrateur puis rit de ses sanctions"}""",
                )
                toolUse("set_skill", """{"player": "Bartok", "skill": "survival", "score": 3, "rationale": "1 mort dans la lave du Nether en une session", "evidence": ["${e.lava}"]}""")
                toolUse("set_relation", """{"player": "Bartok", "other": "Mimosa", "kind": "rival", "note": "meurt dans la lave sous ses yeux", "evidence": ["${e.lava}"]}""")
                toolUse("write_sheet", """{"player": "Bartok", "section": "recap", "text": "Bartok insulte le Narrateur quand il perd, puis rit de ses sanctions. Il est mort dans la lave du Nether.", "evidence": ["${e.insulted}"]}""")
                toolUse("dream_hint", """{"player": "Bartok", "text": "Mort dans la lave du Nether ce soir, en creusant vers des débris antiques."}""")
            },
            reply { text("Bartok : insolent, récap écrit, rival de Mimosa.") },
            // Step 1, Mimosa.
            reply {
                toolUse("set_trait", """{"player": "Mimosa", "trait": "deference", "side": "low", "intensity": 2, "confidence": "low", "evidence": ["${e.prayed}"]}""")
                toolUse("write_sheet", """{"player": "Mimosa", "section": "recap", "text": "Mimosa prie le Narrateur quand elle perd ses affaires, poliment."}""")
            },
            reply { text("Mimosa : respectueuse.") },
            // Step 2: the review.
            reply {
                toolUse("write_review", """{"text": "Le guichet des insultes a fait rire Bartok (« mdr ok »). Les commentaires sur l'abîme de lave tombent à plat."}""")
                toolUse("set_gag", """{"id": "${e.gag}", "state": "worn", "note": "plus aucune réaction ce soir"}""")
            },
            reply { text("Revue écrite, gag usé.") },
            // Step 3: the session, then the season that covers it.
            reply {
                toolUse("write_chronicle", """{"level": "session", "title": "La soirée du guichet", "text": "Bartok a insulté le Narrateur puis est mort dans la lave. Mimosa a prié.", "covers": ["${e.chapterEntry}"]}""")
            },
        )
        f.fake.enqueue { request ->
            val session = Regex("wrote (H-\\d+)").find(lastToolResult(request.json))!!.groupValues[1]
            reply { toolUse("write_chronicle", """{"level": "season", "title": "Les débuts", "text": "La première saison commence par une soirée d'insultes.", "covers": ["$session"]}""") }
        }
        // A Grimoire of M3, with its plans: refused (M6: the plans are the programme's).
        val invalid = "## Players\n- Bartok : insolent.\n## Arcs\n- aucun\n## Running gags\n- « le Plongeur ».\n## What works\n\n## What falls flat\n- aucun\n## Plans\n- aucun"
        val grimoire = listOf(
            "## Players", "- Bartok : insolent quand il perd, il rit des sanctions ; surnommé « le Plongeur ».", "- Mimosa : respectueuse, prie quand elle perd ses affaires.",
            "## Running gags", "- « le Plongeur » : frais.", "## Arcs", "- aucun", "## What works", "- Le guichet des insultes a fait rire Bartok.",
            "## What falls flat", "- aucun", "## Watch out", "- Mimosa perd souvent ses affaires : pas de châtiment gratuit.",
        ).joinToString("\n")
        f.fake.enqueue(
            reply { text("Session et saison écrites.") },
            // Step 4: an invalid Grimoire, then the Grimoire corrected.
            reply { toolUse("write_grimoire", """{"text": ${json(invalid)}}""") },
            reply { toolUse("write_grimoire", """{"text": ${json(grimoire)}}""") },
            reply { text("Grimoire réécrit.") },
            // Step 5, the storyteller: an arc planned, a season and a challenge.
            reply {
                toolUse("remember", """{"kind": "arc", "what": "Le Narrateur prépare la revanche de Bartok contre la lave du Nether.", "players": ["Bartok"], "theme": "revanche_sur_la_lave", "importance": 6}""")
                toolUse(
                    "propose_idea",
                    """{"kind": "season", "title": "Les débuts", "pitch": "Une saison de premiers pas : le Nether pour Bartok, le retour des affaires perdues pour Mimosa.",
                       "fit": "toute la saison", "novelty": 3, "fun": 3, "fairness": 4, "hours": 168}""",
                )
                toolUse(
                    "propose_idea",
                    """{"kind": "challenge", "title": "La course au pic", "pitch": "Course au sommet du pic enneigé au nord du spawn ; le premier arrivé gagne un joker.",
                       "fit": "Bartok et Mimosa en ligne", "players": ["Bartok", "Mimosa"], "register": "sports_commentary", "novelty": 4, "fun": 4, "fairness": 4}""",
                )
            },
            reply { text("Une saison, une course et un récit prévu.") },
            // Step 6: the two memories of the same death.
            reply { toolUse("merge_memories", """{"keep": "${e.lava}", "merged": ["${e.lavaAgain}"]}""") },
            reply { text("Une fusion.") },
        )

        val outcome = f.dreamer.dream(12, 1)

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        assertEquals(0, f.fake.pending)
        assertEquals(emptyList(), f.fake.rejections)
        assertNull(outcome.budget)
        assertEquals(6, outcome.lastStep)
        assertEquals(1, outcome.rejected, "the invalid Grimoire")
        assertEquals(
            "step 1 done: Bartok, Mimosa · step 2 done · step 3 done · step 4 done: Grimoire version 1 · step 5 done: 2 ideas written, 0 closed · " +
                "step 6 done: 1 pair read",
            outcome.report,
        )
        assertEquals("6", f.state.state(MemoryJobs.stepKey(12)))

        // The conversations, in the order of the steps; the Dreamer's with the prompt and the tools of the role, the programme the storyteller's.
        val labels = f.rows("SELECT label, role, end_reason FROM conversations WHERE role IN ('dreamer', 'storyteller') ORDER BY started_at, conversation_id") {
            Triple(getString(1), getString(2), getString(3))
        }
        assertEquals(outcome.conversations.size, labels.size)
        assertEquals(7, outcome.conversations.size)
        val order = f.rows("SELECT c.label, c.role FROM conversations c JOIN (SELECT conversation_id, min(id) AS first FROM llm_calls GROUP BY 1) l USING (conversation_id) ORDER BY l.first") {
            "${getString(1)} (${getString(2)})"
        }
        assertEquals(
            listOf(
                "Dream run 12, step 1: Bartok (dreamer)", "Dream run 12, step 1: Mimosa (dreamer)", "Dream run 12, step 2: review (dreamer)",
                "Dream run 12, step 3: chronicle (dreamer)", "Dream run 12, step 4: grimoire (dreamer)", "Dream run 12, step 5: programme (storyteller)",
                "Dream run 12, step 6: merges (dreamer)",
            ),
            order,
        )
        assertEquals(listOf("done"), labels.map { it.third }.distinct())
        assertEquals(listOf("dream", "programme"), f.rows("SELECT DISTINCT purpose FROM llm_calls ORDER BY 1") { getString(1) })
        val first = f.fake.requests.first().json
        assertEquals(Prompts.bundled().get("dreamer"), first.path("system").first().path("text").asText())
        assertEquals(
            listOf(
                "dream_hint", "merge_memories", "note_to_director", "recall", "remember", "resolve", "retire_trait", "set_gag",
                "set_relation", "set_skill", "set_trait", "write_chronicle", "write_grimoire", "write_review", "write_sheet",
            ),
            first.path("tools").map { it.path("name").asText() },
        )
        // The six conversations of the Dreamer open on the same tools and system prompt, byte for byte (M3-POST, the gateway's cache).
        val dreamer = f.fake.requests.filter { it.conversation.orEmpty().startsWith("dreamer-") }
        val prefixes = dreamer.groupBy { it.conversation }.values.map { it.first() }.map { listOf(it.raw("tools"), it.raw("system")) }
        assertEquals(6, prefixes.size)
        assertEquals(1, prefixes.toSet().size)

        // Step 1: what the model read about Bartok, and what it wrote; then the reflection is kept.
        val opening = text(first.path("messages").first())
        println("=== The opening of step 1 for Bartok, as the model read it ===\n$opening\n=== end ===")
        println("The Dreamer's prefix: system prompt ${first.path("system").toString().length} characters, tools ${first.path("tools").toString().length}")
        assertTrue(opening.startsWith("<job kind=\"dream\" run=\"12\" step=\"1\" of=\"6\" task=\"players\" player=\"Bartok\" last_reflection=\"never\"/>\n<sheet>\nBartok · "), opening)
        assertTrue("<new_interactions since=\"ever\" count=\"2\">\n" in opening, opening)
        assertTrue("- ${e.insulted} 10-01 20:01 insult -2 · said « narrateur t'es nul »: a traité le Narrateur de « nul » dans le chat" in opening, opening)
        assertTrue("<stats_change>(first reflection on this player: no earlier values; the sheet has the current ones)</stats_change>" in opening, opening)
        assertTrue("- [10-01 20:01] e${e.insult} CHAT (addressed 0.9, hostile, provocation): \"narrateur t'es nul\"" in opening, opening)
        assertTrue("- Mimosa: " in opening && "toward them: none named · toward this player: none named" in opening, opening)
        assertTrue(opening.length <= DreamInputs.STEP_MAX.getValue(1), "${opening.length}")
        val trait = f.write { traits(f.bartok.uuid) }.single()
        assertEquals(listOf("deference", "1", "3", "0.6"), listOf(trait.trait, trait.side.toString(), trait.intensity.toString(), trait.confidence.toString()))
        assertEquals(listOf(e.insulted, e.provoked), trait.evidence)
        assertEquals("rival", f.write { relations(f.bartok.uuid) }.single().kind.id)
        assertEquals(3, f.write { skills(f.bartok.uuid) }.single().score)
        assertTrue(f.write { doc("sheet:${f.bartok.uuid}:recap") }!!.content.startsWith("Bartok insulte le Narrateur"))
        assertEquals(1L, f.count("notes", "kind = 'dream_hint' AND run_id = 12"))
        listOf(f.bartok, f.mimosa).forEach { player ->
            val reflection = DreamInputs.Reflection.decode(f.state.state(DreamInputs.reflectedKey(player.uuid)))
            assertNotNull(reflection, player.name)
            assertEquals(Instant.parse("2026-10-01T22:10:00Z").toEpochMilli(), reflection.at)
            assertEquals(1.0, reflection.stats["deaths"])
        }

        // Step 2: the review and the worn gag; the reactions the model read.
        val review = text(f.fake.requests[4].json.path("messages").first())
        assertTrue(review.startsWith("<job kind=\"dream\" run=\"12\" step=\"2\" of=\"6\" task=\"review\"/>\n<tics>"), review)
        assertTrue("<registers from=\"10-01 20:01\" to=\"10-01 22:10\" lines=\"2\" reviewed=\"0\">(no line reviewed: the line review was off, or failed)</registers>" in review, review)
        assertTrue("<lines count=\"2\" order=\"latest first\" reactions=\"laughed 1 · ignored 1\">" in review, review)
        assertTrue("- [10-01 20:01] e${e.sanction} → Bartok laughed: \"Bartok, guichet des insultes" in review, review)
        assertTrue("worn" == f.rows("SELECT freshness FROM canon WHERE id = ${e.gag.id}") { getString(1) }.single())
        assertEquals(1, f.write { doc("review") }!!.version)

        // Step 3: a session over the chapter, and the season in progress that covers it.
        val chronicle = f.rows("SELECT level, covers::VARCHAR, open FROM chronicle WHERE source = 'dreamer' ORDER BY id") { Triple(getString(1), getString(2), getBoolean(3)) }
        assertEquals(listOf("session", "season"), chronicle.map { it.first })
        assertEquals("[${e.chapterEntry.id}]", chronicle[0].second)
        assertEquals(true, chronicle[1].third)

        // Step 4: the invalid Grimoire refused with all its problems, then the valid one written.
        val calls = f.rows("SELECT tool, status, result FROM tool_calls WHERE tool = 'write_grimoire' ORDER BY id") { Triple(getString(1), getString(2), getString(3)) }
        assertEquals(listOf("invalid", "ok"), calls.map { it.second })
        val headings = "## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out"
        assertEquals(
            "Invalid arguments: the Grimoire is not written: headings: \"## Plans\" is not a section; the only headings are $headings; " +
                "sections: ## Watch out missing; write all six: $headings; sections: \"## What works\" has no line; write at least one (« - aucun » when there is nothing)",
            calls[0].third,
        )
        assertEquals("ok: wrote the Grimoire (version 1, ${grimoire.length} of 10000 characters)", calls[1].third)
        assertEquals(grimoire, f.write { doc("grimoire") }!!.content)
        val audit = f.rows("SELECT subject, reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'voice_audit'") { getString(1) to getString(2) }.single()
        assertEquals("run 12", audit.first)
        assertTrue(audit.second.endsWith("1 with grandiose or dark words: abîme (memory_docs review:1)"), audit.second)

        // Step 5: the storyteller read the new Grimoire and the players, then wrote two ideas and an arc, as itself, in the run.
        val programme = f.fake.requests.first { it.conversation.orEmpty().startsWith("storyteller-") }.json
        assertEquals(Prompts.bundled().get("storyteller"), programme.path("system").first().path("text").asText())
        assertEquals(
            listOf("drop_idea", "inspire", "player_profile", "propose_idea", "recall", "remember", "resolve", "sql"),
            programme.path("tools").map { it.path("name").asText() },
        )
        val brief = text(programme.path("messages").first())
        println("=== The opening of the programme, as the storyteller read it ===\n$brief\n=== end ===")
        println(
            "The storyteller's prefix: system prompt ${programme.path("system").toString().length} characters, tools ${programme.path("tools").toString().length}, " +
                "opening ${brief.length}",
        )
        assertTrue(brief.startsWith("<job kind=\"dream\" run=\"12\" step=\"5\" of=\"6\" task=\"programme\"/>\n<grimoire version=\"1\""), brief.take(300))
        assertTrue(grimoire.lines()[1] in brief, "the Grimoire just rewritten")
        assertTrue("<players count=\"2\" days=\"7\" order=\"the most active first\">\n- Bartok « le Plongeur » · 1h50m played in 7 days" in brief, brief)
        assertTrue("<ideas open=\"0\">(none open, none closed lately)</ideas>" in brief, brief)
        assertTrue(brief.length <= ProgrammeInputs.STEP_MAX, "${brief.length}")
        assertEquals(
            listOf(listOf("season", "storyteller", "12"), listOf("challenge", "storyteller", "12")),
            f.rows("SELECT kind, source, run_id FROM ideas ORDER BY id") { listOf(getString(1), getString(2), getLong(3).toString()) },
        )
        assertEquals(listOf("planned" to "storyteller"), f.rows("SELECT status, source FROM canon WHERE kind = 'arc'") { getString(1) to getString(2) })
        val decision = f.rows("SELECT reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'programme'") { getString(1) }.single()
        assertEquals("done: 2 ideas written, 0 closed", decision)

        // Step 6: the duplicate merged into the memory kept.
        assertEquals(listOf(true to e.lava.id), f.rows("SELECT archived, merged_into FROM memories WHERE id = ${e.lavaAgain.id}") { getBoolean(1) to getLong(2) })
        assertEquals(outcome.writes.toLong(), f.count("memory_log", "run_id = 12"))
    }

    @Test
    fun `a dream cut short by the gateway's budget resumes at its step, without doing the steps before again`() = runBlocking<Unit> {
        f.evening()
        f.fake.enqueue(
            reply { text("Rien de neuf sur Bartok.") },
            reply { text("Rien de neuf sur Mimosa.") },
            reply { text("Rien à revoir.") },
            budgetExceeded(resetAt = "2026-10-01T23:00:00Z"),
        )

        val cut = f.dreamer.dream(20, 1)

        assertEquals(RunStatus.FAILED, cut.status)
        assertEquals(Instant.parse("2026-10-01T23:00:00Z"), cut.budget?.resetAt)
        assertEquals(2, cut.lastStep, "steps 1 and 2 passed; step 3 to do again")
        assertEquals(4, cut.conversations.size)
        assertEquals("2", f.state.state(MemoryJobs.stepKey(20)))
        assertTrue(cut.report!!.endsWith("step 3 budget: budget: the LLMGW budget is spent until 23:00"), cut.report)

        // MemoryJobs starts the next attempt at the step after the last one passed.
        val grimoire = GrimoireFormat.SECTIONS.joinToString("\n") { "## $it\n- aucun" }
        f.fake.enqueue(
            reply { text("Rien à écrire dans la chronique.") },
            reply { toolUse("write_grimoire", """{"text": ${json(grimoire)}}""") },
            reply { text("Grimoire écrit.") },
            reply { text("Rien à proposer ce soir.") },
            reply { text("Aucun doublon.") },
        )

        val resumed = f.dreamer.dream(21, 3)

        assertEquals(RunStatus.DONE, resumed.status, resumed.report)
        assertEquals(6, resumed.lastStep)
        assertEquals(
            "resumed at step 3 · step 3 done · step 4 done: Grimoire version 1 · step 5 done: 0 ideas written, 0 closed · step 6 done: 1 pair read",
            resumed.report,
        )
        assertEquals("6", f.state.state(MemoryJobs.stepKey(21)))
        val steps = f.fake.requests.drop(4).filter { it.json.path("messages").size() == 1 }.map { text(it.json.path("messages").first()).substringBefore("/>") }
        assertEquals(
            listOf(3 to "chronicle", 4 to "grimoire", 5 to "programme", 6 to "merges").map { (step, task) -> "<job kind=\"dream\" run=\"21\" step=\"$step\" of=\"6\" task=\"$task\"" },
            steps,
        )
        assertEquals(2L, f.count("conversations", "label LIKE '%step 1:%'"), "the players were not reflected on twice")

        // An attempt cut after its last step finishes at once, done since the Grimoire was rewritten.
        val after = f.dreamer.dream(22, 7)
        assertEquals(RunStatus.DONE, after.status)
        assertEquals(emptyList(), after.conversations)
        assertEquals("resumed at step 7", after.report)
    }

    private companion object {
        /** [text] as a JSON string. */
        fun json(text: String): String = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text)
    }
}
