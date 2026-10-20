package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.host.AbandonTest.Companion.attack
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import kotlin.system.exitProcess

/**
 * Finding 1 of M5-04 at its worst, in a JVM of its own (`AbandonTest` starts it): L2 = 10,000 × L1 fed to
 * `Stream.distinct()` — a factory the guard does not wrap (M5-18's residual), which hashes it in one call of the JDK,
 * ten minutes of a thread that cannot be stopped. Prints how long the caller waited, the fault, and what the lost
 * thread does to the JVM's safepoints (`System.gc()` before and while it runs); then exits, which ends it.
 */
object SharedStructureAttack {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Log.forge.warn("an attack's JVM: the log is set up, as a running server's is")
        val baseline = gcs()
        val threads = ModuleThreads("attack-module")
        val fake = FakeContext()
        val host = AbandonTest.attacked("Shared", AbandonTest.SHARED, threads)
        check(host.started(fake.context)) { "${host.disabled}" }
        val called = host.attack { fake.timers[1].run() }!!
        val alive = threads.lost.alive()
        Thread.sleep(500)
        val during = gcs()
        println(
            "attack waited=%.1f kind=%s disables=%s cause=%s alive=%d stillAlive=%d gcBefore=%s gcDuring=%s"
                .format(called.nanos / 1e6, called.fault?.kind, called.fault?.disables, called.fault?.cause?.replace(' ', '_'), alive, threads.lost.alive(), baseline, during),
        )
        System.out.flush()
        exitProcess(0)
    }

    /** Five full collections, each a safepoint: their milliseconds. */
    private fun gcs(): String = (1..5).joinToString("/") {
        val start = System.nanoTime()
        System.gc()
        "%.0f".format((System.nanoTime() - start) / 1e6)
    }
}
