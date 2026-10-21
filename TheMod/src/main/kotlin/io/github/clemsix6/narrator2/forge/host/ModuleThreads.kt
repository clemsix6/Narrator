package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.rt.ForgeLimit
import io.github.clemsix6.narrator2.forge.rt.Meter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * The threads that run the modules' code (M5 §1.1; the supervisor's decision on finding 1 of M5-04). A module's call
 * never runs on the thread that asks for it — the server thread in the game, the bench's worker at the bench — but on
 * the **module thread** of this object; the caller hands the call over and waits for it **at most its budget**, never
 * more, whatever the module does: a single call of the JDK that hashes a shared structure for minutes, an `indexOf` of
 * O(n·m), anything the meter cannot see between two ticks of the module's code.
 *
 * - **Hand-over** ([run]): the caller spins while the call runs (the hand-over costs about a microsecond; a parked
 *   thread costs tens and may wake milliseconds late), and meanwhile it **serves the module's SDK requests**
 *   ([toCaller]): every SDK call of a module runs on the caller's thread — the server thread in the game — as if the
 *   module ran there. The game's own checks of the main thread (`ServerChunkCache.getChunkNow` answers null elsewhere;
 *   `getChunk` waits for the main thread, which would wait for the module: a deadlock) and the SDK objects written for
 *   the server thread work unchanged, and no world access ever races with the server.
 * - **Abandon**: past the budget, the caller gives the call up and goes on. An SDK request it is serving ends first
 *   (an SDK call is short and bounded); none is served after: a request already posted, or a later one, throws
 *   [ForgeLimit] into the abandoned thread, which therefore **never touches the world again**. A fresh thread, started
 *   then, takes the next calls. By default the given-up thread is **lost** at once ([lose]: the normal priority,
 *   counted by [LostThreads]).
 * - **Held** ([Held], FORGE-ABANDON): a caller that tells first whether the module computed (the host, M5-LOAD) has the
 *   call given up **held** instead: its thread runs on, its SDK requests **wait** (never refused, never served behind
 *   the caller's back) until the caller [resume]s the call — a call is never half done — or [cut]s it (abandoned, or
 *   its module unloaded: refused from then on, as above).
 * - **Patience** ([Patience]): a caller that may wait (the bench, a module's start) waits past the budget as long as the
 *   call's thread has not computed, parked by slices so that the machine runs that thread.
 * - **Priority**: the module thread runs at its caller's priority (the server thread's is 8 on a machine of more than
 *   four cores): a call is the caller's own work, handed over; it is not held back by threads the caller outranks.
 * - **Ceiling** ([LostThreads]): at most [LostThreads.limit] lost threads still alive; past it, every call is
 *   refused ([Ran.STUCK]) until the server restarts, and the Forge is told once.
 *
 * One caller at a time (the server thread, or one bench worker): calls never overlap; a call made from a module thread
 * nests in the one that thread runs ([run]).
 */
