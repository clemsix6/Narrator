package io.github.clemsix6.narrator2.forge.api;

/**
 * Someone in a {@link GameFact}: a player or a creature, as the fact saw it (no lookup in the world).
 *
 * @param type {@code player} for a player, else the creature's type id ({@code skeleton}).
 * @param uuid its UUID, as text; null when the game did not tell. Compare it with {@link Creatures#get} to know a
 *     creature of one's own.
 * @param name a player's name, or a creature's custom name; null for a creature without one.
 */
public record Who(String type, String uuid, String name) {
    /** Whether it is a player ({@link #name()} is then their name). */
    public boolean isPlayer() {
        return "player".equals(type);
    }
}
