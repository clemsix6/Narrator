package io.github.clemsix6.narrator2.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.minecraft.network.chat.Component
import net.minecraft.util.thread.BlockableEventLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The answers of the `/narrator` commands that work off the server thread ([replyOn], behind
 * `replyLater`): sent on the server thread only, never from another once the server has stopped (M2
 * review, finding 17), against small event loops standing in for the server.
 */
class RepliesTest {
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
        fun runQueuedTasks() = runAllTasks()
    }

    @AfterEach
    fun close() = scope.cancel()

    @Test
    fun `the answer is sent on the server thread`() = runBlocking {
        val loop = TestLoop()
        val server = Thread {
            while (!Thread.currentThread().isInterrupted) {
                loop.runQueuedTasks()
                Thread.onSpinWait()
            }
        }
        loop.thread = server
        server.start()
        try {
            val sent = CopyOnWriteArrayList<Pair<String, Thread>>()
            replyOn(loop, scope, "usage", { sent += it.string to Thread.currentThread() }) { listOf(Component.literal("Usage: 3 calls")) }.join()
            assertEquals(listOf("Usage: 3 calls" to server), sent.toList())
        } finally {
            server.interrupt()
        }
    }

    @Test
    fun `once the server has stopped, the answer is dropped, never sent from another thread`() = runBlocking {
        val loop = TestLoop(running = false)
        val sent = CopyOnWriteArrayList<Thread>()
        replyOn(loop, scope, "reload", { sent += Thread.currentThread() }) { listOf(Component.literal("reloaded")) }.join()
        replyOn(loop, scope, "ping", { sent += Thread.currentThread() }) { error("the gateway is down") }.join()
        assertEquals(emptyList(), sent.toList(), "not sent on ${sent.toList()}")
    }
}