class ModuleThreads(
    private val name: String = "narrator2-module",
    val lost: LostThreads = LostThreads(),
) {
    private val log = Log.forge
    private var worker: Worker? = null
    private var running = false
    private val made = AtomicInteger()

    /** The nanoseconds the caller spent serving the SDK requests of the last call it handed over or resumed ([run], [resume]): work done for the module. */
    var served = 0L
        private set

    /** How a [run] ended. */
    enum class Ran {
        /** The body ran to its end (whatever it threw is the body's own business). */
        DONE,

        /** Past the budget (and the patience): given up; its thread runs on alone, refused by the SDK, or held ([Held]). */
        ABANDONED,

        /** The module thread did not even start it in time (an overloaded machine): skipped, nobody's fault. */
        LATE,

        /** Too many threads lost: no module code runs any more until the server restarts. */
        STUCK,
    }

    /**
     * How long past a call's budget its caller may wait while [computing] answers no (the call's thread has not computed
     * past its budget: the machine holds it back), asked about once a millisecond. A caller that never waits has none.
     */
    class Patience(val nanos: Long, val computing: (Thread) -> Boolean)

    /**
     * A call given up and held (FORGE-ABANDON): its [thread] runs on, and its SDK requests wait until the caller
     * [resume]s it or [cut]s it. Whoever holds it does one or the other in the end: its thread waits for that.
     */
    class Held internal constructor(internal val call: Call, val thread: Thread) {
        /** Whether it ran to its end: what it wrote before is visible. Any thread. */
        val ended: Boolean get() = call.state.get() == Phase.DONE

        /** Whether its thread waits for an SDK request: the module asks the server for something. Any thread. */
        val asking: Boolean get() = call.request != null && call.state.get() == Phase.HELD
    }

    /**
     * Runs [body] (a call of a module: arm its meter, call it, disarm it) on the module thread, the calling thread
     * waiting at most [budgetNanos] (and [patience]) and serving the module's SDK requests meanwhile ([served]). Past
     * them, the call is given up: with [hold], it is held and handed to [hold] ([Held]); else [onAbandon] gets the
     * given-up thread (its stack is still readable) before the caller goes on, and that thread is lost at once. Never
     * on two threads at once.
     *
     * **Nested**: called from a module thread itself (at the bench, a scenario's code runs the module's handlers), [body]
     * runs right there, with no new hand-over: the call it nests in keeps its deadline, so the caller waits no longer.
     * On a thread whose call was given up, it throws [ForgeLimit] instead, before and after [body].
     */
    fun run(budgetNanos: Long, onAbandon: (Thread) -> Unit = {}, patience: Patience? = null, hold: ((Held) -> Unit)? = null, body: () -> Unit): Ran {
        val current = Thread.currentThread()
        if (current is Worker) return current.nested(body)
        check(!running) { "a module's call runs already: calls never overlap" }
        if (lost.stuck) return Ran.STUCK
        running = true
        served = 0
        try {
            val worker = worker ?: start()
            if (worker.priority != current.priority) worker.priority = current.priority
            val call = Call(body, current)
            worker.post(call)
            val ran = attend(call, budgetNanos, patience, Phase.RUNNING, if (hold != null) Phase.HELD else Phase.ABANDONED)
            if (ran == Ran.ABANDONED) givenUp(worker, call, onAbandon, hold)
            return ran
        } finally {
            running = false
        }
    }

    /**
     * Waits again for [held] (FORGE-ABANDON), at most [budgetNanos], serving its SDK requests as [run] does: [Ran.DONE]
     * once it ran to its end, [Ran.ABANDONED] when given up again (held still: the caller may resume it later) or cut
     * ([cut]), [Ran.STUCK] when no module code may run any more. [served] is what this resume served. The caller's
     * thread, never while a call runs.
     */
    fun resume(held: Held, budgetNanos: Long): Ran {
        check(!running) { "a module's call runs already: calls never overlap" }
        if (lost.stuck) return Ran.STUCK
        val call = held.call
        if (!call.state.compareAndSet(Phase.HELD, Phase.RESUMED)) return if (held.ended) Ran.DONE else Ran.ABANDONED
        running = true
        served = 0
        try {
            return attend(call, budgetNanos, null, Phase.RESUMED, Phase.HELD)
        } finally {
            running = false
        }
    }

    /**
     * Abandons [held] (FORGE-ABANDON: it computed past its budget, or its module is unloaded), held or being resumed:
     * its SDK request waiting, and every later one, throw [ForgeLimit] into its thread, which unwinds and **never
     * touches the world again**; a [resume] that attends it gives it up. A call that ended is left as it is. The
     * caller's thread.
     */
    fun cut(held: Held) {
        val before = held.call.state.getAndUpdate { if (it == Phase.HELD || it == Phase.RESUMED) Phase.ABANDONED else it }
        if (before == Phase.HELD || before == Phase.RESUMED) refuse(held.call, held.thread)
    }

    /**
     * Waits for [call] (in [running]) until it is done, serving its SDK requests, or past [budgetNanos] (and [patience])
     * moves it to [givenUp]: [Ran.ABANDONED] then, or [Ran.LATE] when its module thread had not even started it. A call
     * [cut] meanwhile (by a request served here) is [Ran.ABANDONED] at once.
     */
    private fun attend(call: Call, budgetNanos: Long, patience: Patience?, running: Phase, givenUp: Phase): Ran {
        val deadline = System.nanoTime() + budgetNanos
        val givenUpAt = deadline + (patience?.nanos ?: 0)
        var askedAt = deadline
        while (true) {
            when (call.state.get()) {
                Phase.DONE -> return Ran.DONE
                Phase.ABANDONED -> return Ran.ABANDONED
                else -> {}
            }
            val now = System.nanoTime()
            val past = now - deadline >= 0
            if (past) {
                val over = now - givenUpAt >= 0
                if (over && call.state.compareAndSet(Phase.POSTED, Phase.CANCELLED)) return Ran.LATE
                val computed = !over && patience != null && now - askedAt >= POLL_NANOS && call.thread?.let { thread ->
                    askedAt = now
                    patience.computing(thread)
                } == true
                if ((over || computed) && call.state.compareAndSet(running, givenUp)) return Ran.ABANDONED
            }
            val request = call.request
            when {
                request != null -> serve(call, request)
                past -> {
                    // Patient: the machine runs the module thread meanwhile, which wakes the caller for a request.
                    call.parked = true
                    if (call.request == null && call.state.get() != Phase.DONE) LockSupport.parkNanos(POLL_NANOS)
                }
                else -> Thread.onSpinWait()
            }
        }
    }

    /**
     * Ends the live module thread and starts the next one now, when one is live (a module was unloaded: what the JVM
     * keeps on a thread goes with it, and the next call is handed to a thread already started, not one still to make).
     */
    fun renew() {
        if (worker == null) return
        close()
        start()
    }

    /**
     * Counts [thread], whose call was given up, among the lost threads: it computes on past its budget (M5-LOAD), at the
     * normal priority from now on, until its JDK call returns ([LostThreads], whose ceiling may make the Forge stuck).
     *
     * Not the lowest (M5-LOAD): every pause of the JVM (a garbage collection) first waits for each thread running Java
     * code to reach a safepoint, and a busy machine starves a thread at the lowest priority for seconds — measured, the
     * whole JVM, server thread included, stood still 2.2 to 2.6 s at each collection while such a thread computed.
     */
    fun lose(thread: Thread) {
        thread.priority = Thread.NORM_PRIORITY
        if (!thread.name.endsWith(LOST_SUFFIX)) thread.name = thread.name.removeSuffix(GIVEN_UP_SUFFIX) + LOST_SUFFIX
        lost.add(thread)
    }

    private fun start(): Worker = Worker("$name-${made.incrementAndGet()}").also {
        worker = it
        it.start()
    }

    /** Ends the live module thread (the server stops, a bench or a test is done); a later [run] makes a new one. */
    fun close() {
        worker?.let {
            it.closing = true
            LockSupport.unpark(it)
        }
        worker = null
    }

    private fun serve(call: Call, request: Request) {
        val began = System.nanoTime()
        call.request = null
        try {
            request.value = request.body()
        } catch (thrown: Throwable) {
            request.error = thrown
        }
        request.done = true
        call.thread?.let(LockSupport::unpark)
        served += System.nanoTime() - began
    }

    /** [call], given up on [worker]: a fresh thread for the next calls; [call] held ([hold]), or refused and its thread lost. */
    private fun givenUp(worker: Worker, call: Call, onAbandon: (Thread) -> Unit, hold: ((Held) -> Unit)?) {
        start()
        worker.name = worker.name + GIVEN_UP_SUFFIX
        if (hold == null) refuse(call, worker)
        try {
            if (hold != null) hold(Held(call, worker)) else onAbandon(worker)
        } catch (e: Exception) {
            log.error("a module thread was given up; its host failed to note it", e)
        }
        if (hold == null) lose(worker)
    }

    /** [call], abandoned: its SDK request waiting is refused (and every later one, as its state says), its [thread] woken to unwind. */
    private fun refuse(call: Call, thread: Thread) {
        call.request?.let { pending ->
            call.request = null
            pending.error = ForgeLimit(ABANDONED_MESSAGE)
            pending.done = true
        }
        LockSupport.unpark(thread)
    }

    internal enum class Phase { POSTED, RUNNING, DONE, CANCELLED, ABANDONED, HELD, RESUMED }

    /**
     * One call handed over: [state] moves POSTED → RUNNING → DONE on the module thread, or to CANCELLED or ABANDONED on
     * the caller's; given up and held, RUNNING → HELD, then RESUMED and back to HELD as often as its caller resumes it,
     * until DONE (on the module thread) or ABANDONED ([cut]).
     */
    internal class Call(val body: () -> Unit, val caller: Thread) {
        val state = AtomicReference(Phase.POSTED)

        /** The module thread serving this call, once it started. */
        @Volatile
        var thread: Thread? = null

        /** The SDK request the module thread waits for, served by the caller. */
        @Volatile
        var request: Request? = null

        /** The caller waits past the budget, parked by slices: the module thread wakes it for a request, or at its end. */
        @Volatile
        var parked = false

        /** Whether its caller waits for it now (it serves its SDK requests). */
        fun attended(): Boolean = state.get().let { it == Phase.RUNNING || it == Phase.RESUMED }
    }

    /** An SDK call of the module, run on the caller's thread. */
    internal class Request(val body: () -> Any?) {
        var value: Any? = null
        var error: Throwable? = null

        @Volatile
        var done = false
    }

    /**
     * A module thread. Born at the priority of the thread that makes it (the caller: a thread made after an abandon starts
     * at once on a busy machine, not after the threads its caller outranks).
     */
    private inner class Worker(name: String) : Thread(null, null, name, STACK_BYTES) {
        /** The call handed over and not taken yet; taken and cleared in one step, so that no call posted meanwhile is lost. */
        private val next = AtomicReference<Call?>()

        @Volatile
        var closing = false

        /** The call this thread runs now; null between calls. */
        @Volatile
        private var serving: Call? = null

        init {
            isDaemon = true
        }

        fun post(call: Call) {
            next.set(call)
            LockSupport.unpark(this)
        }

        override fun run() {
            while (runNext()) continue
        }

        /**
         * Takes the next call and runs it; false when this thread ends (closed, or lost). A frame of its own, so that
         * no reference to a finished call (and to its module) stays in a slot while the thread waits.
         */
        private fun runNext(): Boolean {
            val call = take() ?: return false
            if (!call.state.compareAndSet(Phase.POSTED, Phase.RUNNING)) return true // cancelled: it came too late
            call.thread = this
            serving = call
            try {
                call.body()
            } catch (thrown: Throwable) {
                log.error("a module's call let something through on $name", thrown)
            }
            serving = null
            val before = call.state.getAndUpdate { if (it == Phase.RUNNING || it == Phase.HELD || it == Phase.RESUMED) Phase.DONE else it }
            if (before != Phase.ABANDONED && call.parked) LockSupport.unpark(call.caller)
            // A call given up (held, resumed or abandoned): this thread ends with it, a fresh one took the next calls.
            return before == Phase.RUNNING
        }

        /** The next call: spins a little (a tick's calls come in a burst), then parks. Null once closed. */
        private fun take(): Call? {
            val spinUntil = System.nanoTime() + IDLE_SPIN_NANOS
            while (true) {
                next.getAndSet(null)?.let { return it }
                if (closing) return null
                if (System.nanoTime() - spinUntil < 0) Thread.onSpinWait() else LockSupport.park(this)
            }
        }

        /** [body], a call nested in the one this thread serves (the bench), run here; refused once that call was given up. */
        fun nested(body: () -> Unit): Ran {
            val call = serving ?: throw IllegalStateException("module code ran outside a call")
            if (!call.attended()) throw ForgeLimit(ABANDONED_MESSAGE)
            try {
                body()
            } catch (thrown: Throwable) {
                log.error("a module's nested call let something through on $name", thrown)
            }
            if (!call.attended()) throw ForgeLimit(ABANDONED_MESSAGE)
            return Ran.DONE
        }

        /**
         * [body], run by the caller of the call this thread serves; refused once that call was abandoned. While the call
         * is held (FORGE-ABANDON), the request waits for its caller to resume it (or to cut it): both wake this thread.
         *
         * The wait is never the module's computing (FORGE-ABANDON-FIX): this thread spins a little while the caller
         * attends the call (a request is served in a microsecond), then parks until it is served, and the CPU time it
         * spent waiting is taken out of its call's evidence of computing ([Meter.waited]). A call held for ticks may wait
         * for hundreds of slow requests: counted, that wait read as computing, and an honest module was abandoned.
         */
        fun <T> ask(body: () -> T): T {
            val call = serving ?: throw IllegalStateException("module code ran outside a call")
            if (call.state.get() == Phase.ABANDONED) throw ForgeLimit(ABANDONED_MESSAGE)
            val request = Request(body)
            val waiting = Meter.waitStarts()
            try {
                call.request = request
                if (call.parked) LockSupport.unpark(call.caller)
                val spinUntil = System.nanoTime() + REQUEST_SPIN_NANOS
                while (!request.done) {
                    when (call.state.get()) {
                        Phase.ABANDONED -> throw ForgeLimit(ABANDONED_MESSAGE)
                        Phase.HELD -> LockSupport.park(this)
                        else -> if (System.nanoTime() - spinUntil < 0) Thread.onSpinWait() else LockSupport.park(this)
                    }
                }
            } finally {
                Meter.waited(waiting)
            }
            request.error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return request.value as T
        }
    }

    companion object {
        /** What an abandoned thread gets from the SDK. */
        const val ABANDONED_MESSAGE = "the call was abandoned: it ran past its time budget"

        /** A module thread's stack: the same everywhere, whatever `-Xss` says (the module's own depth is bounded at 64 frames). */
        private const val STACK_BYTES = 4L * 1024 * 1024

        /** After a call, the module thread spins this long for the next one before it parks (a tick's calls come in a burst). */
        private const val IDLE_SPIN_NANOS = 100_000L

        /** A module thread waiting for its SDK request spins this long, then parks until served (the caller unparks it). */
        private const val REQUEST_SPIN_NANOS = 200_000L

        /** A patient caller, past a call's budget, parks by slices of this and asks whether the call's thread computed as often. */
        private const val POLL_NANOS = 1_000_000L

        /** The name of a module thread whose call was given up, and of one lost. */
        private const val GIVEN_UP_SUFFIX = " (given up)"
        private const val LOST_SUFFIX = " (abandoned)"

        /**
         * [body], one SDK call of a module: on a module thread, run by the thread that waits for its call (the server
         * thread in the game) and refused ([ForgeLimit]) once that call was abandoned; on any other thread, run in place.
         */
        fun <T> toCaller(body: () -> T): T = when (val thread = Thread.currentThread()) {
            is ModuleThreads.Worker -> thread.ask(body)
            else -> body()
        }

        /** Whether this thread is a module thread (its code is a module's, metered). */
        fun onModuleThread(): Boolean = Thread.currentThread() is ModuleThreads.Worker
    }
}

