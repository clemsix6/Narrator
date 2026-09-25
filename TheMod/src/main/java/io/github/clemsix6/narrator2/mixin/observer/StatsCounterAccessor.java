package io.github.clemsix6.narrator2.mixin.observer;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The whole stats table of a player (OBSERVER_API.md §5.2), read by {@code StatsTracker} for its
 * snapshots. The field is protected; its {@code dirty} set is useless (emptied whenever a client opens
 * its statistics screen).
 */
@Mixin(StatsCounter.class)
public interface StatsCounterAccessor {
    @Accessor("stats")
    Object2IntMap<Stat<?>> narrator2$stats();
}
