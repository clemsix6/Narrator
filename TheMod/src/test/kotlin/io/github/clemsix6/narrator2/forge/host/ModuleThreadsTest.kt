package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.rt.ForgeLimit
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module threads (the supervisor's decision on finding 1 of M5-04): a call runs on a module thread, the caller
 * waits at most its budget and serves the call's SDK requests meanwhile; past the budget it abandons the call, whose
 * thread can no longer reach the SDK; at most two lost threads alive, past that the Forge is stuck.
 */
class ModuleThreadsTest {
    private val budget = 20_000_000L

    @Test
    fun `a call runs on a module thread, and its SDK requests on the caller's`() {
        val threads = ModuleThreads("test-module")
        val caller = Thread.currentThread()
        val ran = AtomicReference<Thread>()
        val served = AtomicReference<Thread>()
        try {
            val result = threads.run(budget) {
                ran.set(Thread.currentThread())
                served.set(ModuleThreads.toCaller { Thread.currentThread() })
            }
            assertEquals(ModuleThreads.Ran.DONE, result)
            assertNotEquals(caller, ran.get(), "not on the caller's thread")
            assertTrue(ran.get().name.startsWith("test-module-"), ran.get().name)
            assertTrue(ran.get().isDaemon, "a daemon")
            assertEquals(caller, served.get(), "the SDK request ran on the caller's thread")
            // The same live thread takes the next call.
            val again = AtomicReference<Thread>()
            threads.run(budget) { again.set(Thread.currentThread()) }
            assertEquals(ran.get(), again.get())
            // Off a module thread, an SDK call runs in place.
            assertEquals(caller, ModuleThreads.toCaller { Thread.currentThread() })
        } finally {
            threads.close()
        }
    }

    @Test
    fun `what an SDK request throws reaches the module's thread`() {
        val threads = ModuleThreads()
        val caught = AtomicReference<Throwable>()
        try {
            threads.run(budget) {
                try {
                    ModuleThreads.toCaller { throw IllegalArgumentException("refused") }
                } catch (thrown: IllegalArgumentException) {
                    caught.set(thrown)
                }
            }
            assertEquals("refused", caught.get()?.message)
        } finally {
            threads.close()
        }
    }

    @Test
    fun `past its budget a call is abandoned, the caller goes on, and its thread never reaches the SDK again`() {
        val threads = ModuleThreads("test-module")
        val release = CountDownLatch(1)
        val afterwards = AtomicReference<Throwable>()
        val ended = CountDownLatch(1)
        val served = AtomicInteger()
        val abandoned = AtomicReference<Thread>()
        try {
            val start = System.nanoTime()
            val result = threads.run(budget, onAbandon = { abandoned.set(it) }) {
                ModuleThreads.toCaller { served.incrementAndGet() }
                release.await(10, TimeUnit.SECONDS) // a JDK call that runs long
                try {
                    ModuleThreads.toCaller { served.incrementAndGet() }
                } catch (thrown: Throwable) {
                    afterwards.set(thrown)
                }
                ended.countDown()
            }
            val waited = (System.nanoTime() - start) / 1e6
            assertEquals(ModuleThreads.Ran.ABANDONED, result)
            assertTrue(waited >= 20 && waited < 25, "the caller waited its budget, no more: $waited ms")
            val lost = assertNotNull(abandoned.get())
            assertEquals(Thread.MIN_PRIORITY, lost.priority)
            assertTrue(lost.name.endsWith("(abandoned)"), lost.name)
            assertEquals(1, threads.lost.alive())

            // A fresh thread takes the next call at once.
            val next = AtomicReference<Thread>()
            assertEquals(ModuleThreads.Ran.DONE, threads.run(budget) { next.set(Thread.currentThread()) })
            assertNotEquals(lost, next.get())

            release.countDown()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertTrue(afterwards.get() is ForgeLimit, "the SDK refuses the abandoned thread: ${afterwards.get()}")
            assertEquals(ModuleThreads.ABANDONED_MESSAGE, afterwards.get()!!.message)
            assertEquals(1, served.get(), "nothing was served after the abandon")
            lost.join(5_000)
            assertEquals(0, threads.lost.alive(), "the lost thread ended")
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `an SDK request the caller has not served yet is refused when the call is abandoned`() {
        val threads = ModuleThreads()
        val refused = AtomicReference<Throwable>()
        val ended = CountDownLatch(1)
        val served = AtomicInteger()
        try {
            // The request itself runs past the budget on the caller's side: served once, then the call is abandoned
            // and the next request is refused without being run.
            val result = threads.run(budget) {
                ModuleThreads.toCaller { Thread.sleep(30) }
                try {
                    ModuleThreads.toCaller { served.incrementAndGet() }
                } catch (thrown: Throwable) {
                    refused.set(thrown)
                }
                ended.countDown()
            }
            assertEquals(ModuleThreads.Ran.ABANDONED, result)
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertTrue(refused.get() is ForgeLimit, "${refused.get()}")
            assertEquals(0, served.get())
        } finally {
            threads.close()
        }
    }

    @Test
    fun `past two lost threads alive the Forge is stuck, once, until the server restarts`() {
        val stuck = mutableListOf<String>()
        val lost = LostThreads(onStuck = { stuck += it })
        val threads = ModuleThreads("test-module", lost)
        val release = CountDownLatch(1)
        try {
            repeat(2) {
                assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget) { release.await(10, TimeUnit.SECONDS) })
            }
            assertEquals(2, lost.alive())
            assertNull(lost.stuckSince, "two lost threads: not stuck yet")
            assertEquals(ModuleThreads.Ran.DONE, threads.run(budget) { })

            assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget) { release.await(10, TimeUnit.SECONDS) })
            assertNotNull(lost.stuckSince, "a third one: stuck")
            assertEquals(1, stuck.size)
            assertTrue("3 module threads were abandoned" in stuck.single(), stuck.single())

            val ran = AtomicInteger()
            assertEquals(ModuleThreads.Ran.STUCK, threads.run(budget) { ran.incrementAndGet() })
            assertEquals(0, ran.get(), "no module code runs once stuck")

            release.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            while (lost.alive() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(0, lost.alive())
            assertTrue(lost.stuck, "stuck until the server restarts, even once the threads ended")
            assertEquals(ModuleThreads.Ran.STUCK, threads.run(budget) { ran.incrementAndGet() })
            assertEquals(1, stuck.size, "told once")
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `calls never overlap nor nest`() {
        val threads = ModuleThreads()
        val nested = AtomicReference<Throwable>()
        try {
            threads.run(budget) {
                try {
                    ModuleThreads.toCaller { threads.run(budget) { } }
                } catch (thrown: Throwable) {
                    nested.set(thrown)
                }
            }
            assertTrue(nested.get() is IllegalStateException, "a call made while another runs is refused: ${nested.get()}")
        } finally {
            threads.close()
        }
    }

    @Test
    fun `the hand-over costs about a microsecond, an SDK request about as much`() {
        val threads = ModuleThreads()
        try {
            repeat(20_000) { threads.run(budget) { ModuleThreads.toCaller { } } } // warm up
            val calls = LongArray(20_000)
            val requests = LongArray(20_000)
            for (i in calls.indices) {
                val start = System.nanoTime()
                threads.run(budget) { }
                calls[i] = System.nanoTime() - start
            }
            for (i in requests.indices) {
                var took = 0L
                threads.run(budget) {
                    val start = System.nanoTime()
                    ModuleThreads.toCaller { }
                    took = System.nanoTime() - start
                }
                requests[i] = took
            }
            calls.sort()
            requests.sort()
            fun us(nanos: Long) = "%.1f".format(nanos / 1e3)
            println(
                "[host] hand-over of an empty call: median ${us(calls[calls.size / 2])} µs, p99 ${us(calls[calls.size * 99 / 100])} µs; " +
                    "an SDK request from the module thread: median ${us(requests[requests.size / 2])} µs, p99 ${us(requests[requests.size * 99 / 100])} µs",
            )
            assertTrue(calls[calls.size / 2] < 50_000, "a hand-over in tens of µs at most: ${us(calls[calls.size / 2])} µs")
        } finally {
            threads.close()
        }
    }
}
