package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.EffectLedger
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.TargetingPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.guard.clockTime
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.tool.speech.closestIds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Actions on players (M1_M2 §9.3): `apply_effect`, `remove_effect`, `set_attribute`, `reset_attribute`,
 * `teleport`, `launch`, `give_item`, `take_item`. `ToolCatalog` builds the family once per conversation;
 * the [GiftLimiter] is the server's, shared by every conversation.
 *
 * Every tool follows the guards' order (`handoff/T09a.md`): arguments and the caps of the chaos level
 * first, then `Guards.hostile` for a hostile action (protection, chaos 0, no piling on, one punishment at
 * a time, fairness), then the death guard for a harmful change and any teleport, armed for what it can
 * kill with (`Peril`), then the change itself through a ledger, so that it is undone when due. Whether an
 * action is hostile is decided here from what it does, never declared by the model:
 * - a harmful effect, an attribute moved the wrong way, a teleport of more than 32 blocks, a launch, a
 *   confiscation (M1_M2 §10.3);
 * - also, beyond the design: taking away a beneficial effect or a beneficial attribute change (the fire
 *   resistance of a player in lava, the fall protection of a launched one), which can kill as surely, and
 *   replacing a beneficial effect by a shorter or weaker one (M2 review, finding 2).
 */
fun playerActionTools(services: NarratorServices): List<NarratorTool<*>> = listOf(
    ApplyEffectTool(),
    RemoveEffectTool(),
    SetAttributeTool(),
    ResetAttributeTool(),
    TeleportTool(),
    LaunchTool(),
    GiveItemTool(GiftLimiter.of(services)),
    TakeItemTool(),
)

private const val PLAYER_DOC = "Name of the online player."
private const val PLAYERS_DOC = "Names of the online players."

// ── apply_effect ────────────────────────────────────────────────────────────────────────────────

@Serializable
data class ApplyEffectArgs(
    @Doc(PLAYERS_DOC)
    @Items(1, 8)
    @MaxLen(16)
    val players: List<String>,
    @Doc(
        "Effect id, e.g. speed, slowness, jump_boost, levitation, glowing, nausea, darkness, night_vision, " +
            "slow_falling, instant_health; the minecraft: namespace is optional.",
    )
    @MaxLen(64)
    val effect: String,
    @Doc("How long, in seconds. Ignored by the instant effects (instant_health, instant_damage, saturation), applied once.")
    @Range(1.0, 900.0)
    val seconds: Int,
    @Doc("Level, 1 being the effect's first level.")
    @Range(1.0, 5.0)
    val level: Int = 1,
    @Doc("Show the effect's particles around the players.")
    val particles: Boolean = true,
)

class ApplyEffectTool : NarratorTool<ApplyEffectArgs>(
    name = "apply_effect",
    description = "Puts a status effect on players for a while, replacing the one of that kind they have. A harmful effect " +
        "is a punishment: refused for a protected player, one who died a few minutes ago or one already punished by an " +
        "earlier turn, and capped in length and level by the chaos level. A beneficial effect never replaces a longer and " +
        "stronger one the player has; replacing one that is longer or stronger takes something away, so it is a " +
        "punishment too. Resistance goes up to level 3. Effects that act on the world (omens, infested, oozing, weaving, " +
        "wind_charged) are never given.",
    args = ApplyEffectArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: ApplyEffectArgs): ToolResult = answer {
        val targets = ctx.online(args.players)
        val effect = Effects.lookup(args.effect)
        val id = shortId(effect)
        Effects.forbidden(effect)?.let { refuse(ToolResult.denied(Effects.FORBIDDEN_EFFECT, "$id is never given: $it; the Narrator's effects never act on the world.")) }
        val harmful = effect.value().category == MobEffectCategory.HARMFUL
        val instant = effect.value().isInstantaneous
        val caps = ctx.guards.caps()
        val (maxSeconds, maxLevel) = if (harmful) caps.harmfulSeconds to caps.harmfulLevel else Caps.BENEFICIAL_SECONDS to Caps.BENEFICIAL_LEVEL
        // At chaos 0 a harmful effect is refused by the guards, with their rule.
        if ((!harmful || caps.hostileAllowed) && ((!instant && args.seconds > maxSeconds) || args.level > maxLevel)) {
            val what = if (harmful) "$id is harmful: at chaos ${ctx.chaos} it" else "an effect"
            refuse(
                ToolResult.invalid(
                    if (instant) {
                        "$what goes up to level $maxLevel (asked level ${args.level})"
                    } else {
                        "$what lasts at most $maxSeconds s and goes up to level $maxLevel (asked ${args.seconds} s, level ${args.level})"
                    },
                ),
            )
        }
        if (effect.value() === MobEffects.RESISTANCE.value() && args.level > Effects.MAX_RESISTANCE_LEVEL) {
            refuse(
                ToolResult.invalid(
                    "resistance goes up to level ${Effects.MAX_RESISTANCE_LEVEL}: level 4 makes a player nearly immune to damage, " +
                        "level 5 wholly (asked level ${args.level})",
                ),
            )
        }
        val shown = "$id ${roman(args.level)}"
        val now = ctx.clock.millis()
        val seconds = if (instant) Effects.INSTANT_SECONDS else args.seconds
        val until = now + seconds * 1_000L
        val outcomes = ctx.onServer { server ->
            targets.map { ref ->
                outcome(ref) {
                    val player = server.live(ref)
                    var replaced = ""
                    if (harmful) {
                        ctx.punish(player, until)
                        // The effect's own length and 30 s more: a levitation's fall outlasts it (M2 review, finding 1).
                        Effects.perilOf(effect)?.let { ctx.guards.armDeathGuard(player.uuid, seconds + Effects.GUARD_MARGIN_SECONDS, it) }
                    } else {
                        player.requireAlive()
                        if (!instant) replaced = ctx.replacing(player, ref, effect, args.level, args.seconds)
                    }
                    if (instant) {
                        effect.value().applyInstantaneousEffect(player.level(), null, null, player, args.level - 1, 1.0)
                        "${ref.name} (health ${health(player)})"
                    } else {
                        ctx.guards.effects.apply(player, effect, args.level, args.seconds, args.particles, ctx.caller.turnId)
                        ref.name + replaced
                    }
                }
            }
        }
        report(outcomes) { done ->
            val names = done.joinToString { it.done!! }
            if (instant) "ok: $shown on $names" else "ok: $names ${if (done.size == 1) "gets" else "get"} $shown for ${args.seconds} s (until ${ctx.at(until)})"
        }
    }
}

