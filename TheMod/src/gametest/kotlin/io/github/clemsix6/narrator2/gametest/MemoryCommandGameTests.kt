package io.github.clemsix6.narrator2.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.permissions.LevelBasedPermissionSet

/**
 * In-game tests of `/narrator sheet` and `/narrator memory …` (M3 §14), through the server's real command
 * dispatcher, run by a game master, on the real memory of the test server (M3-18): `sheet` and `memory
 * status` answer off the server thread, and the test waits for them. The refusal of every command without
 * a memory is tested in `MemoryCommandsTest`.
 *
 * Owned by M3-17. Shared helpers: `GameTestSupport.kt`.
 */
class MemoryCommandGameTests {

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun sheetAndMemoryStatusAnswerOnTheTestServer(helper: GameTestHelper) {
        val memory = helper.narrator().memory
        helper.assertTrue(memory.available, "the memory of the test server: ${memory.unavailableReason}")
        helper.closingOnFailure(TestPlayer(helper, "mc_"), ASYNC_MAX_TICKS) { operator ->
            val output = CommandOutput()
            val source = operator.player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
            fun perform(command: String) = helper.level.server.commands.performPrefixedCommand(source, command)
            val lines = output.lines

            perform("narrator status")
            val status = lines.firstOrNull { it.startsWith("Memory: ") }
            helper.assertTrue(status?.startsWith("Memory: available · jobs running · ") == true, "the Memory line of /narrator status: $status")
            lines.clear()
            perform("narrator sheet ${operator.name}")
            perform("narrator memory status")
            helper.awaitAsync {
                helper.assertTrue(lines.any { it.startsWith("[Narrator] sheet of ${operator.name} · full") }, "sheet: $lines")
                helper.assertTrue(lines.any { it.startsWith("Identity: uuid ${operator.player.uuid} · first seen ") }, "the sheet read in the Journal: $lines")
                helper.assertTrue(lines.any { it == "[Narrator] memory status" }, "memory status: $lines")
                helper.assertTrue(lines.any { it.startsWith("Memory: available · jobs running · world clock ") }, "the jobs and the play clock: $lines")
                helper.assertTrue(lines.any { it.startsWith("Memories: ") && "interactions" in it }, "the tables counted in the Journal: $lines")
                operator.close()
            }
        }
    }
}
