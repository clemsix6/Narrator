package io.github.clemsix6.narrator2.forge.compile

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import kotlinx.coroutines.future.await
import org.eclipse.jdt.core.compiler.CategorizedProblem
import org.eclipse.jdt.core.compiler.CompilationProgress
import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import java.io.IOException
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val log = Log.forge

/**
 * One problem ECJ found in a source (M5 §5.3).
 *
 * @property line and [column]: where it starts, from 1; 0 for a problem of the whole source.
 * @property start and [end]: the offsets of the faulty text in the source, both included; -1 for none.
 * @property id ECJ's `IProblem` id, 0 for none; [arguments] its arguments (qualified type names, a selector, the
 *   parameter types…), which the hints of [Diagnostics] read.
 */
data class SourceProblem(
    val error: Boolean,
    val line: Int,
    val column: Int,
    val start: Int,
    val end: Int,
    val message: String,
    val id: Int = 0,
    val arguments: List<String> = emptyList(),
)

/**
 * What one compilation gave (M5 §5.1).
 *
 * @property classes binary name (`TagGame`, `TagGame$Phase`) → bytecode, as ECJ wrote it (neither guarded nor
 *   rewritten); empty unless [outcome] is [Outcome.COMPILED].
 * @property problems what ECJ found, errors and warnings, in the order of the source.
 * @property millis how long it took on the compiler's thread, from its start; 0 when it did not run.
 */
class Compilation(val outcome: Outcome, val classes: Map<String, ByteArray>, val problems: List<SourceProblem>, val millis: Long) {
    enum class Outcome {
        /** Compiled: [classes] holds every class; [problems] holds its warnings, if any. */
        COMPILED,

        /** The source has errors ([problems]): what the forger fixes. */
        ERRORS,

        /** Cancelled past its budget (`ForgeLimits.COMPILE_MS`): `FAILED: the compilation took too long`. */
        TOO_LONG,

        /** Not run: the compiler is stuck until the server restarts (M5 §3.8, `compiler stuck`). */
        STUCK,

        /** Not run, or cut short: the compiler was stopped (`SERVER_STOPPING`). */
        STOPPED,
    }

    val ok: Boolean get() = outcome == Outcome.COMPILED

    val errors: List<SourceProblem> get() = problems.filter { it.error }

    override fun toString(): String = "Compilation($outcome, ${classes.keys}, ${errors.size} errors, $millis ms)"
}

/**
 * The Forge's compiler (M5 §5.1), the first barrier of the sandbox (M5 §3.2): ECJ's native API, in memory, on a
 * thread of its own.
 *
 * A source is one compilation unit, `<TypeName>.java`, **without a package** (a `package` line is an error). It is
 * compiled against what a module may see, read by name when ECJ asks, never listed in advance: the **exported**
 * packages of `java.base`, from the runtime image (`jrt:/`, which a JRE has too), and the SDK's two packages
 * (`forge.api`, `forge.api.test`), read through [sdkLoader] (Knot in game, the tests' loader in unit tests). Nothing
 * else exists for it: `net.minecraft`, Fabric, the mod, `forge.rt`, `jdk.internal`, `sun.misc` and the other
 * modules of the JDK all "cannot be resolved".
 *
 * One thread, [THREAD_NAME] (a daemon, at the lowest priority), compiles one source at a time, in the order asked;
 * the callers suspend, never block, and the server thread never compiles. A compilation past [budgetMs] is cancelled
 * at ECJ's next check (between two of its phases) and its caller gets [Compilation.Outcome.TOO_LONG] right then;
 * one that ignores its cancellation up to [stuckMs] makes the compiler **stuck until the server restarts** (M5 §3.8):
 * every compilation waiting or asked afterwards gets [Compilation.Outcome.STUCK], and [onStuck] is told once (on a
 * pool thread) with the source's label and when its compilation started.
 *
 * **Both limits are the compiler's own work** (M5-LOAD): its thread's CPU time on the compilation, read about every
 * [WATCH_MS] and at each of ECJ's checks, never the wall clock. At the lowest priority on a saturated machine, a
 * compilation of 40 ms of work once took 13.8 s of wall clock (M5-15): that is the machine's doing, not the source's.
 * Without a CPU clock, the wall clock, as before.
 *
 * Built when the Forge starts: without ECJ on the class path, the constructor throws `NoClassDefFoundError`.
 */
