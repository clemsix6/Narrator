package io.github.clemsix6.narrator2.forge.api;

import io.github.clemsix6.narrator2.tool.ToolText;

/**
 * A point in a dimension: where a player stands (their feet), where a fact happened, where to act.
 *
 * @param dimension {@code overworld}, {@code the_nether}, {@code the_end}, or another mod's id with its namespace;
 *     written in any of its forms ({@code minecraft:the_nether}, {@code nether}, {@code End}), it is kept in the
 *     SDK's ({@code the_nether}), so that two places of the same dimension are equal.
 * @param x east, in blocks.
 * @param y up, in blocks.
 * @param z south, in blocks.
 * @throws ForgeRefusal without a dimension, or with a coordinate that is not a finite number.
 */
public record Place(String dimension, double x, double y, double z) {
    public Place {
        dimension = dimensionOf(dimension);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new ForgeRefusal("a place's coordinates must be finite numbers");
    }

    /** The straight distance to {@code other}, in blocks; {@code Double.POSITIVE_INFINITY} in another dimension. */
    public double distance(Place other) {
        if (!dimension.equals(other.dimension)) return Double.POSITIVE_INFINITY;
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** This place moved by {@code dx}, {@code dy}, {@code dz} blocks. */
    public Place offset(double dx, double dy, double dz) {
        return new Place(dimension, x + dx, y + dy, z + dz);
    }

    /** The block cell it is in. */
    public Cell cell() {
        return new Cell(dimension, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** {@code raw} as the SDK writes a dimension: the Narrator's own form (`ToolText`), one source for the tools and the SDK. */
    static String dimensionOf(String raw) {
        if (raw == null || raw.isBlank()) throw new ForgeRefusal("a place needs a dimension: overworld, the_nether or the_end");
        return ToolText.INSTANCE.dimension(ToolText.INSTANCE.dimensionId(raw));
    }
}
