package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.HOME
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.NOW
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.bartok
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.kelvin
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.mimosa
import io.github.clemsix6.narrator2.memory.SheetSamples.filler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** M3 §11.2, §11.3: the sheet of a player who joins, and the `<memory>` block of a wake-up (M3-13). */
class JoinBriefTest {
    private fun at(text: String): Instant = Instant.parse(text)

    private fun golden(): List<String> =
        Files.readString(Path.of(javaClass.getResource("/narrator2/memory/sheets/bartok.condensed.txt")!!.toURI())).trimEnd('\n').lines()

    /** Bartok's evening: his last session ended on 10-01 at 21:52, the briefing data of [BriefingSamples.evening]. */
    private fun evening(f: BriefingFixture) {
        BriefingSamples.evening(f)
        f.session(800, bartok, at("2026-10-01T20:10:00Z"), at("2026-10-01T21:52:00Z"))
        f.session(801, mimosa, at("2026-10-02T20:00:00Z"), at("2026-10-02T23:00:00Z"))
    }

    // ── A join ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a join attaches the condensed sheet, the last session, what happened while away and the dream hint`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            val join = f.join(bartok)
            val block = f.attachments.block(listOf(join), chapter, f.now)!!.lines()
            assertEquals("<memory>", block.first())
            assertEquals("</memory>", block.last())
            assertEquals("SHEET Bartok (joined; away 1d22h, last session 10-01 20:10–21:52):", block[1])
            val sheet = golden()
            assertEquals(sheet.map { "  $it" }, block.subList(2, 2 + sheet.size), "the condensed sheet, indented")
            val rest = block.subList(2 + sheet.size, block.size - 1)
            assertEquals(
                listOf(
                    "  Last session: A trouvé 4 diamants et perdu trois fois contre le Warden dans la même cité antique. A promis de revenir se venger, " +
                        "puis a insulté le Narrateur après sa dernière mort.",
                    "  While away: 1 session (H-89 10-02 « Le pont de Mimosa ») · 1 chapter not yet in a session (H-91 10-03 « Le retour du Warden ») · " +
                        "open about this player: C-63 promise, due 10-04 21:00 (in 1d1h) « Finissez ce pont ensemble et je vous couvre d'or. » · " +
                        "C-61 threat « Encore une insulte et il pleuvra des slimes. »",
                    "  Dream hint (once, N-14): 3 morts de chute cette semaine, toutes dans la même chambre d'épreuve.",
                ),
                rest,
            )
            assertTrue(block.drop(1).dropLast(1).joinToString("\n").length <= JoinBrief.MAX_CHARS)

            val shown = chapter.shownRefs.map { it.toString() }.toSet()
            assertTrue(shown.containsAll(listOf("C-12", "I-505", "I-512", "I-498", "I-402", "H-89", "H-91", "C-63", "C-61", "N-14")), "$shown")
            assertFalse("H-88" in shown, "the session he played is not one he missed")
            assertEquals(f.now, chapter.sheetsShownAt[bartok.uuid])
            assertTrue(f.store.recalls.isEmpty() && f.store.writes.isEmpty(), "nothing is written before the model accepts the request")

