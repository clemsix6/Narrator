package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * What the memory attaches when a player joins (M3 §11.2): their sheet, in the `<memory>` block of the
 * Director's wake-up, within [MAX_CHARS] per player.
 *
 * ```
 * SHEET Bartok (joined; away 2d3h, last session 10-01 20:10–21:52):
 *   … the condensed sheet (M3 §11.4), indented …
 *   Last session: the `since_last` text the Chronicler wrote when the chapter this player left in closed
 *   While away: 2 sessions (H-89 10-02 « La nuit des slimes », H-90 10-03 « Le pont de Mimosa ») · open about this player: C-45 promise, due in 1h « … »
 *   Dream hint (once, N-14): the Dreamer's factual hint, shown at one join only
 * ```
 *
 * - A player's first time on the server: `SHEET Kelvin (first time on this server)`, and nothing else.
 * - A sheet already shown in this chapter less than [RESHOW_MS] ago (a reconnection): one line,
 *   `SHEET Bartok (rejoined; away 4m; full sheet shown 12m ago in this chapter)`.
 * - A sheet shown by the briefing of this very wake-up (the player was online when the chapter opened):
 *   nothing.
 * - A player who left again among the same events: nothing.
 * - Beyond [MAX_FULL] players joining at once, the others get one line each: header and the sheet's line.
 *
 * "While away" is written by the code: the chronicle's sessions written since the player left, the
 * chapters not in a session yet, the open commitments about them, the plans that target them included
 * (`C-61 plan: Un comité d'accueil attend Bartok…`). Every source is read on its own; one
 * that fails says so in its place. What is shown goes into the chapter ([ChapterMemory.shownRefs],
 * [ChapterMemory.sheetsShownAt]); its `recalls` rows (`join`) and its dream hints to mark `shown` wait in
 * [ChapterMemory.unrecorded] for the model to accept the request ([Memory.recordShown]). Built again after
 * a request that was not accepted (the Director then puts the chapter back as it was), the same block
 * comes back, its dream hint included, and is recorded once.
 *
 * @param sheets where the sheets are read; they are rendered here ([SheetRenderer]), which knows the rows each form shows.
 * @param zone the Journal's zone, for dates.
 */
