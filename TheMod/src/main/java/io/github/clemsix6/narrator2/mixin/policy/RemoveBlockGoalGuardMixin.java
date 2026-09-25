package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's creatures never seek out a block to break it (M2 review, finding 6): the zombies'
 * {@code ZombieAttackTurtleEggGoal}, which walks up to the turtle eggs within 24 blocks and crushes them,
 * is the game's only {@code RemoveBlockGoal}. It never starts for them: {@code canUse} says no.
 */
@Mixin(RemoveBlockGoal.class)
abstract class RemoveBlockGoalGuardMixin {
    @Shadow
    @Final
    private Mob removerMob;

    @Inject(method = "canUse()Z", at = @At("HEAD"), cancellable = true)
    private void narrator2$neverForTheNarrator(CallbackInfoReturnable<Boolean> cir) {
        if (CreatureShield.INSTANCE.isNarratorsDoing(this.removerMob)) cir.setReturnValue(false);
    }
}
