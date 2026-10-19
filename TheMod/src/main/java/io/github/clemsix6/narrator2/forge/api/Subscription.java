package io.github.clemsix6.narrator2.forge.api;

/** A subscription to facts ({@link Events#on}). Every subscription ends with its deployment. */
public interface Subscription {
    /** No more facts from now on, even those already queued; its place among the subscriptions is free again. Cancelling twice changes nothing. */
    void cancel();

    /** Whether facts still come. */
    boolean active();
}
