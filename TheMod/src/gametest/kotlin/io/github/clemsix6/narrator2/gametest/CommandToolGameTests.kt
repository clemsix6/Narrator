package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.TurnCounter
import io.github.clemsix6.narrator2.tool.command.CommandHelpArgs
import io.github.clemsix6.narrator2.tool.command.CommandHelpTool
import io.github.clemsix6.narrator2.tool.command.RunCommandArgs
import io.github.clemsix6.narrator2.tool.command.RunCommandTool
import io.github.clemsix6.narrator2.tool.command.commandTools
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of `run_command` and `command_help` (M1_M2 §9.5, §10.6, §17.3), on the server's real
 * dispatcher: a valid command runs and its answer comes back (without a word to the operators), an
 * invalid one gets its error, where it is and the exact syntax, a forbidden one is refused, behind
 * `execute … run` too, and the world is left as it was.
 *
 * Each test builds on its own gold block at (1, 1, 1) and names players uniquely: tests run side by side.
 */
class CommandToolGameTests {

    @GameTest
    fun aValidCommandRunsAndItsAnswerComesBack(helper: GameTestHelper) {
        val gold = gold(helper)
        val at = "${gold.x} ${gold.y} ${gold.z}"

        helper.assertEquals("ok: result 1\nTest passed", run(helper, "execute if block $at minecraft:gold_block").text, "a probe that passes")
        val failed = run(helper, "execute if block $at minecraft:stone")
        helper.assertEquals(ToolStatus.FAILED, failed.status, failed.text)
        helper.assertEquals("FAILED: the command ran and failed\nTest failed", failed.text, "a probe that fails")
        helper.assertEquals(
            "ok: ran for nothing (its selectors matched no entity); it sent no output",
            run(helper, "execute as @e[type=minecraft:ender_dragon,distance=..1] run list").text,
            "an execute that matches nobody",
        )
        helper.assertEquals("ok: result 1\nTest passed", run(helper, "/execute if block $at minecraft:gold_block").text, "a leading slash is fine")
        helper.succeed()
    }

    /** The operators get no "[Narrator: …]" line; the same command from a source that informs them would reach them. */
    @GameTest
    fun theOperatorsAreNotToldWhatTheNarratorRuns(helper: GameTestHelper) {
        val server = helper.level.server
        val objective = "t17_" + UUID.randomUUID().toString().take(8)
        Watcher(helper).use { operator ->
            server.playerList.op(operator.player.nameAndId())
            try {
                operator.forget()
                val added = run(helper, "scoreboard objectives add $objective dummy")
                helper.assertEquals(ToolStatus.OK, added.status, added.text)
                helper.assertTrue(added.text.endsWith("\nCreated new objective [$objective]"), added.text)
                helper.assertEquals(emptyList<String>(), operator.chat(), "what the operator got from the Narrator's command")

                // The control: removed by a source that informs the operators, the operator hears of it.
                val source = server.createCommandSourceStack().withSource(Informing)
                val remove = "scoreboard objectives remove $objective"
                server.commands.performCommand(server.commands.dispatcher.parse(remove, source), remove)
                helper.assertTrue(operator.chat().any { "Removed objective [$objective]" in it }, "an informing source reaches the operator: ${operator.chat()}")
            } finally {
                server.playerList.deop(operator.player.nameAndId())
            }
        }
        helper.succeed()
    }

    /** `as_player`: the command runs where the player stands, and `@s` is the player. */
    @GameTest
    fun asPlayerRunsTheCommandWhereThePlayerStands(helper: GameTestHelper) {
        val gold = gold(helper)
        Watcher(helper).use { watcher ->
            watcher.player.teleportTo(gold.x + 0.5, gold.y + 1.0, gold.z + 0.5)
            helper.assertEquals(
                "ok: result 1\nTest passed",
                run(helper, "execute if block ~ ~-1 ~ minecraft:gold_block", asPlayer = watcher.name.uppercase()).text,
                "the block under the player (names ignore case)",
            )
            helper.assertEquals("ok: result 1\nTest passed. Count: 1", run(helper, "execute if entity @s", asPlayer = watcher.name).text, "@s")
            helper.assertEquals(ToolStatus.FAILED, run(helper, "execute if entity @s").status, "no @s without a player")
            val absent = run(helper, "list", asPlayer = "nobody_t17")
            helper.assertEquals(ToolStatus.INVALID, absent.status, absent.text)
            helper.assertTrue(absent.text.startsWith("Invalid arguments: nobody_t17 is not online; online: ") && watcher.name in absent.text, absent.text)
        }
        helper.succeed()
    }

