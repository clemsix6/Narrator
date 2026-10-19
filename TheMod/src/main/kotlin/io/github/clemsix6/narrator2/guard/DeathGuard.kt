package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.Teleports
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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
 * beyond the quota it never kills: the player is rescued.
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
 *   ([GuardState.protectedDeath], on disk); quota spent → the death is refused, every time, and the
 *   player is rescued ([rescue]): taken out of the danger that was killing them, to their respawn point.
 *   A rescue arms nothing: a player who dies again under the same armings is rescued again, and cannot
 *   die over and over where they were, since they are no longer there.
 * - **At `AFTER_DEATH`**: the death happened (no totem saved the player): it is counted.
 * - The keep-inventory mixins read the mark on the dying player, then on the old player at respawn
 *   ([keepsInventory]): the mark is the only state they need, and it lives in the player's file until
 *   the respawn clears it, restarts and disconnections included.
 * - **Falls**: a player the Narrator leaves in the air (the end of its levitation) lands without fall
 *   damage ([cushionFall]), and so does a rescued player.
 *
 * Server thread only.
 */
class DeathGuard(
    private val states: GuardStates,
    private val caps: () -> Caps,
    private val decisions: Decisions,
    private val clock: Clock,
    private val attributes: AttributeLedger,
    private val effects: EffectLedger,
    private val shields: Shields,
) {
    private val log = Log.guard

    /** The players rescued during the current server tick, put out again at its end ([afterTick]). */
    private val rescuedThisTick = mutableListOf<ServerPlayer>()

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
     * and the player rescued, every time.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        if (!attributed(player, source)) {
            clearMark(player)
            return true
        }
        val now = playTime(player)
        val cap = caps().narratorKillsPerHour
        val recent = recentKills(states.of(player).narratorKills, now)
        if (recent.size >= cap) {
            rescueBeyondQuota(player, source, recent, cap, now)
            return false
        }
        states.update(player) { it.copy(protectedDeath = true, narratorKills = recent) }
        return true
    }

    /**
     * Beyond the quota, [player] does not die of [source]: they are taken out of the danger that was
     * killing them ([save]). Decision `death_guard rescued` with the quota.
     */
    private fun rescueBeyondQuota(player: ServerPlayer, source: DamageSource, recent: List<Long>, cap: Int, now: Long) {
        val cause = source.typeHolder().registeredName
        val saved = save(player, "$cause beyond the Narrator's quota of deaths")
        val nextFree = recent.minOrNull()?.let { (it + HOUR_TICKS - now) / TICKS_PER_SECOND }
        decisions.record(
            log, DecisionRecord.Kind.DEATH_GUARD, "rescued", player.plainTextName,
            reason = "$cause: the Narrator's quota of deaths ($cap per played hour) is spent; ${saved.what(player)}; nothing armed" +
                (nextFree?.let { " (the quota comes back in ${ToolText.wait(it * 1_000)} of play)" } ?: "") + saved.warning(),
            detail = buildJsonObject {
                put("cause", cause)
                put("cap", cap)
                put("recent_kills", recent.size)
                saved.into(this, player)
            },
            level = saved.level,
        )
    }

    /**
     * Saves [player] from a death the engine refuses (a duel, M4 §5.5): the same rescue as beyond the quota
     * ([save]), whatever is killing them, without counting anything against the quota nor arming anything.
     * Decision `death_guard rescued` with [why]. Called from `ALLOW_DEATH` (the caller then refuses the death)
     * or at any time. [inPlace]: the danger is already gone (the creatures of a punishment, sent away: M4-POST),
     * and the player is kept where they stand rather than taken to their respawn point. Server thread.
     */
    fun rescue(player: ServerPlayer, why: String, inPlace: Boolean = false) {
        val saved = save(player, why, inPlace)
        decisions.record(
            log, DecisionRecord.Kind.DEATH_GUARD, "rescued", player.plainTextName,
            reason = "$why: ${saved.what(player)}; not counted against the Narrator's quota, nothing armed${saved.warning()}",
            detail = buildJsonObject {
                put("why", why)
                saved.into(this, player)
            },
            level = saved.level,
        )
    }

    /**
     * What a rescue did: where the player was taken ([safety]: how and where, null when they could not be
     * moved), what of the Narrator's was [lifted], and how many times they were rescued lately ([rescues]).
     */
    private class Saved(val safety: Pair<String, Vec3>?, val lifted: List<String>, val health: Float, val rescues: Int) {
        val often: Boolean get() = rescues > RESCUES_BEFORE_WARNING

        /** At the `WARN` level for the operators when the player is rescued too often. */
        val level: Log.Level get() = if (often) Log.Level.WARN else Log.Level.INFO

        /**
         * `taken to respawn point (x …), fire put out, air given, lifted slowness II, 6.0 health, no fall damage for 30s,
         * nothing hostile against them for 5m`; `kept where they stood (x …), …` for a rescue in place.
         */
        fun what(player: ServerPlayer): String =
            (safety?.let { (how, spot) -> "${moved(how)} (${where(player, spot)})" } ?: "could not be moved") +
                ", fire put out, air given" +
                (if (lifted.isEmpty()) "" else ", lifted ${lifted.joinToString()}") +
                ", ${"%.1f".format(Locale.ROOT, health)} health, no fall damage for ${ToolText.duration(CUSHION_SECONDS * 1_000L)}" +
                ", nothing hostile against them for ${ToolText.duration(Guards.RESCUE_SHIELD_MS)}"

        /** `taken to respawn point`, `kept where they stood`. */
        private fun moved(how: String): String = if (how == IN_PLACE) "kept where they stood" else "taken to ${how.replace('_', ' ')}"

        fun warning(): String =
            if (often) "; rescued $rescues times in ${RESCUE_WINDOW_MS / 60_000} min: look at what keeps putting them in danger" else ""

        fun into(detail: JsonObjectBuilder, player: ServerPlayer) {
            safety?.let { (how, spot) ->
                detail.put("rescued_by", how)
                detail.put("rescued_to", where(player, spot))
            }
            detail.put("lifted", JsonArray(lifted.map(::JsonPrimitive)))
            detail.put("health", health)
            detail.put("recent_rescues", rescues)
        }
    }

    /**
     * Takes [player] out of the danger that was killing them. In order: alive again, before anything moves
     * them; the Narrator's harmful effects and modifiers lifted (a levitation would lift them again, a
     * smaller scale changes where they fit); taken to safety ([moveToSafety]), unless [inPlace] (the danger is
     * already gone: they stay where they stand); their fall stopped, their fire
     * put out (again at the end of the tick, [afterTick]), air in their lungs, food if they were starving; no
     * fall damage for [CUSHION_SECONDS] (a favor in the attribute ledger); [RESCUE_HEALTH] health; the mark
     * of a protected death cleared; and a shield of [Guards.RESCUE_SHIELD_MS] because [why]: nothing hostile
     * of the Narrator's against them meanwhile (M4 review, finding 18). Nothing is armed and nothing counted.
     * Each rescue is remembered for [RESCUE_WINDOW_MS]: more than [RESCUES_BEFORE_WARNING] warn the operators
     * that something keeps putting the player in mortal danger, or that they use the rescue as a way home.
     */
    private fun save(player: ServerPlayer, why: String, inPlace: Boolean = false): Saved {
        player.health = 1f
        val effectsLifted = effects.removeHarmful(player).map(EffectLedger::describe)
        val attributesLifted = attributes.resetHarmful(player).map { it.id }
        val safety = if (inPlace) IN_PLACE to player.position() else moveToSafety(player)
        if (safety == null) log.error("could not take ${player.plainTextName} to safety: the game refused to move them; kept alive where they are")
        player.resetFallDistance()
        player.deltaMovement = Vec3.ZERO
        player.clearFire()
        player.airSupply = player.maxAirSupply
        if (player.foodData.foodLevel == 0) player.foodData.foodLevel = RESCUE_FOOD
        cushion(player, CUSHION_SECONDS, turnId = null)
        player.health = RESCUE_HEALTH
        rescuedThisTick += player

        val at = clock.millis()
        val state = states.update(player) { it.copy(protectedDeath = false, rescues = recentRescues(it.rescues, at) + at) }
        shields.shield(player, at + Guards.RESCUE_SHIELD_MS, why)
        return Saved(safety, effectsLifted + attributesLifted, player.health, state.rescues.size)
    }

    /**
     * Takes [player] to their respawn point as the game finds it (their bed or anchor, without using a
     * charge; the world spawn otherwise), else onto safe ground in the column where they are
     * ([Teleports.landing]). Returns how ([RESPAWN_POINT] or [SAFE_GROUND]) and where they are now; null
     * when there was nowhere to go (the game refuses to move a player it is removing).
     */
    private fun moveToSafety(player: ServerPlayer): Pair<String, Vec3>? {
        val home = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
        // Rebuilt without the "no respawn block" flag: the player is not respawning, and must not be told so.
        val spot = TeleportTransition(home.newLevel(), home.position(), Vec3.ZERO, home.yRot(), home.xRot(), home.relatives(), TeleportTransition.DO_NOTHING)
        if (player.teleport(spot) != null) return RESPAWN_POINT to player.position()
        val ground = Teleports.landing(player.level(), player, player.blockX, player.blockZ, null) ?: return null
        return if (Teleports.move(player, ground)) SAFE_GROUND to player.position() else null
    }

    /**
     * End of a server tick: the players rescued during it are put out again. What set them alight may
     * act again after the blow the rescue answered, in the same tick: a player who crossed two blocks of
     * lava is set alight once for each.
     */
    fun afterTick() {
        if (rescuedThisTick.isEmpty()) return
        for (player in rescuedThisTick) if (!player.isRemoved) player.clearFire()
        rescuedThisTick.clear()
    }

    /**
     * [player] may fall from high up through no doing of their own: the Narrator's levitation ended. No
     * fall damage for [CUSHION_SECONDS], longer when a fall from where they are to the bottom of the world
     * lasts longer ([cushion]), and the guard armed as long for a fall. Returns the seconds covered.
     */
    fun cushionFall(player: ServerPlayer, turnId: Long?): Int {
        val seconds = CUSHION_SECONDS + fallSeconds(player.y - player.level().minY, player.gravity)
        cushion(player, seconds, turnId)
        arm(player, seconds, Peril.FALL)
        return seconds
    }

    /**
     * No fall damage for [player] for [seconds], through the attribute ledger (it ends by itself, even
     * after a restart). A longer cushion of the Narrator's is kept.
     */
    private fun cushion(player: ServerPlayer, seconds: Int, turnId: Long?) {
        val until = clock.millis() + seconds * 1_000L
        val key = AttributeKey.FALL_DAMAGE_MULTIPLIER
        val kept = attributes.on(player).firstOrNull { it.attribute == key.id }
            ?.takeIf { !it.harmful && it.expiresAt >= until && attributes.value(player, key) == 0.0 }
        if (kept == null) {
            attributes.apply(player, key, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, until, harmful = false, turnId = turnId)
        }
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

    /** Forgets armings that ran out, the kills older than a played hour and the old rescues: the saved state stays small. Once a second. */
    fun tidy(player: ServerPlayer) {
        val state = states.of(player)
        if (state.armedUntil == 0L && state.perils.isEmpty() && state.rescues.isEmpty() && state.narratorKills.isEmpty()) return
        val now = playTime(player)
        val tidied = state.copy(
            armedUntil = if (state.armedUntil > now) state.armedUntil else 0L,
            perils = state.perils.filterValues { it > now },
            narratorKills = recentKills(state.narratorKills, now),
            rescues = recentRescues(state.rescues, clock.millis()),
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

    companion object {
        /** `x 12.5, y 64, z -7.5 in overworld`, for decisions. */
        private fun where(player: ServerPlayer, at: Vec3): String =
            "x %.1f, y %.1f, z %.1f in %s".format(Locale.ROOT, at.x, at.y, at.z, ToolText.dimension(player.level().dimension().identifier().toString()))

        const val TICKS_PER_SECOND = 20

        /** One played hour, in ticks of the `PLAY_TIME` stat. */
        const val HOUR_TICKS = 72_000L

        /** A fall the Narrator caused is cushioned at least this long (M2 review, finding 1), and a rescued player too. */
        const val CUSHION_SECONDS = 30

        /** The health a rescued player is left with: 3 hearts, or their maximum when it is lower. */
        const val RESCUE_HEALTH = 6f

        /** The food a rescued player who was starving gets: enough not to starve again at once. */
        const val RESCUE_FOOD = 6

        /** Past this many rescues of a player in [RESCUE_WINDOW_MS], each new one is a warning for the operators. */
        const val RESCUES_BEFORE_WARNING = 3

        /** The window of [RESCUES_BEFORE_WARNING], in milliseconds of real time. */
        const val RESCUE_WINDOW_MS = 10 * 60_000L

        /** How a rescue took the player to safety, in its decision: to their respawn point. */
        const val RESPAWN_POINT = "respawn_point"

        /** How a rescue took the player to safety, in its decision: onto safe ground where they were. */
        const val SAFE_GROUND = "safe_ground"

        /** How a rescue took the player to safety, in its decision: nowhere, the danger being gone (`rescue` in place). */
        const val IN_PLACE = "in_place"

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

        /** The rescues within [RESCUE_WINDOW_MS] before [now] (epoch milliseconds). */
        fun recentRescues(rescues: List<Long>, now: Long): List<Long> = rescues.filter { now - it < RESCUE_WINDOW_MS }

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
