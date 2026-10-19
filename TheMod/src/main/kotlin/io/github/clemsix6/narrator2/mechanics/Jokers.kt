package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Narrator's jokers (M4 §7.1): a paper a player plays by themselves, with a right click in thin air, to
 * lift one of the Narrator's punishments. It works whether the Director sleeps or not, and whether the
 * mechanics engine runs or not: it is a guard (a pardon), not a mechanic.
 *
 * A joker is given by `favor joker` (or a challenge's reward, which calls it), at most
 * [MechanicsLimits.JOKERS_PER_DAY] per player per day ([limit], [give]); it goes into the inventory through the
 * ledger, so that one that finds no room waits there, never on the ground. Playing it ([onUse]) pardons the player
 * as an act of its own, takes one joker from the stack, and tells the Director (`JOKER`).
 */
object Jokers {
    private val log = Log.mechanics

    /** The conversation of a joker's acts, as the guards' decisions show it (an engine act is `mechanics:<what>`). */
    const val CONVERSATION = "mechanics:joker"

    /** How long a joker given counts against [MechanicsLimits.JOKERS_PER_DAY]. */
    const val DAY_MS = 24 * 3_600_000L

    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /**
     * One joker: paper named `joker.name` in the Narrator's color, the lore `joker.lore` under it, a glint, marked
     * `CUSTOM_DATA {narrator2: {joker: 1b}}` ([ItemLedger.isJoker]): never staked nor offered. Jokers stack together.
     */
    fun stack(ui: UiTexts): ItemStack {
        val stack = ItemStack(Items.PAPER)
        val color = Speech.color(ui)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(ui["joker.name"]).withStyle { it.withItalic(false).withColor(color) })
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.literal(ui["joker.lore"]).withStyle { it.withItalic(false).withColor(ChatFormatting.GRAY) })))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        val mark = CompoundTag()
        mark.putBoolean(ItemLedger.JOKER_KEY, true)
        val data = CompoundTag()
        data.put(Speech.CUSTOM_DATA_KEY, mark)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
        return stack
    }

    /**
     * Why the player named [name] may not get a joker at [now] (epoch milliseconds): they got
     * [MechanicsLimits.JOKERS_PER_DAY] within the last day, [given] being when they got theirs
     * (`GuardState.jokersGivenAt`). Null when they may. Times are written in [clock]'s zone.
     */
    fun limit(name: String, given: List<Long>, now: Long, clock: Clock): Verdict.Denied? {
        val recent = given.filter { now - it < DAY_MS }.sorted()
        if (recent.size < MechanicsLimits.JOKERS_PER_DAY) return null
        val next = recent[recent.size - MechanicsLimits.JOKERS_PER_DAY] + DAY_MS
        val at = DAY_TIME.format(Instant.ofEpochMilli(next).atZone(clock.zone))
        return Verdict.Denied(
            Rules.FAVOR_LIMIT,
            "${ModelText.name(name)} got a joker ${ToolText.duration(now - recent.last())} ago, and a player gets " +
                "${MechanicsLimits.JOKERS_PER_DAY} a day at most; the next one is allowed at $at (in ${ToolText.wait(next - now)})",
        )
    }

    /**
     * Whether [player] may get a joker from [caller] at [now] (epoch milliseconds): [limit] on their guards' state.
     * A refusal is a `guard denied` decision, like the guards' own refusals of a favor. Server thread.
     */
    fun allowed(services: NarratorServices, player: ServerPlayer, caller: Caller, now: Long): Verdict {
        val denied = limit(player.plainTextName, GuardStates.read(player).jokersGivenAt, now, services.clock) ?: return Verdict.Allowed
        Decisions(services.trace, services.clock).record(
            Log.guard, DecisionRecord.Kind.GUARD, "denied", player.plainTextName, reason = "${denied.rule}: ${denied.message}",
            conversationId = caller.conversationId, turnId = caller.turnId,
            detail = buildJsonObject { put("rule", denied.rule) },
        )
        return denied
    }

    /**
     * Gives [player] a joker, a favor of [caller] at [now] (epoch milliseconds): they are told (`joker.given`), it
     * goes into their inventory through the ledger (what finds no room waits there and comes once there is room),
     * and it is dated in their guards' state (`jokersGivenAt`, saved with that inventory). Ask [allowed] first.
     * Returns whether it waits for room. Decision `guard joker_given`. Server thread.
     */
    fun give(services: NarratorServices, player: ServerPlayer, caller: Caller, now: Long): Boolean {
        val ui = services.settings.get().ui
        player.sendSystemMessage(Speech.chatLine(ui, ui["joker.given"], private = true))
        val waiting = services.guards.items.owe(player, listOf(stack(ui)), "a joker, a favor of the Narrator").isNotEmpty()
        // The attachment itself: the guards' own copy of the state reads it back at their next change of this player.
        GuardStates.write(player, GuardStates.read(player).let { it.copy(jokersGivenAt = it.jokersGivenAt + now) })
        Decisions(services.trace, services.clock).record(
            Log.guard, DecisionRecord.Kind.GUARD, "joker_given", player.plainTextName,
            reason = "a joker to play by themselves against one of the Narrator's punishments" +
                if (waiting) "; it waits for room in their inventory" else "",
            conversationId = caller.conversationId, turnId = caller.turnId,
            detail = buildJsonObject {
                put("act", caller.act)
                put("waiting", waiting)
            },
        )
        return waiting
    }

    /**
     * `UseItemCallback` (`registerMechanicsHooks`), server side only: [player] uses the stack in [hand].
     * [InteractionResult.PASS] lets the game go on as usual: not a joker, or nothing to lift (the player is told
     * `joker.nothing`, and keeps it). A joker played pardons the player as an act of its own, takes one joker from
     * the stack, tells the player (`joker.played`) and the Director (`JOKER`, no priority), and answers a success, so
     * that the game does nothing else with it. Server thread.
     *
     * The act is its decision `mechanic joker` (subject: the player), whose id the pardon carries as its
     * `Caller.firingId` (the guards' `pardoned` decision of the act). Its reason is the `JOKER` line, which stands
     * alone: the Chronicler reads it as it is in its exchange log (M4-08). It is written once the pardon said what it
     * lifted, under the id taken before.
     */
    fun onUse(services: NarratorServices, player: ServerPlayer, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (!ItemLedger.isJoker(stack)) return InteractionResult.PASS
        val ui = services.settings.get().ui
        if (!services.guards.isPunished(player)) {
            player.sendSystemMessage(Speech.chatLine(ui, ui["joker.nothing"], private = true))
            return InteractionResult.PASS
        }
        val name = player.plainTextName
        val act = services.trace.nextId(IdKind.DECISION)
        val lifted = services.guards.pardon(player, Caller(Role.DIRECTOR, CONVERSATION, turnId = null, taskId = null, firingId = act))
        if (lifted.isEmpty()) {
            player.sendSystemMessage(Speech.chatLine(ui, ui["joker.nothing"], private = true))
            return InteractionResult.PASS
        }
        stack.shrink(1)
        player.containerMenu.broadcastChanges()
        player.sendSystemMessage(Speech.chatLine(ui, ui["joker.played"], private = true))
        val line = "${ModelText.name(name)} played a joker: ${lifted(lifted)}"
        services.trace.decision(
            DecisionRecord(
                act, services.clock.instant(), DecisionRecord.Kind.MECHANIC, "joker", name, line, eventId = null,
                conversationId = CONVERSATION, turnId = null,
                detail = buildJsonObject {
                    put("hand", hand.name.lowercase())
                    put("lifted", JsonArray(lifted.map(::JsonPrimitive)))
                    put("left", stack.count)
                },
            ),
        )
        log.info(Decisions.line("joker", name, line))
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.JOKER, line, services.clock.millis(), priority = false))
        return InteractionResult.SUCCESS
    }

    /** What a pardon lifted ([io.github.clemsix6.narrator2.guard.Guards.pardon]), for the model: ids without `minecraft:`. */
    fun lifted(lifted: List<String>): String = lifted.joinToString().replace("minecraft:", "")
}
