package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerReplayTest.Companion.text
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Chronicler's batches and naps (M3 §7.3, §7.4) and how its jobs fail (§7.6). */
class ChroniclerTest {
    private val f = ChroniclerFixture()

    @AfterEach
    fun close() = f.close()

    private val summary = """
        Joueurs vus : Bartok, Mimosa.
        Faits marquants :
        - 20:02 Mimosa est morte d'une chute.
        Engagements en cours :
        - menace — Mimosa — « la prochaine fois, je garde les bottes ».
    """.trimIndent()

    /** The evening, then Bartok leaves at 20:20 and the chapter closes at 20:30; the batches had covered it until 20:10. */
    private fun closed(summary: String?, reason: EndReason): ChroniclerFixture.Evening {
        val evening = f.scenario()
        f.publish("20:20:00", PlayerLeft(f.bartok, ChroniclerFixture.OW, sessionId = 1, sessionSeconds = 1_200, reason = LeaveReason.LEAVE))
        f.clock.now = at("20:30:30")
        f.chapterRow(ended = "20:30", summary = summary, reason = reason)
        f.journal.sync()
        return evening
    }

    @Test
    fun `a nap without summary, the Chronicler writes the chapter once, the since_last of who left, and an episode`() = runBlocking<Unit> {
        val e = closed(summary = null, reason = EndReason.SHUTDOWN)
        f.runRow(9, RunKind.NAP, "20:10", "20:30")
        f.fake.enqueue(
            reply {
                toolUse(
                    "write_chapter",
                    """{"title": "Les bottes de Mimosa", "text": "Bartok a insulté le Narrateur, qui l'a ralenti. Mimosa est morte d'une chute, a prié, et a récupéré ses bottes."}""",
                )
                toolUse("write_chapter", """{"title": "Encore", "text": "Une seconde chronique du même chapitre."}""")
                toolUse("since_last", """{"player": "Bartok", "text": "Bartok a insulté le Narrateur et a été ralenti une minute ; il est parti à 20:20."}""")
                toolUse(
                    "record_episode",
                    """{"title": "La chute de Mimosa", "what": "Mimosa est tombée, a perdu ses affaires, puis a prié le Narrateur de les lui rendre.",
                       "players": ["Mimosa"], "importance": 4, "valence": -1, "tags": ["death", "fall", "prayer"], "evidence": ["e${e.death}", "e${e.prayer}"]}""",
                )
            },
            reply { text("Chapitre écrit, épisode de la chute de Mimosa, retour de Bartok préparé.") },
        )

        val outcome = f.chronicler.nap(9, f.chapterRow(ended = "20:30", summary = null, reason = EndReason.SHUTDOWN))

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        assertEquals(1, outcome.conversations.size, "the last batch had nothing to read: one conversation, the nap's")
        assertEquals(1, outcome.rejected, "the second chronicle of the chapter")
        val opening = text(f.fake.requests.first().json.path("messages").first())
        assertTrue(opening.startsWith("<job kind=\"nap\" run=\"9\" chapter=\"13\" day=\"2026-10-01\" from=\"20:00\" to=\"20:30\" end_reason=\"shutdown\" chronicle=\"none: write it with write_chapter\"/>"), opening)
        assertTrue("<summary>\n(no summary: shutdown)\n</summary>" in opening, opening)
        assertTrue("<exchange chapter=\"13\" day=\"2026-10-01\" from=\"20:00:00\" to=\"20:30:00\"" in opening, opening)
        assertTrue("<left>\n- Bartok left at 20:20 after 20m: 0 deaths, 0 blocks broken, 0 kills, 0 advancements\n</left>" in opening, opening)
        assertTrue("<shown>\n- ${e.lava} at 20:02 (reminiscence, for e${e.death}): « Mort dans la lave du Nether: Mimosa est morte" in opening, opening)
        assertTrue("<notable>\n[20:00:01] e${e.joinBartok} JOIN Bartok (overworld 0 64 0)" in opening, opening)
        assertTrue("\n- ${e.threat} threat → Mimosa (by the director at 20:06, active): " in opening, opening)

        val chronicle = f.rows("SELECT level, title, period_from, period_to, conversation_id, source FROM chronicle") {
            listOf(getString(1), getString(2), getObject(3).toString(), getObject(4).toString(), getString(5), getString(6))
        }
        assertEquals(1, chronicle.size)
        assertEquals(listOf("chapter", "Les bottes de Mimosa"), chronicle.single().take(2))
        assertEquals(listOf(f.chapter, "chronicler"), chronicle.single().drop(4))
        assertEquals(
            listOf("Bartok a insulté le Narrateur et a été ralenti une minute ; il est parti à 20:20."),
            f.rows("SELECT content FROM memory_docs WHERE key = 'sheet:${f.bartok.uuid}:since_last'") { getString(1) },
        )
        val episode = f.rows("SELECT title, dim, x, y, z, occurred_from, run_id FROM memories WHERE kind = 'episode' AND source = 'chronicler'") {
            listOf(getString(1), getString(2), getInt(3), getInt(4), getInt(5), (getObject(6) as java.time.OffsetDateTime).toInstant(), getLong(7))
        }.single()
        assertEquals(listOf("La chute de Mimosa", "minecraft:overworld", -120, 64, 330, at("20:02"), 9L), episode)
        // The code covered the punishment and the gift of the whole chapter, nobody having noted them.
        assertEquals(
            listOf("punishment", "gift"),
            f.rows("SELECT kind FROM interactions WHERE source = 'engine' ORDER BY id") { getString(1) },
        )
    }

