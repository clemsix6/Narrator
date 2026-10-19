package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.CreatureControl
import io.github.clemsix6.narrator2.forge.sdk.GroundView
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.chaos
import io.github.clemsix6.narrator2.tool.live
import io.github.clemsix6.narrator2.tool.online
import io.github.clemsix6.narrator2.tool.punish
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The tools of the modules alone (M5 §4.4, §9.1): `hud` (a game's action bar, once a second per player),
 * `place_blocks` (`Ground.place`, through the `BlockRestorer`, air only) and `creature_attack` (one of a deployment's
 * creatures goes for a player). Like the rules' own tools, no conversation ever sees them (`ToolCatalog` keeps them
 * from every role); only the engine's acts run them (`EngineActions`, given them by task M5-21), each in the act of a
 * module's call (`mechanics:C-70`), with the same guards as any other tool.
 */
fun moduleOnlyTools(): List<NarratorTool<*>> = listOf(HudTool(), PlaceBlocksTool(), CreatureAttackTool())

/** The deployment whose act [caller] is (`mechanics:C-70`, with a firing id), or null for any other caller. */
internal fun deploymentOf(caller: Caller): Long? {
    if (caller.firingId == null || !caller.conversationId.startsWith(EngineActions.CONVERSATION_PREFIX)) return null
    return Ref.parse(caller.conversationId.removePrefix(EngineActions.CONVERSATION_PREFIX))?.takeIf { it.kind == RefKind.CANON }?.id
}

/** The deployment of the act, or a refusal: these tools run only in a module's act. */
private fun ToolContext.deployment(tool: String): Long = deploymentOf(caller) ?: refuse(ToolResult.invalid("$tool runs only in a module's act"))

// ── hud ────────────────────────────────────────────────────────────────────────────────────────

/** The arguments of `hud` (`Acts.hud`). */
@Serializable
data class HudArgs(
    @Doc("The line above the hotbar: a game's display, a countdown or a score.") @MaxLen(ForgeLimits.HUD_CHARS) val text: String,
    @Doc("Names of online players; everyone online when left out.") @Items(1, 8) @MaxLen(16) val to: List<String>? = null,
)

/**
 * `hud`: a line above the hotbar of some players, or of everyone online, in the Narrator's color: a game's display (a
 * countdown, a score), not a line of the Narrator's: not journaled, and out of the speech limits that the Director and
 * the rules share. One line per player and second ([ForgeLimits.HUD_GAP_MS]), on the server, whatever module asks: the
 * players who got one less than a second ago are skipped, and when all of them did it is `DENIED (module_acts)`.
 */
class HudTool : NarratorTool<HudArgs>(
    name = "hud",
    description = "Modules only: shows a game's line above the hotbar of some players, or of everyone online.",
    args = HudArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    /** When each player last got a line, epoch milliseconds of the tools' clock. */
    private val shown = ConcurrentHashMap<UUID, Long>()

    override suspend fun check(ctx: ToolContext, args: HudArgs): ToolResult? =
        if (Speech.clean(args.text).isEmpty()) ToolResult.invalid("text has nothing to show once formatting codes and invisible characters are removed") else null

    override suspend fun run(ctx: ToolContext, args: HudArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        ctx.deployment(name)
        val text = Speech.clean(args.text)
        val audience = args.to?.let { ctx.online(it) } ?: ctx.players.online.map { it.ref }
        if (audience.isEmpty()) refuse(ToolResult.failed("nobody is online"))
        val now = ctx.clock.millis()
        val (allowed, waiting) = synchronized(shown) {
            audience.partition { now - (shown[it.uuid] ?: Long.MIN_VALUE / 2) >= ForgeLimits.HUD_GAP_MS }.also { (allowed, _) -> allowed.forEach { shown[it.uuid] = now } }
        }
        if (allowed.isEmpty()) {
            refuse(ActQueue.denied("one line above the hotbar per player and second: ${ToolText.names(waiting)} got one less than a second ago"))
        }
        val got = ctx.onServer { server ->
            val line = Speech.colored(ctx.settings.ui, text)
            allowed.mapNotNull { server.playerList.getPlayer(it.uuid) }.onEach { it.sendOverlayMessage(line) }.map { it.ref() }
        }
        if (got.isEmpty()) refuse(ToolResult.failed("nobody got it: ${ToolText.names(allowed)} left the game"))
        val skipped = if (waiting.isEmpty()) "" else "; skipped (one line per player and second): ${ToolText.names(waiting)}"
        ToolResult.ok("ok: shown to ${ToolText.names(got)}$skipped", got)
    }
}

