package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Bytecode the rewriting cannot bound (M5 §3.4); the message is for the forger. */
class RewriteRefused(message: String) : RuntimeException(message)

/**
 * A member of the JDK the rewriting sends through `Guarded` (M5 §3.3, « réécrits, donc permis »): on [owner] and its
 * subtypes for a method (a static one too: `LinkedHashMap.newHashMap` is `HashMap`'s), on [owner] alone for a
 * constructor (`<init>`).
 */
data class RewrittenMember(val owner: String, val name: String, val descriptor: String)

/**
 * The rewriting of a module's bytecode (M5 §3.4), after the guard (M5 §3.3) and before the loader (M5 §3.5), with ASM
 * and **without recomputing the stack map frames** (`COMPUTE_MAXS`): each insertion leaves the operand stack and the
 * locals as they were at every frame, and adds no branch.
 *
 * - A holder class `<Main>$-meter` (a name no Java source can write), one static field `m`: the module's
 *   `forge.rt.Meter`, set by the host before any code of the module runs ([Built.meterHolder], [Built.METER_FIELD]).
 * - Each method: `m.enter()` at its entry, `m.exit()` before each return, `m.tick()` before each backward jump or
 *   switch, and at the entry of each exception handler (a loop through a handler has no jump of its own).
 * - `newarray`, `anewarray`: `Guarded.array(n)` first.
 * - The constructors that reserve a capacity at once ([SandboxPolicy.CAPACITY_CTOR_TYPES]): `Guarded.capacity(n)` on
 *   that argument, wherever it sits on the stack; `StringBuilder(CharSequence)`: a CharSequence whose length can be
 *   trusted.
 * - Every member of [rewritten] ([SandboxPolicy.CAPACITY_METHODS], and the growth and texts that only this rewriting
 *   bounds), called on a type of the JDK: a static method of `Guarded` in its place, the receiver first for an
 *   instance method. `toString()` on any type of the JDK but `String`, `StringBuilder` and the boxes.
 * - `invokedynamic`: a concatenation and a record's `toString` bootstrap through `Guarded` (each object in them made a
 *   text within the limit); a lambda's method reference to anything but the module's own code goes through a private
 *   bridge in the class, rewritten like the rest, so that `list::add` is guarded and `List::stream` ticks.
 *
 * It refuses ([RewriteRefused]) what it cannot bound: a `multianewarray`; a class of the module that is a
 * `Collection`, a `Map` or a `CharSequence`, which the guard permits (the JDK trusts their `size()` and `length()`:
 * `new HashSet<>(lying)` allocates the size it is told, `lying.chars().toArray()` the length); an exception handler
 * inside its own range (a tripped meter would throw there forever); module code that names the holder or `forge.rt`
 * (only the rewriting does). Pure and thread-safe: it runs on the compiler's thread.
 */
object Instrumenter {
    private const val RT = SandboxPolicy.RT_PACKAGE
    private const val METER = RT + "Meter"
    private const val GUARDED = RT + "Guarded"
    private const val METER_DESCRIPTOR = "L$METER;"

    private const val OBJECT = "java/lang/Object"
    private const val STRING = "java/lang/String"
    private const val BUILDER = "java/lang/StringBuilder"
    private const val COLLECTION = "java/util/Collection"
    private const val LIST = "java/util/List"
    private const val MAP = "java/util/Map"
    private const val SET = "java/util/Set"
    private const val ARRAYS = "java/util/Arrays"
    private const val COLLECTIONS = "java/util/Collections"
    private const val STREAM = "java/util/stream/Stream"
    private const val COLLECTORS = "java/util/stream/Collectors"
    private const val OBJECT_TYPE = "Ljava/lang/Object;"

    private const val CONCAT_BOOTSTRAP =
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"
    private const val RECORD_BOOTSTRAP =
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"

