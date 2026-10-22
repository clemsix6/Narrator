package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.memory.enumOfId
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stats.SheetStats
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.memory.strings
import java.sql.Connection
import java.time.Instant

/**
 * The watch of the Narrator's style (M6 §3.4): the Director's latest [WINDOW] lines and the line review's latest [LABELS]
 * labels, in memory, fed by the bus ([said], [named], through `StyleModule`) and by the review ([labeled]); seeded from
 * the Journal at the start ([seed]).
 *
 * At each line, label or new name it recomputes, under its lock, what it answers from (a [View], published at once, never
 * changed afterwards): the readers ([restingOpener] for `say`, [situationLine], [atRest], the review's
 * [crutchCandidates] and [recentLines]) read the latest view without waiting. Any thread; never reads the database.
 *
 * - **Openers at rest**: the normalized openers ([Tics.openers]) of [Tics.OPENER_MIN] lines or more in the window, counted on
 *   the lines said with `say` of [Tics.OPENER_MIN_CHARS] characters or more. The window makes the rest last: a refused
 *   opener is no longer served, its lines leave the window one by one, and it is free again.
 * - **Phrases at rest**: the phrases of [Tics.PHRASE_MIN] lines or more ([Tics.phrases]).
 * - **Crutch words**: a word the review marked in [CRUTCH_MIN] of the latest [CRUTCH_LINES] labeled lines; it stays at rest
 *   while it is in [CRUTCH_STAY].
 * - **Registers**: those of the latest labeled lines, and the least used of the latest [LABELS].
 *
 * @param tone told of each label, with the latest ones, and of each new line the review reads, for its length (null: no
 *   note of tone).
 */
class StyleWatchImpl(private val tone: ToneWatch? = null) : StyleWatch {
    private val lock = Any()
    private val lines = ArrayDeque<Held>() // guarded by lock: the latest lines, oldest first
    private val labels = ArrayList<LineLabels>() // guarded by lock: the latest labels, by line id
    private val nameSet = HashSet<String>() // guarded by lock
    private var names = Tics.Names.NONE // guarded by lock
    private var lastId = Long.MIN_VALUE // guarded by lock: the latest line held
    private var crutchesAtRest = emptySet<String>() // guarded by lock

    @Volatile
    private var view = View.EMPTY

    /** A line of the Director held: its `chat` row and what [Tics] read in it. */
    private class Held(val id: Long, val said: Tics.Said)

    /** What the watch answers from, rebuilt at each change and never changed once published. */
    private class View(
        val names: Tics.Names,
        val lines: List<Held>,
        /** The latest labels, the latest line first. */
        val labels: List<LineLabels>,
        val openers: List<RestingOpener>,
        val openerKeys: Map<String, RestingOpener>,
        val phrases: List<Tics.Construction>,
        /** The crutch words at rest and their marks in the latest [CRUTCH_LINES] labeled lines, the most marked first. */
        val crutches: List<Pair<String, Int>>,
        val candidates: List<String>,
    ) {
        companion object {
            val EMPTY = View(Tics.Names.NONE, emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList())
        }
    }

    /** A line read from the Journal at the start. */
    data class SeedLine(val id: Long, val ts: Instant, val text: String)

    /** What the Journal held at the start: the Director's latest lines (oldest first), the names, the latest labels. */
    data class Seed(val lines: List<SeedLine>, val names: Set<String>, val labels: List<LineLabels>)

    // ── Feeding ─────────────────────────────────────────────────────────────────────────────────────

    /** What the Journal held at the start; a line already held is not taken twice. */
    fun seed(seed: Seed) {
        synchronized(lock) {
            addNames(seed.names)
            seed.lines.forEach { add(it.id, it.ts, it.text) }
            seed.labels.forEach(::addLabel)
            publish()
        }
    }

    /**
     * A line of the Director (its `chat` row [id], said at [ts]); one already held is ignored. A line the review reads
     * ([Tics.opens]: said with `say`, never a title) has its length measured by the tone's monitor ([ToneWatch.said]).
     */
    fun said(id: Long, ts: Instant, text: String) {
        val added = synchronized(lock) {
            add(id, ts, text).also { if (it) publish() }
        }
        if (added && Tics.opens(text)) tone?.said(id, text)
    }

