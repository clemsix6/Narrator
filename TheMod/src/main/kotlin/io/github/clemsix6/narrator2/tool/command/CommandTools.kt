package io.github.clemsix6.narrator2.tool.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.ParseResults
import com.mojang.brigadier.context.ContextChain
import com.mojang.brigadier.context.ParsedCommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.guard.CommandPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.speech.closestIds
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3

/**
 * Command tools (M1_M2 §9.5, §10.6): `run_command`, the last resort for what no typed tool does, and
 * `command_help`, the syntax of a command. `ToolCatalog` builds the family once per conversation.
 *
 * Both work on the server's own dispatcher, with the source `run_command` runs with ([NarratorCommands.source]):
 * what the model is told about a command's syntax is what this very server parses, never a memory of
 * an older version. A command is parsed, then checked by `CommandPolicy` (behind `execute … run` too),
 * then checked for syntax, and only then run.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun commandTools(services: NarratorServices): List<NarratorTool<*>> = listOf(RunCommandTool(), CommandHelpTool())

// ── run_command ─────────────────────────────────────────────────────────────────────────────────

@Serializable
data class RunCommandArgs(
    @Doc("The command, without the leading slash: `scoreboard players get Bartok kills`.")
    @MaxLen(400)
    val command: String,
    @Doc(
        "Name of an online player the command runs as: their position, dimension and rotation, and @s. " +
            "Left out: at the world spawn, in the overworld, without @s.",
    )
    @MaxLen(16)
    @SerialName("as_player")
    val asPlayer: String? = null,
)

class RunCommandTool : NarratorTool<RunCommandArgs>(
    name = "run_command",
    description = "Last resort: runs a Minecraft 26.3 server command, for what no other tool does (scoreboard, particle, " +
        "recipe, execute if … probes). The command is parsed by this server first and runs only if it is valid; " +
        "otherwise you get the error, where it is (<--[HERE]) and the exact syntax. command_help gives a command's syntax " +
        "beforehand. Refused, also behind execute … run: commands that change blocks, items, entities, players, game rules " +
        "or the server, speech (use the speech tools) and what another tool does. At most $COMMANDS_PER_TURN commands per " +
        "turn. Returns what the command answered.",
    args = RunCommandArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    private var turn: Pair<String, Long?>? = null
    private var ranThisTurn = 0

    override suspend fun run(ctx: ToolContext, args: RunCommandArgs): ToolResult {
        val text = Commands.trimOptionalPrefix(args.command.trim())
        if (text.isEmpty()) return ToolResult.invalid("command is empty")
        val anchor = args.asPlayer?.let { name ->
            ctx.players.byName(name)?.ref ?: return ToolResult.invalid("$name is not online; online: ${online(ctx)}")
        }
        return ctx.onServer { server ->
            val player = anchor?.let {
                server.playerList.getPlayer(it.uuid) ?: return@onServer ToolResult.invalid("${it.name} is not online; online: ${online(ctx)}")
            }
            val capture = CapturingSource()
            val runs = mutableListOf<Run>()
            val source = NarratorCommands.source(server, capture, player).withCallback { success, result -> runs += Run(success, result) }
            val dispatcher = server.commands.dispatcher
            val policy = ctx.guards.commands
            val parse = dispatcher.parse(text, source)
            val verdict = policy.check(parse)
            if (verdict is Verdict.Denied) {
                recordDenial(ctx, text, verdict)
                return@onServer ToolResult.denied(verdict)
            }
            val deepest = NarratorCommands.deepest(parse)
                ?: return@onServer ToolResult.invalid(NarratorCommands.unknownCommand(dispatcher, source, policy, text))
            NarratorCommands.syntaxProblem(dispatcher, source, parse, deepest, text)?.let { return@onServer ToolResult.invalid(it) }
            take(ctx.caller)?.let { return@onServer ToolResult.denied(it) }
            // Outside of any running command (tools never run inside one), so it runs now, not queued.
            server.commands.performCommand(parse, text)
            outcome(runs, capture.lines)
        }
    }

    /** One more command in [caller]'s turn: null when allowed (and counted), else the refusal. */
    @Synchronized
    private fun take(caller: Caller): Verdict.Denied? {
        val key = caller.conversationId to caller.turnId
        if (key != turn) {
            turn = key
            ranThisTurn = 0
        }
        if (ranThisTurn >= COMMANDS_PER_TURN) {
            return Verdict.Denied(
                LIMIT_RULE,
                "$COMMANDS_PER_TURN commands already ran this turn, the most a turn may run; do the rest next turn, or with the typed tools",
            )
        }
        ranThisTurn++
        return null
    }

    /** A `guard denied` decision, like the guards' own refusals, so that `/narrator why` shows it. */
    private fun recordDenial(ctx: ToolContext, command: String, verdict: Verdict.Denied) {
        Decisions(ctx.services.trace, ctx.clock).record(
            Log.guard, DecisionRecord.Kind.GUARD, "denied", "run_command",
            reason = "${verdict.rule}: ${verdict.message}",
            conversationId = ctx.caller.conversationId, turnId = ctx.caller.turnId,
            detail = buildJsonObject {
                put("rule", verdict.rule)
                put("command", command)
            },
        )
    }

    /** One run of the command: once, or once per entity of an `execute as …` fork. */
    private class Run(val success: Boolean, val result: Int)

    /** What the model gets back: how the runs went, then the lines the command sent. */
    private fun outcome(runs: List<Run>, lines: List<String>): ToolResult {
        val output = if (lines.isEmpty()) "; it sent no output" else "\n" + capped(lines, MAX_OUTPUT_LINES).joinToString("\n")
        val succeeded = runs.filter { it.success }
        return when {
            runs.isEmpty() -> ToolResult.ok("ok: ran for nothing (its selectors matched no entity)$output")
            succeeded.isEmpty() && runs.size == 1 -> ToolResult.failed("the command ran and failed$output")
            succeeded.isEmpty() -> ToolResult.failed("the command ran ${runs.size} times and failed each time$output")
            runs.size == 1 -> ToolResult.ok("ok: result ${runs.single().result}$output")
            else -> ToolResult.ok("ok: ran ${runs.size} times, ${succeeded.size} succeeded, results add up to ${succeeded.sumOf { it.result }}$output")
        }
    }

    companion object {
        const val COMMANDS_PER_TURN = 6
        const val MAX_OUTPUT_LINES = 20

        /** The rule of the per-turn limit, in `DENIED (command_limit): …`. */
        const val LIMIT_RULE = "command_limit"
    }
}