class EcjCompiler(
    private val sdkLoader: ClassLoader = ForgeModule::class.java.classLoader,
    private val budgetMs: Long = ForgeLimits.COMPILE_MS,
    private val stuckMs: Long = ForgeLimits.COMPILER_STUCK_MS,
    private val onStuck: (label: String, since: Instant) -> Unit = { _, _ -> },
) {
    private class Job(val typeName: String, val source: String, val label: String) {
        val result = CompletableFuture<Compilation>()

        /** When it started on the compiler's thread, epoch milliseconds. */
        @Volatile
        var startedAt = 0L

        /** When it started, on the clock of [spent]: the compiler thread's CPU time (or [System.nanoTime] without one). */
        @Volatile
        var clockAtStart = 0L
    }

    private class Stuck(val label: String, val since: Instant)

    private val environment = Visible(sdkLoader)
    private val queue = LinkedBlockingQueue<Job>()
    private val stopPill = Job("Stop", "", "stop")

    @Volatile
    private var running: Job? = null

    @Volatile
    private var stuck: Stuck? = null

    @Volatile
    private var stopped = false

    private val thread = Thread(null, ::work, THREAD_NAME, STACK_BYTES).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        thread.start()
    }

    /**
     * Compiles [source], the file `[typeName].java`, on the compiler's thread; [label] names it in the logs and the
     * status (the module's name). Suspends until it is done, past its budget, or refused (stuck, stopped); cancelling
     * the caller drops it. The pipeline checks the source first (M5 §5.4 step 1): a [typeName] that is not a class
     * name or a source over `ForgeLimits.SOURCE_CHARS` throws `IllegalArgumentException`.
     */
    suspend fun compile(typeName: String, source: String, label: String = typeName): Compilation {
        require(CLASS_NAME.matches(typeName)) { "not a class name: $typeName" }
        require(source.length <= ForgeLimits.SOURCE_CHARS) {
            "a source of ${source.length} characters, over ${ForgeLimits.SOURCE_CHARS}: the pipeline checks the size first"
        }
        refusal()?.let { return it }
        val job = Job(typeName, source, label)
        queue.add(job)
        // stop() or the stuck watch may have emptied the queue between the check and the add.
        refusal()?.let { job.result.complete(it) }
        return job.result.await()
    }

    /** When the compiler got stuck: the start of the compilation that ignored its cancellation; null while it works. Any thread. */
    val stuckSince: Instant? get() = stuck?.since

    /** What `/narrator forge status` shows of the compiler (M5 §14): `idle`, `compiling statues (2s)`, `stuck since 21:04:10`, `stopped`. Any thread. */
    fun status(): String {
        stuck?.let { return "stuck since ${CLOCK_TIME.format(it.since.atZone(ZoneId.systemDefault()))}" }
        if (stopped) return "stopped"
        val job = running ?: return "idle"
        return "compiling ${job.label} (${(System.currentTimeMillis() - job.startedAt) / 1_000}s)"
    }

    /**
     * `SERVER_STOPPING`: nothing compiles any more; the waiting compilations and the running one get
     * [Compilation.Outcome.STOPPED] at once, and the thread ends after ECJ's next check. Never waits.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        drain(Compilation.Outcome.STOPPED)
        running?.result?.complete(empty(Compilation.Outcome.STOPPED))
        queue.add(stopPill)
        log.debug { "the compiler is stopped" }
    }

    private fun refusal(): Compilation? = when {
        stopped -> empty(Compilation.Outcome.STOPPED)
        stuck != null -> empty(Compilation.Outcome.STUCK)
        else -> null
    }

    private fun drain(outcome: Compilation.Outcome) {
        generateSequence { queue.poll() }.forEach { it.result.complete(empty(outcome)) }
    }

    private fun work() {
        while (!stopped) {
            val job = try {
                queue.take()
            } catch (e: InterruptedException) {
                continue
            }
            if (job === stopPill || job.result.isDone) continue
            if (stuck != null) {
                job.result.complete(empty(Compilation.Outcome.STUCK))
                continue
            }
            run(job)
        }
    }

    private fun run(job: Job) {
        val start = System.nanoTime()
        job.startedAt = System.currentTimeMillis()
        job.clockAtStart = clock()
        running = job
        watch(job)
        val compilation = try {
            ecj(job, start)
        } catch (e: Throwable) {
            crashed(job, e, start)
        } finally {
            running = null
        }
        job.result.complete(compilation)
        when (compilation.outcome) {
            Compilation.Outcome.COMPILED -> log.debug { "${job.label}: compiled in ${compilation.millis} ms (${compilation.classes.size} classes)" }
            Compilation.Outcome.ERRORS -> log.debug {
                "${job.label}: ${compilation.errors.size} compilation errors in ${compilation.millis} ms, first: ${compilation.errors.firstOrNull()?.message}"
            }
            Compilation.Outcome.TOO_LONG ->
                if (job.result.isCancelled) {
                    log.debug { "${job.label}: dropped by its caller after ${compilation.millis} ms" }
                } else {
                    log.warn("${job.label}: the compilation took more than $budgetMs ms of the compiler's work; cancelled after ${compilation.millis} ms")
                }
            else -> log.debug { "${job.label}: ${compilation.outcome.name.lowercase()} after ${compilation.millis} ms" }
        }
    }

    private fun ecj(job: Job, start: Long): Compilation {
        val classes = LinkedHashMap<String, ByteArray>()
        val problems = mutableListOf<SourceProblem>()
        var accepted = false
        var errors = false
        val requestor = ICompilerRequestor { result ->
            accepted = true
            if (result.hasErrors()) errors = true
            result.allProblems.orEmpty().forEach { problems += it.toSourceProblem() }
            result.classFiles.forEach { classes[String(it.fileName()).replace('/', '.')] = it.bytes }
        }
        val progress = Progress { stopped || job.result.isDone || spent(job) >= budgetMs }
        val compiler = Compiler(
            environment, DefaultErrorHandlingPolicies.proceedWithAllProblems(), options(), requestor,
            DefaultProblemFactory(Locale.ROOT), null, progress,
        )
        compiler.useSingleThread = true
        compiler.compile(arrayOf<ICompilationUnit>(SourceUnit(job.typeName, job.source)))
        val millis = (System.nanoTime() - start) / 1_000_000
        return when {
            stopped -> Compilation(Compilation.Outcome.STOPPED, emptyMap(), emptyList(), millis)
            !accepted && progress.cancelled -> Compilation(Compilation.Outcome.TOO_LONG, emptyMap(), emptyList(), millis)
            !accepted -> failed("the compiler gave no result for this source", millis)
            errors -> Compilation(Compilation.Outcome.ERRORS, emptyMap(), problems, millis)
            else -> Compilation(Compilation.Outcome.COMPILED, classes, problems, millis)
        }
    }

    /** ECJ threw (it records its own "Internal compiler error" first, then rethrows): an error of the source for the forger, the details for the log. */
    private fun crashed(job: Job, e: Throwable, start: Long): Compilation {
        val millis = (System.nanoTime() - start) / 1_000_000
        return if (e is StackOverflowError) {
            log.warn("${job.label}: ECJ ran out of stack after $millis ms (StackOverflowError): the source nests too deeply")
            failed("the source nests too deeply for the compiler (StackOverflowError): split the longest expressions and the deepest nestings", millis)
        } else {
            log.warn("${job.label}: ECJ failed after $millis ms", e)
            failed("the compiler failed on this source (${e.javaClass.simpleName}): write the part it chokes on another way", millis)
        }
    }

    /**
     * Every [WATCH_MS] while [job] compiles: past [budgetMs] of the compiler's work, its caller gets
     * [Compilation.Outcome.TOO_LONG] at once (ECJ stops at its next check); past [stuckMs], ECJ ignored its
     * cancellation: the compiler is stuck.
     */
    private fun watch(job: Job) {
        CompletableFuture.delayedExecutor(WATCH_MS, TimeUnit.MILLISECONDS).execute {
            if (running !== job) return@execute
            val spent = spent(job)
            if (spent >= budgetMs) job.result.complete(Compilation(Compilation.Outcome.TOO_LONG, emptyMap(), emptyList(), System.currentTimeMillis() - job.startedAt))
            if (spent >= stuckMs) stuck(job) else watch(job)
        }
    }

    /** The milliseconds of the compiler's own work on [job] so far: its thread's CPU time (the wall clock without a CPU clock). */
    private fun spent(job: Job): Long = (clock() - job.clockAtStart) / 1_000_000

    /** The compiler thread's CPU time, or [System.nanoTime] without a CPU clock. Any thread. */
    private fun clock(): Long {
        val cpu = CPU?.getThreadCpuTime(thread.threadId()) ?: -1
        return if (cpu >= 0) cpu else System.nanoTime()
    }

    private fun stuck(job: Job) {
        val since = Instant.ofEpochMilli(job.startedAt)
        synchronized(this) {
            if (stopped || stuck != null || running !== job) return
            stuck = Stuck(job.label, since)
        }
        log.warn("the compiler is stuck: ${job.label} has been compiling for more than $stuckMs ms of the compiler's work despite its cancellation; no more compilation until the server restarts")
        drain(Compilation.Outcome.STUCK)
        try {
            onStuck(job.label, since)
        } catch (e: Exception) {
            log.error("the compiler is stuck, and telling the Forge failed", e)
        }
    }

    private fun empty(outcome: Compilation.Outcome) = Compilation(outcome, emptyMap(), emptyList(), 0)

    private fun failed(message: String, millis: Long) =
        Compilation(Compilation.Outcome.ERRORS, emptyMap(), listOf(SourceProblem(true, 0, 0, -1, -1, message)), millis)

    /** ECJ asks it between its phases; once it said yes, the compilation is over (ECJ aborts silently). */
    private class Progress(private val cancel: () -> Boolean) : CompilationProgress() {
        @Volatile
        var cancelled = false
            private set

        override fun isCanceled(): Boolean = cancelled || cancel().also { cancelled = it }

        override fun begin(remainingWork: Int) {}

        override fun done() {}

        override fun setTaskName(name: String?) {}

        override fun worked(workIncrement: Int, remainingWork: Int) {}
    }

    companion object {
        const val THREAD_NAME = "narrator2-forge-compiler"

        /** The compiler thread's stack, fixed so that how deeply a source may nest does not depend on `-Xss` (about 15,000 levels). */
        private const val STACK_BYTES = 1L shl 20

        /** How often the compiler's work on a compilation is read, while it compiles (the CPU clock moves by steps of 15.6 ms under Windows). */
        const val WATCH_MS = 100L

        /** The JVM's CPU clock of a thread; null without one. */
        private val CPU: ThreadMXBean? = ManagementFactory.getThreadMXBean().takeIf { it.isThreadCpuTimeSupported && it.isThreadCpuTimeEnabled }

        /** The SDK's packages: the only ones of the mod a module sees. */
        val SDK_PACKAGES = setOf("io.github.clemsix6.narrator2.forge.api", "io.github.clemsix6.narrator2.forge.api.test")

        private val CLASS_NAME = Regex("[A-Z][A-Za-z0-9]*")
        private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** ECJ's version (`3.46.100`), read from its own messages, for `/narrator forge status`; null when it cannot be read. */
        val version: String? by lazy {
            try {
                Compiler::class.java.classLoader.getResourceAsStream("org/eclipse/jdt/internal/compiler/batch/messages.properties")?.use { stream ->
                    // compiler.version = v20260826-1225, 3.46.100
                    Properties().apply { load(stream) }.getProperty("compiler.version")?.substringAfterLast(',')?.trim()?.ifEmpty { null }
                }
            } catch (e: IOException) {
                null
            }
        }

        /** The options of M5 §5.1: Java 25; lines, source file and local variables kept; deprecation, unused imports, raw types and unchecked operations not reported. */
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

        private fun CategorizedProblem.toSourceProblem() = SourceProblem(
            error = isError,
            line = sourceLineNumber,
            column = (this as? DefaultProblem)?.sourceColumnNumber ?: 0,
            start = sourceStart,
            end = sourceEnd,
            message = message,
            id = id,
            arguments = arguments.orEmpty().toList(),
        )
    }
}

