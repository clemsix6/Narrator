package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.dream.DreamInputs
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The memory's store on the Journal's DuckDB database (M3 §4.2): the one point of writing of the memory.
 * Each write is one [Journal.write] job in which the store checks the vocabularies, bounds, players and
 * refs, runs [VoiceGuard] on every text written (players' names masked), checks what each author may write
 * and the Chronicler's quotes against the Narrator lines it cites ([Quotes], [SAID_KINDS],
 * [DREAMER_CANON]), looks for duplicates ([Dedup]), then writes the row, its index terms ([indexer]) and
 * its `memory_log` row ([MemoryLog]). A broken rule throws [MemoryRejected] (nothing written) with a
 * message for the model; each write ends with a `memory` decision (`written`, `duplicate`, `rejected`).
 * Reads run on the Journal's read pool.
 *
 * What the store computes, never the writer: ids ([IdKind.MEMORY]), times of writing, play clocks
 * ([playClock]; an interaction's from the sessions at its time, [DuckPlayClock.playerSecondsAt]), the
 * initial stability of a memory (M3 §9.1), `counts` and `disrespect`, the time and place of a memory from
 * its evidence, the entry a nickname or title replaces, a trait's `since` and `trend`.
 */
class DuckMemoryStore(
    private val journal: Journal,
    private val indexer: Indexer,
    private val playClock: PlayClock,
    private val clock: Clock,
    trace: TraceSink = journal,
) : MemoryStore {
    private val log = Log.memory
    private val decisions = Decisions(trace, clock)
    private val dedup = Dedup(indexer)
    private val shortTime = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(clock.zone)

    /** One write job: its connection (the writer's, in the job's transaction), its author, its time. */
    private inner class Job(val connection: Connection, val author: Author, val now: Instant) {
        val names: Names by lazy { Names.of(connection) }

        fun next(kind: RefKind): Ref = Ref(kind, journal.nextId(IdKind.MEMORY))

        fun log(table: Audited, rowKey: String, op: LogOp, before: String?, after: String?, reason: String? = null) =
            MemoryLog.append(connection, now, author, table, rowKey, op, before, after, reason)

        /** Logs the change of a citable row, from its snapshot [before] to its current state. */
        fun logRow(ref: Ref, op: LogOp, before: String?, reason: String? = null) =
            log(Audited.of(ref.kind), ref.id.toString(), op, before, connection.snapshot(ref), reason)

        fun index(row: MemoryRow) {
            val text = indexText(row) ?: return
            indexer.remove(connection, row.ref)
            indexer.index(connection, row.ref, text)
        }

        /** The row [ref], which must be of [kind] ([what] for the message) and exist. */
        inline fun <reified T : MemoryRow> row(ref: Ref, kind: RefKind, what: String): T {
            if (ref.kind != kind) reject("$ref is not $what: give a ${kind.prefix}- id")
            return connection.readRow(ref) as? T ?: reject("$ref does not exist")
        }

        /** [players], known to the world, [max] at most, without repeats. */
        fun players(players: List<UUID>, field: String, max: Int = MAX_PLAYERS): List<UUID> {
            if (players.size > max) reject("$field: $max players at most")
            players.firstOrNull { !names.known(it) }?.let { reject("$field: $it is not a player of this world; name players who have played here") }
            return players.distinct()
        }

        /** [refs] exist, are of [kinds], [max] at most; without repeats. */
        fun refs(refs: List<Ref>, field: String, kinds: Set<RefKind>, max: Int): List<Ref> {
            if (refs.size > max) reject("$field: $max refs at most")
            refs.firstOrNull { it.kind !in kinds }?.let { ref ->
                reject("$field: $ref is not one of ${kinds.joinToString(", ") { "${it.prefix}-" }}")
            }
            val found = connection.refsOf(refs.map { it.id })
            refs.firstOrNull { found[it.id] != it }?.let { reject("$field: $it does not exist; cite refs you were given") }
            return refs.distinct()
        }

        /** The evidence of the Journal exists: [events] in `events` or `chat`, [tools] in `tool_calls`. */
        fun evidence(events: List<Long>, tools: List<Long>, field: String = "evidence") {
            if (events.size + tools.size > MAX_EVIDENCE) reject("$field: $MAX_EVIDENCE refs at most")
            if (events.isNotEmpty()) {
                val found = connection.query(
                    "SELECT id FROM events WHERE list_contains(?, id) UNION SELECT id FROM chat WHERE list_contains(?, id)",
                    longArray(events), longArray(events),
                ) { getLong(1) }.toSet()
                events.firstOrNull { it !in found }?.let { reject("$field: e$it is not in the Journal; cite the e… and t… ids you were given") }
            }
            if (tools.isNotEmpty()) {
                val found = connection.query("SELECT id FROM tool_calls WHERE list_contains(?, id)", longArray(tools)) { getLong(1) }.toSet()
                tools.firstOrNull { it !in found }?.let { reject("$field: t$it is not a tool call of the Journal; cite the e… and t… ids you were given") }
            }
        }

        /** When the evidence happened (first and last), and where its first event with a place did. */
        fun facts(events: List<Long>, tools: List<Long>): Facts {
            if (events.isEmpty() && tools.isEmpty()) return Facts(null, null, null)
            val (first, last) = connection.query(
                "SELECT min(ts), max(ts) FROM (SELECT ts FROM events WHERE list_contains(?, id) UNION ALL " +
                    "SELECT ts FROM chat WHERE list_contains(?, id) UNION ALL SELECT ts FROM tool_calls WHERE list_contains(?, id))",
                longArray(events), longArray(events), longArray(tools),
            ) { instantOrNull(1) to instantOrNull(2) }.single()
            val place = if (events.isEmpty()) {
                null
            } else {
                connection.query(
                    "SELECT dim, x, y, z FROM events WHERE list_contains(?, id) AND dim IS NOT NULL AND x IS NOT NULL ORDER BY ts, id LIMIT 1",
                    longArray(events),
                ) { Pos(getString(1), getInt(2), getInt(3), getInt(4)) }.firstOrNull()
            }
            return Facts(first, last, place)
        }

        /** The names of the players of the world, masked by [voice]. */
        private val playerNames: List<String> by lazy { connection.query("SELECT name FROM players") { getString(1) }.filterNotNull() }

        /**
         * [VoiceGuard]: [text] is factual and in the third person, within [max]; the players' names are
         * masked (a player may be called `Moi`). The passages between quotes are bounded for the texts of
         * the models, not for those of the code (which quotes statements already checked) nor of operators.
         */
        fun voice(text: String, field: String, max: Int) {
            VoiceGuard.check(text, field, max, playerNames, boundQuotes = author.source in MODELS)?.let(::reject)
        }

        /** What the Dreamer, which reads the players' chat and never hears the Narrator, may remember: observations. */
        fun memoryKind(kind: MemoryKind) {
            if (author.source == Source.DREAMER && kind != MemoryKind.OBSERVATION) {
                reject("the Dreamer remembers observations only, not ${an(kind.id)}: remember it as an observation")
            }
        }

        /**
         * Who may put what in the canon (decision 8): the Narrator's commitments come from its own words.
         * - The Dreamer plans arcs (`planned`) and keeps lore, without quote: it never heard the Narrator.
         * - The Chronicler copies the Narrator's words: for the kinds said to players ([SAID_KINDS]) a quote
         *   is required, and any quote must be [Quotes.substantial] and inside a Narrator line cited in the
         *   evidence ([Quotes.narratorLines]).
         */
        fun canonSource(draft: CanonDraft, status: CanonStatus) {
            when (author.source) {
                Source.DREAMER -> {
                    if (draft.kind !in DREAMER_CANON) {
                        reject("the Dreamer plans arcs and keeps lore; ${an(draft.kind.id)} is recorded from the Narrator's own words, not by the Dreamer")
                    }
                    if (draft.kind == CanonKind.ARC && status != CanonStatus.PLANNED) reject("an arc the Dreamer prepares starts planned: the Narrator starts it")
                    if (draft.quote != null) reject("quote: the Dreamer never heard the Narrator, it quotes nobody: remove quote")
                }
                Source.CHRONICLER -> narratorQuote(draft.kind, draft.quote, draft.evidenceEvents, draft.evidenceTools)
                else -> Unit
            }
        }

        private fun narratorQuote(kind: CanonKind, quote: String?, events: List<Long>, tools: List<Long>) {
            val fix = "cite the NARRATOR line in evidence and copy its words"
            if (quote == null) {
                if (kind in SAID_KINDS) reject("quote is required for ${an(kind.id)}, the Narrator's exact words: $fix")
                return
            }
            if (!Quotes.substantial(quote)) {
                reject("quote is too short to show what the Narrator said (${Quotes.MIN_WORDS} words or ${Quotes.MIN_CHARS} characters at least): $fix")
            }
            if (!Quotes.saidIn(quote, Quotes.narratorLines(connection, events, tools))) {
                reject("quote must be words of a NARRATOR line cited in evidence (an e… NARRATOR line, or a t… say, give_book or show_title call): $fix")
            }
        }

        /** The Chronicler's interaction [quote] is words [player] said in its job's window (public chat or prayer). */
        fun playerQuote(quote: String?, player: UUID) {
            if (quote == null || author.source != Source.CHRONICLER) return
            val runId = author.runId ?: reject("quote cannot be checked without a memory job: leave quote out")
            val (from, to) = Quotes.window(connection, runId, now)
                ?: reject("quote cannot be checked: memory job $runId is unknown; leave quote out")
            if (Quotes.saidByPlayer(connection, player, quote, from, to)) return
            reject("quote must be words ${names.ref(player).name} said in this window; leave quote out or copy them exactly")
        }

        /**
         * What reverting a nap or a dream undoes besides its rows (M3-FIX-A): its `memory_runs` row turns
         * `failed` (« reverted by … »), so that the catch-up naps the chapter again and the next dream does
         * not take it for done; a dream's reflections on players (`memory_state` `reflected:<uuid>`, written
         * while it ran) are forgotten, so that its players are read again. Returns what it did, for the log.
         */
        fun forgetRun(runId: Long): List<String> {
            val (kind, started, ended) = connection.query("SELECT kind, started_at, ended_at FROM memory_runs WHERE run_id = ?", runId) {
                Triple(getString(1), instant(2), instantOrNull(3))
            }.singleOrNull() ?: return emptyList()
            if (kind != RunKind.NAP.id && kind != RunKind.DREAM.id) return emptyList()
            connection.update(
                "UPDATE memory_runs SET status = ?, error = ? WHERE run_id = ?",
                RunStatus.FAILED.id, "reverted by ${author.actor}", runId,
            )
            val done = listOf("$kind run $runId marked failed")
            if (kind != RunKind.DREAM.id) return done
            val reflections = connection.query(
                "SELECT key FROM memory_state WHERE starts_with(key, ?) AND updated_at BETWEEN ? AND ? ORDER BY key",
                DreamInputs.REFLECTED_PREFIX, started, ended ?: now,
            ) { getString(1) }
            if (reflections.isEmpty()) return done
            connection.update("DELETE FROM memory_state WHERE list_contains(?, key)", stringArray(reflections))
            return done + "reflections forgotten: ${reflections.joinToString()}"
        }
    }

    /** What the evidence of a row says: when it happened (first and last), where. */
    private class Facts(val first: Instant?, val last: Instant?, val place: Pos?)

    // ── Writes ──────────────────────────────────────────────────────────────────────────────────────

    override suspend fun addMemory(draft: MemoryDraft, author: Author): Added<MemoryEntry> {
        if (draft.evidenceEvents.isNotEmpty() || draft.evidenceTools.isNotEmpty()) journal.barrier()
        val what = "memory ${draft.kind.id}"
        val added = write<Added<MemoryEntry>>(what, author) { job ->
            job.memoryKind(draft.kind)
            job.voice(draft.title, "title", MemoryLimits.MEMORY_TITLE)
            job.voice(draft.content, "what", MemoryLimits.MEMORY_CONTENT)
            within(draft.importance, MemoryLimits.IMPORTANCE, "importance")
            within(draft.valence, MemoryLimits.VALENCE, "valence")
            tags(draft.tags)
            entities(draft.entities)
            val players = job.players(draft.players, "players")
            job.evidence(draft.evidenceEvents, draft.evidenceTools)
            if (draft.occurredFrom != null && draft.occurredTo != null && draft.occurredFrom > draft.occurredTo) {
                reject("the memory ends before it starts")
            }
            dedup.memory(job.connection, players, draft.tags, draft.evidenceEvents, draft.evidenceTools)?.let { ref ->
                return@write Added.Duplicate(job.row<MemoryEntry>(ref, RefKind.MEMORY, "a memory"))
            }
            val facts = job.facts(draft.evidenceEvents, draft.evidenceTools)
            val from = draft.occurredFrom ?: facts.first ?: job.now
            val to = draft.occurredTo ?: maxOf(from, facts.last ?: from)
            val ref = job.next(RefKind.MEMORY)
            job.connection.update(
                "INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, " +
                    "importance, valence, dim, x, y, z, stability_h, reinforced_play_s, source, run_id, turn_id, conversation_id, " +
                    "evidence_events, evidence_tools) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.id, draft.kind.id, job.now, from, to, draft.title, draft.content, uuidArray(players),
                stringArray(draft.entities.distinct()), stringArray(draft.tags.distinct()), draft.importance, draft.valence,
                facts.place?.dim, facts.place?.x, facts.place?.y, facts.place?.z, Decay.initialStability(draft.importance),
                playClock.worldPlaySeconds(job.now.toEpochMilli()), author.source.id, author.runId, author.turnId, author.conversationId,
                longArray(draft.evidenceEvents.distinct()), longArray(draft.evidenceTools.distinct()),
            )
            val row = job.row<MemoryEntry>(ref, RefKind.MEMORY, "a memory")
            job.index(row)
            job.logRow(ref, LogOp.INSERT, null)
            Added.New(row)
        }
        recordAdd(added, what, author, listOf(draft.title, draft.content))
        return added
    }

    override suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry> {
        if (draft.evidenceEvents.isNotEmpty() || draft.evidenceTools.isNotEmpty() || draft.quote != null) journal.barrier()
        val kind = draft.kind
        val what = "canon ${kind.id}"
        val added = write<Added<CanonEntry>>(what, author) { job ->
            job.voice(draft.statement, "what", MemoryLimits.CANON_STATEMENT)
            quote(draft.quote, MemoryLimits.CANON_QUOTE)
            draft.stakes?.let {
                if (kind != CanonKind.CHALLENGE) reject("stakes only go with a challenge, not ${an(kind.id)}: leave stakes out")
                job.voice(it, "stakes", MemoryLimits.CANON_STAKES)
            }
            within(draft.importance, MemoryLimits.IMPORTANCE, "importance")
            val status = draft.status ?: CanonStatus.ACTIVE
            if (status !in kind.startsAs) reject("${an(kind.id)} starts ${kind.startsAs.joinToString(" or ") { it.id }}, not ${status.id}")
            if (draft.dueAt != null && !kind.dueAllowed) reject("a due time does not apply to ${an(kind.id)}: leave due_in_minutes out")
            val players = job.players(draft.players, "players")
            if (kind.onePerPlayer && players.size != 1) reject("${an(kind.id)} is given to exactly one player: name one")
            if (kind == CanonKind.PLACE && draft.place == null) reject("a place needs where it is: give at_player")
            job.evidence(draft.evidenceEvents, draft.evidenceTools)
            job.canonSource(draft, status)
            val replaced = if (kind.onePerPlayer) dedup.active(job.connection, kind, players.single()) else null
            val duplicate = if (replaced != null) {
                replaced.takeIf { sameName(job.row(it, RefKind.CANON, "a canon entry"), draft) }
            } else {
                dedup.canon(job.connection, kind, players, draft.quote, canonText(draft.statement, draft.quote, kind))
            }
            if (duplicate != null) return@write Added.Duplicate(job.row<CanonEntry>(duplicate, RefKind.CANON, "a canon entry"))
            val ref = job.next(RefKind.CANON)
            job.connection.update(
                "INSERT INTO canon (id, kind, created_at, statement, quote, players, stakes, importance, status, due_at, replaces, " +
                    "dim, x, y, z, freshness, source, run_id, turn_id, conversation_id, evidence_events, evidence_tools) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.id, kind.id, job.now, draft.statement, draft.quote, uuidArray(players), draft.stakes, draft.importance, status.id,
                draft.dueAt, replaced?.id, draft.place?.dim, draft.place?.x, draft.place?.y, draft.place?.z,
                if (kind == CanonKind.RUNNING_GAG) GagState.FRESH.id else null, author.source.id, author.runId, author.turnId,
                author.conversationId, longArray(draft.evidenceEvents.distinct()), longArray(draft.evidenceTools.distinct()),
            )
            if (replaced != null) {
                val before = job.connection.snapshot(replaced)
                job.connection.update(
                    "UPDATE canon SET status = 'retired', resolved_at = ?, resolution = ? WHERE id = ?",
                    job.now, "replaced by $ref", replaced.id,
                )
                job.logRow(replaced, LogOp.UPDATE, before, "replaced by $ref")
            }
            val row = job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
            job.index(row)
            job.logRow(ref, LogOp.INSERT, null)
            Added.New(row)
        }
        recordAdd(added, what, author, listOfNotNull(draft.statement, draft.stakes))
        return added
    }

    override suspend fun resolveCanon(ref: Ref, status: CanonStatus, note: String, winners: List<UUID>, author: Author): CanonEntry {
        val row = write("resolve $ref", author) { job ->
            val old = job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
            if (!old.status.open) {
                val since = old.resolvedAt?.let { " (${shortTime.format(it)})" }.orEmpty()
                reject("$ref is already ${old.status.id}$since; remember a new entry instead")
            }
            if (author.source == Source.DREAMER && old.kind != CanonKind.ARC) {
                reject("the Dreamer settles arcs only; $ref is ${an(old.kind.id)}, settled by the Narrator from what happens in game")
            }
            if (!old.kind.allows(old.status, status)) {
                val allowed = old.kind.closedBy.joinToString(", ") { it.id }
                reject("${an(old.kind.id)} cannot be ${status.id}; use $allowed")
            }
            job.voice(note, "note", MemoryLimits.RESOLUTION)
            val players = if (status == CanonStatus.WON) {
                if (winners.isEmpty()) reject("won needs its winners: name them")
                job.players(winners, "winners")
            } else {
                if (winners.isNotEmpty()) reject("winners only go with won: leave them out")
                emptyList()
            }
            val before = job.connection.snapshot(ref)
            if (status == CanonStatus.ACTIVE) {
                job.connection.update("UPDATE canon SET status = 'active' WHERE id = ?", ref.id)
            } else {
                job.connection.update(
                    "UPDATE canon SET status = ?, resolved_at = ?, resolution = ?, winners = ?, " +
                        "freshness = CASE WHEN kind = 'running_gag' THEN 'retired' ELSE freshness END WHERE id = ?",
                    status.id, job.now, note, if (players.isEmpty()) null else uuidArray(players), ref.id,
                )
            }
            job.logRow(ref, LogOp.UPDATE, before, note)
            job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
        }
        recordWrite("$ref", "canon ${row.kind.id} ${status.id}", author, listOf(note))
        return row
    }

    override suspend fun useCanon(ref: Ref, at: Instant, author: Author): CanonEntry {
        val row = write("use $ref", author) { job ->
            val old = job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
            if (!old.status.open) reject("$ref is ${old.status.id}: it cannot be used any more")
            val before = job.connection.snapshot(ref)
            job.connection.update("UPDATE canon SET uses = uses + 1, last_used_at = ? WHERE id = ?", at, ref.id)
            job.logRow(ref, LogOp.UPDATE, before, "used")
            job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
        }
        recordWrite("$ref", "canon ${row.kind.id} used (${row.uses})", author)
        return row
    }

    override suspend fun addInteraction(draft: InteractionDraft, author: Author): Added<Interaction> {
        if (draft.evidenceEvents.isNotEmpty() || draft.toolCalls.isNotEmpty() || draft.quote != null) journal.barrier()
        val what = "interaction ${draft.kind.id} ${draft.valence}"
        val added = write<Added<Interaction>>(what, author) { job ->
            if (author.source == Source.DREAMER) reject("the Dreamer does not rate interactions: the Narrator and the Chronicler note them as they happen")
            val player = job.players(listOf(draft.player), "player").single()
            within(draft.valence, MemoryLimits.VALENCE, "valence")
            job.voice(draft.summary, "summary", MemoryLimits.INTERACTION_SUMMARY)
            quote(draft.quote, MemoryLimits.INTERACTION_QUOTE)
            draft.canon?.let { job.refs(listOf(it), "canon", setOf(RefKind.CANON), 1) }
            draft.memory?.let { job.refs(listOf(it), "memory", setOf(RefKind.MEMORY), 1) }
            job.evidence(draft.evidenceEvents, draft.toolCalls)
            job.playerQuote(draft.quote, player)
            val at = draft.at ?: job.facts(draft.evidenceEvents, emptyList()).first ?: job.facts(emptyList(), draft.toolCalls).first ?: job.now
            val text = interactionText(draft.summary, draft.quote, draft.kind)
            dedup.interaction(job.connection, player, draft.kind, at, draft.evidenceEvents, draft.toolCalls, text)?.let { ref ->
                return@write Added.Duplicate(job.row<Interaction>(ref, RefKind.INTERACTION, "an interaction"))
            }
            val ref = job.next(RefKind.INTERACTION)
            job.connection.update(
                "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, quote, played_s, " +
                    "canon_id, memory_id, tool_calls, source, run_id, turn_id, conversation_id, evidence_events) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.id, at, player, job.names.ref(player).name, draft.kind.id, draft.valence, draft.kind.counts,
                draft.kind.isDisrespect(draft.valence), draft.summary, draft.quote, DuckPlayClock.playerSecondsAt(job.connection, player, at),
                draft.canon?.id, draft.memory?.id, longArray(draft.toolCalls.distinct()), author.source.id, author.runId, author.turnId,
                author.conversationId, longArray(draft.evidenceEvents.distinct()),
            )
            val row = job.row<Interaction>(ref, RefKind.INTERACTION, "an interaction")
            job.index(row)
            job.logRow(ref, LogOp.INSERT, null)
            Added.New(row)
        }
        recordAdd(added, what, author, listOf(draft.summary))
        return added
    }

    override suspend fun setTrait(draft: TraitDraft, author: Author): Trait {
        val key = "${draft.player}:${draft.trait}"
        val trait = write("trait $key", author) { job ->
            val player = job.players(listOf(draft.player), "player").single()
            val axis = TraitAxis.of(draft.trait)
            if (axis == null && !TraitAxis.isFree(draft.trait)) {
                reject("trait must be one of ${TraitAxis.entries.joinToString(", ") { it.id }}, or free:<slug> (a-z, 0-9, _)")
            }
            if (axis != null) {
                if (draft.side != -1 && draft.side != 1) reject("side must be low or high for ${axis.id}")
                if (draft.label != null) reject("label only goes with a free trait: leave it out")
            } else {
                if (draft.side != 0) reject("a free trait has no side: leave it out")
                job.voice(draft.label ?: reject("a free trait needs its label"), "label", MemoryLimits.TRAIT_LABEL)
            }
            within(draft.intensity, MemoryLimits.INTENSITY, "intensity")
            if (!(draft.confidence in 0.0..1.0)) reject("confidence must be between 0 and 1")
            draft.note?.let { job.voice(it, "note", MemoryLimits.TRAIT_NOTE) }
            if (draft.evidence.isEmpty()) reject("evidence: cite at least one M- or I- row about this player")
            val evidence = job.refs(draft.evidence, "evidence", setOf(RefKind.MEMORY, RefKind.INTERACTION), MAX_TRAIT_EVIDENCE)
            evidence.firstOrNull { !concerns(job.connection.readRow(it), player) }?.let {
                reject("evidence: $it is not about ${job.names.ref(player).name}; cite rows about this player")
            }
            val old = job.connection.traits("player_uuid = ? AND trait = ?", player, draft.trait).singleOrNull()?.takeUnless { it.retired }
            if (axis == null && old == null) {
                val free = job.connection.traits("player_uuid = ? AND starts_with(trait, ?) AND NOT retired", player, TraitAxis.FREE_PREFIX)
                if (free.size >= TraitAxis.MAX_FREE) {
                    reject("${job.names.ref(player).name} has ${TraitAxis.MAX_FREE} free traits already (${free.joinToString { it.trait }}): retire one or change one")
                }
            }
            val turned = old != null && old.side * draft.side < 0
            val confidence = Decay.confidenceOnSet(draft.confidence, old?.side, draft.side)
            val trend = when {
                old == null -> 0
                turned -> -1
                else -> draft.intensity.compareTo(old.intensity).coerceIn(-1, 1)
            }
            val since = if (old == null || turned) job.now else old.since
            val before = job.connection.snapshot("player_traits", "player_uuid = ? AND trait = ?", player, draft.trait)
            job.connection.update(
                "INSERT OR REPLACE INTO player_traits (player_uuid, trait, side, label, intensity, confidence, evidence, since, trend, " +
                    "note, set_at, set_played_s, run_id, retired) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)",
                player, draft.trait, draft.side, draft.label, draft.intensity, confidence, longArray(evidence.map { it.id }), since, trend,
                draft.note, job.now, playClock.playerPlaySeconds(player, job.now.toEpochMilli()), author.runId,
            )
            val after = job.connection.snapshot("player_traits", "player_uuid = ? AND trait = ?", player, draft.trait)
            job.log(Audited.TRAITS, key, if (before == null) LogOp.INSERT else LogOp.UPDATE, before, after, if (turned) "pole changed" else null)
            job.connection.traits("player_uuid = ? AND trait = ?", player, draft.trait).single()
        }
        recordWrite(key, "trait ${trait.trait} ${trait.side} x${trait.intensity}", author, listOfNotNull(draft.label, draft.note))
        return trait
    }

    override suspend fun retireTrait(player: UUID, trait: String, reason: String, author: Author) {
        val key = "$player:$trait"
        write("retire trait $key", author) { job ->
            reason(reason)
            val old = job.connection.traits("player_uuid = ? AND trait = ?", player, trait).singleOrNull()
            if (old == null || old.retired) reject("$trait is not a trait of this player: nothing to retire")
            val before = job.connection.snapshot("player_traits", "player_uuid = ? AND trait = ?", player, trait)
            job.connection.update("UPDATE player_traits SET retired = true WHERE player_uuid = ? AND trait = ?", player, trait)
            val after = job.connection.snapshot("player_traits", "player_uuid = ? AND trait = ?", player, trait)
            job.log(Audited.TRAITS, key, LogOp.UPDATE, before, after, "retired: $reason")
        }
        recordWrite(key, "trait $trait retired", author)
    }

    override suspend fun setSkill(
        player: UUID,
        skill: Skill,
        score: Int,
        rationale: String,
        evidence: List<Ref>,
        author: Author,
    ): SkillScore {
        val key = "$player:${skill.id}"
        val row = write("skill $key", author) { job ->
            job.players(listOf(player), "player")
            within(score, MemoryLimits.SKILL_SCORE, "score")
            job.voice(rationale, "rationale", MemoryLimits.SKILL_RATIONALE)
            val refs = job.refs(evidence, "evidence", RefKind.entries.toSet(), MAX_SHEET_EVIDENCE)
            val where = "player_uuid = ? AND skill = ?"
            val before = job.connection.snapshot("player_skills", where, player, skill.id)
            job.connection.update(
                "INSERT OR REPLACE INTO player_skills (player_uuid, skill, score, rationale, evidence, set_at, run_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                player, skill.id, score, rationale, longArray(refs.map { it.id }), job.now, author.runId,
            )
            val after = job.connection.snapshot("player_skills", where, player, skill.id)
            job.log(Audited.SKILLS, key, if (before == null) LogOp.INSERT else LogOp.UPDATE, before, after)
            job.connection.skills(where, player, skill.id).single()
        }
        recordWrite(key, "skill ${skill.id} $score", author, listOf(rationale))
        return row
    }

    override suspend fun setRelation(
        player: UUID,
        other: UUID,
        kind: RelationKind,
        note: String,
        evidence: List<Ref>,
        author: Author,
    ): Relation {
        val key = "$player:$other"
        val row = write("relation $key", author) { job ->
            job.players(listOf(player), "player")
            job.players(listOf(other), "other")
            if (player == other) reject("a relation is between two different players")
            job.voice(note, "note", MemoryLimits.RELATION_NOTE)
            if (evidence.isEmpty()) reject("evidence: cite at least one row")
            val refs = job.refs(evidence, "evidence", RefKind.entries.toSet(), MAX_SHEET_EVIDENCE)
            val where = "player_uuid = ? AND other_uuid = ?"
            val before = job.connection.snapshot("player_relations", where, player, other)
            job.connection.update(
                "INSERT OR REPLACE INTO player_relations (player_uuid, other_uuid, kind, note, evidence, set_at, run_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                player, other, kind.id, note, longArray(refs.map { it.id }), job.now, author.runId,
            )
            val after = job.connection.snapshot("player_relations", where, player, other)
            job.log(Audited.RELATIONS, key, if (before == null) LogOp.INSERT else LogOp.UPDATE, before, after)
            job.connection.relations(where, player, other).single()
        }
        recordWrite(key, "relation ${kind.id}", author, listOf(note))
        return row
    }

    override suspend fun writeDoc(key: DocKey, content: String, evidence: List<Ref>, author: Author): MemoryDoc {
        val doc = write("doc ${key.key}", author) { job ->
            val max = when (key) {
                DocKey.Grimoire -> MemoryLimits.GRIMOIRE
                DocKey.Review -> MemoryLimits.REVIEW
                is DocKey.Sheet -> key.section.maxChars.also { job.players(listOf(key.player), "player") }
            }
            job.voice(content, "text", max)
            val refs = job.refs(evidence, "evidence", RefKind.entries.toSet(), MAX_DOC_EVIDENCE)
            val doc = MemoryDocs.write(job.connection, key.key, content, refs, job.now, author)
            val rowKey = MemoryDocs.rowKey(doc.key, doc.version)
            job.log(Audited.DOCS, rowKey, LogOp.INSERT, null, job.connection.snapshot("memory_docs", "key = ? AND version = ?", doc.key, doc.version))
            doc
        }
        recordWrite(MemoryDocs.rowKey(doc.key, doc.version), "doc ${doc.key} v${doc.version}", author, listOf(content))
        return doc
    }

    override suspend fun addChronicle(draft: ChronicleDraft, author: Author): ChronicleEntry {
        val level = draft.level
        val row = write("chronicle ${level.id}", author) { job ->
            job.voice(draft.title, "title", MemoryLimits.CHRONICLE_TITLE)
            job.voice(draft.summary, "text", level.maxChars)
            if (draft.from > draft.to) reject("the period ends before it starts")
            val below = level.below
            if (below == null) {
                if (draft.covers.isNotEmpty()) reject("a chapter covers no other entry: leave covers out")
            } else {
                if (draft.covers.isEmpty()) reject("covers: cite the ${below.id} entries this ${level.id} covers")
                val covers = job.refs(draft.covers, "covers", setOf(RefKind.CHRONICLE), MAX_COVERS)
                covers.forEach { ref ->
                    val covered = job.row<ChronicleEntry>(ref, RefKind.CHRONICLE, "a chronicle entry")
                    if (covered.level != below) reject("covers: $ref is ${an(covered.level.id)}; ${an(level.id)} covers ${below.id} entries")
                    covered.supersededBy?.let { reject("covers: $ref was rewritten as $it; cite $it") }
                }
            }
            if (draft.open && level != ChronicleLevel.SEASON && level != ChronicleLevel.SAGA) reject("only the season in progress and the saga stay open")
            val rewritten = draft.rewrites?.let { ref ->
                val old = job.row<ChronicleEntry>(ref, RefKind.CHRONICLE, "a chronicle entry")
                if (old.level != level || !old.open || old.supersededBy != null) reject("rewrites: $ref is not the open ${level.id}")
                old
            }
            if (level == ChronicleLevel.CHAPTER && draft.conversationId != null) {
                job.connection.query(
                    "SELECT id FROM chronicle WHERE level = 'chapter' AND conversation_id = ? AND superseded_by IS NULL",
                    draft.conversationId,
                ) { getLong(1) }.firstOrNull()?.let { reject("the chronicle of this chapter exists already (H-$it)") }
            }
            val ref = job.next(RefKind.CHRONICLE)
            job.connection.update(
                "INSERT INTO chronicle (id, level, period_from, period_to, title, summary, covers, conversation_id, open, written_at, source, run_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.id, level.id, draft.from, draft.to, draft.title, draft.summary, longArray(draft.covers.map { it.id }.distinct()),
                draft.conversationId, draft.open, job.now, author.source.id, author.runId,
            )
            if (rewritten != null) {
                val before = job.connection.snapshot(rewritten.ref)
                job.connection.update("UPDATE chronicle SET open = false, superseded_by = ? WHERE id = ?", ref.id, rewritten.ref.id)
                job.logRow(rewritten.ref, LogOp.UPDATE, before, "rewritten as $ref")
            }
            val row = job.row<ChronicleEntry>(ref, RefKind.CHRONICLE, "a chronicle entry")
            job.index(row)
            job.logRow(ref, LogOp.INSERT, null)
            row
        }
        recordWrite("${row.ref}", "chronicle ${level.id}", author, listOf(draft.title, draft.summary))
        return row
    }

    override suspend fun addNote(draft: NoteDraft, author: Author): Note {
        val row = write("note ${draft.kind.id}", author) { job ->
            val max = if (draft.kind == NoteKind.DREAM_HINT) MemoryLimits.DREAM_HINT else MemoryLimits.NOTE_TEXT
            job.voice(draft.text, "text", max)
            val players = job.players(draft.players, "players")
            val refs = job.refs(draft.refs, "refs", RefKind.entries.toSet(), MAX_NOTE_REFS)
            val ref = job.next(RefKind.NOTE)
            job.connection.update(
                "INSERT INTO notes (id, created_at, kind, text, players, refs, status, expires_at, source, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.id, job.now, draft.kind.id, draft.text, uuidArray(players), longArray(refs.map { it.id }), NoteStatus.OPEN.id,
                draft.expiresAt, author.source.id, author.runId,
            )
            job.logRow(ref, LogOp.INSERT, null)
            job.row<Note>(ref, RefKind.NOTE, "a note")
        }
        recordWrite("${row.ref}", "note ${draft.kind.id}", author, listOf(draft.text))
        return row
    }

    override suspend fun markNote(ref: Ref, status: NoteStatus, author: Author): Note {
        val row = write("mark $ref", author) { job ->
            val old = job.row<Note>(ref, RefKind.NOTE, "a note")
            val before = job.connection.snapshot(ref)
            when (status) {
                NoteStatus.OPEN -> reject("a note is marked shown or done, never open again")
                NoteStatus.SHOWN -> job.connection.update(
                    "UPDATE notes SET status = ?, shown_count = shown_count + 1, last_shown_at = ? WHERE id = ?",
                    if (old.status == NoteStatus.DONE) NoteStatus.DONE.id else NoteStatus.SHOWN.id, job.now, ref.id,
                )
                NoteStatus.DONE -> job.connection.update("UPDATE notes SET status = 'done' WHERE id = ?", ref.id)
            }
            job.logRow(ref, LogOp.UPDATE, before, status.id)
            job.row<Note>(ref, RefKind.NOTE, "a note")
        }
        recordWrite("$ref", "note ${status.id}", author)
        return row
    }

    override suspend fun setGag(ref: Ref, state: GagState, note: String, author: Author): CanonEntry {
        val row = write("gag $ref", author) { job ->
            val old = job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
            if (old.kind != CanonKind.RUNNING_GAG) reject("$ref is ${an(old.kind.id)}, not a running gag")
            if (!old.status.open) reject("$ref is ${old.status.id}: remember a new running gag instead")
            job.voice(note, "note", MemoryLimits.GAG_NOTE)
            val before = job.connection.snapshot(ref)
            if (state == GagState.RETIRED) {
                job.connection.update(
                    "UPDATE canon SET freshness = 'retired', status = 'retired', resolved_at = ?, resolution = ? WHERE id = ?",
                    job.now, note, ref.id,
                )
            } else {
                job.connection.update("UPDATE canon SET freshness = ? WHERE id = ?", state.id, ref.id)
            }
            job.logRow(ref, LogOp.UPDATE, before, note)
            job.row<CanonEntry>(ref, RefKind.CANON, "a canon entry")
        }
        recordWrite("$ref", "running gag ${state.id}", author, listOf(note))
        return row
    }

    override suspend fun merge(keep: Ref, merged: List<Ref>, content: String?, author: Author): MemoryEntry {
        val row = write("merge into $keep", author) { job ->
            val kept = job.row<MemoryEntry>(keep, RefKind.MEMORY, "a memory")
            if (kept.archived) reject("$keep is archived: keep a memory that is not")
            if (merged.isEmpty()) reject("merged: name the memories to merge into $keep")
            if (merged.size > MAX_MERGED) reject("merged: $MAX_MERGED memories at most")
            if (keep in merged) reject("merged: $keep cannot be merged into itself")
            content?.let { job.voice(it, "text", MemoryLimits.MEMORY_CONTENT) }
            val others = merged.distinct().map { ref ->
                job.row<MemoryEntry>(ref, RefKind.MEMORY, "a memory").also { if (it.archived) reject("merged: $ref is archived already") }
            }
            val all = listOf(kept) + others
            val evidence = job.connection.query(
                "SELECT flatten(list(evidence_events)), flatten(list(evidence_tools)) FROM memories WHERE list_contains(?, id)",
                longArray(all.map { it.ref.id }),
            ) { longs(1).distinct() to longs(2).distinct() }.single()
            val best = all.maxBy { it.stabilityHours }
            val before = job.connection.snapshot(keep)
            job.connection.update(
                "UPDATE memories SET content = ?, occurred_from = ?, occurred_to = ?, players = ?, entities = ?, tags = ?, importance = ?, " +
                    "stability_h = ?, reinforced_play_s = ?, recalls = ?, last_recalled_at = ?, evidence_events = ?, evidence_tools = ? WHERE id = ?",
                content ?: kept.content, all.minOf { it.occurredFrom }, all.maxOf { it.occurredTo },
                uuidArray(all.flatMap { e -> e.players.map { it.uuid } }.distinct()), stringArray(all.flatMap { it.entities }.distinct()),
                stringArray(all.flatMap { it.tags }.distinct()), all.maxOf { it.importance }, best.stabilityHours,
                all.maxOf { it.reinforcedPlaySeconds }, all.sumOf { it.recalls }, all.mapNotNull { it.lastRecalledAt }.maxOrNull(),
                longArray(evidence.first), longArray(evidence.second), keep.id,
            )
            others.forEach { other ->
                val was = job.connection.snapshot(other.ref)
                job.connection.update("UPDATE memories SET archived = true, merged_into = ? WHERE id = ?", keep.id, other.ref.id)
                job.logRow(other.ref, LogOp.MERGE, was, "merged into $keep")
            }
            job.logRow(keep, LogOp.MERGE, before, "absorbed ${others.joinToString { it.ref.toString() }}")
            job.row<MemoryEntry>(keep, RefKind.MEMORY, "a memory").also(job::index)
        }
        recordWrite("$keep", "merged ${merged.joinToString()}", author, listOfNotNull(content))
        return row
    }

    override suspend fun archive(ref: Ref, archived: Boolean, reason: String, author: Author) {
        write(if (archived) "archive $ref" else "restore $ref", author) { job ->
            reason(reason)
            if (ref.kind !in ARCHIVABLE) reject("$ref cannot be archived: only memories, canon entries and interactions can")
            val row = job.connection.readRow(ref) ?: reject("$ref does not exist")
            val was = when (row) {
                is MemoryEntry -> row.archived
                is CanonEntry -> row.archived
                is Interaction -> row.archived
                else -> error("not archivable: $ref")
            }
            if (was == archived) reject("$ref is ${if (archived) "archived" else "not archived"} already")
            val before = job.connection.snapshot(ref)
            job.connection.update("UPDATE ${ref.kind.table} SET archived = ? WHERE id = ?", archived, ref.id)
            job.logRow(ref, if (archived) LogOp.ARCHIVE else LogOp.RESTORE, before, reason)
        }
        decide(if (archived) "archived" else "restored", "$ref", "$reason (by ${author.actor})", author, Log.Level.INFO)
    }

    override suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
        if (refs.isEmpty()) return
        val shown = journal.write(WRITE_TIMEOUT_MS) { connection ->
            val now = clock.instant()
            val world = playClock.worldPlaySeconds(now.toEpochMilli())
            val found = connection.refsOf(refs.map { it.id })
            refs.filter { found[it.id] == it }.onEach { ref ->
                connection.update(
                    "INSERT INTO recalls VALUES (?, ?, ?, ?, ?, ?)",
                    now, ref.id, channel.id, context.conversationId, context.turnId, context.eventId,
                )
                if (ref.kind == RefKind.MEMORY) reinforce(connection, ref, channel, world, now)
            }
        }
        log.debug { "recalled ${shown.joinToString()} (${channel.id})" }
    }

    /**
     * Reinforces a memory shown again through [channel] (M3 §9.2, [Decay.reinforce]): the more it was
     * forgotten, the more it grows, and its clock starts again; `recalls + 1`. A channel that does not
     * reinforce (the Dreamer's reading) leaves the memory as it is.
     */
    private fun reinforce(connection: Connection, ref: Ref, channel: RecallChannel, world: Long, now: Instant) {
        val (stability, since) = connection.query("SELECT stability_h, reinforced_play_s FROM memories WHERE id = ?", ref.id) {
            getDouble(1) to getLong(2)
        }.single()
        val reinforced = Decay.reinforce(stability, since, world, channel) ?: return
        connection.update(
            "UPDATE memories SET stability_h = ?, reinforced_play_s = ?, recalls = recalls + 1, last_recalled_at = ? WHERE id = ?",
            reinforced.stabilityHours, reinforced.reinforcedPlaySeconds, now, ref.id,
        )
    }

    override suspend fun rollbackDoc(key: String, version: Int, author: Author): MemoryDoc {
        val doc = write("rollback $key", author) { job ->
            DocKey.parse(key) ?: reject("$key is not a document: grimoire, review or sheet:<uuid>:<section>")
            val old = MemoryDocs.read(job.connection, key, version)
                ?: reject("$key has no version $version (latest: ${MemoryDocs.latestVersion(job.connection, key)})")
            val doc = MemoryDocs.write(job.connection, key, old.content, old.evidence, job.now, author, restoredFrom = version)
            val after = job.connection.snapshot("memory_docs", "key = ? AND version = ?", key, doc.version)
            job.log(Audited.DOCS, MemoryDocs.rowKey(key, doc.version), LogOp.ROLLBACK, null, after, "copy of version $version")
            doc
        }
        decide("rolled_back", key, "version $version restored as version ${doc.version} (by ${author.actor})", author, Log.Level.INFO)
        return doc
    }

    override suspend fun revertRun(runId: Long, author: Author): Int {
        val reverted = write("revert run $runId", author) { job ->
            val result = MemoryLog.revert(job.connection, runId, author, job.now, indexer)
            if (result.already) reject("run $runId was reverted already")
            if (result.conflicts.isNotEmpty()) {
                val changes = result.conflicts.take(5).joinToString("; ") { "${it.table} ${it.rowKey} by ${it.actor} (${shortTime.format(it.at)})" }
                reject("run $runId cannot be reverted: rows it wrote changed since: $changes")
            }
            result to if (result.writes > 0) job.forgetRun(runId) else emptyList()
        }
        val (result, forgotten) = reverted
        val also = forgotten.joinToString("") { "; $it" }
        decide("reverted", "run $runId", "${result.writes} writes undone on ${result.rows.size} rows$also (by ${author.actor})", author, Log.Level.INFO)
        return result.writes
    }

    override suspend fun state(key: String): String? = read { connection ->
        connection.query("SELECT value FROM memory_state WHERE key = ?", key) { getString(1) }.singleOrNull()
    }

    override suspend fun setState(key: String, value: String) {
        journal.write(WRITE_TIMEOUT_MS) { connection ->
            connection.update("INSERT OR REPLACE INTO memory_state VALUES (?, ?, ?)", key, value, clock.instant())
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────────────────

    override suspend fun row(ref: Ref): MemoryRow? = read { it.readRow(ref) }

    override suspend fun memory(ref: Ref): MemoryEntry? = if (ref.kind == RefKind.MEMORY) row(ref) as MemoryEntry? else null

    override suspend fun canon(ref: Ref): CanonEntry? = if (ref.kind == RefKind.CANON) row(ref) as CanonEntry? else null

    override suspend fun canon(filter: CanonFilter): List<CanonEntry> = read { connection ->
        val names = Names.of(connection)
        val status = when (filter.open) {
            true -> " AND status IN ('planned', 'active')"
            false -> " AND status NOT IN ('planned', 'active')"
            null -> ""
        }
        connection.query(
            "SELECT $CANON_COLUMNS FROM canon WHERE NOT archived AND (len(?) = 0 OR list_contains(?, kind))$status " +
                "AND (CAST(? AS UUID) IS NULL OR len(players) = 0 OR list_contains(players, ?)) " +
                "ORDER BY due_at ASC NULLS LAST, importance DESC, created_at DESC, id DESC LIMIT ?",
            stringArray(filter.kinds.map { it.id }), stringArray(filter.kinds.map { it.id }), filter.player, filter.player, filter.limit,
        ) { canonEntry(names) }
    }

    override suspend fun interactions(player: UUID, limit: Int, marked: Boolean): List<Interaction> = read { connection ->
        val only = if (marked) " AND (abs(valence) = 3 OR disrespect)" else ""
        connection.query(
            "SELECT $INTERACTION_COLUMNS FROM interactions WHERE player_uuid = ? AND NOT archived$only ORDER BY ts DESC, id DESC LIMIT ?",
            player, limit,
        ) { interaction() }
    }

    override suspend fun traits(player: UUID, includeRetired: Boolean): List<Trait> = read { connection ->
        connection.traits("player_uuid = ?" + if (includeRetired) "" else " AND NOT retired", player)
    }

    override suspend fun skills(player: UUID): List<SkillScore> = read { it.skills("player_uuid = ?", player) }

    override suspend fun relations(player: UUID): List<Relation> = read { it.relations("player_uuid = ?", player) }

    override suspend fun doc(key: String, version: Int?): MemoryDoc? = read { MemoryDocs.read(it, key, version) }

    override suspend fun docVersions(key: String): List<MemoryDoc> = read { MemoryDocs.versions(it, key) }

    override suspend fun chronicle(level: ChronicleLevel, limit: Int): List<ChronicleEntry> = read { connection ->
        connection.query(
            "SELECT $CHRONICLE_COLUMNS FROM chronicle WHERE level = ? AND superseded_by IS NULL ORDER BY period_to DESC, id DESC LIMIT ?",
            level.id, limit,
        ) { chronicleEntry() }
    }

    override suspend fun openNotes(): List<Note> = read { connection ->
        connection.notes(
            "SELECT $NOTE_COLUMNS FROM notes WHERE status <> 'done' AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at, id",
            clock.instant(),
        )
    }

    override suspend fun log(ref: Ref): List<MemoryLogRow> = read { MemoryLog.of(it, ref) }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun <T> read(query: (Connection) -> T): T = journal.read(READ_TIMEOUT_MS, query)

    /** Runs [block] as one write job; a [MemoryRejected] is recorded (decision `rejected`, [what]) and rethrown. */
    private suspend fun <T> write(what: String, author: Author, block: (Job) -> T): T = try {
        journal.write(WRITE_TIMEOUT_MS) { connection -> block(Job(connection, author, clock.instant())) }
    } catch (e: MemoryRejected) {
        decide("rejected", what, "${e.message} (by ${author.actor})", author, Log.Level.INFO)
        throw e
    }

    private fun <T : MemoryRow> recordAdd(added: Added<T>, what: String, author: Author, texts: List<String>) {
        if (added is Added.Duplicate) {
            decide("duplicate", "${added.row.ref}", "$what by ${author.actor}: the same is recorded already", author, Log.Level.DEBUG)
        } else {
            recordWrite("${added.row.ref}", what, author, texts)
        }
    }

    /** Decision `written`, and `voice_warning` when a text a model wrote sounds grandiose or dark (M3 §12.2). */
    private fun recordWrite(subject: String, what: String, author: Author, texts: List<String> = emptyList()) {
        decide("written", subject, "$what by ${author.actor}", author, Log.Level.DEBUG)
        val words = texts.flatMap { VoiceGuard.warnings(it) }.distinct()
        if (words.isNotEmpty()) decide("voice_warning", subject, "${words.joinToString(", ")} (by ${author.actor})", author, Log.Level.INFO)
    }

    private fun decide(decision: String, subject: String, reason: String, author: Author, level: Log.Level) {
        decisions.record(
            log, DecisionRecord.Kind.MEMORY, decision, subject, reason,
            conversationId = author.conversationId, turnId = author.turnId, level = level,
        )
    }

    companion object {
        /** How long a write job may wait for the writer and run. */
        const val WRITE_TIMEOUT_MS = 10_000L

        /** How long a read may wait for a connection and run. */
        const val READ_TIMEOUT_MS = 5_000L

        /** Players a row may concern. */
        const val MAX_PLAYERS = 8

        /** Tags and game ids of a memory. */
        const val MAX_TAGS = 8
        const val MAX_ENTITIES = 8

        /** Journal evidence of a row (`e…` and `t…` together). */
        const val MAX_EVIDENCE = 20

        /** Evidence of a trait, a skill, a relation (M3 §8.3). */
        const val MAX_TRAIT_EVIDENCE = 10
        const val MAX_SHEET_EVIDENCE = 10

        /** Evidence of a document, refs of a note, entries a chronicle entry covers, memories merged at once. */
        const val MAX_DOC_EVIDENCE = 20
        const val MAX_NOTE_REFS = 8
        const val MAX_COVERS = 40
        const val MAX_MERGED = 10

        /** Reasons of operators and the Dreamer (`memory_log.reason`). */
        const val MAX_REASON = 300

        private val ARCHIVABLE = setOf(RefKind.MEMORY, RefKind.CANON, RefKind.INTERACTION)

        /** The authors whose texts are a model's: the passages they quote are bounded ([VoiceGuard.MAX_QUOTED]). */
        private val MODELS = setOf(Source.DIRECTOR, Source.CHRONICLER, Source.DREAMER)

        /**
         * The kinds of canon the Narrator says to the players: what the Chronicler records of them quotes the
         * Narrator's line it cites (decision 8). The Dreamer never records them.
         */
        val SAID_KINDS = setOf(
            CanonKind.PROMISE, CanonKind.THREAT, CanonKind.CHALLENGE, CanonKind.PROPHECY, CanonKind.LAW,
            CanonKind.NICKNAME, CanonKind.TITLE, CanonKind.PLACE,
        )

        /** What the Dreamer may add to the canon: an arc it plans, lore. Its running gags only change freshness ([setGag]). */
        val DREAMER_CANON = setOf(CanonKind.ARC, CanonKind.LORE)
    }
}

private fun reject(message: String): Nothing = throw MemoryRejected(message)

/** Someone's exact words: exempt from [VoiceGuard], but bounded. */
private fun quote(quote: String?, max: Int) {
    if (quote == null) return
    if (quote.isBlank()) reject("quote must not be empty: leave it out")
    val length = quote.codePointCount(0, quote.length)
    if (length > max) reject("quote is longer than $max characters (got $length): shorten it")
    if ('<' in quote || '>' in quote) reject("quote must not contain < or >")
}

private fun within(value: Int, range: IntRange, field: String) {
    if (value !in range) reject("$field must be between ${range.first} and ${range.last} (got $value)")
}

private fun reason(reason: String) {
    if (reason.length > DuckMemoryStore.MAX_REASON) reject("reason is longer than ${DuckMemoryStore.MAX_REASON} characters: shorten it")
}

private fun tags(tags: List<String>) {
    if (tags.size > DuckMemoryStore.MAX_TAGS) reject("tags: ${DuckMemoryStore.MAX_TAGS} at most")
    tags.firstOrNull { !MemoryTags.isValid(it) }?.let {
        reject("tags: \"$it\" is not a tag; use ${MemoryTags.ALL.joinToString(", ")}, or gag:<slug>, arc:C-<id>")
    }
}

private fun entities(entities: List<String>) {
    if (entities.size > DuckMemoryStore.MAX_ENTITIES) reject("entities: ${DuckMemoryStore.MAX_ENTITIES} at most")
    entities.firstOrNull { !ENTITY_ID.matches(it) }?.let { reject("entities: \"$it\" is not a game id (minecraft:lava) nor a ref (C-45)") }
}

/** A game id or a ref in a memory's `entities`: `minecraft:trial_chambers`, `C-45`. */
private val ENTITY_ID = Regex("[A-Za-z0-9_:./-]{1,64}")

/**
 * Whether a nickname or title draft gives the same name as [active]: the same name between « » in both
 * statements, else the same quote, else the same statement, normalized. The name comes first because the
 * quotes differ for one name: the Chronicler copies a few words around it (« Salut, le Plongeur ! »), the
 * Director often the name alone.
 */
private fun sameName(active: CanonEntry, draft: CanonDraft): Boolean {
    val name = named(draft.statement)
    val activeName = named(active.statement)
    if (name != null && activeName != null) return name == activeName
    val quote = draft.quote?.let(Quotes::normalize)
    val activeQuote = active.quote?.let(Quotes::normalize)
    return if (quote != null && activeQuote != null) quote == activeQuote else Quotes.normalize(active.statement) == Quotes.normalize(draft.statement)
}

/** The first name between « » of [statement], normalized; null when it has none. */
private fun named(statement: String): String? = NAMED.find(statement)?.groupValues?.get(1)?.let(Quotes::normalize)?.takeIf { it.isNotEmpty() }

private val NAMED = Regex("«([^«»]+)»")

/** [word] with its indefinite article: `a promise`, `an arc`. */
private fun an(word: String): String = if (word.first() in "aeiou") "an $word" else "a $word"

/** Whether [row] (a memory or an interaction) is about [player]. */
private fun concerns(row: MemoryRow?, player: UUID): Boolean = when (row) {
    is MemoryEntry -> row.players.any { it.uuid == player }
    is Interaction -> row.player.uuid == player
    else -> false
}