/** Effects the tools recognize, forbid and describe. */
private object Effects {
    /** The rule of a refused effect: `DENIED (forbidden_effect): …`. */
    const val FORBIDDEN_EFFECT = "forbidden_effect"

    /** An instant effect is a punishment for this long, and the death guard covers it. */
    const val INSTANT_SECONDS = 5

    /**
     * The death guard of a harmful effect lasts this much longer than the effect: at least the 30 s the
     * M2 review asks for (finding 1), since a levitation's fall outlasts it.
     */
    const val GUARD_MARGIN_SECONDS = 30

    /** Resistance IV takes 80 % of the damage away, V all of it (M2 review, finding 13). */
    const val MAX_RESISTANCE_LEVEL = 3

    /**
     * What an effect can kill with, when the Narrator gives it (a harmful one) or takes it away (a
     * beneficial one): the peril its death guard covers. Those left out weaken the player against
     * everything ([Peril.ANY]); null for those that cannot kill.
     */
    private val PERILS: Map<String, Peril?> = mapOf(
        "minecraft:levitation" to Peril.FALL,
        "minecraft:slow_falling" to Peril.FALL,
        "minecraft:jump_boost" to Peril.FALL,
        "minecraft:wither" to Peril.MAGIC,
        "minecraft:poison" to Peril.MAGIC,
        "minecraft:instant_damage" to Peril.MAGIC,
        "minecraft:hunger" to Peril.HUNGER,
        "minecraft:fire_resistance" to Peril.FIRE,
        "minecraft:water_breathing" to Peril.DROWNING,
        "minecraft:conduit_power" to Peril.DROWNING,
        "minecraft:dolphins_grace" to Peril.DROWNING,
        "minecraft:breath_of_the_nautilus" to Peril.DROWNING,
        "minecraft:luck" to null,
        "minecraft:unluck" to null,
        "minecraft:hero_of_the_village" to null,
        "minecraft:glowing" to null,
    )

    /** The peril [effect]'s death guard covers; null when it cannot kill. */
    fun perilOf(effect: Holder<MobEffect>): Peril? = if (effect.registeredName in PERILS) PERILS[effect.registeredName] else Peril.ANY

    /**
     * Effects never given, with why: they act on the world (M1_M2 §9.3; `handoff/T09b.md` for the last
     * four, checked in the effect classes of 26.3: only these override what happens when the bearer is
     * hurt or removed, or start raids and ominous trials).
     */
    private val FORBIDDEN = mapOf(
        "minecraft:bad_omen" to "it turns into a raid at the next village",
        "minecraft:raid_omen" to "it starts a raid",
        "minecraft:trial_omen" to "it turns trial spawners ominous",
        "minecraft:infested" to "it spawns silverfish, which burrow into stone, whenever the player is hurt",
        "minecraft:oozing" to "it spawns slimes when the player dies",
        "minecraft:weaving" to "it places cobwebs when the player dies",
        "minecraft:wind_charged" to "it bursts a wind charge when the player dies, which works doors, levers and buttons",
    )

    /** The effect called [raw]; refuses with the closest ids otherwise. */
    fun lookup(raw: String): Holder.Reference<MobEffect> =
        parseId(raw)?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) }
            ?: refuse(ToolResult.invalid("unknown effect \"$raw\"; closest ids: ${closestIds(raw, BuiltInRegistries.MOB_EFFECT.keySet().map(::shortId)).joinToString()}"))

    /** Why [effect] is never given, or null. */
    fun forbidden(effect: Holder<MobEffect>): String? = FORBIDDEN[effect.registeredName]

    /** The ids of the forbidden effects. */
    val forbiddenIds: Set<String> get() = FORBIDDEN.keys
}

/** The ids of the effects `apply_effect` never gives. */
val forbiddenEffectIds: Set<String> get() = Effects.forbiddenIds

// ── remove_effect ───────────────────────────────────────────────────────────────────────────────

@Serializable
data class RemoveEffectArgs(
    @Doc(PLAYERS_DOC)
    @Items(1, 8)
    @MaxLen(16)
    val players: List<String>,
    @Doc(
        "Effect id to remove, whoever put it there (a potion, a beacon, a mob, the Narrator); or all_narrator for every " +
            "effect the Narrator put on them, and only those.",
    )
    @MaxLen(64)
    val effect: String,
)

