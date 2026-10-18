package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.DuckPlayClock
import io.github.clemsix6.narrator2.memory.MemoryService
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * The memory as the test server starts it (M3-18): the real one, not `DisabledMemory`. Its schema is
 * migrated, its play clock loaded and saved, its jobs run, the Director's tool set has its five tools, and
 * `/narrator memory status` answers.
 *
 * Owned by M3-18. Shared helpers: `GameTestSupport.kt`, and `runScript` (`DirectorEndToEndTests.kt`).
 */
class MemoryGameTests {

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theRealMemoryRunsOnTheTestServer(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val memory = runtime.memory
        check(memory is MemoryService && memory.available) { "the memory of the test server: ${memory.unavailableReason ?: memory.javaClass.simpleName}" }

        val version = runCatchingJournal(runtime, "SELECT max(version) FROM schema_version")?.single() as Long?
        check(version != null && version >= 2) { "the memory's migration: schema version $version" }
        val clock = runCatchingJournal(runtime, "SELECT value FROM memory_state WHERE key = ?", DuckPlayClock.WORLD_KEY)
        check(clock != null) { "the world play clock is saved at its first load" }
        val jobs = memory.jobs.status()
        check(!jobs.paused && jobs.blockedUntil == null) { "the memory jobs run: $jobs" }

        // The tool set of a chapter, as the Director's `Chapters` builds it.
        val env = ToolEnv(journalAvailable = runtime.journal.available, workerRoles = runtime.workers.roles, memoryAvailable = runtime.memory.available)
        val tools = ToolCatalog.toolSetFor(Role.DIRECTOR, env, runtime).names
        check(tools.containsAll(MEMORY_TOOLS)) { "the Director's tools: $tools" }
        check("record_episode" !in tools && "write_grimoire" !in tools) { "no tool of the Chronicler nor of the Dreamer for the Director: $tools" }

        val output = operatorCommands(helper, "narrator memory status")
        awaitCondition("the answer of /narrator memory status") { output.lines.any { it.startsWith("Index: ") } }
        val lines = output.lines.toList()
        check(lines.first() == "[Narrator] memory status") { "memory status: $lines" }
        check(lines.any { it.startsWith("Memory: available · jobs running · world clock ") }) { "memory status: $lines" }
    }
}
