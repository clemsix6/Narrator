package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the module threads are for (the supervisor's decision on finding 1 of M5-04): a single call of the JDK that
 * runs for long — hashing a structure shared ten thousand times, an `indexOf` of O(n·m) — never holds the caller past
 * the budget; the module is disabled, and its abandoned thread never reaches the SDK again.
 *
 * Each attack is one call with the game's budget and margin ([attack]); the module's `start` before it has a busy
 * machine's patience ([attacked]). The caller's wait is bounded on the wall clock with [BusyMachine.SLACK_MILLIS] to
 * spare; that the call was given up while its JDK call ran on is checked as such (the fault, the lost thread). Since
 * M5-LOAD the caller gives the call up without judging it: the module waits ([ModuleHost.busy]) until its thread shows
 * it computed past its budget ([Hosts.settled]; 40 ms of CPU at least); then the abandon is its fault, as before.
 */
class AbandonTest {
    private val stuck = Collections.synchronizedList(mutableListOf<String>())
    private val threads = ModuleThreads("abandon-module", LostThreads(onStuck = { stuck += it }))

    @AfterEach
    fun close() {
        threads.close()
    }

    private val shared = SHARED

    /**
     * The attack is one JDK call longer than the caller's budget and the slack a busy machine is given ([LONG_SCAN]: an
     * equality scan of ~2 s, then an SDK call with what it found): whatever holds the caller back, it can only go on
     * while that call still runs, so the call is given up, never seen to its end (FLAKY-6). With the `indexOf` of
     * ~75 ms it used before, a caller held back ~50 ms past its deadline found the call ended, judged by its meter (one
     * limit fault, which does not disable), and the test failed with `disabled` null; the bound below would let such
     * a caller through.
     */
    @Test
    fun `a single long call of the JDK never holds the caller past the budget, and nothing reaches the SDK after`() {
        val fake = FakeContext()
        val calls = Collections.synchronizedList(mutableListOf<Long>())
        val faults = mutableListOf<Fault>()
        val host = attacked("Shared", shared, threads, faults = faults)
        assertTrue(host.started(timed(fake.context, calls)), "${host.disabled}")
        val before = calls.size

        val called = assertNotNull(host.attack { fake.timers[LONG_SCAN].run() })
        val abandonedAt = System.nanoTime()
        val waited = called.nanos / 1e6
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + BusyMachine.SLACK_MILLIS, "the caller went on at the budget: $waited ms")
        assertTrue(Hosts.settled(host), "settled once its thread computed past its budget, or ended")
        val fault = assertNotNull(host.disabled, "faults: $faults")
        assertEquals(Fault.Kind.LIMIT, fault.kind)
        assertTrue(fault.disables)
        assertEquals("time budget: one call ran past $BUDGET_MS ms and was abandoned", fault.cause, "the server thread never waits for the line")
        assertTrue(threads.lost.alive() <= 1, "its thread, lost while it runs")

        // The scan goes on on the lost thread; then it returns, its log is refused, and the thread ends.
        assertTrue(awaitNoLostThread(), "the lost thread ended")
        val lived = (System.nanoTime() - abandonedAt) / 1e6
        assertEquals(before, calls.size, "no SDK call reached the context after the abandon")
        assertEquals(emptyList(), fake.logs.toList())
        assertNull(host.call("timer") { fake.timers[1].run() }, "a disabled module never runs again")
        assertTrue(stuck.isEmpty())
        println("[host] an equality scan of ~2 s in one JDK call: the caller went on after %.1f ms; the lost thread ended %.0f ms later".format(waited, lived))
    }

    @Test
    fun `an equality scan of minutes in one JDK call holds the caller no longer - in a JVM of its own`() {
        // The lost thread would run for minutes and lengthen every safepoint of this JVM: another JVM, ended at once.
        val javaBinary = ProcessHandle.current().info().command().orElse("java")
        // The test classpath is too long for a Windows command line: a jar whose manifest lists it, as URLs.
        val entries = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toASCIIString() }
        val pathing = Files.createTempFile("narrator2-attack", ".jar")
        val manifest = java.util.jar.Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ")
        }
        JarOutputStream(Files.newOutputStream(pathing), manifest).close()
        val process = ProcessBuilder(javaBinary, "-cp", pathing.toString(), SharedStructureAttack::class.java.name)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), output)
        Files.deleteIfExists(pathing)
        assertEquals(0, process.exitValue(), output)
        val line = output.lines().single { it.startsWith("attack ") }
        val values = line.removePrefix("attack ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        val waited = values.getValue("waited").toDouble()
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + BusyMachine.SLACK_MILLIS, "the caller went on at the budget: $line")
        // Given up at its budget, the call is abandoned once its thread computed past it: its fault, as before.
        assertEquals("LIMIT", values["kind"], line)
        assertEquals("true", values["disables"], line)
        assertEquals("time budget: one call ran past $BUDGET_MS ms and was abandoned", values["cause"]?.replace('_', ' '), line)
        assertEquals("1", values["stillAlive"], "the lost thread burns on: $line")
        println("[host] an equality scan of 10,000 comparisons in a JVM of its own: $line (System.gc() ms, each a safepoint)")
    }

    @Test
    fun `at the bench the caller may wait for the line of the abandoned call`() {
        // The equality scan of SCAN_SETUP over 35 references: ~0.75 s in one JDK call — long enough that the lost
        // thread's stack is still in it when read, short enough to end before the test does. (It hashed 10 × L1 through
        // Stream.distinct() before M5-FIX-2b, which the guard now bounds.)
        val module = Hosts.source(
            "Search",
            "List<?> target; List<List<Integer>> near; List<Object> few;",
            """
                $SCAN_SETUP
                few = new ArrayList<>(); for (int i = 0; i < 35; i++) few.add(near);
                m.timers().every(1, () -> m.log("" + Collections.indexOfSubList(few, target)));
            """.trimIndent(),
        )
        val fake = FakeContext()
        // The lost thread's stack is read at its next safepoint: on a busy machine, that may take seconds. The bench waits for it as long as it is given (here a minute; the bench's own, 1 s).
        // As the bench does, its caller is patient (M5-LOAD): it gives the call up once its thread computed past
        // its budget, the thread still in its JDK call.
        val host = attacked("Search", module, threads, label = "bench Search", lineWaitMillis = 60_000, patienceNanos = BusyMachine.PATIENT_NANOS)
        assertTrue(host.started(fake.context))
        val fault = assertNotNull(host.attack { fake.timers[0].run() }?.fault)
        assertTrue(fault.cause.startsWith("time budget: one call ran past $BUDGET_MS ms and was abandoned in java.util."), fault.cause)
        assertTrue("at Search.java:" in fault.cause, fault.cause)
        assertTrue(fault.line > 0)
        assertTrue(awaitNoLostThread())
    }

    @Test
    fun `an indexOf of O(n·m) in a loop is given up at the budget`() {
        val fake = FakeContext()
        val body = """
            String text = "a".repeat(65_535) + "b";
            String pattern = "a".repeat(32_767) + "b";
            m.timers().every(1, () -> { long t = System.nanoTime(); text.indexOf(pattern); m.log("one indexOf: " + (System.nanoTime() - t) / 1000 + " us"); });
            m.timers().every(1, () -> { while (true) { if (text.indexOf(pattern) < 0) m.log("never"); } });
        """.trimIndent()
        // One indexOf, measured in a call with time to spare (FLAKY-4 saw a saturated machine hold such a call past
        // 2 s). Should even this one run past its ten seconds, one indexOf is longer still.
        val probe = attacked("Search", Hosts.source("Search", body = body), threads)
        assertTrue(probe.started(fake.context))
        probe.call("timer", BusyMachine.PATIENT_NANOS) { fake.timers[0].run() }
        val one = fake.logs.singleOrNull() ?: "one indexOf: over ${BusyMachine.PATIENT_NANOS / 1_000} us"
        val oneMillis = one.removePrefix("one indexOf: ").removePrefix("over ").removeSuffix(" us").toLong() / 1e3

        val looping = FakeContext()
        val host = attacked("Search", Hosts.source("Search", body = body), threads)
        assertTrue(host.started(looping.context))
        val called = assertNotNull(host.attack { looping.timers[1].run() })
        val waited = called.nanos / 1e6
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + BusyMachine.SLACK_MILLIS, "the caller went on at the budget: $waited ms")
        assertTrue(Hosts.settled(host))
        assertEquals(Fault.Kind.LIMIT, host.disabled?.kind)
        val fault = host.disabled!!
        assertTrue(fault.disables || fault.cause.startsWith("time budget"), "$fault")
        // One indexOf longer than the budget (75 ms here): no tick of the module's code can come before the caller's
        // deadline, so the call was given up, never stopped by its meter.
        if (oneMillis > BUDGET_MS) assertEquals("time budget: one call ran past $BUDGET_MS ms and was abandoned", fault.cause, "one indexOf took $oneMillis ms")
        assertTrue(awaitNoLostThread(), "the lost thread ended at its next tick")
        assertEquals(emptyList(), looping.logs.toList())
        println("[host] indexOf of 32,768 characters in 65,536 ($one), looped: the caller went on after %.1f ms (${called.fault})".format(waited))
    }

    @Test
    fun `past the lost threads the Forge allows, no module runs`() {
        val lost = LostThreads(limit = 0, onStuck = { stuck += it })
        val strict = ModuleThreads("strict-module", lost)
        try {
            val fake = FakeContext()
            val host = attacked("Shared", shared, strict)
            assertTrue(host.started(fake.context))
            // timer 2: the equality scan over 35 references, ~0.75 s of one JDK call: lost while it runs, once it computed past its budget.
            host.attack { fake.timers[2].run() }
            assertTrue(Hosts.settled(host))
            assertEquals(Fault.Kind.LIMIT, host.disabled?.kind)
            assertTrue(lost.stuck)
            assertEquals(1, stuck.size)

            val other = FakeContext()
            val next = attacked("Shared", shared, strict)
            next.start(other.context)
            assertEquals(Fault.Kind.STUCK, next.disabled?.kind)
            assertEquals(emptyList(), other.timers, "its code never ran")
        } finally {
            strict.close()
        }
    }

    /**
     * Waits for the lost threads to end. On a machine whose cores are all taken, that may take long (FLAKY-4 measured,
     * at the lowest priority, the 0.75 s hash of the bench's test over a minute under 32 spinning threads on 16 cores;
     * lost threads run at the normal priority since M5-LOAD).
     */
    private fun awaitNoLostThread(millis: Long = 300_000): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (threads.lost.alive() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return threads.lost.alive() == 0
    }

    /** [context] behind a recorder that notes the time of each SDK call (and of those of what it hands out). */
    private fun timed(context: ModuleContext, calls: MutableList<Long>): ModuleContext = recorder(ModuleContext::class.java, context, calls)

    private fun <T : Any> recorder(type: Class<T>, target: T, calls: MutableList<Long>): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                calls += System.nanoTime()
                val result = method.invoke(target, *(arguments ?: arrayOf()))
                if (result != null && method.returnType.isInterface && method.returnType.packageName == type.packageName) {
                    @Suppress("UNCHECKED_CAST")
                    recorder(method.returnType as Class<Any>, result, calls)
                } else {
                    result
                }
            },
        )

    internal companion object {
        /** A call's budget and the margin the meter has to unwind before the caller abandons it: the game's. */
        val BUDGET_MS = ForgeLimits.CALL_MILLIS + ModuleHost.ABANDON_MARGIN_NANOS / 1_000_000

        /**
         * The equality scan the host alone can stop (M5-FIX-2b), as Java statements of a module's `start`: `target`, a
         * list holding one list of 300 lists of 10,000 numbers, and `near`, a list of 300 references to another list of
         * the same numbers, equal to it up to its last element. `Collections.indexOfSubList(list, target)` over a list of
         * n references to `near` compares the two n times, each a walk of ~3 million elements (~20 ms measured): one call
         * of the JDK of O(n·m), with no code of the module in it. The guard bounds the size of what one comparison walks
         * (4 million element slots), not how many comparisons a scan makes. Needs the fields `List<?> target` and
         * `List<List<Integer>> near`.
         */
        const val SCAN_SETUP = """
            List<Integer> a = new ArrayList<>(); for (int i = 0; i < 10_000; i++) a.add(i);
            List<Integer> b = new ArrayList<>(); for (int i = 0; i < 10_000; i++) b.add(i);
            near = new ArrayList<>(); for (int i = 0; i < 300; i++) near.add(a);
            List<List<Integer>> probe = new ArrayList<>(); for (int i = 0; i < 299; i++) probe.add(b); probe.add(List.of(-1));
            target = List.of(probe);
        """

        /**
         * Attacks the meter and the guard cannot see: one long call of the JDK that is neither a backward jump of the
         * module nor a hash or a comparison the guard bounds (M5-18, M5-FIX-2, M5-FIX-2b): the equality scan of
         * [SCAN_SETUP], timer [LONG_SCAN] over 100 references (~2 s in one call: longer than a call's budget and
         * [BusyMachine.SLACK_MILLIS] together), timer 1 over 10,000 (a single call that runs for minutes), timer 2 over
         * 35 (~0.75 s). (They hashed a shared structure through `Stream.distinct()` before M5-FIX-2b, which the guard now
         * bounds; timer 0 was an `indexOf` of ~75 ms before FLAKY-6, which a caller held back by a busy machine could see
         * to its end.) All are given up by the host at the budget, their thread lost once it computed past its budget.
         */
        val SHARED = Hosts.source(
            "Shared",
            "List<?> target; List<List<Integer>> near; List<Object> longer; List<Object> many; List<Object> few;",
            """
                $SCAN_SETUP
                longer = new ArrayList<>(); for (int i = 0; i < 100; i++) longer.add(near);
                many = new ArrayList<>(); for (int i = 0; i < 10_000; i++) many.add(near);
                few = new ArrayList<>(); for (int i = 0; i < 35; i++) few.add(near);
                m.timers().every(1, () -> m.log("" + Collections.indexOfSubList(longer, target)));
                m.timers().every(1, () -> m.log("" + Collections.indexOfSubList(many, target)));
                m.timers().every(1, () -> m.log("" + Collections.indexOfSubList(few, target)));
            """.trimIndent(),
        )

        /** The timer of [SHARED] whose one JDK call outlasts a call's budget and a busy machine's slack. */
        const val LONG_SCAN = 0

        /**
         * A host whose attack has the game's margin, and whose other calls (its `start`, which builds the attack's
         * structure) a busy machine's patience ([BusyMachine.TIME_SCALE]).
         */
        fun attacked(
            name: String,
            source: String,
            threads: ModuleThreads,
            faults: MutableList<Fault> = mutableListOf(),
            label: String = "C-70 $name",
            lineWaitMillis: Long = 0,
            patienceNanos: Long = 0,
        ): ModuleHost = ModuleHost(
            name, Hosts.build(name, source), threads, label,
            lineWaitMillis = lineWaitMillis, timeScale = BusyMachine.TIME_SCALE, abandonMarginNanos = ModuleHost.ABANDON_MARGIN_NANOS,
            patienceNanos = patienceNanos,
        ) { faults += it }

        /** How many times an attack is asked while its module thread does not even start it. */
        private const val ATTACK_TRIES = 20

        /**
         * One call of the module's code with the game's budget ([ForgeLimits.CALL_NANOS]): the attack. A busy machine may
         * not start the module thread within the call's deadline: the call is then skipped (`LATE`: nothing of it ran,
         * nobody's fault, M5-LOAD), and it is asked again, as the engine replays a skipped call, until it runs. What the
         * tests check is the call that ran; never started in [ATTACK_TRIES] tries, the last skipped call is returned.
         */
        fun ModuleHost.attack(body: () -> Unit): ModuleHost.Called? {
            var called = call("timer", ForgeLimits.CALL_NANOS, body)
            var tries = 1
            while (called != null && called.skipped && tries++ < ATTACK_TRIES) called = call("timer", ForgeLimits.CALL_NANOS, body)
            return called
        }
    }
}
