package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.memory.Audited
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.LogOp
import io.github.clemsix6.narrator2.memory.MemoryLog
import io.github.clemsix6.narrator2.memory.RecallHit
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.testing.MemorySamples.bartok
import io.github.clemsix6.narrator2.testing.MemorySamples.mimosa
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hybrid recall (M3 §10.4, §10.6): candidates, filters, weights, strengths, reminiscence draws and lines. */
class HybridRecallTest {
    private val now = SearchFixture.NOW
    private fun ago(hours: Long): Instant = now.minus(Duration.ofHours(hours))

    private fun tool(text: String?, players: List<PlayerRef> = emptyList(), tags: List<String> = emptyList(), limit: Int = 8) =
        RecallQuery(text, players = players.map { it.uuid }, tags = tags, limit = limit, purpose = RecallPurpose.TOOL)

    private fun List<RecallHit>.refs(): List<Ref> = map { it.ref }

    private fun memory(id: Long) = Ref(RefKind.MEMORY, id)

    private fun fixture(block: SearchFixture.() -> Unit) = SearchFixture().use {
        it.player(bartok)
        it.player(mimosa)
        it.block()
    }

    @Test
    fun `relevance, weight and strength make the score`() = fixture {
        memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", players = listOf(bartok), tags = listOf("death", "lava"))
        val hit = search(tool("lave", players = listOf(bartok), tags = listOf("lava"))).single()
        // relevance = 0.6 × 1 + 0.4 × (0.5 player + 0.25 tag); weight = 0.4 + 0.4 × 5/10 + 0.2 × 2^0; strength 1.
        assertEquals(0.9, hit.relevance, 1e-9)
        assertEquals(1.0, hit.strength, 1e-9)
        assertEquals(0.9 * 0.8, hit.score, 1e-9)

        playClock.world = 38 * 3_600L
        val later = search(tool("lave", players = listOf(bartok), tags = listOf("lava"))).single()
        assertEquals(0.5, later.strength, 1e-9, "one half-life of world play later")
        assertEquals(0.9 * 0.8 * 0.5, later.score, 1e-9)

        playClock.world = 100_000 * 3_600L
        assertEquals(0.02, search(tool("lave")).single().strength, 1e-9, "the floor: never forgotten for good")
    }

    @Test
    fun `importance, age and strength order rows that match alike`() = fixture {
        memory(1, "Lave", "Bartok est tombé dans la lave.", importance = 3)
        memory(2, "Lave", "Bartok est tombé dans la lave.", importance = 9)
        memory(3, "Lave", "Bartok est tombé dans la lave.", importance = 9, at = ago(24 * 60))
        memory(4, "Lave", "Bartok est tombé dans la lave.", importance = 9, reinforcedPlaySeconds = -38 * 3_600)
        assertEquals(listOf(2L, 3L, 1L, 4L), search(tool("lave")).map { it.ref.id })
        val hits = search(tool("lave")).associateBy { it.ref.id }
        assertEquals(0.6 * (0.4 + 0.36 + 0.2 * 0.25), hits.getValue(3).score, 1e-9, "60 days old: 2^-2")
        assertEquals(hits.getValue(2).score / 2, hits.getValue(4).score, 1e-9, "half as strong")
    }

