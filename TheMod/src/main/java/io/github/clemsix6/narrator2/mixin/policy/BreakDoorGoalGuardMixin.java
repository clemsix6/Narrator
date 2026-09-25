package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.NarratorMobs;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's creatures never break a door (M1_M2 §10.7): zombies and their kin on hard difficulty,
 * vindicators in a raid (whose goal calls this {@code canUse} first). Whatever {@code canBreakDoors} says,
 * and through conversions too: a converted creature keeps the {@code narrator2} tag.
 */
@Mixin(BreakDoorGoal.class)
abstract class BreakDoorGoalGuardMixin extends DoorInteractGoal {
    private BreakDoorGoalGuardMixin(Mob mob) {
        super(mob);
    }

    @Inject(method = "canUse()Z", at = @At("HEAD"), cancellable = true)
    private void narrator2$neverForTheNarrator(CallbackInfoReturnable<Boolean> cir) {
        if (NarratorMobs.Companion.isNarrators(this.mob)) cir.setReturnValue(false);
    }
}
