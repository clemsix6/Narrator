package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.memory.BriefingFixture
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.style.StyleSamples.START
import io.github.clemsix6.narrator2.style.StyleSamples.labels
import io.github.clemsix6.narrator2.style.StyleSamples.watch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The watch of the style (M6 §3.4, §3.6): openers, phrases and crutch words at rest, registers, the `Style:` line, the seed. */
class StyleWatchTest {

    // ── Openers ─────────────────────────────────────────────────────────────────────────────────────

    /** The M4 acceptance (essay): `<n> secondes` opened five lines, the latest « Dix-sept secondes ». */
    @Test
    fun `the openers of the M4 acceptance rest, neither its titles nor its short lines`() {
        val watch = watch(StyleSamples.m4(), StyleSamples.M4_PLAYERS)
        val secondes = RestingOpener("Dix-sept secondes", 5, listOf("Vingt secondes", "Douze secondes", "Onze secondes", "Seize secondes"))
        assertEquals(secondes, watch.restingOpener("Vingt-deux secondes ! Mimosa gagne la course du désert."))
        assertEquals(listOf(secondes), watch.atRest().openers, "the one opener at rest of the evening")
        assertNull(watch.restingOpener("Bonsoir, mortels ! La nuit tombe sur le désert."), "three lines only")
        assertNull(watch.restingOpener("Vingt secondes ! Bravo."), "a line under 25 characters never rests")
        assertNull(watch.restingOpener("Gaspard, Firmin : vous vous chamaillez encore et encore."), "`<nom> vous` is no tic")
    }

    @Test
    fun `the window frees an opener, line after line`() {
        val resting = List(4) { i -> "Vingt secondes ! Le record numéro $i tombe à nouveau." }
        val others = List(StyleWatchImpl.WINDOW - 4) { i -> "${word(i)} parle enfin, et le désert l'écoute." }
        val watch = watch(resting + others)
        val line = "Trente secondes ! Encore un record pour personne."
        assertEquals(4, watch.restingOpener(line)?.count, "four lines of the window open so")
        watch.said(100_000, START.plusSeconds(10_000), "${word(999)} prend la parole, pour changer.")
        assertNull(watch.restingOpener(line), "the oldest left the window: three are not enough")
    }

    @Test
    fun `names learned later normalize the lines held`() {
        val greetings = listOf("Zoé", "Max", "Léa", "Tom").map { "Bonsoir, $it ! Le serveur t'attendait, hélas." }
        val watch = watch(greetings)
        val ugo = "Bonsoir, Ugo ! Tu arrives après la bataille."
        assertNull(watch.restingOpener(ugo), "four different openers while the names are unknown")
        watch.named(listOf("Zoé", "Max", "Léa", "Tom", "Ugo"))
        assertEquals(RestingOpener("Bonsoir, Tom", 4, listOf("Bonsoir, Léa", "Bonsoir, Max", "Bonsoir, Zoé")), watch.restingOpener(ugo))
    }

    @Test
    fun `a line already held is not taken twice`() {
        val text = "Vingt secondes ! Le record tombe encore une fois."
        val watch = watch(List(3) { text })
        watch.said(3, START, text)
        watch.seed(StyleWatchImpl.Seed(listOf(StyleWatchImpl.SeedLine(2, START, text)), emptySet(), emptyList()))
        assertNull(watch.restingOpener(text), "three lines, not four nor five")
        watch.said(4, START, text)
        assertEquals(4, watch.restingOpener(text)?.count)
    }

    // ── Crutch words and candidates ─────────────────────────────────────────────────────────────────

    @Test
    fun `a crutch word rests at three marks of the latest thirty, and stays while two`() {
        val watch = StyleWatchImpl()
        watch.labeled(labels(1, crutches = listOf("céleste")))
        watch.labeled(labels(2, crutches = listOf("Céleste", "guichet")))
        assertEquals(emptyList(), watch.atRest().crutches, "two marks are not enough")
        watch.labeled(labels(3, crutches = listOf("céleste", "guichet")))
        assertEquals(listOf("céleste"), watch.atRest().crutches)
        (4L..31L).forEach { watch.labeled(labels(it)) }
        assertEquals(listOf("céleste"), watch.atRest().crutches, "two marks left in the latest thirty: still at rest")
        watch.labeled(labels(32))
        assertEquals(emptyList(), watch.atRest().crutches, "one mark left: free")
    }

