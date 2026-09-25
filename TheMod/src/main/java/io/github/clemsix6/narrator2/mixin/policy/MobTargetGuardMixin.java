package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player the Narrator may not act against is no valid target for its creatures ({@link CreatureShield}).
 * {@code Mob.asValidTarget} is the game's own gate, read with {@code javap -c}: {@code setTarget},
 * {@code getTarget} and {@code getTargetFromBrain} all go through it, and it already turns away creative
 * and spectator players; no subclass overrides it.
 */
@Mixin(Mob.class)
abstract class MobTargetGuardMixin {
    @Inject(
        method = "asValidTarget(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/LivingEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$spareShieldedPlayers(LivingEntity target, CallbackInfoReturnable<LivingEntity> cir) {
        if (CreatureShield.keepsAway((Mob) (Object) this, target)) cir.setReturnValue(null);
    }
}
