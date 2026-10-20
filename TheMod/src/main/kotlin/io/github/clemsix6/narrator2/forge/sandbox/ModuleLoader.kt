package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/** Classes a [ModuleLoader] refuses to define (M5 §3.5): the message says which and why, in English. */
class LoadRefused(message: String) : RuntimeException(message)

/**
 * The filtering class loader of one loaded module (M5 §3.5, barrier 4 of §3.2): one per loaded version, in the game as
 * at the bench, with no parent. It defines the module's own classes ([classes], binary names, rewritten), lazily, at
 * their first use; it resolves a name of the JDK through the platform loader **only if** [SandboxPolicy.resolvable]
 * allows it (the allow-list, and the linkage classes of the dynamic calls, never the head class
 * `java.lang.invoke.MethodHandles`: design review, finding 2); the SDK and `forge.rt` through the mod's own loader;
 * and nothing else (`ClassNotFoundException: … is not visible to modules`, a `NoClassDefFoundError` at link). It has no
 * resource at all.
 *
 * What it defines is checked first ([LoadRefused]): the main class and classes nested in it only, and among the names
 * with the reserved marker `$-` only the meter's holder ([Built.meterHolder]) that the rewriting makes — a class
 * without any method and with the one static field of the meter, so that no class handed in under that name can run
 * code (design review, finding 7).
 */
class ModuleLoader(val mainClass: String, classes: Map<String, ByteArray>) : ClassLoader("narrator2-module-$mainClass", null) {
    /** The bytes of the classes not defined yet, by binary name; each is let go once defined. */
    private val pending = HashMap(classes)

    /** The binary names of the module's classes, the holder included. */
    val names: Set<String> = classes.keys.toSet()

    private val loaded = mutableListOf<Class<*>>()

    /** The module's classes defined so far (those whose static fields may hold something). */
    val defined: List<Class<*>> get() = synchronized(this) { loaded.toList() }

    init {
        if (!SIMPLE_NAME.matches(mainClass)) throw LoadRefused("$mainClass is not the name of a module's class")
        if (mainClass !in classes) throw LoadRefused("the module's class $mainClass is missing")
        val holder = mainClass + Built.METER_SUFFIX
        for (name in classes.keys) {
            when {
                name == holder -> checkHolder(name, classes.getValue(name))
                name != mainClass && !name.startsWith("$mainClass\$") ->
                    throw LoadRefused("$name is not $mainClass nor a class nested in it")
                "\$-" in name -> throw LoadRefused("$name carries the marker reserved to the rewriting")
            }
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val found = findLoadedClass(name) ?: define(name) ?: when {
            !SandboxPolicy.resolvable(name) -> throw ClassNotFoundException("$name is not visible to modules")
            name.startsWith("java.") -> getPlatformClassLoader().loadClass(name)
            else -> MOD.loadClass(name)
        }
        if (resolve) resolveClass(found)
        found
    }

    override fun findClass(name: String): Class<*> = throw ClassNotFoundException("$name is not visible to modules")

    override fun getResource(name: String): URL? = null

    override fun getResources(name: String): Enumeration<URL> = Collections.emptyEnumeration()

    override fun findResource(name: String): URL? = null

    override fun findResources(name: String): Enumeration<URL> = Collections.emptyEnumeration()

    private fun define(name: String): Class<*>? {
        val bytes = pending.remove(name) ?: return null
        return defineClass(name, bytes, 0, bytes.size).also { loaded += it }
    }

    /** The holder the rewriting makes: no method at all (no static initializer), one static field, the meter. */
    private fun checkHolder(name: String, bytes: ByteArray) {
        var methods = 0
        val fields = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(access: Int, field: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                fields += "${access and Opcodes.ACC_STATIC != 0} $field $descriptor"
                return null
            }

            override fun visitMethod(access: Int, method: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                methods++
                return null
            }
        }, ClassReader.SKIP_CODE)
        if (methods != 0 || fields != listOf("true ${Built.METER_FIELD} $METER_DESCRIPTOR")) {
            throw LoadRefused("$name is not the meter's holder that the rewriting makes")
        }
    }

    private companion object {
        val SIMPLE_NAME = Regex("[A-Z][A-Za-z0-9]*")
        const val METER_DESCRIPTOR = "L${SandboxPolicy.RT_PACKAGE}Meter;"

        /** The mod's own loader (Knot in the game): the SDK and `forge.rt`. */
        val MOD: ClassLoader = ModuleLoader::class.java.classLoader
    }
}
