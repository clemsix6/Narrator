package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.OfferRefusal
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * `/offer`, for every player (M4 §7.2, §18.2): offers the stack held in the main hand to the Narrator. It goes
 * into escrow in the player's ledger (`purpose = offering`, `O-<id>`) and the Director is told (`OFFERING`,
 * priority); the Narrator takes it only by granting a favor for it (`favor` with `offering`), else it goes
 * back after `mechanics.offeringMinutes`. One `/offer` per player per `mechanics.offeringMinutes`, taken or not, and
 * one offering waiting at a time: a player does not wake the Director at will. Only the player who sends it offers
 * ([ownPlayer]: never through `execute as` nor a command block). English literal, no alias (decision F); forbidden
 * to `run_command`. What the player reads comes from `UiTexts`.
 */
object OfferCommand {
    private val log = Log.mechanics

    /** Registers `/offer` (its own root: every player may use it). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(Commands.literal("offer").executes { offer(it.source) })
    }

    private fun offer(source: CommandSourceStack): Int {
        val player = ownPlayer(source, "make an offering") ?: return 0
        val runtime = runningNarrator(source) ?: return 0
        val offer = offer(runtime, runtime.guards.items, player)
        source.sendSuccess({ offer.reply }, false)
        return if (offer.held != null) 1 else 0
    }

    /** What `/offer` did: what the player reads, and the offering held (null when refused). */
    class Offer(val reply: Component, val held: Confiscation?)

    /**
     * [player] offers the stack in their main hand (M4 §7.2), with [services] and their [ledger]: refused while the
     * mechanics are off (`mechanics.unavailable`), while an offering of theirs waits (`offer.pending`), less than
     * `mechanics.offeringMinutes` after their last one (`offer.too_soon`), and for an empty hand or a joker
     * (`offer.no_item`). Otherwise the stack goes into escrow until the deadline, the Director is told (`OFFERING`,
     * priority) and the player reads `offer.sent`. Server thread.
     */
    fun offer(services: NarratorServices, ledger: ItemLedger, player: ServerPlayer): Offer {
        val settings = services.settings.get()
        val texts = settings.ui
        if (!services.mechanics.available) return refused(texts["mechanics.unavailable"])
        val minutes = settings.config.mechanics.offeringMinutes
        val now = services.clock.millis()
        when (val refusal = ledger.offerRefusal(player, now, minutes)) {
            OfferRefusal.Pending -> return refused(texts["offer.pending"])
            is OfferRefusal.TooSoon -> return refused(texts.format("offer.too_soon", "time" to ToolText.wait(refusal.waitMs)))
            null -> Unit
        }
        val offered = player.mainHandItem.copy()
        val deadline = now + minutes * 60_000L
        val held = ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.OFFERING, deadline, canon = null, reason = "offered by the player")
            ?: return refused(texts["offer.no_item"])
        val line = Escrow.offeringLine(held, player.plainTextName, deadline - now)
        log.info("${player.plainTextName} offers ${held.what} (${Escrow.ref(held.id)}), back at the latest in $minutes min")
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.OFFERING, line, now, priority = true))
        return Offer(ledger.line("offer.sent", listOf(offered)), held)
    }

    /** A refusal: gray italic, like the replies of `/pray`. */
    private fun refused(text: String): Offer = Offer(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), null)
}
