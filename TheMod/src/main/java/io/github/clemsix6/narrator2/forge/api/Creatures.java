package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * The creatures of a deployment (M5 §4.5): those {@link Acts#spawn} summoned for it, and those it claimed.
 * {@link Limits#MAX_OWN_CREATURES} at once. They are the Narrator's (they never pick up items, break blocks or
 * attack anything but players), take no fall damage while they are its own, never push players, and get back to
 * normal when it ends: a summoned one vanishes, a claimed one goes back where it was claimed, unchanged.
 */
public interface Creatures {
    /** Its creatures alive and loaded, in the order they became its own. */
    List<Creature> own();

    /** Its creature {@code uuid}, alive and loaded; null otherwise (never another's). */
    Creature get(String uuid);

    /**
     * Makes the wild creature {@code uuid} its own while it lives: a hostile creature ({@code zombie},
     * {@code skeleton}, {@code spider}…) that {@link Acts#spawn} could summon, within
     * {@link Limits#MAX_CLAIM_DISTANCE} blocks of an online player. Null when refused: an animal (the cattle of a pen
     * is a player's), a villager, a trader, a golem, a tamed, named or ridden creature, a boss or a creature out of
     * scale (warden, wither, ender dragon, elder guardian), another's, or {@link Limits#MAX_OWN_CREATURES} already.
     * A module that wants animals summons them.
     */
    Creature claim(String uuid);
}
