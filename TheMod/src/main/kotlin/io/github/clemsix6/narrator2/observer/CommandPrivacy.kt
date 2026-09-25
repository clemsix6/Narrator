package io.github.clemsix6.narrator2.observer

import com.mojang.brigadier.ParseResults
import com.mojang.brigadier.context.CommandContextBuilder
import io.github.clemsix6.narrator2.event.CommandIssued

/**
 * What the Journal keeps of a command a player typed. The model reads `events` through `sql`, and a
 * private message between players must never reach it (ARCHITECTURE §17, decision 7), whatever the
 * command that carries it: `msg` itself, `execute as … run tell …`, another mod's `/r`, an alias.
 *
 * So the text is kept only when every command the line runs is one of [CommandIssued.TEXT_KEPT_ROOTS]
 * (vanilla commands, none of them a private message) and none is withheld by the configuration. "Every
 * command the line runs" is read from the server's own parse: the head of each segment (the command
 * itself, and what each `execute … run` or `return run` runs) and each command a redirect lands in
 * (`tell` → `msg`, an alias of another mod). The root alone is always journaled.
 */
internal object CommandPrivacy {
    /**
     * The text to journal for [line] (a command without its `/`), or null to withhold it. [withheld] are
     * bare roots ([bare]). [parse] parses [line] with the server's dispatcher and the player's own source;
     * it is only called when the root may keep its text, so a withheld command is never parsed here.
     * A line that does not parse to its end is withheld: what follows the error was never analysed, and
     * the line runs nothing anyway.
     */
    fun <S> text(line: String, withheld: Set<String>, parse: () -> ParseResults<S>): String? {
        if (!kept(line.substringBefore(' '), withheld)) return null
        val parsed = parse()
        if (parsed.reader.canRead() || parsed.exceptions.isNotEmpty()) return null
        val root = parsed.context.rootNode
        var context: CommandContextBuilder<S>? = parsed.context
        while (context != null) {
            val command = when {
                // A segment: the command typed, or the one an `execute … run` runs.
                context.rootNode === root -> context.nodes.firstOrNull()?.node?.name
                // A redirect into a command: `tell` → `msg`, `execute as …` → `execute`.
                root.getChild(context.rootNode.name) === context.rootNode -> context.rootNode.name
                // A redirect deeper into another command (another mod's alias): not worth mapping, withheld.
                else -> null
            }
            if (command == null || !kept(command, withheld)) return null
            context = context.child
        }
        return line
    }

    /** A command root without its namespace, lowercase: `Minecraft:MSG` → `msg`. */
    fun bare(root: String): String = root.lowercase().substringAfter(':')

    private fun kept(root: String, withheld: Set<String>): Boolean = bare(root).let { it in CommandIssued.TEXT_KEPT_ROOTS && it !in withheld }
}
