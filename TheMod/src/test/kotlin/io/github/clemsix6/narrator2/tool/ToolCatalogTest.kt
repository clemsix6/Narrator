package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.memory.chroniclerTools
import io.github.clemsix6.narrator2.tool.memory.dreamerTools
import io.github.clemsix6.narrator2.tool.memory.memoryTools
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The catalog of every tool, and the tool set of each conversation (M1_M2 §8.6, §9.7; M3 §5, §7.5, §8.3). */
class ToolCatalogTest {
    private val services = TestServices()
    private val decisions = Decisions(services.traces, services.clock)
    private val everythingWired = ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST), memoryAvailable = true)
    private val memoryOff = ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST), memoryAvailable = false)

    /** A few tools shaped like the real ones: `sql` needs the Journal, `delegate` a worker role, the memory tools the memory. */
    private val tools = listOf(
        noArgsTool("say", ToolEffect.SPEECH),
        noArgsTool("list_players"),
        noArgsTool("sql", available = { it.journalAvailable }),
        noArgsTool("delegate", ToolEffect.TASK, available = { it.workerRoles.isNotEmpty() }),
        noArgsTool("remind_me", ToolEffect.ENGINE),
        noArgsTool("remember", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("resolve", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("note_interaction", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("recall", available = { it.memoryAvailable }),
        noArgsTool("player_profile", available = { it.memoryAvailable }),
        noArgsTool("record_episode", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("note_to_director", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("set_trait", ToolEffect.ENGINE, available = { it.memoryAvailable }),
        noArgsTool("write_grimoire", ToolEffect.ENGINE, available = { it.memoryAvailable }),
    )

    /** The memory jobs' own families among [tools]. */
    private val jobs = ToolCatalog.JobTools(chronicler = setOf("record_episode", "note_to_director"), dreamer = setOf("set_trait", "write_grimoire"))

    private fun names(role: Role, env: ToolEnv = everythingWired): List<String> = ToolCatalog.toolSetFor(role, env, tools, decisions, jobs).names

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
    fun `the Director gets every wired tool but the memory jobs' own, sorted by name`() {
        val set = ToolCatalog.toolSetFor(Role.DIRECTOR, everythingWired, tools, decisions, jobs)

        assertEquals(
            listOf("delegate", "list_players", "note_interaction", "player_profile", "recall", "remember", "remind_me", "resolve", "say", "sql"),
            set.names,
        )
        assertEquals(set.names, set.definitions.map { it.asTool().name() })
    }

    @Test
    fun `the Analyst gets its reads, the memory ones included, the roles without tools nothing`() {
        assertEquals(listOf("list_players", "player_profile", "recall", "sql"), names(Role.ANALYST))
        assertEquals(emptyList(), names(Role.SCOUT))
    }

    @Test
    fun `the Chronicler and the Dreamer get their own family and the tools they share`() {
        assertEquals(listOf("note_interaction", "note_to_director", "recall", "record_episode", "remember", "resolve"), names(Role.CHRONICLER))
        assertEquals(listOf("note_to_director", "recall", "remember", "resolve", "set_trait", "write_grimoire"), names(Role.DREAMER))
    }

    @Test
    fun `the memory tools disappear from every role when the memory does not run`() {
        assertEquals(listOf("delegate", "list_players", "remind_me", "say", "sql"), names(Role.DIRECTOR, memoryOff))
        assertEquals(listOf("list_players", "sql"), names(Role.ANALYST, memoryOff))
        assertEquals(emptyList(), names(Role.CHRONICLER, memoryOff))
        assertEquals(emptyList(), names(Role.DREAMER, memoryOff))
    }

    @Test
    fun `the real memory tools go to their roles, never the jobs' own to the Director`() {
        val shared = memoryTools(services).map { it.name }
        val director = ToolCatalog.toolSetFor(Role.DIRECTOR, everythingWired, services).names
        assertTrue(director.containsAll(shared), "$director")
        val own = (chroniclerTools(services) + dreamerTools(services)).map { it.name }
        assertEquals(emptyList(), director.filter { it in own })
        assertEquals(
            listOf("area_info", "inspect_player", "list_players", "locate", "player_profile", "player_stats", "recall", "scan_area", "sql"),
            ToolCatalog.toolSetFor(Role.ANALYST, everythingWired, services).names,
        )
        assertEquals(emptyList(), ToolCatalog.toolSetFor(Role.DIRECTOR, memoryOff, services).names.filter { it in shared })
        assertTrue(ToolCatalog.toolSetFor(Role.CHRONICLER, everythingWired, services).names.containsAll(listOf("remember", "resolve", "note_interaction", "recall")))
        val dreamer = ToolCatalog.toolSetFor(Role.DREAMER, everythingWired, services).names
        assertTrue(dreamer.containsAll(listOf("remember", "resolve", "recall", "note_to_director")), "$dreamer")
        assertTrue("sql" !in dreamer && "player_profile" !in dreamer, "$dreamer")
    }

    @Test
    fun `a tool that is not wired is not shown to the model, and the decision says so`() {
        val env = ToolEnv(journalAvailable = false, workerRoles = emptySet())

        val set = LogCapture().use { logs ->
            ToolCatalog.toolSetFor(Role.DIRECTOR, env, tools, decisions, jobs).also {
                assertContains(logs.text, "[Tool] built director: 3 tools; not wired, left out: delegate, note_interaction, player_profile, recall, remember, resolve, sql")
            }
        }

        assertEquals(listOf("list_players", "remind_me", "say"), set.names)
        val decision = services.traces.decisions.single()
        assertEquals(DecisionRecord.Kind.TOOL_CATALOG, decision.kind)
        assertEquals("built", decision.decision)
        assertEquals("director", decision.subject)
        assertEquals(
            Json.parseToJsonElement(
                """{"tools":["list_players","remind_me","say"],"left_out":["delegate","note_interaction","player_profile","recall",""" +
                    """"remember","resolve","sql"],"journal_available":false,"memory_available":false,"mechanics_available":false,"worker_roles":[]}""",
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
        assertEquals("true", decision.detail?.get("memory_available").toString())
        assertEquals("false", decision.detail?.get("mechanics_available").toString())
    }

    @Test
    fun `a tool of M4 is shown only while the mechanics engine runs, and the decision says whether it does`() {
        val punish = object : MechanicsTool<NoArgs>("punish", "A tool of M4.", NoArgs.serializer(), ToolEffect.WORLD) {
            override suspend fun run(ctx: ToolContext, args: NoArgs) = ToolResult.ok("ok")
        }
        val withEngine = everythingWired.copy(mechanicsAvailable = true)
        val catalog = tools + punish

        assertTrue("punish" in ToolCatalog.toolSetFor(Role.DIRECTOR, withEngine, catalog, decisions, jobs).names)
        assertEquals("true", services.traces.decisions.last().detail?.get("mechanics_available").toString())
        val without = ToolCatalog.toolSetFor(Role.DIRECTOR, everythingWired, catalog, decisions, jobs)
        assertTrue("punish" !in without.names)
        assertEquals("""["punish"]""", services.traces.decisions.last().detail?.get("left_out").toString())
        assertEquals(false, ToolEnv(journalAvailable = true, workerRoles = emptySet()).mechanicsAvailable, "off unless said otherwise")
    }

    @Test
    fun `without the mechanics engine the Director keeps exactly its 37 tools of M3`() {
        val names = ToolCatalog.toolSetFor(Role.DIRECTOR, everythingWired, services).names
        assertEquals(37, names.size, "$names")
        listOf("start_challenge", "end_challenge", "propose_duel", "create_rule", "remove_rule", "punish", "favor", "survey").forEach {
            assertTrue(it !in names, "$it needs the engine, or is the scout's")
        }
    }
}
