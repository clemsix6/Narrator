package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
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
 * [tools]: what the engine may call, by name (M4-23: `ToolCatalog.allTools` and the rules' own three).
 * Which of them a rule or a stake may use is checked when it is created (M4 §8.5), not here.
 */
class EngineActions(private val services: NarratorServices, tools: List<NarratorTool<*>>) {
    private val log = Log.mechanics
    private val byName: Map<String, NarratorTool<*>> = tools.associateBy { it.name }
    private val runner = ToolRunner(services)

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
        val start = System.nanoTime()
        val found = byName[tool]
        val result = when {
            found == null -> ToolResult.invalid("unknown tool \"$tool\" for the engine")
            services.phase != Phase.RUNNING -> ToolResult.failed("server is stopping")
            else -> try {
                runner.invoke(found, firing.caller, args.toString())
            } catch (e: CancellationException) {
                action(firing, tool, found, args, ToolRunner.interrupted(found), start)
                throw e
            }
        }
        action(firing, tool, found, args, result, start)
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
     * The `mechanic action` decision of one call of [tool] ([found] when the engine has it), `DEBUG` when it
     * did what was asked, `INFO` otherwise. The guards' death report reads it (M4-04, `DeathReport.actionsOn`):
     * `subject` the player the call affected (their names joined when several), `detail.targets` their names,
     * `detail.tool`, `detail.status` (a `ToolStatus` id), `detail.result`, `detail.effect` (a `ToolEffect` id:
     * speech is left out of the report), and the act's conversation (`mechanics:C-60`).
     */
    private fun action(firing: Firing, tool: String, found: NarratorTool<*>?, args: JsonObject, result: ToolResult, start: Long) {
        val ms = (System.nanoTime() - start) / 1_000_000
        val targets = result.targets.map { it.name }
        Decisions(services.trace, services.clock).record(
            log,
            DecisionRecord.Kind.MECHANIC,
            ACTION,
            subject = targets.joinToString().ifEmpty { null },
            reason = "$tool ${result.status.id}: ${cut(result.text.lineSequence().first(), FIRST_LINE_MAX)}",
            conversationId = firing.caller.conversationId,
            detail = buildJsonObject {
                put("firing", firing.id)
                firing.caller.act?.let { put("act", it) }
                firing.ref?.let { put("ref", it.toString()) }
                put("tool", tool)
                found?.let { put("effect", it.effect.id) }
                put("status", result.status.id)
                put("ms", ms)
                putJsonArray("targets") { targets.forEach { add(it) } }
                put("args", args)
                put("result", ToolRunner.truncate(result.text, ToolCallRecord.RESULT_MAX_CHARS))
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

        /** [text] cut to [max] characters, ending with `…` when cut, never inside a character. */
        internal fun cut(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max - 1
            if (text[end - 1].isHighSurrogate()) end--
            return text.take(end) + "…"
        }
    }
}
