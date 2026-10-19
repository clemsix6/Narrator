package io.github.clemsix6.narrator2.forge.api;

import java.util.function.Consumer;

/** An act on its way ({@link Acts}, {@link Ground#place}, {@link Creature#attack}, {@link Game}). Ignore it if the outcome does not matter. */
public interface Pending {
    /**
     * Calls {@code callback} with the act's {@link Outcome} on the server thread, in a call of its own, at the tick
     * after the act was done (or refused, or dropped). One callback per act: a second {@code then} throws
     * {@link ForgeRefusal}. Given after the outcome came, it is called at the next tick. Not called once the
     * deployment has ended.
     */
    void then(Consumer<Outcome> callback);
}
