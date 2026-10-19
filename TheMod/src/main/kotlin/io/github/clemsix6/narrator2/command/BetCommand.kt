package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack

/**
 * `/bet <id> <player|nobody>`, for every player (M4 §5.6, §18.2): bets the stack held in the main hand on a
 * participant of a challenge or a duel, or on nobody (`ChallengeControl.bet`), suggesting the challenges open
 * to bets and their participants. English literals, no alias (decision F); forbidden to `run_command`. What
 * the player reads comes from `UiTexts`. Filled by M4-20.
 */
object BetCommand {
    /** Registers `/bet` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {}
}
