package io.github.clemsix6.narrator2.memory

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** References shown to the model (M3 §3.1) and the controlled vocabularies (§3.6). */
class RefTest {
    @Test
    fun `a ref reads back from what the model writes`() {
        assertEquals(Ref(RefKind.CANON, 45), Ref.parse("C-45"))
        assertEquals(Ref(RefKind.CANON, 45), Ref.parse("c45"))
        assertEquals(Ref(RefKind.MEMORY, 812), Ref.parse(" M-812 "))
        assertEquals(Ref(RefKind.INTERACTION, 7), Ref.parse("i-007"))
        assertEquals(Ref(RefKind.CHRONICLE, 88), Ref.parse("H-88"))
        assertEquals(Ref(RefKind.NOTE, 9), Ref.parse("N9"))
        RefKind.entries.forEach { kind -> assertEquals(Ref(kind, 3), Ref.parse(Ref(kind, 3).toString())) }
    }

    @Test
    fun `anything else is no ref`() {
        listOf("", "C", "C-", "45", "C-0", "C--45", "T-3", "R-2", "e1234", "t88", "C-45x", "CC-45", "C 45", "C-4 5", "C-99999999999999999999")
            .forEach { assertNull(Ref.parse(it), it) }
    }

    @Test
    fun `a challenge, a duel or a rule is named by its canon reference, or its number`() {
        val c50 = Ref(RefKind.CANON, 50)
        for (typed in listOf("C-50", "c50", "50", " 50 ")) assertEquals(c50, Ref.canonOf(typed), typed)
        for (typed in listOf("0", "-3", "M-50", "O-812", "fifty", "")) assertNull(Ref.canonOf(typed), typed)
    }

    @Test
    fun `a ref shows as prefix and id`() {
        assertEquals("C-45", Ref(RefKind.CANON, 45).toString())
        assertEquals(listOf("M", "C", "I", "H", "N", "P"), RefKind.entries.map { it.prefix })
        assertEquals(setOf("memory", "canon", "interaction", "chronicle"), RefKind.searchable.map { it.id }.toSet())
        assertFailsWith<IllegalArgumentException> { Ref(RefKind.MEMORY, 0) }
    }

    /** M6 §6.2: an idea is cited, indexed against repeats, never recalled nor taken for evidence. */
    @Test
    fun `an idea is indexed but neither searched nor a fact`() {
        assertEquals(Ref(RefKind.IDEA, 41), Ref.parse("P-41"))
        assertEquals(RefKind.searchable + RefKind.IDEA, RefKind.indexed)
        assertEquals(RefKind.entries.toSet() - RefKind.IDEA, RefKind.facts)
        assertNull(Ref.canonOf("P-41"))
    }

    @Test
    fun `the author names its actor`() {
        assertEquals("director", Author(Source.DIRECTOR, turnId = 3).actor)
        assertEquals("chronicler", Author(Source.CHRONICLER, runId = 7).actor)
        assertEquals("operator:Clement", Author(Source.OPERATOR, operator = "Clement").actor)
        assertEquals(Author(Source.DREAMER, runId = 4, conversationId = "dreamer-1"), RunContext(4, RunKind.DREAM, null, null, null).author("dreamer-1"))
        assertEquals(Source.CHRONICLER, RunContext(5, RunKind.NAP, null, null, "director-c1").author("chronicler-2").source)
        assertEquals("storyteller", Author(Source.STORYTELLER, runId = 4).actor)
    }

