package io.github.clemsix6.narrator2.mechanics.punish

import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.ApplyEffectArgs
import io.github.clemsix6.narrator2.tool.action.EquipmentSpec
import io.github.clemsix6.narrator2.tool.action.LaunchArgs
import io.github.clemsix6.narrator2.tool.action.LaunchDirection
import io.github.clemsix6.narrator2.tool.action.LightningArgs
import io.github.clemsix6.narrator2.tool.action.ParticleKey
import io.github.clemsix6.narrator2.tool.action.ParticlesArgs
import io.github.clemsix6.narrator2.tool.action.SetAttributeArgs
import io.github.clemsix6.narrator2.tool.action.SpawnArgs

/**
 * One step of a punishment: a call of a real tool (M4 §6.3), with the arguments the catalogue chose. The tool
 * runs it as for the Director, in the punishment's act: its own checks, its category and its ledger.
 */
sealed interface Step {
    /** The tool it calls. */
    val tool: String

    data class Attribute(val args: SetAttributeArgs) : Step {
        override val tool: String get() = "set_attribute"
    }

    data class Effect(val args: ApplyEffectArgs) : Step {
        override val tool: String get() = "apply_effect"
    }

    data class Particles(val args: ParticlesArgs) : Step {
        override val tool: String get() = "particles"
    }

    /** [tiny]: each creature is made a slime of size 1 once summoned (`Slime.setSize`), which never splits. */
    data class Spawn(val args: SpawnArgs, val tiny: Boolean = false) : Step {
        override val tool: String get() = "spawn"
    }

    data class Launch(val args: LaunchArgs) : Step {
        override val tool: String get() = "launch"
    }

    data class Lightning(val args: LightningArgs) : Step {
        override val tool: String get() = "lightning"
    }
}

/** What no tool does (M4 §6.3, §9.2): done by [Primitives], in the tick the punishment's episode opens. */
sealed interface Primitive {
    /** `butterfingers`: the 27 slots of the main inventory shuffled, and the hotbar with them when [hotbar]. */
    data class Shuffle(val hotbar: Boolean) : Primitive

    /** `tree_top`: on top of the highest column around, a tree's leaves first. */
    data object TreeTop : Primitive

    /** `cage` (glass) or `web` (cobwebs), for the punishment's seconds. */
    data class Trap(val shape: TrapShape) : Primitive
}

/** The two traps of the catalogue (M4 §9.2). */
enum class TrapShape { CAGE, WEB }

/**
 * What one punishment of the catalogue does at one severity, within the caps of one chaos level (M4 §6.3).
 *
 * @property category the punishment's own (`PunishmentCategory.of`): `punish` opens its episode with it.
 * @property strikes every category the punishment strikes, its steps' included: each rests afterwards against the
 *   player (`heavy_feet` also strikes `gravity` from severity 2, by its jump step).
 * @property seconds how long it runs: its episode, and the death guard `punish` arms for [peril]. An instant
 *   punishment (a shuffle, a launch, a tree top, a bolt) still has an episode of these seconds.
 * @property lasting whether its effects last [seconds] (the result says until when); else it is done at once.
 * @property peril what `punish` arms the death guard for during [seconds]; null when its steps arm their own
 *   (an effect, an attribute change).
 * @property primitive the catalogue's own action, done first, in the tick the episode opens.
 * @property steps the tool calls, in order, once the episode is open.
 * @property capped the values the caps of the chaos level bounded, for the result:
 *   `duration capped at 60s by the chaos level`.
 */
data class PunishmentPlan(
    val punishment: PunishmentId,
    val severity: Int,
    val category: PunishmentCategory,
    val strikes: Set<PunishmentCategory>,
    val seconds: Int,
    val lasting: Boolean,
    val peril: Peril?,
    val primitive: Primitive?,
    val steps: List<Step>,
    val capped: List<String>,
)

/**
 * The catalogue of `punish` (M4 §6.3): for each punishment at a severity from 1 to 5, its steps (calls of the
 * real tools, or one of the catalogue's [Primitive]s), its category, how long it runs and what the death guard
 * covers. The severity chooses the values; the caps of the chaos level still bound them, and a value they bound
 * is said ([PunishmentPlan.capped]). Only a severity beyond `Caps.maxSeverity` is an error ([severityRefusal]).
 * Pure, any thread.
 */
object PunishmentCatalog {
    /** The highest severity of all, at any chaos level. */
    const val MAX_SEVERITY = 5

