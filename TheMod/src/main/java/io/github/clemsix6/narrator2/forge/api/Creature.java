package io.github.clemsix6.narrator2.forge.api;

/**
 * One of the deployment's own creatures ({@link Creatures}), steered at once, on the server thread, without a tool
 * (M5 §4.5). On a creature gone (dead, vanished, unloaded) the steering does nothing; once it is not its own any more
 * (released, or the deployment over), every method but {@link #uuid()}, {@link #info()} and {@link #alive()} throws
 * {@link ForgeRefusal}, and so does a bad argument. To act on a player, a creature {@link #attack}s: steering it into
 * players does not push them.
 */
public interface Creature {
    String uuid();

    /** As it is now; null once it is gone or not its own any more. */
    CreatureInfo info();

    /** Whether it lives, is loaded, and is still its own. */
    boolean alive();

    /** Walks to {@code to} (same dimension), as its pathfinding can, at {@code speed} 0.3 (slow) to 2.5 (running). */
    void moveTo(Place to, double speed);

    /**
     * Sets its motion, in blocks per tick, each component within -2 and 2: beyond, {@link ForgeRefusal}, never a capped
     * value (cap a computed speed yourself). With {@link #gravity} off, it keeps it.
     */
    void velocity(double x, double y, double z);

    /** Adds to its motion, each component within -2 and 2: beyond, {@link ForgeRefusal}, never a capped value. */
    void push(double x, double y, double z);

    /** Turns its head toward {@code at}. */
    void lookAt(Place at);

    /** Off: it floats where its motion takes it. On again: it falls, without fall damage while it is its own. */
    void gravity(boolean on);

    /** Its outline seen through walls. */
    void glow(boolean on);

    /** The name shown above it, 32 characters; null for none. */
    void name(String name);

    /**
     * Puts an effect on it ({@code speed}, {@code slowness}, {@code weakness}, {@code glowing}…) for 1 to 900 seconds,
     * level 1 to 5, as a law's {@code mob_effect}: never one that makes it hard to kill or unseen ({@code resistance},
     * {@code regeneration}, {@code invisibility}…), that wounds it ({@code wither}, {@code poison}) or makes it fall
     * ({@code levitation}, {@code jump_boost}).
     */
    void effect(String effect, int seconds, int level);

    /**
     * Multiplies an attribute of it while it is its own, as a law's {@code mob_attribute}: {@code scale} 0.5 to 2,
     * {@code movement_speed} 0.25 to 3, {@code max_health} 1 to 5, {@code attack_damage} 0 to 2,
     * {@code jump_strength} 0 to 1, {@code gravity} 1 to 2.
     */
    void attribute(String attribute, double multiply);

    /**
     * It goes for the online player {@code player}, never anything else: an act ({@link Acts}), hostile, refused like
     * any punishment; the death guard watches over the player. Its blows hurt but never kill: a fatal one is a rescue
     * where the player stands, nothing lost.
     */
    Pending attack(String player);

    /** It forgets its target. */
    void calm();

    /**
     * Not its own any more, at once: a summoned one vanishes; a claimed one gets back to normal (marks, attributes,
     * effects, gravity, glow, name, target) and back where it was claimed when that place is loaded and safe.
     */
    void release();
}
