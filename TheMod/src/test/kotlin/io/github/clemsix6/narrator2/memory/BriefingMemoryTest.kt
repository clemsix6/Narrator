package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.director.Briefing
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.NOW
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.bartok
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.kelvin
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.mimosa
import io.github.clemsix6.narrator2.memory.SheetSamples.filler
import io.github.clemsix6.narrator2.testing.MemorySamples
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** M3 §11.1, §15.1: the memory's six sections of a chapter's briefing (M3-13). */
class BriefingMemoryTest {
    // ── Order and forms ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the six sections come in the briefing's order, each with its reduced form`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val sections = f.briefing.sections(f.chapter(), f.now)
            assertEquals(BriefingSection.TAGS, sections.map { it.tag })
            val byTag = sections.associateBy { it.tag }
            for (tag in BriefingSection.NEVER_REDUCED) assertEquals(byTag.getValue(tag).lines, byTag.getValue(tag).reduced, tag)
            assertNull(byTag.getValue("memories").reduced, "memories leave a reduced briefing")

            val chronicle = byTag.getValue("chronicle")
            assertTrue(chronicle.lines.any { it.startsWith("Session ") })
            assertEquals(listOf("Saga", "Season"), chronicle.reduced!!.map { it.substringBefore(' ') }, "the reduced chronicle keeps the saga and the season")

            val sheets = byTag.getValue("sheets")
            assertEquals(2, sheets.reduced!!.size, "one line per player online")
            assertTrue(sheets.reduced!![0].startsWith("Bartok · « le Plongeur » · 61h played"), sheets.reduced!![0])

            val notes = byTag.getValue("notes")
            assertTrue(notes.lines.last().startsWith("Style: overused lately: « Ah Bartok encore » ×4"), notes.lines.last())
            assertEquals(notes.lines.dropLast(1), notes.reduced, "the reduced notes leave the tics out")

