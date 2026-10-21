package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.memory.chronicle.ExchangeLog
import io.github.clemsix6.narrator2.memory.doubleOrNull
import io.github.clemsix6.narrator2.memory.enumOfId
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stats.Reactions
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.memory.strings
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The Narrator's style over a period, read from the Journal (M6 §3.7, §4.6): the Director's lines with their latest
 * labels (`narrator_lines`), how the players took each register ([Reactions]), the lines flagged for their tone, and the
 * measures of the period. Read by the Dreamer's review ([review], three blocks), the storyteller's history
 * ([registers]), `/narrator style` ([latestFlags], [coverage]), the dashboard and the evals.
 *
 * The watch ([StyleWatch]) keeps the latest lines in memory; this report reads any period from the Journal, on a read
 * connection its caller holds (`Journal.read`): never on the server thread. Only the Director's lines count (channel
 * `narrator`, said in a turn): an operator's `/narrator say`, a rule's or a module's text are not its style, and the
 * review labels none of them. A label whose register is no longer known (a register replaced since) counts as
 * [ComicRegister.OTHER].
 *
 * The openers and the tics of a period are normalized as the watch's are ([Tics], M6 §3.4): its names, those of
 * `player_names` and the canon's nicknames (`Tics.Names.of(Tics.names(connection))`), become `<nom>`, its numbers `<n>`.
 * The watch measures its latest lines with the same [measures] ([StyleWatch.diversity]).
 */
object StyleReport {
    /** A flaw at this score or more flags a line (M6 §3.7): what the tone flags and their counts hold. */
    const val FLAG = 0.7

    /** Budgets of the Dreamer's three blocks, in characters, tags included. */
    const val REGISTERS_MAX = 1_600
    const val FLAGS_MAX = 1_500
    const val DIVERSITY_MAX = 400

    /** Flagged lines quoted in `<tone_flags>`; the rest are counted. */
    const val FLAGS_SHOWN = 8

    /** Characters of a line quoted in `<tone_flags>`. */
    const val QUOTE_MAX = 160

    /** Seconds each statement may run. */
    private const val QUERY_TIMEOUT_SECONDS = 10

    /** From [from] (included) to [to] (excluded). */
    data class Period(val from: Instant, val to: Instant) {
        init {
            require(!to.isBefore(from)) { "a period ends after it starts: $from to $to" }
        }
    }

    /** A flaw the line review scores from 0 to 1 (M6 §3.3), the most serious first, as the notes of tone rank them. */
    enum class Flaw {
        DARK, CRUEL, OUT_OF_CHARACTER, FLAT, REPEATS;

        /** `out_of_character`: the column of `narrator_lines`, and the word shown. */
        val id: String get() = name.lowercase()
    }

    /**
     * A line of the Director and its latest labels: [register] is null when the line was not reviewed, and [flaws] and
     * [crutches] then empty.
     */
    data class Line(
        val id: Long,
        val ts: Instant,
        val text: String,
        val register: ComicRegister?,
        val flaws: Map<Flaw, Double>,
        val crutches: List<String>,
    ) {
        val reviewed: Boolean get() = register != null

        /** The flaws at [FLAG] or more, the most serious first; empty for a line not flagged. */
        val flags: List<Pair<Flaw, Double>> get() = flaws.filter { it.value >= FLAG }.toList().sortedBy { it.first.ordinal }
    }

    /**
     * How players took the Director's lines of one register: [lines] of it, then each reaction of a player addressed by
     * one of them ([Reactions]: a player can react to a line, several players to one). [laughing] and [annoying] name who
     * laughed and who was annoyed, the most often first.
     */
    data class RegisterUse(
        val register: ComicRegister,
        val lines: Int,
        val laughed: Int,
        val annoyed: Int,
        val replied: Int,
        val ignored: Int,
        val laughing: List<Pair<String, Int>>,
        val annoying: List<Pair<String, Int>>,
    )

