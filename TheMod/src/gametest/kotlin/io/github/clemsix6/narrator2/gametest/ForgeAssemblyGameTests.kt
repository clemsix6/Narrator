package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.module.ModuleBook
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.DeployModuleTool
import io.github.clemsix6.narrator2.tool.mechanics.forgeAvailable
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Mob

/**
 * In-game test of the assembly of the Forge (task M5-21, M5 §16.3): the server's own Forge and modules' part, as
 * `createForge` and `createMechanics` built them, reached the way the game reaches them — the Director's real
 * `deploy_module`, the operators' commands through the server's dispatcher, the server's ticks: no Forge nor engine of
 * the test's own.
 *
 * - The Forge runs: the built-in modules were compiled as the server started, the Director has his 47 tools (the
 *   Forge's three with them), the forger his five, `/narrator forge` says so.
 * - `deploy_module` deploys `parade` on a test player: its canon row (`mechanic = module`), its chickens summoned by the
 *   real `spawn`, marked, marching; `/narrator module list` shows it.
 * - `/narrator forge off` stops it at once (canon cancelled, its chickens gone), `deploy_module` is refused, the
 *   Director and the forger lose the Forge's tools; `/narrator forge on` gives them back.
 *
 * It pulls the server's switch: it runs alone in a batch of its own ([FORGE_ASSEMBLY_ENVIRONMENT]), never beside the
 * Forge's batch, whose forger tools read that switch; and it lifts the switch and stops what it deployed in its `finally`.
 */
class ForgeAssemblyGameTests {

