package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator forge …` and `/narrator module …` (M5 §14): the arsenal, the approval of a version, the Forge's switch
 * (`off`/`on`), the running modules and their stop. Already attached under `/narrator`; empty until task M5-14
 * fills it. They replace the `/narrator module list | unload | unload-all` of `ARCHITECTURE.md` §13.
 */
object ForgeCommands {
    /** Adds `forge` and `module` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {}
}
