package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.onServerThread
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.permissions.LevelBasedPermissionSet

/**
 * In-game tests of the operators' commands of the Forge (M5-14, M5 §14), through the server's real dispatcher, in the
 * batch of the Forge ([ForgeContractsGameTests.FORGE_ENVIRONMENT]):
 *
 * - `/narrator forge [status]` and the `Forge:` line of `/narrator status` on the server's Forge and engine.
 * - Until the assembly (M5-21) the server runs without the Forge (`DisabledForge`, `DisabledModules`): every other
 *   command is refused with the reason, the switch is neither set nor lifted, and no `operator` decision is written.
 *   Once assembled, the lists answer from the real Forge and engine; pulling the switch on the shared server is
 *   M5-21's test (`forge off` then `on`).
 * - Only a game master reaches the commands: below level 2, `/narrator forge off|on` and `/narrator module stop` do
 *   not exist.
 *
 * The switch itself (every module stopped at once, kept across a restart, lifted only by `forge on`) is tested on the
 * commands' functions in `ForgeCommandsTest`, on the modules' control's contract.
 */
class ForgeCommandGameTests {

    @GameTest(environment = ForgeContractsGameTests.FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theForgeCommandsAnswerOnTheTestServer(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val operator = server.onServerThread { TestPlayer(helper, "fc_") }

        /** Runs [command] with [permission] on the server thread; the lines it answers, at once or, for a change, once they come. */
        suspend fun perform(command: String, permission: LevelBasedPermissionSet = LevelBasedPermissionSet.GAMEMASTER): List<String> {
            val output = CommandOutput()
            server.onServerThread {
                val source = operator.player.createCommandSourceStack().withSource(output).withPermission(permission)
                server.commands.performPrefixedCommand(source, command)
            }
            awaitCondition("the answer to /$command", 5_000) { output.lines.isNotEmpty() }
            server.onServerThread {} // the lines of one answer are sent in one task of the server thread
            return output.lines.toList()
        }

        try {
            val forge = runtime.forge
            val status = perform("narrator forge")
            check(status.first() == "[Narrator] forge status" && status.size == 4) { "forge status: $status" }
            check(perform("narrator forge status").first() == "[Narrator] forge status") { "forge status, by its name" }
            val summary = perform("narrator status").singleOrNull { it.startsWith("Forge: ") }

            if (!forge.available) {
                val reason = forge.unavailableReason
                check(status[1] == "Forge: off ($reason) · ECJ unknown · arsenal 0 (0 forged, 0 pending, 0 broken) · 0 running · compiler off · leaks 0") {
                    "the Forge's line: ${status[1]}"
                }
                check(status[2].startsWith("Switch: not set. ")) { "the switch: ${status[2]}" }
                check(summary == "Forge: off ($reason) · 0 modules running") { "the Forge line of /narrator status: $summary" }

                val noArsenal = listOf("[Narrator] the Forge is unavailable ($reason): no arsenal to show or change.")
                for (command in listOf("narrator forge list", "narrator forge list 2", "narrator forge show tag", "narrator forge show tag 0", "narrator forge approve tag", "narrator forge reject tag 1")) {
                    val answer = perform(command)
                    check(answer == noArsenal) { "/$command: $answer" }
                }
                val noModule = listOf("[Narrator] the Forge is unavailable ($reason): no module runs.")
                for (command in listOf("narrator module", "narrator module list", "narrator module stop C-1 Le module a été arrêté.")) {
                    val answer = perform(command)
                    check(answer == noModule) { "/$command: $answer" }
                }
                val off = perform("narrator forge off essai du coupe-circuit")
                check(off == listOf("[Narrator] the Forge is unavailable ($reason): no module can run, nothing to switch off.")) { "forge off: $off" }
                check(runtime.mechanics.modules.halted == null) { "no switch set by a refusal" }
                val on = perform("narrator forge on")
                check(on == listOf("[Narrator] the Forge is unavailable ($reason): its switch is not set, nothing to lift.")) { "forge on: $on" }
            } else {
                check(status[1].startsWith("Forge: ") && " · ECJ " in status[1] && " · compiler " in status[1]) { "the Forge's line: ${status[1]}" }
                check(summary != null && Regex("^Forge: .* · \\d+ modules? running$").matches(summary)) { "the Forge line of /narrator status: $summary" }
                val list = perform("narrator forge list")
                check(list.first().startsWith("[Narrator] arsenal · ")) { "forge list: $list" }
                val modules = perform("narrator module list")
                check(modules.first().startsWith("[Narrator] modules · ")) { "module list: $modules" }
            }

            // Below a game master, the commands do not exist: neither the switch nor a stop reaches the Narrator.
            for (command in listOf("narrator forge off", "narrator forge on", "narrator module stop C-1", "narrator forge")) {
                val answer = perform(command, LevelBasedPermissionSet.MODERATOR)
                check(answer.none { it.startsWith("[Narrator]") }) { "/$command typed by a moderator: $answer" }
            }

            // No operator decision: every change was refused, or never reached the Narrator.
            runtime.journal.barrier()
            val decisions = runtime.journal.read(5_000) { connection ->
                connection.prepareStatement("SELECT decision FROM engine_decisions WHERE kind = 'operator' AND subject = ?").use { statement ->
                    statement.setString(1, operator.name)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            }
            check(decisions.isEmpty()) { "no operator decision: $decisions" }
        } finally {
            server.onServerThread { operator.close() }
        }
    }
}
