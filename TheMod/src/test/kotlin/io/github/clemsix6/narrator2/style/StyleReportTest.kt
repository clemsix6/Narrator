package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.memory.stats.Reactions
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.style.StyleReport.Flaw
import io.github.clemsix6.narrator2.style.StyleReport.Line
import io.github.clemsix6.narrator2.style.StyleReport.Period
import io.github.clemsix6.narrator2.style.StyleReport.RegisterUse
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two hours of the Director's lines on a real Journal (UTC), for the style's report and `/narrator style`: their labels,
 * and what the players did around them. Bartok and Mimosa are online from 20:00.
 *
 * - **Before** ([BEFORE], 20:00–21:00): courtroom twice, divine_bureaucracy once (crutch « céleste »), weather_report
 *   once, flagged dark 0.9. Nobody answers.
 * - **Now** ([PERIOD], 21:00–22:00): divine_bureaucracy three times (the first labeled `other`, then reviewed again;
 *   « Céleste » twice as a crutch), sports_commentary once (Bartok laughs), plain once (flat 0.8; Bartok is annoyed),
 *   one line never reviewed. Bartok answers the first line; Mimosa and Bartok ignore the others. An operator's line and a
 *   rule's text (no turn) are not the Director's.
 */
internal object StyleDataset {
    val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")

    val DAY: Instant = Instant.parse("2026-10-21T00:00:00Z")
    val BEFORE = Period(at("20:00"), at("21:00"))
    val PERIOD = Period(at("21:00"), at("22:00"))
    val NOW: Instant = at("22:00")

    fun at(time: String): Instant {
        val (hours, minutes) = time.split(':').map { it.toLong() }
        return DAY.plusSeconds(hours * 3_600 + minutes * 60)
    }

    /** A Journal holding the dataset, committed; its clock at [NOW]. */
    fun journal(): JournalFixture {
        val fixture = JournalFixture(clock = TestClock(at("19:59")))
        fun on(time: String, event: GameEvent): Envelope {
            fixture.clock.now = at(time)
            return fixture.publish(event)
        }
        var turn = 100L
        fun say(time: String, text: String) = on(time, NarratorSpoke(NarratorChannel.NARRATOR, text, null, turn++, null))
        fun chat(time: String, player: PlayerRef, text: String) = on(time, ChatMessage(player, ChatChannel.PUBLIC, text, emptyList()))

        on("20:00", PlayerJoined(bartok, Pos("minecraft:overworld", 0, 64, 0), 1, firstTime = true, isOp = false))
        on("20:00", PlayerJoined(mimosa, Pos("minecraft:overworld", 9, 64, 0), 2, firstTime = true, isOp = false))

        label(fixture, say("20:10", "Silence ! Le tribunal juge Bartok pour vol de carottes."), "courtroom")
        label(fixture, say("20:20", "Mimosa, la cour vous condamne à replanter le potager."), "courtroom")
        label(fixture, say("20:30", "Le guichet céleste exige un formulaire pour chaque pioche."), "divine_bureaucracy", crutches = listOf("céleste"))
        label(fixture, say("20:40", "Ciel couvert sur Bartok, avec de fortes chances d'enclume."), "weather_report", dark = 0.9)

        val stamped = say("21:00", "Bartok, le guichet céleste a tamponné ta demande.")
        label(fixture, stamped, "other")
        label(fixture, stamped, "divine_bureaucracy", crutches = listOf("Céleste"), after = 3)
        chat("21:01", bartok, "ok")
        label(fixture, say("21:05", "Mimosa, formulaire B-12 rempli en triple exemplaire."), "divine_bureaucracy")
        label(fixture, say("21:10", "Et Bartok s'élance, il glisse, il tombe : quel plongeon !"), "sports_commentary")
        chat("21:11", bartok, "mdr")
        label(fixture, say("21:20", "Effet lenteur appliqué à Bartok."), "plain", flat = 0.8)
        val insult = chat("21:21", bartok, "narrateur t'es nul")
        fixture.journal.chatSignals(
            ChatSignalsRecord(insult.id, at("21:21"), bartok.uuid, "heuristic", 0.9, Tone.HOSTILE, 0.9, null, 0.0, null, null, null, null, null, 1),
        )
        label(fixture, say("21:30", "Le guichet céleste ferme ses portes, Bartok."), "divine_bureaucracy", repeats = 0.75, crutches = listOf("céleste"))
        say("21:40", "Pas encore relue, celle-ci.")
        on("21:45", NarratorSpoke(NarratorChannel.OPERATOR, "Bartok, l'opérateur te salue.", null, null, "Clement"))
        on("21:50", NarratorSpoke(NarratorChannel.NARRATOR, "Loi du jour : Bartok ne touche plus à la lave.", null, null, null))
        fixture.clock.now = NOW
        fixture.sync()
        return fixture
    }

