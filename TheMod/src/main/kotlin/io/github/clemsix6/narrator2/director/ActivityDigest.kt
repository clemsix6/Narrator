package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.director.EventFormatter.Companion.id
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Traded
import java.time.Clock
import java.util.UUID
import kotlin.math.sqrt

/**
 * What each player did lately, in one English line (M1_M2 §12.4): the heartbeat's matter, the
 * situation's `last 5m` and `list_players`' activity. Fed with every event the salience sees, kept per
 * player in one-minute slices over the last [RETENTION_MINUTES] minutes ([recent]), and in the current
 * heartbeat window, since the last turn or heartbeat ([takeWindow], [mark]).
 *
 * A line reads like `mined 140 (3 diamond_ore y=-54..-51, stone 90), killed 4 zombie, took 18 damage
 * (mob_attack), 90% underground` and holds at most [LINE_MAX] characters. Being underground or idle
 * only qualifies an activity: a player who did nothing else has no line.
 *
 * Any thread: fed by the salience, read by the Director and the tools.
 */
class ActivityDigest(private val clock: Clock) {
    private val lock = Any()
    private val players = HashMap<UUID, Track>() // guarded by lock
    private var markedAt: Long = clock.millis() // guarded by lock

    /** Counts [envelope] for its player, in its minute and in the heartbeat window; ignores what is not activity. */
    fun record(envelope: Envelope) {
        val event = envelope.event
        val player = event.player ?: return
        synchronized(lock) {
            val track = players.getOrPut(player.uuid) { Track(player) }
            track.ref = player
            if (event is IdleChanged) {
                track.idleSince = if (event.idle) envelope.ts - event.idleSeconds * 1_000L else null
                return
            }
            val distance = if (event is PositionSampled) track.moved(event) else 0.0
            listOf(track.slice(envelope.ts / MINUTE_MS), track.window).forEach { it.count(event, distance) }
            prune(clock.millis())
        }
    }

    /** What [player] did in the last [minutes] minutes (at most [RETENTION_MINUTES]); null when nothing. */
    fun recent(player: UUID, minutes: Int): String? = synchronized(lock) {
        val now = clock.millis()
        prune(now)
        val track = players[player] ?: return null
        val from = now / MINUTE_MS - minutes.coerceIn(1, RETENTION_MINUTES) + 1
        val total = Slice(from)
        track.slices.filter { it.minute >= from }.forEach(total::add)
        track.summary(total, now, LINE_MAX)
    }

    /** When the current heartbeat window started: the last turn or heartbeat. */
    val windowStart: Long get() = synchronized(lock) { markedAt }

    /**
     * What each player did in the heartbeat window, one `Name: …` line per active player, and starts a
     * new window. An empty list means everyone was idle.
     */
    fun takeWindow(): List<String> = synchronized(lock) {
        val now = clock.millis()
        val lines = players.values.sortedBy { it.ref.name }.mapNotNull { track ->
            val name = track.ref.name
            track.summary(track.window, now, LINE_MAX - name.length - 2)?.let { "$name: $it" }
        }
        restart(now)
        lines
    }

    /** Starts a new heartbeat window without reading it: a turn just told the Director the situation. */
    fun mark() = synchronized(lock) { restart(clock.millis()) }

    private fun restart(now: Long) {
        markedAt = now
        players.values.forEach { it.window = Slice(0) }
    }

    private fun prune(now: Long) {
        val oldest = now / MINUTE_MS - RETENTION_MINUTES + 1
        players.values.forEach { track -> track.slices.removeAll { it.minute < oldest } }
    }

    /** One player's slices and heartbeat window, and what outlives them. */
    private class Track(var ref: PlayerRef) {
        val slices = ArrayList<Slice>()
        var window = Slice(0)
        var lastPos: Pos? = null
        var idleSince: Long? = null

        fun slice(minute: Long): Slice = slices.lastOrNull()?.takeIf { it.minute == minute } ?: Slice(minute).also { slices += it }

        /** The distance walked since the previous sample; a jump (teleport, respawn, dimension) walks nothing. */
        fun moved(sample: PositionSampled): Double {
            val last = lastPos
            lastPos = sample.pos
            if (last == null || last.dim != sample.pos.dim || sample.reason in JUMPS) return 0.0
            val dx = (sample.pos.x - last.x).toDouble()
            val dy = (sample.pos.y - last.y).toDouble()
            val dz = (sample.pos.z - last.z).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** The line of [total], at most [max] characters; null when the player did nothing. */
        fun summary(total: Slice, now: Long, max: Int): String? {
            val parts = total.parts().toMutableList()
            if (parts.isEmpty()) return null
            if (total.samples >= 3 && total.undergroundSamples > 0) parts += "${total.undergroundSamples * 100 / total.samples}% underground"
            idleSince?.let { parts += "idle ${((now - it) / MINUTE_MS).coerceAtLeast(1)}m" }
            return join(parts, max)
        }
    }

    /** What a player did during one minute (or, summed, a window). */
    private class Slice(val minute: Long) {
        val broken = Counter()
        val oreYs = HashMap<String, IntRange>()
        val placed = Counter()
        var placedAt: Pos? = null
        val killed = Counter()
        var damage = 0.0
        val causes = HashMap<String, Double>()
        var deaths = 0
        var distance = 0.0
        var samples = 0
        var undergroundSamples = 0
        val entered = LinkedHashSet<String>()
        val crafted = Counter()
        var trades = 0

