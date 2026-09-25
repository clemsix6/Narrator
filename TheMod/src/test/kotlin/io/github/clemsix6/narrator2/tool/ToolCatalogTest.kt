package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The catalog of every tool, and the tool set of each conversation (M1_M2 §8.6, §9.7). */
class ToolCatalogTest {
    private val services = TestServices()
    private val decisions = Decisions(services.traces, services.clock)
    private val everythingWired = ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST))

    /** A few tools shaped like the real ones: `sql` needs the Journal, `delegate` a worker role. */
    private val tools = listOf(
        noArgsTool("say", ToolEffect.SPEECH),
        noArgsTool("list_players"),
        noArgsTool("sql", available = { it.journalAvailable }),
        noArgsTool("delegate", ToolEffect.TASK, available = { it.workerRoles.isNotEmpty() }),
        noArgsTool("remind_me", ToolEffect.ENGINE),
    )

    @Test
    fun `every tool of every family builds, with a description and a schema`() {
        val all = ToolCatalog.allTools(services)
        val set = ToolSet.of(all) // fails on a duplicate name or an argument type the schema cannot describe

        assertEquals(all.size, set.definitions.size)
        assertTrue(all.all { it.description.isNotBlank() })
        for (role in Role.entries) {
            val roleSet = ToolCatalog.toolSetFor(role, everythingWired, services)
            assertTrue(roleSet.names.all { name -> all.any { it.name == name } })
        }
    }

    @Test
    fun `the Director gets every wired tool, sorted by name`() {
        val set = ToolCatalog.toolSetFor(Role.DIRECTOR, everythingWired, tools, decisions)

        assertEquals(listOf("delegate", "list_players", "remind_me", "say", "sql"), set.names)
        assertEquals(set.names, set.definitions.map { it.asTool().name() })
    }

    @Test
    fun `the Analyst gets its reads only, the other roles nothing yet`() {
        assertEquals(listOf("list_players", "sql"), ToolCatalog.toolSetFor(Role.ANALYST, everythingWired, tools, decisions).names)
        assertEquals(emptyList(), ToolCatalog.toolSetFor(Role.SCOUT, everythingWired, tools, decisions).names)
    }

    @Test
    fun `a tool that is not wired is not shown to the model, and the decision says so`() {
        val env = ToolEnv(journalAvailable = false, workerRoles = emptySet())

        val set = LogCapture().use { logs ->
            ToolCatalog.toolSetFor(Role.DIRECTOR, env, tools, decisions).also {
                assertContains(logs.text, "[Tool] built director: 3 tools; not wired, left out: delegate, sql")
            }
        }

        assertEquals(listOf("list_players", "remind_me", "say"), set.names)
        val decision = services.traces.decisions.single()
        assertEquals(DecisionRecord.Kind.TOOL_CATALOG, decision.kind)
        assertEquals("built", decision.decision)
        assertEquals("director", decision.subject)
        assertEquals(
            Json.parseToJsonElement(
                """{"tools":["list_players","remind_me","say"],"left_out":["delegate","sql"],"journal_available":false,"worker_roles":[]}""",
            ) as JsonObject,
            decision.detail,
        )
    }

    @Test
    fun `building from the services records the decision in their trace`() {
        ToolCatalog.toolSetFor(Role.ANALYST, everythingWired, services)

        val decision = services.traces.decisions.single()
        assertEquals("analyst", decision.subject)
        assertEquals("""["analyst"]""", decision.detail?.get("worker_roles").toString())
    }
}
