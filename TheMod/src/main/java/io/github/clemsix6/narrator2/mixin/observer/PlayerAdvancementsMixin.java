package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Advancements earned (OBSERVER_API.md §3.11). The call to {@code AdvancementRewards.grant} is reached
 * exactly once per advancement, when it goes from not done to done (checked with javap: the only call
 * in {@code award}).
 */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(
        method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void narrator2$onEarned(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        ObserverHooks.onAdvancement(player, advancement);
    }
}
