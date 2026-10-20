package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.compile.Diagnostic
import io.github.clemsix6.narrator2.forge.compile.Diagnostics
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * How the bench runs a module's code (M5 §7): the same loader, rewriting and host as in the game, with a loader and a
 * meter of its own. The bench never runs a module's code on its own thread: every call goes through [call]. The Forge
 * (M5-10) makes one per `forge_test` from the module's built classes and M5-05's host; the unit tests run modules
 * written in Kotlin through a host of their own, without a compiler.
 */
interface BenchHost {
    /** The module's class: it implements `ForgeModule` and has a public constructor without arguments; not initialized yet. */
    val main: Class<*>

    /**
     * Runs [body], the module's code, as one call measured as in the game (M5-04 handoff, finding 1): on a module
     * thread, never the caller's, armed for [budgetNanos] of its own work; the caller waits for it while it has not
     * computed past that (a busy machine holding its thread back is not its doing), then gives it up (the bench then
     * closes the deployment's SDK, which refuses the given-up thread from then on). Never throws: what stopped the call
     * is [HostCall.fault].
     *
     * Calls nest: a scenario's own call (armed for what is left of the bench's time) runs the bench's ticks, which make
     * the module's calls, each through [call] again, from the scenario's module thread, armed for 20 ms on its own.
     */
    fun call(budgetNanos: Long, body: () -> Unit): HostCall

    /** The line of the module's first frame in [error]'s stack trace (`TagGame.java:42` → 42); 0 when none is the module's. */
    fun line(error: Throwable): Int

    /**
     * The bytes the module retains from [roots] (its instance, the handlers, tasks and callbacks the SDK still holds for
     * it), its classes' static fields with them, as the game's memory gauge estimates them; null when it cannot tell.
     * Between calls, never in one.
     */
    fun retained(roots: List<Any>): Long?
}

/**
 * One measured call of the module ([BenchHost.call]).
 *
 * @property nanos how long it ran on the wall clock (until the caller gave it up, if it did).
 * @property fault what stopped it, as the host keeps a fault (M5 §3.7): `IllegalStateException: no wolf at
 *   TagGame.java:42`, `ForgeLimit: time budget at TagGame.java:41`; null when it returned in time.
 * @property line the module's line [fault] names; 0 when unknown.
 * @property work its own work (M5-LOAD: its code as its meter measured it, its thread's CPU time for a long step of the
 *   JDK): what the bench judges, never [nanos], which a busy machine stretches. The wall time when the host cannot tell.
 */
data class HostCall(val nanos: Long, val fault: String?, val line: Int = 0, val work: Long = nanos)

/**
 * What the bench found (M5 §6.2): the smoke run, the scenarios, the dry checks of the acts.
 *
 * @property ok the smoke run passed, every scenario passed, every act passed its dry checks.
 * @property summary one line: `smoke: start ok, 3 facts, 1,200 ticks, acts say ×2 spawn ×1 (checked), slowest call
 *   0.4 ms, retained 0.2 MiB; scenarios 2/2`.
 * @property problems in order (smoke, its memory, scenarios, acts), each saying where, what and how to fix it:
 *   `fault` ones (`fault smoke, timer 1 at tick 20: …`) and `refused` acts (`refused act "punish Bartok tiny 5": …`), with
 *   the module's line when known: `Diagnostics.render` quotes it from the source.
 * @property scenariosPassed the names of the scenarios that passed.
 */
data class BenchReport(val ok: Boolean, val summary: String, val problems: List<Diagnostic>, val scenariosPassed: List<String>) {
    /**
     * The report for the forger, [MAX_CHARS] at most: [summary], then [problems] as `Diagnostics.render` writes them,
     * quoting [source]'s lines (none without it).
     */
    fun text(source: String?): String {
        val head = ModelText.cut(ModelText.escape(summary), SUMMARY_CHARS)
        if (problems.isEmpty()) return head
        val escaped = problems.map { it.copy(where = ModelText.escape(it.where), message = ModelText.cut(ModelText.escape(it.message), PROBLEM_CHARS)) }
        return head + "\n" + Diagnostics.render(escaped, source, MAX_CHARS - head.length - 1)
    }

    companion object {
        /** The bench's part of `forge_test`'s report (`ForgeLimits.TEST_REPORT_CHARS` in all, the compiler's steps before it). */
        const val MAX_CHARS = 3_000
        const val SUMMARY_CHARS = 400

        /** A problem's message: the module's texts it carries (a check's, an exception's) are cut to this. */
        const val PROBLEM_CHARS = 300
    }
}

/**
 * The bench step of `forge_test` and `forge_save` (M5 §5.4 step 7, §6.2), off the server thread, within [budgetMs] in
 * all:
 * 1. the smoke run ([SmokeRun]);
 * 2. the module's [scenarios] (the names of its `Scenarios` methods, in order), each on a new bench ([BenchImpl.scenario]);
 * 3. the dry checks: each distinct act the smoke run and the scenarios asked (tool and arguments), and each effect or
 *    attribute put on a creature (`mob_effect`, `mob_attribute`), goes through [dryCheck] (the Forge gives
 *    `EngineActions.check` with the Director's caller, the module-only tools among its tools); an `Invalid arguments`
 *    or a `DENIED` is a problem; so is a lasting refusal of the SDK itself (a forbidden effect); a check that fails
 *    to run is only counted.
 *
 * The module is [host]'s, its parameters [params] (its manifest's). The bench never touches the world: the acts are
 * recorded, never done (M5 §7). The smoke run and the scenarios share [host]'s loaded classes (their static fields).
 */
