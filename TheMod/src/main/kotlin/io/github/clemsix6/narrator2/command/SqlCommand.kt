package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator sql <query>` (M1_M2 §6.6): a read-only query on the Journal, run off the server thread
 * ([replyLater]), one gray line per row.
 *
 * Provisional body (T02): no subcommand yet. T04 writes it.
 */
object SqlCommand {
    /** Adds this module's subcommands under `/narrator`. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) = Unit
}
