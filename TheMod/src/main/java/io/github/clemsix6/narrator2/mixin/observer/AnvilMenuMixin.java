package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The result of an anvil taken (OBSERVER_API.md §3.7). At the head, both inputs and the cost are still
 * in the menu; the stack is the result.
 */
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin {
    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void narrator2$onTake(Player player, ItemStack result, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ObserverHooks.onAnvil(serverPlayer, (AnvilMenu) (Object) this, result);
        }
    }
}
