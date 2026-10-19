package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import java.util.UUID

/** An engine rule's answer to an action. */
sealed interface Verdict {
    data object Allowed : Verdict

    /**
     * Refused by [rule] (one of [Rules], or another snake_case name). [message] is one English
     * sentence for the model that says why and until when, e.g. `Bartok died 2m ago; hostile actions
     * against him are refused until 21:44 (in 3m).`
     */
    data class Denied(val rule: String, val message: String) : Verdict
}

/** Names of the engine rules, as they appear in `DENIED (<rule>): …` and in `guard` decisions. */
object Rules {
    /** The player is protected (`safety.protectedPlayers` or `/narrator protect`). */
    const val PROTECTED = "protected"

    /** Chaos 0: no hostile action at all. */
    const val CHAOS_0 = "chaos_0"

    /** The player died less than 5 minutes ago. */
    const val NO_PILE_ON = "no_pile_on"

    /** A punishment opened by another turn is still running against the player. */
    const val ALREADY_PUNISHED = "already_punished"

    /** `run_command` refused a command. */
    const val FORBIDDEN_COMMAND = "forbidden_command"

    /** Another act struck the player in the same category of punishment too recently (M4 §6.1). */
    const val SAME_PUNISHMENT = "same_punishment"

    /** The player is under the Narrator's grace (M4 §7.1). */
    const val GRACE = "grace"

    /** A favor's own limit: a grace too soon after the last one, a second joker within a day (M4 §7.1). */
    const val FAVOR_LIMIT = "favor_limit"
}

/**
 * The engine's rules, enforced by code whatever the model asks: protected players, no piling on, the
 * death guard, the ledgers that undo every temporary effect. Tools check here before acting, and change
 * players only through the ledgers (M1_M2 §10; `docs/design/handoff/T09a.md` shows how).
 */
interface Guards {
    /** The player is protected from every hostile action. Any thread. */
    fun isProtected(uuid: UUID): Boolean

    /**
     * Checks a hostile action of [caller] against [target] (protection, chaos 0, recent death, a
     * punishment of another act still running, fairness, the player's grace, and the rest of [category]
     * against them) and, when allowed, opens or extends the caller's episode against [target] until
     * [until] (epoch milliseconds), its act being [Caller.act]. [category]: the kind of punishment (M4
     * §6.1), deduced by the tool from its arguments; null for an action without one. Server thread only.
     */
    fun hostile(target: ServerPlayer, caller: Caller, until: Long, category: PunishmentCategory? = null): Verdict

    /**
     * Deaths of [target] in the next [seconds] of their play time are attributed to the Narrator (any
     * action that can kill arms it, before acting). On the server thread; from another thread, it is
     * queued there, and dropped once the server has stopped. Nothing happens for a player offline.
     */
    fun armDeathGuard(target: UUID, seconds: Int)

    /**
     * Like [armDeathGuard], for the deaths [peril] covers only (M2 review, finding 11): a levitation arms
     * the guard for a fall, not for a zombie's blow. [armDeathGuard] without a peril covers [Peril.ANY].
     * A fake that does not tell perils apart arms for any.
     */
    fun armDeathGuard(target: UUID, seconds: Int, peril: Peril) = armDeathGuard(target, seconds)

    /** Whether a death is the Narrator's doing and the inventory is kept, for the `PlayerDied` event. Server thread only. */
    fun deathAttribution(victim: ServerPlayer, source: DamageSource): DeathAttribution

    /** The Narrator's effects still active on a player (for `inspect_player`, the situation and the briefing). Any thread. */
    fun activeOn(uuid: UUID): List<ActiveEffect>

    /** `/narrator protect`: protects [uuid] or lifts the protection; persisted with [name] for operators. */
    fun setProtected(uuid: UUID, name: String, on: Boolean)

    /** The caps at the chaos level in force, read at each call (`/narrator reload` may change the level). Any thread. */
    fun caps(): Caps

    /** The Narrator's attribute modifiers: applied, reapplied after a death or a join, removed when due. Server thread. */
    val attributes: AttributeLedger

