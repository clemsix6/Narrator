package io.github.clemsix6.narrator2.core

import kotlinx.coroutines.future.await
import net.minecraft.util.thread.BlockableEventLoop
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The server thread did not run a task in time (overloaded or stuck). The task will not run later. */
class ServerThreadTimeoutException(timeoutMs: Long) :
    RuntimeException("the server thread did not run the task within $timeoutMs ms")

/** The server has stopped running tasks, so the task was not run. */
class ServerStoppedException : IllegalStateException("the server is stopped: the task was not run")

/**
 * Runs [block] on the server thread without waiting for it: at once when already there, else as a task
 * of the server. The block never runs on another thread: once the server has stopped, Minecraft runs a
 * submitted task on the caller's thread, and the block is dropped then (never submitted again, which would
 * loop for ever). Returns false when it was dropped. For what may be lost with a stopped server.
 */
fun BlockableEventLoop<*>.runOnServerThread(block: () -> Unit): Boolean {
    if (isSameThread) {
        block()
        return true
    }
    var dropped = false
    execute { if (isSameThread) block() else dropped = true }
    return !dropped
}

/**
 * Runs [block] on the server thread and suspends until it completes. Anything that touches the
 * world must go through here: Minecraft's game state is single-threaded.
 *
 * The block never runs on another thread: once the server has stopped, Minecraft runs submitted tasks
 * on the caller's thread, and this fails with [ServerStoppedException] instead. If the server thread
 * does not get to the block within [timeoutMs], this fails with [ServerThreadTimeoutException] and the
 * block is skipped when its turn comes. Cancelling the caller skips it too.
 */
suspend fun <T> BlockableEventLoop<*>.onServerThread(timeoutMs: Long = 5_000, block: () -> T): T {
    if (isSameThread) return block()
    val result = CompletableFuture<T>()
    execute {
        when {
            result.isDone -> Unit // timed out or cancelled: too late to run
            !isSameThread -> result.completeExceptionally(ServerStoppedException())
            else -> try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
    }
    return try {
        result.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).await()
    } catch (e: TimeoutException) {
        throw ServerThreadTimeoutException(timeoutMs)
    }
}
