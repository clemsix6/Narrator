package io.github.clemsix6.narrator2.forge.api;

/**
 * A module's timers, counted in server ticks ({@link Limits#TICKS_PER_SECOND} a second): each run is a call of its
 * own, on the server thread. Prefer a timer every few ticks to work at each tick. A delay out of 1 to
 * {@link Limits#MAX_DELAY_TICKS}, or a timer past {@link Limits#MAX_PENDING_TIMERS} waiting
 * ({@link Limits#MAX_REPEATING_TIMERS} repeating), throws {@link ForgeRefusal}. Timers end with the deployment and
 * are not kept across a restart.
 */
public interface Timers {
    /** Runs {@code task} once, {@code ticks} ticks from now. */
    Scheduled after(int ticks, Runnable task);

    /** Runs {@code task} every {@code ticks} ticks, the first time {@code ticks} ticks from now, until it is cancelled. */
    Scheduled every(int ticks, Runnable task);
}
