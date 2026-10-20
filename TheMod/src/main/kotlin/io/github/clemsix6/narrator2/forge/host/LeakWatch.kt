package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ForgeLimits
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.time.Clock

/**
 * The watch over unloaded modules (M5 §3.7): each loader let go ([ModuleHost.unload]) is kept by a weak reference;
 * still alive [ForgeLimits.LEAK_CHECK_MS] **and** [ForgeLimits.LEAK_GC_CYCLES] garbage collections later (counted by
 * the JVM's `GarbageCollectorMXBean`s; never a `System.gc()` in the game), it is a leak: a `WARN`, told once by
 * [check] (the engine writes the decision `forge leak`), counted in [leaks] (`ModuleControl.leaks`). An abandoned
 * module thread still running holds its module's classes: its loader leaks until the thread ends, and is told so.
 */
class LeakWatch(private val clock: Clock = Clock.systemUTC(), private val collections: () -> Long = ::gcCount) {
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
        leaking.forEach { log.warn("the classes of $it are still in memory ${ForgeLimits.LEAK_CHECK_MS / 1000} s after its end: a leak") }
        return leaking
    }

    /** The loaders watched now. */
    @Synchronized
    fun watching(): Int = watched.size

    private companion object {
        fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }
    }
}
