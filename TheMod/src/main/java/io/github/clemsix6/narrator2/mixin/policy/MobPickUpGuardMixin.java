package io.github.clemsix6.narrator2.mixin.policy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.clemsix6.narrator2.guard.NarratorMob;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The creatures the Narrator summoned never pick up items from the ground: they disappear when due, and what
 * they held would disappear with them. Only those (the {@code narrator2:mob} attachment): a creature a rule
 * merely changed bears the Narrator's tag too, but lives on, and keeps picking up as any other (a piglin its
 * gold, a villager its bread: M4 code review, finding 30). {@code Mob.aiStep} gates every pickup on
 * {@code canPickUpLoot()} (read with {@code javap -c}); the call is wrapped there rather than the method itself,
 * which allays and sulfur cubes override.
 */
@Mixin(Mob.class)
abstract class MobPickUpGuardMixin {
    @WrapOperation(method = "aiStep()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;canPickUpLoot()Z"))
    private boolean narrator2$neverForTheNarrator(Mob mob, Operation<Boolean> original) {
        return original.call(mob) && !mob.hasAttached(NarratorMob.Companion.getTYPE());
    }
}
