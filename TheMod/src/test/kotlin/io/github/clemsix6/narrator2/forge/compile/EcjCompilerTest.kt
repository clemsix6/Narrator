package io.github.clemsix6.narrator2.forge.compile

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.compile.Compilation.Outcome
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.LogCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Forge's compiler (task M5-02, M5 §5.1): ECJ in memory, blind to all but `java.base` and the SDK, on its own thread, with its limits. */
class EcjCompilerTest {
    /** The mod's loader, as the compiler reads the SDK through it, recording what is read, by which thread; each read waits for [gate] when there is one. */
    private class RecordingLoader : ClassLoader(null) {
        val paths = ConcurrentLinkedQueue<String>()
        val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        var gate: CountDownLatch? = null

        override fun getResourceAsStream(name: String): InputStream? {
            paths += name
            threads += Thread.currentThread().name
            gate?.await(10, TimeUnit.SECONDS)
            return ForgeModule::class.java.classLoader.getResourceAsStream(name)
        }
    }

    private val compilers = mutableListOf<EcjCompiler>()
    private val gates = mutableListOf<CountDownLatch>()

    private fun compiler(
        loader: ClassLoader = ForgeModule::class.java.classLoader,
        budgetMs: Long = ForgeLimits.COMPILE_MS,
        stuckMs: Long = ForgeLimits.COMPILER_STUCK_MS,
        onStuck: (String, Instant) -> Unit = { _, _ -> },
    ) = EcjCompiler(loader, budgetMs, stuckMs, onStuck).also { compilers += it }

    /** A gate the loader waits for: the compilation that reads the SDK through it blocks there, out of ECJ's reach. */
    private fun RecordingLoader.closed(): CountDownLatch = CountDownLatch(1).also { gate = it; gates += it }

    @AfterEach
    fun stopEverything() {
        gates.forEach { it.countDown() }
        compilers.forEach { it.stop() }
    }

    @Test
    fun `the witness compiles against java_base and the SDK alone, as the reference environment does, on the compiler's own thread`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        val compilation = compiler(loader).compile(ForgeSamples.CLASS, ForgeSamples.SOURCE, ForgeSamples.NAME)

