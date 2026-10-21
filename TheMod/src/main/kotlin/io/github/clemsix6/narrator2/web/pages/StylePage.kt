package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.StyleCommands
import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stats.Reactions
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import io.github.clemsix6.narrator2.storyteller.Intervention
import io.github.clemsix6.narrator2.storyteller.Interventions
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.style.DisabledStyle
import io.github.clemsix6.narrator2.style.StyleReport
import io.github.clemsix6.narrator2.style.StyleReport.Line
import io.github.clemsix6.narrator2.style.StyleReport.Measures
import io.github.clemsix6.narrator2.style.StyleReport.RegisterUse
import io.github.clemsix6.narrator2.web.Bar
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.bars
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * `/style` (M6 §8.4): the Narrator's style. First what the watch holds now (in memory, no read): the `Style:` line of
 * the situation, what is at rest, the diversity of the latest 50 lines, the latest registers. Then, over a period
 * (`?period=day|week|month`, a week by default), in one read of the Journal:
 * - the diversity (M6 §3.7) of each session of play: from a first join to the last leave, gaps under
 *   [SESSION_GAP_MINUTES] minutes merged; [SESSIONS_PER_PAGE] sessions a page, the latest first;
 * - the registers and how players took them (`StyleReport.registers`), the neighbors the line review confuses counted
 *   together ([FAMILIES], M6-02's advice), each register on its own below;
 * - the tics and crutch words of the period; the lines flagged for their tone, with the line; the interventions of
 *   the Director by lever (`Interventions`).
 *
 * Only the Director's lines count (said in a turn), as for `/narrator style` and the Dreamer's review.
 */
object StylePage : WebPage {
    override val root = "style"

    override val label = "Style"

    /** The periods the page reads. */
    private enum class Span(val label: String, val days: Long) {
        DAY("last day", 1), WEEK("last 7 days", 7), MONTH("last 30 days", 30);

        val id: String get() = name.lowercase()
    }

    /** Neighboring registers the line review confuses (M6-02, T1: ±10 points from one pass to the next), counted together. */
    private val FAMILIES: Map<ComicRegister, String> = mapOf(
        ComicRegister.DIVINE_BUREAUCRACY to "administration",
        ComicRegister.CUSTOMER_SERVICE to "administration",
        ComicRegister.CLASSIFIED_ADS to "administration",
        ComicRegister.SPORTS_COMMENTARY to "show",
        ComicRegister.GAME_SHOW to "show",
    )

    /** Sessions of play shown a page. */
    const val SESSIONS_PER_PAGE = 20

    /** Two spans of play closer than this make one session. */
    const val SESSION_GAP_MINUTES = 30

    /** The Director's latest lines whose review is counted. */
    private const val LATEST = 50

    /** Lines flagged for their tone shown, the most serious first; the others are counted. */
    private const val FLAGS = 50

    /** Crutch words shown, and players named for a lever. */
    private const val CRUTCHES = 20
    private const val TARGETS = 5

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val span = request.choice<Span>("period") ?: Span.WEEK
        val paging = Paging.of(request, SESSIONS_PER_PAGE)
        val now = Instant.ofEpochMilli(context.now)
        val from = now.minus(Duration.ofDays(span.days))
        val reading: Reading = try {
            read(context, from, now)
        } catch (e: JournalUnavailableException) {
            Reading.Unread("${e.message}")
        } catch (e: JournalTimeoutException) {
            Reading.Unread("${e.message}: the Journal is busy, reload the page in a moment")
        }
        return Page("Style") {
            watch(context)
            h2("Over the ${span.label}")
            tabs(span.label, Span.entries.map { it.label to link(root, query = mapOf("period" to it.id)) })
            when (reading) {
                is Reading.Unread -> p("Not shown: ${reading.why}.", "failure")
                is Reading.Read -> period(reading, request, paging, context)
            }
        }
    }

    // ── Now: the watch ──────────────────────────────────────────────────────────────────────────────

    private fun Html.watch(context: PageContext) {
        val style = context.services.style
        h2("Now: the watch")
        if (style === DisabledStyle) {
            p("The style watch is off: it runs with the Journal.", "warning")
            return
        }
        val line = style.situationLine(context.now)
        if (line == null) p("The situation says nothing of the style: nothing at rest, no register overused.", "muted") else pre(line)
        val atRest = style.atRest()
        val diversity = style.diversity()
        dl {
            item("Openers at rest") {
                if (atRest.openers.isEmpty()) text("none")
                atRest.openers.forEachIndexed { i, opener ->
                    if (i > 0) text(" · ")
                    text("« ${opener.opener} » ×${opener.count}")
                    if (opener.examples.isNotEmpty()) small(" (${opener.examples.joinToString { "« $it »" }})")
                }
            }
            item("Phrases at rest", atRest.phrases.joinToString { "« $it »" }.ifEmpty { "none" })
            item("Crutch words at rest", atRest.crutches.joinToString { "« $it »" }.ifEmpty { "none" })
            item(
                "Latest ${diversity.lines} lines",
                "openers distinct ${decimal(diversity.openersDistinct)} · ${StyleCommands.registers(diversity)} · " +
                    "${diversity.tics} ${TraceText.plural(diversity.tics, "tic")}" +
                    (diversity.crutches.takeIf { it.isNotEmpty() }?.joinToString(", ", " · crutches ") { (word, count) -> "« $word » in $count" }.orEmpty()),
            )
            item("Last registers", style.lastRegisters(8).joinToString { it.id }.ifEmpty { "none labeled yet" })
            item("Least used lately", style.leastUsedRegisters(3).joinToString { it.id })
        }
    }

    // ── Over a period ───────────────────────────────────────────────────────────────────────────────

    /** What the page read of the period, or why it could not. */
    private sealed interface Reading {
        class Read(
            val lines: List<Line>,
            val names: Tics.Names,
            val reactions: List<Reactions.Reaction>,
            val sessions: List<Session>,
            val interventions: List<Intervention>,
            val coverage: StyleReport.Coverage,
        ) : Reading

        class Unread(val why: String) : Reading
    }

    /** A session of play: from [from] to [to]. */
    private class Session(val from: Instant, val to: Instant)

    private fun read(context: PageContext, from: Instant, now: Instant): Reading.Read {
        val language = context.services.settings.get().config.language
        val words = HeuristicWords.bundled(language) ?: HeuristicWords.bundled(DEFAULT_LANGUAGE)
        val end = now.plusMillis(1)
        return context.read { c ->
            val lines = StyleReport.lines(c, StyleReport.Period(from, end))
            val reactions = if (words != null && lines.any { it.reviewed }) Reactions.read(c, from, end, words) else emptyList()
            Reading.Read(lines, Tics.Names.of(Tics.names(c)), reactions, sessions(c, from, now), Interventions.recent(c, from), StyleReport.coverage(c, LATEST))
        }
    }

    /** The sessions of play between [from] and [now], the latest first: the union of the players' sessions, gaps merged. */
    private fun sessions(c: Connection, from: Instant, now: Instant): List<Session> =
        c.query(SESSIONS_SQL, from, now, now, timeoutSeconds = QUERY_SECONDS) { Session(Instant.ofEpochMilli(getLong(1)), Instant.ofEpochMilli(getLong(2))) }

    private fun Html.period(read: Reading.Read, request: WebRequest, paging: Paging, context: PageContext) {
        val all = StyleReport.measures(read.lines, read.names)
        p("muted") {
            text("${all.lines} ${TraceText.plural(all.lines, "line")} of the Director, ${all.reviewed} reviewed. ")
            text("The review labeled ${read.coverage.reviewed} of the Director's latest ${read.coverage.lines} lines.")
        }
        sessions(read, all, request, paging, context)
        registers(read)
        tics(read, all)
        flags(read.lines, context)
        levers(read.interventions, context)
    }

    private fun Html.sessions(read: Reading.Read, all: Measures, request: WebRequest, paging: Paging, context: PageContext) {
        h3("Diversity by session")
        val (sessions, more) = paging.rows(read.sessions.drop(paging.offset).take(paging.limit))
        table(listOf("Session", "#Lines", "#Reviewed", "#Openers distinct", "Top register", "#Entropy", "Top crutch", "#Tics", "#Flagged")) {
            if (paging.page == 1) measuresRow("the whole period", all, "warning")
            sessions.forEach { session ->
                val said = read.lines.filter { !it.ts.isBefore(session.from) && !it.ts.isAfter(session.to) }
                measuresRow("${context.time(session.from)} → ${context.time(session.to)}", StyleReport.measures(said, read.names), null)
            }
        }
        if (read.sessions.isEmpty()) p("Nobody played in this period.", "muted")
        pager(request, paging, more)
        p("A session runs from a first join to the last leave, gaps under $SESSION_GAP_MINUTES minutes merged. Openers count the lines said with say in 25 characters or more; the shares, the reviewed lines.", "muted")
    }

    private fun Html.Table.measuresRow(label: String, measures: Measures, cls: String?) = row(cls) {
        cell(label)
        number(measures.lines)
        number(measures.reviewed)
        number(if (measures.openersDistinct > 0) decimal(measures.openersDistinct) else null)
        // Lines all plain or other have no register of the voice (M6 §3.7): said so, as /narrator style does.
        cell(measures.topRegister?.let { "${it.id} ${percent(measures.topRegisterShare)}" } ?: if (measures.reviewed > 0) "none of the voice · plain ${percent(measures.plainShare)}" else null)
        number(if (measures.reviewed > 0) decimal(measures.registerEntropy) else null)
        cell(measures.crutches.firstOrNull()?.let { (word, _) -> "« $word » ${percent(measures.crutchShare(word))}" })
        number(measures.tics)
        number(measures.flagged)
    }

    /** One family of registers ([FAMILIES]): its registers and how players took their lines. */
    private class Family(val name: String, val uses: List<RegisterUse>) {
        val lines = uses.sumOf { it.lines }
        val laughed = uses.sumOf { it.laughed }
        val annoyed = uses.sumOf { it.annoyed }
        val replied = uses.sumOf { it.replied }
        val ignored = uses.sumOf { it.ignored }
        val laughing = merged(uses.flatMap { it.laughing })
        val annoying = merged(uses.flatMap { it.annoying })
    }

    private fun Html.registers(read: Reading.Read) {
        h3("Registers and how players took them")
        val uses = StyleReport.registers(read.lines, read.reactions)
        if (uses.isEmpty()) {
            p("No line reviewed in this period: the line review was off, or failed.", "muted")
            return
        }
        val reviewed = uses.sumOf { it.lines }
        val families = uses.groupBy { FAMILIES[it.register] ?: it.register.id }.map { (name, group) -> Family(name, group) }
            .sortedWith(compareByDescending<Family> { it.lines }.thenBy { it.name })
        bars("Reviewed lines by register family", families.map { Bar(it.name, it.lines.toDouble(), "${it.lines} · ${percent(it.lines.toDouble() / reviewed)}") })
        table(listOf("Family", "Registers", "#Lines", "#Share", "Laughed", "Annoyed", "#Replied", "#Ignored")) {
            families.forEach { family ->
                row {
                    cell(family.name)
                    cell(family.uses.joinToString { "${it.register.id} ${it.lines}" })
                    number(family.lines)
                    number(percent(family.lines.toDouble() / reviewed))
                    cell(who(family.laughed, family.laughing))
                    cell(who(family.annoyed, family.annoying))
                    number(family.replied)
                    number(family.ignored)
                }
            }
        }
        p(
            "The line review confuses neighboring registers (${FAMILIES.entries.groupBy({ it.value }, { it.key.id }).entries.joinToString("; ") { (family, members) -> "$family: ${members.joinToString()}" }}): " +
                "they are counted together; a register alone moves by about ten points from one review to another.",
            "muted",
        )
        details("Each register") {
            table(listOf("Register", "#Lines", "#Share", "Laughed", "Annoyed", "#Replied", "#Ignored")) {
                uses.forEach { use ->
                    row {
                        cell(use.register.id)
                        number(use.lines)
                        number(percent(use.lines.toDouble() / reviewed))
                        cell(who(use.laughed, use.laughing))
                        cell(who(use.annoyed, use.annoying))
                        number(use.replied)
                        number(use.ignored)
                    }
                }
            }
        }
    }

    private fun Html.tics(read: Reading.Read, all: Measures) {
        h3("Tics and crutch words")
        val tics = Tics.tics(read.lines.map { Tics.said(it.text, it.ts, read.names) })
        if (tics.isEmpty()) p("No tic: no opener in 4 lines, no phrase in 3.", "muted")
        else ul { tics.forEach { tic -> li("« ${tic.text} » ×${tic.count}" + (tic.share?.let { " (${percent(it)} of the lines)" }.orEmpty())) } }
        if (all.crutches.isEmpty()) p("No crutch word marked by the review.", "muted")
        else table(listOf("Crutch word", "#Lines", "#Share")) {
            all.crutches.take(CRUTCHES).forEach { (word, count) ->
                row {
                    cell("« $word »")
                    number(count)
                    number(percent(all.crutchShare(word)))
                }
            }
        }
    }

    private fun Html.flags(lines: List<Line>, context: PageContext) {
        h3("Lines flagged for their tone")
        val flagged = lines.filter { it.flags.isNotEmpty() }
            .sortedWith(compareBy<Line> { it.flags.first().first.ordinal }.thenByDescending { line -> line.flags.maxOf { it.second } }.thenBy { it.ts })
        if (flagged.isEmpty()) {
            p("None: no line reached ${StyleReport.FLAG} in ${StyleReport.FLAWS.joinToString { it.id }}.", "muted")
            return
        }
        table(listOf("When", "Flags", "Register", "Line")) {
            flagged.take(FLAGS).forEach { line ->
                row(if (line.flags.first().first.ordinal <= StyleReport.Flaw.OUT_OF_CHARACTER.ordinal) "failure" else "warning") {
                    cell(context.time(line.ts))
                    cell(StyleReport.flagList(line))
                    cell(line.register?.id)
                    cell(line.text)
                }
            }
        }
        if (flagged.size > FLAGS) p("… and ${flagged.size - FLAGS} more.", "muted")
    }

    /** The Director's interventions of the period by lever: how many, of which kinds, when last, on whom. */
    private fun Html.levers(interventions: List<Intervention>, context: PageContext) {
        h3("Interventions by lever")
        if (interventions.isEmpty()) {
            p("None in this period.", "muted")
            return
        }
        val levers = interventions.groupBy { it.key.substringBefore(':') }.entries.sortedWith(compareByDescending<Map.Entry<String, List<Intervention>>> { it.value.size }.thenBy { it.key })
        table(listOf("Lever", "#Times", "Kinds", "Last", "Players")) {
            levers.forEach { (lever, uses) ->
                row {
                    cell(lever)
                    number(uses.size)
                    cell(counted(uses.mapNotNull { use -> use.key.substringAfter(':', "").ifEmpty { null } }).joinToString { (kind, count) -> if (count > 1) "$kind ×$count" else kind })
                    cell(context.time(uses.maxOf { it.at }))
                    cell {
                        val targets = counted(uses.flatMap { it.targets })
                        if (targets.isEmpty()) span("everyone", "muted")
                        targets.take(TARGETS).forEachIndexed { i, (name, count) ->
                            if (i > 0) text(", ")
                            a(link("players", name), name)
                            if (count > 1) text(" ×$count")
                        }
                    }
                }
            }
        }
        p("A lever's kind: the punishment, the challenge's goal, the module, the effect…; say: the register of a reviewed line.", "muted")
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────────

    /** `3 (Bartok 2, Mimosa 1)`. */
    private fun who(count: Int, names: List<Pair<String, Int>>): String =
        "$count" + if (names.isEmpty()) "" else names.joinToString(", ", " (", ")") { (name, times) -> "$name $times" }

    /** [names] counted, the most frequent first, then in alphabetical order. */
    private fun counted(names: List<String>): List<Pair<String, Int>> = merged(names.map { it to 1 })

    private fun merged(counts: List<Pair<String, Int>>): List<Pair<String, Int>> =
        counts.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

    private const val DEFAULT_LANGUAGE = "fr"

    /** The union of the sessions' spans between two instants (an open session runs until the second), as islands. */
    private val SESSIONS_SQL = """
        WITH spans AS (
            SELECT greatest(joined_at, ?) AS a, least(coalesce(left_at, ?), ?) AS b FROM sessions
        ), played AS (SELECT a, b FROM spans WHERE b > a),
        marked AS (
            SELECT a, b, CASE WHEN a <= max(b) OVER (ORDER BY a, b ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) + INTERVAL $SESSION_GAP_MINUTES MINUTE
                              THEN 0 ELSE 1 END AS starts
            FROM played
        ), islands AS (
            SELECT a, b, sum(starts) OVER (ORDER BY a, b ROWS UNBOUNDED PRECEDING) AS island FROM marked
        )
        SELECT epoch_ms(min(a)), epoch_ms(max(b)) FROM islands GROUP BY island ORDER BY 1 DESC
    """.trimIndent()
}
