package io.github.clemsix6.narrator2.mixin.difficulty;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.clemsix6.narrator2.difficulty.Difficulty;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Bow archers shoot faster ({@code difficulty.mobFireRate}, M7-SKELETONS). In {@code tick}, the archer fires once
 * {@code getTicksUsingItem()} reaches 20, with the power {@code BowItem.getPowerForTime} of that same value, then waits
 * {@code attackIntervalMin} ticks before drawing again (bytecode of 26.3). Both are scaled by the archer's fire rate:
 * the bow is fully drawn sooner, so each arrow keeps its full power, and the wait is shorter. Read at each tick, so a
 * reload applies at once.
 */
@Mixin(RangedBowAttackGoal.class)
abstract class RangedBowAttackGoalFireRateMixin {
    @Shadow
    @Final
    private Monster mob;

    @ModifyExpressionValue(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Monster;getTicksUsingItem()I")
    )
    private int narrator2$drawFaster(int ticks) {
        return Difficulty.drawTicks(this.mob, ticks);
    }

    @ModifyExpressionValue(
        method = "tick",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/goal/RangedBowAttackGoal;attackIntervalMin:I")
    )
    private int narrator2$waitLess(int ticks) {
        return Difficulty.attackInterval(this.mob, ticks);
    }
}