    /**
     * The measures of a period (M6 §3.7). The shares count the reviewed lines.
     *
     * @property lines the Director's lines said.
     * @property reviewed those the review labeled.
     * @property registers the reviewed lines of each register.
     * @property crutches each crutch word (in lower case) and the reviewed lines that hold it, the most frequent first.
     * @property flagged the reviewed lines with a flaw at [FLAG] or more.
     * @property openersDistinct distinct normalized openers over the lines that open ([openersDistinct]); 0 when none opens.
     * @property tics the tics of the lines ([Tics.tics]: openers in 4 lines, phrases in 3, the ellipsis).
     */
    data class Measures(
        val lines: Int,
        val reviewed: Int,
        val registers: Map<ComicRegister, Int>,
        val crutches: List<Pair<String, Int>>,
        val flagged: Int,
        val openersDistinct: Double,
        val tics: Int,
    ) {
        /** The register of the most reviewed lines (ties: the enum's order); null without a review. */
        val topRegister: ComicRegister? =
            registers.entries.sortedWith(compareByDescending<Map.Entry<ComicRegister, Int>> { it.value }.thenBy { it.key.ordinal }).firstOrNull()?.key

        /** [topRegister]'s share, 0 to 1. */
        val topRegisterShare: Double get() = topRegister?.let(::share) ?: 0.0

        /** The registers of the voice's entropy over log(min(K, n)), 0 to 1 ([entropy]). */
        val registerEntropy: Double get() = entropy(ComicRegister.VOICE.map { registers[it] ?: 0 })

        /** The share of the reviewed lines with [register], 0 to 1. */
        fun share(register: ComicRegister): Double = if (reviewed == 0) 0.0 else (registers[register] ?: 0).toDouble() / reviewed

        /** The share of the reviewed lines that hold [word] as a crutch, 0 to 1. */
        fun crutchShare(word: String): Double =
            if (reviewed == 0) 0.0 else (crutches.firstOrNull { it.first == word.lowercase() }?.second ?: 0).toDouble() / reviewed
    }

    /** How many of the Director's latest lines the review labeled. */
    data class Coverage(val lines: Int, val reviewed: Int)

    // ── Reads (a read connection, off the server thread) ────────────────────────────────────────────

    /** The Director's lines said in [period] and their latest labels, oldest first. */
    fun lines(connection: Connection, period: Period): List<Line> =
        connection.query(
            "$LINE_COLUMNS FROM narrator_lines n JOIN chat c ON c.id = n.id AND c.channel = 'narrator' " +
                "WHERE c.turn_id IS NOT NULL AND n.ts >= ? AND n.ts < ? ORDER BY n.ts, n.id",
            period.from, period.to,
            timeoutSeconds = QUERY_TIMEOUT_SECONDS,
        ) { line() }

    /** The [count] latest lines flagged for their tone, at any time, the latest first. */
    fun latestFlags(connection: Connection, count: Int): List<Line> =
        connection.query(
            "$LINE_COLUMNS FROM narrator_lines n WHERE greatest(${Flaw.entries.joinToString { "n.${it.id}" }}) >= ? " +
                "ORDER BY n.ts DESC, n.id DESC LIMIT ?",
            FLAG, count,
            timeoutSeconds = QUERY_TIMEOUT_SECONDS,
        ) { line() }

    /** How many of the Director's [latest] lines the review labeled. */
    fun coverage(connection: Connection, latest: Int): Coverage =
        connection.query(
            "SELECT count(*), count(register) FROM (SELECT n.register FROM narrator_lines n JOIN chat c ON c.id = n.id AND c.channel = 'narrator' " +
                "WHERE c.turn_id IS NOT NULL ORDER BY n.ts DESC, n.id DESC LIMIT ?) latest",
            latest,
            timeoutSeconds = QUERY_TIMEOUT_SECONDS,
        ) { Coverage(getInt(1), getInt(2)) }.single()

    /** How players took each register in [period]: [registers] of its lines and their [Reactions]. */
    fun registers(connection: Connection, period: Period, words: HeuristicWords): List<RegisterUse> {
        val lines = lines(connection, period)
        return registers(lines, reactions(connection, period, lines, words))
    }

