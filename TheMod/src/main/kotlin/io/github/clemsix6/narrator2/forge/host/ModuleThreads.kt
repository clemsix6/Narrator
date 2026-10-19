package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.rt.ForgeLimit
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
 *   [ForgeLimit] into the abandoned thread, which therefore **never touches the world again**. The caller's
 *   `onAbandon` revokes the module's meter, so the thread ends at the module's next code. A fresh thread takes the
 *   next calls; the abandoned one runs at the lowest priority until its JDK call returns.
 * - **Ceiling** ([LostThreads]): at most [LostThreads.limit] abandoned threads still alive; past it, every call is
 *   refused ([Ran.STUCK]) until the server restarts, and the Forge is told once.
 *
 * One caller at a time (the server thread, or one bench worker): calls never overlap nor nest.
 */
class ModuleThreads(
    private val name: String = "narrator2-module",
    val lost: LostThreads = LostThreads(),
) {
    private val log = Log.forge
    private var worker: Worker? = null
    private var running = false
    private val made = AtomicInteger()

    /** How a [run] ended. */
    enum class Ran {
        /** The body ran to its end (whatever it threw is the body's own business). */
        DONE,

        /** Past the budget: given up, its thread lost (revoked, lowest priority) until its JDK call returns. */
        ABANDONED,

        /** The module thread did not even start it within the budget (an overloaded machine): skipped, nobody's fault. */
        LATE,

        /** Too many threads lost: no module code runs any more until the server restarts. */
        STUCK,
    }

    /**
     * Runs [body] (a call of a module: arm its meter, call it, disarm it) on the module thread, the calling thread
     * waiting at most [budgetNanos] and serving the module's SDK requests meanwhile. Past the budget, [onAbandon] gets
     * the abandoned thread (its stack is still readable) before the caller goes on. Never on two threads at once.
     */
    fun run(budgetNanos: Long, onAbandon: (Thread) -> Unit = {}, body: () -> Unit): Ran {
        check(!running) { "a module's call runs already: calls never overlap nor nest" }
        if (lost.stuck) return Ran.STUCK
        running = true
        try {
            val worker = worker ?: Worker("$name-${made.incrementAndGet()}").also { worker = it; it.start() }
            val call = Call(body, Thread.currentThread())
            val deadline = System.nanoTime() + budgetNanos
            worker.post(call)
            while (true) {
                if (call.state.get() == Phase.DONE) return Ran.DONE
                if (System.nanoTime() - deadline >= 0) {
                    if (call.state.compareAndSet(Phase.POSTED, Phase.CANCELLED)) return Ran.LATE
                    if (call.state.compareAndSet(Phase.RUNNING, Phase.ABANDONED)) {
                        abandon(worker, call, onAbandon)
                        return Ran.ABANDONED
                    }
                    continue // it ended just now
                }
                val request = call.request
                if (request != null) serve(call, request) else Thread.onSpinWait()
            }
        } finally {
            running = false
        }
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
        call.request = null
        try {
            request.value = request.body()
        } catch (thrown: Throwable) {
            request.error = thrown
        }
        request.done = true
        call.thread?.let(LockSupport::unpark)
    }

    private fun abandon(worker: Worker, call: Call, onAbandon: (Thread) -> Unit) {
        this.worker = null
        call.request?.let { pending ->
            call.request = null
            pending.error = ForgeLimit(ABANDONED_MESSAGE)
            pending.done = true
        }
        LockSupport.unpark(worker)
        worker.priority = Thread.MIN_PRIORITY
        worker.name = "${worker.name} (abandoned)"
        try {
            onAbandon(worker)
        } catch (e: Exception) {
            log.error("a module thread was abandoned; its host failed to note it", e)
        }
        lost.add(worker)
    }

    private enum class Phase { POSTED, RUNNING, DONE, CANCELLED, ABANDONED }

    /** One call handed over: [state] moves POSTED → RUNNING → DONE on the module thread, or to CANCELLED or ABANDONED on the caller's. */
    private class Call(val body: () -> Unit, val caller: Thread) {
        val state = AtomicReference(Phase.POSTED)

        /** The module thread serving this call, once it started. */
        @Volatile
        var thread: Thread? = null

        /** The SDK request the module thread waits for, served by the caller. */
        @Volatile
        var request: Request? = null
    }

    /** An SDK call of the module, run on the caller's thread. */
    private class Request(val body: () -> Any?) {
        var value: Any? = null
        var error: Throwable? = null

        @Volatile
        var done = false
    }

    private inner class Worker(name: String) : Thread(null, null, name, STACK_BYTES) {
        @Volatile
        private var next: Call? = null

        @Volatile
        var closing = false

        /** The call this thread runs now; null between calls. */
        @Volatile
        private var serving: Call? = null

        init {
            isDaemon = true
            priority = NORM_PRIORITY
        }

        fun post(call: Call) {
            next = call
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
            return call.state.compareAndSet(Phase.RUNNING, Phase.DONE) // abandoned: this thread is lost, it ends here
        }

        /** The next call: spins a little (a tick's calls come in a burst), then parks. Null once closed. */
        private fun take(): Call? {
            val spinUntil = System.nanoTime() + IDLE_SPIN_NANOS
            while (true) {
                next?.let {
                    next = null
                    return it
                }
                if (closing) return null
                if (System.nanoTime() - spinUntil < 0) Thread.onSpinWait() else LockSupport.park(this)
            }
        }

        /** [body], run by the caller of the call this thread serves; refused once that call was abandoned. */
        fun <T> ask(body: () -> T): T {
            val call = serving ?: throw IllegalStateException("module code ran outside a call")
            if (call.state.get() != Phase.RUNNING) throw ForgeLimit(ABANDONED_MESSAGE)
            val request = Request(body)
            call.request = request
            val spinUntil = System.nanoTime() + REQUEST_SPIN_NANOS
            while (!request.done) {
                if (call.state.get() != Phase.RUNNING) throw ForgeLimit(ABANDONED_MESSAGE)
                if (System.nanoTime() - spinUntil < 0) Thread.onSpinWait() else LockSupport.parkNanos(this, REQUEST_PARK_NANOS)
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

        /** A module thread waiting for its SDK request spins this long, then parks by slices (the caller unparks it). */
        private const val REQUEST_SPIN_NANOS = 200_000L
        private const val REQUEST_PARK_NANOS = 100_000L

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
 * The module threads lost by abandonment (M5 §3.7): each still runs its JDK call, at the lowest priority, until it
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
