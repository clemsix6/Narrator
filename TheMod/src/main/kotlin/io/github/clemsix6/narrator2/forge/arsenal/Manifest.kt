package io.github.clemsix6.narrator2.forge.arsenal

import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Sdk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.floor

/**
 * What a module is (M5 §5.2, §6.1): its name, its summary, its parameters and its scenarios, read from its compiled
 * classes by [read] (the invisible annotations `@ModuleInfo`, `@Param` and `@ParamList`, and the methods of its nested
 * class `Scenarios`), never by running it. Saved next to its source as `v<k>.json` ([encode], [decode]).
 *
 * @property mainClass the module's top-level class: [name] in UpperCamelCase ([mainClass]).
 * @property classes and [bytes]: the classes and the bytecode its compilation produced, before the rewriting.
 */
data class Manifest(
    val name: String,
    val mainClass: String,
    val summary: String,
    val params: List<ParamSpec>,
    val scenarios: List<String>,
    val classes: Int,
    val bytes: Int,
) {
    /** This manifest as version [version] of the arsenal, compiled against the SDK of now. */
    fun entry(version: Int, status: ModuleStatus, builtIn: Boolean, savedAt: Instant, by: String, note: String?, hash: String): ArsenalEntry =
        ArsenalEntry(
            name = name, version = version, status = status, builtIn = builtIn, summary = summary, params = params,
            scenarios = scenarios, savedAt = savedAt, by = by, note = note, hash = hash, sdk = Sdk.VERSION,
            classes = classes, bytes = bytes, problem = null,
        )

    companion object {
        /** How many players a [ParamType.PLAYERS] holds at most, and how long a [ParamType.TEXT] is at most (`@Param`'s Javadoc). */
        const val PARAM_PLAYERS = 8
        const val PARAM_TEXT_CHARS = 300

        private const val SDK = "io/github/clemsix6/narrator2/forge/api/"
        private const val FORGE_MODULE = "${SDK}ForgeModule"
        private const val MODULE_INFO = "L${SDK}ModuleInfo;"
        private const val PARAM = "L${SDK}Param;"
        private const val PARAM_LIST = "L${SDK}ParamList;"
        private const val PARAM_TYPE = "L${SDK}ParamType;"
        private const val SCENARIO = "(L${SDK}test/Bench;)V"
        private const val SCENARIOS = "Scenarios"

        /** Names Windows keeps for its devices, whatever the case and the extension: no folder may bear one. */
        private val WINDOWS_DEVICES = Regex("con|prn|aux|nul|com[0-9]|lpt[0-9]")
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val INTEGER = Regex("-?[0-9]{1,10}")
        private val NUMBER = Regex("-?[0-9]{1,15}(\\.[0-9]{1,15})?")
        private val DIMENSION = Regex("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")

        /**
         * Why [name] cannot be a module's name, or null when it can: snake_case, 3 to 32 characters
         * (`ForgeLimits.MODULE_NAME`, ASCII only), and not a name Windows keeps for a device (`con`, `nul`, `com1`…).
         * The name is also the arsenal's folder: a name that passes cannot leave it (no dot, no separator, no colon).
         */
        fun nameProblem(name: String): String? = when {
            !ForgeLimits.MODULE_NAME.matches(name) ->
                "a module's name is snake_case: 3 to 32 characters, a to z, 0 to 9 and _, starting with a letter (got \"${shown(name)}\")"
            WINDOWS_DEVICES.matches(name) -> "\"$name\" is a name Windows keeps for a device: choose another"
            else -> null
        }

        /** The class of module [name]: `orbiting_chickens` → `OrbitingChickens`. */
        fun mainClass(name: String): String = name.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

        /** `sha256:` and the hex digest of [source]'s UTF-8 bytes: a version's hash. */
        fun hash(source: String): String = hash(source.toByteArray(Charsets.UTF_8))

        /** `sha256:` and the hex digest of [bytes]. */
        fun hash(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Reads the manifest of module [name] from [classes] (binary name → bytecode, as ECJ produced them, before the
         * rewriting), and checks it against M5 §5.2: the class [mainClass] of [name], public, concrete, implementing
         * `ForgeModule`, with a public constructor without arguments; `@ModuleInfo(name, summary)`; at most 8
         * `@Param`, each with a snake_case name of its own, a doc, bounds and choices that fit its type, and a
         * fallback written in its type's text form; and an optional `public static` nested class `Scenarios`, with a
         * public constructor without arguments and at most 8 methods `public void x(Bench b)`. Nothing is loaded nor
         * run. Throws [ForgeRejected] (`Invalid arguments`) with every problem found, one a line.
         */
        fun read(name: String, classes: Map<String, ByteArray>): Manifest {
            nameProblem(name)?.let { throw ForgeRejected(null, it) }
            val main = mainClass(name)
            val facts = classes.mapValues { (_, bytes) -> ClassFacts.of(bytes) }
            val top = facts[main] ?: throw ForgeRejected(
                null,
                "the module's class must be $main ($name in UpperCamelCase), public, at the top of the file " +
                    "(the file declares ${facts.keys.filter { '$' !in it }.sorted().joinToString().ifEmpty { "no class" }})",
            )
            val problems = mutableListOf<String>()
            checkClass(top, facts, problems)
            val summary = moduleInfo(name, top, problems)
            val params = params(top, problems)
            val scenarios = facts["$main\$$SCENARIOS"]?.let { scenarios(top, it, problems) }.orEmpty()
            if (problems.isNotEmpty()) throw ForgeRejected(null, problems.joinToString("\n"))
            return Manifest(name, main, summary, params, scenarios, classes.size, classes.values.sumOf { it.size })
        }

        /** The text of `v<k>.json` for [entry] (M5 §6.1): its built-in flag and its problem are not saved. */
        fun encode(entry: ArsenalEntry): String = json.encodeToString(ManifestFile.serializer(), ManifestFile.of(entry)) + "\n"

        /**
         * The version a `v<k>.json` describes, as a forged one (not built-in, no problem). Throws
         * [IllegalArgumentException] (or a `SerializationException`, one too) when [text] is not a manifest:
         * malformed JSON, a status that is not saved (`broken` never is), an unknown parameter type, a bad hash.
         */
        fun decode(text: String): ArsenalEntry = json.decodeFromString(ManifestFile.serializer(), text).entry()

        // ── The class ──────────────────────────────────────────────────────────────────────────────────────────────

        private fun checkClass(top: ClassFacts, facts: Map<String, ClassFacts>, problems: MutableList<String>) {
            val main = top.name
            val kind = when {
                top.access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION) != 0 -> "an interface"
                top.access and Opcodes.ACC_ENUM != 0 -> "an enum"
                else -> null
            }
            if (kind != null) {
                problems += "$main must be a class, not $kind"
                return
            }
            if (top.access and Opcodes.ACC_ABSTRACT != 0) problems += "$main must not be abstract: the engine makes an instance of it"
            if (!implementsModule(top, facts, mutableSetOf())) problems += "$main must implement ForgeModule"
            // The default constructor of a class that is not public is not public either: one problem, not two.
            when {
                top.access and Opcodes.ACC_PUBLIC == 0 -> problems += "$main must be public: public final class $main implements ForgeModule"
                !top.hasPublicConstructor() -> problems += "$main needs a public constructor without arguments (or no constructor at all)"
            }
        }

        /** Whether [type] implements `ForgeModule`, itself or through a class or an interface of the module. */
        private fun implementsModule(type: ClassFacts, facts: Map<String, ClassFacts>, seen: MutableSet<String>): Boolean {
            if (!seen.add(type.name)) return false
            if (FORGE_MODULE in type.interfaces) return true
            return (type.interfaces + listOfNotNull(type.superName)).any { parent -> facts[parent]?.let { implementsModule(it, facts, seen) } == true }
        }

        private fun moduleInfo(name: String, top: ClassFacts, problems: MutableList<String>): String {
            val info = top.annotations[MODULE_INFO]
            if (info == null) {
                problems += "@ModuleInfo is missing: @ModuleInfo(name = \"$name\", summary = \"what the players see, one English line\")"
                return ""
            }
            val declared = info["name"] as? String
            if (declared != name) problems += "@ModuleInfo name is \"${shown(declared.orEmpty())}\" but the module is $name: they must be the same"
            val summary = (info["summary"] as? String).orEmpty().trim()
            when {
                summary.isEmpty() -> problems += "@ModuleInfo summary is empty: say what the players see, one English line"
                summary.length > ForgeLimits.SUMMARY_CHARS -> problems += "@ModuleInfo summary is ${summary.length} characters long, at most ${ForgeLimits.SUMMARY_CHARS}"
                summary.any { it == '\n' || it == '\r' } -> problems += "@ModuleInfo summary must be one line"
            }
            return summary
        }

        // ── The parameters ─────────────────────────────────────────────────────────────────────────────────────────

        private fun params(top: ClassFacts, problems: MutableList<String>): List<ParamSpec> {
            val single = top.annotations[PARAM]
            val list = top.annotations[PARAM_LIST]
            if (single != null && list != null) {
                problems += "write @Param once per parameter, and never @ParamList yourself"
                return emptyList()
            }
            val annotations = single?.let { listOf(it) } ?: (list?.get("value") as? List<*>).orEmpty().map { (it as? AnnotationValues)?.values.orEmpty() }
            if (annotations.size > ForgeLimits.PARAMS) problems += "${annotations.size} @Param, at most ${ForgeLimits.PARAMS}"
            val params = annotations.mapNotNull { param(it, problems) }
            params.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { problems += "@Param $it: two parameters have this name" }
            return params
        }

        /** One `@Param` from its [values] (only what the source wrote: the others are the annotation's defaults). */
        private fun param(values: Map<String, Any?>, problems: MutableList<String>): ParamSpec? {
            val name = values["name"] as? String ?: return null.also { problems += "@Param without a name" }
            val where = "@Param ${shown(name)}"
            val type = (values["type"] as? EnumValue)?.takeIf { it.descriptor == PARAM_TYPE }?.let { e -> ParamType.entries.firstOrNull { it.name == e.value } }
                ?: return null.also { problems += "$where: its type is missing" }
            val doc = (values["doc"] as? String).orEmpty().trim()
            val required = values["required"] as? Boolean ?: true
            val fallback = values["fallback"] as? String ?: ""
            val min = values["min"] as? Double ?: Double.NEGATIVE_INFINITY
            val max = values["max"] as? Double ?: Double.POSITIVE_INFINITY
            val choices = (values["choices"] as? List<*>).orEmpty().map { it as? String ?: "" }
            val before = problems.size

            if (!ForgeLimits.PARAM_NAME.matches(name)) problems += "$where: a parameter's name is snake_case, 1 to 32 characters, a to z, 0 to 9 and _, starting with a letter"
            when {
                doc.isEmpty() -> problems += "$where: doc is empty: say what it is, one English line for the Director"
                doc.length > ForgeLimits.PARAM_DOC_CHARS -> problems += "$where: doc is ${doc.length} characters long, at most ${ForgeLimits.PARAM_DOC_CHARS}"
                doc.any { it == '\n' || it == '\r' } -> problems += "$where: doc must be one line"
            }
            bounds(where, type, min, max, problems)
            if (choices.isNotEmpty()) choices(where, type, choices, min, max, problems)
            when {
                required && fallback.isNotEmpty() -> problems += "$where: a required parameter has no fallback (write required = false to give it one)"
                !required && fallback.isEmpty() && type in ALWAYS_VALUED ->
                    problems += "$where: ${article(type)} that is not required needs a fallback, the value it takes when left out"
                fallback.isNotEmpty() -> fallbackProblem(type, fallback, min, max, choices)?.let { problems += "$where: fallback \"${shown(fallback)}\" $it" }
            }
            if (problems.size > before) return null
            return ParamSpec(
                name = name, type = type, doc = doc, required = required, fallback = fallback.ifEmpty { null },
                min = min.takeIf { it.isFinite() }, max = max.takeIf { it.isFinite() }, choices = choices,
            )
        }

        private val ALWAYS_VALUED = setOf(ParamType.INTEGER, ParamType.NUMBER, ParamType.BOOLEAN)

        /** The bounds of a parameter: what they bound depends on its type (`@Param.min`). */
        private fun bounds(where: String, type: ParamType, min: Double, max: Double, problems: MutableList<String>) {
            val given = listOf("min" to min, "max" to max).filter { it.second.isFinite() || it.second.isNaN() }
            if (given.isEmpty()) return
            if (given.any { it.second.isNaN() }) {
                problems += "$where: min and max must be numbers"
                return
            }
            val range = when (type) {
                ParamType.INTEGER -> Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
                ParamType.NUMBER -> null
                ParamType.PLAYERS -> 0.0..PARAM_PLAYERS.toDouble()
                ParamType.TEXT -> 0.0..PARAM_TEXT_CHARS.toDouble()
                ParamType.PLAYER, ParamType.BOOLEAN, ParamType.PLACE -> {
                    problems += "$where: min and max bound only an integer, a number, how many players, or how long a text is"
                    return
                }
            }
            val what = when (type) {
                ParamType.PLAYERS -> "is how many players"
                ParamType.TEXT -> "is how many characters"
                else -> "must fit an int"
            }
            given.forEach { (bound, value) ->
                if (type != ParamType.NUMBER && value != floor(value)) problems += "$where: $bound must be a whole number (got ${number(value)})"
                if (range != null && value !in range) {
                    problems += "$where: $bound $what, from ${number(range.start)} to ${number(range.endInclusive)} (got ${number(value)})"
                }
            }
            if (min > max) problems += "$where: min ${number(min)} is above max ${number(max)}"
        }

        private fun choices(where: String, type: ParamType, choices: List<String>, min: Double, max: Double, problems: MutableList<String>) {
            if (type != ParamType.TEXT) {
                problems += "$where: choices are only for a text"
                return
            }
            choices.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { problems += "$where: the choice \"${shown(it)}\" is there twice" }
            choices.forEach { choice -> textProblem(choice, min, max)?.let { problems += "$where: the choice \"${shown(choice)}\" $it" } }
        }

        /** Why [fallback] is not a value of [type] within [min], [max] and [choices], or null when it is. */
        private fun fallbackProblem(type: ParamType, fallback: String, min: Double, max: Double, choices: List<String>): String? = when (type) {
            ParamType.INTEGER -> {
                val value = fallback.takeIf { INTEGER.matches(it) }?.toIntOrNull()
                if (value == null) "is not a whole number, e.g. \"6\"" else within(value.toDouble(), min, max)
            }
            ParamType.NUMBER -> {
                val value = fallback.takeIf { NUMBER.matches(it) }?.toDoubleOrNull()
                if (value == null) "is not a number, e.g. \"2.5\"" else within(value, min, max)
            }
            ParamType.BOOLEAN -> if (fallback == "true" || fallback == "false") null else "is not \"true\" nor \"false\""
            ParamType.TEXT -> textProblem(fallback, min, max)
                ?: "is not one of its choices (${choices.joinToString { shown(it) }})".takeIf { choices.isNotEmpty() && fallback !in choices }
            ParamType.PLAYER -> if (PLAYER_NAME.matches(fallback)) null else "is not a player's name"
            ParamType.PLAYERS -> playersProblem(fallback, min, max)
            ParamType.PLACE -> if (PLAYER_NAME.matches(fallback) || isPlace(fallback)) null else "is not a place: \"120 64 -30\", \"120 64 -30 the_nether\" or a player's name"
        }

        private fun within(value: Double, min: Double, max: Double): String? = when {
            value < min -> "is below min ${number(min)}"
            value > max -> "is above max ${number(max)}"
            else -> null
        }

        private fun textProblem(text: String, min: Double, max: Double): String? {
            val longest = minOf(max, PARAM_TEXT_CHARS.toDouble())
            return when {
                text.isBlank() -> "is empty"
                text.length < min -> "is ${text.length} characters long, below min ${number(min)}"
                text.length > longest -> "is ${text.length} characters long, above ${number(longest)}"
                else -> null
            }
        }

        private fun playersProblem(fallback: String, min: Double, max: Double): String? {
            val names = fallback.split(',').map { it.trim() }
            val most = minOf(max, PARAM_PLAYERS.toDouble())
            return when {
                names.any { !PLAYER_NAME.matches(it) } -> "is not a list of players' names, e.g. \"Bartok,Mimosa\""
                names.map { it.lowercase() }.toSet().size < names.size -> "names a player twice"
                names.size < min -> "has ${names.size} players, below min ${number(min)}"
                names.size > most -> "has ${names.size} players, above ${number(most)}"
                else -> null
            }
        }

        private fun isPlace(text: String): Boolean {
            val parts = text.trim().split(' ').filter { it.isNotEmpty() }
            return parts.size in 3..4 && parts.take(3).all { NUMBER.matches(it) } && (parts.size == 3 || DIMENSION.matches(parts[3]))
        }

        private fun article(type: ParamType): String = when (type) {
            ParamType.INTEGER -> "an integer"
            ParamType.NUMBER -> "a number"
            else -> "a boolean"
        }

        // ── The scenarios ──────────────────────────────────────────────────────────────────────────────────────────

        private fun scenarios(top: ClassFacts, scenarios: ClassFacts, problems: MutableList<String>): List<String> {
            val access = top.inner[scenarios.name] ?: scenarios.inner[scenarios.name] ?: 0
            when {
                // An inner or hidden class's default constructor is not public without arguments either: one problem.
                access and Opcodes.ACC_PUBLIC == 0 || access and Opcodes.ACC_STATIC == 0 ->
                    problems += "Scenarios must be a public static nested class: public static final class Scenarios { public void x(Bench b) { … } }"
                scenarios.access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ENUM) != 0 ->
                    problems += "Scenarios must be a concrete class: the bench makes an instance of it"
                !scenarios.hasPublicConstructor() -> problems += "Scenarios needs a public constructor without arguments (or no constructor at all)"
            }
            val found = mutableListOf<String>()
            scenarios.methods.filter { it.isScenarioLike() }.forEach { method ->
                if (method.descriptor == SCENARIO) {
                    found += method.name
                } else {
                    problems += "Scenarios.${method.name}: a scenario is public void ${method.name}(Bench b); make a helper private or static"
                }
            }
            if (found.size > ForgeLimits.SCENARIOS) problems += "${found.size} scenarios, at most ${ForgeLimits.SCENARIOS}"
            return found
        }

        // ── Helpers ────────────────────────────────────────────────────────────────────────────────────────────────

        /** A name as the model wrote it, safe to quote in one line: controls escaped, cut at 40 characters. */
        private fun shown(text: String): String {
            val escaped = buildString { text.forEach { c -> if (c.isISOControl()) append("\\u%04x".format(c.code)) else append(c) } }
            return if (escaped.length > 40) escaped.take(40) + "…" else escaped
        }

        /** A bound as the source wrote it: `8`, not `8.0`. */
        private fun number(value: Double): String =
            if (value.isFinite() && value == floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

        private val json = Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** An annotation's values as the source wrote them (the defaults are not in the bytecode). */
private class AnnotationValues(val values: MutableMap<String, Any?> = LinkedHashMap())

/** An enum constant in an annotation: its type's descriptor and its name. */
private data class EnumValue(val descriptor: String, val value: String)

private class MethodFacts(val access: Int, val name: String, val descriptor: String) {
    /** Public, of an instance, written in the source: a method the bench would call as a scenario. */
    fun isScenarioLike(): Boolean =
        name != "<init>" && name != "<clinit>" &&
            access and Opcodes.ACC_PUBLIC != 0 &&
            access and (Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0
}

/** What [Manifest.read] needs of one class file, read by ASM without its code. */
private class ClassFacts(
    val access: Int,
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    /** Descriptor → values, for the class's own annotations, visible or not. */
    val annotations: Map<String, Map<String, Any?>>,
    val methods: List<MethodFacts>,
    /** Its `InnerClasses` attribute: a nested class's binary name → its access flags as declared in the source. */
    val inner: Map<String, Int>,
) {
    fun hasPublicConstructor(): Boolean = methods.any { it.name == "<init>" && it.descriptor == "()V" && it.access and Opcodes.ACC_PUBLIC != 0 }

    companion object {
        fun of(bytes: ByteArray): ClassFacts {
            var access = 0
            var name = ""
            var superName: String? = null
            var interfaces = emptyList<String>()
            val annotations = LinkedHashMap<String, Map<String, Any?>>()
            val methods = mutableListOf<MethodFacts>()
            val inner = LinkedHashMap<String, Int>()
            val visitor = object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, flags: Int, className: String, signature: String?, parent: String?, parents: Array<out String>?) {
                    access = flags
                    name = className
                    superName = parent
                    interfaces = parents.orEmpty().toList()
                }

                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    val annotation = AnnotationValues()
                    annotations[descriptor] = annotation.values
                    return Collector { key, value -> annotation.values[key.orEmpty()] = value }
                }

                override fun visitInnerClass(innerName: String, outerName: String?, simpleName: String?, flags: Int) {
                    inner[innerName] = flags
                }

                override fun visitMethod(flags: Int, methodName: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    methods += MethodFacts(flags, methodName, descriptor)
                    return null
                }
            }
            ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return ClassFacts(access, name, superName, interfaces, annotations, methods, inner)
        }
    }
}

