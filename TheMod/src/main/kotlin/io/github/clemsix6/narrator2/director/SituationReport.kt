package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.tool.ToolText.dimension
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.id
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.number
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.quote
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.who
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The world's clock and weather, read on the server thread. [time] is `day`, `dusk`, `night` or `dawn`. */
data class WorldTime(val day: Long, val time: String, val weather: String) {
    companion object {
        /** From the overworld's clock ([ticks]) and weather. */
        fun of(ticks: Long, raining: Boolean, thundering: Boolean): WorldTime {
            val tod = Math.floorMod(ticks, TICKS_PER_DAY)
            val time = when {
                tod < 12_000 -> "day"
                tod < 13_000 -> "dusk"
                tod < 23_000 -> "night"
                else -> "dawn"
            }
            val weather = when {
                thundering -> "thunder"
                raining -> "rain"
                else -> "clear"
            }
            return WorldTime(Math.floorDiv(ticks, TICKS_PER_DAY), time, weather)
        }

        private const val TICKS_PER_DAY = 24_000L
    }
}

/** One acting tool call of the Director, for `Your last actions`. */
data class DirectorAction(val at: Long, val tool: String, val targets: List<String>)

/**
 * The `<situation>` block that ends every wake-up message (M1_M2 §12.6): the world, one line per player
 * online, the Narrator's active effects, the deaths it may still cause each player, its tasks and
 * reminders, and its last actions. Every section is written even when empty, so that the model never
 * mistakes a missing source for an empty one. Player names go through the common escape (`core/ModelText`).
 * Then the mechanics' lines ([MechanicsLines], M4 §4.5: challenges, rules, pacing, offerings) and the
 * memory's (M3 §11.3: favor, commitments due soon, players who had nothing from the Narrator for long),
 * each written only when it has something to say.
 */
