package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.runtime.NarratorServices
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult

/**
 * The Narrator's jokers (M4 §7.1): a paper a player plays by themselves, with a right click in thin air, to
 * lift one of the Narrator's punishments. It works whether the Director sleeps or not, and whether the
 * mechanics engine runs or not: it is a guard (a pardon), not a mechanic. Filled by M4-16.
 */
object Jokers {
    /**
     * `UseItemCallback` (`registerMechanicsHooks`), server side only: [player] uses the stack in [hand].
     * [InteractionResult.PASS] lets the game go on as usual (not a joker, or nothing done); a joker played
     * answers a success so that the game does nothing else with it. Server thread.
     */
    fun onUse(services: NarratorServices, player: ServerPlayer, hand: InteractionHand): InteractionResult = InteractionResult.PASS
}