/**
 * The module threads lost by abandonment (M5 §3.7): each still runs its JDK call, at the normal priority, until it
 * returns. [limit] may be alive at once; past it, the Forge is **stuck** until the server restarts ([stuck]): no
 * module code runs any more, and [onStuck] is called once, with why (the Forge stops every module, writes the decision
 * `forge stuck` and tells the operators, like a stuck compiler). One per Forge: the server's module threads and the
 * bench's share it.
 */
class LostThreads(val limit: Int = LIMIT, private val onStuck: (String) -> Unit = {}) {
    private val log = Log.forge
    private val threads = mutableListOf<Thread>()

    /** When the Forge got stuck; null while it is not. Any thread. */
    @Volatile
    var stuckSince: Instant? = null
        private set

    val stuck: Boolean get() = stuckSince != null

    /** The abandoned threads still alive. Any thread. */
    @Synchronized
    fun alive(): Int {
        threads.removeAll { !it.isAlive }
        return threads.size
    }

    @Synchronized
    internal fun add(thread: Thread) {
        threads.removeAll { !it.isAlive }
        threads += thread
        log.warn("a module thread was abandoned past its budget: ${thread.name} (${threads.size} lost and still running, $limit at most)")
        if (threads.size <= limit || stuck) return
        stuckSince = Instant.now()
        val why = "${threads.size} module threads were abandoned and still run (at most $limit): no module runs until the server restarts"
        log.error("the Forge is stuck: $why")
        try {
            onStuck(why)
        } catch (e: Exception) {
            log.error("telling the Forge it is stuck failed", e)
        }
    }

    companion object {
        /** Abandoned module threads that may still run at once (the supervisor's decision on finding 1 of M5-04). */
        const val LIMIT = 2
    }
}
