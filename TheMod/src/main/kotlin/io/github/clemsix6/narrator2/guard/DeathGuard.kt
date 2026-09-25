package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.player.Player
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The death guard (M1_M2 §10.3): the Narrator kills a player at most [Caps.narratorKillsPerHour] times
 * per hour that player has played, the player keeps their inventory and experience when it does, and
 * beyond the quota the death is prevented.
 *
 * - **Arming**: any action that can kill arms the guard for the player ([arm]), for a number of seconds
 *   of the player's own play time (the `PLAY_TIME` stat), saved with the player: it covers the same
 *   ticks as the effects it protects from, even across a restart.
 * - **Attribution**: a death is the Narrator's when the guard is armed, or when the source or direct
 *   entity carries the `narrator2` tag. An operator's `/kill` (`generic_kill`) never is.
 * - **At `ALLOW_DEATH`** (before the totem): quota left → the death goes on and the player is marked
 *   ([GuardState.protectedDeath], on disk); quota spent → the death is refused, the player keeps 1 health
 *   and the guard stays armed 5 more seconds, so a lasting hazard (lava, fire) cannot finish the job.
 *   The void cannot be survived: a death there is always a protected one.
 * - **At `AFTER_DEATH`**: the death happened (no totem saved the player): it is counted.
 * - The keep-inventory mixins read the mark on the dying player, then on the old player at respawn
 *   ([keepsInventory]): the mark is the only state they need, and it lives in the player's file until
 *   the respawn clears it, restarts and disconnections included.
 *
 * Server thread only.
 */
class DeathGuard(
    private val states: GuardStates,
    private val caps: () -> Caps,
    private val decisions: Decisions,
    private val clock: Clock,
) {
    private val log = Log.guard

    /** When each player's last prevented death was recorded, epoch milliseconds: a hazard fires many times. */
    private val lastPrevented = ConcurrentHashMap<UUID, Long>()

    /** Deaths of [player] in the next [seconds] of their play time are the Narrator's; never shortens a longer arming. */
    fun arm(player: ServerPlayer, seconds: Int) {
        val until = playTime(player) + seconds.coerceAtLeast(0) * TICKS_PER_SECOND
        states.update(player) { if (it.armedUntil >= until) it else it.copy(armedUntil = until) }
    }

    /** Whether the guard is armed for [player] now. */
    fun isArmed(player: ServerPlayer): Boolean = states.of(player).armedUntil > playTime(player)

    /** Whether a death of [player] by [source] is the Narrator's doing. */
    fun attributed(player: ServerPlayer, source: DamageSource): Boolean =
        !source.`is`(DamageTypes.GENERIC_KILL) &&
            (isArmed(player) || NarratorMobs.isNarrators(source.entity) || NarratorMobs.isNarrators(source.directEntity))

    /**
     * `ALLOW_DEATH` for a player about to die: whether the death may happen. A death attributed to the
     * Narrator marks the player (inventory kept) while the quota lasts, and is refused beyond it.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        if (!attributed(player, source)) {
            clearMark(player)
            return true
        }
        val now = playTime(player)
        val cap = caps().narratorKillsPerHour
        val recent = recentKills(states.of(player).narratorKills, now)
        if (recent.size < cap || source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            states.update(player) { it.copy(protectedDeath = true, narratorKills = recent) }
            return true
        }
        player.health = 1f
        arm(player, PREVENTED_REARM_SECONDS)
        val at = clock.millis()
        val last = lastPrevented[player.uuid]
        if (last == null || at - last >= PREVENTED_LOG_INTERVAL_MS) {
            lastPrevented[player.uuid] = at
            val nextFree = recent.minOrNull()?.let { (it + HOUR_TICKS - now) / TICKS_PER_SECOND }
            decisions.record(
                log, DecisionRecord.Kind.DEATH_GUARD, "prevented", player.plainTextName,
                reason = "${source.typeHolder().registeredName}: the Narrator's quota of $cap deaths per played hour is spent; " +
                    "kept alive at 1 health" + (nextFree?.let { " (next death allowed in $it s of play)" } ?: ""),
                detail = buildJsonObject {
                    put("cause", source.typeHolder().registeredName)
                    put("cap", cap)
                    put("recent_kills", recent.size)
                },
            )
        } else {
            log.debug { "prevented ${player.plainTextName} again: ${source.typeHolder().registeredName}" }
        }
        return false
    }

    /** `AFTER_DEATH` for a player who died: a protected death counts against the quota. */
    fun afterDeath(player: ServerPlayer, source: DamageSource) {
        if (!keepsInventory(player)) return
        val now = playTime(player)
        val state = states.update(player) { it.copy(narratorKills = recentKills(it.narratorKills, now) + now) }
        val cap = caps().narratorKillsPerHour
        decisions.record(
            log, DecisionRecord.Kind.DEATH_GUARD, "counted", player.plainTextName,
            reason = "${source.typeHolder().registeredName}: a death caused by the Narrator; inventory and experience kept; " +
                "${state.narratorKills.size} of $cap in the last played hour",
            detail = buildJsonObject {
                put("cause", source.typeHolder().registeredName)
                put("cap", cap)
                put("recent_kills", state.narratorKills.size)
                put("play_time", now)
            },
        )
    }

    /** A player joined: a mark left on a living player (a totem saved them) is stale. */
    fun onJoin(player: ServerPlayer) {
        if (!player.isDeadOrDying) clearMark(player)
    }

    /** The mark of a player who respawned is cleared: the inventory has been carried over. */
    fun onRespawn(player: ServerPlayer) {
        clearMark(player)
    }

    /** Clears [player]'s mark, keeping the copy readable from other threads up to date. */
    private fun clearMark(player: ServerPlayer) {
        if (keepsInventory(player)) states.update(player) { it.copy(protectedDeath = false) }
    }

    companion object {
        const val TICKS_PER_SECOND = 20

        /** One played hour, in ticks of the `PLAY_TIME` stat. */
        const val HOUR_TICKS = 72_000L

        /** How long the guard stays armed after it prevented a death. */
        const val PREVENTED_REARM_SECONDS = 5

        private const val PREVENTED_LOG_INTERVAL_MS = 5_000L

        /**
         * Whether [player] keeps their inventory and experience at this death: they are marked as dying
         * under the death guard. Called by the keep-inventory mixins, with the dying player and, at
         * respawn, with the old player object. Server thread.
         */
        @JvmStatic
        fun keepsInventory(player: Player): Boolean = GuardStates.read(player).protectedDeath

        /**
         * Clears [player]'s mark without a running Narrator (it failed to start): a mark must never
         * outlive the respawn, or a later ordinary death would keep the inventory.
         */
        fun clearMarkWithoutGuards(player: Player) {
            val state = GuardStates.read(player)
            if (state.protectedDeath) GuardStates.write(player, state.copy(protectedDeath = false))
        }

        /** [player]'s play time: the `PLAY_TIME` stat, in ticks. */
        fun playTime(player: ServerPlayer): Long = player.stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME).toLong()

        /**
         * The Narrator's kills within the last played hour at play time [now]. A kill recorded after
         * [now] (the stats were reset) counts as a kill at [now], so it still expires an hour later.
         */
        fun recentKills(kills: List<Long>, now: Long): List<Long> = kills.map { minOf(it, now) }.filter { now - it < HOUR_TICKS }

        /** How many more deaths the Narrator may cause at play time [now] with a cap of [cap] per hour. */
        fun quotaLeft(kills: List<Long>, now: Long, cap: Int): Int = (cap - recentKills(kills, now).size).coerceAtLeast(0)
    }
}