            assertEquals("version=\"12\" written=\"10-03 04:00\"", byTag.getValue("grimoire").attributes)
            assertEquals(BriefingSamples.GRIMOIRE.lines().filter { it.isNotBlank() }, byTag.getValue("grimoire").lines)
        }
    }

    @Test
    fun `the chronicle gives the saga, the season, the last two sessions and the chapters since`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val lines = f.sections().getValue("chronicle").lines
            assertEquals(5, lines.size, lines.joinToString("\n"))
            assertTrue(lines[0].startsWith("Saga (H-3, 09-12–10-01) « Les premiers temps »: Bartok, Mimosa et clemsix6 arrivent"), lines[0])
            assertTrue(lines[1].startsWith("Season 1 so far (H-40, since 09-20) « La guerre des coffres »: "), lines[1])
            assertTrue(lines[2].startsWith("Session 10-01 19:00–21:52 (H-88) « La nuit des slimes »: "), lines[2])
            assertTrue(lines[3].startsWith("Session 10-02 20:10–23:05 (H-89) « Le pont de Mimosa »: "), lines[3])
            assertEquals("Chapters not yet in a session: H-91 10-03 18:00 « Le retour du Warden »", lines[4])
        }
    }

    @Test
    fun `the canon puts the open commitments first by due time, then the standing canon by kind`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val lines = f.sections().getValue("canon").lines
            assertEquals(
                "C-45 promise → Mimosa, due 21:00 (in 1h00m): « je te rends tes bottes demain, promis » — " +
                    "Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer le lendemain.",
                lines[0],
            )
            assertEquals(
                "C-50 challenge → all, due 10-04 05:00 (in 9h00m): « Le premier qui me rapporte un œuf de tortue avant l'aube aura ma bénédiction. » — " +
                    "Le Narrateur a défié tous les joueurs de rapporter un œuf de tortue avant l'aube. · stakes: une bénédiction pour le gagnant, " +
                    "une nuit de lenteur pour les autres",
                lines[1],
            )
            assertTrue(lines[2].startsWith("C-63 promise → Bartok, Mimosa, due 10-04 21:00 (in 1d1h): « Finissez ce pont"), lines[2])
            assertTrue(lines[3].startsWith("C-61 threat → Bartok: « Encore une insulte et il pleuvra des slimes. » — "), lines[3])
            assertEquals(
                listOf("Laws", "Names", "Places", "Gags", "Arcs planned", "Lore", "Opinions"),
                lines.drop(4).map { it.substringBefore(':') },
            )
            assertTrue("Names: Mimosa title « Architecte du Ciel » (C-39) · Bartok « le Plongeur » (C-12, 3 uses)" in lines, lines.joinToString("\n"))
            assertTrue("Places: C-56 « la Dent du Juge » (overworld 120 90 -400)" in lines)
            assertTrue(lines.any { it.startsWith("Gags: C-27 Le Narrateur commente chaque chute de Bartok comme un plongeon olympique. (worn, 5 uses, last 10-01)") })
        }
    }

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every section stays within its budget when filled to the maximum, in both forms`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.maximal(f)
            val chapter = f.chapter()
            val sections = f.briefing.sections(chapter, f.now).associateBy { it.tag }
            val measured = StringBuilder("memory sections at their maximum (characters / budget):")
            for (tag in BriefingSection.TAGS) {
                val section = sections.getValue(tag)
                val budget = Briefing.MEMORY_BUDGETS.getValue(tag)
                val full = BriefText.length(section.lines)
                val reduced = section.reduced?.let { BriefText.length(it) }
                measured.append("\n  $tag: $full${reduced?.let { " (reduced $it)" } ?: ""} / $budget")
                assertTrue(full <= budget, "$tag: $full > $budget")
                if (reduced != null) assertTrue(reduced <= budget, "$tag reduced: $reduced > $budget")
                assertTrue(section.lines.none { '<' in it || '>' in it }, "$tag: nothing unescaped")
                assertTrue(full > budget * 0.85, "$tag fills its budget: $full of $budget")
            }
            println(measured)

            // The Grimoire is never cut: it holds its 10,000 characters.
            assertEquals(BriefingSamples.maxGrimoire.replace('<', '‹').replace('>', '›').lines(), sections.getValue("grimoire").lines)

            // The canon: the soonest commitment first, its quote whole, then a count of what is left out.
            val canon = sections.getValue("canon").lines
            assertTrue(canon[0].startsWith("C-1001 challenge → "), canon[0])
            assertTrue(canon[0].contains("« " + filler(MemoryLimits.CANON_QUOTE, "Q1").replace('<', '‹').replace('>', '›').replace('«', '‹').replace('»', '›') + " »"), "the quote stays whole")
            val more = Regex("""\(\+(\d+) more: recall kinds=canon\)""").matchEntire(canon.last())
            assertNotNull(more, canon.last())
            val shownCanon = chapter.shownRefs.count { it.kind == RefKind.CANON && it.id > 1_000 }
            assertEquals(BriefingSamples.MAX_CANON, shownCanon + more!!.groupValues[1].toInt(), "every open entry is shown or counted")

            // Sheets: condensed while they fit (six at most), one line for the others.
            val sheets = sections.getValue("sheets")
            val headers = sheets.lines.count { it.contains(" · no title · ") || it.contains(" · title « ") }
            assertTrue(headers in 5..MemoryBriefing.MAX_CONDENSED, "condensed sheets: $headers")
            assertEquals(8, sheets.reduced!!.size)
            assertEquals(8, chapter.sheetsShownAt.size)

            // Memories: twelve, each cut inside its quote.
            val memories = sections.getValue("memories").lines
            assertEquals(MemoryBriefing.MEMORIES, memories.size)
            assertTrue(memories.all { it.startsWith("M-") && it.endsWith("… »") }, memories.first())

            // Notes: as many as fit, a count of the others, the tics last.
            val notes = sections.getValue("notes").lines
            assertTrue(notes.any { Regex("""\(\+\d+ more open notes\)""").matches(it) }, notes.joinToString("\n"))
            assertTrue(notes.last().startsWith("Style: overused lately: "), notes.last())
        }
    }

    @Test
    fun `six condensed sheets fit when each is at its own budget`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            f.online = (1..6).map { PlayerRef(UUID(7, it.toLong()), "Player_$it") }
            f.online.forEach { f.sheets.sheets[it.uuid] = SheetSamples.reference.copy(player = it) }
            val sheets = f.sections().getValue("sheets")
            assertEquals(6, sheets.lines.count { it.startsWith("Player_") && it.contains(" sessions · ") }, sheets.lines.joinToString("\n"))
            assertTrue(BriefText.length(sheets.lines) <= Briefing.MEMORY_BUDGETS.getValue("sheets"))
        }
    }

    // ── Failures and empty sources ──────────────────────────────────────────────────────────────────

    @Test
    fun `a source that fails says so in its section, and the others are written`() = runBlocking<Unit> {
        BriefingFixture(DisabledJournal("the Journal is down")).use { f ->
            BriefingSamples.evening(f)
            f.sheets.failing += mimosa.uuid
            val locked = object : MemoryStore by f.store {
                override suspend fun canon(filter: CanonFilter): List<CanonEntry> = throw IllegalStateException("the canon table is locked")
            }
            val briefing = MemoryBriefing(locked, f.recall, f.sheets, f.journal, { f.online }, BriefingFixture.UTC)
            val chapter = f.chapter()
            val sections = briefing.sections(chapter, f.now).associateBy { it.tag }
            assertEquals(listOf("(unavailable: the canon table is locked)"), sections.getValue("canon").lines)
            assertEquals(listOf("(unavailable: the canon table is locked)"), sections.getValue("canon").reduced)

            val sheets = sections.getValue("sheets").lines
            assertTrue(sheets.first().startsWith("Bartok (formerly Bartok_) · « le Plongeur » (C-12)"), "Bartok's sheet is there")
            assertTrue("Mimosa: (sheet unavailable: the sheet of ${mimosa.uuid} is locked)" in sheets, sheets.joinToString("\n"))
            assertEquals(setOf(bartok.uuid), chapter.sheetsShownAt.keys, "only the sheet shown counts")

            assertEquals("Style: (unavailable: the Journal is unavailable: the Journal is down)", sections.getValue("notes").lines.last())
            assertTrue(sections.getValue("notes").lines.first().startsWith("N-9 coherence: "))
            assertTrue(sections.getValue("grimoire").lines.first().startsWith("## Players"))
            assertTrue(sections.getValue("memories").lines.first().startsWith("M-120 "))
        }
    }

    @Test
    fun `a failing search or store read leaves the other sections whole`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val broken = object : Recall {
                override suspend fun search(query: RecallQuery): List<RecallHit> = throw IllegalStateException("the index is rebuilding")
            }
            val down = object : MemoryStore by f.store {
                override suspend fun doc(key: String, version: Int?): MemoryDoc? = throw IllegalStateException("read timed out")

                override suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext) = throw IllegalStateException("write queue full")
            }
            val chapter = f.chapter()
            val sections = MemoryBriefing(down, broken, f.sheets, f.journal, { f.online }, BriefingFixture.UTC).sections(chapter, f.now).associateBy { it.tag }
            assertEquals(listOf("(unavailable: read timed out)"), sections.getValue("grimoire").lines)
            assertEquals(listOf("(unavailable: the index is rebuilding)"), sections.getValue("memories").lines)
            assertNull(sections.getValue("memories").reduced)
            assertTrue(sections.getValue("canon").lines.first().startsWith("C-45 promise"))
            assertTrue(Ref(RefKind.CANON, 45) in chapter.shownRefs, "the chapter knows what was shown, even when recalls cannot be written")
        }
    }

    @Test
    fun `an empty memory says so in every section`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            val sections = f.sections()
            assertEquals(listOf("(no grimoire yet: the Narrator has not dreamed yet)"), sections.getValue("grimoire").lines)
            assertEquals(listOf("(no chronicle yet: the Dreamer writes it in a deep dream, the chapters at each nap)"), sections.getValue("chronicle").lines)
            assertEquals(listOf("(no canon yet: nothing promised, decided nor named)"), sections.getValue("canon").lines)
            assertEquals(listOf("(nobody online)"), sections.getValue("sheets").lines)
            assertEquals(listOf("(no memory yet)"), sections.getValue("memories").lines)
            assertEquals(listOf("(no open note)"), sections.getValue("notes").lines)

            f.online = listOf(kelvin)
            f.put(MemorySamples.gag)
            val again = f.sections()
            assertEquals(listOf("(no memory yet about the players online)"), again.getValue("memories").lines)
            assertEquals(listOf("Kelvin: (no sheet: this player is not in the Journal yet)"), again.getValue("sheets").lines)
            assertEquals("(no open commitment)", again.getValue("canon").lines.first())
        }
    }

    // ── What the briefing shows ─────────────────────────────────────────────────────────────────────

    @Test
    fun `what the briefing shows goes into the chapter and gets a briefing recall`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val chapter = f.chapter()
            f.briefing.sections(chapter, f.now)
            val shown = chapter.shownRefs.map { it.toString() }.toSet()
            for (ref in listOf("C-45", "C-50", "C-25", "C-12", "C-27", "H-3", "H-40", "H-88", "H-89", "H-91", "M-120", "M-77", "N-9", "N-12", "N-14", "I-512", "I-505")) {
                assertTrue(ref in shown, "$ref shown: $shown")
            }
            assertFalse("M-130" in shown, "a ref the Grimoire only mentions was not shown")
            assertEquals(chapter.shownRefs, f.store.recalls.map { it.first }.toSet())
            assertTrue(f.store.recalls.all { it.second == RecallChannel.BRIEFING && it.third == RecallContext("director-c14", null, null) })
            assertEquals(mapOf(bartok.uuid to f.now, mimosa.uuid to f.now), chapter.sheetsShownAt)
            assertEquals(setOf("markNote N-9 by engine", "markNote N-12 by engine", "markNote N-14 by engine"), f.store.writes.toSet())
        }
    }

    @Test
    fun `a note is shown in two briefings, a dream hint once and only for a player online`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            f.note(15, NoteKind.DREAM_HINT, "Kelvin s'est noyé quatre fois cette semaine.", listOf(kelvin))
            fun notes(sections: Map<String, BriefingSection>) = sections.getValue("notes").lines.map { it.substringBefore(':') }

            val first = notes(f.sections(f.chapter("director-c14")))
            assertEquals(listOf("N-9 coherence", "N-12 expired", "N-14 dream_hint for Bartok (once)", "Style"), first)
            assertEquals(listOf("N-9 coherence", "N-12 expired", "Style"), notes(f.sections(f.chapter("director-c15"))))
            assertEquals(listOf("(no open note)", "Style"), notes(f.sections(f.chapter("director-c16"))))
            assertEquals(2, (f.store.rows.getValue(Ref(RefKind.NOTE, 9)) as Note).shownCount)
            assertEquals(NoteStatus.OPEN, (f.store.rows.getValue(Ref(RefKind.NOTE, 15)) as Note).status, "Kelvin's hint waits for his join")
        }
    }

    @Test
    fun `a briefing built again after a refused request shows the same notes without marking them twice`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val chapter = f.chapter()
            val before = f.briefing.sections(chapter, f.now).first { it.tag == "notes" }.lines
            chapter.shownRefs.clear() // the Director puts the chapter back as it was before the briefing
            chapter.sheetsShownAt.clear()
            val again = f.briefing.sections(chapter, f.now).first { it.tag == "notes" }.lines
            assertEquals(before, again)
            assertEquals(3, f.store.writes.size, "each note marked once: ${f.store.writes}")
            assertEquals(1, (f.store.rows.getValue(Ref(RefKind.NOTE, 14)) as Note).shownCount)
            assertEquals(2, chapter.sheetsShownAt.size)
        }
    }

    @Test
    fun `memories search the strongest about the players online`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            f.sections()
            val query = f.recall.queries.single()
            assertEquals(RecallPurpose.BRIEFING, query.purpose)
            assertEquals(listOf(bartok.uuid, mimosa.uuid), query.players)
            assertEquals(setOf(RefKind.MEMORY), query.kinds)
            assertEquals(12, query.limit)
            assertNull(query.text)
        }
    }
}

/** The data the briefing tests read: [evening], a realistic evening; [maximal], every source at its cap. */
internal object BriefingSamples {
    private fun at(text: String): Instant = Instant.parse(text)

    private fun c(id: Long) = Ref(RefKind.CANON, id)

    val GRIMOIRE = """
        ## Players
        - Bartok : provocateur qui revient quémander ; ses châtiments font rire tout le monde, lui compris ; les téléportations l'agacent vraiment. Faveur basse depuis trois sessions (M-130).
        - Mimosa : bâtisseuse patiente ; prie peu, mais toujours poliment.
        ## Running gags
        - « le Plongeur » (C-12) : usé (5 usages, plus de réaction depuis le 10-01) ; à laisser reposer.
        ## Arcs
        - C-60 « Le procès des parieurs » : prévu ; à lancer quand Bartok aura perdu un nouveau pari.
        ## What works
        - Les formulaires administratifs font rire Mimosa ; les prophéties écrites dans un livre aussi.
        ## What falls flat
        - Les commentaires sportifs sur les morts : plus de réaction depuis une semaine.
        ## Plans
        - Prochaine session de Mimosa : un défi à l'arc (elle vient d'enchanter le sien).
        ## Watch out
        - Kelvin : 4 noyades cette semaine et il parle peu ; taquiner léger, pas de châtiment gratuit.
    """.trimIndent()

    /** Bartok and Mimosa online on 10-03 at 20:00, after three weeks of play. */
    fun evening(f: BriefingFixture) {
        f.grimoire(GRIMOIRE, at = at("2026-10-03T04:00:00Z"))
        f.chronicle(
            3, ChronicleLevel.SAGA, at("2026-09-12T18:00:00Z"), at("2026-10-01T23:05:00Z"), "Les premiers temps",
            "Bartok, Mimosa et clemsix6 arrivent sur un monde vierge. Bartok découvre le Nether le troisième jour et y laisse son équipement ; " +
                "Mimosa bâtit le premier village, que Bartok pille deux fois avant d'être réduit à la taille d'un lapin.",
        )
        f.chronicle(
            40, ChronicleLevel.SEASON, at("2026-09-20T19:00:00Z"), at("2026-10-02T23:05:00Z"), "La guerre des coffres",
            "Bartok et Mimosa se disputent chaque coffre des chambres d'épreuve ; le Narrateur arbitre à coups de défis et de lois.", open = true,
        )
        f.chronicle(
            88, ChronicleLevel.SESSION, at("2026-10-01T19:00:00Z"), at("2026-10-01T21:52:00Z"), "La nuit des slimes",
            "Bartok insulte le ciel, le ciel répond par une pluie de slimes ; Mimosa finit son pont.",
        )
        f.chronicle(
            89, ChronicleLevel.SESSION, at("2026-10-02T20:10:00Z"), at("2026-10-02T23:05:00Z"), "Le pont de Mimosa",
            "Mimosa achève le pont suspendu au-dessus du ravin et reçoit le titre d'Architecte du Ciel ; Bartok gagne la course au Nether.",
        )
        f.chronicle(
            91, ChronicleLevel.CHAPTER, at("2026-10-03T18:00:00Z"), at("2026-10-03T19:30:00Z"), "Le retour du Warden",
            "Bartok meurt trois fois contre le Warden et insulte le Narrateur, qui lui inflige lenteur II.",
        )

        f.put(MemorySamples.canon.copy(dueAt = at("2026-10-03T21:00:00Z")))
        f.put(
            SheetSamples.canon(
                50, CanonKind.CHALLENGE, "Le Narrateur a défié tous les joueurs de rapporter un œuf de tortue avant l'aube.",
                quote = "Le premier qui me rapporte un œuf de tortue avant l'aube aura ma bénédiction.", players = emptyList(),
                due = at("2026-10-04T05:00:00Z"), stakes = "une bénédiction pour le gagnant, une nuit de lenteur pour les autres",
            ),
        )
        SheetSamples.reference.openCanon.filter { it.kind != CanonKind.RUNNING_GAG }.forEach(f::put)
        f.put(SheetSamples.canon(25, CanonKind.LAW, "Quiconque casse la construction d'un autre joueur est réduit à la taille d'un lapin.", players = emptyList()))
        f.put(SheetSamples.reference.nickname!!.copy(uses = 3, importance = 5))
        f.put(
            SheetSamples.canon(39, CanonKind.TITLE, "Mimosa porte le titre « Architecte du Ciel » depuis son pont suspendu.", players = listOf(mimosa)),
        )
        f.put(
            SheetSamples.canon(56, CanonKind.PLACE, "Le pic rocheux au nord du village s'appelle « la Dent du Juge ».", players = emptyList())
                .copy(place = Pos("minecraft:overworld", 120, 90, -400)),
        )
        f.put(MemorySamples.gag)
        f.put(
            SheetSamples.canon(60, CanonKind.ARC, "Le procès des parieurs : le Narrateur jugera ceux qui ont parié contre lui.", players = emptyList(), status = CanonStatus.PLANNED),
        )
        f.put(SheetSamples.canon(70, CanonKind.LORE, "Selon le Narrateur, le Nether est la cave à vin des dieux.", players = emptyList()))
        f.put(
            SheetSamples.canon(71, CanonKind.OPINION, "Le Narrateur juge que les ponts de Mimosa sont les seules constructions dignes de ce monde.", players = listOf(mimosa)),
        )

        f.online = listOf(bartok, mimosa)
        f.sheets.sheets[bartok.uuid] = SheetSamples.reference
        f.sheets.sheets[mimosa.uuid] = BriefingFixture.mimosaSheet

        f.recall.hits += f.hit(
            120, "M-120 · 10-01 21:40 (1d ago) · Bartok, Mimosa · ★7 · Mort dans la lave du Nether: « Bartok est mort dans un lac de lave du Nether " +
                "en creusant vers des débris antiques, devant Mimosa. »",
        )
        f.recall.hits += f.hit(
            77, "M-77 · 09-20 21:30 (1w ago) · Bartok, Mimosa · ★8 · Le sauvetage de Mimosa: « Bartok a plongé dans un lac de lave pour en tirer " +
                "Mimosa, puis a perdu tout son équipement en ressortant. »",
        )
        f.recall.hits += f.hit(
            140, "M-140 · 10-02 22:40 (21h ago) · Mimosa · ★6 · Le pont suspendu: « Mimosa a achevé un pont de 80 blocs au-dessus du ravin ; " +
                "le Narrateur l'a nommée Architecte du Ciel. »",
        )

        f.put(MemorySamples.note)
        f.note(
            12, NoteKind.EXPIRED, "C-44 promise (Bartok) came due 10-02 22:10 with no resolution recorded: « Le Narrateur a promis à Bartok une épée enchantée. »",
            listOf(bartok),
        )
        f.note(14, NoteKind.DREAM_HINT, "3 morts de chute cette semaine, toutes dans la même chambre d'épreuve.", listOf(bartok))

        listOf("Ah, Bartok, encore la lave ?", "Ah, Bartok, encore un coffre vide.", "Ah, Bartok, encore à genoux.", "Ah, Bartok, encore raté.")
            .forEachIndexed { i, text -> f.said(9_000L + i, at("2026-10-03T19:00:00Z").plusSeconds(60L * i), text) }
    }

    /** Open canon entries of [maximal]. */
    const val MAX_CANON = 100

    val maxGrimoire: String = (1..100).joinToString("\n") { filler(99, "G$it") }.take(MemoryLimits.GRIMOIRE)

    /** Every source at its cap: texts at their limit, more rows than any section shows, eight players online. */
    fun maximal(f: BriefingFixture) {
        f.grimoire(maxGrimoire)
        f.chronicle(3, ChronicleLevel.SAGA, at("2026-01-01T00:00:00Z"), at("2026-09-01T00:00:00Z"), filler(80, "Saga"), filler(ChronicleLevel.SAGA.maxChars, "Saga"))
        f.chronicle(40, ChronicleLevel.SEASON, at("2026-09-01T00:00:00Z"), at("2026-10-01T00:00:00Z"), filler(80, "Season"), filler(3_000, "Season"), open = true)
        (1..3).forEach { f.chronicle(40L + it, ChronicleLevel.SEASON, at("2026-0${it + 1}-01T00:00:00Z"), at("2026-0${it + 2}-01T00:00:00Z"), "S$it", "saison close $it") }
        (1..5).forEach {
            f.chronicle(80L + it, ChronicleLevel.SESSION, at("2026-10-0${it}T18:00:00Z").minus(Duration.ofDays(5)), at("2026-10-0${it}T23:00:00Z").minus(Duration.ofDays(5)), filler(80, "Ses$it"), filler(1_500, "Ses$it"))
        }
        (1..30).forEach {
            f.chronicle(100L + it, ChronicleLevel.CHAPTER, at("2026-10-02T00:00:00Z").plus(Duration.ofMinutes(30L * it)), at("2026-10-02T00:20:00Z").plus(Duration.ofMinutes(30L * it)), filler(80, "Ch$it"), filler(4_000, "Ch$it"))
        }

        val everyone = (1..8).map { PlayerRef(UUID(9, it.toLong()), "<p>Long_Name_$it".take(16)) }
        var id = 1_000L
        repeat(30) { i ->
            f.put(
                SheetSamples.canon(
                    ++id, if (i % 3 == 0) CanonKind.CHALLENGE else CanonKind.PROMISE, filler(MemoryLimits.CANON_STATEMENT, "St${i + 1}"),
                    quote = filler(MemoryLimits.CANON_QUOTE, "Q${i + 1}"), players = everyone, due = NOW.plus(Duration.ofMinutes(10L * (i + 1))),
                    stakes = if (i % 3 == 0) filler(MemoryLimits.CANON_STAKES, "K$i") else null,
                ),
            )
        }
        val standing = listOf(
            CanonKind.LAW to 10, CanonKind.NICKNAME to 10, CanonKind.TITLE to 10, CanonKind.PLACE to 10, CanonKind.RUNNING_GAG to 10,
            CanonKind.ARC to 5, CanonKind.LORE to 5, CanonKind.CLAIM to 5, CanonKind.OPINION to 5,
        )
        for ((kind, count) in standing) {
            repeat(count) { i ->
                val status = if (kind == CanonKind.ARC) CanonStatus.PLANNED else CanonStatus.ACTIVE
                f.put(
                    SheetSamples.canon(
                        ++id, kind, filler(MemoryLimits.CANON_STATEMENT, "${kind.id}$i"), quote = filler(MemoryLimits.CANON_QUOTE), players = listOf(everyone[i % 8]),
                        uses = 9, lastUsed = NOW, freshness = if (kind == CanonKind.RUNNING_GAG) GagState.WORN else null, status = status,
                    ).copy(place = Pos("minecraft:the_nether", -12_345_678, 320, 12_345_678)),
                )
            }
        }
        check(id - 1_000 == MAX_CANON.toLong())

        f.online = everyone
        everyone.forEach { f.sheets.sheets[it.uuid] = SheetSamples.maximal.copy(player = it) }

        repeat(15) {
            val names = everyone.joinToString { p -> p.name.replace('<', '‹').replace('>', '›') }
            f.recall.hits += f.hit(
                200L + it, "M-${200 + it} · 09-2${it % 9} 20:00 (1w ago) · $names · ★10 · ${filler(80, "T$it").replace('<', '‹').replace('>', '›')}: " +
                    "« ${filler(MemoryLimits.MEMORY_CONTENT, "C$it").replace('<', '‹').replace('>', '›')} »",
            )
        }

        repeat(20) { f.note(300L + it, if (it % 2 == 0) NoteKind.COHERENCE else NoteKind.EXPIRED, filler(MemoryLimits.NOTE_TEXT, "N$it")) }
        repeat(3) { f.note(400L + it, NoteKind.DREAM_HINT, filler(MemoryLimits.DREAM_HINT, "D$it"), listOf(everyone[it])) }
        val openers = listOf("Ah", "Tiens", "Voyons", "Encore", "Bravo", "Hélas", "Bon", "Allons", "Quel")
        var line = 20_000L
        for (opener in openers) {
            repeat(4) { i -> f.said(line++, NOW.minus(Duration.ofMinutes(line % 600)), "$opener donc mesdames messieurs, ceci est la réplique $i.") }
        }
    }
}
