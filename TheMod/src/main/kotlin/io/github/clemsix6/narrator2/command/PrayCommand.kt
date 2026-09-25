package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.observer.ref
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/pray <message>`, for every player (M1_M2 §14.5): a private message to the Narrator. The message is
 * published as a `ChatMessage(PRAY)`, which the Journal keeps and which wakes the Director (a prayer
 * always does); it is shown to nobody, and its author reads `pray.sent`. One prayer every
 * [COOLDOWN_MS] per player; a prayer too soon is refused with `pray.cooldown` and published nowhere.
 * It is not a private message between players: those are `/msg` and are never shown to the model.
 */
object PrayCommand {
    /** Shortest time between two prayers of a player. */
    const val COOLDOWN_MS = 10_000L

    /** When each player last prayed, epoch milliseconds of the Narrator's clock. Server thread. */
    private val lastPrayer = ConcurrentHashMap<UUID, Long>()

    /** Registers `/pray` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("pray").then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { pray(it.source, StringArgumentType.getString(it, "message")) },
            ),
        )
    }

    private fun pray(source: CommandSourceStack, message: String): Int {
        val player = source.player
        if (player == null) {
            source.sendFailure(errorLine("only a player can pray."))
            return 0
        }
        val runtime = runningNarrator(source) ?: return 0
        val text = message.trim()
        if (text.isEmpty()) {
            source.sendFailure(Component.literal("/pray <message>"))
            return 0
        }
        val ui = runtime.settings.get().ui
        val now = runtime.clock.millis()
        val wait = waitBefore(lastPrayer[player.uuid], now)
        if (wait > 0) {
            val reply = heaven(ui.format("pray.cooldown", "seconds" to (wait + 999) / 1_000))
            source.sendSuccess({ reply }, false)
            return 0
        }
        if (runtime.bus.publish(ChatMessage(player.ref(), ChatChannel.PRAY, text, emptyList())) == null) {
            source.sendFailure(errorLine("the server is stopping: the prayer was not heard."))
            return 0
        }
        lastPrayer[player.uuid] = now
        val reply = heaven(ui["pray.sent"])
        source.sendSuccess({ reply }, false)
        return 1
    }

    /** How long a player who last prayed at [last] must wait at [now] before praying again; 0 when they may. */
    internal fun waitBefore(last: Long?, now: Long): Long = if (last == null) 0 else (last + COOLDOWN_MS - now).coerceAtLeast(0)

    /** A reply of heaven to a prayer: gray italic, like a whisper, without the Narrator's prefix (it speaks of him). */
    private fun heaven(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
}
