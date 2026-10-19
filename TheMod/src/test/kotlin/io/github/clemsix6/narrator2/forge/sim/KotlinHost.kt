package io.github.clemsix6.narrator2.forge.sim

import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [BenchHost] for modules written in Kotlin (or compiled by the tests, not rewritten): no loader of its own, no
 * meter, but each call on a module thread of its own (a pool), never the caller's, as M5-05's host does. The caller
 * waits [wait] for it (a budget of 20 ms waits 10 s by default: a busy machine never abandons a test's call), then
 * abandons it. A call's time is the module thread's CPU time; its fault the exception's class, message and the
 * module's line, as the game's host keeps it. The module's frames are those of [main] and its nested classes.
 * [retained] is a small reflective estimate in the spirit of the game's gauge: texts, arrays, the JDK's collections and
 * maps, and the fields of the module's own objects, from the roots and [main]'s static fields; any other object counts
 * 16 bytes and is not followed (a module's own collection is never asked anything).
 */
internal class KotlinHost(override val main: Class<*>, private val wait: (Long) -> Long = { maxOf(it, PATIENCE) }) : BenchHost {
    private val threads = ManagementFactory.getThreadMXBean()

    /** How many calls ran. */
    val calls = AtomicInteger()

    override fun call(budgetNanos: Long, body: () -> Unit): HostCall {
        calls.incrementAndGet()
        val waiting = wait(budgetNanos)
        val future = POOL.submit(
            Callable {
                DEPTH.set(DEPTH.get() + 1)
                val start = threads.currentThreadCpuTime
                try {
                    body()
                    HostCall(threads.currentThreadCpuTime - start, null)
                } catch (error: Throwable) {
                    val line = line(error)
                    HostCall(threads.currentThreadCpuTime - start, error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "") + where(error), line)
                } finally {
                    DEPTH.set(DEPTH.get() - 1)
                }
            },
        )
        return try {
            future.get(waiting, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            HostCall(waiting, "ForgeLimit: time budget (the call was abandoned)")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            HostCall(0, "the caller was interrupted")
        }
    }

    override fun line(error: Throwable): Int = frame(error)?.lineNumber ?: 0

    private fun where(error: Throwable): String = frame(error)?.let { " at ${it.fileName}:${it.lineNumber}" }.orEmpty()

    private fun frame(error: Throwable): StackTraceElement? =
        error.stackTrace.firstOrNull { it.className == main.name || it.className.startsWith(main.name + "$") }

    override fun retained(roots: List<Any>): Long {
        val seen = IdentityHashMap<Any, Boolean>()
        val pending = ArrayDeque<Any>(roots)
        main.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.trySetAccessible() }.mapNotNullTo(pending) { it.get(null) }
        var bytes = 0L
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (seen.put(item, true) != null) continue
            bytes += when (item) {
                is String -> TEXT + 2L * item.length
                is CharArray -> ARRAY + 2L * item.size
                is ByteArray -> ARRAY + item.size
                is IntArray -> ARRAY + 4L * item.size
                is LongArray -> ARRAY + 8L * item.size
                is DoubleArray -> ARRAY + 8L * item.size
                is Array<*> -> {
                    item.filterNotNullTo(pending)
                    ARRAY + REFERENCE * item.size
                }
                is Collection<*> if trusted(item) -> {
                    item.filterNotNullTo(pending)
                    OBJECT + REFERENCE * item.size
                }
                is Map<*, *> if trusted(item) -> {
                    item.keys.filterNotNullTo(pending)
                    item.values.filterNotNullTo(pending)
                    OBJECT + 2 * REFERENCE * item.size
                }
                else -> if (item.javaClass.name.startsWith(main.name)) fields(item, pending) else OBJECT
            }
        }
        return bytes
    }

    /** A collection of the JDK or of Kotlin, walked through its API; a module's own one (it may loop in `size`) only by its fields. */
    private fun trusted(item: Any): Boolean = item.javaClass.name.let { it.startsWith("java.") || it.startsWith("kotlin.") }

    private fun fields(item: Any, pending: ArrayDeque<Any>): Long {
        val fields = generateSequence<Class<*>>(item.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.trySetAccessible() }
            .toList()
        fields.mapNotNullTo(pending) { it.get(item) }
        return OBJECT + REFERENCE * fields.size
    }

    companion object {
        private val POOL = Executors.newCachedThreadPool { task -> Thread(task, "bench-module").apply { isDaemon = true } }
        private val DEPTH = ThreadLocal.withInitial { 0 }
        private val PATIENCE = TimeUnit.SECONDS.toNanos(10)

        /** Whether the module's code runs in a call of a [KotlinHost] on this thread. */
        val inCall: Boolean get() = DEPTH.get() > 0

        private const val OBJECT = 16L
        private const val ARRAY = 16L
        private const val TEXT = 40L
        private const val REFERENCE = 8L
    }
}
