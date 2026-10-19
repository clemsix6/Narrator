package io.github.clemsix6.narrator2.forge.api;

/** A timer ({@link Timers}). */
public interface Scheduled {
    /** It will not run again; its place among the timers is free again. Cancelling twice, or a timer that ran, changes nothing. */
    void cancel();

    /** Whether it will still run: false once a one-time timer ran, or once cancelled. */
    boolean active();
}
