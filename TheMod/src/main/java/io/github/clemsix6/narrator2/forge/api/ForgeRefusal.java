package io.github.clemsix6.narrator2.forge.api;

/**
 * The SDK refused a call (M5 §3.6): a limit of the SDK (subscriptions, timers, creatures, storage), a bad argument
 * (an unknown parameter, a radius too wide, an effect a creature may not get), or a deployment that is over. The
 * module may catch it and go on; uncaught, it is a fault of the call (three in ten minutes disable the module).
 */
public final class ForgeRefusal extends RuntimeException {
    /** @param message English: what was refused and why, e.g. {@code 16 subscriptions are active already, the most}. */
    public ForgeRefusal(String message) {
        super(message);
    }
}