/** One source without a package. Its module is the unnamed one: without it, ECJ throws a `NullPointerException` (M5 §5.1). */
private class SourceUnit(private val typeName: String, private val source: String) : ICompilationUnit {
    override fun getContents(): CharArray = source.toCharArray()

    override fun getMainTypeName(): CharArray = typeName.toCharArray()

    override fun getPackageName(): Array<CharArray> = emptyArray()

    override fun getFileName(): CharArray = "$typeName.java".toCharArray()

    override fun module(environment: LookupEnvironment): ModuleBinding = environment.UnNamedModule
}

/**
 * What a module sees (M5 §3.2, barrier 1): the packages `java.base` exports to everyone, read from the runtime image,
 * and the SDK's packages, read by name through [sdkLoader]. Called on the compiler's thread only.
 */
private class Visible(private val sdkLoader: ClassLoader) : INameEnvironment {
    private val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
    private val base: Set<String> = Any::class.java.module.descriptor.exports().filterNot { it.isQualified }.map { it.source() }.toSet()
    private val packages: Set<String> = (base + EcjCompiler.SDK_PACKAGES).flatMap { name ->
        name.split('.').runningReduce { parent, part -> "$parent.$part" }
    }.toSet()

    override fun findType(compoundTypeName: Array<CharArray>): NameEnvironmentAnswer? = find(compoundTypeName.joinToString(".") { String(it) })

    override fun findType(typeName: CharArray, packageName: Array<CharArray>): NameEnvironmentAnswer? =
        find((packageName.map { String(it) } + String(typeName)).joinToString("."))

    override fun isPackage(parentPackageName: Array<CharArray>?, packageName: CharArray): Boolean =
        (parentPackageName.orEmpty().map { String(it) } + String(packageName)).joinToString(".") in packages

    override fun cleanup() {}

    private fun find(binaryName: String): NameEnvironmentAnswer? {
        val pkg = binaryName.substringBeforeLast('.', "")
        val path = binaryName.replace('.', '/') + ".class"
        val bytes = try {
            when (pkg) {
                in EcjCompiler.SDK_PACKAGES -> sdkLoader.getResourceAsStream(path)?.use { it.readAllBytes() }
                in base -> jrt.getPath("/modules", "java.base", path).takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }
                else -> null
            }
        } catch (e: IOException) {
            log.warn("the compiler could not read $path", e)
            null
        } ?: return null
        return try {
            NameEnvironmentAnswer(ClassFileReader(bytes, path.toCharArray(), true), null)
        } catch (e: ClassFormatException) {
            log.warn("the compiler could not read $path", e)
            null
        }
    }
}
