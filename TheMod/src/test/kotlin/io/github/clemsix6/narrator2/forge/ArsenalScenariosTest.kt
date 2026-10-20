package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.runBlocking
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in arsenal (M5 §6.1, §10; tasks M5-15 to M5-17) through the Forge's real pipeline (task M5-10): each module
 * of [ArsenalStore.BUILT_IN] that the mod's resources hold is built when the Forge starts (listed as version 0, ready,
 * built-in), has two scenarios at least, and passes `forge_test` — its smoke run, its acts checked dry by the real
 * tools at chaos 3, its scenarios — as the forger's modules do. Every source of the arsenal's folder is one of them.
 *
 * Until the built-in modules arrive, there is nothing to check: the test passes and says how many are there.
 */
class ArsenalScenariosTest {
    @Test
    fun `each built-in module is built at the start and passes its smoke run, its dry checks and its scenarios`() = runBlocking<Unit> {
        val present = ArsenalStore.BUILT_IN.filter { source(it) != null }
        val missing = ArsenalStore.BUILT_IN - present.toSet()
        println("[arsenal] ${present.size} of ${ArsenalStore.BUILT_IN.size} built-in modules are in the mod's resources" + if (missing.isEmpty()) "" else "; not yet: ${missing.joinToString()}")
        if (present.isEmpty()) return@runBlocking

        val forge = ForgeService(TestServices(dataDir = Files.createTempDirectory("narrator2-arsenal")))
        try {
            forge.start().join()
            val failures = mutableListOf<String>()
            for (name in present) {
                val entry = forge.arsenal.entry(name, 0)
                if (entry == null || !entry.builtIn || entry.status != ModuleStatus.READY) {
                    failures += "$name was not built when the Forge started (its ERROR is in the log): $entry"
                    continue
                }
                if (entry.scenarios.size < MIN_SCENARIOS) failures += "$name has ${entry.scenarios.size} scenarios: $MIN_SCENARIOS at least (M5 §10)"
                val report = forge.test(name, source(name)!!)
                if (report.ok) println("[arsenal] $name: ${report.text}") else failures += "$name:\n${report.text}"
                if (report.scenariosPassed != entry.scenarios) failures += "$name: scenarios passed ${report.scenariosPassed}, of ${entry.scenarios}"
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
        } finally {
            forge.stop()
        }
    }

    @Test
    fun `every source of the arsenal's folder is a built-in module`() {
        val folder = ArsenalStore::class.java.getResource(FOLDER)?.let { Path.of(it.toURI()) }
        val files = folder?.let { dir -> Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() } }.orEmpty()
        val unknown = files.filter { !it.endsWith(".java") || it.removeSuffix(".java") !in ArsenalStore.BUILT_IN }
        assertEquals(emptyList(), unknown, "a built-in module is <name>.java and its name is in ArsenalStore.BUILT_IN")
    }

    private fun source(name: String): String? =
        ArsenalStore::class.java.getResourceAsStream("$FOLDER/$name.java")?.use { String(it.readAllBytes(), Charsets.UTF_8) }

    companion object {
        private const val FOLDER = "/narrator2/forge/arsenal"

        /** Each built-in module has two scenarios at least: the normal case, and a refusal of the guards (M5 §10). */
        private const val MIN_SCENARIOS = 2

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
