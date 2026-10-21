package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.ModelText
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
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.tool.ToolText.dimension
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes what the Director reads about the game (M1_M2 §12.6): English, compact, one line per event,
 * dated relative to the `now` of the block that holds it (`[-36s]`). The memory's signals have theirs
 * (M3 §11.3): `DUE C-45 promise → …`, `NOTE N-9 coherence: …`, `RETURN …`, `ANNIVERSARY …`; the mechanics'
 * too (M4 §4.4): `CHALLENGE C-50 won by …`, `JUDGE …`, `DUEL …`, `RULE …`, `OFFERING …`, `JOKER …`.
 *
 * Whatever a player or a model wrote goes through the common escape, `core/ModelText`, so that no one
 * can forge an `<operator>` or `<engine>` tag nor start a line of their own: texts are quoted ([quote]:
 * between double quotes, with `"` and `\` escaped, line breaks turned into spaces, at most [QUOTE_MAX]
 * characters, `<` `>` replaced by `‹` `›`); player names ([who]), a mob's name tag, the triage's
 * annotation and a grief's count are escaped the same way, without quotes. Ids lose their
 * `minecraft:` prefix, dimensions too (`overworld`, `the_nether`, `the_end`); durations are written as
 * everywhere else (`tool/ToolText`: `36s`, `4m05s`, `1h12m`).
 *
 * @param taskStartedAt when a task started (epoch milliseconds), to say how long it took; null when unknown.
 */
class EventFormatter(zone: ZoneId, private val taskStartedAt: (Long) -> Long? = { null }) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone)

    /** `21:32:41`, in the server's time zone. */
    fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms))

    /**
     * The `<events now=… since=…>` block of a wake-up: one line per item, oldest first; `since` is the
     * oldest item's time. [skipped] counts by kind the older items the inbox could not keep: they come
     * first, as one line ([Inbox]).
     */
    fun events(items: List<Item>, now: Long, skipped: Map<String, Int> = emptyMap()): String {
        val since = items.minOfOrNull { it.at } ?: now
        return block("events now=\"${time(now)}\" since=\"${time(since)}\"", "events", skippedLines(skipped) + lines(items, now))
    }

    /** The `<new_events now=…>` block placed after the tool results, during a turn; [skipped] as in [events]. */
    fun newEvents(items: List<Item>, now: Long, skipped: Map<String, Int> = emptyMap()): String =
        block("new_events now=\"${time(now)}\"", "new_events", skippedLines(skipped) + lines(items, now))

    /**
     * The `<heartbeat now=… window=… hint=…>` block: what the players did during [windowMs], one line per
     * player (escaped: it holds their names); [hint] is what the reflexes found worth a word, left out
     * when they found nothing; then the [idea] of the storyteller's programme the heartbeat carries, if any (M6 §5.6,
     * [idea]).
     */
    fun heartbeat(summary: List<String>, windowMs: Long, hint: String?, now: Long, idea: Idea? = null): String {
        val minutes = ((windowMs + 30_000) / 60_000).coerceAtLeast(1)
        val attributes = "heartbeat now=\"${time(now)}\" window=\"${minutes}m\"" + (hint?.let { " hint=\"${plain(it)}\"" } ?: "")
        return block(attributes, "heartbeat", summary.map(ModelText::escape) + listOfNotNull(idea?.let(::idea)))
    }

    /**
     * `Idea: P-41 challenge (Bartok, Mimosa) « La course au pic »: <fit> — <pitch>`, at most [IDEA_LINE_MAX] characters:
     * the fit cut to [IDEA_FIT_MAX], the pitch to what is left (`everyone` for an idea without players).
     */
    fun idea(idea: Idea): String {
        val players = idea.players.joinToString { who(it) }.ifEmpty { "everyone" }
        val head = "Idea: ${idea.ref} ${idea.kind.id} ($players) « ${plain(idea.title)} »: ${ModelText.escape(ModelText.cut(idea.fit, IDEA_FIT_MAX))}"
        val room = IDEA_LINE_MAX - head.length - IDEA_PITCH_SEPARATOR.length
        return if (room < IDEA_PITCH_MIN) ModelText.cut(head, IDEA_LINE_MAX) else head + IDEA_PITCH_SEPARATOR + ModelText.escape(ModelText.cut(idea.pitch, room))
    }

    /** The lines of [items] that are events (whispers are notes, not lines), oldest first. */
    fun lines(items: List<Item>, now: Long): List<String> = items.sortedBy { it.at }.mapNotNull { line(it, now) }

    /** `[-36s] CHAT Bartok (…): "…"`; null for a whisper, which reaches the model as an operator note. */
    fun line(item: Item, now: Long): String? {
        val body = when (val signal = item.signal) {
            is DirectorSignal.Event -> event(signal.envelope, signal.annotation)
            is DirectorSignal.TaskDone -> task(signal)
            is DirectorSignal.ReminderDue -> reminder(signal)
            is DirectorSignal.MemoryDue -> "DUE ${memory(signal.line)}"
            is DirectorSignal.MemoryNote -> "NOTE ${signal.ref} ${signal.kind.id}: ${memory(signal.text)}"
            is DirectorSignal.MemoryCue -> "${signal.kind.name} ${memory(signal.line)}"
            is DirectorSignal.Mechanic -> "${signal.kind.name} ${mechanic(signal)}"
            else -> return null
        }
        return "[-${duration(now - item.at)}] $body"
    }

    /**
     * A line written by the memory (M3 §11.3): already escaped by its producer, escaped again on one line
     * all the same (escaping twice changes nothing) and cut to [MEMORY_LINE_MAX] characters.
     */
    private fun memory(text: String): String = ModelText.escape(ModelText.cut(text, MEMORY_LINE_MAX))

    /**
     * A line written by the mechanics engine (M4 §4.4): like [memory], cut to [MECHANIC_LINE_MAX] characters,
     * [RULE_LINE_MAX] for a rule's (its `notify` note and the fact that fired it).
     */
    private fun mechanic(signal: DirectorSignal.Mechanic): String {
        val max = if (signal.kind == MechanicSignal.RULE) RULE_LINE_MAX else MECHANIC_LINE_MAX
        return ModelText.escape(ModelText.cut(signal.line, max))
    }

    /**
     * `REMINDER R-3 set 10m ago: "…"`; a reminder that came due while the server was down says so (M4 §15.1):
     * `REMINDER R-3 set 3h30m ago, due 3h00m ago (late: the server was down): "…"`.
     */
    private fun reminder(signal: DirectorSignal.ReminderDue): String {
        val late = signal.dueAt?.let { ", due ${duration(signal.at - it)} ago (late: the server was down)" }.orEmpty()
        return "REMINDER R-${signal.id} set ${duration(signal.at - signal.setAt)} ago$late: ${quote(signal.note)}"
    }

    /** One event, without its date. [annotation] comes from the salience: the chat triage, or a grief's count. */
    fun event(envelope: Envelope, annotation: String?): String = when (val event = envelope.event) {
        is ChatMessage -> {
            val kind = if (event.channel == ChatChannel.PRAY) "PRAY" else "CHAT"
            "$kind ${who(event.player)}${annotation?.let { " ${ModelText.escape(it)}" }.orEmpty()}: ${quote(event.text)}"
        }
        is NarratorSpoke -> {
            val by = if (event.channel == NarratorChannel.OPERATOR) "through operator ${plain(event.operator ?: "?")}" else "yourself"
            val to = event.recipients?.joinToString { who(it) } ?: "all"
            "YOU SAID ($by) to $to: ${quote(event.text)}"
        }
        is PlayerDied -> buildString {
            append("DEATH ${who(event.player)}: ${id(event.cause)}")
            event.killer?.let { append(" by ${entity(it)}") }
            event.weapon?.let { append(" with ${id(it)}") }
            append(" (${pos(event.pos)}), inventory ${if (event.keptInventory) "kept" else "dropped"}")
            if (event.narrator) append(", caused by you")
        }
        is EntityKilled ->
            (if (event.pvp) "PVP" else "KILL") + " ${who(event.player)} killed ${entity(event.victim)}" +
                (event.weapon?.let { " with ${id(it)}" }.orEmpty()) + " (${pos(event.pos)})"
        is HealthCritical -> "LOW HEALTH ${who(event.player)}: ${number(event.health)} hp (${pos(event.pos)})"
        is PlayerJoined -> {
            val notes = listOfNotNull("first time".takeIf { event.firstTime }, "operator".takeIf { event.isOp })
            "JOIN ${who(event.player)}${if (notes.isEmpty()) "" else " (${notes.joinToString()})"} (${pos(event.pos)})"
        }
        is PlayerLeft -> "LEAVE ${who(event.player)} after ${duration(event.sessionSeconds * 1_000)}"
        is AdvancementEarned -> {
            val frame = event.frame.takeIf { it != "task" }?.let { "$it, " }.orEmpty()
            "ADVANCEMENT ${who(event.player)}: ${quote(event.title, TITLE_MAX)} ($frame${pos(event.pos)})"
        }
        is StructureEntered ->
            "STRUCTURE ${who(event.player)} entered ${id(event.structure)}${if (event.firstVisit) ", first visit" else ""} (${pos(event.pos)})"
        is BiomeEntered -> "BIOME ${who(event.player)} entered ${id(event.biome)}${if (event.firstVisit) ", first visit" else ""} (${pos(event.pos)})"
        is BlockBroken ->
            if (annotation != null) {
                "GRIEF ${who(event.player)} ${ModelText.escape(annotation)} (${pos(event.pos)})"
            } else {
                "MINED ${who(event.player)} ${id(event.block)} (${pos(event.pos)})"
            }
        is ExplosionHappened -> buildString {
            append("EXPLOSION ${event.source?.let(::id) ?: "block"}")
            event.author?.let { append(" by ${entity(it)}") }
            if (event.nearby.isNotEmpty()) append(" near ${event.nearby.joinToString { who(it) }}")
            append(
                if (event.blocksHit == 0) {
                    ": no block destroyed"
                } else {
                    ": ${event.blocksHit} blocks destroyed, ${event.playerBlocksHit} of them built by players"
                },
            )
            append(" (${pos(event.pos)})")
        }
        is LootChestOpened -> "LOOT ${who(event.player)} opened ${id(event.lootTable)} (${pos(event.pos)})"
        is Teleported -> "TELEPORT ${who(event.player)} jumped ${event.distance.toLong()} blocks to (${pos(event.pos)})"
        is DimensionChanged -> "DIMENSION ${who(event.player)} ${dimension(event.from.dim)} → ${dimension(event.pos.dim)} via ${event.via} (${pos(event.pos)})"
        is PlayerRespawned -> "RESPAWN ${who(event.player)}${if (event.alive) " left the End" else ""} (${pos(event.pos)})"
        else -> generic(event)
    }

    private fun generic(event: GameEvent): String =
        event.type.uppercase(Locale.ROOT) + (event.player?.let { " ${who(it)}" }.orEmpty()) + (event.pos?.let { " (${pos(it)})" }.orEmpty())

    private fun task(done: DirectorSignal.TaskDone): String {
        val head = "TASK T-${done.taskId} ${done.role.id}"
        val took = taskStartedAt(done.taskId)?.let { " in ${duration(done.at - it)}" }.orEmpty()
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

    /** `(412 older events were left out: too many were waiting; MINED 300, CHAT 100, BIOME 12)`, or nothing. */
    private fun skippedLines(skipped: Map<String, Int>): List<String> {
        val total = skipped.values.sum()
        if (total <= 0) return emptyList()
        val kinds = skipped.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key} ${it.value}" }
        return listOf("($total older ${if (total == 1) "event was" else "events were"} left out: too many were waiting; $kinds)")
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

        /** Longest line of the memory after its keyword (`DUE`, `NOTE`, `RETURN`, `ANNIVERSARY`); a note holds 300 characters at most. */
        const val MEMORY_LINE_MAX = 600

        /** Longest line of the mechanics engine after its keyword (`CHALLENGE`, `DUEL`…): a challenge's end, its stakes and bets. */
        const val MECHANIC_LINE_MAX = MechanicsLimits.EVENT_LINE

        /** Longest `RULE` line after its keyword: a rule's `notify` note (200 at most) and the fact that fired it. */
        const val RULE_LINE_MAX = MechanicsLimits.RULE_LINE

        /** Longest task result given to the Director (a worker's report is at most 3 000 characters). */
        const val TASK_RESULT_MAX = 3_000

        /** Longest `Idea:` line of a heartbeat (M6 §5.6): short, the card is one `inspire` away. */
        const val IDEA_LINE_MAX = 300

        /** Longest fit in an `Idea:` line (an idea's holds 200). */
        const val IDEA_FIT_MAX = 100

        /** Shortest pitch worth adding to an `Idea:` line. */
        private const val IDEA_PITCH_MIN = 30
        private const val IDEA_PITCH_SEPARATOR = " — "

        private const val INDENT = "    "

        /**
         * [text] as a quote ([ModelText.quote]): between double quotes, `\` and `"` escaped, line breaks
         * and control characters turned into spaces, `<` `>` replaced by `‹` `›`, cut to [max] characters with `…`.
         */
        fun quote(text: String, max: Int = QUOTE_MAX): String = ModelText.quote(text, max)

        /** [text] on one line, escaped ([ModelText.escape]), at most [TITLE_MAX] characters: for names and words that are not quoted. */
        fun plain(text: String): String = ModelText.escape(ModelText.cut(text, TITLE_MAX))

        /** A player's name, escaped ([ModelText.name]): `<engine>` is a valid name on an offline server. */
        fun who(player: PlayerRef): String = ModelText.name(player.name)

        /** [text] as indented lines under a line, each escaped ([ModelText.escape]): a long report. */
        fun indented(text: String): String = text.lines().joinToString("\n") { INDENT + ModelText.escape(it) }

        /**
         * The kind of [item], as its line starts (`CHAT`, `PRAY`, `MINED`, `TASK`…; addressed chat is
         * `CHAT to you`): how the inbox counts the items it could not keep.
         */
        fun kind(item: Item): String = when (val signal = item.signal) {
            is DirectorSignal.Event -> when (val event = signal.envelope.event) {
                is ChatMessage -> when {
                    event.channel == ChatChannel.PRAY -> "PRAY"
                    item.isAddressedChat -> "CHAT to you"
                    else -> "CHAT"
                }
                is NarratorSpoke -> "YOU SAID"
                is PlayerDied -> "DEATH"
                is EntityKilled -> if (event.pvp) "PVP" else "KILL"
                is HealthCritical -> "LOW HEALTH"
                is PlayerJoined -> "JOIN"
                is PlayerLeft -> "LEAVE"
                is AdvancementEarned -> "ADVANCEMENT"
                is StructureEntered -> "STRUCTURE"
                is BiomeEntered -> "BIOME"
                is BlockBroken -> if (signal.annotation != null) "GRIEF" else "MINED"
                is ExplosionHappened -> "EXPLOSION"
                is LootChestOpened -> "LOOT"
                is Teleported -> "TELEPORT"
                is DimensionChanged -> "DIMENSION"
                is PlayerRespawned -> "RESPAWN"
                else -> event.type.uppercase(Locale.ROOT)
            }
            is DirectorSignal.TaskDone -> "TASK"
            is DirectorSignal.ReminderDue -> "REMINDER"
            is DirectorSignal.OperatorWhisper -> "WHISPER"
            is DirectorSignal.MemoryDue -> "DUE"
            is DirectorSignal.MemoryNote -> "NOTE"
            is DirectorSignal.MemoryCue -> signal.kind.name
            is DirectorSignal.Mechanic -> signal.kind.name
            is DirectorSignal.StyleNote -> "TONE CHECK"
            else -> "OTHER"
        }

        /** An id without its `minecraft:` namespace, e.g. `diamond_ore`; other namespaces are kept. */
        fun id(id: String): String = id.removePrefix("minecraft:")

        /** `overworld -120 64 330`, `the_nether 40 15 -12` ([ToolText.dimension]). */
        fun pos(pos: Pos): String = "${dimension(pos.dim)} ${pos.x} ${pos.y} ${pos.z}"

        /** A player by name; a mob by type, with its name tag quoted. */
        fun entity(entity: EntityRef): String =
            entity.player?.let(::who) ?: (id(entity.type) + (entity.name?.let { " ${quote(it, TITLE_MAX)}" }.orEmpty()))

        /** A health or damage number: `3`, `3.5`. */
        fun number(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
    }
}
