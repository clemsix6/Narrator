package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.Briefing
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The memory's six sections of a chapter's briefing (M3 §11.1), in [BriefingSection.TAGS] order:
 * what gives the Narrator its continuity from one chapter to the next. Factual, dense, citable by refs,
 * without any voice: code labels in English, the memory's French texts escaped, the words someone said
 * between « ».
 *
 * - `<grimoire>`: the current version, as it is (never reduced).
 * - `<chronicle>`: the saga, the season in progress, the last [SESSIONS] sessions, then the chapters not
 *   in a session yet (titles only). Reduced: the saga and the season.
 * - `<canon>`: the open commitments by due time with their quote, then laws, names, places, gags, planned
 *   arcs, lore and opinions; cut at its budget with `(+N more: recall kinds=canon)` (never reduced).
 * - `<sheets>`: the condensed sheet of each player online, [MAX_CONDENSED] at most, one line for the
 *   others and for those that do not fit. Reduced: one line each.
 * - `<memories>`: the [MEMORIES] strongest memories about the players online. Left out of a reduced briefing.
 * - `<notes>`: the open notes for the Director, the dream hints of the players online (once), then the
 *   Narrator's verbal tics. Reduced: the notes without the tics.
 *
 * Each section stays within its budget ([Briefing.MEMORY_BUDGETS], counted on its lines joined by `\n`),
 * in both forms. Each source is read on its own: one that fails says `(unavailable: <reason>)` in its
 * section, the others are written; an empty one says so.
 *
 * What the form the briefing renders shows (and only that form: the reduced one shows less) goes into the
 * chapter: a row whose content is shown (a canon line, a chronicle entry, a memory, a note, the rows a
 * condensed sheet names) into [ChapterMemory.shownRefs]; a ref merely mentioned inside a text (the
 * Grimoire, a summary) does not. The players whose condensed sheet is shown get
 * [ChapterMemory.sheetsShownAt], so that their join in this chapter does not show it again; a sheet's
 * line is not the sheet, and their join will show it. Their `recalls` rows (`briefing`) and the notes to
 * mark `shown` wait in [ChapterMemory.unrecorded]: they are written once the model accepts the request
 * ([Memory.recordShown]), so a briefing built again after a request that was not accepted (the Director
 * then puts the chapter back as it was) is recorded once, in the form that was accepted.
 *
 * @param sheets where the sheets are read; they are rendered here ([SheetRenderer]), which knows the rows each form shows.
 * @param online the players online, in order of arrival.
 * @param zone the Journal's zone, for dates.
 */
