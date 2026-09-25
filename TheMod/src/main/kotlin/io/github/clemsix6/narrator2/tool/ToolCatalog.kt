package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.command.commandTools
import io.github.clemsix6.narrator2.tool.oracle.oracleTools
import io.github.clemsix6.narrator2.tool.pacing.pacingTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import io.github.clemsix6.narrator2.tool.sql.sqlTools
import io.github.clemsix6.narrator2.tool.task.taskTools
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Every tool of the Narrator, and the tool set of each conversation (M1_M2 §8.6, §9). Each family
 * lives in its own file and belongs to its own task; this catalog only lists them.
 */
object ToolCatalog {
    /** The Analyst's tools (M1_M2 §9.7): reads only. */
    private val ANALYST_TOOLS = setOf("sql", "list_players", "inspect_player", "player_stats", "area_info", "scan_area", "locate")

    /** Every tool, family by family, built for the running server's [services]. */
    fun allTools(services: NarratorServices): List<NarratorTool<*>> = listOf(
        speechTools(services),
        oracleTools(services),
        playerActionTools(services),
        worldActionTools(services),
        commandTools(services),
        sqlTools(services),
        pacingTools(services),
        taskTools(services),
    ).flatten()

    /**
     * The tools a conversation of [role] sees: the role's tools (§9.7) that are wired in [env]
     * ([NarratorTool.isAvailable]), sorted by name, with their definitions. Built once per conversation
     * and never changed after: a component that fails later leaves its tool declared, answering
     * `FAILED`. Records a `tool_catalog` decision with the tools left out.
     */
    fun toolSetFor(role: Role, env: ToolEnv, services: NarratorServices): ToolSet =
        toolSetFor(role, env, allTools(services), Decisions(services.trace, services.clock))

    /** [toolSetFor] among [tools] instead of every tool. */
    internal fun toolSetFor(role: Role, env: ToolEnv, tools: List<NarratorTool<*>>, decisions: Decisions): ToolSet {
        val (wired, leftOut) = tools.filter { isFor(role, it) }.partition { it.isAvailable(env) }
        val set = ToolSet.of(wired)
        val leftOutNames = leftOut.map { it.name }.sorted()
        decisions.record(
            Log.tool,
            DecisionRecord.Kind.TOOL_CATALOG,
            decision = "built",
            subject = role.id,
            reason = "${set.tools.size} tools" + if (leftOutNames.isEmpty()) "" else "; not wired, left out: ${leftOutNames.joinToString()}",
            detail = buildJsonObject {
                putJsonArray("tools") { set.names.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("left_out") { leftOutNames.forEach { add(JsonPrimitive(it)) } }
                put("journal_available", env.journalAvailable)
                putJsonArray("worker_roles") { env.workerRoles.map { it.id }.sorted().forEach { add(JsonPrimitive(it)) } }
            },
        )
        return set
    }

    /** Whether [role] has [tool] (§9.7): the Director has every tool, the Analyst its reads, the other roles none yet. */
    private fun isFor(role: Role, tool: NarratorTool<*>): Boolean = when (role) {
        Role.DIRECTOR -> true
        Role.ANALYST -> tool.name in ANALYST_TOOLS
        else -> false
    }
}