    // The values of each severity, 1 to 5 (M4 §6.3).
    val TINY_SCALE = listOf(0.6, 0.5, 0.4, 0.33, 0.25)
    val MOON_GRAVITY = listOf(0.5, 0.4, 0.3, 0.2, 0.15)
    val SLOW_HANDS_SPEED = listOf(0.6, 0.45, 0.3, 0.2, 0.1)

    /** `heavy_feet` from severity 2: the jump, halved. */
    const val HEAVY_JUMP = 0.5

    /** Seconds per point of severity of the punishments that last. */
    const val TINY_SECONDS = 60
    const val MOON_GRAVITY_SECONDS = 60
    const val SLOW_HANDS_SECONDS = 60
    const val HEAVY_FEET_SECONDS = 45
    const val HUNGER_SECONDS = 30

    /** `guests` stay 2 minutes, a `slime_rain` 1, the slimes falling from this high, this close to the player. */
    const val GUESTS_MINUTES = 2
    const val SLIME_MINUTES = 1
    const val SLIME_HEIGHT = 12
    const val SLIME_DISTANCE = 4

    /**
     * What the guests wear on their head: a costume, and a hat against the sun (a zombie with something on its
     * head does not burn by day, and the punishment does not go up in smoke).
     */
    const val GUESTS_HAT = "carved_pumpkin"

    /** The episode of the instant punishments: time to sort an inventory, to climb down, to get over a bolt. */
    const val SHUFFLE_SECONDS = 30
    const val TREE_TOP_SECONDS = 60
    const val SMITE_SECONDS = 30

    /** A launch's own fall protection (`launch`): the episode of `skyward`. */
    const val SKYWARD_SECONDS = 15

    /** Particles shown with `tiny` and `moon_gravity`. */
    const val PARTICLES = 40

    /**
     * Why [severity] is too much at [chaos] (whose caps are [caps]), for `Invalid arguments: …`; null when the
     * chaos level allows it.
     */
    fun severityRefusal(severity: Int, caps: Caps, chaos: Int): String? =
        if (severity <= caps.maxSeverity) null else "at chaos $chaos a punishment has a severity of at most ${caps.maxSeverity} (asked $severity)"

