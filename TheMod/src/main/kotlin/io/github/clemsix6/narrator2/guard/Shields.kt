package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.time.Clock

/**
 * A player the Narrator leaves alone for a while: saved from a death since [at], until [until] (epoch
 * milliseconds), because [reason] (`duel C-52: fatal blow stopped at 1 hp`).
 */
data class Shield(val at: Long, val until: Long, val reason: String) {
    companion object {
        /** Saved with the player (`GuardState.shield`). */
        val CODEC: Codec<Shield> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("at").forGetter(Shield::at),
                Codec.LONG.fieldOf("until").forGetter(Shield::until),
                Codec.STRING.fieldOf("reason").forGetter(Shield::reason),
            ).apply(instance, ::Shield)
        }
    }
}

/**
 * The players saved from a death lately (M4 review, finding 18): a rescue of the death guard, or a death the
 * engine refused (a duel's fatal blow). Until their shield ends, the guards refuse every hostile action against
 * them ([Rules.NO_PILE_ON], as after a real death) and the Narrator's creatures and explosions spare them.
 *
 * Saved with the player ([GuardState.shield]), like the death it stands for ([GuardState.lastDeathAt]): it holds
 * across a reconnection, a respawn and a restart. Server thread.
 */
class Shields(private val states: GuardStates, private val clock: Clock) {
    /** Shields [player] until [until] because [reason]; a shield that ends later is kept. Returns the shield in force. */
    fun shield(player: ServerPlayer, until: Long, reason: String): Shield {
        active(player)?.takeIf { it.until >= until }?.let { return it }
        val shield = Shield(clock.millis(), until, reason)
        states.update(player) { it.copy(shield = shield) }
        return shield
    }

    /** The shield of [player] still running, if any. */
    fun active(player: Player): Shield? = GuardStates.read(player).shield?.takeIf { it.until > clock.millis() }

    /** Forgets [player]'s shield once it ran out, so that their file stays clean (a join, a respawn). */
    fun tidy(player: ServerPlayer) {
        val shield = GuardStates.read(player).shield ?: return
        if (shield.until <= clock.millis()) states.update(player) { it.copy(shield = null) }
    }
}
