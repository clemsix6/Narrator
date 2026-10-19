package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleInfo
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.Param
import io.github.clemsix6.narrator2.forge.api.ParamList
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.forge.api.Text
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.DisabledModules
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.tool.mechanics.DeployModuleArgs
import io.github.clemsix6.narrator2.tool.mechanics.ForgeModuleArgs
import io.github.clemsix6.narrator2.tool.mechanics.StopModuleArgs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.annotation.ElementType
import java.lang.annotation.Repeatable
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.Type as JavaType

/**
 * The contracts of M5 that the tasks share (M5 §4, §11, §16.1): the SDK as a module sees it, checked against the
 * allow-list of the sandbox (§3.3) before it was frozen at version 1 (M5 design review, finding 5), and the Kotlin
 * contracts' refusals.
 */
class ForgeContractsTest {
    /**
     * The exit criterion of M5-01 (M5 design review, finding 5): a representative module, the prototype's adapted to
     * the SDK, compiles with ECJ against `java.base` and the SDK only, and its bytecode names nothing but its own
     * classes, the SDK and the JDK of the allow-list, through the forms ECJ emits for ordinary code.
     */
    @Test
    fun `the witness module compiles against the SDK alone and passes a skeleton of the guard`() {
        val compiled = ForgeSamples.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE)
        assertTrue(compiled.ok, "the witness compiles: ${compiled.problems}")
        assertEquals(emptyList(), compiled.problems.filter { it.startsWith("error") })
        assertEquals(1, Sdk.VERSION)

        val guard = SkeletonGuard(compiled.classes.keys)
        compiled.classes.values.forEach { guard.check(it) }
        assertEquals(emptyList(), guard.violations, "inventory: ${guard.inventory}")

