package io.github.clemsix6.narrator2.memory

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Decay and reinforcement (M3 §9): pure functions over the play clocks of [PlayClock], any thread. What
 * fades is measured in time played, never in calendar time. The values are constants of the code, not
 * settings.
 *
 * - A memory's **strength** is a half-life decay from its last reinforcement, with a floor: a forgotten
 *   memory can always come back when it is very relevant ([strength], [initialStability]).
 * - Each time a memory is put before the Narrator it is **reinforced**: its half-life grows, the more so
 *   when it was nearly forgotten, and its strength starts again from 1 ([reinforce], [gain]).
 * - A trait's **confidence** halves every 200 hours that its player plays without a new proof
 *   ([traitConfidence]); below [TRAIT_VISIBLE] the sheet no longer shows it.
 * - A running gag **wears out** when it is served too often ([gagWorn]).
 * - A trivial memory long at the floor is **archived** by the hygiene ([archivable]).
 *
 * Hours are hours of play: world play for memories and gags, the player's own play for traits. Clock
 * values (`reinforced_play_s`, `set_played_s`, [PlayClock]) are seconds.
 */
object Decay {
    /** The lowest strength: nothing is ever fully forgotten. */
    const val FLOOR = 0.02

    /** The half-life of an importance-1 memory, in hours of world play. */
    const val BASE_STABILITY_HOURS = 4.0

    /** How much longer an importance-10 memory lasts than an importance-1 one (640 h against 4 h). */
    const val IMPORTANCE_SPREAD = 160.0

    /** The largest half-life a memory can reach by reinforcement, in hours. */
    const val MAX_STABILITY_HOURS = 5_000.0

    /** Gain of a memory the Narrator took up in a line (the nap's `mark_used`). */
    const val GAIN_USED = 1.0

    /** Gain of a memory the Narrator looked for himself with `recall`. */
    const val GAIN_RECALL_TOOL = 0.5

    /** Gain of a memory only shown: reminiscence, sheet, briefing, profile. */
    const val GAIN_SHOWN = 0.2

    /** A trait's confidence halves after this many hours of its player's play without a new proof. */
    const val TRAIT_HALF_LIFE_HOURS = 200.0

    /** Below this effective confidence a trait is not shown on the sheet, and the Dreamer reviews it. */
    const val TRAIT_VISIBLE = 0.2

    /** The confidence a trait keeps at most when it changes pole on its axis. */
    const val POLE_CHANGE_CONFIDENCE = 0.5

    /** A running gag served this many times… */
    const val GAG_WEAR_USES = 3

    /** …within this many hours of world play is worn out. */
    const val GAG_WEAR_HOURS = 20.0

    /** Memories of this importance or less may be archived by the hygiene… */
    const val ARCHIVE_MAX_IMPORTANCE = 2

    /** …once at the floor for more than this many hours of world play. */
    const val ARCHIVE_AFTER_FLOOR_HOURS = 50.0

    private const val SECONDS_PER_HOUR = 3_600.0

    /** Hours of play in [seconds] of a play clock. */
    fun hours(seconds: Long): Double = seconds / SECONDS_PER_HOUR

    /**
     * The initial half-life of a memory of [importance] (1..10, clamped), in hours of world play:
     * `4 h × 160^((i − 1) / 9)`, i.e. 4 h, 12 h for 3, 38 h for 5, 118 h for 7, 207 h for 8, 640 h for 10.
     */
    fun initialStability(importance: Int): Double {
        val i = importance.coerceIn(MemoryLimits.IMPORTANCE)
        return BASE_STABILITY_HOURS * IMPORTANCE_SPREAD.pow((i - 1) / 9.0)
    }

    /**
     * The strength of a memory [ageHours] after its last reinforcement, with a half-life of
     * [stabilityHours]: `max(0.02, 2^(−age / S))`, in [[FLOOR], 1]. A negative age counts as 0.
     */
    fun strength(ageHours: Double, stabilityHours: Double): Double =
        max(FLOOR, 2.0.pow(-max(0.0, ageHours) / stabilityHours))

    /** The strength of a memory reinforced at the world clock [reinforcedPlaySeconds], read at [worldPlaySeconds]. */
    fun strength(stabilityHours: Double, reinforcedPlaySeconds: Long, worldPlaySeconds: Long): Double =
        strength(hours(worldPlaySeconds - reinforcedPlaySeconds), stabilityHours)

    /** The strength of [memory] at the world clock [worldPlaySeconds]. */
    fun strength(memory: MemoryEntry, worldPlaySeconds: Long): Double =
        strength(memory.stabilityHours, memory.reinforcedPlaySeconds, worldPlaySeconds)

