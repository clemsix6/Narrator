package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * The judged challenge a deployment referees ({@code deploy_module challenge}, M5 §6.4): the module names its winners
 * itself. Its end is queued like an act and answers like one ({@link Pending}); the engine then pays the challenge's
 * stakes and tells the Director. A deployment that ends without {@link #win} or {@link #lose} leaves the challenge to
 * the Director.
 */
public interface Game {
    /** The challenge, e.g. {@code C-75}. */
    String id();

    /** Its participants' names, in the order they were entered. */
    List<String> players();

    /** Whether it still runs: false once it was won, lost, cancelled or expired, by the module or anyone else. */
    boolean running();

    /**
     * Won by {@code winners}, among {@link #players()}: the reward to them, the penalty to the others. {@code note}:
     * French, how they won (« Mimosa a été le moins longtemps loup »), cut to {@link Limits#MAX_NOTE_CHARS}. Once
     * only: a challenge already over answers {@code invalid}.
     */
    Pending win(List<String> winners, String note);

    /** Nobody won: every participant entered at the start gets the penalty. {@code note} as {@link #win}. */
    Pending lose(String note);
}
