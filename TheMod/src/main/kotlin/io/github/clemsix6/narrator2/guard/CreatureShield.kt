package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TraceableEntity
import net.minecraft.world.entity.player.Player

/**
 * The Narrator's creatures never hurt a player the Narrator may not act against (ARCHITECTURE §17: the
 * protected players, and no piling on a player who is dead or died less than 5 minutes ago), whatever
 * they do: blows, arrows, potions, fangs, spit, thorns, explosions, and the effects that come with them.
 * [GuardsImpl.spares] says who is spared.
 *
 * - **Damage** (`ServerLivingEntityEvents.ALLOW_DAMAGE`, early in `LivingEntity.hurtServer`, before any
 *   knockback): refused when the source or the direct entity is the Narrator's doing. A blow refused
 *   there carries none of its after-effects either (a wither skeleton's wither, a cave spider's poison,
 *   a tipped arrow's effect, fire aspect: the game applies them only after a blow that landed).
 * - **Effects** (`mixin.policy.LivingEntityEffectGuardMixin`, `addEffect` with its source): what does not
 *   go through a blow, a witch's splash or lingering potion; beneficial effects still go through.
 * - **Targeting** (`mixin.policy.MobTargetGuardMixin`, `TargetingConditionsGuardMixin`): a spared player
 *   is no valid target for the Narrator's creatures, as a creative player is for any mob, so that they
 *   go for someone else instead of pressing on a player they cannot hurt.
 * - **Explosions**: [ExplosionGuard.spares] spares the same players.
 *
 * "The Narrator's doing": a creature carrying the `narrator2` tag, or what one of them owns (an arrow, a
 * potion, an effect cloud, evoker fangs, a vex). Without a running Narrator nobody is spared. Server
 * thread only.
 */
object CreatureShield {
    private val log = Log.guard

    /** Whether [entity] is one of the Narrator's creatures, or something one of them owns. */
    fun isNarratorsDoing(entity: Entity?): Boolean =
        NarratorMobs.isNarrators(entity) || (entity is TraceableEntity && NarratorMobs.isNarrators(entity.owner))

    /** Whether [player] is out of the Narrator's creatures' reach now. */
    @JvmStatic
    fun spares(player: Player): Boolean = (NarratorRuntime.current?.guards as? GuardsImpl)?.spares(player) == true

    /** `ALLOW_DAMAGE`: whether [victim] may take damage from [source]. */
    fun allowsDamage(victim: Player, source: DamageSource): Boolean {
        if (!isNarratorsDoing(source.entity) && !isNarratorsDoing(source.directEntity)) return true
        if (!spares(victim)) return true
        log.debug { "spared ${victim.plainTextName} from ${source.typeHolder().registeredName} by ${(source.entity ?: source.directEntity)?.type}" }
        return false
    }

    /** Whether [effect], from [source], must not reach [target]: a harmful or neutral effect of the Narrator's doing on a spared player. */
    @JvmStatic
    fun blocksEffect(target: LivingEntity, effect: MobEffectInstance, source: Entity?): Boolean {
        if (target !is Player || target.level().isClientSide) return false
        if (effect.effect.value().category == MobEffectCategory.BENEFICIAL || !isNarratorsDoing(source)) return false
        if (!spares(target)) return false
        log.debug { "spared ${target.plainTextName} from ${effect.effect.registeredName} by ${source?.type}" }
        return true
    }

    /** Whether [attacker], one of the Narrator's creatures, must leave [target] alone: a spared player. */
    @JvmStatic
    fun keepsAway(attacker: LivingEntity?, target: LivingEntity?): Boolean =
        target is Player && isNarratorsDoing(attacker) && spares(target)
}
