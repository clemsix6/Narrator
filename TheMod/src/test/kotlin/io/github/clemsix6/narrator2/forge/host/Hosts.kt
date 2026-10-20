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

    /** A host for [name]; [timeScale], [abandonMarginNanos] and [patienceNanos] as [ModuleHost] has them (the game's by default; see [BusyMachine]). */
    fun host(
        name: String,
        source: String,
        threads: ModuleThreads,
        clock: Clock = Clock.systemUTC(),
        faults: MutableList<Fault> = mutableListOf(),
        timeScale: Long = 1,
        abandonMarginNanos: Long = ModuleHost.ABANDON_MARGIN_NANOS * timeScale,
        patienceNanos: Long = 0,
    ): ModuleHost = ModuleHost(
        name, build(name, source), threads, "C-70 $name", clock,
        timeScale = timeScale, abandonMarginNanos = abandonMarginNanos, patienceNanos = patienceNanos,
    ) { faults += it }

    /**
     * Settles the call [host] gave up, if any, waiting for its thread (on a busy machine a held-back thread may wait
     * seconds); true once nothing of it runs any more.
     */
    fun settled(host: ModuleHost, millis: Long = 300_000): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (host.busy() && System.nanoTime() - deadline < 0) Thread.sleep(5)
        return !host.busy()
    }

    /**
     * About [millis] of a module's own work, as rounds of [ROUNDS]' loop (`for (long i = 0; i < n; i++) x += i ^ (x >>> 3);`,
     * a tick a round): measured once on this machine by its meter ([ModuleHost.Called.work]), which a busy machine does
     * not stretch, over calls of about 100 ms (the CPU clock's steps of 15.6 ms then weigh little).
     */
    fun rounds(millis: Long): Long = maxOf(1, (roundsPerNano * millis * 1_000_000).toLong())

    /** A module whose timer 0 loops [rounds] rounds, the number set in its static field `n` once it started. */
    const val ROUNDS = "public static long n; long sink;"
    const val ROUNDS_BODY = "m.timers().every(1, () -> { long x = 0; for (long i = 0; i < n; i++) x += i ^ (x >>> 3); sink = x; });"

    private val roundsPerNano: Double by lazy {
        val threads = ModuleThreads("rounds-probe")
        try {
            val probe = host("Rounds", source("Rounds", ROUNDS, ROUNDS_BODY), threads, timeScale = BusyMachine.TIME_SCALE)
            val fake = io.github.clemsix6.narrator2.forge.sandbox.FakeContext()
            check(probe.started(fake.context)) { "the probe did not start: ${probe.disabled}" }
            var n = 1_000_000L
            probe.main.getField("n").setLong(null, n)
            var work = probe.call("timer") { fake.timers[0].run() }!!.work
            while (work < 100_000_000) { // up to calls of about 100 ms of work
                n *= 2
                probe.main.getField("n").setLong(null, n)
                work = probe.call("timer") { fake.timers[0].run() }!!.work
            }
            val works = (1..3).map { probe.call("timer") { fake.timers[0].run() }!!.work }.sorted()
            probe.unload()
            n.toDouble() / works[1]
        } finally {
            threads.close()
        }
    }

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