            f.accept(chapter)
            assertTrue(f.store.recalls.all { it.second == RecallChannel.JOIN && it.third == RecallContext("director-c14", null, join.envelope.id) })
            assertEquals(chapter.shownRefs, f.store.recalls.map { it.first }.toSet())
            assertEquals(listOf("markNote N-14 by engine"), f.store.writes)
        }
    }

    @Test
    fun `a player's first time on the server is said, and nothing else`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            assertEquals("<memory>\nSHEET Kelvin (first time on this server)\n</memory>", f.attachments.block(listOf(f.join(kelvin, firstTime = true)), chapter, f.now))
            assertEquals(f.now, chapter.sheetsShownAt[kelvin.uuid])
            f.accept(chapter)
            assertTrue(f.store.recalls.isEmpty())
        }
    }

    @Test
    fun `a sheet is not repeated in a chapter - a reconnection within 30 minutes gets one line`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            f.joins.lines(listOf(f.join(bartok)), chapter, f.now)
            f.accept(chapter)
            val shownAt = f.now

            f.clock.advance(Duration.ofMinutes(8))
            f.session(901, bartok, NOW, f.clock.instant())
            f.clock.advance(Duration.ofMinutes(4))
            assertEquals(
                listOf("SHEET Bartok (rejoined; away 4m; full sheet shown 12m ago in this chapter)"),
                f.joins.lines(listOf(f.join(bartok, session = 902)), chapter, f.now),
            )
            assertEquals(shownAt, chapter.sheetsShownAt[bartok.uuid], "the 30 minutes count from the full sheet")

            f.clock.advance(Duration.ofMinutes(19))
            f.session(902, bartok, NOW.plus(Duration.ofMinutes(12)), f.clock.instant())
            f.clock.advance(Duration.ofMinutes(1))
            val again = f.joins.lines(listOf(f.join(bartok, session = 903)), chapter, f.now)
            assertEquals("SHEET Bartok (joined; away 1m, last session 10-03 20:12–20:31):", again.first(), "32 minutes later, the full sheet again")
            assertTrue(again.none { it.startsWith("  Dream hint") }, "the dream hint was shown once")
            assertEquals(f.now, chapter.sheetsShownAt[bartok.uuid])
        }
    }

    @Test
    fun `a sheet shown by the briefing of the same wake-up is not attached again`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            val join = f.join(bartok, at = NOW.minusSeconds(3))
            f.briefing.sections(chapter, f.now)
            assertNull(f.attachments.block(listOf(join), chapter, f.now))
        }
    }

    @Test
    fun `a player who left again among the same events gets nothing`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            assertNull(f.attachments.block(listOf(f.join(bartok), f.leave(bartok)), chapter, f.now))
            assertTrue(chapter.sheetsShownAt.isEmpty())
            val back = f.attachments.block(listOf(f.join(bartok), f.leave(bartok), f.join(bartok, session = 903)), chapter, f.now)!!
            assertEquals(1, back.lines().count { it.startsWith("SHEET Bartok") }, "the last join counts")
        }
    }

    @Test
    fun `the dream hint is shown at one join, and again only when the request that showed it failed`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            val join = f.join(bartok)
            val mark = chapter.mark()
            val first = f.attachments.block(listOf(join), chapter, f.now)
            mark.restore() // the request was not accepted: the Director puts the chapter back as it was
            assertEquals(first, f.attachments.block(listOf(join), chapter, f.now), "the same block, hint included")
            f.accept(chapter)
            assertEquals(listOf("markNote N-14 by engine"), f.store.writes, "marked once")
            assertEquals(chapter.shownRefs.size, f.store.recalls.size, "each ref recorded once")

            val next = f.chapter("director-c15")
            f.clock.advance(Duration.ofHours(1))
            val later = f.attachments.block(listOf(f.join(bartok, session = 903)), next, f.now)!!
            assertTrue("Dream hint" !in later, later)
        }
    }

    @Test
    fun `what happened while away is said even when nothing was chronicled`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            f.sheets.sheets[mimosa.uuid] = BriefingFixture.mimosaSheet
            f.session(801, mimosa, at("2026-10-03T18:00:00Z"), at("2026-10-03T19:00:00Z"))
            val lines = f.joins.lines(listOf(f.join(mimosa)), f.chapter(), f.now)
            assertEquals("SHEET Mimosa (joined; away 1h00m, last session 10-03 18:00–19:00):", lines.first())
            assertEquals("  Last session: (not written yet: written when a chapter closes after this player left)", lines[lines.size - 2])
            assertEquals("  While away: nothing chronicled since this player left", lines.last())

            f.sheets.sheets[kelvin.uuid] = SheetSamples.empty
            val unknown = f.joins.lines(listOf(f.join(kelvin)), f.chapter(), f.now)
            assertEquals("SHEET Kelvin (joined):", unknown.first())
            assertEquals("  While away: (unknown: no closed session of this player in the Journal)", unknown.last())
        }
    }

    /** Known point of the M4 review (M4-08): a plan that targets the player is among what is open about them, as in the briefing. */
    @Test
    fun `what is open about a player who joins includes the plans that target them`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            fun canon(id: Long, kind: CanonKind, statement: String, status: CanonStatus = CanonStatus.ACTIVE) =
                SheetSamples.canon(id, kind, statement, players = listOf(mimosa), status = status).copy(mechanic = CanonMechanic.RULE)
            val open = listOf(
                canon(64, CanonKind.PLAN, "Un comité d'accueil attend Mimosa dans le Nether."),
                canon(65, CanonKind.PLAN, "Une pluie de slimes déjà tombée sur Mimosa.", CanonStatus.FULFILLED),
                canon(66, CanonKind.LAW, "Mimosa rapetisse quand elle casse chez un autre."),
            )
            f.sheets.sheets[mimosa.uuid] = BriefingFixture.mimosaSheet.copy(openCanon = open)
            f.session(801, mimosa, at("2026-10-03T18:00:00Z"), at("2026-10-03T19:00:00Z"))
            val chapter = f.chapter()
            val lines = f.joins.lines(listOf(f.join(mimosa)), chapter, f.now)
            assertEquals("  While away: open about this player: C-64 plan: Un comité d'accueil attend Mimosa dans le Nether.", lines.last())
            assertTrue(Ref(RefKind.CANON, 64) in chapter.shownRefs)
        }
    }

    @Test
    fun `a since-last text written before the last session says so`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            f.sheets.sheets[bartok.uuid] = SheetSamples.reference
            f.session(800, bartok, at("2026-10-02T20:00:00Z"), at("2026-10-02T21:00:00Z"))
            val lines = f.joins.lines(listOf(f.join(bartok)), f.chapter(), f.now)
            assertTrue(
                lines.any {
                    it.startsWith(
                        "  Last session: (not written yet for 10-02 20:00–21:00: written when the chapter this player left in closes) · " +
                            "before that (written 10-01): A trouvé 4 diamants",
                    )
                },
                lines.joinToString("\n"),
            )
        }
    }

    // ── Budgets and failures ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a join stays within its budget at the maximum`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.maximal(f)
            val player = SheetSamples.maximal.player
            f.sheets.sheets[player.uuid] = SheetSamples.maximal
            f.clock.now = NOW.plus(Duration.ofHours(2))
            f.session(800, player, NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofHours(1)))
            (1..40).forEach { f.chronicle(500L + it, ChronicleLevel.SESSION, NOW.plus(Duration.ofMinutes(61L + it)), NOW.plus(Duration.ofMinutes(100L + it)), filler(80, "W$it"), "x") }
            repeat(2) { f.note(600L + it, NoteKind.DREAM_HINT, filler(MemoryLimits.DREAM_HINT, "H$it"), listOf(player)) }

            val lines = f.joins.lines(listOf(f.join(player)), f.chapter(), f.now)
            val length = BriefText.length(lines)
            println("a join at its maximum: $length characters of ${JoinBrief.MAX_CHARS}")
            assertTrue(length <= JoinBrief.MAX_CHARS, "$length")
            assertTrue(lines.any { it.startsWith("  Last session: (not written yet for ") }, "the longest form of the last session")
            assertTrue(lines.any { it.startsWith("  While away: 40 sessions (") && it.contains("more") }, lines.joinToString("\n"))
            assertEquals(2, lines.count { it.startsWith("  Dream hint (once, N-60") })
            assertTrue(lines.none { '<' in it || '>' in it })
        }
    }

    @Test
    fun `beyond three players joining at once, the others get one line`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            val players = (1..4).map { PlayerRef(UUID(5, it.toLong()), "Joueur$it") }
            players.forEach { f.sheets.sheets[it.uuid] = SheetSamples.reference.copy(player = it) }
            val lines = f.joins.lines(players.map { f.join(it) }, f.chapter(), f.now)
            assertEquals(4, lines.count { it.startsWith("SHEET ") })
            assertEquals(3, lines.count { it.startsWith("  Recap") })
            assertTrue(lines.last().startsWith("SHEET Joueur4 (joined): Joueur4 · « le Plongeur » · 61h played"), lines.last())
        }
    }

    @Test
    fun `a sheet that cannot be read says so, and the other joins are attached`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            f.sheets.failing += bartok.uuid
            val chapter = f.chapter()
            val lines = f.joins.lines(listOf(f.join(bartok), f.join(mimosa)), chapter, f.now)
            assertEquals(
                "SHEET Bartok (joined; away 1d22h, last session 10-01 20:10–21:52): (sheet unavailable: the sheet of ${bartok.uuid} is locked)",
                lines.first(),
            )
            assertTrue(lines.any { it.startsWith("SHEET Mimosa (joined; away 21h00m, last session 10-02 20:00–23:00):") }, lines.joinToString("\n"))
            assertEquals(setOf(mimosa.uuid), chapter.sheetsShownAt.keys, "a sheet not shown can be shown later")
        }
    }

    // ── The <memory> block ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the reminiscences come after the sheets`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chapter = f.chapter()
            val death = f.signal(PlayerDied(mimosa, HOME, "minecraft:lava", null, null, null, 5, false, false))
            val block = f.attachments.block(listOf(f.join(bartok), death), chapter, f.now)!!.lines()
            assertTrue(block[1].startsWith("SHEET Bartok (joined"), block[1])
            assertEquals(
                "RECALLED for DEATH Mimosa: M-120 · 10-01 21:40 (1d ago) · Bartok, Mimosa · ★7 · Mort dans la lave du Nether: « Bartok est mort " +
                    "dans un lac de lave du Nether en creusant vers des débris antiques, devant Mimosa. »",
                block[block.size - 2],
            )
            assertTrue(Ref(RefKind.MEMORY, 120) in chapter.shownRefs)
        }
    }

    @Test
    fun `a memory too slow to attach leaves a line for each sheet it would have shown`() {
        BriefingFixture().use { f ->
            val chapter = f.chapter()
            chapter.sheetsShownAt[mimosa.uuid] = f.now // the briefing of this wake-up showed hers
            val signals = listOf(f.join(bartok), f.join(mimosa, at = NOW.minusSeconds(3)), f.join(kelvin, firstTime = true))
            assertEquals(
                "<memory>\n(the sheet of Bartok could not be read in time: player_profile Bartok)\n</memory>",
                WakeAttachments.late(signals, chapter, f.now),
            )
            assertNull(WakeAttachments.late(listOf(f.join(kelvin, firstTime = true), f.join(bartok), f.leave(bartok)), chapter, f.now), "a newcomer has no sheet")
        }
    }

    @Test
    fun `nothing to attach gives no block`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            evening(f)
            val chat = f.signal(ChatMessage(bartok, ChatChannel.PUBLIC, "on va miner ?", emptyList()))
            assertNull(f.attachments.block(listOf(chat), f.chapter(), f.now))
            assertNull(f.attachments.block(emptyList(), f.chapter(), f.now))
        }
    }
}
