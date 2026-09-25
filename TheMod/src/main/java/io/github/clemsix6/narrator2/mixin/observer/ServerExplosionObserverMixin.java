package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Explosions (OBSERVER_API.md §3.18). {@code explode} calls {@code interactWithBlocks} only when the
 * explosion touches blocks: its head counts the blocks still in place (and those placed by players),
 * kept on the explosion until the return of {@code explode} publishes the summary.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionObserverMixin {
    /** Blocks hit and, among them, blocks placed by players; null when no block was touched. */
    @Unique
    private int[] narrator2$blocksHit;

    @Inject(method = "interactWithBlocks(Ljava/util/List;)V", at = @At("HEAD"))
    private void narrator2$countBlocks(List<BlockPos> positions, CallbackInfo ci) {
        narrator2$blocksHit = ObserverHooks.explodedBlocks(((ServerExplosion) (Object) this).level(), positions);
    }

    @Inject(method = "explode()I", at = @At("RETURN"))
    private void narrator2$onExploded(CallbackInfoReturnable<Integer> cir) {
        ObserverHooks.onExplosion((ServerExplosion) (Object) this, narrator2$blocksHit);
    }
}
