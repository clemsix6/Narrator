package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.ModuleRun
import io.github.clemsix6.narrator2.mechanics.RuleKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.jdt.core.compiler.CategorizedProblem
import org.eclipse.jdt.internal.compiler.CompilationResult
import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Samples of the Forge for every task of M5 (design M5 §11): a legitimate module as the forger writes one (the
 * witness `tag_game`, `src/test/resources/forge/samples/TagGame.java`), its classes as ECJ compiles them against the
 * SDK (not rewritten), its manifest, its deployment and its entry in the state.
 *
 * [compile] is the tests' own compilation, as M5 §5.1 describes the real one (task M5-02, `EcjCompiler`): ECJ, native
 * API, in memory, one unit without a package; the module sees `java.base` (the runtime image, `jrt:/`) and the SDK's
 * two packages, read by name through the mod's class loader, nothing else. It lets the guard (M5-03) and the
 * rewriting (M5-04) test real bytecode before the compiler exists. Never on the server thread.
 */
object ForgeSamples {
    const val NAME = "tag_game"
    const val CLASS = "TagGame"

    /** The witness module's source. */
    val SOURCE: String by lazy {
        ForgeSamples::class.java.getResourceAsStream("/forge/samples/$CLASS.java").use { stream ->
            String(requireNotNull(stream) { "no forge/samples/$CLASS.java among the test resources" }.readAllBytes(), Charsets.UTF_8)
        }
    }

    /** Its classes as ECJ compiles them (binary name → bytecode), before the guard and the rewriting. */
    val classes: Map<String, ByteArray> by lazy { compile(CLASS, SOURCE).also { check(it.ok) { "the witness does not compile: ${it.problems}" } }.classes }

    /** `sha256:` and the hex digest of [SOURCE], as a manifest writes it. */
    val hash: String by lazy { "sha256:" + MessageDigest.getInstance("SHA-256").digest(SOURCE.toByteArray()).joinToString("") { "%02x".format(it) } }

    /** Its manifest, version 1, saved by the forger's task T-31 on the evening of [MechanicsSamples.evening]. */
    val manifest: ArsenalEntry by lazy {
        ArsenalEntry(
            name = NAME, version = 1, status = ModuleStatus.READY, builtIn = false,
            summary = "Tag: the wolf passes on by touch; whoever was the wolf the least wins.",
            params = listOf(
                ParamSpec("players", ParamType.PLAYERS, "Who plays.", required = true, fallback = null, min = 2.0, max = 8.0, choices = emptyList()),
                ParamSpec("minutes", ParamType.INTEGER, "How long it lasts.", required = false, fallback = "5", min = 2.0, max = 15.0, choices = emptyList()),
                ParamSpec("mood", ParamType.TEXT, "How the Narrator speaks.", required = false, fallback = "taquin", min = null, max = null, choices = listOf("taquin", "sévère")),
            ),
            scenarios = listOf("theWolfPassesOnByTouch", "theLeastWolfWins"),
            savedAt = Instant.ofEpochMilli(MechanicsSamples.evening), by = "forger T-31", note = "A game of tag that referees a judged challenge.",
            hash = hash, sdk = Sdk.VERSION, classes = classes.size, bytes = classes.values.sumOf { it.size }, problem = null,
        )
    }

    /** Its parameters as the Director writes them. */
    val params = JsonObject(mapOf("players" to JsonArray(listOf(JsonPrimitive("Bartok"), JsonPrimitive("Mimosa"))), "minutes" to JsonPrimitive(5)))

    /** `deploy_module` of it as a law, refereeing C-75. */
    val deploy = DeploySpec(
        module = NAME, version = null, params = params, kind = RuleKind.LAW, statement = "Le Narrateur a lancé une partie de loup entre Bartok et Mimosa.",
        quote = "Que le loup coure !", minutes = 10, challenge = MechanicsSamples.canon(75),
    )

    /** Its entry in the state: C-70, deployed by the Director's turn 42, Bartok the wolf. */
    val run: ModuleRun by lazy {
        ModuleRun(
            id = 70, module = NAME, version = 1, hash = hash, params = params, kind = RuleKind.LAW,
            createdAt = MechanicsSamples.evening, endsAt = MechanicsSamples.evening + 10 * 60_000L, challenge = 75,
            playerIds = listOf(MechanicsSamples.bartok.uuid.toString(), MechanicsSamples.mimosa.uuid.toString()),
            storage = mapOf("wolf" to "Bartok"), turnId = 42, conversationId = "director-c13", acts = 3, faults = 0,
        )
    }

    /** What [compile] gives: [classes] (binary name → bytecode) when [ok], and every problem ECJ found, one line each. */
    class Compiled(val ok: Boolean, val classes: Map<String, ByteArray>, val problems: List<String>)

