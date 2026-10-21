package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * `/narrator dashboard` and `/narrator dashboard reset` (M6 §13): the dashboard's login link, or why there is no
 * dashboard; a new token. Already attached under `/narrator`; empty until the dashboard's server fills it.
 */
object DashboardCommands {
    /** Adds `dashboard` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {}
}