    @Test
    fun `a nap with summary, the code writes the chapter entry once, the model marks a memory used, which is reinforced`() = runBlocking<Unit> {
        val e = closed(summary, EndReason.QUIET_LIMIT)
        f.runRow(10, RunKind.NAP, "20:10", "20:30")
        val before = f.rows("SELECT stability_h, recalls FROM memories WHERE id = ${e.lava.id}") { getDouble(1) to getInt(2) }.single()
        f.fake.enqueue(
            reply {
                toolUse("mark_used", """{"refs": ["${e.lava}"]}""")
                toolUse("mark_used", """{"refs": ["M-99999"]}""")
            },
            reply { text("La lave du Nether a été resservie.") },
        )
        val chapter = f.chapterRow(ended = "20:30", summary = summary, reason = EndReason.QUIET_LIMIT)

        val outcome = f.chronicler.nap(10, chapter)

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        val entry = f.rows("SELECT id, title, summary, source FROM chronicle WHERE level = 'chapter'") {
            listOf(getLong(1).toString(), getString(2), getString(3), getString(4))
        }.single()
        assertEquals(listOf("Chapter 13", summary, "director"), entry.drop(1))
        assertEquals(listOf(10L), f.rows("SELECT run_id FROM memory_log WHERE table_name = 'chronicle'") { getLong(1) })
        val opening = text(f.fake.requests.first().json.path("messages").first())
        assertTrue("end_reason=\"quiet_limit\" chronicle=\"H-${entry[0]}\"/>\n<summary>\n$summary\n</summary>" in opening, opening)
        assertFalse("<exchange" in opening, "a chapter with a summary is read through it")

        val results = f.rows("SELECT status, result FROM tool_calls WHERE conversation_id = '${outcome.conversations.single()}' ORDER BY id") {
            getString(1) to getString(2)
        }
        assertEquals("ok" to "ok: ${e.lava} marked as used by the Narrator, reinforced", results[0])
        assertEquals("invalid", results[1].first)
        assertContains(results[1].second, "M-99999 was never shown to the Narrator in this chapter")
        assertEquals(listOf(f.chapter), f.rows("SELECT conversation_id FROM recalls WHERE channel = 'used' AND ref_id = ${e.lava.id}") { getString(1) })
        val after = f.rows("SELECT stability_h, recalls FROM memories WHERE id = ${e.lava.id}") { getDouble(1) to getInt(2) }.single()
        assertTrue(after.first > before.first, "stability $before → $after")
        assertEquals(before.second + 1, after.second)

        // A nap run again (the catch-up) leaves the chapter's entry as it is, and a used memory is not marked twice.
        f.runRow(11, RunKind.NAP, "20:10", "20:30")
        f.fake.enqueue(reply { toolUse("mark_used", """{"refs": ["${e.lava}"]}""") }, reply { text("Rien de nouveau.") })
        val again = f.chronicler.nap(11, chapter)
        assertEquals(RunStatus.DONE, again.status)
        assertEquals(1L, f.count("chronicle"))
        assertEquals(1L, f.count("recalls", "channel = 'used'"))
        assertContains(f.rows("SELECT result FROM tool_calls WHERE conversation_id = '${again.conversations.single()}'") { getString(1) }.single(), "already marked")
    }

