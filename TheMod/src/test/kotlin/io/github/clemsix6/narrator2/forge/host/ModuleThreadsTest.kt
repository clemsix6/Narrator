package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.rt.ForgeLimit
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module threads (the supervisor's decision on finding 1 of M5-04): a call runs on a module thread, the caller
 * waits at most its budget and serves the call's SDK requests meanwhile; past the budget it abandons the call, whose
 * thread can no longer reach the SDK; at most two lost threads alive, past that the Forge is stuck.
 *
 * The budgets are a busy machine's ([BusyMachine]): [patient] for a call that ends at once (never late to start), and
 * [budget] for a call to be given up, long enough that a busy machine always starts it before its end (a call not even
 * started is [ModuleThreads.Ran.LATE], not abandoned). The game's own, 20 ms and 5 more, are `AbandonTest`'s.
 */
class ModuleThreadsTest {
    private val patient = BusyMachine.PATIENT_NANOS
    private val budget = BusyMachine.SLACK_NANOS
    private val budgetMillis = budget / 1_000_000

    @Test
    fun `a call runs on a module thread, and its SDK requests on the caller's`() {
        val threads = ModuleThreads("test-module")
        val caller = Thread.currentThread()
        val ran = AtomicReference<Thread>()
        val served = AtomicReference<Thread>()
        try {
            val result = threads.run(patient) {
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
            threads.run(patient) { again.set(Thread.currentThread()) }
            assertEquals(ran.get(), again.get())
            // Off a module thread, an SDK call runs in place.
            assertEquals(caller, ModuleThreads.toCaller { Thread.currentThread() })
        } finally {
            threads.close()
        }
    }

    @Test
    fun `the module thread runs at its caller's priority`() {
        // The server thread is at 8 on a machine of more than four cores (MinecraftServer.spin): its module's call is
        // its own work, never held back by the threads it outranks (M5-LOAD).
        val threads = ModuleThreads("test-module")
        val seen = AtomicInteger()
        try {
            Thread { threads.run(patient) { seen.set(Thread.currentThread().priority) } }.apply {
                priority = 8
                start()
                join(60_000)
            }
            assertEquals(8, seen.get())
            threads.run(patient) { seen.set(Thread.currentThread().priority) }
            assertEquals(Thread.currentThread().priority, seen.get())
        } finally {
            threads.close()
        }
    }

    @Test
    fun `what an SDK request throws reaches the module's thread`() {
        val threads = ModuleThreads()
        val caught = AtomicReference<Throwable>()
        try {
            threads.run(patient) {
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
                release.await(60, TimeUnit.SECONDS) // a JDK call that runs long
                try {
                    ModuleThreads.toCaller { served.incrementAndGet() }
                } catch (thrown: Throwable) {
                    afterwards.set(thrown)
                }
                ended.countDown()
            }
            val waited = (System.nanoTime() - start) / 1e6
            assertEquals(ModuleThreads.Ran.ABANDONED, result)
            // Never before its budget; after it, only what a busy machine adds (the call itself would wait a minute).
            assertTrue(waited >= budgetMillis && waited < budgetMillis + BusyMachine.SLACK_MILLIS, "the caller waited its budget, no more: $waited ms")
            val lost = assertNotNull(abandoned.get())
            // The normal priority, not the lowest: a busy machine would starve it while every pause of the JVM waits for it.
            assertEquals(Thread.NORM_PRIORITY, lost.priority)
            assertTrue(lost.name.endsWith("(abandoned)"), lost.name)
            assertEquals(1, threads.lost.alive())

            // A fresh thread takes the next call at once.
            val next = AtomicReference<Thread>()
            assertEquals(ModuleThreads.Ran.DONE, threads.run(patient) { next.set(Thread.currentThread()) })
            assertNotEquals(lost, next.get())

            release.countDown()
            // A busy machine may keep the lost thread waiting for seconds.
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertTrue(afterwards.get() is ForgeLimit, "the SDK refuses the abandoned thread: ${afterwards.get()}")
            assertEquals(ModuleThreads.ABANDONED_MESSAGE, afterwards.get()!!.message)
            assertEquals(1, served.get(), "nothing was served after the abandon")
            lost.join(60_000)
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
                ModuleThreads.toCaller { Thread.sleep(budgetMillis + 10) }
                try {
                    ModuleThreads.toCaller { served.incrementAndGet() }
                } catch (thrown: Throwable) {
                    refused.set(thrown)
                }
                ended.countDown()
            }
            assertEquals(ModuleThreads.Ran.ABANDONED, result)
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertTrue(refused.get() is ForgeLimit, "${refused.get()}")
            assertEquals(0, served.get())
        } finally {
            threads.close()
        }
    }

    @Test
    fun `a call given up and held asks the SDK only when its caller resumes it, on the caller's thread, until it ends`() {
        val threads = ModuleThreads("test-module")
        val caller = Thread.currentThread()
        val release = CountDownLatch(1)
        val served = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        var held: ModuleThreads.Held? = null
        try {
            val result = threads.run(budget, hold = { held = it }) {
                served += ModuleThreads.toCaller { "first on ${Thread.currentThread() == caller}" }
                release.await(60, TimeUnit.SECONDS) // held back past the caller's deadline (the machine's doing)
                served += ModuleThreads.toCaller { "second on ${Thread.currentThread() == caller}" }
                served += ModuleThreads.toCaller { "third on ${Thread.currentThread() == caller}" }
                ended.countDown()
            }
            assertEquals(ModuleThreads.Ran.ABANDONED, result)
            val call = assertNotNull(held)
            assertEquals(0, threads.lost.alive(), "held, not lost")
            assertTrue(call.thread.name.endsWith("(given up)"), call.thread.name)
            assertEquals(ModuleThreads.Ran.DONE, threads.run(patient) { }, "a fresh thread takes the next calls meanwhile")

            release.countDown()
            val asking = System.nanoTime() + 60_000_000_000L
            while (!call.asking) check(System.nanoTime() < asking) { "the held call never asked" }
            assertEquals(listOf("first on true"), served.toList(), "its request waits: never served behind the caller's back")
            assertFalse(call.ended)

            assertEquals(ModuleThreads.Ran.DONE, threads.resume(call, patient))
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertTrue(call.ended)
            assertEquals(listOf("first on true", "second on true", "third on true"), served.toList(), "the rest of the call, served on the caller's thread")
            assertEquals(ModuleThreads.Ran.DONE, threads.resume(call, patient), "a call that ended is not resumed")
            call.thread.join(60_000)
            assertEquals(ModuleThreads.Ran.DONE, threads.run(patient) { }, "the given-up thread ended with its call, the next calls go on")
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `a held call cut is refused by the SDK, its request waiting and every later one`() {
        val threads = ModuleThreads("test-module")
        val release = CountDownLatch(1)
        val refused = CopyOnWriteArrayList<Throwable>()
        val served = AtomicInteger()
        val ended = CountDownLatch(1)
        var held: ModuleThreads.Held? = null
        try {
            assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget, hold = { held = it }) {
                release.await(60, TimeUnit.SECONDS)
                repeat(2) {
                    try {
                        ModuleThreads.toCaller { served.incrementAndGet() }
                    } catch (thrown: Throwable) {
                        refused += thrown
                    }
                }
                ended.countDown()
            })
            val call = assertNotNull(held)
            release.countDown()
            val asking = System.nanoTime() + 60_000_000_000L
            while (!call.asking) check(System.nanoTime() < asking) { "the held call never asked" }

            threads.cut(call)
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertEquals(0, served.get(), "nothing served once cut")
            assertEquals(2, refused.size)
            assertTrue(refused.all { it is ForgeLimit && it.message == ModuleThreads.ABANDONED_MESSAGE }, "$refused")
            assertEquals(ModuleThreads.Ran.ABANDONED, threads.resume(call, patient), "a call cut is never resumed")
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `a held call given up again as it is resumed stays held, and a later resume runs it to its end`() {
        val threads = ModuleThreads("test-module")
        val release = CountDownLatch(1)
        val served = CopyOnWriteArrayList<String>()
        var held: ModuleThreads.Held? = null
        try {
            assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget, hold = { held = it }) {
                release.await(60, TimeUnit.SECONDS)
                served += ModuleThreads.toCaller { Thread.sleep(RESUME_MILLIS * 10); "slow" } // served past the resume's budget
                served += ModuleThreads.toCaller { "last" }
            })
            val call = assertNotNull(held)
            release.countDown()
            awaitAsking(call)

            assertEquals(ModuleThreads.Ran.ABANDONED, threads.resume(call, RESUME_MILLIS * 1_000_000), "given up again once its request was served")
            assertTrue(threads.served > RESUME_MILLIS * 1_000_000, "what this resume served, past its budget: ${threads.served} ns")
            awaitAsking(call)
            assertFalse(call.ended, "held still")
            assertEquals(listOf("slow"), served.toList())
            assertEquals(0, threads.lost.alive(), "held, not lost")

            assertEquals(ModuleThreads.Ran.DONE, threads.resume(call, patient))
            assertTrue(call.ended)
            assertEquals(listOf("slow", "last"), served.toList())
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `a call cut while it is resumed is given up at once, and the SDK refuses it from then on`() {
        // Its end comes while its caller serves it (FORGE-ABANDON review, finding 7): cut held or resumed alike.
        val threads = ModuleThreads("test-module")
        val release = CountDownLatch(1)
        val served = CopyOnWriteArrayList<String>()
        val refused = AtomicReference<Throwable>()
        val ended = CountDownLatch(1)
        var held: ModuleThreads.Held? = null
        try {
            assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget, hold = { held = it }) {
                release.await(60, TimeUnit.SECONDS)
                served += ModuleThreads.toCaller { threads.cut(checkNotNull(held)); "cut" }
                try {
                    served += ModuleThreads.toCaller { "after" }
                } catch (thrown: Throwable) {
                    refused.set(thrown)
                }
                ended.countDown()
            })
            val call = assertNotNull(held)
            release.countDown()
            awaitAsking(call)

            assertEquals(ModuleThreads.Ran.ABANDONED, threads.resume(call, patient), "cut while resumed: its caller goes on at once")
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertEquals(listOf("cut"), served.toList(), "nothing served once cut")
            assertTrue(refused.get() is ForgeLimit && refused.get()!!.message == ModuleThreads.ABANDONED_MESSAGE, "${refused.get()}")
            assertEquals(ModuleThreads.Ran.ABANDONED, threads.resume(call, patient), "a call cut is never resumed")
        } finally {
            release.countDown()
            threads.close()
        }
    }

    /** Waits until [call]'s thread waits for an SDK request. */
    private fun awaitAsking(call: ModuleThreads.Held) {
        val asking = System.nanoTime() + 60_000_000_000L
        while (!call.asking) check(System.nanoTime() < asking) { "the held call never asked" }
    }

    @Test
    fun `past two lost threads alive the Forge is stuck, once, until the server restarts`() {
        val stuck = mutableListOf<String>()
        val lost = LostThreads(onStuck = { stuck += it })
        val threads = ModuleThreads("test-module", lost)
        val release = CountDownLatch(1)
        try {
            repeat(2) {
                assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget) { release.await(60, TimeUnit.SECONDS) })
            }
            assertEquals(2, lost.alive())
            assertNull(lost.stuckSince, "two lost threads: not stuck yet")
            assertEquals(ModuleThreads.Ran.DONE, threads.run(patient) { })

            assertEquals(ModuleThreads.Ran.ABANDONED, threads.run(budget) { release.await(60, TimeUnit.SECONDS) })
            assertNotNull(lost.stuckSince, "a third one: stuck")
            assertEquals(1, stuck.size)
            assertTrue("3 module threads were abandoned" in stuck.single(), stuck.single())

            val ran = AtomicInteger()
            assertEquals(ModuleThreads.Ran.STUCK, threads.run(budget) { ran.incrementAndGet() })
            assertEquals(0, ran.get(), "no module code runs once stuck")

            release.countDown()
            val deadline = System.currentTimeMillis() + 60_000
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
    fun `calls never overlap, and one made from the module thread nests right there`() {
        val threads = ModuleThreads()
        val overlapping = AtomicReference<Throwable>()
        val outer = AtomicReference<Thread>()
        val inner = AtomicReference<Thread>()
        val served = AtomicReference<Thread>()
        try {
            threads.run(patient) {
                try {
                    ModuleThreads.toCaller { threads.run(patient) { } }
                } catch (thrown: Throwable) {
                    overlapping.set(thrown)
                }
                outer.set(Thread.currentThread())
                val ran = threads.run(patient) {
                    inner.set(Thread.currentThread())
                    served.set(ModuleThreads.toCaller { Thread.currentThread() })
                }
                check(ran == ModuleThreads.Ran.DONE)
            }
            assertTrue(overlapping.get() is IllegalStateException, "a call made by the caller while another runs is refused: ${overlapping.get()}")
            assertEquals(outer.get(), inner.get(), "the nested call ran on the module thread, no new hand-over")
            assertEquals(Thread.currentThread(), served.get(), "and its SDK requests still reach the caller")
        } finally {
            threads.close()
        }
    }

    @Test
    fun `a call nested in an abandoned one throws, before and after its body`() {
        val threads = ModuleThreads()
        val release = CountDownLatch(1)
        val refused = AtomicReference<Throwable>()
        val ended = CountDownLatch(1)
        val ran = AtomicInteger()
        try {
            assertEquals(
                ModuleThreads.Ran.ABANDONED,
                threads.run(budget) {
                    release.await(60, TimeUnit.SECONDS)
                    try {
                        threads.run(budget) { ran.incrementAndGet() }
                    } catch (thrown: Throwable) {
                        refused.set(thrown)
                    }
                    ended.countDown()
                },
            )
            release.countDown()
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertTrue(refused.get() is ForgeLimit, "${refused.get()}")
            assertEquals(0, ran.get())
        } finally {
            release.countDown()
            threads.close()
        }
    }

    @Test
    fun `the hand-over costs about a microsecond, an SDK request about as much`() {
        val threads = ModuleThreads()
        try {
            repeat(20_000) { threads.run(patient) { ModuleThreads.toCaller { } } } // warm up
            val calls = LongArray(20_000)
            val requests = LongArray(20_000)
            for (i in calls.indices) {
                val start = System.nanoTime()
                threads.run(patient) { }
                calls[i] = System.nanoTime() - start
            }
            for (i in requests.indices) {
                var took = 0L
                threads.run(patient) {
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

    private companion object {
        /** A resume's budget, for a request served past it (a resume is never late: its call runs already). */
        const val RESUME_MILLIS = 2L
    }
}
