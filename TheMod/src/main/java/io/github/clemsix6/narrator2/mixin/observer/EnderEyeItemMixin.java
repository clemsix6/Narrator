package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * End portal activated (OBSERVER_API.md §3.12). {@code globalLevelEvent} is only called once the eye
 * completed the frame and the portal blocks are placed (checked with javap: the only call in
 * {@code useOn}).
 */
@Mixin(EnderEyeItem.class)
abstract class EnderEyeItemMixin {
    @Inject(
        method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;globalLevelEvent(ILnet/minecraft/core/BlockPos;I)V")
    )
    private void narrator2$onPortalLit(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        ObserverHooks.onEndPortalLit(context);
    }
}
