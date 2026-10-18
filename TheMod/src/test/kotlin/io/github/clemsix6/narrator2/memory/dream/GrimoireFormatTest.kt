package io.github.clemsix6.narrator2.memory.dream

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The form of the Grimoire (M3 §8.4): seven sections in order, none empty, within the budget, factual. */
class GrimoireFormatTest {
    private fun grimoire(vararg sections: Pair<String, String>): String = sections.joinToString("\n") { (heading, body) -> "## $heading\n$body" }

    private val valid = grimoire(
        "Players" to "- Bartok : provocateur qui revient quémander ; ses châtiments font rire tout le monde.\n- Mimosa : polie, prie souvent.",
        "Running gags" to "- « le Plongeur » (C-12) : usé, à laisser reposer.",
        "Arcs" to "- C-60 « La Dent du Juge » : actif, deuxième acte.",
        "What works" to "- Les formulaires administratifs font rire Mimosa.",
        "What falls flat" to "- Les commentaires sportifs sur les morts.",
        "Plans" to "- Prochaine session de Mimosa : un défi à l'arc.",
        "Watch out" to "- aucun",
    )

    @Test
    fun `the seven sections in order, each with a line, are a Grimoire`() {
        assertEquals(emptyList(), GrimoireFormat.problems(valid))
        assertEquals(emptyList(), GrimoireFormat.problems("\n$valid\n\n"), "blank lines around it")
        assertEquals(emptyList(), GrimoireFormat.problems(GrimoireFormat.SECTIONS.joinToString("\n") { "## $it\n- aucun" }))
    }

    @Test
    fun `every problem is listed at once, each with its fix`() {
        val broken = grimoire(
            "Players" to "- Bartok.", "Arcs" to "- aucun", "Running gags" to "\n", "What works" to "- x", "What falls flat" to "- y", "Plans" to "- z", "Watch out" to "- w",
        )
        assertEquals(
            listOf(
                "sections: out of order; keep this order: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Plans, ## Watch out",
                "sections: \"## Running gags\" has no line; write at least one (« - aucun » when there is nothing)",
            ),
            GrimoireFormat.problems(broken),
        )
        val missing = GrimoireFormat.problems("Introduction.\n## Players\n- Bartok.\n# Notes\n- x\n## Players\n- encore")
        assertEquals(4, missing.size, "$missing")
        assertTrue(missing[0].startsWith("headings: \"# Notes\" is not a section; the only headings are ## Players, "), missing[0])
        assertTrue(missing[1].startsWith("sections: ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Plans, ## Watch out missing"), missing[1])
        assertEquals("sections: ## Players written twice; each section once", missing[2])
        assertEquals("text: it starts with ## Players, nothing before it", missing[3])
    }

    @Test
    fun `the voice, the chevrons and the budget are checked like any text of the memory`() {
        val voice = GrimoireFormat.problems(valid.replace("- Les formulaires administratifs font rire Mimosa.", "- Mes formulaires font rire Mimosa."))
        assertEquals(1, voice.size)
        assertTrue(voice.single().startsWith("text must be factual and in the third person"), voice.single())
        assertEquals(listOf("text must not contain < or >: write the words instead"), GrimoireFormat.problems(valid.replace("aucun", "<aucun>")))
        val long = GrimoireFormat.problems(valid.replace("- aucun", "- " + "a".repeat(10_000)))
        assertTrue(long.single().startsWith("text is longer than 10000 characters"), long.single())
        assertEquals(listOf("text must not be empty", "sections: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Plans, ## Watch out missing; write all seven: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Plans, ## Watch out"), GrimoireFormat.problems(" "))
    }
}