    /**
     * The Dreamer's three blocks (M6 §4.6) over [period], each within its budget: `<registers>` (each register's lines
     * and how players took them), `<tone_flags>` (the flagged lines, the most serious first) and `<diversity>` (the
     * period's measures, its openers normalized with the names of the Journal, compared with [before] when given).
     * Times in [zone]. Everything a model or a player wrote is escaped ([ModelText]).
     */
    fun review(connection: Connection, period: Period, before: Period?, words: HeuristicWords, zone: ZoneId): String {
        val lines = lines(connection, period)
        val uses = registers(lines, reactions(connection, period, lines, words))
        val previous = before?.let { lines(connection, it) }
        return listOf(
            registersBlock(period, lines, uses, zone),
            flagsBlock(lines, zone),
            diversityBlock(lines, previous, Tics.Names.of(Tics.names(connection))),
        ).joinToString("\n")
    }

    // ── Computed (pure) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Each register of the reviewed [lines] and how players took them ([reactions] of any line, matched by its id), the
     * most used first (ties: the enum's order).
     */
    fun registers(lines: List<Line>, reactions: List<Reactions.Reaction>): List<RegisterUse> {
        val byLine = reactions.groupBy { it.lineId }
        return lines.filter { it.reviewed }.groupBy { it.register!! }
            .map { (register, said) ->
                val taken = said.flatMap { byLine[it.id].orEmpty() }
                fun count(kind: Reactions.Kind) = taken.count { it.kind == kind }
                fun who(kind: Reactions.Kind) = ranked(taken.filter { it.kind == kind }.groupingBy { it.player.name }.eachCount())
                RegisterUse(
                    register, said.size, count(Reactions.Kind.LAUGHED), count(Reactions.Kind.ANNOYED), count(Reactions.Kind.REPLIED),
                    count(Reactions.Kind.IGNORED), who(Reactions.Kind.LAUGHED), who(Reactions.Kind.ANNOYED),
                )
            }
            .sortedWith(compareByDescending<RegisterUse> { it.lines }.thenBy { it.register.ordinal })
    }

    /**
     * The measures of [lines] (M6 §3.7), their openers and tics read with [names] (`Tics.Names.of(Tics.names(connection))`;
     * without names, the numbers are still normalized).
     */
    fun measures(lines: List<Line>, names: Tics.Names = Tics.Names.NONE): Measures {
        val reviewed = lines.filter { it.reviewed }
        val said = lines.map { Tics.said(it.text, it.ts, names) }
        return Measures(
            lines = lines.size,
            reviewed = reviewed.size,
            registers = reviewed.groupingBy { it.register!! }.eachCount(),
            crutches = ranked(reviewed.flatMap { line -> line.crutches.map { it.lowercase() }.distinct() }.groupingBy { it }.eachCount()),
            flagged = reviewed.count { it.flags.isNotEmpty() },
            openersDistinct = openersDistinct(said),
            tics = Tics.tics(said).size,
        )
    }

    /** Distinct normalized openers ([Tics.Said.opener]) over the lines of [said] that open; 0 when none opens. */
    fun openersDistinct(said: List<Tics.Said>): Double {
        val openers = said.mapNotNull { it.opener?.key }
        return if (openers.isEmpty()) 0.0 else openers.toSet().size.toDouble() / openers.size
    }

    /**
     * The entropy of [counts] (lines per register of the voice) over log(min(K, n)), K the registers of the voice and n
     * the lines counted (M6 §3.7): 1 when the lines spread evenly over as many registers as they can, 0 with one
     * register or fewer than two lines.
     */
    fun entropy(counts: List<Int>): Double {
        val n = counts.sum()
        val most = minOf(ComicRegister.VOICE.size, n)
        if (most < 2) return 0.0
        val h = counts.filter { it > 0 }.sumOf { count ->
            val p = count.toDouble() / n
            -p * ln(p)
        }
        return (h / ln(most.toDouble())).coerceIn(0.0, 1.0)
    }

    // ── The Dreamer's blocks ────────────────────────────────────────────────────────────────────────

    /**
     * `<registers from="10-19 22:40" to="10-20 01:10" lines="26" reviewed="22">`, then one line per register:
     * `- divine_bureaucracy: 14 lines · laughed 3 (Bartok 2, Mimosa 1) · annoyed 0 · replied 6 · ignored 5`.
     */
    fun registersBlock(period: Period, lines: List<Line>, uses: List<RegisterUse>, zone: ZoneId): String {
        val reviewed = lines.count { it.reviewed }
        val span = "from=\"${dayTime(period.from, zone)}\" to=\"${dayTime(period.to, zone)}\""
        return when {
            lines.isEmpty() -> "<registers $span>(the Director said nothing in this period)</registers>"
            reviewed == 0 -> "<registers $span lines=\"${lines.size}\" reviewed=\"0\">(no line reviewed: the line review was off, or failed)</registers>"
            else -> ExchangeLog.fit(
                listOf("<registers $span lines=\"${lines.size}\" reviewed=\"$reviewed\">") + uses.map(::registerLine) + "</registers>",
                REGISTERS_MAX,
            )
        }
    }

    /** `- divine_bureaucracy: 14 lines · laughed 3 (Bartok 2, Mimosa 1) · annoyed 0 · replied 6 · ignored 5`, the names escaped. */
    fun registerLine(use: RegisterUse): String {
        fun who(names: List<Pair<String, Int>>) =
            if (names.isEmpty()) "" else names.joinToString(", ", " (", ")") { (name, count) -> "${ModelText.name(name)} $count" }
        return "- ${use.register.id}: ${use.lines} ${plural(use.lines, "line")} · laughed ${use.laughed}${who(use.laughing)} · " +
            "annoyed ${use.annoyed}${who(use.annoying)} · replied ${use.replied} · ignored ${use.ignored}"
    }

    /**
     * `<tone_flags flagged="2" of="22">`, then the flagged lines, the most serious first:
     * `- 10-19 23:05 flat 0.80, repeats 0.70: "Effet lenteur appliqué."`; [FLAGS_SHOWN] at most, the rest counted.
     */
    fun flagsBlock(lines: List<Line>, zone: ZoneId): String {
        val reviewed = lines.count { it.reviewed }
        if (reviewed == 0) return "<tone_flags>(no line reviewed in this period)</tone_flags>"
        val flagged = lines.filter { it.flags.isNotEmpty() }
            .sortedWith(compareBy<Line> { it.flags.first().first.ordinal }.thenByDescending { line -> line.flags.maxOf { it.second } }.thenBy { it.ts })
        if (flagged.isEmpty()) {
            return "<tone_flags flagged=\"0\" of=\"$reviewed\">(none: no line reached $THRESHOLD in ${Flaw.entries.joinToString { it.id }})</tone_flags>"
        }
        val items = flagged.take(FLAGS_SHOWN).map { line ->
            "- ${dayTime(line.ts, zone)} ${flagList(line)}: ${ModelText.quote(line.text, QUOTE_MAX)}"
        } + listOfNotNull(if (flagged.size > FLAGS_SHOWN) "- … and ${flagged.size - FLAGS_SHOWN} more" else null)
        return ExchangeLog.fit(listOf("<tone_flags flagged=\"${flagged.size}\" of=\"$reviewed\">") + items + "</tone_flags>", FLAGS_MAX)
    }

    /**
     * `<diversity>openers distinct 0.84 (0.80 before) · top register divine_bureaucracy 38% (44% before) · register
     * entropy 0.71 (0.65 before) · crutch « céleste » 11% (18% before)</diversity>`: the measures of [lines], their
     * openers read with [names], and those of [before] in parentheses when given. The openers are left out when no line
     * opens (none said with `say` in 25 characters or more).
     */
    fun diversityBlock(lines: List<Line>, before: List<Line>?, names: Tics.Names = Tics.Names.NONE): String {
        val now = measures(lines, names)
        val then = before?.let { measures(it, names) }
        val previous = then?.takeIf { it.reviewed > 0 }
        val parts = buildList {
            if (now.openersDistinct > 0) {
                val earlier = then?.openersDistinct?.takeIf { it > 0 }?.let { " (${decimal(it)} before)" }.orEmpty()
                add("openers distinct ${decimal(now.openersDistinct)}$earlier")
            }
            if (now.reviewed == 0) {
                add(if (lines.isEmpty()) "nothing said in this period" else "no line reviewed in this period")
            } else {
                add(topRegister(now, previous))
                add("register entropy ${decimal(now.registerEntropy)}${previous?.let { " (${decimal(it.registerEntropy)} before)" }.orEmpty()}")
                add(topCrutch(now, previous))
            }
        }
        return ModelText.cut("<diversity>${parts.joinToString(" · ")}", DIVERSITY_MAX - "</diversity>".length) + "</diversity>"
    }

    /** `flat 0.80, repeats 0.70`: the flags of [line], the most serious first. */
    fun flagList(line: Line): String = line.flags.joinToString(", ") { (flaw, score) -> "${flaw.id} ${decimal(score)}" }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────────

    private const val LINE_COLUMNS = "SELECT n.id, epoch_ms(n.ts), n.text, n.register, n.dark, n.cruel, n.out_of_character, n.flat, n.repeats, n.crutches"

    /** `0.7`, as the flags' threshold is shown. */
    private val THRESHOLD = FLAG.toString()

    /** A row of [LINE_COLUMNS]. */
    private fun ResultSet.line(): Line {
        val register = getString(4)?.let { enumOfId<ComicRegister>(it) ?: ComicRegister.OTHER }
        val flaws = if (register == null) emptyMap() else Flaw.entries.mapIndexedNotNull { i, flaw -> doubleOrNull(5 + i)?.let { flaw to it } }.toMap()
        return Line(getLong(1), Instant.ofEpochMilli(getLong(2)), getString(3), register, flaws, if (register == null) emptyList() else strings(10))
    }

    /** The reactions to the lines of [period]; none read when no line of it was reviewed, since only those are matched. */
    private fun reactions(connection: Connection, period: Period, lines: List<Line>, words: HeuristicWords): List<Reactions.Reaction> =
        if (lines.none { it.reviewed }) emptyList() else Reactions.read(connection, period.from, period.to, words)

    /**
     * `top register divine_bureaucracy 38% (44% before)`; `top register sports_commentary 30% (before: divine_bureaucracy
     * 44%)` when another register led then.
     */
    private fun topRegister(now: Measures, previous: Measures?): String {
        val top = now.topRegister!!
        val then = when {
            previous == null -> ""
            previous.topRegister == top -> " (${percent(previous.topRegisterShare)} before)"
            else -> " (before: ${previous.topRegister!!.id} ${percent(previous.topRegisterShare)})"
        }
        return "top register ${top.id} ${percent(now.topRegisterShare)}$then"
    }

    /** `crutch « céleste » 11% (18% before)`, or `no crutch word (« céleste » 18% before)`. */
    private fun topCrutch(now: Measures, previous: Measures?): String {
        val top = now.crutches.firstOrNull()?.first
        if (top == null) {
            val then = previous?.crutches?.firstOrNull()?.first?.let { " (« ${ModelText.escape(it)} » ${percent(previous.crutchShare(it))} before)" }.orEmpty()
            return "no crutch word$then"
        }
        val then = previous?.let { " (${percent(it.crutchShare(top))} before)" }.orEmpty()
        return "crutch « ${ModelText.escape(top)} » ${percent(now.crutchShare(top))}$then"
    }

    /** [counts] the largest first, then in alphabetical order. */
    private fun ranked(counts: Map<String, Int>): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    private fun dayTime(at: Instant, zone: ZoneId): String = DAY_TIME.withZone(zone).format(at)

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

    private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
}
