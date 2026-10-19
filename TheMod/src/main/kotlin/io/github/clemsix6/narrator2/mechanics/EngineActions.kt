package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock
import java.time.Instant

/**
 * The acts of the mechanics engine (M4 §4.3): a rule's firing, a challenge's stakes. The engine acts through
 * the very tools of the Director, never a second path: each call is decoded and bounded by `ToolArgs` like a
 * model's, then runs the tool's own `run` through [ToolRunner.invoke], with the act's [Caller]. Same checks,
 * same caps of the chaos level, same guards, same limits per server ([NarratorServices.toolLimits]) as the
 * Director: a rule cannot do what the Director may not.
 *
 * Each act is an act of its own for the guards ([open]): its own episode, its own `already_punished`,
 * `fair_share` and categories, through [Caller.act] (minus the act's id: never mistaken for a turn).
 *
 * No `tool_calls` row: each call is a `mechanic action` decision instead (tool, status, the first line of
 * its result, the players it affected in `subject`, the act), so that the Director's "last actions" stay
 * his own; the guards' death report reads these decisions too (M4-04).
 *
 * [tools]: what the engine may call, by name (`MechanicsService.TOOLS`: the tools of the rules and of the stakes,
 * M4 review, finding 23); any other is `INVALID`. Which of them a rule or a stake may use is checked when it is
 * created (M4 §8.5), not here.
 */
class EngineActions(private val services: NarratorServices, tools: List<NarratorTool<*>>) {
    private val log = Log.mechanics
    private val byName: Map<String, NarratorTool<*>> = tools.associateBy { it.name }
    private val runner = ToolRunner(services)

    /** The names of the tools the engine may call; internal for the tests. */
    internal val names: Set<String> get() = byName.keys

    init {
        require(byName.size == tools.size) { "duplicate tool names: ${tools.groupBy { it.name }.filterValues { it.size > 1 }.keys}" }
    }

    /**
     * Opens an act: records the `mechanic` decision [what] (`fired`, `stake`…) about [subject] (a player, a
     * rule), for [ref] (the challenge or the rule it acts for), and returns the act, whose id is that
     * decision's. [reason] and [detail] go in the decision; [level] is its log line's (`DEBUG` for the
     * firings, M4 §19). Any thread.
     */
    fun open(
        what: String,
        ref: Ref?,
        subject: String?,
        detail: JsonObject? = null,
        reason: String? = null,
        level: Log.Level = Log.Level.DEBUG,
    ): Firing {
        val conversation = conversationOf(ref)
        val id = Decisions(services.trace, services.clock).record(
            log, DecisionRecord.Kind.MECHANIC, what, subject, reason, conversationId = conversation, detail = detail, level = level,
        )
        return Firing(id, ref, Caller(Role.DIRECTOR, conversation, turnId = null, taskId = null, firingId = id))
    }

    /**
     * Runs [tool] with [args] in [firing], as [Firing.caller], and records its `mechanic action` decision.
     * An unknown tool is `INVALID`; while the server is not running, every call answers
     * `FAILED: server is stopping` without running. A cancellation of the caller (the server stops) records
     * the call as `UNKNOWN` (`FAILED` for a read) and goes through.
     *
     * Never on the server thread, like any tool call: a tool waits for the server thread (`ctx.onServer`),
     * and some for the world (a chunk to load).
     */
    suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult {
        val call = Call(firing, tool, byName[tool], args, services.clock.instant(), System.nanoTime())
        val found = call.found
        val result = when {
            found == null -> ToolResult.invalid("unknown tool \"$tool\" for the engine")
            services.phase != Phase.RUNNING -> ToolResult.failed("server is stopping")
            else -> try {
                runner.invoke(found, firing.caller, args.toString())
            } catch (e: CancellationException) {
                action(call, ToolRunner.interrupted(found))
                throw e
            }
        }
        action(call, result)
        return result
    }

    /**
     * Runs [instead] as the call of [tool] with [args] in [firing], in place of the tool's own run, once the tool's
     * dry checks pass as [Firing.caller] ([check]): a stake the engine pays its own way (an item reward through the
     * ledger, never on the ground: M4 review, finding 36). Its `mechanic action` decision is the one [run] records,
     * with `detail.instead`. Never on the server thread.
     */
    suspend fun runInstead(firing: Firing, tool: String, args: JsonObject, instead: suspend () -> ToolResult): ToolResult {
        val call = Call(firing, tool, byName[tool], args, services.clock.instant(), System.nanoTime(), instead = true)
        val result = check(tool, args, firing.caller) ?: instead()
        action(call, result)
        return result
    }

