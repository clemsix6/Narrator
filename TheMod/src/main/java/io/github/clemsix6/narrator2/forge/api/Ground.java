package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * Temporary blocks (M5 §4.4), through the Narrator's block restorer: in thin air only, taken away by themselves.
 * There is no pit and no floor: no block ever replaces a block that is there, not even natural ground (decision Q4 of
 * M5). Other blocks than these are never changed by a module.
 */
public interface Ground {
    /**
     * An act ({@link Acts}): puts {@code block} ({@code glass}, a stained glass such as {@code red_stained_glass},
     * {@code cobweb}, {@code slime_block} or {@code honey_block}) in each of {@code cells} (1 to
     * {@link Limits#MAX_PLACE_CELLS}, loaded, air), all or nothing, for {@code seconds} (1 to
     * {@link Limits#MAX_PLACE_SECONDS}). Within 2 blocks of a player it is a trap of that player, refused like any
     * punishment, its length capped by the chaos level, and the death guard watches over them.
     */
    Pending place(List<Cell> cells, String block, int seconds);
}