        /** Counts [event]; [distance] is what a position sample walked. */
        fun count(event: GameEvent, distance: Double) {
            when (event) {
                is BlockBroken -> broken(event.block, event.pos.y)
                is BlockPlaced -> if (event.kind == PlaceKind.BLOCK) placed(event.block, event.pos)
                is EntityKilled -> killed.add(event.victim.player?.name ?: id(event.victim.type))
                is DamageTaken -> damage(id(event.damageType), event.amount)
                is PlayerDied -> deaths++
                is PositionSampled -> {
                    this.distance += distance
                    samples++
                    if (event.underground) undergroundSamples++
                }
                is BiomeEntered -> entered.add(id(event.biome))
                is StructureEntered -> entered.add(id(event.structure))
                is ItemCrafted -> crafted.add(id(event.item), event.count)
                is Traded -> trades++
                else -> Unit
            }
        }

        private fun broken(block: String, y: Int) {
            val name = id(block)
            broken.add(name)
            if (isOre(name)) oreYs[name] = oreYs[name]?.let { minOf(it.first, y)..maxOf(it.last, y) } ?: y..y
        }

        private fun placed(block: String, pos: Pos) {
            placed.add(id(block))
            placedAt = pos
        }

        private fun damage(cause: String, amount: Double) {
            damage += amount
            causes[cause] = (causes[cause] ?: 0.0) + amount
        }

        fun add(other: Slice) {
            broken.addAll(other.broken)
            other.oreYs.forEach { (ore, ys) -> oreYs[ore] = oreYs[ore]?.let { minOf(it.first, ys.first)..maxOf(it.last, ys.last) } ?: ys }
            placed.addAll(other.placed)
            other.placedAt?.let { placedAt = it }
            killed.addAll(other.killed)
            damage += other.damage
            other.causes.forEach { (cause, amount) -> causes[cause] = (causes[cause] ?: 0.0) + amount }
            deaths += other.deaths
            distance += other.distance
            samples += other.samples
            undergroundSamples += other.undergroundSamples
            entered += other.entered
            crafted.addAll(other.crafted)
            trades += other.trades
        }

        /** The activities worth a mention, most telling first. */
        fun parts(): List<String> = buildList {
            if (deaths > 0) add(if (deaths == 1) "died" else "died ${deaths}x")
            if (broken.total > 0) add("mined ${broken.total} (${mined()})")
            if (placed.total > 0) {
                val at = placedAt?.let { " near ${it.x} ${it.y} ${it.z}" }.orEmpty()
                add("placed ${placed.total} (${placed.top(3).joinToString { (name, n) -> "$name $n" }})$at")
            }
            if (killed.total > 0) add("killed " + killed.top(3).joinToString { (name, n) -> "$n $name" })
            if (damage >= 1) add("took ${EventFormatter.number(Math.rint(damage))} damage (${causes.maxBy { it.value }.key})")
            if (distance >= MOVE_MIN) add("moved ${distance.toLong()} blocks")
            if (entered.isNotEmpty()) add("entered " + entered.take(3).joinToString())
            if (crafted.total > 0) add("crafted " + crafted.top(2).joinToString { (name, n) -> "$n $name" })
            if (trades > 0) add("traded ${trades}x")
        }

        /** Ores first with their depth, then the most broken blocks: five entries at most. */
        private fun mined(): String {
            val ores = broken.top(Int.MAX_VALUE).filter { isOre(it.first) }.map { (name, n) ->
                val ys = oreYs.getValue(name)
                "$n $name y=" + if (ys.first == ys.last) "${ys.first}" else "${ys.first}..${ys.last}"
            }
            val others = broken.top(Int.MAX_VALUE).filterNot { isOre(it.first) }.map { (name, n) -> "$name $n" }
            return (ores + others).take(5).joinToString()
        }
    }

    /** Counts per name, kept in first-seen order for ties. */
    private class Counter {
        private val counts = LinkedHashMap<String, Int>()
        val total: Int get() = counts.values.sum()

        fun add(name: String, n: Int = 1) {
            counts[name] = (counts[name] ?: 0) + n
        }

        fun addAll(other: Counter) = other.counts.forEach { (name, n) -> add(name, n) }

        fun top(n: Int): List<Pair<String, Int>> = counts.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    companion object {
        /** Minutes of activity kept per player. */
        const val RETENTION_MINUTES = 10

        /** Longest line, in characters. */
        const val LINE_MAX = 200

        /** Distance below which moving about is not an activity. */
        private const val MOVE_MIN = 20.0

        private const val MINUTE_MS = 60_000L

        /** Samples after which the distance from the previous one is a jump, not a walk. */
        private val JUMPS = setOf(SampleReason.JOIN, SampleReason.RESPAWN, SampleReason.DIMENSION, SampleReason.TELEPORT)

        private fun isOre(block: String): Boolean = block.endsWith("_ore") || block == "ancient_debris"

        /** [parts] joined with `, `, as many as fit in [max] characters. */
        private fun join(parts: List<String>, max: Int): String {
            val line = StringBuilder()
            for (part in parts) {
                val next = if (line.isEmpty()) part else ", $part"
                if (line.length + next.length > max) break
                line.append(next)
            }
            return line.toString().ifEmpty { parts.first().take(max) }
        }
    }
}
