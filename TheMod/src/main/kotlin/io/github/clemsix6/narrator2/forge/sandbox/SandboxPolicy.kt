package io.github.clemsix6.narrator2.forge.sandbox

/**
 * The allow-list of the Forge's sandbox (M5 §3.3): the one source of what a module's bytecode may name, call, catch
 * and annotate, and of what its class loader ([io.github.clemsix6.narrator2.forge.sandbox.ModuleLoader], M5-05) may
 * resolve by name. The guard ([BytecodeGuard], M5-03) and the loader read the same list, so they agree on every class
 * of `java.base` (`SandboxPolicyTest`).
 *
 * **Everything not listed is refused**, with a reason written for the forger. Names are internal (`java/lang/String`);
 * a nested class follows its outer class (`java/util/Map$Entry` with `java/util/Map`). The rewriting ([Guarded], M5-04)
 * bounds the members that allocate or grow at once; this file names them ([CAPACITY_CTOR_TYPES], [CAPACITY_METHODS])
 * so that `SandboxPolicyTest` fails if a listed type ever exposes an allocating member that is neither refused nor
 * wrapped (M5 design review, finding 1; M5-01 handoff).
 */
object SandboxPolicy {
    const val SDK_PACKAGE = "io/github/clemsix6/narrator2/forge/api/"
    const val SDK_TEST_PACKAGE = "io/github/clemsix6/narrator2/forge/api/test/"
    const val RT_PACKAGE = "io/github/clemsix6/narrator2/forge/rt/"

    /** The one SDK interface a module may implement (the SDK never receives a module object behind one of its own interfaces). */
    const val FORGE_MODULE = "io/github/clemsix6/narrator2/forge/api/ForgeModule"

    // ── Types a module may name ─────────────────────────────────────────────────────────────────────────────────

    /** Classes a module may name, by their exact internal name (nested classes follow their outer class). */
    val CLASSES: Set<String> = buildSet {
        listOf(
            "Object", "String", "StringBuilder", "CharSequence", "Math", "StrictMath", "Integer", "Long", "Short", "Byte",
            "Double", "Float", "Boolean", "Character", "Number", "Enum", "Record", "Comparable", "Iterable", "Runnable",
            "Void", "Cloneable", "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "StringIndexOutOfBoundsException",
            "UnsupportedOperationException", "ArithmeticException", "ClassCastException", "NumberFormatException", "MatchException",
            "IncompatibleClassChangeError", "NoSuchFieldError", "AssertionError", "Override", "Deprecated", "SuppressWarnings",
            "FunctionalInterface", "SafeVarargs", "Class", "System", "Throwable",
        ).forEach { add("java/lang/$it") }
        listOf(
            "ArrayList", "LinkedList", "ArrayDeque", "HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet",
            "EnumMap", "EnumSet", "PriorityQueue", "List", "Set", "Map", "Collection", "Deque", "Queue", "Iterator", "ListIterator",
            "Comparator", "Collections", "Arrays", "Objects", "Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "Random",
            "StringJoiner", "UUID", "Locale", "NoSuchElementException", "ConcurrentModificationException", "SequencedCollection",
            "SequencedSet", "SequencedMap", "NavigableMap", "NavigableSet", "SortedMap", "SortedSet", "IntSummaryStatistics",
            "LongSummaryStatistics", "DoubleSummaryStatistics",
        ).forEach { add("java/util/$it") }
        listOf("Stream", "IntStream", "LongStream", "DoubleStream", "BaseStream", "Collector", "Collectors").forEach { add("java/util/stream/$it") }
        listOf("ThreadLocalRandom", "atomic/AtomicInteger", "atomic/AtomicLong", "atomic/AtomicBoolean", "atomic/AtomicReference")
            .forEach { add("java/util/concurrent/$it") }
        listOf(
            "Duration", "Instant", "LocalDate", "LocalTime", "LocalDateTime", "ZonedDateTime", "ZoneId", "DayOfWeek", "Month",
            "temporal/ChronoUnit", "format/DateTimeFormatter",
        ).forEach { add("java/time/$it") }
    }

