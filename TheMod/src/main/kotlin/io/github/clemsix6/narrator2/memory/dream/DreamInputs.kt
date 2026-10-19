package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.CHRONICLE_COLUMNS
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.Decay
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.INTERACTION_COLUMNS
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.MEMORY_COLUMNS
import io.github.clemsix6.narrator2.memory.MemoryDoc
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MergeCandidate
import io.github.clemsix6.narrator2.memory.Names
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.Relation
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.SheetStat
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.Trait
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.chronicle.ExchangeLog
import io.github.clemsix6.narrator2.memory.chronicleEntry
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.instantOrNull
import io.github.clemsix6.narrator2.memory.interaction
import io.github.clemsix6.narrator2.memory.memoryEntry
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.relations
import io.github.clemsix6.narrator2.memory.stats.PairStats
import io.github.clemsix6.narrator2.memory.stats.Reactions
import io.github.clemsix6.narrator2.memory.stats.SheetStats
import io.github.clemsix6.narrator2.memory.stats.Signals
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.memory.uuid
import io.github.clemsix6.narrator2.memory.uuids
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What each step of a dream reads (M3 §8.2): its first message, built by the code from the Journal and the
 * memory, every block within its budget (the constants of the companion, checked at their maximum by a
 * test). A message starts with `<job kind="dream" run="…" step="N" of="5" task="…"/>`, then its blocks,
 * each `<tag …>` … `</tag>`; a block with nothing to show says why. Everything a player or a model wrote is
 * escaped ([ModelText]): the memory reaches the model as recorded facts, never as instructions. Reads only.
 *
 * - **Step 1, one conversation per player** ([candidates], [player]): the full sheet, the traits it hides,
 *   what is new about the player since their last reflection (memories, interactions, stats before and
 *   after), the reflexes' signals, the pairs they are in, their latest public messages.
 * - **Step 2** ([review]): the Narrator's tics, its latest lines with the players' reactions, the running
 *   gags and nicknames with their uses, the previous review.
 * - **Step 3** ([chronicle]): the chapter entries no session covers yet, grouped by server session; the
 *   season in progress; the closed seasons; the saga.
 * - **Step 4** ([grimoire]): the Grimoire, one line per player, the review, the latest session, the open
 *   canon, the arcs, the open notes.
 * - **Step 5** ([merges]): the merge candidates of the hygiene, with both texts.
 */
internal class DreamInputs(private val services: NarratorServices) {
    private val memory get() = services.memory
    private val journal get() = services.journal

    /** The zone of the times shown: the Journal's (`journal.timeZone`), else the engine's clock. */
    private val zone: ZoneId
        get() = services.settings.get().config.journal.timeZone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: services.clock.zone

    // ── Step 1: the players ─────────────────────────────────────────────────────────────────────────

    /**
     * A player the Dreamer may reflect on, and what they did since their [last] reflection: [playedSeconds]
     * of play, [memories] about them (not the Dreamer's own, see [candidates]) and [interactions] of theirs
     * written since.
     */
    data class Candidate(val player: PlayerRef, val last: Reflection?, val playedSeconds: Long, val memories: Int, val interactions: Int)

    /**
     * A reflection on a player (`memory_state` `reflected:<uuid>`): when its input was read, and the values
     * of the player's stats then, for the next reflection's « before → after ».
     */
    @Serializable
    data class Reflection(val at: Long, val stats: Map<String, Double>) {
        fun encode(): String = JSON.encodeToString(serializer(), this)

        companion object {
            /** The reflection stored as [text]; null when there is none or it cannot be read. */
            fun decode(text: String?): Reflection? = text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
        }
    }

    /** The first message of a player's conversation ([text]) and the [reflection] to store once it is done. */
    class PlayerInput(val text: String, val reflection: Reflection)

