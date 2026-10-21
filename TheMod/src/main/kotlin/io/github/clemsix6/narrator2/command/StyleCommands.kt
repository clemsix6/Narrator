package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator style` (M6 §13): the `Style:` line, the diversity of the Director's latest lines and the latest flags of
 * tone. Already attached under `/narrator`; empty until the style's report fills it.
 */
object StyleCommands {
    /** Adds `style` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {}
}
