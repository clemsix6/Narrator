package io.github.clemsix6.narrator2.director

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.util.thread.BlockableEventLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the Director shows things to the players ([showOnServer], used by `ServerGameView`): on the server
 * thread only, even once the server has stopped (M2 review, finding 17), against small event loops
 * standing in for the server.
 */
class ShowOnServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    @AfterEach
    fun close() = scope.cancel()

    @Test
    fun `once the server has stopped, nothing runs on the caller's thread`() {
        val loop = TestLoop(running = false)
        val ran = CopyOnWriteArrayList<Thread>()

        showOnServer(loop, scope, 1_000) { ran += Thread.currentThread() }
        Thread.sleep(50)
        assertEquals(emptyList(), ran, "the block was dropped, not run on ${Thread.currentThread()}")
    }

    @Test
    fun `from another thread, the blocks run on the server thread, in the order they were given`() {
        val loop = TestLoop()
        val shown = CopyOnWriteArrayList<Pair<String, Thread>>()

        showOnServer(loop, scope, 5_000) { shown += "thinking" to Thread.currentThread() }
        showOnServer(loop, scope, 5_000) { shown += "cleared" to Thread.currentThread() }
        assertTrue(shown.isEmpty(), "nothing runs before the server thread gets to it")

        val server = Thread { loop.runQueuedTasks() }
        server.start()
        server.join()
        assertEquals(listOf("thinking" to server, "cleared" to server), shown.toList())
    }

    @Test
    fun `on the server thread, the block runs at once`() {
        val loop = TestLoop()
        loop.thread = Thread.currentThread()
        val shown = CopyOnWriteArrayList<Thread>()

        showOnServer(loop, scope, 5_000) { shown += Thread.currentThread() }
        assertEquals(listOf(Thread.currentThread()), shown.toList(), "not deferred behind the task in progress")
    }
}
