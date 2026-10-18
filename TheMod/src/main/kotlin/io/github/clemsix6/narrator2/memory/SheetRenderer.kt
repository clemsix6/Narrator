package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.memory.stats.Signals
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The text a model reads to remember a player (M3 §11.4): one [PlayerSheet], three forms, all made here.
 *
 * - [SheetDetail.FULL] (`player_profile`, `/narrator sheet`): every section; lists one item per line, with
 *   the refs (`M-`, `I-`, `C-`) that prove or name each thing.
 * - [SheetDetail.CONDENSED] (the briefing, a join): one line per subject, the essentials. It leaves out
 *   what the briefing and the join show elsewhere: the "last session" text, the open canon, the memories.
 * - [SheetDetail.LINE] (beyond six players online): who they are, in one line.
 *
 * Labels are the code's, in English; the texts the Dreamer and the Chronicler wrote are French. Every text
 * that came from a player or a model (names, quotes, documents, summaries, values of the stats) is escaped
 * ([ModelText.escape]). A missing section says so (`Recap: (not written yet: …)`) rather than disappear:
 * nothing is made up. A trait under [Decay.TRAIT_VISIBLE] of effective confidence (on the player's play
 * clock, [PlayerSheet.playedSeconds]) is not shown.
 *
 * **Budgets**: each form stays within [SheetDetail.maxChars] (UTF-16 characters, newlines included). A form
 * is made of pieces (a line, or a heading with its items). When they do not all fit, the room is shared out
 * by weight (water-filling: a piece shorter than its share keeps all of it); a piece short of room cuts its
 * texts at a word, never inside a quote, or leaves out its last items (`+N more`). Pure: no database.
 */