/** Gathers an annotation's values: plain constants, enum constants, arrays and nested annotations. */
private class Collector(private val sink: (String?, Any?) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) = sink(name, value)

    override fun visitEnum(name: String?, descriptor: String, value: String) = sink(name, EnumValue(descriptor, value))

    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        val nested = AnnotationValues()
        sink(name, nested)
        return Collector { key, value -> nested.values[key.orEmpty()] = value }
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        val items = mutableListOf<Any?>()
        sink(name, items)
        return Collector { _, value -> items += value }
    }
}

/** `v<k>.json` (M5 §6.1): snake_case keys, ids in lowercase, absent rather than null. */
@Serializable
private data class ManifestFile(
    val name: String,
    val version: Int,
    val sdk: Int,
    val hash: String,
    @SerialName("saved_at") val savedAt: String,
    val by: String,
    val status: String,
    val summary: String,
    val params: List<ParamFile> = emptyList(),
    val scenarios: List<String> = emptyList(),
    val classes: Int = 0,
    val bytes: Int = 0,
    val note: String? = null,
) {
    fun entry(): ArsenalEntry {
        val saved = ModuleStatus.entries.firstOrNull { it.id == status && it != ModuleStatus.BROKEN }
        require(saved != null) { "status \"$status\" is not ready, pending nor rejected" }
        require(HASH.matches(hash)) { "hash \"$hash\" is not sha256: and 64 hex digits" }
        require(version >= 1) { "version $version is not 1 or more" }
        require(sdk >= 1 && classes >= 0 && bytes >= 0) { "sdk, classes or bytes is out of range" }
        return ArsenalEntry(
            name = name, version = version, status = saved, builtIn = false, summary = summary, params = params.map { it.spec() },
            scenarios = scenarios, savedAt = Instant.parse(savedAt), by = by, note = note, hash = hash, sdk = sdk,
            classes = classes, bytes = bytes, problem = null,
        )
    }

    companion object {
        private val HASH = Regex("sha256:[0-9a-f]{64}")

        fun of(entry: ArsenalEntry) = ManifestFile(
            name = entry.name, version = entry.version, sdk = entry.sdk, hash = entry.hash, savedAt = entry.savedAt.toString(),
            by = entry.by, status = entry.status.id, summary = entry.summary, params = entry.params.map { ParamFile.of(it) },
            scenarios = entry.scenarios, classes = entry.classes, bytes = entry.bytes, note = entry.note,
        )
    }
}

@Serializable
private data class ParamFile(
    val name: String,
    val type: String,
    val doc: String,
    val required: Boolean,
    val fallback: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val choices: List<String>? = null,
) {
    fun spec(): ParamSpec {
        val parsed = ParamType.entries.firstOrNull { it.name.lowercase() == type }
        require(parsed != null) { "parameter $name: type \"$type\" is unknown" }
        return ParamSpec(name, parsed, doc, required, fallback, min, max, choices.orEmpty())
    }

    companion object {
        fun of(spec: ParamSpec) = ParamFile(
            name = spec.name, type = spec.type.name.lowercase(), doc = spec.doc, required = spec.required, fallback = spec.fallback,
            min = spec.min, max = spec.max, choices = spec.choices.ifEmpty { null },
        )
    }
}
