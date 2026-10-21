package io.github.clemsix6.narrator2.memory.dream

/**
 * The form of the Grimoire (M3 §8.4, M6 §2.6), checked before it is written. The Grimoire is the Narrator's compact
 * core memory, always in its briefing, rewritten whole by every dream within the same budget:
 *
 * - the six sections of [SECTIONS], each as a `## ` heading in English, in this order, nothing before the
 *   first one and no other heading (a line starting with `#`);
 * - at least one non-blank line under each heading (`- aucun` when there is nothing to say).
 *
 * The Grimoire looks back (the Dreamer's pen): what comes next is the storyteller's programme, not a section of it.
 *
 * Its budget, its chevrons and its voice are left to the store: `MemoryStore.writeDoc` checks them like
 * any document's, with the names of the players masked first (a player called `Moi` is not the Narrator's
 * voice). A check of the voice here, without the names, would refuse a Grimoire that names such a player.
 *
 * [problems] lists every problem of form at once, for the model to fix them all in one go.
 */
object GrimoireFormat {
    /** The sections of the Grimoire, in order: their headings are `## Players`, `## Running gags`… */
    val SECTIONS: List<String> = listOf("Players", "Running gags", "Arcs", "What works", "What falls flat", "Watch out")

    /** Every problem of form of [text] as a Grimoire, in English, each with its fix; empty when the store may check the rest. */
    fun problems(text: String): List<String> = buildList {
        val lines = text.lines()
        val headings = lines.withIndex().filter { it.value.trimStart().startsWith("#") }
        val names = headings.map { it.value.trim().removePrefix("##").trim() }
        val unknown = headings.filter { heading -> heading.value.trim().let { !it.startsWith("## ") || it.removePrefix("## ").trim() !in SECTIONS } }
        if (unknown.isNotEmpty()) {
            add("headings: ${unknown.joinToString { "\"${it.value.trim().take(40)}\"" }} ${if (unknown.size > 1) "are" else "is"} not a section; the only headings are ${headingList()}")
        }
        val missing = SECTIONS.filter { it !in names }
        if (missing.isNotEmpty()) add("sections: ${missing.joinToString { "## $it" }} missing; write all six: ${headingList()}")
        val repeated = SECTIONS.filter { section -> names.count { it == section } > 1 }
        if (repeated.isNotEmpty()) add("sections: ${repeated.joinToString { "## $it" }} written twice; each section once")
        val order = names.filter { it in SECTIONS }.distinct()
        if (missing.isEmpty() && repeated.isEmpty() && order != SECTIONS) add("sections: out of order; keep this order: ${headingList()}")
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first >= 0 && headings.firstOrNull()?.index != first) add("text: it starts with ## ${SECTIONS.first()}, nothing before it")
        val empty = headings.withIndex().filter { (i, heading) ->
            val end = headings.getOrNull(i + 1)?.index ?: lines.size
            lines.subList(heading.index + 1, end).all { it.isBlank() }
        }.map { it.value.value.trim() }
        if (empty.isNotEmpty()) add("sections: ${empty.joinToString { "\"$it\"" }} ${if (empty.size > 1) "have" else "has"} no line; write at least one (« - aucun » when there is nothing)")
    }

    private fun headingList(): String = SECTIONS.joinToString(", ") { "## $it" }
}
