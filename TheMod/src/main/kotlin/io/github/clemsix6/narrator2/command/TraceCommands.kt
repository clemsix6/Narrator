package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator trace [n]`, `trace turn <id>`, `trace task <id>`, `why` (M1_M2 §14.4), read from the
 * Journal off the server thread ([replyLater]).
 *
 * Provisional body (T02): no subcommand yet. T21 writes it.
 */
object TraceCommands {
    /** Adds this module's subcommands under `/narrator`. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) = Unit
}
