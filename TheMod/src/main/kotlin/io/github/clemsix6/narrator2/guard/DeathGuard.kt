package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.tool.action.Teleports
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.Locale

/**
 * The death guard (M1_M2 §10.3): the Narrator kills a player at most [Caps.narratorKillsPerHour] times
 * per hour that player has played, the player keeps their inventory and experience when it does, and
 * beyond the quota the death is prevented.
 *
 * - **Arming**: any harmful action arms the guard for the player ([arm]), for a number of seconds of the
 *   player's own play time (the `PLAY_TIME` stat), saved with the player: it covers the same ticks as the
 *   effects it protects from, even across a restart. Each arming names its [Peril], what the action can
 *   plausibly kill with.
 * - **Attribution**: a death is the Narrator's when the source or direct entity is one of its creatures or
 *   something one of them owns ([CreatureShield.isNarratorsDoing]: a vex, an arrow), or when the guard
 *   covers the cause of the death and no other player dealt the blow. An operator's `/kill`
 *   (`generic_kill`) never is.
 * - **At `ALLOW_DEATH`** (before the totem): quota left → the death goes on and the player is marked
 *   ([GuardState.protectedDeath], on disk); quota spent → the death is refused once per episode ([revive]):
 *   the player keeps 1 health, the guard stays armed 5 more seconds, and a player who fell into the void
 *   is taken out of it. A second death of the same episode goes on, the inventory kept: an armed guard is
 *   never an immortality.
 * - **At `AFTER_DEATH`**: the death happened (no totem saved the player): it is counted.
 * - The keep-inventory mixins read the mark on the dying player, then on the old player at respawn
 *   ([keepsInventory]): the mark is the only state they need, and it lives in the player's file until
 *   the respawn clears it, restarts and disconnections included.
 * - **Falls**: a player the Narrator leaves in the air (the end of its levitation) or takes out of the
 *   void lands without fall damage ([cushionFall]).
 *
 * Server thread only.
 */
