package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.ItemUsedOnLocationTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks placed, fluids poured, fires lit (OBSERVER_API.md §3.2). The {@code PLACED_BLOCK} trigger fires
 * right after a successful placement: {@code BlockItem.place}, {@code BucketItem.use} and
 * {@code FlintAndSteelItem.useOn}. The same class serves other triggers, hence the test on {@code this}.
 */
@Mixin(ItemUsedOnLocationTrigger.class)
abstract class ItemUsedOnLocationTriggerMixin {
    @Inject(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemInstance;)V",
        at = @At("HEAD")
    )
    private void narrator2$onPlaced(ServerPlayer player, BlockPos pos, ItemInstance item, CallbackInfo ci) {
        if ((Object) this == CriteriaTriggers.PLACED_BLOCK && item instanceof ItemStack stack) {
            ObserverHooks.onPlaced(player, pos.immutable(), stack);
        }
    }
}
