package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's creatures put no harmful effect on a player the Narrator may not act against
 * ({@link CreatureShield}): a witch's splash or lingering potion, whatever reaches a player without a
 * blow. {@code addEffect(MobEffectInstance, Entity)} is where every effect with a source goes (read with
 * {@code javap}: not overridden by players; {@code forceAddEffect} is the client's). Fabric's
 * {@code ALLOW_ADD} does not tell the source, hence this mixin.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectGuardMixin {
    @Inject(
        method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$spareShieldedPlayers(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        if (CreatureShield.blocksEffect((LivingEntity) (Object) this, effect, source)) cir.setReturnValue(false);
    }
}
