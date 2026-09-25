package io.github.clemsix6.narrator2.tool

/**
 * The tool set of [tools] (M1_M2 §8.6): sorted by name, each with the definition generated from its
 * arguments by [ToolSchema]. The same tools always give the same bytes, whatever their order here, so
 * the cached prefix of a conversation stays valid. Fails, naming the tool and the argument, on a
 * duplicate name or an argument type the schema cannot describe.
 *
 * Choosing the tools of a conversation (role, what is wired) is `ToolCatalog.toolSetFor`'s job.
 */
fun ToolSet.Companion.of(tools: List<NarratorTool<*>>): ToolSet {
    val sorted = tools.sortedBy { it.name }
    return ToolSet(sorted, sorted.map(ToolSchema::definition))
}