    /** Names a line may hold (players, nicknames): the lines held are read again when one is new. */
    fun named(more: Collection<String>) {
        synchronized(lock) {
            if (addNames(more)) publish()
        }
    }

    override fun labeled(labels: LineLabels) {
        val latest = synchronized(lock) {
            addLabel(labels)
            publish()
            view.labels
        }
        tone?.labeled(labels, latest)
    }

    private fun add(id: Long, ts: Instant, text: String): Boolean {
        if (id <= lastId) return false
        lastId = id
        lines.addLast(Held(id, Tics.said(text, ts, names)))
        while (lines.size > WINDOW) lines.removeFirst()
        return true
    }

    private fun addNames(more: Collection<String>): Boolean {
        if (!nameSet.addAll(more.filter { it.isNotBlank() })) return false
        names = Tics.Names.of(nameSet)
        val again = lines.map { Held(it.id, Tics.said(it.said.text, it.said.ts, names)) }
        lines.clear()
        lines.addAll(again)
        return true
    }

    /** Keeps [label] among the latest [LABELS] by line id; a line reviewed again keeps its latest labels. */
    private fun addLabel(label: LineLabels) {
        labels.removeAll { it.eventId == label.eventId }
        val at = labels.indexOfFirst { it.eventId > label.eventId }
        if (at < 0) labels += label else labels.add(at, label)
        while (labels.size > LABELS) labels.removeAt(0)
    }

    /** Recomputes and publishes the view. Under the lock. */
    private fun publish() {
        val held = lines.toList()
        val said = held.map { it.said }
        val latest = labels.asReversed().toList()
        val openers = Tics.openers(said).filter { it.count >= Tics.OPENER_MIN }.map { opener ->
            opener.key to RestingOpener(opener.text, opener.count, opener.forms.drop(1))
        }
        view = View(
            names = names,
            lines = held,
            labels = latest,
            openers = openers.map { it.second },
            openerKeys = openers.toMap(),
            phrases = Tics.phrases(said),
            crutches = crutches(latest),
            candidates = candidates(said),
        )
    }

