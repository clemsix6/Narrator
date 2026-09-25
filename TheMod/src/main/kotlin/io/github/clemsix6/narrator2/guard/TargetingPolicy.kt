package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.tool.Caller
import java.time.Clock

/**
 * The no-piling-on rules of hostile actions (M1_M2 §10.3): refused against a player who died less than
 * 5 minutes ago ([Rules.NO_PILE_ON]), or while a punishment opened by another turn is still running
 * ([Rules.ALREADY_PUNISHED]). Protection and chaos 0, which come first, are checked by the guards.
 *
 * Written by T02 as the contract between T09a (consumer) and T09b (owner). Provisional body: every
 * hostile action is refused. T09b writes it.
 */
class TargetingPolicy(
    @Suppress("unused") private val players: PlayerStates,
    @Suppress("unused") private val clock: Clock,
) {
    /** Whether [caller] may act against [target] now, given the running [episodes]. */
    @Suppress("UNUSED_PARAMETER")
    fun check(target: PlayerRef, caller: Caller, episodes: Episodes): Verdict =
        Verdict.Denied(NOT_READY, "hostile actions are not allowed until the engine's rules are in place.")

    private companion object {
        const val NOT_READY = "not_ready"
    }
}