    /**
     * How much a memory shown through [channel] is reinforced: [GAIN_USED], [GAIN_RECALL_TOOL], or
     * [GAIN_SHOWN] for the briefing, a join, a reminiscence, a profile, or what `inspire` and a heartbeat deal (M6
     * §5). 0 for [RecallChannel.DREAM] and [RecallChannel.PROGRAMME]: the Dreamer or the storyteller reads the memory,
     * the Narrator does not see it, so it is not reinforced (its `recalls` row is still written).
     */
    fun gain(channel: RecallChannel): Double = when (channel) {
        RecallChannel.USED -> GAIN_USED
        RecallChannel.RECALL_TOOL -> GAIN_RECALL_TOOL
        RecallChannel.BRIEFING, RecallChannel.JOIN, RecallChannel.REMINISCENCE, RecallChannel.PROFILE,
        RecallChannel.INSPIRE, RecallChannel.HEARTBEAT -> GAIN_SHOWN
        RecallChannel.DREAM, RecallChannel.PROGRAMME -> 0.0
    }

    /**
     * The half-life after a reinforcement of gain [gain] of a memory of half-life [stabilityHours] and
     * current strength [strength] (spacing effect: the weaker it was, the more it grows):
     * `min(5 000 h, S × (1 + g × (0.5 + (1 − s))))`. The caller then sets `reinforced_play_s` to the world
     * clock, so the strength starts again from 1.
     */
    fun reinforce(stabilityHours: Double, strength: Double, gain: Double): Double =
        min(MAX_STABILITY_HOURS, stabilityHours * (1 + gain * (0.5 + (1 - strength))))

    /** A memory's decay columns after a reinforcement: `stability_h` and `reinforced_play_s`. */
    data class Reinforced(val stabilityHours: Double, val reinforcedPlaySeconds: Long)

    /**
     * Reinforces a memory shown through [channel] at the world clock [worldPlaySeconds]: the new
     * `stability_h` and `reinforced_play_s`; null when the channel does not reinforce ([gain] 0), and the
     * two columns stay as they are.
     */
    fun reinforce(stabilityHours: Double, reinforcedPlaySeconds: Long, worldPlaySeconds: Long, channel: RecallChannel): Reinforced? {
        val gain = gain(channel)
        if (gain <= 0.0) return null
        val strength = strength(stabilityHours, reinforcedPlaySeconds, worldPlaySeconds)
        return Reinforced(reinforce(stabilityHours, strength, gain), max(worldPlaySeconds, reinforcedPlaySeconds))
    }

    /** Hours of world play after its last reinforcement for a memory of half-life [stabilityHours] to reach [FLOOR]. */
    fun hoursToFloor(stabilityHours: Double): Double = stabilityHours * ln(1 / FLOOR) / ln(2.0)

    /**
     * Whether the hygiene archives a memory (M3 §8.6): importance ≤ [ARCHIVE_MAX_IMPORTANCE], and at the
     * floor for more than [ARCHIVE_AFTER_FLOOR_HOURS] hours of world play.
     */
    fun archivable(importance: Int, stabilityHours: Double, reinforcedPlaySeconds: Long, worldPlaySeconds: Long): Boolean =
        importance <= ARCHIVE_MAX_IMPORTANCE &&
            hours(worldPlaySeconds - reinforcedPlaySeconds) > hoursToFloor(stabilityHours) + ARCHIVE_AFTER_FLOOR_HOURS

    /**
     * A trait's confidence as read now: `c × 2^(−Δ / 200 h)`, Δ the hours its player has played since it
     * was set ([setPlayedSeconds] against [playerPlaySeconds], the player's own clock).
     */
    fun traitConfidence(confidence: Double, setPlayedSeconds: Long, playerPlaySeconds: Long): Double =
        confidence * 2.0.pow(-max(0.0, hours(playerPlaySeconds - setPlayedSeconds)) / TRAIT_HALF_LIFE_HOURS)

    /** [trait]'s effective confidence when its player's clock reads [playerPlaySeconds]. */
    fun traitConfidence(trait: Trait, playerPlaySeconds: Long): Double =
        traitConfidence(trait.confidence, trait.setPlayedSeconds, playerPlaySeconds)

    /** Whether a trait of effective confidence [effective] is shown on the sheet (not below [TRAIT_VISIBLE]). */
    fun traitVisible(effective: Double): Boolean = effective >= TRAIT_VISIBLE

    /**
     * The confidence stored when a trait is set to [confidence]: capped at [POLE_CHANGE_CONFIDENCE] when
     * it changes pole on its axis ([previousSide] and [side] of opposite signs; the store then sets
     * `trend = −1`).
     */
    fun confidenceOnSet(confidence: Double, previousSide: Int?, side: Int): Double =
        if (previousSide != null && previousSide * side < 0) min(confidence, POLE_CHANGE_CONFIDENCE) else confidence

    /**
     * Whether a running gag is worn out (the reverse of a reinforcement): served [GAG_WEAR_USES] times
     * within less than [GAG_WEAR_HOURS] hours of world play. [uses] counts its uses since it was last made
     * fresh; [hoursSpanned] is the world play between the oldest and the newest of its last
     * [GAG_WEAR_USES] uses.
     */
    fun gagWorn(uses: Int, hoursSpanned: Double): Boolean = uses >= GAG_WEAR_USES && hoursSpanned < GAG_WEAR_HOURS
}