    @Test
    fun `the crutch candidates are the frequent content words, neither names nor numbers`() {
        val watch = watch(
            listOf(
                "Bartok, le guichet céleste est fermé pour trois jours.",
                "Bartok, le tampon céleste refuse ta pomme.",
                "Mimosa, trois pommes ne font pas un guichet céleste.",
                "Le guichet attend, Mimosa : trois minutes de silence.",
                "La lave attend, la lave patiente, la lave gagne.",
            ),
            names = listOf("Bartok", "Mimosa"),
        )
        // « guichet » and « céleste » in 3 lines, « attend » in 2, then the words of 1 line, the latest first; « lave » is
        // too short, « trois » a number, Bartok and Mimosa names.
        assertEquals(listOf("guichet", "céleste", "attend", "gagne", "patiente", "minutes", "silence", "pommes"), watch.crutchCandidates())
    }

    @Test
    fun `« céleste » is a candidate of the M4 acceptance`() {
        val candidates = watch(StyleSamples.m4(), StyleSamples.M4_PLAYERS).crutchCandidates()
        assertTrue("céleste" in candidates, "$candidates")
        assertTrue(candidates.none { it.replaceFirstChar(Char::uppercase) in StyleSamples.M4_PLAYERS }, "$candidates")
    }

    // ── Lines and registers ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the recent lines are those said before a line, oldest first`() {
        val watch = watch(listOf("Un.", "Deux.", "Trois.", "Quatre."))
        assertEquals(listOf("Deux.", "Trois."), watch.recentLines(4, 2))
        assertEquals(listOf("Un.", "Deux.", "Trois.", "Quatre."), watch.recentLines(99, 5))
        assertEquals(emptyList(), watch.recentLines(1, 5))
    }

    @Test
    fun `the registers of the latest lines, and the least used of the voice`() {
        val watch = StyleWatchImpl()
        listOf(ComicRegister.COURTROOM, ComicRegister.DIVINE_BUREAUCRACY, ComicRegister.PLAIN, ComicRegister.NATURE_DOCUMENTARY)
            .forEachIndexed { i, register -> watch.labeled(labels(10L + i, register)) }
        watch.labeled(labels(5, ComicRegister.GAME_SHOW))
        assertEquals(listOf(ComicRegister.NATURE_DOCUMENTARY, ComicRegister.PLAIN, ComicRegister.DIVINE_BUREAUCRACY), watch.lastRegisters(3), "by line, not by arrival")
        assertEquals(
            listOf(ComicRegister.SPORTS_COMMENTARY, ComicRegister.WEATHER_REPORT, ComicRegister.SILLY_PROPHECY, ComicRegister.FOOD_CRITIC),
            watch.leastUsedRegisters(4),
            "unused first, in the voice's order",
        )
        watch.labeled(labels(10, ComicRegister.SPORTS_COMMENTARY))
        assertEquals(ComicRegister.SPORTS_COMMENTARY, watch.lastRegisters(5)[3], "a line reviewed again keeps its latest labels")
        assertEquals(5, watch.lastRegisters(9).size)
    }

    // ── The Style line ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Style line names what rests, and the registers when one holds four of the last eight`() {
        val watch = watch(
            listOf(
                "Seize secondes ! Le désert applaudit poliment.",
                "Onze secondes : la pioche tremble encore.",
                "Douze secondes, et pas une de plus.",
                "Seize secondes ! Personne ne bat ce record.",
                "Le service client céleste vous remercie de votre patience.",
                "Appelez le service client céleste, il ne répond jamais.",
                "Le service client céleste a perdu votre dossier.",
            ),
        )
        assertNull(watch.situationLine(0)?.takeIf { "your last" in it }, "no register yet")
        val registers = List(5) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.SPORTS_COMMENTARY } + ComicRegister.COURTROOM
        registers.forEachIndexed { i, register -> watch.labeled(labels(i + 1L, register, crutches = if (i < 3) listOf("céleste") else emptyList())) }
        assertEquals(
            "Style: openers at rest « Seize secondes » ×4 · phrases « service client céleste » ×3 · crutch words « céleste » ×3 (of your last 8 lines) · " +
                "your last 8 lines: divine_bureaucracy 5, sports_commentary 2, courtroom 1; least used lately: nature_documentary, weather_report, silly_prophecy",
            watch.situationLine(0),
        )
        assertEquals(AtRest(watch.atRest().openers, listOf("service client céleste"), listOf("céleste")), watch.atRest())
        assertEquals(RestingOpener("Seize secondes", 4, listOf("Douze secondes", "Onze secondes")), watch.atRest().openers.single())
    }

    @Test
    fun `the Style line says nothing when nothing rests, and no register under four of eight`() {
        assertNull(StyleWatchImpl().situationLine(0))
        val watch = watch(listOf("Une ligne parmi d'autres, sans tic aucun."))
        listOf(ComicRegister.COURTROOM, ComicRegister.COURTROOM, ComicRegister.COURTROOM, ComicRegister.GAME_SHOW, ComicRegister.PLAIN, ComicRegister.PLAIN, ComicRegister.PLAIN, ComicRegister.PLAIN)
            .forEachIndexed { i, register -> watch.labeled(labels(i + 1L, register)) }
        assertNull(watch.situationLine(0), "four plain lines are no register worn out")
    }

    @Test
    fun `the Style line holds 400 characters, the last items counted`() {
        val texts = (0 until 40).flatMap { i -> List(4) { "${word(i)} dit ceci : une phrase de plus pour la forme." } }
        val line = watch(texts).situationLine(0)!!
        assertTrue(line.length <= StyleWatchImpl.SITUATION_MAX, "${line.length}: $line")
        assertTrue(Regex(""" \(\+\d+ more\)$""").containsMatchIn(line), line)
        assertTrue(line.startsWith("Style: openers at rest « "), line)
    }

    // ── The seed ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the seed reads the Director's lines, the names and nicknames, and the latest labels`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            val writer = f.direct.db.writer
            writer.update("INSERT INTO player_names (player_uuid, name, first_seen) VALUES (?, 'Pistache', ?)", UUID(1, 1), START)
            writer.update(
                "INSERT INTO canon (id, kind, created_at, statement, players, importance, status, source, evidence_events, evidence_tools) " +
                    "VALUES (77, 'nickname', ?, 'Le Narrateur surnomme Pistache « Client Mécontent ».', []::UUID[], 5, 'active', 'director', []::BIGINT[], []::BIGINT[])",
                START,
            )
            val texts = listOf(
                "Client Mécontent, encore une réclamation classée.", "Pistache, encore une réclamation rejetée.", "Opaline, encore une réclamation en attente.",
            )
            texts.forEachIndexed { i, text ->
                writer.update("INSERT INTO chat (id, ts, channel, text, turn_id) VALUES (?, ?, 'narrator', ?, ?)", 100L + i, START.plusSeconds(i.toLong()), text, 7L + i)
            }
            writer.update("INSERT INTO chat (id, ts, channel, text) VALUES (110, ?, 'narrator', 'Une loi parle, sans tour.')", START)
            writer.update("INSERT INTO chat (id, ts, channel, text, operator) VALUES (111, ?, 'operator', 'Un opérateur parle.', 'clement')", START)
            for ((ts, register) in listOf(1L to "courtroom", 2L to "game_show")) {
                writer.update(
                    "INSERT INTO line_labels VALUES (100, ?, 'reflex:claude-haiku-5-5', ?, 0.1, 0.0, 0.2, 0.0, 0.0, ['céleste'], 'une raison', 900)",
                    START.plusSeconds(ts), register,
                )
            }

            val seed = f.journal.read(5_000) { StyleWatchImpl.read(it) }
            assertEquals(texts, seed.lines.map { it.text }, "the Director's lines only, oldest first")
            assertEquals(setOf("Pistache", "Client Mécontent"), seed.names)
            val label = seed.labels.single()
            assertEquals(listOf(100L, ComicRegister.GAME_SHOW, texts[0], listOf("céleste")), listOf(label.eventId, label.register, label.text, label.crutches), "the latest review of the line")

            val watch = StyleWatchImpl()
            watch.seed(seed)
            watch.said(103, START.plusSeconds(3), "Mimosa, encore une réclamation perdue.")
            assertEquals(listOf(ComicRegister.GAME_SHOW), watch.lastRegisters(5))
            assertEquals(texts, watch.recentLines(103, 5))
            // « Client Mécontent » and « Pistache » are names; « Opaline » and « Mimosa » are not known.
            watch.said(104, START.plusSeconds(4), "Pistache, encore une réclamation au guichet.")
            assertNull(watch.restingOpener("Mimosa, encore une réclamation à traiter."), "three `<nom> encore` only")
            watch.said(105, START.plusSeconds(5), "Client Mécontent, encore une plainte reçue.")
            assertEquals(RestingOpener("Client Mécontent, encore", 4, listOf("Pistache, encore")), watch.restingOpener("Pistache, encore une fois, non merci."))
        }
    }

    /** A made-up word of its own for each [i]: `Zzaaa`, `Zzaba`… */
    private fun word(i: Int): String = "Zz" + ('a' + i / 26 % 26) + ('a' + i % 26) + ('a' + i / 676)
}
