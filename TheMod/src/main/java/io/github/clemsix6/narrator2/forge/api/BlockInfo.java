package io.github.clemsix6.narrator2.forge.api;

/**
 * A block as it is now ({@link World#block}).
 *
 * @param block its id ({@code stone}, {@code oak_planks}, {@code water}).
 * @param air nothing there: a place for {@link Ground#place}.
 * @param liquid water or lava.
 * @param solid one can stand on it.
 * @param placedByPlayer a player placed it, and it is still there: part of someone's construction.
 */
public record BlockInfo(Cell cell, String block, boolean air, boolean liquid, boolean solid, boolean placedByPlayer) {}
