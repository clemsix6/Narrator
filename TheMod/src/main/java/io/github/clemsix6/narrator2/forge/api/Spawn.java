package io.github.clemsix6.narrator2.forge.api;

/**
 * How {@link Acts#spawn} summons: {@code Spawn.of(6).withHeight(3).withName("Poulette")}. Out of their bounds, the
 * act answers {@code invalid}.
 *
 * @param count how many, 1 to 12; the chaos level caps one call at 0, 2, 4, 6, 10 or 12 (chaos 0 to 5), and the
 *     Narrator's creatures alive in the whole world at 0, 4, 8, 16, 24 or 32: past either, the act is refused. Two by
 *     two passes at any level where summoning does; handle the refusal.
 * @param distance how far from the player they appear, between half this and this, 2 to 24 blocks.
 * @param name the name shown above each one, 32 characters; null for none.
 * @param target true: they go straight for the player (a hostile act, refused like any other).
 * @param lifetimeMinutes minutes before they vanish, 1 to 10 (a module that lasts summons again).
 * @param height they appear this many blocks above the ground, 0 to 30, without fall damage.
 * @param baby the baby form, for the creatures that have one.
 */
public record Spawn(int count, int distance, String name, boolean target, int lifetimeMinutes, int height, boolean baby) {
    /** {@code count} creatures, 8 blocks away at most, unnamed, not targeting, for 5 minutes, on the ground, adults. */
    public static Spawn of(int count) {
        return new Spawn(count, 8, null, false, 5, 0, false);
    }

    public Spawn withDistance(int distance) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }

    public Spawn withName(String name) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }

    public Spawn withTarget(boolean target) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }

    public Spawn withLifetimeMinutes(int lifetimeMinutes) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }

    public Spawn withHeight(int height) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }

    public Spawn withBaby(boolean baby) {
        return new Spawn(count, distance, name, target, lifetimeMinutes, height, baby);
    }
}
