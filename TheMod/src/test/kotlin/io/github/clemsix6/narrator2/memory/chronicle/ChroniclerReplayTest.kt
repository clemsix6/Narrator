package io.github.clemsix6.narrator2.memory.chronicle

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ChroniclerReplayTest` (M3 §15.1): the reference exchange log of an evening and the Chronicler's scripted
 * answers (fake Anthropic server): the expected writes, the Director's duplicate refused, a made-up quote
 * refused, and the punishment nobody noted covered by the code.
 */
class ChroniclerReplayTest {
    private val f = ChroniclerFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `a batch over the reference evening records what the Director forgot, and only that`() = runBlocking<Unit> {
        val e = f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        f.fake.enqueue(
            reply {
                toolUse(
                    "remember",
                    """{"kind": "threat", "what": "Le Narrateur a menacé Bartok d'une pluie de slimes s'il recommence à l'insulter.",
                       "quote": "je te promets une pluie de slimes si tu recommences", "players": ["Bartok"], "evidence": ["e${e.slimes}"], "importance": 4}""",
                )
                toolUse(
                    "remember",
                    """{"kind": "threat", "what": "Le Narrateur a menacé Mimosa de garder ses bottes la prochaine fois.",
                       "quote": "la prochaine fois, je garde les bottes", "players": ["Mimosa"], "evidence": ["e${e.granted}"]}""",
                )
                toolUse(
                    "remember",
                    """{"kind": "promise", "what": "Le Narrateur a promis un diamant à Mimosa.", "quote": "je te donnerai un diamant demain",
                       "players": ["Mimosa"], "evidence": ["e${e.granted}"]}""",
                )
                toolUse(
                    "note_interaction",
                    """{"player": "Bartok", "kind": "insult", "valence": -2, "summary": "a traité le Narrateur de « nul » dans le chat",
                       "quote": "narrateur t'es nul", "evidence": ["e${e.insult}"]}""",
                )
                toolUse(
                    "note_interaction",
                    """{"player": "Mimosa", "kind": "gift", "valence": 1, "summary": "a reçu des bottes en fer après sa prière",
                       "evidence": ["e${e.prayer}", "t${e.boots}"]}""",
                )
                toolUse("use_canon", """{"id": "${e.nickname}", "evidence": ["e${e.plongeur}"]}""")
            },
            reply { text("Menace de slimes envers Bartok enregistrée ; insulte de Bartok et bottes de Mimosa notées ; « le Plongeur » resservi.") },
        )

        val outcome = f.chronicler.batch(7, f.chapter, at("20:00"), at("20:10"))

        assertEquals(RunStatus.DONE, outcome.status)
        assertEquals("Menace de slimes envers Bartok enregistrée ; insulte de Bartok et bottes de Mimosa notées ; « le Plongeur » resservi.", outcome.report)
        assertEquals(1, outcome.conversations.size)
        val conversation = outcome.conversations.single()
        assertTrue(conversation.startsWith("chronicler-"), conversation)
        assertEquals(5, outcome.writes, "a threat, two interactions, a use of the nickname, and the punishment covered by the code")
        assertEquals(1, outcome.rejected, "the made-up quote")
        assertNull(outcome.budget)
        assertEquals(emptyList(), f.fake.rejections)

        // What the model read: its prompt, its ten tools, the exchange log and what the memory knows.
        val first = f.fake.requests.first().json
        assertEquals(Prompts.bundled().get("chronicler"), first.path("system").first().path("text").asText())
        assertEquals(
            listOf("mark_used", "note_interaction", "note_to_director", "recall", "record_episode", "remember", "resolve", "since_last", "use_canon", "write_chapter"),
            first.path("tools").map { it.path("name").asText() },
        )
        val opening = text(first.path("messages").first())
        assertTrue(opening.startsWith("<job kind=\"batch\" run=\"7\" chapter=\"13\"/>\n<exchange chapter=\"13\" day=\"2026-10-01\" from=\"20:00:00\" to=\"20:10:00\""), opening)
        assertTrue("[20:04:00] e${e.slimes} NARRATOR→all: \"Bartok, je te promets une pluie de slimes si tu recommences.\"" in opening, opening)
        assertTrue("\n<known>\nPlayers: Bartok, Mimosa\n" in opening, opening)

        // The tools' answers, as the model read them.
        val results = f.rows("SELECT tool, status, result FROM tool_calls WHERE conversation_id = '$conversation' ORDER BY id") {
            Triple(getString(1), getString(2), getString(3))
        }
        assertEquals(listOf("ok", "ok", "invalid", "ok", "ok", "ok"), results.map { it.second })
        assertTrue(results[1].third.startsWith("ok: already remembered as ${e.threat} "), results[1].third)
        assertTrue(results[2].third.startsWith("Invalid arguments: quote must be words"), results[2].third)
        assertTrue(results[5].third.startsWith("ok: ${e.nickname} nickname → Bartok served again at 20:05: 1 use"), results[5].third)

        // The canon: the Director's threat once, the forgotten one written by the Chronicler with its quote, for its run.
        val canon = f.rows("SELECT kind, quote, source, run_id, conversation_id FROM canon WHERE kind = 'threat' ORDER BY id") {
            listOf(getString(1), getString(2), getString(3), getObject(4)?.toString(), getString(5))
        }
        assertEquals(
            listOf(
                listOf("threat", "la prochaine fois, je garde les bottes", "director", null, f.chapter),
                listOf("threat", "je te promets une pluie de slimes si tu recommences", "chronicler", "7", conversation),
            ),
            canon,
        )
        assertEquals(1, f.rows("SELECT uses FROM canon WHERE id = ${e.nickname.id}") { getInt(1) }.single())

        // The interactions: the Director's prayer, the Chronicler's insult and gift, and the punishment covered by the code.
        val interactions = f.rows("SELECT player, kind, valence, source, summary, tool_calls::VARCHAR FROM interactions ORDER BY id") {
            listOf(getString(1), getString(2), getInt(3).toString(), getString(4), getString(5), getString(6))
        }
        assertEquals(
            listOf(
                listOf("Mimosa", "prayer", "1", "director", "a prié le Narrateur de lui rendre ses affaires", "[]"),
                listOf("Bartok", "insult", "-2", "chronicler", "a traité le Narrateur de « nul » dans le chat", "[]"),
                listOf("Mimosa", "gift", "1", "chronicler", "a reçu des bottes en fer après sa prière", "[${e.boots}]"),
                listOf("Bartok", "punishment", "0", "engine", "apply_effect « slowness II on Bartok for 1m » (t${e.slowness})", "[${e.slowness}]"),
            ),
            interactions,
        )

        // Its conversation: a row, a transcript, two calls of purpose chronicle; its run context is gone.
        val row = f.rows("SELECT role, label, end_reason, summary, transcript FROM conversations WHERE conversation_id = '$conversation'") {
            listOf(getString(1), getString(2), getString(3), getString(4), getString(5))
        }.single()
        assertEquals(listOf("chronicler", "Batch of chapter 13 (run 7)", "done", outcome.report), row.take(4))
        assertTrue(row[4]!!.endsWith("$conversation.md"), row[4])
        assertEquals(listOf("chronicle", "chronicle"), f.rows("SELECT purpose FROM llm_calls WHERE conversation_id = '$conversation' ORDER BY id") { getString(1) })
        assertNull(f.agents.runContext(conversation))
        assertEquals(1L, f.count("memory_runs"), "the Chronicler never writes the job's row")
        assertEquals("running", f.rows("SELECT status FROM memory_runs WHERE run_id = 7") { getString(1) }.single())
    }

    companion object {
        /** The text of a message, whether its content is a string or blocks. */
        fun text(message: JsonNode): String {
            val content = message.path("content")
            return if (content.isTextual) content.asText() else content.joinToString("\n") { it.path("text").asText() }
        }
    }
}