class DeathGuard(
    private val states: GuardStates,
    private val caps: () -> Caps,
    private val decisions: Decisions,
    private val clock: Clock,
    private val attributes: AttributeLedger,
) {
    private val log = Log.guard

    /**
     * Deaths of [player] by [peril] in the next [seconds] of their play time are the Narrator's; never
     * shortens a longer arming.
     */
    fun arm(player: ServerPlayer, seconds: Int, peril: Peril = Peril.ANY) {
        val until = playTime(player) + seconds.coerceAtLeast(0) * TICKS_PER_SECOND
        states.update(player) { state ->
            if ((state.perils[peril.id] ?: 0L) >= until) {
                state
            } else {
                state.copy(armedUntil = maxOf(state.armedUntil, until), perils = state.perils + (peril.id to until))
            }
        }
    }

    /** Whether the guard is armed for [player] now, for any peril. */
    fun isArmed(player: ServerPlayer): Boolean = states.of(player).armedUntil > playTime(player)

    /** Until which value of [player]'s `PLAY_TIME` the guard covers [peril]; 0 when it never did. */
    fun armedFor(player: ServerPlayer, peril: Peril): Long = states.of(player).perils[peril.id] ?: 0L

    /**
     * Whether a death of [player] by [source] is the Narrator's doing. Killed by another player, it is
     * that player's doing, armed or not: the death guard never shields anyone from PvP.
     */
    fun attributed(player: ServerPlayer, source: DamageSource): Boolean {
        if (source.`is`(DamageTypes.GENERIC_KILL)) return false
        if (CreatureShield.isNarratorsDoing(source.entity) || CreatureShield.isNarratorsDoing(source.directEntity)) return true
        val killer = source.entity
        if (killer is Player && killer !== player) return false
        val now = playTime(player)
        return states.of(player).perils.any { (id, until) -> until > now && Peril.of(id)?.covers(source) == true }
    }

    /**
     * `ALLOW_DEATH` for a player about to die: whether the death may happen. A death attributed to the
     * Narrator marks the player (inventory kept) while the quota lasts; beyond it, the death is refused
     * once per episode.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        if (!attributed(player, source)) {
            clearMark(player)
            return true
        }
        val now = playTime(player)
        val cap = caps().narratorKillsPerHour
        val state = states.of(player)
        val recent = recentKills(state.narratorKills, now)
        // Beyond the quota, a death is refused once per episode; the next one goes on, like one within
        // the quota: an armed guard never makes a player immortal, and they keep their inventory.
        if (recent.size >= cap && state.revivedUntil <= now && revive(player, source, recent, cap, now)) return false
        states.update(player) { it.copy(protectedDeath = true, narratorKills = recent) }
        return true
    }

    /**
     * Refuses the death of [player] by [source], beyond the quota: they keep 1 health, the guard stays
     * armed [PREVENTED_REARM_SECONDS] more seconds (a lasting hazard, lava or fire, is still the
     * Narrator's), and no other death is refused until this armed stretch ends. A player who fell into
     * the void is taken out of it first ([rescue]); false when there is nowhere to take them, and the
     * death goes on.
     */
    private fun revive(player: ServerPlayer, source: DamageSource, recent: List<Long>, cap: Int, now: Long): Boolean {
        val cause = source.typeHolder().registeredName
        val health = player.health
        // Alive before anything moves them; given back when they cannot be saved, so the death goes on as it was.
        player.health = 1f
        val rescued = if (source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            rescue(player) ?: run {
                player.health = health
                return false
            }
        } else {
            null
        }
        arm(player, PREVENTED_REARM_SECONDS)
        val state = states.update(player) { it.copy(protectedDeath = false, revivedUntil = it.armedUntil) }
        val nextFree = recent.minOrNull()?.let { (it + HOUR_TICKS - now) / TICKS_PER_SECOND }
        decisions.record(
            log, DecisionRecord.Kind.DEATH_GUARD, "prevented", player.plainTextName,
            reason = "$cause: the Narrator's quota of $cap deaths per played hour is spent; kept alive at 1 health" +
                (rescued?.let { " and taken out of the void to ${where(player, it)}" } ?: "") +
                "; one revival per episode: another death within ${(state.revivedUntil - now) / TICKS_PER_SECOND} s of play goes on" +
                (nextFree?.let { " (the quota comes back in $it s of play)" } ?: ""),
            detail = buildJsonObject {
                put("cause", cause)
                put("cap", cap)
                put("recent_kills", recent.size)
                put("revived_until", state.revivedUntil)
                if (rescued != null) put("rescued_to", where(player, rescued))
            },
        )
        return true
    }

    /**
     * Takes [player] out of the void: to their respawn point as the game finds it (their bed or anchor,
     * without using a charge; the world spawn otherwise), else onto safe ground in the column they fell
     * through ([Teleports.landing]). Their fall is cushioned. Returns where they are, or null when there
     * was nowhere to go.
     */
    private fun rescue(player: ServerPlayer): Vec3? {
        val home = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
        // Rebuilt without the "no respawn block" flag: the player is not respawning, and must not be told so.
        val spot = TeleportTransition(home.newLevel(), home.position(), Vec3.ZERO, home.yRot(), home.xRot(), home.relatives(), TeleportTransition.DO_NOTHING)
        val moved = player.teleport(spot) != null ||
            Teleports.landing(player.level(), player, player.blockX, player.blockZ, null)?.let { Teleports.move(player, it) } == true
        if (!moved) {
            log.warn("could not take ${player.plainTextName} out of the void: no respawn point, no ground in their column")
            return null
        }
        player.resetFallDistance()
        player.deltaMovement = Vec3.ZERO
        cushionFall(player, turnId = null)
        return player.position()
    }

    /**
     * [player] may fall from high up through no doing of their own: the Narrator's levitation ended, or
     * they were taken out of the void. No fall damage for [CUSHION_SECONDS], longer when a fall from where
     * they are to the bottom of the world lasts longer, through the attribute ledger (it ends by itself,
     * even after a restart), and the guard armed as long for a fall. A longer cushion of the Narrator's is
     * kept. Returns the seconds covered.
     */
    fun cushionFall(player: ServerPlayer, turnId: Long?): Int {
        val seconds = CUSHION_SECONDS + fallSeconds(player.y - player.level().minY, player.gravity)
        val until = clock.millis() + seconds * 1_000L
        val key = AttributeKey.FALL_DAMAGE_MULTIPLIER
        val kept = attributes.on(player).firstOrNull { it.attribute == key.id }
            ?.takeIf { !it.harmful && it.expiresAt >= until && attributes.value(player, key) == 0.0 }
        if (kept == null) {
            attributes.apply(player, key, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, until, harmful = false, turnId = turnId)
        }
        arm(player, seconds, Peril.FALL)
        return seconds
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
                "${state.narratorKills.size} of $cap in the last played hour" +
                (if (state.narratorKills.size > cap) " (beyond the quota: this episode's one revival was already spent)" else ""),
            detail = buildJsonObject {
                put("cause", source.typeHolder().registeredName)
                put("cap", cap)
                put("recent_kills", state.narratorKills.size)
                put("play_time", now)
            },
        )
    }

    /** Forgets armings and a revival that ran out, and the kills older than a played hour: the saved state stays small. Once a second. */
    fun tidy(player: ServerPlayer) {
        val state = states.of(player)
        if (state.armedUntil == 0L && state.perils.isEmpty() && state.revivedUntil == 0L && state.narratorKills.isEmpty()) return
        val now = playTime(player)
        val tidied = state.copy(
            armedUntil = if (state.armedUntil > now) state.armedUntil else 0L,
            perils = state.perils.filterValues { it > now },
            revivedUntil = if (state.revivedUntil > now) state.revivedUntil else 0L,
            narratorKills = recentKills(state.narratorKills, now),
        )
        if (tidied != state) states.update(player) { tidied }
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

    /** `x 12.5, y 64, z -7.5 in overworld`, for decisions. */
    private fun where(player: ServerPlayer, at: Vec3): String =
        "x %.1f, y %.1f, z %.1f in %s".format(Locale.ROOT, at.x, at.y, at.z, player.level().dimension().identifier().path)

    companion object {
        const val TICKS_PER_SECOND = 20

        /** One played hour, in ticks of the `PLAY_TIME` stat. */
        const val HOUR_TICKS = 72_000L

        /** How long the guard stays armed after it prevented a death. */
        const val PREVENTED_REARM_SECONDS = 5

        /** A fall the Narrator caused is cushioned at least this long (M2 review, finding 1). */
        const val CUSHION_SECONDS = 30

        /** The longest fall [fallSeconds] counts: a player who barely falls (no gravity) is not cushioned for ever. */
        private const val MAX_FALL_SECONDS = 900

        /** What the air keeps of a falling player's speed at each tick (`LivingEntity.travel`). */
        private const val AIR_DRAG = 0.98

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

        /**
         * How long a fall of [height] blocks lasts from a standstill at [gravity] (blocks per tick per
         * tick; 0.08 for a player), in whole seconds, rounded up; at most [MAX_FALL_SECONDS].
         */
        fun fallSeconds(height: Double, gravity: Double): Int {
            var speed = 0.0
            var fallen = 0.0
            var ticks = 0
            while (fallen < height && ticks < MAX_FALL_SECONDS * TICKS_PER_SECOND) {
                speed = (speed + gravity) * AIR_DRAG
                fallen += speed
                ticks++
            }
            return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND
        }
    }
}