class MemoryBriefing(
    private val store: MemoryStore,
    private val recall: Recall,
    private val sheets: PlayerSheets,
    private val journal: Journal,
    private val online: () -> List<PlayerRef>,
    zone: ZoneId,
) {
    private val log = Log.memory
    private val dates = MemoryDates(zone)
    private val renderer = SheetRenderer(zone)

    /** What a form of a section shows: the rows ([refs]), the players whose condensed sheet it is ([sheets]), the notes. */
    private class Showing(val refs: List<Ref> = emptyList(), val sheets: List<UUID> = emptyList(), val notes: List<Ref> = emptyList())

    /** One section built, with what each of its forms shows. */
    private class Built(val section: BriefingSection, val shown: Showing = Showing(), val reducedShown: Showing = shown)

    /**
     * The six sections at [now] (epoch milliseconds), for [chapter], which records what the form the briefing
     * renders ([reduced] or not) shows.
     */
    suspend fun sections(chapter: ChapterMemory, now: Long, reduced: Boolean = false): List<BriefingSection> {
        val started = System.nanoTime()
        val players = online()
        val at = Instant.ofEpochMilli(now)
        val built = coroutineScope {
            listOf(
                async { build("grimoire") { grimoire(at) } },
                async { build("chronicle") { chronicle(at) } },
                async { build("canon") { canon(at) } },
                async { build("sheets") { sheets(players, now) } },
                async { build("memories") { memories(players) } },
                async { build("notes") { notes(players, at) } },
            ).awaitAll()
        }
        val shown = record(built.map { if (reduced) it.reducedShown else it.shown }, chapter, now)
        log.debug {
            "briefing ${chapter.conversationId}${if (reduced) " (reduced)" else ""}: " +
                built.joinToString { "${it.section.tag} ${BriefText.length(it.section.lines)}" } +
                " characters, $shown refs shown, in ${(System.nanoTime() - started) / 1_000_000} ms"
        }
        return built.map { it.section }
    }

    /** One section; a source that fails says so in it. */
    private suspend fun build(tag: String, read: suspend () -> Built): Built = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        log.error("briefing: the $tag section is unavailable: $reason")
        val lines = listOf("(unavailable: ${ModelText.escape(reason)})")
        Built(BriefingSection(tag, "", lines, lines.takeUnless { tag == MEMORIES_TAG }))
    }

    /** What [shown] shows goes into [chapter], its `recalls` rows and notes into what waits to be recorded; returns how many refs. */
    private fun record(shown: List<Showing>, chapter: ChapterMemory, now: Long): Int {
        val refs = shown.flatMap { it.refs }.distinct()
        chapter.shownRefs += refs
        shown.flatMap { it.sheets }.forEach { chapter.sheetsShownAt[it] = now }
        chapter.unrecorded += Shown(refs, RecallChannel.BRIEFING, RecallContext(chapter.conversationId, null, null), shown.flatMap { it.notes }.distinct())
        return refs.size
    }

    // ── <grimoire> ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun grimoire(now: Instant): Built {
        val doc = store.doc(DocKey.Grimoire.key) ?: return whole("grimoire", listOf(NO_GRIMOIRE))
        val lines = BriefText.within(ModelText.lines(doc.content).lines().filter { it.isNotBlank() }, budget("grimoire"))
        val attributes = "version=\"${doc.version}\" written=\"${dates.dayTime(doc.writtenAt, now)}\""
        return Built(BriefingSection("grimoire", attributes, lines, lines))
    }

    // ── <chronicle> ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun chronicle(now: Instant): Built {
        val budget = budget("chronicle")
        val saga = store.chronicle(ChronicleLevel.SAGA, 1).firstOrNull()
        val seasons = store.chronicle(ChronicleLevel.SEASON, MAX_ROWS)
        val season = seasons.firstOrNull { it.open }
        val sessions = store.chronicle(ChronicleLevel.SESSION, SESSIONS).reversed()
        val newest = sessions.lastOrNull()
        val chapters = store.chronicle(ChronicleLevel.CHAPTER, MAX_ROWS).filter { newest == null || it.from >= newest.to }.reversed()
        if (saga == null && season == null && sessions.isEmpty() && chapters.isEmpty()) return whole("chronicle", listOf(NO_CHRONICLE))

        val stable = buildList {
            saga?.let { add(it to "Saga (${it.ref}, ${dates.day(it.from, now)}–${dates.day(it.to, now)}) ${title(it)}: ") }
            season?.let { add(it to "Season ${seasons.size} so far (${it.ref}, since ${dates.day(it.from, now)}) ${title(it)}: ") }
        }
        val recent = sessions.map { it to "Session ${dates.period(it.from, it.to, now)} (${it.ref}) ${title(it)}: " }
        val listed = chapters.takeLast(CHAPTERS_LISTED)
        val chapterLine = if (listed.isEmpty()) {
            null
        } else {
            val more = chapters.size - listed.size
            "Chapters not yet in a session: " + listed.joinToString(" · ") { "${it.ref} ${dates.dayTime(it.from, now)} ${title(it)}" } +
                if (more > 0) " (+$more older)" else ""
        }
        val lines = summaries(stable + recent, listOfNotNull(chapterLine), budget)
        val reduced = summaries(stable, emptyList(), budget)
        return Built(
            BriefingSection("chronicle", "", lines, reduced),
            Showing(stable.map { it.first.ref } + recent.map { it.first.ref } + listed.map { it.ref }),
            Showing(stable.map { it.first.ref }),
        )
    }

    /** `« La nuit des slimes »`, the title of a chronicle entry. */
    private fun title(entry: ChronicleEntry): String = BriefText.quote(entry.title, MemoryLimits.CHRONICLE_TITLE)

    /**
     * One line per entry, `head` + its summary, then the [fixed] lines, within [budget]: when they do not all
     * fit, the room is shared out evenly (water-filling) and the longest summaries are cut at a word.
     */
    private fun summaries(entries: List<Pair<ChronicleEntry, String>>, fixed: List<String>, budget: Int): List<String> {
        val texts = entries.map { (entry, head) -> head to ModelText.escape(entry.summary) }
        val naturals = texts.map { (head, summary) -> head.length + summary.length } + fixed.map { it.length }
        val caps = BriefText.share(naturals, budget - (naturals.size - 1).coerceAtLeast(0))
        val lines = texts.mapIndexed { i, (head, summary) ->
            head + SheetRenderer.cutText(summary, (caps[i] - head.length).coerceAtLeast(0))
        } + fixed.mapIndexed { i, line -> SheetRenderer.cutText(line, caps[texts.size + i]) }
        return BriefText.within(lines.filter { it.isNotEmpty() }, budget)
    }

    // ── <canon> ─────────────────────────────────────────────────────────────────────────────────────

    private suspend fun canon(now: Instant): Built {
        val open = store.canon(CanonFilter(open = true, limit = MAX_ROWS))
        if (open.isEmpty()) return whole("canon", listOf(NO_CANON))
        val (commitments, standing) = open.partition { it.kind.dueAllowed && it.status == CanonStatus.ACTIVE }
        val fill = Filler(budget("canon") - MORE_ROOM)
        if (commitments.isEmpty()) fill.line(NO_COMMITMENT)
        commitments.sortedWith(compareBy<CanonEntry> { it.dueAt ?: Instant.MAX }.thenByDescending { it.importance }.thenByDescending { it.ref.id })
            .forEach { fill.item(it.ref, commitment(it, now)) }
        val byWeight = compareByDescending<CanonEntry> { it.importance }.thenByDescending { it.ref.id }
        fun group(label: String, kinds: Set<CanonKind>, render: (CanonEntry) -> String) =
            fill.group(label, standing.filter { it.kind in kinds }.sortedWith(byWeight).map { it.ref to render(it) })
        group("Laws", setOf(CanonKind.LAW)) { "${it.ref} (since ${dates.day(it.createdAt, now)}): ${BriefText.cut(it.statement, STATEMENT_SHORT)}" }
        group("Names", setOf(CanonKind.NICKNAME, CanonKind.TITLE), ::name)
        group("Places", setOf(CanonKind.PLACE), ::place)
        group("Gags", setOf(CanonKind.RUNNING_GAG)) { gag(it, now) }
        group("Arcs planned", setOf(CanonKind.ARC)) { "${it.ref}: ${BriefText.cut(it.statement, STATEMENT_SHORT)}" }
        group("Lore", setOf(CanonKind.LORE, CanonKind.CLAIM)) { "${it.ref} ★${it.importance}: ${BriefText.cut(it.statement, STATEMENT_SHORT)}" }
        group("Opinions", setOf(CanonKind.OPINION)) { "${it.ref} ★${it.importance}: ${BriefText.cut(it.statement, STATEMENT_SHORT)}" }
        val lines = fill.lines + listOfNotNull(fill.omitted.takeIf { it > 0 }?.let { "(+$it more: recall kinds=canon)" })
        return Built(BriefingSection("canon", "", lines, lines), Showing(fill.shown))
    }

    /**
     * `C-45 promise → Mimosa, due 21:00 (in 1h): « je te rends tes bottes demain, promis » — Le Narrateur a promis…`,
     * with ` · stakes: …` for a challenge.
     */
    private fun commitment(entry: CanonEntry, now: Instant): String = buildString {
        append("${entry.ref} ${entry.kind.id} → ${BriefText.players(entry.players)}")
        entry.dueAt?.let { append(", ").append(BriefText.due(it, now, dates)) }
        append(": ")
        entry.quote?.let { append(BriefText.quote(it, MemoryLimits.CANON_QUOTE)).append(" — ") }
        append(BriefText.cut(entry.statement, STATEMENT_LONG))
        entry.stakes?.let { append(" · stakes: ").append(BriefText.cut(it, STAKES)) }
    }

    /** `Bartok « le Plongeur » (C-12, 5 uses)`, `Josiane title « Architecte du Ciel » (C-39)`. */
    private fun name(entry: CanonEntry): String {
        val who = BriefText.players(entry.players)
        val title = if (entry.kind == CanonKind.TITLE) " title" else ""
        val facts = listOfNotNull(entry.ref.toString(), entry.freshness?.id, entry.uses.takeIf { it > 0 }?.let { "$it uses" })
        return "$who$title « ${BriefText.named(entry)} » (${facts.joinToString(", ")})"
    }

    /** `C-56 « la Dent du Juge » (overworld 120 90 -400)`. */
    private fun place(entry: CanonEntry): String {
        val where = entry.place?.let { " (${ModelText.escape(ToolText.dimension(it.dim))} ${it.x} ${it.y} ${it.z})" } ?: ""
        return "${entry.ref} « ${BriefText.named(entry)} »$where"
    }

    /** `C-27 Le Narrateur commente chaque chute… (worn, 5 uses, last 10-01)`. */
    private fun gag(entry: CanonEntry, now: Instant): String {
        val facts = listOfNotNull(entry.freshness?.id, "${entry.uses} uses", entry.lastUsedAt?.let { "last ${dates.day(it, now)}" })
        return "${entry.ref} ${BriefText.cut(entry.statement, STATEMENT_SHORT)} (${facts.joinToString(", ")})"
    }

    // ── <sheets> ────────────────────────────────────────────────────────────────────────────────────

    /** A player's sheet as read: its condensed form and its line, or one line saying why there is none. */
    private class Read(val player: PlayerRef, val condensed: SheetRenderer.Rendered?, val line: String)

    /**
     * Shows the condensed sheets that fit and the rows they name; the lines show no row and no sheet. The
     * reduced form is a line per player: it shows neither.
     */
    private suspend fun sheets(players: List<PlayerRef>, now: Long): Built {
        if (players.isEmpty()) return Built(BriefingSection("sheets", "", listOf(NOBODY_ONLINE), listOf(NOBODY_ONLINE)))
        val read = coroutineScope { players.map { player -> async { read(player, now) } }.awaitAll() }
        val budget = budget("sheets")
        val forms = read.map { it.line }.toMutableList()
        val condensed = mutableListOf<Read>()
        for ((i, sheet) in read.withIndex()) {
            val full = sheet.condensed ?: continue
            if (condensed.size >= MAX_CONDENSED) break
            val total = BriefText.length(forms) - forms[i].length + full.text.length
            if (total <= budget) {
                forms[i] = full.text
                condensed += sheet
            }
        }
        val lines = BriefText.within(forms.flatMap { it.lines() }, budget)
        val reduced = BriefText.within(read.map { it.line }, budget)
        return Built(
            BriefingSection("sheets", "", lines, reduced),
            Showing(condensed.flatMap { it.condensed!!.refs }.distinct(), condensed.map { it.player.uuid }),
            Showing(),
        )
    }

    private suspend fun read(player: PlayerRef, now: Long): Read = try {
        val sheet = sheets.sheet(player.uuid, now)
        if (sheet == null) {
            Read(player, null, "${player.shownName}: (no sheet: this player is not in the Journal yet)")
        } else {
            Read(player, renderer.rendered(sheet, SheetDetail.CONDENSED, now), renderer.render(sheet, SheetDetail.LINE, now))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        log.warn("briefing: the sheet of ${player.name} is unavailable: $reason")
        Read(player, null, "${player.shownName}: (sheet unavailable: ${ModelText.escape(reason)})")
    }

    // ── <memories> ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun memories(players: List<PlayerRef>): Built {
        val hits = recall.search(
            RecallQuery(null, players = players.map { it.uuid }, kinds = setOf(RefKind.MEMORY), limit = MEMORIES, purpose = RecallPurpose.BRIEFING),
        )
        if (hits.isEmpty()) {
            val none = if (players.isEmpty()) NO_MEMORY else NO_MEMORY_ONLINE
            return Built(BriefingSection(MEMORIES_TAG, "", listOf(none), null))
        }
        val budget = budget(MEMORIES_TAG)
        val caps = BriefText.share(hits.map { it.line.length }, budget - (hits.size - 1))
        val lines = BriefText.within(hits.mapIndexed { i, hit -> BriefText.cutQuoted(hit.line, caps[i]) }, budget)
        return Built(BriefingSection(MEMORIES_TAG, "", lines, null), Showing(hits.take(lines.size).map { it.ref }), Showing())
    }

    // ── <notes> ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The open notes for the Director, until they have been shown in [Hygiene.NOTE_SHOWINGS] accepted briefings; the
     * dream hints of the players online, until one is shown. A note is marked only once a request that showed
     * it is accepted: a briefing built again shows the same notes.
     */
    private suspend fun notes(players: List<PlayerRef>, now: Instant): Built {
        val online = players.associateBy { it.uuid }
        val notes = store.openNotes().filter { note ->
            when (note.kind) {
                NoteKind.DREAM_HINT -> note.players.any { it.uuid in online } && note.status == NoteStatus.OPEN
                else -> note.shownCount < Hygiene.NOTE_SHOWINGS
            }
        }
        val style = style(now)
        val budget = budget("notes")
        val fill = Filler(budget - (style?.length?.plus(1) ?: 0) - MORE_ROOM)
        if (notes.isEmpty()) fill.line(NO_NOTE)
        for (note in notes) {
            val line = if (note.kind == NoteKind.DREAM_HINT) {
                val who = note.players.filter { it.uuid in online }.joinToString { it.shownName }
                "${note.ref} dream_hint for $who (once): ${ModelText.escape(note.text)}"
            } else {
                "${note.ref} ${note.kind.id}: ${ModelText.escape(note.text)}"
            }
            fill.item(note.ref, line)
        }
        val more = fill.omitted.takeIf { it > 0 }?.let { "(+$it more open notes)" }
        val lines = BriefText.within(fill.lines + listOfNotNull(more, style), budget)
        val reduced = BriefText.within(fill.lines + listOfNotNull(more), budget)
        return Built(BriefingSection("notes", "", lines, reduced), Showing(fill.shown, notes = fill.shown))
    }

    /** `Style: overused lately: « Ministère de la » ×7 (last 2h ago) · …`; null without tics; says so when the chat cannot be read. */
    private suspend fun style(now: Instant): String? = try {
        val tics = journal.read(READ_TIMEOUT_MS) { Tics.render(Tics.find(Tics.lines(it)), now) }
        if (tics.isEmpty()) null else "Style: overused lately: ${tics.joinToString(" · ")}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        log.warn("briefing: the Narrator's tics are unavailable: $reason")
        "Style: (unavailable: ${ModelText.escape(reason)})"
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** A section whose two forms are [lines]. */
    private fun whole(tag: String, lines: List<String>) = Built(BriefingSection(tag, "", lines, lines))

    /**
     * Lines filled in order within [room] characters (joined by `\n`): a line or an item that does not fit
     * is left out and counted in [omitted]; what is left out never stops a shorter one after it.
     */
    private class Filler(private val room: Int) {
        val lines = mutableListOf<String>()
        val shown = mutableListOf<Ref>()
        var omitted = 0
            private set

        private val used get() = BriefText.length(lines)

        private fun fits(line: String) = used + (if (lines.isEmpty()) 0 else 1) + line.length <= room

        fun line(line: String) {
            if (fits(line)) lines += line
        }

        fun item(ref: Ref, line: String) {
            if (fits(line)) {
                lines += line
                shown += ref
            } else {
                omitted++
            }
        }

        /** `label: a · b · c`, as many items as fit. */
        fun group(label: String, items: List<Pair<Ref, String>>) {
            if (items.isEmpty()) return
            var line = "$label: "
            val kept = mutableListOf<Ref>()
            for ((ref, text) in items) {
                val next = if (kept.isEmpty()) line + text else "$line · $text"
                if (fits(next)) {
                    line = next
                    kept += ref
                } else {
                    omitted++
                }
            }
            if (kept.isEmpty()) return
            lines += line
            shown += kept
        }
    }

    companion object {
        /** Sessions of `<chronicle>`. */
        const val SESSIONS = 2

        /** Chapters named, at most, after the sessions. */
        const val CHAPTERS_LISTED = 8

        /** Condensed sheets in `<sheets>`, at most (M3 §11.1: 6 × 1,800 = its budget). */
        const val MAX_CONDENSED = 6

        /** Memories of `<memories>` (M3 §10.6). */
        const val MEMORIES = 12

        internal const val MEMORIES_TAG = "memories"

        private const val MAX_ROWS = 500
        private const val READ_TIMEOUT_MS = 5_000L

        /** Room kept for the `(+N more …)` line. */
        private const val MORE_ROOM = 48

        private const val STATEMENT_LONG = 200
        private const val STATEMENT_SHORT = 160
        private const val STAKES = 120

        private const val NO_GRIMOIRE = "(no grimoire yet: the Narrator has not dreamed yet)"
        private const val NO_CHRONICLE = "(no chronicle yet: the Dreamer writes it in a deep dream, the chapters at each nap)"
        private const val NO_CANON = "(no canon yet: nothing promised, decided nor named)"
        private const val NO_COMMITMENT = "(no open commitment)"
        private const val NOBODY_ONLINE = "(nobody online)"
        private const val NO_MEMORY = "(no memory yet)"
        private const val NO_MEMORY_ONLINE = "(no memory yet about the players online)"
        private const val NO_NOTE = "(no open note)"

        private fun budget(tag: String): Int = Briefing.MEMORY_BUDGETS.getValue(tag)
    }
}

