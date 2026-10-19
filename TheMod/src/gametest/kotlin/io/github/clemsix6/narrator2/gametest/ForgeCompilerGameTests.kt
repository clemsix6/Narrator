package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.compile.Compilation
import io.github.clemsix6.narrator2.forge.compile.EcjCompiler
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the Forge's compiler (task M5-02, M5 §16.3), in the batch of the Forge: in the real server, ECJ
 * compiles on its own thread and reads the SDK by name through Knot, the mod's class loader, which also knows the
 * game and the mod; a module still sees neither. The cold and warm times go to the log (M5 §5.1 measures).
 */
class ForgeCompilerGameTests {

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theCompilerReadsTheSdkThroughKnotOnItsOwnThreadAndLogsItsTimes(helper: GameTestHelper) {
        val knot = Forge::class.java.classLoader
        val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val reads: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val recording = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? {
                threads += Thread.currentThread().name
                reads += name
                return knot.getResourceAsStream(name)
            }
        }
        val compiler = EcjCompiler(recording)
        val serverThread = Thread.currentThread().name
        val outcome = AtomicReference<Pair<Compilation, List<Long>>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val cold = compiler.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE, ForgeSamples.NAME)
                val warm = (1..20).map { compiler.compile(ForgeSamples.CLASS, ForgeSamples.SOURCE, ForgeSamples.NAME).millis }.sorted()
                val small = (1..20).map { compiler.compile("Beacon", SMALL_MODULE, "beacon").also { check(it.ok) { it.problems } }.millis }.sorted()
                Log.forge.info(
                    "ECJ ${EcjCompiler.version} in the server: the witness (${ForgeSamples.SOURCE.lines().size} lines) " +
                        "cold ${cold.millis} ms, then min ${warm.first()} / median ${warm[warm.size / 2]} / max ${warm.last()} ms; " +
                        "a module of ${SMALL_MODULE.lines().size} lines min ${small.first()} / median ${small[small.size / 2]} / max ${small.last()} ms",
                )
                outcome.set(cold to warm)
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                compiler.stop()
            }
        }
        helper.awaitAsync {
            helper.assertTrue(failure.get() == null, "the compiler failed in the server: ${failure.get()}")
            helper.assertTrue(outcome.get() != null, "compiling the witness")
            val (cold, warm) = outcome.get()
            helper.assertTrue(cold.ok, "the witness compiles in the server: ${cold.problems}")
            helper.assertTrue(cold.classes.keys == ForgeSamples.classes.keys, "its classes: ${cold.classes.keys}")
            helper.assertTrue(warm.size == 20, "twenty warm compilations")
            helper.assertTrue(threads == setOf(EcjCompiler.THREAD_NAME) && serverThread !in threads, "read on the compiler's thread only: $threads")
            helper.assertTrue("io/github/clemsix6/narrator2/forge/api/ModuleContext.class" in reads, "the SDK read by name through Knot: $reads")
        }
    }

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun inTheServerAModuleStillSeesNeitherTheGameNorTheMod(helper: GameTestHelper) {
        val source = """
            public class Outlaw {
                net.minecraft.server.MinecraftServer server;
                net.minecraft.world.level.block.Blocks blocks;
                io.github.clemsix6.narrator2.NarratorRuntime runtime;
                io.github.clemsix6.narrator2.forge.rt.Meter meter;
                net.fabricmc.loader.api.FabricLoader fabric;
            }
        """.trimIndent()
        val compiler = EcjCompiler()
        val result = AtomicReference<Compilation>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                result.set(compiler.compile("Outlaw", source))
            } finally {
                compiler.stop()
            }
        }
        helper.awaitAsync {
            helper.assertTrue(result.get() != null, "compiling")
            val compilation = result.get()
            helper.assertTrue(compilation.outcome == Compilation.Outcome.ERRORS, "it does not compile: $compilation")
            helper.assertTrue(compilation.errors.map { it.line } == (2..6).toList(), "one error per field: ${compilation.errors}")
            helper.assertTrue(compilation.errors.all { "cannot be resolved" in it.message }, "${compilation.errors}")
        }
    }

    private companion object {
        /** A module of twenty lines, the size the spike of M5 measured (7 ms warm in its server). */
        val SMALL_MODULE = """
            import io.github.clemsix6.narrator2.forge.api.*;
            import java.util.*;

            @ModuleInfo(name = "beacon", summary = "Fireworks over a player, once a minute.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Over whom.")
            public final class Beacon implements ForgeModule {
                private final List<String> lit = new ArrayList<>();

                @Override
                public void start(ModuleContext ctx) {
                    String player = ctx.args().player("player");
                    ctx.timers().every(1200, () -> {
                        PlayerInfo info = ctx.world().player(player);
                        if (info == null) return;
                        ctx.acts().fireworks(info.at(), 3);
                        lit.add(player);
                        ctx.log("lit " + lit.size() + " times");
                    });
                }
            }
        """.trimIndent()
    }
}
