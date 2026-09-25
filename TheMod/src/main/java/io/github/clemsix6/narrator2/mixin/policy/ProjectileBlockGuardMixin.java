package io.github.clemsix6.narrator2.mixin.policy;

import io.github.clemsix6.narrator2.guard.CreatureShield;
import io.github.clemsix6.narrator2.guard.NarratorMobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Narrator's projectiles change no block they hit (M2 review, finding 6): a skeleton's flaming arrow
 * neither lights a campfire nor primes TNT, an arrow does not break a decorated pot or a chorus flower, a
 * trident does not break pointed dripstone. Every block that reacts to a projectile that way asks it
 * {@code mayInteract} and, to break, {@code mayBreak} (read with {@code javap -c}; no projectile overrides
 * either): both say no for a projectile of the Narrator's doing ({@link CreatureShield#isNarratorsDoing}).
 * What only rings, sounds or signals (bells, targets, amethyst) is left alone.
 *
 * <p>A projectile shot by one of the Narrator's creatures also takes the {@code narrator2} tag when its
 * owner is set: it stays the Narrator's once its owner is gone (expired while it flies), when
 * {@code getOwner()} no longer finds it and the game would treat it as an ownerless projectile, which may
 * break blocks. In 26.3, {@code mayBreak} already refuses a projectile owned by a creature (it takes an
 * adventure-mode predicate, never set on theirs): that ownerless moment is the one it covers.
 */
@Mixin(Projectile.class)
abstract class ProjectileBlockGuardMixin {
    @Inject(method = "setOwner(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void narrator2$markTheNarratorsShots(Entity owner, CallbackInfo ci) {
        if (NarratorMobs.Companion.isNarrators(owner)) ((Entity) (Object) this).addTag(NarratorMobs.TAG);
    }

    @Inject(
        method = "mayInteract(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$noInteractionForTheNarrator(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (CreatureShield.INSTANCE.isNarratorsDoing((Entity) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(
        method = "mayBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void narrator2$noBreakingForTheNarrator(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (CreatureShield.INSTANCE.isNarratorsDoing((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
