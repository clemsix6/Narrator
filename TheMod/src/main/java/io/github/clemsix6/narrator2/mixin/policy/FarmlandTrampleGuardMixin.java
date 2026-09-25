package io.github.clemsix6.narrator2.mixin.policy;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.clemsix6.narrator2.guard.CreatureShield;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Narrator's creatures never trample the players' farmland (M2 review, finding 6): a zombie, or a cow
 * of a rain of creatures, landing on it leaves it farmland, with its crop. Only the call that turns it back
 * into dirt is skipped ({@code turnToBaseBlock} in {@code fallOn}, read with {@code javap -c}); the fall
 * itself, and its damage, go on as usual. {@link CreatureShield#isNarratorsDoing} also covers a player's
 * ride on one of the Narrator's mounts: the mount is what lands.
 */
@Mixin(FarmlandBlock.class)
abstract class FarmlandTrampleGuardMixin {
    @WrapWithCondition(
        method = "fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;D)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"
        )
    )
    private boolean narrator2$neverForTheNarrator(FarmlandBlock block, Entity entity, BlockState state, Level level, BlockPos pos) {
        return !CreatureShield.INSTANCE.isNarratorsDoing(entity);
    }
}
