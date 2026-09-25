package io.github.clemsix6.narrator2.mixin.policy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.clemsix6.narrator2.guard.NarratorMobs;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Narrator's creatures never pick up items from the ground: they disappear when due, and what they
 * held would disappear with them. {@code Mob.aiStep} gates every pickup on {@code canPickUpLoot()} (read
 * with {@code javap -c}); the call is wrapped there rather than the method itself, which allays and
 * sulfur cubes override.
 */
@Mixin(Mob.class)
abstract class MobPickUpGuardMixin {
    @WrapOperation(method = "aiStep()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;canPickUpLoot()Z"))
    private boolean narrator2$neverForTheNarrator(Mob mob, Operation<Boolean> original) {
        return original.call(mob) && !NarratorMobs.Companion.isNarrators(mob);
    }
}
