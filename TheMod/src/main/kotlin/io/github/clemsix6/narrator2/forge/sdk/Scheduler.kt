package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Scheduled
import io.github.clemsix6.narrator2.forge.api.Timers
import java.util.PriorityQueue

/**
 * One deployment's timers (M5 §4.2, [Timers]), counted in the ticks the engine gives it: [advance] once a tick, then
 * [next] until it answers null, each [Due] run in a measured call of its own. A timer made at tick `t` for `n` ticks is
 * due at `t + n`; due timers run in the order they are due, then in the order they were made. A repeating one is due
 * again `n` ticks after it ran. Nothing survives a restart (`start` makes its timers again).
 *
 * Limits: a delay of 1 to `ForgeLimits.TIMER_MAX_TICKS`, `ForgeLimits.PENDING_TIMERS` timers waiting,
 * `ForgeLimits.REPEATING_TIMERS` of them repeating; beyond, [ForgeRefusal] and nothing changes.
 *
 * **The module's tasks are opaque** (design review, finding 8): kept as they are, never a key, never logged, never asked
 * for `equals`, `hashCode` or `toString`, and run only by [Due.run], which the engine calls inside a call armed by the
 * host. While the module stops ([stopping]) no timer is made; once it ended ([close]) every timer is cancelled and
 * [Timers] refused. Server thread.
 */
class Scheduler : Timers {
    private val queue = PriorityQueue<Timer>(compareBy<Timer>({ it.due }, { it.id }))
    private var tick = 0L
    private var lastId = 0
    private var repeating = 0
    private var phase = Phase.RUNNING

    override fun after(ticks: Int, task: Runnable?): Scheduled = add("after", ticks, task, repeat = false)

    override fun every(ticks: Int, task: Runnable?): Scheduled = add("every", ticks, task, repeat = true)

    /** One more tick: the timers due by now come out of [next]. */
    fun advance() {
        tick++
    }

    /**
     * The next timer due by now, taken off the waiting ones (a repeating one is put back, due again in its period);
     * null when none is due. Run it with [Due.run] inside an armed call before asking for the next one.
     */
    fun next(): Due? {
        val timer = queue.peek()?.takeIf { it.due <= tick } ?: return null
        queue.poll()
        if (timer.repeat) {
            timer.due = tick + timer.period
            queue += timer
        } else {
            timer.done = true
        }
        return Due(timer)
    }

    /** Timers waiting, repeating ones included. */
    val pending: Int get() = queue.size

    /** The module's `stop` is about to run: no timer from now on (none runs during `stop` either). */
    fun stopping() {
        if (phase == Phase.RUNNING) phase = Phase.STOPPING
    }

    /** The deployment ended: every timer cancelled, [Timers] refused. */
    fun close() {
        phase = Phase.ENDED
        queue.forEach { it.cancelled = true }
        queue.clear()
        repeating = 0
    }

    private fun add(method: String, ticks: Int, task: Runnable?, repeat: Boolean): Scheduled {
        when (phase) {
            Phase.RUNNING -> Unit
            Phase.STOPPING -> throw ForgeRefusal("Timers.$method: no timer while the module stops")
            Phase.ENDED -> throw ForgeRefusal("the deployment has ended: its SDK objects no longer work")
        }
        if (task == null) throw ForgeRefusal("Timers.$method: the task must not be null")
        if (ticks !in 1..ForgeLimits.TIMER_MAX_TICKS) {
            throw ForgeRefusal("Timers.$method: a delay is 1 to ${ForgeLimits.TIMER_MAX_TICKS} ticks (Limits.MAX_DELAY_TICKS; got $ticks)")
        }
        if (queue.size >= ForgeLimits.PENDING_TIMERS) {
            throw ForgeRefusal("Timers.$method: ${ForgeLimits.PENDING_TIMERS} timers wait already (Limits.MAX_PENDING_TIMERS); cancel one first")
        }
        if (repeat && repeating >= ForgeLimits.REPEATING_TIMERS) {
            throw ForgeRefusal("Timers.every: ${ForgeLimits.REPEATING_TIMERS} timers repeat already (Limits.MAX_REPEATING_TIMERS); cancel one first")
        }
        val timer = Timer(++lastId, task, ticks, repeat, tick + ticks)
        queue += timer
        if (repeat) repeating++
        return timer
    }

    private enum class Phase { RUNNING, STOPPING, ENDED }

    /** A timer: its number in the deployment, the module's task (opaque), its period and when it is due next. */
    internal inner class Timer(val id: Int, val task: Runnable, val period: Int, val repeat: Boolean, var due: Long) : Scheduled {
        /** A one-time timer that came out of [next]. */
        var done = false
        var cancelled = false

        override fun cancel() {
            if (cancelled || done) return
            cancelled = true
            queue.removeIf { it === this }
            if (repeat) repeating--
        }

        override fun active(): Boolean = !cancelled && !done
    }

    /**
     * A timer due now. [run] runs the module's task: only inside a call armed by the host.
     *
     * @property label for the logs: `timer 3 (every 20 ticks)`.
     */
    class Due internal constructor(private val timer: Scheduler.Timer) {
        val label: String get() = "timer ${timer.id}" + if (timer.repeat) " (every ${timer.period} ticks)" else ""

        /** Runs the module's task, unless the timer was cancelled meanwhile. Inside an armed call only. */
        fun run() {
            if (!timer.cancelled) timer.task.run()
        }
    }
}
