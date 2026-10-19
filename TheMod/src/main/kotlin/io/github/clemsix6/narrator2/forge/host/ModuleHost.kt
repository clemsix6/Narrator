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
 * - **Unloading** ([unload]): everything let go, a [WeakReference] to the loader returned for the [LeakWatch].
 *
 * The caller's thread only (the server thread, or the bench's worker); [label] names it in the log (`C-70
 * orbiting_chickens`). [lineWaitMillis]: how long the caller may wait, after an abandon, for the module's line (0 for
 * the server thread, which never waits; the bench may wait for it, the forger needs it).
 */
class ModuleHost(
    mainClass: String,
    classes: Map<String, ByteArray>,
    private val threads: ModuleThreads,
    val label: String,
    private val clock: Clock = Clock.systemUTC(),
    private val lineWaitMillis: Long = 0,
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
        val loader = ModuleLoader(mainClass, classes)
        val meter = Meter(loader)
        Class.forName(mainClass + Built.METER_SUFFIX, true, loader).getField(Built.METER_FIELD).set(null, meter)
        this.loader = loader
        this.meter = meter
        type = Class.forName(mainClass, false, loader)
        gauge = RetainedGauge(loader)
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
        val constructed = call(CONSTRUCTOR, startup = true) {
            made = try {
                type.getConstructor().newInstance() as ForgeModule
            } catch (wrapped: InvocationTargetException) {
                throw wrapped.cause ?: wrapped
            }
        }
        val module = made?.takeIf { disabled == null } ?: return constructed
        instance = module
        return call(START, startup = true) { module.start(seen) }
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
    fun call(what: String, body: () -> Unit): Called? = call(what, startup = false, body)

    private fun call(what: String, startup: Boolean, body: () -> Unit): Called? {
        if (disabled != null || unloaded) return null
        val meter = meter ?: return null
        val names = loader?.names ?: return null
        val budget = if (startup) STARTUP_CALL_NANOS else ForgeLimits.CALL_NANOS
        val outcome = Outcome()
        var lost: Thread? = null
        val started = System.nanoTime()
        val ran = threads.run(budget + ABANDON_MARGIN_NANOS, onAbandon = { thread -> meter.revoke().also { lost = thread } }) {
            meter.arm(budget, ForgeLimits.CALL_ALLOCATION_BYTES)
            try {
                body()
            } catch (thrown: Throwable) {
                describe(thrown, names, outcome)
            }
            meter.disarm()
            outcome.tripped = meter.tripped()
            outcome.allocated = meter.allocatedSinceArm()
        }
        val nanos = System.nanoTime() - started
        spentThisSecond += nanos
        val fault = when (ran) {
            ModuleThreads.Ran.DONE -> done(what, startup, outcome)
            ModuleThreads.Ran.ABANDONED -> abandoned(what, lost, names, budget)
            ModuleThreads.Ran.LATE -> limit(what, startup, "the module thread did not start the call within ${(budget + ABANDON_MARGIN_NANOS) / 1_000_000} ms (an overloaded machine)", 0)
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
        val gauge = gauge ?: return null
        val loader = loader ?: return null
        var measure: RetainedGauge.Measure? = null
        val called = call(GAUGE) { measure = gauge.measure(listOf(instance) + handed, loader.defined) } ?: return null
        if (called.fault != null) return called.fault
        val found = measure ?: return null
        retained = found
        slowMeasures = if (found.complete || found.objects > ForgeLimits.GAUGE_MAX_OBJECTS) 0 else slowMeasures + 1
        val cause = when {
            found.objects > ForgeLimits.GAUGE_MAX_OBJECTS -> "retains too much to measure (over ${ForgeLimits.GAUGE_MAX_OBJECTS} objects)"
            slowMeasures >= SLOW_MEASURES ->
                "retains too much to measure (${found.objects} objects seen in ${ForgeLimits.GAUGE_MAX_NANOS / 1_000_000} ms, $slowMeasures measures in a row)"
            !found.complete -> {
                log.info("$label: its memory was not measured within ${ForgeLimits.GAUGE_MAX_NANOS / 1_000_000} ms (${found.objects} objects seen); again next time")
                return null
            }
            found.bytes > ForgeLimits.RETAINED_BYTES -> "retains about ${found.bytes / MIB} MiB (at most ${ForgeLimits.RETAINED_BYTES / MIB})"
            else -> return null
        }
        return Fault(Fault.Kind.MEMORY, GAUGE, cause, 0, disables = true).also(::fault)
    }

    /**
     * Lets everything go — instance, context, meter, classes, loader — and returns a weak reference to the loader,
     * for the [LeakWatch]. No code of the module runs after this.
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
        val cause = "time budget: one call ran past ${(budget + ABANDON_MARGIN_NANOS) / 1_000_000} ms and was abandoned"
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

        private const val MESSAGE_CHARS = 200
        private const val MINUTE_MS = 60_000L
        private const val MIB = 1024L * 1024
        private const val HOST_PACKAGE = "io.github.clemsix6.narrator2.forge.host."
    }
}
