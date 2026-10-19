package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.tool.ToolText
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import java.time.Clock

/**
 * The effects the Narrator put on players. The game keeps the effects themselves (saved with the
 * player, lost at death); this ledger, in the player's [GuardState], tells the Narrator's effects from
 * the others: for `remove_effect … all_narrator`, `activeOn` and the punishments.
 *
 * An entry is dropped as soon as its effect is gone from the player (run out, milk, death, removed).
 * Server thread only.
 */
class EffectLedger(private val states: GuardStates, private val clock: Clock) {
    private val log = Log.guard

    /**
     * Puts [effect] at [level] (1 for its first level) on [player] for [seconds], replacing the effect of
     * that kind they have now (`addEffect` alone keeps a stronger or longer one), and records it. An
     * effect that is not `BENEFICIAL` is recorded as part of a punishment (a `NEUTRAL` one too: `glowing`,
     * M4 review, finding 33), so that a pardon lifts it.
     */
    fun apply(player: ServerPlayer, effect: Holder<MobEffect>, level: Int, seconds: Int, particles: Boolean, turnId: Long?): LedgerEffect {
        val entry = LedgerEffect(
            effect.registeredName, level, clock.millis() + seconds * 1_000L,
            harmful = effect.value().category != MobEffectCategory.BENEFICIAL, turnId = turnId,
        )
        // Forgotten before the removal: the effect replaced does not end as the Narrator's (a levitation
        // replaced by a new one leaves nobody falling, so its end is not cushioned).
        states.update(player) { state -> state.copy(effects = state.effects.filterNot { it.effect == entry.effect }) }
        player.removeEffect(effect)
        player.addEffect(MobEffectInstance(effect, seconds * TICKS_PER_SECOND, level - 1, false, particles, true))
        states.update(player) { state -> state.copy(effects = state.effects + entry) }
        log.debug { "effect ${describe(entry)} on ${player.plainTextName} for $seconds s" }
        return entry
    }

    /** The Narrator's effects still on [player]. */
    fun on(player: ServerPlayer): List<LedgerEffect> {
        prune(player)
        return states.of(player).effects
    }

    /**
     * The ledger's entry of [effect] on [player], if the Narrator put it there; read as it is, without
     * pruning, so that it still answers while the effect is leaving the player.
     */
    fun entryOf(player: ServerPlayer, effect: Holder<MobEffect>): LedgerEffect? =
        states.of(player).effects.firstOrNull { it.effect == effect.registeredName }

    /** Removes [effect] from [player] and forgets it; false when the player did not have it. */
    fun remove(player: ServerPlayer, effect: Holder<MobEffect>): Boolean {
        val removed = player.removeEffect(effect)
        states.update(player) { state -> state.copy(effects = state.effects.filterNot { it.effect == effect.registeredName }) }
        return removed
    }

    /** Removes every effect the Narrator put on [player] (`all_narrator`); returns them. */
    fun removeAll(player: ServerPlayer): List<LedgerEffect> {
        val mine = on(player)
        for (entry in mine) holderOf(entry)?.let { player.removeEffect(it) }
        states.update(player) { it.copy(effects = emptyList()) }
        return mine
    }

    /**
     * Removes the harmful effects the Narrator put on [player] (a rescue by the death guard), forgotten
     * before the removal: they do not end as the Narrator's (a levitation lifted this way is not
     * cushioned, and arms nothing). Returns them.
     */
    fun removeHarmful(player: ServerPlayer): List<LedgerEffect> {
        val harmful = on(player).filter { it.harmful }
        if (harmful.isEmpty()) return harmful
        states.update(player) { state -> state.copy(effects = state.effects - harmful.toSet()) }
        for (entry in harmful) holderOf(entry)?.let { player.removeEffect(it) }
        return harmful
    }

    /** Forgets the entries whose effect is no longer on [player]. Once a second, and at each join and respawn. */
    fun prune(player: ServerPlayer) {
        val effects = states.of(player).effects
        if (effects.isEmpty()) return
        val gone = effects.filter { entry -> holderOf(entry)?.let { !player.hasEffect(it) } ?: true }
        if (gone.isNotEmpty()) states.update(player) { state -> state.copy(effects = state.effects - gone.toSet()) }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20

        /** The game's effect of a ledger entry; null when it no longer exists. */
        fun holderOf(entry: LedgerEffect): Holder<MobEffect>? =
            Identifier.tryParse(entry.effect)?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) }

        /** `slowness II`. */
        fun describe(entry: LedgerEffect): String = "${entry.effect.substringAfter(':')} ${ToolText.roman(entry.level)}"
    }
}