    @Test
    fun `canon statuses move as the vocabulary says`() {
        val promise = CanonKind.PROMISE
        assertTrue(promise.allows(CanonStatus.ACTIVE, CanonStatus.FULFILLED))
        assertFalse(promise.allows(CanonStatus.ACTIVE, CanonStatus.WON), "a promise cannot be won")
        assertFalse(promise.allows(CanonStatus.FULFILLED, CanonStatus.BROKEN), "a closed entry never moves again")
        assertTrue(CanonKind.CHALLENGE.allows(CanonStatus.ACTIVE, CanonStatus.LOST))
        assertTrue(CanonKind.ARC.allows(CanonStatus.PLANNED, CanonStatus.ACTIVE))
        assertFalse(CanonKind.LAW.allows(CanonStatus.PLANNED, CanonStatus.ACTIVE))
        assertTrue(CanonKind.LAW.allows(CanonStatus.ACTIVE, CanonStatus.RETIRED))
        assertEquals(setOf(CanonStatus.PLANNED, CanonStatus.ACTIVE), CanonKind.ARC.startsAs)
        assertEquals(setOf(CanonStatus.ACTIVE), CanonKind.NICKNAME.startsAs)
        assertEquals(CanonKind.entries.filter { it.dueAllowed }.toSet(), setOf(CanonKind.PROMISE, CanonKind.THREAT, CanonKind.PROPHECY, CanonKind.CHALLENGE, CanonKind.ARC))
        CanonKind.entries.forEach { kind -> assertTrue(kind.closedBy.none { it.open }, "$kind closes") }
        assertEquals(setOf(CanonKind.NICKNAME, CanonKind.TITLE), CanonKind.entries.filter { it.onePerPlayer }.toSet())
    }

    @Test
    fun `interactions count and disrespect as the vocabulary says`() {
        assertTrue(InteractionKind.INSULT.isDisrespect(-1), "an insult is always a lack of respect")
        assertTrue(InteractionKind.CONVERSATION.isDisrespect(-2))
        assertFalse(InteractionKind.CONVERSATION.isDisrespect(-1))
        assertFalse(InteractionKind.GRIEF.isDisrespect(-3), "a grief wrongs another player")
        assertFalse(InteractionKind.PUNISHMENT.isDisrespect(-3), "the Narrator's answer is not the player's conduct")
        assertEquals(setOf(InteractionKind.PUNISHMENT, InteractionKind.BLESSING, InteractionKind.GIFT), InteractionKind.entries.filterNot { it.counts }.toSet())
    }

    @Test
    fun `tags, traits and document keys follow the vocabulary`() {
        listOf("death", "lava", "running_gag", "gag:plongeon_olympique", "arc:C-60").forEach { assertTrue(MemoryTags.isValid(it), it) }
        listOf("Death", "minecraft:lava", "gag:", "gag:Plongeon", "arc:C-0", "arc:M-60", "mort").forEach { assertFalse(MemoryTags.isValid(it), it) }

        assertEquals(TraitAxis.BOLDNESS, TraitAxis.of("boldness"))
        assertEquals("reckless", TraitAxis.BOLDNESS.pole(1))
        assertEquals("cautious", TraitAxis.BOLDNESS.pole(-1))
        listOf("boldness", "patience", "free:obsede_par_le_butin").forEach { assertTrue(TraitAxis.isTrait(it), it) }
        listOf("Boldness", "reckless", "free:", "free:Butin", "free:a").forEach { assertFalse(TraitAxis.isTrait(it), it) }

        val player = UUID.fromString("00000000-0000-0000-0000-00000000b471")
        val sheet = DocKey.Sheet(player, SheetSection.SINCE_LAST)
        assertEquals("sheet:00000000-0000-0000-0000-00000000b471:since_last", sheet.key)
        listOf(DocKey.Grimoire, DocKey.Review, sheet).forEach { assertEquals(it, DocKey.parse(it.key)) }
        listOf("Grimoire", "sheet:x:recap", "sheet:$player", "sheet:$player:notes", "sheet:$player:recap:2").forEach { assertNull(DocKey.parse(it), it) }

        assertEquals(ChronicleLevel.SESSION, ChronicleLevel.SEASON.below)
        assertNull(ChronicleLevel.CHAPTER.below)
        assertEquals(CanonKind.RUNNING_GAG, enumOfId<CanonKind>("running_gag"))
        assertNull(enumOfId<CanonKind>("RUNNING_GAG"))
    }
}
