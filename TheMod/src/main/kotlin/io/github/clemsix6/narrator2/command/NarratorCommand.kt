package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * The Narrator's commands (M1_M2 §14.5, M4 §18, M5 §14, M6 §13):`/narrator …` for game masters (permission level 2), built
 * from one module per group of subcommands, each in its own file and owned by its own task, and the
 * players' own commands, each with its own root: `/pray`, `/duel`, `/bet`, `/offer`. [register] runs at
 * each command registration (the server builds its dispatcher at startup and on `/reload`); commands reach
 * the running server's Narrator through `NarratorRuntime.current` (see [runningNarrator]).
 */
object NarratorCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = Commands.literal("narrator").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        AdminCommands.attach(root)
        SqlCommand.attach(root)
        DirectorCommands.attach(root)
        GuardCommands.attach(root)
        TraceCommands.attach(root)
        MemoryCommands.attach(root)
        MechanicsCommands.attach(root)
        ForgeCommands.attach(root)
        StyleCommands.attach(root)
        DashboardCommands.attach(root)
        RelicsCommands.attach(root)
        dispatcher.register(root)
        PrayCommand.register(dispatcher)
        DuelCommand.register(dispatcher)
        BetCommand.register(dispatcher)
        OfferCommand.register(dispatcher)
    }
}
