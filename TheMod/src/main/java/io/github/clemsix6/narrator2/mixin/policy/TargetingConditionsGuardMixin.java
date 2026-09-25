package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When one of the Narrator's creatures looks for someone to attack (target goals, brain sensors, the
 * revenge of {@code HurtByTargetGoal}: all go through the combat conditions), a player the Narrator may
 * not act against does not count ({@link CreatureShield}): the creature picks another target rather than
 * the nearest one it would drop at once. Non-combat conditions (looking at a player) are left alone.
 */
@Mixin(TargetingConditions.class)
abstract class TargetingConditionsGuardMixin {
    @Shadow
    @Final
    private boolean isCombat;

    @Inject(
        method = "test(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$spareShieldedPlayers(ServerLevel level, LivingEntity attacker, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (this.isCombat && CreatureShield.keepsAway(attacker, target)) cir.setReturnValue(false);
    }
}
