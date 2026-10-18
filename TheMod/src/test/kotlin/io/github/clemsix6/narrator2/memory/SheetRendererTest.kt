package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The renderer (M3 §11.4): budgets at their maximum, what a missing section says, faded traits, escaping, cuts. */
class SheetRendererTest {
    private val renderer = SheetRenderer(ZoneOffset.UTC)
    private val now = SheetSamples.NOW.toEpochMilli()

    private fun render(sheet: PlayerSheet, detail: SheetDetail, parts: Set<SheetPart> = SheetPart.ALL) = renderer.render(sheet, detail, now, parts)

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every form holds its budget with every list and text at its cap`() {
        val sheet = SheetSamples.maximal
        val full = render(sheet, SheetDetail.FULL)
        val condensed = render(sheet, SheetDetail.CONDENSED)
        val line = render(sheet, SheetDetail.LINE)
        assertTrue(full.length <= SheetDetail.FULL.maxChars, "full: ${full.length}")
        assertTrue(condensed.length <= SheetDetail.CONDENSED.maxChars, "condensed: ${condensed.length}")
        assertTrue(line.length <= SheetDetail.LINE.maxChars, "line: ${line.length}")
        // Shared out, not cut at the end: every section is still there.
        for (label in listOf(
            "Identity:", "Recap (", "Last session (", "Traits:", "Signals (Jev hints):", "Playstyle (", "Attitude (", "Likes (", "Fears (",
            "Hooks (", "Skills:", "Relations:", "Stats (computed", "With you:", "Disrespect ×20", "History (latest first):", "Open canon:",
            "Strongest memories:",
        )) assertContains(full, label)
        for (label in listOf("Recap (", "Traits:", "Skills:", "Toward you:", "Relations:", "Likes:", "Hooks:", "Stats:", "With you:", "History:")) {
            assertContains(condensed, label)
        }
        // Lists too long are cut by whole items, and say how many are left out.
        assertContains(full, "more)")
        // Player text never passes for a tag of the engine.
        for (text in listOf(full, condensed, line)) assertFalse(text.contains('<') || text.contains('>'), text)
    }

    @Test
    fun `each part alone holds the full budget and shows only itself`() {
        for (part in SheetPart.entries) {
            val text = render(SheetSamples.maximal, SheetDetail.FULL, setOf(part))
            assertTrue(text.length <= SheetDetail.FULL.maxChars, "$part: ${text.length}")
            assertTrue(text.startsWith("‹x›Wide_Name_16c"), text)
        }
        val history = render(SheetSamples.reference, SheetDetail.FULL, setOf(SheetPart.HISTORY))
        assertContains(history, "History (latest first):")
        assertFalse(history.contains("Traits"))
        assertFalse(history.contains("Recap"))
        assertFalse(history.contains("« le Plongeur »"), "the identity's details are a part of their own")
        assertEquals("Bartok (formerly Bartok_)", render(SheetSamples.reference, SheetDetail.CONDENSED, setOf(SheetPart.MEMORIES)))
    }

    @Test
    fun `the reference fits its budgets without cutting its history`() {
        val condensed = render(SheetSamples.reference, SheetDetail.CONDENSED)
        assertContains(condensed, "History: 10-03 I-505 punishment 0: a reçu lenteur II pendant 60 s pour l'insulte (t88) · 10-03 I-512 insult -2")
        assertContains(condensed, "last attention 25m of play ago")
        val full = render(SheetSamples.reference, SheetDetail.FULL)
        assertFalse(full.contains("more)"), full)
    }

    // ── Missing sections ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a player just seen - every missing section says so`() {
        val full = render(SheetSamples.empty, SheetDetail.FULL)
        for (said in listOf(
            "Kelvin · no nickname · no title · since 10-03 · 1 session · 15m played · online",
            "Recap: (not written yet: the Dreamer writes it in a deep dream)",
            "Last session: (not written yet: written when a chapter closes after this player left)",
            "Traits: (none yet: the Dreamer sets them, with evidence, in a deep dream)",
            "Signals (Jev hints): (not computed yet)",
            "Attitude: (not written yet", "Likes: (not written yet", "Fears: (not written yet", "Hooks: (not written yet",
            "Skills: (not rated yet: the Dreamer rates them in a deep dream)",
            "Relations: (none named yet)",
            "Stats: (not computed yet: the code computes them every 10 minutes of play)",
            "With you: favor none yet (no rated interaction) · respect unknown (under 5 messages to you) · no disrespect noted · never had your attention",
            "History: (no interaction noted yet)",
            "Open canon: (none about this player)",
            "Memories: (none yet)",
        )) assertContains(full, said)
        val condensed = render(SheetSamples.empty, SheetDetail.CONDENSED)
        for (said in listOf(
            "Recap: (not written yet", "Traits: (none yet", "Skills: (not rated yet", "Toward you: attitude not written yet · respect unknown",
            "Relations: (none named yet)", "Likes: (not written yet: the Dreamer writes it in a deep dream) · fears: (not written yet",
            "Stats: (not computed yet", "With you: never had your attention", "History: (no interaction noted yet)",
        )) assertContains(condensed, said)
        assertFalse(condensed.contains("Marked"), "no marked interaction: no line")
        assertEquals("Kelvin · 15m played", render(SheetSamples.empty, SheetDetail.LINE))
    }