// ── place_blocks ───────────────────────────────────────────────────────────────────────────────

/** A block cell of `place_blocks`. */
@Serializable
data class BlockCell(val x: Int, val y: Int, val z: Int)

/** Longest a block of `place_blocks` stays, in seconds, as a constant the schema can read. */
private const val PLACE_MAX_SECONDS = Limits.MAX_PLACE_SECONDS * 1.0

/** The arguments of `place_blocks` (`Ground.place`). */
@Serializable
data class PlaceBlocksArgs(
    @Doc("Dimension of the cells: overworld, the_nether or the_end.") @MaxLen(40) val dimension: String,
    @Doc("The cells, each in thin air.") @Items(1, ForgeLimits.PLACE_CELLS) val cells: List<BlockCell>,
    @Doc("glass, a stained glass (red_stained_glass…), cobweb, slime_block or honey_block.") @MaxLen(64) val block: String,
    @Doc("How long they stay, in seconds.") @Range(1.0, PLACE_MAX_SECONDS) val seconds: Int,
)

/**
 * `place_blocks`: temporary blocks in thin air through the `BlockRestorer` (M5 §4.4), all or nothing; never in place of
 * a block (decision Q4), never where an entity is. Within [ForgeLimits.PLACE_NEAR_BLOCKS] blocks of a player it is a
 * trap of each such player: refused like any punishment (`trap`, the guards' `vet` of all of them first, then `hostile`),
 * at most [ForgeLimits.PLACE_SECONDS_PER_SEVERITY] seconds per severity the chaos level allows, with the death guard
 * armed for its length; away from every player it is a trap of nobody, [Limits.MAX_PLACE_SECONDS] at most. Whoever stands
 * on or in a block when it goes falls without fall damage (`BlockRestorer`). The cells' checks come first: a cell that
 * is not air is the world's, never a punishment.
 */
class PlaceBlocksTool : NarratorTool<PlaceBlocksArgs>(
    name = GroundView.PLACE_BLOCKS,
    description = "Modules only: sets temporary blocks in thin air, taken away by themselves.",
    args = PlaceBlocksArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun check(ctx: ToolContext, args: PlaceBlocksArgs): ToolResult? =
        if (blockOf(args.block) == null) ToolResult.invalid("block is glass, a stained glass (red_stained_glass…), cobweb, slime_block or honey_block (got \"${args.block}\")") else null

    override suspend fun run(ctx: ToolContext, args: PlaceBlocksArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        ctx.deployment(name)
        val block = blockOf(args.block)!!
        ctx.onServer { server -> answer { place(ctx, server, args, block) } }
    }

    private fun place(ctx: ToolContext, server: MinecraftServer, args: PlaceBlocksArgs, block: Block): ToolResult {
        val level = ModuleCreatures.levelOf(server, args.dimension) ?: refuse(ToolResult.invalid("unknown dimension \"${args.dimension}\""))
        val state = block.defaultBlockState()
        val positions = args.cells.map { BlockPos(it.x, it.y, it.z) }
        if (positions.toSet().size < positions.size) refuse(ToolResult.invalid("the same cell is given twice"))
        val reach = ForgeLimits.PLACE_NEAR_BLOCKS.toDouble()
        val near = level.players().filter { player -> !player.isSpectator && positions.any { player.boundingBox.inflate(reach).intersects(AABB(it)) } }
        val trapped = near.firstOrNull()?.uuid
        for (pos in positions) BlockRestorer.cellRefusal(level, pos, state, trapped)?.let { refuse(ToolResult.failed(it)) }
        val id = BuiltInRegistries.BLOCK.getKey(block).path
        val now = ctx.clock.millis()
        if (near.isNotEmpty()) {
            for (player in near) ctx.guards.vet(player, ctx.caller, PunishmentCategory.TRAP).let { if (it is Verdict.Denied) refuse(ToolResult.denied(it)) }
            val most = ForgeLimits.PLACE_SECONDS_PER_SEVERITY * ctx.guards.caps().maxSeverity
            if (args.seconds > most) {
                refuse(ToolResult.invalid("near a player, blocks stay at most $most s at chaos ${ctx.chaos} (asked ${args.seconds}): ${ToolText.names(near.map { it.ref() })}"))
            }
            for (player in near) ctx.punish(player, now + args.seconds * 1_000L, PunishmentCategory.TRAP)
            for (player in near) ctx.guards.armDeathGuard(player.uuid, args.seconds)
        }
        // The restorer's own clock: the wall clock, as every trap's.
        val until = System.currentTimeMillis() + args.seconds * 1_000L
        val set = BlockRestorer.place(level, positions, state, until, trapped, ctx.caller.act).getOrElse { refuse(ToolResult.failed(it.message ?: "the blocks could not be set")) }
        val first = positions.first()
        val trap = if (near.isEmpty()) "" else "; a trap of ${ToolText.names(near.map { it.ref() })}, the death guard watching over them"
        return ToolResult.ok(
            "ok: $set $id set from ${first.x} ${first.y} ${first.z} until ${ctx.at(now + args.seconds * 1_000L)} (in ${ToolText.duration(args.seconds * 1_000L)})$trap",
            near.map { it.ref() },
        )
    }

    private companion object {
        /** The block called [raw], when the restorer may set it; null otherwise. */
        fun blockOf(raw: String): Block? = parseId(raw)?.let { BuiltInRegistries.BLOCK.get(it).orElse(null)?.value() }?.takeIf(BlockRestorer::allows)
    }
}

