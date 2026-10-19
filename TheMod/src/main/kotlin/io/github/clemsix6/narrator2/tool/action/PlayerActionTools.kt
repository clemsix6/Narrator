package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.BeneficialWhen
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.EffectLedger
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.guard.clockTime
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.Refusal
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.tool.ToolText.number
import io.github.clemsix6.narrator2.tool.ToolText.roman
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.chaos
import io.github.clemsix6.narrator2.tool.live
import io.github.clemsix6.narrator2.tool.online
import io.github.clemsix6.narrator2.tool.punish
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.tool.speech.closestIds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
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
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Actions on players (M1_M2 §9.3): `apply_effect`, `remove_effect`, `set_attribute`, `reset_attribute`,
 * `teleport`, `launch`, `give_item`, `take_item`. `ToolCatalog` builds the family once per conversation;
 * the [GiftLimiter] is the server's (`ToolLimits.gifts`), shared by every conversation.
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
 *
 * Each hostile action tells the guards its category of punishment (M4 §6.1): the harmful effect's or the
 * attribute's own, `teleport`, `air` for a launch, `inventory` for a confiscation, `other` for a benefit taken
 * away. The checks that need neither the players online nor the world are each tool's `check` (M4 §4.3), which
 * `run` starts with: the mechanics engine runs them alone to refuse a rule's effect or a challenge's reward
 * when it is created, with the same answer.
 */
