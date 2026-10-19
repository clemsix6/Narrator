package io.github.clemsix6.narrator2.forge.api;

import java.util.Random;

/**
 * What a deployed module can use (M5 §4.2): one per deployment, given to {@link ForgeModule#start} and
 * {@link ForgeModule#stop}; keep it in a field. Only from the module's own calls, on the server thread; nothing
 * blocks. Once the deployment has ended (after {@code stop} returns), every method throws {@link ForgeRefusal}.
 */
public interface ModuleContext {
    /** Its deployment: the canon row the Director's {@code deploy_module} wrote, e.g. {@code C-70}. */
    String id();

    /**
     * True when it starts again after a server restart: its {@link #storage()} is back, its creatures come back as
     * their chunks load, but its subscriptions and timers are not (start makes them again), and the facts of the
     * restart (the players' joins among them) were missed.
     */
    boolean restarted();

    /** Now, epoch milliseconds of the server's clock (one tick is 50 ms at the bench). */
    long now();

    /** When the deployment ends by itself ({@link StopReason#EXPIRED}), epoch milliseconds. */
    long endsAt();

    /** Its own random numbers, seeded for this deployment. {@code ints}, {@code longs} and {@code doubles} (endless streams) are refused. */
    Random random();

    /** Its parameters, as the Director gave them and they were checked. */
    Args args();

    /** The facts of the game it subscribes to. */
    Events events();

    /** Its timers. */
    Timers timers();

    /** Reads of the world, immediate. */
    World world();

    /** The Narrator's tools: queued, done after the call. */
    Acts acts();

    /** The creatures it summoned or claimed, and steers. */
    Creatures creatures();

    /** Temporary blocks in thin air. */
    Ground ground();

    /** Its small saved state, kept with the world. */
    Storage storage();

    /** The judged challenge it referees ({@code deploy_module challenge}); null when it referees none. */
    Game game();

    /**
     * A line for the server's log and the deployment's trace (English), cut to {@link Limits#MAX_NOTE_CHARS};
     * past {@link Limits#MAX_LOG_LINES_PER_MINUTE} a minute, lines are dropped and counted.
     */
    void log(String line);

    /**
     * Wakes the Director with a {@code MODULE} line: French, what he should know (« Bartok est éliminé, il reste
     * Mimosa »), cut to {@link Limits#MAX_NOTE_CHARS}. One every {@link Limits#DIRECTOR_NOTE_GAP_MINUTES} minutes:
     * a note sooner waits for the gap, the latest one only, and the dropped ones are counted in it.
     */
    void tellDirector(String note);

    /**
     * Ends the deployment once this call returns: {@link ForgeModule#stop} with {@link StopReason#ENDED}, then its
     * creatures are taken back. {@code why} (French, cut to {@link Limits#MAX_NOTE_CHARS}) resolves its canon row and
     * is told to the Director. Calling it again changes nothing.
     */
    void end(String why);
}
