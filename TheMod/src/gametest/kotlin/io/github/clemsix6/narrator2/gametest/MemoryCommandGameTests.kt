package io.github.clemsix6.narrator2.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.permissions.LevelBasedPermissionSet

/**
 * In-game tests of `/narrator sheet` and `/narrator memory …` (M3 §14), through the server's real command
 * dispatcher, run by a game master. Until M3-18 wires the memory, the test server runs with
 * `DisabledMemory`: every memory command is then refused at once, with the reason. Once a memory runs,
 * `sheet` and `memory status` answer off the server thread, and the test waits for them.
 *
 * Owned by M3-17. Shared helpers: `GameTestSupport.kt`.
 */
class MemoryCommandGameTests {

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun sheetAndMemoryStatusAnswerOnTheTestServer(helper: GameTestHelper) {
        val memory = helper.narrator().memory
        helper.closingOnFailure(TestPlayer(helper, "mc_"), ASYNC_MAX_TICKS) { operator ->
            val output = CommandOutput()
            val source = operator.player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
            fun perform(command: String) = helper.level.server.commands.performPrefixedCommand(source, command.replace("<player>", operator.name))
            val lines = output.lines

            perform("narrator status")
            val status = lines.firstOrNull { it.startsWith("Memory: ") }
            if (!memory.available) {
                helper.assertEquals("Memory: unavailable (${memory.unavailableReason})", status, "the Memory line of /narrator status")
                lines.clear()
                COMMANDS.forEach(::perform)
                val refusal = "[Narrator] the memory is unavailable (${memory.unavailableReason}): nothing to read nor change."
                helper.assertEquals(COMMANDS.map { refusal }, lines.toList(), "every memory command, refused at once")
                operator.close()
                helper.succeed()
                return@closingOnFailure
            }
            helper.assertTrue(status?.startsWith("Memory: available · ") == true, "the Memory line of /narrator status: $status")
            lines.clear()
            perform("narrator sheet <player>")
            perform("narrator memory status")
            helper.awaitAsync {
                helper.assertTrue(lines.any { it.startsWith("[Narrator] sheet of ${operator.name} · full") }, "sheet: $lines")
                helper.assertTrue(lines.any { it == "[Narrator] memory status" } && lines.any { it.startsWith("Memory: available · ") }, "memory status: $lines")
                operator.close()
            }
        }
    }

    private companion object {
        /** Every memory command, as an operator types it; `<player>` is the operator's name. */
        val COMMANDS = listOf(
            "narrator sheet <player>",
            "narrator sheet <player> stats 2",
            "narrator memory",
            "narrator memory status",
            "narrator memory canon all <player>",
            "narrator memory recall lave du Nether",
            "narrator memory show C-1",
            "narrator memory forget C-1 souvenir inventé",
            "narrator memory restore C-1",
            "narrator memory note Bartok préfère qu'on l'appelle le Plongeur.",
            "narrator memory docs sheet:<player>:recap",
            "narrator memory rollback grimoire 1",
            "narrator memory revert 1",
            "narrator memory chronicle",
            "narrator memory dream",
            "narrator memory pause",
            "narrator memory resume",
        )
    }
}
