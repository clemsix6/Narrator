package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Trades with villagers and wandering traders (OBSERVER_API.md §3.8): one call per trade made, from
 * {@code MerchantResultSlot.onTake}. At the head, the offer's uses are not increased yet.
 */
@Mixin(AbstractVillager.class)
abstract class AbstractVillagerMixin {
    @Inject(method = "notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V", at = @At("HEAD"))
    private void narrator2$onTrade(MerchantOffer offer, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (self.getTradingPlayer() instanceof ServerPlayer player) {
            ObserverHooks.onTrade(player, self, offer);
        }
    }
}
