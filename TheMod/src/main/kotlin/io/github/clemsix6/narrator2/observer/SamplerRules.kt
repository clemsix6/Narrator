package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.StructureLeft
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.event.UndergroundChanged
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The pure part of the position sampler (M1_M2 §7.4): thresholds and rules, without the game. The
 * sampler (`PositionSampler`) reads the world into an [Observation]; a [PlayerTrack] decides what to
 * publish from it. Times are milliseconds of the Observer's clock: server ticks × [SamplerRules.MS_PER_TICK].
 */

/** The thresholds of M1_M2 §7.4 (OBSERVER_API.md §4), and the sleep hook's night-skip rule. */
object SamplerRules {
    /** One server tick, in milliseconds of the Observer's clock. */
    const val MS_PER_TICK = 50L

    /** Each player is examined once every this many ticks (once a second), spread over the ticks by index. */
    const val EXAMINE_EVERY_TICKS = 20

    /** A row once the player is this far from the last row, horizontally… */
    const val MOVED_HORIZONTAL = 16.0

    /** …or vertically. */
    const val MOVED_VERTICAL = 8

    /** A row after this long since the last row… */
    const val INTERVAL_MS = 60_000L

    /** …if the player moved at least this far. */
    const val INTERVAL_MIN_DISTANCE = 2.0

    /** A presence row after this long without any row (`reason = idle`). */
    const val HEARTBEAT_MS = 300_000L

    /** A jump this long between two examinations in the same dimension is a teleport. */
    const val TELEPORT_DISTANCE = 64.0

    /** At most one row this often, except the first after a join or respawn, dimension changes and teleports. */
    const val MIN_ROW_GAP_MS = 3_000L

    /** Underground: no sky light and this far below sea level… */
    const val UNDERGROUND_DEPTH = 8

    /** …for this long before it changes. */
    const val UNDERGROUND_STABLE_MS = 10_000L

    /** A new biome must be read at this many examinations in a row. */
    const val BIOME_STABLE_CHECKS = 3

    /** The structure is looked up this often, and whenever the player enters another chunk. */
    const val STRUCTURE_INTERVAL_MS = 5_000L

    /** Health at or below this is critical… */
    const val HEALTH_CRITICAL = 4.0

    /** …and the alert is re-armed once health is back to this. */
    const val HEALTH_REARM = 8.0

    /** Idle after this long without an action and without moving [IDLE_DISTANCE]. */
    const val IDLE_AFTER_MS = 300_000L

    /** Moving less than this does not count as moving, for idleness. */
    const val IDLE_DISTANCE = 2.0

    /**
     * When the world's clock moved this many ticks further than the server ticked while a player slept,
     * the night was skipped (the server moved the clock to the wake-up time).
     */
    const val NIGHT_SKIP_MIN_JUMP = 1_000L

    /** No sky light at the player's position, and well below sea level: a roof or a tree is not underground. */
    fun isUnderground(canSeeSky: Boolean, y: Int, seaLevel: Int): Boolean = !canSeeSky && y < seaLevel - UNDERGROUND_DEPTH

    /**
     * Whether a player's wake-up followed a night skip: the world's clock advanced by [clockAdvance]
     * ticks while the server ticked [ticksElapsed] times. The server moves the clock to the wake-up
     * time right before waking the sleepers, whereas `Level.isBrightOutside()` still reads the night's
     * sky darkness at that moment (it is refreshed after the wake-up).
     */
    fun nightSkipped(clockAdvance: Long, ticksElapsed: Long): Boolean = clockAdvance - ticksElapsed >= NIGHT_SKIP_MIN_JUMP
}

/**
 * What the sampler reads about a player at one examination.
 *
 * @property biome biome id at the player's position.
 * @property structureChecked the structure was looked up at this examination ([PlayerTrack.structureDue]).
 * @property structure the structure the player is in, when [structureChecked]; ignored otherwise.
 * @property underground right now (no hysteresis): [SamplerRules.isUnderground].
 * @property skyLight and [blockLight]: light levels (0-15) at the player's position.
 * @property sinceActionMs milliseconds since the player's last action (a move, a click, a key; `ServerPlayer.getLastActionTime`).
 */
data class Observation(
    val pos: Pos,
    val biome: String?,
    val structureChecked: Boolean,
    val structure: String?,
    val underground: Boolean,
    val skyLight: Int?,
    val blockLight: Int?,
    val health: Double,
    val food: Int,
    val vehicle: String?,
    val sinceActionMs: Long,
)

