package io.github.clemsix6.narrator2.forge.compile

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.ModuleInfo
import io.github.clemsix6.narrator2.forge.api.ParamList
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.forge.api.Text
import io.github.clemsix6.narrator2.forge.api.test.Bench
import org.eclipse.jdt.core.compiler.IProblem
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.math.max
import kotlin.math.min

/**
 * One entry of a report to the forger (M5 §5.3): an error of the compiler, a refusal of the guard, a fault at the
 * bench. Built by [Diagnostics.compilation], [Diagnostics.refused] and [Diagnostics.fault]; written by
 * [Diagnostics.render].
 *
 * @property where `OrbitingChickens.java:31:15`, `OrbitingChickens.java:57`, `scenario theyCircleThePlayer`.
 * @property line the line of the source quoted under it, from 1; 0 for none.
 * @property column where its underline starts, from 1, [length] characters long; 0 for no underline.
 * @property also the other places of the same error (`40:9`).
 * @property hints what the SDK and the rules suggest (`Acts: Pending spawn(String type, String near, Spawn how)`).
 */
data class Diagnostic(
    val kind: Kind,
    val where: String,
    val message: String,
    val line: Int = 0,
    val column: Int = 0,
    val length: Int = 0,
    val also: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
) {
    enum class Kind {
        ERROR,
        REFUSED,
        FAULT,
        ;

        /** `error`: how the report names it. */
        val id: String get() = name.lowercase()
    }
}

/**
 * The reports the forger reads (M5 §5.3): a dense text, 20 entries at most, each `[n] kind where: message`, the
 * faulty line of the source and its underline, then hints drawn from the SDK (its types and members, by edit
 * distance) and from the rules (a module sees neither the game nor the mod). The same form for the compiler's
 * errors, the guard's refusals (the line from the bytecode's line table) and the bench's faults (the line of the
 * module's first frame):
 *
 * ```
 * [1] error OrbitingChickens.java:31:20: The method spawn(String, String, Spawn) in the type Acts is not applicable for the arguments (String, String)
 *   31 | ctx.acts().spawn("chicken", player);
 *      |            ^^^^^
 *   hint: Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how) — Spawn.of(int)
 * [2] refused OrbitingChickens.java:57: java.lang.Thread: threads are not allowed in a module (use ctx.timers())
 *   57 | new Thread(this::loop).start();
 * [3] fault scenario theyCircleThePlayer: ForgeLimit: a collection over 10000 elements at OrbitingChickens.java:72
 * ```
 *
 * A method of the SDK shows its parameters' names (the SDK is compiled with `-parameters`), a record's constructor its
 * components. A method unknown on an SDK type, with no name close to it, gets the SDK's name for it when there is one
 * (`actionbar` → `Acts: Pending hud(String player, String text)`) and the list of the type's methods, written once:
 *
 * ```
 *   hint: Acts (ctx.acts()): Pending hud(String player, String text)
 *   hint: methods of Acts (ctx.acts()): attribute(String player, String attribute, double multiply, int seconds), effect(…), …
 * ```
 */
object Diagnostics {
    /** A module may not see the game: what to use instead. */
    const val GAME_RULE = "modules cannot see the game's classes; the SDK has World, Acts, Creatures, Ground"
    const val MOD_RULE = "modules see only the SDK of the Narrator: io.github.clemsix6.narrator2.forge.api"
    const val JDK_RULE =
        "modules see only java.base and the SDK, and may use a part of java.base: java.lang, java.util, java.util.function, java.util.stream, java.time"
    const val NO_PACKAGE = "a module has no package: delete this line"
    const val SDK_IMPORT = "import io.github.clemsix6.narrator2.forge.api.*;"

