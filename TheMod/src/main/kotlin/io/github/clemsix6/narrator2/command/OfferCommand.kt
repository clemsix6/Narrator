package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack

/**
 * `/offer`, for every player (M4 §7.2, §18.2): offers the stack held in the main hand to the Narrator. It goes
 * into escrow in the player's ledger (`purpose = offering`, `O-<id>`) and the Director is told (`OFFERING`,
 * priority); the Narrator takes it only by granting a favor for it (`favor` with `offering`), else it goes
 * back after `mechanics.offeringMinutes`. One `/offer` per player per `mechanics.offeringMinutes`. English
 * literal, no alias (decision F); forbidden to `run_command`. What the player reads comes from `UiTexts`.
 * Filled by M4-15.
 */
object OfferCommand {
    /** Registers `/offer` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {}
}