/** Where a player has been before; the game keeps it in `VisitedPlaces`. */
interface Visits {
    /** Records that the player is in biome [id]; true the first time. */
    fun biome(id: String): Boolean

    /** Records that the player is in structure [id]; true the first time. */
    fun structure(id: String): Boolean
}

/**
 * What one examination publishes, in order: a teleport, the `positions` row, then the facts derived
 * from it (biome, structure, underground, health, idleness).
 *
 * @property inactiveMs how long the player has neither acted nor moved [SamplerRules.IDLE_DISTANCE].
 */
class Examination(val events: List<GameEvent>, val inactiveMs: Long) {
    val row: PositionSampled? get() = events.firstNotNullOfOrNull { it as? PositionSampled }
}

/**
 * The sampler's memory of one online player, from their join to their departure. Pure: fed with
 * [Observation]s, it returns what to publish. Not thread-safe (the server thread owns it).
 *
 * The `positions` row and the player's live state take each reading as it is; the derived events use
 * hysteresis (three readings for a biome, ten seconds for underground, a re-arm level for health).
 * At the first examination the biome and structure are adopted silently, unless the player has never
 * been there: a new player's first biome is an event, a returning player's spawn biome is not.
 */
class PlayerTrack(private val player: PlayerRef) {
    private var forced: SampleReason? = SampleReason.JOIN
    private var lastSeen: Pos? = null

    private var lastRow: Pos? = null
    private var lastRowAt = 0L
    private var lastRowVehicle: String? = null

    private var biome: String? = null
    private var biomeCandidate: String? = null
    private var biomeCandidateChecks = 0

    /** The structure the player is in, as of the last lookup. */
    var structure: String? = null
        private set
    private var structureKnown = false
    private var structureCheckedAt = 0L
    private var structureChunk: Pos? = null

    private var underground: Boolean? = null
    private var undergroundSince: Long? = null

    private var healthArmed = true

    private var anchor: Pos? = null
    private var anchorAt = 0L

    /** No action and no move for [SamplerRules.IDLE_AFTER_MS]. */
    var idle = false
        private set
    private var idleSince = 0L

    /** After a respawn or a dimension change: the next examination writes a row for [reason], and no jump is a teleport. */
    fun restart(reason: SampleReason) {
        forced = reason
        lastSeen = null
    }

    /** Whether the structure must be looked up for a player at [pos]: first time, another chunk, or every few seconds. */
    fun structureDue(pos: Pos, now: Long): Boolean =
        !structureKnown || chunkOf(pos) != structureChunk || now - structureCheckedAt >= SamplerRules.STRUCTURE_INTERVAL_MS

    /** Takes one reading; [visits] records first visits. */
    fun examine(obs: Observation, now: Long, visits: Visits): Examination {
        val pos = obs.pos
        val previous = lastSeen
        val jump = if (forced == null && previous != null && previous.dim == pos.dim) distance(previous, pos) else 0.0
        val teleport = if (jump >= SamplerRules.TELEPORT_DISTANCE) Teleported(player, pos, previous!!, jump) else null

        val derived = ArrayList<GameEvent>(0)
        underground(obs, now, derived)
        biome(obs, visits, derived)
        structure(obs, now, visits, derived)
        health(obs, derived)
        val inactiveMs = idleness(obs, now, derived)

        val reason = rowReason(pos, obs.vehicle, now, teleport != null)
        val row = reason?.let {
            PositionSampled(player, pos, obs.biome, structure, obs.underground, obs.skyLight, obs.blockLight, obs.health, obs.food, obs.vehicle, it)
        }
        if (row != null) {
            lastRow = pos
            lastRowAt = now
            lastRowVehicle = obs.vehicle
        }
        forced = null
        lastSeen = pos
        return Examination(listOfNotNull(teleport, row) + derived, inactiveMs)
    }

    private fun rowReason(pos: Pos, vehicle: String?, now: Long, teleported: Boolean): SampleReason? {
        forced?.let { return it }
        val last = lastRow ?: return SampleReason.JOIN
        if (last.dim != pos.dim) return SampleReason.DIMENSION
        if (teleported) return SampleReason.TELEPORT
        val since = now - lastRowAt
        return when {
            since < SamplerRules.MIN_ROW_GAP_MS -> null
            vehicle != lastRowVehicle -> SampleReason.VEHICLE
            horizontal(last, pos) >= SamplerRules.MOVED_HORIZONTAL || abs(pos.y - last.y) >= SamplerRules.MOVED_VERTICAL -> SampleReason.MOVED
            since >= SamplerRules.INTERVAL_MS && distance(last, pos) >= SamplerRules.INTERVAL_MIN_DISTANCE -> SampleReason.INTERVAL
            since >= SamplerRules.HEARTBEAT_MS -> SampleReason.IDLE
            else -> null
        }
    }