fun playerActionTools(services: NarratorServices): List<NarratorTool<*>> = listOf(
    ApplyEffectTool(),
    RemoveEffectTool(),
    SetAttributeTool(),
    ResetAttributeTool(),
    TeleportTool(),
    LaunchTool(),
    GiveItemTool(services.toolLimits.gifts),
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
    /** The effect known and allowed, and within the caps of the chaos level (a harmful one) or of the gifts. */
    override suspend fun check(ctx: ToolContext, args: ApplyEffectArgs): ToolResult? = dryCheck {
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
    }

    override suspend fun run(ctx: ToolContext, args: ApplyEffectArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val targets = ctx.online(args.players)
        val effect = Effects.lookup(args.effect)
        val harmful = effect.value().category == MobEffectCategory.HARMFUL
        val instant = effect.value().isInstantaneous
        val category = PunishmentCategory.ofEffect(effect.registeredName)
        val shown = "${shortId(effect)} ${roman(args.level)}"
        val now = ctx.clock.millis()
        val seconds = if (instant) Effects.INSTANT_SECONDS else args.seconds
        val until = now + seconds * 1_000L
        val outcomes = ctx.onServer { server ->
            targets.map { ref ->
                outcome(ref) {
                    val player = server.live(ref)
                    var replaced = ""
                    if (harmful) {
                        ctx.punish(player, until, category)
                        // The effect's own length and 30 s more: a levitation's fall outlasts it (M2 review, finding 1).
                        Effects.perilOf(effect)?.let { ctx.guards.armDeathGuard(player.uuid, seconds + Effects.GUARD_MARGIN_SECONDS, it) }
                    } else {
                        player.requireAlive()
                        if (!instant) replaced = ctx.replacing(player, ref, effect, args.level, args.seconds)
                    }
                    if (instant) {
                        effect.value().applyInstantaneousEffect(player.level(), null, null, player, args.level - 1, 1.0)
                        "${ref.shownName} (health ${health(player)})"
                    } else {
                        ctx.guards.effects.apply(player, effect, args.level, args.seconds, args.particles, ctx.caller.turnId)
                        ref.shownName + replaced
                    }
                }
            }
        }
        report(outcomes) { done ->
            val names = done.joinToString { it.done!! }
            if (instant) "ok: $shown on $names" else "ok: $names ${if (done.size == 1) "gets" else "get"} $shown for ${duration(args.seconds * 1_000L)} (until ${ctx.at(until)})"
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
                        ctx.punish(player, until, PunishmentCategory.OTHER)
                        benefits.mapNotNull { Effects.perilOf(it) }.distinct().forEach { ctx.guards.armDeathGuard(player.uuid, REMOVAL_GUARD_SECONDS, it) }
                    }
                    if (only == null) ctx.guards.effects.removeAll(player) else ctx.guards.effects.remove(player, only)
                    "${shown.joinToString()} from ${ref.shownName}"
                }
            }
        }
        val acted = outcomes.filter { it.refusal != null || it.done!!.isNotEmpty() }
        if (acted.isEmpty()) {
            val whom = ToolText.names(targets)
            return@answer if (only == null) {
                ToolResult.ok("ok: nothing to remove: the Narrator has no effect on $whom", targets)
            } else {
                ToolResult.invalid("$whom ${if (targets.size == 1) "does" else "do"} not have ${shortId(only)}; ${ctx.effectsOf(targets)}")
            }
        }
        val untouched = outcomes.filter { it.refusal == null && it.done!!.isEmpty() }.map { it.ref.shownName }
        report(acted) { done ->
            "ok: removed ${done.joinToString("; ") { it.done!! }}" + (if (untouched.isEmpty()) "" else "; nothing on ${untouched.joinToString()}")
        }
    }

    /** The effects [players] have now, for an error message. */
    private suspend fun ToolContext.effectsOf(players: List<PlayerRef>): String = onServer { server ->
        players.joinToString("; ") { ref ->
            val effects = server.playerList.getPlayer(ref.uuid)?.activeEffects.orEmpty()
                .map { "${shortId(it.effect)} ${roman(it.amplifier + 1)}" }
            "${ref.shownName} has ${effects.joinToString().ifEmpty { "no effect" }}"
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
    val lasting = if (had.isInfiniteDuration) "with no end" else "for ${ToolText.duration(had.duration * 1_000L / TICKS_PER_SECOND)}"
    val what = "${shortId(effect)} ${roman(had.amplifier + 1)} $lasting"
    if (!longer && !stronger) refuse(ToolResult.invalid("${ref.shownName} already has $what"))
    if (!shorter && !weaker) return ""
    punish(player, clock.millis() + REMOVAL_GUARD_SECONDS * 1_000L, PunishmentCategory.OTHER)
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
    /**
     * A change in the player's disfavor whatever their attribute's value now (one that is never a gift, or an
     * amount added the wrong way) lasts no longer than the chaos level allows. Whether any other change is in
     * their disfavor, and whether it stays within the attribute's bounds, depends on the player: [run] tells.
     */
    override suspend fun check(ctx: ToolContext, args: SetAttributeArgs): ToolResult? {
        val caps = ctx.guards.caps()
        if (!caps.hostileAllowed || args.seconds <= caps.harmfulSeconds || !certainlyHarmful(args)) return null
        return tooLong(ctx, ModelText.name(args.player), args.attribute, args.seconds)
    }

    override suspend fun run(ctx: ToolContext, args: SetAttributeArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
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
            if (harmful && caps.hostileAllowed && args.seconds > caps.harmfulSeconds) refuse(tooLong(ctx, ref.shownName, key, args.seconds))
            val until = ctx.clock.millis() + args.seconds * 1_000L
            // Only a change in the player's disfavor arms the death guard (M2 review, finding 11), for
            // what it can kill with, until 30 s after it ends (a gravity that comes back mid-jump).
            if (harmful) {
                ctx.punish(player, until, PunishmentCategory.ofAttribute(key))
                key.peril?.let { ctx.guards.armDeathGuard(player.uuid, args.seconds + Effects.GUARD_MARGIN_SECONDS, it) }
            } else {
                player.requireAlive()
            }
            val value = ledger.apply(player, key, amount, operation, until, harmful, ctx.caller.turnId)
            ToolResult.ok("ok: ${ref.shownName}'s ${key.id} ${number(current)} → ${number(value)} for ${duration(args.seconds * 1_000L)} (until ${ctx.at(until)})", listOf(ref))
        }
    }

    /** A change in [name]'s disfavor that lasts longer than the chaos level allows. */
    private fun tooLong(ctx: ToolContext, name: String, key: AttributeKey, seconds: Int) = ToolResult.invalid(
        "this change of ${key.id} is in $name's disfavor: at chaos ${ctx.chaos} it lasts at most ${ctx.guards.caps().harmfulSeconds} s (asked $seconds s)",
    )

    /**
     * Whether [args] move the attribute in the player's disfavor whatever its value: an attribute that is never
     * a gift (size, gravity…), or an amount added the wrong way. A factor depends on the value (a luck of 0
     * stays 0), and going past [AttributeKey.favorableUpTo] too.
     */
    private fun certainlyHarmful(args: SetAttributeArgs): Boolean = when (args.attribute.beneficial) {
        BeneficialWhen.NEVER -> true
        BeneficialWhen.HIGHER -> (args.add ?: 0.0) < 0
        BeneficialWhen.LOWER -> (args.add ?: 0.0) > 0
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
                    return@onServer ToolResult.ok("ok: nothing to put back: the Narrator changed none of ${ref.shownName}'s attributes", listOf(ref))
                }
                val all = ledger.on(player).joinToString { AttributeLedger.describe(it) }.ifEmpty { "none" }
                refuse(ToolResult.invalid("the Narrator did not change ${ref.shownName}'s ${args.attribute.id}; its changes on ${ref.shownName}: $all"))
            }
            val favors = changes.filter { !it.harmful }
            if (favors.isNotEmpty()) {
                ctx.punish(player, ctx.clock.millis() + REMOVAL_GUARD_SECONDS * 1_000L, PunishmentCategory.OTHER)
                favors.mapNotNull { AttributeLedger.keyOf(it)?.peril }.distinct().forEach { ctx.guards.armDeathGuard(player.uuid, REMOVAL_GUARD_SECONDS, it) }
            }
            val back = changes.mapNotNull { AttributeLedger.keyOf(it) }.map { key ->
                val before = ledger.value(player, key)
                ledger.reset(player, key)
                "${key.id} ${number(before)} → ${number(ledger.value(player, key))}"
            }
            ToolResult.ok("ok: put back ${ref.shownName}'s ${back.joinToString()}", listOf(ref))
        }
    }
}

