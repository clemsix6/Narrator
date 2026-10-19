package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack

/**
 * `/duel accept <id>` and `/duel decline <id>`, for every player (M4 §5.5, §18.2): the answer to a duel's
 * invitation (`ChallengeControl.answerDuel`), suggesting the player's own invitations. English literals, no
 * alias (decision F); forbidden to `run_command`. What the player reads comes from `UiTexts`. Filled by M4-19.
 */
object DuelCommand {
    /** Registers `/duel` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {}
}