    /** Whole packages a module may name (their classes, not their subpackages'). */
    val PACKAGES: List<String> = listOf("java/util/function/", SDK_PACKAGE, SDK_TEST_PACKAGE)

    /** Null when a module may name [internalName], else why not (written for the forger). Arrays and descriptors are stripped by the caller. */
    fun typeRefusal(internalName: String): String? {
        val base = internalName.substringBefore('$')
        if (base in CLASSES) return null
        if (PACKAGES.any { base.startsWith(it) && '/' !in base.substring(it.length) }) return null
        return when {
            internalName.startsWith("java/io/") || internalName.startsWith("java/nio/") ->
                "a module cannot touch files; keep what you need in ctx.storage()"
            internalName.startsWith("java/net/") || internalName.startsWith("javax/net/") -> "a module cannot open the network"
            internalName.startsWith("java/lang/reflect/") || internalName.startsWith("java/lang/invoke/") ||
                internalName.startsWith("java/lang/runtime/") -> "a module cannot use reflection or method handles"
            internalName == "java/lang/Thread" || internalName == "java/lang/ThreadLocal" ||
                internalName.startsWith("java/util/concurrent/") -> "a module cannot start threads; plan work with ctx.timers()"
            internalName.startsWith("sun/") || internalName.startsWith("jdk/internal/") || internalName.startsWith("java/lang/foreign/") ->
                "a module cannot reach native or internal code"
            internalName.startsWith("io/github/clemsix6/narrator2/") -> "a module sees only the Forge SDK (forge.api), not the mod or its runtime"
            internalName.startsWith("net/minecraft/") || internalName.startsWith("net/fabricmc/") ->
                "a module cannot see the game's classes; the SDK has World, Acts, Creatures, Ground"
            else -> "${internalName.dot()} is not on the module allow-list"
        }
    }

    fun mayName(internalName: String): Boolean = typeRefusal(internalName) == null

    fun isSdkType(internalName: String): Boolean =
        internalName.startsWith(SDK_PACKAGE) || internalName.startsWith(SDK_TEST_PACKAGE)

    // ── Members ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Members refused on every owner, by name. */
    val NO_MEMBER: Map<String, String> = mapOf(
        "wait" to "a module cannot block the server thread",
        "notify" to "a module cannot use monitors",
        "notifyAll" to "a module cannot use monitors",
        "finalize" to "finalizers run on another thread",
        "parallelStream" to "parallel work runs on other threads; use a sequential stream",
        "parallel" to "parallel work runs on other threads; use a sequential stream",
        "parallelSort" to "parallel work runs on other threads",
        "parallelSetAll" to "parallel work runs on other threads",
        "parallelPrefix" to "parallel work runs on other threads",
        "printStackTrace" to "use ctx.log()",
        "getStackTrace" to "stack traces are not readable in a module",
        "setStackTrace" to "stack traces are not writable in a module",
    )

    /** Members refused on one owner (`owner.name`). */
    val NO_OWNER_MEMBER: Map<String, String> = mapOf(
        "java/lang/Integer.getInteger" to "a module cannot read system properties",
        "java/lang/Long.getLong" to "a module cannot read system properties",
        "java/lang/Boolean.getBoolean" to "a module cannot read system properties",
        "java/util/Locale.setDefault" to "a module cannot change the whole server",
        "java/lang/String.matches" to "regular expressions can run for ever; use Text.words(), Text.normalize(), contains() or equals()",
        "java/lang/String.replaceAll" to "regular expressions can run for ever; use replace()",
        "java/lang/String.replaceFirst" to "regular expressions can run for ever; use replace()",
        "java/lang/String.split" to "regular expressions can run for ever; use Text.words()",
        "java/util/stream/Stream.iterate" to "an endless stream",
        "java/util/stream/Stream.generate" to "an endless stream",
        "java/util/stream/IntStream.iterate" to "an endless stream",
        "java/util/stream/IntStream.generate" to "an endless stream",
        "java/util/stream/LongStream.iterate" to "an endless stream",
        "java/util/stream/LongStream.generate" to "an endless stream",
        "java/util/stream/DoubleStream.iterate" to "an endless stream",
        "java/util/stream/DoubleStream.generate" to "an endless stream",
        "java/util/Random.ints" to "an endless stream",
        "java/util/Random.longs" to "an endless stream",
        "java/util/Random.doubles" to "an endless stream",
        "java/util/concurrent/ThreadLocalRandom.ints" to "an endless stream",
        "java/util/concurrent/ThreadLocalRandom.longs" to "an endless stream",
        "java/util/concurrent/ThreadLocalRandom.doubles" to "an endless stream",
        "java/util/Collections.nCopies" to "builds a huge list in one call",
        "java/util/UUID.randomUUID" to "draws on the system's entropy; use ctx.random()",
    ) + rangeViews()