class BenchRun(
    private val host: BenchHost,
    private val params: List<ParamSpec>,
    private val scenarios: List<String>,
    private val dryCheck: suspend (tool: String, args: JsonObject) -> ToolResult?,
    private val budgetMs: Long = ForgeLimits.BENCH_MS,
) {
    private val log = Log.forge

    /** Runs the bench on a worker thread (it waits for the module's calls); cancelled with its coroutine, at the next call or tick. */
    suspend fun run(): BenchReport {
        val started = System.nanoTime()
        val job = currentCoroutineContext()[Job]
        val runs = withContext(Dispatchers.IO) { runs(started + budgetMs * NANOS_PER_MS) { job?.isActive == false } }
        currentCoroutineContext().ensureActive()
        if (runs.cancelled) throw CancellationException("the bench of ${host.main.simpleName} was cancelled")
        val checks = check(runs.dry)
        val report = report(runs, checks)
        log.debug { "bench ${host.main.simpleName} in ${(System.nanoTime() - started) / NANOS_PER_MS} ms: ${report.summary}" }
        report.problems.forEach { problem -> log.debug { "bench ${host.main.simpleName}: ${problem.kind.id} ${problem.where}: ${problem.message}" } }
        return report
    }

    /** The smoke run, then the scenarios. */
    private fun runs(deadline: Long, cancel: () -> Boolean): Runs {
        val smoke = SmokeRun(host, params, deadline, budgetMs, cancel).run()
        val dry = LinkedHashMap<String, DryCall>()
        var dryDropped = smoke.dryDropped
        fun keep(calls: Collection<DryCall>) = calls.forEach { call ->
            if (call.key in dry) return@forEach
            if (dry.size >= SimDeployment.MAX_DRY_CALLS) dryDropped++ else dry[call.key] = call
        }
        keep(smoke.dry)
        var cancelled = smoke.cancelled
        val results = LinkedHashMap<String, Diagnostic?>()
        for (name in scenarios) {
            if (cancelled) break
            if (System.nanoTime() - deadline > 0) {
                results[name] = Diagnostics.fault("scenario $name", "not run: the bench ran out of time")
                continue
            }
            val bench = BenchImpl(host, params, deadline, budgetMs, "scenario $name", BenchImpl.SCENARIO_MINUTES, cancel)
            results[name] = bench.scenario(name)
            bench.deployment?.let {
                keep(it.dry.values)
                dryDropped += it.dryDropped
            }
            cancelled = bench.cancelled
        }
        return Runs(smoke, results, dry.values.toList(), dryDropped, cancelled)
    }

    private class Runs(val smoke: SmokeResult, val scenarios: Map<String, Diagnostic?>, val dry: List<DryCall>, val dryDropped: Int, val cancelled: Boolean)

    /** What the dry checks refused, one problem each, and how many could not be checked. */
    private class Checks(val refused: List<Diagnostic>, val unchecked: Int)

    private suspend fun check(calls: List<DryCall>): Checks {
        val refused = mutableListOf<Diagnostic>()
        var unchecked = 0
        for (call in calls) {
            val result = call.known ?: try {
                dryCheck(call.tool, call.args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("the dry checks of ${call.tool} failed for ${host.main.simpleName}", e)
                ToolResult.failed(e.message ?: e.javaClass.simpleName)
            }
            when (result?.status) {
                null, ToolStatus.OK -> Unit
                ToolStatus.INVALID, ToolStatus.DENIED ->
                    refused += Diagnostic(Diagnostic.Kind.REFUSED, "act \"${call.line}\"", result.text.lineSequence().first(), line = call.moduleLine)
                else -> unchecked++
            }
        }
        return Checks(refused, unchecked)
    }

    private fun report(runs: Runs, checks: Checks): BenchReport {
        val smoke = runs.smoke
        val problems = buildList {
            smoke.failure?.let { add(it) }
            addAll(smoke.memory)
            addAll(runs.scenarios.values.filterNotNull())
            addAll(checks.refused)
        }.distinct()
        val passed = runs.scenarios.filterValues { it == null }.keys.toList()
        val ok = problems.isEmpty() && runs.scenarios.size == scenarios.size
        return BenchReport(ok, summary(runs, checks, passed), problems, passed)
    }

    /** `smoke: start ok, 3 facts, 1,200 ticks, acts say ×2 spawn ×1 (checked), slowest call 0.4 ms, retained 0.2 MiB; scenarios 2/2`. */
    private fun summary(runs: Runs, checks: Checks, passed: List<String>): String = buildString {
        val smoke = runs.smoke
        append("smoke: ")
        append(if (smoke.failure == null) "start ok" else "failed at tick ${smoke.ticks}")
        append(", ${smoke.facts} facts, ${"%,d".format(Locale.ROOT, smoke.ticks)} ticks")
        smoke.endedItselfAt?.let { append(", ended by itself at tick $it") }
        append(", acts ")
        append(if (smoke.acts.isEmpty()) "none" else smoke.acts.entries.joinToString(" ") { "${it.key} ×${it.value}" })
        val unchecked = checks.unchecked + runs.dryDropped
        append(
            when {
                checks.refused.isNotEmpty() -> " (${checks.refused.size} refused by the dry checks)"
                unchecked > 0 -> " ($unchecked not checked)"
                else -> " (checked)"
            },
        )
        append(", slowest call ${BenchImpl.ms(smoke.slowestNanos)} ms")
        append(", retained ${smoke.retained?.let { ToolText.number(it / BYTES_PER_MIB) + " MiB" } ?: "unknown"}")
        if (smoke.logsDropped > 0) append(", ${smoke.logsDropped} log lines dropped")
        append("; ")
        append(if (scenarios.isEmpty()) "no scenarios" else "scenarios ${passed.size}/${scenarios.size}")
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}
