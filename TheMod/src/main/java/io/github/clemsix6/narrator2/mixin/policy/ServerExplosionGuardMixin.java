package io.github.clemsix6.narrator2.mixin.policy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.clemsix6.narrator2.guard.ExplosionGuard;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's explosions ({@link ExplosionGuard}, M1_M2 §10.7). In {@code explode()}, read with
 * {@code javap -c}: {@code hurtEntities()} skips every entity whose {@code ignoreExplosion(this)} is true
 * (no damage, no push), then {@code interactWithBlocks} runs only if {@code interactsWithBlocks()}, then
 * {@code createFire} only if the explosion has fire. The design's target in {@code hurtEntities},
 * {@code ExplosionDamageCalculator.shouldDamageEntity}, is there too, but it only spares from damage:
 * the push would still throw items and animals around, so the whole entity is skipped instead.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionGuardMixin {
    @Inject(method = "interactsWithBlocks()Z", at = @At("HEAD"), cancellable = true)
    private void narrator2$touchNoBlock(CallbackInfoReturnable<Boolean> cir) {
        if (ExplosionGuard.isNarrators((Explosion) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "createFire(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
    private void narrator2$startNoFire(List<BlockPos> positions, CallbackInfo ci) {
        if (ExplosionGuard.isNarrators((Explosion) (Object) this)) ci.cancel();
    }

    @WrapOperation(
        method = "hurtEntities()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;ignoreExplosion(Lnet/minecraft/world/level/Explosion;)Z")
    )
    private boolean narrator2$spareBystanders(Entity entity, Explosion explosion, Operation<Boolean> original) {
        return original.call(entity, explosion) || ExplosionGuard.isNarrators(explosion) && ExplosionGuard.spares(entity);
    }
}
