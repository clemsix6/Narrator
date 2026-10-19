package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.tool.ToolText
import java.util.UUID

/** Why a player may not `/offer` now (M4 §7.2). */
sealed interface OfferRefusal {
    /** Their last offering still waits for the Narrator's answer: one at a time. */
    data object Pending : OfferRefusal

    /** They offered less than `mechanics.offeringMinutes` ago: one `/offer` per player per that many minutes, taken or not. */
    data class TooSoon(val waitMs: Long) : OfferRefusal
}

/**
 * The escrow's rules (M4 §5.6, §7.2): how stakes, offerings and what is owed move in a player's ledger. Pure
 * functions of a [GuardState], unit-tested; [ItemLedger] applies them to the players, on the server thread.
 *
 * - A **stake** (`/bet`) is held until its challenge is settled: [release]d to its owner, or [forfeit]ed to a winner,
 *   to whom it goes once both are online ([ceded], then [winnings], [receive]). It always has a fallback deadline
 *   ([stakeDeadline], [cededDeadline]) that gives it back to its owner if its settlement is lost: nothing stays held
 *   for ever.
 * - An **offering** (`/offer`) waits for the Narrator's answer until its deadline ([offering], [pendingOffering]):
 *   taken for good only with a favor granted for it, otherwise given back.
 * - What is **owed** (winnings, a joker) and found no room waits, due at once.
 *
 * Every entry is given back (or delivered) once [due]. Applying a change twice changes nothing more: [release],
 * [forfeit] and [receive] answer null when there is nothing to do, so an instruction or a hand-over replayed after a
 * crash is harmless: the winner's ledger remembers the stakes it received ([ReceivedStake]) after their delivery.
 */
object Escrow {
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * MINUTE_MS

    /** A stake's fallback deadline (M4 §5.6): the planned end [endsAt] of its challenge, judging included, plus [MechanicsLimits.STAKE_RESCUE_DAYS]. */
    fun stakeDeadline(endsAt: Long): Long = endsAt + MechanicsLimits.STAKE_RESCUE_DAYS * DAY_MS

    /** The fallback deadline of a stake ceded at [now] to a winner: [MechanicsLimits.CEDED_RESCUE_DAYS] later, it goes back to its owner. */
    fun cededDeadline(now: Long): Long = now + MechanicsLimits.CEDED_RESCUE_DAYS * DAY_MS

    /** [state] with [entry] held: a stake or an offering; an offering also dates the player's last `/offer`. */
    fun hold(state: GuardState, entry: Confiscation): GuardState {
        require(entry.purpose == LedgerPurpose.STAKE || entry.purpose == LedgerPurpose.OFFERING) { "only a stake or an offering is held, not ${entry.purpose}" }
        return state.copy(
            confiscations = state.confiscations + entry,
            lastOfferAt = if (entry.purpose == LedgerPurpose.OFFERING) entry.takenAt else state.lastOfferAt,
        )
    }

    /**
     * [state] with stake [id] settled at [now]: due for its owner, no longer ceded to anyone; null when nothing changes:
     * no such stake (already given back), or already released. A stake past its fallback deadline whose settlement
     * comes at last (its owner away a week) is released too: it comes back for its settlement, not as a fallback.
     */
    fun release(state: GuardState, id: Long, now: Long): GuardState? {
        val stake = stake(state, id) ?: return null
        if (stake.settledAt != null && stake.owedTo == null) return null
        return replace(state, stake.copy(returnAt = minOf(stake.returnAt, now), owedTo = null, settledAt = now))
    }

    /**
     * [state] with stake [id] ceded [to] a winner at [now]: it goes to them once both are online, or back to its owner
     * at its new fallback deadline ([cededDeadline]); null when nothing changes: no such stake, already ceded to them,
     * or settled for its owner already ([release], [returnNow]: what is left of it waits for room, and stays theirs).
     */
    fun forfeit(state: GuardState, id: Long, to: UUID, now: Long): GuardState? {
        val stake = stake(state, id) ?: return null
        if (stake.owedTo == to || stake.settledAt != null && stake.owedTo == null) return null
        return replace(state, stake.copy(owedTo = to, returnAt = cededDeadline(now), settledAt = now))
    }

    /** The stakes of [state] ceded to a winner and not yet past their fallback deadline at [now]: to hand over once both are online. */
    fun ceded(state: GuardState, now: Long): List<Confiscation> =
        state.confiscations.filter { it.purpose == LedgerPurpose.STAKE && it.owedTo != null && it.returnAt > now }

    /**
     * What a winner is owed for [stake], ceded by the player named [from]: the same stacks, due at [now], **under the
     * stake's own id**, so that a hand-over replayed after a crash finds them already there ([receive]).
     */
    fun winnings(stake: Confiscation, from: String, now: Long): Confiscation = Confiscation(
        id = stake.id,
        items = stake.items,
        what = stake.what,
        takenAt = now,
        returnAt = now,
        reason = "won from $from" + (stake.canon?.let { " on C-$it" } ?: ""),
        turnId = null,
        purpose = LedgerPurpose.OWED,
        canon = stake.canon,
        dataVersion = stake.dataVersion,
    )