    /**
     * The range views of the sorted sets and maps, every form (`subSet(a, b)`, `subSet(a, true, b, false)`, `headMap`…),
     * on every owner a call site may name: what is added through a view counts only within its range, so a set grows
     * past its limit unseen (finding 2 of M5-04).
     */
    private fun rangeViews(): Map<String, String> {
        val why = "a range view of a sorted set or map grows it past its limit unseen; use ceiling(), floor(), higher(), lower() or a filtered stream"
        val sets = listOf("java/util/TreeSet", "java/util/NavigableSet", "java/util/SortedSet").flatMap { owner ->
            listOf("subSet", "headSet", "tailSet").map { "$owner.$it" to why }
        }
        val maps = listOf("java/util/TreeMap", "java/util/NavigableMap", "java/util/SortedMap").flatMap { owner ->
            listOf("subMap", "headMap", "tailMap").map { "$owner.$it" to why }
        }
        return (sets + maps).toMap()
    }

    /** Owners of which only these members may be called. */
    val ONLY: Map<String, Set<String>> = mapOf(
        "java/lang/System" to setOf("nanoTime", "currentTimeMillis", "arraycopy", "identityHashCode", "lineSeparator"),
        "java/lang/Class" to setOf("getName", "getSimpleName", "isInstance", "cast", "hashCode", "equals", "toString", "desiredAssertionStatus"),
        "java/lang/Throwable" to setOf("<init>", "getMessage", "getLocalizedMessage", "getCause", "toString", "addSuppressed"),
    )

    /** Why [name] is refused on **every** owner, even the module's own classes (it is inherited from `Object`), else null. */
    fun forbiddenMemberName(name: String): String? = NO_MEMBER[name]

    /** Why a module may not call [name] on [owner], an owner that is not one of its own classes (owner-specific and only-lists), else null. */
    fun restrictedMemberRefusal(owner: String, name: String): String? {
        NO_OWNER_MEMBER["$owner.$name"]?.let { return it }
        ONLY[owner]?.let { only -> if (name !in only) return "${owner.dot()}.$name is not available to a module" }
        return null
    }

    /** Null when a module may call [name] on [owner] (an owner it may already name), else why not. The owner's type and the descriptor are checked by the caller. */
    fun memberRefusal(owner: String, name: String): String? = forbiddenMemberName(name) ?: restrictedMemberRefusal(owner, name)

    // ── Dynamic calls (the bootstraps ECJ emits for lambdas, concatenation, records and pattern switches) ───────

    val BOOTSTRAPS: Set<String> = setOf(
        "java/lang/invoke/LambdaMetafactory.metafactory",
        "java/lang/invoke/LambdaMetafactory.altMetafactory",
        "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
        "java/lang/runtime/ObjectMethods.bootstrap",
        "java/lang/runtime/SwitchBootstraps.typeSwitch",
        "java/lang/runtime/SwitchBootstraps.enumSwitch",
    )

    fun isBootstrap(owner: String, name: String): Boolean = "$owner.$name" in BOOTSTRAPS

    // ── Catch ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Never caught: the meter and the call-site guards throw an [io.github.clemsix6.narrator2.forge.rt.ForgeLimit] (an `Error`), which must reach the Forge. */
    val NO_CATCH: Set<String> = setOf("java/lang/Throwable", "java/lang/Error")

    /** Null when a module may catch [internalName], else why not. */
    fun catchRefusal(internalName: String): String? {
        if (internalName in NO_CATCH) return "catch a specific exception; errors (interruptions) must reach the Forge"
        return typeRefusal(internalName)
    }