    /**
     * Rewrites [classes] (binary name → bytecode, as ECJ produced them, past the guard) of the module whose main class
     * is [mainClass]: the same names, rewritten, and the holder [Built.METER_SUFFIX] of the main class.
     */
    fun rewrite(mainClass: String, classes: Map<String, ByteArray>): Map<String, ByteArray> {
        val holder = mainClass + Built.METER_SUFFIX
        if (holder in classes) throw RewriteRefused("a module may not define $holder: the rewriting makes it")
        val readers = classes.mapValues { (_, bytes) -> ClassReader(bytes) }
        val module = Module(readers.values.map { it.className }.toSet(), internal(holder))
        refuseContainers(readers, module.own)
        val rewritten = LinkedHashMap<String, ByteArray>()
        readers.forEach { (name, reader) ->
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            reader.accept(ClassRewriter(writer, module), 0)
            rewritten[name] = writer.toByteArray()
        }
        rewritten[holder] = holderClass(module.holder, readers[mainClass]?.readUnsignedShort(6) ?: Opcodes.V25)
        return rewritten
    }

    /** The members of the JDK sent through `Guarded`, besides `toString()` on any type of the JDK and the bootstraps: for the guard's list (M5-03). */
    val rewritten: List<RewrittenMember> by lazy { CALLS.map { it.member } + CONSTRUCTORS.values.map { it.member } }

    private fun internal(binaryName: String) = binaryName.replace('.', '/')

