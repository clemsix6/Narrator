package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.challenge.BetSettlement
import io.github.clemsix6.narrator2.mechanics.challenge.Bets
import io.github.clemsix6.narrator2.runtime.NarratorServices
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/bet <id> <player|nobody>`, for every player (M4 §5.6, §18.2): bets the stack held in the main hand on a
 * participant of a challenge or a duel, or on nobody (`ChallengeControl.bet`), suggesting the challenges open
 * to bets and what the player may bet on (a participant, on themselves only). Only the player who sends it bets
 * ([ownPlayer]: never through `execute as` nor a command block). English literals, no alias (decision F);
 * forbidden to `run_command`. What the player reads comes from `UiTexts`.
 */
object BetCommand {
    /** Registers `/bet` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("bet").then(
                Commands.argument("id", StringArgumentType.word()).suggests(CHALLENGES).then(
                    Commands.argument("on", StringArgumentType.word()).suggests(OPTIONS).executes { context ->
                        bet(context.source, StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "on"))
                    },
                ),
            ),
        )
    }

    private fun bet(source: CommandSourceStack, id: String, on: String): Int {
        val player = ownPlayer(source, "bet") ?: return 0
        val runtime = runningNarrator(source) ?: return 0
        val reply = bet(runtime, player, id, on)
        source.sendSuccess({ reply }, false)
        return 1
    }

    /**
     * [player] bets the stack in their main hand on [on] in the challenge they typed as [id] (`C-50`, `50`), with
     * [services]: refused while the mechanics are off (`mechanics.unavailable`) and for an id that names no
     * challenge (`command.unknown_challenge`); the rest is `ChallengeControl.bet`. What the player reads. Server
     * thread.
     */
    fun bet(services: NarratorServices, player: ServerPlayer, id: String, on: String): Component {
        val texts = services.settings.get().ui
        if (!services.mechanics.available) return Bets.refused(texts["mechanics.unavailable"])
        val ref = Bets.challengeRef(id) ?: return Bets.refused(texts.format("command.unknown_challenge", "id" to id.trim()))
        return services.mechanics.challenges.bet(player, ref, on)
    }

    /** The challenges and duels running with bets (whether still open is told by the answer). */
    private val CHALLENGES = SuggestionProvider<CommandSourceStack> { _, builder ->
        val open = NarratorRuntime.current?.mechanics?.challenges?.list().orEmpty()
            .filter { it.status == ChallengeStatus.RUNNING && (it.spec?.bets == true || it.duel?.bets == true) }
        SharedSuggestionProvider.suggest(open.map { it.ref.toString() }, builder)
    }

    /** What the player may bet on in the challenge typed: its participants then nobody, or themselves when they take part. */
    private val OPTIONS = SuggestionProvider<CommandSourceStack> { context, builder ->
        val ref = Bets.challengeRef(StringArgumentType.getString(context, "id"))
        val view = ref?.let { r -> NarratorRuntime.current?.mechanics?.challenges?.list()?.firstOrNull { it.ref == r } }
        SharedSuggestionProvider.suggest(BetSettlement.options(view?.participants.orEmpty(), context.source.player?.uuid?.toString()), builder)
    }
}
