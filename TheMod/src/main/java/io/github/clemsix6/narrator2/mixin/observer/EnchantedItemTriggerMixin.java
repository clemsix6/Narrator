package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enchanting table (OBSERVER_API.md §3.7). Called once the item is enchanted; the int is the cost in
 * levels.
 */
@Mixin(EnchantedItemTrigger.class)
abstract class EnchantedItemTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;I)V", at = @At("HEAD"))
    private void narrator2$onEnchanted(ServerPlayer player, ItemStack stack, int cost, CallbackInfo ci) {
        ObserverHooks.onEnchanted(player, stack, cost);
    }
}
