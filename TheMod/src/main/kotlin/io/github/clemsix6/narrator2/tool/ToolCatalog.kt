package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.action.favorTools
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.command.commandTools
import io.github.clemsix6.narrator2.tool.forge.forgerTools
import io.github.clemsix6.narrator2.tool.mechanics.challengeTools
import io.github.clemsix6.narrator2.tool.mechanics.duelTools
import io.github.clemsix6.narrator2.tool.mechanics.moduleTools
import io.github.clemsix6.narrator2.tool.mechanics.ruleTools
import io.github.clemsix6.narrator2.tool.memory.chroniclerTools
import io.github.clemsix6.narrator2.tool.memory.dreamerTools
import io.github.clemsix6.narrator2.tool.memory.memoryTools
import io.github.clemsix6.narrator2.tool.oracle.oracleTools
import io.github.clemsix6.narrator2.tool.oracle.surveyTools
import io.github.clemsix6.narrator2.tool.pacing.pacingTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import io.github.clemsix6.narrator2.tool.sql.sqlTools
import io.github.clemsix6.narrator2.tool.storyteller.inspireTools
import io.github.clemsix6.narrator2.tool.storyteller.storytellerTools
import io.github.clemsix6.narrator2.tool.task.taskTools
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Every tool of the Narrator, and the tool set of each conversation (M1_M2 §8.6, §9; M3 §5, §7.5, §8.3; M6 §6.5).
 * Each family lives in its own file and belongs to its own task; this catalog only lists them and says
 * which role has which tool:
 *
 * - the Director has every tool but the memory jobs' own families (the Chronicler's, the Dreamer's), the
 *   scout's own ([SCOUT_OWN]), the forger's ([FORGER_TOOLS]) and the storyteller's own ([STORYTELLER_OWN]);
 * - the Analyst has its reads ([ANALYST_TOOLS]), the scout its own ([SCOUT_TOOLS]), the forger its own
 *   ([FORGER_TOOLS]);
 * - the Chronicler, the Dreamer and the storyteller have their own family, plus the tools they share with others
 *   ([CHRONICLER_SHARED], [DREAMER_SHARED], [STORYTELLER_SHARED]);
 * - the rules' own tools ([RULE_ONLY]) and the modules' ([MODULE_ONLY]) are nobody's: only the mechanics engine
 *   runs them.
 */
object ToolCatalog {
    /** The Analyst's tools (M1_M2 §9.7, M3 §5): reads only. */
    private val ANALYST_TOOLS = setOf(
        "sql", "list_players", "inspect_player", "player_stats", "area_info", "scan_area", "locate", "recall", "player_profile",
    )

    /**
     * The scout's tools (M4 §14): where the players are and what is around them, the places they haunt (`sql`),
     * the places the Narrator named (`recall`), and its own [SCOUT_OWN]. Reads only.
     */
    private val SCOUT_TOOLS = setOf("list_players", "inspect_player", "scan_area", "area_info", "locate", "sql", "recall", "survey")

    /** The scout's own tools, never the Director's: a survey takes several readings, which he delegates. */
    private val SCOUT_OWN = setOf("survey")

    /**
     * The tools of the rules alone (M4 §8.4): `mob_effect`, `mob_attribute` and `notify` exist only as the
     * effects of a rule, run by the engine (`EngineActions`); no conversation ever sees them.
     */
    private val RULE_ONLY = setOf("mob_effect", "mob_attribute", "notify")

    /**
     * The tools of the modules alone (M5 §4.4): `hud`, `place_blocks`, `creature_attack` exist only as the acts of a
     * module, run by the engine (`mechanics/module/ModuleOnlyTools.kt`); no conversation ever sees them.
     */
    private val MODULE_ONLY = setOf("hud", "place_blocks", "creature_attack")

    /** The forger's tools (M5 §8.1, §8.3), its alone: the arsenal, the game's ids, the bench and the save. */
    private val FORGER_TOOLS = setOf("module_list", "module_read", "game_ids", "forge_test", "forge_save")

    /** What the Chronicler has besides its own family (M3 §7.5). */
    private val CHRONICLER_SHARED = setOf("remember", "resolve", "note_interaction", "recall")

    /**
     * What the Dreamer has besides its own family (M3 §8.3); `note_to_director` is of the Chronicler's family.
     * Neither `sql` nor `player_profile` (M3 review, finding 7): each step's opening holds what it needs, the
     * sheet included, and `recall` finds the rest.
     */
    private val DREAMER_SHARED = setOf("remember", "resolve", "recall", "note_to_director")

    /** The storyteller's own tools (M6 §6.5), never the Director's: the ideas of the programme. */
    private val STORYTELLER_OWN = setOf("propose_idea", "drop_idea")

    /**
     * What the storyteller has besides its own (M6 §6.5): `inspire`, which it shares with the Director, the memory's
     * reads and the writes of planned arcs and lore (the store holds the rest), and `sql`. Nothing that acts on the
     * world, speaks to players or delegates.
     */
    private val STORYTELLER_SHARED = setOf("inspire", "remember", "resolve", "recall", "player_profile", "sql")

    /**
     * Every tool, family by family, built for the running server's [services]. The families of M4 (M4 §12.1), of
     * M5 (M5 §8.3, §9.1) and of M6 (M6 §12.6) are listed once and for all here; each is filled by its own task. Each
     * Director's tool of M4 is shown only while the mechanics engine runs ([ToolEnv.mechanicsAvailable]), each tool of
     * M5 only while the Forge runs too ([ToolEnv.forgeAvailable]), `inspire` only while the storyteller can work
     * ([ToolEnv.storytellerAvailable]).
     */
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
        punishTools(services),
        favorTools(services),
        challengeTools(services),
        duelTools(services),
        ruleTools(services),
        surveyTools(services),
        moduleTools(services),
        forgerTools(services),
        inspireTools(services),
        storytellerTools(services),
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
                put("mechanics_available", env.mechanicsAvailable)
                put("forge_available", env.forgeAvailable)
                put("storyteller_available", env.storytellerAvailable)
                putJsonArray("worker_roles") { env.workerRoles.map { it.id }.sorted().forEach { add(JsonPrimitive(it)) } }
            },
        )
        return set
    }

    /** Whether [role] has the tool called [name] (M1_M2 §9.7; M3 §5, §7.5, §8.3; M4 §8.4, §14; M5 §8.1, §9.1; M6 §6.5). */
    private fun isFor(role: Role, name: String, jobs: JobTools): Boolean = name !in RULE_ONLY && name !in MODULE_ONLY && when (role) {
        Role.DIRECTOR -> name !in jobs.chronicler && name !in jobs.dreamer && name !in SCOUT_OWN && name !in FORGER_TOOLS &&
            name !in STORYTELLER_OWN
        Role.ANALYST -> name in ANALYST_TOOLS
        Role.SCOUT -> name in SCOUT_TOOLS
        Role.CHRONICLER -> name in jobs.chronicler || name in CHRONICLER_SHARED
        Role.DREAMER -> name in jobs.dreamer || name in DREAMER_SHARED
        Role.FORGER -> name in FORGER_TOOLS
        Role.STORYTELLER -> name in STORYTELLER_OWN || name in STORYTELLER_SHARED
    }
}
