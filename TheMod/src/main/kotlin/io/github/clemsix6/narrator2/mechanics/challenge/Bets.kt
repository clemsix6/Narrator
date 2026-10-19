package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.mechanics.Bet
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.time.Clock
import java.util.UUID

/**
 * The bets (M4 §5.6): no house. A player stakes the stack they hold on a participant of a challenge or a duel, or on
 * nobody; a participant stakes on themselves only (they could lose on purpose otherwise). When it ends, the winning
 * bettors get their stakes back and share the losing ones, and nothing ever goes to the Narrator. When no bet won,
 * or the challenge did not really end (cancelled, expired), every stake goes back.
 *
 * - [bet] (`ChallengeControl.bet`, `/bet`): the stake goes into escrow in the bettor's ledger
 *   ([ItemLedger.hold], purpose `stake`, its fallback deadline a week after the challenge's planned end), then
 *   the bet into the challenge's state, in the same tick.
 * - [settle] ([BetBook], pure): when the challenge ends, the settlement is written as instructions
 *   ([MechanicsState.settlements]: give back, or cede to a winning bettor) in the very update that takes the
 *   challenge out of the state; they are saved together. See [BetSettlement].
 * - The part applies the instructions of the owners online ([BetInstructions]): each tick and each second (a
 *   challenge that ends settles in the same tick), and at a join, before the guards give anything back (the
 *   engine's `JOIN` comes first).
 *
 * Decisions `mechanic bet` (a bet placed) and those of [BetInstructions]; the ledger's own (`held`, `released`,
 * `forfeited`, `transferred`…). Server thread.
 *
 * @param ledger the escrow: the guards' ledger, or a test's own.
 * @param clock the engine's clock: a GameTest moves it on.
 */
