package io.github.clemsix6.narrator2.tool

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

/**
 * Every tool of the Narrator, and the tool set of each conversation (M1_M2 §8.6, §9). Each family
 * lives in its own file and belongs to its own task; this catalog only lists them.
 */
object ToolCatalog {
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
     * The tools a conversation of [role] sees: the role's tools (§9.7) that are available in [env],
     * with their definitions, in a stable order. Built once per conversation and never changed after.
     *
     * Provisional body (T02): no tool at all. T08 writes it (schemas, role sets, `tool_catalog`
     * decision), from [allTools].
     */
    @Suppress("UNUSED_PARAMETER")
    fun toolSetFor(role: Role, env: ToolEnv, services: NarratorServices): ToolSet = ToolSet.EMPTY
}