    /** The review's labels of [line], [after] seconds after it. */
    fun label(
        fixture: JournalFixture,
        line: Envelope,
        register: String,
        dark: Double = 0.0,
        cruel: Double = 0.0,
        flat: Double = 0.0,
        outOfCharacter: Double = 0.0,
        repeats: Double = 0.0,
        crutches: List<String> = emptyList(),
        after: Long = 1,
    ) = fixture.journal.lineLabels(
        LineLabelsRecord(
            line.id, Instant.ofEpochMilli(line.ts).plusSeconds(after), "reflex:claude-haiku-5-5", register, dark, cruel, flat, outOfCharacter,
            repeats, crutches, "Une phrase du relecteur.", 900,
        ),
    )

    fun <T> read(fixture: JournalFixture, block: (Connection) -> T): T = runBlocking { fixture.journal.read(10_000, block) }
}

/** The style's report (M6 §3.7, §4.6): the Director's lines of a period, how players took each register, the flags, the measures, the Dreamer's blocks. */
class StyleReportTest {
    private val words = HeuristicWords.bundled("fr")!!
    private val utc = ZoneOffset.UTC
    private val latest = Diversity(50, 0.8, ComicRegister.DIVINE_BUREAUCRACY, 0.38, 0.71, listOf("céleste" to 6), 3)

    @Test
    fun `a period holds the Director's lines only, with their latest labels`() {
        StyleDataset.journal().use { fixture ->
            val lines = StyleDataset.read(fixture) { StyleReport.lines(it, StyleDataset.PERIOD) }
            assertEquals(
                listOf(
                    "Bartok, le guichet céleste a tamponné ta demande." to ComicRegister.DIVINE_BUREAUCRACY,
                    "Mimosa, formulaire B-12 rempli en triple exemplaire." to ComicRegister.DIVINE_BUREAUCRACY,
                    "Et Bartok s'élance, il glisse, il tombe : quel plongeon !" to ComicRegister.SPORTS_COMMENTARY,
                    "Effet lenteur appliqué à Bartok." to ComicRegister.PLAIN,
                    "Le guichet céleste ferme ses portes, Bartok." to ComicRegister.DIVINE_BUREAUCRACY,
                    "Pas encore relue, celle-ci." to null,
                ),
                lines.map { it.text to it.register },
                "neither the operator's line nor the rule's text; the second review of the first line wins",
            )
            assertEquals(StyleDataset.at("21:00"), lines.first().ts)
            assertEquals(listOf("Céleste"), lines.first().crutches)
            assertEquals(listOf(Flaw.FLAT to 0.8), lines[3].flags)
            assertEquals(mapOf(), lines.last().flaws, "a line not reviewed has no flaw")
            assertEquals(4, StyleDataset.read(fixture) { StyleReport.lines(it, StyleDataset.BEFORE) }.size)
        }
    }

