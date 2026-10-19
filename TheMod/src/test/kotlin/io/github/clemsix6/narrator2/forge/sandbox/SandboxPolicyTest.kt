package io.github.clemsix6.narrator2.forge.sandbox

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The allow-list itself (M5 §3.3, §16.1): every class and member it names exists in the JDK of now; the guard and the
 * loader give the same answer on every class of `java.base` (the head class `MethodHandles` resolvable by neither,
 * M5 design review, finding 2); and no type of the list exposes a capacity member that is neither refused nor wrapped
 * by the rewriting (finding 1). The guard's behaviour on real bytecode is `BytecodeGuardTest`.
 */
class SandboxPolicyTest {
    @Test
    fun `every class of the allow-list exists in this JDK`() {
        SandboxPolicy.CLASSES.forEach { internal ->
            val name = internal.replace('/', '.')
            assertNotNull(runCatching { Class.forName(name) }.getOrNull(), "$name is on the allow-list but not in this JDK")
        }
    }

    @Test
    fun `every restricted or wrapped member the list names exists on its owner`() {
        val named = SandboxPolicy.NO_OWNER_MEMBER.keys + SandboxPolicy.CAPACITY_METHODS +
            SandboxPolicy.ONLY.flatMap { (owner, members) -> members.map { "$owner.$it" } } +
            SandboxPolicy.BOOTSTRAPS
        named.forEach { entry ->
            val owner = entry.substringBeforeLast('.').replace('/', '.')
            val member = entry.substringAfterLast('.')
            val type = Class.forName(owner)
            val exists = member == "<init>" ||
                type.methods.any { it.name == member } || type.declaredMethods.any { it.name == member } ||
                type.fields.any { it.name == member }
            assertTrue(exists, "$owner.$member is named by the policy but missing from this JDK")
        }
        // The members refused on every owner all exist somewhere in the JDK (a sample of their owners).
        assertTrue(Any::class.java.declaredMethods.any { it.name == "finalize" })
        listOf("wait", "notify", "notifyAll").forEach { m -> assertTrue(Any::class.java.methods.any { it.name == m }, m) }
        listOf("printStackTrace", "getStackTrace", "setStackTrace").forEach { m -> assertTrue(Throwable::class.java.methods.any { it.name == m }, m) }
        assertTrue(java.util.List::class.java.methods.any { it.name == "parallelStream" })
    }

    @Test
    fun `member rules refuse the dangerous members and keep the safe ones`() {
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/System", "exit"))
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/System", "getenv"))
        assertNull(SandboxPolicy.memberRefusal("java/lang/System", "nanoTime"))
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/Class", "getClassLoader"))
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/Class", "forName"))
        assertNull(SandboxPolicy.memberRefusal("java/lang/Class", "getSimpleName"))
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/String", "split"))
        assertNull(SandboxPolicy.memberRefusal("java/lang/String", "repeat"))
        assertNotNull(SandboxPolicy.memberRefusal("java/util/Collections", "nCopies"))
        assertNotNull(SandboxPolicy.memberRefusal("java/util/Random", "ints"))
        assertNull(SandboxPolicy.memberRefusal("java/util/Random", "nextInt"))
        assertNotNull(SandboxPolicy.memberRefusal("java/lang/Object", "wait"))
    }

    @Test
    fun `the type rules name the SDK and the JDK of the list, and refuse the rest with a reason`() {
        assertTrue(SandboxPolicy.mayName("java/lang/String"))
        assertTrue(SandboxPolicy.mayName("java/util/Map\$Entry"))
        assertTrue(SandboxPolicy.mayName("java/util/function/Consumer"))
        assertTrue(SandboxPolicy.mayName("io/github/clemsix6/narrator2/forge/api/World"))
        assertTrue(SandboxPolicy.mayName("io/github/clemsix6/narrator2/forge/api/GameFact\$Chat"))
        assertTrue(SandboxPolicy.mayName("io/github/clemsix6/narrator2/forge/api/test/Bench"))
        listOf(
            "java/io/File", "java/nio/file/Files", "java/net/Socket", "java/lang/reflect/Method", "java/lang/invoke/MethodHandles",
            "java/lang/Thread", "java/util/concurrent/Executors", "java/lang/foreign/Arena", "sun/misc/Unsafe",
            "net/minecraft/server/MinecraftServer", "io/github/clemsix6/narrator2/forge/rt/Meter",
            "io/github/clemsix6/narrator2/forge/Forge", "java/util/regex/Pattern",
        ).forEach { assertNotNull(SandboxPolicy.typeRefusal(it), "$it should be refused") }
    }