    /**
     * What [punishment] does to [player] (their name, for the tools' arguments) at [severity] within [caps]; [name]
     * is what the guests bear. The severity is not checked against the chaos level here ([severityRefusal]).
     */
    fun plan(punishment: PunishmentId, severity: Int, caps: Caps, player: String, name: String? = null): PunishmentPlan {
        require(severity in 1..MAX_SEVERITY) { "severity must be between 1 and $MAX_SEVERITY (got $severity)" }
        val notes = mutableListOf<String>()
        val s = severity - 1

        /** [perSeverity] seconds per point of severity, within the longest harmful change of the chaos level. */
        fun lasting(perSeverity: Int): Int {
            val asked = perSeverity * severity
            if (asked <= caps.harmfulSeconds) return asked
            notes += "duration capped at ${caps.harmfulSeconds}s by the chaos level"
            return caps.harmfulSeconds
        }

        /** An [effect]'s [asked] level, within the highest harmful level of the chaos level. */
        fun level(effect: String, asked: Int): Int {
            if (asked <= caps.harmfulLevel) return asked
            notes += "$effect capped at level ${ToolText.roman(caps.harmfulLevel)} by the chaos level"
            return caps.harmfulLevel
        }

        /** [asked] creatures, within those one call may summon at the chaos level. */
        fun creatures(what: String, asked: Int): Int {
            if (asked <= caps.spawnsPerAction) return asked
            notes += "$what capped at ${caps.spawnsPerAction} by the chaos level"
            return caps.spawnsPerAction
        }

        fun attribute(key: AttributeKey, factor: Double, seconds: Int) = Step.Attribute(SetAttributeArgs(player, key, multiply = factor, seconds = seconds))
        fun effect(id: String, seconds: Int, level: Int) = Step.Effect(ApplyEffectArgs(listOf(player), id, seconds = seconds, level = level))
        fun particles(key: ParticleKey) = Step.Particles(ParticlesArgs(player = player, particle = key, count = PARTICLES))

        val category = PunishmentCategory.of(punishment)
        val plan = when (punishment) {
            PunishmentId.TINY -> {
                val seconds = lasting(TINY_SECONDS)
                Draft(seconds, true, null, null, listOf(attribute(AttributeKey.SCALE, TINY_SCALE[s], seconds), particles(ParticleKey.POOF)))
            }
            PunishmentId.MOON_GRAVITY -> {
                val seconds = lasting(MOON_GRAVITY_SECONDS)
                Draft(seconds, true, Peril.FALL, null, listOf(attribute(AttributeKey.GRAVITY, MOON_GRAVITY[s], seconds), particles(ParticleKey.END_ROD)))
            }
            PunishmentId.SLOW_HANDS -> {
                val seconds = lasting(SLOW_HANDS_SECONDS)
                val fatigue = if (severity >= 3) listOf(effect("mining_fatigue", seconds, level("mining_fatigue", severity - 2))) else emptyList()
                Draft(seconds, true, null, null, listOf(attribute(AttributeKey.BLOCK_BREAK_SPEED, SLOW_HANDS_SPEED[s], seconds)) + fatigue)
            }
            PunishmentId.HEAVY_FEET -> {
                val seconds = lasting(HEAVY_FEET_SECONDS)
                val jump = if (severity >= 2) listOf(attribute(AttributeKey.JUMP_STRENGTH, HEAVY_JUMP, seconds)) else emptyList()
                Draft(seconds, true, null, null, listOf(effect("slowness", seconds, level("slowness", (severity + 1) / 2))) + jump)
            }
            PunishmentId.HUNGER -> {
                val seconds = lasting(HUNGER_SECONDS)
                Draft(seconds, true, null, null, listOf(effect("hunger", seconds, level("hunger", severity))))
            }
            PunishmentId.BUTTERFINGERS -> {
                val hotbar = severity >= 3
                // Shuffling the hotbar under a player bridging over lava can kill in any way; the inventory alone cannot.
                Draft(SHUFFLE_SECONDS, false, if (hotbar) Peril.ANY else null, Primitive.Shuffle(hotbar), emptyList())
            }
            PunishmentId.GUESTS -> {
                val count = creatures("zombies", severity + 1)
                val guests = SpawnArgs(
                    entity = "zombie", count = count, near = player, name = name, target = true,
                    equipment = EquipmentSpec(head = GUESTS_HAT), lifetimeMinutes = GUESTS_MINUTES, baby = severity <= 2,
                )
                Draft(GUESTS_MINUTES * 60, true, Peril.ANY, null, listOf(Step.Spawn(guests)))
            }
            PunishmentId.SLIME_RAIN -> {
                val count = creatures("slimes", 2 + 2 * severity)
                val slimes = SpawnArgs(entity = "slime", count = count, near = player, distance = SLIME_DISTANCE, lifetimeMinutes = SLIME_MINUTES, height = SLIME_HEIGHT)
                Draft(SLIME_MINUTES * 60, true, Peril.ANY, null, listOf(Step.Spawn(slimes, tiny = true)))
            }
            PunishmentId.SKYWARD -> {
                // 0.6 + 0.3·s, written in tenths so that 1.8 is 1.8.
                val asked = (6 + 3 * severity) / 10.0
                val strength = if (asked <= caps.launchStrength) {
                    asked
                } else {
                    notes += "strength capped at ${ToolText.number(caps.launchStrength)} by the chaos level"
                    caps.launchStrength
                }
                Draft(SKYWARD_SECONDS, true, Peril.FALL, null, listOf(Step.Launch(LaunchArgs(player, LaunchDirection.UP, strength))))
            }
            PunishmentId.TREE_TOP -> Draft(TREE_TOP_SECONDS, false, Peril.FALL, Primitive.TreeTop, emptyList())
            PunishmentId.SMITE -> {
                val hearts = if (severity <= caps.lightningHearts) {
                    severity.toDouble()
                } else {
                    notes += "damage capped at ${ToolText.number(caps.lightningHearts)} hearts by the chaos level"
                    caps.lightningHearts
                }
                Draft(SMITE_SECONDS, false, Peril.ANY, null, listOf(Step.Lightning(LightningArgs(player = player, damageHearts = hearts))))
            }
            PunishmentId.CAGE, PunishmentId.WEB -> {
                val shape = if (punishment == PunishmentId.CAGE) TrapShape.CAGE else TrapShape.WEB
                Draft(MechanicsLimits.TRAP_SECONDS_PER_SEVERITY * severity, true, Peril.ANY, Primitive.Trap(shape), emptyList())
            }
        }
        val strikes = setOf(category) + plan.steps.filterIsInstance<Step.Attribute>().map { PunishmentCategory.ofAttribute(it.args.attribute) } +
            plan.steps.filterIsInstance<Step.Effect>().map { PunishmentCategory.ofEffect(it.args.effect) }
        return PunishmentPlan(punishment, severity, category, strikes, plan.seconds, plan.lasting, plan.peril, plan.primitive, plan.steps, notes.distinct())
    }

    /** A plan before its category and notes. */
    private class Draft(val seconds: Int, val lasting: Boolean, val peril: Peril?, val primitive: Primitive?, val steps: List<Step>)
}
