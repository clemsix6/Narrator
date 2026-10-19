package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the contracts of M5 (task M5-01, M5 §16.3), in the batch of the Forge ([FORGE_ENVIRONMENT]), where
 * every GameTest of M5 runs:
 *
 * - ECJ, nested in the mod's jar by Loom with the `fabric.mod.json` it generates (M5 §5.1), loads in the server, and
 *   compiles the witness module against `java.base` and the SDK, which the compiler reads by name through the mod's
 *   class loader (Knot here), off the server thread.
 * - The server runs with the Forge off until its assembly (M5-21): a `DisabledForge("not wired yet")`, no module, the
 *   modules refused, and the Director keeps his 44 tools.
 */
class ForgeContractsGameTests {

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun ecjLoadsInTheServerAndCompilesAModuleAgainstTheSdk(helper: GameTestHelper) {
        val loader = Forge::class.java.classLoader
        val compiler = Class.forName("org.eclipse.jdt.internal.compiler.Compiler", true, loader)
        helper.assertTrue(compiler.classLoader != null, "ECJ is loaded by a class loader of the game, not the platform's: ${compiler.classLoader}")
        helper.assertTrue(loader.getResource("io/github/clemsix6/narrator2/forge/api/Sdk.class") != null, "the SDK is read by name through the mod's loader")

        val compiled = AtomicReference<ForgeSamples.Compiled>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                compiled.set(ForgeSamples.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        helper.awaitAsync {
            helper.assertTrue(failure.get() == null, "ECJ failed in the server: ${failure.get()}")
            val result = compiled.get()
            helper.assertTrue(result != null, "compiling the witness")
            helper.assertTrue(result!!.ok, "the witness compiles in the server: ${result.problems}")
            helper.assertTrue(ForgeSamples.CLASS in result.classes, "its main class: ${result.classes.keys}")
        }
    }

    @GameTest(environment = FORGE_ENVIRONMENT)
    fun theServerRunsWithTheForgeOffAndTheDirectorKeepsHisFortyFourTools(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val forge = runtime.forge
        helper.assertTrue(forge is DisabledForge && !forge.available, "the Forge is off until its assembly: $forge")
        helper.assertEquals("not wired yet", forge.unavailableReason, "why")
        val modules = runtime.mechanics.modules
        helper.assertTrue(modules.list().isEmpty() && modules.halted == null && modules.leaks == 0, "no module runs")

        // The Director's tools, as a chapter opens them (Chapters.open, M5-12 adds forgeAvailable there).
        val env = ToolEnv(
            journalAvailable = runtime.journal.available,
            workerRoles = runtime.workers.roles,
            memoryAvailable = runtime.memory.available,
            mechanicsAvailable = runtime.mechanics.available,
            forgeAvailable = forge.available,
        )
        val director = ToolCatalog.toolSetFor(Role.DIRECTOR, env, runtime).names
        helper.assertTrue(director.size == 44, "the Director's 44 tools: ${director.size} $director")
        helper.assertTrue(ToolCatalog.toolSetFor(Role.FORGER, env, runtime).names.isEmpty(), "the forger has no tool")
        helper.succeed()
    }

    companion object {
        /** The batch of every GameTest of M5 (M5 §16): `test_environment/forge.json`. */
        const val FORGE_ENVIRONMENT = "narrator2-gametest:forge"
    }
}