        assertEquals(Outcome.COMPILED, compilation.outcome, "${compilation.problems}")
        assertTrue(compilation.ok && compilation.errors.isEmpty())
        assertEquals(ForgeSamples.classes.keys, compilation.classes.keys)
        ForgeSamples.classes.forEach { (name, bytes) -> assertContentEquals(bytes, compilation.classes[name], name) }
        // The SDK is read by name through the mod's loader (Knot in game), and nothing else is asked of it.
        assertTrue("io/github/clemsix6/narrator2/forge/api/ForgeModule.class" in loader.paths, "${loader.paths}")
        assertTrue(loader.paths.all { it.startsWith("io/github/clemsix6/narrator2/forge/api/") }, "${loader.paths}")
        // Never on the caller's thread (nor the server's): on the compiler's own, a daemon at the lowest priority.
        assertEquals(setOf(EcjCompiler.THREAD_NAME), loader.threads)
        val threads = Thread.getAllStackTraces().keys.filter { it.name == EcjCompiler.THREAD_NAME && it.isAlive }
        assertTrue(threads.isNotEmpty() && threads.all { it.isDaemon && it.priority == Thread.MIN_PRIORITY }, "$threads")
    }

    @Test
    fun `a module sees neither the game, nor the mod, nor the sandbox's runtime, nor the JDK past what java_base exports`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        val source = """
            public class Blind {
                net.minecraft.server.MinecraftServer server;
                io.github.clemsix6.narrator2.forge.Forge forge;
                io.github.clemsix6.narrator2.forge.rt.Meter meter;
                jdk.internal.misc.Unsafe internal;
                sun.misc.Unsafe unsafe;
                java.sql.Connection sql;
                javax.script.ScriptEngine script;
                net.fabricmc.loader.api.FabricLoader fabric;
            }
        """.trimIndent()
        val compilation = compiler(loader).compile("Blind", source)

        assertEquals(Outcome.ERRORS, compilation.outcome)
        assertTrue(compilation.classes.isEmpty())
        assertEquals((2..9).toList(), compilation.errors.map { it.line }, "one error per field: ${compilation.errors}")
        assertTrue(compilation.errors.all { "cannot be resolved" in it.message }, "${compilation.errors}")
        // The mod's loader, which knows the game and the mod in game, is only ever asked for the SDK.
        assertTrue(loader.paths.none { it.startsWith("net/") || "/forge/rt/" in it || it.endsWith("/forge/Forge.class") }, "${loader.paths}")
    }

    @Test
    fun `ordinary Java compiles - records, sealed types, pattern switches, lambdas, streams, text blocks - and keeps its warnings`() = runBlocking<Unit> {
        val source = """
            import java.util.*;
            import java.util.stream.*;

            public class Ordinary {
                sealed interface Shape permits Dot, Line {}
                record Dot(int x) implements Shape {}
                record Line(int from, int to) implements Shape {}
                enum Mood { CALM, ANGRY }

                String describe(List<Shape> shapes, Mood mood) {
                    int unused = 0;
                    String all = shapes.stream().map(s -> switch (s) {
                        case Dot d when d.x() > 3 -> "far dot";
                        case Dot d -> "dot";
                        case Line(int from, int to) -> "line " + (to - from);
                    }).collect(Collectors.joining(", "));
                    return switch (mood) { case CALM -> all; case ANGRY -> ""${'"'}
                        %s!
                        ""${'"'}.formatted(all.toUpperCase()); };
                }
            }
        """.trimIndent()
        val compilation = compiler().compile("Ordinary", source)

        assertEquals(Outcome.COMPILED, compilation.outcome, "${compilation.errors}")
        assertTrue(setOf("Ordinary", "Ordinary\$Shape", "Ordinary\$Dot", "Ordinary\$Line", "Ordinary\$Mood").all { it in compilation.classes }, "${compilation.classes.keys}")
        assertTrue(compilation.problems.isNotEmpty() && compilation.problems.none { it.error }, "${compilation.problems}")
        assertTrue(compilation.problems.any { "unused" in it.message && it.line == 11 }, "${compilation.problems}")
    }

    @Test
    fun `a package line, or a class that is not the file's, is an error`() = runBlocking<Unit> {
        val compiler = compiler()

        val packaged = compiler.compile("Packaged", "package games.tag;\n\npublic class Packaged {}\n")
        assertEquals(Outcome.ERRORS, packaged.outcome)
        val problem = packaged.errors.single()
        assertTrue("does not match the expected package" in problem.message && problem.line == 1, "$problem")

        val misnamed = compiler.compile("Statues", "public class RedLightGreenLight {}\n")
        assertEquals(Outcome.ERRORS, misnamed.outcome)
        assertTrue("must be defined in its own file" in misnamed.errors.single().message, "${misnamed.errors}")
    }

    @Test
    fun `a problem keeps its place, its offsets, ECJ's id and its arguments`() = runBlocking<Unit> {
        val source = """
            import io.github.clemsix6.narrator2.forge.api.*;

            public final class Chickens implements ForgeModule {
                public void start(ModuleContext ctx) {
                    ctx.acts().spawn("chicken", "Bartok");
                }
            }
        """.trimIndent()
        val problem = compiler().compile("Chickens", source).errors.single()

        assertEquals(5, problem.line)
        assertEquals(20, problem.column)
        assertEquals("spawn", source.substring(problem.start, problem.end + 1))
        assertEquals(org.eclipse.jdt.core.compiler.IProblem.ParameterMismatch, problem.id)
        assertEquals(listOf("io.github.clemsix6.narrator2.forge.api.Acts", "spawn"), problem.arguments.take(2))
    }

    @Test
    fun `a compilation past its budget answers too long at once, is cancelled at ECJ's next check, and the compiler goes on`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        val gate = loader.closed()
        val compiler = compiler(loader, budgetMs = 1_000)

        LogCapture().use { logs ->
            // Its caller hears of it at the budget, while ECJ is still held in a read of the SDK.
            val slow = compiler.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE, "slow")
            assertEquals(Outcome.TOO_LONG, slow.outcome)
            assertTrue(slow.classes.isEmpty())
            assertTrue(compiler.status().startsWith("compiling slow ("), compiler.status())

            // Let go, ECJ stops at its next check instead of finishing; then the next source compiles.
            gate.countDown()
            val next = compiler.compile("Next", "public class Next {}", "next")
            assertEquals(Outcome.COMPILED, next.outcome, "${next.problems}")
            assertEquals("idle", compiler.status())
            assertTrue(logs.at(Level.WARN).any { "slow: the compilation took more than 1000 ms; cancelled after" in it }, logs.text)
        }
    }

    @Test
    fun `a compilation that ignores its cancellation makes the compiler stuck until the server restarts`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        val gate = loader.closed()
        val told = CompletableDeferred<Pair<String, Instant>>()
        val compiler = compiler(loader, budgetMs = 50, stuckMs = 400) { label, since -> told.complete(label to since) }
        val before = Instant.now()

        LogCapture().use { logs ->
            val stuckOne = async { compiler.compile("Blocked", "public class Blocked { int v = io.github.clemsix6.narrator2.forge.api.Sdk.VERSION; }", "blocked") }
            val waiting = async { compiler.compile("Waiting", "public class Waiting {}", "waiting") }
            assertEquals(Outcome.TOO_LONG, stuckOne.await().outcome)
            assertTrue(compiler.status().startsWith("compiling blocked ("), compiler.status())
            assertNull(compiler.stuckSince)

            assertEquals(Outcome.STUCK, withTimeout(5_000) { waiting.await() }.outcome)
            val (label, since) = withTimeout(5_000) { told.await() }
            assertEquals("blocked", label)
            assertTrue(!since.isBefore(before.minusSeconds(1)) && since == compiler.stuckSince, "$since")
            assertTrue(Regex("stuck since \\d\\d:\\d\\d:\\d\\d").matches(compiler.status()), compiler.status())
            assertEquals(Outcome.STUCK, compiler.compile("Later", "public class Later {}").outcome)
            assertTrue(logs.at(Level.WARN).any { "the compiler is stuck: blocked" in it }, logs.text)

            // When the stuck compilation ends at last, the compiler stays stuck: only a restart brings it back.
            gate.countDown()
            delay(200)
            assertEquals(Outcome.STUCK, compiler.compile("Later", "public class Later {}").outcome)
            assertNotNull(compiler.stuckSince)
        }
    }

    @Test
    fun `a caller that gives up drops its compilation before it starts`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        val gate = loader.closed()
        val compiler = compiler(loader)

        val first = async { compiler.compile("First", "public class First { int v = io.github.clemsix6.narrator2.forge.api.Sdk.VERSION; }") }
        val second = async { compiler.compile("Second", "public class Second { Object w = io.github.clemsix6.narrator2.forge.api.Text.words(\"a\"); }") }
        delay(100)
        second.cancel()
        gate.countDown()

        assertEquals(Outcome.COMPILED, first.await().outcome)
        assertEquals(Outcome.COMPILED, compiler.compile("Third", "public class Third {}").outcome)
        assertTrue(loader.paths.none { it.endsWith("/Text.class") }, "the dropped one never ran: ${loader.paths}")
    }

    @Test
    fun `stopped, the compiler answers stopped to the running, the waiting and the later compilations`() = runBlocking<Unit> {
        val loader = RecordingLoader()
        loader.closed()
        val compiler = compiler(loader)

        val running = async { compiler.compile("Blocked", "public class Blocked { int v = io.github.clemsix6.narrator2.forge.api.Sdk.VERSION; }") }
        val waiting = async { compiler.compile("Waiting", "public class Waiting {}") }
        delay(100)
        assertTrue(compiler.status().startsWith("compiling Blocked ("), compiler.status())
        compiler.stop()

        assertEquals(Outcome.STOPPED, withTimeout(1_000) { running.await() }.outcome)
        assertEquals(Outcome.STOPPED, withTimeout(1_000) { waiting.await() }.outcome)
        assertEquals(Outcome.STOPPED, compiler.compile("Later", "public class Later {}").outcome)
        assertEquals("stopped", compiler.status())
        compiler.stop()
    }

    @Test
    fun `a source too deeply nested for the compiler is an error for the forger, and the compiler goes on`() = runBlocking<Unit> {
        val compiler = compiler()
        val source = "public class Deep { int f(int a) { return ${"a+".repeat(20_000)}1; } }"
        assertTrue(source.length <= ForgeLimits.SOURCE_CHARS)

        val deep = LogCapture().use { logs ->
            compiler.compile("Deep", source).also { assertTrue(logs.at(Level.WARN).any { "StackOverflowError" in it }, logs.text) }
        }
        assertEquals(Outcome.ERRORS, deep.outcome)
        val problem = deep.errors.single()
        assertTrue("nests too deeply" in problem.message && problem.line == 0, "$problem")

        assertEquals(Outcome.COMPILED, compiler.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE).outcome)
    }

    @Test
    fun `the pipeline checks the source first - a source too long or a name that is not a class is the caller's defect`() = runBlocking<Unit> {
        val compiler = compiler()
        assertFailsWith<IllegalArgumentException> { compiler.compile("Long", "public class Long {}" + " ".repeat(ForgeLimits.SOURCE_CHARS)) }
        assertFailsWith<IllegalArgumentException> { compiler.compile("tag_game", "public class tag_game {}") }
        assertFailsWith<IllegalArgumentException> { compiler.compile("../Evil", "public class Evil {}") }
    }

    @Test
    fun `ECJ's version is read from its own jar, for the status of the Forge`() {
        assertEquals("3.46.100", EcjCompiler.version)
    }
}