    /**
     * The players with something new since their last reflection (play, memories about them, interactions of
     * theirs), the most active first (play time, then new rows), [max] at most.
     *
     * The memories the Dreamer wrote itself do not count, here nor in [player]: they are its conclusions
     * from a reflection (an observation drawn from the chat), written after the reflection's time, and
     * nothing the player did. Counted, they made the player a candidate at every dream, without playing.
     */
    suspend fun candidates(now: Instant, max: Int): List<Candidate> = journal.read(READ_TIMEOUT_MS) { connection ->
        val players = connection.query("SELECT player_uuid, name FROM players") { PlayerRef(uuid(1), getString(2)) }
        val last = connection.query("SELECT key, value FROM memory_state WHERE starts_with(key, ?)", REFLECTED_PREFIX) {
            getString(1).removePrefix(REFLECTED_PREFIX) to Reflection.decode(getString(2))
        }.mapNotNull { (key, reflection) -> runCatching { UUID.fromString(key) }.getOrNull()?.let { it to reflection } }.toMap()
        val oldest = players.minOfOrNull { last[it.uuid]?.at ?: 0L }?.let(Instant::ofEpochMilli) ?: return@read emptyList()
        val sessions = connection.query(
            "SELECT player_uuid, session_id, joined_at, left_at FROM sessions WHERE left_at IS NULL OR left_at > ?", oldest,
        ) { played() }
        val memories = connection.query(
            "SELECT players, created_at FROM memories WHERE NOT archived AND created_at > ? AND source <> ?", oldest, Source.DREAMER.id,
        ) { uuids(1) to instant(2) }
        val interactions = connection.query(
            "SELECT i.player_uuid, l.ts FROM memory_log l JOIN interactions i ON i.id = TRY_CAST(l.row_key AS BIGINT) " +
                "WHERE l.table_name = 'interactions' AND l.op = 'insert' AND l.ts > ? AND NOT i.archived",
            oldest,
        ) { uuid(1) to instant(2) }
        players.map { player ->
            val since = Instant.ofEpochMilli(last[player.uuid]?.at ?: 0L)
            Candidate(
                player = player,
                last = last[player.uuid],
                playedSeconds = sessions.filter { it.player == player.uuid }.sumOf { it.secondsWithin(since, now) },
                memories = memories.count { (about, at) -> player.uuid in about && at > since },
                interactions = interactions.count { (of, at) -> of == player.uuid && at > since },
            )
        }.filter { it.playedSeconds > 0 || it.memories > 0 || it.interactions > 0 }
            .sortedWith(compareByDescending<Candidate> { it.playedSeconds }.thenByDescending { it.memories + it.interactions }.thenBy { it.player.name })
            .take(max)
    }

    /** The first message of the conversation of step 1 about [candidate], and the reflection to store when it is done. */
    suspend fun player(runId: Long, candidate: Candidate, now: Instant): PlayerInput {
        val uuid = candidate.player.uuid
        val nowMs = now.toEpochMilli()
        val sheets = memory.sheets
        val sheet = sheets.sheet(uuid, nowMs)
        val traits = memory.store.traits(uuid)
        val played = memory.clock.playerPlaySeconds(uuid, nowMs)
        val since = Instant.ofEpochMilli(candidate.last?.at ?: 0L)
        val read = journal.read(READ_TIMEOUT_MS) { connection ->
            val names = Names.of(connection)
            PlayerRead(
                memories = connection.query(
                    "SELECT $MEMORY_COLUMNS FROM memories WHERE NOT archived AND list_contains(players, ?) AND created_at > ? AND source <> ? " +
                        "ORDER BY importance DESC, occurred_from DESC, id DESC LIMIT ?",
                    uuid, since, Source.DREAMER.id, MEMORIES_COUNT,
                ) { memoryEntry(names) },
                interactions = connection.query(
                    "SELECT $INTERACTION_COLUMNS FROM interactions WHERE player_uuid = ? AND NOT archived AND id IN (SELECT TRY_CAST(row_key AS BIGINT) " +
                        "FROM memory_log WHERE table_name = 'interactions' AND op = 'insert' AND ts > ?) ORDER BY ts DESC, id DESC LIMIT ?",
                    uuid, since, INTERACTIONS_COUNT,
                ) { interaction() },
                stats = SheetStats.read(connection, uuid),
                pairs = PairStats.read(connection, uuid),
                relations = connection.relations("player_uuid = ? OR other_uuid = ?", uuid, uuid),
                chat = connection.query(
                    "SELECT id, ts, channel, text FROM chat WHERE player_uuid = ? AND channel IN ('public', 'pray') ORDER BY ts DESC, id DESC LIMIT ?",
                    uuid, CHAT_COUNT,
                ) { Said(getLong(1), instant(2), getString(3), getString(4)) },
                names = names,
            )
        }
        val name = candidate.player.shownName
        val lastAt = candidate.last?.let { dayTime(Instant.ofEpochMilli(it.at)) }
        val parts = listOf(
            job(runId, 1, "players", "player=\"${ModelText.attribute(candidate.player.name)}\" last_reflection=\"${lastAt ?: "never"}\""),
            sheet?.let { textBlock("sheet", "", emptyList(), sheets.render(it, SheetDetail.FULL, nowMs), SHEET_MAX) }
                ?: block("sheet", "", emptyList(), SHEET_MAX, "(no sheet: the memory has never seen $name)"),
            hiddenTraits(traits, sheet?.traits.orEmpty(), played),
            newMemories(read.memories, candidate, lastAt),
            newInteractions(read.interactions, candidate, lastAt),
            statsChange(read.stats, candidate.last, lastAt),
            signals(read.stats[SIGNALS]),
            pairs(uuid, read),
            chat(read.chat),
        )
        val values = read.stats.mapNotNull { (stat, value) -> value.value?.let { stat to it } }.toMap()
        return PlayerInput(parts.joinToString("\n"), Reflection(nowMs, values))
    }