    @Test
    fun `a refusal fails the batch with its reason, and the code still covers the punishments and benefits`() = runBlocking<Unit> {
        f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(reply { refusal(category = "cyber") })

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.FAILED, outcome.status)
        assertEquals("refused by the safety filter (category cyber)", outcome.report)
        assertEquals(2, outcome.writes, "the code's own: the punishment and the gift")
        assertEquals(listOf("punishment", "gift"), f.rows("SELECT kind FROM interactions WHERE source = 'engine' ORDER BY id") { getString(1) })
        assertEquals(listOf("failed"), f.rows("SELECT end_reason FROM conversations WHERE role = 'chronicler'") { getString(1) })
    }

    @Test
    fun `a spent budget fails the batch with the gateway's reset time, for the scheduler to wait`() = runBlocking<Unit> {
        f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(budgetExceeded(resetAt = "2026-10-01T21:00:00Z"))

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.FAILED, outcome.status)
        assertEquals(Instant.parse("2026-10-01T21:00:00Z"), outcome.budget?.resetAt)
        assertEquals("budget: the LLMGW budget is spent until 21:00", outcome.report)
        assertEquals(2, outcome.writes, "the punishment and the gift, covered by the code")
        assertEquals(3L, f.count("interactions"), "the Director's prayer, and the code's two")
    }

    @Test
    fun `a block that fails does not stop the next ones, but a 402 does`() = runBlocking<Unit> {
        longWindow()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(reply { text("Rien.") }, reply { refusal(category = "cyber") }, reply { text("Toujours rien.") })

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.FAILED, outcome.status)
        assertEquals(3, outcome.conversations.size, "the third block is read after the second failed")
        assertEquals("refused by the safety filter (category cyber)", outcome.report)
        val ends = f.rows("SELECT conversation_id, end_reason FROM conversations WHERE role = 'chronicler'") { getString(1) to getString(2) }.toMap()
        assertEquals(listOf("done", "failed", "done"), outcome.conversations.map { ends[it] }, "in the order of the blocks")

        f.runRow(8, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(budgetExceeded(resetAt = "2026-10-01T21:00:00Z"))
        val spent = f.chronicler.batch(8, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.FAILED, spent.status)
        assertEquals(1, spent.conversations.size, "the next blocks would hit the same 402")
        assertEquals(Instant.parse("2026-10-01T21:00:00Z"), spent.budget?.resetAt)
    }

    /**
     * M3-19 §21.3: a new conversation of the Chronicler read the tools from the gateway's cache, but wrote the
     * system prompt again. Nothing of ours varies in that prefix: the batch, the nap's last batch and its
     * consolidation send the same tools then the same system prompt, byte for byte, with the role's session.
     */
    @Test
    fun `every conversation of the Chronicler sends the same tools and system prompt, byte for byte`() = runBlocking<Unit> {
        closed(summary, EndReason.QUIET_LIMIT)
        f.chat("20:15:00", f.mimosa, "merci pour les bottes, Narrateur")
        f.clock.now = at("20:30:30")
        f.journal.sync()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(reply { text("Rien.") })
        f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))
        f.runRow(12, RunKind.NAP, "20:10", "20:30")
        f.fake.enqueue(reply { text("Rien.") }, reply { text("Chapitre consolidé.") })
        f.chronicler.nap(12, f.chapterRow(ended = "20:30", summary = summary, reason = EndReason.QUIET_LIMIT))

        val openings = f.fake.requests.groupBy { it.conversation }.values.map { it.first() }
        assertEquals(3, openings.size, "the batch, then the nap's last batch and its consolidation")
        val prefixes = openings.map { listOf(it.raw("tools"), it.raw("system")) }
        assertTrue(prefixes.first().all { !it.isNullOrEmpty() }, "tools and system are sent")
        assertEquals(1, prefixes.toSet().size, "the same tools and system prompt in every conversation")
        assertEquals(setOf(ClaudeClient.sessionOf(Role.CHRONICLER)), openings.map { it.session }.toSet())
        assertTrue(openings.all { it.json.path("cache_control").isMissingNode }, "no cache marker of ours: LLMGW places its own")
    }

    @Test
    fun `a nap whose last batch fails goes on to its consolidation, and its window is passed with a warning and a decision`() = runBlocking<Unit> {
        closed(summary, EndReason.QUIET_LIMIT)
        f.chat("20:15:00", f.mimosa, "merci pour les bottes, Narrateur")
        f.clock.now = at("20:30:30")
        f.journal.sync()
        f.runRow(12, RunKind.NAP, "20:10", "20:30")
        f.fake.enqueue(reply { refusal(category = "cyber") }, reply { text("Chapitre consolidé.") })

        val outcome = f.chronicler.nap(12, f.chapterRow(ended = "20:30", summary = summary, reason = EndReason.QUIET_LIMIT))

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        assertEquals(2, outcome.conversations.size)
        assertEquals("failed: refused by the safety filter (category cyber) / Chapitre consolidé.", outcome.report)
        assertEquals(
            listOf(
                "run 12" to "the last batch of the nap of chapter 13 (20:10–20:30) failed; the nap went on without it: " +
                    "refused by the safety filter (category cyber)",
            ),
            f.rows("SELECT subject, reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'window_passed'") { getString(1) to getString(2) },
        )
        assertEquals(listOf("punishment", "gift"), f.rows("SELECT kind FROM interactions WHERE source = 'engine' ORDER BY id") { getString(1) })
    }

    @Test
    fun `the nap of an old chapter reads what was written during it and by its own job, not what came after`() = runBlocking<Unit> {
        val e = closed(summary, EndReason.QUIET_LIMIT)
        val thanks = f.chat("20:15:00", f.mimosa, "merci pour les bottes, Narrateur")
        f.journal.sync()
        f.clock.now = at("22:00")
        val later = f.store.addMemory(
            MemoryDraft(MemoryKind.EPISODE, "La tour de Bartok", "Bartok a bâti une tour de terre au spawn.", listOf(f.bartok.uuid), importance = 3),
            Author(Source.DIRECTOR, conversationId = "director-c14"),
        ).row.ref
        f.clock.now = at("23:00")
        f.runRow(12, RunKind.NAP, "20:10", "20:30")
        f.fake.enqueue(
            reply {
                toolUse(
                    "note_interaction",
                    """{"player": "Mimosa", "kind": "compliment", "valence": 1, "summary": "a remercié le Narrateur pour les bottes",
                       "quote": "merci pour les bottes", "evidence": ["e$thanks"]}""",
                )
            },
            reply { text("Remerciement de Mimosa noté.") },
            reply { text("Chapitre consolidé.") },
        )

        val outcome = f.chronicler.nap(12, f.chapterRow(ended = "20:30", summary = summary, reason = EndReason.QUIET_LIMIT))

        assertEquals(RunStatus.DONE, outcome.status, outcome.report)
        val thanked = f.rows("SELECT id FROM interactions WHERE run_id = 12 AND source = 'chronicler'") { getLong(1) }.single()
        val opening = text(f.fake.requests.last().json.path("messages").first())
        val written = opening.substringAfter("<written>\n").substringBefore("\n</written>")
        assertTrue("- ${e.threat} threat → Mimosa (by the director at 20:06" in written, written)
        assertTrue("- I-$thanked Mimosa compliment +1 at " in written, "what the nap's last batch wrote: $written")
        assertFalse("$later" in written, "written later, for another chapter: $written")
    }

    /** Chapter 13 from 20:00, with 150 long lines of chat from 20:01: three blocks of exchange log. */
    private fun longWindow() {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        val sentence = "Le pont de Mimosa tient encore, mais pour combien de temps, personne ne le sait vraiment ici. "
        repeat(150) { i -> f.chat(at("20:01").plusSeconds(2L * i).toString().substring(11, 19), f.bartok, "$i " + sentence.repeat(3)) }
        f.journal.sync()
    }

    @Test
    fun `a window without chat nor tool call is skipped, with no conversation`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:01:00", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        f.journal.sync()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.SKIPPED, outcome.status)
        assertEquals(emptyList(), outcome.conversations)
        assertEquals(0, f.fake.requests.size)
    }

    @Test
    fun `a window too long for one block is chronicled in one conversation per block`() = runBlocking<Unit> {
        longWindow()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(reply { text("Rien.") }, reply { text("Rien non plus.") }, reply { text("Toujours rien.") })

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.DONE, outcome.status)
        assertEquals(3, outcome.conversations.toSet().size)
        assertEquals("Rien. / Rien non plus. / Toujours rien.", outcome.report)
        val openings = f.fake.requests.map { text(it.json.path("messages").first()) }
        assertEquals(listOf("1 of 3", "2 of 3", "3 of 3"), openings.map { Regex("part=\"([^\"]+)\"").find(it)!!.groupValues[1] })
        assertTrue(openings.all { "\n<known>\n" in it })
    }
}
