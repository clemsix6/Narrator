package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.memory.WriteGrimoireTool
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The form of the Grimoire (M3 §8.4, M6 §2.6): six sections in order, none empty; its budget and voice are the store's. */
class GrimoireFormatTest {
    private fun grimoire(vararg sections: Pair<String, String>): String = sections.joinToString("\n") { (heading, body) -> "## $heading\n$body" }

    private val valid = grimoire(
        "Players" to "- Bartok : provocateur qui revient quémander ; ses châtiments font rire tout le monde.\n- Mimosa : polie, prie souvent.",
        "Running gags" to "- « le Plongeur » (C-12) : usé, à laisser reposer.",
        "Arcs" to "- C-60 « La Dent du Juge » : actif, deuxième acte.",
        "What works" to "- Les formulaires administratifs font rire Mimosa.",
        "What falls flat" to "- Les commentaires sportifs sur les morts.",
        "Watch out" to "- aucun",
    )

    @Test
    fun `the six sections in order, each with a line, are a Grimoire, and Plans is none of them`() {
        assertEquals(emptyList(), GrimoireFormat.problems(valid))
        assertEquals(emptyList(), GrimoireFormat.problems("\n$valid\n\n"), "blank lines around it")
        assertEquals(emptyList(), GrimoireFormat.problems(GrimoireFormat.SECTIONS.joinToString("\n") { "## $it\n- aucun" }))
        assertEquals(listOf("Players", "Running gags", "Arcs", "What works", "What falls flat", "Watch out"), GrimoireFormat.SECTIONS)

        // M6: what comes next is the storyteller's programme; a Grimoire of M3 with its plans is refused, with the fix.
        val withPlans = valid.replace("## Watch out", "## Plans\n- Un défi à l'arc pour Mimosa.\n## Watch out")
        assertEquals(
            listOf("headings: \"## Plans\" is not a section; the only headings are ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out"),
            GrimoireFormat.problems(withPlans),
        )
    }

    @Test
    fun `every problem is listed at once, each with its fix`() {
        val broken = grimoire(
            "Players" to "- Bartok.", "Arcs" to "- aucun", "Running gags" to "\n", "What works" to "- x", "What falls flat" to "- y", "Watch out" to "- w",
        )
        assertEquals(
            listOf(
                "sections: out of order; keep this order: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out",
                "sections: \"## Running gags\" has no line; write at least one (« - aucun » when there is nothing)",
            ),
            GrimoireFormat.problems(broken),
        )
        val missing = GrimoireFormat.problems("Introduction.\n## Players\n- Bartok.\n# Notes\n- x\n## Players\n- encore")
        assertEquals(4, missing.size, "$missing")
        assertTrue(missing[0].startsWith("headings: \"# Notes\" is not a section; the only headings are ## Players, "), missing[0])
        assertTrue(missing[1].startsWith("sections: ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out missing"), missing[1])
        assertEquals("sections: ## Players written twice; each section once", missing[2])
        assertEquals("text: it starts with ## Players, nothing before it", missing[3])
    }

    @Test
    fun `the voice, the chevrons and the budget are left to the store, which checks them when the Grimoire is written`() {
        val voice = valid.replace("- Les formulaires administratifs font rire Mimosa.", "- Mes formulaires font rire Mimosa.")
        val chevrons = valid.replace("aucun", "<aucun>")
        val long = valid.replace("- aucun", "- " + "a".repeat(10_000))
        listOf(voice, chevrons, long).forEach { assertEquals(emptyList(), GrimoireFormat.problems(it), "only the form is checked here") }
        assertEquals(
            listOf("sections: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out missing; write all six: ## Players, ## Running gags, ## Arcs, ## What works, ## What falls flat, ## Watch out"),
            GrimoireFormat.problems(" "),
        )

        // The tool of the Dreamer: the form first, then the store refuses what is not factual, in the third person, without chevrons.
        DreamerFixture().use { f ->
            fun write(text: String) = f.call(WriteGrimoireTool(), """{"text": ${JsonPrimitive(text)}}""")
            val refused = write(voice)
            assertEquals(ToolStatus.INVALID, refused.status, refused.text)
            assertTrue(refused.text.startsWith("Invalid arguments: text must be factual and in the third person"), refused.text)
            assertTrue(write(chevrons).text.startsWith("Invalid arguments: text must not contain < or >"))
            assertEquals(0L, f.count("memory_docs"), "nothing written")
            assertEquals(ToolStatus.OK, write(valid).status)
            assertEquals(1L, f.count("memory_docs"))
        }
    }
}
