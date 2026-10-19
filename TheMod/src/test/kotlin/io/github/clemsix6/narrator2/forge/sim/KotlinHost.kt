package io.github.clemsix6.narrator2.forge.sim

import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * A [BenchHost] for modules written in Kotlin (or compiled by the tests, not rewritten): no loader of its own, no
 * meter. A call's time is the thread's CPU time (a GC pause or a busy machine never makes a test's call slow), its
 * fault the exception's class, message and the module's line, as the game's host keeps it. The module's frames are
 * those of [main] and its nested classes. [retained] is a small reflective estimate in the spirit of the game's gauge:
 * texts, arrays, collections and maps, and the fields of the module's own objects, from the roots and [main]'s
 * static fields; any other object counts 16 bytes and is not followed.
 */
internal class KotlinHost(override val main: Class<*>) : BenchHost {
    private val threads = ManagementFactory.getThreadMXBean()

    /** How many calls ran. */
    var calls = 0
        private set

    override fun call(budgetNanos: Long, body: () -> Unit): HostCall {
        calls++
        val depth = DEPTH.get()
        DEPTH.set(depth + 1)
        val start = threads.currentThreadCpuTime
        val fault = try {
            body()
            null
        } catch (error: Throwable) {
            error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "") + (where(error)?.let { " at $it" } ?: "")
        } finally {
            DEPTH.set(depth)
        }
        return HostCall(threads.currentThreadCpuTime - start, fault)
    }

    override fun where(error: Throwable): String? =
        error.stackTrace.firstOrNull { it.className == main.name || it.className.startsWith(main.name + "$") }?.let { "${it.fileName}:${it.lineNumber}" }

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
        private val DEPTH = ThreadLocal.withInitial { 0 }

        /** Whether the module's code runs in a call of a [KotlinHost] on this thread. */
        val inCall: Boolean get() = DEPTH.get() > 0

        private const val OBJECT = 16L
        private const val ARRAY = 16L
        private const val TEXT = 40L
        private const val REFERENCE = 8L
    }
}
