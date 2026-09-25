package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator protect <player> [on|off]` (M1_M2 §14.5).
 *
 * Provisional body (T02): no subcommand yet. T21 writes it.
 */
object GuardCommands {
    /** Adds this module's subcommands under `/narrator`. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) = Unit
}
