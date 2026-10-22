package io.github.clemsix6.narrator2.forge.api;

/**
 * The Narrator's tools, for a module (M5 §4.4). Each method is one call of one of the Narrator's own tools, with the
 * same checks, caps and guards as when the Director calls it: it is queued, done once the module's call has
 * returned, and its {@link Outcome} comes back later ({@link Pending#then}). Nothing here throws for a refused act:
 * read the outcome and do not insist.
 *
 * <ul>
 *   <li>The acts of one call of the module are one act of the engine: the guards see them as one turn of the
 *       Director (one who died a minute ago, a protected player, too many punishments of one player this hour,
 *       the same kind of punishment too soon, chaos 0: each refused). {@link Limits#MAX_ACTS_PER_CALL} acts per call,
 *       {@link Limits#MAX_ACTS_PER_MINUTE} a minute; beyond, the outcome is {@code denied}
 *       ({@code DENIED (module_acts): …}) and nothing is done.</li>
 *   <li>Players are online players' names. A player who is not online: {@code invalid}.</li>
 *   <li>Texts players read are French, in the Narrator's voice.</li>
 *   <li>A module never gives nor takes an item, never runs a command, never changes the weather or the time: there
 *       is no method for that.</li>
 * </ul>
 */
public interface Acts {
    /**
     * {@code say}: speaks in the chat to everyone online. 300 characters, 3 lines at most. With
     * {@link #sayTo} and {@link #title}, {@link Limits#MAX_LINES_PER_MINUTE} a minute per deployment, within the
     * server's own speech limits.
     */
    Pending say(String text);

    /** {@code say}, privately to {@code player}: only they see it. As {@link #say}. */
    Pending sayTo(String player, String text);

    /**
     * {@code show_title}: a big title (18 characters at most) and an optional subtitle (36, or null) on the screen of
     * {@code player}, or of everyone online when it is null; longer is {@code invalid}, never cut. Counted as {@link #say}.
     */
    Pending title(String player, String title, String subtitle);

    /**
     * The action bar of {@code player} (everyone online when null): a game's display, a countdown or a score, 80
     * characters. {@link Limits#MAX_HUD_PER_SECOND} per player and second; not counted with the speech.
     */
    Pending hud(String player, String text);

    /** {@code play_sound}: a sound event id ({@code entity.chicken.egg}, {@code block.bell.use}) for {@code player}, or everyone online when null. */
    Pending sound(String player, String sound);

    /**
     * {@code particles}: {@code count} (1 to 200) particles at {@code at}. {@code particle}: one of the tool's list
     * ({@code heart}, {@code flame}, {@code smoke}, {@code cloud}, {@code firework}, {@code happy_villager},
     * {@code angry_villager}, {@code end_rod}, {@code note}…); another id answers {@code invalid} with the list.
     */
    Pending particles(Place at, String particle, int count);

    /** {@code fireworks}: 1 to 8 rockets that go up near {@code at} and burst high, harmless. */
    Pending fireworks(Place at, int count);

    /** {@code lightning} at {@code at}: thunder and a flash, harmless, no fire. */
    Pending lightning(Place at);

    /** {@code lightning} on {@code player}, which hurts: 1 to 5 hearts, capped by the chaos level. A punishment of the player. */
    Pending smite(String player, int hearts);

    /**
     * {@code explosion} at {@code at}: power 0.5 to 4 (capped by the chaos level). It never breaks a block nor starts a
     * fire. {@code hurts} false: a show that hurts and pushes nobody; true: a punishment of each player near.
     */
    Pending explosion(Place at, double power, boolean hurts);

    /**
     * {@code apply_effect} on {@code player}: an effect id ({@code speed}, {@code slowness}, {@code glowing}…) for
     * 1 to 900 seconds, level 1 to 5. A harmful one is a punishment, capped by the chaos level. Never
     * {@code wither}, {@code poison}, {@code instant_damage} nor {@code levitation}, as for a law: {@code denied}.
     */
    Pending effect(String player, String effect, int seconds, int level);

    /**
     * {@code set_attribute} on {@code player}: an attribute ({@code scale}, {@code gravity}, {@code movement_speed},
     * {@code jump_strength}…) multiplied by {@code multiply} for 10 to 900 seconds, then back by itself. A change in
     * their disfavor is a punishment.
     */
    Pending attribute(String player, String attribute, double multiply, int seconds);

    /**
     * {@code launch}: throws {@code player} {@code up}, {@code forward}, {@code backward}, {@code left},
     * {@code right} or {@code random}; strength 0.5 to 3, capped by the chaos level. No fall damage for 15 seconds.
     * Always a punishment.
     */
    Pending launch(String player, String direction, double strength);

    /**
     * {@code teleport}: moves {@code player} to {@code to}, in their own dimension, on a safe landing near it. A
     * move of more than 32 blocks is a punishment. Never to another player: a module never brings a player to
     * another.
     */
    Pending teleport(String player, Place to);

    /** {@code teleport}: moves {@code player} to a random safe spot of their dimension, between half {@code radius} and {@code radius} blocks away (10 to 500). A punishment. */
    Pending teleportRandom(String player, int radius);

    /**
     * {@code spawn}: summons creatures of {@code type} ({@code chicken}, {@code zombie}…) around the online player
     * {@code near}, as {@code how} says ({@link Spawn#of}; null is {@code Spawn.of(1)}). They become its own
     * ({@link Outcome#creatures()}, {@link Creatures}): they belong to the Narrator, never drop anything, never break a
     * block, attack players only, and vanish at the end of their lifetime or of the deployment. Hostile ones near a
     * player are a punishment. Never a boss nor a creature out of scale (warden, wither, ender dragon, elder
     * guardian…); beyond {@link Limits#MAX_OWN_CREATURES} own creatures: {@code denied}.
     */
    Pending spawn(String type, String near, Spawn how);

    /**
     * {@code punish}: a punishment of the Narrator's catalogue ({@code tiny}, {@code moon_gravity},
     * {@code slow_hands}, {@code heavy_feet}, {@code hunger}, {@code butterfingers}, {@code guests},
     * {@code slime_rain}, {@code skyward}, {@code tree_top}, {@code smite}, {@code cage}, {@code web}) on
     * {@code player}, severity 1 to 5 capped by the chaos level. {@code reason}: one factual line, French, third
     * person (« a bougé quand le Narrateur regardait »), kept in the player's history. One {@code punish} per call
     * and player.
     */
    Pending punish(String player, String punishment, int severity, String reason);
}
