package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * Reads of the world (M5 §4.2): immediate, as the world is now, in loaded chunks only (a read never loads a chunk).
 * Nothing here changes anything.
 */
public interface World {
    /** The players online, spectators left out, in the order they joined. */
    List<PlayerInfo> players();

    /** The player {@code name} online, as now; null when they are not online, or a spectator. */
    PlayerInfo player(String name);

    /**
     * The creatures (mobs: no player, item, vehicle nor armor stand) within {@code radius} blocks of {@code around},
     * the nearest first, {@link Limits#MAX_CREATURES_FOUND} at most; its own ones too ({@link CreatureInfo#own()}).
     * {@code type}: a creature type id ({@code zombie}), or null for any. A radius beyond
     * {@link Limits#MAX_CREATURES_RADIUS} throws {@link ForgeRefusal}.
     */
    List<CreatureInfo> creatures(Place around, double radius, String type);

    /** The block at {@code cell}; null when its chunk is not loaded. */
    BlockInfo block(Cell cell);

    /**
     * Where one stands on the surface at {@code x}, {@code z} of {@code dimension}: the y of the first free block
     * above the highest block that blocks movement or holds a fluid (the game's {@code MOTION_BLOCKING} height);
     * {@code Integer.MIN_VALUE} when the chunk is not loaded.
     */
    int surface(String dimension, int x, int z);

    /** The biome id at {@code at} ({@code plains}); null when its chunk is not loaded. */
    String biome(Place at);

    /** How many items of {@code item} (an id, or a tag such as {@code #logs}) the player {@code player} carries, armor and off hand included; -1 when they are not online. */
    int count(String player, String item);

    /** The time of day in the overworld, 0 to 23,999 (0 is dawn, 6,000 noon, 13,000 nightfall, 18,000 midnight). */
    long dayTime();

    /** The overworld's weather: {@code clear}, {@code rain} or {@code thunder}. */
    String weather();
}
