package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.speech.Speech
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * `/duel accept <id>` and `/duel decline <id>`, for every player (M4 §5.5, §18.2): the answer to a duel's
 * invitation (`ChallengeControl.answerDuel`), suggesting the player's own invitations. The invitation's buttons run
 * these commands. English literals, no alias (decision F); forbidden to `run_command`. What the player reads comes
 * from `UiTexts`.
 */
object DuelCommand {
    /**
     * Registers `/duel` (its own root: every player may use it). [control] gives the challenges it answers to: the
     * running server's engine, null while the Narrator does not run; a GameTest gives its own engine's.
     */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, control: () -> ChallengeControl? = ::running) {
        dispatcher.register(
            Commands.literal("duel")
                .then(Commands.literal("accept").then(id(control, accept = true)))
                .then(Commands.literal("decline").then(id(control, accept = false))),
        )
    }

    private fun id(control: () -> ChallengeControl?, accept: Boolean): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("id", StringArgumentType.word())
            .suggests { context, builder ->
                val player = context.source.player
                if (player != null) {
                    control()?.list()
                        ?.filter { view -> view.duel != null && view.status == ChallengeStatus.INVITED && view.participants.any { it.uuid == player.uuid.toString() } }
                        ?.forEach { builder.suggest(it.ref.toString()) }
                }
                builder.buildFuture()
            }
            .executes { answer(it.source, StringArgumentType.getString(it, "id"), accept, control) }

    private fun answer(source: CommandSourceStack, id: String, accept: Boolean, control: () -> ChallengeControl?): Int {
        val player = ownPlayer(source, "answer a duel") ?: return 0
        val challenges = control()
        if (challenges == null) {
            source.sendFailure(errorLine("the Narrator is not running, or the server is stopping."))
            return 0
        }
        val reply = Ref.canonOf(id)?.let { challenges.answerDuel(player, it, accept) } ?: unknown(id)
        source.sendSuccess({ reply }, false)
        return 1
    }

    /** `command.unknown_challenge` for an id that names no challenge at all. */
    private fun unknown(id: String): Component {
        val ui = NarratorRuntime.current?.settings?.get()?.ui ?: return Component.literal("no challenge $id")
        return Speech.chatLine(ui, ui.format("command.unknown_challenge", "id" to id), private = true)
    }

    private fun running(): ChallengeControl? = NarratorRuntime.current?.takeIf { it.phase == Phase.RUNNING }?.mechanics?.challenges
}
