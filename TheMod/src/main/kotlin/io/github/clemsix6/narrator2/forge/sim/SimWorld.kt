package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.BlockInfo
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import java.time.Instant
import java.util.Locale
import java.util.Random
import java.util.UUID

/**
 * The simulated world of the bench (M5 §7): players, a sparse map of blocks, creatures, and a clock that moves only
 * with the ticks. No physics, no collisions, no chunks: everything is loaded; the blocks are air from y = 64 up and
 * stone below, but for those a scenario or the module put. Creatures move by their motion, kept tick after tick until
 * the module changes it (no friction, no fall), or walk straight to where `moveTo` sends them. One bench uses it, on
 * the bench's worker thread.
 */
internal class SimWorld {
    /** Ticks run since the bench started. */
    var tick: Int = 0
        private set

    /** The bench's clock, epoch milliseconds: [START] then 50 ms a tick. */
    val now: Long get() = START + tick * ForgeLimits.BENCH_TICK_MS

    /** By lower-case name, in the order they joined (a player who joins again goes last). */
    private val players = LinkedHashMap<String, SimPlayer>()
    private val blocks = HashMap<Cell, String>()

    /** By UUID, in the order they appeared; a creature gone is removed. */
    val creatures = LinkedHashMap<String, SimCreature>()
    private val ids = Random(SEED)

    /** One tick more: the creatures move. */
    fun advance() {
        tick++
        creatures.values.forEach { it.step() }
    }

    /** The player [name] the bench knows, online or not; case does not matter. */
    fun player(name: String?): SimPlayer? = name?.let { players[key(it)] }

    /** The player [name] online, or null. */
    fun online(name: String?): SimPlayer? = player(name)?.takeIf { it.online }

    /** The players online, in the order they joined. */
    fun online(): List<SimPlayer> = players.values.filter { it.online }

    /**
     * [name] online at [at]: a new player (20 health, 20 food, survival, on the ground, looking ahead), or one the
     * bench knows, moved there and online again. Returns whether they were not online before (they just joined).
     */
    fun join(name: String, at: Place): Boolean {
        val known = players[key(name)]
        if (known != null && known.online) {
            known.at = at
            return false
        }
        players.remove(key(name))
        val player = known ?: SimPlayer(name, UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString(), at)
        player.at = at
        player.online = true
        players[key(name)] = player
        return true
    }

    /** The block at [cell]: what was put there, else air from y = [GROUND_Y] up and stone below. */
    fun block(cell: Cell): String = blocks[cell] ?: if (cell.y >= GROUND_Y) AIR else STONE

    fun setBlock(cell: Cell, block: String) {
        blocks[cell] = block
    }

    fun blockInfo(cell: Cell): BlockInfo {
        val block = block(cell)
        val air = isAir(block)
        val liquid = block in LIQUIDS
        return BlockInfo(cell, block, air, liquid, !air && !liquid && block !in NOT_SOLID, false)
    }

    /** The y of the first free block above the highest block that is not air at [x], [z] of [dimension]. */
    fun surface(dimension: String, x: Int, z: Int): Int {
        val column = blocks.keys.filter { it.dimension == dimension && it.x == x && it.z == z }
        var y = maxOf(GROUND_Y - 1, column.maxOfOrNull { it.y } ?: Int.MIN_VALUE)
        while (y > MIN_Y && isAir(block(Cell(dimension, x, y, z)))) y--
        return y + 1
    }

    /** A new creature of [type] at [at]: wild, or the deployment's own when [own] (summoned for it). */
    fun addCreature(type: String, at: Place, name: String?, own: Boolean): SimCreature {
        val creature = SimCreature(UUID(ids.nextLong(), ids.nextLong()).toString(), type, at, name)
        creature.own = own
        creature.summoned = own
        creatures[creature.uuid] = creature
        return creature
    }

    companion object {
        /** When the bench's clock starts: an evening of M5 §1.2. */
        val START: Long = Instant.parse("2026-10-19T18:30:00Z").toEpochMilli()

        /** The first air above the stone. */
        const val GROUND_Y = 64

        /** The lowest y the bench looks at for a surface. */
        const val MIN_Y = -64

        /** The seed of the bench's own randomness (the creatures' UUIDs): runs are reproducible. */
        const val SEED = 52L

        const val AIR = "air"
        const val STONE = "stone"
        const val OVERWORLD = "overworld"
        private val AIRS = setOf(AIR, "cave_air", "void_air")
        private val LIQUIDS = setOf("water", "lava")
        private val NOT_SOLID = setOf("cobweb", "short_grass", "tall_grass", "fern", "snow", "torch", "vine")

        fun isAir(block: String): Boolean = block in AIRS

        private fun key(name: String): String = name.lowercase(Locale.ROOT)
    }
}

/** A player of the bench, as a scenario sets them up. */
internal class SimPlayer(val name: String, val uuid: String, var at: Place) {
    var online = false
    var health = MAX_HEALTH
    var food = MAX_FOOD
    var onGround = true
    var sneaking = false
    var pitch = 0f
    var heldItem = SimWorld.AIR

    /** Items given by the scenario (`Bench.give`), by id. */
    val items = LinkedHashMap<String, Int>()

    fun info(): PlayerInfo = PlayerInfo(
        name, uuid, at, health, MAX_HEALTH, food, "survival", onGround, sneaking, false, false, false, 0f, pitch, heldItem, false,
    )

    companion object {
        const val MAX_HEALTH = 20f
        const val MAX_FOOD = 20
    }
}

/** A creature of the bench: wild (a scenario's), or the deployment's own (summoned for it, or claimed). */
internal class SimCreature(val uuid: String, val type: String, var at: Place, var name: String?) {
    var own = false

    /** Summoned by `spawn` for the deployment: it vanishes when it is released or the deployment ends. */
    var summoned = false

    /** A claimed creature: where it was claimed, where it goes back when released, and its name then. */
    var claimedAt: Place? = null
    var nameBefore: String? = null

    /** When a summoned creature vanishes (its lifetime), epoch milliseconds of the bench; null for none. */
    var vanishesAt: Long? = null

    var gravity = true
    var glow = false
    var target: String? = null

    /** Its motion, blocks a tick, kept until changed. */
    var vx = 0.0
    var vy = 0.0
    var vz = 0.0

    /** Where it walks to (`moveTo`), and how fast; null when it does not walk. */
    var walkTo: Place? = null
    var speed = 0.0

    fun info(): CreatureInfo = CreatureInfo(uuid, type, at, HEALTH, HEALTH, name, own)

    /** One tick: it walks 0.2 × speed block toward [walkTo], straight; or it moves by its motion. */
    fun step() {
        val to = walkTo
        if (to != null) {
            val distance = at.distance(to)
            val stride = WALK_PER_TICK * speed
            if (distance <= stride || distance.isInfinite()) {
                if (!distance.isInfinite()) at = to
                walkTo = null
            } else {
                at = at.offset((to.x - at.x) / distance * stride, (to.y - at.y) / distance * stride, (to.z - at.z) / distance * stride)
            }
        } else if (vx != 0.0 || vy != 0.0 || vz != 0.0) {
            at = at.offset(vx, vy, vz)
        }
    }

    companion object {
        /** Every creature of the bench has this health: the bench does not know the game's creatures. */
        const val HEALTH = 20f

        /** Blocks a tick that `moveTo` walks at speed 1. */
        const val WALK_PER_TICK = 0.2
    }
}
