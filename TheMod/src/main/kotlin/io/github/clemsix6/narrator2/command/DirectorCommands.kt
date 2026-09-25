package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator pause`, `resume`, `say <text>`, `whisper <instruction>`, `chapter close` (M1_M2 §14.5).
 *
 * Provisional body (T02): no subcommand yet. T21 writes it.
 */
object DirectorCommands {
    /** Adds this module's subcommands under `/narrator`. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) = Unit
}
