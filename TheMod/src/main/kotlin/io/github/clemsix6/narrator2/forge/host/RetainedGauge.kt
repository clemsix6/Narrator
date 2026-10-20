package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * The gauge of what one module retains (M5 §3.7, design review finding 9): from its roots (its instance, the static
 * fields of its classes, the lambdas it handed to the SDK), it walks what the module keeps and estimates its size —
 * a text 2 bytes a character, an array its element's size, an object 16 bytes and 8 a field, a collection 8 bytes an
 * element, a map 16 an entry.
 *
 * - The module's own objects ([loader]'s classes, its lambdas included) are walked by their fields (reflection on
 *   **its** classes only); the JDK's collections, maps, `Optional` and `AtomicReference` by their public API, which
 *   never calls the module's code (no class of a module may be one of them: the guard refuses it); texts, boxes and
 *   arrays by their size. Anything else — the JDK's other objects, the SDK's and the mod's (a handle, a proxy, a
 *   record, a view of `Guarded`) — counts as one small object and is not walked: what the mod holds is not the
 *   module's to pay for.
 * - Each object counts once ([IdentityHashMap] of the objects seen): a cycle ends, and a structure shared ten thousand
 *   times (finding 1 of M5-04) is walked once.
 * - **Its ceilings are work, not time** (M5-LOAD): past [maxObjects] objects, or past [maxBytes] estimated, the walk
 *   stops ([Measure.complete] false) and the verdict is known (« retains too much to measure », « retains about … »).
 *   Every reference it follows was paid 8 bytes by what holds it, so a walk follows at most `maxBytes / 8` references
 *   (4 Mi) and sees at most [maxObjects] objects, whatever the machine does: a slow or busy machine makes it slower,
 *   never wrong. The host runs it on the module thread, where the server never waits for it past a call's budget.
 *
 * Never calls `hashCode`, `equals` nor `toString` of anything, nor any code of the module: [statics], which may
 * initialize a class of the module (its static initializer is the module's code), comes apart, inside a metered call.
 */
class RetainedGauge(
    private val loader: ClassLoader,
    private val maxObjects: Int = ForgeLimits.GAUGE_MAX_OBJECTS,
    private val maxBytes: Long = ForgeLimits.RETAINED_BYTES,
) {
    /** [bytes] estimated over [objects] distinct objects in [nanos]; [complete] false when a ceiling stopped the walk. */
    data class Measure(val bytes: Long, val objects: Int, val nanos: Long, val complete: Boolean)

    /** A class of the module: how many instance fields it has, and those that hold a reference (readable). */
    private class Shape(val fields: Int, val references: List<Field>)

    private val shapes = HashMap<Class<*>, Shape>()

    /**
     * The values of the static reference fields of [classes] that are the module's ([loader]'s). Reading one initializes
     * its class when it is not yet: the module's static initializer runs, so the host calls this inside a metered call.
     */
    fun statics(classes: Collection<Class<*>>): List<Any?> = classes.filter { it.classLoader === loader }.flatMap { type ->
        type.declaredFields.filter { Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.trySetAccessible() }.map { it.get(null) }
    }

    /** What [roots] retain (the module's instance, what it handed to the SDK, its [statics]): the walk, no code of the module. */
    fun measure(roots: Collection<Any?>): Measure {
        val start = System.nanoTime()
        val seen = IdentityHashMap<Any, Unit>()
        val pending = ArrayDeque<Any>()
        var bytes = 0L
        val visit = { value: Any? -> if (value != null && seen.put(value, Unit) == null) pending.addLast(value) }

        roots.forEach(visit)
        while (true) {
            val value = pending.removeFirstOrNull() ?: break
            bytes += size(value, visit)
            if (seen.size > maxObjects || bytes > maxBytes) return Measure(bytes, seen.size, System.nanoTime() - start, complete = false)
        }
        return Measure(bytes, seen.size, System.nanoTime() - start, complete = true)
    }

    /** [value]'s own size; what it holds goes to [visit]. */
    private fun size(value: Any, visit: (Any?) -> Unit): Long {
        val type = value.javaClass
        return when {
            type.classLoader === loader -> {
                val shape = shapeOf(type)
                shape.references.forEach { visit(it.get(value)) }
                OBJECT + FIELD * shape.fields
            }
            value is String -> OBJECT + 2L * value.length
            value is StringBuilder -> OBJECT + 2L * value.capacity()
            type.isArray -> array(value, visit)
            !jdk(type) -> OBJECT
            value is Collection<*> -> {
                value.forEach(visit)
                OBJECT + FIELD * value.size
            }
            value is Map<*, *> -> {
                value.forEach { (key, item) ->
                    visit(key)
                    visit(item)
                }
                OBJECT + 2 * FIELD * value.size
            }
            value is Optional<*> -> {
                visit(value.orElse(null))
                OBJECT
            }
            value is AtomicReference<*> -> {
                visit(value.get())
                OBJECT
            }
            else -> OBJECT
        }
    }

    private fun array(value: Any, visit: (Any?) -> Unit): Long {
        val element = value.javaClass.componentType
        if (!element.isPrimitive) {
            @Suppress("UNCHECKED_CAST")
            val items = value as Array<Any?>
            items.forEach(visit)
            return OBJECT + FIELD * items.size
        }
        val width = when (element) {
            java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
            Integer.TYPE, java.lang.Float.TYPE -> 4
            java.lang.Short.TYPE, Character.TYPE -> 2
            else -> 1
        }
        return OBJECT + width.toLong() * java.lang.reflect.Array.getLength(value)
    }

    /** The instance fields of [type] and of its superclasses of the module. */
    private fun shapeOf(type: Class<*>): Shape = shapes.getOrPut(type) {
        val own = generateSequence(type) { it.superclass?.takeIf { parent -> parent.classLoader === loader } }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .toList()
        Shape(own.size, own.filter { !it.type.isPrimitive && it.trySetAccessible() })
    }

    /** A class of the JDK (the boot or the platform loader): its public API never runs a module's code. */
    private fun jdk(type: Class<*>): Boolean = type.classLoader == null || type.classLoader === ClassLoader.getPlatformClassLoader()

    private companion object {
        const val OBJECT = 16L
        const val FIELD = 8L
    }
}
