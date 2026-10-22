package io.github.clemsix6.narrator2.mixin.difficulty;

import io.github.clemsix6.narrator2.difficulty.Difficulty;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Natural regeneration waits longer between heals, for the same food per heal: in {@code tick}, {@code bipush 10} is the
 * fast regeneration's interval and the first {@code bipush 80} the slow one's; the second 80 is starvation's, untouched
 * ({@code DIFFICULTY_API.md} §3, bytecode of 26.3).
 */
@Mixin(FoodData.class)
abstract class FoodDataRegenerationMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 10))
    private int narrator2$fastRegeneration(int ticks) {
        return Difficulty.regenerationTicks(ticks);
    }

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 80, ordinal = 0))
    private int narrator2$slowRegeneration(int ticks) {
        return Difficulty.regenerationTicks(ticks);
    }
}
