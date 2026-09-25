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
 * The result of a grindstone taken (OBSERVER_API.md §3.7). No trigger or stat fires at that moment, so
 * the target is the menu's output slot, an anonymous class.
 *
 * FRAGILE: in 26.3 the output slot is {@code GrindstoneMenu$4} (checked with javap: the fourth anonymous
 * class the constructor creates, added as slot 2 on {@code resultSlots}; {@code $1} is the input
 * container, {@code $2} and {@code $3} the two input slots). Any change to the menu's code can renumber
 * it. With {@code defaultRequire: 1} a missing {@code onTake} stops the server at start, and the in-game
 * test {@code grindstoneResultIsObserved} checks that slot 2 is still this class.
 */
@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
abstract class GrindstoneResultSlotMixin {
    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void narrator2$onTake(Player player, ItemStack result, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ObserverHooks.onGrindstone(serverPlayer, result);
        }
    }
}
