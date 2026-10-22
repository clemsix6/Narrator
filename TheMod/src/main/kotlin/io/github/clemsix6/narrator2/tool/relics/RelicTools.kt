package io.github.clemsix6.narrator2.tool.relics

import io.github.clemsix6.narrator2.relics.ArmorerJob
import io.github.clemsix6.narrator2.relics.BookDraft
import io.github.clemsix6.narrator2.relics.ChampionDraft
import io.github.clemsix6.narrator2.relics.ConsumableDraft
import io.github.clemsix6.narrator2.relics.RelicDraft
import io.github.clemsix6.narrator2.relics.RelicsService
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * The tools of the armorer's work (M7-RELICS): the Director's `summon_champion`, and the armorer's own typed outputs,
 * `create_champion`, `create_relic`, `create_consumable` and `write_book`, each checked by the code before anything is
 * made (`RelicRules`, `RelicItems`). All are shown only while the armorer can work ([ToolEnv.relicsAvailable]); the work
 * is the service's (`RelicsService`), the tools hand it over.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun relicTools(services: NarratorServices): List<NarratorTool<*>> =
    listOf(SummonChampionTool(), CreateChampionTool(), CreateRelicTool(), CreateConsumableTool(), WriteBookTool())

/** A tool of the armorer's work: shown while it can work, and failing with its reason when it fell since. */
abstract class RelicTool<A : Any>(name: String, description: String, args: KSerializer<A>, effect: ToolEffect, timeoutMs: Long = 15_000) :
    NarratorTool<A>(name, description, args, effect, timeoutMs) {
    final override fun isAvailable(env: ToolEnv): Boolean = env.relicsAvailable

    /** Runs [work]; a refusal in an armorer's task is kept as its last ([ArmorerJob.lastRefusal]), for its report. */
    final override suspend fun run(ctx: ToolContext, args: A): ToolResult {
        val relics = ctx.services.relics ?: return ToolResult.failed("the armorer is unavailable: the Journal is unavailable")
        val result = work(ctx, relics, args)
        if (result.isError) relics.job(ctx.caller.conversationId)?.lastRefusal = result.text
        return result
    }

    abstract suspend fun work(ctx: ToolContext, relics: RelicsService, args: A): ToolResult
}

@Serializable
data class SummonChampionArgs(
    @Doc("The online player it is made for, and goes for.") @MaxLen(16) val player: String,
    @Doc(
        "For the armorer, who knows nothing else: what the champion stands for (a grudge, a running joke, a prophecy, the " +
            "end of an arc), its tone, what you want the player to feel. Not its numbers: the armorer sets them within the caps.",
    )
    @MaxLen(600)
    val brief: String,
)

/** `summon_champion`: the Director commissions a champion; the armorer designs it, and it appears near its player. */
class SummonChampionTool : RelicTool<SummonChampionArgs>(
    name = "summon_champion",
    description = "Commissions a champion from the armorer: a hostile creature far harder than its kind, designed for one " +
        "player online (its name, legend, gear and powers fit them and your story). It appears near them within a minute " +
        "or two, goes for them alone, and vanishes at its time (half an hour by default) or when they die or leave; its " +
        "fatal blow kills within your quota of deaths, else it is a rescue. Whoever kills it gets a relic " +
        "made for them, a lasting good item, and its gear. A punishment of that player (creatures) under your rules. Rare: " +
        "for a strong player who boasts, coasts or earned a trial, or the peak of an arc, a challenge or a season; never " +
        "for a new or struggling player, nor twice in an evening for the same one. A CHAMPION event tells you what it " +
        "looks like once it stands, then how it ends: announce it, taunt, crown the winner.",
    args = SummonChampionArgs.serializer(),
    effect = ToolEffect.TASK,
) {
    override suspend fun work(ctx: ToolContext, relics: RelicsService, args: SummonChampionArgs): ToolResult =
        relics.summon(args.player, args.brief, ctx.caller, ctx.caller.role.id)
}

/** `create_champion`: the armorer's champion, summoned near the player of its task. */
class CreateChampionTool : RelicTool<ChampionDraft>(
    name = "create_champion",
    description = "Makes the champion of your task stand near its player: checked against its caps, then summoned with its " +
        "gear and powers; it goes for that player. Refused with the reason when something is off: fix it and call again. " +
        "Once per task.",
    args = ChampionDraft.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = 20_000,
) {
    override suspend fun work(ctx: ToolContext, relics: RelicsService, args: ChampionDraft): ToolResult = relics.createChampion(ctx, args)
}

/** `create_relic`: a relic for a champion's killer, or for the chests' reserve. */
class CreateRelicTool : RelicTool<RelicDraft>(
    name = "create_relic",
    description = "Forges one relic: a weapon, a tool, a piece of armor or a shield that stays a good item (at least one " +
        "bonus, at most one light malus). In a relic task it goes to the champion's killer at once; in a restock it waits " +
        "in the chests' reserve. Refused with the reason when something is off: fix it and call again.",
    args = RelicDraft.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun work(ctx: ToolContext, relics: RelicsService, args: RelicDraft): ToolResult = relics.createRelic(ctx, args)
}

/** `create_consumable`: a strange consumable for the chests' reserve. */
class CreateConsumableTool : RelicTool<ConsumableDraft>(
    name = "create_consumable",
    description = "Puts one strange consumable in the chests' reserve: a plain food whose effects you choose, each with its " +
        "chance; never deadly. Restock tasks only.",
    args = ConsumableDraft.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun work(ctx: ToolContext, relics: RelicsService, args: ConsumableDraft): ToolResult = relics.createConsumable(ctx, args)
}

/** `write_book`: a written book for the chests' reserve. */
class WriteBookTool : RelicTool<BookDraft>(
    name = "write_book",
    description = "Puts one written book in the chests' reserve: the server's history, a prophecy, a rumor, about real " +
        "players and real events. Restock tasks only.",
    args = BookDraft.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun work(ctx: ToolContext, relics: RelicsService, args: BookDraft): ToolResult = relics.writeBook(ctx, args)
}