// ── command_help ────────────────────────────────────────────────────────────────────────────────

@Serializable
data class CommandHelpArgs(
    @Doc(
        "A command, or the start of one: `scoreboard`, `execute if`, `scoreboard players set`. " +
            "Left empty: the list of the commands run_command accepts.",
    )
    @MaxLen(400)
    val command: String = "",
)

class CommandHelpTool : NarratorTool<CommandHelpArgs>(
    name = "command_help",
    description = "The exact syntax of a Minecraft 26.3 command on this server, as a compact tree like /help: one line per " +
        "branch, <argument>, [optional], (a|b) choices, -> execute where it loops back. Give a command or the start of one " +
        "(`execute if`) to see what can follow it; leave it empty to list the commands run_command accepts. Says when a " +
        "command or a branch is forbidden.",
    args = CommandHelpArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: CommandHelpArgs): ToolResult = ctx.onServer { server ->
        val dispatcher = server.commands.dispatcher
        val policy = ctx.guards.commands
        val source = NarratorCommands.source(server, CapturingSource(), null)
        val text = Commands.trimOptionalPrefix(args.command.trim())
        if (text.isEmpty()) {
            val names = NarratorCommands.accepted(dispatcher, source, policy)
            return@onServer ToolResult.ok("Commands run_command accepts (${names.size}): ${names.joinToString(", ")}")
        }
        val parse = dispatcher.parse(text, source)
        val verdict = policy.check(parse)
        if (verdict is Verdict.Denied) return@onServer ToolResult.denied(verdict)
        val deepest = NarratorCommands.deepest(parse)
            ?: return@onServer ToolResult.invalid(NarratorCommands.unknownCommand(dispatcher, source, policy, text))
        val prefix = text.substring(0, deepest.range.end).trimEnd()
        val rest = text.substring(deepest.range.end).trim()
        val notUnderstood = if (rest.isEmpty()) "" else "\n(\"$rest\" is not part of the syntax: here is what can follow `$prefix`)"
        val usage = dispatcher.getSmartUsage(deepest.node, source)
        if (usage.isEmpty()) return@onServer ToolResult.ok("`$prefix` takes nothing more: it runs as is$notUnderstood")
        val lines = usage.map { (child, line) ->
            val forbidden = child is LiteralCommandNode<*> && policy.check(dispatcher.parse("$prefix ${child.literal}", source)) is Verdict.Denied
            "$prefix $line" + if (forbidden) "  (forbidden)" else ""
        }
        ToolResult.ok(capped(lines, MAX_LINES).joinToString("\n") + notUnderstood)
    }

    companion object {
        const val MAX_LINES = 40
    }
}

// ── shared ──────────────────────────────────────────────────────────────────────────────────────

