package io.github.clemsix6.narrator2.forge.rt;

/**
 * Thrown into a module's code past a limit of the sandbox (M5 §3.4): by its {@link Meter} (time, allocation, depth)
 * or at a call site the rewriting guards ({@link Guarded}: sizes, capacities). An {@code Error}, so that
 * {@code catch (Exception e)} does not swallow it, and the guard refuses {@code catch (Throwable)} and
 * {@code catch (Error)}. Invisible to a module's source: only the rewritten bytecode reaches this package.
 */
public final class ForgeLimit extends Error {
    /** @param message English, for the forger and the log: {@code time budget}, {@code a collection over 10000 elements}. */
    public ForgeLimit(String message) {
        super(message);
    }
}