    @GameTest(environment = FORGE_ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theServersForgeDeploysAModuleOfItsArsenalUntilAnOperatorSwitchesItOff(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val forge = runtime.forge
        check(forge is ForgeService && forge.available) { "the server's Forge runs: ${forge.unavailableReason}" }
        forge.start().join() // its start, as the server started: the arsenal read, the built-in modules built
        val builtIn = forge.arsenal.list().filter { it.builtIn }
        check(builtIn.map { it.name }.toSet() == ArsenalStore.BUILT_IN.toSet() && builtIn.all { it.version == 0 && it.status == ModuleStatus.READY }) {
            "the built-in modules, built as the server started: ${builtIn.map { "${it.name} v${it.version} ${it.status.id}" }}"
        }
        val modules = runtime.mechanics.modules
        check(modules is ModuleBook && modules.halted == null && modules.list().isEmpty()) { "the engine's modules: $modules ${modules.list()}" }
        check(tools(runtime, Role.DIRECTOR).let { it.size == 47 && it.containsAll(FORGE_TOOLS) }) { "the Director's 47 tools: ${tools(runtime, Role.DIRECTOR)}" }
        check(tools(runtime, Role.FORGER).size == 5) { "the forger's five tools: ${tools(runtime, Role.FORGER)}" }
        val status = answer(helper, "narrator forge")
        check(status.getOrNull(1) == "Forge: on · ECJ ${forge.status().ecj} · arsenal ${forge.arsenal.list().size} (0 forged, 0 pending, 0 broken) · 0 running · compiler idle · leaks 0") {
            "forge status: $status"
        }

        val server = helper.level.server
        val bob = server.onServerThread {
            floor(helper)
            TestPlayer(helper, "fa_").also { it.standAt(BlockPos(3, 1, 3)) }
        }
        var deployed: Ref? = null
        try {
            val result = deploy(runtime, bob.name)
            check(result.status == ToolStatus.OK) { "deploy_module: ${result.text}" }
            val view = modules.list().single()
            deployed = view.ref
            check(view.module == PARADE && view.version == 0 && result.text.startsWith("ok: module parade v0 deployed as ${view.ref} (plan) until ")) {
                "deployed: ${result.text}; $view"
            }
            val row = runtime.memory.store.canon(view.ref)
            check(row?.mechanic == CanonMechanic.MODULE && row.status == CanonStatus.ACTIVE) { "its canon row: $row" }
            awaitCondition("chickens of ${view.ref} marching behind Bob", 20_000) { server.onServerThread { chickens(helper, view.ref).isNotEmpty() } }
            val listed = answer(helper, "narrator module list")
            check(listed.any { "${view.ref} parade plan " in it && " · v0 · " in it }) { "module list: $listed" }

            // The switch: the module stops at once, its chickens go, nothing deploys, the tools of the Forge are gone.
            val off = answer(helper, "narrator forge off essai de l'assemblage")
            check(off.firstOrNull() == "[Narrator] the Forge is switched off by Server: 1 module stopped (${view.ref} parade).") { "forge off: $off" }
            check(modules.list().isEmpty() && modules.halted?.by == "Server") { "nothing runs, the switch is set: ${modules.list()} ${modules.halted}" }
            awaitCondition("${view.ref} closed") {
                runtime.memory.store.canon(view.ref)?.status == CanonStatus.CANCELLED && ended(runtime, view.ref) == "forge_off"
            }
            check(runtime.memory.store.canon(view.ref)?.resolution == ModuleEngine.FORGE_OFF_NOTE) { "its canon note" }
            awaitCondition("its chickens gone") { server.onServerThread { chickens(helper, view.ref).isEmpty() } }
            val refused = deploy(runtime, bob.name)
            check(refused.text == "DENIED (forge_off): the Forge was switched off by Server: essai de l'assemblage") { "deploy_module switched off: ${refused.text}" }
            check(tools(runtime, Role.DIRECTOR).size == 44 && tools(runtime, Role.FORGER).isEmpty()) { "the Forge's tools hidden: ${tools(runtime, Role.DIRECTOR)}" }

            val on = answer(helper, "narrator forge on")
            check(on.firstOrNull() == "[Narrator] the Forge is switched back on by Server: modules may be deployed again.") { "forge on: $on" }
            check(modules.halted == null && modules.list().isEmpty()) { "lifted; what it stopped stays stopped" }
            check(tools(runtime, Role.DIRECTOR).size == 47 && tools(runtime, Role.FORGER).size == 5) { "the Forge's tools back" }
        } finally {
            if (modules.halted != null) runCatching { modules.resume("gametest") }
            for (view in modules.list().filter { it.ref == deployed }) runCatching { modules.stop(view.ref, "Le test est fini.", CLEANUP) }
            server.onServerThread { bob.close() }
        }
    }

    private companion object {
        /** The batch of this test alone (`test_environment/forge_assembly.json`): it pulls the switch of the server's Forge. */
        const val FORGE_ASSEMBLY_ENVIRONMENT = "narrator2-gametest:forge_assembly"

        const val PARADE = "parade"

        /** The Director's tools of the Forge (M5 §9.1). */
        val FORGE_TOOLS = listOf("forge_module", "deploy_module", "stop_module")

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest-forge-assembly", turnId = 5021, taskId = null)

        /** Who ends, in the test's `finally`, what it left running. */
        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")

        /** [role]'s tools, as a chapter or a task opens them now (`Chapters.open`, `Workers`). */
        fun tools(runtime: NarratorRuntime, role: Role): List<String> {
            val env = ToolEnv(
                journalAvailable = runtime.journal.available,
                workerRoles = runtime.workers.roles,
                memoryAvailable = runtime.memory.available,
                mechanicsAvailable = runtime.mechanics.available,
                forgeAvailable = forgeAvailable(runtime),
            )
            return ToolCatalog.toolSetFor(role, env, runtime).names
        }

        /** `deploy_module` of `parade` behind [player], three chickens, as the model writes it. Off the server thread. */
        suspend fun deploy(runtime: NarratorRuntime, player: String): ToolResult = ToolRunner(runtime).invoke(
            DeployModuleTool(), DIRECTOR,
            """{"module": "$PARADE", "params": {"player": "$player", "count": 3}, "kind": "plan", "statement": "Le Narrateur a fait suivre $player par une parade de poules.", "minutes": 5}""",
        )

        /** What the server console gets from [command], once its answer came (a change answers off the server thread). */
        suspend fun answer(helper: GameTestHelper, command: String): List<String> {
            val output = operatorCommands(helper, command)
            awaitCondition("the answer to /$command", 10_000) { output.lines.isNotEmpty() }
            helper.level.server.onServerThread {} // the lines of one answer are sent in one task of the server thread
            return output.lines.toList()
        }

        /** The creatures of deployment [ref] around the test. Server thread. */
        fun chickens(helper: GameTestHelper, ref: Ref): List<Mob> =
            helper.level.getEntitiesOfClass(Mob::class.java, helper.bounds.inflate(32.0)) { ModuleCreatures.ownerOf(it) == ref.id }

        /** How [ref] ended, from its decision `mechanic module_ended` in the Journal; null before it ends. */
        suspend fun ended(runtime: NarratorRuntime, ref: Ref): String? = runCatchingJournal(
            runtime, "SELECT json_extract_string(detail, '$.ending') FROM engine_decisions WHERE kind = 'mechanic' AND decision = ? AND subject = ?",
            ModuleEngine.ENDED, ref.toString(),
        )?.single()?.toString()
    }
}
