package io.github.clemsix6.narrator2.tool.forge

import com.anthropic.core.jsonMapper
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ForgeServiceTest
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.TestReport
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ForgeTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The forger's five tools (M5 §8.3, task M5-10): `ForgeTool`s, their texts, their answers against a fake Forge (the
 * arsenal's lines, a source, the registries' ids, a test, a save, every refusal), and, at the end, against the real
 * Forge: a test, a save, then the arsenal and the source as the forger reads them.
 */
class ForgerToolsTest {
    private val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening))
    private val forge = FakeForge(clock)
    private val mechanics = FakeMechanics(clock)
    private val forger = Caller(Role.FORGER, "forger-t31", turnId = null, taskId = 31)

    private fun services(forge: Forge = this.forge) = TestServices(clock = clock, forge = forge, mechanics = mechanics)

    private fun run(name: String, json: String, services: TestServices = services()): ToolResult = runBlocking {
        call(forgerTools(services).single { it.name == name }, json, services)
    }

    private suspend fun <A : Any> call(tool: NarratorTool<A>, json: String, services: TestServices): ToolResult {
        val decoded = ToolArgs.decode(tool.args, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "$json: $decoded")
        return tool.run(ToolContext(services, forger, clock, tool.timeoutMs), decoded.args)
    }

    // ── The tools ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the five tools are the forger's, shown only with the engine and the Forge, and say what they do`() {
        val tools = forgerTools(services()).associateBy { it.name }
        assertEquals(listOf("module_list", "module_read", "game_ids", "forge_test", "forge_save"), tools.keys.toList())
        assertEquals(listOf(ToolEffect.READ, ToolEffect.READ, ToolEffect.READ, ToolEffect.READ, ToolEffect.ENGINE), tools.values.map { it.effect })
        assertEquals(listOf(5_000L, 5_000L, 5_000L, 45_000L, 45_000L), tools.values.map { it.timeoutMs })
        val wired = ToolEnv(journalAvailable = true, workerRoles = emptySet(), mechanicsAvailable = true, forgeAvailable = true)
        for (tool in tools.values) {
            assertIs<ForgeTool<*>>(tool)
            assertTrue(tool.isAvailable(wired))
            assertFalse(tool.isAvailable(wired.copy(forgeAvailable = false)), "${tool.name} hidden without the Forge")
            assertFalse(tool.isAvailable(wired.copy(mechanicsAvailable = false)), "${tool.name} hidden without the engine")
        }
        assertEquals(
            listOf(
                "Lists the arsenal, one line per module: its latest version and status (ready, pending: an operator must approve " +
                    "it, rejected, broken: it no longer compiles), who saved it, its summary, parameters and scenarios, its last " +
                    "problem, and its other versions. Version 0 is built-in: read those for examples.",
                "Reads the Java source of a module of the arsenal, its latest version when version is left out: to start from a " +
                    "module close to what you want, or to fix one. A fix is saved as the next version of the same name.",
                "Lists the ids a module may write, as the SDK takes them (without minecraft:), sorted, 150 at most: creature types " +
                    "(entity), items, blocks, the particles the particles act knows, sounds, effects, attributes (the players' and the " +
                    "creatures'), biomes, structures, dimensions, punishments, launch directions. Narrow with contains.",
                "Tests a module without saving it. Compiles the whole file against the SDK, checks it against the sandbox, then runs " +
                    "it at the bench, a simulated world: a smoke run of one simulated minute with sample parameters and facts, the acts " +
                    "it asked checked dry by the real tools, then each method of its Scenarios. Answers ok with what ran, or where it " +
                    "stopped and each problem with its line and hints: fix them and test again. Nothing touches the world.",
                "Saves a module as the next version of its name in the arsenal, after the same tests as forge_test, which must all " +
                    "pass, with one scenario at least. The version is ready for the Director, or waits for an operator's approval when " +
                    "the server asks for one. A saved version never changes: a fix is the next version.",
            ),
            tools.values.map { it.description },
        )
        val mapper = jsonMapper()
        val sizes = tools.mapValues { (_, tool) -> mapper.writeValueAsString(ToolSchema.definition(tool)).length }
        println("[forger] the five definitions: ${sizes.values.sum()} characters $sizes")
        assertTrue(sizes.values.sum() <= DEFINITIONS_BUDGET, "the five definitions take ${sizes.values.sum()} characters: $sizes")
    }

    // ── module_list, module_read ───────────────────────────────────────────────────────────────────────────

    private fun stock() {
        val tag = ForgeSamples.manifest
        forge.arsenal.add(tag.copy(version = 0, builtIn = true, by = ArsenalStore.BUILT_IN_BY, note = null, savedAt = Instant.EPOCH), "// tag v0\n")
        forge.arsenal.add(tag.copy(problem = "exception in timer: IllegalStateException: no wolf at TagGame.java:42"), ForgeSamples.SOURCE)
        val statues = tag.copy(
            name = "statues", summary = "Red light, green light: whoever moves is sent back.", params = listOf(tag.params.first()),
            scenarios = listOf("movingIsCaught", "stillnessWins"),
        )
        forge.arsenal.add(statues.copy(version = 1, status = ModuleStatus.REJECTED))
        forge.arsenal.add(statues.copy(version = 2, status = ModuleStatus.PENDING, by = "forger T-32"))
        forge.arsenal.add(
            tag.copy(
                name = "beacon", summary = "Fireworks <over> a player.", by = "forger T-33", status = ModuleStatus.BROKEN, scenarios = listOf("itLightsUp"),
                params = listOf(ParamSpec("player", ParamType.PLAYER, "Over whom.", true, null, null, null, emptyList())),
                problem = "it no longer builds against the SDK of now: Beacon.java:10:20: The method shout(String) is undefined for the type Acts",
            ),
            "line 1\nline 2\n",
        )
    }

    @Test
    fun `module_list shows each module once, its latest version, its problem and its other versions`() {
        assertEquals("arsenal: empty", run("module_list", "{}").text)
        stock()

        assertEquals(
            """
            arsenal: 3 modules (1 built-in, 2 forged)
            beacon v1 broken (forger T-33) · Fireworks ‹over› a player. · params: player (player) · scenarios 1 · broken: it no longer builds against the SDK of now: Beacon.java:10:20: The method shout(String) is undefined for the type Acts
            statues v2 pending (forger T-32) · Red light, green light: whoever moves is sent back. · params: players (players 2-8) · scenarios 2 · last fault: none · also v1 rejected
            tag_game v1 ready (forger T-31) · Tag: the wolf passes on by touch; whoever was the wolf the least wins. · params: players (players 2-8), minutes (integer 2-15, default 5), mood (text: taquin|sévère, default taquin) · scenarios 2 · last fault: exception in timer: IllegalStateException: no wolf at TagGame.java:42 · also v0 ready
            """.trimIndent(),
            run("module_list", "{}").text,
        )
    }

    @Test
    fun `module_read gives a version's source as it was saved, the latest by default`() {
        stock()

        val latest = run("module_read", """{"name": "tag_game"}""")
        assertEquals(ToolStatus.OK, latest.status)
        assertEquals("tag_game v1 (forger T-31, ready), ${ForgeSamples.SOURCE.trimEnd().lines().size} lines:\n${ForgeSamples.SOURCE}", latest.text)
        assertEquals("tag_game v0 (built-in, ready), 1 line:\n// tag v0\n", run("module_read", """{"name": "tag_game", "version": 0}""").text)
        assertTrue(run("module_read", """{"name": "beacon"}""").text.startsWith("beacon v1 (forger T-33, broken: it no longer builds against the SDK of now: Beacon.java:10:20: "))
        assertEquals("Invalid arguments: no module \"nope\" in the arsenal; its modules: beacon, statues, tag_game", run("module_read", """{"name": "nope"}""").text)
        assertEquals("Invalid arguments: tag_game has no version 7; its versions: 0, 1", run("module_read", """{"name": "tag_game", "version": 7}""").text)
        assertEquals("FAILED: the source of statues v2 cannot be read", run("module_read", """{"name": "statues"}""").text)
    }

    @Test
    fun `without the Forge the arsenal's tools are refused`() {
        forge.available = false
        val refusal = "DENIED (forge_off): the Forge is unavailable: fake forge off"
        assertEquals(refusal, run("module_list", "{}").text)
        assertEquals(refusal, run("module_read", """{"name": "tag_game"}""").text)
        assertEquals(refusal, run("forge_test", """{"name": "beacon", "source": "x"}""").text)
        assertEquals(refusal, run("forge_save", """{"name": "beacon", "source": "x", "note": "A beacon."}""").text)
        assertTrue(forge.tested.isEmpty() && forge.saved.isEmpty())
    }

    // ── game_ids ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `game_ids lists the ids the SDK's acts are checked against, sorted, narrowed, at most 150`() {
        assertEquals("particle \"smoke\": campfire_signal_smoke, large_smoke, smoke", run("game_ids", """{"kind": "particle", "contains": "Smoke"}""").text)
        assertEquals(
            "punishment: butterfingers, cage, guests, heavy_feet, hunger, moon_gravity, skyward, slime_rain, slow_hands, smite, tiny, tree_top, web",
            run("game_ids", """{"kind": "punishment"}""").text,
        )
        assertEquals("launch_direction: backward, forward, left, random, right, up", run("game_ids", """{"kind": "launch_direction"}""").text)
        assertEquals(
            "attribute \"gravity\": players (Acts.attribute): gravity; creatures (Creature.attribute): gravity",
            run("game_ids", """{"kind": "attribute", "contains": "gravity"}""").text,
        )
        assertEquals("sound \"zzzz\": none", run("game_ids", """{"kind": "sound", "contains": "zzzz"}""").text)

        val pigs = run("game_ids", """{"kind": "entity", "contains": "minecraft:pig"}""").text.lines()
        assertTrue(pigs[0].startsWith("entity \"pig\": ") && pigs[0].substringAfter(": ").split(", ").all { "pig" in it }, pigs[0])
        assertTrue("piglin" in pigs[0].substringAfter(": ").split(", "), pigs[0])
        assertEquals(
            "spawn never summons: allay, blaze, copper_golem, elder_guardian, ender_dragon, enderman, ghast, giant, rabbit, ravager, silverfish, " +
                "snow_golem, villager, warden, wither",
            pigs[1],
        )
        val effects = run("game_ids", """{"kind": "effect", "contains": "speed"}""").text
        assertEquals("effect \"speed\": speed", effects)

        val items = run("game_ids", """{"kind": "item"}""").text
        val shown = items.substringAfter("item: ").substringBefore(" … and ").split(", ")
        assertEquals(150, shown.size, items.take(300))
        assertEquals(shown.sorted(), shown)
        assertTrue(Regex(""" … and \d+ more: narrow it with contains$""").containsMatchIn(items), items.takeLast(200))
    }

    // ── forge_test, forge_save ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `forge_test hands the Forge the module and answers its report, ok or Invalid arguments`() {
        val ok = run("forge_test", """{"name": " beacon ", "source": "  the source\n"}""")
        assertEquals(ToolStatus.OK, ok.status)
        assertEquals(forge.report.text, ok.text)
        assertEquals(listOf("beacon" to "  the source\n"), forge.tested.toList(), "the name trimmed, the source as written")

        forge.report = TestReport(false, "does not compile: 1 error (12 ms)\n[1] error Beacon.java:5:20: …", emptyList(), null)
        val failed = run("forge_test", """{"name": "beacon", "source": "x"}""")
        assertEquals(ToolStatus.INVALID, failed.status)
        assertEquals("Invalid arguments: does not compile: 1 error (12 ms)\n[1] error Beacon.java:5:20: …", failed.text)

        forge.rejection = ForgeRejected(null, "the compilation of beacon took too long (over 10 s): write a simpler source", failed = true)
        assertEquals("FAILED: the compilation of beacon took too long (over 10 s): write a simpler source", run("forge_test", """{"name": "beacon", "source": "x"}""").text)
    }

    @Test
    fun `forge_save saves as the forger's task and says whether the Director may deploy it`() {
        val saved = run("forge_save", """{"name": "beacon", "source": "src", "note": "A beacon\nof fireworks."}""")
        assertEquals(
            "ok: saved beacon v1 (ready): params players (players 2-8), minutes (integer 2-15, default 5), mood (text: taquin|sévère, default taquin); " +
                "the Director can deploy it",
            saved.text,
        )
        assertEquals(FakeForge.Saved("beacon", "src", "A beacon of fireworks.", "forger T-31"), forge.saved.single())

        forge.approval = false
        assertEquals(
            "ok: saved beacon v2 (pending: an operator must approve it): params players (players 2-8), minutes (integer 2-15, default 5), " +
                "mood (text: taquin|sévère, default taquin); the Director sees it once approved",
            run("forge_save", """{"name": "beacon", "source": "src", "note": "Again."}""").text,
        )

        assertEquals(
            "Invalid arguments: note is empty: say in one English line what the module is or what changed",
            run("forge_save", """{"name": "beacon", "source": "src", "note": "  "}""").text,
        )
        forge.rejection = ForgeRejected(ArsenalStore.ARSENAL_FULL, "beacon has 20 versions, the most a module may have: save it under a new name")
        assertEquals(
            "DENIED (arsenal_full): beacon has 20 versions, the most a module may have: save it under a new name",
            run("forge_save", """{"name": "beacon", "source": "src", "note": "Again."}""").text,
        )
        assertEquals(2, forge.saved.size)
    }

    @Test
    fun `an operator's switch refuses tests and saves, the reads go on`() {
        stock()
        mechanics.modules.halted = ForgeHalt("too many chickens", "Clement", clock.millis())

        val refusal = "DENIED (forge_off): the Forge was switched off by Clement: too many chickens"
        assertEquals(refusal, run("forge_test", """{"name": "beacon", "source": "x"}""").text)
        assertEquals(refusal, run("forge_save", """{"name": "beacon", "source": "x", "note": "A beacon."}""").text)
        assertTrue(forge.tested.isEmpty() && forge.saved.isEmpty())
        assertEquals(ToolStatus.OK, run("module_list", "{}").status)
        assertEquals(ToolStatus.OK, run("game_ids", """{"kind": "punishment"}""").status)
    }

    // ── With the real Forge ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the real Forge - a test, a save, then the arsenal and the source as the forger reads them`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("narrator2-forger")
        val inner = TestServices(clock = clock, dataDir = dir)
        val real = ForgeService(inner, ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), clock, builtInNames = emptyList()))
        try {
            val services = services(real)
            val source = ForgeServiceTest.BEACON
            fun json(code: String, extra: String = "") = "{\"name\": \"beacon\", \"source\": ${JsonPrimitive(code)}$extra}"

            val tested = call(forgerTools(services).single { it.name == "forge_test" }, json(source), services)
            assertEquals(ToolStatus.OK, tested.status, tested.text)
            assertTrue(tested.text.startsWith("ok: compiled (") && tested.text.endsWith("; scenarios 1/1"), tested.text)

            val broken = source.replace("ctx.acts().say(", "ctx.acts().sya(")
            val failed = call(forgerTools(services).single { it.name == "forge_test" }, json(broken), services)
            assertEquals(ToolStatus.INVALID, failed.status)
            assertTrue(failed.text.startsWith("Invalid arguments: does not compile: 1 error ("), failed.text)
            assertTrue("hint: Acts (ctx.acts()): Pending say(String text)" in failed.text, failed.text)

            val saved = call(forgerTools(services).single { it.name == "forge_save" }, json(source, ", \"note\": \"Fireworks over a player, once a minute.\""), services)
            assertEquals("ok: saved beacon v1 (ready): params player (player); the Director can deploy it", saved.text)

            assertEquals(
                "arsenal: 1 module (0 built-in, 1 forged)\n" +
                    "beacon v1 ready (forger T-31) · Fireworks over a player, once a minute. · params: player (player) · scenarios 1 · last fault: none",
                call(forgerTools(services).single { it.name == "module_list" }, "{}", services).text,
            )
            assertEquals(
                "beacon v1 (forger T-31, ready), ${source.trimEnd().lines().size} lines:\n$source",
                call(forgerTools(services).single { it.name == "module_read" }, """{"name": "beacon"}""", services).text,
            )
        } finally {
            real.stop()
        }
    }

    companion object {
        /** The five definitions as the SDK sends them, in the forger's cached prefix (M5 §8.4 counts 1,500 tokens). */
        private const val DEFINITIONS_BUDGET = 4_000

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