    // ── Annotations ────────────────────────────────────────────────────────────────────────────────────────────

    /** Annotations a module's bytecode may carry (descriptors). The SDK's are `CLASS`-retained; the JDK's that reach bytecode, `RUNTIME`. */
    val ANNOTATIONS: Set<String> = setOf(
        "L${SDK_PACKAGE}ModuleInfo;", "L${SDK_PACKAGE}Param;", "L${SDK_PACKAGE}ParamList;",
        "Ljava/lang/Override;", "Ljava/lang/Deprecated;", "Ljava/lang/SuppressWarnings;",
        "Ljava/lang/FunctionalInterface;", "Ljava/lang/SafeVarargs;",
    )

    /** Null when a module may carry the annotation [descriptor] (a `L…;` type), else why not. */
    fun annotationRefusal(descriptor: String): String? {
        if (descriptor in ANNOTATIONS) return null
        val internal = descriptor.removePrefix("L").removeSuffix(";")
        return typeRefusal(internal) ?: "${internal.dot()} is not an annotation a module may carry"
    }

    // ── Supertypes ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The throwables of [CLASSES]: a module may extend one (`class MyError extends IllegalStateException`). */
    val THROWABLES: Set<String> = setOf(
        "java/lang/Throwable", "java/lang/Exception", "java/lang/RuntimeException", "java/lang/IllegalArgumentException",
        "java/lang/IllegalStateException", "java/lang/NullPointerException", "java/lang/IndexOutOfBoundsException",
        "java/lang/ArrayIndexOutOfBoundsException", "java/lang/StringIndexOutOfBoundsException", "java/lang/UnsupportedOperationException",
        "java/lang/ArithmeticException", "java/lang/ClassCastException", "java/lang/NumberFormatException", "java/lang/MatchException",
        "java/lang/IncompatibleClassChangeError", "java/lang/NoSuchFieldError", "java/lang/AssertionError",
    )

    private val SUPERCLASSES: Set<String> = THROWABLES + setOf("java/lang/Object", "java/lang/Record", "java/lang/Enum")

    /** Null when a module class may extend [internalName] (its own classes are checked by the guard), else why not. */
    fun superclassRefusal(internalName: String): String? =
        if (internalName in SUPERCLASSES) null
        else "a module class extends Object, a record, an enum, one of its own classes, or an allow-listed exception, not ${internalName.dot()}"