    @GameTest
    fun anInvalidCommandGetsItsErrorWhereItIsAndTheExactSyntax(helper: GameTestHelper) {
        val tool = RunCommandTool(TurnCounter(RunCommandTool.COMMANDS_PER_TURN))
        val unknownBlock = run(helper, tool, "execute if block ~ ~ ~ minecraft:hart")
        helper.assertEquals(ToolStatus.INVALID, unknownBlock.status, unknownBlock.text)
        helper.assertEquals(
            "Invalid arguments: the command does not parse: Unknown block type 'minecraft:hart'\n" +
                "at character 23: execute if block ~ ~ ~ <--[HERE]\n" +
                "syntax:\n" +
                "execute if block ~ ~ ~ <block> -> execute",
            unknownBlock.text,
            "a wrong argument",
        )
        helper.assertEquals(
            "Invalid arguments: the command is incomplete\n" +
                "at character 25: scoreboard objectives add<--[HERE]\n" +
                "syntax:\n" +
                "scoreboard objectives add <objective> <criteria> [<displayName>]",
            run(helper, tool, "scoreboard objectives add").text,
            "a command that stops too early",
        )
        helper.assertEquals(
            "Invalid arguments: the command does not parse: Incorrect argument for command\n" +
                "at character 5: list <--[HERE]\n" +
                "syntax:\n" +
                "list [uuids]",
            run(helper, tool, "list everyone").text,
            "a word too many",
        )
        val typo = run(helper, tool, "scorebord objectives list")
        helper.assertTrue(typo.text.startsWith("Invalid arguments: unknown command \"scorebord\"; closest commands: scoreboard, "), typo.text)
        // Nothing ran, so nothing counted: the whole turn's commands are still there.
        repeat(RunCommandTool.COMMANDS_PER_TURN) { helper.assertEquals(ToolStatus.OK, run(helper, tool, "list").status, "run ${it + 1}") }
        helper.succeed()
    }

    @GameTest
    fun aForbiddenCommandIsRefusedAndTheWorldIsUntouched(helper: GameTestHelper) {
        val gold = gold(helper)
        val at = "${gold.x} ${gold.y} ${gold.z}"
        val fill = run(helper, "fill $at $at minecraft:air")
        helper.assertEquals(ToolStatus.DENIED, fill.status, fill.text)
        helper.assertEquals(
            "DENIED (${Rules.FORBIDDEN_COMMAND}): `fill` is forbidden: it changes blocks, and the players' constructions are never modified.",
            fill.text,
            "fill",
        )
        helper.assertEquals(
            "DENIED (${Rules.FORBIDDEN_COMMAND}): `stop` is forbidden: server administration is for the operators alone.",
            run(helper, "stop").text,
            "a command the source cannot even see",
        )
        helper.assertEquals(
            "DENIED (${Rules.FORBIDDEN_COMMAND}): `give` is forbidden: it creates, changes or destroys items, experience or progress " +
                "outside the Narrator's ledgers; use the give_item tool instead.",
            run(helper, "give @a minecraft:diamond 64").text,
            "what a typed tool does",
        )
        helper.assertBlockPresent(Blocks.GOLD_BLOCK, BlockPos(1, 1, 1))
        helper.succeed()
    }