class RemoveEffectTool : NarratorTool<RemoveEffectArgs>(
    name = "remove_effect",
    description = "Removes a status effect from players, or every effect the Narrator put on them (all_narrator). Lifting " +
        "a harmful effect is a mercy; taking away a beneficial one (fire resistance, water breathing, slow falling) " +
        "is a punishment, refused like any other when the player may not be punished.",
    args = RemoveEffectArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: RemoveEffectArgs): ToolResult = answer {
        val targets = ctx.online(args.players)
        val all = args.effect.trim().equals(ALL_NARRATOR, ignoreCase = true)
        val only = if (all) null else Effects.lookup(args.effect)
        val until = ctx.clock.millis() + REMOVAL_GUARD_SECONDS * 1_000L
        val outcomes = ctx.onServer { server ->
            targets.map { ref ->
                outcome(ref) {
                    val player = server.live(ref)
                    val removed = if (only == null) {
                        ctx.guards.effects.on(player).mapNotNull { EffectLedger.holderOf(it) }
                    } else {
                        listOfNotNull(only.takeIf { player.hasEffect(it) })
                    }
                    if (removed.isEmpty()) return@outcome ""
                    val shown = removed.map { effect -> "${shortId(effect)} ${roman(player.getEffect(effect)!!.amplifier + 1)}" }
                    val benefits = removed.filter { it.value().category == MobEffectCategory.BENEFICIAL }
                    if (benefits.isNotEmpty()) {
                        ctx.punish(player, until)
                        benefits.mapNotNull { Effects.perilOf(it) }.distinct().forEach { ctx.guards.armDeathGuard(player.uuid, REMOVAL_GUARD_SECONDS, it) }
                    }
                    if (only == null) ctx.guards.effects.removeAll(player) else ctx.guards.effects.remove(player, only)
                    "${shown.joinToString()} from ${ref.name}"
                }
            }
        }
        val acted = outcomes.filter { it.refusal != null || it.done!!.isNotEmpty() }
        if (acted.isEmpty()) {
            val whom = targets.joinToString { it.name }
            return@answer if (only == null) {
                ToolResult.ok("ok: nothing to remove: the Narrator has no effect on $whom", targets)
            } else {
                ToolResult.invalid("$whom ${if (targets.size == 1) "does" else "do"} not have ${shortId(only)}; ${ctx.effectsOf(targets)}")
            }
        }
        val untouched = outcomes.filter { it.refusal == null && it.done!!.isEmpty() }.map { it.ref.name }
        report(acted) { done ->
            "ok: removed ${done.joinToString("; ") { it.done!! }}" + (if (untouched.isEmpty()) "" else "; nothing on ${untouched.joinToString()}")
        }
    }

    /** The effects [players] have now, for an error message. */
    private suspend fun ToolContext.effectsOf(players: List<PlayerRef>): String = onServer { server ->
        players.joinToString("; ") { ref ->
            val effects = server.playerList.getPlayer(ref.uuid)?.activeEffects.orEmpty()
                .map { "${shortId(it.effect)} ${roman(it.amplifier + 1)}" }
            "${ref.name} has ${effects.joinToString().ifEmpty { "no effect" }}"
        }
    }

    private companion object {
        const val ALL_NARRATOR = "all_narrator"
    }
}

/**
 * Taking back a benefit (an effect or an attribute change in the player's favor) is a punishment for this
 * long, under the death guard: a player in lava, under water or in the air may die of it.
 */
private const val REMOVAL_GUARD_SECONDS = 30

private const val TICKS_PER_SECOND = 20

/**
 * A beneficial [effect] at [level] for [seconds], given to [player] who may already have it (M2 review,
 * finding 2): the Narrator's 30 seconds of fire resistance must not replace a potion of 8 minutes in the
 * middle of the Nether's lava, even for a protected player.
 * - what they have is at least as long and as strong: refused, there is nothing to give;
 * - it is longer, or stronger: the call takes that away, so it is a punishment like `remove_effect`, and
 *   the death guard covers what the effect protected from until the new one has ended, and 30 s more.
 *
 * Returns what the answer says of the effect replaced, "" when nothing is taken away. Server thread.
 */
private fun ToolContext.replacing(player: ServerPlayer, ref: PlayerRef, effect: Holder<MobEffect>, level: Int, seconds: Int): String {
    val had = player.getEffect(effect) ?: return ""
    val ticks = seconds * TICKS_PER_SECOND
    val longer = !had.isInfiniteDuration && ticks > had.duration
    val stronger = level - 1 > had.amplifier
    val shorter = had.isInfiniteDuration || ticks < had.duration
    val weaker = level - 1 < had.amplifier
    val lasting = if (had.isInfiniteDuration) "with no end" else "for ${TargetingPolicy.span(had.duration * 1_000L / TICKS_PER_SECOND)}"
    val what = "${shortId(effect)} ${roman(had.amplifier + 1)} $lasting"
    if (!longer && !stronger) refuse(ToolResult.invalid("${ref.name} already has $what"))
    if (!shorter && !weaker) return ""
    punish(player, clock.millis() + REMOVAL_GUARD_SECONDS * 1_000L)
    Effects.perilOf(effect)?.let { guards.armDeathGuard(player.uuid, seconds + REMOVAL_GUARD_SECONDS, it) }
    return " (in place of $what: a punishment)"
}

// ── set_attribute ───────────────────────────────────────────────────────────────────────────────

@Serializable
data class SetAttributeArgs(
    @Doc(PLAYER_DOC)
    @MaxLen(16)
    val player: String,
    @Doc(
        "The attribute: scale (size), gravity, jump_strength, movement_speed, block_break_speed, safe_fall_distance, " +
            "step_height, block/entity_interaction_range (reach), mining_efficiency, luck, max_health, attack_speed, " +
            "knockback_resistance, oxygen_bonus, burning_time, fall_damage_multiplier, bounciness, friction_modifier, " +
            "air_drag_modifier, sneaking_speed.",
    )
    val attribute: AttributeKey,
    @Doc("Multiplies the attribute's value: 0.5 halves it, 2 doubles it. Give this or add, not both.")
    @Range(0.0, 20.0)
    val multiply: Double? = null,
    @Doc("Adds to the attribute's value, negative to lower it. Give this or multiply, not both.")
    @Range(-100.0, 100.0)
    val add: Double? = null,
    @Doc("How long it lasts, in seconds; then the attribute comes back by itself, across deaths and restarts.")
    @Range(10.0, 900.0)
    val seconds: Int,
) {
    init {
        require((multiply == null) != (add == null)) { "give exactly one of multiply or add" }
    }
}

