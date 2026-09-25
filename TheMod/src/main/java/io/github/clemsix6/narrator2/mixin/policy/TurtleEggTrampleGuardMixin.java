package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.TurtleEggBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's creatures never trample turtle eggs (M2 review, finding 6). Walking on them
 * ({@code stepOn}) and falling on them ({@code fallOn}) both crack an egg through {@code destroyEgg}, which
 * asks {@code canDestroyEgg} first (read with {@code javap -c}): it says no for the Narrator's doing. Their
 * zombies' deliberate egg-breaking goal is guarded by {@link RemoveBlockGoalGuardMixin}.
 */
@Mixin(TurtleEggBlock.class)
abstract class TurtleEggTrampleGuardMixin {
    @Inject(
        method = "canDestroyEgg(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$neverForTheNarrator(ServerLevel level, BlockPos pos, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CreatureShield.INSTANCE.isNarratorsDoing(entity)) cir.setReturnValue(false);
    }
}
