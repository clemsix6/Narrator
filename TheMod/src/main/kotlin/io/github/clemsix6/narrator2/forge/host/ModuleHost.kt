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
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A fault of a module (M5 §3.7): its [kind], the [call] it happened in (`constructor`, `start`, `timer`, `gauge`…), its
 * [cause] written for the forger with its place (`time budget at TagGame.java:41`, `ForgeLimit: a collection over
 * 10000 elements at TagGame.java:72`), the module's [line] (0 when unknown), and whether it [disables] the module.
 * Text only: no exception is ever kept (it would pin the module's classes through its stack).
 */
data class Fault(val kind: Kind, val call: String, val cause: String, val line: Int, val disables: Boolean) {
    enum class Kind(val id: String) {
        /** The meter stopped the call (its own work past its budget, allocation, depth), or the call was given up while its thread computed past its budget. */
        LIMIT("limit"),

        /** Anything else the module's code threw, a limit of `Guarded` and a stack overflow included. */
        EXCEPTION("exception"),

        /** Over [ForgeLimits.DEPLOYMENT_MS_PER_SECOND] of its own work in a second, [ForgeLimits.SLOW_SECONDS] seconds in a row. */
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
 *   the meter to unwind): armed, run, disarmed and read (`tripped`, allocation, its own work) **on the module thread**.
 *   The module sees its context through an [SdkBridge]: every SDK call runs on the caller's thread.
 * - **Only the module's doing is a fault** (M5-LOAD): its meter counts its own work, not the wall clock ([Meter]); a
 *   call **given up** at the caller's deadline ([busy]) is **held** ([ModuleThreads.Held]), the module waiting for it:
 *   it runs on, and its SDK requests wait for the caller to **resume** it ([busy], FORGE-ABANDON: a call is never half
 *   done, its effects all applied or, cut, none after). It is settled once it ended — a fault of its meter, or
 *   nobody's — or once its thread's CPU time shows it computed past its budget ([computing]: then it is **abandoned**,
 *   cut, lost, and disables at once). A call the module thread did not even start in time is **skipped**, nobody's
 *   fault ([Called.skipped]: its caller may ask it again). Both are logged and counted apart ([starved], [skipped]).
 *   Its constructor and `start`, and every call of a host with [patienceNanos] (the bench), wait past their budget as
 *   long as the module thread has not computed ([STARTUP_PATIENCE_NANOS]).
 * - **Faults** (§3.7), counted over ten sliding minutes: a *limit* (2 disable; an abandoned call disables at once), an
 *   *exception* (3 disable), *slowness* ([second]: its own work, [Called.work]), *memory* ([second], [gauge]); any
 *   fault in the constructor or `start` disables at once. Once disabled, no code of the module ever runs again.
 *   [onFault] hears each fault, on the caller's thread. The text of an exception is all that is kept: class, message
 *   (cut), the module's line.
 * - **Unloading** ([unload]): everything let go, the module thread its code ran on ended, a [WeakReference] to the
 *   loader returned for the [LeakWatch].
 *
 * The caller's thread only (the server thread, or the bench's worker); [label] names it in the log (`C-70
 * orbiting_chickens`). [lineWaitMillis]: how long the caller may wait, after an abandon, for the module's line (0 for
 * the server thread, which never waits; the bench may wait for it, the forger needs it).
 *
 * [timeScale] and [abandonMarginNanos] are for tests; the game keeps their defaults. A test whose subject is not time
 * multiplies the host's own limits by [timeScale] — a call's budget, `start`'s, the margin —, so that only the limit
 * it tests can stop its module (FLAKY-4). A budget given to [call] is kept as it is (a test of time gives the game's).
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
    private val patienceNanos: Long = 0,
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
    private var given: Given? = null

    private val limits = ArrayDeque<Long>()
    private val exceptions = ArrayDeque<Long>()
    private var spentThisSecond = 0L
    private var workThisSecond = 0L
    private var slowSeconds = 0
    private var minuteStart = 0L
    private var allocatedThisMinute = 0L

    /** The fault that disabled it; null while it may run. */
    var disabled: Fault? = null
        private set

    /** Why it could not start without a fault of its own (its start given up, its thread held back past its patience); null while it may. */
    var stalled: String? = null
        private set

    /** Faults so far (`ModuleView.faults`). */
    var faults = 0
        private set

    /** Calls given up that its thread had not computed past their budget: nobody's fault. */
    var starved = 0
        private set

    /** Calls that never ran: the module thread did not start them in time, or a call given up still ran. */
    var skipped = 0
        private set

    /** The milliseconds its calls held their caller in the last whole second (`ModuleView.msPerSecond`). */
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
        gauge = RetainedGauge(loader)
    }

    /**
     * What one call did: its wall time on the caller's side, the bytes it allocated (-1 unmeasured), its fault; [work],
     * its own work (its meter's measure of its code, or its thread's CPU time for long steps of the JDK, and the SDK
     * calls its caller served), 0 when unknown; [skipped] when nothing of it ran (asked too late, or a call given up
     * before still runs): its caller may ask it again.
     */
    class Called(val nanos: Long, val allocated: Long, val fault: Fault?, val work: Long = 0, val skipped: Boolean = false)

    /**
     * Makes the module (its constructor, its static initializer) and starts it with [context] (which it sees behind
     * the [SdkBridge]), each in a call of its own: a fault in either disables it at once — it started when [disabled]
     * and [stalled] are still null after, and the last call was not [Called.skipped]. Returns the report of the last
     * call made (the constructor's when it failed). Asked again after a skipped call, it does what is left: the module
     * is made once.
     */
    fun start(context: ModuleContext): Called? {
        val type = type ?: return null
        val seen = this.context ?: bridge.wrap(ModuleContext::class.java, context).also { this.context = it }
        val module = instance ?: run {
            var made: ForgeModule? = null
            val constructed = call(CONSTRUCTOR, STARTUP_CALL_NANOS, STARTUP_CALL_NANOS * timeScale, startup = true) {
                made = try {
                    type.getConstructor().newInstance() as ForgeModule
                } catch (wrapped: InvocationTargetException) {
                    throw wrapped.cause ?: wrapped
                }
            }
            made?.takeIf { disabled == null && stalled == null }?.also { instance = it } ?: return constructed
        }
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
     * `callback`…), on the module thread, metered, given up past its budget. Never throws: a fault is counted here,
     * told to [onFault], and may disable the module. Nothing runs once it is disabled or unloaded (null then).
     */
    fun call(what: String, body: () -> Unit): Called? = call(what, ForgeLimits.CALL_NANOS, ForgeLimits.CALL_NANOS * timeScale, startup = false, body = body)

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
    fun call(what: String, budgetNanos: Long, body: () -> Unit): Called? = call(what, budgetNanos, budgetNanos, startup = false, body = body)

    /**
     * Whether a call given up before still runs (its thread held back): the module waits for it, and nothing of it
     * runs meanwhile ([call] skips). Settles that call first, which may count a fault and disable the module; when its
     * thread waits for an SDK request, resumes it first ([resume]).
     */
    fun busy(): Boolean {
        settle()
        resume()
        return given != null && disabled == null && stalled == null
    }

    /**
     * [budget] of its own work; [nominal], the game's budget for it, which its allocation allowance follows; [after],
     * the host's own work on the module thread once the call is disarmed (the gauge's walk, which runs no module code).
     */
    private fun call(what: String, nominal: Long, budget: Long, startup: Boolean, after: ((Outcome) -> Unit)? = null, body: () -> Unit): Called? {
        if (disabled != null || unloaded || stalled != null) return null
        // A nested call runs inside this host's own running call, on its module thread: what is left to settle is the
        // caller's (and a call nested in a call given up is refused by the module threads).
        val nested = ModuleThreads.onModuleThread()
        if (!nested) {
            settle()
            if (disabled != null) return null
            if (given != null) {
                skipped++
                log.debug { "$label: its $what call is skipped, a call given up before still runs" }
                return Called(0, -1, null, skipped = true)
            }
        }
        val meter = meter ?: return null
        val names = loader?.names ?: return null
        val calls = if (startup) 1 else maxOf(1, nominal / ForgeLimits.CALL_NANOS)
        val allowance = (ForgeLimits.CALL_ALLOCATION_BYTES * calls).coerceAtMost(ForgeLimits.ALLOCATION_PER_MINUTE_BYTES)
        val outcome = Outcome()
        var held: ModuleThreads.Held? = null
        val waits = if (startup) maxOf(patienceNanos, STARTUP_PATIENCE_NANOS * timeScale) else patienceNanos
        // A call with a budget of its own (a scenario: what is left of the bench) has had all the time it may have.
        val patience = waits.takeIf { it > 0 && budget <= STARTUP_CALL_NANOS * timeScale }?.let { nanos ->
            ModuleThreads.Patience(nanos) { running -> computing(running, budget) }
        }
        val started = System.nanoTime()
        val ran = threads.run(budget + abandonMarginNanos, patience = patience, hold = { held = it }) {
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
            outcome.worked = meter.worked()
            outcome.cpu = meter.cpu()
            try {
                after?.invoke(outcome)
            } catch (e: Exception) {
                log.error("$label: the host's work after its $what call failed", e)
            }
            running?.let(meter::unnest)
        }
        val nanos = System.nanoTime() - started
        val served = if (nested) 0 else threads.served
        if (!nested) spentThisSecond += nanos
        return when (ran) {
            ModuleThreads.Ran.DONE -> {
                val work = outcome.work(nanos) + served
                if (!nested) workThisSecond += work
                val fault = done(what, startup, outcome) ?: allocated(what, outcome.allocated)
                fault?.let(::fault)
                Called(nanos, outcome.allocated, fault, work)
            }
            ModuleThreads.Ran.ABANDONED -> givenUp(Given(what, checkNotNull(held), budget, outcome, served), startup, nanos)
            ModuleThreads.Ran.LATE -> {
                skipped++
                log.debug { "$label: its $what call was skipped: the module thread did not start it within ${(budget + abandonMarginNanos) / NANOS_PER_MS} ms (an overloaded machine), nobody's fault" }
                Called(nanos, -1, null, skipped = true)
            }
            ModuleThreads.Ran.STUCK -> {
                val fault = Fault(Fault.Kind.STUCK, what, "the Forge is stuck: too many module threads were lost", 0, disables = true)
                fault(fault)
                Called(nanos, -1, fault)
            }
        }
    }

    /**
     * Once a second, between two calls: its own work in the last second (a fault *slowness* after
     * [ForgeLimits.SLOW_SECONDS] seconds in a row over [ForgeLimits.DEPLOYMENT_MS_PER_SECOND]). The wall time its calls
     * held their caller is [msPerSecond]: on a busy machine it grows with what the machine holds back, its work does not.
     */
    fun second(): Fault? {
        settle()
        msPerSecond = spentThisSecond / NANOS_PER_MS
        spentThisSecond = 0
        val work = workThisSecond
        workThisSecond = 0
        if (disabled != null || unloaded) return null
        slowSeconds = if (work > ForgeLimits.DEPLOYMENT_MS_PER_SECOND * NANOS_PER_MS) slowSeconds + 1 else 0
        if (slowSeconds < ForgeLimits.SLOW_SECONDS) return null
        return Fault(
            Fault.Kind.SLOWNESS, SECOND,
            "over ${ForgeLimits.DEPLOYMENT_MS_PER_SECOND} ms of its code a second, $slowSeconds seconds in a row (${work / NANOS_PER_MS} ms of its own work in the last)",
            0, disables = true,
        ).also(::fault)
    }

    /**
     * Measures what it retains (M5 §3.7), once a minute (every 10 seconds without allocation metering), between two
     * calls: its instance, its classes' static fields and [handed] (the lambdas it gave the SDK), in a call of its own.
     * Past [ForgeLimits.RETAINED_BYTES], or past [ForgeLimits.GAUGE_MAX_OBJECTS] objects, a fault *memory* disables it.
     * Only its verdict can: the walk is bounded by these ceilings, not by a clock (M5-LOAD); a measure its machine makes
     * slow finishes on the module thread, given up by the caller like any call, and is judged once it ended.
     */
    fun gauge(handed: Collection<Any?> = emptyList()): Fault? {
        val found = measure(listOf(instance) + handed) ?: return null
        return verdict(found)
    }

    /**
     * What [roots] and the static fields of the module's classes retain ([RetainedGauge]), measured in a call of its
     * own; null when it could not run (disabled, unloaded, skipped, given up, or the call faulted: the fault is counted
     * as any other). The static fields are read in the metered call (a class not yet initialized runs its module's
     * static initializer), the walk after it, on the same thread. The bench's own memory checks use it; [gauge]
     * applies the game's limits.
     */
    fun measure(roots: Collection<Any?>): RetainedGauge.Measure? {
        val gauge = gauge ?: return null
        val loader = loader ?: return null
        var statics: List<Any?> = emptyList()
        var found: RetainedGauge.Measure? = null
        val after = { outcome: Outcome ->
            found = gauge.measure(roots + statics)
            outcome.measure = found
        }
        val called = call(GAUGE, ForgeLimits.CALL_NANOS, ForgeLimits.CALL_NANOS * timeScale, startup = false, after = after) {
            statics = gauge.statics(loader.defined)
        } ?: return null
        if (called.fault != null || called.skipped || given != null) return null
        return found
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
     * for the [LeakWatch]. No code of the module runs after this: a call given up that still runs is cut and revoked,
     * its thread counted among the lost ones until it ends.
     *
     * The module thread goes too ([ModuleThreads.renew]: a new one, started now, takes the next call of any module):
     * once the code of a call is compiled, the JVM keeps on its thread the last exception thrown there — the module's
     * own, or its meter's —, whose stack pins the module's classes, until another exception is thrown on that thread or
     * it ends (FLAKY-4: measured, the last of twenty modules unloaded stayed alive on a warm JVM, never on an
     * interpreted one).
     */
    fun unload(): WeakReference<ClassLoader> {
        given?.let { call ->
            threads.cut(call.held)
            if (call.held.thread.isAlive) {
                meter?.revoke()
                threads.lose(call.held.thread)
            }
        }
        given = null
        val reference = WeakReference<ClassLoader>(loader)
        unloaded = true
        instance = null
        context = null
        type = null
        meter = null
        gauge = null
        loader = null
        bridge.clear()
        threads.renew()
        return reference
    }

    /**
     * A call given up at its caller's deadline ([call]): abandoned at once when its thread already computed past its
     * budget ([computing]); else held, resumed when it asks for the SDK ([resume]) and settled once it ended ([settle]).
     * A start (the constructor or `start`) given up past its patience without computing never ends well: it is cut, the
     * module does not start ([stalled]), nobody's fault.
     */
    private fun givenUp(call: Given, startup: Boolean, nanos: Long): Called {
        if (computing(call.held.thread, call.budget)) {
            val fault = abandoned(call, alive = true)
            fault(fault)
            return Called(nanos, -1, fault, call.served)
        }
        given = call
        if (startup) {
            stalled = "its ${call.what} did not end within ${nanos / NANOS_PER_MS} ms, its thread held back by an overloaded machine"
            threads.cut(call.held)
            meter?.revoke()
            log.warn("$label: $stalled; it does not start (nobody's fault)")
        } else {
            log.info("$label: its ${call.what} call was given up at ${nanos / NANOS_PER_MS} ms, its thread held back; it runs on, resumed when it asks the server, the module waiting for it")
        }
        return Called(nanos, -1, null, call.served)
    }

    /**
     * The call given up, when its thread waits for an SDK request (FORGE-ABANDON): the caller waits for it again, at most
     * a call's budget, serving its requests as [call] does, so that the call runs to its end with all its effects, never
     * half of them. Given up again (the machine still holds its thread back), it stays held for the next [busy]. Never
     * while the call computes or is held back between two requests: the caller does not wait for that.
     */
    private fun resume() {
        val call = given ?: return
        if (disabled != null || stalled != null || unloaded || !call.held.asking) return
        val started = System.nanoTime()
        val ran = threads.resume(call.held, call.budget + abandonMarginNanos)
        spentThisSecond += System.nanoTime() - started
        call.served += threads.served
        if (ran == ModuleThreads.Ran.ABANDONED) log.debug { "$label: its ${call.what} call, resumed, was given up again: its thread still held back" }
        settle()
    }

    /**
     * Settles the call given up, if any: once it ended, the fault of its meter (its own work past its budget,
     * allocation, depth), an abandon when its thread's CPU clock shows it computed past its budget in a long step of the
     * JDK, or nobody's fault ([starved]); while it runs, an abandon once it computed past its budget ([computing]), else
     * nothing yet. A given-up gauge's verdict comes then.
     */
    private fun settle() {
        val call = given ?: return
        if (stalled != null) return
        val ended = call.held.ended || !call.held.thread.isAlive
        if (!ended && !computing(call.held.thread, call.budget)) return
        given = null
        val outcome = call.outcome
        val tripped = outcome.tripped
        val fault = when {
            !ended -> abandoned(call, alive = true)
            tripped == null -> null
            tripped.startsWith(TIME_BUDGET) && outcome.worked < call.budget -> abandoned(call, alive = false)
            else -> limit(call.what, false, tripped, lineOf(tripped))
        }
        workThisSecond += maxOf(outcome.worked, outcome.cpu, 0) + call.served
        if (fault != null) {
            fault(fault)
            return
        }
        starved++
        log.info(
            "$label: its ${call.what} call, given up, ended without computing past its budget (${outcome.worked / NANOS_PER_MS} ms of its own work): " +
                "its thread was held back, nobody's fault ($starved so far)",
        )
        if (call.what == GAUGE) outcome.measure?.let(::verdict)
    }

    /**
     * Whether [thread], running a call of [budget], computed past it: its CPU clock since the call was armed reached
     * [Meter.cpuEvidence] (its budget, 40 ms at least). That is the module's doing, which its meter could not stop (one
     * long step of the JDK): a call merely held back by the machine never does (the CPU clock moves by steps of 15.6 ms
     * under Windows: 40 ms is three of them, which a call of a few milliseconds never shows). Without a CPU clock, any
     * call past its budget does.
     *
     * A call its meter stopped only unwinds (no code of the module runs after the stop): its CPU clock counts from the
     * stop ([Meter.cpuAtTrip], FORGE-ABANDON). From the arm, a loop of 20 ms of work cut into slices by a busy machine
     * read 40 ms or more as it unwound, and was judged abandoned instead of its meter's one fault (FLAKY-5).
     */
    private fun computing(thread: Thread, budget: Long): Boolean {
        val meter = meter ?: return false
        val armed = meter.cpuAtArm()
        if (armed == Meter.UNARMED) return false
        if (armed < 0) return true
        val from = meter.cpuAtTrip().takeIf { it != Meter.UNARMED } ?: armed
        val now = CPU_TIMES.getThreadCpuTime(thread.threadId())
        return now >= 0 && now - from >= Meter.cpuEvidence(budget)
    }

    /**
     * The fault of a call given up while its thread computed on past its budget (M5-05: one long call of the JDK the
     * meter cannot stop): disables at once; the call cut and the meter revoked, so that its thread ends at its next SDK
     * request or the module's next code. When [alive], the thread is lost ([ModuleThreads.lose]) and where the module
     * was is read from its stack — but that waits for the thread's next safepoint poll, and inside one long call of the
     * JDK none may come for tens of milliseconds (measured: 50 to 65 ms in the hash of finding 1, a whole `indexOf` of
     * 75 ms). So it is read on a helper thread and waited at most [lineWaitMillis] (0 on the server thread: it never
     * waits; the place goes to the log when read).
     */
    private fun abandoned(call: Given, alive: Boolean): Fault {
        threads.cut(call.held)
        meter?.revoke()
        val cause = "time budget: one call ran past ${(call.budget + abandonMarginNanos) / NANOS_PER_MS} ms and was abandoned"
        if (!alive) return Fault(Fault.Kind.LIMIT, call.what, cause, 0, disables = true)
        val thread = call.held.thread
        threads.lose(thread)
        val names = loader?.names.orEmpty()
        val stack = CompletableFuture<Array<StackTraceElement>>()
        Thread.ofPlatform().daemon().name("narrator2-module-stack").start { stack.complete(thread.stackTrace) }
        val frames = try {
            stack.get(lineWaitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        }
        if (frames == null) {
            stack.thenAccept { late -> log.warn("$label: its call abandoned past its budget ran${place(late, names).first.ifEmpty { " out of its code" }}") }
            return Fault(Fault.Kind.LIMIT, call.what, cause, 0, disables = true)
        }
        val (place, line) = place(frames, names)
        return Fault(Fault.Kind.LIMIT, call.what, cause + place, line, disables = true)
    }

    private fun verdict(found: RetainedGauge.Measure): Fault? {
        retained = found
        val cause = when {
            found.objects > ForgeLimits.GAUGE_MAX_OBJECTS -> "retains too much to measure (over ${ForgeLimits.GAUGE_MAX_OBJECTS} objects)"
            found.bytes > ForgeLimits.RETAINED_BYTES ->
                "retains ${if (found.complete) "about" else "over"} ${found.bytes / MIB} MiB (at most ${ForgeLimits.RETAINED_BYTES / MIB})"
            else -> return null
        }
        return Fault(Fault.Kind.MEMORY, GAUGE, cause, 0, disables = true).also(::fault)
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

    /**
     * What a call left, written on the module thread before its end, read by the caller after it (never after a give-up
     * but once its thread ended, or its stack read).
     */
    private class Outcome {
        var thrown: String? = null
        var line = 0
        var tripped: String? = null
        var allocated = -1L
        var worked = -1L
        var cpu = -1L
        var measure: RetainedGauge.Measure? = null

        /**
         * Its own work: what its meter measured of its code, or its thread's CPU time (a long step of the JDK) up to the
         * call's [wall] time, the larger (the CPU clock moves by steps of 15.6 ms under Windows: [wall] caps a step that
         * fell in a short call). Without a CPU clock, the wall time.
         */
        fun work(wall: Long): Long = if (cpu < 0) wall else maxOf(worked, minOf(cpu, wall))
    }

    /** A call given up: what it was, the call held, its budget, what it leaves, the time the caller spent serving its SDK calls (resumes included). */
    private class Given(val what: String, val held: ModuleThreads.Held, val budget: Long, val outcome: Outcome, var served: Long)

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

        /** Past its budget, the meter stops a call at its next check and the call unwinds: the caller waits this much more before it gives it up. */
        const val ABANDON_MARGIN_NANOS = 5L * 1_000_000

        /**
         * How long past its budget a start (the constructor, `start`) is waited for while its thread has not computed
         * (M5-LOAD): a start cannot be given up and finish alone (its SDK calls would be refused), and a saturated machine
         * held a cold start (28 ms of work) back for over a second. The server thread waits that long only when the
         * machine holds the module thread back, once per deployment.
         */
        const val STARTUP_PATIENCE_NANOS = 2_000L * 1_000_000

        private val CPU_TIMES = ManagementFactory.getThreadMXBean()

        /** How the meter names the time it stops a call for (`time budget at TagGame.java:41`). */
        private const val TIME_BUDGET = "time budget"

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
            // An act's JSON and its text, built once as the first act would build them (M5-16, FLAKY-4: the first act of
            // the first module of a JVM paid 6 to 8 ms of loading in its call).
            check(buildJsonObject { put("text", "x"); put("seconds", 0.5) }.toString().isNotEmpty())
            SdkBridge.interfaces().forEach { type -> java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } }
            Log.forge.debug { "the modules' runtime and SDK loaded in ${(System.nanoTime() - started) / NANOS_PER_MS} ms" + if (failed.isEmpty()) "" else " (not here: $failed)" }
        }

        /**
         * Classes (binary names) that the module's first calls would load: the runtime, the SDK's values, its game side,
         * the path of an act (FLAKY-4, read in a class-loading log of the bench's first module).
         */
        private val PRELOADED = listOf(
            "io.github.clemsix6.narrator2.forge.rt.Meter", "io.github.clemsix6.narrator2.forge.rt.Guarded",
            "io.github.clemsix6.narrator2.forge.rt.Texts", "io.github.clemsix6.narrator2.forge.rt.Views",
            "io.github.clemsix6.narrator2.forge.rt.Meter\$Nested", "io.github.clemsix6.narrator2.forge.rt.Guarded\$Handles",
            "io.github.clemsix6.narrator2.forge.rt.Texts\$Out", "io.github.clemsix6.narrator2.core.ModelText",
            "io.github.clemsix6.narrator2.forge.sdk.Act", "io.github.clemsix6.narrator2.forge.sdk.Act\$Kind",
            "io.github.clemsix6.narrator2.forge.sdk.ActQueue\$Asked", "io.github.clemsix6.narrator2.forge.sdk.Scheduler\$Due",
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
        private const val NANOS_PER_MS = 1_000_000L
        private const val MIB = 1024L * 1024
        private const val HOST_PACKAGE = "io.github.clemsix6.narrator2.forge.host."
    }
}