class SetAttributeTool : NarratorTool<SetAttributeArgs>(
    name = "set_attribute",
    description = "Changes one attribute of a player for a while (a tiny or giant player, moon gravity, clumsy hands…), " +
        "replacing the Narrator's earlier change of that attribute. The resulting value must stay within the attribute's " +
        "bounds. A change in the player's disfavor (a jump_strength above 0.6 too: every jump then hurts) is a punishment: " +
        "refused like any other when the player may not be punished, and capped in length by the chaos level.",
    args = SetAttributeArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: SetAttributeArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        val key = args.attribute
        val (amount, operation) =
            if (args.multiply != null) args.multiply - 1 to AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL else args.add!! to AttributeModifier.Operation.ADD_VALUE
        val caps = ctx.guards.caps()
        ctx.onServer { server ->
            val player = server.live(ref)
            val ledger = ctx.guards.attributes
            val current = ledger.value(player, key)
            val before = ledger.valueWith(player, key, 0.0, AttributeModifier.Operation.ADD_VALUE)
            val after = ledger.valueWith(player, key, amount, operation)
            key.outOfBounds(after)?.let { refuse(ToolResult.invalid(it)) }
            val harmful = key.harmful(before, after)
            val maxSeconds = if (harmful) caps.harmfulSeconds else Caps.BENEFICIAL_SECONDS
            if ((!harmful || caps.hostileAllowed) && args.seconds > maxSeconds) {
                refuse(ToolResult.invalid("this change of ${key.id} is in ${ref.name}'s disfavor: at chaos ${ctx.chaos} it lasts at most $maxSeconds s (asked ${args.seconds} s)"))
            }
            val until = ctx.clock.millis() + args.seconds * 1_000L
            // Only a change in the player's disfavor arms the death guard (M2 review, finding 11), for
            // what it can kill with, until 30 s after it ends (a gravity that comes back mid-jump).
            if (harmful) {
                ctx.punish(player, until)
                key.peril?.let { ctx.guards.armDeathGuard(player.uuid, args.seconds + Effects.GUARD_MARGIN_SECONDS, it) }
            } else {
                player.requireAlive()
            }
            val value = ledger.apply(player, key, amount, operation, until, harmful, ctx.caller.turnId)
            ToolResult.ok("ok: ${ref.name}'s ${key.id} ${number(current)} → ${number(value)} for ${args.seconds} s (until ${ctx.at(until)})", listOf(ref))
        }
    }
}

// ── reset_attribute ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ResetAttributeArgs(
    @Doc(PLAYER_DOC)
    @MaxLen(16)
    val player: String,
    @Doc("The attribute to put back; leave it out to put back every attribute the Narrator changed on this player.")
    val attribute: AttributeKey? = null,
)

class ResetAttributeTool : NarratorTool<ResetAttributeArgs>(
    name = "reset_attribute",
    description = "Puts back, before their time, the attributes the Narrator changed on a player: one, or all of them. " +
        "Lifting a change in the player's disfavor is a mercy; taking back one in their favor (the fall protection of a " +
        "launch, a bonus of health) is a punishment, refused like any other when the player may not be punished.",
    args = ResetAttributeArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: ResetAttributeArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        ctx.onServer { server ->
            val player = server.live(ref)
            val ledger = ctx.guards.attributes
            val changes = ledger.on(player).filter { args.attribute == null || it.attribute == args.attribute.id }
            if (changes.isEmpty()) {
                if (args.attribute == null) {
                    return@onServer ToolResult.ok("ok: nothing to put back: the Narrator changed none of ${ref.name}'s attributes", listOf(ref))
                }
                val all = ledger.on(player).joinToString { AttributeLedger.describe(it) }.ifEmpty { "none" }
                refuse(ToolResult.invalid("the Narrator did not change ${ref.name}'s ${args.attribute.id}; its changes on ${ref.name}: $all"))
            }
            val favors = changes.filter { !it.harmful }
            if (favors.isNotEmpty()) {
                ctx.punish(player, ctx.clock.millis() + REMOVAL_GUARD_SECONDS * 1_000L)
                favors.mapNotNull { AttributeLedger.keyOf(it)?.peril }.distinct().forEach { ctx.guards.armDeathGuard(player.uuid, REMOVAL_GUARD_SECONDS, it) }
            }
            val back = changes.mapNotNull { AttributeLedger.keyOf(it) }.map { key ->
                val before = ledger.value(player, key)
                ledger.reset(player, key)
                "${key.id} ${number(before)} → ${number(ledger.value(player, key))}"
            }
            ToolResult.ok("ok: put back ${ref.name}'s ${back.joinToString()}", listOf(ref))
        }
    }
}

// ── teleport ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class TeleportArgs(
    @Doc("Name of the online player to move.")
    @MaxLen(16)
    val player: String,
    @Doc("Name of an online player, in the same dimension, to bring them next to. Give this, or x and z.")
    @SerialName("to_player")
    @MaxLen(16)
    val toPlayer: String? = null,
    @Doc("Destination block x, in the player's own dimension; with z.")
    @Range(-30_000_000.0, 30_000_000.0)
    val x: Int? = null,
    @Doc("Destination block z; with x.")
    @Range(-30_000_000.0, 30_000_000.0)
    val z: Int? = null,
    @Doc("With x and z: the height to look for a landing from, downwards first. Left out: from the surface.")
    @Range(-2_048.0, 2_048.0)
    val y: Int? = null,
) {
    init {
        require((toPlayer != null) != (x != null || z != null)) { "give to_player, or x and z" }
        require((x == null) == (z == null)) { "give both x and z" }
        require(y == null || x != null) { "y goes with x and z" }
    }
}

