package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.mixin.guard.CommandSourceStackAccessor
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

/**
 * The player who sends this command themselves: they typed it, or clicked a button that runs it. `/bet`, `/offer`
 * and `/duel` stake a player's items or put them in a fight: only that player decides. [CommandSourceStack.getPlayer]
 * is the entity `execute as` names; the original sender (`CommandSourceStack.source`, read by an accessor) must be
 * that very player, so a command block, the console or another player cannot act for them. Otherwise [source] is
 * told why, `only a player can [action]`, and the answer is null.
 */
fun ownPlayer(source: CommandSourceStack, action: String): ServerPlayer? {
    val player = source.player
    if (player != null && (source as CommandSourceStackAccessor).`narrator2$source`() === player.commandSource()) return player
    val why = if (player == null) "" else ", for themselves: ${player.plainTextName} did not send this command (execute as, a command block or the console)"
    source.sendFailure(errorLine("only a player can $action$why."))
    return null
}
