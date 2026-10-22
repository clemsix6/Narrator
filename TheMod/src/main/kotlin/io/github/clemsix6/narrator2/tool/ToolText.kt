package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.guard.clockTime
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.Locale

/**
 * How the engine writes numbers, durations, levels and places for the model (M2 review, finding 16): one
 * form for each, whoever writes it (tools, events, situation, briefing, the Oracle). Pure, any thread.
 *
 * - A duration is `36s`, `4m05s`, `25m`, `1h12m` or `2d4h` ([duration]; [wait] for a wait).
 * - A dimension is its id without `minecraft:`: `overworld`, `the_nether`, `the_end` ([dimension]), like
 *   every other id and like the Journal's views; the model may write `nether` or `end` too ([dimensionId]).
 * - Player names go through the common escape ([names], `core/ModelText`).
 */
object ToolText {
    /** A duration: `36s`, `4m05s`, `25m`, `1h12m`, `2d4h`; rounded to the nearest second, `0s` for a time in the future. */
    fun duration(ms: Long): String = format((ms.coerceAtLeast(0) + 500) / 1_000)

    /** How long until something is possible, as [duration] but rounded up to the second: a wait that is not over never reads `0s`. */
    fun wait(ms: Long): String = format((ms.coerceAtLeast(0) + 999) / 1_000)

    private fun format(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 600 && seconds % 60 != 0L -> "${seconds / 60}m${two(seconds % 60)}s"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h${two(seconds % 3_600 / 60)}m"
        else -> "${seconds / 86_400}d${seconds % 86_400 / 3_600}h"
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    /** [value] without a useless fraction: `3`, `0.5`, `12.25`. */
    fun number(value: Double): String = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

    /** An effect's or an enchantment's level in Roman numerals, `II`; digits beyond 10. */
    fun roman(level: Int): String = ROMAN.getOrElse(level - 1) { level.toString() }

    /** A dimension as the model reads it, from its full id: `overworld`, `the_nether`, `the_end`, `othermod:moon`. */
    fun dimension(id: String): String = id.removePrefix(VANILLA)

    /**
     * The full id of a dimension the model wrote, in any of its forms: `the_nether`, `nether`,
     * `minecraft:the_nether`, `Nether`; `end` and `the_end` alike. Another mod's dimension keeps its namespace.
     */
    fun dimensionId(name: String): String {
        val lower = name.trim().lowercase()
        val id = if (':' in lower) lower else VANILLA + lower
        return SHORT[id] ?: id
    }

    /** The names of [players] for the model, escaped (`core/ModelText`): `Bartok, Mimosa`, or `nobody`. */
    fun names(players: List<PlayerRef>): String = players.joinToString { it.shownName }.ifEmpty { "nobody" }

    private const val VANILLA = "minecraft:"
    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    /** The short names of the vanilla dimensions, as the model may write them. */
    private val SHORT = mapOf("minecraft:nether" to "minecraft:the_nether", "minecraft:end" to "minecraft:the_end")
}

/** The player's name as the model reads it: escaped (`core/ModelText`), since `<engine>` is a valid name offline. */
val PlayerRef.shownName: String get() = ModelText.name(name)

/** The player's name as the model reads it: escaped (`core/ModelText`). */
val ServerPlayer.shownName: String get() = ModelText.name(plainTextName)

// ── What every tool family shares ───────────────────────────────────────────────────────────────

/** Stops a tool's checks with [result] as its answer, from anywhere, the server thread included. Caught by [answer]. */
internal class Refusal(val result: ToolResult) : RuntimeException(result.text, null, false, false)

/** Stops the tool with [result] as its answer. */
internal fun refuse(result: ToolResult): Nothing = throw Refusal(result)

/** Runs a tool's [block]; a [refuse] on the way becomes the answer. */
internal inline fun answer(block: () -> ToolResult): ToolResult = try {
    block()
} catch (e: Refusal) {
    e.result
}

/** The names of the players online, for an error message: `Bartok, Mimosa`, or `nobody`. Any thread. */
fun ToolContext.onlineNames(): String = ToolText.names(players.online.map { it.ref })

/** `Invalid arguments: Bob is not online; online: …`, for the [names] the model gave that are not online. */
fun ToolContext.notOnline(names: List<String>): ToolResult {
    val shown = names.distinct().map(ModelText::name)
    val who = if (shown.size == 1) "${shown.single()} is not online" else "${shown.joinToString()} are not online"
    return ToolResult.invalid("$who; online: ${onlineNames()}")
}

fun ToolContext.notOnline(name: String): ToolResult = notOnline(listOf(name))

/** The online players named [names], ignoring case and repeats; refuses with who is online otherwise. Any thread. */
fun ToolContext.online(names: List<String>): List<PlayerRef> {
    val found = names.map { it to players.byName(it) }
    val missing = found.filter { it.second == null }.map { it.first }
    if (missing.isNotEmpty()) refuse(notOnline(missing))
    return found.mapNotNull { it.second?.ref }.distinctBy { it.uuid }
}

/** The online player called [name] (ignoring case); refuses with who is online otherwise. Any thread. */
fun ToolContext.online(name: String): PlayerRef = online(listOf(name)).single()

/**
 * A hostile action against [player] until [until] (epoch milliseconds): the guards check it (protection,
 * chaos 0, no piling on, one `punish` per act, fairness; from M4 the grace and the rest of [category])
 * and open or extend the act's punishment. [category]: the kind of punishment (M4 §6.1), deduced by the tool.
 * Refuses with the verdict. Server thread.
 */
fun ToolContext.punish(player: ServerPlayer, until: Long, category: PunishmentCategory? = null) {
    val verdict = guards.hostile(player, caller, until, category)
    if (verdict is Verdict.Denied) refuse(ToolResult.denied(verdict))
}

/** The chaos level in force. */
val ToolContext.chaos: Int get() = settings.config.chaos

/** [epochMs] as a time of day, `21:41:10`. */
fun ToolContext.at(epochMs: Long): String = clockTime(clock, epochMs)

/** The live player of [ref]; refuses when they left. Server thread. */
fun MinecraftServer.live(ref: PlayerRef): ServerPlayer =
    playerList.getPlayer(ref.uuid) ?: refuse(ToolResult.failed("${ref.shownName} left the game"))
