package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.forge.forgerTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the Forge's service and the forger's tools (task M5-10, M5 §16.3), in the batch of the Forge: on the
 * test server, `forge_test` through the real tool checks a module's acts dry with the real tools — the server's
 * registries, its guards, its chaos level — off the server thread; `game_ids` reads the server's own registries; a save
 * fills the cache, and a restart builds the version again from its source. The times go to the log.
 *
 * Each test has a [ForgeService] of its own, on a folder of its own, seen by the tools through the server's services: the
 * server's own Forge and its arsenal are left alone (`ForgeAssemblyGameTests` tests them).
 */
class ForgeServiceGameTests {

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun forgeTestChecksTheActsDryWithTheRealToolsAndTheServersRegistries(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val dir = Files.createTempDirectory("narrator2-gametest-forge")
        val forge = ForgeService(runtime, ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), runtime.clock, builtInNames = emptyList()))
        val services = object : NarratorServices by runtime {
            override val forge: Forge = forge
        }
        val serverThread = Thread.currentThread()
        val answers = AtomicReference<Map<String, ToolResult>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                check(Thread.currentThread() !== serverThread)
                // A first run loads the bench's classes: the batch's other tests keep the server busy, and the bench
                // judges each call on the wall clock (20 ms).
                val warm = forge.test(NAME, RIGHT)
                val began = System.nanoTime()
                val wrong = tool(services, "forge_test", args(WRONG))
                val testedIn = (System.nanoTime() - began) / 1_000_000
                val right = tool(services, "forge_test", args(RIGHT))
                val saved = tool(services, "forge_save", args(RIGHT, ", \"note\": \"Fireworks over a player, once a minute.\""))
                val hit = forge.build(NAME, null)
                val hits = (1..100).map {
                    val start = System.nanoTime()
                    forge.build(NAME, null)
                    System.nanoTime() - start
                }.sorted()
                check(forge.build(NAME, 1).classes === hit.classes) { "a save fills the cache" }
                forge.stop()

                // A restart: the version is built again from its source, then taken from the cache.
                val again = ForgeService(runtime, ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), runtime.clock, builtInNames = emptyList()))
                val missStart = System.nanoTime()
                again.start().join()
                val miss = (System.nanoTime() - missStart) / 1_000_000
                val rebuilt = again.build(NAME, null)
                check(again.build(NAME, null).classes === rebuilt.classes) { "the restart's build is in the cache" }
                again.stop()
                Log.forge.info(
                    "M5-10 in the server: a first forge_test ${warm.text.substringBefore(", smoke")}; " +
                        "then forge_test of a module whose acts the tools refuse in $testedIn ms; " +
                        "${right.text.substringBefore(", smoke")}; build from the cache median ${hits[hits.size / 2] / 1_000} µs; " +
                        "a restart with one forged version ready in $miss ms (its build from the source included)",
                )
                answers.set(
                    mapOf(
                        "wrong" to wrong, "right" to right, "saved" to saved,
                        "dimensions" to tool(services, "game_ids", """{"kind": "dimension"}"""),
                        "biomes" to tool(services, "game_ids", """{"kind": "biome", "contains": "plains"}"""),
                        "structures" to tool(services, "game_ids", """{"kind": "structure", "contains": "village"}"""),
                    ),
                )
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                forge.stop()
            }
        }
        helper.awaitAsync {
            helper.assertTrue(failure.get() == null, "the Forge failed in the server: ${failure.get()}")
            helper.assertTrue(answers.get() != null, "testing the modules")
            val got = answers.get()
            val wrong = got.getValue("wrong")
            helper.assertTrue(wrong.status == ToolStatus.INVALID, "the tools refuse its acts: ${wrong.text}")
            helper.assertTrue("(2 refused by the dry checks)" in wrong.text, wrong.text)
            helper.assertTrue("refused act \"sound everyone: entity.chicken.nope\": Invalid arguments: unknown sound" in wrong.text, wrong.text)
            helper.assertTrue(Regex("""refused act "spawn not_a_creature[^"]*": Invalid arguments: """).containsMatchIn(wrong.text), wrong.text)
            val right = got.getValue("right")
            helper.assertTrue(right.status == ToolStatus.OK && right.text.startsWith("ok: compiled (") && "(checked)" in right.text, right.text)
            helper.assertEquals("ok: saved beacon v1 (ready): params player (player); the Director can deploy it", got.getValue("saved").text, "the save")
            helper.assertEquals("dimension: overworld, the_end, the_nether", got.getValue("dimensions").text, "the server's dimensions")
            helper.assertTrue(got.getValue("biomes").text.startsWith("biome \"plains\": ") && "plains" in got.getValue("biomes").text.split(": ", ", "), got.getValue("biomes").text)
            helper.assertTrue("village_plains" in got.getValue("structures").text, got.getValue("structures").text)
        }
    }

    private suspend fun tool(services: NarratorServices, name: String, json: String): ToolResult {
        val tool = forgerTools(services).single { it.name == name }
        return call(tool, services, json)
    }

    private suspend fun <A : Any> call(tool: NarratorTool<A>, services: NarratorServices, json: String): ToolResult {
        val decoded = ToolArgs.decode(tool.args, json)
        check(decoded is ToolArgs.Decoded.Ok<A>) { "$json: $decoded" }
        return tool.run(ToolContext(services, FORGER, services.clock, tool.timeoutMs), decoded.args)
    }

    private fun args(source: String, extra: String = "") = "{\"name\": \"$NAME\", \"source\": ${JsonPrimitive(source)}$extra}"

    private companion object {
        const val NAME = "beacon"
        val FORGER = Caller(Role.FORGER, "forger-gametest", turnId = null, taskId = 31)

        /** A module that passes: it speaks, then fireworks over its player every 10 s; one scenario. */
        val RIGHT = """
            import io.github.clemsix6.narrator2.forge.api.*;
            import io.github.clemsix6.narrator2.forge.api.test.Bench;

            @ModuleInfo(name = "beacon", summary = "Fireworks over a player, once a minute.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Over whom.")
            public final class Beacon implements ForgeModule {
                @Override
                public void start(ModuleContext ctx) {
                    String player = ctx.args().player("player");
                    ctx.acts().say("Le Narrateur allume une balise.");
                    ctx.acts().sound(null, "block.bell.use");
                    ctx.timers().every(200, () -> {
                        PlayerInfo info = ctx.world().player(player);
                        if (info != null) ctx.acts().fireworks(info.at(), 1);
                    });
                }

                public static final class Scenarios {
                    public void itLightsUp(Bench b) {
                        b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
                        b.start("player=Bartok");
                        b.run(220);
                        b.check(b.acts().stream().anyMatch(a -> a.startsWith("fireworks")), "fireworks over Bartok");
                    }
                }
            }
        """.trimIndent() + "\n"

        /** The same, with a sound the game does not know and a spawn of something that is not a creature: only the real tools can tell. */
        val WRONG = RIGHT.replace(
            "ctx.acts().sound(null, \"block.bell.use\");",
            "ctx.acts().sound(null, \"entity.chicken.nope\");\n        ctx.acts().spawn(\"not_a_creature\", player, Spawn.of(1));",
        )
    }
}