    @Test
    fun `kinds, time and exclusions filter`() = fixture {
        memory(1, "Lave", "Bartok est tombé dans la lave.", at = ago(72))
        memory(2, "Lave", "Mimosa a nagé dans la lave.", at = ago(1))
        canon(3, CanonKind.THREAT, "Le Narrateur a menacé Bartok de le jeter dans la lave.", players = listOf(bartok))
        interaction(4, bartok, InteractionKind.PROVOCATION, -1, "a défié le Narrateur de le jeter dans la lave")
        chronicle(5, ChronicleLevel.SESSION, "La soirée de lave", "Tout le monde est tombé dans la lave.", ago(30), ago(26))

        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), search(tool("lave")).map { it.ref.id }.toSet())
        assertEquals(listOf(Ref(RefKind.CANON, 3)), search(tool("lave").copy(kinds = setOf(RefKind.CANON))).refs())
        assertEquals(emptyList(), search(tool("lave").copy(kinds = setOf(RefKind.NOTE))), "notes are never searched")
        val recent = search(tool("lave").copy(sinceMillis = ago(48).toEpochMilli())).map { it.ref.id }.toSet()
        assertEquals(setOf(2L, 3L, 4L, 5L), recent)
        val left = search(tool("lave").copy(exclude = setOf(memory(1), Ref(RefKind.CHRONICLE, 5)))).map { it.ref.id }.toSet()
        assertEquals(setOf(2L, 3L, 4L), left)
        assertEquals(2, search(tool("lave", limit = 2)).size)
        assertEquals(emptyList(), search(tool("lave", limit = 0)))
    }

    @Test
    fun `archived, merged and superseded rows only with includeClosed`() = fixture {
        memory(1, "Lave", "Bartok est tombé dans la lave.", archived = true)
        memory(2, "Lave", "Bartok est tombé dans la lave, encore.", mergedInto = 3, archived = true)
        memory(3, "Lave", "Bartok tombe souvent dans la lave.")
        interaction(4, bartok, InteractionKind.JOKE, 1, "a plaisanté sur la lave", archived = true)
        chronicle(5, ChronicleLevel.SEASON, "Saison de lave", "La lave partout.", ago(100), ago(2), supersededBy = 6)
        chronicle(6, ChronicleLevel.SEASON, "Saison de lave", "La lave partout, réécrite.", ago(100), ago(1))

        assertEquals(setOf(3L, 6L), search(tool("lave")).map { it.ref.id }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), search(tool("lave").copy(includeClosed = true)).map { it.ref.id }.toSet())
    }

    @Test
    fun `what an operator forgot stays out, even with includeClosed, unlike what the hygiene or a merge archived`() = fixture {
        val clement = Author(Source.OPERATOR, operator = "Clement")
        fun log(table: Audited, id: Long, author: Author, op: LogOp) = MemoryLog.append(connection, clock.instant(), author, table, id.toString(), op, null, null)
        memory(1, "Lave", "Bartok est tombé dans la lave.", archived = true)
        log(Audited.MEMORIES, 1, clement, LogOp.ARCHIVE)
        memory(2, "Lave", "Bartok est tombé dans la lave, encore.", archived = true)
        log(Audited.MEMORIES, 2, Author(Source.ENGINE), LogOp.ARCHIVE)
        memory(3, "Lave", "Bartok est tombé dans la lave, une fois de plus.", mergedInto = 4, archived = true)
        log(Audited.MEMORIES, 3, Author(Source.DREAMER, runId = 7), LogOp.MERGE)
        memory(4, "Lave", "Bartok tombe souvent dans la lave.")
        memory(5, "Lave", "Mimosa a vu Bartok tomber dans la lave.", archived = true)
        log(Audited.MEMORIES, 5, clement, LogOp.ARCHIVE)
        log(Audited.MEMORIES, 5, clement, LogOp.RESTORE)
        log(Audited.MEMORIES, 5, Author(Source.ENGINE), LogOp.ARCHIVE) // restored, then faded and archived by the hygiene
        canon(6, CanonKind.THREAT, "Le Narrateur a menacé Bartok de la lave.", players = listOf(bartok))
        connection.update("UPDATE canon SET archived = true WHERE id = 6")
        log(Audited.CANON, 6, clement, LogOp.ARCHIVE)
        interaction(7, bartok, InteractionKind.JOKE, 1, "a plaisanté sur la lave", archived = true)
        log(Audited.INTERACTIONS, 7, clement, LogOp.ARCHIVE)

        assertEquals(setOf(4L), search(tool("lave")).map { it.ref.id }.toSet())
        assertEquals(setOf(2L, 3L, 4L, 5L), search(tool("lave", limit = 20).copy(includeClosed = true)).map { it.ref.id }.toSet())
    }

    @Test
    fun `without any criterion a briefing gets the strongest rows, and the tool the latest`() = fixture {
        memory(1, "Vieux", "Un vieux souvenir.", importance = 10, at = ago(100))
        memory(2, "Récent", "Un souvenir récent.", importance = 1, at = ago(1))
        memory(3, "Fané", "Un souvenir fané.", importance = 10, at = ago(50), reinforcedPlaySeconds = -200 * 3_600)
        val briefing = RecallQuery(null, kinds = setOf(RefKind.MEMORY), limit = 2, purpose = RecallPurpose.BRIEFING)
        assertEquals(listOf(1L, 2L), search(briefing).map { it.ref.id }, "importance × strength first")
        assertEquals(listOf(2L, 3L), search(briefing.copy(purpose = RecallPurpose.TOOL)).map { it.ref.id }, "the latest first")
    }

    @Test
    fun `open canon is law, closed canon weighs less and only with includeClosed`() = fixture {
        canon(1, CanonKind.PROMISE, "Le Narrateur a promis des bottes à Mimosa.", players = listOf(mimosa))
        canon(2, CanonKind.PROMISE, "Le Narrateur a promis des bottes à Mimosa.", players = listOf(mimosa), status = CanonStatus.FULFILLED)
        canon(3, CanonKind.ARC, "Le Narrateur prépare une quête des bottes.", status = CanonStatus.PLANNED)
        playClock.world = 10_000 * 3_600L

        val open = search(tool("bottes"))
        assertEquals(setOf(1L, 3L), open.map { it.ref.id }.toSet())
        assertTrue(open.all { it.strength == 1.0 }, "whatever the clock")
        val all = search(tool("bottes").copy(includeClosed = true)).associateBy { it.ref.id }
        assertEquals(0.6, all.getValue(2).strength)
        assertEquals(all.getValue(1).score * 0.6, all.getValue(2).score, 1e-9)
    }

    @Test
    fun `without text, the rows of the query's players, tags and ids`() = fixture {
        memory(1, "Lave", "Bartok est tombé dans la lave.", players = listOf(bartok), tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
        memory(2, "Chute", "Mimosa est tombée d'une falaise.", players = listOf(mimosa), tags = listOf("death", "fall"))
        memory(3, "Base", "Mimosa a bâti une base.", players = listOf(mimosa), tags = listOf("build"))
        canon(4, CanonKind.NICKNAME, "Le Narrateur appelle Bartok « le Plongeur ».", players = listOf(bartok))
        interaction(5, bartok, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul »")
        interaction(6, mimosa, InteractionKind.COMPLIMENT, 2, "a remercié le Narrateur")

        val bartoks = search(tool(null, players = listOf(bartok))).associateBy { it.ref.id }
        assertEquals(setOf(1L, 4L, 5L), bartoks.keys)
        assertTrue(bartoks.values.all { it.relevance == 0.5 })

        val deaths = search(tool(null, players = listOf(bartok), tags = listOf("death"))).associate { it.ref.id to it.relevance }
        assertEquals(mapOf(1L to 0.75, 2L to 0.25, 4L to 0.5, 5L to 0.5), deaths)
        val insults = search(tool(null, tags = listOf("insult"))).associate { it.ref.id to it.relevance }
        assertEquals(mapOf(5L to 0.25), insults, "an interaction's kind is a tag")
        val ids = search(RecallQuery(null, entities = listOf("minecraft:lava"), purpose = RecallPurpose.TOOL)).refs()
        assertEquals(listOf(memory(1)), ids)
    }

    @Test
    fun `text and structure add up`() = fixture {
        memory(1, "Lave", "Mimosa a regardé la lave.", players = listOf(mimosa))
        memory(2, "Lave", "Bartok a regardé la lave.", players = listOf(bartok))
        memory(3, "Mort", "Bartok est mort.", players = listOf(bartok))
        val hits = search(tool("lave", players = listOf(bartok))).associate { it.ref.id to it.relevance }
        assertEquals(1.0 * 0.6 + 0.4 * 0.5, hits.getValue(2), 1e-9)
        assertEquals(0.4 * 0.5, hits.getValue(3), 1e-9, "no word in common, but the player")
        assertTrue(hits.getValue(1) < hits.getValue(2))
    }

    @Test
    fun `no criterion lists the latest`() = fixture {
        memory(1, "Vieux", "Un vieux souvenir.", importance = 10, at = ago(100))
        memory(2, "Récent", "Un souvenir récent.", importance = 1, at = ago(1))
        interaction(3, bartok, InteractionKind.JOKE, 1, "a plaisanté", at = ago(50))
        val hits = search(tool(null))
        assertEquals(listOf(2L, 3L, 1L), hits.map { it.ref.id })
        assertTrue(hits.all { it.relevance == 1.0 })
        assertEquals(listOf(2L, 3L, 1L), search(tool("le la les")).map { it.ref.id }, "stop words only: no criterion")
    }

    @Test
    fun `an interaction fades with its player's play, down to a floor`() = fixture {
        interaction(1, bartok, InteractionKind.INSULT, -2, "a insulté le Narrateur", playedSeconds = 1_000)
        playClock.players[bartok.uuid] = 1_000 + 100 * 3_600L
        assertEquals(0.5, search(tool("insulte")).single().strength, 1e-9)
        playClock.players[bartok.uuid] = 1_000 + 1_000 * 3_600L
        assertEquals(0.1, search(tool("insulte")).single().strength, 1e-9)
        playClock.world = 1_000_000L
        assertEquals(0.1, search(tool("insulte")).single().strength, 1e-9, "the world clock does not matter")
    }

    @Test
    fun `reminiscences keep what passes the thresholds and draw by squared score, the same for the same seed`() = fixture {
        memory(1, "Lave", "Bartok est tombé dans la lave.", players = listOf(bartok), tags = listOf("lava"), importance = 9)
        memory(2, "Lave", "Bartok a nagé dans la lave.", players = listOf(bartok), tags = listOf("lava"), importance = 6)
        memory(3, "Lave", "Bartok a regardé la lave.", players = listOf(bartok), importance = 2, reinforcedPlaySeconds = -200 * 3_600)
        memory(
            4, "Ciel", "Mimosa a longuement regardé le ciel étoilé au-dessus de la mer calme, puis la lave.",
            players = listOf(mimosa), importance = 2,
        )
        fun remind(seed: Long, limit: Int = 1) = search(
            RecallQuery("lave", players = listOf(bartok.uuid), tags = listOf("lava"), limit = limit, purpose = RecallPurpose.REMINISCENCE, seed = seed),
        )

        val everything = search(tool("lave", players = listOf(bartok), tags = listOf("lava"))).associateBy { it.ref.id }
        val eligible = everything.values.filter { it.score >= 0.35 && it.relevance >= 0.4 }.map { it.ref.id }.toSet()
        assertEquals(setOf(1L, 2L), eligible, "3 is too weak, 4 not relevant enough: ${everything.values}")

        val firsts = (0L until 400L).map { remind(it).single().ref.id }
        assertTrue(firsts.all { it in eligible })
        val best = firsts.count { it == 1L }
        val p1 = everything.getValue(1).score.let { it * it }
        val expected = p1 / (p1 + everything.getValue(2).score.let { it * it })
        assertTrue(best in 1 until 400, "the best wins often, not always: $best of 400")
        assertEquals(expected, best / 400.0, 0.08, "drawn in proportion to the squared score")
        assertEquals(remind(7, limit = 2).refs(), remind(7, limit = 2).refs(), "deterministic")
        assertEquals(setOf(1L, 2L), remind(7, limit = 5).map { it.ref.id }.toSet(), "drawn without replacement")
    }

    @Test
    fun `lines show each kind as recall writes it`() = fixture {
        val engine = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000e0e0e"), "<engine>")
        player(engine)
        memory(
            120, "Mort dans la lave du Nether", "Bartok est mort dans un lac de lave.", players = listOf(bartok, mimosa),
            importance = 7, at = ago(21 * 24),
        )
        memory(121, "Lave partout", "La lave a coulé sur le spawn.", at = ago(3))
        memory(122, "Lave", "Le joueur a vu de la lave.", players = listOf(engine, PlayerRef(UUID.fromString("12345678-0000-0000-0000-000000000000"), "?")))
        canon(
            45, CanonKind.PROMISE, "Le Narrateur a promis des bottes à Mimosa, contre de la lave.", quote = "je te rends tes bottes",
            players = listOf(mimosa), importance = 6, dueAt = now.plus(Duration.ofDays(1)),
        )
        canon(
            41, CanonKind.THREAT, "Le Narrateur a menacé Bartok d'une pluie de lave.", players = listOf(bartok),
            status = CanonStatus.FULFILLED, resolvedAt = ago(48), resolution = "la lave est tombée",
        )
        interaction(512, bartok, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul » près de la lave", quote = "t'es nul", at = ago(1))
        interaction(513, mimosa, InteractionKind.HELP, 2, "a sorti Bartok de la lave", at = ago(1))
        chronicle(88, ChronicleLevel.SESSION, "La nuit de lave", "Bartok insulte le ciel, la lave répond.", ago(24).plusSeconds(600), ago(21).plusSeconds(120))

        val lines = search(tool("lave", limit = 20).copy(includeClosed = true)).associate { it.ref.toString() to it.line }
        assertEquals("M-120 · 09-16 20:00 (3w ago) · Bartok, Mimosa · ★7 · Mort dans la lave du Nether: « Bartok est mort dans un lac de lave. »", lines["M-120"])
        assertEquals("M-121 · 10-07 17:00 (3h ago) · everyone · ★5 · Lave partout: « La lave a coulé sur le spawn. »", lines["M-121"])
        assertEquals("M-122 · 10-07 20:00 (0m ago) · ‹engine›, 12345678 · ★5 · Lave: « Le joueur a vu de la lave. »", lines["M-122"])
        assertEquals(
            "C-45 · promise → Mimosa · active, due 10-08 20:00 · ★6 · Le Narrateur a promis des bottes à Mimosa, contre de la lave. · said « je te rends tes bottes »",
            lines["C-45"],
        )
        assertEquals("C-41 · threat → Bartok · fulfilled 10-05 (la lave est tombée) · ★5 · Le Narrateur a menacé Bartok d'une pluie de lave.", lines["C-41"])
        assertEquals("I-512 · 10-07 19:00 · Bartok · insult -2 · a traité le Narrateur de « nul » près de la lave · « t'es nul »", lines["I-512"])
        assertEquals("I-513 · 10-07 19:00 · Mimosa · help +2 · a sorti Bartok de la lave", lines["I-513"])
        assertEquals("H-88 · session 10-06 20:10–23:02 · « La nuit de lave »: Bartok insulte le ciel, la lave répond.", lines["H-88"])
    }

    @Test
    fun `ago is short`() {
        assertEquals("0m ago", HybridRecall.ago(-5))
        assertEquals("59m ago", HybridRecall.ago(59 * 60_000L))
        assertEquals("47h ago", HybridRecall.ago(47 * 3_600_000L))
        assertEquals("13d ago", HybridRecall.ago(13 * 86_400_000L))
        assertEquals("8w ago", HybridRecall.ago(62 * 86_400_000L))
        assertEquals("23mo ago", HybridRecall.ago(700 * 86_400_000L))
        assertEquals("2y ago", HybridRecall.ago(800 * 86_400_000L))
    }

    @Test
    fun `search reads through the Journal`() {
        SearchFixture().use { fixture ->
            fixture.player(bartok)
            fixture.memory(1, "Lave", "Bartok est tombé dans la lave.", players = listOf(bartok))
            val journal = DuckJournal.open(fixture.db, JournalConfig(), fixture.clock)
            try {
                val recall = HybridRecall(journal, fixture.index, fixture.playClock, fixture.clock)
                val hits = runBlocking { recall.search(tool("lave")) }
                assertEquals(listOf(memory(1)), hits.refs())
            } finally {
                journal.close(Duration.ofSeconds(10))
            }
        }
    }
}