    private fun underground(obs: Observation, now: Long, out: MutableList<GameEvent>) {
        val stable = underground
        when {
            stable == null -> underground = obs.underground
            obs.underground == stable -> undergroundSince = null
            else -> {
                val since = undergroundSince ?: now.also { undergroundSince = it }
                if (now - since >= SamplerRules.UNDERGROUND_STABLE_MS) {
                    underground = obs.underground
                    undergroundSince = null
                    out += UndergroundChanged(player, obs.pos, obs.underground)
                }
            }
        }
    }

    private fun biome(obs: Observation, visits: Visits, out: MutableList<GameEvent>) {
        val read = obs.biome ?: return
        when (read) {
            biome -> {
                biomeCandidate = null
                biomeCandidateChecks = 0
            }
            biomeCandidate -> if (++biomeCandidateChecks >= SamplerRules.BIOME_STABLE_CHECKS) {
                biome = read
                biomeCandidate = null
                biomeCandidateChecks = 0
                out += BiomeEntered(player, obs.pos, read, visits.biome(read))
            }
            else -> if (biome == null) {
                biome = read
                if (visits.biome(read)) out += BiomeEntered(player, obs.pos, read, firstVisit = true)
            } else {
                biomeCandidate = read
                biomeCandidateChecks = 1
            }
        }
    }

    private fun structure(obs: Observation, now: Long, visits: Visits, out: MutableList<GameEvent>) {
        if (!obs.structureChecked) return
        structureCheckedAt = now
        structureChunk = chunkOf(obs.pos)
        val found = obs.structure
        if (!structureKnown) {
            structureKnown = true
            structure = found
            if (found != null && visits.structure(found)) out += StructureEntered(player, obs.pos, found, firstVisit = true)
        } else if (found != structure) {
            structure?.let { out += StructureLeft(player, obs.pos, it) }
            found?.let { out += StructureEntered(player, obs.pos, it, visits.structure(it)) }
            structure = found
        }
    }

    private fun health(obs: Observation, out: MutableList<GameEvent>) {
        if (healthArmed && obs.health > 0 && obs.health <= SamplerRules.HEALTH_CRITICAL) {
            healthArmed = false
            out += HealthCritical(player, obs.pos, obs.health)
        } else if (!healthArmed && obs.health >= SamplerRules.HEALTH_REARM) {
            healthArmed = true
        }
    }

    /** Updates idleness; returns how long the player has been inactive. */
    private fun idleness(obs: Observation, now: Long, out: MutableList<GameEvent>): Long {
        val from = anchor
        if (from == null || from.dim != obs.pos.dim || distance(from, obs.pos) >= SamplerRules.IDLE_DISTANCE) {
            anchor = obs.pos
            anchorAt = now
        }
        val inactive = min(obs.sinceActionMs, now - anchorAt).coerceAtLeast(0)
        if (!idle && inactive >= SamplerRules.IDLE_AFTER_MS) {
            idle = true
            idleSince = now - inactive
            out += IdleChanged(player, obs.pos, idle = true, idleSeconds = (inactive / 1000).toInt())
        } else if (idle && inactive < SamplerRules.IDLE_AFTER_MS) {
            idle = false
            out += IdleChanged(player, obs.pos, idle = false, idleSeconds = ((now - inactive - idleSince) / 1000).toInt())
        }
        return inactive
    }

    private companion object {
        fun distance(a: Pos, b: Pos): Double {
            val dx = (a.x - b.x).toDouble()
            val dy = (a.y - b.y).toDouble()
            val dz = (a.z - b.z).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun horizontal(a: Pos, b: Pos): Double {
            val dx = (a.x - b.x).toDouble()
            val dz = (a.z - b.z).toDouble()
            return sqrt(dx * dx + dz * dz)
        }

        /** The chunk of [pos]: its dimension and chunk coordinates (`y` unused). */
        fun chunkOf(pos: Pos) = Pos(pos.dim, pos.x shr 4, 0, pos.z shr 4)
    }
}
