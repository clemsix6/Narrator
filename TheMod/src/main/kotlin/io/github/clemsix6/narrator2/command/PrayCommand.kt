package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack

/**
 * `/pray <message>`, for every player: a private message to the Narrator, published as a
 * `ChatMessage(PRAY)` (M1_M2 §14.5).
 *
 * Provisional body (T02): no command yet. T21 writes it.
 */
object PrayCommand {
    /** Registers `/pray` (its own root: every player may use it). */
    @Suppress("UNUSED_PARAMETER")
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) = Unit
}
