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
 *       JDK loop calls has no backward jump of its own), it compares the call's time to its budget and, when the JVM
 *       measures it, the bytes the thread allocated since {@link #arm} to the call's allowance; past either, it trips.
 *       <b>The call's time is the module's own work, not the wall clock</b> (M5-LOAD): the time between two readings of
 *       the clock up to {@link #RUN_GAP_NANOS} ({@link #worked()}), a gap longer than that being the machine holding
 *       the thread back (another thread on its core, a pause of the GC) or one long step of the JDK; past its budget of
 *       wall clock, the thread's CPU clock tells the second ({@link #cpuEvidence}). A thread held back by the machine is
 *       never past its budget for that; a loop of the module is stopped once it ran its budget, at 20 ms of wall clock
 *       on an idle machine as before.</li>
 *   <li>Past {@code Limits.MAX_DEPTH} frames counted by {@code enter} and {@code exit}, it counts the module's real
 *       frames ({@code StackWalker}: an exception unwinding the module's frames skips their {@code exit}) before it
 *       trips.</li>
 *   <li>Tripping is sticky: it throws a {@link ForgeLimit}, then every later {@code enter} or {@code tick} of the
 *       same call throws again (a loop in a {@code finally} cannot outlive it), and {@link #tripped()} keeps the cause
 *       even if the module swallowed the error.</li>
 *   <li>{@link #disarm()} checks the time and the allocation one last time: a call whose thread's CPU clock shows it
 *       overran its budget inside a single call of the JDK, where no tick could stop it, is tripped all the same
 *       (without a throw).</li>
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

    /** The JVM's CPU clock of a thread; null without one (the wall clock alone then tells a call's time, as before M5-LOAD). */
    private static final java.lang.management.ThreadMXBean CPU = cpuClock();

    /**
     * Up to this, the time between two readings of the clock in a call is the module's own running time. A longer gap
     * is the machine holding the thread back, or one long step of the JDK (a sort, an {@code indexOf}), which only the
     * thread's CPU clock tells apart: it advances by steps of 15.6 ms under Windows, so it judges long steps only.
     */
    public static final long RUN_GAP_NANOS = 1_000_000L;

    /** What {@link #cpuAtArm()} answers when no call is armed. */
    public static final long UNARMED = Long.MIN_VALUE;

    /**
     * The least CPU time that shows a call computed past its budget in a long step of the JDK: twice a call's budget
     * (40 ms), because the thread's CPU clock may read one or two of its steps (15.6 ms under Windows) more than a short
     * call ran; never that much for a call of a few milliseconds.
     */
    public static final long MIN_CPU_EVIDENCE_NANOS = 2 * ForgeLimits.CALL_NANOS;

    /** The CPU time that shows a call of {@code budgetNanos} computed past it: its budget, {@link #MIN_CPU_EVIDENCE_NANOS} at least. */
    public static long cpuEvidence(long budgetNanos) {
        return Math.max(budgetNanos, MIN_CPU_EVIDENCE_NANOS);
    }

    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * The meter of the call running on this thread (set between {@link #arm} and {@link #disarm}), so that a long walk
     * inside the runtime ({@link Guarded}'s deep hash and equality) can check the budget and stop at it — the meter the
     * rewritten bytecode cannot reach from a static helper. A module thread runs one call at a time, nesting restores it.
     */
    private static final ThreadLocal<Meter> CURRENT = new ThreadLocal<>();

    private final ClassLoader owner;
    private boolean armed;
    private long budget;
    private long deadline;
    private long lastClock;
    private long worked;
    private long cpuCheckedAt;
    private long workedAtDisarm = -1;
    private long cpuAtDisarm = -1;

    /** The thread's CPU clock at {@link #arm}: read by the host's thread while the call runs (has its thread computed?). */
    private volatile long cpuAtArm = UNARMED;

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

    /** Starts a call of the module: {@code budgetNanos} of its own work and {@code allocationBytes} allocated at most; clears {@link #tripped()}. */
    public void arm(long budgetNanos, long allocationBytes) {
        long now = System.nanoTime();
        budget = budgetNanos;
        deadline = now + budgetNanos;
        lastClock = now;
        cpuCheckedAt = now;
        worked = 0;
        workedAtDisarm = -1;
        cpuAtDisarm = -1;
        cpuAtArm = cpuNow();
        allocationAllowance = allocationBytes;
        allocationStart = ALLOCATIONS == null ? 0 : ALLOCATIONS.getCurrentThreadAllocatedBytes();
        allocatedAtDisarm = -1;
        ticks = 0;
        depth = 0;
        tripped = null;
        armed = true;
        CURRENT.set(this);
    }

    /**
     * Ends the call: from now on, no module code may run until the next {@link #arm}. A call that overran its time or
     * its allocation without a tick to notice it (one long call of the JDK) is tripped here; read {@link #tripped()},
     * {@link #worked()} and {@link #cpu()} after this.
     */
    public void disarm() {
        if (!armed) return;
        long now = clock();
        long cpu = cpuAtArm < 0 ? -1 : cpuNow() - cpuAtArm;
        if (tripped == null) {
            if (worked >= budget || (now - deadline >= 0 && (cpu < 0 || cpu >= cpuEvidence(budget)))) tripped = "time budget";
            else if (overAllocated()) tripped = "allocation budget";
        }
        allocatedAtDisarm = ALLOCATIONS == null ? -1 : allocatedNow();
        workedAtDisarm = worked;
        cpuAtDisarm = cpu;
        cpuAtArm = UNARMED;
        armed = false;
        CURRENT.remove();
    }

    /**
     * The module's own running time in the last call, once disarmed: the time between two readings of the clock, each
     * up to {@link #RUN_GAP_NANOS} (-1 before). Exact for the module's own code, a lower bound for long steps of the JDK.
     */
    public long worked() {
        return workedAtDisarm;
    }

    /**
     * The thread's CPU time in the last call, once disarmed; -1 before, or without a CPU clock. Under Windows it moves by
     * steps of 15.6 ms (a call of 1 ms reads 0, or 15.6 ms once in fifteen): true on average, coarse for one call.
     */
    public long cpu() {
        return cpuAtDisarm;
    }

    /**
     * The thread's CPU clock when the running call was armed, for another thread to tell whether it computes
     * ({@code ThreadMXBean.getThreadCpuTime}): {@link #UNARMED} between calls, -1 without a CPU clock. Any thread.
     */
    public long cpuAtArm() {
        return cpuAtArm;
    }

    /**
     * Checks the clock and the allocation now — not batched like {@link #tick} — and trips past either; for a long walk
     * inside the runtime ({@link Guarded}) that calls {@link #sample} between its steps, so it stops at the budget even
     * cold, before the host would abandon the call. Throws into the abandoned thread once {@link #revoke}d.
     */
    private void checkpoint() {
        if (revoked) throw abandoned();
        if (!armed) return;
        if (tripped != null) throw new ForgeLimit(tripped);
        // The time bound only: a deep walk allocates little (a work stack of references), and reading the thread's
        // allocated bytes is a JMX call too dear to make between every slice of the walk.
        if (overTime()) trip("time budget");
    }

    /** The current thread's call checks its budget (called by {@link Guarded} between the steps of a deep walk); a no-op outside a metered call. */
    public static void sample() {
        Meter current = CURRENT.get();
        if (current != null) current.checkpoint();
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
     * module's handlers, M5-05): armed for {@code budgetNanos}, but never past what the running call has left of its
     * own work, with {@code allocationBytes} of its own. Returns the running call's state, for {@link #unnest}.
     */
    public Nested nest(long budgetNanos, long allocationBytes) {
        Nested running = new Nested(
            armed, budget, deadline, lastClock, worked, cpuCheckedAt, cpuAtArm, workedAtDisarm, cpuAtDisarm,
            allocationStart, allocationAllowance, allocatedAtDisarm, ticks, depth, tripped);
        long left = armed ? Math.max(0, budget - worked) : budgetNanos;
        arm(Math.min(budgetNanos, left), allocationBytes);
        return running;
    }

    /**
     * Resumes the call that was running ({@code running}, from {@link #nest}) once the nested one is disarmed: its
     * deadline unchanged, the nested call's work counted as its own, what the nested call allocated not counted twice.
     */
    public void unnest(Nested running) {
        long nested = Math.max(0, allocatedAtDisarm);
        long nestedWork = Math.max(0, workedAtDisarm);
        armed = running.armed;
        budget = running.budget;
        deadline = running.deadline;
        lastClock = System.nanoTime();
        worked = running.worked + nestedWork;
        cpuCheckedAt = running.cpuCheckedAt;
        cpuAtArm = running.cpuAtArm;
        workedAtDisarm = running.workedAtDisarm;
        cpuAtDisarm = running.cpuAtDisarm;
        allocationStart = running.allocationStart + nested;
        allocationAllowance = running.allocationAllowance;
        allocatedAtDisarm = running.allocatedAtDisarm;
        ticks = running.ticks;
        depth = running.depth;
        tripped = running.tripped;
        CURRENT.set(this);
    }

    /** The state of a call while another one runs nested in it. */
    public record Nested(boolean armed, long budget, long deadline, long lastClock, long worked, long cpuCheckedAt, long cpuAtArm,
                         long workedAtDisarm, long cpuAtDisarm, long allocationStart, long allocationAllowance, long allocatedAtDisarm,
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
        if (overTime()) trip("time budget");
        if (overAllocated()) trip("allocation budget");
    }

    /** Reads the clock: the time since the last reading is the module's own work when short enough ({@link #RUN_GAP_NANOS}). */
    private long clock() {
        long now = System.nanoTime();
        long gap = now - lastClock;
        if (gap <= RUN_GAP_NANOS) worked += gap;
        lastClock = now;
        return now;
    }

    /**
     * Whether the call ran its budget: its module's own work did; or, past its budget of wall clock, its thread's CPU
     * clock says it computed that ({@link #cpuEvidence}: a long step of the JDK between two readings), read once a
     * millisecond at most (a system call). Without a CPU clock, past its budget of wall clock, as before.
     */
    private boolean overTime() {
        long now = clock();
        if (worked >= budget) return true;
        if (now - deadline < 0) return false;
        if (cpuAtArm < 0) return true;
        if (now - cpuCheckedAt < RUN_GAP_NANOS) return false;
        cpuCheckedAt = now;
        return cpuNow() - cpuAtArm >= cpuEvidence(budget);
    }

    /** The current thread's CPU clock; -1 without one. */
    private static long cpuNow() {
        return CPU == null ? -1 : CPU.getCurrentThreadCpuTime();
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

    private static java.lang.management.ThreadMXBean cpuClock() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()) return bean;
        } catch (Throwable missing) {
            // No java.management: the wall clock alone.
        }
        return null;
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