    private fun holderClass(name: String, version: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(version, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_SYNTHETIC, name, null, OBJECT, null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, Built.METER_FIELD, METER_DESCRIPTOR, null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    // ── What a module's class may not be ────────────────────────────────────────────────────────────────────────

    private val CONTAINERS = listOf(Collection::class.java, Map::class.java, CharSequence::class.java)

    private fun refuseContainers(readers: Map<String, ClassReader>, own: Set<String>) {
        val supertypes = readers.values.associate { it.className to listOfNotNull(it.superName) + it.interfaces }
        for (start in supertypes.keys) {
            val seen = HashSet<String>()
            val pending = ArrayDeque(supertypes.getValue(start))
            while (pending.isNotEmpty()) {
                val type = pending.removeFirst()
                if (!seen.add(type)) continue
                if (type in own) {
                    pending += supertypes[type].orEmpty()
                    continue
                }
                val jdk = jdkClass(type) ?: continue
                if (CONTAINERS.any { it.isAssignableFrom(jdk) }) {
                    throw RewriteRefused(
                        "${start.replace('/', '.')} may not be a ${jdk.name}: a module's class may not be a collection, a map or a CharSequence " +
                            "(the JDK trusts their sizes); hold one in a field instead",
                    )
                }
            }
        }
    }

    // ── The JDK's classes, by internal name ─────────────────────────────────────────────────────────────────────

    private val jdkClasses = ConcurrentHashMap<String, Any>()
    private val MISSING = Any()

    /** The JDK's class [internalName], loaded without being initialized; null for any other name. */
    private fun jdkClass(internalName: String): Class<*>? {
        if (!internalName.startsWith("java/")) return null
        val found = jdkClasses.computeIfAbsent(internalName) {
            runCatching { Class.forName(it.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()) }.getOrDefault(MISSING)
        }
        return found as? Class<*>
    }

    /** Whether a call site can name [type]: a primitive, a public class, or an array of one. */
    private fun nameable(type: Class<*>): Boolean = when {
        type.isArray -> nameable(type.componentType)
        else -> type.isPrimitive || Modifier.isPublic(type.modifiers)
    }

    private fun subtype(owner: String, base: String): Boolean {
        if (owner == base) return true
        val ownerClass = jdkClass(owner) ?: return false
        val baseClass = jdkClass(base) ?: return false
        return baseClass.isAssignableFrom(ownerClass)
    }

    // ── The members sent through Guarded ────────────────────────────────────────────────────────────────────────

    private enum class Form { STATIC, INSTANCE }

    /** [member] replaced by `Guarded.[guarded]`: the same descriptor for a static method, the receiver first for an instance one. */
    private class CallRule(val member: RewrittenMember, val form: Form, val guarded: String) {
        val guardedDescriptor = if (form == Form.STATIC) member.descriptor else "(L${member.owner};" + member.descriptor.substring(1)
    }

    /**
     * A constructor whose argument is checked by `Guarded.[check]` ([checkDescriptor]: it takes the argument and gives
     * it back) before the call: a capacity [depth] category-1 values below the top of the stack, or, at the top, a
     * CharSequence to trust or the collection or map a hash table copies.
     */
    private class ConstructorRule(val member: RewrittenMember, val depth: Int = 0, val check: String = "capacity", val checkDescriptor: String = "(I)I")

    private fun instance(owner: String, name: String, descriptor: String, guarded: String = name) =
        CallRule(RewrittenMember(owner, name, descriptor), Form.INSTANCE, guarded)

    private fun static(owner: String, name: String, descriptor: String, guarded: String = name) =
        CallRule(RewrittenMember(owner, name, descriptor), Form.STATIC, guarded)

    /**
     * The capacities the guard permits because they are wrapped here ([SandboxPolicy.CAPACITY_METHODS], the one list):
     * every public overload the JDK declares under each name, sent to the `Guarded` method of the same name.
     */
    private val CAPACITY_CALLS: List<CallRule> = SandboxPolicy.CAPACITY_METHODS.flatMap { member ->
        val owner = member.substringBeforeLast('.')
        val name = member.substringAfterLast('.')
        val type = checkNotNull(jdkClass(owner)) { "no $owner in the JDK" }
        // A covariant bridge names the JDK's package-private AbstractStringBuilder: no call site can name it.
        (type.methods.asSequence() + type.declaredMethods.asSequence())
            .filter { method -> method.name == name && Modifier.isPublic(method.modifiers) && (method.parameterTypes + method.returnType).all(::nameable) }
            .map { Type.getMethodDescriptor(it) to Modifier.isStatic(it.modifiers) }
            .distinct()
            .map { (descriptor, isStatic) -> if (isStatic) static(owner, name, descriptor) else instance(owner, name, descriptor) }
            .toList()
    }

    /** What the guard permits and only the rewriting bounds: growth, texts, views (M5 §3.4; M5-01 and M5-03 handoffs). */
    private val CALLS: List<CallRule> = CAPACITY_CALLS + buildList {
        // Collections: 10,000 elements each.
        add(instance(COLLECTION, "add", "(Ljava/lang/Object;)Z"))
        add(instance(COLLECTION, "addAll", "(Ljava/util/Collection;)Z"))
        add(instance(LIST, "add", "(ILjava/lang/Object;)V"))
        add(instance(LIST, "addAll", "(ILjava/util/Collection;)Z"))
        add(instance("java/util/SequencedCollection", "addFirst", "(Ljava/lang/Object;)V"))
        add(instance("java/util/SequencedCollection", "addLast", "(Ljava/lang/Object;)V"))
        add(instance("java/util/Queue", "offer", "(Ljava/lang/Object;)Z"))
        add(instance("java/util/Deque", "offerFirst", "(Ljava/lang/Object;)Z"))
        add(instance("java/util/Deque", "offerLast", "(Ljava/lang/Object;)Z"))
        add(instance("java/util/Deque", "push", "(Ljava/lang/Object;)V"))
        add(instance(LIST, "subList", "(II)Ljava/util/List;"))
        add(instance(LIST, "listIterator", "()Ljava/util/ListIterator;"))
        add(instance(LIST, "listIterator", "(I)Ljava/util/ListIterator;"))
        add(static("java/util/Collections", "addAll", "(Ljava/util/Collection;[Ljava/lang/Object;)Z"))
        add(static("java/util/Collections", "nCopies", "(ILjava/lang/Object;)Ljava/util/List;"))
        add(instance(MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance(MAP, "putAll", "(Ljava/util/Map;)V"))
        add(instance(MAP, "putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance(MAP, "merge", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
        add(instance(MAP, "compute", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
        add(instance(MAP, "computeIfAbsent", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
        add(instance("java/util/SequencedMap", "putFirst", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance("java/util/SequencedMap", "putLast", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        // Builders of streams: as many elements as a collection.
        add(static("java/util/stream/Stream", "builder", "()Ljava/util/stream/Stream\$Builder;"))
        add(static("java/util/stream/IntStream", "builder", "()Ljava/util/stream/IntStream\$Builder;", "intBuilder"))
        add(static("java/util/stream/LongStream", "builder", "()Ljava/util/stream/LongStream\$Builder;", "longBuilder"))
        add(static("java/util/stream/DoubleStream", "builder", "()Ljava/util/stream/DoubleStream\$Builder;", "doubleBuilder"))
        // Texts: 65,536 characters, the text of a whole collection, map or array included.
        add(instance(OBJECT, "toString", "()Ljava/lang/String;"))
        add(static(STRING, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;"))
        add(static("java/util/Objects", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", "valueOf"))
        add(static("java/util/Objects", "toString", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"))
        for (element in listOf("Z", "B", "C", "S", "I", "J", "F", "D", "Ljava/lang/Object;")) {
            add(static(ARRAYS, "toString", "([$element)Ljava/lang/String;", "arrayToString"))
        }
        add(static(ARRAYS, "deepToString", "([Ljava/lang/Object;)Ljava/lang/String;"))
        add(static(STRING, "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
        add(static(STRING, "format", "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
        add(instance(STRING, "formatted", "([Ljava/lang/Object;)Ljava/lang/String;"))
        add(static(STRING, "join", "(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;"))
        add(static(STRING, "join", "(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;"))
        add(static("java/util/stream/Collectors", "joining", "()Ljava/util/stream/Collector;"))
        add(static("java/util/stream/Collectors", "joining", "(Ljava/lang/CharSequence;)Ljava/util/stream/Collector;"))
        add(static("java/util/stream/Collectors", "joining", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/util/stream/Collector;"))
        add(instance(STRING, "concat", "(Ljava/lang/String;)Ljava/lang/String;"))
        add(instance(STRING, "replace", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"))
        val text = "L$BUILDER;"
        for (argument in listOf("Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;II", "[C", "[CII", "Z", "C", "I", "J", "F", "D")) {
            add(instance(BUILDER, "append", "($argument)$text"))
            add(instance(BUILDER, "insert", "(I$argument)$text"))
        }
        add(instance(BUILDER, "appendCodePoint", "(I)$text"))
        add(instance(BUILDER, "replace", "(IILjava/lang/String;)$text"))
        add(instance("java/util/StringJoiner", "add", "(Ljava/lang/CharSequence;)Ljava/util/StringJoiner;"))
        add(instance("java/util/StringJoiner", "merge", "(Ljava/util/StringJoiner;)Ljava/util/StringJoiner;"))
        // Deep hashing and equality: a single JDK call the meter cannot see, bounded before the JDK does it (M5-05, M5-18).
        add(instance(OBJECT, "hashCode", "()I"))
        add(instance(OBJECT, "equals", "(Ljava/lang/Object;)Z"))
        add(static("java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", "hashCode"))
        add(static("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", "equals"))
        add(static("java/util/Objects", "hash", "([Ljava/lang/Object;)I", "hash"))
        add(static("java/util/Objects", "deepEquals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", "objectsDeepEquals"))
        add(instance(COLLECTION, "containsAll", "(Ljava/util/Collection;)Z"))
        add(instance(COLLECTION, "removeAll", "(Ljava/util/Collection;)Z"))
        add(instance(COLLECTION, "retainAll", "(Ljava/util/Collection;)Z"))
        add(instance(COLLECTION, "contains", "(Ljava/lang/Object;)Z"))
        add(instance(MAP, "containsKey", "(Ljava/lang/Object;)Z"))
        add(instance(MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;"))
        add(static(ARRAYS, "hashCode", "([Ljava/lang/Object;)I", "arraysHashCode"))
        add(static(ARRAYS, "deepHashCode", "([Ljava/lang/Object;)I", "deepHashCode"))
        add(static(ARRAYS, "equals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z", "arraysEquals"))
        add(static(ARRAYS, "deepEquals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z", "arraysDeepEquals"))
        // The rest of that family (M5-FIX-2): lookups and removals, copies of a hash table, immutable factories,
        // distinct and the collectors that hash. Copy constructors are checked where they are called (CONSTRUCTORS).
        add(instance(COLLECTION, "remove", "(Ljava/lang/Object;)Z"))
        add(instance(MAP, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance(MAP, "remove", "(Ljava/lang/Object;Ljava/lang/Object;)Z"))
        add(instance(MAP, "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance(MAP, "replace", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        add(instance(MAP, "replace", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"))
        add(instance(MAP, "computeIfPresent", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
        add(instance(MAP, "containsValue", "(Ljava/lang/Object;)Z"))
        add(instance(LIST, "indexOf", "(Ljava/lang/Object;)I"))
        add(instance(LIST, "lastIndexOf", "(Ljava/lang/Object;)I"))
        add(instance("java/util/Deque", "removeFirstOccurrence", "(Ljava/lang/Object;)Z"))
        add(instance("java/util/Deque", "removeLastOccurrence", "(Ljava/lang/Object;)Z"))
        add(static(COLLECTIONS, "frequency", "(Ljava/util/Collection;Ljava/lang/Object;)I"))
        add(static(COLLECTIONS, "disjoint", "(Ljava/util/Collection;Ljava/util/Collection;)Z"))
        add(instance("java/util/HashMap", "clone", "()Ljava/lang/Object;"))
        add(instance("java/util/HashSet", "clone", "()Ljava/lang/Object;"))
        for (count in 1..10) add(static(SET, "of", "(${OBJECT_TYPE.repeat(count)})L$SET;", "setOf"))
        add(static(SET, "of", "([$OBJECT_TYPE)L$SET;", "setOf"))
        add(static(SET, "copyOf", "(L$COLLECTION;)L$SET;", "setCopyOf"))
        for (pairs in 1..10) add(static(MAP, "of", "(${OBJECT_TYPE.repeat(2 * pairs)})L$MAP;", "mapOf"))
        add(static(MAP, "ofEntries", "([Ljava/util/Map\$Entry;)L$MAP;", "mapOfEntries"))
        add(static(MAP, "copyOf", "(L$MAP;)L$MAP;", "mapCopyOf"))
        // Stream.distinct and the collectors that hash (M5-FIX-2b): each element, or the key made of it, bounded as the
        // JDK's loop reaches it, the call's budget checked as it goes.
        add(instance(STREAM, "distinct", "()L$STREAM;"))
        val function = "Ljava/util/function/Function;"
        val supplier = "Ljava/util/function/Supplier;"
        val merge = "Ljava/util/function/BinaryOperator;"
        val collector = "Ljava/util/stream/Collector;"
        for ((name, parameters) in listOf(
            "toSet" to "", "toUnmodifiableSet" to "", "toCollection" to supplier,
            "toMap" to function + function, "toMap" to function + function + merge, "toMap" to function + function + merge + supplier,
            "toUnmodifiableMap" to function + function, "toUnmodifiableMap" to function + function + merge,
            "groupingBy" to function, "groupingBy" to function + collector, "groupingBy" to function + supplier + collector,
            "groupingByConcurrent" to function, "groupingByConcurrent" to function + collector,
            "groupingByConcurrent" to function + supplier + collector,
        )) {
            add(static(COLLECTORS, name, "($parameters)$collector"))
        }
    }

    private val CALLS_BY_SIGNATURE: Map<String, List<CallRule>> = CALLS.groupBy { it.member.name + it.member.descriptor }

    /** On the short types below, these members are left alone (no deep structure to walk). */
    private val SHORT_MEMBERS = setOf("toString", "hashCode", "equals")

    /** `toString()`/`hashCode()`/`equals()` of these is the text/hash/equality the module built through the guards, or a short one: left alone. */
    private val SHORT_TEXTS = setOf(
        STRING, BUILDER, "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Double", "java/lang/Float",
        "java/lang/Boolean", "java/lang/Character",
    )

    /**
     * Every public constructor with a leading `int` of [SandboxPolicy.CAPACITY_CTOR_TYPES] (the one list), its capacity
     * checked where it sits; `StringBuilder(CharSequence)`, which reserves the length it is told; and the copy
     * constructors of the hash tables, which hash every element or key again in one call (M5-FIX-2).
     */
    private val CONSTRUCTORS: Map<String, ConstructorRule> = (
        SandboxPolicy.CAPACITY_CTOR_TYPES.flatMap { owner ->
            checkNotNull(jdkClass(owner)) { "no $owner in the JDK" }.constructors
                .filter { it.parameterCount > 0 && it.parameterTypes[0] == Integer.TYPE }
                .map { constructor ->
                    val above = constructor.parameterTypes.drop(1)
                    check(above.size <= 2 && above.none { it == java.lang.Long.TYPE || it == java.lang.Double.TYPE }) { "cannot reach the capacity of $constructor" }
                    ConstructorRule(RewrittenMember(owner, "<init>", Type.getConstructorDescriptor(constructor)), above.size)
                }
        } + ConstructorRule(RewrittenMember(BUILDER, "<init>", "(Ljava/lang/CharSequence;)V"), check = "trusted", checkDescriptor = "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;") +
            listOf("java/util/HashSet", "java/util/LinkedHashSet").map { owner ->
                ConstructorRule(RewrittenMember(owner, "<init>", "(L$COLLECTION;)V"), check = "hashedElements", checkDescriptor = "(L$COLLECTION;)L$COLLECTION;")
            } +
            listOf("java/util/HashMap", "java/util/LinkedHashMap").map { owner ->
                ConstructorRule(RewrittenMember(owner, "<init>", "(L$MAP;)V"), check = "hashedKeys", checkDescriptor = "(L$MAP;)L$MAP;")
            }
        ).associateBy { it.member.owner + it.member.descriptor }

    private fun callRule(opcode: Int, owner: String, name: String, descriptor: String, own: Set<String>): CallRule? {
        if (owner in own || !owner.startsWith("java/")) return null
        // A string, a builder and the boxes have short texts, hashes and equality: no deep walk to bound.
        if (name in SHORT_MEMBERS && owner in SHORT_TEXTS) return null
        val form = if (opcode == Opcodes.INVOKESTATIC) Form.STATIC else Form.INSTANCE
        return CALLS_BY_SIGNATURE[name + descriptor]?.firstOrNull { it.form == form && subtype(owner, it.member.owner) }
    }

    // ── The rewriting of one class ──────────────────────────────────────────────────────────────────────────────

    /** The module being rewritten: its classes' internal names, and its holder's. */
    private class Module(val own: Set<String>, val holder: String) {
        /** Module code never names the holder nor `forge.rt`: only what the rewriting inserts does. */
        fun refuseNamed(type: String) {
            val bare = type.trimStart('[').removePrefix("L").removeSuffix(";")
            if (bare == holder || bare.startsWith(RT)) throw RewriteRefused("module code may not name ${bare.replace('/', '.')}")
        }

        fun refuseNamed(constant: Any?) {
            when (constant) {
                is Type -> if (constant.sort == Type.METHOD) constant.argumentTypes.plus(constant.returnType).forEach { refuseNamed(it.descriptor) } else refuseNamed(constant.descriptor)
                is Handle -> refuseNamed(constant.owner)
                is ConstantDynamic -> {
                    refuseNamed(constant.bootstrapMethod)
                    for (i in 0 until constant.bootstrapMethodArgumentCount) refuseNamed(constant.getBootstrapMethodArgument(i))
                }
            }
        }
    }

    /** A private static bridge in the class for a lambda's method reference to [target]. */
    private class Bridge(val name: String, val descriptor: String, val target: Handle)

    private class ClassRewriter(next: ClassVisitor, val module: Module) : ClassVisitor(Opcodes.ASM9, next) {
        lateinit var className: String
        private var isInterface = false
        private val bridges = LinkedHashMap<String, Bridge>()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
            isInterface = access and Opcodes.ACC_INTERFACE != 0
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val next = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return MethodRewriter(next, this, module)
        }

        /** The handle of the bridge that calls [target]: the module's own lambdas need none. */
        fun bridge(target: Handle): Handle {
            val key = "${target.tag} ${target.owner}.${target.name}${target.desc} ${target.isInterface}"
            val bridge = bridges.getOrPut(key) {
                val owner = Type.getObjectType(target.owner).descriptor
                val descriptor = when (target.tag) {
                    Opcodes.H_INVOKESTATIC -> target.desc
                    Opcodes.H_NEWINVOKESPECIAL -> target.desc.substringBeforeLast(')') + ")" + owner
                    else -> "($owner" + target.desc.substring(1)
                }
                Bridge("forge\$bridge\$${bridges.size}", descriptor, target)
            }
            return Handle(Opcodes.H_INVOKESTATIC, className, bridge.name, bridge.descriptor, isInterface)
        }

        override fun visitEnd() {
            bridges.values.toList().forEach(::writeBridge)
            super.visitEnd()
        }

        private fun writeBridge(bridge: Bridge) {
            val target = bridge.target
            val method = visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, bridge.name, bridge.descriptor, null, null)!!
            method.visitCode()
            if (target.tag == Opcodes.H_NEWINVOKESPECIAL) {
                method.visitTypeInsn(Opcodes.NEW, target.owner)
                method.visitInsn(Opcodes.DUP)
            }
            var slot = 0
            for (argument in Type.getArgumentTypes(bridge.descriptor)) {
                method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot)
                slot += argument.size
            }
            when (target.tag) {
                Opcodes.H_INVOKESTATIC -> method.visitMethodInsn(Opcodes.INVOKESTATIC, target.owner, target.name, target.desc, target.isInterface)
                Opcodes.H_INVOKEVIRTUAL -> method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.owner, target.name, target.desc, false)
                Opcodes.H_INVOKEINTERFACE -> method.visitMethodInsn(Opcodes.INVOKEINTERFACE, target.owner, target.name, target.desc, true)
                Opcodes.H_NEWINVOKESPECIAL -> method.visitMethodInsn(Opcodes.INVOKESPECIAL, target.owner, "<init>", target.desc, false)
            }
            method.visitInsn(Type.getReturnType(bridge.descriptor).getOpcode(Opcodes.IRETURN))
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
    }

    private class MethodRewriter(next: MethodVisitor, val owner: ClassRewriter, val module: Module) : MethodVisitor(Opcodes.ASM9, next) {
        private val seen = HashSet<Label>()
        private val handlers = HashMap<Label, MutableList<Pair<Label, Label>>>()
        private var handlerTick = false

        private fun meter(call: String) {
            super.visitFieldInsn(Opcodes.GETSTATIC, module.holder, Built.METER_FIELD, METER_DESCRIPTOR)
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METER, call, "()V", false)
        }

        private fun guarded(name: String, descriptor: String) = super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARDED, name, descriptor, false)

        /** Before each instruction: the tick of an exception handler goes after its label and its frame. */
        private fun instruction() {
            if (handlerTick) {
                handlerTick = false
                meter("tick")
            }
        }

        override fun visitCode() {
            super.visitCode()
            meter("enter")
        }

        override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
            handlers.getOrPut(handler) { mutableListOf() } += start to end
            super.visitTryCatchBlock(start, end, handler, type)
        }

        override fun visitLabel(label: Label) {
            seen += label
            handlers[label]?.let { ranges ->
                handlerTick = true
                // A handler inside its own range would catch, forever, the error that a tripped meter throws again in it.
                if (ranges.any { (start, end) -> start in seen && end !in seen }) throw RewriteRefused("an exception handler that covers itself")
            }
            super.visitLabel(label)
        }

        override fun visitInsn(opcode: Int) {
            instruction()
            if (opcode in Opcodes.IRETURN..Opcodes.RETURN) meter("exit")
            super.visitInsn(opcode)
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            instruction()
            if (opcode == Opcodes.NEWARRAY) guarded("array", "(I)I")
            super.visitIntInsn(opcode, operand)
        }

        override fun visitVarInsn(opcode: Int, varIndex: Int) {
            instruction()
            super.visitVarInsn(opcode, varIndex)
        }

        override fun visitIincInsn(varIndex: Int, increment: Int) {
            instruction()
            super.visitIincInsn(varIndex, increment)
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            instruction()
            module.refuseNamed(type)
            if (opcode == Opcodes.ANEWARRAY) guarded("array", "(I)I")
            super.visitTypeInsn(opcode, type)
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            throw RewriteRefused("a multi-dimensional array (new T[a][b]) is not allowed: make an array of arrays")
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            instruction()
            module.refuseNamed(owner)
            module.refuseNamed(descriptor)
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitLdcInsn(value: Any?) {
            instruction()
            module.refuseNamed(value)
            super.visitLdcInsn(value)
        }

        override fun visitJumpInsn(opcode: Int, label: Label) {
            instruction()
            if (label in seen) meter("tick")
            super.visitJumpInsn(opcode, label)
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
            instruction()
            if (dflt in seen || labels.any { it in seen }) meter("tick")
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
            instruction()
            if (dflt in seen || labels.any { it in seen }) meter("tick")
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            instruction()
            module.refuseNamed(owner)
            if (opcode == Opcodes.INVOKESPECIAL) {
                if (name == "<init>") CONSTRUCTORS[owner + descriptor]?.let(::checkArgument)
            } else {
                val rule = callRule(opcode, owner, name, descriptor, module.own)
                if (rule != null) return guarded(rule.guarded, rule.guardedDescriptor)
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        /** Checks a constructor's argument where it sits, the category-1 arguments above it put back as they were. */
        private fun checkArgument(rule: ConstructorRule) {
            when (rule.depth) {
                0 -> guarded(rule.check, rule.checkDescriptor)
                1 -> {
                    super.visitInsn(Opcodes.SWAP)
                    guarded(rule.check, rule.checkDescriptor)
                    super.visitInsn(Opcodes.SWAP)
                }
                else -> {
                    super.visitInsn(Opcodes.DUP2_X1)
                    super.visitInsn(Opcodes.POP2)
                    guarded(rule.check, rule.checkDescriptor)
                    super.visitInsn(Opcodes.DUP_X2)
                    super.visitInsn(Opcodes.POP)
                }
            }
        }

        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrap: Handle, vararg arguments: Any?) {
            instruction()
            module.refuseNamed(bootstrap)
            module.refuseNamed(Type.getMethodType(descriptor))
            arguments.forEach(module::refuseNamed)
            val key = "${bootstrap.owner}.${bootstrap.name}${bootstrap.desc}"
            when {
                key == "java/lang/invoke/StringConcatFactory.makeConcatWithConstants$CONCAT_BOOTSTRAP" ->
                    return super.visitInvokeDynamicInsn(name, descriptor, Handle(Opcodes.H_INVOKESTATIC, GUARDED, "concatenation", CONCAT_BOOTSTRAP, false), *arguments)
                key == "java/lang/runtime/ObjectMethods.bootstrap$RECORD_BOOTSTRAP" && name in RECORD_METHODS ->
                    return super.visitInvokeDynamicInsn(name, descriptor, Handle(Opcodes.H_INVOKESTATIC, GUARDED, "record", RECORD_BOOTSTRAP, false), *arguments)
                bootstrap.owner == "java/lang/invoke/LambdaMetafactory" && arguments.size > 1 -> {
                    val target = arguments[1]
                    if (target is Handle && target.tag in BRIDGED && target.owner !in module.own) {
                        val bridged = arrayOf<Any?>(*arguments)
                        bridged[1] = owner.bridge(target)
                        return super.visitInvokeDynamicInsn(name, descriptor, bootstrap, *bridged)
                    }
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, *arguments)
        }
    }

    private val BRIDGED = setOf(Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_NEWINVOKESPECIAL)

    /** A record's generated methods, all through `Guarded.record`: `toString` bounds the text, `hashCode`/`equals` the deep structure of a component. */
    private val RECORD_METHODS = setOf("toString", "hashCode", "equals")
}
