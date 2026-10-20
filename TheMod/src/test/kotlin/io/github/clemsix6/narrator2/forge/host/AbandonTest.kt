package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
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
 */
class AbandonTest {
    private val stuck = Collections.synchronizedList(mutableListOf<String>())
    private val threads = ModuleThreads("abandon-module", LostThreads(onStuck = { stuck += it }))

    @AfterEach
    fun close() {
        threads.close()
    }

    private val shared = SHARED

    @Test
    fun `hashing a structure shared ten thousand times never holds the caller past the budget, and nothing reaches the SDK after`() {
        val fake = FakeContext()
        val calls = Collections.synchronizedList(mutableListOf<Long>())
        val faults = mutableListOf<Fault>()
        val host = Hosts.host("Shared", shared, threads, faults = faults)
        assertTrue(host.started(timed(fake.context, calls)), "${host.disabled}")
        val before = calls.size

        val called = assertNotNull(host.call("timer") { fake.timers[0].run() })
        val abandonedAt = System.nanoTime()
        val waited = called.nanos / 1e6
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + SLACK_MS, "the caller went on at the budget: $waited ms")
        val fault = assertNotNull(called.fault)
        assertEquals(Fault.Kind.LIMIT, fault.kind)
        assertTrue(fault.disables)
        assertEquals(fault, host.disabled)
        assertEquals("time budget: one call ran past $BUDGET_MS ms and was abandoned", fault.cause, "the server thread never waits for the line")
        assertEquals(1, threads.lost.alive(), "its thread runs on, lost")

        // L1's hash takes ~80 ms on the lost thread; then its next step (the log) is refused, and it ends.
        assertTrue(awaitNoLostThread(5_000), "the lost thread ended")
        val lived = (System.nanoTime() - abandonedAt) / 1e6
        assertEquals(before, calls.size, "no SDK call reached the context after the abandon")
        assertEquals(emptyList(), fake.logs.toList())
        assertNull(host.call("timer") { fake.timers[1].run() }, "a disabled module never runs again")
        assertTrue(stuck.isEmpty())
        println("[host] L1 = 10,000 × L0 hashed in one JDK call: the caller went on after %.1f ms; the lost thread ended %.0f ms later".format(waited, lived))
    }

    @Test
    fun `hashing L2 = 10,000 × L1, ten minutes of one JDK call, holds the caller no longer - in a JVM of its own`() {
        // The lost thread would run for ten minutes and lengthen every safepoint of this JVM: another JVM, ended at once.
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
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + SLACK_MS, "the caller went on at the budget: $line")
        assertEquals("LIMIT", values["kind"], line)
        assertEquals("true", values["disables"], line)
        assertEquals("1", values["stillAlive"], "the lost thread burns on, at the lowest priority: $line")
        println("[host] L2 = 10,000 × L1 in a JVM of its own: $line (System.gc() ms, each a safepoint)")
    }

    @Test
    fun `at the bench the caller may wait for the line of the abandoned call`() {
        val fake = FakeContext()
        val host = ModuleHost("Shared", Hosts.build("Shared", shared), threads, "bench Shared", lineWaitMillis = 2_000)
        assertTrue(host.started(fake.context))
        val fault = assertNotNull(host.call("timer") { fake.timers[0].run() }?.fault)
        assertTrue(fault.cause.startsWith("time budget: one call ran past $BUDGET_MS ms and was abandoned in java.util."), fault.cause)
        assertTrue("hashCode at Shared.java:" in fault.cause, fault.cause)
        assertTrue(fault.line > 0)
        assertTrue(awaitNoLostThread(5_000))
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
        val probe = Hosts.host("Search", Hosts.source("Search", body = body), threads)
        assertTrue(probe.started(fake.context))
        probe.call("timer") { fake.timers[0].run() }
        val one = fake.logs.toList()

        val looping = FakeContext()
        val host = Hosts.host("Search", Hosts.source("Search", body = body), threads)
        assertTrue(host.started(looping.context))
        val called = assertNotNull(host.call("timer") { looping.timers[1].run() })
        val waited = called.nanos / 1e6
        assertTrue(waited >= ForgeLimits.CALL_MILLIS && waited < BUDGET_MS + SLACK_MS, "the caller went on at the budget: $waited ms")
        assertEquals(Fault.Kind.LIMIT, called.fault?.kind)
        val fault = called.fault!!
        assertTrue(fault.disables || fault.cause.startsWith("time budget"), "$fault")
        assertTrue(awaitNoLostThread(10_000), "the lost thread ended at its next tick")
        assertEquals(emptyList(), looping.logs.toList())
        println("[host] indexOf of 32,768 characters in 65,536 (${one.firstOrNull() ?: "over its budget"}), looped: the caller went on after %.1f ms (${called.fault})".format(waited))
    }

    @Test
    fun `past the lost threads the Forge allows, no module runs`() {
        val lost = LostThreads(limit = 0, onStuck = { stuck += it })
        val strict = ModuleThreads("strict-module", lost)
        try {
            val fake = FakeContext()
            val host = Hosts.host("Shared", shared, strict)
            assertTrue(host.started(fake.context))
            assertEquals(Fault.Kind.LIMIT, host.call("timer") { fake.timers[0].run() }?.fault?.kind)
            assertTrue(lost.stuck)
            assertEquals(1, stuck.size)

            val other = FakeContext()
            val next = Hosts.host("Shared", shared, strict)
            next.start(other.context)
            assertEquals(Fault.Kind.STUCK, next.disabled?.kind)
            assertEquals(emptyList(), other.timers, "its code never ran")
        } finally {
            strict.close()
        }
    }

    private fun awaitNoLostThread(millis: Long): Boolean {
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
        /** A call's budget and the margin the meter has to unwind before the caller abandons it. */
        val BUDGET_MS = ForgeLimits.CALL_MILLIS + ModuleHost.ABANDON_MARGIN_NANOS / 1_000_000

        /** What a busy test machine may add. */
        const val SLACK_MS = 5

        /** The exact case of finding 1: L0 of 10,000 numbers, L1 = 10,000 × L0, L2 = 10,000 × L1, each within its limits. */
        val SHARED = Hosts.source(
            "Shared",
            "List<List<Integer>> l1; List<List<List<Integer>>> l2;",
            """
                List<Integer> l0 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l0.add(i);
                l1 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l1.add(l0);
                l2 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l2.add(l1);
                m.timers().every(1, () -> m.log("" + l1.hashCode()));
                m.timers().every(1, () -> m.log("" + l2.hashCode()));
            """.trimIndent(),
        )
    }
}