    @Test
    fun `each register's lines and how the players took them, who laughed and who was annoyed`() {
        StyleDataset.journal().use { fixture ->
            val uses = StyleDataset.read(fixture) { StyleReport.registers(it, StyleDataset.PERIOD, words) }
            assertEquals(
                listOf(
                    RegisterUse(ComicRegister.DIVINE_BUREAUCRACY, 3, laughed = 0, annoyed = 0, replied = 1, ignored = 2, laughing = emptyList(), annoying = emptyList()),
                    RegisterUse(ComicRegister.SPORTS_COMMENTARY, 1, laughed = 1, annoyed = 0, replied = 0, ignored = 0, laughing = listOf("Bartok" to 1), annoying = emptyList()),
                    RegisterUse(ComicRegister.PLAIN, 1, laughed = 0, annoyed = 1, replied = 0, ignored = 0, laughing = emptyList(), annoying = listOf("Bartok" to 1)),
                ),
                uses,
                "the most used first, then the enum's order",
            )
            assertEquals(
                listOf(RegisterUse(ComicRegister.COURTROOM, 2, 0, 0, 0, 2, emptyList(), emptyList())),
                StyleDataset.read(fixture) { StyleReport.registers(it, StyleDataset.BEFORE, words) }.take(1),
                "nobody answered before 21:00",
            )
        }
    }

    @Test
    fun `the measures of a period count the reviewed lines`() {
        StyleDataset.journal().use { fixture ->
            val now = StyleReport.measures(StyleDataset.read(fixture) { StyleReport.lines(it, StyleDataset.PERIOD) })
            assertEquals(6, now.lines)
            assertEquals(5, now.reviewed)
            assertEquals(mapOf(ComicRegister.DIVINE_BUREAUCRACY to 3, ComicRegister.SPORTS_COMMENTARY to 1, ComicRegister.PLAIN to 1), now.registers)
            assertEquals(ComicRegister.DIVINE_BUREAUCRACY, now.topRegister)
            assertEquals(0.6, now.topRegisterShare, 1e-9)
            // plain is not a register of the voice: 3 and 1 over min(16, 4).
            assertEquals((-(0.75 * ln(0.75)) - 0.25 * ln(0.25)) / ln(4.0), now.registerEntropy, 1e-9)
            assertEquals(listOf("céleste" to 2), now.crutches, "words in lower case, once per line")
            assertEquals(0.4, now.crutchShare("Céleste"), 1e-9)
            assertEquals(2, now.flagged)

            val before = StyleReport.measures(StyleDataset.read(fixture) { StyleReport.lines(it, StyleDataset.BEFORE) })
            assertEquals(ComicRegister.COURTROOM, before.topRegister)
            assertEquals(0.75, before.registerEntropy, 1e-9)
            assertEquals(1, before.flagged)
        }
    }

    @Test
    fun `the entropy spreads from 0 to 1`() {
        assertEquals(1.0, StyleReport.entropy(ComicRegister.VOICE.map { 3 }), 1e-9, "every register of the voice as often")
        assertEquals(1.0, StyleReport.entropy(listOf(1, 1)), 1e-9, "two lines in two registers: as spread as two lines can be")
        assertEquals(0.0, StyleReport.entropy(listOf(7, 0, 0)), "one register")
        assertEquals(0.0, StyleReport.entropy(listOf(1)), "one line")
        assertEquals(0.0, StyleReport.entropy(emptyList()))
        val uneven = StyleReport.entropy(listOf(9, 1, 0))
        assertTrue(uneven > 0.0 && uneven < 0.5, "$uneven")
    }

    @Test
    fun `the Dreamer's three blocks, compared with the period before`() {
        StyleDataset.journal().use { fixture ->
            val review = StyleDataset.read(fixture) { StyleReport.review(it, StyleDataset.PERIOD, StyleDataset.BEFORE, words, latest, utc) }
            assertEquals(
                """
                <registers from="10-21 21:00" to="10-21 22:00" lines="6" reviewed="5">
                - divine_bureaucracy: 3 lines · laughed 0 · annoyed 0 · replied 1 · ignored 2
                - sports_commentary: 1 line · laughed 1 (Bartok 1) · annoyed 0 · replied 0 · ignored 0
                - plain: 1 line · laughed 0 · annoyed 1 (Bartok 1) · replied 0 · ignored 0
                </registers>
                <tone_flags flagged="2" of="5">
                - 10-21 21:20 flat 0.80: "Effet lenteur appliqué à Bartok."
                - 10-21 21:30 repeats 0.75: "Le guichet céleste ferme ses portes, Bartok."
                </tone_flags>
                <diversity>openers distinct 0.80 in the latest 50 lines · top register divine_bureaucracy 60% (before: courtroom 50%) · register entropy 0.41 (0.75 before) · crutch « céleste » 40% (25% before)</diversity>
                """.trimIndent(),
                review,
            )
            val alone = StyleDataset.read(fixture) { StyleReport.review(it, StyleDataset.BEFORE, null, words, Diversity.NONE, utc) }
            assertEquals(
                "<diversity>top register courtroom 50% · register entropy 0.75 · crutch « céleste » 25%</diversity>",
                alone.lines().last(),
                "no period before, no line measured by the watch",
            )
            assertTrue(alone.contains("- 10-21 20:40 dark 0.90: \"Ciel couvert sur Bartok, avec de fortes chances d'enclume.\""), alone)
        }
    }