    /** The effects the Narrator put on players, told apart from the others. Server thread. */
    val effects: EffectLedger

    /** Confiscations: taken with their trace, given back when due. Server thread. */
    val items: ItemLedger

    /** The Narrator's creatures: marked, counted against the cap, removed when due. */
    val mobs: NarratorMobs

    /** What `run_command` may run. */
    val commands: CommandPolicy

    /** What `spawn` may summon. */
    val spawns: SpawnPolicy

    /**
     * How many more deaths the Narrator may cause [player] now (the death guard's quota: the chaos
     * level's [Caps.narratorKillsPerHour] minus the Narrator's kills in the player's last played hour);
     * 0 for a protected player. For a player online (the full quota for one offline). Any thread.
     */
    fun deathsLeft(player: UUID): Int

    /**
     * Lifts now everything the Narrator inflicted on [player] (M4 §7.1): its harmful effects and changes,
     * its confiscations (given back now; never a stake, an offering or what is owed), its harmful creatures
     * summoned for them, the traps holding them, and the episode. What was lifted, readable (`slowness II
     * lifted`, `3x diamond returned`); empty when there was nothing to pardon. Decision `guard pardoned`.
     * Server thread.
     */
    fun pardon(player: ServerPlayer, caller: Caller): List<String>

    /**
     * The Narrator's grace on [player] until [until] (M4 §7.1): until then [hostile] refuses
     * ([Rules.GRACE]) and the Narrator's creatures and explosions spare them. [Verdict.Denied] ([Rules.FAVOR_LIMIT])
     * when a grace ended less than `MechanicsLimits.GRACE_GAP_MINUTES` ago. Decision `guard graced`. Server thread.
     */
    fun grace(player: ServerPlayer, until: Long, caller: Caller): Verdict

    /**
     * Whether the Narrator inflicts something on [player] now: an episode, a harmful effect or change, a
     * confiscation, a harmful creature, a trap. A joker is played only then. Server thread.
     */
    fun isPunished(player: ServerPlayer): Boolean

    /**
     * Saves [player] from a death the engine refuses (a duel, M4 §5.5): the death guard's own rescue (out of
     * the danger, to the respawn point or safe ground, fire out, air, health, no fall damage for a while),
     * without counting the quota. Decision `death_guard rescued` with [why]. Server thread.
     */
    fun rescue(player: ServerPlayer, why: String)

    /** What limits the Narrator with each of [players] now (M4 §4.5, the `Pacing` and `Offerings` lines), in the order given. Any thread. */
    fun pacing(players: List<UUID>, now: Long): List<PlayerPacing>
}

/**
 * What limits the Narrator with one player now (M4 §4.5).
 *
 * @property punishedThisHour new punishments in the last hour, against [perHour] (`fair_share`).
 * @property resting the categories resting against them (`same_punishment`), until when (epoch milliseconds).
 * @property graceUntil the end of their grace, if they are under it.
 * @property offerings their offerings waiting for an answer (`O-<id>`: the confiscation's id).
 */
data class PlayerPacing(
    val player: UUID,
    val punishedThisHour: Int,
    val perHour: Int,
    val resting: Map<PunishmentCategory, Long>,
    val graceUntil: Long?,
    val offerings: List<Confiscation>,
)

/**
 * @property narrator the death is attributed to the Narrator.
 * @property keptInventory the player keeps their inventory (always true for a death the Narrator caused).
 */
data class DeathAttribution(val narrator: Boolean, val keptInventory: Boolean)

/**
 * One of the Narrator's effects on a player.
 *
 * @property kind `effect`, `attribute`, `confiscation` or `mob`; from M4 also `trap`, and the escrows that
 *   are not punishments: `stake`, `offering`, `owed` (never [harmful]).
 * @property what e.g. `slowness II`, `scale x0.5`, `3x minecraft:diamond`, `4 minecraft:zombie`.
 * @property until when it ends, epoch milliseconds.
 * @property harmful part of a punishment (a hostile action).
 */
data class ActiveEffect(val kind: String, val what: String, val until: Long, val harmful: Boolean)
