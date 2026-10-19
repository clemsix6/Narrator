package io.github.clemsix6.narrator2.forge.rt;

/**
 * The meter of one loaded module (M5 §3.4): one per module class loader, at the bench as on the server, set by the
 * host in the static field {@code m} of the module's holder class ({@code Top$-meter}) before any of the module's
 * code runs. The rewritten bytecode calls {@link #enter()} at each method entry, {@link #exit()} before each return
 * and {@link #tick()} before each backward jump; the host {@link #arm}s it around each call of the module and reads
 * {@link #tripped()} after. One thread at a time: the module's calls never overlap.
 *
 * <p>Contract (task M5-04 writes the bodies; until then they limit nothing):
 * <ul>
 *   <li>Every 16 ticks, {@code enter} counting as one (a module's lambda that a JDK loop calls has no backward jump of
 *       its own), it compares the wall clock ({@code System.nanoTime}) to the call's budget and, when the JVM measures
 *       it, the bytes the thread allocated since {@link #arm} to the call's allowance; past either, it trips.</li>
 *   <li>Past {@code Limits.MAX_DEPTH} frames counted by {@code enter} and {@code exit}, it counts the module's real
 *       frames ({@code StackWalker}: an exception unwinding the module's frames skips their {@code exit}) before it
 *       trips.</li>
 *   <li>Tripping is sticky: it throws a {@link ForgeLimit}, then every later {@code enter} or {@code tick} of the
 *       same call throws again (a loop in a {@code finally} cannot outlive it), and {@link #tripped()} keeps the cause
 *       even if the module swallowed the error.</li>
 *   <li>Outside {@link #arm} … {@link #disarm}, no module code may run: {@code enter} and {@code tick} throw a
 *       {@link ForgeLimit} (the host never calls a module's object outside an armed call, M5 §4.1).</li>
 * </ul>
 */
public final class Meter {
    private final ClassLoader owner;

    /** @param owner the module's class loader: the frames of its classes are the module's own. */
    public Meter(ClassLoader owner) {
        this.owner = owner;
    }

    /** Starts a call of the module: {@code budgetNanos} of wall clock and {@code allocationBytes} allocated at most; clears {@link #tripped()}. */
    public void arm(long budgetNanos, long allocationBytes) {
    }

    /** Ends the call: from now on, no module code may run until the next {@link #arm}. */
    public void disarm() {
    }

    /**
     * Why the last call was stopped ({@code time budget}, {@code allocation budget}, {@code call depth over 64}), with
     * the module's line when it is known ({@code at TagGame.java:41}), even if the module swallowed the error; null
     * when it was not stopped.
     */
    public String tripped() {
        return null;
    }

    /** The bytes the thread allocated since {@link #arm}; -1 when the JVM does not measure it (no {@code jdk.management}). */
    public long allocatedSinceArm() {
        return -1;
    }

    /** At each method entry of the module: one frame deeper, and one tick. */
    public void enter() {
    }

    /** Before each return of the module's methods: one frame less. */
    public void exit() {
    }

    /** Before each backward jump of the module's code. */
    public void tick() {
    }
}