/** Dates as the briefing and the joins write them, in the Journal's zone: `10-03`, `10-03 20:10`, with the year when it is not the year of `now`. */
internal class MemoryDates(private val zone: ZoneId) {
    private val day = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(zone)
    private val yearDay = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone)
    private val dayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(zone)
    private val yearDayTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
    private val hour = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)

    private fun sameYear(at: Instant, now: Instant) = at.atZone(zone).year == now.atZone(zone).year

    private fun sameDay(a: Instant, b: Instant) = a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()

    fun day(at: Instant, now: Instant): String = (if (sameYear(at, now)) day else yearDay).format(at)

    fun dayTime(at: Instant, now: Instant): String = (if (sameYear(at, now)) dayTime else yearDayTime).format(at)

    /** `21:00` today, else `10-04 21:00`. */
    fun time(at: Instant, now: Instant): String = if (sameDay(at, now)) hour.format(at) else dayTime(at, now)

    /** `10-01 20:10–21:52`, or `10-01 23:10–10-02 01:15` across midnight. */
    fun period(from: Instant, to: Instant, now: Instant): String =
        "${dayTime(from, now)}–${if (sameDay(from, to)) hour.format(to) else dayTime(to, now)}"
}

/** How the memory's briefing and joins write their texts: escaped, cut at a word, quotes kept whole. Pure. */
internal object BriefText {
    /** The length of [lines] joined by `\n`. */
    fun length(lines: List<String>): Int = if (lines.isEmpty()) 0 else lines.sumOf { it.length } + lines.size - 1