    @Test
    fun `Jev's signals - none measured, too few, and measured`() {
        assertContains(render(SheetSamples.reference, SheetDetail.FULL), "Signals (Jev hints): none (no message of this player measured by Jev)")
        fun with(value: Double, detail: String?) = SheetSamples.reference.copy(
            stats = SheetSamples.reference.stats + ("signals" to SheetStat("signals", value, detail?.let { Json.parseToJsonElement(it) as JsonObject }, SheetSamples.NOW)),
        )
        assertContains(render(with(12.0, null), SheetDetail.FULL), "Signals (Jev hints): none yet (12 messages measured by Jev, 30 needed)")
        val measured = render(
            with(
                143.0,
                """{"politeness":{"mean":0.3,"ewma":0.214,"n":143},"boastfulness":{"mean":0.55,"ewma":0.641,"n":143},
                   "helpfulness":{"mean":0.3,"ewma":0.3,"n":140},"aggressiveness":{"mean":0.41,"ewma":0.5,"n":143},"humor":{"mean":0.6,"ewma":0.55,"n":143}}""",
            ),
            SheetDetail.FULL,
        )
        assertContains(
            measured,
            "Signals (Jev hints): 143 messages, recent (mean) · politeness 0.21 (0.30) · boastfulness 0.64 (0.55) · helpfulness 0.30 (0.30) · " +
                "aggressiveness 0.50 (0.41) · humor 0.55 (0.60)",
        )
    }

