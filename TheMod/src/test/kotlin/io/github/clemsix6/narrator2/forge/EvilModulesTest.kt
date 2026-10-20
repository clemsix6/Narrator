package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.forge.host.ModuleHost
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sandbox.ModuleLoader
import io.github.clemsix6.narrator2.forge.sandbox.RewriteRefused
import io.github.clemsix6.narrator2.testing.BusyMachine
import io.github.clemsix6.narrator2.testing.ForgeSamples
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The attack battery (M5 §16.2, task M5-18), written by an agent trying to break the sandbox. Each malicious module
 * under `src/test/resources/forge/evil/` (and the fabricated classes no source can produce) carries its expected
 * verdict in its head (`// expect: <barrier> <keyword>`), and each runs through the **real** pipeline — ECJ, the guard,
 * the rewriting, the filtering loader, the host — so that an attack which crosses every barrier fails the build.
 *
 * Barriers: `compile` (the blind compiler), `guard` ([BytecodeGuard]), `rewrite` ([Instrumenter]), `limit` (at run time,
 * the meter, the call-site guards, or the host's abandon). Each case stopped by the guard that compiles is **also**
 * loaded without the guard (`defense in depth`): the loader, the rewriting or the host must stop it alone — the one
 * exception, by design, is the `java.lang.invoke` family, where the loader keeps the head class `MethodHandles` out of
 * reach so that even code fabricated and loaded without the guard cannot obtain a `Lookup`.
 *
 * The runtime families reuse the real loader and host; the acts and creatures of §17 are attacked through the real
 * tools and guards in `EvilModulesGameTests`.
 */
class EvilModulesTest {
    private val threads = ModuleThreads("evil-module")

    @AfterEach
    fun close() {
        awaitNoLostThread(10_000)
        threads.close()
    }

    /** One malicious module: its class name, its source, and the verdict its head declares. */
    private class Evil(val name: String, val source: String) {
        private val expect = source.lineSequence().first { it.startsWith("// expect:") }.removePrefix("// expect:").trim()
        val barrier = expect.substringBefore(' ')
        val keyword = expect.substringAfter(' ').trim()
    }

    private val corpus: List<Evil> by lazy {
        val dir: Path = Paths.get(javaClass.getResource("/forge/evil")!!.toURI())
        Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".java") }
                .map { Evil(it.fileName.toString().removeSuffix(".java"), Files.readString(it)) }
                .sorted(compareBy { it.name })
                .toList()
        }
    }

    // ── The full battery, each case stopped at its barrier ────────────────────────────────────────────────────────

    @Test
    fun `every malicious module is stopped at the barrier it names, through the real pipeline`() {
        assertTrue(corpus.size >= 20, "the corpus has ${corpus.size} modules")
        for (evil in corpus) {
            val compiled = ForgeSamples.compile(evil.name, evil.source)
            when (evil.barrier) {
                "compile" -> {
                    assertTrue(!compiled.ok, "${evil.name} must not compile")
                    assertTrue(compiled.problems.any { evil.keyword in it }, "${evil.name}: ${compiled.problems}")
                }
                "guard" -> {
                    assertTrue(compiled.ok, "${evil.name} compiles so the guard is the barrier: ${compiled.problems}")
                    val violations = BytecodeGuard().check(compiled.classes)
                    assertTrue(violations.isNotEmpty(), "${evil.name} must be refused by the guard")
                    assertTrue(violations.any { evil.keyword in it.reason }, "${evil.name}: ${violations.map { it.reason }}")
                }
                "rewrite" -> {
                    assertTrue(compiled.ok, "${evil.name} compiles")
                    assertTrue(BytecodeGuard().check(compiled.classes).isEmpty(), "${evil.name} is clean to the guard")
                    val refused = runCatching { Instrumenter.rewrite(evil.name, compiled.classes) }.exceptionOrNull()
                    assertTrue(refused is RewriteRefused && evil.keyword in refused.message!!, "${evil.name}: $refused")
                }
                "limit" -> {
                    assertTrue(compiled.ok, "${evil.name} compiles: ${compiled.problems}")
                    assertTrue(BytecodeGuard().check(compiled.classes).isEmpty(), "${evil.name} is clean to the guard (the rewriting and host are the barrier)")
                    val built = Instrumenter.rewrite(evil.name, compiled.classes)
                    val host = ModuleHost(evil.name, built, threads, "evil ${evil.name}")
                    host.start(FakeContext().context)
                    val disabled = host.disabled ?: fail("${evil.name} ran to the end, crossing every barrier")
                    // A deep walk cold can hit the meter's time budget before its own count cap; both are a sandbox stop.
                    assertTrue(evil.keyword in disabled.cause || "time budget" in disabled.cause, "${evil.name}: ${disabled.cause}")
                    host.unload()
                    // An abandoned call leaves a lost thread burning its JDK call (bounded to two, at the lowest
                    // priority); its end is the host's to clean up, not this assertion's (see @AfterEach, best effort).
                }
                else -> fail("${evil.name}: unknown barrier '${evil.barrier}'")
            }
        }
    }

    // ── Defense in depth: a guard refusal that names a forbidden type dies at the loader, without the guard ─────────

    @Test
    fun `a forbidden type is refused at link even without the guard`() {
        // A busy machine's patience (FLAKY-4): the loader, never the clock, is what stops these; were it gone, they
        // would run to their end.
        for (name in listOf("FileRead", "Socket", "Exec", "NewThread")) {
            val evil = corpus.first { it.name == name }
            val built = Instrumenter.rewrite(name, ForgeSamples.compile(name, evil.source).classes)
            val host = ModuleHost(name, built, threads, "nogard $name", timeScale = BusyMachine.TIME_SCALE)
            host.start(FakeContext().context)
            val disabled = host.disabled ?: fail("$name linked and ran without the guard")
            assertTrue("NoClassDefFoundError" in disabled.cause || "is not visible" in disabled.cause, "$name: ${disabled.cause}")
            host.unload()
        }
        // Reflection whose target is a forbidden class: it links and runs, but the loader blocks the class it reaches for.
        val forName = corpus.first { it.name == "ForName" }
        val context = FakeContext()
        val built = Instrumenter.rewrite("ForName", ForgeSamples.compile("ForName", forName.source).classes)
        val host = ModuleHost("ForName", built, threads, "nogard ForName", timeScale = BusyMachine.TIME_SCALE)
        host.start(context.context)
        assertTrue(context.logs.contains("no forName"), "the loader blocked the reflective target: ${context.logs}")
        host.unload()
    }

    @Test
    fun `a structural or looping guard refusal is also stopped without the guard`() {
        // A multi-dimensional array: the rewriting refuses it (the dimensions cannot be bounded).
        val multi = corpus.first { it.name == "MultiArray" }
        val refused = runCatching { Instrumenter.rewrite("MultiArray", ForgeSamples.compile("MultiArray", multi.source).classes) }.exceptionOrNull()
        assertTrue(refused is RewriteRefused, "MultiArray without the guard: $refused")

        // Catching Throwable, and an endless stream through a module lambda: the sticky meter stops both at the budget.
        for (name in listOf("CatchThrowable", "StreamGenerate")) {
            val evil = corpus.first { it.name == name }
            val context = FakeContext()
            val built = Instrumenter.rewrite(name, ForgeSamples.compile(name, evil.source).classes)
            val host = ModuleHost(name, built, threads, "nogard $name")
            host.start(context.context)
            val disabled = host.disabled ?: fail("$name ran to the end without the guard")
            assertTrue("time budget" in disabled.cause, "$name: ${disabled.cause}")
            assertTrue("swallowed" !in context.logs, "the meter's error was not swallowed: ${context.logs}")
            host.unload()
        }
    }

    // ── The java.lang.invoke family: fabricated, loaded without the guard, fails at link (design review, finding 2) ─

    @Test
    fun `fabricated code loaded without the guard cannot reach MethodHandles nor the meter's holder`() {
        // MethodHandles.lookup(): the guard refuses java.lang.invoke, but the loader is the second barrier — the head
        // class is not resolvable, so even without the guard the link fails (no Lookup, no defineClass).
        val lookup = outlaw("Lookup", "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles\$Lookup;")
        val linkFailure = linkError("Lookup", mapOf("Lookup" to lookup))
        assertTrue("MethodHandles" in linkFailure, "MethodHandles is out of the loader's reach: $linkFailure")

        // A class that reaches for the meter's holder by name: the loader refuses to even construct such a module.
        val meterGrab = outlawField("Grab", "Grab" + Built.METER_SUFFIX, "m")
        val refused = runCatching { ModuleLoader("Grab", mapOf("Grab" to meterGrab, "Grab" + Built.METER_SUFFIX to meterGrab)) }.exceptionOrNull()
        assertTrue(refused != null, "a module may not define a class under the meter marker: $refused")
    }

    // ── Unloading: a module that keeps the context is collected once it is let go ─────────────────────────────────

    @Test
    fun `a module that keeps its context in a static field is collected after it is unloaded`() {
        val source = """
            import io.github.clemsix6.narrator2.forge.api.*;
            public final class Keeper implements ForgeModule {
                static ModuleContext kept;
                public void start(ModuleContext m) { kept = m; m.log("kept"); }
            }
        """.trimIndent()
        val built = Instrumenter.rewrite("Keeper", ForgeSamples.compile("Keeper", source).classes)
        val host = ModuleHost("Keeper", built, threads, "evil Keeper", timeScale = BusyMachine.TIME_SCALE)
        host.start(FakeContext().context)
        assertTrue(host.disabled == null, "it started: ${host.disabled}")
        val loader = host.unload()
        assertTrue(io.github.clemsix6.narrator2.forge.host.Hosts.collected(listOf(loader)), "the loader (and the module's classes and its kept context) is collected")
    }

    private fun awaitNoLostThread(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (threads.lost.alive() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return threads.lost.alive() == 0
    }

    /** The fault of loading [classes] (as `name`) with the real loader and no guard, and running its static `touch`. */
    private fun linkError(name: String, classes: Map<String, ByteArray>): String {
        val loader = ModuleLoader(name, classes)
        val thrown = runCatching {
            try {
                Class.forName(name, true, loader).getMethod("touch").invoke(null)
            } catch (wrapped: InvocationTargetException) {
                throw wrapped.cause ?: wrapped
            }
        }.exceptionOrNull() ?: fail("$name linked and ran")
        val chain = generateSequence(thrown) { it.cause }.toList()
        val link = chain.firstOrNull { it is NoClassDefFoundError } ?: fail("$name did not fail at link: $thrown")
        return link.message.orEmpty()
    }

    /** A class `name` whose static `touch()` calls `owner.method`: what no guard would let through. */
    private fun outlaw(name: String, owner: String, method: String, descriptor: String): ByteArray =
        ClassWriter(ClassWriter.COMPUTE_MAXS).run {
            visit(Opcodes.V25, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "touch", "()V", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, descriptor, false)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
            toByteArray()
        }

    /** A fabricated holder-named class with a static field `field`: the loader refuses the reserved meter marker. */
    private fun outlawField(name: String, holder: String, field: String): ByteArray =
        ClassWriter(0).run {
            visit(Opcodes.V25, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, holder, null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, field, "Ljava/lang/Object;", null, null).visitEnd()
            visitEnd()
            toByteArray()
        }
}