    /** The error entries of a compilation of `[typeName].java` (its warnings left out), one per message, its other places in `also`, hints added. */
    fun compilation(typeName: String, source: String, problems: List<SourceProblem>): List<Diagnostic> {
        val file = "$typeName.java"
        return problems.filter { it.error }
            .sortedWith(compareBy({ it.line }, { it.column }))
            .groupBy { it.message }
            .map { (message, same) ->
                val first = same.first()
                Diagnostic(
                    kind = Diagnostic.Kind.ERROR,
                    where = if (first.line > 0) "$file:${first.line}:${first.column}" else file,
                    message = message,
                    line = first.line,
                    column = first.column,
                    length = if (first.start >= 0) first.end - first.start + 1 else 0,
                    also = same.drop(1).map { "${it.line}:${it.column}" },
                    hints = hints(first, source, typeName),
                )
            }
    }

    /** A refusal of the guard (M5 §3.3) at [line] of `[typeName].java` (0 when unknown); [message]: `java.lang.Thread: threads are not allowed…`. */
    fun refused(typeName: String, line: Int, message: String): Diagnostic =
        Diagnostic(Diagnostic.Kind.REFUSED, if (line > 0) "$typeName.java:$line" else "$typeName.java", message, line = line)

    /** A fault at the bench: [where] (`scenario theyCircleThePlayer`, `smoke run`), [message] with its place, [line] of the module's first frame (0 when unknown). */
    fun fault(where: String, message: String, line: Int = 0): Diagnostic = Diagnostic(Diagnostic.Kind.FAULT, where, message, line = line)

    /**
     * The report: at most `ForgeLimits.DIAGNOSTICS` entries and [maxChars] characters, the rest counted on a last
     * line. [source] gives the lines quoted (none without it). A rule ([GAME_RULE], [MOD_RULE], [JDK_RULE]) is
     * written once; the hints about the SDK's members, at each entry.
     */
    fun render(diagnostics: List<Diagnostic>, source: String?, maxChars: Int = ForgeLimits.TEST_REPORT_CHARS): String {
        val lines = source?.lines().orEmpty()
        val written = HashSet<String>()
        val out = StringBuilder()
        for ((index, diagnostic) in diagnostics.withIndex()) {
            val left = diagnostics.size - index
            val reserve = if (left > 1) MORE.length + 8 else 0
            val hints = diagnostic.hints.filter { it !in written }
            val entry = entry(index + 1, diagnostic, hints, lines)
            if (index == ForgeLimits.DIAGNOSTICS || out.length + entry.length + reserve > maxChars) {
                if (index == 0) out.append(entry.take(max(0, maxChars - reserve - 2))).append("…\n")
                val more = if (index == 0) left - 1 else left
                if (more > 0) out.append("… $more $MORE\n")
                break
            }
            written += hints.filter { it in RULES || it.startsWith(LISTING) }
            out.append(entry)
        }
        return out.toString().trimEnd()
    }

    private const val MORE = "more not shown"
    private val RULES = setOf(GAME_RULE, MOD_RULE, JDK_RULE)

    /** How a hint that lists every method of a type begins ([listing]): written once in a report, like a rule. */
    private const val LISTING = "methods of "

    /** The longest list of a type's methods in a hint ([listing]). */
    private const val LISTING_CHARS = 800

    /** The widest excerpt of a line, around the underline. */
    private const val EXCERPT = 100

    private fun entry(number: Int, d: Diagnostic, hints: List<String>, lines: List<String>): String = buildString {
        append('[').append(number).append("] ").append(d.kind.id).append(' ').append(d.where).append(": ")
        append(d.message.replace(Regex("\\s*\\R\\s*"), " ").trim()).append('\n')
        lines.getOrNull(d.line - 1)?.let { append(excerpt(it, d.line, d.column, d.length)) }
        if (d.also.isNotEmpty()) append("  also at ").append(d.also.take(5).joinToString(", ")).append(if (d.also.size > 5) ", …" else "").append('\n')
        hints.forEach { append("  hint: ").append(it).append('\n') }
    }

