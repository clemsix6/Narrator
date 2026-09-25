package io.github.clemsix6.narrator2.core

import kotlinx.coroutines.runBlocking
import net.minecraft.util.thread.BlockableEventLoop
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [onServerThread] against small event loops standing in for the server; the real server is covered
 * by the GameTests.
 */
class ServerThreadTest {
    /** An event loop whose thread is [thread]; [running] false mimics a stopped MinecraftServer. */
    private class TestLoop(private val running: Boolean = true) : BlockableEventLoop<Runnable>("test-loop", false) {
        @Volatile
        var thread: Thread = Thread {}

        override fun wrapRunnable(runnable: Runnable) = runnable

        override fun shouldRun(task: Runnable) = true

        override fun getRunningThread(): Thread = thread

        // A stopped MinecraftServer answers false, and execute() then runs the task on the caller's thread.
        override fun scheduleExecutables(): Boolean = running && super.scheduleExecutables()

        /** Runs the queued tasks on the calling thread, as the server thread would. */
        fun runQueuedTasks() {
            thread = Thread.currentThread()
            runAllTasks()
        }
    }

    @Test
    fun `runs the block on the loop's thread and returns its value`() {
        val loop = TestLoop()
        val worker = Thread {
            loop.thread = Thread.currentThread()
            while (!Thread.currentThread().isInterrupted) {
                loop.runQueuedTasks()
                Thread.onSpinWait()
            }
        }
        worker.start()
        try {
            while (loop.thread !== worker) Thread.onSpinWait()
            val ranOn = runBlocking { loop.onServerThread { Thread.currentThread() } }
            assertEquals(worker, ranOn)
            val error = assertFailsWith<IllegalArgumentException> { runBlocking { loop.onServerThread { require(false) { "boom" } } } }
            assertEquals("boom", error.message)
        } finally {
            worker.interrupt()
        }
    }

    @Test
    fun `on the loop's own thread the block runs at once`() {
        val loop = TestLoop()
        loop.thread = Thread.currentThread()
        assertEquals(42, runBlocking { loop.onServerThread { 42 } })
    }

    @Test
    fun `a stopped server fails clearly and never runs the block on another thread`() {
        val loop = TestLoop(running = false)
        val ran = AtomicBoolean(false)

        assertFailsWith<ServerStoppedException> { runBlocking { loop.onServerThread { ran.set(true) } } }
        assertFalse(ran.get())
    }

    @Test
    fun `without waiting, a block runs at once on the loop's thread, later from another`() {
        val loop = TestLoop()
        val ran = mutableListOf<Thread>()
        loop.thread = Thread.currentThread()
        assertTrue(loop.runOnServerThread { ran += Thread.currentThread() })
        assertEquals(listOf(Thread.currentThread()), ran, "at once, not behind the task in progress")

        loop.thread = Thread {}
        assertTrue(loop.runOnServerThread { ran += Thread.currentThread() }, "queued")
        assertEquals(1, ran.size, "not run on the caller's thread")
        val server = Thread { loop.runQueuedTasks() }
        server.start()
        server.join()
        assertEquals(listOf(Thread.currentThread(), server), ran)
    }

    @Test
    fun `without waiting, a stopped server drops the block instead of looping or running it elsewhere`() {
        val loop = TestLoop(running = false)
        val ran = AtomicBoolean(false)

        // The death guard's old way (M2-fix-EF, limits): off the server thread, queue the same call again. A
        // stopped server runs a task on the caller's thread at once, so it called itself for ever (5 times here).
        fun oldArm(depth: Int): Int {
            if (loop.isSameThread || depth == 5) return depth
            var reached = depth
            loop.execute { reached = oldArm(depth + 1) }
            return reached
        }
        assertEquals(5, oldArm(0), "the old way never ends")

        assertFalse(loop.runOnServerThread { ran.set(true) }, "dropped")
        assertFalse(ran.get(), "never run on the caller's thread")
    }

    @Test
    fun `a timeout is an ordinary exception, and the late block is skipped`() {
        val loop = TestLoop()
        val ran = AtomicBoolean(false)

        // A ServerThreadTimeoutException is not a CancellationException, so it cannot silently cancel the caller.
        val error = assertFailsWith<ServerThreadTimeoutException> {
            runBlocking { loop.onServerThread(timeoutMs = 50) { ran.set(true) } }
        }
        loop.runQueuedTasks()
        assertFalse(ran.get(), "the block did not run after its timeout")
        assertTrue(error.message!!.contains("50 ms"))
    }
}
