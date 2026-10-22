package io.github.clemsix6.narrator2.mixin.difficulty;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.clemsix6.narrator2.difficulty.Difficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fewer ore blobs, each of vanilla size: the private {@code place} applies the placement modifiers, then calls
 * {@code Feature.place} once per position (two call sites); a share of the ore features' calls is skipped
 * ({@code DIFFICULTY_API.md} §2.3, measured: half the blobs at 0.5, same mean size). World generation threads.
 */
@Mixin(FeaturePlacer.class)
abstract class FeaturePlacerOreMixin {
    @WrapOperation(
        method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 2
    )
    private boolean narrator2$fewerOres(
        Feature feature,
        WorldGenLevel level,
        ChunkGenerator generator,
        RandomSource random,
        BlockPos pos,
        Operation<Boolean> original,
        @Local(argsOnly = true) PlacedFeature placed
    ) {
        if (Difficulty.skipsOre(placed, level.getSeed(), pos)) {
            return false;
        }
        return original.call(feature, level, generator, random, pos);
    }
}