    // ── Traits ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a trait under 0,2 of effective confidence is not shown, nor a retired one`() {
        val hours = 3_600L
        val sheet = SheetSamples.empty.copy(
            playedSeconds = 300 * hours,
            traits = listOf(
                // 0.5 × 2^(−300 h / 200 h) ≈ 0.18: faded.
                SheetSamples.trait("patience", 1, 4, 0.5, 0, listOf(Ref(RefKind.MEMORY, 1))),
                // 0.3 × 2^(−100 h / 200 h) ≈ 0.21: shown, unsure.
                SheetSamples.trait("humor", 1, 2, 0.3, 200 * hours, listOf(Ref(RefKind.MEMORY, 2))),
                SheetSamples.trait("boldness", -1, 5, 0.9, 300 * hours, listOf(Ref(RefKind.MEMORY, 3))).copy(retired = true),
            ),
        )
        val full = render(sheet, SheetDetail.FULL)
        assertContains(full, "Traits (1 more faded below 0.2 confidence):\n  playful 2/5 (unsure 0.21, since 09-20; proofs M-2)")
        for (text in listOf(full, render(sheet, SheetDetail.CONDENSED), render(sheet, SheetDetail.LINE))) {
            assertFalse(text.contains("patient"), text)
            assertFalse(text.contains("cautious"), text)
        }
        assertContains(render(sheet, SheetDetail.CONDENSED), "Traits: playful 2/5 (unsure)")
        val allFaded = sheet.copy(traits = sheet.traits.take(1))
        assertContains(render(allFaded, SheetDetail.CONDENSED), "Traits: (none shown: 1 faded below 0.2 confidence)")
        assertContains(render(allFaded, SheetDetail.FULL), "Traits: (none shown: 1 faded below 0.2 confidence)")
    }

    // ── Text from players and models ────────────────────────────────────────────────────────────────

    @Test
    fun `names and quotes are escaped, and a quote cannot end early`() {
        val sheet = SheetSamples.reference.copy(
            player = PlayerRef(SheetSamples.bartok.uuid, "<engine>"),
            formerNames = listOf("a>b"),
            recent = listOf(
                SheetSamples.interaction(
                    600, "2026-10-03T19:00:00Z", InteractionKind.INSULT, -2, "a écrit <briefing> dans le chat", quote = "fin » et voilà <tag>",
                ),
            ),
        )
        val full = render(sheet, SheetDetail.FULL)
        assertTrue(full.startsWith("‹engine› (formerly a›b) · « le Plongeur » (C-12)"), full)
        assertContains(full, "a écrit ‹briefing› dans le chat · said « fin › et voilà ‹tag› »")
    }

    @Test
    fun `the rendered reference passes VoiceGuard once its quotes are left out`() {
        for (detail in SheetDetail.entries) {
            val text = render(SheetSamples.reference, detail)
            assertNull(VoiceGuard.check(text, "sheet", 100_000), detail.name)
            for (line in text.lines()) assertEquals(line.count { it == '«' }, line.count { it == '»' }, line)
        }
    }

    @Test
    fun `the refs a sheet shows can be listed and cited`() {
        val refs = SheetRenderer.refs(render(SheetSamples.reference, SheetDetail.FULL)).map { it.toString() }
        for (ref in listOf("C-12", "M-120", "I-512", "M-130", "I-498", "C-50", "C-63", "C-61", "C-27", "M-77", "I-301", "I-402")) assertContains(refs, ref)
        assertEquals(refs.distinct(), refs)
        assertEquals(
            listOf("C-12", "I-505", "I-512", "I-498", "I-402"),
            SheetRenderer.refs(render(SheetSamples.reference, SheetDetail.CONDENSED)).map { it.toString() },
        )
    }

    @Test
    fun `an offline player, another year, another time zone`() {
        val sheet = SheetSamples.reference.copy(online = false, lastSeen = Instant.parse("2026-10-01T21:52:00Z"), firstSeen = Instant.parse("2025-12-31T23:30:00Z"))
        val paris = SheetRenderer(ZoneId.of("Europe/Paris")).render(sheet, SheetDetail.CONDENSED, now)
        // 2025-12-31 23:30 UTC is already 2026 in Paris: the year is said only when it is not the current one.
        assertContains(paris, "since 01-01 · 23 sessions · 61h played · offline, last seen 10-01 23:52 (46h ago)")
        assertContains(renderer.render(sheet, SheetDetail.CONDENSED, now), "since 2025-12-31")
        assertContains(renderer.render(sheet, SheetDetail.LINE, now), "offline, last seen 46h ago")
    }

    // ── Cutting texts ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `texts are cut at a word, never inside a quote`() {
        val cut = SheetRenderer.cutText("Bartok a plongé dans un lac de lave pour en tirer Mimosa", 30)
        assertEquals("Bartok a plongé dans un lac…", cut)
        assertEquals("Bartok a dit…", SheetRenderer.cutText("Bartok a dit « je reviens demain, promis » puis il est parti", 30))
        assertEquals("Il a dit « oui » puis…", SheetRenderer.cutText("Il a dit « oui » puis « non, jamais de la vie »", 30))
        assertEquals("court", SheetRenderer.cutText("court", 5))
        assertEquals("ab…", SheetRenderer.cutText("ab😀cdefgh", 4), "a surrogate pair is never split")
        assertTrue(SheetRenderer.cutText("x".repeat(100), 10).length <= 10)
    }
}