class JoinBrief(
    private val store: MemoryStore,
    private val sheets: PlayerSheets,
    private val journal: Journal,
    zone: ZoneId,
) {
    private val log = Log.memory
    private val dates = MemoryDates(zone)
    private val renderer = SheetRenderer(zone)

    /** The player's last session before this one. */
    private class Session(val joinedAt: Instant, val leftAt: Instant)

    /** One part of "While away": its items, and the words around them; [error] when it could not be read. */
    private class Part(val items: List<Pair<Ref, String>>, val wrap: (List<String>, Int) -> String, val error: String? = null) {
        var shown: Int = items.size
    }

    /**
     * The `SHEET` lines of the players who join among [signals], in order of arrival: the last join of each
     * player still online at the end of [signals]. Empty when nobody joins.
     *
     * @param now epoch milliseconds.
     */
    suspend fun lines(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): List<String> =
        joins(signals).flatMapIndexed { i, join -> brief(join, chapter, now, full = i < MAX_FULL) }

    private suspend fun brief(envelope: Envelope, chapter: ChapterMemory, now: Long, full: Boolean): List<String> {
        val join = envelope.event as PlayerJoined
        val player = join.player
        val name = player.shownName
        if (join.firstTime) {
            chapter.sheetsShownAt[player.uuid] = now
            return listOf("SHEET $name (first time on this server)")
        }
        val shownAt = chapter.sheetsShownAt[player.uuid]
        if (shownAt != null && shownAt >= envelope.ts) return emptyList()
        val last = lastSession(player.uuid, join.sessionId)
        val away = last?.let { "away ${ToolText.duration(now - it.leftAt.toEpochMilli())}" }
        if (shownAt != null && now - shownAt < RESHOW_MS) {
            return listOf("SHEET $name (${listOfNotNull("rejoined", away).joinToString("; ")}; full sheet shown ${ToolText.duration(now - shownAt)} ago in this chapter)")
        }
        val lastPeriod = last?.let { "last session ${dates.period(it.joinedAt, it.leftAt, Instant.ofEpochMilli(now))}" }
        val facts = listOfNotNull("joined", listOfNotNull(away, lastPeriod).joinToString(", ").ifEmpty { null })
        val header = "SHEET $name (${facts.joinToString("; ")})"
        val sheet = try {
            sheets.sheet(player.uuid, now) ?: return listOf("$header: (no sheet: this player is not in the Journal yet)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = reason(e)
            log.warn("join: the sheet of ${player.name} is unavailable: $reason")
            return listOf("$header: (sheet unavailable: ${ModelText.escape(reason)})")
        }
        chapter.sheetsShownAt[player.uuid] = now
        if (!full) return listOf("$header: ${renderer.render(sheet, SheetDetail.LINE, now)}")
        return fullBrief(header, sheet, last, envelope.id, chapter, now)
    }

    /** The full join of [sheet]'s player, within [MAX_CHARS]; what it shows goes into [chapter], and waits there to be recorded. */
    private suspend fun fullBrief(header: String, sheet: PlayerSheet, last: Session?, eventId: Long, chapter: ChapterMemory, now: Long): List<String> {
        val at = Instant.ofEpochMilli(now)
        val condensed = renderer.rendered(sheet, SheetDetail.CONDENSED, now)
        val hints = hints(sheet.player.uuid)
        val fixed = buildList {
            add("$header:")
            condensed.text.lines().forEach { add(INDENT + it) }
            add(INDENT + lastSessionLine(sheet, last, at))
        }
        val hintLines = hints.map { "${INDENT}Dream hint (once, ${it.ref}): ${ModelText.escape(it.text)}" }
        val room = MAX_CHARS - BriefText.length(fixed + hintLines) - 1 - INDENT.length
        val (awayLine, awayRefs) = whileAway(sheet, last, at, room)
        val lines = BriefText.within(fixed + (INDENT + awayLine) + hintLines, MAX_CHARS)

        val shown = (condensed.refs + awayRefs + hints.map { it.ref }).distinct()
        chapter.shownRefs += shown
        chapter.unrecorded += Shown(shown, RecallChannel.JOIN, RecallContext(chapter.conversationId, null, eventId), hints.map { it.ref })
        return lines
    }

    // ── Sources ─────────────────────────────────────────────────────────────────────────────────────

    /** The player's last closed session before [current]; null when there is none, or when it cannot be read (`WARN`). */
    private suspend fun lastSession(player: UUID, current: Long): Session? = try {
        journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(
                "SELECT joined_at, left_at FROM sessions WHERE player_uuid = ? AND left_at IS NOT NULL AND session_id <> ? " +
                    "ORDER BY left_at DESC LIMIT 1",
                player, current,
            ) { Session(instant(1), instant(2)) }.firstOrNull()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("join: the last session of $player is unavailable: ${reason(e)}")
        null
    }

    /** The dream hints for [player] not shown yet, the newest [MAX_HINTS]: one shown by a request that was not accepted is not marked, and comes again. */
    private suspend fun hints(player: UUID): List<Note> = try {
        store.openNotes().filter { note ->
            note.kind == NoteKind.DREAM_HINT && note.players.any { it.uuid == player } && note.status == NoteStatus.OPEN
        }.takeLast(MAX_HINTS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("join: the dream hints of $player are unavailable: ${reason(e)}")
        emptyList()
    }

    /** `Last session: …`, the `since_last` text; says so when it is not written yet, or written before the last session. */
    private fun lastSessionLine(sheet: PlayerSheet, last: Session?, now: Instant): String {
        val doc = sheet.docs[SheetSection.SINCE_LAST] ?: return "Last session: $NOT_WRITTEN"
        val text = BriefText.cut(doc.content, SheetSection.SINCE_LAST.maxChars)
        if (last == null || !doc.writtenAt.isBefore(last.leftAt)) return "Last session: $text"
        return "Last session: (not written yet for ${dates.period(last.joinedAt, last.leftAt, now)}: written when the chapter this player " +
            "left in closes) · before that (written ${dates.day(doc.writtenAt, now)}): $text"
    }

    /**
     * `While away: 2 sessions (…) · 1 chapter not yet in a session (…) · open about this player: …`, within
     * [room] characters (the last items of the longest part go first, counted as `+N more`), and the refs it shows.
     */
    private suspend fun whileAway(sheet: PlayerSheet, last: Session?, now: Instant, room: Int): Pair<String, List<Ref>> {
        val parts = buildList {
            if (last != null) addAll(chronicled(last.leftAt, now))
            val open = sheet.openCanon.filter(::isCommitment).map { it.ref to commitment(it, now) }
            if (open.isNotEmpty()) add(Part(open, { items, left -> "open about this player: " + (items + moreItems(left)).joinToString(" · ") }))
        }
        if (parts.isEmpty()) {
            return (if (last != null) "While away: nothing chronicled since this player left" else "While away: $UNKNOWN_ABSENCE") to emptyList()
        }
        fun text() = "While away: " + parts.joinToString(" · ") { part -> part.error ?: part.wrap(part.items.take(part.shown).map { it.second }, part.items.size - part.shown) }
        while (text().length > room) {
            val longest = parts.filter { it.shown > 0 }.maxByOrNull { part -> part.items.take(part.shown).sumOf { it.second.length } } ?: break
            longest.shown--
        }
        return SheetRenderer.cutText(text(), room) to parts.flatMap { it.items.take(it.shown).map { item -> item.first } }
    }

    /** The sessions of the chronicle written since [leftAt], and the chapters not in a session yet. */
    private suspend fun chronicled(leftAt: Instant, now: Instant): List<Part> = try {
        val sessions = store.chronicle(ChronicleLevel.SESSION, MAX_ROWS)
        val newest = sessions.firstOrNull()
        val away = sessions.filter { it.to.isAfter(leftAt.plus(OVERLAP)) }.reversed().takeLast(MAX_LISTED)
        val awayCount = sessions.count { it.to.isAfter(leftAt.plus(OVERLAP)) }
        val chapters = store.chronicle(ChronicleLevel.CHAPTER, MAX_ROWS)
            .filter { !it.from.isBefore(leftAt) && (newest == null || !it.from.isBefore(newest.to)) }
        buildList {
            if (awayCount > 0) {
                add(
                    Part(away.map { it.ref to entry(it, now) }, { items, left ->
                        "${count(awayCount, "session")} (${(items + moreItems(left + awayCount - away.size)).joinToString(", ")})"
                    }),
                )
            }
            if (chapters.isNotEmpty()) {
                val listed = chapters.reversed().takeLast(MAX_LISTED)
                add(
                    Part(listed.map { it.ref to entry(it, now) }, { items, left ->
                        "${count(chapters.size, "chapter")} not yet in a session (${(items + moreItems(left + chapters.size - listed.size)).joinToString(", ")})"
                    }),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = reason(e)
        log.warn("join: the chronicle is unavailable: $reason")
        listOf(Part(emptyList(), { _, _ -> "" }, "sessions (unavailable: ${ModelText.escape(reason)})"))
    }

    /** `H-89 10-02 « La nuit des slimes »`. */
    private fun entry(entry: ChronicleEntry, now: Instant): String = "${entry.ref} ${dates.day(entry.from, now)} ${BriefText.quote(entry.title, TITLE_MAX)}"

    /**
     * An open commitment about the player: a promise, a threat, a prophecy, a challenge or an arc, and a plan that
     * targets them (M4 §3.3), which has no due time of its own: among the commitments, as in the briefing.
     */
    private fun isCommitment(entry: CanonEntry): Boolean = entry.status.open && (entry.kind.dueAllowed || entry.kind == CanonKind.PLAN)

    /** `C-45 promise, due in 1h « je te rends… »`, or `C-61 threat: Le Narrateur a menacé…`. */
    private fun commitment(entry: CanonEntry, now: Instant): String = buildString {
        append("${entry.ref} ${entry.kind.id}")
        entry.dueAt?.let { append(", ").append(BriefText.due(it, now, dates)) }
        append(entry.quote?.let { " ${BriefText.quote(it, QUOTE_MAX)}" } ?: ": ${BriefText.cut(entry.statement, QUOTE_MAX)}")
    }

    private fun reason(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        /** The joins among [signals]: the last join of each player still online at the end of [signals], in order of arrival. */
        fun joins(signals: List<DirectorSignal>): List<Envelope> {
            val events = signals.filterIsInstance<DirectorSignal.Event>().map { it.envelope }
            val last = events.filter { it.event is PlayerJoined }.sortedBy { it.id }.associateBy { (it.event as PlayerJoined).player.uuid }
            return last.values.filter { join ->
                val uuid = (join.event as PlayerJoined).player.uuid
                events.none { it.id > join.id && (it.event as? PlayerLeft)?.player?.uuid == uuid }
            }.sortedBy { it.id }
        }

        /**
         * The players among [signals] whose join would show their sheet in [chapter] at [now]: not their first
         * time on the server, their sheet not shown since they joined, nor less than [RESHOW_MS] ago.
         */
        fun sheetsDue(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): List<PlayerRef> = joins(signals).mapNotNull { envelope ->
            val join = envelope.event as PlayerJoined
            val shownAt = chapter.sheetsShownAt[join.player.uuid]
            join.player.takeIf { !join.firstTime && (shownAt == null || (shownAt < envelope.ts && now - shownAt >= RESHOW_MS)) }
        }

        /** Characters of one player's join, at most (M3 §11.2). */
        const val MAX_CHARS = 3_500

        /** A sheet shown in the chapter less than this ago (epoch milliseconds) is not shown again at a reconnection. */
        const val RESHOW_MS = 30 * 60_000L

        /** Players joining at once who get their full join; the others get one line. */
        const val MAX_FULL = 3

        /** Dream hints shown at one join, the newest. */
        const val MAX_HINTS = 2

        /** Sessions and chapters named in "While away", the newest. */
        const val MAX_LISTED = 4

        /** A session of the chronicle counts as "while away" when it went on this long after the player left. */
        val OVERLAP: Duration = Duration.ofMinutes(10)

        private const val INDENT = "  "
        private const val MAX_ROWS = 200
        private const val READ_TIMEOUT_MS = 2_000L
        private const val TITLE_MAX = 60
        private const val QUOTE_MAX = 80
        private const val NOT_WRITTEN = "(not written yet: written when a chapter closes after this player left)"
        private const val UNKNOWN_ABSENCE = "(unknown: no closed session of this player in the Journal)"

        private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

        private fun moreItems(n: Int): List<String> = if (n > 0) listOf("+$n more") else emptyList()
    }
}
