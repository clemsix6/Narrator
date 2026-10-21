package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.MemoryCommands
import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.memory.CANON_COLUMNS
import io.github.clemsix6.narrator2.memory.CHRONICLE_COLUMNS
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.IDEA_COLUMNS
import io.github.clemsix6.narrator2.memory.INTERACTION_COLUMNS
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MEMORY_COLUMNS
import io.github.clemsix6.narrator2.memory.MemoryDoc
import io.github.clemsix6.narrator2.memory.MemoryDocs
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryLog
import io.github.clemsix6.narrator2.memory.MemoryLogRow
import io.github.clemsix6.narrator2.memory.MemoryRow
import io.github.clemsix6.narrator2.memory.NOTE_COLUMNS
import io.github.clemsix6.narrator2.memory.Names
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.canonEntry
import io.github.clemsix6.narrator2.memory.chronicleEntry
import io.github.clemsix6.narrator2.memory.ideas
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.instantOrNull
import io.github.clemsix6.narrator2.memory.interaction
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.memoryEntry
import io.github.clemsix6.narrator2.memory.notes
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.readRow
import io.github.clemsix6.narrator2.memory.strings
import io.github.clemsix6.narrator2.web.BadRequest
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Link
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * `/memory` (M6 §8.4): what the Narrator remembers, read from the memory's tables in one read of the Journal per page,
 * each part a list of [Paging] rows with its filters:
 * - `/memory` and `/memory/canon`: the canon, open and closed; `/memory/memories`, `/memory/interactions`,
 *   `/memory/notes`, `/memory/chronicle`;
 * - `/memory/ideas`: the storyteller's ideas and what became of them (dealt, dropped, expired, and why);
 * - `/memory/docs`: the versioned documents (Grimoire, review, sheet sections); `/memory/docs/<key>[/<version>]` one
 *   version and the list of the others;
 * - `/memory/jobs`: the memory's jobs (`memory_runs`);
 * - `/memory/<ref>`: one row (`C-45`, `P-41`…), where it comes from, its audit (`memory_log`) and its recalls.
 *
 * The tables stay readable when the memory is off (they are the Journal's): the page says it is off and shows them.
 * Viewing records nothing: an operator is not the Narrator.
 */
object MemoryPage : WebPage {
    override val root = "memory"

    override val label = "Memory"

    /** The parts of `/memory`, each a path segment (`/memory/ideas`): never a ref, which holds a number. */
    private enum class Part(val title: String) {
        CANON("Canon"), MEMORIES("Memories"), INTERACTIONS("Interactions"), NOTES("Notes"), IDEAS("Ideas"),
        CHRONICLE("Chronicle"), DOCS("Documents"), JOBS("Jobs");

        val id: String get() = name.lowercase()
        val target: Link get() = link(root, id)
    }

    override fun render(request: WebRequest, context: PageContext): Page? {
        val path = request.path
        if (path.isEmpty()) return list(Part.CANON, request, context)
        val part = Part.entries.firstOrNull { it.id == path.first() }
        return when {
            part == Part.DOCS && path.size > 1 -> if (path.size <= 3) document(path[1], path.getOrNull(2), context) else null
            part != null -> if (path.size == 1) list(part, request, context) else null
            path.size == 1 -> Ref.parse(path.first())?.let { one(it, context) }
            else -> null
        }
    }

    // ── The lists ───────────────────────────────────────────────────────────────────────────────────

    private fun list(part: Part, request: WebRequest, context: PageContext): Page {
        val paging = Paging.of(request)
        val body: Html.() -> Unit = when (part) {
            Part.CANON -> canon(request, context, paging)
            Part.MEMORIES -> memories(request, context, paging)
            Part.INTERACTIONS -> interactions(request, context, paging)
            Part.NOTES -> notes(request, context, paging)
            Part.IDEAS -> ideas(request, context, paging)
            Part.CHRONICLE -> chronicle(request, context, paging)
            Part.DOCS -> documents(request, context, paging)
            Part.JOBS -> jobs(request, context, paging)
        }
        return Page("Memory · ${part.title}") {
            frame(context, part.title)
            body()
        }
    }

    /** The memory's state, then the tabs of its parts. */
    private fun Html.frame(context: PageContext, current: String) {
        val memory = context.services.memory
        if (!memory.available) off("The memory", "${memory.unavailableReason}; its tables are shown as the Journal keeps them")
        tabs(current, Part.entries.map { it.title to it.target })
    }

    private fun canon(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        when (request.choice("status", OPEN_CLOSED)) {
            OPEN -> where.add("status IN ('planned', 'active')")
            CLOSED -> where.add("status NOT IN ('planned', 'active')")
        }
        request.choice<CanonKind>("kind")?.let { where.add("kind = ?", it.id) }
        val (rows, more) = paging.rows(
            context.read { c ->
                val names = Names.of(c)
                c.query(
                    "SELECT $CANON_COLUMNS FROM canon WHERE $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { canonEntry(names) }
            },
        )
        return {
            filters(Part.CANON.target, request, listOf(Filter.Choice("status", "Status", OPEN_CLOSED), Filter.Choice("kind", "Kind", CanonKind.entries.map { it.id })))
            if (rows.isEmpty()) p("No canon entry.", "muted")
            else table(listOf("Ref", "Kind", "Status", "To", "#★", "Statement", "Due", "Source")) {
                rows.forEach { entry ->
                    row(if (entry.archived || !entry.status.open) "muted" else null) {
                        cell { refLink(entry.ref) }
                        cell(entry.kind.id + (entry.mechanic?.let { " (${it.id})" }.orEmpty()))
                        cell(entry.status.id + (entry.resolvedAt?.let { " ${context.time(it)}" }.orEmpty()) + if (entry.archived) " · archived" else "")
                        cell { playerLinks(entry.players) }
                        number(entry.importance)
                        cell(short(entry.statement))
                        cell(entry.dueAt?.let { context.time(it) })
                        cell(entry.source.id)
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    private fun memories(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice<MemoryKind>("kind")?.let { where.add("kind = ?", it.id) }
        when (request.choice("state", KEPT_ARCHIVED)) {
            KEPT -> where.add("NOT archived")
            ARCHIVED -> where.add("archived")
        }
        val (rows, more) = paging.rows(
            context.read { c ->
                val names = Names.of(c)
                c.query(
                    "SELECT $MEMORY_COLUMNS FROM memories WHERE $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { memoryEntry(names) }
            },
        )
        return {
            filters(Part.MEMORIES.target, request, listOf(Filter.Choice("kind", "Kind", MemoryKind.entries.map { it.id }), Filter.Choice("state", "State", KEPT_ARCHIVED)))
            if (rows.isEmpty()) p("No memory.", "muted")
            else table(listOf("Ref", "When", "Kind", "Title", "Players", "#★", "#Valence", "Tags", "Source")) {
                rows.forEach { memory ->
                    row(if (memory.archived) "muted" else null) {
                        cell { refLink(memory.ref) }
                        cell(context.time(memory.occurredFrom))
                        cell(memory.kind.id + if (memory.archived) " · archived" else "")
                        cell(short(memory.title))
                        cell { playerLinks(memory.players, "the server") }
                        number(memory.importance)
                        number(signed(memory.valence))
                        cell(memory.tags.joinToString())
                        cell(memory.source.id)
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    private fun interactions(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice<InteractionKind>("kind")?.let { where.add("kind = ?", it.id) }
        request.param("player")?.let { where.add("(lower(player) = lower(?) OR player_uuid IN (SELECT player_uuid FROM players WHERE lower(name) = lower(?)))", it, it) }
        val (rows, more) = paging.rows(
            context.read { c ->
                c.query(
                    "SELECT $INTERACTION_COLUMNS FROM interactions WHERE $where ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { interaction() }
            },
        )
        return {
            filters(Part.INTERACTIONS.target, request, listOf(Filter.Choice("kind", "Kind", InteractionKind.entries.map { it.id }), Filter.Field("player", "Player")))
            if (rows.isEmpty()) p("No interaction.", "muted")
            else table(listOf("Ref", "When", "Player", "Kind", "#Valence", "Summary", "Quote", "Counts")) {
                rows.forEach { interaction ->
                    row(if (interaction.archived) "muted" else if (interaction.disrespect) "warning" else null) {
                        cell { refLink(interaction.ref) }
                        cell(context.time(interaction.ts))
                        cell { playerLinks(listOf(interaction.player)) }
                        cell(interaction.kind.id + if (interaction.archived) " · archived" else "")
                        number(signed(interaction.valence))
                        cell(short(interaction.summary))
                        cell(short(interaction.quote))
                        cell(listOfNotNull("favor".takeIf { interaction.counts }, "disrespect".takeIf { interaction.disrespect }).joinToString())
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    private fun notes(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice<NoteStatus>("status")?.let { where.add("status = ?", it.id) }
        val (rows, more) = paging.rows(
            context.read { c -> c.notes("SELECT $NOTE_COLUMNS FROM notes WHERE $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) },
        )
        return {
            filters(Part.NOTES.target, request, listOf(Filter.Choice("status", "Status", NoteStatus.entries.map { it.id })))
            if (rows.isEmpty()) p("No note for the Director.", "muted")
            else table(listOf("Ref", "Created", "Kind", "Status", "#Shown", "Text", "About")) {
                rows.forEach { note ->
                    row(if (note.status == NoteStatus.DONE) "muted" else null) {
                        cell { refLink(note.ref) }
                        cell(context.time(note.createdAt))
                        cell(note.kind.id)
                        cell(note.status.id)
                        number(note.shownCount)
                        cell(short(note.text))
                        cell { about(note) }
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    private fun ideas(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice<IdeaStatus>("status")?.let { where.add("status = ?", it.id) }
        request.choice<IdeaKind>("kind")?.let { where.add("kind = ?", it.id) }
        val (rows, more) = paging.rows(
            context.read { c -> c.ideas("SELECT $IDEA_COLUMNS FROM ideas WHERE $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", *where.values, paging.limit, paging.offset) },
        )
        val world = worldPlay(context)
        return {
            filters(Part.IDEAS.target, request, listOf(Filter.Choice("status", "Status", IdeaStatus.entries.map { it.id }), Filter.Choice("kind", "Kind", IdeaKind.entries.map { it.id })))
            if (rows.isEmpty()) p("No idea: the storyteller writes them in the dream's programme, or when the Director asks.", "muted")
            else table(listOf("Ref", "Created", "Kind", "For", "Title", "Notes", "Became", "#Dealt")) {
                rows.forEach { idea ->
                    row(if (idea.status == IdeaStatus.OPEN) null else "muted") {
                        cell { refLink(idea.ref) }
                        cell(context.time(idea.createdAt))
                        cell(idea.kind.id + (idea.module?.let { " $it" }.orEmpty()))
                        cell { playerLinks(idea.players, "whoever is online") }
                        cell(short(idea.title))
                        cell("n${idea.novelty} f${idea.funScore} e${idea.fairness}")
                        cell(became(idea, world, context))
                        cell("num") {
                            text(idea.dealt)
                            idea.lastDealtAt?.let { small(" last ${context.time(it)}") }
                        }
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    /** `open · 30h00m of play left`, `dropped 21:40: replaced by the season P-52`, `expired 10-20 23:00: its hours…`. */
    private fun became(idea: Idea, world: Long?, context: PageContext): String = when (idea.status) {
        IdeaStatus.OPEN -> "open" + (world?.let { " · " + playLeft(idea.expiresPlaySeconds - it) }.orEmpty())
        else -> idea.status.id + (idea.closedAt?.let { " ${context.time(it)}" }.orEmpty()) + (idea.closeReason?.let { ": $it" }.orEmpty())
    }

    /** The world's play clock now; null when the memory is off (its clock stands still). */
    private fun worldPlay(context: PageContext): Long? =
        context.services.memory.takeIf { it.available }?.clock?.worldPlaySeconds(context.now)

    private fun playLeft(seconds: Long): String = if (seconds > 0) "${played(seconds)} of play left" else "due, the hygiene closes it"

    private fun chronicle(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice<ChronicleLevel>("level")?.let { where.add("level = ?", it.id) }
        val (rows, more) = paging.rows(
            context.read { c ->
                c.query(
                    "SELECT $CHRONICLE_COLUMNS FROM chronicle WHERE $where ORDER BY period_to DESC, id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { chronicleEntry() }
            },
        )
        return {
            filters(Part.CHRONICLE.target, request, listOf(Filter.Choice("level", "Level", ChronicleLevel.entries.map { it.id })))
            if (rows.isEmpty()) p("No chronicle entry.", "muted")
            else table(listOf("Ref", "Level", "Period", "Title", "Summary", "State")) {
                rows.forEach { entry ->
                    row(if (entry.supersededBy != null) "muted" else null) {
                        cell { refLink(entry.ref) }
                        cell(entry.level.id)
                        cell("${context.time(entry.from)} → ${context.time(entry.to)}")
                        cell(short(entry.title))
                        cell(short(entry.summary, SUMMARY_CHARS))
                        cell {
                            when {
                                entry.supersededBy != null -> { text("superseded by "); refLink(entry.supersededBy) }
                                entry.open -> text("open, rewritten as it goes")
                                else -> text("closed")
                            }
                        }
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    // ── Documents ───────────────────────────────────────────────────────────────────────────────────

    /** One versioned document: its key, its latest version, how many it has, when the latest was written. */
    private class DocLine(val key: String, val latest: Int, val versions: Int, val writtenAt: Instant)

    private fun documents(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val (lines, names) = context.read { c ->
            c.query(
                "SELECT key, max(version), count(*), max(written_at) FROM memory_docs GROUP BY key ORDER BY max(written_at) DESC, key LIMIT ? OFFSET ?",
                paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
            ) { DocLine(getString(1), getInt(2), getInt(3), instant(4)) } to Names.of(c)
        }
        val (rows, more) = paging.rows(lines)
        return {
            p("The Grimoire, the Dreamer's review of the Narrator, and the written sections of each player's sheet: every version is kept.", "muted")
            if (rows.isEmpty()) p("No document yet: the dreams write them.", "muted")
            else table(listOf("Document", "#Version", "#Versions", "Written")) {
                rows.forEach { doc ->
                    row {
                        cell { a(link(root, Part.DOCS.id, doc.key), docName(doc.key, names)) }
                        number("v${doc.latest}")
                        number(doc.versions)
                        cell(context.time(doc.writtenAt))
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    /** A version of a document, the latest when [typedVersion] is null, and the list of its versions. */
    private fun document(key: String, typedVersion: String?, context: PageContext): Page? {
        val version = typedVersion?.let { it.removePrefix("v").toIntOrNull() ?: return null }
        if (DocKey.parse(key) == null) return null
        val read = context.read { c ->
            val doc = MemoryDocs.read(c, key, version) ?: return@read null
            val versions = c.query(
                "SELECT version, written_at, source, length(content), restored_from FROM memory_docs WHERE key = ? ORDER BY version DESC LIMIT ?",
                key, DOC_VERSIONS, timeoutSeconds = QUERY_SECONDS,
            ) { Version(getInt(1), instant(2), getString(3), getInt(4), longOrNull(5)?.toInt()) }
            Triple(doc, versions, Names.of(c))
        } ?: return null
        val (doc, versions, names) = read
        val name = docName(key, names)
        return Page("$name v${doc.version}") {
            frame(context, Part.DOCS.title)
            dl {
                item("Written", "${context.time(doc.writtenAt)} by ${doc.source.id}" + (doc.restoredFrom?.let { " (restored from v$it)" }.orEmpty()))
                item("Length", "${doc.content.length} characters")
                if (doc.evidence.isNotEmpty()) item("Evidence") { refs(doc.evidence) }
            }
            pre(doc.content)
            h2("Versions")
            table(listOf("#Version", "Written", "Source", "#Characters", "Restored from")) {
                versions.forEach { v ->
                    row(if (v.version == doc.version) "warning" else null) {
                        cell("num") { a(link(root, Part.DOCS.id, key, v.version), "v${v.version}") }
                        cell(context.time(v.writtenAt))
                        cell(v.source)
                        number(v.characters)
                        cell(v.restoredFrom?.let { "v$it" })
                    }
                }
            }
            if (versions.size >= DOC_VERSIONS) p("The latest $DOC_VERSIONS versions; older ones by their number in the address.", "muted")
        }
    }

    private class Version(val version: Int, val writtenAt: Instant, val source: String, val characters: Int, val restoredFrom: Int?)

    /** `grimoire`, `review`, `sheet of Bartok: recap`. */
    private fun docName(key: String, names: Names): String = when (val doc = DocKey.parse(key)) {
        is DocKey.Sheet -> "sheet of ${names.ref(doc.player).name}: ${doc.section.id}"
        else -> key
    }

    // ── Jobs ────────────────────────────────────────────────────────────────────────────────────────

    /** A row of `memory_runs`. */
    private class Run(
        val runId: Long, val kind: String, val trigger: String, val status: String, val startedAt: Instant, val endedAt: Instant?,
        val windowFrom: Instant?, val windowTo: Instant?, val conversations: List<String>, val writes: Int, val rejected: Int,
        val inputTokens: Long?, val outputTokens: Long?, val report: String?, val error: String?,
    )

    private fun jobs(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        request.choice("kind", JOB_KINDS)?.let { where.add("kind = ?", it) }
        request.choice<RunStatus>("status")?.let { where.add("status = ?", it.id) }
        val (rows, more) = paging.rows(
            context.read { c ->
                c.query(
                    "SELECT run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, conversations, writes, rejected, " +
                        "input_tokens, output_tokens, report, error FROM memory_runs WHERE $where ORDER BY started_at DESC, run_id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) {
                    Run(
                        getLong(1), getString(2), getString(3), getString(4), instant(5), instantOrNull(6), instantOrNull(7), instantOrNull(8),
                        strings(9), getInt(10), getInt(11), longOrNull(12), longOrNull(13), getString(14), getString(15),
                    )
                }
            },
        )
        return {
            p(MemoryCommands.statusLine(context.services.memory, TraceText(context.zone, context.now)), "muted")
            filters(Part.JOBS.target, request, listOf(Filter.Choice("kind", "Kind", JOB_KINDS), Filter.Choice("status", "Status", RunStatus.entries.map { it.id })))
            if (rows.isEmpty()) p("No job yet.", "muted")
            else table(listOf("#Run", "Kind", "Trigger", "Status", "Started", "Took", "Window", "#Writes", "#Refused", "#Tokens", "Conversations", "Report")) {
                rows.forEach { run ->
                    row(if (run.status == RunStatus.FAILED.id || run.status == RunStatus.INTERRUPTED.id) "failure" else null) {
                        number(run.runId)
                        cell(run.kind)
                        cell(run.trigger)
                        cell(run.status)
                        cell(context.time(run.startedAt))
                        cell(run.endedAt?.let { TraceText.duration(it.toEpochMilli() - run.startedAt.toEpochMilli()) })
                        cell(run.windowFrom?.let { from -> "${context.time(from)} → ${run.windowTo?.let { context.time(it) } ?: "…"}" })
                        number(run.writes)
                        number(run.rejected)
                        number(if (run.inputTokens == null && run.outputTokens == null) null else "${TraceText.tokens(run.inputTokens ?: 0)} / ${TraceText.tokens(run.outputTokens ?: 0)}")
                        cell {
                            run.conversations.forEachIndexed { i, id ->
                                if (i > 0) br()
                                a(link("conversations", id), id)
                            }
                        }
                        cell(short(run.error ?: run.report))
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    // ── One row ─────────────────────────────────────────────────────────────────────────────────────

    /** What `/memory/<ref>` reads: the row, its audit, how often it was shown, and the recalls. */
    private class RowView(val row: MemoryRow, val history: List<MemoryLogRow>, val recalls: List<Recall>, val recalled: Long, val names: Names)

    private class Recall(val ts: Instant, val channel: String, val conversationId: String?, val turnId: Long?)

    private fun one(ref: Ref, context: PageContext): Page? {
        val view = context.read { c ->
            val row = c.readRow(ref) ?: return@read null
            val recalls = c.query(
                "SELECT ts, channel, conversation_id, turn_id FROM recalls WHERE ref_id = ? ORDER BY ts DESC LIMIT ?",
                ref.id, RECALLS, timeoutSeconds = QUERY_SECONDS,
            ) { Recall(instant(1), getString(2), getString(3), longOrNull(4)) }
            val count = c.query("SELECT count(*) FROM recalls WHERE ref_id = ?", ref.id, timeoutSeconds = QUERY_SECONDS) { getLong(1) }.single()
            RowView(row, MemoryLog.of(c, ref), recalls, count, Names.of(c))
        } ?: return null
        val world = worldPlay(context)
        return Page(title(view.row)) {
            frame(context, "")
            when (val row = view.row) {
                is MemoryEntry -> memory(row, context)
                is CanonEntry -> canon(row, context)
                is Interaction -> interaction(row, context)
                is ChronicleEntry -> chronicle(row, context)
                is Note -> note(row, context)
                is Idea -> idea(row, world, context)
            }
            provenance(view.history)
            history(view.history, context)
            recalls(view, context)
        }
    }

    private fun title(row: MemoryRow): String = when (row) {
        is MemoryEntry -> "${row.ref} · memory ${row.kind.id}"
        is CanonEntry -> "${row.ref} · canon ${row.kind.id} · ${row.status.id}"
        is Interaction -> "${row.ref} · interaction ${row.kind.id} · ${row.player.name}"
        is ChronicleEntry -> "${row.ref} · chronicle ${row.level.id}"
        is Note -> "${row.ref} · note ${row.kind.id} · ${row.status.id}"
        is Idea -> "${row.ref} · idea ${row.kind.id} · ${row.status.id}"
    }

    private fun Html.memory(row: MemoryEntry, context: PageContext) = dl {
        item("Title", row.title)
        item("Content", row.content)
        item("When", "${context.time(row.occurredFrom)} → ${context.time(row.occurredTo)} (written ${context.time(row.createdAt)})")
        item("Players") { playerLinks(row.players, "the whole server") }
        item("Importance", "★${row.importance} · valence ${signed(row.valence)}")
        item("Tags", row.tags.joinToString().ifEmpty { "none" })
        item("Entities", row.entities.joinToString().ifEmpty { "none" })
        row.place?.let { item("Place", place(it)) }
        item("Strength", "stability ${row.stabilityHours}h · reinforced at ${played(row.reinforcedPlaySeconds)} of world play")
        item("Recalled", "${row.recalls} ${TraceText.plural(row.recalls, "time")}" + (row.lastRecalledAt?.let { ", last ${context.time(it)}" }.orEmpty()))
        item("State") {
            text(if (row.archived) "archived" else "kept")
            row.mergedInto?.let { text(" · merged into "); refLink(it) }
        }
    }

    private fun Html.canon(row: CanonEntry, context: PageContext) = dl {
        item("Statement", row.statement)
        row.quote?.let { item("Said", "« $it »") }
        item("To") { playerLinks(row.players) }
        item("Status", row.status.id + (row.resolvedAt?.let { " ${context.time(it)}" }.orEmpty()) + if (row.archived) " · archived" else "")
        row.resolution?.let { item("Resolution", it) }
        if (row.winners.isNotEmpty()) item("Winners") { playerLinks(row.winners) }
        row.stakes?.let { item("Stakes", it) }
        item("Importance", "★${row.importance} · set ${context.time(row.createdAt)}")
        row.dueAt?.let { item("Due", context.time(it)) }
        row.mechanic?.let { item("Mechanic") { text("${it.id}, run by the engine · "); a(link("mechanics"), "mechanics") } }
        row.theme?.let { item("Theme", it) }
        if (row.uses > 0 || row.freshness != null) {
            item("Uses", "${row.uses}" + (row.lastUsedAt?.let { ", last ${context.time(it)}" }.orEmpty()) + (row.freshness?.let { " · ${it.id}" }.orEmpty()))
        }
        row.replaces?.let { item("Replaces") { refLink(it) } }
        row.place?.let { item("Place", place(it)) }
    }

    private fun Html.interaction(row: Interaction, context: PageContext) = dl {
        item("Player") { playerLinks(listOf(row.player)) }
        item("When", "${context.time(row.ts)} · at ${played(row.playedSeconds)} of the player's play")
        item("Kind", "${row.kind.id} · valence ${signed(row.valence)}")
        item("Summary", row.summary)
        row.quote?.let { item("Quote", "« $it »") }
        item("Counts", listOfNotNull("toward favor".takeIf { row.counts }, "a lack of respect".takeIf { row.disrespect }).joinToString(" · ").ifEmpty { "no" })
        row.canon?.let { item("Canon") { refLink(it) } }
        row.memory?.let { item("Memory") { refLink(it) } }
        if (row.toolCalls.isNotEmpty()) item("Tool calls") { toolCalls(row.toolCalls) }
        if (row.archived) item("State", "archived")
    }

    private fun Html.chronicle(row: ChronicleEntry, context: PageContext) = dl {
        item("Title", row.title)
        item("Period", "${context.time(row.from)} → ${context.time(row.to)}")
        item("Summary") { pre(row.summary) }
        if (row.covers.isNotEmpty()) item("Covers") { refs(row.covers) }
        row.conversationId?.let { id -> item("Chapter") { a(link("conversations", id), id) } }
        item("State") {
            when {
                row.supersededBy != null -> { text("superseded by "); refLink(row.supersededBy) }
                row.open -> text("open: rewritten as the period goes on")
                else -> text("closed")
            }
        }
    }

    private fun Html.note(row: Note, context: PageContext) = dl {
        item("Text", row.text)
        item("Created", "${context.time(row.createdAt)} · shown ${row.shownCount} ${TraceText.plural(row.shownCount, "time")}")
        if (row.players.isNotEmpty() || row.refs.isNotEmpty()) item("About") { about(row) }
    }

    private fun Html.idea(row: Idea, world: Long?, context: PageContext) = dl {
        item("Title", row.title)
        item("Pitch", row.pitch)
        item("Fits", row.fit)
        item("For") { playerLinks(row.players, "whoever is online") }
        row.register?.let { item("Register", it.id) }
        item("Notes", "novelty ${row.novelty} · fun ${row.funScore} · fairness ${row.fairness}")
        if (row.refs.isNotEmpty()) item("Builds on") { refs(row.refs) }
        row.module?.let { module -> item("Module") { a(link("forge", module), module) } }
        row.brief?.let { item("Brief") { pre(it) } }
        item("Created", context.time(row.createdAt))
        item("Dealt", "${row.dealt} ${TraceText.plural(row.dealt, "time")}" + (row.lastDealtAt?.let { ", last ${context.time(it)}" }.orEmpty()))
        item("Became", became(row, world, context))
    }

    /** `Source: chronicler · run 7 · turn #42` and the evidence, from the columns the audit last saw. */
    private fun Html.provenance(history: List<MemoryLogRow>) {
        val columns = history.lastOrNull()?.let { it.after ?: it.before } ?: return
        val events = columns.longs("evidence_events")
        val tools = columns.longs("evidence_tools")
        dl {
            item("Source") {
                text(columns.string("source") ?: "unknown")
                columns.long("run_id")?.let { run -> text(" · "); a(link(root, Part.JOBS.id), "run $run") }
                columns.long("turn_id")?.let { turn -> text(" · "); a(link("turns", turn), "turn #$turn") }
                columns.string("conversation_id")?.let { id -> text(" · "); a(link("conversations", id), id) }
            }
            if (events.isNotEmpty()) {
                item("Evidence") {
                    text(events.joinToString { "e$it" } + " · ")
                    a(link("sql", query = mapOf("q" to "SELECT * FROM timeline WHERE id IN (${events.joinToString()}) ORDER BY ts, id")), "read them")
                }
            }
            if (tools.isNotEmpty()) item("Tool calls") { toolCalls(tools) }
        }
    }

    /** The changes of the row, oldest first: who, in which job, which columns, why. */
    private fun Html.history(history: List<MemoryLogRow>, context: PageContext) {
        h2("History")
        if (history.isEmpty()) {
            p("No change recorded.", "muted")
            return
        }
        table(listOf("When", "Change", "By", "Job", "Columns", "Reason")) {
            history.takeLast(HISTORY).forEach { change ->
                row {
                    cell(context.time(change.ts))
                    cell(change.op.id)
                    cell(change.actor)
                    cell {
                        change.runId?.let { text("run $it ") }
                        change.turnId?.let { a(link("turns", it), "turn #$it") }
                    }
                    cell(changes(change.before, change.after))
                    cell(change.reason)
                }
            }
        }
        if (history.size > HISTORY) p("The latest $HISTORY of ${history.size} changes.", "muted")
    }

    private fun Html.recalls(view: RowView, context: PageContext) {
        h2("Shown to a model")
        if (view.recalled == 0L) {
            p("Never.", "muted")
            return
        }
        p("${view.recalled} ${TraceText.plural(view.recalled.toInt(), "time")}, the latest first:", "muted")
        table(listOf("When", "Channel", "Conversation", "#Turn")) {
            view.recalls.forEach { recall ->
                row {
                    cell(context.time(recall.ts))
                    cell(recall.channel)
                    cell { recall.conversationId?.let { a(link("conversations", it), it) } }
                    cell("num") { recall.turnId?.let { a(link("turns", it), "#$it") } }
                }
            }
        }
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────────

    private fun Html.refs(refs: List<Ref>) = refs.forEachIndexed { i, ref ->
        if (i > 0) text(", ")
        refLink(ref)
    }

    private fun Html.about(note: Note) {
        playerLinks(note.players, "")
        if (note.players.isNotEmpty() && note.refs.isNotEmpty()) text(" · ")
        refs(note.refs)
    }

    /** `t88, t90 · read them`: the tool calls, readable on the SQL page. */
    private fun Html.toolCalls(ids: List<Long>) {
        text(ids.joinToString { "t$it" } + " · ")
        a(link("sql", query = mapOf("q" to "SELECT id, ts, tool, args, status, result FROM tool_calls WHERE id IN (${ids.joinToString()}) ORDER BY ts, id")), "read them")
    }

    /** `status active → fulfilled, archived false → true`: the columns that changed. */
    private fun changes(before: JsonObject?, after: JsonObject?): String {
        if (before == null) return if (after == null) "" else "created"
        if (after == null) return "removed"
        return (before.keys + after.keys).filter { before[it] != after[it] }
            .joinToString(", ") { "$it ${value(before[it])} → ${value(after[it])}" }
    }

    private fun value(element: JsonElement?): String = when (element) {
        null, JsonNull -> "–"
        is JsonPrimitive -> TraceText.abbreviate(element.content, VALUE_CHARS)
        else -> TraceText.abbreviate(element.toString(), VALUE_CHARS)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.longs(key: String): List<Long> = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()

    private fun place(pos: Pos): String = "${pos.dim.removePrefix("minecraft:")} ${pos.x} ${pos.y} ${pos.z}"

    /** Play time: `45m00s`, `61h12m`. */
    private fun played(seconds: Long): String =
        if (seconds < 3_600) TraceText.duration(seconds * 1_000) else "${seconds / 3_600}h${(seconds % 3_600 / 60).toString().padStart(2, '0')}m"

    private const val OPEN = "open"
    private const val CLOSED = "closed"
    private val OPEN_CLOSED = listOf(OPEN, CLOSED)

    private const val KEPT = "kept"
    private const val ARCHIVED = "archived"
    private val KEPT_ARCHIVED = listOf(KEPT, ARCHIVED)

    /** `memory_runs.kind`: the jobs of [RunKind], and the stats. */
    private val JOB_KINDS = RunKind.entries.map { it.id } + "stats"

    /** Versions of a document listed, changes of a row and its recalls shown. */
    private const val DOC_VERSIONS = 50
    private const val HISTORY = 100
    private const val RECALLS = 20

    private const val SUMMARY_CHARS = 300
    private const val VALUE_CHARS = 60
}
