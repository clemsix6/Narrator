package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator challenge list|cancel <id>`, `/narrator rule list|remove <id>` and `/narrator traps [restore]`
 * (M4 §18.1): what the mechanics engine runs, and the operators' ways to stop it. Gray English lines, 20 at
 * most; each command that acts writes an `operator` decision. Filled by M4-21.
 */
object MechanicsCommands {
    /** Adds this module's subcommands under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {}
}