class TeleportTool : NarratorTool<TeleportArgs>(
    name = "teleport",
    description = "Moves a player, within their own dimension, next to another player or to a column x, z: they land on " +
        "safe ground (never in lava, water, a wall or the void), or nothing happens. A protected player is never moved. " +
        "Taking them more than 32 blocks away is a punishment, refused like any other when the player may not be punished, " +
        "and its distance is capped by the chaos level. Slow: the destination loads first.",
    args = TeleportArgs.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = 12_000,
) {
    /** The column to land in, and its loading. */
    private class Destination(val level: ServerLevel, val x: Int, val z: Int, val fromY: Int?, val near: String?, val loading: CompletableFuture<*>)

    override suspend fun run(ctx: ToolContext, args: TeleportArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        val toRef = args.toPlayer?.let { ctx.online(it) }
        if (toRef?.uuid == ref.uuid) refuse(ToolResult.invalid("${ref.name} cannot be teleported to themselves"))
        val caps = ctx.guards.caps()
        val destination = ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            // Near or far, a protected player stays where they are (M2 review, finding 12).
            if (ctx.guards.isProtected(ref.uuid)) {
                refuse(ToolResult.denied(Rules.PROTECTED, "${ref.name} is protected by the operators; the Narrator never teleports them."))
            }
            val level = player.level()
            val (x, z, fromY) = if (toRef != null) {
                val other = server.live(toRef)
                if (other.level().dimension() != level.dimension()) {
                    refuse(ToolResult.invalid("${toRef.name} is in ${dimension(other.level())} and ${ref.name} in ${dimension(level)}; a teleport stays in the player's own dimension"))
                }
                Triple(other.blockX, other.blockZ, other.blockY)
            } else {
                Triple(args.x!!, args.z!!, args.y)
            }
            if (fromY != null && level.isOutsideBuildHeight(fromY)) {
                refuse(ToolResult.invalid("y must be between ${level.minY} and ${level.maxY} in ${dimension(level)}"))
            }
            if (!level.worldBorder.isWithinBounds(x + 0.5, z + 0.5)) refuse(ToolResult.invalid("x $x, z $z is beyond the world border"))
            val far = hypot(player.x - (x + 0.5), player.z - (z + 0.5))
            if (far > Caps.HOSTILE_TELEPORT_BLOCKS) {
                // Certainly a punishment: refused before loading anything when it cannot be one.
                if (!caps.hostileAllowed || ctx.guards.isProtected(ref.uuid)) ctx.punish(player, ctx.clock.millis())
                if (far > caps.teleportBlocks) refuse(tooFar(ctx, far, caps))
            }
            Destination(level, x, z, fromY, toRef?.name, Teleports.requestLoad(level, x, z))
        }
        if (!Teleports.awaitLoaded(destination.loading)) {
            refuse(ToolResult.failed("the destination x ${destination.x}, z ${destination.z} did not load within ${Teleports.LOAD_TIMEOUT_MS / 1_000} s; nothing happened"))
        }
        ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            if (player.level() !== destination.level) refuse(ToolResult.failed("${ref.name} changed dimension meanwhile; nothing happened"))
            val spot = Teleports.landing(destination.level, player, destination.x, destination.z, destination.fromY)
                ?: refuse(
                    ToolResult.failed(
                        "no safe landing at x ${destination.x}, z ${destination.z} (only liquid, danger or no room to stand in that column); " +
                            "nothing happened, try another spot",
                    ),
                )
            val distance = player.position().distanceTo(spot)
            if (distance > Caps.HOSTILE_TELEPORT_BLOCKS) {
                if (caps.hostileAllowed && distance > caps.teleportBlocks) refuse(tooFar(ctx, distance, caps))
                ctx.punish(player, ctx.clock.millis() + TELEPORT_PUNISHMENT_SECONDS * 1_000L)
            }
            // The landing is safe, what is around it may not be (M2 review, finding 12).
            ctx.guards.armDeathGuard(player.uuid, TELEPORT_GUARD_SECONDS, Peril.ANY)
            if (!Teleports.move(player, spot)) refuse(ToolResult.failed("the game refused to move ${ref.name}"))
            val near = destination.near?.let { " next to $it" } ?: ""
            ToolResult.ok(
                "ok: ${ref.name} teleported$near, ${number(distance)} blocks away, to x ${number(spot.x)}, y ${number(spot.y)}, z ${number(spot.z)}",
                listOf(ref),
            )
        }
    }

    private fun tooFar(ctx: ToolContext, distance: Double, caps: Caps) =
        ToolResult.invalid("at chaos ${ctx.chaos} a teleport goes at most ${caps.teleportBlocks} blocks (this one: ${number(distance)})")

    private companion object {
        /** A far teleport is a punishment for this long: no other turn piles on the stranded player. */
        const val TELEPORT_PUNISHMENT_SECONDS = 60

        /** Any teleport arms the death guard this long. */
        const val TELEPORT_GUARD_SECONDS = 30
    }
}

// ── launch ──────────────────────────────────────────────────────────────────────────────────────

/** Where `launch` sends a player; the horizontal ones are relative to where they look. */
@Serializable
enum class LaunchDirection {
    @SerialName("up") UP,
    @SerialName("forward") FORWARD,
    @SerialName("backward") BACKWARD,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("random") RANDOM,
}

@Serializable
data class LaunchArgs(
    @Doc(PLAYER_DOC)
    @MaxLen(16)
    val player: String,
    @Doc("up; or forward, backward, left, right from where the player looks, with some lift; or random.")
    val direction: LaunchDirection,
    @Doc("How hard: 1 is a strong shove, 2 sends them flying.")
    @Range(0.5, 3.0)
    val strength: Double,
)