    /**
     * The allow-listed types that are a collection, a map or a text (`CharSequence`): no class of a module may be one
     * (the supervisor's decision on M5-04's finding; the rewriting refuses it too). The JDK trusts their `size()` and
     * `length()`: `new HashSet<>(lying)` reserves the size it is told, at once, and the list of such doors has no end.
     */
    val CONTAINERS: Set<String> by lazy {
        val kinds = listOf(Collection::class.java, Map::class.java, CharSequence::class.java)
        CLASSES.filterTo(HashSet()) { name ->
            val type = runCatching { Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()) }.getOrNull()
            type != null && kinds.any { it.isAssignableFrom(type) }
        }
    }

    /** Null when a module class may implement [internalName] (an allow-listed interface), else why not. */
    fun containerRefusal(internalName: String): String? =
        if (internalName in CONTAINERS) {
            "a module's class may not be a collection, a map or a CharSequence (${internalName.dot()}): the JDK trusts their size; keep one in a field instead"
        } else {
            null
        }

    // ── Names a module's top class may not shadow ──────────────────────────────────────────────────────────────

    private val SDK_SIMPLE_NAMES: Set<String> = setOf(
        "Acts", "Args", "BlockInfo", "Cell", "Creature", "CreatureInfo", "Creatures", "Events", "ForgeModule", "ForgeRefusal",
        "Game", "GameFact", "Ground", "Limits", "ModuleContext", "ModuleInfo", "Outcome", "Param", "ParamList", "ParamType",
        "Pending", "Place", "PlayerInfo", "Scheduled", "Sdk", "Spawn", "StopReason", "Storage", "Subscription", "Text", "Timers",
        "Who", "World", "Bench",
    )

    private val DANGEROUS_SIMPLE_NAMES = setOf("Thread", "Timer", "Runtime", "Process", "ProcessBuilder", "ClassLoader", "Unsafe", "ThreadLocal")

    /** Simple names a module's top-level class may not take (it would mask a type the module relies on; M5 §3.3). */
    val SHADOW_NAMES: Set<String> = buildSet {
        CLASSES.forEach { add(it.substringAfterLast('/')) }
        addAll(SDK_SIMPLE_NAMES)
        addAll(DANGEROUS_SIMPLE_NAMES)
    }

    /** Null when a module's top-level class may be named [simpleName], else why not. */
    fun shadowRefusal(simpleName: String): String? =
        if (simpleName in SHADOW_NAMES) "a module cannot be named '$simpleName': it would mask a type the module relies on"
        else null

    // ── What the loader resolves by name (M5 §3.5), shared with ModuleLoader (M5-05) ────────────────────────────

    /** Classes resolvable for the linkage of ECJ's dynamic calls, which the source may never name (binary names). */
    val LINKAGE_BINARY: Set<String> = setOf(
        "java.lang.invoke.LambdaMetafactory", "java.lang.invoke.StringConcatFactory", "java.lang.invoke.MethodHandles\$Lookup",
        "java.lang.invoke.MethodType", "java.lang.invoke.MethodHandle", "java.lang.invoke.CallSite", "java.lang.invoke.TypeDescriptor",
        "java.lang.runtime.ObjectMethods", "java.lang.runtime.SwitchBootstraps",
    )

    /**
     * Whether the module loader may resolve [binaryName] (M5 §3.5): an allow-listed type (the platform loader), a
     * linkage class, the SDK or the runtime (`forge.rt`). Everything else is refused at link — in particular the
     * head class `java.lang.invoke.MethodHandles` is **not** resolvable, so no module code can obtain a `Lookup`
     * (M5 design review, finding 2), even one fabricated and loaded without the guard.
     */
    fun resolvable(binaryName: String): Boolean {
        if (binaryName in LINKAGE_BINARY) return true
        if (binaryName.startsWith(SDK_PACKAGE.dot()) || binaryName.startsWith(RT_PACKAGE.dot())) return true
        return mayName(binaryName.replace('.', '/'))
    }

    // ── Members the rewriting ([Guarded], M5-04) bounds, so the guard permits them (coordination, M5-01 handoff) ──

    /** The allow-listed types whose every constructor taking a leading `int` reserves that capacity at once: wrapped by `Guarded.capacity`. */
    val CAPACITY_CTOR_TYPES: Set<String> = setOf(
        "java/util/ArrayList", "java/util/ArrayDeque", "java/util/PriorityQueue", "java/lang/StringBuilder",
        "java/util/HashMap", "java/util/LinkedHashMap", "java/util/HashSet", "java/util/LinkedHashSet",
    )

    /**
     * The members (`owner.name`) that allocate or reserve a size in one JDK call and are wrapped by the rewriting
     * (M5 §3.4): hash tables allocate their whole table at the first `put`, `ensureCapacity`/`setLength`/`repeat`/
     * `indent` a buffer, `copyOf`/`range` an array or a stream. `StringBuilder(CharSequence)`/`(String)` and the
     * `append`/`insert` of a `CharSequence` reserve `length()`; those are shaped by `Guarded`, listed in M5-04.
     */
    val CAPACITY_METHODS: Set<String> = setOf(
        "java/lang/StringBuilder.ensureCapacity", "java/lang/StringBuilder.setLength", "java/lang/StringBuilder.repeat",
        "java/util/ArrayList.ensureCapacity",
        "java/lang/String.repeat", "java/lang/String.indent",
        "java/util/HashMap.newHashMap", "java/util/LinkedHashMap.newLinkedHashMap", "java/util/HashSet.newHashSet",
        "java/util/LinkedHashSet.newLinkedHashSet",
        "java/util/Arrays.copyOf", "java/util/Arrays.copyOfRange",
        "java/util/stream/IntStream.range", "java/util/stream/IntStream.rangeClosed",
        "java/util/stream/LongStream.range", "java/util/stream/LongStream.rangeClosed",
    )

    private fun String.dot(): String = replace('/', '.')
}
