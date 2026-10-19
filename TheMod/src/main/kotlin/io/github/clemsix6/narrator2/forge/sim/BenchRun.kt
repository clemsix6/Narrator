package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * How the bench runs a module's code (M5 §7): with the same loader, rewriting and host as in the game, but a loader
 * and a meter of its own, off the server thread. The Forge (M5-10) makes one per `forge_test` from the module's built
 * classes; the unit tests run modules written in Kotlin through a host of their own, without a compiler.
 */
interface BenchHost {
    /** The module's class: it implements `ForgeModule` and has a public constructor without arguments; not initialized yet. */
    val main: Class<*>

    /**
     * Runs [body], the module's code, as one call measured as in the game, armed for [budgetNanos] of wall clock; never
     * throws: what stopped it is the result's [HostCall.fault]. Calls nest: a scenario's own call (armed for what is
     * left of the bench's time) makes the module's calls of the ticks it runs, each armed for 20 ms on its own; once
     * an inner call returns, the outer one goes on with what is left of its own budget.
     */
    fun call(budgetNanos: Long, body: () -> Unit): HostCall

    /** Where [error] was thrown in the module's code: `TagGame.java:42`; null when none of its frames is the module's. */
    fun where(error: Throwable): String?

    /**
     * The bytes the module retains from [roots] (its instance, the handlers, tasks and callbacks it handed to the
     * SDK), its classes' static fields with them, as the game's memory gauge estimates them; null when it cannot tell.
     * Between calls, never in one.
     */
    fun retained(roots: List<Any>): Long?
}

/**
 * One measured call of the module ([BenchHost.call]).
 *
 * @property nanos how long it ran.
 * @property fault what stopped it, as the host keeps a fault (M5 §3.7): `IllegalStateException: no wolf at
 *   TagGame.java:42`, `ForgeLimit: time budget at TagGame.java:41`; null when it returned.
 */
data class HostCall(val nanos: Long, val fault: String?)

/**
 * What the bench found (M5 §6.2): the smoke run, the dry checks of the acts, the scenarios.
 *
 * @property ok the smoke run passed, every act passed its dry checks, every scenario passed.
 * @property summary one line: `smoke: start ok, 3 facts, 1,200 ticks, acts say ×2 spawn ×1 (checked), slowest call
 *   0.4 ms, retained 0.2 MiB; scenarios 2/2`.
 * @property problems one line each, in order (smoke, memory, scenarios, acts), each saying where and what to do.
 * @property scenariosPassed the names of the scenarios that passed.
 */
data class BenchReport(val ok: Boolean, val summary: String, val problems: List<String>, val scenariosPassed: List<String>) {
    /**
     * The report for the forger: [summary], then the first [SHOWN_PROBLEMS] problems, one line each, cut to
     * [PROBLEM_CHARS], escaped (they carry the module's own texts); [MAX_CHARS] at most in all.
     */
    val text: String
        get() {
            val shown = problems.take(SHOWN_PROBLEMS).map { ModelText.cut(ModelText.escape(it), PROBLEM_CHARS) }
            val more = problems.size - shown.size
            val lines = listOf(ModelText.cut(ModelText.escape(summary), SUMMARY_CHARS)) + shown +
                if (more > 0) listOf("… and $more more problems") else emptyList()
            return ModelText.cut(lines.joinToString("\n"), MAX_CHARS)
        }

    companion object {
        /** The bench's part of `forge_test`'s report (`ForgeLimits.TEST_REPORT_CHARS` in all, the compiler's steps before it). */
        const val MAX_CHARS = 3_000
        const val SUMMARY_CHARS = 400
        const val PROBLEM_CHARS = 300
        const val SHOWN_PROBLEMS = 8
    }
}