class Bets(
    private val services: NarratorServices,
    private val store: StateStore,
    private val ledger: ItemLedger = services.guards.items,
    private val clock: Clock = services.clock,
) : BetBook, MechanicPart {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)
    private val instructions = BetInstructions(services, store, ledger, clock)

    // ── /bet (ChallengeControl.bet) ──

    /**
     * [player] stakes the stack in their main hand on [on] in challenge or duel [ref] (M4 §5.6): a participant
     * still in the running (by name, whatever the case) or [NOBODY]; a participant stakes on themselves only. Refused,
     * with nothing taken, when [ref] is not in progress (`command.unknown_challenge`), its bets are closed or it has
     * none (`bet.closed`; for a participant already through or out too), the player already bet on it
     * (`bet.already`), [on] is not one of their options (`bet.unknown_option`), or the hand is empty or holds a joker
     * (`bet.no_item`). Returns what the player reads. Server thread.
     */
    fun bet(player: ServerPlayer, ref: Ref, on: String): Component {
        val texts = services.settings.get().ui
        val now = clock.millis()
        val challenge = (if (ref.kind == RefKind.CANON) store.get().challenge(ref.id) else null)
            ?: return refusal(player, ref, on, "unknown", texts.format("command.unknown_challenge", "id" to ref.toString()))
        if (!isOpen(challenge, now)) return refusal(player, ref, on, "closed", texts["bet.closed"])
        val bettor = player.uuid.toString()
        if (challenge.bets.any { it.uuid == bettor }) return refusal(player, ref, on, "already", texts["bet.already"])
        val options = BetSettlement.options(challenge.participants, bettor)
        if (options.isEmpty()) return refusal(player, ref, on, "a participant through or out", texts["bet.closed"])
        val pick = BetSettlement.pick(challenge.participants, on, bettor)
            ?: return refusal(player, ref, on, "not an option", texts.format("bet.unknown_option", "options" to options.joinToString()))
        val stack = player.mainHandItem.copy()
        val onName = pick.on?.name ?: NOBODY
        val entry = ledger.hold(
            player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(plannedEnd(challenge)), challenge.id,
            reason = "a bet that $onName wins", // the ledger's line names the challenge
        ) ?: return refusal(player, ref, on, "empty hand or joker", texts["bet.no_item"])
        val bet = Bet(bettor, player.plainTextName, pick.on?.uuid, entry.id, Escrow.short(entry.what))
        store.update { state -> state.copy(challenges = state.challenges.map { if (it.id == challenge.id) it.copy(bets = it.bets + bet) else it }) }
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, BET, "${challenge.ref}",
            reason = "${bet.name} bets ${bet.what} on $onName (stake ${bet.stake})",
            conversationId = EngineActions.conversationOf(challenge.ref),
            detail = buildJsonObject {
                put("bettor", bet.name)
                put("on", onName)
                put("stake", bet.stake)
                put("items", bet.what)
            },
        )
        return placed(texts, stack, pick.on?.name ?: texts["bet.on_nobody"], challenge.ref.toString())
    }

    /** Whether [challenge] takes bets at [now]: running, its bets open and not yet closed. */
    private fun isOpen(challenge: ChallengeState, now: Long): Boolean =
        challenge.status == ChallengeStatus.RUNNING && challenge.betsCloseAt?.let { now < it } == true

    /** When [challenge] is planned to end, the judging of a judged one included: its stakes' fallback deadline counts from there. */
    private fun plannedEnd(challenge: ChallengeState): Long =
        challenge.endsAt + if (challenge.spec?.goal == GoalKind.JUDGED) services.settings.get().config.mechanics.judgeMinutes * MINUTE else 0

    private fun refusal(player: ServerPlayer, ref: Ref, on: String, why: String, text: String): Component {
        log.debug { "${player.plainTextName}'s bet on $on in $ref is refused: $why" }
        return refused(text)
    }

    /** `[Narrateur → toi] bet.placed`, the stake's count and name in it (the item shows on hover), like the ledger's lines. */
    private fun placed(texts: UiTexts, stack: ItemStack, on: String, id: String): Component {
        val text = texts.format("bet.placed", "items" to ITEMS, "on" to on, "id" to id)
        val (before, after) = text.split(ITEMS, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val color = TextColor.parseColor(texts["narrator.color"]).result().orElse(null)
        return Component.empty()
            .append(Component.literal(texts["narrator.whisper_prefix"] + " ").withStyle { it.withColor(color) })
            .append(before)
            .append(Component.literal("${stack.count}× ").append(stack.hoverName))
            .append(after)
    }

    // ── The settlement (BetBook) ──

    override fun settle(state: MechanicsState, challenge: ChallengeState, ending: Ending, winners: List<String>): Pair<MechanicsState, String?> =
        BetSettlement.settle(state, challenge, ending, winners, at = clock.millis())

    // ── The engine's part: the instructions applied ──

    override fun everyTick(server: MinecraftServer, now: Long) = instructions.applyOnline(server, now)

    override fun everySecond(server: MinecraftServer, now: Long) = instructions.everySecond(server, now)

    /** A player joined: their instructions first, before the guards give anything back to them. */
    override fun onJoin(player: ServerPlayer) = instructions.onJoin(player, clock.millis())

    companion object {
        /** What a player types to bet that nobody wins (`/bet C-50 nobody`). */
        const val NOBODY = "nobody"

        /** The decisions of the bets (M4 §19). */
        const val BET = "bet"
        const val SETTLED = "settled"

        private const val MINUTE = 60_000L
        private const val ITEMS = "{items}"

        /** A refusal of `/bet`: gray italic, like the refusals of `/offer` and the replies of `/pray`. */
        fun refused(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)

        /** The challenge a player typed: `C-50`, `c50` or `50`; null for anything else. */
        fun challengeRef(typed: String): Ref? {
            val text = typed.trim()
            text.toLongOrNull()?.let { return if (it > 0) Ref(RefKind.CANON, it) else null }
            return Ref.parse(text)?.takeIf { it.kind == RefKind.CANON }
        }
    }
}

