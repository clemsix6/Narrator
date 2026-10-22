package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.relics.RelicsService
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerPlayer

/**
 * `/narrator champion <player> [brief]`, `/narrator relics` and `/narrator relics restock` (M7-RELICS), for the operators:
 * force a champion (it goes through the caps and the guards like the Director's, and the armorer designs it), see the
 * reserves and the champions, and start a restock now (when a reserve is under its target, whatever the last one did).
 * Each command that acts is an `operator` decision.
 */
object RelicsCommands {
    private val log = Log.command

    /** Champions listed by `/narrator relics`, the latest first. */
    private const val LAST_CHAMPIONS = 5

    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("champion").then(
                Commands.argument("player", EntityArgument.player())
                    .executes { champion(it.source, EntityArgument.getPlayer(it, "player"), "") }
                    .then(
                        Commands.argument("brief", StringArgumentType.greedyString())
                            .executes { champion(it.source, EntityArgument.getPlayer(it, "player"), StringArgumentType.getString(it, "brief")) },
                    ),
            ),
        )
        root.then(
            Commands.literal("relics")
                .executes { status(it.source) }
                .then(Commands.literal("restock").executes { restock(it.source) }),
        )
    }

    private fun relicsOf(source: CommandSourceStack): Pair<NarratorRuntime, RelicsService>? {
        val runtime = runningNarrator(source) ?: return null
        val relics = runtime.relics
        if (relics == null) {
            source.sendFailure(errorLine("the armorer's work is unavailable: the Journal is unavailable."))
            return null
        }
        return runtime to relics
    }

    private fun champion(source: CommandSourceStack, player: ServerPlayer, brief: String): Int {
        val (runtime, relics) = relicsOf(source) ?: return 0
        val by = source.textName
        log.info("$by: narrator champion ${player.plainTextName} ${brief.take(80)}")
        Decisions(runtime.trace, runtime.clock).record(log, DecisionRecord.Kind.OPERATOR, "champion", player.plainTextName, "asked by $by: ${brief.take(200)}")
        replyLater(source, runtime, "narrator champion") {
            val caller = Caller(Role.DIRECTOR, "operator:$by", turnId = null, taskId = null)
            val result = relics.summon(player.plainTextName, brief, caller, "operator $by")
            listOf(if (result.status == ToolStatus.OK) narratorLine(result.text.removePrefix("ok: ")) else errorLine(result.text))
        }
        return 1
    }

    private fun status(source: CommandSourceStack): Int {
        val (runtime, relics) = relicsOf(source) ?: return 0
        replyLater(source, runtime, "narrator relics") {
            val last = relics.lastChampions(LAST_CHAMPIONS)
            buildList {
                add(narratorLine("the armorer's work"))
                relics.statusLines().forEach { add(detailLine(it)) }
                if (last.isNotEmpty()) {
                    add(detailLine("last champions:"))
                    last.forEach { row ->
                        val end = row.killer?.let { " slain by $it" + (row.relic?.let { relic -> ", relic $relic" } ?: ", no relic") }.orEmpty()
                        add(detailLine("  #${row.id} « ${row.name} » ${row.mob} for ${row.playerName}: ${row.status}$end"))
                    }
                }
            }
        }
        return 1
    }

    private fun restock(source: CommandSourceStack): Int {
        val (runtime, relics) = relicsOf(source) ?: return 0
        val what = relics.maybeRestock("asked by ${source.textName}", force = true)
        Decisions(runtime.trace, runtime.clock).record(log, DecisionRecord.Kind.OPERATOR, "relics_restock", source.textName, what)
        source.sendSuccess({ narratorLine(what) }, false)
        return 1
    }
}