// ── teleport ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class TeleportArgs(
    @Doc("Name of the online player to move.")
    @MaxLen(16)
    val player: String,
    @Doc("Name of an online player, in the same dimension, to bring them next to. Give this, x and z, or random_radius.")
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
    @Doc("A random spot in their own dimension, between half this many blocks and this many from them.")
    @SerialName("random_radius")
    @Range(10.0, 500.0)
    val randomRadius: Int? = null,
) {
    init {
        require(listOf(toPlayer != null, x != null || z != null, randomRadius != null).count { it } == 1) { "give to_player, x and z, or random_radius" }
        require((x == null) == (z == null)) { "give both x and z" }
        require(y == null || x != null) { "y goes with x and z" }
    }
}

class TeleportTool : NarratorTool<TeleportArgs>(
    name = "teleport",
    description = "Moves a player, within their own dimension, next to another player, to a column x, z or to a random spot " +
        "(random_radius): they land on safe ground (never in lava, water, a wall or the void), or nothing happens. A protected " +
        "player is never moved. Taking them more than 32 blocks away is a punishment, refused like any other when the player " +
        "may not be punished, and its distance is capped by the chaos level. Slow: the destination loads first.",
    args = TeleportArgs.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = 12_000,
) {
    /**
     * Where to land: the [columns] to try in order, all in the chunk being loaded ([loading]); [how] completes
     * the answer (` next to Bob`, ` at random`).
     */
    private class Destination(val level: ServerLevel, val columns: List<Teleports.Column>, val fromY: Int?, val how: String, val loading: CompletableFuture<*>)

    /** Nobody is teleported to themselves; a random spot is no farther than the chaos level allows. */
    override suspend fun check(ctx: ToolContext, args: TeleportArgs): ToolResult? {
        if (args.toPlayer != null && args.toPlayer.equals(args.player, ignoreCase = true)) {
            return ToolResult.invalid("${ModelText.name(args.player)} cannot be teleported to themselves")
        }
        val radius = args.randomRadius ?: return null
        val caps = ctx.guards.caps()
        if (caps.hostileAllowed && radius > caps.teleportBlocks) {
            return ToolResult.invalid("at chaos ${ctx.chaos} a teleport goes at most ${caps.teleportBlocks} blocks (random_radius $radius)")
        }
        return null
    }

    override suspend fun run(ctx: ToolContext, args: TeleportArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val ref = ctx.online(args.player)
        val toRef = args.toPlayer?.let { ctx.online(it) }
        val caps = ctx.guards.caps()
        val destination = ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            // Near or far, a protected player stays where they are (M2 review, finding 12).
            if (ctx.guards.isProtected(ref.uuid)) {
                refuse(ToolResult.denied(Rules.PROTECTED, "${ref.shownName} is protected by the operators; the Narrator never teleports them."))
            }
            val level = player.level()
            val (column, fromY, how) = when {
                toRef != null -> {
                    val other = server.live(toRef)
                    if (other.level().dimension() != level.dimension()) {
                        refuse(ToolResult.invalid("${toRef.shownName} is in ${dimension(other.level())} and ${ref.shownName} in ${dimension(level)}; a teleport stays in the player's own dimension"))
                    }
                    Triple(Teleports.Column(other.blockX, other.blockZ), other.blockY, " next to ${toRef.shownName}")
                }
                args.randomRadius != null -> Triple(randomColumn(level, player, args.randomRadius), null, " at random")
                else -> Triple(Teleports.Column(args.x!!, args.z!!), args.y, "")
            }
            if (fromY != null && level.isOutsideBuildHeight(fromY)) {
                refuse(ToolResult.invalid("y must be between ${level.minY} and ${level.maxY} in ${dimension(level)}"))
            }
            if (!level.worldBorder.isWithinBounds(column.x + 0.5, column.z + 0.5)) refuse(ToolResult.invalid("x ${column.x}, z ${column.z} is beyond the world border"))
            val far = hypot(player.x - (column.x + 0.5), player.z - (column.z + 0.5))
            if (far > Caps.HOSTILE_TELEPORT_BLOCKS) {
                // Certainly a punishment: refused before loading anything when it cannot be one.
                if (!caps.hostileAllowed || ctx.guards.isProtected(ref.uuid)) ctx.punish(player, ctx.clock.millis(), PunishmentCategory.TELEPORT)
                if (far > caps.teleportBlocks) refuse(tooFar(ctx, far, caps))
            }
            val columns = args.randomRadius?.let { Teleports.columnsAround(level.random, column, player.x, player.z, it) } ?: listOf(column)
            Destination(level, columns, fromY, how, Teleports.requestLoad(level, column.x, column.z))
        }
        val first = destination.columns.first()
        if (!Teleports.awaitLoaded(destination.loading)) {
            refuse(ToolResult.failed("the destination x ${first.x}, z ${first.z} did not load within ${duration(Teleports.LOAD_TIMEOUT_MS)}; nothing happened"))
        }
        ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            if (player.level() !== destination.level) refuse(ToolResult.failed("${ref.shownName} changed dimension meanwhile; nothing happened"))
            val spot = destination.columns.firstNotNullOfOrNull { Teleports.landing(destination.level, player, it.x, it.z, destination.fromY) }
                ?: refuse(
                    ToolResult.failed(
                        if (destination.columns.size == 1) {
                            "no safe landing at x ${first.x}, z ${first.z} (only liquid, danger or no room to stand in that column); " +
                                "nothing happened, try another spot"
                        } else {
                            "no safe landing around x ${first.x}, z ${first.z} (${destination.columns.size} columns tried: only liquid, danger " +
                                "or no room to stand); nothing happened, try again"
                        },
                    ),
                )
            val distance = player.position().distanceTo(spot)
            if (distance > Caps.HOSTILE_TELEPORT_BLOCKS) {
                if (caps.hostileAllowed && distance > caps.teleportBlocks) refuse(tooFar(ctx, distance, caps))
                ctx.punish(player, ctx.clock.millis() + TELEPORT_PUNISHMENT_SECONDS * 1_000L, PunishmentCategory.TELEPORT)
            }
            // The landing is safe, what is around it may not be (M2 review, finding 12).
            ctx.guards.armDeathGuard(player.uuid, TELEPORT_GUARD_SECONDS, Peril.ANY)
            if (!Teleports.move(player, spot)) refuse(ToolResult.failed("the game refused to move ${ref.shownName}"))
            ToolResult.ok(
                "ok: ${ref.shownName} teleported${destination.how}, ${number(distance)} blocks away, to x ${number(spot.x)}, y ${number(spot.y)}, z ${number(spot.z)}",
                listOf(ref),
            )
        }
    }

    /**
     * The column a random teleport of [player] aims at ([Teleports.randomColumn]), inside the world border: a
     * few draws at most, then a refusal. Server thread.
     */
    private fun randomColumn(level: ServerLevel, player: ServerPlayer, radius: Int): Teleports.Column =
        generateSequence { Teleports.randomColumn(level.random, player.x, player.z, radius) }
            .take(Teleports.RANDOM_COLUMNS)
            .firstOrNull { level.worldBorder.isWithinBounds(it.x + 0.5, it.z + 0.5) }
            ?: refuse(ToolResult.failed("no spot ${radius / 2} to $radius blocks from ${player.shownName} is inside the world border; nothing happened"))

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
    /** No stronger than the chaos level allows (at chaos 0, the guards refuse any launch). */
    override suspend fun check(ctx: ToolContext, args: LaunchArgs): ToolResult? {
        val caps = ctx.guards.caps()
        if (!caps.hostileAllowed || args.strength <= caps.launchStrength) return null
        return ToolResult.invalid("at chaos ${ctx.chaos} a launch has a strength of at most ${number(caps.launchStrength)} (asked ${number(args.strength)})")
    }

    override suspend fun run(ctx: ToolContext, args: LaunchArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val ref = ctx.online(args.player)
        ctx.onServer { server ->
            val player = server.live(ref)
            val until = ctx.clock.millis() + LAUNCH_SECONDS * 1_000L
            ctx.punish(player, until, PunishmentCategory.AIR)
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
                "ok: ${ref.shownName} launched ${args.direction.name.lowercase()} at strength ${number(args.strength)}; no fall damage until ${ctx.at(until)}",
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

class GiveItemTool(internal val limiter: GiftLimiter) : NarratorTool<GiveItemArgs>(
    name = "give_item",
    description = "Gives an item to a player, possibly named, described, enchanted or unbreakable: a reward, a gag, a " +
        "cursed trinket. It goes into their inventory, or at their feet when it is full. Operator items, unbreakable " +
        "blocks, spawners and spawn eggs are never given. At most 20 gifts per player per hour, and few precious items: " +
        "the Narrator slows progression down (4 diamonds an hour, or a single piece of netherite, diamond gear, elytra, " +
        "totem, enchanted golden apple, beacon, shulker box…).",
    args = GiveItemArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /**
     * The item described (known, enabled in this world, never forbidden, one stack at most, its texts and its
     * enchantments), and worth no more precious points than any player may get in an hour. Not a rule's effect
     * (M4 §8.4), but a challenge's reward (M4 §5.3): the engine refuses it when the challenge starts. Reads the
     * server's registries, frozen once it runs, never a player.
     */
    override suspend fun check(ctx: ToolContext, args: GiveItemArgs): ToolResult? = dryCheck {
        val server = ctx.services.server
        val spec = resolve(server.registryAccess(), server.worldData.enabledFeatures(), args)
        GiftLimiter.tooPrecious(points(spec), what(spec))?.let { refuse(ToolResult.denied(it)) }
    }

    override suspend fun run(ctx: ToolContext, args: GiveItemArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val ref = ctx.online(args.player)
        ctx.onServer { server ->
            val player = server.live(ref)
            player.requireAlive()
            val spec = resolve(server.registryAccess(), player.level().enabledFeatures(), args)
            limiter.take(ref, points(spec), what(spec)).let { if (it is Verdict.Denied) refuse(ToolResult.denied(it)) }
            val dropped = give(player, ItemSpecs.build(spec, ctx.caller.turnId))
            val where = if (dropped) "dropped at ${ref.shownName}'s feet (inventory full)" else "to ${ref.shownName}"
            ToolResult.ok("ok: gave ${ItemSpecs.describe(spec)} $where", listOf(ref))
        }
    }

    private fun resolve(registries: RegistryAccess, features: FeatureFlagSet, args: GiveItemArgs): ItemSpec =
        ItemSpecs.resolve(registries, features, args.item, args.count, args.name, args.lore, args.enchantments, args.unbreakable)

    /** What [spec] is worth in precious points ([ItemSpecs.preciousness]). */
    private fun points(spec: ItemSpec): Int = ItemSpecs.preciousness(spec.item) * spec.count

    /** `3x diamond`. */
    private fun what(spec: ItemSpec): String = "${spec.count}x ${shortId(spec.item)}"

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
 * nothing. The server's own is `ToolLimits.gifts`, so a new chapter does not reset the counts; in memory
 * only. Any thread.
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
                "${player.shownName} got $GIFTS_PER_HOUR gifts in the last hour, the most a player may get; the next is possible at " +
                    "${clockTime(clock, next)} (in ${ToolText.wait(next - now)})",
            )
        }
        val spent = lastHour(precious, player, now)
        if (points > 0 && spent.size + points > PRECIOUS_POINTS_PER_HOUR) {
            tooPrecious(points, what)?.let { return it }
            // The points leave the hour oldest first: enough have left once this one has.
            val next = spent[spent.size + points - PRECIOUS_POINTS_PER_HOUR - 1] + HOUR_MS
            return precious(
                "${player.shownName} got ${spent.size} of the $PRECIOUS_POINTS_PER_HOUR precious points a player may get in an hour, and " +
                    "$what is worth $points; it is possible at ${clockTime(clock, next)} (in ${ToolText.wait(next - now)})",
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

        /**
         * The refusal of a gift, [what] (`8x diamond`), worth more [points] than any player may get in an hour,
         * whoever gets it and whenever; null when it may be given to a player who got nothing precious lately.
         */
        fun tooPrecious(points: Int, what: String): Verdict.Denied? =
            if (points <= PRECIOUS_POINTS_PER_HOUR) {
                null
            } else {
                precious("$what is worth $points precious points, more than the $PRECIOUS_POINTS_PER_HOUR a player may get in an hour")
            }

        private fun precious(why: String) = Verdict.Denied(
            PRECIOUS_RULE,
            "$why. The Narrator slows progression down: a diamond or a golden apple is 1 point; netherite, diamond gear, elytra, " +
                "totems, enchanted golden apples, beacons, shulker boxes and the like are $PRECIOUS_POINTS_PER_HOUR.",
        )
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
            if (has == 0) refuse(ToolResult.invalid("${ref.shownName} has no $raw"))
            val returnAt = ctx.clock.millis() + args.minutes * 60_000L
            ctx.punish(player, returnAt, PunishmentCategory.INVENTORY)
            // Taking the elytra of a gliding player, the blocks of one bridging over lava, the armor of one
            // at war with a horde, can kill in any way.
            ctx.guards.armDeathGuard(player.uuid, TAKE_GUARD_SECONDS, Peril.ANY)
            val taken = ctx.guards.items.confiscate(player, match, args.count, returnAt, reason, ctx.caller.turnId)
                ?: refuse(ToolResult.invalid("${ref.shownName} has no $raw"))
            val all = if (has <= args.count) " (all they had)" else ""
            ToolResult.ok(
                "ok: took ${taken.what}$all from ${ref.shownName} until ${ctx.at(returnAt)} (in ${ToolText.duration(returnAt - ctx.clock.millis())}); " +
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

/**
 * Runs a tool's dry checks ([NarratorTool.check]), written with [refuse] like the rest of the tool: the first
 * refusal is the answer, null when they all pass.
 */
internal inline fun dryCheck(checks: () -> Unit): ToolResult? = try {
    checks()
    null
} catch (e: Refusal) {
    e.result
}

/** Refuses when the player is dead, on the respawn screen: what they get now would be lost. */
private fun ServerPlayer.requireAlive() {
    if (isDeadOrDying) refuse(ToolResult.failed("$shownName is dead (on the respawn screen); try again once they respawn"))
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

/** `14/20`. */
private fun health(player: ServerPlayer): String = "${number(player.health.toDouble())}/${number(player.maxHealth.toDouble())}"

/** `the_nether`, `overworld`. */
private fun dimension(level: ServerLevel): String = ToolText.dimension(level.dimension().identifier().toString())
