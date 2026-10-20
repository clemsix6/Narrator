package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sandbox.Rewrites
import io.github.clemsix6.narrator2.testing.BusyMachine
import java.lang.ref.WeakReference
import java.time.Clock

/** Modules built as the Forge builds them (ECJ, the guard, the rewriting) and hosted by the real loader and host (M5-05). */
internal object Hosts {
    /** [source] compiled against the SDK, clean to the guard, rewritten. */
    fun build(name: String, source: String): Map<String, ByteArray> {
        val classes = Rewrites.compile(name, source)
        val violations = BytecodeGuard().check(classes)
        check(violations.isEmpty()) { "$name is refused by the guard: $violations" }
        return Instrumenter.rewrite(name, classes)
    }

    /** A module `[name]` with [members] and [body] as its `start(ModuleContext m)` (the SDK, `java.util.*`, functions and streams imported). */
    fun source(name: String, members: String = "", body: String = ""): String = Rewrites.source(name, members, body)

    /** A host for [name]; [timeScale], [abandonMarginNanos] and [gaugeNanos] as [ModuleHost] has them (the game's by default; see [BusyMachine]). */
    fun host(
        name: String,
        source: String,
        threads: ModuleThreads,
        clock: Clock = Clock.systemUTC(),
        faults: MutableList<Fault> = mutableListOf(),
        timeScale: Long = 1,
        abandonMarginNanos: Long = ModuleHost.ABANDON_MARGIN_NANOS * timeScale,
        gaugeNanos: Long = ForgeLimits.GAUGE_MAX_NANOS * timeScale,
    ): ModuleHost = ModuleHost(
        name, build(name, source), threads, "C-70 $name", clock,
        timeScale = timeScale, abandonMarginNanos = abandonMarginNanos, gaugeNanos = gaugeNanos,
    ) { faults += it }

    /**
     * Collects until [references] are all cleared, or gives up after [millis]; true when they are. A wait for what it
     * waits for, not a number of rounds: a busy machine may be slow to end a thread that held one.
     */
    fun collected(references: List<WeakReference<*>>, millis: Long = 30_000): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (references.any { it.get() != null } && System.nanoTime() - deadline < 0) {
            System.gc()
            Thread.sleep(50)
        }
        return references.all { it.get() == null }
    }
}

/** Makes and starts the module with [context]; whether it started (no fault disabled it). */
internal fun ModuleHost.started(context: ModuleContext): Boolean {
    start(context)
    return disabled == null
}