// ── creature_attack ────────────────────────────────────────────────────────────────────────────

/** The arguments of `creature_attack` (`Creature.attack`). */
@Serializable
data class CreatureAttackArgs(
    @Doc("The creature, by UUID: one of the module's own.") @MaxLen(36) val creature: String,
    @Doc("Name of the online player it goes for.") @MaxLen(16) val player: String,
)

/**
 * `creature_attack`: one of the deployment's own creatures goes for an online player, and nobody else (M5 §4.4, §4.5):
 * a hostile act (`creatures`), refused like any punishment (protected, dead lately, in grace, dueling, already punished
 * by another act…); for [SECONDS] the creature may aim at that player (`ModuleCreatures.aim`) and the death guard
 * watches over them. `Mob.setTarget` (javap). A creature that never attacks (a chicken) is refused.
 */
class CreatureAttackTool : NarratorTool<CreatureAttackArgs>(
    name = CreatureControl.CREATURE_ATTACK,
    description = "Modules only: one of the module's own creatures goes for a player.",
    args = CreatureAttackArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: CreatureAttackArgs): ToolResult = answer {
        val deployment = ctx.deployment(name)
        val uuid = runCatching { UUID.fromString(args.creature) }.getOrNull() ?: refuse(ToolResult.invalid("creature is a creature's UUID (got \"${args.creature}\")"))
        val target = ctx.online(args.player)
        ctx.onServer { server -> answer { attack(ctx, server, deployment, uuid, server.live(target)) } }
    }

    private fun attack(ctx: ToolContext, server: MinecraftServer, deployment: Long, uuid: UUID, player: ServerPlayer): ToolResult {
        val mob = (server.overworld().getEntityInAnyDimension(uuid) as? Mob)?.takeIf { it.isAlive && !it.isRemoved }
            ?: refuse(ToolResult.failed("the creature $uuid is gone"))
        val type = CreatureControl.shortType(mob)
        if (ModuleCreatures.ownerOf(mob) != deployment) refuse(ToolResult.invalid("$type $uuid is not one of C-$deployment's creatures"))
        if (SpawnPolicy.isHarmless(mob.type)) refuse(ToolResult.invalid("a $type never attacks: summon a creature that does"))
        if (mob.level() !== player.level()) refuse(ToolResult.failed("the $type is not in the dimension of ${player.shownName}"))
        if (player.isCreative || player.isSpectator) refuse(ToolResult.failed("no creature attacks ${player.shownName}, who plays in creative or spectator mode"))
        val until = ctx.clock.millis() + SECONDS * 1_000L
        ctx.punish(player, until, PunishmentCategory.CREATURES)
        ctx.guards.armDeathGuard(player.uuid, SECONDS)
        ModuleCreatures.aim(mob, player, System.currentTimeMillis() + SECONDS * 1_000L)
        mob.target = player
        if (mob.target !== player) refuse(ToolResult.failed("the $type would not take ${player.shownName} as its target"))
        return ToolResult.ok(
            "ok: the $type goes for ${player.shownName} until ${ctx.at(until)} (in ${ToolText.duration(SECONDS * 1_000L)}); the death guard watches over them",
            listOf(player.ref()),
        )
    }

    companion object {
        /** How long a creature may go for the player of one attack, and the death guard watches over them, in seconds. */
        const val SECONDS = 60
    }
}
