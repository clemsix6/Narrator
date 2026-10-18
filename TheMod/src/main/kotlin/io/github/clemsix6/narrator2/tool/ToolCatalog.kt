package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.command.commandTools
import io.github.clemsix6.narrator2.tool.memory.chroniclerTools
import io.github.clemsix6.narrator2.tool.memory.dreamerTools
import io.github.clemsix6.narrator2.tool.memory.memoryTools
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
 * Every tool of the Narrator, and the tool set of each conversation (M1_M2 §8.6, §9; M3 §5, §7.5, §8.3).
 * Each family lives in its own file and belongs to its own task; this catalog only lists them and says
 * which role has which tool:
 *
 * - the Director has every tool but the memory jobs' own families (the Chronicler's, the Dreamer's);
 * - the Analyst has its reads ([ANALYST_TOOLS]);
 * - the Chronicler and the Dreamer have their own family, plus the tools they share with others
 *   ([CHRONICLER_SHARED], [DREAMER_SHARED]);
 * - the other roles have none yet.
 */
object ToolCatalog {
    /** The Analyst's tools (M1_M2 §9.7, M3 §5): reads only. */
    private val ANALYST_TOOLS = setOf(
        "sql", "list_players", "inspect_player", "player_stats", "area_info", "scan_area", "locate", "recall", "player_profile",
    )

    /** What the Chronicler has besides its own family (M3 §7.5). */
    private val CHRONICLER_SHARED = setOf("remember", "resolve", "note_interaction", "recall")

    /**
     * What the Dreamer has besides its own family (M3 §8.3); `note_to_director` is of the Chronicler's family.
     * Neither `sql` nor `player_profile` (M3 review, finding 7): each step's opening holds what it needs, the
     * sheet included, and `recall` finds the rest.
     */
    private val DREAMER_SHARED = setOf("remember", "resolve", "recall", "note_to_director")

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
        memoryTools(services),
        chroniclerTools(services),
        dreamerTools(services),
    ).flatten()

    /**
     * The tools a conversation of [role] sees: the role's tools that are wired in [env]
     * ([NarratorTool.isAvailable]), sorted by name, with their definitions. Built once per conversation
     * and never changed after: a component that fails later leaves its tool declared, answering
     * `FAILED`. Records a `tool_catalog` decision with the tools left out.
     */
    fun toolSetFor(role: Role, env: ToolEnv, services: NarratorServices): ToolSet {
        val jobs = JobTools(chroniclerTools(services).map { it.name }.toSet(), dreamerTools(services).map { it.name }.toSet())
        return toolSetFor(role, env, allTools(services), Decisions(services.trace, services.clock), jobs)
    }

    /** The names of the memory jobs' own tools (M3 §7.5, §8.3): the [chronicler]'s family, the [dreamer]'s. Never the Director's. */
    internal class JobTools(val chronicler: Set<String>, val dreamer: Set<String>)

    /** [toolSetFor] among [tools] instead of every tool; [jobs] names the memory jobs' own tools among them. */
    internal fun toolSetFor(
        role: Role,
        env: ToolEnv,
        tools: List<NarratorTool<*>>,
        decisions: Decisions,
        jobs: JobTools = JobTools(emptySet(), emptySet()),
    ): ToolSet {
        val (wired, leftOut) = tools.filter { isFor(role, it.name, jobs) }.partition { it.isAvailable(env) }
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
                put("memory_available", env.memoryAvailable)
                putJsonArray("worker_roles") { env.workerRoles.map { it.id }.sorted().forEach { add(JsonPrimitive(it)) } }
            },
        )
        return set
    }

    /** Whether [role] has the tool called [name] (M1_M2 §9.7; M3 §5, §7.5, §8.3). */
    private fun isFor(role: Role, name: String, jobs: JobTools): Boolean = when (role) {
        Role.DIRECTOR -> name !in jobs.chronicler && name !in jobs.dreamer
        Role.ANALYST -> name in ANALYST_TOOLS
        Role.CHRONICLER -> name in jobs.chronicler || name in CHRONICLER_SHARED
        Role.DREAMER -> name in jobs.dreamer || name in DREAMER_SHARED
        else -> false
    }
}
