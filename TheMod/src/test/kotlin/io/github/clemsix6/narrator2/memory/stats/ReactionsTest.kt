package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.DAY2
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.day2
import io.github.clemsix6.narrator2.memory.stats.Reactions.Kind
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** How players took the Narrator's lines (M3 §6.4), on the day 2 of [MemoryDataset]. */
class ReactionsTest {
    private val words = HeuristicWords.bundled("fr")!!

    @Test
    fun `laughed, annoyed, replied or ignored, offline players and operator lines left out`() {
        MemoryDataset.journal().use { fixture ->
            val reactions = MemoryDataset.read(fixture) { Reactions.read(it, DAY2, NOW, words) }
            assertEquals(
                listOf(
                    // « mdr » one minute later: a laugh of the heuristic's word list.
                    Triple("Bartok, ta pioche pleure.", "Bartok", Kind.LAUGHED),
                    // Online since 20:10, silent.
                    Triple("Kelvin, tu dors ?", "Kelvin", Kind.IGNORED),
                    // A private line (recipients), answered with a hostile message.
                    Triple("Ta prière est refusée.", "Bartok", Kind.ANNOYED),
                    // « Mimosa, reviens. »: Mimosa is offline, no reaction. One line, two players named (any case).
                    Triple("Bartok et kelvin, au travail.", "Bartok", Kind.REPLIED),
                    // The reflex model's humor of 0.7.
                    Triple("Bartok et kelvin, au travail.", "Kelvin", Kind.LAUGHED),
                ),
                reactions.map { Triple(it.line, it.player.name, it.kind) },
            )
            assertEquals(day2("20:20"), reactions.first().ts)
            assertEquals("laughed 2 · annoyed 1 · replied 1 · ignored 1", Reactions.summary(reactions))
            assertEquals("none", Reactions.summary(emptyList()))
            // A window without any Narrator line.
            assertEquals(emptyList(), MemoryDataset.read(fixture) { Reactions.read(it, day2("20:00"), day2("20:10"), words) })
        }
    }
}