    /** [value] escaped and cut at a word to [max], never inside a quote. */
    fun cut(value: String, max: Int): String = SheetRenderer.cutText(ModelText.escape(value), max)

    /** Someone's words between « », escaped and cut inside to [max], their own « » turned into ‹ › so the quote cannot end early. */
    fun quote(value: String, max: Int): String =
        "« " + SheetRenderer.cutText(ModelText.escape(value).replace('«', '‹').replace('»', '›'), max) + " »"

    /** `Bartok, Mimosa`, or `all` for an entry about everyone. */
    fun players(players: List<PlayerRef>): String = players.joinToString { it.shownName }.ifEmpty { "all" }

    /** The name a nickname, a title or a place gives: what its statement puts between « », else its quote when short, else its statement. */
    fun named(entry: CanonEntry): String {
        val named = NAMED.find(entry.statement)?.groupValues?.get(1)?.trim()
        val name = named ?: entry.quote?.takeIf { it.length <= NAME_MAX } ?: entry.statement
        return SheetRenderer.cutText(ModelText.escape(name).replace('«', '‹').replace('»', '›'), NAME_MAX)
    }

    /** `due 21:00 (in 1h)`, `due 10-04 21:00 (in 1d1h)`, or `due 20:00 (40m ago, overdue)`. */
    fun due(at: Instant, now: Instant, dates: MemoryDates): String {
        val left = at.toEpochMilli() - now.toEpochMilli()
        val wait = if (left > 0) "in ${ToolText.wait(left)}" else "${ToolText.duration(-left)} ago, overdue"
        return "due ${dates.time(at, now)} ($wait)"
    }

