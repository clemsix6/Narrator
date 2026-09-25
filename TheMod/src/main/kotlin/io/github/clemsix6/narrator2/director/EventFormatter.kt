package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes what the Director reads about the game (M1_M2 §12.6): English, compact, one line per event,
 * dated relative to the `now` of the block that holds it (`[-36s]`).
 *
 * Whatever a player or a model wrote is quoted and escaped ([quote]): between double quotes, with `"`
 * and `\` escaped, line breaks turned into spaces, at most [QUOTE_MAX] characters, and `<` `>` replaced
 * by `‹` `›`, so that no one can forge an `<operator>` or `<engine>` tag. Names that are not player
 * names (a mob's name tag) are escaped the same way, without quotes ([plain]). Ids lose their
 * `minecraft:` prefix; dimensions are `overworld`, `nether` and `end`.
 *
 * @param taskStartedAt when a task started (epoch milliseconds), to say how long it took; null when unknown.
 */
class EventFormatter(zone: ZoneId, private val taskStartedAt: (Long) -> Long? = { null }) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone)

    /** `21:32:41`, in the server's time zone. */
    fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms))

    /** The `<events now=… since=…>` block of a wake-up: one line per item, oldest first; `since` is the oldest item's time. */
    fun events(items: List<Item>, now: Long): String {
        val since = items.minOfOrNull { it.at } ?: now
        return block("events now=\"${time(now)}\" since=\"${time(since)}\"", "events", lines(items, now))
    }

    /** The `<new_events now=…>` block placed after the tool results, during a turn. */
    fun newEvents(items: List<Item>, now: Long): String = block("new_events now=\"${time(now)}\"", "new_events", lines(items, now))

    /**
     * The `<heartbeat now=… window=… hint=…>` block: what the players did during [windowMs], one line per
     * player; [hint] is what the reflexes found worth a word, left out when they found nothing.
     */
    fun heartbeat(summary: List<String>, windowMs: Long, hint: String?, now: Long): String {
        val minutes = ((windowMs + 30_000) / 60_000).coerceAtLeast(1)
        val attributes = "heartbeat now=\"${time(now)}\" window=\"${minutes}m\"" + (hint?.let { " hint=\"${plain(it)}\"" } ?: "")
        return block(attributes, "heartbeat", summary)
    }

    /** The lines of [items] that are events (whispers are notes, not lines), oldest first. */
    fun lines(items: List<Item>, now: Long): List<String> = items.sortedBy { it.at }.mapNotNull { line(it, now) }

    /** `[-36s] CHAT Bartok (…): "…"`; null for a whisper, which reaches the model as an operator note. */
    fun line(item: Item, now: Long): String? {
        val body = when (val signal = item.signal) {
            is DirectorSignal.Event -> event(signal.envelope, signal.annotation)
            is DirectorSignal.TaskDone -> task(signal)
            is DirectorSignal.ReminderDue -> "REMINDER R-${signal.id} set ${ago(signal.at - signal.setAt)} ago: ${quote(signal.note)}"
            else -> return null
        }
        return "[-${ago(now - item.at)}] $body"
    }

    /** One event, without its date. [annotation] comes from the salience: the chat triage, or a grief's count. */
    fun event(envelope: Envelope, annotation: String?): String = when (val event = envelope.event) {
        is ChatMessage -> {
            val kind = if (event.channel == ChatChannel.PRAY) "PRAY" else "CHAT"
            "$kind ${event.player.name}${annotation?.let { " $it" }.orEmpty()}: ${quote(event.text)}"
        }
        is NarratorSpoke -> {
            val by = if (event.channel == NarratorChannel.OPERATOR) "through operator ${plain(event.operator ?: "?")}" else "yourself"
            val to = event.recipients?.joinToString { it.name } ?: "all"
            "YOU SAID ($by) to $to: ${quote(event.text)}"
        }
        is PlayerDied -> buildString {
            append("DEATH ${event.player.name}: ${id(event.cause)}")
            event.killer?.let { append(" by ${entity(it)}") }
            event.weapon?.let { append(" with ${id(it)}") }
            append(" (${pos(event.pos)}), inventory ${if (event.keptInventory) "kept" else "dropped"}")
            if (event.narrator) append(", caused by you")
        }
        is EntityKilled ->
            (if (event.pvp) "PVP" else "KILL") + " ${event.player.name} killed ${entity(event.victim)}" +
                (event.weapon?.let { " with ${id(it)}" }.orEmpty()) + " (${pos(event.pos)})"
        is HealthCritical -> "LOW HEALTH ${event.player.name}: ${number(event.health)} hp (${pos(event.pos)})"
        is PlayerJoined -> {
            val notes = listOfNotNull("first time".takeIf { event.firstTime }, "operator".takeIf { event.isOp })
            "JOIN ${event.player.name}${if (notes.isEmpty()) "" else " (${notes.joinToString()})"} (${pos(event.pos)})"
        }
        is PlayerLeft -> "LEAVE ${event.player.name} after ${ago(event.sessionSeconds * 1_000)}"
        is AdvancementEarned -> {
            val frame = event.frame.takeIf { it != "task" }?.let { "$it, " }.orEmpty()
            "ADVANCEMENT ${event.player.name}: ${quote(event.title, TITLE_MAX)} ($frame${pos(event.pos)})"
        }
        is StructureEntered ->
            "STRUCTURE ${event.player.name} entered ${id(event.structure)}${if (event.firstVisit) ", first visit" else ""} (${pos(event.pos)})"
        is BiomeEntered -> "BIOME ${event.player.name} entered ${id(event.biome)}${if (event.firstVisit) ", first visit" else ""} (${pos(event.pos)})"
        is BlockBroken ->
            if (annotation != null) {
                "GRIEF ${event.player.name} $annotation (${pos(event.pos)})"
            } else {
                "MINED ${event.player.name} ${id(event.block)} (${pos(event.pos)})"
            }
        is ExplosionHappened -> buildString {
            append("EXPLOSION ${event.source?.let(::id) ?: "block"}")
            event.author?.let { append(" by ${entity(it)}") }
            if (event.nearby.isNotEmpty()) append(" near ${event.nearby.joinToString { it.name }}")
            append(
                if (event.blocksHit == 0) {
                    ": no block destroyed"
                } else {
                    ": ${event.blocksHit} blocks destroyed, ${event.playerBlocksHit} of them built by players"
                },
            )
            append(" (${pos(event.pos)})")
        }
        is LootChestOpened -> "LOOT ${event.player.name} opened ${id(event.lootTable)} (${pos(event.pos)})"
        is Teleported -> "TELEPORT ${event.player.name} jumped ${event.distance.toLong()} blocks to (${pos(event.pos)})"
        is DimensionChanged -> "DIMENSION ${event.player.name} ${dim(event.from.dim)} → ${dim(event.pos.dim)} via ${event.via} (${pos(event.pos)})"
        is PlayerRespawned -> "RESPAWN ${event.player.name}${if (event.alive) " left the End" else ""} (${pos(event.pos)})"
        else -> generic(event)
    }

    private fun generic(event: GameEvent): String =
        event.type.uppercase(Locale.ROOT) + (event.player?.let { " ${it.name}" }.orEmpty()) + (event.pos?.let { " (${pos(it)})" }.orEmpty())

    private fun task(done: DirectorSignal.TaskDone): String {
        val head = "TASK T-${done.taskId} ${done.role.id}"
        val took = taskStartedAt(done.taskId)?.let { " in ${ago(done.at - it)}" }.orEmpty()
        return when (done.status) {
            TaskRecord.Status.DONE -> {
                val result = done.result.trim()
                if (result.isEmpty()) {
                    "$head done$took, empty result"
                } else if ('\n' !in result && result.length <= QUOTE_MAX) {
                    "$head done$took: ${quote(result)}"
                } else {
                    "$head done$took:\n" + indented(result.take(TASK_RESULT_MAX))
                }
            }
            TaskRecord.Status.CANCELLED -> "$head cancelled$took"
            TaskRecord.Status.TIMEOUT -> "$head timed out$took"
            else -> "$head failed$took: ${quote(done.result)}"
        }
    }

    private fun block(open: String, close: String, lines: List<String>): String =
        buildString {
            append('<').append(open).append(">\n")
            lines.forEach { append(it).append('\n') }
            append("</").append(close).append('>')
        }

    companion object {
        /** Longest quote of a player's text, in characters. */
        const val QUOTE_MAX = 300

        /** Longest title (advancement) or name (mob name tag). */
        const val TITLE_MAX = 60

        /** Longest task result given to the Director (a worker's report is at most 3 000 characters). */
        const val TASK_RESULT_MAX = 3_000

        private const val INDENT = "    "

        /**
         * [text] as a quote: between double quotes, `\` and `"` escaped, line breaks and control
         * characters turned into spaces, `<` `>` replaced by `‹` `›`, cut to [max] characters with `…`.
         */
        fun quote(text: String, max: Int = QUOTE_MAX): String {
            val escaped = cut(flat(text), max).replace("\\", "\\\\").replace("\"", "\\\"")
            return "\"${angles(escaped)}\""
        }

        /** [text] on one line, with `<` `>` replaced by `‹` `›`: for names and words that are not quoted. */
        fun plain(text: String): String = angles(cut(flat(text), TITLE_MAX))

        /** [text] as indented lines under a line, `<` `>` replaced: a long report. */
        fun indented(text: String): String =
            text.lines().joinToString("\n") { INDENT + angles(it.map { c -> if (c.isISOControl()) ' ' else c }.joinToString("")) }

        /** An id without its `minecraft:` namespace, e.g. `diamond_ore`; other namespaces are kept. */
        fun id(id: String): String = id.removePrefix("minecraft:")

        /** `overworld`, `nether`, `end`, or the id of another dimension. */
        fun dim(dim: String): String = when (dim) {
            "minecraft:the_nether" -> "nether"
            "minecraft:the_end" -> "end"
            else -> id(dim)
        }

        /** `overworld -120 64 330`. */
        fun pos(pos: Pos): String = "${dim(pos.dim)} ${pos.x} ${pos.y} ${pos.z}"

        /** A player by name; a mob by type, with its name tag quoted. */
        fun entity(entity: EntityRef): String =
            entity.player?.name ?: (id(entity.type) + (entity.name?.let { " ${quote(it, TITLE_MAX)}" }.orEmpty()))

        /** A duration: `36s`, `1m30s`, `12m`, `1h05m`. */
        fun ago(ms: Long): String {
            val seconds = (ms.coerceAtLeast(0) + 500) / 1_000
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 600 && seconds % 60 != 0L -> "${seconds / 60}m%02ds".format(Locale.ROOT, seconds % 60)
                seconds < 3_600 -> "${seconds / 60}m"
                else -> "${seconds / 3_600}h%02dm".format(Locale.ROOT, seconds % 3_600 / 60)
            }
        }

        /** A health or damage number: `3`, `3.5`. */
        fun number(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

        private fun flat(text: String): String = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")

        private fun angles(text: String): String = text.replace('<', '‹').replace('>', '›')

        private fun cut(text: String, max: Int): String {
            if (text.codePointCount(0, text.length) <= max) return text
            return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…"
        }
    }
}
