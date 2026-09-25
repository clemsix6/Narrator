package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeding (OBSERVER_API.md §3.16). The player is the parent's "love cause"; the child is null for
 * turtles (an egg).
 */
@Mixin(BredAnimalsTrigger.class)
abstract class BredAnimalsTriggerMixin {
    @Inject(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V",
        at = @At("HEAD")
    )
    private void narrator2$onBred(ServerPlayer player, Animal parent, Animal partner, AgeableMob child, CallbackInfo ci) {
        ObserverHooks.onBred(player, parent);
    }
}