    /** The traits set on the player that the sheet does not show: faded below 0.2, or left without standing evidence. */
    private fun hiddenTraits(traits: List<Trait>, onSheet: List<Trait>, played: Long): String {
        val shown = onSheet.filter { Decay.traitVisible(Decay.traitConfidence(it, played)) }.map { it.trait }.toSet()
        val standing = onSheet.map { it.trait }.toSet()
        val lines = traits.filter { it.trait !in shown }.map { trait ->
            val effective = Decay.traitConfidence(trait, played)
            val why = if (trait.trait in standing) "faded below ${Decay.TRAIT_VISIBLE}" else "no standing evidence: its proofs were archived"
            "- ${traitName(trait)} ${trait.intensity}/5: confidence ${decimal(trait.confidence)} set ${day(trait.setAt)}, ${decimal(effective)} now; " +
                "proofs ${trait.evidence.joinToString(" ").ifEmpty { "none" }} — $why"
        }
        return block("hidden_traits", "", lines, HIDDEN_TRAITS_MAX, "(none: the sheet shows every trait set)")
    }

    private fun newMemories(rows: List<MemoryEntry>, candidate: Candidate, lastAt: String?): String {
        val items = rows.map { row ->
            val with = row.players.filter { it.uuid != candidate.player.uuid }.takeIf { it.isNotEmpty() }?.let { " · with ${ToolText.names(it)}" }.orEmpty()
            val tags = row.tags.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString(", ") { tag -> ModelText.escape(tag) }}" }.orEmpty()
            "- ${row.ref} ${dayTime(row.occurredFrom)} ★${row.importance} valence ${signed(row.valence)}$with$tags: ${ModelText.escape(row.title)}: " to
                ModelText.escape(row.content)
        }
        val shown = if (candidate.memories > rows.size) " shown=\"the ${rows.size} most important\"" else ""
        return block("new_memories", "since=\"${lastAt ?: "ever"}\" count=\"${candidate.memories}\"$shown", shared(items, MEMORIES_MAX), MEMORIES_MAX, "(none)")
    }

    private fun newInteractions(rows: List<Interaction>, candidate: Candidate, lastAt: String?): String {
        val items = rows.map { row ->
            val quote = row.quote?.let { " · said « ${quoted(it, QUOTE_MAX)} »" }.orEmpty()
            val links = listOfNotNull(row.canon?.let { "canon $it" }, row.memory?.let { "memory $it" }).joinToString("") { " · $it" }
            "- ${row.ref} ${dayTime(row.ts)} ${row.kind.id} ${signed(row.valence)}$quote$links: " to ModelText.escape(row.summary)
        }
        val shown = if (candidate.interactions > rows.size) " shown=\"the latest ${rows.size}\"" else ""
        return block(
            "new_interactions", "since=\"${lastAt ?: "ever"}\" count=\"${candidate.interactions}\"$shown",
            shared(items, INTERACTIONS_MAX), INTERACTIONS_MAX, "(none)",
        )
    }

    /** `deaths 75 → 87 (+12)` for each stat that changed since the last reflection. */
    private fun statsChange(stats: Map<String, SheetStat>, last: Reflection?, lastAt: String?): String {
        if (last == null) return block("stats_change", "", emptyList(), STATS_MAX, "(first reflection on this player: no earlier values; the sheet has the current ones)")
        if (stats.isEmpty()) return block("stats_change", "since=\"$lastAt\"", emptyList(), STATS_MAX, "(not computed yet)")
        val after = SheetStats.STATS.mapNotNull { stat -> stats[stat]?.value?.let { stat to it } }
        val (changed, same) = after.partition { (stat, value) -> last.stats[stat] != value }
        val lines = changed.map { (stat, value) ->
            val before = last.stats[stat]
            if (before == null) "- $stat ${statValue(stat, value)} (new)" else "- $stat ${statValue(stat, before)} → ${statValue(stat, value)} (${delta(stat, value - before)})"
        } + listOfNotNull(same.takeIf { it.isNotEmpty() }?.let { "- unchanged: ${it.joinToString(", ") { stat -> stat.first }}" })
        return block("stats_change", "since=\"$lastAt\"", lines, STATS_MAX, "(no stat)")
    }

    /** The reflex model's signals (M3 §6.3): hints in figures, never traits. */
    private fun signals(stat: SheetStat?): String {
        val measured = stat?.value?.roundToLong() ?: 0
        val detail = stat?.detail
        if (detail == null) {
            val why = if (measured == 0L) "none: no message of this player measured by the reflexes" else "none yet: $measured messages measured by the reflexes, ${Signals.MIN_MESSAGES} needed"
            return block("signals", "", emptyList(), SIGNALS_MAX, "($why)")
        }
        val lines = Signals.NAMES.mapNotNull { name ->
            val signal = detail[name] as? JsonObject ?: return@mapNotNull null
            val recent = signal.number("ewma") ?: return@mapNotNull null
            "- $name: recent ${decimal(recent)}, mean ${signal.number("mean")?.let(::decimal) ?: "?"} (${signal.number("n")?.roundToLong() ?: 0} messages)"
        }
        return block("signals", "measured=\"$measured\"", lines, SIGNALS_MAX, "(none)")
    }

    /** Each other player the player met, fought or griefed: the facts, and the relations each way. */
    private fun pairs(player: UUID, read: PlayerRead): String {
        val lines = read.pairs.map { pair ->
            val mine = pair.a == player
            val other = read.names.ref(if (mine) pair.b else pair.a)
            val won = if (mine) pair.pvpAOnB else pair.pvpBOnA
            val lost = if (mine) pair.pvpBOnA else pair.pvpAOnB
            val done = if (mine) pair.griefsAOnB else pair.griefsBOnA
            val suffered = if (mine) pair.griefsBOnA else pair.griefsAOnB
            val toward = read.relations.firstOrNull { it.player == player && it.other.uuid == other.uuid }
            val back = read.relations.firstOrNull { it.player == other.uuid && it.other.uuid == player }
            "- ${other.shownName}: ${minutes(pair.minutesTogether)} together (${minutes(pair.minutesTogether7d)} in the last 7 days) · PvP $won won, $lost lost · " +
                "griefs $done done, $suffered suffered · toward them: ${relation(toward)} · toward this player: ${relation(back)}"
        }
        return block("pairs", "", lines, PAIRS_MAX, "(none: this player has met nobody yet)")
    }

    private fun chat(said: List<Said>): String {
        val lines = said.map { line ->
            val prayer = if (line.channel == "pray") " (prayer)" else ""
            "- [${dayTime(line.ts)}] e${line.id}$prayer ${ModelText.quote(line.text, CHAT_TEXT_MAX)}"
        }
        return block("chat", "count=\"${said.size}\" order=\"latest first\"", lines, CHAT_MAX, "(no public message nor prayer yet)")
    }

    // ── Step 2: the review of the Narrator's play ───────────────────────────────────────────────────

    /** The first message of step 2; null when there is nothing to review (no line, no gag, no review). */
    suspend fun review(runId: Long, now: Instant): String? {
        val language = services.settings.get().config.language
        val words = HeuristicWords.bundled(language) ?: HeuristicWords.bundled(DEFAULT_LANGUAGE)
        val read = journal.read(READ_TIMEOUT_MS) { connection ->
            val tics = Tics.render(Tics.find(Tics.lines(connection)), now)
            val lines = connection.query(
                "SELECT id, ts, text, recipients FROM chat WHERE channel = 'narrator' ORDER BY ts DESC, id DESC LIMIT ?", LINES_COUNT,
            ) { Said(getLong(1), instant(2), "narrator", getString(3)) }
            val reactions = if (lines.isEmpty() || words == null) emptyList() else Reactions.read(connection, lines.minOf { it.ts }, now.plusMillis(1), words)
            Triple(tics, lines, reactions)
        }
        val (tics, lines, reactions) = read
        val gags = memory.store.canon(CanonFilter(kinds = GAG_KINDS, open = true, limit = GAGS_COUNT))
        val review = memory.store.doc(DocKey.Review.key)
        if (lines.isEmpty() && gags.isEmpty() && review == null) return null
        val byLine = reactions.groupBy { it.lineId }
        val lineItems = lines.map { line ->
            val who = byLine[line.id]?.joinToString(" · ") { "${it.player.shownName} ${it.kind.id}" } ?: "no reaction"
            "- [${dayTime(line.ts)}] e${line.id} → $who: ${ModelText.quote(line.text, LINE_TEXT_MAX)}"
        }
        return listOf(
            job(runId, 2, "review"),
            block("tics", "", tics.map { "- $it" }, TICS_MAX, "(none found in the Narrator's latest ${Tics.LINES} lines)"),
            block(
                "lines", "count=\"${lines.size}\" order=\"latest first\" reactions=\"${ModelText.attribute(Reactions.summary(reactions))}\"",
                lineItems, LINES_MAX, "(the Narrator has said nothing yet)",
            ),
            block("gags", "count=\"${gags.size}\"", gags.map(::gagLine), GAGS_MAX, "(no running gag nor nickname open)"),
            document("review", review, REVIEW_MAX, "(none yet: write the first one)"),
        ).joinToString("\n")
    }

    private fun gagLine(entry: CanonEntry): String {
        val to = entry.players.takeIf { it.isNotEmpty() }?.let { " → ${ToolText.names(it)}" }.orEmpty()
        val state = listOfNotNull(
            entry.freshness?.id ?: GagState.FRESH.id.takeIf { entry.kind == CanonKind.RUNNING_GAG },
            "${entry.uses} use${if (entry.uses == 1) "" else "s"}",
            entry.lastUsedAt?.let { "last ${dayTime(it)}" },
            "since ${day(entry.createdAt)}",
        ).joinToString(", ")
        val quote = entry.quote?.let { " · said « ${quoted(it, QUOTE_MAX)} »" }.orEmpty()
        return "- ${entry.ref} ${entry.kind.id}$to ($state): ${ModelText.escape(entry.statement)}$quote"
    }

    // ── Step 3: the chronicle ───────────────────────────────────────────────────────────────────────

    /**
     * The first message of step 3; null when no chapter entry waits for its session: none is left out of a
     * session, or the server session they belong to is not over (somebody plays, or the server has been
     * empty for less than [SESSION_GAP]).
     */
    suspend fun chronicle(runId: Long, now: Instant): String? {
        val (chapters, played) = journal.read(READ_TIMEOUT_MS) { connection ->
            val chapters = connection.query(
                "SELECT $CHRONICLE_COLUMNS FROM chronicle c WHERE level = 'chapter' AND superseded_by IS NULL AND NOT EXISTS " +
                    "(SELECT 1 FROM chronicle s WHERE s.level = 'session' AND s.superseded_by IS NULL AND list_contains(s.covers, c.id)) " +
                    "ORDER BY period_from, id",
            ) { chronicleEntry() }
            val start = chapters.minOfOrNull { it.from } ?: return@read chapters to emptyList()
            chapters to connection.query(
                "SELECT player_uuid, session_id, joined_at, left_at FROM sessions WHERE left_at IS NULL OR left_at > ?", start - SESSION_GAP,
            ) { played() }
        }
        val groups = groups(chapters, islands(played, now), now)
        if (groups.isEmpty()) return null
        val shown = mutableListOf<Group>()
        for (group in groups) {
            if (shown.isNotEmpty() && shown.sumOf { it.chapters.size } + group.chapters.size > SESSION_CHAPTERS) break
            shown += group
        }
        val share = (SESSIONS_MAX - 200 * shown.sumOf { it.chapters.size + 1 }) / shown.sumOf { it.chapters.size }
        val items = shown.flatMap { group ->
            listOf("<session from=\"${dayTime(group.from)}\" to=\"${dayTime(group.to)}\" chapters=\"${group.chapters.joinToString(", ") { it.ref.toString() }}\">") +
                group.chapters.map { chapter ->
                    "${chapter.ref} chapter « ${ModelText.escape(chapter.title)} » ${dayTime(chapter.from)}–${dayTime(chapter.to)}:\n" +
                        ModelText.cut(ModelText.lines(chapter.summary), share.coerceIn(MIN_SUMMARY, ChronicleLevel.CHAPTER.maxChars))
                } + "</session>"
        } + listOfNotNull((groups.size - shown.size).takeIf { it > 0 }?.let { "($it more session${if (it > 1) "s wait" else " waits"} for the next dream)" })
        val seasons = memory.store.chronicle(ChronicleLevel.SEASON, SEASONS_READ)
        val open = seasons.firstOrNull { it.open }
        val closed = seasons.filter { !it.open }
        val saga = memory.store.chronicle(ChronicleLevel.SAGA, 1).firstOrNull()
        return listOf(
            job(runId, 3, "chronicle"),
            block("sessions", "count=\"${shown.size}\"", items, SESSIONS_MAX, ""),
            open?.let { season ->
                val days = Duration.between(season.from, season.to).toDays()
                entry(
                    "season", season,
                    "sessions=\"${season.covers.size}\" days=\"$days\" closes_at=\"$SEASON_SESSIONS sessions or $SEASON_DAYS days\"",
                    SEASON_MAX,
                )
            } ?: block("season", "", emptyList(), SEASON_MAX, "(none open: the sessions above start a new season)"),
            block(
                "closed_seasons", "count=\"${closed.size}\"",
                closed.sortedBy { it.from }.map { "- ${it.ref} « ${ModelText.escape(it.title)} » ${day(it.from)}–${day(it.to)} (${it.covers.size} sessions)" },
                CLOSED_SEASONS_MAX, "(none yet)",
            ),
            saga?.let { entry("saga", it, "", SAGA_MAX) } ?: block("saga", "", emptyList(), SAGA_MAX, "(none yet: written when the first season closes)"),
        ).joinToString("\n")
    }

    /** The server sessions: the players' sessions merged when the server was empty for less than [SESSION_GAP] between them. */
    private fun islands(played: List<Played>, now: Instant): List<Island> {
        val spans = played.map { Island(it.joined, it.end(now), it.running) }.filter { it.to > it.from || it.open }.sortedBy { it.from }
        val islands = mutableListOf<Island>()
        for (span in spans) {
            val last = islands.lastOrNull()
            if (last != null && span.from <= last.to + SESSION_GAP) {
                islands[islands.size - 1] = Island(last.from, maxOf(last.to, span.to), last.open || span.open)
            } else {
                islands += span
            }
        }
        return islands.map { if (!it.open && it.to + SESSION_GAP > now) it.copy(open = true) else it }
    }

    /** The chapters grouped by the server session they started in; a session not over keeps its chapters for later. */
    private fun groups(chapters: List<ChronicleEntry>, islands: List<Island>, now: Instant): List<Group> {
        val groups = LinkedHashMap<Island, MutableList<ChronicleEntry>>()
        for (chapter in chapters) {
            val island = islands.firstOrNull { chapter.from >= it.from - SESSION_GAP && chapter.from <= it.to + SESSION_GAP }
                ?: Island(chapter.from, chapter.to, chapter.to + SESSION_GAP > now)
            if (island.open) continue
            groups.getOrPut(island) { mutableListOf() } += chapter
        }
        return groups.map { (island, entries) -> Group(minOf(island.from, entries.minOf { it.from }), maxOf(island.to, entries.maxOf { it.to }), entries) }
            .sortedBy { it.from }
    }

    // ── Step 4: the Grimoire ────────────────────────────────────────────────────────────────────────

    /** The first message of step 4. */
    suspend fun grimoire(runId: Long, now: Instant): String {
        val nowMs = now.toEpochMilli()
        val store = memory.store
        val sheets = memory.sheets
        val players = journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query("SELECT player_uuid FROM players ORDER BY last_seen DESC, name LIMIT ?", PLAYERS_COUNT) { uuid(1) }
        }
        val lines = players.mapNotNull { player -> sheets.sheet(player, nowMs)?.let { "- " + sheets.render(it, SheetDetail.LINE, nowMs) } }
        val session = store.chronicle(ChronicleLevel.SESSION, 1).firstOrNull()
        val open = store.canon(CanonFilter(open = true, limit = CANON_READ))
        val (arcs, canon) = open.partition { it.kind == CanonKind.ARC }
        val notes = store.openNotes()
        return listOf(
            job(runId, 4, "grimoire"),
            document("grimoire", store.doc(DocKey.Grimoire.key), GRIMOIRE_MAX, "(none yet: write the first one)"),
            block("players", "count=\"${lines.size}\" order=\"latest seen first\"", lines, PLAYERS_MAX, "(no player yet)"),
            document("review", store.doc(DocKey.Review.key), REVIEW_MAX, "(none yet)"),
            session?.let { entry("session", it, "", SESSION_MAX) } ?: block("session", "", emptyList(), SESSION_MAX, "(none yet)"),
            block("canon", "count=\"${canon.size}\"", shared(canon.take(CANON_COUNT).map(::canonItem), CANON_MAX), CANON_MAX, "(no open entry)"),
            block("arcs", "count=\"${arcs.size}\"", arcs.map { canonItem(it).let { (head, body) -> head + body } }, ARCS_MAX, "(no arc open)"),
            block("notes", "count=\"${notes.size}\"", notes.map(::noteLine), NOTES_MAX, "(no open note)"),
        ).joinToString("\n")
    }

    private fun canonItem(entry: CanonEntry): Pair<String, String> {
        val to = entry.players.takeIf { it.isNotEmpty() }?.let { " → ${ToolText.names(it)}" }.orEmpty()
        val state = listOfNotNull(
            if (entry.status == CanonStatus.PLANNED) "planned" else null,
            entry.dueAt?.let { "due ${dayTime(it)}" },
            "since ${day(entry.createdAt)}",
        ).joinToString(", ")
        val quote = entry.quote?.let { " · said « ${quoted(it, QUOTE_MAX)} »" }.orEmpty()
        return "- ${entry.ref} ${entry.kind.id}$to ($state)$quote: " to ModelText.escape(entry.statement)
    }

    private fun noteLine(note: Note): String {
        val about = note.players.takeIf { it.isNotEmpty() }?.let { " → ${ToolText.names(it)}" }.orEmpty()
        val shown = if (note.shownCount > 0) ", shown ${note.shownCount}×" else ""
        return "- ${note.ref} ${note.kind.id}$about (${day(note.createdAt)}$shown): ${ModelText.escape(note.text)}"
    }

    // ── Step 5: the merges ──────────────────────────────────────────────────────────────────────────

    /** The first message of step 5; null without candidates. */
    suspend fun merges(runId: Long, candidates: List<MergeCandidate>): String? {
        if (candidates.isEmpty()) return null
        val rows = candidates.flatMap { listOf(it.a, it.b) }.distinct().mapNotNull { memory.store.memory(it) }.associateBy { it.ref }
        val items = candidates.flatMap { pair ->
            val a = rows[pair.a] ?: return@flatMap emptyList()
            val b = rows[pair.b] ?: return@flatMap emptyList()
            listOf("<pair a=\"${pair.a}\" b=\"${pair.b}\" similarity=\"${decimal(pair.similarity)}\">", memoryLine(a), memoryLine(b), "</pair>")
        }
        return listOf(job(runId, 5, "merges"), block("candidates", "count=\"${candidates.size}\"", items, CANDIDATES_MAX, "")).joinToString("\n")
    }

    private fun memoryLine(row: MemoryEntry): String {
        val players = row.players.takeIf { it.isNotEmpty() }?.let { " · ${ToolText.names(it)}" } ?: " · the whole server"
        val tags = row.tags.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString(", ") { tag -> ModelText.escape(tag) }}" }.orEmpty()
        val period = if (row.occurredTo > row.occurredFrom) "${dayTime(row.occurredFrom)}–${time(row.occurredTo)}" else dayTime(row.occurredFrom)
        return "- ${row.ref} $period ★${row.importance} valence ${signed(row.valence)}$players$tags · ${row.recalls} recalls: " +
            "${ModelText.escape(row.title)}: ${ModelText.escape(row.content)}"
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────────

    private fun job(runId: Long, step: Int, task: String, attributes: String = ""): String =
        "<job kind=\"dream\" run=\"$runId\" step=\"$step\" of=\"$STEPS\" task=\"$task\"${if (attributes.isEmpty()) "" else " $attributes"}/>"

    /** `<tag attributes>`, its [lines] (or [empty]), `</tag>`, cut at a line within [max] characters. */
    private fun block(tag: String, attributes: String, lines: List<String>, max: Int, empty: String): String {
        val open = if (attributes.isEmpty()) "<$tag>" else "<$tag $attributes>"
        if (lines.isEmpty()) return "$open$empty</$tag>"
        return ExchangeLog.fit(listOf(open) + lines + "</$tag>", max)
    }

    /** A document (the Grimoire, the review) with its version, or [empty]. */
    private fun document(tag: String, doc: MemoryDoc?, max: Int, empty: String): String =
        if (doc == null) block(tag, "", emptyList(), max, empty) else textBlock(tag, "version=\"${doc.version}\" written=\"${dayTime(doc.writtenAt)}\"", emptyList(), doc.content, max)

    /** A chronicle entry: its ref, period and covers, its title, then its text. */
    private fun entry(tag: String, entry: ChronicleEntry, attributes: String, max: Int): String {
        val covers = entry.covers.joinToString(", ") { it.toString() }
        val head = "ref=\"${entry.ref}\" from=\"${dayTime(entry.from)}\" to=\"${dayTime(entry.to)}\" covers=\"$covers\"" + if (attributes.isEmpty()) "" else " $attributes"
        return textBlock(tag, head, listOf("« ${ModelText.escape(entry.title)} »"), entry.summary, max)
    }

    /** `<tag attributes>`, the [head] lines, then [text] (escaped, its lines kept) cut to what is left of [max], `</tag>`. */
    private fun textBlock(tag: String, attributes: String, head: List<String>, text: String, max: Int): String {
        val open = if (attributes.isEmpty()) "<$tag>" else "<$tag $attributes>"
        val fixed = (listOf(open) + head + "</$tag>").sumOf { it.length + 1 }
        return (listOf(open) + head + ModelText.cut(ModelText.lines(text), (max - fixed).coerceAtLeast(1)) + "</$tag>").joinToString("\n")
    }

    /** Items `head + body` sharing [room] characters: each body is cut to its share, never under [MIN_BODY]. */
    private fun shared(items: List<Pair<String, String>>, room: Int): List<String> {
        if (items.isEmpty()) return emptyList()
        val share = (room - BLOCK_ROOM) / items.size
        return items.map { (head, body) -> head + ModelText.cut(body, (share - head.length - 1).coerceAtLeast(MIN_BODY)) }
    }

    private fun traitName(trait: Trait): String = TraitAxis.of(trait.trait)?.let { "${it.id} (${it.pole(trait.side)})" }
        ?: "${trait.trait} « ${ModelText.escape(trait.label.orEmpty())} »"

    private fun relation(relation: Relation?): String = relation?.kind?.id ?: "none named"

    private fun statValue(stat: String, value: Double): String = if (stat == PLAYTIME) played(value) else number(value)

    private fun delta(stat: String, value: Double): String = (if (value < 0) "-" else "+") + statValue(stat, abs(value))

    /** Seconds of play as `50m` or `61h05m`. */
    private fun played(seconds: Double): String {
        val whole = seconds.roundToLong()
        return if (whole < 3_600) "${whole / 60}m" else "${whole / 3_600}h${(whole % 3_600 / 60).toString().padStart(2, '0')}m"
    }

    private fun number(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else decimal(value)

    private fun minutes(value: Double): String = ToolText.duration((value * 60_000).roundToLong())

    private fun day(at: Instant): String = DAY.withZone(zone).format(at)

    private fun dayTime(at: Instant): String = DAY_TIME.withZone(zone).format(at)

    private fun time(at: Instant): String = TIME.withZone(zone).format(at)

    private fun quoted(text: String, max: Int): String = ModelText.cut(ModelText.escape(text), max).replace('«', '‹').replace('»', '›')

    /** A row of `sessions` read as `player_uuid, session_id, joined_at, left_at`; running when its player is still in it. */
    private fun ResultSet.played(): Played {
        val player = uuid(1)
        val id = getLong(2)
        val left = instantOrNull(4)
        return Played(player, instant(3), left, left == null && services.players.get(player)?.sessionId == id)
    }

    /** A session of a player; [running] while they are still in it (a session left open by a crash is not). */
    private class Played(val player: UUID, val joined: Instant, val left: Instant?, val running: Boolean) {
        /** Its end at [now]: when the player left, now while they play, its start when a crash left it open. */
        fun end(now: Instant): Instant = left ?: if (running) now else joined

        /** Seconds of it after [since] and before [now]. */
        fun secondsWithin(since: Instant, now: Instant): Long {
            val from = maxOf(joined, since)
            val to = end(now)
            return if (to > from) Duration.between(from, to).seconds else 0
        }
    }

    /** A server session: a stretch of play with nobody away for [SESSION_GAP] or more; [open] when not over yet. */
    private data class Island(val from: Instant, val to: Instant, val open: Boolean)

    private class Group(val from: Instant, val to: Instant, val chapters: List<ChronicleEntry>)

    private class Said(val id: Long, val ts: Instant, val channel: String, val text: String)

    private class PlayerRead(
        val memories: List<MemoryEntry>,
        val interactions: List<Interaction>,
        val stats: Map<String, SheetStat>,
        val pairs: List<PairStats.PlayerPair>,
        val relations: List<Relation>,
        val chat: List<Said>,
        val names: Names,
    )

    companion object {
        /** The steps of a dream. */
        const val STEPS = 5

        /** The `memory_state` key of the last reflection on a player: `reflected:<uuid>`. */
        const val REFLECTED_PREFIX = "reflected:"

        fun reflectedKey(player: UUID): String = "$REFLECTED_PREFIX$player"

        /** A server session ends once the server has been empty this long (M3 §8.5). */
        val SESSION_GAP: Duration = Duration.ofMinutes(20)

        /** A season closes when it covers this many sessions, or spans [SEASON_DAYS] days (M3 §8.5). */
        const val SEASON_SESSIONS = 12
        const val SEASON_DAYS = 30L

        // Budgets in characters (M3 §8.2), tags included, and how many rows a block reads at most.
        const val SHEET_MAX = 7_100
        const val HIDDEN_TRAITS_MAX = 1_000
        const val MEMORIES_MAX = 12_000
        const val MEMORIES_COUNT = 40
        const val INTERACTIONS_MAX = 6_000
        const val INTERACTIONS_COUNT = 60
        const val STATS_MAX = 2_000
        const val SIGNALS_MAX = 500
        const val PAIRS_MAX = 1_000
        const val CHAT_MAX = 3_000
        const val CHAT_COUNT = 30
        const val TICS_MAX = 1_500
        const val LINES_MAX = 24_000
        const val LINES_COUNT = 150
        const val GAGS_MAX = 4_000
        const val GAGS_COUNT = 40
        const val REVIEW_MAX = 3_200
        const val SESSIONS_MAX = 40_000

        /** Chapters step 3 reads at most (whole sessions; the first one always); the rest waits for the next dream. */
        const val SESSION_CHAPTERS = 10
        const val SEASON_MAX = 3_300
        const val CLOSED_SEASONS_MAX = 3_000
        const val SAGA_MAX = 5_000
        const val GRIMOIRE_MAX = 10_200
        const val PLAYERS_MAX = 4_400
        const val PLAYERS_COUNT = 20
        const val SESSION_MAX = 1_800
        const val CANON_MAX = 6_000
        const val CANON_COUNT = 40
        const val ARCS_MAX = 3_000
        const val NOTES_MAX = 3_000
        const val CANDIDATES_MAX = 30_000

        /** The longest opening of each step: the sum of its blocks, plus the job line. */
        val STEP_MAX: Map<Int, Int> = mapOf(
            1 to 300 + SHEET_MAX + HIDDEN_TRAITS_MAX + MEMORIES_MAX + INTERACTIONS_MAX + STATS_MAX + SIGNALS_MAX + PAIRS_MAX + CHAT_MAX,
            2 to 300 + TICS_MAX + LINES_MAX + GAGS_MAX + REVIEW_MAX,
            3 to 300 + SESSIONS_MAX + SEASON_MAX + CLOSED_SEASONS_MAX + SAGA_MAX,
            4 to 300 + GRIMOIRE_MAX + PLAYERS_MAX + REVIEW_MAX + SESSION_MAX + CANON_MAX + ARCS_MAX + NOTES_MAX,
            5 to 300 + CANDIDATES_MAX,
        )

        private const val SIGNALS = "signals"
        private const val PLAYTIME = "playtime"
        private const val DEFAULT_LANGUAGE = "fr"
        private const val READ_TIMEOUT_MS = 15_000L
        private const val QUOTE_MAX = 120
        private const val CHAT_TEXT_MAX = 90
        private const val LINE_TEXT_MAX = 140
        private const val MIN_BODY = 60
        private const val MIN_SUMMARY = 400
        private const val BLOCK_ROOM = 200
        private const val SEASONS_READ = 200
        private const val CANON_READ = 200
        private val GAG_KINDS = setOf(CanonKind.RUNNING_GAG, CanonKind.NICKNAME, CanonKind.TITLE)

        private val JSON = Json { ignoreUnknownKeys = true }
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }

        private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
    }
}