    /** The SDK's packages, the only ones of the mod a module sees. */
    private val SDK_PACKAGES = setOf("io.github.clemsix6.narrator2.forge.api", "io.github.clemsix6.narrator2.forge.api.test")

    /** Compiles [source], the file `[typeName].java` without a package, against `java.base` and the SDK only. */
    fun compile(typeName: String, source: String): Compiled {
        val classes = LinkedHashMap<String, ByteArray>()
        val problems = mutableListOf<String>()
        var failed = false
        val requestor = ICompilerRequestor { result: CompilationResult ->
            if (result.hasErrors()) failed = true
            result.allProblems.orEmpty().forEach { problems += describe(it) }
            result.classFiles.forEach { classes[String(it.fileName()).replace('/', '.')] = it.bytes }
        }
        val compiler = Compiler(Environment(), DefaultErrorHandlingPolicies.proceedWithAllProblems(), options(), requestor, DefaultProblemFactory(Locale.ROOT))
        compiler.compile(arrayOf<ICompilationUnit>(Unit(typeName, source)))
        return Compiled(!failed, if (failed) emptyMap() else classes, problems)
    }

    private fun describe(problem: CategorizedProblem): String {
        val column = (problem as? DefaultProblem)?.sourceColumnNumber ?: -1
        return "${if (problem.isError) "error" else "warning"} ${problem.sourceLineNumber}:$column ${problem.message}"
    }

    private fun options(): CompilerOptions = CompilerOptions(
        mapOf(
            CompilerOptions.OPTION_Compliance to "25",
            CompilerOptions.OPTION_Source to "25",
            CompilerOptions.OPTION_TargetPlatform to "25",
            CompilerOptions.OPTION_LineNumberAttribute to CompilerOptions.GENERATE,
            CompilerOptions.OPTION_SourceFileAttribute to CompilerOptions.GENERATE,
            CompilerOptions.OPTION_LocalVariableAttribute to CompilerOptions.GENERATE,
            CompilerOptions.OPTION_Encoding to "UTF-8",
            CompilerOptions.OPTION_ReportDeprecation to CompilerOptions.IGNORE,
            CompilerOptions.OPTION_ReportUnusedImport to CompilerOptions.IGNORE,
            CompilerOptions.OPTION_ReportRawTypeReference to CompilerOptions.IGNORE,
            CompilerOptions.OPTION_ReportUncheckedTypeOperation to CompilerOptions.IGNORE,
        ),
    )

    /** One unit, without a package. Its module is the unnamed one: without it, ECJ throws a `NullPointerException` (M5 §5.1). */
    private class Unit(private val typeName: String, private val source: String) : ICompilationUnit {
        override fun getContents(): CharArray = source.toCharArray()

        override fun getMainTypeName(): CharArray = typeName.toCharArray()

        override fun getPackageName(): Array<CharArray> = emptyArray()

        override fun getFileName(): CharArray = "$typeName.java".toCharArray()

        override fun module(environment: LookupEnvironment): ModuleBinding = environment.UnNamedModule
    }

    /** What the module sees: `java.base` from `jrt:/`, and the SDK read by name through the mod's class loader. */
    private class Environment : INameEnvironment {
        private val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        private val basePackages: Set<String> = Any::class.java.module.packages
        private val packages: Set<String> = (basePackages + SDK_PACKAGES).flatMap { name ->
            name.split('.').runningReduce { parent, part -> "$parent.$part" }
        }.toSet()
        private val loader: ClassLoader = ForgeModule::class.java.classLoader

        override fun findType(compoundTypeName: Array<CharArray>): NameEnvironmentAnswer? = find(compoundTypeName.joinToString(".") { String(it) })

        override fun findType(typeName: CharArray, packageName: Array<CharArray>): NameEnvironmentAnswer? =
            find((packageName.map { String(it) } + String(typeName)).joinToString("."))

        override fun isPackage(parentPackageName: Array<CharArray>?, packageName: CharArray): Boolean =
            (parentPackageName.orEmpty().map { String(it) } + String(packageName)).joinToString(".") in packages

        override fun cleanup() {}

        private fun find(binaryName: String): NameEnvironmentAnswer? {
            val pkg = binaryName.substringBeforeLast('.', "")
            val path = binaryName.replace('.', '/') + ".class"
            val bytes = when (pkg) {
                in SDK_PACKAGES -> loader.getResourceAsStream(path)?.use { it.readAllBytes() }
                in basePackages -> jrt.getPath("/modules", "java.base", path).takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }
                else -> null
            } ?: return null
            return NameEnvironmentAnswer(ClassFileReader(bytes, path.toCharArray(), true), null)
        }
    }
}