    @Test
    fun `an empty period, a period never reviewed, and a period without a flag say so`() {
        val period = Period(StyleDataset.at("21:00"), StyleDataset.at("22:00"))
        assertEquals("<registers from=\"10-21 21:00\" to=\"10-21 22:00\">(the Director said nothing in this period)</registers>", StyleReport.registersBlock(period, emptyList(), emptyList(), utc))
        assertEquals("<diversity>nothing said in this period</diversity>", StyleReport.diversityBlock(emptyList(), null, Diversity.NONE))

        val unreviewed = listOf(line(1, "Une réplique.", null))
        assertEquals(
            "<registers from=\"10-21 21:00\" to=\"10-21 22:00\" lines=\"1\" reviewed=\"0\">(no line reviewed: the line review was off, or failed)</registers>",
            StyleReport.registersBlock(period, unreviewed, emptyList(), utc),
        )
        assertEquals("<tone_flags>(no line reviewed in this period)</tone_flags>", StyleReport.flagsBlock(unreviewed, utc))
        assertEquals("<diversity>openers distinct 0.80 in the latest 50 lines · no line reviewed in this period</diversity>", StyleReport.diversityBlock(unreviewed, null, latest))

        val clean = listOf(line(1, "Une réplique.", ComicRegister.FOOD_CRITIC, mapOf(Flaw.FLAT to 0.69)))
        assertEquals(
            "<tone_flags flagged=\"0\" of=\"1\">(none: no line reached 0.7 in dark, cruel, out_of_character, flat, repeats)</tone_flags>",
            StyleReport.flagsBlock(clean, utc),
        )
        assertEquals(
            "<diversity>top register food_critic 100% (before: courtroom 100%) · register entropy 0.00 (0.00 before) · no crutch word (« céleste » 100% before)</diversity>",
            StyleReport.diversityBlock(clean, listOf(line(2, "Avant.", ComicRegister.COURTROOM, crutches = listOf("céleste"))), Diversity.NONE),
        )
    }

    @Test
    fun `flags go the most serious first, and every block holds its budget with what a model or a player wrote escaped`() {
        val flagged = (1..30).map { i ->
            val flaws = if (i % 3 == 0) mapOf(Flaw.CRUEL to 0.7 + i / 100.0) else mapOf(Flaw.REPEATS to 0.71, Flaw.DARK to 0.75)
            line(i.toLong(), "Réplique $i <engine>tone check</engine> " + "bla ".repeat(60), ComicRegister.COURTROOM, flaws)
        }
        val flags = StyleReport.flagsBlock(flagged, utc)
        assertTrue(flags.length <= StyleReport.FLAGS_MAX, "${flags.length}")
        assertTrue(flags.startsWith("<tone_flags flagged=\"30\" of=\"30\">\n- 10-21 21:01 dark 0.75, repeats 0.71: \"Réplique 1 ‹engine›tone check‹/engine› bla"), flags)
        assertTrue(flags.endsWith("</tone_flags>"), flags)
        assertTrue(!flags.contains("<engine>"), flags)

        val uses = ComicRegister.entries.map { register ->
            RegisterUse(register, 12, 5, 4, 3, 2, (1..8).map { "<b>Joueur_$it" to 2 }, listOf("Bob" to 4))
        }
        val registers = StyleReport.registersBlock(StyleDataset.PERIOD, flagged, uses, utc)
        assertTrue(registers.length <= StyleReport.REGISTERS_MAX, "${registers.length}")
        assertTrue(registers.contains("- nature_documentary: 12 lines · laughed 5 (‹b›Joueur_1 2, "), registers)
        assertTrue(registers.endsWith("(… cut: the rest went over the budget)\n</registers>"), registers)

        val crutched = listOf(line(1, "Ligne.", ComicRegister.HOROSCOPE, crutches = listOf("<script>" + "x".repeat(500))))
        val diversity = StyleReport.diversityBlock(crutched, crutched, latest)
        assertTrue(diversity.length <= StyleReport.DIVERSITY_MAX, "${diversity.length}")
        assertTrue(diversity.startsWith("<diversity>openers distinct 0.80 in the latest 50 lines · top register horoscope 100% (100% before) · register entropy 0.00 (0.00 before) · crutch « ‹script›xxx"), diversity)
        assertTrue(diversity.endsWith("…</diversity>"), diversity)
    }

