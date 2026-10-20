package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the contracts of M5 (task M5-01, M5 §16.3), in the batch of the Forge ([FORGE_ENVIRONMENT]), where
 * the GameTests of M5 run: ECJ, nested in the mod's jar by Loom with the `fabric.mod.json` it generates (M5 §5.1), loads in
 * the server, and compiles the witness module against `java.base` and the SDK, which the compiler reads by name through the
 * mod's class loader (Knot here), off the server thread. The server's own Forge, assembled, is `ForgeAssemblyGameTests`'s.
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

    companion object {
        /** The batch of every GameTest of M5 (M5 §16): `test_environment/forge.json`. */
        const val FORGE_ENVIRONMENT = "narrator2-gametest:forge"
    }
}
