package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.journal.SchemaDoc
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestServices
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The prompts only name what the model really has (M1_M2 §18.2, T22, M3 §16). The lesson of the v1: tools
 * that did nothing were presented to the model as working. Every identifier quoted between backticks in
 * `director.md`, `analyst.md`, `scout.md`, `forger.md`, `chronicler.md` and `dreamer.md` must be a tool of that role's
 * set, with everything wired (the memory, the mechanics engine and the Forge included, M4 §12.1, M5 §9.4); and since
 * each prompt says it lists "all the tools", each role's set must be named in full by its prompt.
 */
class PromptToolMentionsTest {
    private val prompts = Prompts.bundled()
    private val services = TestServices()
    private val everythingWired = ToolEnv(
        journalAvailable = true, workerRoles = setOf(Role.ANALYST, Role.SCOUT), memoryAvailable = true, mechanicsAvailable = true,
        forgeAvailable = true,
    )

    private fun toolsOf(role: Role, env: ToolEnv = everythingWired): Set<String> = ToolCatalog.toolSetFor(role, env, services).names.toSet()

    /** The identifiers quoted between backticks in [prompt]. */
    private fun quoted(prompt: String): Set<String> = BACKTICKED.findAll(prompts.get(prompt)).map { it.groupValues[1] }.toSet()

    private fun assertNamesExactly(prompt: String, role: Role, env: ToolEnv = everythingWired) {
        val quoted = quoted(prompt)
        val tools = toolsOf(role, env)
        assertTrue(quoted.isNotEmpty(), "$prompt.md quotes no tool at all")
        assertEquals(emptySet(), quoted - tools, "$prompt.md quotes identifiers that are not tools of the ${role.id}: they do not exist or are not wired")
        assertEquals(emptySet(), tools - quoted, "tools of the ${role.id} that $prompt.md never names, although it says it lists them all")
    }

    @Test
    fun `the Director's prompt names its tools, all of them and nothing else`() {
        assertEquals(47, toolsOf(Role.DIRECTOR).size, "the Director's tools with the mechanics engine and the Forge (M5 §9.1): ${toolsOf(Role.DIRECTOR)}")
        assertNamesExactly("director", Role.DIRECTOR)
    }

    @Test
    fun `the Analyst's prompt names its tools, all of them and nothing else`() = assertNamesExactly("analyst", Role.ANALYST)

    @Test
    fun `the scout's prompt names its tools, all of them and nothing else`() {
        assertEquals(
            setOf("area_info", "inspect_player", "list_players", "locate", "recall", "scan_area", "sql", "survey"),
            toolsOf(Role.SCOUT),
            "the scout's tools (M4 §14)",
        )
        assertNamesExactly("scout", Role.SCOUT)
    }

    @Test
    fun `the forger's prompt names its tools, all of them and nothing else`() {
        assertEquals(setOf("forge_save", "forge_test", "game_ids", "module_list", "module_read"), toolsOf(Role.FORGER), "the forger's tools (M5 §8.1)")
        assertEquals(emptySet(), toolsOf(Role.FORGER, everythingWired.copy(forgeAvailable = false)), "without the Forge, the forger has no tool")
        assertNamesExactly("forger", Role.FORGER)
    }

    @Test
    fun `the Chronicler's prompt names its tools, all of them and nothing else`() = assertNamesExactly("chronicler", Role.CHRONICLER)

    @Test
    fun `the Dreamer's prompt names its tools, all of them and nothing else`() {
        assertEquals(15, toolsOf(Role.DREAMER).size, "the Dreamer's tools: ${toolsOf(Role.DREAMER)}")
        assertNamesExactly("dreamer", Role.DREAMER)
    }

    @Test
    fun `the chapter summary prompt names no tool, since no tool runs while summing up`() {
        val all = ToolCatalog.allTools(services).map { it.name }.toSet()
        assertEquals(emptySet(), quoted("chapter_summary") intersect all)
    }

    /** The views and macros the Analyst is told to prefer are in the schema that `sql` describes. */
    @Test
    fun `the views and macros of the Analyst's method exist in the Journal`() {
        val analyst = prompts.get("analyst")
        val lists = LISTS.findAll(analyst).map { it.groupValues[1] }.toList()
        if (lists.size != 2) fail("analyst.md no longer lists its views and macros in parentheses after « vues aux tables brutes » and « macros aux requêtes »: update this test")
        val names = lists.flatMap { list -> list.split(',').map { it.trim() } }
        assertTrue(names.size > 10, "names read: $names")
        val schema = SchemaDoc.text
        val missing = names.filterNot { name -> Regex("""\b$name\b""").containsMatchIn(schema) }
        assertEquals(emptyList(), missing, "views or macros named by analyst.md but absent from the schema of sql")
        assertTrue("columns_of(" in analyst && "columns_of" in schema, "the column macro named by analyst.md")
    }

    private companion object {
        val BACKTICKED = Regex("`([a-z][a-z0-9_]*)`")
        val LISTS = Regex("""(?:vues aux tables brutes|macros aux requêtes écrites à la main) \(([a-z_, ]+)\)""")
    }
}