        // What ECJ emits for ordinary code, which the guard (M5-03) and the rewriting (M5-04) must take.
        listOf(
            "indy java/lang/invoke/LambdaMetafactory.metafactory", "indy java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
            "indy java/lang/runtime/ObjectMethods.bootstrap", "indy java/lang/runtime/SwitchBootstraps.typeSwitch",
            "catch java/lang/NoSuchFieldError", "call java/lang/MatchException.<init>", "call java/lang/Class.desiredAssertionStatus",
            "handle java/lang/Integer.sum", "annotation Lio/github/clemsix6/narrator2/forge/api/ModuleInfo;",
            "annotation Lio/github/clemsix6/narrator2/forge/api/ParamList;",
        ).forEach { assertTrue(it in guard.inventory, "ECJ emits $it: ${guard.inventory}") }
        assertEquals(
            setOf(
                "TagGame", "TagGame\$Phase", "TagGame\$Score", "TagGame\$Move", "TagGame\$Touch", "TagGame\$Escape", "TagGame\$Scenarios",
                "TagGame\$1", "TagGame\$1Rank",
            ),
            compiled.classes.keys,
            "the module's classes: its own, nested, anonymous and local ones",
        )
    }

    @Test
    fun `a module sees neither the game, nor the mod, nor the sandbox's runtime`() {
        val compiled = ForgeSamples.compile(
            "Peeker",
            """
            public final class Peeker implements io.github.clemsix6.narrator2.forge.api.ForgeModule {
                net.minecraft.server.MinecraftServer server;
                io.github.clemsix6.narrator2.forge.rt.Meter meter;
                io.github.clemsix6.narrator2.forge.Forge forge;
                public void start(io.github.clemsix6.narrator2.forge.api.ModuleContext ctx) {}
            }
            """.trimIndent(),
        )
        assertFalse(compiled.ok)
        val errors = compiled.problems.filter { it.startsWith("error") }
        assertEquals(listOf(2, 3, 4), errors.map { it.substringAfter(' ').substringBefore(':').toInt() }, "one error per field: $errors")
        assertTrue(errors.all { "cannot be resolved" in it }, "$errors")
    }

    @Test
    fun `the SDK's public signatures name only the SDK and the JDK of the allow-list`() {
        val strangers = sdkClasses().flatMap { type -> signatureTypes(type).filterNot { allowedInSignatures(it) }.map { "${type.name}: ${it.name}" } }
        assertEquals(emptyList(), strangers.distinct())
        SIGNATURE_JDK.filter { it != java.lang.annotation.Annotation::class.java }.forEach { jdk ->
            assertTrue(SkeletonGuard.allows(Type.getInternalName(jdk)), "${jdk.name} is in the SDK's signatures, so a module may name it")
        }
    }

    @Test
    fun `no simple name of the SDK hides a class of java_lang, java_util, java_util_function, java_util_stream or java_time`() {
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        val jdk = listOf("java/lang", "java/util", "java/util/function", "java/util/stream", "java/time").flatMap { pkg ->
            Files.list(jrt.getPath("/modules", "java.base", pkg)).use { files ->
                files.map { it.fileName.toString() }.filter { it.endsWith(".class") && '$' !in it }.map { it.removeSuffix(".class") }.toList()
            }
        }.toSet()
        val sdk = sdkClasses().map { it.simpleName }
        assertTrue(sdk.size > 50, "the SDK's types, nested ones included: $sdk")
        assertEquals(emptyList(), sdk.filter { it in jdk })
    }

    @Test
    fun `the facts are the twenty records of the sealed GameFact, each with its player and its place first`() {
        val facts = GameFact::class.java.permittedSubclasses.toList()
        assertEquals(20, facts.size, "${facts.map { it.simpleName }}")
        facts.forEach { fact ->
            assertTrue(fact.isRecord, fact.simpleName)
            assertEquals(listOf("player", "at"), fact.recordComponents.take(2).map { it.name }, fact.simpleName)
        }
    }

    @Test
    fun `there is no pit and no floor - decision Q4 - and the module's annotations are read from the bytecode only`() {
        assertEquals(listOf("place"), Ground::class.java.declaredMethods.map { it.name })
        val names = sdkClasses().flatMap { type -> type.declaredMethods.map { it.name.lowercase() } }
        assertTrue(names.none { it == "pit" || it == "floor" || it.contains("dig") || it.contains("swap") }, "$names")

        listOf(ModuleInfo::class.java, Param::class.java, ParamList::class.java).forEach { annotation ->
            assertEquals(RetentionPolicy.CLASS, annotation.getAnnotation(Retention::class.java).value, annotation.simpleName)
            assertEquals(listOf(ElementType.TYPE), annotation.getAnnotation(Target::class.java).value.toList(), annotation.simpleName)
        }
        assertEquals(ParamList::class, Param::class.java.getAnnotation(Repeatable::class.java).value)
        assertTrue(ForgeModule::class.java.isInterface && ForgeModule::class.java.methods.map { it.name }.toSet() == setOf("start", "stop"))
    }

    @Test
    fun `places and cells keep the SDK's form of a dimension, and measure within it only`() {
        val here = Place("minecraft:overworld", 1.5, 64.0, -2.25)
        assertEquals("overworld", here.dimension)
        assertEquals(here, Place("Overworld", 1.5, 64.0, -2.25))
        assertEquals("the_nether", Place("nether", 0.0, 0.0, 0.0).dimension)
        assertEquals("the_end", Cell("minecraft:the_end", 0, 0, 0).dimension)
        assertEquals("othermod:moon", Cell("othermod:moon", 0, 0, 0).dimension)
        assertEquals(5.0, here.distance(here.offset(3.0, 4.0, 0.0)), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, here.distance(Place("the_nether", 1.5, 64.0, -2.25)))
        assertEquals(Cell("overworld", 1, 64, -3), here.cell())
        assertEquals(Place("overworld", 1.5, 64.5, -2.5), here.cell().center())
        assertEquals(Cell("overworld", 1, 65, -3), here.cell().offset(0, 1, 0))
        assertContains(assertFailsWith<ForgeRefusal> { Place(null, 0.0, 0.0, 0.0) }.message.orEmpty(), "a place needs a dimension")
        assertFailsWith<ForgeRefusal> { Place("overworld", Double.NaN, 0.0, 0.0) }
        assertFailsWith<ForgeRefusal> { Cell(" ", 0, 0, 0) }
    }

    @Test
    fun `the SDK's helpers - text without regular expressions, spawns, outcomes, who`() {
        assertEquals(listOf("l", "oeuf", "deja"), Text.words("« L'Œuf, déjà ! »"))
        assertEquals(listOf("diamond", "ore"), Text.words("minecraft:diamond_ore").drop(1))
        assertEquals(emptyList(), Text.words(null))
        assertEquals("c est une enclume", Text.normalize("C'est une Enclume !"))
        assertEquals("Bonjou…", Text.cut("Bonjour Bartok", 7))
        assertEquals("1m05s", Text.duration(65_000))

        val chickens = Spawn.of(6)
        assertEquals(Spawn(6, 8, null, false, 5, 0, false), chickens)
        assertEquals(Spawn(6, 4, "Poulette", true, 2, 3, true), chickens.withDistance(4).withName("Poulette").withTarget(true).withLifetimeMinutes(2).withHeight(3).withBaby(true))

        assertTrue(Outcome("ok", "ok: said", null).ok())
        assertEquals(emptyList(), Outcome("ok", "ok: said", null).creatures)
        assertFalse(Outcome("denied", "DENIED (protected): Mimosa is protected", listOf()).ok())
        assertTrue(Who("player", "00000000-0000-0000-0000-00000000b471", "Bartok").isPlayer)
        assertFalse(Who("skeleton", null, null).isPlayer)
    }

    @Test
    fun `a refusal of the Forge or of the modules is invalid, denied or failed, never two at once`() {
        assertEquals(ToolResult.invalid("no module statues"), ForgeRejected(null, "no module statues").toResult())
        assertEquals(ToolResult.denied("arsenal_full", "64 modules"), ForgeRejected("arsenal_full", "64 modules").toResult())
        assertEquals(ToolResult.failed("statues v1 no longer compiles"), ForgeRejected(null, "statues v1 no longer compiles", failed = true).toResult())
        assertFailsWith<IllegalArgumentException> { ForgeRejected("forge_off", "x", failed = true) }
        assertEquals(ToolResult.failed("C-70 failed to start"), MechanicRejected(null, "C-70 failed to start", failed = true).toResult())
        assertEquals(ToolResult.denied("module_limit", "4 modules run"), MechanicRejected("module_limit", "4 modules run").toResult())
    }

    @Test
    fun `the disabled Forge refuses everything with its reason, and its arsenal is empty`() = runBlocking<Unit> {
        val off = DisabledForge("not wired yet")
        assertFalse(off.available)
        assertEquals("not wired yet", off.unavailableReason)
        val refusals = listOf(
            assertFailsWith<ForgeRejected> { off.test(ForgeSamples.NAME, ForgeSamples.SOURCE) },
            assertFailsWith<ForgeRejected> { off.save(ForgeSamples.NAME, ForgeSamples.SOURCE, "a game", "forger T-31") },
            assertFailsWith<ForgeRejected> { off.build(ForgeSamples.NAME, null) },
            assertFailsWith<ForgeRejected> { off.review(ForgeSamples.NAME, 1, approve = true, operator = "Clement") },
        )
        refusals.forEach { assertEquals(ToolResult.denied("forge_off", "the Forge is unavailable: not wired yet"), it.toResult()) }
        assertEquals(emptyList(), off.arsenal.list())
        assertEquals(null, off.arsenal.entry(ForgeSamples.NAME))
        assertEquals(null, off.arsenal.source(ForgeSamples.NAME, 1))
        assertEquals(ForgeStatus(null, "off"), off.status())
        off.stop()
    }

    @Test
    fun `the modules are refused while the engine is off, or not wired`() = runBlocking<Unit> {
        val engineOff = DisabledMechanics("mechanics.enabled is false").modules
        val notWired = DisabledModules(DisabledForge.FORGE_OFF, "the Forge is unavailable: not wired yet")
        val operator = Author(Source.OPERATOR, operator = "Clement")
        for ((modules, expected) in listOf(
            engineOff to ToolResult.denied("mechanics_off", "the mechanics are unavailable: mechanics.enabled is false"),
            notWired to ToolResult.denied("forge_off", "the Forge is unavailable: not wired yet"),
        )) {
            listOf(
                assertFailsWith<MechanicRejected> { modules.deploy(ForgeSamples.deploy, MechanicsSamples.director) },
                assertFailsWith<MechanicRejected> { modules.stop(MechanicsSamples.canon(70), "arrêté", operator) },
                assertFailsWith<MechanicRejected> { modules.halt("maintenance", "Clement") },
                assertFailsWith<MechanicRejected> { modules.resume("Clement") },
            ).forEach { assertEquals(expected, it.toResult()) }
            assertEquals(emptyList(), modules.list())
            assertEquals(null, modules.halted)
            assertEquals(0, modules.leaks)
        }
    }

    /**
     * The Director's three tools of the Forge cost at most 5,000 characters of definitions (M5 §9.1, checked by
     * `ToolCatalogTest` once they exist, M5-20); their arguments take at most this share (1,495 when written), leaving
     * 3,500 to their names, descriptions and the keys of the definitions (about 1,300 characters of descriptions in §9.1).
     */
    @Test
    fun `the arguments of the Director's three new tools build a schema and fit their share of the budget`() {
        val arguments: List<KSerializer<*>> = listOf(ForgeModuleArgs.serializer(), DeployModuleArgs.serializer(), StopModuleArgs.serializer())
        val sizes = arguments.associate { it.descriptor.serialName.substringAfterLast('.') to ToolSchema.of(it.descriptor).toString().length }
        assertTrue(sizes.values.sum() <= ARGUMENTS_BUDGET, "the arguments take ${sizes.values.sum()} characters: $sizes")
    }

    /** Every public type of the SDK's two packages, nested ones included, as the compiled classes of the mod hold them. */
    private fun sdkClasses(): List<Class<*>> = listOf("io/github/clemsix6/narrator2/forge/api", "io/github/clemsix6/narrator2/forge/api/test").flatMap { dir ->
        val folder = Path.of(requireNotNull(ForgeModule::class.java.classLoader.getResource(dir)) { dir }.toURI())
        Files.list(folder).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".class") }.toList()
        }.map { Class.forName(dir.replace('/', '.') + "." + it.removeSuffix(".class")) }
    }.filter { Modifier.isPublic(it.modifiers) }

    /** The types named by [type]'s own public signatures: supertypes, fields, constructors, methods, with their type arguments. */
    private fun signatureTypes(type: Class<*>): Set<Class<*>> {
        val found = mutableSetOf<Class<*>>()
        fun walk(t: JavaType?) {
            when (t) {
                null -> Unit
                is Class<*> -> if (t.isArray) walk(t.componentType) else if (!t.isPrimitive) found += t
                is ParameterizedType -> {
                    walk(t.rawType)
                    t.actualTypeArguments.forEach(::walk)
                }
                is WildcardType -> (t.upperBounds + t.lowerBounds).forEach(::walk)
                is TypeVariable<*> -> t.bounds.forEach(::walk)
                is GenericArrayType -> walk(t.genericComponentType)
            }
        }
        walk(type.genericSuperclass)
        type.genericInterfaces.forEach(::walk)
        type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach { walk(it.genericType) }
        type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.forEach { c -> c.genericParameterTypes.forEach(::walk) }
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { m ->
            walk(m.genericReturnType)
            m.genericParameterTypes.forEach(::walk)
            m.genericExceptionTypes.forEach(::walk)
        }
        return found
    }

    private fun allowedInSignatures(type: Class<*>): Boolean = type.name.startsWith(SDK) || type in SIGNATURE_JDK

    private companion object {
        const val SDK = "io.github.clemsix6.narrator2.forge.api."
        const val ARGUMENTS_BUDGET = 1_500

        /** The JDK's types the SDK's signatures may name: values, the JDK's functional interfaces, and the supertypes of records, enums, the refusal and annotations. */
        val SIGNATURE_JDK: Set<Class<*>> = setOf(
            String::class.java, Any::class.java, Class::class.java, Runnable::class.java, java.util.List::class.java, java.util.Set::class.java,
            java.util.Random::class.java, java.util.function.Consumer::class.java, java.lang.Record::class.java, java.lang.Enum::class.java,
            java.lang.RuntimeException::class.java, java.lang.annotation.Annotation::class.java,
        )
    }
}

