package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.config.ForgeApproval
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.arsenal.Manifest
import io.github.clemsix6.narrator2.forge.compile.Compilation
import io.github.clemsix6.narrator2.forge.compile.Diagnostic
import io.github.clemsix6.narrator2.forge.compile.Diagnostics
import io.github.clemsix6.narrator2.forge.compile.EcjCompiler
import io.github.clemsix6.narrator2.forge.host.Fault
import io.github.clemsix6.narrator2.forge.host.LostThreads
import io.github.clemsix6.narrator2.forge.host.ModuleHost
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sandbox.RewriteRefused
import io.github.clemsix6.narrator2.forge.sim.BenchHost
import io.github.clemsix6.narrator2.forge.sim.BenchImpl
import io.github.clemsix6.narrator2.forge.sim.BenchReport
import io.github.clemsix6.narrator2.forge.sim.BenchRun
import io.github.clemsix6.narrator2.forge.sim.HostCall
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicsService
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * The Forge (M5 §2, §5.4, §6.1 to §6.3, §11): the arsenal ([ArsenalStore]) and the pipeline from a source to a module
 * ready to load. `NarratorServices.forge` once the assembly (M5-21) builds it; [start] it, then [stop] it at
 * `SERVER_STOPPING`.
 *
 * | Step (M5 §5.4) | Who | Refusal | [test] | [save] | [build] |
 * |---|---|---|---|---|---|
 * | 1. name, size, no `package` | here | `Invalid arguments` | ✓ | ✓ | ✓ |
 * | 2. compilation | [EcjCompiler] | the diagnostics | ✓ | ✓ | ✓ |
 * | 3-4. shape, allow-list | [BytecodeGuard] | `refused …` | ✓ | ✓ | ✓ |
 * | 5. manifest | [Manifest.read] | `@Param …` | ✓ | ✓ | ✓ |
 * | 6. rewriting | [Instrumenter] | `refused …` (a failure is the mod's: `FAILED`) | ✓ | ✓ | ✓ |
 * | 7. bench: smoke run, dry checks, scenarios | [BenchRun] on a [ModuleHost] | `fault …`, `refused act …` | ✓ | ✓ | |
 * | 8. a new version | [ArsenalStore.save] | `DENIED (arsenal_full)` | | ✓ | |
 *
 * [save] also wants one scenario at least; a saved version is `ready`, or `pending` with `forge.approval = operator`
 * (M5 §6.3). [build] takes a version that is `ready` (`pending`: `DENIED (awaiting_approval)`; `broken`: `FAILED`) from
 * the **cache** of the last [ForgeLimits.BUILD_CACHE] built versions (by name, version, hash and `Sdk.VERSION`), else
 * builds it again from its source, checked against its hash: one that no longer builds against the SDK of now becomes
 * `broken` until the server restarts (M5 §6.1, §6.7). A save puts its version in the cache: its first deployment does
 * not compile again.
 *
 * **Threads.** Nothing heavy runs on the caller's thread: ECJ on its compiler thread, the guard, the manifest and the
 * rewriting on `Dispatchers.Default`, the bench on `Dispatchers.IO` and a module thread of its own (sharing the Forge's
 * [lostThreads]), the arsenal's files on `Dispatchers.IO`. Calls may come from several coroutines at once (the forger,
 * a deployment, an operator).
 *
 * **Start** ([start], or the first call): reads the arsenal, loads the modules' runtime once (`ModuleHost.preload`),
 * builds the built-in modules (one that does not build is an `ERROR` and stays out of the arsenal), then checks the
 * forged versions one at a time in the background. Every call waits for the arsenal and the built-in modules.
 *
 * **Unavailable** ([available] false): the arsenal could not be read, the compiler is stuck (M5 §3.8), too many module
 * threads were lost ([lostThreads]), or the server stops. Every call then throws `DENIED (forge_off): …`.
 * [onUnavailable] hears it once, with why, but for the server's stop (M5-LOAD, for M5-21's assembly).
 *
 * **One build at a time per version**: two callers that miss the cache together (the start's check of the forged
 * versions and a deployment built again after a restart) share one compilation, the second waiting for the first.
 *
 * **Decisions** `forge` (M5 §15): `tested` (or `refused` when the sandbox refused it), `saved`, `compiled` (a version
 * built for a deployment), `broken`, `approved`, `rejected`, `stuck`.
 *
 * [dryCheck]: the dry checks of the bench's acts ([engineDryCheck] by default: the engine's tools and the modules' own).
 * [newCompiler] makes the compiler, given what it calls when it gets stuck (a test gives one with a short budget).
 * Without ECJ on the class path, the constructor throws `NoClassDefFoundError`: the assembly's `DisabledForge`.
 */
