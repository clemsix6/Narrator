package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What a player does that only {@link ServerPlayer} sees (OBSERVER_API.md §3.4, §3.9, §3.10, §3.13, §3.19, §5.1):
 * stats, menus, respawn point, mounts, and the full death message. Every injection only hands plain
 * arguments to {@link ObserverHooks}, which never throws into the game.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerObserverMixin {
    /** Every stat goes through here (60-100 calls a second per player): the hook's first test is a reference comparison. */
    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
    private void narrator2$onStat(Stat<?> stat, int amount, CallbackInfo ci) {
        ObserverHooks.onStat((ServerPlayer) (Object) this, stat, amount);
    }

    /** At the return, the menu is open and is the player's {@code containerMenu}. */
    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At("RETURN"))
    private void narrator2$onMenuOpened(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (provider != null && cir.getReturnValue().isPresent()) {
            ObserverHooks.onMenuOpened((ServerPlayer) (Object) this, provider);
        }
    }

    /** At the head, the previous respawn point is still readable: the hook skips a point that does not change. */
    @Inject(method = "setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V", at = @At("HEAD"))
    private void narrator2$onRespawnPoint(ServerPlayer.RespawnConfig config, boolean sendMessage, CallbackInfo ci) {
        ObserverHooks.onRespawnPoint((ServerPlayer) (Object) this, config);
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
    private void narrator2$onMounted(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) ObserverHooks.onMounted((ServerPlayer) (Object) this, vehicle);
    }

    /** At the head, the vehicle is still known. */
    @Inject(method = "removeVehicle()V", at = @At("HEAD"))
    private void narrator2$onDismounting(CallbackInfo ci) {
        ObserverHooks.onDismounting((ServerPlayer) (Object) this);
    }

    /** At the head, the combat tracker still has the fight's context ("whilst trying to escape…"). */
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"))
    private void narrator2$onDying(DamageSource source, CallbackInfo ci) {
        ObserverHooks.onDying((ServerPlayer) (Object) this);
    }
}
