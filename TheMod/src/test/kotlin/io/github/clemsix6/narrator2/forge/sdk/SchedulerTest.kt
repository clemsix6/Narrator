package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Scheduled
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deployment's timers (M5 §4.2; task M5-06): when they run and in which order, repeating ones, cancellations, the
 * limits, the module's stop and end, and the module's tasks kept opaque (design review, finding 8).
 */
class SchedulerTest {
    private val scheduler = Scheduler()
    private val ran = mutableListOf<String>()
    private var armed = false

    /** A task that records its runs, inside an armed call only, and that no host may ever ask for equals, hashCode or toString. */
    private inner class Task(val name: String, val then: () -> Unit = {}) : Runnable {
        override fun run() {
            check(armed) { "a task ran outside an armed call" }
            ran += "$name@$tick"
            then()
        }

        override fun equals(other: Any?): Boolean = error("the host called equals on a module's object")

        override fun hashCode(): Int = error("the host called hashCode on a module's object")

        override fun toString(): String = error("the host called toString on a module's object")
    }

    private var tick = 0

    /** [n] ticks as the engine runs them: advance, then each due timer in an armed call of its own. */
    private fun ticks(n: Int) = repeat(n) {
        tick++
        scheduler.advance()
        while (true) {
            val due = scheduler.next() ?: break
            armed = true
            try {
                due.run()
            } finally {
                armed = false
            }
        }
    }

    @Test
    fun `the tasks of the timers waiting are the memory gauge's roots`() {
        val once = Runnable { }
        val again = Runnable { }
        scheduler.after(1, once)
        scheduler.every(5, again)
        assertEquals(2, scheduler.tasks().size)
        scheduler.advance()
        scheduler.next()!!.run()
        assertTrue(scheduler.tasks().single() === again, "a one-time timer that ran is let go")
        scheduler.close()
        assertEquals(emptyList(), scheduler.tasks())
    }

    @Test
    fun `a timer runs once, its delay after it was made, and repeating ones again each period, in the order they are due then made`() {
        val once = scheduler.after(3, Task("once"))
        scheduler.every(2, Task("every"))
        scheduler.after(2, Task("two"))
        assertTrue(once.active())
        ticks(6)
        assertEquals(listOf("every@2", "two@2", "once@3", "every@4", "every@6"), ran)
        assertFalse(once.active(), "a one-time timer that ran")
        assertEquals(1, scheduler.pending, "the repeating one waits")
    }

    @Test
    fun `a timer made by a timer counts from the tick it was made in, and runs one tick later at the soonest`() {
        scheduler.after(1, Task("first") { scheduler.after(1, Task("second")) })
        scheduler.every(1, Task("each"))
        ticks(3)
        assertEquals(listOf("first@1", "each@1", "each@2", "second@2", "each@3"), ran, "at tick 2, the repeating one was made first")
    }

    @Test
    fun `a cancelled timer never runs again, even one due in the same tick, and frees its place`() {
        lateinit var victim: Scheduled
        scheduler.after(5, Task("killer") { victim.cancel() })
        victim = scheduler.every(5, Task("victim"))
        val repeating = scheduler.every(1, Task("self"))
        repeating.cancel()
        repeating.cancel()
        ticks(10)
        assertEquals(listOf("killer@5"), ran)
        assertFalse(victim.active())
        assertEquals(0, scheduler.pending)

        val self = arrayOfNulls<Scheduled>(1)
        self[0] = scheduler.every(1, Task("self") { self[0]!!.cancel() })
        ticks(3)
        assertEquals(listOf("killer@5", "self@11"), ran, "a timer may cancel itself")
    }

    @Test
    fun `delays, waiting timers and repeating timers have their limits`() {
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.after(0, Task("x")) }.message.orEmpty(), "1 to 72000 ticks")
        assertFailsWith<ForgeRefusal> { scheduler.every(ForgeLimits.TIMER_MAX_TICKS + 1, Task("x")) }
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.after(1, null) }.message.orEmpty(), "task")
        scheduler.after(ForgeLimits.TIMER_MAX_TICKS, Task("an hour"))

        val repeating = (1 until ForgeLimits.REPEATING_TIMERS + 1).map { scheduler.every(10, Task("r$it")) }
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.every(10, Task("one too many")) }.message.orEmpty(), "16 timers repeat already")
        repeating.first().cancel()
        scheduler.every(10, Task("again"))

        repeat(ForgeLimits.PENDING_TIMERS - scheduler.pending) { scheduler.after(100, Task("w$it")) }
        assertEquals(ForgeLimits.PENDING_TIMERS, scheduler.pending)
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.after(100, Task("one too many")) }.message.orEmpty(), "64 timers wait already")
        ticks(100)
        scheduler.after(1, Task("a one-time timer that ran frees its place"))
    }

    @Test
    fun `while the module stops no timer is made, and once it ended none runs and every method refuses`() {
        val timer = scheduler.every(1, Task("t"))
        scheduler.stopping()
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.after(1, Task("x")) }.message.orEmpty(), "while the module stops")
        scheduler.close()
        assertFalse(timer.active())
        ticks(3)
        assertEquals(emptyList(), ran)
        assertNull(scheduler.next())
        assertContains(assertFailsWith<ForgeRefusal> { scheduler.every(1, Task("x")) }.message.orEmpty(), "has ended")
        timer.cancel()
    }

    @Test
    fun `a due timer has a label for the logs that tells nothing of the module's object`() {
        scheduler.every(20, Task("t"))
        scheduler.after(20, Task("u"))
        repeat(20) { scheduler.advance() }
        assertEquals("timer 1 (every 20 ticks)", scheduler.next()?.label)
        assertEquals("timer 2", scheduler.next()?.label)
        assertNull(scheduler.next())
    }
}
