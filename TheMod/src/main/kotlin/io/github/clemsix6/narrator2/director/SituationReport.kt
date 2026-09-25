package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.ago
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.dim
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.id
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.number
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.quote
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.who
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 */
class SituationReport(private val services: NarratorServices, private val digest: ActivityDigest, zone: ZoneId) {
    private val formatter = EventFormatter(zone)
    private val hours = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)

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
            append("</situation>")
        }

    private fun player(live: LivePlayer, now: Long): String {
        val parts = mutableListOf<String>()
        val place = StringBuilder(dim(live.pos.dim))
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
        parts += "online ${ago(now - live.joinedAt)}" + if (live.idle) ", idle" else ""
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
                TaskRecord.Status.RUNNING -> "running ${ago(now - (task.startedAt ?: task.createdAt).toEpochMilli())}"
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
            "-${ago(now - action.at)} ${action.tool}" + if (action.targets.isEmpty()) "" else "→${action.targets.joinToString(",") { ModelText.name(it) }}"
        }

    companion object {
        /** The window of each player's `last …m` activity. */
        const val RECENT_MINUTES = 5

        /** Hostile actions are refused against a player who died less than this long ago (`Rules.NO_PILE_ON`). */
        const val NO_PILE_ON_MS = 5 * 60_000L
    }
}
