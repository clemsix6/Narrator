package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.LootTableTrigger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A container opened with its loot table still set: a structure chest, barrel or minecart opened for
 * the first time, or suspicious sand brushed (OBSERVER_API.md §3.9).
 */
@Mixin(LootTableTrigger.class)
abstract class LootTableTriggerMixin {
    @Inject(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;)V",
        at = @At("HEAD")
    )
    private void narrator2$onLootOpened(ServerPlayer player, ResourceKey<LootTable> lootTable, CallbackInfo ci) {
        ObserverHooks.onLootOpened(player, lootTable);
    }
}
