package io.github.clemsix6.narrator2.forge.api.test;

import io.github.clemsix6.narrator2.forge.api.Cell;
import io.github.clemsix6.narrator2.forge.api.CreatureInfo;
import io.github.clemsix6.narrator2.forge.api.GameFact;
import io.github.clemsix6.narrator2.forge.api.Place;
import java.util.List;

/**
 * A simulated world for a module's scenarios (M5 §7): the module runs there with the same sandbox as in the game,
 * but without physics, collisions, guards nor chunks, and its acts are recorded rather than done. A scenario is a
 * method {@code public void x(Bench b)} of the module's {@code public static} nested class {@code Scenarios}; each
 * runs on a new bench: set the world up, {@link #start} the module, feed it facts, {@link #run} ticks, then
 * {@link #check} what it did.
 *
 * <p>Fidelity: a tick is 50 ms of {@code now()}; the blocks are air above y = 64 and stone below, but for what the
 * scenario puts; creatures do not defend themselves; {@code moveTo} walks 0.2 × speed block a tick in a straight
 * line, {@code velocity} and {@code push} move them each tick; every act answers {@code ok (simulated)} unless
 * {@link #refuseNext} says otherwise. The bench checks the module's logic, not how it looks in the game.
 */
public interface Bench {
    /** An online player {@code name} at {@code at}: 20 health, 20 food, survival, on the ground, looking ahead. */
    void player(String name, Place at);

    /** Moves the player {@code name} to {@code to}. */
    void move(String name, Place to);

    /** How the player {@code name} stands: sneaking or not, on the ground or not, looking up (-90) to down (90). */
    void pose(String name, boolean sneaking, boolean onGround, float pitch);

    /** Puts {@code item} in the main hand of the player {@code name}. */
    void hold(String name, String item);

    /** Gives {@code count} {@code item}s to the player {@code name} ({@code World.count} reads them). */
    void give(String name, String item, int count);

    /** Sets the health of the player {@code name}. */
    void health(String name, float health);

    /** The player {@code name} leaves (a {@link GameFact.Left} follows when the module listens). */
    void leave(String name);

    /** Puts {@code block} at {@code cell}. */
    void block(Cell cell, String block);

    /** A wild creature of {@code type} at {@code at}, one the module may claim; its UUID. */
    String creature(String type, Place at);

    /**
     * The module referees a judged challenge of these players (call it before {@link #start}): its
     * {@code ctx.game()} is not null, and its win or loss is recorded with the acts ({@code game won: Mimosa}).
     */
    void challenge(String... players);

    /**
     * Deploys the module: constructor and {@code start}, with its parameters in their text form
     * ({@code "player=Bartok"}, {@code "chickens=4"}), checked as {@code deploy_module} checks them.
     */
    void start(String... params);

    /** Stops the module as the server does ({@code SERVER_STOPPING}), then starts a new instance with {@code restarted()} true and the same storage. */
    void restart();

    /** Delivers {@code fact} at the next tick, to the module's subscriptions to its class. */
    void fact(GameFact fact);

    /** The next act through {@code tool} ({@code punish}, {@code say}, {@code spawn}…) answers {@code text}, e.g. {@code DENIED (already_punished): …}. */
    void refuseNext(String tool, String text);

    /** Runs {@code ticks} ticks ({@code 20} a second): facts, timers, callbacks, creatures' motion. */
    void run(int ticks);

    /** The acts so far, in order, one line each: {@code say: Ça commence.}, {@code spawn chicken x6 near Bartok}, {@code punish Bartok tiny 2}. */
    List<String> acts();

    /** Its own creatures as the bench moved them. */
    List<CreatureInfo> creatures();

    /** Its {@code log} and {@code tellDirector} lines, in order. */
    List<String> notes();

    /** Why it ended ({@code ctx.end}, or its time ran out); null while it runs. */
    String ended();

    /** Fails the scenario with {@code message} when {@code condition} is false; the failure stands even if the scenario catches what it throws. */
    void check(boolean condition, String message);
}
