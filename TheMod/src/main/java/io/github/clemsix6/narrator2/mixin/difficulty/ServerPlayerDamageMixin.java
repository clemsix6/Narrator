package io.github.clemsix6.narrator2.mixin.difficulty;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.clemsix6.narrator2.difficulty.Difficulty;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every blow a player takes goes through {@code hurtServer}: what a hostile creature inflicts, its arrows, fireballs,
 * explosions and potions included, is scaled at its head, before the vanilla difficulty, the armor and Fabric's
 * {@code ALLOW_DAMAGE} ({@code DIFFICULTY_API.md} §1.5). Effects (poison, wither) have no attacker and stay vanilla.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float narrator2$hostileDamage(float amount, @Local(argsOnly = true) DamageSource source) {
        return Difficulty.scaleDamage(source, amount);
    }
}