class ForgeService(
    private val services: NarratorServices,
    private val store: ArsenalStore = ArsenalStore(services.dataDir.resolve(ARSENAL_DIR), services.clock),
    private val dryCheck: suspend (tool: String, args: JsonObject) -> ToolResult? = engineDryCheck(services),
    newCompiler: (onStuck: (label: String, since: Instant) -> Unit) -> EcjCompiler = { onStuck -> EcjCompiler(onStuck = onStuck) },
    private val onUnavailable: (reason: String) -> Unit = {},
) : Forge {
    private val log = Log.forge
    private val decisions = Decisions(services.trace, services.clock)
    private val compiler: EcjCompiler = newCompiler(::compilerStuck)

    /**
     * The module threads lost by abandonment (M5 §3.7), one for the whole Forge: the bench's module threads and the
     * engine's share it (the assembly gives it to the engine's `ModuleThreads`). Past its limit, the Forge is stuck
     * until the server restarts: every call of a module faults (`Fault.Kind.STUCK`), and the Forge is unavailable.
     */
    val lostThreads = LostThreads(onStuck = ::threadsStuck)

    private val cache = BuildCache()

    /** The builds under way, by version: a second caller waits for the first ([construct]). */
    private val building = ConcurrentHashMap<Key, CompletableDeferred<Unit>>()

    /** Whether [onUnavailable] was told. */
    private val toldUnavailable = java.util.concurrent.atomic.AtomicBoolean()
    private val loaded = CompletableDeferred<Boolean>()
    private val benches: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private var startup: Job? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var stopped = false

    override val arsenal: Arsenal get() = store

    override val available: Boolean get() = unavailableReason == null

    override val unavailableReason: String?
        get() {
            failure?.let { return it }
            if (stopped) return "the server is stopping"
            compiler.stuckSince?.let { return "the compiler is stuck since ${time(it)}: a compilation ignored its cancellation; it works again once the server restarts" }
            lostThreads.stuckSince?.let { return "too many module threads were lost since ${time(it)}: no module runs until the server restarts" }
            return null
        }

    /**
     * Starts the Forge in [scope] (the services' by default), off the server thread, once (a second call returns the
     * same job): reads the arsenal, loads the modules' runtime, builds the built-in modules, then checks each forged
     * version that is `ready` or `pending` against the SDK of now, one at a time (one that no longer builds becomes
     * `broken`). The first call of the Forge starts it when nobody did.
     */
    fun start(scope: CoroutineScope = services.scope): Job = synchronized(this) {
        startup ?: scope.launch { open() }.also { startup = it }
    }

    override suspend fun test(name: String, source: String): TestReport {
        ready()
        return examine(name, source, toSave = false).report
    }

    override suspend fun save(name: String, source: String, note: String, by: String): ArsenalEntry {
        ready()
        val examined = examine(name, source, toSave = true)
        val built = examined.built
        if (!examined.report.ok || built == null) throw ForgeRejected(null, examined.report.text)
        val approval = services.settings.get().config.forge.approvalMode
        val status = if (approval == ForgeApproval.OPERATOR) ModuleStatus.PENDING else ModuleStatus.READY
        val entry = store.save(built.manifest, source, note, by, status)
        cache.put(key(entry), built)
        decide(
            SAVED, "${entry.name} v${entry.version}", "${entry.status.id}, by $by: ${note.trim()}",
            buildJsonObject {
                put("module", entry.name)
                put("version", entry.version)
                put("status", entry.status.id)
                put("by", by)
                put("hash", entry.hash)
                put("classes", entry.classes)
                put("bytes", entry.bytes)
            },
        )
        return entry
    }

    override suspend fun build(name: String, version: Int?): Built {
        ready()
        Manifest.nameProblem(name)?.let { throw ForgeRejected(null, it) }
        return construct(deployable(name, version))
    }

    override suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry {
        ready()
        val entry = store.review(name, version, approve, operator)
        decide(
            if (approve) APPROVED else REJECTED, "${entry.name} v${entry.version}", "by $operator",
            buildJsonObject {
                put("module", entry.name)
                put("version", entry.version)
                put("operator", operator)
            },
        )
        return entry
    }

    /**
     * The last fault of a deployment of [name] at [version] (M5 §3.7), shown in `module_list` and `/narrator forge show`
     * until the server restarts; the engine (M5-11) tells it. In memory, any thread.
     */
    override fun noteFault(name: String, version: Int, fault: String) {
        store.noteFault(name, version, fault)
    }

    override fun status(): ForgeStatus = ForgeStatus(EcjCompiler.version, compiler.status())

    override fun stop() {
        if (stopped) return
        stopped = true
        compiler.stop()
        synchronized(this) { startup }?.cancel()
        benches.forEach { it.cancel() }
        loaded.complete(false)
        log.info("the Forge is stopped")
    }

    // ── Start ─────────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun open() {
        val began = System.nanoTime()
        try {
            try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "the arsenal cannot be read (${e.javaClass.simpleName}: ${e.message})"
                log.error("the Forge is unavailable: $failure")
                unavailableNow()
                return
            }
            withContext(Dispatchers.IO) { ModuleHost.preload() }
            val built = builtIns()
            loaded.complete(true)
            log.info(
                "the Forge is ready in ${(System.nanoTime() - began) / NANOS_PER_MS} ms: ECJ ${EcjCompiler.version}, " +
                    "$built of ${store.builtInSources.size} built-in modules built, ${count(store.list().count { !it.builtIn }, "forged version")}",
            )
        } finally {
            loaded.complete(false)
        }
        checkForged()
    }

    /** Builds each built-in module of the jar (steps 1 to 6) and lists it; returns how many were. */
    private suspend fun builtIns(): Int {
        var built = 0
        for ((name, source) in store.builtInSources) {
            if (stopped) break
            val began = System.nanoTime()
            val prepared = try {
                prepare(name, source)
            } catch (e: ForgeRejected) {
                log.error("the built-in module $name was not built: ${e.message}")
                continue
            }
            when (prepared) {
                is Prepared.Refused -> log.error("the built-in module $name does not build, so it is not in the arsenal: ${prepared.first}")
                is Prepared.Ready -> {
                    val entry = store.addBuiltIn(prepared.manifest)
                    cache.put(key(entry), prepared)
                    built++
                    log.debug { "built-in module $name built in ${(System.nanoTime() - began) / NANOS_PER_MS} ms (compiled in ${prepared.compileMillis} ms)" }
                }
            }
        }
        return built
    }

    /** Builds each forged version a deployment or an approval may want, newest first: one that no longer builds becomes `broken`. */
    private suspend fun checkForged() {
        val versions = store.list().filter { !it.builtIn && (it.status == ModuleStatus.READY || it.status == ModuleStatus.PENDING) }
        if (versions.isEmpty()) return
        val began = System.nanoTime()
        var broken = 0
        for (entry in versions) {
            if (!available) return
            try {
                construct(entry)
            } catch (e: ForgeRejected) {
                if (store.entry(entry.name, entry.version)?.status == ModuleStatus.BROKEN) broken++ else log.warn("arsenal: ${entry.name} v${entry.version} was not checked: ${e.message}")
            }
        }
        log.info("arsenal: ${versions.size} forged versions checked against the SDK in ${(System.nanoTime() - began) / NANOS_PER_MS} ms, $broken broken")
    }

    /** Waits for the start; throws `DENIED (forge_off)` when the Forge is unavailable. */
    private suspend fun ready() {
        start()
        if (!loaded.await() || !available) throw unavailable()
    }

    private fun unavailable() = ForgeRejected(DisabledForge.FORGE_OFF, "the Forge is unavailable: ${unavailableReason ?: "it could not start"}")

    // ── forge_test, forge_save ────────────────────────────────────────────────────────────────────────────────

    /** What `forge_test` found, and the module built when it got that far. */
    private class Examined(val report: TestReport, val built: Prepared.Ready?)

    /**
     * Steps 1 to 7 for [source] as module [name]: the report the forger reads, and a decision `tested` (`refused` when
     * the sandbox refused it). [toSave]: `forge_save`'s, which wants one scenario at least before the bench runs.
     */
    private suspend fun examine(name: String, source: String, toSave: Boolean): Examined {
        val began = System.nanoTime()
        val prepared = prepare(name, source)
        val examined = when (prepared) {
            is Prepared.Refused -> Examined(TestReport(false, prepared.text(source), emptyList(), null), null)
            is Prepared.Ready -> {
                val head = "compiled (${prepared.compileMillis} ms), guard clean, "
                val manifest = prepared.manifest.entry(0, ModuleStatus.READY, false, services.clock.instant(), TESTED_BY, null, Manifest.hash(source))
                if (toSave && prepared.manifest.scenarios.isEmpty()) {
                    Examined(TestReport(false, "${head}but $NO_SCENARIO", emptyList(), manifest), prepared)
                } else {
                    val bench = bench(prepared)
                    val text = if (bench.ok) {
                        "ok: $head${bench.text(source)}" + if (prepared.manifest.scenarios.isEmpty()) "; forge_save needs one scenario at least" else ""
                    } else {
                        head + bench.text(source)
                    }
                    Examined(TestReport(bench.ok, cutReport(text), bench.scenariosPassed, manifest), prepared)
                }
            }
        }
        val report = examined.report
        decide(
            if (prepared is Prepared.Refused && prepared.sandbox) REFUSED else TESTED,
            name,
            report.text.lineSequence().first().take(DECISION_REASON_CHARS),
            buildJsonObject {
                put("ok", report.ok)
                put("for", if (toSave) "save" else "test")
                put("ms", (System.nanoTime() - began) / NANOS_PER_MS)
                (prepared as? Prepared.Ready)?.let {
                    put("compile_ms", it.compileMillis)
                    put("scenarios", it.manifest.scenarios.size)
                }
                put("scenarios_passed", report.scenariosPassed.size)
                put("hash", Manifest.hash(source))
            },
            level = Log.Level.INFO,
        )
        return examined
    }

    /**
     * Runs [built] at the bench (M5 §6.2, §7): a host of its own (its loader, its meter, its module thread, the
     * Forge's lost threads), the line of an abandoned call waited for, and a busy machine's patience
     * ([BENCH_PATIENCE_NANOS]: the bench's worker is no server thread, it may wait for a module thread held back);
     * cancelled with its caller, or by [stop].
     */
    private suspend fun bench(built: Prepared.Ready): BenchReport {
        val job = currentCoroutineContext()[Job]
        job?.let(benches::add)
        val threads = ModuleThreads(BENCH_THREAD, lostThreads)
        var host: ModuleHost? = null
        try {
            host = withContext(Dispatchers.IO) {
                try {
                    ModuleHost(
                        built.manifest.mainClass, built.classes, threads, "bench ${built.manifest.name}",
                        lineWaitMillis = LINE_WAIT_MS, patienceNanos = BENCH_PATIENCE_NANOS,
                    )
                } catch (e: Exception) {
                    log.error("the bench could not load ${built.manifest.name}: a defect of the mod", e)
                    throw ForgeRejected(null, "the bench could not load ${built.manifest.name} (${e.javaClass.simpleName}): a defect of the mod, not of the module", failed = true)
                }
            }
            return BenchRun(HostBench(host), built.manifest.params, built.manifest.scenarios, dryCheck).run()
        } finally {
            job?.let(benches::remove)
            threads.close()
            host?.unload()
        }
    }

    // ── Build ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** [name] at [version] (its latest `ready` one when null), when it may be deployed; else the refusal of M5 §6.4 step 2. */
    private fun deployable(name: String, version: Int?): ArsenalEntry {
        val versions = store.list().filter { it.name == name }
        if (versions.isEmpty()) throw ForgeRejected(null, "no module $name in the arsenal")
        val entry = if (version == null) {
            store.entry(name, null) ?: versions.maxBy { it.version }
        } else {
            versions.firstOrNull { it.version == version }
                ?: throw ForgeRejected(null, "$name has no version $version (its versions: ${versions.map { it.version }.sorted().joinToString()})")
        }
        return when (entry.status) {
            ModuleStatus.READY -> entry
            ModuleStatus.PENDING -> throw ForgeRejected(
                AWAITING_APPROVAL,
                "$name v${entry.version} waits for an operator's approval (/narrator forge approve $name ${entry.version})",
            )
            ModuleStatus.REJECTED -> throw ForgeRejected(null, "$name v${entry.version} was rejected by an operator: deploy another version")
            ModuleStatus.BROKEN -> throw ForgeRejected(null, "$name v${entry.version} is broken: ${entry.problem}", failed = true)
        }
    }

    /**
     * [entry]'s classes, from the cache or built from its source (read again and checked against its hash). One that
     * no longer builds is marked `broken` (M5 §6.7) and throws `FAILED: statues v1 is broken: it no longer builds against
     * the SDK of now: <first problem>`. One build at a time per version: a caller that finds it under way waits for it,
     * then takes it from the cache (or its refusal), never compiles it again.
     */
    private suspend fun construct(entry: ArsenalEntry): Built {
        val key = key(entry)
        val label = "${entry.name} v${entry.version}"
        while (true) {
            cache.get(key)?.let { return Built(entry, it.manifest.mainClass, it.classes) }
            val mine = CompletableDeferred<Unit>()
            val other = building.putIfAbsent(key, mine)
            if (other == null) {
                try {
                    return rebuild(entry, key, label)
                } finally {
                    building.remove(key, mine)
                    mine.complete(Unit)
                }
            }
            other.await()
            store.entry(entry.name, entry.version)?.takeIf { it.status == ModuleStatus.BROKEN }?.let {
                throw ForgeRejected(null, "$label is broken: ${it.problem}", failed = true)
            }
        }
    }

    /** [construct]'s build, the only one under way for [key]. */
    private suspend fun rebuild(entry: ArsenalEntry, key: Key, label: String): Built {
        val began = System.nanoTime()
        val source = store.checkedSource(entry.name, entry.version)
        when (val prepared = prepare(entry.name, source)) {
            is Prepared.Refused -> {
                val problem = "it no longer builds against the SDK of now: ${prepared.first}"
                store.markBroken(entry.name, entry.version, problem)
                decide(BROKEN, label, problem, level = Log.Level.WARN)
                throw ForgeRejected(null, "$label is broken: $problem", failed = true)
            }
            is Prepared.Ready -> {
                cache.put(key, prepared)
                decide(
                    COMPILED, label, "in ${(System.nanoTime() - began) / NANOS_PER_MS} ms (ECJ ${prepared.compileMillis} ms)",
                    buildJsonObject {
                        put("module", entry.name)
                        put("version", entry.version)
                        put("compile_ms", prepared.compileMillis)
                        put("classes", prepared.classes.size)
                    },
                    level = Log.Level.DEBUG,
                )
                return Built(entry, prepared.manifest.mainClass, prepared.classes)
            }
        }
    }

    // ── Steps 1 to 6 ──────────────────────────────────────────────────────────────────────────────────────────

    /** What steps 1 to 6 made of a source. */
    private sealed interface Prepared {
        /** Refused: [head] says at which step, [problems] what (rendered with the source's lines); [sandbox] when the guard or the rewriting refused it. */
        class Refused(val head: String, val problems: List<Diagnostic>, val sandbox: Boolean = false) : Prepared {
            /** The first problem, one line: what a `broken` version shows. */
            val first: String get() = problems.firstOrNull()?.let { "${it.where}: ${it.message}".replace(Regex("\\s*\\R\\s*"), " ") } ?: head

            /** The report: [head], then the problems, within `ForgeLimits.TEST_REPORT_CHARS` and the `Invalid arguments: ` before it. */
            fun text(source: String): String {
                if (problems.isEmpty()) return cutReport(head)
                return head + "\n" + Diagnostics.render(problems, source, REPORT_CHARS - head.length - 1)
            }
        }

        /** Compiled, guarded, its manifest read, rewritten. */
        class Ready(val manifest: Manifest, val classes: Map<String, ByteArray>, val compileMillis: Long) : Prepared
    }

    /** Steps 1 to 6 for [source] as module [name]. Throws [ForgeRejected] for what is not the source's fault (a compilation too long, a stuck compiler, the server stopping, a defect of the mod). */
    private suspend fun prepare(name: String, source: String): Prepared {
        sourceProblem(name, source)?.let { return Prepared.Refused(it, emptyList()) }
        val main = Manifest.mainClass(name)
        val compiled = compiler.compile(main, source, name)
        when (compiled.outcome) {
            Compilation.Outcome.COMPILED -> Unit
            Compilation.Outcome.ERRORS -> {
                val errors = compiled.errors.size
                return Prepared.Refused("does not compile: $errors error${if (errors == 1) "" else "s"} (${compiled.millis} ms)", Diagnostics.compilation(main, source, compiled.problems))
            }
            Compilation.Outcome.TOO_LONG -> throw ForgeRejected(
                null, "the compilation of $name took too long (over ${ForgeLimits.COMPILE_MS / 1_000} s): write a simpler source", failed = true,
            )
            Compilation.Outcome.STUCK -> throw unavailable()
            Compilation.Outcome.STOPPED -> throw ForgeRejected(null, "the server is stopping", failed = true)
        }
        return withContext(Dispatchers.Default) { sandboxed(name, main, source, compiled) }
    }

    /** Steps 3 to 6, off the caller's thread. */
    private fun sandboxed(name: String, main: String, source: String, compiled: Compilation): Prepared {
        val head = "compiled (${compiled.millis} ms)"
        val violations = BytecodeGuard().check(compiled.classes)
        if (violations.isNotEmpty()) {
            val problems = violations.map { Diagnostics.refused(main, it.line, "${it.what}: ${it.reason}") }.distinct()
            log.info("$name refused by the guard: ${violations.joinToString("; ")}")
            return Prepared.Refused("$head, refused by the sandbox: ${count(problems.size, "problem")}", problems, sandbox = true)
        }
        val manifest = try {
            Manifest.read(name, compiled.classes)
        } catch (e: ForgeRejected) {
            val problems = e.message.orEmpty().lines().filter { it.isNotBlank() }.map { Diagnostic(Diagnostic.Kind.ERROR, "$main.java", it) }
            return Prepared.Refused("$head, guard clean, but the module's form is wrong: ${count(problems.size, "problem")}", problems)
        }
        val classes = try {
            Instrumenter.rewrite(main, compiled.classes)
        } catch (e: RewriteRefused) {
            log.info("$name refused by the rewriting: ${e.message}")
            return Prepared.Refused("$head, refused by the sandbox: 1 problem", listOf(Diagnostics.refused(main, 0, e.message.orEmpty())), sandbox = true)
        } catch (e: Exception) {
            log.error("the rewriting of $name failed: a defect of the mod (source ${Manifest.hash(source)})", e)
            throw ForgeRejected(null, "the rewriting of $name failed (${e.javaClass.simpleName}): a defect of the mod, not of the module", failed = true)
        }
        return Prepared.Ready(manifest, classes, compiled.millis)
    }

    /** Step 1: why [source] cannot be module [name] at all, or null. */
    private fun sourceProblem(name: String, source: String): String? {
        Manifest.nameProblem(name)?.let { return it }
        if (source.isBlank()) return "source is empty: write the whole Java file"
        if (source.length > ForgeLimits.SOURCE_CHARS) return "the source is ${source.length} characters long, at most ${ForgeLimits.SOURCE_CHARS}: write a shorter module"
        PACKAGE.find(source)?.let { found ->
            val line = source.substring(0, found.range.first + found.value.indexOf("package")).count { it == '\n' } + 1
            return "line $line: ${Diagnostics.NO_PACKAGE}"
        }
        return null
    }

    // ── Decisions, stuck ──────────────────────────────────────────────────────────────────────────────────────

    private fun decide(what: String, subject: String?, reason: String?, detail: JsonObject? = null, level: Log.Level = Log.Level.INFO) {
        try {
            decisions.record(log, DecisionRecord.Kind.FORGE, what, subject, reason, detail = detail, level = level)
        } catch (e: Exception) {
            log.error("the decision forge $what $subject could not be recorded", e)
        }
    }

    private fun compilerStuck(label: String, since: Instant) {
        decide(STUCK, "compiler", "the compilation of $label started at ${time(since)} ignored its cancellation: the Forge is unavailable until the server restarts", level = Log.Level.ERROR)
        unavailableNow()
    }

    private fun threadsStuck(why: String) {
        decide(STUCK, "module threads", why, level = Log.Level.ERROR)
        unavailableNow()
    }

    /** The Forge became unavailable while the server runs: [onUnavailable] is told, once, with why. Any thread. */
    private fun unavailableNow() {
        if (stopped) return
        val reason = unavailableReason ?: return
        if (!toldUnavailable.compareAndSet(false, true)) return
        try {
            onUnavailable(reason)
        } catch (e: Exception) {
            log.error("telling that the Forge is unavailable failed", e)
        }
    }

    private fun time(at: Instant): String = CLOCK_TIME.format(at.atZone(services.clock.zone))

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

    /** The built versions kept, the least recently used dropped first ([ForgeLimits.BUILD_CACHE]). Any thread. */
    private class BuildCache {
        private val map = object : LinkedHashMap<Key, Prepared.Ready>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Prepared.Ready>?): Boolean = size > ForgeLimits.BUILD_CACHE
        }

        @Synchronized
        fun get(key: Key): Prepared.Ready? = map[key]

        @Synchronized
        fun put(key: Key, built: Prepared.Ready) {
            map[key] = built
        }
    }

    /** A built version: its name, version, source hash, and the SDK it was built against. */
    private data class Key(val name: String, val version: Int, val hash: String, val sdk: Int)

    private fun key(entry: ArsenalEntry) = Key(entry.name, entry.version, entry.hash, Sdk.VERSION)

    /**
     * The bench's way into the real host (M5-05 handoff): a fault as the bench words it, the module's line, its own
     * work (what the bench judges, never the wall clock: M5-LOAD), the memory gauge.
     */
    private class HostBench(private val host: ModuleHost) : BenchHost {
        override val main: Class<*> get() = host.main

        override fun call(budgetNanos: Long, body: () -> Unit): HostCall {
            val called = host.call(BENCH_CALL, budgetNanos, body)
                ?: return HostCall(0, "the module no longer runs: ${host.disabled?.text ?: host.stalled ?: "it was unloaded"}")
            if (called.skipped) return HostCall(called.nanos, BenchImpl.OVERLOADED, work = 0)
            val fault = called.fault ?: return HostCall(called.nanos, null, work = called.work)
            return HostCall(called.nanos, if (fault.kind == Fault.Kind.LIMIT) "ForgeLimit: ${fault.cause}" else fault.cause, fault.line, called.work)
        }

        override fun line(error: Throwable): Int = host.line(error)

        override fun retained(roots: List<Any>): Long? = host.measure(roots)?.bytes
    }

    companion object {
        /** The arsenal's folder in `world/narrator2/`. */
        const val ARSENAL_DIR = "forge"

        /** The rule of a deployment of a version an operator has not approved yet: `DENIED (awaiting_approval): …`. */
        const val AWAITING_APPROVAL = "awaiting_approval"

        /** What `forge_save` answers a module without scenarios. */
        const val NO_SCENARIO =
            "save needs at least one scenario: a nested class public static class Scenarios with methods public void name(Bench b)"

        /** The decisions `forge` of the pipeline and the arsenal (M5 §15). */
        const val TESTED = "tested"
        const val REFUSED = "refused"
        const val SAVED = "saved"
        const val COMPILED = "compiled"
        const val BROKEN = "broken"
        const val APPROVED = "approved"
        const val REJECTED = "rejected"
        const val STUCK = "stuck"

        /** Who a `forge_test`'s manifest says saved it (it is not saved). */
        const val TESTED_BY = "forge_test"

        /** The bench's module threads; the name of its calls in a fault. */
        const val BENCH_THREAD = "narrator2-bench-module"
        private const val BENCH_CALL = "bench"

        /** How long the bench waits for the line of an abandoned call (the forger needs it; M5-05 measured 50 to 65 ms). */
        const val LINE_WAIT_MS = 1_000L

        /**
         * How long past a call's budget the bench waits while the module thread has not computed (M5-LOAD): the bench
         * judges the module's own work, never what a busy server holds its thread back (FLAKY-4 saw a saturated machine
         * hold a call of 75 ms of work past 2 s).
         */
        const val BENCH_PATIENCE_NANOS = 10_000L * 1_000_000

        /** A report's length (`TestReport.text`): within `ForgeLimits.TEST_REPORT_CHARS` with the `Invalid arguments: ` of a refusal. */
        val REPORT_CHARS = ForgeLimits.TEST_REPORT_CHARS - "Invalid arguments: ".length

        private fun cutReport(text: String): String = ModelText.cut(text, REPORT_CHARS)

        private const val DECISION_REASON_CHARS = 300
        private const val NANOS_PER_MS = 1_000_000L
        private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val PACKAGE = Regex("^\\s*package\\s+[\\w.]+\\s*;", RegexOption.MULTILINE)

        /**
         * The dry checks of the acts a module asked at the bench (M5 §6.2, M5-08 handoff): `EngineActions.check` with a
         * caller of the Director's, over the engine's tools ([MechanicsService.engineTools]) and the modules' own
         * (`hud`, `place_blocks`, `creature_attack`): ids, the caps of the chaos level, arguments that contradict each
         * other. Nothing runs, nothing is recorded.
         */
        fun engineDryCheck(services: NarratorServices): suspend (tool: String, args: JsonObject) -> ToolResult? {
            val actions = EngineActions(services, (MechanicsService.engineTools(services) + moduleOnlyTools()).distinctBy { it.name })
            val caller = Caller(Role.DIRECTOR, EngineActions.conversationOf(null), turnId = null, taskId = null)
            return { tool, args -> actions.check(tool, args, caller) }
        }
    }
}