    /**
     * [room] shared out among parts of [naturals] lengths (water-filling): a part shorter than its share keeps
     * its length, the others share what is left evenly.
     */
    fun share(naturals: List<Int>, room: Int): IntArray {
        val caps = IntArray(naturals.size)
        var left = room.coerceAtLeast(0)
        var parts = naturals.size
        for (i in naturals.indices.sortedBy { naturals[it] }) {
            val portion = left / parts
            caps[i] = minOf(naturals[i], portion)
            left -= caps[i]
            parts--
        }
        return caps
    }

    /**
     * A `recall` line within [max]: the memory's text between its closing « … » is cut at a word, so that the
     * ref, the date and the title stay whole; any other line is cut at a word.
     */
    fun cutQuoted(line: String, max: Int): String {
        if (line.length <= max) return line
        val open = line.indexOf(": « ")
        if (open < 0 || !line.endsWith(" »")) return SheetRenderer.cutText(line, max)
        val head = line.substring(0, open + 4)
        val room = max - head.length - 2
        if (room < MIN_QUOTED) return SheetRenderer.cutText(line.substring(0, open), max)
        return head + SheetRenderer.cutText(line.substring(open + 4, line.length - 2), room) + " »"
    }

    /** [lines] within [budget] (joined by `\n`): the last lines that do not fit go, the last one kept is cut. A safety net. */
    fun within(lines: List<String>, budget: Int): List<String> {
        if (length(lines) <= budget) return lines
        val kept = mutableListOf<String>()
        for (line in lines) {
            val room = budget - length(kept) - (if (kept.isEmpty()) 0 else 1)
            if (line.length <= room) {
                kept += line
            } else {
                if (room > MIN_CUT) kept += SheetRenderer.cutText(line, room)
                break
            }
        }
        return kept
    }

    private const val NAME_MAX = 40
    private const val MIN_QUOTED = 40
    private const val MIN_CUT = 24
    private val NAMED = Regex("«\\s*([^«»]{1,$NAME_MAX})\\s*»")
}
