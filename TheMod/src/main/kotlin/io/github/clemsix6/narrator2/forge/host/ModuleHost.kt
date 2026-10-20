package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.rt.Meter
import io.github.clemsix6.narrator2.forge.sandbox.ModuleLoader
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A fault of a module (M5 §3.7): its [kind], the [call] it happened in (`constructor`, `start`, `timer`, `gauge`…), its
 * [cause] written for the forger with its place (`time budget at TagGame.java:41`, `ForgeLimit: a collection over
 * 10000 elements at TagGame.java:72`), the module's [line] (0 when unknown), and whether it [disables] the module.
 * Text only: no exception is ever kept (it would pin the module's classes through its stack).
 */
data class Fault(val kind: Kind, val call: String, val cause: String, val line: Int, val disables: Boolean) {
    enum class Kind(val id: String) {
        /** The meter stopped the call (time, allocation, depth), or the call was abandoned past its budget. */
        LIMIT("limit"),

        /** Anything else the module's code threw, a limit of `Guarded` and a stack overflow included. */
        EXCEPTION("exception"),

        /** Over [ForgeLimits.DEPLOYMENT_MS_PER_SECOND] in a second, [ForgeLimits.SLOW_SECONDS] seconds in a row. */
        SLOWNESS("slowness"),

        /** Retains or allocates too much. */
        MEMORY("memory"),

        /** Too many module threads were lost: the Forge runs no module until the server restarts. */
        STUCK("stuck"),
    }

    /** `limit in timer: time budget at TagGame.java:41`. */
    val text: String get() = "${kind.id} in $call: $cause"
}

/**
 * One loaded version of a module, deployed in the game or run at the bench (M5 §3.5, §3.7; barrier 5 of §3.2): its
 * filtering loader ([ModuleLoader]), its [Meter], its instance, its calls, its faults, its memory, its unloading.
 *
 * - **Loading** (§3.5): the meter is made and set in the holder (`Top$-meter`, no static initializer) before any code
 *   of the module, then its class is loaded **without** being initialized: its static initializer runs under the meter,
 *   in the constructor's call.
 * - **Calls** ([call]): each one on the module thread of [threads], the caller waiting at most its budget
 *   ([ForgeLimits.CALL_NANOS]; [STARTUP_CALL_NANOS] for the constructor and `start`; plus [ABANDON_MARGIN_NANOS] for
 *   the meter to unwind): armed, run, disarmed and read (`tripped`, allocation) **on the module thread**; abandoned past it. The
 *   module sees its context through an [SdkBridge]: every SDK call runs on the caller's thread.
 * - **Faults** (§3.7), counted over ten sliding minutes: a *limit* (2 disable; an abandoned call disables at once), an
 *   *exception* (3 disable), *slowness* ([second]), *memory* ([second], [gauge]); any fault in the constructor or
 *   `start` disables at once. Once disabled, no code of the module ever runs again. [onFault] hears each fault, on the
 *   caller's thread. The text of an exception is all that is kept: class, message (cut), the module's line.
 * - **Unloading** ([unload]): everything let go, the module thread its code ran on ended, a [WeakReference] to the
 *   loader returned for the [LeakWatch].
 *
 * The caller's thread only (the server thread, or the bench's worker); [label] names it in the log (`C-70
 * orbiting_chickens`). [lineWaitMillis]: how long the caller may wait, after an abandon, for the module's line (0 for
 * the server thread, which never waits; the bench may wait for it, the forger needs it).
 *
 * [timeScale], [abandonMarginNanos] and [gaugeNanos] are for tests; the game keeps their defaults. A busy test machine
 * holds any thread back for tens of milliseconds, sometimes more than a hundred (FLAKY-4): a test whose subject is not
 * time multiplies the host's own wall-clock limits by [timeScale] — a call's budget, `start`'s, the margin, the
 * gauge's clock —, so that only the limit it tests can stop its module. A budget given to [call] is kept as it is (a
 * test of time gives the game's). A test of the meter's own stop gives the margin alone more, so that a call its
 * meter stops is never abandoned instead.
 */
class ModuleHost(
    mainClass: String,
    classes: Map<String, ByteArray>,
    private val threads: ModuleThreads,
    val label: String,
    private val clock: Clock = Clock.systemUTC(),
    private val lineWaitMillis: Long = 0,
    private val timeScale: Long = 1,
    private val abandonMarginNanos: Long = ABANDON_MARGIN_NANOS * timeScale,
    private val gaugeNanos: Long = ForgeLimits.GAUGE_MAX_NANOS * timeScale,
    private val onFault: (Fault) -> Unit = {},
) {
    private val log = Log.forge
    private var loader: ModuleLoader?
    private var meter: Meter?
    private var type: Class<*>?
    private var gauge: RetainedGauge?
    private var instance: ForgeModule? = null
    private var context: ModuleContext? = null
    private val bridge = SdkBridge()
    private var unloaded = false

    private val limits = ArrayDeque<Long>()
    private val exceptions = ArrayDeque<Long>()
    private var spentThisSecond = 0L
    private var slowSeconds = 0
    private var minuteStart = 0L
    private var allocatedThisMinute = 0L
    private var slowMeasures = 0

    /** The fault that disabled it; null while it may run. */
    var disabled: Fault? = null
        private set

    /** Faults so far (`ModuleView.faults`). */
    var faults = 0
        private set

    /** The milliseconds its calls took in the last whole second (`ModuleView.msPerSecond`). */
    var msPerSecond = 0L
        private set

    /** The last measure of [gauge]; null before the first. */
    var retained: RetainedGauge.Measure? = null
        private set

    init {
        preload()
        val loader = ModuleLoader(mainClass, classes)
        val meter = Meter(loader)
        Class.forName(mainClass + Built.METER_SUFFIX, true, loader).getField(Built.METER_FIELD).set(null, meter)
        this.loader = loader
        this.meter = meter
        type = Class.forName(mainClass, false, loader)
        gauge = RetainedGauge(loader, maxNanos = gaugeNanos)
    }

    /** What one call did: its wall time on the caller's side, the bytes it allocated (-1 unmeasured), its fault. */
    class Called(val nanos: Long, val allocated: Long, val fault: Fault?)

    /**
     * Makes the module (its constructor, its static initializer) and starts it with [context] (which it sees behind
     * the [SdkBridge]), each in a call of its own: a fault in either disables it at once — it started when [disabled]
     * is still null after. Returns the report of the last call made (the constructor's when it failed).
     */
    fun start(context: ModuleContext): Called? {
        val type = type ?: return null
        val seen = bridge.wrap(ModuleContext::class.java, context)
        this.context = seen
        var made: ForgeModule? = null
        val constructed = call(CONSTRUCTOR, STARTUP_CALL_NANOS, STARTUP_CALL_NANOS * timeScale, startup = true) {
            made = try {
                type.getConstructor().newInstance() as ForgeModule
            } catch (wrapped: InvocationTargetException) {
                throw wrapped.cause ?: wrapped
            }
        }
        val module = made?.takeIf { disabled == null } ?: return constructed
        instance = module
        return call(START, STARTUP_CALL_NANOS, STARTUP_CALL_NANOS * timeScale, startup = true) { module.start(seen) }
    }

    /** The module's `stop` (an end that is not a disabling), in a call of its own. */
    fun stop(why: StopReason): Called? {
        val module = instance ?: return null
        val seen = context ?: return null
        return call(STOP) { module.stop(seen, why) }
    }

    /**
     * Runs [body] — code of the module: a fact's handler, a timer, a callback — as one call ([what]: `fact`, `timer`,
     * `callback`…), on the module thread, metered, abandoned past its budget. Never throws: a fault is counted here,
     * told to [onFault], and may disable the module. Nothing runs once it is disabled or unloaded (null then).
     */
    fun call(what: String, body: () -> Unit): Called? = call(what, ForgeLimits.CALL_NANOS, ForgeLimits.CALL_NANOS * timeScale, startup = false, body)

    /**
     * [call] with a budget of its own (the bench: a scenario has what is left of the bench's 30 s), and an allocation
     * allowance in proportion ([ForgeLimits.CALL_ALLOCATION_BYTES] a call's budget, [ForgeLimits.ALLOCATION_PER_MINUTE_BYTES]
     * at most).
     *
     * **Nested**: made from the module thread itself, inside one of this module's calls (the bench: a scenario's
     * `b.run(…)` runs the module's handlers), it runs right there, without a new hand-over, metered on its own for at
     * most what the running call has left; the running call then resumes, its deadline unchanged. On a thread whose
     * call was abandoned, it throws the meter's [ForgeLimit][io.github.clemsix6.narrator2.forge.rt.ForgeLimit] (the
     * thread is lost: it must unwind).
     */
    fun call(what: String, budgetNanos: Long, body: () -> Unit): Called? = call(what, budgetNanos, budgetNanos, startup = false, body)

    /** [budget] of wall clock; [nominal], the game's budget for it, which its allocation allowance follows. */
    private fun call(what: String, nominal: Long, budget: Long, startup: Boolean, body: () -> Unit): Called? {
        if (disabled != null || unloaded) return null
        val meter = meter ?: return null
        val names = loader?.names ?: return null
        val calls = if (startup) 1 else maxOf(1, nominal / ForgeLimits.CALL_NANOS)
        val allowance = (ForgeLimits.CALL_ALLOCATION_BYTES * calls).coerceAtMost(ForgeLimits.ALLOCATION_PER_MINUTE_BYTES)
        val nested = ModuleThreads.onModuleThread()
        val outcome = Outcome()
        var lost: Thread? = null
        val started = System.nanoTime()
        val ran = threads.run(budget + abandonMarginNanos, onAbandon = { thread -> meter.revoke().also { lost = thread } }) {
            val running = if (nested) meter.nest(budget, allowance) else null
            if (running == null) meter.arm(budget, allowance)
            try {
                body()
            } catch (thrown: Throwable) {
                describe(thrown, names, outcome)
            }
            meter.disarm()
            outcome.tripped = meter.tripped()
            outcome.allocated = meter.allocatedSinceArm()
            running?.let(meter::unnest)
        }
        val nanos = System.nanoTime() - started
        if (!nested) spentThisSecond += nanos
        val fault = when (ran) {
            ModuleThreads.Ran.DONE -> done(what, startup, outcome)
            ModuleThreads.Ran.ABANDONED -> abandoned(what, lost, names, budget)
            ModuleThreads.Ran.LATE -> limit(what, startup, "the module thread did not start the call within ${(budget + abandonMarginNanos) / 1_000_000} ms (an overloaded machine)", 0)
            ModuleThreads.Ran.STUCK -> Fault(Fault.Kind.STUCK, what, "the Forge is stuck: too many module threads were lost", 0, disables = true)
        } ?: allocated(what, if (ran == ModuleThreads.Ran.DONE) outcome.allocated else -1)
        fault?.let(::fault)
        return Called(nanos, if (ran == ModuleThreads.Ran.DONE) outcome.allocated else -1, fault)
    }

    /**
     * Once a second, between two calls: its time in the last second (a fault *slowness* after
     * [ForgeLimits.SLOW_SECONDS] seconds in a row over [ForgeLimits.DEPLOYMENT_MS_PER_SECOND]).
     */
    fun second(): Fault? {
        msPerSecond = spentThisSecond / 1_000_000
        spentThisSecond = 0
        if (disabled != null || unloaded) return null
        slowSeconds = if (msPerSecond > ForgeLimits.DEPLOYMENT_MS_PER_SECOND) slowSeconds + 1 else 0
        if (slowSeconds < ForgeLimits.SLOW_SECONDS) return null
        return Fault(
            Fault.Kind.SLOWNESS, SECOND,
            "over ${ForgeLimits.DEPLOYMENT_MS_PER_SECOND} ms of its code a second, $slowSeconds seconds in a row (${msPerSecond} ms in the last)", 0, disables = true,
        ).also(::fault)
    }

    /**
     * Measures what it retains (M5 §3.7), once a minute (every 10 seconds without allocation metering), between two
     * calls: its instance, its classes' static fields and [handed] (the lambdas it gave the SDK), in a call of its own.
     * Past [ForgeLimits.RETAINED_BYTES], or past [ForgeLimits.GAUGE_MAX_OBJECTS] objects, a fault *memory* disables it.
     * A measure stopped by the clock ([ForgeLimits.GAUGE_MAX_NANOS]) is noted and taken again next time: only
     * [SLOW_MEASURES] of them in a row disable it (a first walk with a cold JIT costs about 1 µs an object, a warm one
     * 40 to 80 ns).
     */
    fun gauge(handed: Collection<Any?> = emptyList()): Fault? {
        val found = measure(listOf(instance) + handed) ?: return null
        retained = found
        slowMeasures = if (found.complete || found.objects > ForgeLimits.GAUGE_MAX_OBJECTS) 0 else slowMeasures + 1
        val cause = when {
            found.objects > ForgeLimits.GAUGE_MAX_OBJECTS -> "retains too much to measure (over ${ForgeLimits.GAUGE_MAX_OBJECTS} objects)"
            slowMeasures >= SLOW_MEASURES ->
                "retains too much to measure (${found.objects} objects seen in ${gaugeNanos / 1_000_000} ms, $slowMeasures measures in a row)"
            !found.complete -> {
                log.info("$label: its memory was not measured within ${gaugeNanos / 1_000_000} ms (${found.objects} objects seen); again next time")
                return null
            }
            found.bytes > ForgeLimits.RETAINED_BYTES -> "retains about ${found.bytes / MIB} MiB (at most ${ForgeLimits.RETAINED_BYTES / MIB})"
            else -> return null
        }
        return Fault(Fault.Kind.MEMORY, GAUGE, cause, 0, disables = true).also(::fault)
    }

    /**
     * What [roots] and the static fields of the module's classes retain ([RetainedGauge]), measured in a call of its
     * own (a nested one from the module thread); null when it could not run (disabled, unloaded, or the call faulted:
     * the fault is counted as any other). The bench's own memory checks use it; [gauge] applies the game's limits.
     */
    fun measure(roots: Collection<Any?>): RetainedGauge.Measure? {
        val gauge = gauge ?: return null
        val loader = loader ?: return null
        var measure: RetainedGauge.Measure? = null
        val called = call(GAUGE) { measure = gauge.measure(roots, loader.defined) } ?: return null
        return measure.takeIf { called.fault == null }
    }

    /** The module's class, loaded and not initialized (its static initializer runs in its constructor's call). */
    val main: Class<*> get() = type ?: throw IllegalStateException("$label was unloaded")

    /** The line of the module's first frame in [error]'s stack trace (`TagGame.java:42` → 42); 0 when none is the module's. */
    fun line(error: Throwable): Int {
        val names = loader?.names ?: return 0
        return error.stackTrace.firstOrNull { it.className in names && it.lineNumber > 0 }?.lineNumber ?: 0
    }

    /**
     * Lets everything go — instance, context, meter, classes, loader — and returns a weak reference to the loader,
     * for the [LeakWatch]. No code of the module runs after this.
     *
     * The module thread goes too ([ModuleThreads.close]; a new one takes the next call of any module): once the code
     * of a call is compiled, the JVM keeps on its thread the last exception thrown there — the module's own, or its
     * meter's —, whose stack pins the module's classes, until another exception is thrown on that thread or it ends
     * (FLAKY-4: measured, the last of twenty modules unloaded stayed alive on a warm JVM, never on an interpreted one).
     */
    fun unload(): WeakReference<ClassLoader> {
        val reference = WeakReference<ClassLoader>(loader)
        unloaded = true
        instance = null
        context = null
        type = null
        meter = null
        gauge = null
        loader = null
        bridge.clear()
        threads.close()
        return reference
    }

    private fun done(what: String, startup: Boolean, outcome: Outcome): Fault? {
        outcome.tripped?.let { return limit(what, startup, it, lineOf(it)) }
        val thrown = outcome.thrown ?: return null
        val now = clock.millis()
        exceptions.addLast(now)
        val counted = recent(exceptions, now)
        return Fault(Fault.Kind.EXCEPTION, what, thrown, outcome.line, disables = startup || counted >= ForgeLimits.EXCEPTION_FAULTS)
    }

    private fun limit(what: String, startup: Boolean, cause: String, line: Int): Fault {
        val now = clock.millis()
        limits.addLast(now)
        val counted = recent(limits, now)
        return Fault(Fault.Kind.LIMIT, what, cause, line, disables = startup || counted >= ForgeLimits.LIMIT_FAULTS)
    }

    /** The bytes allocated in the minute (a fault *memory* past [ForgeLimits.ALLOCATION_PER_MINUTE_BYTES]). */
    private fun allocated(what: String, bytes: Long): Fault? {
        if (bytes < 0) return null
        val now = clock.millis()
        if (now - minuteStart >= MINUTE_MS) {
            minuteStart = now
            allocatedThisMinute = 0
        }
        allocatedThisMinute += bytes
        if (allocatedThisMinute <= ForgeLimits.ALLOCATION_PER_MINUTE_BYTES) return null
        return Fault(
            Fault.Kind.MEMORY, what,
            "allocates too much: ${allocatedThisMinute / MIB} MiB in a minute (at most ${ForgeLimits.ALLOCATION_PER_MINUTE_BYTES / MIB})", 0, disables = true,
        )
    }

    private fun recent(times: ArrayDeque<Long>, now: Long): Int {
        while (times.isNotEmpty() && now - times.first() >= ForgeLimits.FAULT_WINDOW_MS) times.removeFirst()
        return times.size
    }

    private fun fault(fault: Fault) {
        faults++
        if (fault.disables && disabled == null) {
            disabled = fault
            log.warn("$label disabled: ${fault.text}")
        } else {
            log.info("$label: ${fault.text}")
        }
        try {
            onFault(fault)
        } catch (e: Exception) {
            log.error("$label: noting a fault failed", e)
        }
    }

    /** What a call left, written on the module thread before its end, read by the caller after it (never after an abandon). */
    private class Outcome {
        var thrown: String? = null
        var line = 0
        var tripped: String? = null
        var allocated = -1L
    }

    /**
     * [thrown] as text, on the module thread, while the call is still armed (a module's exception may compute its own
     * message: its code runs under the meter); then the throwable is let go.
     */
    private fun describe(thrown: Throwable, names: Set<String>, outcome: Outcome) {
        val cause = if (thrown is ExceptionInInitializerError) thrown.cause ?: thrown else thrown
        val message = try {
            cause.message
        } catch (_: Throwable) {
            null
        }
        val frame = try {
            cause.stackTrace.firstOrNull { it.className in names && it.lineNumber > 0 }
        } catch (_: Throwable) {
            null
        }
        val type = cause.javaClass.name.substringAfterLast('.')
        val text = if (message.isNullOrBlank()) type else "$type: ${ModelText.cut(message, MESSAGE_CHARS)}"
        val place = frame?.let { " at ${it.fileName ?: it.className}:${it.lineNumber}" }.orEmpty()
        outcome.thrown = if (place.isNotEmpty() && text.endsWith(place)) text else text + place
        outcome.line = frame?.lineNumber ?: 0
    }

    /**
     * The fault of a call abandoned on [thread]. Where the module was is read from the thread's stack — but that waits
     * for the thread's next safepoint poll, and inside one long call of the JDK none may come for tens of milliseconds
     * (measured: 50 to 65 ms in the hash of finding 1, a whole `indexOf` of 75 ms). So it is read on a helper thread and
     * waited at most [lineWaitMillis] (0 on the server thread: it never waits; the place goes to the log when read).
     */
    private fun abandoned(what: String, thread: Thread?, names: Set<String>, budget: Long): Fault {
        val cause = "time budget: one call ran past ${(budget + abandonMarginNanos) / 1_000_000} ms and was abandoned"
        val stack = CompletableFuture<Array<StackTraceElement>>()
        if (thread != null) Thread.ofPlatform().daemon().name("narrator2-module-stack").start { stack.complete(thread.stackTrace) }
        val frames = try {
            stack.get(lineWaitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        }
        if (frames == null) {
            stack.thenAccept { late -> log.warn("$label: its call abandoned past its budget ran${place(late, names).first.ifEmpty { " out of its code" }}") }
            return Fault(Fault.Kind.LIMIT, what, cause, 0, disables = true)
        }
        val (place, line) = place(frames, names)
        return Fault(Fault.Kind.LIMIT, what, cause + place, line, disables = true)
    }

    /** ` in java.util.AbstractList.hashCode at Shared.java:7`: the JDK call the module's innermost frame was in, and its line. */
    private fun place(stack: Array<StackTraceElement>, names: Set<String>): Pair<String, Int> {
        val at = stack.indexOfFirst { it.className in names }
        if (at < 0) return "" to 0
        val jdk = stack.getOrNull(at - 1)?.takeIf { !it.className.startsWith(HOST_PACKAGE) }
        val module = stack[at].takeIf { it.lineNumber > 0 }
        val inJdk = jdk?.let { " in ${it.className}.${it.methodName}" }.orEmpty()
        val atLine = module?.let { " at ${it.fileName ?: it.className}:${it.lineNumber}" }.orEmpty()
        return inJdk + atLine to (module?.lineNumber ?: 0)
    }

    private fun lineOf(tripped: String): Int = tripped.substringAfterLast(':', "").toIntOrNull() ?: 0

    companion object {
        /** The calls' names in faults and logs. */
        const val CONSTRUCTOR = "constructor"
        const val START = "start"
        const val STOP = "stop"
        const val GAUGE = "gauge"
        const val SECOND = "second"

        /**
         * The startup calls of a deployment (its constructor, its `start`): its classes are loaded, verified and
         * initialized, and most of its dynamic call sites linked, inside them (finding 4 of M5-04). Measured with the
         * witness module: 1 to 2 ms warm, but 28 ms for the first module of a cold JVM (19 ms in `start` alone).
         */
        const val STARTUP_CALL_NANOS = 50L * 1_000_000

        /** Past its budget, the meter stops a call at its next check and the call unwinds: the caller waits this much more before it abandons it. */
        const val ABANDON_MARGIN_NANOS = 5L * 1_000_000

        /** Measures of the gauge stopped by its clock, in a row, that disable a module. */
        const val SLOW_MEASURES = 3

        /**
         * Loads and initializes, once, what the first call of the first module would otherwise load inside its
         * metered time (M5-08's measure: 31 ms, a false fault): the runtime the rewritten code calls (`forge.rt`), the
         * SDK's types and the proxy class of each of its interfaces ([SdkBridge]), and the game's side of the SDK
         * (`Facts` and the bus's classes, the acts, the creatures, `SpawnPolicy`). Any thread; the first [ModuleHost]
         * does it, and the Forge may do it at startup, off the server thread. Best effort: what cannot load here
         * (a unit test without the game) is left to its first use.
         */
        fun preload() {
            preloaded
        }

        private val preloaded: Unit by lazy {
            val started = System.nanoTime()
            val mod = ModuleHost::class.java.classLoader
            val failed = PRELOADED.filter { name -> runCatching { Class.forName(name, true, mod) }.isFailure }
            SdkBridge.interfaces().forEach { type -> java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } }
            Log.forge.debug { "the modules' runtime and SDK loaded in ${(System.nanoTime() - started) / 1_000_000} ms" + if (failed.isEmpty()) "" else " (not here: $failed)" }
        }

        /** Classes (binary names) that the module's first calls would load: the runtime, the SDK's values, its game side. */
        private val PRELOADED = listOf(
            "io.github.clemsix6.narrator2.forge.rt.Meter", "io.github.clemsix6.narrator2.forge.rt.Guarded",
            "io.github.clemsix6.narrator2.forge.rt.Texts", "io.github.clemsix6.narrator2.forge.rt.Views",
            "io.github.clemsix6.narrator2.forge.rt.ForgeLimit", "io.github.clemsix6.narrator2.forge.api.Place",
            "io.github.clemsix6.narrator2.forge.api.Cell", "io.github.clemsix6.narrator2.forge.api.Outcome",
            "io.github.clemsix6.narrator2.forge.api.Text", "io.github.clemsix6.narrator2.forge.api.ForgeRefusal",
            "io.github.clemsix6.narrator2.forge.sdk.Facts", "io.github.clemsix6.narrator2.forge.sdk.ActQueue",
            "io.github.clemsix6.narrator2.forge.sdk.ModuleActs", "io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures",
            "io.github.clemsix6.narrator2.forge.sdk.CreatureControl", "io.github.clemsix6.narrator2.forge.sdk.WorldView",
            "io.github.clemsix6.narrator2.forge.sdk.GroundView", "io.github.clemsix6.narrator2.forge.sdk.FactQueue",
            "io.github.clemsix6.narrator2.forge.sdk.Scheduler", "io.github.clemsix6.narrator2.forge.sdk.StorageMap",
            "io.github.clemsix6.narrator2.forge.sdk.ArgsView", "io.github.clemsix6.narrator2.guard.SpawnPolicy",
        )

        private const val MESSAGE_CHARS = 200
        private const val MINUTE_MS = 60_000L
        private const val MIB = 1024L * 1024
        private const val HOST_PACKAGE = "io.github.clemsix6.narrator2.forge.host."
    }
}
