package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.HOME
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.bartok
import io.github.clemsix6.narrator2.memory.BriefingFixture.Companion.mimosa
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * M3 §12.3: the memory has no voice. Every memory section of the briefing, and the `<memory>` block of a
 * join, filled with test data, pass [VoiceGuard] once their quotes are left out (M3-13).
 *
 * Player names are data, not a voice: a player may be called `Tu` or `Je` (M3-07), and their name stands
 * outside any quote in the sheets and the lines. The check masks the names of the players it knows.
 */
class BriefingVoiceTest {
    /** [text] with the names of [players] replaced by a neutral word, then checked: null when it has no voice. */
    private fun voice(text: String, field: String, players: List<PlayerRef>): String? {
        val masked = players.fold(text) { acc, player -> acc.replace(Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(player.name)}(?![\\p{L}\\p{N}_])"), "Joueur") }
        return VoiceGuard.check(masked, field, Int.MAX_VALUE)
    }

    @Test
    fun `every memory section of the briefing passes VoiceGuard, in both forms`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            val sections = f.briefing.sections(f.chapter(), f.now)
            assertEquals(6, sections.size)
            for (section in sections) {
                assertTrue(section.lines.size > 1 || section.tag == "grimoire", "${section.tag} is filled")
                assertNull(VoiceGuard.check(section.lines.joinToString("\n"), section.tag, Int.MAX_VALUE), section.lines.joinToString("\n"))
                section.reduced?.let { assertNull(VoiceGuard.check(it.joinToString("\n"), "${section.tag} reduced", Int.MAX_VALUE)) }
                assertNull(VoiceGuard.check(section.attributes.ifEmpty { "-" }, "${section.tag} attributes", Int.MAX_VALUE))
            }
        }
    }

    @Test
    fun `the memory block of a join and its reminiscences pass VoiceGuard`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            BriefingSamples.evening(f)
            f.session(800, bartok, Instant.parse("2026-10-01T20:10:00Z"), Instant.parse("2026-10-01T21:52:00Z"))
            val chapter = f.chapter()
            val death = f.signal(PlayerDied(mimosa, HOME, "minecraft:lava", null, null, null, 5, false, false))
            val block = f.attachments.block(listOf(f.join(bartok), death), chapter, f.now)!!
            val body = block.removePrefix("<memory>\n").removeSuffix("\n</memory>")
            assertTrue(body.contains("RECALLED for DEATH Mimosa") && body.contains("Dream hint"), body)
            assertNull(VoiceGuard.check(body, "memory", Int.MAX_VALUE), body)

            chapter.sheetsShownAt[bartok.uuid] = f.now - 60_000
            val rejoined = f.joins.lines(listOf(f.join(bartok, session = 903)), chapter, f.now).single()
            assertNull(VoiceGuard.check(rejoined, "rejoin", Int.MAX_VALUE), rejoined)
            assertNull(VoiceGuard.check(f.joins.lines(listOf(f.join(BriefingFixture.kelvin, firstTime = true)), chapter, f.now).single(), "first", Int.MAX_VALUE))
        }
    }

    @Test
    fun `a player named like a pronoun is a name, not a voice`() = runBlocking<Unit> {
        BriefingFixture().use { f ->
            val tu = PlayerRef(UUID(3, 1), "Tu")
            f.online = listOf(tu)
            f.sheets.sheets[tu.uuid] = SheetSamples.reference.copy(player = tu, formerNames = emptyList())
            val sheets = f.sections().getValue("sheets").lines.joinToString("\n")
            assertTrue(sheets.startsWith("Tu · « le Plongeur »"), "the briefing never refuses a name: $sheets")
            assertNotNull(VoiceGuard.check(sheets, "sheets", Int.MAX_VALUE), "VoiceGuard alone would take the name for the Narrator's voice")
            assertNull(voice(sheets, "sheets", listOf(tu)), "the name is the only « tu » there")

            val joined = f.joins.lines(listOf(f.join(tu)), f.chapter("director-c15"), f.now).joinToString("\n")
            assertTrue(joined.startsWith("SHEET Tu (joined):"), joined)
            assertNull(voice(joined, "join", listOf(tu)))
        }
    }
}