class SituationReport(private val services: NarratorServices, private val digest: ActivityDigest, zone: ZoneId) {
    private val log = Log.director
    private val formatter = EventFormatter(zone)
    private val hours = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)
    private val mechanics = MechanicsLines(services, zone)

    /**
     * The block at [now]; [world] failed when the server thread could not be read in time.
     *
     * @param actions the Director's last acting tool calls, oldest first.
     */
    fun render(now: Long, world: Result<WorldTime>, reminders: List<PendingReminder>, actions: List<DirectorAction>): String =
        buildString {
            append("<situation now=\"${formatter.time(now)}\"")
            world.onSuccess { append(" day=\"${it.day}\" time=\"${it.time}\" weather=\"${it.weather}\"") }
            append(">\n")
            world.onFailure { append("World: unavailable (${it.message ?: it.javaClass.simpleName})\n") }
            val online = services.players.online
            if (online.isEmpty()) append("No player online.\n")
            online.forEach { append(player(it, now)).append('\n') }
            append("Your active effects: ").append(effects(online)).append('\n')
            append("Deaths you may still cause: ").append(deathsLeft(online)).append('\n')
            append("Tasks: ").append(tasks(now)).append(" · Reminders: ").append(reminders(reminders)).append('\n')
            append("Your last actions: ").append(actions(actions, now)).append('\n')
            mechanics.render(now).forEach { append(it).append('\n') }
            memoryLines(now).forEach { append(it).append('\n') }
            append("</situation>")
        }

    /**
     * The memory's lines (M3 §11.3: `Favor: …`, `Due soon: …`, `Attention: …`), read from its cache: never
     * the database. None without a memory; one line saying why when it fails. Escaped again, one line each.
     */
    private fun memoryLines(now: Long): List<String> {
        val memory = services.memory
        if (!memory.available) return emptyList()
        return try {
            memory.situationLines(now).filter { it.isNotBlank() }.map { ModelText.escape(ModelText.cut(it, MEMORY_LINE_MAX)) }
        } catch (e: Exception) {
            log.error("situation: the memory's lines are unavailable", e)
            listOf("Memory: unavailable (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun player(live: LivePlayer, now: Long): String {
        val parts = mutableListOf<String>()
        val place = StringBuilder(dimension(live.pos.dim))
        if (live.gameMode != "survival") place.append(" ${live.gameMode}")
        if (live.health <= 0f) {
            place.append(", dead")
        } else {
            if (live.underground) place.append(" underground")
            live.structure?.let { place.append(" in ${id(it)}") }
            place.append(" y=${live.pos.y}")
            live.biome?.let { place.append(" ${id(it)}") }
            live.vehicle?.let { place.append(" riding ${id(it)}") }
        }
        parts += place.toString()
        if (live.health > 0f) parts += "hp ${number(live.health.toDouble())}/${number(live.maxHealth.toDouble())} food ${live.food}"
        parts += "online ${duration(now - live.joinedAt)}" + if (live.idle) ", idle" else ""
        if (services.guards.isProtected(live.ref.uuid)) parts += "protected from every hostile action (operators)"
        live.lastDeathAt?.let { died ->
            val until = died + NO_PILE_ON_MS
            if (until > now) parts += "protected from hostile actions until ${hours.format(Instant.ofEpochMilli(until))} (died)"
        }
        digest.recent(live.ref.uuid, RECENT_MINUTES)?.let { parts += "last ${RECENT_MINUTES}m: ${ModelText.escape(it)}" }
        return "${who(live.ref)}: ${parts.joinToString(" · ")}"
    }

    private fun effects(online: List<LivePlayer>): String {
        val effects = online.flatMap { live ->
            services.guards.activeOn(live.ref.uuid).map { "${who(live.ref)} ${ModelText.escape(it.what)} until ${hours.format(Instant.ofEpochMilli(it.until))}" }
        }
        return if (effects.isEmpty()) "none" else effects.joinToString(", ")
    }

    /** The death guard's quota left for each player online (`Guards.deathsLeft`): `Bartok 1, Mimosa 0`. */
    private fun deathsLeft(online: List<LivePlayer>): String =
        if (online.isEmpty()) "none" else online.joinToString(", ") { "${who(it.ref)} ${services.guards.deathsLeft(it.ref.uuid)}" }

    private fun tasks(now: Long): String {
        val open = services.workers.list().filter { !it.status.finished }
        if (open.isEmpty()) return "none"
        return open.joinToString(", ") { task ->
            val state = when (task.status) {
                TaskRecord.Status.RUNNING -> "running ${duration(now - (task.startedAt ?: task.createdAt).toEpochMilli())}"
                else -> task.status.id
            }
            "T-${task.taskId} ${task.role.id} $state"
        }
    }

    private fun reminders(reminders: List<PendingReminder>): String =
        if (reminders.isEmpty()) "none" else reminders.sortedBy { it.at }.joinToString(", ") {
            "R-${it.id} at ${hours.format(Instant.ofEpochMilli(it.at))} ${quote(it.note)}"
        }

    private fun actions(actions: List<DirectorAction>, now: Long): String =
        if (actions.isEmpty()) "none" else actions.joinToString(" · ") { action ->
            "-${duration(now - action.at)} ${action.tool}" + if (action.targets.isEmpty()) "" else "→${action.targets.joinToString(",") { ModelText.name(it) }}"
        }

    companion object {
        /** The window of each player's `last …m` activity. */
        const val RECENT_MINUTES = 5

        /** Hostile actions are refused against a player who died less than this long ago (`Rules.NO_PILE_ON`). */
        const val NO_PILE_ON_MS = 5 * 60_000L

        /** Longest line of the memory in the situation. */
        const val MEMORY_LINE_MAX = 400
    }
}

/**
 * The mechanics' lines of the situation and of the briefing's `<engine_state>` (M4 §4.5), each written only
 * when it has something to say:
 * - `Challenges: …` and `Rules: …`, written by the engine from its snapshot (`Mechanics.situationLines`), when
 *   it runs;
 * - `Pacing: punished this hour Bartok 2/4, Mimosa 0/4 · recent kinds Bartok size (again at 20:39), air (20:51) ·
 *   grace Mimosa until 21:45`: what limits the Narrator with each player online (`Guards.pacing`), with or
 *   without the engine (the categories and the grace are guards);
 * - `Offerings: O-812 Mimosa 10x diamond (answer by 21:15)`: the offerings waiting for an answer.
 *
 * Each line holds [MechanicsLimits.SITUATION_LINE] characters at most, its last items left out and counted
 * (`(+2 more)`; an engine's line is cut), the four lines [MechanicsLimits.SITUATION_TOTAL] together. Never
 * waits: the engine and the guards answer from their snapshots. A source that fails says so on its line,
 * with an `ERROR` logged. Names and the engine's texts go through the common escape (`core/ModelText`).
 */
class MechanicsLines(private val services: NarratorServices, zone: ZoneId) {
    private val log = Log.director
    private val hours = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)

    /**
     * The lines at [now], in this order: challenges, rules, pacing, offerings. When they would take more than
     * [MechanicsLimits.SITUATION_TOTAL] together, the short ones stay whole and the long ones share the rest.
     */
    fun render(now: Long): List<String> {
        val builders = mutableListOf<(Int) -> String?>()
        engineLines(now).forEach { line -> builders += { max -> ModelText.cut(line, max) } }
        val online = services.players.online.map { it.ref }
        try {
            val pacing = if (online.isEmpty()) emptyList() else services.guards.pacing(online.map { it.uuid }, now)
            val names = online.associate { it.uuid to who(it) }
            builders += { max -> pacing(pacing, names, now, max) }
            builders += { max -> offerings(pacing, names, max) }
        } catch (e: Exception) {
            log.error("situation: the guards' pacing is unavailable", e)
            builders += { "Pacing: unavailable (${reason(e)})" }
        }
        return LineBudget.share(builders, MechanicsLimits.SITUATION_LINE, MechanicsLimits.SITUATION_TOTAL)
    }

    /** The engine's own lines, escaped on one line each; none when it does not run, one saying why when it fails. */
    private fun engineLines(now: Long): List<String> {
        val mechanics = services.mechanics
        if (!mechanics.available) return emptyList()
        return try {
            mechanics.situationLines(now).filter { it.isNotBlank() }.map(ModelText::escape)
        } catch (e: Exception) {
            log.error("situation: the mechanics' lines are unavailable", e)
            listOf("Mechanics: unavailable (${reason(e)})")
        }
    }

    private fun pacing(pacing: List<PlayerPacing>, names: Map<UUID, String>, now: Long, max: Int): String? {
        val resting = pacing.map { player -> player to player.resting.filterValues { it > now }.entries.sortedBy { it.value } }.filter { it.second.isNotEmpty() }
        val graces = pacing.filter { (it.graceUntil ?: 0) > now }
        if (pacing.none { it.punishedThisHour > 0 } && resting.isEmpty() && graces.isEmpty()) return null
        fun name(uuid: UUID) = names[uuid] ?: uuid.toString()
        val chunks = mutableListOf<String>()
        pacing.forEachIndexed { index, player ->
            chunks += (if (index == 0) "punished this hour " else ", ") + "${name(player.player)} ${player.punishedThisHour}/${player.perHour}"
        }
        resting.forEachIndexed { index, (player, kinds) ->
            val head = if (index == 0) " · recent kinds " else "; "
            chunks += head + name(player.player) + " " +
                kinds.mapIndexed { at, (category, until) -> "${category.id} (${if (at == 0) "again at " else ""}${time(until)})" }.joinToString(", ")
        }
        graces.forEachIndexed { index, player ->
            chunks += (if (index == 0) " · grace " else ", ") + "${name(player.player)} until ${time(player.graceUntil ?: 0)}"
        }
        return LineBudget.fit("Pacing: ", chunks, max)
    }

    private fun offerings(pacing: List<PlayerPacing>, names: Map<UUID, String>, max: Int): String? {
        val waiting = pacing.flatMap { player -> player.offerings.map { player.player to it } }.sortedBy { it.second.returnAt }
        val chunks = waiting.mapIndexed { index, (player, offering) ->
            val what = ModelText.escape(offering.what.replace("minecraft:", ""))
            (if (index == 0) "" else ", ") + "O-${offering.id} ${names[player] ?: player} $what (answer by ${time(offering.returnAt)})"
        }
        return LineBudget.fit("Offerings: ", chunks, max)
    }

    private fun time(ms: Long): String = hours.format(Instant.ofEpochMilli(ms))

    private companion object {
        fun reason(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}

/** Lines kept within a budget of characters: their last items left out and counted, the room shared between them. */
object LineBudget {
    /**
     * [head] followed by as many [chunks] (each with its own leading separator) as fit in [max] characters, those
     * left out counted at the end with [more] already left out: `(+3 more)`. Null when there is no chunk, or no
     * room even for the count.
     */
    fun fit(head: String, chunks: List<String>, max: Int, more: Int = 0): String? {
        if (chunks.isEmpty()) return null
        fun note(count: Int) = if (count > 0) " (+$count more)" else ""
        val whole = head + chunks.joinToString("") + note(more)
        if (whole.length <= max) return whole
        val line = StringBuilder(head)
        chunks.forEachIndexed { index, chunk ->
            if (line.length + chunk.length + note(chunks.size - index - 1 + more).length > max) {
                return (line.toString().trimEnd() + note(chunks.size - index + more)).takeIf { it.length <= max }
            }
            line.append(chunk)
        }
        return line.toString() + note(more)
    }

    /**
     * The lines of [builders] (each given the most characters it may take; null when it has nothing to say), each
     * within [lineMax] and all within [total]: the shortest take what they need, up to an even share of what is
     * left, and the longest are built again within what remains. A line a builder cannot fit is cut.
     */
    fun share(builders: List<(Int) -> String?>, lineMax: Int, total: Int): List<String> {
        val natural = builders.map { builder -> builder(lineMax)?.let { ModelText.cut(it, lineMax) } }
        if (natural.sumOf { it?.length ?: 0 } <= total) return natural.filterNotNull()
        val present = natural.indices.filter { natural[it] != null }
        val caps = IntArray(natural.size)
        var room = total
        var left = present.size
        present.sortedBy { natural[it]!!.length }.forEach { index ->
            caps[index] = minOf(natural[index]!!.length, room / left)
            room -= caps[index]
            left--
        }
        return present.mapNotNull { index ->
            val line = natural[index]!!
            if (line.length <= caps[index]) line else builders[index](caps[index])?.let { ModelText.cut(it, caps[index]) }
        }
    }
}