/**
 * A skeleton of the guard (M5 §3.3; task M5-03 writes the real one): every type a module's bytecode names, every
 * member it calls on a restricted owner, every dynamic call, constant, `catch` and annotation, against the allow-list
 * of the design. Its own classes are those its compilation produced ([own]), never a prefix of a name.
 */
private class SkeletonGuard(private val own: Set<String>) {
    val violations = mutableListOf<String>()
    val inventory = sortedSetOf<String>()

    fun check(bytes: ByteArray) = ClassReader(bytes).accept(Checker(), ClassReader.SKIP_FRAMES)

    private fun checkType(descriptorOrName: String, where: String) {
        var internal = descriptorOrName.trimStart('[')
        if (internal.startsWith("L") && internal.endsWith(";")) internal = internal.substring(1, internal.length - 1)
        if (internal.length == 1 || internal in own) return
        if (!allows(internal)) violations += "$where: $internal"
    }

    private fun checkDescriptor(descriptor: String, where: String) {
        val parsed = Type.getType(descriptor)
        if (parsed.sort == Type.METHOD) {
            parsed.argumentTypes.forEach { checkType(it.descriptor, where) }
            checkType(parsed.returnType.descriptor, where)
        } else {
            checkType(descriptor, where)
        }
    }

    private fun checkMember(owner: String, member: String, descriptor: String, where: String) {
        inventory += "call $owner.$member"
        if (owner !in own) {
            checkType(owner, where)
            if (member in NO_MEMBER || "$owner.$member" in NO_OWNER_MEMBER) violations += "$where: $owner.$member"
            ONLY[owner]?.let { if (member !in it) violations += "$where: $owner.$member" }
        }
        checkDescriptor(descriptor, where)
    }

