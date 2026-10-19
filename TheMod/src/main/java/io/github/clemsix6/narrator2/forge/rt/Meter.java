package io.github.clemsix6.narrator2.forge.rt;

import io.github.clemsix6.narrator2.forge.ForgeLimits;
import io.github.clemsix6.narrator2.forge.api.Limits;
import java.lang.management.ManagementFactory;
import java.util.Objects;

/**
 * The meter of one loaded module (M5 §3.4): one per module class loader, at the bench as on the server, set by the
 * host in the static field {@code m} of the module's holder class ({@code Top$-meter}) before any of the module's
 * code runs. The rewritten bytecode calls {@link #enter()} at each method entry, {@link #exit()} before each return
 * and {@link #tick()} before each backward jump and at the entry of each exception handler; the host {@link #arm}s
 * it around each call of the module, {@link #disarm}s it after, then reads {@link #tripped()}. One thread at a time:
 * the module's calls never overlap, and a call is armed, run and disarmed on the same thread.
 *
 * <p>Contract:
 * <ul>
 *   <li>Every {@code ForgeLimits.METER_CHECK_EVERY} ticks, {@code enter} counting as one (a module's lambda that a
 *       JDK loop calls has no backward jump of its own), it compares the wall clock ({@code System.nanoTime}) to the
 *       call's budget and, when the JVM measures it, the bytes the thread allocated since {@link #arm} to the call's
 *       allowance; past either, it trips.</li>
 *   <li>Past {@code Limits.MAX_DEPTH} frames counted by {@code enter} and {@code exit}, it counts the module's real
 *       frames ({@code StackWalker}: an exception unwinding the module's frames skips their {@code exit}) before it
 *       trips.</li>
 *   <li>Tripping is sticky: it throws a {@link ForgeLimit}, then every later {@code enter} or {@code tick} of the
 *       same call throws again (a loop in a {@code finally} cannot outlive it), and {@link #tripped()} keeps the cause
 *       even if the module swallowed the error.</li>
 *   <li>{@link #disarm()} checks the clock and the allocation one last time: a call that overran its budget inside a
 *       single call of the JDK, where no tick could stop it, is tripped all the same (without a throw).</li>
 *   <li>Outside {@link #arm} … {@link #disarm}, no module code may run: {@code enter} and {@code tick} throw a
 *       {@link ForgeLimit} (the host never calls a module's object outside an armed call, M5 §4.1).</li>
 *   <li>{@link #revoke()}, from another thread, is final: when the host abandons a call that one long call of the JDK
 *       holds past its budget (M5-05), the abandoned thread throws at the module's very next {@code enter} or
 *       {@code tick}, armed or not, and the meter never runs module code again.</li>
 * </ul>
 */
public final class Meter {
    /** The JVM's count of the bytes each thread allocated; null without {@code jdk.management} (the clock then bounds a call alone). */
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocations();

    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final ClassLoader owner;
    private boolean armed;
    private long deadline;
    private long allocationStart;
    private long allocationAllowance;
    private long allocatedAtDisarm = -1;
    private int ticks;
    private int depth;
    private String tripped;

    /** Set once, by the host's thread, when it abandons a call: read at each enter and tick of the abandoned thread. */
    private volatile boolean revoked;

