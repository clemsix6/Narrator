package io.github.clemsix6.narrator2.forge.api;

/**
 * A block cell in a dimension: the block at {@code x}, {@code y}, {@code z}.
 *
 * @param dimension as {@link Place#dimension()}, kept in the SDK's form.
 * @throws ForgeRefusal without a dimension.
 */
public record Cell(String dimension, int x, int y, int z) {
    public Cell {
        dimension = Place.dimensionOf(dimension);
    }

    /** The middle of the cell: {@code x + 0.5}, {@code y + 0.5}, {@code z + 0.5}. */
    public Place center() {
        return new Place(dimension, x + 0.5, y + 0.5, z + 0.5);
    }

    /** The cell {@code dx}, {@code dy}, {@code dz} blocks away: {@code offset(0, 1, 0)} is the one above. */
    public Cell offset(int dx, int dy, int dz) {
        return new Cell(dimension, x + dx, y + dy, z + dz);
    }
}