    @Test
    fun `the guard and the loader agree on every class of java_base, and MethodHandles is resolvable by neither`() {
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        val base = jrt.getPath("/modules", "java.base")
        val classes = Files.walk(base).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { base.relativize(it).toString().removeSuffix(".class").replace('\\', '/') }
                .filter { !it.endsWith("module-info") }
                .toList()
        }
        assertTrue(classes.size > 4_000, "java.base has ${classes.size} classes")
        classes.forEach { internal ->
            val binary = internal.replace('/', '.')
            val nameable = SandboxPolicy.mayName(internal)
            val resolvable = SandboxPolicy.resolvable(binary)
            if (nameable) assertTrue(resolvable, "$binary is nameable by the guard but not resolvable by the loader")
            if (resolvable && !nameable) {
                assertTrue(binary in SandboxPolicy.LINKAGE_BINARY, "$binary is resolvable but not nameable, yet not a linkage class")
            }
        }
        // Finding 2: the head class is resolvable by neither; Lookup is resolvable for linkage but never nameable.
        assertFalse(SandboxPolicy.mayName("java/lang/invoke/MethodHandles"))
        assertFalse(SandboxPolicy.resolvable("java.lang.invoke.MethodHandles"))
        assertTrue(SandboxPolicy.resolvable("java.lang.invoke.MethodHandles\$Lookup"))
        assertFalse(SandboxPolicy.mayName("java/lang/invoke/MethodHandles\$Lookup"))
        SandboxPolicy.LINKAGE_BINARY.forEach { binary ->
            assertNotNull(runCatching { Class.forName(binary, false, javaClass.classLoader) }.getOrNull(), "$binary is a linkage class but missing")
            assertTrue(SandboxPolicy.resolvable(binary), binary)
            assertFalse(SandboxPolicy.mayName(binary.replace('.', '/')), "$binary must not be nameable")
        }
    }

    @Test
    fun `every capacity constructor and member of a listed type is refused or wrapped by the rewriting - finding 1`() {
        // A new allow-listed container (a collection, a map or a string builder) with an int-first "initial capacity"
        // constructor must be declared a capacity-constructor type (a boxing ctor like Integer(int) is not a container).
        SandboxPolicy.CLASSES.forEach { internal ->
            val type = Class.forName(internal.replace('/', '.'))
            val container = Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type) || type == StringBuilder::class.java
            val hasCapacityCtor = type.constructors.any { it.parameterCount > 0 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            if (container && hasCapacityCtor) {
                assertTrue(internal in SandboxPolicy.CAPACITY_CTOR_TYPES, "$internal has an int-capacity constructor but is not wrapped (Guarded.capacity)")
            }
        }
        SandboxPolicy.CAPACITY_CTOR_TYPES.forEach { internal ->
            val type = Class.forName(internal.replace('/', '.'))
            assertTrue(
                type.constructors.any { it.parameterCount > 0 && it.parameterTypes[0] == Int::class.javaPrimitiveType },
                "$internal is listed as a capacity-constructor type but has no int-first constructor",
            )
        }
        // A capacity verb declared on a buffer or a hash table must be refused or wrapped (owner-scoped, so no false
        // alarm on List.copyOf; declared-only, so an inherited static like LinkedHashMap.newHashMap keeps its owner).
        val bufferVerbs = setOf("ensureCapacity", "setLength", "repeat", "indent")
        listOf("java/lang/StringBuilder", "java/util/ArrayList", "java/lang/String").forEach { internal ->
            Class.forName(internal.replace('/', '.')).declaredMethods
                .filter { it.name in bufferVerbs && java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .forEach { method -> assertCoveredOrRefused(internal, method.name) }
        }
        listOf("java/util/HashMap", "java/util/LinkedHashMap", "java/util/HashSet", "java/util/LinkedHashSet").forEach { internal ->
            Class.forName(internal.replace('/', '.')).declaredMethods
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && java.lang.reflect.Modifier.isPublic(it.modifiers) && it.name.startsWith("new") }
                .forEach { method -> assertCoveredOrRefused(internal, method.name) }
        }
        // nCopies is the refused counterpart; the ranges and copies are wrapped.
        assertNotNull(SandboxPolicy.memberRefusal("java/util/Collections", "nCopies"))
        listOf(
            "java/util/Arrays.copyOf", "java/util/Arrays.copyOfRange", "java/util/stream/IntStream.range",
            "java/util/stream/LongStream.rangeClosed",
        ).forEach { assertTrue(it in SandboxPolicy.CAPACITY_METHODS, "$it must be wrapped") }
    }

    @Test
    fun `annotations, catches and supertypes follow the design`() {
        assertNull(SandboxPolicy.annotationRefusal("Lio/github/clemsix6/narrator2/forge/api/ModuleInfo;"))
        assertNull(SandboxPolicy.annotationRefusal("Ljava/lang/Deprecated;"))
        assertNotNull(SandboxPolicy.annotationRefusal("Ljava/lang/annotation/Retention;"))
        assertNotNull(SandboxPolicy.annotationRefusal("Lio/github/clemsix6/narrator2/forge/api/ForgeModule;"))

        assertNotNull(SandboxPolicy.catchRefusal("java/lang/Throwable"))
        assertNotNull(SandboxPolicy.catchRefusal("java/lang/Error"))
        assertNull(SandboxPolicy.catchRefusal("java/lang/IllegalStateException"))
        assertNull(SandboxPolicy.catchRefusal("java/lang/NoSuchFieldError"))
        assertNotNull(SandboxPolicy.catchRefusal("java/io/IOException"))

        listOf("java/lang/Object", "java/lang/Record", "java/lang/Enum", "java/lang/IllegalStateException").forEach {
            assertNull(SandboxPolicy.superclassRefusal(it), it)
        }
        listOf("java/util/ArrayList", "java/lang/Thread", "io/github/clemsix6/narrator2/forge/api/World").forEach {
            assertNotNull(SandboxPolicy.superclassRefusal(it), it)
        }
    }

    @Test
    fun `a module's top class may not shadow a type it relies on`() {
        listOf("Thread", "Timer", "List", "Map", "World", "Acts", "Integer").forEach { assertNotNull(SandboxPolicy.shadowRefusal(it), it) }
        listOf("TagGame", "OrbitingChickens", "HotPotato").forEach { assertNull(SandboxPolicy.shadowRefusal(it), it) }
    }

    private fun assertCoveredOrRefused(ownerInternal: String, member: String) {
        val wrapped = "$ownerInternal.$member" in SandboxPolicy.CAPACITY_METHODS
        val refused = SandboxPolicy.memberRefusal(ownerInternal, member) != null
        assertTrue(wrapped || refused, "$ownerInternal.$member allocates by a size and is neither wrapped nor refused")
    }
}
