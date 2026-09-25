package io.github.clemsix6.narrator2.mixin.guard;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.clemsix6.narrator2.guard.DeathGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A dying player marked by the Narrator's death guard keeps their inventory and experience: the game
 * rule {@code keepInventory} reads as true for them in {@code dropEquipment} (inventory dropped) and
 * {@code getBaseExperienceReward} (experience orbs), each of which reads the rule once
 * ({@code ORACLE_ACTIONS_API.md} §5.3, checked with javap on 26.3).
 */
@Mixin(Player.class)
abstract class PlayerKeepInventoryMixin {
    @WrapOperation(
        method = {"dropEquipment", "getBaseExperienceReward"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"
        ),
        require = 2
    )
    private Object narrator2$keepInventory(GameRules rules, GameRule<?> rule, Operation<Object> original) {
        if (rule == GameRules.KEEP_INVENTORY && DeathGuard.keepsInventory((Player) (Object) this)) {
            return Boolean.TRUE;
        }
        return original.call(rules, rule);
    }
}