    /**
     * [state], a winner's, with [winnings] received at [now] ([ReceivedStake]); null when they were received already
     * (a hand-over replayed after a crash): still waiting in the ledger, or delivered within the last
     * [MechanicsLimits.CEDED_RESCUE_DAYS] days. Older receipts are forgotten: the loser's file no longer holds the stake.
     */
    fun receive(state: GuardState, winnings: Confiscation, now: Long): GuardState? {
        if (state.confiscations.any { it.id == winnings.id } || state.receivedStakes.any { it.stake == winnings.id }) return null
        val recent = state.receivedStakes.filter { now - it.at < MechanicsLimits.CEDED_RESCUE_DAYS * DAY_MS }
        return state.copy(confiscations = state.confiscations + winnings, receivedStakes = recent + ReceivedStake(winnings.id, now))
    }

    /** [state] without entry [id]. */
    fun without(state: GuardState, id: Long): GuardState = state.copy(confiscations = state.confiscations.filterNot { it.id == id })

    /**
     * [state] with entry [id] due for its owner at [now], whatever it is: an operator gives it back
     * (`/narrator guards <player> release <id>`). A confiscation before its time, an offering untaken, a stake even
     * ceded to a winner, settled by it (no fallback). Null when there is no such entry, or it is due already.
     */
    fun returnNow(state: GuardState, id: Long, now: Long): GuardState? {
        val entry = state.confiscations.firstOrNull { it.id == id && it.returnAt > now } ?: return null
        val settledAt = if (entry.purpose == LedgerPurpose.STAKE) now else entry.settledAt
        return replace(state, entry.copy(returnAt = now, owedTo = null, settledAt = settledAt))
    }

    /** The offering [id] of [state] still waiting for the Narrator's answer at [now]; null when gone, or past its deadline (going back). */
    fun offering(state: GuardState, id: Long, now: Long): Confiscation? =
        state.confiscations.firstOrNull { it.id == id && it.purpose == LedgerPurpose.OFFERING && it.returnAt > now }

    /** The offering of [state] waiting for the Narrator's answer at [now], if any (one at most). */
    fun pendingOffering(state: GuardState, now: Long): Confiscation? =
        state.confiscations.filter { it.purpose == LedgerPurpose.OFFERING && it.returnAt > now }.maxByOrNull { it.takenAt }

    /** Why [state]'s player may not `/offer` at [now], offerings being [minutes] apart; null when they may. */
    fun offerRefusal(state: GuardState, now: Long, minutes: Int): OfferRefusal? {
        if (pendingOffering(state, now) != null) return OfferRefusal.Pending
        val wait = (state.lastOfferAt ?: return null) + minutes * MINUTE_MS - now
        return if (wait > 0) OfferRefusal.TooSoon(wait) else null
    }

    /** The entries of [state] due at [now]: they go back to the player (or are delivered to them) as soon as there is room. */
    fun due(state: GuardState, now: Long): List<Confiscation> = state.confiscations.filter { it.returnAt <= now }

    /**
     * Whether [entry], due, is a stake that comes back at its fallback deadline: its settlement never came (a crash, a
     * lost instruction), or it was ceded to a winner who never came back while its owner was online.
     */
    fun isFallback(entry: Confiscation): Boolean =
        entry.purpose == LedgerPurpose.STAKE && (entry.owedTo != null || entry.settledAt == null)

    /** The UI text that tells the player [entry]'s items came into their inventory. */
    fun arrivalKey(entry: Confiscation): String = when (entry.purpose) {
        LedgerPurpose.CONFISCATION -> "items.returned"
        LedgerPurpose.STAKE -> "bet.returned"
        LedgerPurpose.OFFERING -> "offer.returned"
        LedgerPurpose.OWED -> if (entry.canon != null) "bet.won" else "items.delivered"
    }

    /** How [entry] is named in decisions: `confiscation 812`, `stake 812`, `offering O-812`, `owed 812`. */
    fun label(entry: Confiscation): String = when (entry.purpose) {
        LedgerPurpose.OFFERING -> "offering ${ref(entry.id)}"
        else -> "${entry.purpose.id} ${entry.id}"
    }

    /** An offering's reference for the model and the players: `O-812`. */
    fun ref(id: Long): String = "O-$id"

    /** The id of an offering the model wrote, `O-812` or `812`; null when it is not one. */
    fun offeringId(ref: String): Long? = ref.trim().let { if (it.startsWith("O-", ignoreCase = true)) it.drop(2) else it }.toLongOrNull()?.takeIf { it > 0 }

    /**
     * The `OFFERING` line of a new offering (M4 §4.4), for the Director: `O-812 Mimosa offers 10x diamond: take it by
     * granting them a favor (favor, offering O-812) within 10m, or let it go back`.
     */
    fun offeringLine(entry: Confiscation, player: String, leftMs: Long): String =
        "${ref(entry.id)} ${ModelText.name(player)} offers ${short(entry.what)}: take it by granting them a favor " +
            "(favor, offering ${ref(entry.id)}) within ${ToolText.duration(leftMs)}, or let it go back"

    /** The `OFFERING` line of an offering given back, not taken: `O-812 went back to Mimosa, not taken: 10x diamond`. */
    fun offeringBackLine(entry: Confiscation, player: String): String =
        "${ref(entry.id)} went back to ${ModelText.name(player)}, not taken: ${short(entry.what)}"

    /** A ledger description for the model, ids without `minecraft:`: `10x diamond, 1x iron_sword`. */
    fun short(what: String): String = what.replace("x minecraft:", "x ")

    private fun stake(state: GuardState, id: Long): Confiscation? = state.confiscations.firstOrNull { it.id == id && it.purpose == LedgerPurpose.STAKE }

    private fun replace(state: GuardState, entry: Confiscation): GuardState =
        state.copy(confiscations = state.confiscations.map { if (it.id == entry.id) entry else it })
}