class LaunchTool : NarratorTool<LaunchArgs>(
    name = "launch",
    description = "Throws a player into the air or sideways. They take no fall damage for the next 15 seconds. Always a " +
        "punishment: refused like any other when the player may not be punished; its strength is capped by the chaos level.",
    args = LaunchArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: LaunchArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        val caps = ctx.guards.caps()
        if (caps.hostileAllowed && args.strength > caps.launchStrength) {
            refuse(ToolResult.invalid("at chaos ${ctx.chaos} a launch has a strength of at most ${number(caps.launchStrength)} (asked ${number(args.strength)})"))
        }
        ctx.onServer { server ->
            val player = server.live(ref)
            val until = ctx.clock.millis() + LAUNCH_SECONDS * 1_000L
            ctx.punish(player, until)
            ctx.guards.armDeathGuard(player.uuid, LAUNCH_SECONDS, Peril.FALL)
            // The fall protection goes through the ledger: it ends by itself, even after a restart.
            ctx.guards.attributes.apply(
                player, AttributeKey.FALL_DAMAGE_MULTIPLIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, until,
                harmful = false, turnId = ctx.caller.turnId,
            )
            player.stopRiding()
            player.deltaMovement = launchVelocity(args.direction, args.strength, player.yRot, player.random.nextDouble() * 2 * Math.PI)
            // Sent to the player's own client at the next entity tracking tick: only that client moves them.
            player.syncVelocity = true
            ToolResult.ok(
                "ok: ${ref.name} launched ${args.direction.name.lowercase()} at strength ${number(args.strength)}; no fall damage until ${ctx.at(until)}",
                listOf(ref),
            )
        }
    }

    private companion object {
        /** The fall protection and the death guard of a launch (M1_M2 §9.3). */
        const val LAUNCH_SECONDS = 15
    }
}

/** Upward share of a sideways launch: without it, the ground's friction stops the player at once. */
private const val LAUNCH_LIFT = 0.5

/**
 * The velocity of a launch, in blocks per tick: straight [UP][LaunchDirection.UP], or [strength]
 * horizontally from the player's facing [yawDegrees] (random: [randomAngle] radians) with some lift.
 */
internal fun launchVelocity(direction: LaunchDirection, strength: Double, yawDegrees: Float, randomAngle: Double): Vec3 {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    // The game's yaw: 0 faces south (+z), 90 west (-x).
    val horizontal = when (direction) {
        LaunchDirection.UP -> return Vec3(0.0, strength, 0.0)
        LaunchDirection.FORWARD -> Vec3(-sin(yaw), 0.0, cos(yaw))
        LaunchDirection.BACKWARD -> Vec3(sin(yaw), 0.0, -cos(yaw))
        LaunchDirection.LEFT -> Vec3(cos(yaw), 0.0, sin(yaw))
        LaunchDirection.RIGHT -> Vec3(-cos(yaw), 0.0, -sin(yaw))
        LaunchDirection.RANDOM -> Vec3(cos(randomAngle), 0.0, sin(randomAngle))
    }
    return horizontal.scale(strength).add(0.0, strength * LAUNCH_LIFT, 0.0)
}

// ── give_item ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class GiveItemArgs(
    @Doc(PLAYER_DOC)
    @MaxLen(16)
    val player: String,
    @Doc("Item id, e.g. diamond, golden_apple, iron_sword, cookie, enchanted_book; the minecraft: namespace is optional.")
    @MaxLen(64)
    val item: String,
    @Doc("How many; at most one stack of that item.")
    @Range(1.0, 64.0)
    val count: Int = 1,
    @Doc("A custom name, shown in white.")
    @MaxLen(40)
    val name: String? = null,
    @Doc("Lines of description under the name.")
    @Items(1, 4)
    @MaxLen(60)
    val lore: List<String>? = null,
    @Doc("Enchantments; an enchanted_book stores them for the anvil.")
    @Items(1, 8)
    val enchantments: List<EnchantmentSpec>? = null,
    @Doc("The item never wears out.")
    val unbreakable: Boolean = false,
)

class GiveItemTool(private val limiter: GiftLimiter) : NarratorTool<GiveItemArgs>(
    name = "give_item",
    description = "Gives an item to a player, possibly named, described, enchanted or unbreakable: a reward, a gag, a " +
        "cursed trinket. It goes into their inventory, or at their feet when it is full. Operator items, unbreakable " +
        "blocks, spawners and spawn eggs are never given. At most 20 gifts per player per hour, and few precious items: " +
        "the Narrator slows progression down (4 diamonds an hour, or a single piece of netherite, diamond gear, elytra, " +
        "totem, enchanted golden apple, beacon, shulker box…).",
    args = GiveItemArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: GiveItemArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            val spec = ItemSpecs.resolve(
                server.registryAccess(), player.level().enabledFeatures(),
                args.item, args.count, args.name, args.lore, args.enchantments, args.unbreakable,
            )
            val points = ItemSpecs.preciousness(spec.item) * spec.count
            limiter.take(ref, points, "${spec.count}x ${shortId(spec.item)}").let { if (it is Verdict.Denied) refuse(ToolResult.denied(it)) }
            val dropped = give(player, ItemSpecs.build(spec, ctx.caller.turnId))
            val where = if (dropped) "dropped at ${ref.name}'s feet (inventory full)" else "to ${ref.name}"
            ToolResult.ok("ok: gave ${ItemSpecs.describe(spec)} $where", listOf(ref))
        }
    }

    /** Puts [stack] in [player]'s inventory, or at their feet when it is full, as `/give` does. Returns true when dropped. */
    private fun give(player: ServerPlayer, stack: ItemStack): Boolean {
        val rest = stack.copy()
        ItemLedger.insert(player.inventory, rest)
        val dropped = !rest.isEmpty
        if (dropped) {
            player.createItemStackToDrop(rest, false, false)?.let { item ->
                item.setNoPickUpDelay()
                item.setTarget(player.uuid)
                player.level().addFreshEntity(item)
            }
        } else {
            val pitch = ((player.random.nextFloat() - player.random.nextFloat()) * 0.7f + 1f) * 2f
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, pitch)
        }
        player.containerMenu.broadcastChanges()
        return dropped
    }
}

