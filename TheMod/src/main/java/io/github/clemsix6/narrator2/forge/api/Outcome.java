package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * How an act ended (M5 §4.4).
 *
 * @param status the tool's own status: {@code ok}, {@code invalid} (bad arguments: a player not online, an unknown
 *     id), {@code denied} (a guard or a limit refused: the player is protected, already punished, the chaos level
 *     forbids it, too many acts), {@code failed} (it could not be done now), {@code unknown} (the tool did not answer
 *     in time: it may or may not have happened); or {@code dropped}: the deployment ended before the act was done.
 * @param text the tool's answer, English, as the Director would read it: {@code ok: …},
 *     {@code DENIED (protected): …}.
 * @param creatures the UUIDs of the creatures the act summoned, now its own ({@link Acts#spawn}); empty otherwise.
 */
public record Outcome(String status, String text, List<String> creatures) {
    public Outcome {
        creatures = creatures == null ? List.of() : List.copyOf(creatures);
    }

    /** Whether it was done: {@code status} is {@code ok}. */
    public boolean ok() {
        return "ok".equals(status);
    }
}
