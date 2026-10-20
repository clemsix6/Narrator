package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sandbox.Rewrites
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

    fun host(
        name: String,
        source: String,
        threads: ModuleThreads,
        clock: Clock = Clock.systemUTC(),
        faults: MutableList<Fault> = mutableListOf(),
    ): ModuleHost = ModuleHost(name, build(name, source), threads, "C-70 $name", clock) { faults += it }

    /** Collects until [references] are all cleared, or gives up after [rounds]; true when they are. */
    fun collected(references: List<WeakReference<*>>, rounds: Int = 20): Boolean {
        repeat(rounds) {
            if (references.all { it.get() == null }) return true
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
