package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.number
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType

/**
 * The `Arsenal` line of the briefing's `<engine_state>` (M5 §9.3): the modules the Director may deploy, the latest
 * `ready` version of each name (what `deploy_module` takes without a version), the newest first, each with its
 * summary and its parameters, within [ForgeLimits.ARSENAL_CHARS]:
 * `Arsenal (3): statues v1 (new) — Red light, green light… (players 2-8) · orbiting_chickens — Chickens circle a
 * player… (player; chickens 3-8 =6; seconds 10-120 =30) · …`.
 *
 * - A built-in module (version 0) shows no version; a forged one saved in the last [NEW_MS] is `(new)`.
 * - A parameter is its name, then what the Director writes: its type when the name does not say it (`player`,
 *   `players`, `place`, `true|false`, `integer` or `number` without bounds, `text` without choices), its bounds
 *   (`3-8`, `≥3`, `≤8`; how many for `players`), its choices (`chicken|pig`), and `=fallback` when it may be left out;
 *   a parameter that may be left out without a fallback ends with `?`.
 * - Summaries are cut to the longest of [SUMMARY_CUTS] that lets every module fit; past the shortest, the newest
 *   modules stay whole and the others are named only (`· also tag, parade`), and those that still do not fit are
 *   counted (`(+3 more)`).
 *
 * The forger writes the summaries, the docs and the choices: they go through the common escape (`core/ModelText`).
 */
object ArsenalLine {
    /** A forged version saved this recently is marked `(new)`. */
    const val NEW_MS = 24 * 3_600_000L

    /** The lengths a summary is cut to, the longest first. */
    val SUMMARY_CUTS = listOf(ForgeLimits.SUMMARY_CHARS, 80, 50)

    /** The line for [entries] (the arsenal's `list()`) at [now]; `Arsenal: none` without any ready module. */
    fun render(entries: List<ArsenalEntry>, now: Long): String {
        val ready = entries.filter { it.status == ModuleStatus.READY }.sortedWith(NEWEST_FIRST).distinctBy { it.name }
        if (ready.isEmpty()) return "Arsenal: none"
        val head = "Arsenal (${ready.size}): "
        val max = ForgeLimits.ARSENAL_CHARS
        fun fits(chunks: List<String>) = (head + chunks.joinToString("")).length <= max
        SUMMARY_CUTS.map { cut -> ready.mapIndexed { index, entry -> (if (index == 0) "" else " · ") + entry(entry, now, cut) } }
            .firstOrNull(::fits)?.let { return head + it.joinToString("") }
        // Too many for their summaries: the newest whole, the others by name, so that the Director knows them all.
        val whole = ready.mapIndexed { index, entry -> (if (index == 0) "" else " · ") + entry(entry, now, SUMMARY_CUTS.last()) }
        fun named(from: Int) = ready.drop(from).mapIndexed { index, entry ->
            when {
                index > 0 -> ", "
                from > 0 -> " · also "
                else -> ""
            } + entry.name
        }
        val chunks = (ready.size - 1 downTo 0).map { kept -> whole.take(kept) + named(kept) }.firstOrNull(::fits) ?: named(0)
        return LineBudget.fit(head, chunks, max) ?: ModelText.cut(head + chunks.joinToString(""), max)
    }

    /** `statues v1 (new) — Red light, green light… (players 2-8)`, its summary cut to [cut]. */
    private fun entry(entry: ArsenalEntry, now: Long, cut: Int): String = buildString {
        append(entry.name)
        if (!entry.builtIn) append(" v").append(entry.version)
        if (!entry.builtIn && now - entry.savedAt.toEpochMilli() < NEW_MS) append(" (new)")
        append(" — ").append(ModelText.cut(ModelText.escape(entry.summary), cut))
        if (entry.params.isNotEmpty()) append(" (").append(entry.params.joinToString("; ") { param(it) }).append(')')
    }

    /** `chickens 3-8 =6`, `players 2-8`, `center? place`, `creature chicken|pig|sheep =chicken`. */
    fun param(param: ParamSpec): String = buildString {
        append(param.name)
        if (!param.required && param.fallback == null) append('?')
        type(param)?.takeIf { it != param.name }?.let { append(' ').append(it) }
        bounds(param)?.let { append(' ').append(it) }
        if (param.choices.isNotEmpty()) append(' ').append(param.choices.joinToString("|") { ModelText.escape(it) })
        param.fallback?.let { append(" =").append(ModelText.escape(it)) }
    }

    /** What the Director writes, when neither the name nor the bounds nor the choices say it. */
    private fun type(param: ParamSpec): String? = when (param.type) {
        ParamType.PLAYER -> "player"
        ParamType.PLAYERS -> "players"
        ParamType.PLACE -> "place"
        ParamType.BOOLEAN -> "true|false"
        ParamType.INTEGER -> if (bounds(param) == null) "integer" else null
        ParamType.NUMBER -> if (bounds(param) == null) "number" else null
        ParamType.TEXT -> if (param.choices.isEmpty()) "text" else null
    }

    /** `3-8`, `≥3`, `≤8`, for a number or how many players; null otherwise (a text's length is the engine's to check). */
    private fun bounds(param: ParamSpec): String? {
        if (param.type != ParamType.INTEGER && param.type != ParamType.NUMBER && param.type != ParamType.PLAYERS) return null
        val min = param.min
        val max = param.max
        return when {
            min != null && max != null -> "${number(min)}-${number(max)}"
            min != null -> "≥${number(min)}"
            max != null -> "≤${number(max)}"
            else -> null
        }
    }

    /** The most recently saved first, then by name, then the highest version (the order of `Arsenal.list()`). */
    private val NEWEST_FIRST = compareByDescending<ArsenalEntry> { it.savedAt }.thenBy { it.name }.thenByDescending { it.version }
}