    @GameTest
    fun aForbiddenCommandHiddenBehindExecuteIsRefused(helper: GameTestHelper) {
        val gold = gold(helper)
        val at = "${gold.x} ${gold.y} ${gold.z}"
        val refused = linkedMapOf(
            "execute as @a at @s run setblock $at minecraft:air" to "setblock",
            "execute if block $at minecraft:gold_block run fill $at $at minecraft:air" to "fill",
            "execute positioned $at run execute align xyz run clone ~ ~ ~ ~ ~ ~ ~ ~1 ~" to "clone",
            "execute store result score @s kills run list" to "execute store",
            "execute summon minecraft:zombie run list" to "execute summon",
            // M2 review, finding 7: past the particles tool's caps, past play_sound's, and to every player's chat.
            "execute as @a at @s run particle minecraft:heart ~ ~ ~ 0 0 0 1 2000000000 force" to "particle",
            "execute at @a run playsound minecraft:entity.pig.ambient master @a" to "playsound",
            "execute run random roll 1..6" to "random roll",
        )
        val wrong = refused.mapNotNull { (command, name) ->
            val result = run(helper, command)
            if (result.status == ToolStatus.DENIED && result.text.startsWith("DENIED (${Rules.FORBIDDEN_COMMAND}): `$name` is forbidden")) null else "$command -> ${result.text}"
        }
        helper.assertTrue(wrong.isEmpty(), "not refused as expected: $wrong")
        helper.assertBlockPresent(Blocks.GOLD_BLOCK, BlockPos(1, 1, 1))
        helper.succeed()
    }

    @GameTest
    fun sixCommandsPerTurn(helper: GameTestHelper) {
        // The tools of two conversations built for this server count against its own limit (ToolLimits.commands).
        val caller = CALLER.copy(conversationId = "command-gametest-limit-${UUID.randomUUID()}")
        val first = commandTools(helper.narrator()).filterIsInstance<RunCommandTool>().single()
        val tool = commandTools(helper.narrator()).filterIsInstance<RunCommandTool>().single()
        repeat(RunCommandTool.COMMANDS_PER_TURN) {
            helper.assertEquals(ToolStatus.OK, run(helper, if (it % 2 == 0) first else tool, "list", caller = caller).status, "command ${it + 1}")
        }
        val seventh = run(helper, tool, "list", caller = caller)
        helper.assertEquals(ToolStatus.DENIED, seventh.status, seventh.text)
        helper.assertEquals(
            "DENIED (${RunCommandTool.LIMIT_RULE}): 6 commands already ran this turn, the most a turn may run; do the rest next turn, or with the typed tools",
            seventh.text,
            "the seventh",
        )
        helper.assertEquals(ToolStatus.OK, run(helper, tool, "list", caller = caller.copy(turnId = caller.turnId!! + 1)).status, "the next turn")
        helper.succeed()
    }

    @GameTest
    fun commandHelpGivesTheSyntaxTreeOfThisServer(helper: GameTestHelper) {
        helper.assertEquals(
            listOf(
                "scoreboard objectives list",
                "scoreboard objectives add <objective> <criteria> [<displayName>]",
                "scoreboard objectives modify <objective> (displayname|rendertype|displayautoupdate|numberformat)",
                "scoreboard objectives remove <objective>",
                "scoreboard objectives setdisplay <slot> [<objective>]",
            ),
            help(helper, "scoreboard objectives").text.lines(),
            "a subcommand's tree",
        )
        val execute = help(helper, "execute").text.lines()
        helper.assertTrue("execute as <targets> -> execute" in execute, "loops back to execute: $execute")
        helper.assertTrue("execute store (result|success)  (forbidden)" in execute, "a forbidden branch is marked: $execute")
        helper.assertTrue("execute if (block" in execute.joinToString("\n"), "if: $execute")
        helper.assertEquals(
            listOf("execute as @a run random value <range> [<sequence>]", "execute as @a run random roll <range> [<sequence>]  (forbidden)"),
            help(helper, "execute as @a run random").text.lines().take(2),
            "behind execute … run, a forbidden branch marked",
        )
        helper.assertEquals(
            "`list uuids` takes nothing more: it runs as is\n(\"extra\" is not part of the syntax: here is what can follow `list uuids`)",
            help(helper, "list uuids extra").text,
            "a leaf, and what was not understood",
        )

        val give = help(helper, "give")
        helper.assertEquals(ToolStatus.DENIED, give.status, give.text)
        helper.assertTrue(give.text.startsWith("DENIED (${Rules.FORBIDDEN_COMMAND}): `give` is forbidden"), give.text)
        helper.assertTrue(help(helper, "execute store").text.startsWith("DENIED (${Rules.FORBIDDEN_COMMAND}): `execute store` is forbidden"), "a forbidden branch")

        val accepted = help(helper, "").text
        helper.assertTrue(accepted.startsWith("Commands run_command accepts ("), accepted)
        val names = accepted.substringAfter(": ").split(", ")
        helper.assertTrue(names.containsAll(listOf("execute", "scoreboard", "stopsound", "random")), "accepted: $names")
        helper.assertTrue(
            names.none { it in listOf("fill", "give", "tp", "say", "stop", "recipe", "particle", "playsound") },
            "no forbidden command: $names",
        )

        val typo = help(helper, "scorebord")
        helper.assertEquals(ToolStatus.INVALID, typo.status, typo.text)
        helper.assertTrue(typo.text.startsWith("Invalid arguments: unknown command \"scorebord\"; closest commands: scoreboard, "), typo.text)
        helper.succeed()
    }