/**
 * `give_item`'s limits, whatever the model asks: [GIFTS_PER_HOUR] gifts per player in any hour, and
 * [PRECIOUS_POINTS_PER_HOUR] points of precious items ([ItemSpecs.preciousness]): the Narrator slows
 * progression down (M2 review, finding 13). A gift is counted when it is allowed; a refusal counts
 * nothing. One limiter per server ([of]), so a new chapter does not reset the counts; in memory only.
 * Any thread.
 */
class GiftLimiter(private val clock: Clock) {
    private val gifts = HashMap<UUID, ArrayDeque<Long>>()

    /** When each precious point was given, per player: one entry per point. */
    private val precious = HashMap<UUID, ArrayDeque<Long>>()

    /**
     * One gift to [player], [what] (`3x diamond`), worth [points] precious points (0 for an ordinary
     * item): allowed and counted, or refused with when it is possible.
     */
    @Synchronized
    fun take(player: PlayerRef, points: Int = 0, what: String = "this gift"): Verdict {
        val now = clock.millis()
        val times = lastHour(gifts, player, now)
        if (times.size >= GIFTS_PER_HOUR) {
            val next = times.first() + HOUR_MS
            return Verdict.Denied(
                RULE,
                "${player.name} got $GIFTS_PER_HOUR gifts in the last hour, the most a player may get; the next is possible at " +
                    "${clockTime(clock, next)} (in ${TargetingPolicy.span(next - now)})",
            )
        }
        val spent = lastHour(precious, player, now)
        if (points > 0 && spent.size + points > PRECIOUS_POINTS_PER_HOUR) {
            val why = if (points > PRECIOUS_POINTS_PER_HOUR) {
                "$what is worth $points precious points, more than the $PRECIOUS_POINTS_PER_HOUR a player may get in an hour"
            } else {
                // The points leave the hour oldest first: enough have left once this one has.
                val next = spent[spent.size + points - PRECIOUS_POINTS_PER_HOUR - 1] + HOUR_MS
                "${player.name} got ${spent.size} of the $PRECIOUS_POINTS_PER_HOUR precious points a player may get in an hour, and " +
                    "$what is worth $points; it is possible at ${clockTime(clock, next)} (in ${TargetingPolicy.span(next - now)})"
            }
            return Verdict.Denied(
                PRECIOUS_RULE,
                "$why. The Narrator slows progression down: a diamond or a golden apple is 1 point; netherite, diamond gear, elytra, " +
                    "totems, enchanted golden apples, beacons, shulker boxes and the like are $PRECIOUS_POINTS_PER_HOUR.",
            )
        }
        repeat(points) { spent.addLast(now) }
        times.addLast(now)
        return Verdict.Allowed
    }

    /** [player]'s entries of [log] within the last hour at [now], the older ones dropped. */
    private fun lastHour(log: HashMap<UUID, ArrayDeque<Long>>, player: PlayerRef, now: Long): ArrayDeque<Long> =
        log.getOrPut(player.uuid) { ArrayDeque() }.also { while (it.isNotEmpty() && now - it.first() >= HOUR_MS) it.removeFirst() }

    companion object {
        /** The rule of the limit: `DENIED (gift_limit): …`. */
        const val RULE = "gift_limit"

        /** Gifts to one player in any hour, at most (M1_M2 §9.3). */
        const val GIFTS_PER_HOUR = 20

        /** The rule of the precious items: `DENIED (precious_limit): …`. */
        const val PRECIOUS_RULE = "precious_limit"

        /** Points of precious items one player may get in any hour ([ItemSpecs.preciousness]). */
        const val PRECIOUS_POINTS_PER_HOUR = 4

        private const val HOUR_MS = 3_600_000L

        private val byServer = WeakHashMap<NarratorServices, GiftLimiter>()

        /** The limiter of the server [services] belong to: the same for every conversation, dropped with the server. */
        @Synchronized
        fun of(services: NarratorServices): GiftLimiter = byServer.getOrPut(services) { GiftLimiter(services.clock) }
    }
}

// ── take_item ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class TakeItemArgs(
    @Doc(PLAYER_DOC)
    @MaxLen(16)
    val player: String,
    @Doc("Item id (diamond, iron_pickaxe), or an item tag starting with # for any item of the tag (#logs, #swords).")
    @MaxLen(64)
    val item: String,
    @Doc("How many items at most; fewer when the player has fewer. Capped by the chaos level.")
    @Range(1.0, 128.0)
    val count: Int,
    @Doc("When they come back, in minutes: they come back by themselves, into the inventory.")
    @Range(1.0, 30.0)
    val minutes: Int,
    @Doc("Why, in a few words, for the record.")
    @MaxLen(80)
    val reason: String? = null,
)

