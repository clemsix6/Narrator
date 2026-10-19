package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.testing.ForgeSamples
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on real bytecode (M5 §3.3, §16.1): the witness is clean, each refusal of the design fires on code ECJ
 * actually emits (compiled here against the SDK, as the real pipeline does), and the module's shape is enforced on
 * fabricated classes (the meter marker, a stray top-level class, too many classes). "A module's own classes" is the
 * set the compilation produced, never a prefix of a name (M5 design review, finding 7).
 */
class BytecodeGuardTest {
    @Test
    fun `the witness module passes the real guard`() {
        val compiled = ForgeSamples.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE)
        assertTrue(compiled.ok, "the witness compiles: ${compiled.problems}")
        val violations = BytecodeGuard().check(compiled.classes)
        assertEquals(emptyList(), violations, "the witness must stay clean")
    }

    @Test
    fun `the forms a legitimate module may use are accepted`() {
        // Records, sealed pattern switch, enum switch, string switch, lambdas, method refs, streams, joining, format,
        // an anonymous class, a local class, assert, try/finally — none is refused.
        assertEquals(emptyList(), BytecodeGuard().check(ForgeSamples.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE).classes))
    }

    @Test
    fun `the full static battery of malicious modules is refused by the compiler or the guard`() {
        // The 47 cases of the prototype's static battery (.research/spikes/m5), each refused by the compiler (a type
        // the module never sees) or the guard. Names are prefixed so none shadows the type it reaches for.
        val guard = listOf(
            Case("FileDelete", body = "new java.io.File(\"x\").delete();", keyword = "files"),
            Case("FilesRead", body = "try { java.nio.file.Files.readString(java.nio.file.Path.of(\"x\")); } catch (Exception e) { }", keyword = "files"),
            Case("Socket", body = "try { new java.net.Socket(\"h\", 80); } catch (Exception e) { }", keyword = "network"),
            Case("Exec", body = "try { Runtime.getRuntime().exec(new String[] {\"calc\"}); } catch (Exception e) { }", keyword = "allow-list"),
            Case("ProcessBuilder", body = "try { new ProcessBuilder(\"calc\").start(); } catch (Exception e) { }", keyword = "allow-list"),
            Case("ForName", body = "try { Class.forName(\"java.lang.Runtime\"); } catch (Exception e) { }", keyword = "not available"),
            Case("DeclaredMethods", body = "String.class.getDeclaredMethods();", keyword = "not available"),
            Case("Lookup", body = "java.lang.invoke.MethodHandles.lookup();", keyword = "reflection or method handles"),
            Case("Thread", body = "new Thread(() -> { }).start();", keyword = "threads"),
            Case("VirtualThread", body = "Thread.ofVirtual().start(() -> { });", keyword = "threads"),
            Case("Executor", body = "java.util.concurrent.Executors.newSingleThreadExecutor();", keyword = "threads"),
            Case("Async", body = "java.util.concurrent.CompletableFuture.runAsync(() -> { });", keyword = "threads"),
            Case("Exit", body = "System.exit(0);", keyword = "not available"),
            Case("Getenv", body = "String s = System.getenv(\"X\");", keyword = "not available"),
            Case("Property", body = "String s = System.getProperty(\"user.home\");", keyword = "not available"),
            Case("Out", body = "System.out.println(\"x\");", keyword = "files"),
            Case("GetInteger", body = "Integer.getInteger(\"x\");", keyword = "system properties"),
            Case("ThreadLocal", body = "java.lang.ThreadLocal<String> t = new java.lang.ThreadLocal<>(); t.set(\"x\");", keyword = "threads"),
            Case("Monitor", body = "Object o = new Object(); synchronized (o) { int i = 1; }", keyword = "monitors"),
            Case("CatchThrowable", body = "try { Math.abs(1); } catch (Throwable t) { }", keyword = "errors"),
            Case("CatchError", body = "try { Math.abs(1); } catch (Error t) { }", keyword = "errors"),
            Case("Parallel", body = "List.of(1, 2).parallelStream().count();", keyword = "parallel"),
            Case("Regex", body = "boolean b = \"aaa\".matches(\"(a+)+b\");", keyword = "regular expressions"),
            Case("ServiceLoader", body = "java.util.ServiceLoader.load(Runnable.class);", keyword = "allow-list"),
            Case("SystemLoader", body = "ClassLoader.getSystemClassLoader();", keyword = "allow-list"),
            Case("Native", members = "public native void x();", keyword = "native"),
            Case("Finalize", members = "protected void finalize() { }", keyword = "finalizers"),
            Case("SyncMethod", members = "public synchronized void s() { }", keyword = "monitors"),
            Case("GetClassLoader", body = "Object.class.getClassLoader();", keyword = "not available"),
            Case("UtilTimer", body = "new java.util.Timer();", keyword = "allow-list"),
            Case("ReflectArray", body = "java.lang.reflect.Array.newInstance(int.class, 3);", keyword = "reflection"),
            Case("Scanner", body = "new Scanner(\"x\");", keyword = "allow-list"),
            Case("WeakRef", body = "new java.lang.ref.WeakReference<Object>(ctx);", keyword = "allow-list"),
            Case("TryResources", body = "try (AutoCloseable c = () -> { }) { int i = 1; } catch (Exception e) { }", keyword = "allow-list"),
            Case("MultiArray", body = "int[][] a = new int[3][3];", keyword = "one array dimension"),
            Case("MethodRefGc", body = "Runnable r = System::gc; r.run();", keyword = "not available"),
            Case("Wait", body = "try { ctx.wait(); } catch (Exception e) { }", keyword = "block"),
            Case("Foreign", body = "java.lang.foreign.Arena.ofConfined();", keyword = "native or internal"),
            Case("ExtendsTimer", members = "static final class T extends java.util.Timer { }", keyword = "extends"),
            Case("LocaleDefault", body = "Locale.setDefault(Locale.ROOT);", keyword = "whole server"),
            Case("RuntimeVersion", body = "Runtime.version();", keyword = "allow-list"),
            Case("StackTrace", body = "int n = new RuntimeException().getStackTrace().length;", keyword = "stack traces"),
            Case("InvocationHandler", members = "static final class H implements java.lang.reflect.InvocationHandler { public Object invoke(Object p, java.lang.reflect.Method x, Object[] a) { return null; } }", keyword = "reflection"),
            Case("NCopies", body = "java.util.Collections.nCopies(5, \"x\");", keyword = "huge list"),
        )
        val compiler = listOf(
            Case("Unsafe", members = "sun.misc.Unsafe u;"),
            Case("Script", body = "new javax.script.ScriptEngineManager();"),
            Case("Rt", body = "new io.github.clemsix6.narrator2.forge.rt.Meter(null);"),
            Case("Minecraft", members = "net.minecraft.server.MinecraftServer s;"),
        )
        for (case in guard) {
            val compiled = ForgeSamples.compile("Evil${case.name}", source(case))
            assertTrue(compiled.ok, "${case.name} should compile so the guard is the barrier: ${compiled.problems}")
            val violations = BytecodeGuard().check(compiled.classes)
            assertTrue(violations.isNotEmpty(), "${case.name} must be refused by the guard")
            assertTrue(
                violations.any { it.reason.contains(case.keyword, ignoreCase = true) },
                "${case.name} should be refused with a reason about '${case.keyword}', got ${violations.map { it.reason }}",
            )
        }
        for (case in compiler) {
            assertTrue(!ForgeSamples.compile("Evil${case.name}", source(case)).ok, "${case.name} should not compile: the compiler is the barrier")
        }
    }

    @Test
    fun `a module may implement only ForgeModule from the SDK`() {
        val compiled = ForgeSamples.compile(
            "FakeWorld",
            """
            import io.github.clemsix6.narrator2.forge.api.*;
            public final class FakeWorld implements ForgeModule, java.util.function.Consumer<String>, Events {
                public void start(ModuleContext ctx) { }
                public void accept(String s) { }
                public <F extends GameFact> Subscription on(Class<F> type, java.util.function.Consumer<F> handler) { return null; }
            }
            """.trimIndent(),
        )
        assertTrue(compiled.ok, "${compiled.problems}")
        val violations = BytecodeGuard().check(compiled.classes)
        assertTrue(
            violations.any { it.reason.contains("only ForgeModule", ignoreCase = true) },
            "implementing Events must be refused; Consumer is allowed: ${violations.map { it.what to it.reason }}",
        )
        assertTrue(violations.none { it.what.contains("Consumer") }, "implementing a JDK functional interface is allowed")
    }

    @Test
    fun `a module cannot be named after a type it relies on`() {
        val violations = BytecodeGuard().check(
            ForgeSamples.compile("Thread", "public final class Thread implements io.github.clemsix6.narrator2.forge.api.ForgeModule { public void start(io.github.clemsix6.narrator2.forge.api.ModuleContext ctx) { } }").classes,
        )
        assertTrue(violations.any { it.reason.contains("mask", ignoreCase = true) }, "a module named Thread must be refused: ${violations.map { it.reason }}")
    }

    @Test
    fun `the module's shape is enforced on fabricated classes`() {
        val guard = BytecodeGuard()

        assertEquals(emptyList(), guard.check(mapOf("Top" to clazz("Top"))), "one clean top-level class is fine")

        val meter = "Top" + Built.METER_SUFFIX
        assertTrue(
            guard.check(mapOf("Top" to clazz("Top"), meter to clazz(meter))).any { it.reason.contains("meter marker") },
            "a class with the reserved meter marker is refused (finding 7)",
        )

        assertTrue(
            guard.check(mapOf("Top" to clazz("Top"), "Other" to clazz("Other"))).any { it.reason.contains("exactly one top-level") },
            "two top-level classes are refused",
        )

        assertTrue(
            guard.check(mapOf("Top" to clazz("Top"), "Other\$Nested" to clazz("Other\$Nested"))).any { it.reason.contains("only its own") },
            "a class not nested in the module's own top class is refused",
        )

        val tooMany = buildMap {
            put("Top", clazz("Top"))
            repeat(ForgeLimits.MAX_CLASSES + 1) { put("Top\$$it", clazz("Top\$$it")) }
        }
        assertTrue(guard.check(tooMany).any { it.reason.contains("at most ${ForgeLimits.MAX_CLASSES} classes") }, "too many classes is refused")
    }

    private data class Case(val name: String, val body: String = "", val members: String = "", val keyword: String = "")

    /** The class is named `Evil…` so a case never shadows the type it reaches for (`new Thread(…)` means java.lang.Thread). */
    private fun source(case: Case): String =
        "import io.github.clemsix6.narrator2.forge.api.*;\nimport java.util.*;\npublic final class Evil${case.name} implements ForgeModule {\n${case.members}\npublic void start(ModuleContext ctx) {\n${case.body}\n}\n}\n"

    /** A minimal, readable class file (its bytes need only be read by the guard, never loaded). */
    private fun clazz(internal: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internal, null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}