    /**
     * The dry checks of a call of [tool] with [args] by [caller] (M4 §4.3): its arguments decoded and bounded
     * ([ToolArgs]), then the tool's own [NarratorTool.check] (registry ids, caps of the chaos level,
     * arguments that contradict each other). Null when they pass; else `INVALID` or `DENIED`, as a run would
     * answer. Nothing runs and nothing is recorded: a rule's effect or a challenge's stake is refused when it
     * is created, with the Director's [caller]. Never on the server thread.
     */
    suspend fun check(tool: String, args: JsonObject, caller: Caller): ToolResult? {
        val found = byName[tool] ?: return ToolResult.invalid("unknown tool \"$tool\" for the engine")
        return dryCheck(found, args, caller)
    }

    private suspend fun <A : Any> dryCheck(tool: NarratorTool<A>, args: JsonObject, caller: Caller): ToolResult? = try {
        when (val decoded = ToolArgs.decode(tool.args, args.toString())) {
            is ToolArgs.Decoded.Invalid -> ToolResult.invalid(decoded.problem)
            is ToolArgs.Decoded.Ok -> tool.check(ToolContext(services, caller, services.clock, tool.timeoutMs), decoded.args)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("the dry checks of ${tool.name} failed with an exception", e)
        ToolResult.failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    /**
     * One call of an act: [tool] ([found] when the engine has it) with [args], started at [at] ([nanos] for its
     * duration); [instead]: the engine's own way in place of the tool's run ([runInstead]).
     */
    private class Call(
        val firing: Firing,
        val tool: String,
        val found: NarratorTool<*>?,
        val args: JsonObject,
        val at: Instant,
        val nanos: Long,
        val instead: Boolean = false,
    )

    /**
     * The `mechanic action` decision of [call], `DEBUG` when it did what was asked, `INFO` otherwise. Stamped at
     * the start of the call, like a `tool_calls` row: an act that kills is before the death. The guards' death
     * report reads it (M4-04, `DeathReport.actionsOn`): `subject` the player the call affected (their names
     * joined when several), `detail.targets` their names, `detail.tool`, `detail.status` (a `ToolStatus` id),
     * `detail.result`, `detail.effect` (a `ToolEffect` id: speech is left out of the report), and the act's
     * conversation (`mechanics:C-60`).
     */
    private fun action(call: Call, result: ToolResult) {
        val ms = (System.nanoTime() - call.nanos) / 1_000_000
        val targets = result.targets.map { it.name }
        Decisions(services.trace, Clock.fixed(call.at, services.clock.zone)).record(
            log,
            DecisionRecord.Kind.MECHANIC,
            ACTION,
            subject = targets.joinToString().ifEmpty { null },
            reason = "${call.tool} ${result.status.id}: ${ModelText.cut(result.text.lineSequence().first(), FIRST_LINE_MAX)}",
            conversationId = call.firing.caller.conversationId,
            detail = buildJsonObject {
                put("firing", call.firing.id)
                call.firing.caller.act?.let { put("act", it) }
                call.firing.ref?.let { put("ref", it.toString()) }
                put("tool", call.tool)
                call.found?.let { put("effect", it.effect.id) }
                put("status", result.status.id)
                put("ms", ms)
                putJsonArray("targets") { targets.forEach { add(it) } }
                put("args", call.args)
                put("result", ToolRunner.truncate(result.text, ToolCallRecord.RESULT_MAX_CHARS))
                if (call.instead) put("instead", true)
            },
            level = if (result.status == ToolStatus.OK) Log.Level.DEBUG else Log.Level.INFO,
        )
    }

    companion object {
        /** The decision of each tool call of an act (M4 §19). */
        const val ACTION = "action"

        /** Longest first line of a result in an `action` decision's reason (M4 §4.3). */
        const val FIRST_LINE_MAX = 200

        /** Start of the conversation of every act: `mechanics:C-60`. */
        const val CONVERSATION_PREFIX = "mechanics:"

        /** The conversation of an act for [ref]: `mechanics:C-60`, or `mechanics:engine` for an act of no challenge nor rule. */
        fun conversationOf(ref: Ref?): String = CONVERSATION_PREFIX + (ref?.toString() ?: "engine")
    }
}
