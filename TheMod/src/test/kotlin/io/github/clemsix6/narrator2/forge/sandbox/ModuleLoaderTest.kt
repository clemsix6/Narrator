package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.rt.Meter
import io.github.clemsix6.narrator2.testing.ForgeSamples
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The filtering loader (M5 §3.5, barrier 4): the module's classes, then the allow-list of the JDK, the linkage classes
 * (never the head class `MethodHandles`, design review finding 2), the SDK and the runtime; nothing else, not even a
 * resource; and among the names with the reserved marker, only the holder the rewriting makes (finding 7).
 */
class ModuleLoaderTest {
    private val witness by lazy { Instrumenter.rewrite(ForgeSamples.CLASS, ForgeSamples.classes) }

    @Test
    fun `the witness's classes are defined at their first use, and the SDK and the runtime are the mod's own`() {
        val loader = ModuleLoader(ForgeSamples.CLASS, witness)
        assertEquals(witness.keys, loader.names)
        assertEquals(emptyList(), loader.defined)
        val type = Class.forName(ForgeSamples.CLASS, false, loader)
        assertSame(loader, type.classLoader)
        assertEquals(listOf(type), loader.defined)
        assertSame(ModuleContext::class.java, loader.loadClass(ModuleContext::class.java.name))
        assertSame(Meter::class.java, loader.loadClass(Meter::class.java.name))
        assertSame(String::class.java, loader.loadClass("java.lang.String"))
        assertSame(Map.Entry::class.java, loader.loadClass("java.util.Map\$Entry"))
        assertSame(Class.forName("java.lang.invoke.MethodHandles\$Lookup"), loader.loadClass("java.lang.invoke.MethodHandles\$Lookup"))
    }

    @Test
    fun `it resolves exactly what the policy says on every class of java_base`() {
        val loader = ModuleLoader(ForgeSamples.CLASS, witness)
        val base = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules", "java.base")
        val names = Files.walk(base).use { paths ->
            paths.filter { it.toString().endsWith(".class") && !it.toString().endsWith("module-info.class") }
                .map { base.relativize(it).toString().removeSuffix(".class").replace('\\', '/').replace('/', '.') }
                .toList()
        }
        assertTrue(names.size > 4_000)
        for (name in names) {
            val resolved = runCatching { loader.loadClass(name) }
            assertEquals(SandboxPolicy.resolvable(name), resolved.isSuccess, "$name: ${resolved.exceptionOrNull()}")
        }
        assertFalse(runCatching { loader.loadClass("java.lang.invoke.MethodHandles") }.isSuccess, "never the head class MethodHandles")
        for (outside in listOf("net.minecraft.server.MinecraftServer", "io.github.clemsix6.narrator2.forge.Forge", "java.sql.Connection", "sun.misc.Unsafe")) {
            val refused = assertFailsWith<ClassNotFoundException> { loader.loadClass(outside) }
            assertTrue("is not visible to modules" in refused.message!!, refused.message)
        }
    }

    @Test
    fun `code loaded without the guard still cannot link outside the list`() {
        val targets = listOf(
            "java/lang/invoke/MethodHandles" to "lookup" to "()Ljava/lang/invoke/MethodHandles\$Lookup;",
            "java/io/File" to "listRoots" to "()[Ljava/io/File;",
            "java/lang/Thread" to "currentThread" to "()Ljava/lang/Thread;",
            "io/github/clemsix6/narrator2/forge/ForgeLimits" to "getMODULE_NAME" to "()Lkotlin/text/Regex;",
        )
        for ((target, descriptor) in targets) {
            val (owner, method) = target
            val loader = ModuleLoader("Outlaw", mapOf("Outlaw" to outlaw(owner, method, descriptor)))
            val failed = assertFailsWith<Throwable> {
                try {
                    Class.forName("Outlaw", true, loader).getMethod("touch").invoke(null)
                } catch (wrapped: InvocationTargetException) {
                    throw wrapped.cause!!
                }
            }
            val chain = generateSequence(failed) { it.cause }.toList()
            assertTrue(chain.any { it is NoClassDefFoundError && owner in it.message.orEmpty() }, "$owner.$method: $failed")
        }
    }

    @Test
    fun `it has no resource at all`() {
        val loader = ModuleLoader(ForgeSamples.CLASS, witness)
        assertNull(loader.getResource("java/lang/Object.class"))
        assertNull(loader.getResourceAsStream("${ForgeSamples.CLASS}.class"))
        assertNull(loader.getResourceAsStream("io/github/clemsix6/narrator2/forge/api/ModuleContext.class"))
        assertFalse(loader.getResources("META-INF/MANIFEST.MF").hasMoreElements())
        assertNull(Class.forName(ForgeSamples.CLASS, false, loader).getResourceAsStream("/fabric.mod.json"))
    }

    @Test
    fun `it defines the module's classes only, and under the reserved marker only the rewriting's holder`() {
        val classes = ForgeSamples.classes
        val main = ForgeSamples.CLASS
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes + ("Other" to classes.getValue(main))) }
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes + ("$main\$-evil" to classes.getValue(main))) }
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes - main) }
        assertFailsWith<LoadRefused> { ModuleLoader("tag_game", classes) }
        // A holder with code, or with another field, is not the rewriting's.
        val holder = main + Built.METER_SUFFIX
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes + (holder to classes.getValue(main))) }
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes + (holder to fakeHolder(holder, withMethod = true))) }
        assertFailsWith<LoadRefused> { ModuleLoader(main, classes + (holder to fakeHolder(holder, withMethod = false, field = "n"))) }
        ModuleLoader(main, classes + (holder to fakeHolder(holder, withMethod = false))) // the rewriting's shape
        ModuleLoader(main, witness)
    }

    /** A class `Outlaw` whose static `touch()` calls [owner].[method]: what no guard would let through. */
    private fun outlaw(owner: String, method: String, descriptor: String): ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).run {
        visit(Opcodes.V25, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "Outlaw", null, "java/lang/Object", null)
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

    private fun fakeHolder(name: String, withMethod: Boolean, field: String = Built.METER_FIELD): ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).run {
        visit(Opcodes.V25, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, field, "L${SandboxPolicy.RT_PACKAGE}Meter;", null, null).visitEnd()
        if (withMethod) {
            visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        visitEnd()
        toByteArray()
    }
}
