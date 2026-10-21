package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ForgeLimits
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.ref.WeakReference
import java.time.Clock

/**
 * The watch over unloaded modules (M5 §3.7): each loader let go ([ModuleHost.unload]) is kept by a weak reference;
 * still alive [ForgeLimits.LEAK_CHECK_MS] **and** [ForgeLimits.LEAK_GC_CYCLES] collections **that unload classes**
 * later ([collections]; never a `System.gc()` in the game), it is a leak: a `WARN`, told once by [check] (the engine
 * writes the decision `forge leak`), counted in [leaks] (`ModuleControl.leaks`). An abandoned module thread still
 * running holds its module's classes: its loader leaks until the thread ends, and is told so.
 *
 * Only a collection that unloads classes can tell a leak (M5-FIX-3): a class loader that defined classes is never
 * collected by any other. With G1, the server's collector, these are a full collection and the remark of a concurrent
 * cycle; a young collection, which comes every few seconds, treats the data of every class loader as a root. On a
 * server whose heap is large and mostly free, a concurrent cycle may not come for hours: counting every collection, as
 * the watch first did, told a leak at each module's end (M5-22: `orbiting_chickens`, `mental_math`, `metronome`, each
 * ended well; measured in a GameTest, the loader of `orbiting_chickens` survives young collections and goes at the first
 * full one). [UnloadingCollections] counts them whatever the collector.
 */
class LeakWatch(private val clock: Clock = Clock.systemUTC(), private val collections: () -> Long = UnloadingCollections()::count) {
    private val log = Log.forge

    private class Watched(val label: String, val loader: WeakReference<ClassLoader>, val since: Long, val gcs: Long)

    private val watched = mutableListOf<Watched>()

    /** Loaders found alive after their delay since the server started. */
    var leaks = 0
        private set

    /** Watches [loader], let go now by the module [label] (`C-70 orbiting_chickens`). */
    @Synchronized
    fun watch(label: String, loader: WeakReference<ClassLoader>) {
        if (loader.get() == null) return
        watched += Watched(label, loader, clock.millis(), collections())
    }

    /** The labels of the loaders found leaking since the last check, each told once; the collected ones forgotten. */
    @Synchronized
    fun check(): List<String> {
        val now = clock.millis()
        val gcs = collections()
        val leaking = mutableListOf<String>()
        watched.removeAll { item ->
            when {
                item.loader.get() == null -> true
                now - item.since >= ForgeLimits.LEAK_CHECK_MS && gcs - item.gcs >= ForgeLimits.LEAK_GC_CYCLES -> {
                    leaking += item.label
                    true
                }
                else -> false
            }
        }
        leaks += leaking.size
        leaking.forEach {
            log.warn(
                "the classes of $it are still in memory ${ForgeLimits.LEAK_CHECK_MS / 1000} s and ${ForgeLimits.LEAK_GC_CYCLES} " +
                    "collections that unload classes after its end: a leak",
            )
        }
        return leaking
    }

    /** The loaders watched now. */
    @Synchronized
    fun watching(): Int = watched.size
}

/**
 * The collections that unloaded classes so far, as a canary sees them ([count]): a class loader of its own that defined
 * one empty class, held by nothing but a weak reference, goes — like the loader of a module let go — only in such a
 * collection. Each time [count] finds it gone, one more is counted and a new one is laid. The canary [count] saw alive
 * goes in a collection after that call: a count that grew by `n` since a call tells `n` collections that unloaded
 * classes after it, at least (two between two calls count once).
 */
internal class UnloadingCollections {
    private var canary = lay()
    private var count = 0L

    @Synchronized
    fun count(): Long {
        if (canary.get() == null) {
            count++
            canary = lay()
        }
        return count
    }

    private class Canary : ClassLoader(null) {
        init {
            defineClass(NAME.replace('/', '.'), BYTES, 0, BYTES.size)
        }
    }

    private companion object {
        const val NAME = "narrator2/LeakCanary"

        /** An empty public class. */
        val BYTES: ByteArray = ClassWriter(0).run {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, NAME, null, "java/lang/Object", null)
            visitEnd()
            toByteArray()
        }

        fun lay(): WeakReference<ClassLoader> = WeakReference(Canary())
    }
}