    /** @param owner the module's class loader: the frames of its classes are the module's own. */
    public Meter(ClassLoader owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Starts a call of the module: {@code budgetNanos} of wall clock and {@code allocationBytes} allocated at most; clears {@link #tripped()}. */
    public void arm(long budgetNanos, long allocationBytes) {
        deadline = System.nanoTime() + budgetNanos;
        allocationAllowance = allocationBytes;
        allocationStart = ALLOCATIONS == null ? 0 : ALLOCATIONS.getCurrentThreadAllocatedBytes();
        allocatedAtDisarm = -1;
        ticks = 0;
        depth = 0;
        tripped = null;
        armed = true;
    }

    /**
     * Ends the call: from now on, no module code may run until the next {@link #arm}. A call that overran its time or
     * its allocation without a tick to notice it (one long call of the JDK) is tripped here; read {@link #tripped()}
     * after this.
     */
    public void disarm() {
        if (!armed) return;
        if (tripped == null) {
            if (System.nanoTime() > deadline) tripped = "time budget";
            else if (overAllocated()) tripped = "allocation budget";
        }
        allocatedAtDisarm = ALLOCATIONS == null ? -1 : allocatedNow();
        armed = false;
    }

    /**
     * Why the last call was stopped ({@code time budget}, {@code allocation budget}, {@code call depth over 64}), with
     * the module's line when it is known ({@code time budget at TagGame.java:41}), even if the module swallowed the
     * error; null when it was not stopped.
     */
    public String tripped() {
        return tripped;
    }

    /** The bytes the thread allocated since {@link #arm} (until {@link #disarm} once disarmed); -1 when the JVM does not measure it (no {@code jdk.management}). */
    public long allocatedSinceArm() {
        if (ALLOCATIONS == null) return -1;
        return armed ? allocatedNow() : allocatedAtDisarm;
    }

    /**
     * Starts a call nested in the one running now, on the same thread (at the bench, a scenario's code calls the
     * module's handlers, M5-05): armed for {@code budgetNanos}, but never past what the running call has left, with
     * {@code allocationBytes} of its own. Returns the running call's state, for {@link #unnest}.
     */
    public Nested nest(long budgetNanos, long allocationBytes) {
        Nested running = new Nested(armed, deadline, allocationStart, allocationAllowance, allocatedAtDisarm, ticks, depth, tripped);
        long left = armed ? Math.max(0, deadline - System.nanoTime()) : budgetNanos;
        arm(Math.min(budgetNanos, left), allocationBytes);
        return running;
    }

    /**
     * Resumes the call that was running ({@code running}, from {@link #nest}) once the nested one is disarmed: its
     * deadline unchanged, what the nested call allocated not counted twice.
     */
    public void unnest(Nested running) {
        long nested = Math.max(0, allocatedAtDisarm);
        armed = running.armed;
        deadline = running.deadline;
        allocationStart = running.allocationStart + nested;
        allocationAllowance = running.allocationAllowance;
        allocatedAtDisarm = running.allocatedAtDisarm;
        ticks = running.ticks;
        depth = running.depth;
        tripped = running.tripped;
    }

    /** The state of a call while another one runs nested in it. */
    public record Nested(boolean armed, long deadline, long allocationStart, long allocationAllowance, long allocatedAtDisarm,
                         int ticks, int depth, String tripped) {
    }

    /**
     * The host abandoned the call running now (M5-05): it ran past its budget inside one call of the JDK, and the
     * server went on without it. Any thread; final. From now on, every {@code enter} and {@code tick} throws, so the
     * abandoned thread ends at the module's next code instead of running it.
     */
    public void revoke() {
        revoked = true;
    }

    /** At each method entry of the module: one frame deeper, and one tick. */
    public void enter() {
        if (revoked) throw abandoned();
        if (!armed) throw outside();
        if (tripped != null) throw new ForgeLimit(tripped);
        if (++depth > Limits.MAX_DEPTH) recount();
        count();
    }

    /** Before each return of the module's methods: one frame less. */
    public void exit() {
        if (depth > 0) depth--;
    }

    /** Before each backward jump of the module's code, and at the entry of each of its exception handlers. */
    public void tick() {
        if (revoked) throw abandoned();
        if (!armed) throw outside();
        if (tripped != null) throw new ForgeLimit(tripped);
        count();
    }

    private void count() {
        if (++ticks < ForgeLimits.METER_CHECK_EVERY) return;
        ticks = 0;
        if (System.nanoTime() > deadline) trip("time budget");
        if (overAllocated()) trip("allocation budget");
    }

    /** The counter drifts when an exception unwinds the module's frames (no {@code exit} on that path): count the real ones. */
    private void recount() {
        long frames = STACK.walk(stack -> stack.filter(frame -> frame.getDeclaringClass().getClassLoader() == owner).count());
        depth = (int) frames;
        if (depth > Limits.MAX_DEPTH) trip("call depth over " + Limits.MAX_DEPTH);
    }

    private boolean overAllocated() {
        return ALLOCATIONS != null && allocatedNow() > allocationAllowance;
    }

    private long allocatedNow() {
        return ALLOCATIONS.getCurrentThreadAllocatedBytes() - allocationStart;
    }

    private void trip(String why) {
        tripped = why + where();
        throw new ForgeLimit(tripped);
    }

    /** {@code " at TagGame.java:41"}: the module's innermost frame that has a line, or nothing. */
    private String where() {
        return STACK.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass().getClassLoader() == owner && frame.getLineNumber() > 0)
                .findFirst())
            .map(frame -> " at " + (frame.getFileName() != null ? frame.getFileName() : frame.getClassName()) + ":" + frame.getLineNumber())
            .orElse("");
    }

    private static ForgeLimit outside() {
        return new ForgeLimit("module code ran outside a metered call");
    }

    private static ForgeLimit abandoned() {
        return new ForgeLimit("the call was abandoned: it ran past its time budget");
    }

    private static com.sun.management.ThreadMXBean allocations() {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
                return bean;
            }
        } catch (Throwable missing) {
            // No jdk.management (a JRE without it): the clock bounds a call alone.
        }
        return null;
    }
}