    /** `  31 | code` and, under it, `     |   ^^^^^`: the line without its indentation, cut around the column when long. */
    private fun excerpt(text: String, line: Int, column: Int, length: Int): String {
        val raw = text.replace('\t', ' ').trimEnd()
        val indent = raw.length - raw.trimStart().length
        val at = column - 1
        var from = indent
        var to = raw.length
        if (to - from > EXCERPT) {
            from = if (column > 0) max(indent, min(at - EXCERPT / 3, to - EXCERPT)) else indent
            to = min(to, from + EXCERPT)
        }
        val head = if (from > indent) "…" else ""
        val tail = if (to < raw.length) "…" else ""
        val gutter = line.toString()
        return buildString {
            append("  ").append(gutter).append(" | ").append(head).append(raw, from, to).append(tail).append('\n')
            if (column > 0 && at in from..to) {
                val width = max(1, min(length, to - at))
                append("  ").append(" ".repeat(gutter.length)).append(" | ").append(" ".repeat(head.length + at - from)).append("^".repeat(width)).append('\n')
            }
        }
    }

    // ── Hints ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun hints(problem: SourceProblem, source: String, typeName: String): List<String> {
        val args = problem.arguments
        return when (problem.id) {
            IProblem.UndefinedMethod, IProblem.ParameterMismatch, IProblem.NotVisibleMethod -> methodHints(args.getOrNull(0), args.getOrNull(1))
            IProblem.UndefinedConstructor, IProblem.NotVisibleConstructor -> constructorHints(args.getOrNull(0))
            IProblem.UndefinedField -> memberHints(args.getOrNull(0), qualifierBefore(source, problem.start))
            IProblem.UnresolvedVariable -> memberHints(args.getOrNull(0), null)
            IProblem.UndefinedName -> args.getOrNull(0)?.let { name -> if (name.firstOrNull()?.isUpperCase() == true) typeHints(name) else memberHints(name, null) }.orEmpty()
            IProblem.UndefinedType, IProblem.NotVisibleType -> typeHints(qualifiedAt(source, problem.start) ?: args.getOrNull(0).orEmpty())
            IProblem.IsClassPathCorrect -> typeHints(args.getOrNull(0).orEmpty())
            IProblem.ImportNotFound -> importHints(importAt(source, problem.start) ?: args.getOrNull(0).orEmpty())
            IProblem.PackageIsNotExpectedPackage -> listOf(NO_PACKAGE)
            IProblem.PublicClassMustMatchFileName -> listOf("a module is one file: its public class is $typeName, the module's name in UpperCamelCase")
            else -> emptyList()
        }
    }

    /**
     * A method missing or not applicable on [owner] (a qualified name): its overloads, the closest names, or the same
     * name elsewhere in the SDK. On an SDK type with no name close to it (`ctx.acts().actionbar(…)`): what the SDK
     * calls it ([SYNONYMS]: `hud`) or the same name on another SDK type, then every method of the type ([listing]).
     */
    private fun methodHints(owner: String?, selector: String?): List<String> {
        if (owner == null || selector == null) return emptyList()
        val type = SdkIndex.type(owner.substringBefore('<'))
        if (type != null) {
            val same = SdkIndex.methods(type).filter { it.name == selector }
            if (same.isNotEmpty()) return same.take(4).map { methodHint(type, it) }
            val close = closest(selector, SdkIndex.methods(type).map { it.name }.distinct())
            if (close.isNotEmpty()) return close.flatMap { name -> SdkIndex.methods(type).filter { it.name == name } }.take(3).map { methodHint(type, it) }
            if (SdkIndex.methods(type).isNotEmpty()) return meant(selector, type) + listing(type)
        } else if (JDK_PREFIXES.any { owner.startsWith(it) }) {
            return emptyList()
        }
        // On the module's own class, or a name the SDK has on another type (ctx.spawn → ctx.acts().spawn).
        return SdkIndex.types.flatMap { other -> SdkIndex.methods(other).filter { it.name == selector }.map { other to it } }
            .filter { (other, _) -> other != type }
            .take(3)
            .map { (other, method) -> methodHint(other, method) }
    }

    /**
     * What [selector], unknown on [type], may have meant: the SDK's name for it ([SYNONYMS]), then the same name; each
     * on [type] when it has it, else on the other SDK types (`ctx.spawn` → `ctx.acts().spawn`); 3 at most.
     */
    private fun meant(selector: String, type: Class<*>): List<String> =
        listOfNotNull(SYNONYMS[selector.lowercase()], selector).flatMap { name ->
            val own = SdkIndex.methods(type).filter { it.name == name }.map { type to it }
            own.ifEmpty { SdkIndex.types.filter { it != type }.flatMap { other -> SdkIndex.methods(other).filter { it.name == name }.map { other to it } } }
        }
            .distinct()
            .take(3)
            .map { (t, method) -> methodHint(t, method) }

    /**
     * Every method of [type], with its parameters' names, no return type: `methods of Acts (ctx.acts()): attribute(String
     * player, String attribute, double multiply, int seconds), effect(…), …`, [LISTING_CHARS] at most. Written once in a report.
     */
    private fun listing(type: Class<*>): String {
        val all = SdkIndex.methods(type).joinToString(", ") { method ->
            (if (Modifier.isStatic(method.modifiers)) "static " else "") + method.name + parameters(method.genericParameterTypes, names(method), method.isVarArgs)
        }
        val cut = if (all.length <= LISTING_CHARS) all else all.take(all.lastIndexOf(", ", LISTING_CHARS - 3)) + ", …"
        return "$LISTING${owner(type)}: $cut"
    }

    private fun methodHint(type: Class<*>, method: Method): String {
        val factories = method.parameterTypes.filter { SdkIndex.isSdk(it) && it != type }.distinct()
            .flatMap { parameter -> SdkIndex.factories(parameter).map { "${show(parameter)}.${it.name}(${it.parameterTypes.joinToString(", ") { p -> show(p) }})" } }
        return "${owner(type)}: ${signature(method)}" + if (factories.isEmpty()) "" else " — " + factories.joinToString(", ")
    }

    /** A constructor missing on an SDK type: its factories, then its constructors. */
    private fun constructorHints(owner: String?): List<String> {
        val type = owner?.let { SdkIndex.type(it.substringBefore('<')) } ?: return emptyList()
        val factories = SdkIndex.factories(type).map { "${show(type)}: ${signature(it)}" }
        val constructors = type.constructors.filter { Modifier.isPublic(it.modifiers) }.map { "${show(type)}: ${signature(type, it)}" }
        return (factories + constructors).take(3)
    }

    /** A field or a name missing: the closest members of [qualifier] when it is an SDK type, else close members anywhere in the SDK. */
    private fun memberHints(name: String?, qualifier: String?): List<String> {
        if (name.isNullOrEmpty()) return emptyList()
        val type = qualifier?.let { SdkIndex.bySimpleName(it) }
        val types = if (type != null) listOf(type) else SdkIndex.types
        val members = types.flatMap { owner -> SdkIndex.fields(owner).map { Triple(owner, it.name, fieldHint(owner, it)) } + SdkIndex.methods(owner).map { Triple(owner, it.name, methodHint(owner, it)) } }
        val close = closest(name, members.map { it.second }.distinct(), tolerance = if (type != null) null else 1)
        return close.flatMap { found -> members.filter { it.second == found }.map { it.third } }.distinct().take(3)
    }

    private fun fieldHint(type: Class<*>, field: Field): String {
        val constant = if (Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) && (field.type.isPrimitive || field.type == String::class.java)) {
            " = " + field.get(null)
        } else {
            ""
        }
        return "${owner(type)}: ${if (Modifier.isStatic(field.modifiers)) "static " else ""}${show(field.genericType)} ${field.name}$constant"
    }

    /** A type the compiler does not see: the rule it breaks, its SDK counterpart, the import it lacks, or the closest SDK types. */
    private fun typeHints(name: String): List<String> {
        if (name.isEmpty()) return emptyList()
        val simple = name.substringAfterLast('.')
        return when {
            GAME_PACKAGES.any { name.startsWith(it) } || simple in GAME_TYPES -> listOfNotNull(GAME_RULE, GAME_TYPES[simple]?.let { "$simple → $it" })
            name.startsWith(MOD_PACKAGE) -> listOf(MOD_RULE)
            '.' in name -> if (usable(name)) emptyList() else listOf(JDK_RULE)
            SdkIndex.bySimpleName(name) != null -> listOf(importOf(SdkIndex.bySimpleName(name)!!))
            jdkImport(name) != null -> listOf(jdkImport(name)!!)
            else -> closest(name, SdkIndex.types.map { show(it) }).map { "did you mean $it? ($SDK_IMPORT)" }
        }
    }

    /** An import the compiler does not see. */
    private fun importHints(name: String): List<String> {
        val simple = name.substringAfterLast('.')
        return when {
            name.startsWith(SDK_PACKAGE) -> closest(simple, SdkIndex.types.map { show(it) }).map { "did you mean $it? ($SDK_IMPORT)" }
            GAME_PACKAGES.any { name.startsWith(it) } -> listOfNotNull(GAME_RULE, GAME_TYPES[simple]?.let { "$simple → $it" })
            name.startsWith(MOD_PACKAGE) -> listOf(MOD_RULE)
            usable(name) -> emptyList()
            else -> listOf(JDK_RULE)
        }
    }

    /** A name in a package of the JDK a module may use (a misspelt class, not a forbidden package). */
    private fun usable(qualified: String): Boolean = qualified.substringBeforeLast('.') in JDK_PACKAGES + "java.lang"

    private fun importOf(type: Class<*>): String = if (type.packageName == SDK_PACKAGE) SDK_IMPORT else "import ${type.packageName}.${show(type)};"

    /** `import java.util.List;` when a package a module may use has a class of that simple name. */
    private fun jdkImport(simple: String): String? = JDK_PACKAGES.firstOrNull { pkg ->
        try {
            Class.forName("$pkg.$simple", false, null)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }?.let { "import $it.$simple;" }

    /** The type name before `.name` at [start] (`Limits` in `Limits.MAX_ACT`); null when there is none. */
    private fun qualifierBefore(source: String, start: Int): String? {
        if (start < 2 || start > source.length || source[start - 1] != '.') return null
        val end = start - 1
        var from = end
        while (from > 0 && Character.isJavaIdentifierPart(source[from - 1])) from--
        return source.substring(from, end).takeIf { it.isNotEmpty() && Character.isJavaIdentifierStart(it.first()) }
    }

    /** The qualified name written at [start] (`net.minecraft.world.level.Level`). */
    private fun qualifiedAt(source: String, start: Int): String? {
        if (start < 0 || start >= source.length) return null
        var end = start
        while (end < source.length && (Character.isJavaIdentifierPart(source[end]) || source[end] == '.')) end++
        return source.substring(start, end).trimEnd('.').ifEmpty { null }
    }

    /** The name an `import` line at [start] brings in (`net.minecraft.server.level.ServerPlayer`). */
    private fun importAt(source: String, start: Int): String? {
        if (start < 0 || start >= source.length) return null
        val lineStart = source.lastIndexOf('\n', start) + 1
        val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        return IMPORT.find(source.substring(lineStart, lineEnd))?.groupValues?.get(1)?.removeSuffix(".*")
    }

    /**
     * The candidates closest to [name] by edit distance (a transposition counts one), ignoring case: within [tolerance]
     * (by default a third of the name, at least 1), or containing it or contained in it (4 characters at least).
     */
    internal fun closest(name: String, candidates: Collection<String>, limit: Int = 3, tolerance: Int? = null): List<String> {
        val wanted = name.lowercase()
        val most = tolerance ?: max(1, wanted.length / 3)
        return candidates.asSequence()
            .map { it to distance(wanted, it.lowercase()) }
            .filter { (candidate, d) ->
                val other = candidate.lowercase()
                d <= most || (tolerance == null && min(wanted.length, other.length) >= 4 && (other.contains(wanted) || wanted.contains(other)))
            }
            .filter { (candidate, _) -> candidate != name }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** The optimal string alignment distance: insertions, deletions, substitutions and adjacent transpositions. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    // ── Signatures ───────────────────────────────────────────────────────────────────────────────────────────

    /** `Acts (ctx.acts())`: an SDK type, and how a module reaches it from its context when it can in one call. */
    private fun owner(type: Class<*>): String {
        if (type == ModuleContext::class.java) return "ModuleContext (ctx)"
        val path = ModuleContext::class.java.methods.firstOrNull { it.parameterCount == 0 && it.returnType == type }
        return if (path != null) "${show(type)} (ctx.${path.name}())" else show(type)
    }

    private fun signature(method: Method): String = buildString {
        if (Modifier.isStatic(method.modifiers)) append("static ")
        if (method.typeParameters.isNotEmpty()) append(method.typeParameters.joinToString(", ", "<", "> ") { typeParameter(it) })
        append(show(method.genericReturnType)).append(' ').append(method.name)
        append(parameters(method.genericParameterTypes, names(method), method.isVarArgs))
    }

    private fun signature(type: Class<*>, constructor: Constructor<*>): String {
        val components = type.recordComponents?.takeIf { it.size == constructor.parameterCount }?.map { it.name }
        return show(type) + parameters(constructor.genericParameterTypes, components ?: names(constructor), constructor.isVarArgs)
    }

    /** The names of [executable]'s parameters when its bytecode has them (`-parameters`) and they match its generic types; else null. */
    private fun names(executable: Executable): List<String>? =
        executable.parameters.takeIf { all -> all.size == executable.genericParameterTypes.size && all.all { it.isNamePresent } }?.map { it.name }

    private fun parameters(types: Array<Type>, names: List<String>?, varArgs: Boolean): String = types.indices.joinToString(", ", "(", ")") { i ->
        val shown = show(types[i]).let { if (varArgs && i == types.lastIndex && it.endsWith("[]")) it.removeSuffix("[]") + "..." else it }
        names?.getOrNull(i)?.let { "$shown $it" } ?: shown
    }

    private fun typeParameter(variable: TypeVariable<*>): String {
        val bounds = variable.bounds.filter { it != Any::class.java }
        return variable.name + if (bounds.isEmpty()) "" else " extends " + bounds.joinToString(" & ") { show(it) }
    }

    /** A type as a module writes it: simple names, `GameFact.Chat`, `List<PlayerInfo>`, `Consumer<? super F>`. */
    private fun show(type: Type): String = when (type) {
        is Class<*> -> when {
            type.isArray -> show(type.componentType) + "[]"
            else -> type.name.substringAfterLast('.').replace('$', '.')
        }
        is ParameterizedType -> show(type.rawType) + type.actualTypeArguments.joinToString(", ", "<", ">") { show(it) }
        is WildcardType -> when {
            type.lowerBounds.isNotEmpty() -> "? super " + show(type.lowerBounds[0])
            type.upperBounds.firstOrNull()?.let { it != Any::class.java } == true -> "? extends " + show(type.upperBounds[0])
            else -> "?"
        }
        is TypeVariable<*> -> type.name
        is GenericArrayType -> show(type.genericComponentType) + "[]"
        else -> type.typeName
    }

    private const val SDK_PACKAGE = "io.github.clemsix6.narrator2.forge.api"
    private const val MOD_PACKAGE = "io.github.clemsix6.narrator2."
    private val GAME_PACKAGES = listOf("net.minecraft.", "com.mojang.", "net.fabricmc.")
    private val JDK_PREFIXES = listOf("java.", "javax.", "jdk.", "sun.")
    private val JDK_PACKAGES = listOf("java.util", "java.util.function", "java.util.stream", "java.time", "java.time.format", "java.time.temporal", "java.util.concurrent.atomic")
    private val IMPORT = Regex("""^\s*import\s+(?:static\s+)?([\w.$*]+)""")

    /** What the game's classes a model reaches for first are in the SDK. */
    private val GAME_TYPES: Map<String, String> = buildMap {
        val player = "a player is a name (String); ctx.world().player(name) gives a PlayerInfo"
        val creature = "a creature of the module is a Creature (ctx.creatures()); the others, a CreatureInfo (ctx.world().creatures(…))"
        val world = "read the world through World (ctx.world()), change it through Acts (ctx.acts())"
        val item = "an item is an id (\"diamond\"): ctx.world().count(player, item)"
        val block = "a block is an id (\"stone\"): ctx.world().block(cell) gives a BlockInfo; Ground (ctx.ground()) places temporary ones"
        val effect = "an effect is an id: ctx.acts().effect(player, effect, seconds, level)"
        listOf("ServerPlayer", "Player").forEach { put(it, player) }
        listOf("Entity", "LivingEntity", "Mob", "PathfinderMob", "Animal", "Monster").forEach { put(it, creature) }
        listOf("Level", "ServerLevel", "MinecraftServer").forEach { put(it, world) }
        listOf("ItemStack", "Item", "Items").forEach { put(it, item) }
        listOf("Block", "BlockState", "Blocks").forEach { put(it, block) }
        listOf("MobEffect", "MobEffects", "MobEffectInstance").forEach { put(it, effect) }
        put("BlockPos", "a block's position is a Cell(dimension, x, y, z)")
        put("Vec3", "a point is a Place(dimension, x, y, z)")
        put("EntityType", "a creature type is an id (\"cow\"): ctx.acts().spawn(type, near, how)")
        put("Component", "a text is a String")
        put("ServerTickEvents", "facts come through Events (ctx.events().on(…)), time through Timers (ctx.timers())")
    }

    /**
     * What a model reaches for first and the SDK names otherwise (lower case → the SDK's method): the names of the
     * game, of Bukkit and of other mods' APIs, for the same things.
     */
    private val SYNONYMS: Map<String, String> = buildMap {
        listOf("actionbar", "sendactionbar", "showactionbar", "setactionbar").forEach { put(it, "hud") }
        listOf("message", "sendmessage", "broadcast", "broadcastmessage", "chat", "announce").forEach { put(it, "say") }
        listOf("tell", "whisper", "sendto", "messageto").forEach { put(it, "sayTo") }
        listOf("sendtitle", "showtitle", "settitle").forEach { put(it, "title") }
        listOf("playsound", "sendsound").forEach { put(it, "sound") }
        listOf("particle", "spawnparticle", "spawnparticles").forEach { put(it, "particles") }
        listOf("firework", "launchfirework").forEach { put(it, "fireworks") }
        listOf("strike", "strikelightning", "lightningbolt").forEach { put(it, "lightning") }
        listOf("damage", "hurt").forEach { put(it, "smite") }
        listOf("explode", "createexplosion").forEach { put(it, "explosion") }
        listOf("addeffect", "giveeffect", "applyeffect", "addpotioneffect", "potion").forEach { put(it, "effect") }
        listOf("tp", "teleportto").forEach { put(it, "teleport") }
        listOf("summon", "spawnentity", "spawnmob", "spawncreature").forEach { put(it, "spawn") }
        listOf("find", "findplayer", "getplayer", "getplayerbyname").forEach { put(it, "player") }
        listOf("getplayers", "onlineplayers", "getonlineplayers", "allplayers").forEach { put(it, "players") }
        listOf("setblock", "placeblock", "placeblocks", "setblocks").forEach { put(it, "place") }
        listOf("schedule", "runlater", "later", "delay", "runtasklater").forEach { put(it, "after") }
        listOf("repeat", "runtimer", "interval", "runtasktimer").forEach { put(it, "every") }
        listOf("subscribe", "listen", "register", "addlistener").forEach { put(it, "on") }
        listOf("finish", "stopmodule", "done").forEach { put(it, "end") }
    }

    /** The SDK's types and members as a module sees them, by reflection on the SDK itself. */
    private object SdkIndex {
        /** Every public type of the SDK: reached from its roots through public signatures, member types and permitted subtypes. */
        val types: List<Class<*>> by lazy {
            val found = LinkedHashSet<Class<*>>()
            val queue = ArrayDeque<Class<*>>(ROOTS)
            while (queue.isNotEmpty()) {
                val type = queue.removeFirst()
                if (!isSdk(type) || !Modifier.isPublic(type.modifiers) || !found.add(type)) continue
                queue += type.declaredClasses
                type.permittedSubclasses?.let { queue += it }
                queue += type.interfaces
                type.superclass?.let { queue += it }
                type.methods.forEach { method -> (method.genericParameterTypes + method.genericReturnType).forEach { queue += classes(it) } }
                type.fields.forEach { queue += classes(it.genericType) }
                type.constructors.forEach { constructor -> constructor.genericParameterTypes.forEach { queue += classes(it) } }
            }
            found.sortedBy { it.name }
        }

        /** What no public signature names: the module's interface, the bench, the constants, the helpers, the annotations, the refusal. */
        private val ROOTS = listOf(
            ForgeModule::class.java, Bench::class.java, Sdk::class.java, Limits::class.java, Text::class.java, ModuleInfo::class.java,
            ParamList::class.java, ForgeRefusal::class.java,
        )

        fun isSdk(type: Class<*>): Boolean = type.packageName in EcjCompiler.SDK_PACKAGES

        fun type(qualified: String): Class<*>? = types.firstOrNull { it.canonicalName == qualified || it.name == qualified }

        fun bySimpleName(simple: String): Class<*>? = types.firstOrNull { show(it) == simple }

        /** Its public methods a module calls: those the SDK declares, without `equals`, `hashCode`, `toString` and the bridges. */
        fun methods(type: Class<*>): List<Method> = type.methods
            .filter { isSdk(it.declaringClass) && !it.isBridge && !it.isSynthetic && it.name !in OBJECT_METHODS }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))

        fun fields(type: Class<*>): List<Field> = type.fields.sortedBy { it.name }

        /** Its public static methods that make one: `Spawn.of(int)`. */
        fun factories(type: Class<*>): List<Method> = type.methods.filter { Modifier.isStatic(it.modifiers) && it.returnType == type }

        private val OBJECT_METHODS = setOf("equals", "hashCode", "toString")

        private fun classes(type: Type): List<Class<*>> = when (type) {
            is Class<*> -> if (type.isArray) classes(type.componentType) else listOf(type)
            is ParameterizedType -> classes(type.rawType) + type.actualTypeArguments.flatMap { classes(it) }
            is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap { classes(it) }
            // Only the raw bounds: `E extends Enum<E>` names itself.
            is TypeVariable<*> -> type.bounds.mapNotNull { bound -> bound as? Class<*> ?: (bound as? ParameterizedType)?.rawType as? Class<*> }
            is GenericArrayType -> classes(type.genericComponentType)
            else -> emptyList()
        }
    }

    /** The SDK's types this index knows: every public type of its two packages (a test compares them with the packages). */
    internal val sdkTypes: List<Class<*>> get() = SdkIndex.types
}
