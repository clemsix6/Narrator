package io.github.clemsix6.narrator2.mixin.guard;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.clemsix6.narrator2.guard.DeathGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * At respawn, {@code restoreFrom(oldPlayer, false)} copies inventory, experience and score to the new
 * player object when {@code keepInventory} is true: it reads as true when the old player died marked by
 * the Narrator's death guard. The mark is read on the old player, which carries it in its saved data:
 * it holds across a disconnection or a restart between the death and the respawn.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerKeepInventoryMixin {
    @WrapOperation(
        method = "restoreFrom",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"
        )
    )
    private Object narrator2$keepInventory(
        GameRules rules,
        GameRule<?> rule,
        Operation<Object> original,
        @Local(argsOnly = true) ServerPlayer oldPlayer
    ) {
        if (rule == GameRules.KEEP_INVENTORY && DeathGuard.keepsInventory(oldPlayer)) {
            return Boolean.TRUE;
        }
        return original.call(rules, rule);
    }
}
