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
}

/**
 * The engine's rules, enforced by code whatever the model asks: protected players, no piling on, the
 * death guard, the ledgers that undo every temporary effect. Tools check here before acting.
 *
 * T09a extends this contract with the caps, the ledgers and the policies.
 */
interface Guards {
    /** The player is protected from every hostile action. Any thread. */
    fun isProtected(uuid: UUID): Boolean

    /**
     * Checks a hostile action of [caller] against [target] (protection, chaos 0, recent death, a
     * punishment of another turn still running) and, when allowed, opens or extends the caller's
     * episode against [target] until [until] (epoch milliseconds). Server thread only.
     */
    fun hostile(target: ServerPlayer, caller: Caller, until: Long): Verdict

    /** Deaths of [target] in the next [seconds] are attributed to the Narrator (any action that can kill arms it). */
    fun armDeathGuard(target: UUID, seconds: Int)

    /** Whether a death is the Narrator's doing and the inventory is kept, for the `PlayerDied` event. Server thread only. */
    fun deathAttribution(victim: ServerPlayer, source: DamageSource): DeathAttribution

    /** The Narrator's effects still active on a player (for `inspect_player`, the situation and the briefing). Any thread. */
    fun activeOn(uuid: UUID): List<ActiveEffect>

    /** `/narrator protect`: protects [uuid] or lifts the protection; persisted with [name] for operators. */
    fun setProtected(uuid: UUID, name: String, on: Boolean)
}

/**
 * @property narrator the death is attributed to the Narrator.
 * @property keptInventory the player keeps their inventory (always true for a death the Narrator caused).
 */
data class DeathAttribution(val narrator: Boolean, val keptInventory: Boolean)

/**
 * One of the Narrator's effects on a player.
 *
 * @property kind `effect`, `attribute`, `confiscation` or `mob`.
 * @property what e.g. `slowness II`, `scale x0.5`, `3x diamond`, `4 zombie`.
 * @property until when it ends, epoch milliseconds.
 * @property harmful part of a punishment (a hostile action).
 */
data class ActiveEffect(val kind: String, val what: String, val until: Long, val harmful: Boolean)