/**
 * The bench step of `forge_test` and `forge_save` (M5 §5.4 step 7, §6.2), off the server thread, within
 * [budgetMs] in all:
 * 1. the smoke run ([SmokeRun]);
 * 2. the module's [scenarios] (the names of its `Scenarios` methods, in order), each on a new bench ([BenchImpl.scenario]);
 * 3. the dry checks: each distinct act the smoke run and the scenarios asked (tool and arguments), and each effect or
 *    attribute it put on a creature, goes through [dryCheck] (the Forge gives `EngineActions.check` with the
 *    Director's caller); an `Invalid arguments` or a `DENIED` is a problem, a check that failed is not counted.
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

    /** Runs the bench; cancelled with its coroutine (the module's calls under way end first). */
    suspend fun run(): BenchReport {
        val started = System.nanoTime()
        val runs = runInterruptible(Dispatchers.Default) { runs(started + budgetMs * NANOS_PER_MS) }
        currentCoroutineContext().ensureActive()
        if (runs.cancelled) throw CancellationException("the bench of ${host.main.simpleName} was cancelled")
        val checks = check(runs.dry)
        val report = report(runs, checks)
        log.debug { "bench ${host.main.simpleName} in ${(System.nanoTime() - started) / NANOS_PER_MS} ms: ${report.summary}" }
        report.problems.forEach { problem -> log.debug { "bench ${host.main.simpleName}: $problem" } }
        return report
    }

    /** The smoke run, then the scenarios, on the bench's worker thread. */
    private fun runs(deadline: Long): Runs {
        val smoke = SmokeRun(host, params, deadline, budgetMs).run()
        val dry = LinkedHashMap<String, DryCall>()
        var dryDropped = smoke.dryDropped
        fun keep(calls: Collection<DryCall>) = calls.forEach { call ->
            if (call.key in dry) return@forEach
            if (dry.size >= SimDeployment.MAX_DRY_CALLS) dryDropped++ else dry[call.key] = call
        }
        keep(smoke.dry)
        var cancelled = smoke.cancelled
        val results = LinkedHashMap<String, String?>()
        for (name in scenarios) {
            if (cancelled) break
            if (System.nanoTime() - deadline > 0) {
                results[name] = "scenario $name was not run: the bench ran out of time"
                continue
            }
            val bench = BenchImpl(host, params, deadline, budgetMs, "scenario $name", BenchImpl.SCENARIO_MINUTES)
            results[name] = bench.scenario(name)
            bench.deployment?.let {
                keep(it.dry.values)
                dryDropped += it.dryDropped
            }
            cancelled = bench.cancelled
        }
        return Runs(smoke, results, dry.values.toList(), dryDropped, cancelled)
    }

    private class Runs(val smoke: SmokeResult, val scenarios: Map<String, String?>, val dry: List<DryCall>, val dryDropped: Int, val cancelled: Boolean)

    /** What the dry checks refused, one problem each, and how many could not be checked. */
    private class Checks(val refused: List<String>, val unchecked: Int)

    private suspend fun check(calls: List<DryCall>): Checks {
        val refused = mutableListOf<String>()
        var unchecked = 0
        for (call in calls) {
            val result = try {
                dryCheck(call.tool, call.args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("the dry checks of ${call.tool} failed for ${host.main.simpleName}", e)
                ToolResult.failed(e.message ?: e.javaClass.simpleName)
            }
            when (result?.status) {
                null, ToolStatus.OK -> Unit
                ToolStatus.INVALID, ToolStatus.DENIED -> refused += "act \"${call.line}\": ${result.text.lineSequence().first()}"
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
        if (smoke.actsOverLimits > 0) append(", ${smoke.actsOverLimits} over the module's limits (denied)")
        append(", slowest call ${BenchImpl.ms(smoke.slowestNanos)} ms")
        append(", retained ${smoke.retained?.let { ToolText.number(it / BYTES_PER_MIB) + " MiB" } ?: "unknown"}")
        if (smoke.logsDropped > 0) append(", ${smoke.logsDropped} log lines dropped")
        if (smoke.factsDropped > 0) append(", ${smoke.factsDropped} facts dropped")
        append("; ")
        append(if (scenarios.isEmpty()) "no scenarios" else "scenarios ${passed.size}/${scenarios.size}")
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}
