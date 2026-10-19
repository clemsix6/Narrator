package io.github.clemsix6.narrator2.forge.api;

/**
 * The facts of the game a module receives ({@link Events#on}, M5 §4.3): what the Narrator's own observer saw,
 * delivered the tick after it happened. Ids are written as everywhere in the SDK ({@link Sdk}). Never a prayer, a
 * whisper or a private message.
 *
 * <p>{@link #player()} is the player the fact is about (a name); null only for {@link CreatureGone}. {@link #at()}
 * is the middle of the floor of the block where it happened; null when the game did not tell (some chat lines).
 * At the bench, {@code Bench.fact} delivers facts a scenario builds with these records.
 */
public sealed interface GameFact {
    String player();

    Place at();

    /** {@code player} broke {@code block}; {@code builtByAnother}: another player had placed it. */
    record BlockBroken(String player, Place at, String block, boolean builtByAnother) implements GameFact {}

    /** {@code player} placed {@code block} (not a fluid nor a fire). */
    record BlockPlaced(String player, Place at, String block) implements GameFact {}

    /** {@code player} hurt {@code creature} by {@code damage}, leaving it {@code health}; never the blow that kills it ({@link CreatureKilled}). */
    record CreatureHit(String player, Place at, Who creature, float damage, float health) implements GameFact {}

    /** {@code player} hurt the player {@code victim} by {@code damage}, without killing them. */
    record PlayerHit(String player, Place at, String victim, float damage) implements GameFact {}

    /**
     * {@code player} was hurt by {@code damage} ({@code cause}: the damage type, {@code fall}, {@code lava},
     * {@code mob_attack}, {@code arrow}…) by {@code attacker} (null when nothing hit them: a fall, lava). The hits of
     * one cause within 5 seconds come as one fact, their damage summed, up to 5 seconds late.
     */
    record PlayerHurt(String player, Place at, String cause, Who attacker, float damage) implements GameFact {}

    /** {@code player} killed {@code creature} (not a player). */
    record CreatureKilled(String player, Place at, Who creature) implements GameFact {}

    /**
     * A {@code projectile} ({@code arrow}, {@code snowball}…) landed: shot by {@code player}, or else hitting them.
     * {@code shooter}: who shot it (null when unknown); {@code target}: what it hit, null for a block.
     */
    record ProjectileHit(String player, Place at, String projectile, Who shooter, Who target) implements GameFact {}

    /** {@code player} died of {@code cause} (the damage type), killed by {@code killer} (null when nothing killed them). */
    record PlayerDied(String player, Place at, String cause, Who killer) implements GameFact {}

    /** {@code player} wrote {@code text} in the public chat. */
    record Chat(String player, Place at, String text) implements GameFact {}

    /** {@code player} went from dimension {@code from} to {@code to}; {@code at} is where they arrived. */
    record DimensionChanged(String player, Place at, String from, String to) implements GameFact {}

    /** {@code player} entered a {@code structure} ({@code village_plains}, {@code ancient_city}). */
    record StructureEntered(String player, Place at, String structure) implements GameFact {}

    /** {@code player} entered a {@code biome} ({@code desert}). */
    record BiomeEntered(String player, Place at, String biome) implements GameFact {}

    /** {@code player} earned an {@code advancement} ({@code story/mine_stone}). */
    record Advancement(String player, Place at, String advancement) implements GameFact {}

    /** {@code player} took {@code count} crafted or smelted {@code item}s. */
    record Crafted(String player, Place at, String item, int count) implements GameFact {}

    /** {@code player} ate or drank {@code item}. */
    record Consumed(String player, Place at, String item) implements GameFact {}

    /** {@code player} lay down in a bed. */
    record Slept(String player, Place at) implements GameFact {}

    /** {@code player} joined the server. */
    record Joined(String player, Place at) implements GameFact {}

    /** {@code player} left the server. */
    record Left(String player, Place at) implements GameFact {}

    /**
     * A wild {@code creature} (not the Narrator's, not tamed, not named) appeared or loaded within 64 blocks of an
     * online player; {@code player} is the nearest. One a module may {@link Creatures#claim}.
     */
    record CreatureAppeared(String player, Place at, Who creature) implements GameFact {}

    /**
     * One of the module's own {@code creature}s is gone: {@code why} is {@code died}, {@code vanished} (its lifetime
     * ran out, or it was removed) or {@code unloaded} (its chunk unloaded: it comes back as its own when it loads
     * again). {@code player} is null.
     */
    record CreatureGone(String player, Place at, Who creature, String why) implements GameFact {}
}