    private companion object {
        val CALLER = Caller(Role.DIRECTOR, "command-gametest", turnId = 1_717, taskId = null)

        /** A gold block at (1, 1, 1) of the test area; its absolute position. */
        fun gold(helper: GameTestHelper): BlockPos {
            helper.setBlock(BlockPos(1, 1, 1), Blocks.GOLD_BLOCK)
            return helper.absolutePos(BlockPos(1, 1, 1))
        }

        /** `run_command` in a turn of its own (a new counter: the per-turn limit starts afresh). */
        fun run(helper: GameTestHelper, command: String, asPlayer: String? = null): ToolResult =
            run(helper, RunCommandTool(TurnCounter(RunCommandTool.COMMANDS_PER_TURN)), command, asPlayer)

        fun run(helper: GameTestHelper, tool: RunCommandTool, command: String, asPlayer: String? = null, caller: Caller = CALLER): ToolResult =
            call(helper, tool, RunCommandArgs(command, asPlayer), caller)

        fun help(helper: GameTestHelper, command: String): ToolResult = call(helper, CommandHelpTool(), CommandHelpArgs(command), CALLER)

        /** Runs [tool] on the server thread, where its world access runs inline. */
        fun <A : Any> call(helper: GameTestHelper, tool: NarratorTool<A>, args: A, caller: Caller): ToolResult {
            val runtime = helper.narrator()
            return runBlocking { tool.run(ToolContext(runtime, caller, runtime.clock, 5_000), args) }
        }
    }
}

/** A command source that informs the operators, as a console or a command block does. */
private object Informing : CommandSource {
    override fun sendSystemMessage(message: Component) = Unit

    override fun acceptsSuccess() = true

    override fun acceptsFailure() = true

    override fun shouldInformAdmins() = true
}

/**
 * A player with a unique name (mock players are all called `test-mock-player`, and `as_player` finds
 * players by name), who joins for one test and records the chat lines the server sends them.
 */
private class Watcher(private val helper: GameTestHelper) : AutoCloseable {
    val name = "ct_" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val connection = ChatConnection()
    val player: ServerPlayer

    init {
        val server = helper.level.server
        val profile = GameProfile(UUID.randomUUID(), name)
        player = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false))
        forget()
    }

    /** Forgets what was received so far. */
    fun forget() = connection.lines.clear()

    /** The chat lines received. */
    fun chat(): List<String> = connection.lines.toList()

    override fun close() {
        val players = helper.level.server.playerList
        players.getPlayer(player.uuid)?.let { players.remove(it) }
    }
}

/** A connection that keeps the chat lines it sends (not the lines above the hotbar), and sends everything on as usual. */
private class ChatConnection : Connection(PacketFlow.SERVERBOUND) {
    val lines = CopyOnWriteArrayList<String>()

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        if (packet is ClientboundSystemChatPacket && !packet.overlay()) lines += packet.content().string
        super.send(packet, listener, flush)
    }
}