    private fun checkConstant(value: Any?, where: String) {
        when (value) {
            is Handle -> {
                inventory += "handle ${value.owner}.${value.name}"
                checkMember(value.owner, value.name, value.desc, where)
            }
            is Type -> if (value.sort == Type.METHOD) checkDescriptor(value.descriptor, where) else checkType(value.descriptor, where)
            is ConstantDynamic -> violations += "$where: dynamic constant"
        }
    }

    private fun checkAnnotation(descriptor: String, where: String): AnnotationVisitor {
        inventory += "annotation $descriptor"
        if (descriptor !in ANNOTATIONS) violations += "$where: annotation $descriptor"
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visitEnum(name: String?, enumDescriptor: String, value: String) {
                checkType(enumDescriptor, where)
            }

            override fun visitAnnotation(name: String?, nested: String): AnnotationVisitor = checkAnnotation(nested, where)

            override fun visitArray(name: String?): AnnotationVisitor = this
        }
    }

    private inner class Checker : ClassVisitor(Opcodes.ASM9) {
        private var current = ""

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            current = name
            superName?.let { checkType(it, "$name extends") }
            interfaces?.forEach { checkType(it, "$name implements") }
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = checkAnnotation(descriptor, current)

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            val where = "$current.$name"
            checkType(descriptor, where)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor = checkAnnotation(annotation, where)
            }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            val where = "$current.$name"
            if (access and (Opcodes.ACC_NATIVE or Opcodes.ACC_SYNCHRONIZED) != 0) violations += "$where: native or synchronized"
            checkDescriptor(descriptor, where)
            exceptions?.forEach { checkType(it, "$where throws") }
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor = checkAnnotation(annotation, where)

                override fun visitInsn(opcode: Int) {
                    if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) violations += "$where: monitor"
                }

                override fun visitTypeInsn(opcode: Int, operand: String) {
                    checkType(operand, where)
                }

                override fun visitFieldInsn(opcode: Int, owner: String, field: String, fieldDescriptor: String) {
                    if (owner !in own) checkType(owner, where)
                    checkType(fieldDescriptor, where)
                }

                override fun visitMethodInsn(opcode: Int, owner: String, method: String, methodDescriptor: String, isInterface: Boolean) {
                    checkMember(owner, method, methodDescriptor, where)
                }

                override fun visitInvokeDynamicInsn(call: String, callDescriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                    val key = "${bootstrap.owner}.${bootstrap.name}"
                    inventory += "indy $key"
                    if (key !in BOOTSTRAPS) violations += "$where: invokedynamic $key"
                    checkDescriptor(callDescriptor, where)
                    arguments.forEach { checkConstant(it, where) }
                }

                override fun visitLdcInsn(value: Any?) {
                    if (value is Handle) violations += "$where: method handle constant" else checkConstant(value, where)
                }

                override fun visitMultiANewArrayInsn(arrayDescriptor: String, numDimensions: Int) {
                    violations += "$where: multi-dimensional array"
                }

                override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, caught: String?) {
                    if (caught == null) return
                    inventory += "catch $caught"
                    if (caught in NO_CATCH) violations += "$where: catch $caught" else checkType(caught, where)
                }

                override fun visitLocalVariable(variable: String, variableDescriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                    checkType(variableDescriptor, where)
                }
            }
        }
    }

    companion object {
        private val CLASSES: Set<String> = (
            listOf(
                "Object", "String", "StringBuilder", "CharSequence", "Math", "StrictMath", "Integer", "Long", "Short", "Byte", "Double", "Float",
                "Boolean", "Character", "Number", "Enum", "Record", "Comparable", "Iterable", "Runnable", "Void", "Cloneable", "Exception",
                "RuntimeException", "IllegalArgumentException", "IllegalStateException", "NullPointerException", "IndexOutOfBoundsException",
                "ArrayIndexOutOfBoundsException", "StringIndexOutOfBoundsException", "UnsupportedOperationException", "ArithmeticException",
                "ClassCastException", "NumberFormatException", "MatchException", "IncompatibleClassChangeError", "NoSuchFieldError", "AssertionError",
                "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface", "SafeVarargs", "Class", "System", "Throwable",
            ).map { "java/lang/$it" } +
                listOf(
                    "ArrayList", "LinkedList", "ArrayDeque", "HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet", "EnumMap",
                    "EnumSet", "PriorityQueue", "List", "Set", "Map", "Collection", "Deque", "Queue", "Iterator", "ListIterator", "Comparator",
                    "Collections", "Arrays", "Objects", "Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "Random", "StringJoiner", "UUID",
                    "Locale", "NoSuchElementException", "ConcurrentModificationException", "SequencedCollection", "SequencedSet", "SequencedMap",
                    "NavigableMap", "NavigableSet", "SortedMap", "SortedSet", "IntSummaryStatistics", "LongSummaryStatistics", "DoubleSummaryStatistics",
                ).map { "java/util/$it" } +
                listOf("Stream", "IntStream", "LongStream", "DoubleStream", "BaseStream", "Collector", "Collectors").map { "java/util/stream/$it" } +
                listOf("ThreadLocalRandom", "atomic/AtomicInteger", "atomic/AtomicLong", "atomic/AtomicBoolean", "atomic/AtomicReference").map { "java/util/concurrent/$it" } +
                listOf(
                    "Duration", "Instant", "LocalDate", "LocalTime", "LocalDateTime", "ZonedDateTime", "ZoneId", "DayOfWeek", "Month",
                    "temporal/ChronoUnit", "format/DateTimeFormatter",
                ).map { "java/time/$it" }
            ).toSet()

        /** Whole packages: their classes, not their subpackages'. */
        private val PACKAGES = listOf("java/util/function/", "io/github/clemsix6/narrator2/forge/api/", "io/github/clemsix6/narrator2/forge/api/test/")

        private val NO_MEMBER = setOf(
            "wait", "notify", "notifyAll", "finalize", "parallelStream", "parallel", "parallelSort", "parallelSetAll", "parallelPrefix",
            "printStackTrace", "getStackTrace", "setStackTrace",
        )
        private val NO_OWNER_MEMBER = setOf(
            "java/lang/Integer.getInteger", "java/lang/Long.getLong", "java/lang/Boolean.getBoolean", "java/util/Locale.setDefault",
            "java/lang/String.matches", "java/lang/String.replaceAll", "java/lang/String.replaceFirst", "java/lang/String.split",
            "java/util/stream/Stream.iterate", "java/util/stream/Stream.generate", "java/util/stream/IntStream.iterate", "java/util/stream/IntStream.generate",
            "java/util/stream/LongStream.iterate", "java/util/stream/LongStream.generate", "java/util/stream/DoubleStream.iterate",
            "java/util/stream/DoubleStream.generate", "java/util/Random.ints", "java/util/Random.longs", "java/util/Random.doubles",
            "java/util/concurrent/ThreadLocalRandom.ints", "java/util/concurrent/ThreadLocalRandom.longs", "java/util/concurrent/ThreadLocalRandom.doubles",
            "java/util/Collections.nCopies", "java/util/UUID.randomUUID",
        )
        private val ONLY = mapOf(
            "java/lang/System" to setOf("nanoTime", "currentTimeMillis", "arraycopy", "identityHashCode", "lineSeparator"),
            "java/lang/Class" to setOf("getName", "getSimpleName", "isInstance", "cast", "hashCode", "equals", "toString", "desiredAssertionStatus"),
            "java/lang/Throwable" to setOf("<init>", "getMessage", "getLocalizedMessage", "getCause", "toString", "addSuppressed"),
        )
        private val BOOTSTRAPS = setOf(
            "java/lang/invoke/LambdaMetafactory.metafactory", "java/lang/invoke/LambdaMetafactory.altMetafactory",
            "java/lang/invoke/StringConcatFactory.makeConcatWithConstants", "java/lang/runtime/ObjectMethods.bootstrap",
            "java/lang/runtime/SwitchBootstraps.typeSwitch", "java/lang/runtime/SwitchBootstraps.enumSwitch",
        )
        private val NO_CATCH = setOf("java/lang/Throwable", "java/lang/Error")
        private val ANNOTATIONS = setOf(
            "Lio/github/clemsix6/narrator2/forge/api/ModuleInfo;", "Lio/github/clemsix6/narrator2/forge/api/Param;",
            "Lio/github/clemsix6/narrator2/forge/api/ParamList;", "Ljava/lang/Override;", "Ljava/lang/Deprecated;", "Ljava/lang/SuppressWarnings;",
            "Ljava/lang/FunctionalInterface;", "Ljava/lang/SafeVarargs;",
        )

        /** Whether a module may name the class [internalName] (`java/util/Map$Entry` follows `java/util/Map`). */
        fun allows(internalName: String): Boolean {
            val top = internalName.substringBefore('$')
            return top in CLASSES || PACKAGES.any { top.startsWith(it) && '/' !in top.removePrefix(it) }
        }
    }
}