/** Captures every line a command sends back, successes and failures; never informs the operators. */
internal class CapturingSource : CommandSource {
    val lines = mutableListOf<String>()

    override fun sendSystemMessage(message: Component) {
        lines += message.string
    }

    override fun acceptsSuccess() = true

    override fun acceptsFailure() = true

    override fun shouldInformAdmins() = false
}

/** How the Narrator's commands are parsed and explained (ORACLE_ACTIONS_API.md §4.11). */
internal object NarratorCommands {
    /**
     * The source of the Narrator's commands, level gamemaster (commands of levels 3 and 4 are invisible
     * to it), whose output goes to [capture]: the operators get no "[Narrator: …]" line. Never
     * `withSuppressedOutput()`, which would silence the capture too. Anchored to [anchor] (position,
     * dimension, rotation, `@s`) when given, else at the world spawn like the server's own source.
     */
    fun source(server: MinecraftServer, capture: CapturingSource, anchor: ServerPlayer?): CommandSourceStack {
        val base = CommandSourceStack(
            capture, Vec3.atLowerCornerOf(server.respawnData.pos()), Vec2.ZERO, server.findRespawnDimension(),
            LevelBasedPermissionSet.GAMEMASTER, Component.literal(SOURCE_NAME), server,
        )
        if (anchor == null) return base
        return base.withEntity(anchor).withPosition(anchor.position()).withRotation(anchor.rotationVector).withLevel(anchor.level())
    }

    /**
     * Why [parse] of [text] cannot run, for the model: the error, where it is, and the exact syntax from
     * the part understood up to its [deepest] node; null when it can run. A command can parse to its end
     * and still be incomplete (`scoreboard objectives add`): the parse error and the flattening are both
     * checked, like `Commands.performCommand` does.
     */
    fun syntaxProblem(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        source: CommandSourceStack,
        parse: ParseResults<CommandSourceStack>,
        deepest: ParsedCommandNode<CommandSourceStack>,
        text: String,
    ): String? {
        val error = Commands.getParseException(parse)
        if (error == null && ContextChain.tryFlatten(parse.context.build(text)).isPresent) return null
        val what = error?.let { "the command does not parse: " + ComponentUtils.fromMessage(it.rawMessage).string } ?: "the command is incomplete"
        val cursor = error?.cursor?.takeIf { it in 0..text.length } ?: text.length
        val prefix = text.substring(0, deepest.range.end).trimEnd()
        val usage = dispatcher.getSmartUsage(deepest.node, source).values.map { "$prefix $it" }
        return buildString {
            append(what)
            append("\nat character ").append(cursor).append(": ").append(text, 0, cursor).append("<--[HERE]")
            if (usage.isNotEmpty()) append("\nsyntax:\n").append(capped(usage, MAX_USAGE_LINES).joinToString("\n"))
        }
    }

    /** The last node the parse went through, behind `execute … run` too; null when not even the command was recognised. */
    fun deepest(parse: ParseResults<CommandSourceStack>): ParsedCommandNode<CommandSourceStack>? {
        var context = parse.context
        var deepest: ParsedCommandNode<CommandSourceStack>? = null
        while (true) {
            context.nodes.lastOrNull()?.let { deepest = it }
            context = context.child ?: return deepest
        }
    }

    /** The commands run_command accepts, sorted: visible to [source], and allowed by [policy]. */
    fun accepted(dispatcher: CommandDispatcher<CommandSourceStack>, source: CommandSourceStack, policy: CommandPolicy): List<String> =
        dispatcher.root.children.filter { it.canUse(source) }.map { it.name }
            .filter { policy.check(dispatcher.parse(it, source)) == Verdict.Allowed }
            .sorted()

    /** `unknown command "x"`, with the closest commands run_command accepts. */
    fun unknownCommand(dispatcher: CommandDispatcher<CommandSourceStack>, source: CommandSourceStack, policy: CommandPolicy, text: String): String {
        val word = text.substringBefore(' ')
        return "unknown command \"$word\"; closest commands: ${closestIds(word, accepted(dispatcher, source, policy)).joinToString()} " +
            "(command_help with no command lists all those run_command accepts)"
    }

    private const val SOURCE_NAME = "Narrator"
    private const val MAX_USAGE_LINES = 20
}

/** At most [max] of [lines], then how many were left out. */
private fun capped(lines: List<String>, max: Int): List<String> =
    if (lines.size <= max) lines else lines.take(max) + "… (+${lines.size - max} lines)"

/** The names of the players online, for an error message. */
private fun online(ctx: ToolContext): String = ctx.players.online.joinToString { it.ref.name }.ifEmpty { "nobody" }
