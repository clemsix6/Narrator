package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crafting, smelting, smithing, stonecutting (OBSERVER_API.md §3.6): {@code this} is the result taken out
 * of a result slot. {@code ItemStack} is common code, so the client's calls are dropped by the
 * {@code ServerPlayer} test, before anything else.
 */
@Mixin(ItemStack.class)
abstract class ItemStackCraftMixin {
    @Inject(method = "onCraftedBy(Lnet/minecraft/world/entity/player/Player;I)V", at = @At("HEAD"))
    private void narrator2$onCrafted(Player player, int amount, CallbackInfo ci) {
        if (amount > 0 && player instanceof ServerPlayer serverPlayer) {
            ObserverHooks.onCrafted(serverPlayer, (ItemStack) (Object) this, amount);
        }
    }
}