class SheetRenderer(private val zone: ZoneId) {
    private val dayFormat = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(zone)
    private val yearDayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone)
    private val dayTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(zone)
    private val yearDayTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)

    /** [sheet] as [detail] at [now] (epoch milliseconds), with only [parts] (the player's name always). */
    fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart> = SheetPart.ALL): String {
        val view = View(sheet, Instant.ofEpochMilli(now), parts)
        return when (detail) {
            SheetDetail.LINE -> view.line()
            SheetDetail.CONDENSED -> assemble(view.condensed(), detail.maxChars)
            SheetDetail.FULL -> assemble(view.full(), detail.maxChars)
        }
    }

    /** One rendering: the sheet, the time, the parts asked for, and what is derived from them. */
    private inner class View(val sheet: PlayerSheet, val now: Instant, val parts: Set<SheetPart>) {
        /** The traits shown, strongest first, with their effective confidence: retired and faded ones are not. */
        val traits: List<Pair<Trait, Double>> = sheet.traits.filter { !it.retired }
            .map { it to Decay.traitConfidence(it, sheet.playedSeconds) }
            .filter { Decay.traitVisible(it.second) }
            .sortedWith(compareByDescending<Pair<Trait, Double>> { it.first.intensity }.thenByDescending { it.second }.thenBy { it.first.trait })

        /** Traits set but faded below [Decay.TRAIT_VISIBLE]. */
        val faded: Int = sheet.traits.count { !it.retired } - traits.size

        val stats = StatsView(sheet.stats, this)

        fun has(part: SheetPart) = part in parts

        fun doc(section: SheetSection): MemoryDoc? = sheet.docs[section]

        fun day(at: Instant): String = (if (sameYear(at)) dayFormat else yearDayFormat).format(at)

        fun dayTime(at: Instant): String = (if (sameYear(at)) dayTimeFormat else yearDayTimeFormat).format(at)

        private fun sameYear(at: Instant) = at.atZone(zone).year == now.atZone(zone).year

        fun ago(at: Instant): String = HybridRecall.ago(now.toEpochMilli() - at.toEpochMilli())

        // ── Shared by the forms ─────────────────────────────────────────────────────────────────────

        /** `Bartok (formerly Bartok_) · « le Plongeur » (C-12) · no title · since 09-12 · 23 sessions · 61h played · online`. */
        fun header(): String = buildList {
            val former = sheet.formerNames.map(ModelText::name)
            add(
                sheet.player.shownName + when {
                    former.isEmpty() -> ""
                    former.size <= MAX_FORMER_NAMES -> " (formerly ${former.joinToString()})"
                    else -> " (formerly ${former.takeLast(MAX_FORMER_NAMES).joinToString()} +${former.size - MAX_FORMER_NAMES})"
                },
            )
            if (!has(SheetPart.IDENTITY)) return@buildList
            add(sheet.nickname?.let { "« ${nameOf(it)} » (${it.ref})" } ?: "no nickname")
            add(sheet.title?.let { "title « ${nameOf(it)} » (${it.ref})" } ?: "no title")
            if (sheet.isOp) add("operator")
            add("since ${day(sheet.firstSeen)}")
            add(plural(sheet.sessions.toLong(), "session"))
            add("${played(sheet.playedSeconds)} played")
            add(if (sheet.online) "online" else "offline, last seen ${dayTime(sheet.lastSeen)} (${ago(sheet.lastSeen)})")
        }.joinToString(" · ")

        /** `reckless 4/5 ↗`, or `free: obsédé par le butin 3/5`. */
        fun traitName(trait: Trait, free: String = "free: "): String {
            val name = TraitAxis.of(trait.trait)?.pole(trait.side) ?: (free + text(trait.label ?: trait.trait.removePrefix(TraitAxis.FREE_PREFIX)))
            val arrow = when {
                trait.trend > 0 -> " ↗"
                trait.trend < 0 -> " ↘"
                else -> ""
            }
            return "$name ${trait.intensity}/5$arrow"
        }

        fun noTraits(): String =
            if (faded > 0) "(none shown: $faded faded below ${Decay.TRAIT_VISIBLE} confidence)" else "(none yet: the Dreamer sets them, with evidence, in a deep dream)"

        /** The written section, escaped and cut to [max], or why it is missing. */
        fun docText(section: SheetSection, max: Int = Int.MAX_VALUE): String =
            doc(section)?.let { cutText(text(it.content), max) } ?: if (section == SheetSection.SINCE_LAST) NOT_WRITTEN_SINCE else NOT_WRITTEN

        /** The label of a written section, with the day it was written: `Recap (10-02)`. */
        fun docLabel(label: String, section: SheetSection): String = doc(section)?.let { "$label (${day(it.writtenAt)})" } ?: label

        /** `favor -31 ↘`, or why there is none. */
        fun favor(): String = sheet.favor?.let { favor ->
            "favor ${Favor.signed(favor)}" + Favor.arrow(sheet.favorTrend).let { if (it.isEmpty()) "" else " $it" }
        } ?: "favor none yet (no rated interaction)"

        fun respect(): String = sheet.respect?.let { "respect $it/100" } ?: "respect unknown (under ${Favor.RESPECT_MIN_MESSAGES} messages to you)"

        /** `disrespect ×5 (last « dieu de pacotille », 10-01)`. */
        fun disrespect(): String {
            val last = sheet.disrespect.firstOrNull() ?: return "no disrespect noted"
            val what = last.quote?.let { quote(it, CONDENSED_QUOTE) } ?: cutText(text(last.summary), CONDENSED_QUOTE)
            return "disrespect ×${sheet.disrespect.size} (last $what, ${day(last.ts)})"
        }

        fun attention(): String =
            sheet.lastAttentionAgoPlayedSeconds?.let { "last attention ${ToolText.duration(it * 1_000)} of play ago" } ?: "never had your attention"

        /** The `narrator` stat: `messages 143 (12 prayers) · punishments 7 · blessings 2 · challenges 2 won, 1 lost`. */
        fun withYou(): List<String> = buildList {
            val detail = stats.detail("narrator") ?: return@buildList
            val prayers = detail.long("prayers") ?: 0
            add("messages ${count(stats.value("narrator") ?: 0.0)}" + if (prayers > 0) " (${plural(prayers, "prayer")})" else "")
            for (key in listOf("punishments", "blessings", "gifts")) detail.long(key)?.takeIf { it > 0 }?.let { add("$key $it") }
            val won = detail.long("challenges_won") ?: 0
            val lost = detail.long("challenges_lost") ?: 0
            if (won + lost > 0) add("challenges $won won, $lost lost")
        }

        /** `10-03 I-512 insult -2: ` + its summary, elastic (CONDENSED). */
        fun shortInteraction(interaction: Interaction): Item = Item(
            "${day(interaction.ts)} ${interaction.ref} ${interaction.kind.id} ${signed(interaction.valence)}: ",
            cutText(text(interaction.summary), CONDENSED_SUMMARY),
            elastic = true,
        )

        /** `10-03 19:40 I-512 insult -2: ` + its summary + ` · said « … » · canon C-50 · memory M-77` (FULL). */
        fun interaction(interaction: Interaction): Item = Item(
            "${dayTime(interaction.ts)} ${interaction.ref} ${interaction.kind.id} ${signed(interaction.valence)}: ",
            text(interaction.summary),
            (interaction.quote?.let { " · said ${quote(it, FULL_QUOTE)}" } ?: "") +
                (interaction.canon?.let { " · canon $it" } ?: "") + (interaction.memory?.let { " · memory $it" } ?: ""),
            elastic = true,
        )

        /** The marked interactions (valence ±3) that [shown] does not hold, newest first. */
        fun marked(shown: List<Interaction>): List<Interaction> {
            val seen = shown.map { it.ref }.toSet()
            return sheet.marked.filter { it.ref !in seen && abs(it.valence) == 3 }
        }

        // ── LINE ────────────────────────────────────────────────────────────────────────────────────

        /** `Bartok · « le Plongeur » · 61h played · favor -31 ↘ · respect 35/100 · disrespect ×5 · reckless 4/5 ↗, … · Explorateur…`. */
        fun line(): String {
            val items = buildList {
                add(Item(sheet.player.shownName))
                if (has(SheetPart.IDENTITY)) {
                    sheet.nickname?.let { add(Item("« ${nameOf(it)} »")) }
                    add(Item("${played(sheet.playedSeconds)} played"))
                    if (!sheet.online) add(Item("offline, last seen ${ago(sheet.lastSeen)}"))
                }
                if (has(SheetPart.HISTORY)) {
                    sheet.favor?.let { add(Item(favor())) }
                    sheet.respect?.let { add(Item(respect())) }
                    if (sheet.disrespect.isNotEmpty()) add(Item("disrespect ×${sheet.disrespect.size}"))
                }
                if (has(SheetPart.PERSONALITY) && traits.isNotEmpty()) add(Item(traits.take(LINE_TRAITS).joinToString(", ") { traitName(it.first, free = "") }))
                if (has(SheetPart.RECAP)) doc(SheetSection.RECAP)?.let { add(Item("", text(it.content), elastic = true)) }
            }
            return ItemsPiece(null, items, 1) { "…" }.fit(SheetDetail.LINE.maxChars) ?: cutText(sheet.player.shownName, SheetDetail.LINE.maxChars)
        }

        // ── CONDENSED ───────────────────────────────────────────────────────────────────────────────

        fun condensed(): List<Piece> = buildList {
            add(Fixed(header()))
            if (has(SheetPart.RECAP)) add(TextPiece(docLabel("Recap", SheetSection.RECAP), docText(SheetSection.RECAP, CONDENSED_RECAP), 8))
            if (has(SheetPart.PERSONALITY)) {
                val shown = traits.map { (trait, confidence) -> Item("${traitName(trait)} (${sureness(confidence)})") }
                add(ItemsPiece("Traits", shown.ifEmpty { listOf(Item(noTraits())) }, 4))
                add(ItemsPiece("Skills", sheet.skills.map { Item("${skillName(it.skill)} ${it.score}") }.ifEmpty { listOf(Item(NOT_RATED)) }, 2))
            }
            if (has(SheetPart.PERSONALITY) || has(SheetPart.HISTORY)) {
                val toward = buildList {
                    if (has(SheetPart.PERSONALITY)) {
                        add(doc(SheetSection.ATTITUDE)?.let { Item("", cutText(text(it.content), CONDENSED_ATTITUDE), elastic = true) } ?: Item("attitude not written yet"))
                    }
                    if (has(SheetPart.HISTORY)) addAll(listOf(Item(respect()), Item(favor()), Item(disrespect())))
                }
                add(ItemsPiece("Toward you", toward, 4))
            }
            if (has(SheetPart.PERSONALITY)) {
                add(ItemsPiece("Relations", sheet.relations.map { Item("${it.other.shownName} ${it.kind.id}") }.ifEmpty { listOf(Item(NO_RELATIONS)) }, 2))
                val likes = Item("", docText(SheetSection.LIKES, CONDENSED_LIKES), elastic = doc(SheetSection.LIKES) != null)
                val fears = Item("fears: ", docText(SheetSection.FEARS, CONDENSED_FEARS), elastic = doc(SheetSection.FEARS) != null)
                add(ItemsPiece("Likes", listOf(likes, fears), 2))
                add(TextPiece("Hooks", docText(SheetSection.HOOKS, CONDENSED_HOOKS), 2))
            }
            if (has(SheetPart.STATS)) add(ItemsPiece("Stats", stats.condensed().map(::Item).ifEmpty { listOf(Item(NOT_COMPUTED)) }, 6))
            if (has(SheetPart.HISTORY)) {
                add(ItemsPiece("With you", (withYou() + attention()).map(::Item), 3))
                val shown = sheet.recent.take(CONDENSED_HISTORY)
                add(ItemsPiece("History", shown.map(::shortInteraction).ifEmpty { listOf(Item(NO_INTERACTION)) }, 5, CONDENSED_MIN_SUMMARY))
                marked(shown).take(CONDENSED_MARKED).takeIf { it.isNotEmpty() }?.let { add(ItemsPiece("Marked", it.map(::shortInteraction), 1, CONDENSED_MIN_SUMMARY)) }
            }
        }

        // ── FULL ────────────────────────────────────────────────────────────────────────────────────

        fun full(): List<Piece> = buildList {
            add(Fixed(header()))
            if (has(SheetPart.IDENTITY)) {
                add(Fixed("Identity: uuid ${sheet.player.uuid} · first seen ${dayTime(sheet.firstSeen)} · last seen ${dayTime(sheet.lastSeen)}"))
            }
            if (has(SheetPart.RECAP)) {
                add(TextPiece(docLabel("Recap", SheetSection.RECAP), docText(SheetSection.RECAP), 6))
                add(TextPiece(docLabel("Last session", SheetSection.SINCE_LAST), docText(SheetSection.SINCE_LAST), 4))
            }
            if (has(SheetPart.PERSONALITY)) addAll(personality())
            if (has(SheetPart.STATS)) {
                add(stats.full()?.let { ListPiece("Stats (computed ${dayTime(stats.computedAt!!)}):", it.map(::Item), 10, ::more) } ?: Fixed("Stats: $NOT_COMPUTED"))
            }
            if (has(SheetPart.HISTORY)) addAll(history())
            if (has(SheetPart.MEMORIES)) add(memories())
        }

        private fun personality(): List<Piece> = buildList {
            val heading = when {
                traits.isEmpty() -> "Traits: ${noTraits()}"
                faded > 0 -> "Traits ($faded more faded below ${Decay.TRAIT_VISIBLE} confidence):"
                else -> "Traits:"
            }
            add(
                ListPiece(heading, traits.map { (trait, confidence) ->
                    val head = "${traitName(trait)} (${sureness(confidence)} ${decimal(confidence)}, since ${day(trait.since)}; proofs ${proofs(trait.evidence)})"
                    trait.note?.let { Item("$head: ", text(it), elastic = true) } ?: Item(head)
                }, 6, ::more),
            )
            add(ItemsPiece("Signals (Jev hints)", stats.signals().map(::Item), 2))
            add(TextPiece(docLabel("Playstyle", SheetSection.PLAYSTYLE), docText(SheetSection.PLAYSTYLE), 2))
            add(TextPiece(docLabel("Attitude", SheetSection.ATTITUDE), docText(SheetSection.ATTITUDE), 2))
            add(TextPiece(docLabel("Likes", SheetSection.LIKES), docText(SheetSection.LIKES), 2))
            add(TextPiece(docLabel("Fears", SheetSection.FEARS), docText(SheetSection.FEARS), 2))
            add(TextPiece(docLabel("Hooks", SheetSection.HOOKS), docText(SheetSection.HOOKS), 2))
            add(
                ListPiece(if (sheet.skills.isEmpty()) "Skills: $NOT_RATED" else "Skills:", sheet.skills.map {
                    Item("${skillName(it.skill)} ${it.score}/10: ", text(it.rationale), proven(it.evidence), elastic = true)
                }, 3, ::more),
            )
            add(
                ListPiece(if (sheet.relations.isEmpty()) "Relations: $NO_RELATIONS" else "Relations:", sheet.relations.map {
                    Item("${it.other.shownName} ${it.kind.id}: ", text(it.note), proven(it.evidence), elastic = true)
                }, 3, ::more),
            )
        }

        private fun history(): List<Piece> = buildList {
            val standing = listOf(favor(), respect()) + if (sheet.disrespect.isEmpty()) listOf("no disrespect noted") else emptyList()
            add(ItemsPiece("With you", (standing + withYou() + attention()).map(::Item), 2))
            if (sheet.disrespect.isNotEmpty()) {
                add(
                    ItemsPiece("Disrespect ×${sheet.disrespect.size}, latest", sheet.disrespect.take(FULL_DISRESPECT).map {
                        val head = "${day(it.ts)} ${it.ref} "
                        it.quote?.let { q -> Item("$head« ", quoted(q, FULL_QUOTE), " »", elastic = true) } ?: Item(head, text(it.summary), elastic = true)
                    }, 2),
                )
            }
            val shown = sheet.recent
            add(ListPiece(if (shown.isEmpty()) "History: $NO_INTERACTION" else "History (latest first):", shown.map(::interaction), 8, ::more))
            val marked = marked(shown).take(FULL_MARKED)
            if (marked.isNotEmpty()) add(ListPiece("Marked (valence ±3, older):", marked.map(::interaction), 3, ::more))
            add(ListPiece(if (sheet.openCanon.isEmpty()) "Open canon: (none about this player)" else "Open canon:", sheet.openCanon.map(::canon), 4, ::more))
        }

        /** `C-63 promise with Mimosa, due 10-04 21:00 (in 1d1h): ` + its statement + ` · said « … » · stakes: …`. */
        private fun canon(entry: CanonEntry): Item {
            val head = buildString {
                append("${entry.ref} ${entry.kind.id}")
                others(entry.players)?.let { append(" with $it") }
                if (entry.status == CanonStatus.PLANNED) append(" (planned)")
                entry.dueAt?.let { due ->
                    val left = due.toEpochMilli() - now.toEpochMilli()
                    append(", due ${dayTime(due)} (${if (left >= 0) "in ${ToolText.duration(left)}" else "overdue by ${ToolText.duration(-left)}"})")
                }
                if (entry.kind == CanonKind.RUNNING_GAG) {
                    append(" (${entry.freshness?.id ?: GagState.FRESH.id}, ${plural(entry.uses.toLong(), "use")}" + (entry.lastUsedAt?.let { ", last ${day(it)}" } ?: "") + ")")
                }
                append(": ")
            }
            val end = (entry.quote?.let { " · said ${quote(it, FULL_QUOTE)}" } ?: "") + (entry.stakes?.let { " · stakes: ${cutText(text(it), FULL_STAKES)}" } ?: "")
            return Item(head, text(entry.statement), end, elastic = true)
        }

        private fun memories(): Piece {
            if (sheet.memories.isEmpty()) return Fixed("Memories: (none yet)")
            return ListPiece("Strongest memories:", sheet.memories.map { memory ->
                val with = others(memory.players)?.let { " with $it" } ?: ""
                Item("${memory.ref} ${day(memory.occurredFrom)} (${ago(memory.occurredFrom)}) ★${memory.importance}$with: ${text(memory.title)}: ", text(memory.content), elastic = true)
            }, 6, ::more)
        }

        /** The players of a row other than this one: `Mimosa, clemsix6 +2`; null when none. */
        private fun others(players: List<PlayerRef>): String? {
            val others = players.filter { it.uuid != sheet.player.uuid }.map { it.shownName }
            if (others.isEmpty()) return null
            return others.take(MAX_OTHERS).joinToString() + if (others.size > MAX_OTHERS) " +${others.size - MAX_OTHERS}" else ""
        }

        /** ` (proofs M-120 I-512)`, or nothing. */
        private fun proven(evidence: List<Ref>): String = if (evidence.isEmpty()) "" else " (proofs ${proofs(evidence)})"

        private fun more(left: Int) = "(+$left more)"
    }

    /** The stats of a sheet as the forms show them; a stat missing from the sheet is left out. */
    private class StatsView(private val stats: Map<String, SheetStat>, private val view: View) {
        val computedAt: Instant? = stats.values.maxOfOrNull { it.computedAt }

        fun value(name: String): Double? = stats[name]?.value

        fun detail(name: String): JsonObject? = stats[name]?.detail

        /** One line: deaths, PvP, mining, gear, dimensions, luck, style, base. */
        fun condensed(): List<String> = buildList {
            value("deaths")?.let { add("deaths ${count(it)}" + top(detail("deaths"), 3).let { t -> if (t.isEmpty()) "" else " ($t)" }) }
            pvp()?.let { add("PvP $it") }
            value("mining")?.let { mined ->
                val ores = detail("mining")?.obj("ores")
                val found = listOfNotNull(
                    ores?.long("diamond")?.takeIf { it > 0 }?.let { plural(it, "diamond") },
                    ores?.long("ancient_debris")?.takeIf { it > 0 }?.let { "$it ancient debris" },
                )
                add("mined ${count(mined)}" + if (found.isEmpty()) "" else " (${found.joinToString(", ")})")
            }
            gear(withPickaxe = false)?.let { add(it) }
            detail("exploration")?.let {
                add("nether ${if (it.string("nether_at") != null) "yes" else "no"}")
                add("end ${if (it.string("end_at") != null) "yes" else "no"}")
            }
            value("luck")?.let { add("luck ${count(it)}/100") }
            style(CONDENSED_STYLE)?.let { add("style $it") }
            detail("places")?.obj("base")?.let { add("base ${position(it)}") }
        }

        /** One line per subject; null when no stat is computed yet. */
        fun full(): List<String>? = buildList {
            stats["playtime"]?.let { stat ->
                val detail = stat.detail
                val parts = listOfNotNull(
                    "${played(stat.value?.roundToLong() ?: 0)} played" + (detail?.long("last7d_s")?.let { " (${played(it)} in the last 7 days)" } ?: ""),
                    detail?.long("sessions")?.let { plural(it, "session") },
                    detail?.array("usual_hours")?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it.isNotEmpty() }
                        ?.let { hours -> "usually plays at ${hours.joinToString { "${it}h" }}" },
                )
                add("Activity: ${parts.joinToString(" · ")}")
            }
            value("deaths")?.let { deaths ->
                val detail = detail("deaths")
                val parts = mutableListOf(count(deaths) + top(detail, 3).let { if (it.isEmpty()) "" else " ($it)" })
                detail?.long("absurd")?.takeIf { it > 0 }?.let { parts += "$it absurd" }
                detail?.obj("last")?.let { last ->
                    val killer = last.string("killer")?.let { " by $it" } ?: ""
                    val at = last.string("ts")?.let { ", ${view.dayTime(Instant.parse(it))}" } ?: ""
                    val where = if (last.string("dim") != null) " at ${position(last)}" else ""
                    parts += "last: ${last.string("cause") ?: "unknown"}$killer$at$where"
                }
                add("Deaths: ${parts.joinToString(" · ")}")
            }
            val fights = buildList {
                value("kills")?.let { kills ->
                    add("${count(kills)} mobs" + top(detail("kills"), 3).let { if (it.isEmpty()) "" else " ($it)" })
                    detail("kills")?.array("bosses")?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }?.let { add("bosses: ${it.joinToString()}") }
                }
                value("pvp")?.let { won ->
                    val detail = detail("pvp")
                    val last = detail?.obj("last")?.let { last ->
                        val at = last.string("ts")?.let { ", ${view.day(Instant.parse(it))}" } ?: ""
                        " (last: ${if (last.bool("won") == true) "won against" else "lost to"} ${last.string("opponent") ?: "?"}$at)"
                    } ?: ""
                    add("PvP ${count(won)} won, ${detail?.long("losses") ?: 0} lost$last")
                }
            }
            if (fights.isNotEmpty()) add("Kills: ${fights.joinToString(" · ")}")
            value("mining")?.let { mined ->
                val detail = detail("mining")
                val ores = detail?.obj("ores")?.entries?.mapNotNull { (ore, n) -> (n as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { "${text(ore)} $it" } }.orEmpty()
                add("Mining: ${count(mined)} blocks" + (if (ores.isEmpty()) "" else " (${ores.joinToString(", ")})") + (detail?.long("avg_ore_y")?.let { " · ores at y≈$it" } ?: ""))
            }
            value("building")?.let { add("Building: ${count(it)} blocks placed" + top(detail("building"), 3).let { t -> if (t.isEmpty()) "" else " ($t)" }) }
            detail("exploration")?.let { exploration ->
                val rare = exploration.array("rare_biomes")?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }?.let { " (rare: ${it.joinToString()})" } ?: ""
                val parts = mutableListOf("${count(value("exploration") ?: 0.0)} biomes$rare")
                exploration.array("structures")?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }?.let { parts += "structures: ${it.joinToString()}" }
                parts += exploration.string("nether_at")?.let { "nether since ${view.day(Instant.parse(it))}" } ?: "nether never"
                parts += exploration.string("end_at")?.let { "end since ${view.day(Instant.parse(it))}" } ?: "end never"
                add("Exploration: ${parts.joinToString(" · ")}")
            }
            value("travel")?.takeIf { it > 0 }?.let { metres ->
                val ways = detail("travel")?.entries?.mapNotNull { (way, m) -> (m as? JsonPrimitive)?.doubleOrNull?.let { "${text(way)} ${distance(it)}" } }.orEmpty()
                add("Travel: ${distance(metres)}" + if (ways.isEmpty()) "" else " (${ways.joinToString(", ")})")
            }
            val progress = buildList {
                gear(withPickaxe = true)?.let { add(it) }
                value("advancements")?.let { total ->
                    val detail = detail("advancements")
                    val more = listOfNotNull(
                        detail?.long("challenges")?.takeIf { it > 0 }?.let { plural(it, "challenge") },
                        detail?.array("last")?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }?.let { "latest: ${it.joinToString()}" },
                    )
                    add("advancements ${count(total)}" + if (more.isEmpty()) "" else " (${more.joinToString("; ")})")
                }
            }
            if (progress.isNotEmpty()) add("Progress: ${progress.joinToString(" · ")}")
            value("economy")?.let { trades ->
                val emeralds = detail("economy")?.long("emeralds_spent")?.takeIf { it > 0 }?.let { " ($it emeralds spent)" } ?: ""
                add("Economy: ${count(trades)} trades$emeralds")
            }
            value("luck")?.let { luck ->
                val detail = detail("luck")
                val parts = listOfNotNull(
                    detail?.double("rare_per_1k_mined")?.let { "rare ores ${ToolText.number(it)} per 1000 mined" },
                    detail?.double("treasure_fish")?.let { "treasure ${percent(it)} of catches" },
                    detail?.double("challenges_ratio")?.let { "challenges won ${percent(it)}" },
                    detail?.double("absurd_deaths_per_h")?.let { "absurd deaths ${ToolText.number(it)} per hour" },
                )
                add("Luck: ${count(luck)}/100" + if (parts.isEmpty()) "" else " (${parts.joinToString(" · ")})")
            }
            style(Int.MAX_VALUE)?.let { add("Style: $it") }
            detail("places")?.let { places ->
                listOf("base", "bed", "mine").mapNotNull { key -> places.obj(key)?.let { "$key ${position(it)}" } }.takeIf { it.isNotEmpty() }
                    ?.let { add("Places: ${it.joinToString(" · ")}") }
            }
            together()?.let { add("Time together: $it") }
        }.ifEmpty { null }

        /** Jev's signals, or why there are none (the heuristic measures none, M3 §6.3). */
        fun signals(): List<String> {
            val stat = stats["signals"] ?: return listOf("(not computed yet)")
            val measured = stat.value?.roundToLong() ?: 0
            val detail = stat.detail ?: return listOf(
                if (measured == 0L) "none (no message of this player measured by Jev)"
                else "none yet ($measured messages measured by Jev, ${Signals.MIN_MESSAGES} needed)",
            )
            return listOf("$measured messages, recent (mean)") + detail.entries.mapNotNull { (name, value) ->
                val signal = value as? JsonObject ?: return@mapNotNull null
                val recent = signal.double("ewma") ?: return@mapNotNull null
                "${text(name)} ${decimal(recent)}" + (signal.double("mean")?.let { " (${decimal(it)})" } ?: "")
            }
        }

        private fun pvp(): String? {
            val won = value("pvp")?.roundToLong() ?: return null
            val lost = detail("pvp")?.long("losses") ?: 0
            return if (won + lost == 0L) null else "$won-$lost"
        }

        /** `gear 4/5 (iron_chestplate, diamond_sword)`. */
        private fun gear(withPickaxe: Boolean): String? {
            val tier = value("gear") ?: return null
            val items = detail("gear")?.let { listOfNotNull(it.string("armor"), it.string("weapon"), if (withPickaxe) it.string("pickaxe") else null) }.orEmpty()
            return "gear ${count(tier)}/5" + if (items.isEmpty()) "" else " (${items.joinToString(", ")})"
        }

        /** `explorer 45% fighter 30% miner 20%`: the [max] largest shares, from 1 %. */
        private fun style(max: Int): String? {
            val shares = detail("style")?.entries?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { name to it } }
                ?.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })?.take(max)
            return shares?.takeIf { it.isNotEmpty() }?.joinToString(" ") { "${text(it.first)} ${it.second}%" }
        }

        /** `Mimosa 7h00m (1h35m this week · PvP 3-5 · griefs 2 done, 1 suffered) · Kelvin 40m`, from the [PAIRS] stat. */
        private fun together(): String? {
            val pairs = detail(PAIRS)?.array(PAIRS)?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() } ?: return null
            return pairs.joinToString(" · ") { pair ->
                val pvp = (pair.long("pvp_won") ?: 0) to (pair.long("pvp_lost") ?: 0)
                val griefs = (pair.long("griefs_done") ?: 0) to (pair.long("griefs_suffered") ?: 0)
                val facts = listOfNotNull(
                    pair.double("minutes_7d")?.takeIf { it > 0 }?.let { "${minutes(it)} this week" },
                    pvp.takeIf { it.first + it.second > 0 }?.let { "PvP ${it.first}-${it.second}" },
                    griefs.takeIf { it.first + it.second > 0 }?.let { "griefs ${it.first} done, ${it.second} suffered" },
                )
                "${pair.string("name") ?: "?"} ${minutes(pair.double("minutes") ?: 0.0)}" + if (facts.isEmpty()) "" else " (${facts.joinToString(" · ")})"
            }
        }

        private fun minutes(value: Double): String = ToolText.duration((value * 60_000).roundToLong())
    }

    // ── Pieces and how they share the room ──────────────────────────────────────────────────────────

    /** A part of a rendering that fits itself into a number of characters; [weight]: its share of the room when not everything fits. */
    private abstract class Piece(val weight: Int) {
        /** This piece within [max] characters: its whole form when it fits; null when even its shortest form does not. */
        abstract fun fit(max: Int): String?

        val natural: Int by lazy { fit(Int.MAX_VALUE)?.length ?: 0 }
    }

    /** A line that is never shortened: the header, or a line saying a section is missing. */
    private class Fixed(private val text: String) : Piece(FIXED_WEIGHT) {
        override fun fit(max: Int): String? = text.takeIf { it.length <= max }
    }

    /** `label: text`, the text cut at a word when it does not fit. */
    private class TextPiece(private val label: String, private val text: String, weight: Int) : Piece(weight) {
        override fun fit(max: Int): String? {
            val room = max.toLong() - label.length - 2
            if (room < text.length && room < MIN_TEXT) return null
            return "$label: ${cutText(text, room.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}"
        }
    }

    /**
     * An item of an [ItemsPiece] or a [ListPiece]: [head], [body], [end]. An [elastic] body may be cut (at a
     * word, never inside a quote) when the piece is short of room; head and end never are.
     */
    private class Item(val head: String, val body: String = "", val end: String = "", val elastic: Boolean = false) {
        val text: String get() = head + body + end

        fun cut(max: Int): String = head + cutText(body, max) + end
    }

    /**
     * `label: a · b · c` (no label: `a · b · c`); short of room, its elastic bodies are cut ([minBody] characters
     * each at least), then its last items left out ([more]).
     */
    private class ItemsPiece(
        private val label: String?,
        private val items: List<Item>,
        weight: Int,
        private val minBody: Int = MIN_ELASTIC,
        private val more: (Int) -> String = { "+$it more" },
    ) : Piece(weight) {
        override fun fit(max: Int): String? = fitItems(items, max, minBody, 1) { texts, left ->
            (label?.let { "$it: " } ?: "") + (texts + if (left > 0) listOf(more(left)) else emptyList()).joinToString(SEPARATOR)
        }
    }

    /** A heading, then one indented line per item; short of room, its elastic bodies are cut, then its last items left out ([more]). */
    private class ListPiece(private val heading: String, private val items: List<Item>, weight: Int, private val more: (Int) -> String) : Piece(weight) {
        override fun fit(max: Int): String? = fitItems(items, max, MIN_LIST_BODY, 0) { texts, left ->
            (listOf(heading) + texts.map { "  $it" } + if (left > 0) listOf("  " + more(left)) else emptyList()).joinToString("\n")
        }
    }

    companion object {
        /** The stat [DuckPlayerSheets] adds from `pair_stats`: `{pairs: [{uuid, name, minutes, minutes_7d, pvp_won, pvp_lost, griefs_done, griefs_suffered}]}`. */
        const val PAIRS = "pairs"

        /** A ref the sheets print: `M-120`, `I-512`, `C-45`. */
        private val REF = Regex("""\b[MCIHN]-[1-9][0-9]{0,17}\b""")

        /**
         * The refs a rendered sheet shows, in order and without repeats: what its reader records as shown
         * (`recalls`, `ChapterMemory.shownRefs`). A player's quote that looks like a ref counts too.
         */
        fun refs(rendered: String): List<Ref> = REF.findAll(rendered).mapNotNull { Ref.parse(it.value) }.distinct().toList()

        private const val SEPARATOR = " · "
        private const val FIXED_WEIGHT = 1_000
        private const val FIT_PASSES = 4
        private const val MIN_TEXT = 24
        private const val MIN_ELASTIC = 16
        private const val MIN_LIST_BODY = 60
        private const val WORD_BACKTRACK = 16
        private const val MAX_FORMER_NAMES = 3
        private const val MAX_OTHERS = 3
        private const val NAME_MAX = 40
        private const val PROOFS_SHOWN = 6

        private const val LINE_TRAITS = 2

        private const val CONDENSED_RECAP = 480
        private const val CONDENSED_ATTITUDE = 110
        private const val CONDENSED_LIKES = 100
        private const val CONDENSED_FEARS = 70
        private const val CONDENSED_HOOKS = 160
        private const val CONDENSED_QUOTE = 40
        private const val CONDENSED_SUMMARY = 80
        private const val CONDENSED_MIN_SUMMARY = 30
        private const val CONDENSED_HISTORY = 3
        private const val CONDENSED_MARKED = 2
        private const val CONDENSED_STYLE = 3

        private const val FULL_QUOTE = 100
        private const val FULL_STAKES = 100
        private const val FULL_DISRESPECT = 3
        private const val FULL_MARKED = 8

        private const val NOT_WRITTEN = "(not written yet: the Dreamer writes it in a deep dream)"
        private const val NOT_WRITTEN_SINCE = "(not written yet: written when a chapter closes after this player left)"
        private const val NOT_RATED = "(not rated yet: the Dreamer rates them in a deep dream)"
        private const val NO_RELATIONS = "(none named yet)"
        private const val NOT_COMPUTED = "(not computed yet: the code computes them every 10 minutes of play)"
        private const val NO_INTERACTION = "(no interaction noted yet)"

        /**
         * [room] shared out among parts of [naturals] lengths by [weights] (water-filling): a part shorter than
         * its share keeps its length, the others share what is left in proportion to their weight.
         */
        private fun share(naturals: List<Int>, weights: List<Int>, room: Int): IntArray {
            val caps = IntArray(naturals.size)
            var left = room.toDouble()
            var weight = weights.sum().toDouble()
            for (i in naturals.indices.sortedBy { naturals[it].toDouble() / weights[it] }) {
                val portion = left * weights[i] / weight
                caps[i] = if (naturals[i] <= portion) naturals[i] else portion.toInt().coerceAtLeast(0)
                left -= caps[i]
                weight -= weights[i]
            }
            return caps
        }

        /**
         * The most of [items] that fit in [max] characters once laid out by [layout] (the texts shown, and how many
         * are left out): whole when they fit; else with their elastic bodies sharing what the rest leaves
         * (water-filling, [minBody] each at least); else with fewer items, down to [minShown].
         */
        private fun fitItems(items: List<Item>, max: Int, minBody: Int, minShown: Int, layout: (List<String>, Int) -> String): String? {
            for (shown in items.size downTo minShown) {
                val kept = items.take(shown)
                val whole = layout(kept.map { it.text }, items.size - shown)
                if (whole.length <= max) return whole
                val elastic = kept.filter { it.elastic }
                if (elastic.isEmpty()) continue
                val room = max - (whole.length - elastic.sumOf { it.body.length })
                if (room < elastic.size * minBody) continue
                val caps = share(elastic.map { it.body.length }, elastic.map { 1 }, room).iterator()
                return layout(kept.map { if (it.elastic) it.cut(caps.next()) else it.text }, items.size - shown)
            }
            return null
        }

        /**
         * [pieces] one per line within [max] characters. The first pass shares the room and always fits (each
         * piece stays within its cap); what the cut pieces left unused (items dropped whole) is shared again.
         */
        private fun assemble(pieces: List<Piece>, max: Int): String {
            val room = max - (pieces.size - 1)
            var allowance = room
            var best = ""
            for (pass in 0 until FIT_PASSES) {
                val caps = share(pieces.map { it.natural }, pieces.map { it.weight }, allowance)
                val text = pieces.indices.mapNotNull { pieces[it].fit(caps[it]) }.joinToString("\n")
                if (text.length > max) break
                best = text
                if (pieces.indices.all { caps[it] >= pieces[it].natural } || text.length == max) break
                allowance += max - text.length
            }
            return best
        }

        /**
         * [text] within [max] characters: cut at a word (when one ends near enough) with `…`, never inside a
         * quote it opens (`« »`, `“ ”`, `"`): what someone said never runs on as if it were a fact.
         */
        internal fun cutText(text: String, max: Int): String {
            if (text.length <= max) return text
            if (max <= 1) return "…".take(max.coerceAtLeast(0))
            var end = max - 1
            if (Character.isLowSurrogate(text[end])) end--
            val space = text.lastIndexOf(' ', end)
            if (space > 0 && end - space <= WORD_BACKTRACK) end = space
            val head = text.substring(0, end)
            return head.substring(0, openQuote(head) ?: head.length).trimEnd(' ', ',', ';', ':', '·', '—', '-', '(') + "…"
        }

        /** Where the first quote [text] opens without closing it starts; null when every quote is closed. */
        private fun openQuote(text: String): Int? {
            var guillemet: Int? = null
            var depth = 0
            var curly: Int? = null
            var straight: Int? = null
            for ((i, c) in text.withIndex()) {
                when (c) {
                    '«' -> {
                        if (depth == 0) guillemet = i
                        depth++
                    }
                    '»' -> if (depth > 0 && --depth == 0) guillemet = null
                    '“' -> if (curly == null) curly = i
                    '”' -> curly = null
                    '"' -> straight = if (straight == null) i else null
                }
            }
            return listOfNotNull(guillemet, curly, straight).minOrNull()
        }

        private fun text(value: String): String = ModelText.escape(value)

        /** Someone's words between « », cut inside ([quoted]). */
        private fun quote(value: String, max: Int): String = "« ${quoted(value, max)} »"

        /** Someone's words, escaped and cut, their « » turned into ‹ › so that the quote around them cannot end early. */
        private fun quoted(value: String, max: Int): String = cutText(text(value).replace('«', '‹').replace('»', '›'), max)

        /** The name a nickname or title gives: what its statement puts between « », else its quote when short, else the statement. */
        private fun nameOf(entry: CanonEntry): String {
            val named = Regex("«\\s*([^«»]{1,$NAME_MAX})\\s*»").find(entry.statement)?.groupValues?.get(1)?.trim()
            val name = named ?: entry.quote?.takeIf { it.length <= NAME_MAX } ?: entry.statement
            return cutText(text(name).replace('«', '‹').replace('»', '›'), NAME_MAX)
        }

        private fun skillName(skill: Skill): String = when (skill) {
            Skill.PVE -> "PvE"
            Skill.PVP -> "PvP"
            else -> skill.id
        }

        /** `sure` from 0.75 of effective confidence, `likely` from 0.5, else `unsure` (M3 §11.4). */
        private fun sureness(confidence: Double): String = when {
            confidence >= 0.75 -> "sure"
            confidence >= 0.5 -> "likely"
            else -> "unsure"
        }

        /** `M-120 I-512 M-130 +3`. */
        private fun proofs(evidence: List<Ref>): String =
            evidence.take(PROOFS_SHOWN).joinToString(" ") + if (evidence.size > PROOFS_SHOWN) " +${evidence.size - PROOFS_SHOWN}" else ""

        /** Play time: `45m`, `61h`. */
        private fun played(seconds: Long): String = if (seconds < 3_600) "${seconds.coerceAtLeast(0) / 60}m" else "${seconds / 3_600}h"

        private fun plural(n: Long, word: String): String = if (n == 1L) "1 $word" else "$n ${word}s"

        private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

        private fun count(value: Double): String = value.roundToLong().toString()

        private fun decimal(value: Double): String = "%.2f".format(Locale.ROOT, value)

        private fun percent(share: Double): String = "${(share * 100).roundToLong()}%"

        /** `640 m`, `84.3 km`. */
        private fun distance(metres: Double): String =
            if (metres < 1_000) "${metres.roundToLong()} m" else "${ToolText.number((metres / 100).roundToLong() / 10.0)} km"

        /** `overworld -1240 72 3310`, from `{dim, x, y, z}`. */
        private fun position(place: JsonObject): String =
            "${place.string("dim") ?: "?"} ${place.long("x") ?: "?"} ${place.long("y") ?: "?"} ${place.long("z") ?: "?"}"

        /** `lava 31, fall 12, mob_attack 9`, from `{top: [[name, n]…]}`. */
        private fun top(detail: JsonObject?, n: Int): String = detail?.array("top")?.mapNotNull { entry ->
            val pair = entry as? JsonArray ?: return@mapNotNull null
            val name = pair.getOrNull(0)?.text() ?: return@mapNotNull null
            "$name ${(pair.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: 0}"
        }?.take(n)?.joinToString(", ").orEmpty()

        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

        private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

        private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.roundToLong() }

        private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

        /** A string of the JSON, escaped; null for JSON null or anything else. */
        private fun JsonObject.string(key: String): String? = get(key)?.text()

        private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::text)
    }
}
