package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Taming (OBSERVER_API.md §3.16): {@code TamableAnimal.tame} and {@code AbstractHorse.tameWithName}. */
@Mixin(TameAnimalTrigger.class)
abstract class TameAnimalTriggerMixin {
    @Inject(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/animal/Animal;)V",
        at = @At("HEAD")
    )
    private void narrator2$onTamed(ServerPlayer player, Animal animal, CallbackInfo ci) {
        ObserverHooks.onTamed(player, animal);
    }
}
