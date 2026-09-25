package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.ConsumeItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Food and drinks (OBSERVER_API.md §3.5). The only caller is {@code Consumable.onConsume}, before the
 * effects apply and before the stack shrinks: the hook reads the "before" values.
 */
@Mixin(ConsumeItemTrigger.class)
abstract class ConsumeItemTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void narrator2$onConsumed(ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        ObserverHooks.onConsumed(player, stack);
    }
}