/**
 * The settlements of the bets ([MechanicsState.settlements], M4 §5.6), applied to their owners once they are online:
 * each stake given back, or ceded to a winning bettor. The ledger does the rest: the hand-over to a winner once both
 * are online, what does not fit, the fallback deadline, and what the players read (`bet.won`, `bet.lost`,
 * `bet.returned`). An instruction applied twice does nothing more: the ledger knows the stake by its id.
 *
 * The engine's part [Bets] runs [applyOnline] each tick, [everySecond] each second and [onJoin] at each join, before
 * the guards' own join. Without the engine (its guardian while the mechanics are off), the same three calls apply
 * the instructions the world kept: they need the world's state, the guards' ledger and a clock, nothing else.
 *
 * Each second, an undated instruction is dated, and one written more than [MechanicsLimits.CEDED_RESCUE_DAYS] days
 * ago is dropped: its owner never came back, and their stake goes back to them at its fallback deadline when they
 * do. Decisions `mechanic settled`. Server thread.
 */
class BetInstructions(
    private val services: NarratorServices,
    private val store: StateStore,
    private val ledger: ItemLedger = services.guards.items,
    private val clock: Clock = services.clock,
) {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)

    /** Applies the instructions whose owner is online. Server thread. */
    fun applyOnline(server: MinecraftServer, now: Long) {
        val pending = store.get().settlements
        if (pending.isEmpty()) return
        for (settlement in pending) {
            val owner = try {
                UUID.fromString(settlement.owner)
            } catch (e: IllegalArgumentException) {
                forget(settlement)
                log.error("the settlement of stake ${settlement.stake} names no player (${settlement.owner}): dropped; the stake goes back at its fallback deadline")
                continue
            }
            server.playerList.getPlayer(owner)?.let { apply(it, settlement, now) }
        }
    }

    /** Dates the undated instructions, drops those too old ([MechanicsLimits.CEDED_RESCUE_DAYS]), then [applyOnline]. Server thread. */
    fun everySecond(server: MinecraftServer, now: Long) {
        val pending = store.get().settlements
        if (pending.any { it.at == null }) store.update { state -> state.copy(settlements = state.settlements.map { if (it.at == null) it.copy(at = now) else it }) }
        store.get().settlements.filter { now - (it.at ?: now) > MechanicsLimits.CEDED_RESCUE_DAYS * DAY_MS }.forEach { abandon(it) }
        applyOnline(server, now)
    }

    /** [player] joined: their instructions applied, before the guards give anything back to them. Server thread. */
    fun onJoin(player: ServerPlayer, now: Long) {
        val owner = player.uuid.toString()
        store.get().settlements.filter { it.owner == owner }.forEach { apply(player, it, now) }
    }

    /**
     * Applies [settlement] to its owner [player], online: their stake given back, or ceded to the winning bettor
     * (handed over at once when they are online too). Then the instruction leaves the state: once the ledger moved,
     * so that a crash in between replays it, harmlessly. One that fails is dropped too, logged: the stake comes back
     * to its owner at its fallback deadline, never lost.
     */
    private fun apply(player: ServerPlayer, settlement: Settlement, now: Long) {
        val stake = ledger.held(player).firstOrNull { it.id == settlement.stake && it.purpose == LedgerPurpose.STAKE }
        val to = settlement.to
        var failed = false
        val outcome = try {
            val winner = to?.let(UUID::fromString)?.takeIf { it != player.uuid }
            if (winner == null) {
                if (ledger.release(player, settlement.stake, now)) "given back" else null
            } else {
                val name = player.level().server.playerList.getPlayer(winner)?.plainTextName ?: to
                if (ledger.forfeit(player, settlement.stake, winner, now)) "ceded to $name" else null
            }
        } catch (e: Exception) {
            failed = true
            log.error("the settlement of ${player.plainTextName}'s stake ${settlement.stake} failed: it goes back to them at its fallback deadline", e)
            "failed (${e.message ?: e.javaClass.simpleName}): back to its owner at its fallback deadline"
        }
        forget(settlement)
        val what = stake?.let { " (${Escrow.short(it.what)}${it.canon?.let { canon -> " on C-$canon" }.orEmpty()})" }.orEmpty()
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, Bets.SETTLED, player.plainTextName,
            reason = "stake ${settlement.stake}$what: " + (outcome ?: "nothing more to do (already settled or back)"),
            detail = buildJsonObject {
                put("stake", settlement.stake)
                put("to", to)
                put("canon", stake?.canon)
                put("applied", outcome != null && !failed)
            },
            level = when {
                failed -> Log.Level.OFF // logged above, with its stack trace
                outcome == null -> Log.Level.DEBUG
                else -> Log.Level.INFO
            },
        )
    }

    /** Drops [settlement], too old: its owner never came back; their stake goes back to them at its fallback deadline. */
    private fun abandon(settlement: Settlement) {
        forget(settlement)
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, Bets.SETTLED, settlement.owner,
            reason = "stake ${settlement.stake}: dropped, its owner was not back within ${MechanicsLimits.CEDED_RESCUE_DAYS} days; " +
                "the stake goes back to them at its fallback deadline",
            detail = buildJsonObject {
                put("stake", settlement.stake)
                put("to", settlement.to)
                put("applied", false)
                put("dropped", true)
            },
            level = Log.Level.WARN,
        )
    }

    /** [settlement] out of the state. */
    private fun forget(settlement: Settlement) {
        store.update { state -> state.copy(settlements = state.settlements.filterNot { it == settlement }) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}

/**
 * How the bets of a challenge or a duel are settled when it ends (M4 §5.6), and the `bets: …` segment of its line
 * (M4 §4.4). Pure, unit-tested.
 *
 * - The winning bets are those on a winner, or on nobody when nobody won.
 * - No winning bet, or a challenge cancelled or expired: every stake goes back to its owner.
 * - Otherwise each winning bettor gets their stake back, and the losing stakes go to the winning bettors in turn,
 *   in the order of the bets (the first losing stake to the first winning bet, the second to the second…).
 */
object BetSettlement {
    /** The longest `bets: …` segment: past it, the last bettors are counted (`(+2 more)`). */
    const val LINE_MAX = 400

    /** What a bet is on: [on], or nobody when null. */
    data class Pick(val on: Participant?)

    /**
     * [state] with the instructions that settle the bets of [challenge], which ended [ending] with [winners] (UUIDs
     * as text), dated [at], and the `bets: …` segment of its line; [state] and null without bets. An instruction
     * already in [state] for the same stake is not written twice.
     */
    fun settle(state: MechanicsState, challenge: ChallengeState, ending: Ending, winners: List<String>, at: Long? = null): Pair<MechanicsState, String?> {
        val bets = challenge.bets
        if (bets.isEmpty()) return state to null
        val winning = winning(bets, ending, winners)
        val shares = shares(bets, winning)
        val instructions = bets.mapIndexed { i, bet -> Settlement(owner = bet.uuid, stake = bet.stake, to = shares[i]?.let { bets[it].uuid }, at = at) }
            .filter { new -> state.settlements.none { it.owner == new.owner && it.stake == new.stake } }
        return state.copy(settlements = state.settlements + instructions) to line(challenge, ending, winning)
    }

    /** The indexes in [bets] of the winning bets: on a winner when [ending] is won, on nobody when lost; none for an end without verdict. */
    fun winning(bets: List<Bet>, ending: Ending, winners: List<String>): List<Int> = bets.indices.filter { i ->
        val on = bets[i].on
        when (ending) {
            Ending.WON -> on != null && on in winners
            Ending.LOST -> on == null
            Ending.EXPIRED, Ending.CANCELLED -> false
        }
    }

    /**
     * Who gets each stake, by index in [bets]: null for a stake that goes back to its owner (a winning bet, or every
     * bet when none won), else the index of the winning bet it goes to, in turn.
     */
    fun shares(bets: List<Bet>, winning: List<Int>): List<Int?> {
        if (winning.isEmpty()) return bets.map { null }
        var turn = 0
        return bets.indices.map { i -> if (i in winning) null else winning[turn++ % winning.size] }
    }

    /**
     * The `bets: …` segment of [challenge], which ended [ending], [winning] the indexes of its winning bets:
     * `bets: Josiane bet 12x iron_ingot on Mimosa, got it back with Bartok's 32x cobblestone; Bartok bet 32x
     * cobblestone on nobody, lost it to Josiane`; `bets: every stake given back (cancelled): Josiane 12x iron_ingot
     * on Mimosa`. Names escaped, [LINE_MAX] characters at most.
     */
    fun line(challenge: ChallengeState, ending: Ending, winning: List<Int>): String {
        val bets = challenge.bets
        fun name(bet: Bet) = ModelText.name(bet.name)
        fun on(bet: Bet) = bet.on?.let { uuid -> challenge.participants.firstOrNull { it.uuid == uuid }?.let { p -> ModelText.name(p.name) } ?: "?" } ?: Bets.NOBODY
        if (winning.isEmpty()) {
            val why = when (ending) {
                Ending.CANCELLED -> "cancelled"
                Ending.EXPIRED -> "expired"
                else -> "no winning bet"
            }
            return fit("bets: every stake given back ($why): ", bets.map { "${name(it)} ${it.what} on ${on(it)}" })
        }
        val shares = shares(bets, winning)
        return fit(
            "bets: ",
            bets.mapIndexed { i, bet ->
                val head = "${name(bet)} bet ${bet.what} on ${on(bet)}, "
                when (val to = shares[i]) {
                    null -> {
                        val gains = bets.indices.filter { shares[it] == i }.map { "${name(bets[it])}'s ${bets[it].what}" }
                        head + if (gains.isEmpty()) "got it back" else "got it back with ${gains.joinToString(" and ")}"
                    }
                    else -> head + "lost it to ${name(bets[to])}"
                }
            },
        )
    }

    /** [head] and as many [parts] as fit in [LINE_MAX], the rest counted. */
    private fun fit(head: String, parts: List<String>): String {
        val full = head + parts.joinToString("; ")
        if (full.length <= LINE_MAX) return full
        for (kept in parts.size - 1 downTo 1) {
            val text = head + parts.take(kept).joinToString("; ") + "; (+${parts.size - kept} more)"
            if (text.length <= LINE_MAX) return text
        }
        return ModelText.cut(head + "(+${parts.size} more)", LINE_MAX)
    }

    /**
     * What [typed] picks among [participants] for [bettor] (UUID as text; null: anyone outside the challenge): one of
     * their [options], by name whatever the case, or nobody; null otherwise.
     */
    fun pick(participants: List<Participant>, typed: String, bettor: String? = null): Pick? {
        val text = typed.trim()
        eligible(participants, bettor).firstOrNull { it.name.equals(text, ignoreCase = true) }?.let { return Pick(it) }
        return if (!takesPart(participants, bettor) && text.equals(Bets.NOBODY, ignoreCase = true)) Pick(null) else null
    }

    /**
     * What [bettor] (UUID as text; null: anyone outside the challenge) may pick among [participants], as typed: those
     * still in the running, then nobody. A participant bets on themselves only, while they are in the running: on
     * anyone else, or on nobody, they could lose on purpose to win their bet. Empty for a participant through or out.
     */
    fun options(participants: List<Participant>, bettor: String? = null): List<String> =
        eligible(participants, bettor).map { it.name } + if (takesPart(participants, bettor)) emptyList() else listOf(Bets.NOBODY)

    /** The participants [bettor] may bet on: themselves when they take part, else those still in the running. */
    private fun eligible(participants: List<Participant>, bettor: String?): List<Participant> =
        running(participants).filter { !takesPart(participants, bettor) || it.uuid == bettor }

    private fun takesPart(participants: List<Participant>, bettor: String?): Boolean = bettor != null && participants.any { it.uuid == bettor }

    /** The participants a bet may still be placed on: neither through (a sure win) nor out (a sure loss). */
    private fun running(participants: List<Participant>): List<Participant> = participants.filter { it.doneAt == null && it.out == null }
}
