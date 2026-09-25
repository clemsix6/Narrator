package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import java.util.Collection;
import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fishing (OBSERVER_API.md §3.16), from {@code FishingHook.retrieve}: a catch (the loot) or an entity
 * reeled in (empty loot).
 */
@Mixin(FishingRodHookedTrigger.class)
abstract class FishingRodHookedTriggerMixin {
    @Inject(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/FishingHook;Ljava/util/Collection;)V",
        at = @At("HEAD")
    )
    private void narrator2$onFished(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> loot, CallbackInfo ci) {
        ObserverHooks.onFished(player, hook, loot);
    }
}