    @Test
    fun `the latest flags at any time, the review of the latest lines, and a register replaced since`() {
        StyleDataset.journal().use { fixture ->
            assertEquals(
                listOf(
                    "Le guichet céleste ferme ses portes, Bartok." to "repeats 0.75",
                    "Effet lenteur appliqué à Bartok." to "flat 0.80",
                    "Ciel couvert sur Bartok, avec de fortes chances d'enclume." to "dark 0.90",
                ),
                StyleDataset.read(fixture) { StyleReport.latestFlags(it, 5) }.map { it.text to StyleReport.flagList(it) },
            )
            assertEquals(2, StyleDataset.read(fixture) { StyleReport.latestFlags(it, 2) }.size)
            assertEquals(StyleReport.Coverage(10, 9), StyleDataset.read(fixture) { StyleReport.coverage(it, 50) })
            assertEquals(StyleReport.Coverage(3, 2), StyleDataset.read(fixture) { StyleReport.coverage(it, 3) }, "the line never reviewed is the latest")

            fixture.clock.now = StyleDataset.at("22:30")
            val renamed = fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Les Archives célestes ont perdu ton dossier.", null, 300, null))
            StyleDataset.label(fixture, renamed, "ministry_of_archives")
            fixture.sync()
            val line = StyleDataset.read(fixture) { StyleReport.lines(it, Period(StyleDataset.at("22:00"), StyleDataset.at("23:00"))) }.single()
            assertEquals(ComicRegister.OTHER, line.register, "a label whose register is no longer known counts as other")
        }
    }

    @Test
    fun `the reactions to any line are matched to the reviewed ones`() {
        val reviewed = line(1, "Bartok, tu plonges.", ComicRegister.NATURE_DOCUMENTARY)
        val reactions = listOf(
            Reactions.Reaction(1, reviewed.ts, reviewed.text, StyleDataset.bartok, Reactions.Kind.LAUGHED),
            Reactions.Reaction(1, reviewed.ts, reviewed.text, StyleDataset.mimosa, Reactions.Kind.LAUGHED),
            Reactions.Reaction(9, reviewed.ts, "Une ligne d'opérateur.", StyleDataset.bartok, Reactions.Kind.ANNOYED),
        )
        assertEquals(
            listOf(RegisterUse(ComicRegister.NATURE_DOCUMENTARY, 1, 2, 0, 0, 0, listOf("Bartok" to 1, "Mimosa" to 1), emptyList())),
            StyleReport.registers(listOf(reviewed, line(2, "Pas relue.", null)), reactions),
        )
    }

    private fun line(
        id: Long,
        text: String,
        register: ComicRegister?,
        flaws: Map<Flaw, Double> = emptyMap(),
        crutches: List<String> = emptyList(),
    ) = Line(id, StyleDataset.at("21:00").plusSeconds(60 * id), text, register, if (register == null) emptyMap() else Flaw.entries.associateWith { flaws[it] ?: 0.0 }, crutches)
}
