package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Projectiles that land (OBSERVER_API.md §3.17). The call to {@code onHit} is only reached when the
 * projectile was not deflected; no subclass overrides {@code hitTargetOrDeflectSelf}. Common code: the
 * hook drops the client side and everything that involves no player.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Inject(
        method = "hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V")
    )
    private void narrator2$onHit(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
        ObserverHooks.onProjectileHit((Projectile) (Object) this, hit);
    }
}