    /** The crutch words at rest: marked in [CRUTCH_MIN] of the latest [CRUTCH_LINES] labeled lines, or still in [CRUTCH_STAY]. */
    private fun crutches(latest: List<LineLabels>): List<Pair<String, Int>> {
        val marks = latest.take(CRUTCH_LINES).flatMap { label -> label.crutches.map { it.lowercase() }.distinct() }.groupingBy { it }.eachCount()
        val resting = marks.filter { (word, count) -> count >= CRUTCH_MIN || (count >= CRUTCH_STAY && word in crutchesAtRest) }
        crutchesAtRest = resting.keys
        return resting.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    /**
     * The [CANDIDATES] content words of [CANDIDATE_MIN_LETTERS] letters or more in the most of the latest [CANDIDATE_LINES]
     * lines, but the Narrator's own name ([NOT_CANDIDATES]).
     */
    private fun candidates(said: List<Tics.Said>): List<String> {
        val counts = HashMap<String, Int>()
        val last = HashMap<String, Int>()
        said.takeLast(CANDIDATE_LINES).forEachIndexed { i, line ->
            for (word in Tics.words(line)) {
                if (word.count { it.isLetter() } < CANDIDATE_MIN_LETTERS || word in NOT_CANDIDATES) continue
                counts.merge(word, 1, Int::plus)
                last[word] = i
            }
        }
        return counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenByDescending { last.getValue(it) }.thenBy { it })
            .take(CANDIDATES)
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    override fun crutchCandidates(): List<String> = view.candidates

    /** Said with `say` only ([Tics.bySay]): a title, a line above the hotbar or a book is no context for the review. */
    override fun recentLines(beforeLineId: Long, count: Int): List<String> =
        view.lines.filter { it.id < beforeLineId && Tics.bySay(it.said.text) }.takeLast(count).map { it.said.text }

    override fun restingOpener(text: String): RestingOpener? {
        val view = view
        if (view.openerKeys.isEmpty()) return null
        val opener = Tics.said(text, Instant.EPOCH, view.names).opener ?: return null
        return view.openerKeys[opener.key]
    }

    override fun atRest(): AtRest {
        val view = view
        return AtRest(view.openers, view.phrases.map { it.text }, view.crutches.map { it.first })
    }

    override fun situationLine(now: Long): String? {
        val view = view
        val crutchLines = minOf(CRUTCH_LINES, view.labels.size)
        val sections = listOfNotNull(
            Section("openers at rest", view.openers.map { "${ModelText.guillemets(it.opener)} ×${it.count}" }),
            Section("phrases", view.phrases.map { "${ModelText.guillemets(it.text)} ×${it.count}" }),
            Section("crutch words", view.crutches.map { (word, marks) -> "${ModelText.guillemets(word)} ×$marks" }, " (of your last $crutchLines lines)"),
            registers(view.labels)?.let { Section("your last ${minOf(REGISTER_LINES, view.labels.size)} lines:", listOf(it)) },
        ).filter { it.items.isNotEmpty() }
        return fit(sections)
    }

    override fun leastUsedRegisters(count: Int): List<ComicRegister> {
        val used = view.labels.groupingBy { it.register }.eachCount()
        return ComicRegister.VOICE.sortedBy { used[it] ?: 0 }.take(count)
    }

    override fun lastRegisters(count: Int): List<ComicRegister> = view.labels.take(count).map { it.register }

    /**
     * The measures of a period ([StyleReport.measures]) on the latest [DIVERSITY_LINES] lines held, each with its labels
     * when the review gave them: one definition for the window and for any period.
     */
    override fun diversity(): Diversity {
        val view = view
        if (view.lines.isEmpty()) return Diversity.NONE
        val labels = view.labels.associateBy { it.eventId }
        val lines = view.lines.takeLast(DIVERSITY_LINES).map { held -> reportLine(held, labels[held.id]) }
        val measures = StyleReport.measures(lines, view.names)
        return Diversity(
            measures.lines, measures.openersDistinct, measures.topRegister, measures.topRegisterShare, measures.registerEntropy,
            measures.crutches, measures.tics, measures.plainShare.takeIf { measures.reviewed > 0 },
        )
    }

    /** [held] as the report reads a line of the Journal, with its [labels] (none: not reviewed). */
    private fun reportLine(held: Held, labels: LineLabels?): StyleReport.Line {
        val flaws = labels?.let {
            mapOf(
                StyleReport.Flaw.DARK to it.dark, StyleReport.Flaw.CRUEL to it.cruel, StyleReport.Flaw.OUT_OF_CHARACTER to it.outOfCharacter,
                StyleReport.Flaw.OBSCURE to it.obscure, StyleReport.Flaw.FLAT to it.flat, StyleReport.Flaw.REPEATS to it.repeats,
            )
        }
        return StyleReport.Line(held.id, held.said.ts, held.said.text, labels?.register, flaws.orEmpty(), labels?.crutches.orEmpty())
    }

    /**
     * The registers of the latest [REGISTER_LINES] labeled lines when one of the voice holds [REGISTER_RUN] of them:
     * `divine_bureaucracy 5, sports_commentary 2, courtroom 1; least used lately: fairy_tale, food_critic, weather_report`;
     * null otherwise.
     */
    private fun registers(latest: List<LineLabels>): String? {
        val counts = latest.take(REGISTER_LINES).groupingBy { it.register }.eachCount()
        if (counts.none { (register, count) -> register in ComicRegister.VOICE && count >= REGISTER_RUN }) return null
        val held = counts.entries.sortedWith(compareByDescending<Map.Entry<ComicRegister, Int>> { it.value }.thenBy { it.key.ordinal })
        return held.joinToString(", ") { "${it.key.id} ${it.value}" } + "; least used lately: " +
            leastUsedRegisters(LEAST_USED).joinToString(", ") { it.id }
    }

    /** A part of the `Style:` line: its [label], its [items] and what follows them. */
    private class Section(val label: String, val items: List<String>, val suffix: String = "")

    /** `Style: ` and the [sections] within [SITUATION_MAX] characters, the last items left out and counted; null when empty. */
    private fun fit(sections: List<Section>): String? {
        val total = sections.sumOf { it.items.size }
        if (total == 0) return null
        for (kept in total downTo 1) {
            var left = kept
            val parts = sections.mapNotNull { section ->
                val items = section.items.take(left).also { left -= it.size }
                if (items.isEmpty()) null else "${section.label} ${items.joinToString(", ")}${section.suffix}"
            }
            val more = if (kept < total) " (+${total - kept} more)" else ""
            val line = "Style: ${parts.joinToString(" · ")}$more"
            if (line.length <= SITUATION_MAX) return line
        }
        return ModelText.cut("Style: ${sections.first().label} ${sections.first().items.first()}", SITUATION_MAX)
    }

    companion object {
        /** The Director's latest lines held. */
        const val WINDOW = Tics.LINES

        /** The latest labels held. */
        const val LABELS = 50

        const val CRUTCH_LINES = 30
        const val CRUTCH_MIN = 3
        const val CRUTCH_STAY = 2

        /** The latest labeled lines whose registers the `Style:` line shows, when one register holds [REGISTER_RUN]. */
        const val REGISTER_LINES = 8
        const val REGISTER_RUN = 4
        const val LEAST_USED = 3

        const val CANDIDATES = 8
        const val CANDIDATE_LINES = 50
        const val CANDIDATE_MIN_LETTERS = 5

        /**
         * The Narrator's own name, never a crutch candidate (M6 review 1, D4): it speaks of itself in the third person, and
         * the review took it for a crutch (M6-02, T1). It is no player's name either: « Le Narrateur… » stays an opener.
         * Search data, in French like [Tics]'s stop words.
         */
        private val NOT_CANDIDATES = setOf("narrateur", "narrateurs")

        /** The latest lines the diversity is measured on. */
        const val DIVERSITY_LINES = 50

        /** Longest `Style:` line. */
        const val SITUATION_MAX = 400

        /**
         * What the Journal holds for the watch, on a read connection: the Director's latest [WINDOW] lines (`chat` channel
         * `narrator` with a turn, no operator), the names ([Tics.names]), and the latest review of the latest [LABELS]
         * lines reviewed (`obscure` 0 in the rows older than that flaw, M7-CLARITY).
         */
        fun read(connection: Connection): Seed {
            val lines = connection.query(
                "SELECT id, epoch_ms(ts), text FROM chat WHERE channel = 'narrator' AND turn_id IS NOT NULL AND operator IS NULL ORDER BY id DESC LIMIT $WINDOW",
                timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
            ) { SeedLine(getLong(1), Instant.ofEpochMilli(getLong(2)), getString(3)) }.reversed()
            val labels = connection.query(
                "SELECT l.event_id, epoch_ms(l.ts), c.text, l.source, l.register, l.dark, l.cruel, l.flat, l.out_of_character, coalesce(l.obscure, 0), " +
                    "l.repeats, l.crutches, l.latency_ms FROM (SELECT DISTINCT ON (event_id) * FROM line_labels ORDER BY event_id, ts DESC) l JOIN chat c ON c.id = l.event_id " +
                    "ORDER BY l.event_id DESC LIMIT $LABELS",
                timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
            ) {
                enumOfId<ComicRegister>(getString(5))?.let { register ->
                    LineLabels(
                        getLong(1), Instant.ofEpochMilli(getLong(2)), getString(3), getString(4), register, getDouble(6), getDouble(7), getDouble(8),
                        getDouble(9), getDouble(10), getDouble(11), strings(12), null, getInt(13),
                    )
                }
            }.filterNotNull()
            return Seed(lines, Tics.names(connection), labels)
        }
    }
}
