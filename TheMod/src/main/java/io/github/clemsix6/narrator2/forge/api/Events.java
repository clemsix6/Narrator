package io.github.clemsix6.narrator2.forge.api;

import java.util.function.Consumer;

/** The facts of the game a module subscribes to (M5 §4.3). */
public interface Events {
    /**
     * Calls {@code handler} with each fact of {@code type} (one of the records of {@link GameFact}, never
     * {@code GameFact} itself), on the server thread, the tick after it happened, in order, each in a call of its
     * own. {@link Limits#MAX_SUBSCRIPTIONS} active subscriptions at most ({@link ForgeRefusal} beyond); facts past
     * the engine's limits (256 a tick, 3,000 a minute per deployment) are dropped and counted. A fact never comes
     * from a prayer, a whisper or a private message.
     *
     * @return the subscription, to cancel it.
     */
    <F extends GameFact> Subscription on(Class<F> type, Consumer<F> handler);
}