class TakeItemTool : NarratorTool<TakeItemArgs>(
    name = "take_item",
    description = "Confiscates items from a player's inventory (armor and off hand included) for some minutes: they come " +
        "back by themselves, and nothing is ever lost. Always a punishment: refused like any other when the player may not " +
        "be punished; the number of items is capped by the chaos level.",
    args = TakeItemArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: TakeItemArgs): ToolResult = answer {
        val ref = ctx.online(args.player)
        val raw = args.item.trim()
        val match: (ItemStack) -> Boolean = if (raw.startsWith("#")) {
            val tag = ItemSpecs.itemTag(raw) ?: refuse(ToolResult.invalid(ItemSpecs.unknownTag(raw)))
            fun(stack: ItemStack) = stack.`is`(tag)
        } else {
            val item = ItemSpecs.item(raw) ?: refuse(ToolResult.invalid(ItemSpecs.unknownItem(raw)))
            fun(stack: ItemStack) = stack.`is`(item)
        }
        val caps = ctx.guards.caps()
        if (caps.hostileAllowed && args.count > caps.confiscatedItems) {
            refuse(ToolResult.invalid("at chaos ${ctx.chaos} one confiscation takes at most ${caps.confiscatedItems} items (asked ${args.count})"))
        }
        val reason = args.reason?.let { Speech.clean(it) }?.ifEmpty { null }
        ctx.onServer { server ->
            val player = server.live(ref)
            val inventory = player.inventory
            val has = (0 until inventory.containerSize).map { inventory.getItem(it) }.filter { !it.isEmpty && match(it) }.sumOf { it.count }
            if (has == 0) refuse(ToolResult.invalid("${ref.name} has no $raw"))
            val returnAt = ctx.clock.millis() + args.minutes * 60_000L
            ctx.punish(player, returnAt)
            // Taking the elytra of a gliding player, the blocks of one bridging over lava, the armor of one
            // at war with a horde, can kill in any way.
            ctx.guards.armDeathGuard(player.uuid, TAKE_GUARD_SECONDS, Peril.ANY)
            val taken = ctx.guards.items.confiscate(player, match, args.count, returnAt, reason, ctx.caller.turnId)
                ?: refuse(ToolResult.invalid("${ref.name} has no $raw"))
            val all = if (has <= args.count) " (all they had)" else ""
            ToolResult.ok(
                "ok: took ${taken.what}$all from ${ref.name} until ${ctx.at(returnAt)} (in ${TargetingPolicy.span(returnAt - ctx.clock.millis())}); " +
                    "confiscation ${taken.id}",
                listOf(ref),
            )
        }
    }

    private companion object {
        /** The death guard of a confiscation. */
        const val TAKE_GUARD_SECONDS = 15
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────

/** Stops a tool's checks with [result] as its answer, from anywhere, the server thread included. Caught by [answer]. */
internal class Refusal(val result: ToolResult) : RuntimeException(result.text, null, false, false)

/** Stops the tool with [result] as its answer. */
internal fun refuse(result: ToolResult): Nothing = throw Refusal(result)

/** Runs a tool's [block]; a [refuse] on the way becomes the answer. */
internal inline fun answer(block: () -> ToolResult): ToolResult = try {
    block()
} catch (e: Refusal) {
    e.result
}

/** The online players named [names], ignoring case and repeats; refuses with who is online otherwise. Any thread. */
private fun ToolContext.online(names: List<String>): List<PlayerRef> {
    val found = names.map { it to players.byName(it) }
    val missing = found.filter { it.second == null }.map { it.first }.distinct()
    if (missing.isNotEmpty()) {
        val who = if (missing.size == 1) "${missing.single()} is not online" else "${missing.joinToString()} are not online"
        refuse(ToolResult.invalid("$who; online: ${players.online.joinToString { it.ref.name }.ifEmpty { "nobody" }}"))
    }
    return found.mapNotNull { it.second?.ref }.distinctBy { it.uuid }
}

private fun ToolContext.online(name: String): PlayerRef = online(listOf(name)).single()

/** The chaos level in force. */
private val ToolContext.chaos: Int get() = settings.config.chaos

/** [epochMs] as a time of day, `21:41:10`. */
private fun ToolContext.at(epochMs: Long): String = clockTime(clock, epochMs)

/**
 * A hostile action against [player] until [until] (epoch milliseconds): the guards check it (protection,
 * chaos 0, no piling on, one punishment at a time, fairness) and open or extend the turn's punishment.
 * Refuses with the verdict. Server thread.
 */
private fun ToolContext.punish(player: ServerPlayer, until: Long) {
    val verdict = guards.hostile(player, caller, until)
    if (verdict is Verdict.Denied) refuse(ToolResult.denied(verdict))
}

/** The live player of [ref]; refuses when they left. Server thread. */
private fun MinecraftServer.live(ref: PlayerRef): ServerPlayer =
    playerList.getPlayer(ref.uuid) ?: refuse(ToolResult.failed("${ref.name} left the game"))

/** Refuses when the player is dead, on the respawn screen: what they get now would be lost. */
private fun ServerPlayer.requireAlive() {
    if (isDeadOrDying) refuse(ToolResult.failed("$plainTextName is dead (on the respawn screen); try again once they respawn"))
}

/** What a tool did to one of several players: [done] says it, or [refusal] why not. */
private class Outcome(val ref: PlayerRef, val done: String?, val refusal: ToolResult?)

/** Acts on one of several players: a refusal for this one does not stop the others. */
private inline fun outcome(ref: PlayerRef, act: () -> String): Outcome = try {
    Outcome(ref, act(), null)
} catch (e: Refusal) {
    Outcome(ref, null, e.result)
}

/**
 * The answer of a tool that acted on several players: ok with the [summary] of those it acted on and the
 * refusals of the others; only the refusals when it acted on nobody.
 */
private fun report(outcomes: List<Outcome>, summary: (List<Outcome>) -> String): ToolResult {
    val done = outcomes.filter { it.refusal == null }
    val refused = outcomes.mapNotNull { it.refusal }
    if (done.isEmpty()) {
        val first = refused.first()
        return if (refused.size == 1) first else ToolResult(first.status, refused.joinToString("; ") { it.text })
    }
    val others = if (refused.isEmpty()) "" else "; not for the others: " + refused.joinToString("; ") { it.text }
    return ToolResult.ok(summary(done) + others, done.map { it.ref })
}

/** `II` for level 2. */
private fun roman(level: Int): String = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrElse(level - 1) { level.toString() }

/** [value] without a useless fraction: `3`, `0.5`, `12.25`. */
private fun number(value: Double): String = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

/** `14/20`. */
private fun health(player: ServerPlayer): String = "${number(player.health.toDouble())}/${number(player.maxHealth.toDouble())}"

/** `the_nether`, `overworld`. */
private fun dimension(level: ServerLevel): String = shortId(level.dimension().identifier())
