package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.EventFormatter
import io.github.clemsix6.narrator2.director.Salience
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.CANON_COLUMNS
import io.github.clemsix6.narrator2.memory.CHRONICLE_COLUMNS
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.INTERACTION_COLUMNS
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MEMORY_COLUMNS
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryRow
import io.github.clemsix6.narrator2.memory.NOTE_COLUMNS
import io.github.clemsix6.narrator2.memory.Names
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.canonEntry
import io.github.clemsix6.narrator2.memory.chronicleEntry
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.interaction
import io.github.clemsix6.narrator2.memory.intOrNull
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.memoryEntry
import io.github.clemsix6.narrator2.memory.notes
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.strings
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The exchange log (M3 §7.2): what the Chronicler reads of a window of the Journal, built by the code (it
 * never reads the transcript files). The complete record of what the players saw and of what the Narrator
 * did, one line per fact, oldest first, each with the ref it can be cited by (`e1234` an event or a line
 * of chat, `t88` a tool call):
 *
 * - the public chat and the prayers, with the triage's annotation (`addressed 0.92, hostile, provocation`:
 *   the Chronicler is not the one addressed); **never a private message** (another table, never read);
 * - every line of the Narrator (`chat` channels `narrator` and `operator`: lines, titles, books), whole;
 * - every tool call of the Director of effect `speech`, `world`, `task` or `engine` (the memory's tools
 *   included), with its targets and the first line of its result; punishments are marked `(hostile)`,
 *   benefits `(benefit)` ([Sanctions]). A successful call that said something (`say`, `show_title`,
 *   `actionbar`, `give_book`) is left out: its text is the Narrator's line. A call refused for its
 *   arguments is left out: nothing happened;
 * - the facts the salience finds notable (T18): deaths, PvP kills, critical health, joins and departures,
 *   milestone advancements, first visits and notable structures, griefs (counted as the salience does),
 *   rare ores (counted by run), explosions near players.
 *
 * [blocks] cuts the lines into `<exchange>` blocks of [MAX_CHARS] at most; [known] says what the memory
 * already holds about the window. The nap reads the chapter through [written], [shown] and [left] too.
 * Everything a player or a model wrote is escaped (`core/ModelText`); times are those of the Journal's
 * zone.
 */
internal class ExchangeLog(private val services: NarratorServices, private val sanctions: Sanctions) {
    /** One line of the log: when, its text, what it is, and the names of the players it involves. */
    data class Line(val at: Instant, val text: String, val kind: Kind, val players: Set<String>, val order: Long)

    enum class Kind {
        /** A message of a player: public chat or prayer. */
        CHAT,

        /** A line of the Narrator. */
        NARRATOR,

        /** A tool call of the Director. */
        TOOL,

        /** A notable fact of the game. */
        FACT,
    }

    /** One `<exchange>` block ([text], at most [MAX_CHARS]) over `]from, to]`, and the players of its lines. */
    data class Block(val text: String, val from: Instant, val to: Instant, val players: Set<String>)

    /** The zone of the Journal's times: `journal.timeZone`, else the engine's clock. */
    val zone: ZoneId
        get() = services.settings.get().config.journal.timeZone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: services.clock.zone

    /** `21:06`, in the Journal's zone. */
    fun time(at: Instant): String = time(at, zone)

    /**
     * The lines of the Director's [chapter] (its conversation id) in `]from, to]`, oldest first; the facts
     * only when [chat] is false. The caller passes a barrier first.
     */
    suspend fun lines(chapter: String, from: Instant, to: Instant, chat: Boolean = true): List<Line> {
        val zone = zone
        return services.journal.read(READ_TIMEOUT_MS) { connection ->
            val lines = mutableListOf<Line>()
            if (chat) {
                lines += chatLines(connection, from, to, zone)
                val marks = sanctions.find(connection, chapter, from, to).flatMap { s -> s.calls.map { it.id to s.kind } }.toMap()
                lines += toolLines(connection, chapter, from, to, zone, marks)
            }
            lines += factLines(connection, from, to, zone)
            lines.sortedWith(compareBy<Line>({ it.at }, { if (it.kind == Kind.TOOL) 1 else 0 }, { it.order }))
        }
    }

    /**
     * [lines] as `<exchange>` blocks of [MAX_CHARS] at most, in order: one when they fit, else as many as
     * needed, each saying which part it is and the time its lines cover. [chapter] names the chapter in
     * the header (its number); [now] is when the log is read.
     */
    fun blocks(lines: List<Line>, chapter: String, from: Instant, to: Instant, now: Instant): List<Block> {
        val parts = mutableListOf<List<Line>>()
        var current = mutableListOf<Line>()
        var size = 0
        for (line in lines) {
            if (current.isNotEmpty() && size + line.text.length + 1 > MAX_CHARS - HEADER_ROOM) {
                parts += current
                current = mutableListOf()
                size = 0
            }
            current += line
            size += line.text.length + 1
        }
        parts += current
        val zone = zone
        return parts.mapIndexed { index, part ->
            val partFrom = if (index == 0) from else parts[index - 1].last().at
            val partTo = if (index == parts.lastIndex) to else part.last().at
            val of = if (parts.size > 1) " part=\"${index + 1} of ${parts.size}\"" else ""
            val head = "<exchange chapter=\"${ModelText.attribute(chapter)}\" day=\"${DAY.withZone(zone).format(partFrom)}\" " +
                "from=\"${SECONDS.withZone(zone).format(partFrom)}\" to=\"${SECONDS.withZone(zone).format(partTo)}\" " +
                "now=\"${SECONDS.withZone(zone).format(now)}\"$of>"
            val text = (listOf(head) + part.map { it.text } + "</exchange>").joinToString("\n")
            Block(text, partFrom, partTo, part.flatMap { it.players }.toSet())
        }
    }

    /**
     * The `<known>` block of a window that starts at [from] (M3 §7.2): the [players] of the window, the open
     * canon that concerns them (or everyone, or written in the window), the open challenges, what the memory
     * got since [from] (until [now]), and the memories the Director of [chapter] was shown since [from].
     * [MAX_KNOWN_CHARS] at most.
     */
    suspend fun known(chapter: String, from: Instant, now: Instant, players: Set<String>): String {
        val zone = zone
        return services.journal.read(READ_TIMEOUT_MS) { connection ->
            val names = Names.of(connection)
            val refs = named(connection, players)
            val uuids = refs.map { it.uuid }.toSet()
            val open = connection.query(OPEN_CANON_SQL) { canonEntry(names) }
                .filter { it.players.isEmpty() || it.players.any { p -> p.uuid in uuids } || !it.createdAt.isBefore(from) }
            val (challenges, canon) = open.partition { it.kind == CanonKind.CHALLENGE }
            val noted = written(connection, names, from, now, zone).filterNot { it.open }
            val shown = shown(connection, chapter, from, now)
            val lines = buildList {
                add("<known>")
                add("Players: " + (refs.joinToString { ModelText.name(it.name) }.ifEmpty { "none" }))
                section("Open canon", canon.map { canonLine(it, from, zone) }, MAX_ITEMS)
                section("Open challenges", challenges.map { canonLine(it, from, zone) }, MAX_ITEMS)
                section("Already noted in this window", noted.map { it.line }, MAX_ITEMS)
                section("Memories shown to the Narrator in this window", shown.map { it.line(zone, withText = false) }, MAX_ITEMS)
                add("</known>")
            }
            fit(lines, MAX_KNOWN_CHARS)
        }
    }

    /**
     * What the memory got between [from] and [until] (its audit, `memory_log`): canon, interactions,
     * memories, notes and chronicle entries written, and the canon closed then; oldest first. [names] gives
     * the players' names.
     */
    fun written(connection: Connection, names: Names, from: Instant, until: Instant, zone: ZoneId): List<Written> {
        val inserted = connection.query(INSERTED_SQL, from, until) { getString(1) to getString(2).toLong() }
            .groupBy({ it.first }, { it.second })
        val rows = buildList<MemoryRow> {
            fun ids(kind: RefKind) = longArray(inserted[kind.table].orEmpty())
            addAll(connection.query("SELECT $CANON_COLUMNS FROM canon WHERE list_contains(?, id)", ids(RefKind.CANON)) { canonEntry(names) })
            addAll(connection.query("SELECT $INTERACTION_COLUMNS FROM interactions WHERE list_contains(?, id)", ids(RefKind.INTERACTION)) { interaction() })
            addAll(connection.query("SELECT $MEMORY_COLUMNS FROM memories WHERE list_contains(?, id)", ids(RefKind.MEMORY)) { memoryEntry(names) })
            addAll(connection.notes("SELECT $NOTE_COLUMNS FROM notes WHERE list_contains(?, id)", ids(RefKind.NOTE)))
            addAll(connection.query("SELECT $CHRONICLE_COLUMNS FROM chronicle WHERE list_contains(?, id)", ids(RefKind.CHRONICLE)) { chronicleEntry() })
        }
        val written = rows.map { row -> writtenOf(row, zone) }
        val closed = connection.query(CLOSED_CANON_SQL, from, until, from) { canonEntry(names) }
            .map { Written(it.resolvedAt!!, "${it.ref} ${canonWhat(it)} ${it.status.id} at ${time(it.resolvedAt, zone)}" + said(it.resolution), false) }
        return (written + closed).sortedBy { it.at }
    }

    /** The memories the Director of [chapter] was shown between [from] and [until], the first time each. */
    fun shown(connection: Connection, chapter: String, from: Instant, until: Instant): List<Shown> {
        val names = Names.of(connection)
        val seen = connection.query(SHOWN_SQL, chapter, from, until) { Triple(instant(1), getLong(2), getString(3) to longOrNull(4)) }
            .distinctBy { it.second }
        val memories = connection.query("SELECT $MEMORY_COLUMNS FROM memories WHERE list_contains(?, id)", longArray(seen.map { it.second })) {
            memoryEntry(names)
        }.associateBy { it.ref.id }
        return seen.mapNotNull { (at, id, how) ->
            memories[id]?.let { Shown(at, "${it.ref}", how.first, how.second, "${it.title}: ${it.content}") }
        }
    }

    /** The players whose session ended between [from] and [until], with what they did in it. */
    fun left(connection: Connection, from: Instant, until: Instant, zone: ZoneId): List<Left> = connection.query(
        LEFT_SQL, stringArray(Salience.NOTABLE_BLOCKS), from, until,
    ) {
        Left(
            player = PlayerRef(UUID.fromString(getString(1)), getString(2)),
            at = instant(3),
            line = buildString {
                append("${ModelText.name(getString(2))} left at ${time(instant(3), zone)} after ${ToolText.duration((intOrNull(4) ?: 0) * 1_000L)}: ")
                val counts = listOf(getLong(5) to "death", getLong(6) to "block broken", getLong(7) to "kill", getLong(8) to "advancement")
                append(counts.joinToString { (n, what) -> "$n ${if (n == 1L) what else what.replaceFirst(Regex("^(\\w+)"), "$1s")}" })
                val ores = strings(9)
                if (ores.isNotEmpty()) append(" (rare ores: ${ores.joinToString { EventFormatter.id(it) }})")
            },
        )
    }

    // ── Chat ────────────────────────────────────────────────────────────────────────────────────────

    private fun chatLines(connection: Connection, from: Instant, to: Instant, zone: ZoneId): List<Line> =
        connection.query(CHAT_SQL, from, to, from, to) {
            val id = getLong(1)
            val at = instant(2)
            val channel = getString(3)
            val player = getString(4)
            val recipients = strings(5)
            val text = getString(6)
            val head = "[${SECONDS.withZone(zone).format(at)}] e$id"
            when (channel) {
                "narrator", "operator" -> {
                    val by = getString(7)?.takeIf { channel == "operator" }?.let { " (said by operator ${ModelText.name(it)})" }.orEmpty()
                    val to = if (recipients.isEmpty()) "all" else recipients.joinToString { ModelText.name(it) }
                    Line(at, "$head NARRATOR$by→$to: ${ModelText.quote(text, NARRATOR_MAX)}", Kind.NARRATOR, recipients.toSet(), id)
                }
                else -> {
                    val pray = channel == "pray"
                    val note = annotation(pray, if (getString(8) == null) null else signal())
                    Line(at, "$head ${if (pray) "PRAY" else "CHAT"} ${ModelText.name(player)} $note: ${ModelText.quote(text, CHAT_MAX)}", Kind.CHAT, setOf(player), id)
                }
            }
        }

    private class Signal(val source: String, val toNarrator: Double?, val tone: String?, val toneConfidence: Double?, val intent: String?, val manipulation: Double?)

    private fun ResultSet.signal() = Signal(getString(8), doubleOrNull(9), getString(10), doubleOrNull(11), getString(12), doubleOrNull(13))

    /**
     * The triage of a message, for a reader who is not the one addressed: `(addressed 0.92, hostile,
     * provocation)`, `(between players, banter_between_players)`; the tone only when the classifier was
     * sure enough, `heuristic` when there was no classifier.
     */
    private fun annotation(pray: Boolean, signal: Signal?): String {
        if (signal == null) return if (pray) "(prayer, not triaged)" else "(not triaged)"
        val p = signal.toNarrator
        val parts = mutableListOf(
            when {
                pray -> "addressed" + (p?.let { " ${number(it)}" } ?: "")
                p == null -> "not triaged"
                p >= ReflexThresholds.ADDRESSED -> "addressed ${number(p)}"
                p >= ReflexThresholds.GRAY_ZONE -> "maybe addressed ${number(p)}"
                else -> "between players"
            },
        )
        val tone = signal.tone
        if (tone != null && tone != NO_TARGET && (signal.toneConfidence ?: 0.0) >= ReflexThresholds.TONE_SHOWN) parts += tone
        signal.intent?.let { parts += it }
        signal.manipulation?.takeIf { it >= ReflexThresholds.MANIPULATION }?.let { parts += "manipulation ${number(it)}" }
        if (signal.source == HeuristicReflexes.SOURCE) parts += "heuristic"
        return "(${parts.joinToString()})"
    }

    // ── Tool calls ──────────────────────────────────────────────────────────────────────────────────

    private fun toolLines(connection: Connection, chapter: String, from: Instant, to: Instant, zone: ZoneId, marks: Map<Long, InteractionKind>): List<Line> =
        connection.query(TOOLS_SQL, chapter, from, to) {
            val id = getLong(1)
            val at = instant(2)
            val tool = getString(3)
            val status = getString(4)
            val effect = getString(7)
            if (status == "ok" && tool in SAID_TOOLS) return@query null
            val targets = strings(6)
            val toWhom = if (targets.isNotEmpty() && (effect == "speech" || effect == "world")) " → ${targets.joinToString { ModelText.name(it) }}" else ""
            val mark = when (marks[id]) {
                null -> ""
                InteractionKind.PUNISHMENT -> " (hostile)"
                else -> " (benefit)"
            }
            val result = getString(5)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ModelText.escape(ModelText.cut(it, RESULT_MAX)) } ?: status
            Line(at, "[${SECONDS.withZone(zone).format(at)}] t$id $tool$toWhom$mark: $result", Kind.TOOL, targets.toSet(), id)
        }.filterNotNull()

    // ── Facts ───────────────────────────────────────────────────────────────────────────────────────

    private class Fact(
        val id: Long, val at: Instant, val type: String, val player: String?, val pos: String?,
        val subject: String?, val amount: Double?, val other: String?, val detail: JsonObject, val grief: Boolean, val owner: String,
    )

    private fun factLines(connection: Connection, from: Instant, to: Instant, zone: ZoneId): List<Line> {
        val facts = connection.query(
            FACTS_SQL, from, to, stringArray(Salience.NOTABLE_STRUCTURES), stringArray(Salience.NOTABLE_BLOCKS),
        ) {
            val dim = getString(6)
            Fact(
                id = getLong(1), at = instant(2), type = getString(3), player = getString(5),
                pos = dim?.let { "${ToolText.dimension(it)} ${getInt(7)} ${getInt(8)} ${getInt(9)}" },
                subject = getString(10), amount = doubleOrNull(11), other = getString(12),
                detail = getString(13)?.let { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() } ?: EMPTY,
                grief = getBoolean(15), owner = getString(14) ?: "an unknown player",
            )
        }
        val lines = mutableListOf<Line>()
        val mined = LinkedHashMap<Pair<String, String>, Run>()
        val griefs = LinkedHashMap<Pair<String, String>, Run>()
        for (fact in facts) {
            val who = fact.player?.let(ModelText::name) ?: "?"
            fun line(body: String, players: Set<String> = setOfNotNull(fact.player)) {
                lines += Line(fact.at, "[${SECONDS.withZone(zone).format(fact.at)}] e${fact.id} $body", Kind.FACT, players, fact.id)
            }
            val where = fact.pos?.let { " ($it)" }.orEmpty()
            when (fact.type) {
                "death" -> line(buildString {
                    append("DEATH $who: ${fact.subject?.let(EventFormatter::id) ?: "?"}")
                    fact.other?.let { append(" by ${ModelText.name(EventFormatter.id(it))}") }
                    fact.detail.text("weapon")?.let { append(" with ${EventFormatter.id(it)}") }
                    append(where)
                    append(if (fact.detail.flag("kept_inventory")) ", inventory kept" else ", inventory dropped")
                    if (fact.detail.flag("narrator")) append(", caused by the Narrator")
                })
                "kill" -> {
                    val victim = fact.detail.text("victim_name") ?: fact.subject?.let(EventFormatter::id) ?: "?"
                    line("PVP $who killed ${ModelText.name(victim)}${fact.other?.let { " with ${EventFormatter.id(it)}" }.orEmpty()}$where", setOfNotNull(fact.player, victim))
                }
                "health_low" -> line("LOW HEALTH $who: ${fact.amount?.let(::number) ?: "?"} hp$where")
                "join" -> line("JOIN $who${if (fact.detail.flag("first_time")) " (first time)" else ""}$where")
                "leave" -> line("LEAVE $who after ${ToolText.duration(((fact.amount ?: 0.0) * 1_000).toLong())}")
                "advancement" -> {
                    val frame = fact.other ?: "task"
                    if (frame == "goal" || frame == "challenge" || fact.subject in Salience.NOTABLE_ADVANCEMENTS) {
                        val title = fact.detail.text("title") ?: fact.subject?.let(EventFormatter::id) ?: "?"
                        line("ADVANCEMENT $who: ${ModelText.quote(title, TITLE_MAX)} (${if (frame == "task") "" else "$frame, "}${fact.pos ?: "?"})")
                    }
                }
                "structure_enter" -> line("STRUCTURE $who entered ${fact.subject?.let(EventFormatter::id)}${if (fact.detail.flag("first_visit")) ", first visit" else ""}$where")
                "biome_enter" -> line("BIOME $who entered ${fact.subject?.let(EventFormatter::id)}, first visit$where")
                "block_break" -> {
                    val block = fact.subject ?: continue
                    val grief = fact.grief
                    val runs = if (grief) griefs else mined
                    val key = (fact.player ?: "?") to (if (grief) fact.owner else block)
                    val open = runs[key]?.takeIf { fact.at.toEpochMilli() - it.first.at.toEpochMilli() <= if (grief) GRIEF_WINDOW_MS else MINED_WINDOW_MS }
                    if (open != null) {
                        open.add(block)
                    } else {
                        runs[key]?.let { lines += it.line(zone, grief) }
                        runs[key] = Run(fact, who).also { it.add(block) }
                    }
                }
                "explosion" -> {
                    val nearby = (fact.detail["nearby"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val hit = fact.detail.number("blocks_hit") ?: 0
                    val built = fact.detail.number("player_blocks_hit") ?: 0
                    line(buildString {
                        append("EXPLOSION ${fact.subject?.let(EventFormatter::id) ?: "block"}")
                        fact.other?.let { append(" by ${ModelText.name(EventFormatter.id(it))}") }
                        if (nearby.isNotEmpty()) append(" near ${nearby.joinToString { ModelText.name(it) }}")
                        append(if (hit == 0) ": no block destroyed" else ": $hit blocks destroyed, $built of them built by players")
                        append(where)
                    }, nearby.toSet() + setOfNotNull(fact.other?.takeIf { ':' !in it }))
                }
            }
        }
        (mined.values + griefs.values).forEach { run -> lines += run.line(zone, run in griefs.values) }
        return lines
    }

    /** Blocks one player broke within a window: rare ores of one kind, or blocks of one owner (a grief). */
    private class Run(val first: Fact, val who: String) {
        private val blocks = LinkedHashMap<String, Int>()

        fun add(block: String) {
            blocks[block] = (blocks[block] ?: 0) + 1
        }

        fun line(zone: ZoneId, grief: Boolean): Line {
            val count = blocks.values.sum()
            val where = first.pos?.let { " ($it)" }.orEmpty()
            val body = if (grief) {
                val top = blocks.entries.sortedByDescending { it.value }.take(4).joinToString { "${EventFormatter.id(it.key)} ${it.value}" }
                "GRIEF $who broke $count block${if (count > 1) "s" else ""} placed by ${ModelText.name(first.owner)}: $top$where"
            } else {
                "MINED $who ${EventFormatter.id(blocks.keys.first())}${if (count > 1) " ×$count" else ""}$where"
            }
            val players = setOfNotNull(first.player, first.owner.takeIf { grief })
            return Line(first.at, "[${SECONDS.withZone(zone).format(first.at)}] e${first.id} $body", Kind.FACT, players, first.id)
        }
    }

    // ── What the memory holds ───────────────────────────────────────────────────────────────────────

    /** A row the memory got: [line] for the model; [open] for canon still open (shown with the open canon). */
    data class Written(val at: Instant, val line: String, val open: Boolean)

    /** A memory shown to the Director: [how] the channel, [event] the event it came for. */
    data class Shown(val at: Instant, val ref: String, val how: String, val event: Long?, val text: String) {
        fun line(zone: ZoneId, withText: Boolean): String =
            "$ref at ${time(at, zone)} ($how${event?.let { ", for e$it" }.orEmpty()})" + if (withText) ": « ${ModelText.escape(ModelText.cut(text, SHOWN_TEXT_MAX))} »" else ""
    }

    /** A player who left: [line] says when and what they did in the session. */
    data class Left(val player: PlayerRef, val at: Instant, val line: String)

    private fun writtenOf(row: MemoryRow, zone: ZoneId): Written = when (row) {
        is CanonEntry -> {
            val state = if (row.status.open) row.status.id else "now ${row.status.id}"
            Written(row.createdAt, canonLine(row, null, zone, "(by the ${row.source.id} at ${time(row.createdAt, zone)}, $state)"), row.status.open)
        }
        is Interaction -> Written(
            row.ts,
            "${row.ref} ${ModelText.name(row.player.name)} ${row.kind.id} ${signed(row.valence)} at ${time(row.ts, zone)} (by the ${row.source.id}): " +
                ModelText.escape(row.summary) + (row.quote?.let { " — « ${ModelText.escape(it)} »" }.orEmpty()) +
                (if (row.toolCalls.isEmpty()) "" else " [${row.toolCalls.joinToString { "t$it" }}]"),
            false,
        )
        is MemoryEntry -> Written(
            row.createdAt,
            "${row.ref} ${row.kind.id}${about(row.players)}, importance ${row.importance} (by the ${row.source.id} at ${time(row.createdAt, zone)}): " +
                ModelText.escape(ModelText.cut("${row.title}: ${row.content}", WRITTEN_TEXT_MAX)),
            false,
        )
        is Note -> Written(
            row.createdAt, "${row.ref} note ${row.kind.id} at ${time(row.createdAt, zone)}: ${ModelText.escape(ModelText.cut(row.text, WRITTEN_TEXT_MAX))}", false,
        )
        is ChronicleEntry -> Written(
            row.from, "${row.ref} chronicle ${row.level.id} (by the ${row.source.id}): ${ModelText.escape(row.title)}", false,
        )
    }

    /** `C-45 promise → Mimosa, due 21:00 (by the director at 20:10): <statement> — « <quote> »`. */
    private fun canonLine(row: CanonEntry, from: Instant?, zone: ZoneId, by: String? = null): String = buildString {
        append("${row.ref} ${canonWhat(row)}")
        if (row.status == CanonStatus.PLANNED) append(", planned")
        row.dueAt?.let { append(", due ${time(it, zone)}") }
        val author = by ?: if (from != null && !row.createdAt.isBefore(from)) "(by the ${row.source.id} at ${time(row.createdAt, zone)})" else null
        author?.let { append(" $it") }
        append(": ${ModelText.escape(ModelText.cut(row.statement, WRITTEN_TEXT_MAX))}")
        row.quote?.let { append(" — « ${ModelText.escape(it)} »") }
        row.stakes?.let { append(" — stakes: ${ModelText.escape(it)}") }
        if (row.uses > 0) append(" — served ${row.uses} time${if (row.uses > 1) "s" else ""}")
    }

    /** The players named [names] that the Journal knows, sorted by name. */
    private fun named(connection: Connection, names: Set<String>): List<PlayerRef> {
        if (names.isEmpty()) return emptyList()
        return connection.query(NAMED_SQL, stringArray(names.map { it.lowercase() }), stringArray(names.map { it.lowercase() })) {
            PlayerRef(UUID.fromString(getString(1)), getString(2))
        }.distinctBy { it.uuid }.sortedBy { it.name.lowercase() }
    }

    companion object {
        /** Longest `<exchange>` block, in characters (M3 §7.2): a longer window becomes several blocks. */
        const val MAX_CHARS = 24_000

        /** Longest `<known>` block. */
        const val MAX_KNOWN_CHARS = 8_000

        /** Items of a `<known>` list at most; the rest is counted. */
        const val MAX_ITEMS = 40

        /** Room kept for the header and the footer of an `<exchange>` block. */
        private const val HEADER_ROOM = 200

        /** Longest line of the Narrator (a book can be long); a player's message is cut like the Director's events. */
        const val NARRATOR_MAX = 2_000
        const val CHAT_MAX = 300
        private const val RESULT_MAX = 300
        private const val TITLE_MAX = 60
        private const val WRITTEN_TEXT_MAX = 300
        private const val SHOWN_TEXT_MAX = 300

        /** Breaks of another player's blocks within this window are one grief, as the salience counts them. */
        private const val GRIEF_WINDOW_MS = Salience.GRIEF_WINDOW_MS

        /** Rare ores of one kind mined by one player within this window are one line. */
        private const val MINED_WINDOW_MS = 120_000L

        private const val READ_TIMEOUT_MS = 10_000L

        /** Speech tools whose successful text is a line of the Narrator in the chat (`tool/speech`). */
        private val SAID_TOOLS = setOf("say", "show_title", "actionbar", "give_book")

        private const val NO_TARGET = "neutral_no_target"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val EMPTY = JsonObject(emptyMap())
        private val SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
        private val MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

        /** `21:06` in [zone]. */
        fun time(at: Instant, zone: ZoneId): String = MINUTES.withZone(zone).format(at)

        /** `21:06:42` in [zone]. */
        fun seconds(at: Instant, zone: ZoneId): String = SECONDS.withZone(zone).format(at)

        /** `promise → Mimosa`, `law`. */
        fun canonWhat(row: CanonEntry): String =
            if (row.players.isEmpty()) row.kind.id else "${row.kind.id} → ${row.players.joinToString { ModelText.name(it.name) }}"

        /** ` about Bartok, Mimosa`, or nothing. */
        private fun about(players: List<PlayerRef>): String = if (players.isEmpty()) "" else " about ${players.joinToString { ModelText.name(it.name) }}"

        private fun said(text: String?): String = text?.let { ": ${ModelText.escape(ModelText.cut(it, WRITTEN_TEXT_MAX))}" }.orEmpty()

        private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

        /** `0.92`, `3`, `3.5`. */
        private fun number(value: Double): String =
            if (value == Math.rint(value) && value >= 1.0) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

        /** A titled list: one line when empty (`none`), else the title and one `- ` line per item, [max] items at most. */
        private fun MutableList<String>.section(title: String, items: List<String>, max: Int) {
            if (items.isEmpty()) {
                add("$title: none")
                return
            }
            add("$title (${items.size}):")
            items.take(max).forEach { add("- $it") }
            if (items.size > max) add("- … and ${items.size - max} more")
        }

        /** [lines] joined, cut to [max] characters on a line boundary (the last line is the closing tag). */
        fun fit(lines: List<String>, max: Int): String {
            val text = lines.joinToString("\n")
            if (text.length <= max) return text
            val close = lines.last()
            val kept = mutableListOf<String>()
            var size = close.length + CUT_NOTE.length + 2
            for (line in lines.dropLast(1)) {
                if (size + line.length + 1 > max) break
                kept += line
                size += line.length + 1
            }
            return (kept + CUT_NOTE + close).joinToString("\n")
        }

        private const val CUT_NOTE = "(… cut: the rest went over the budget)"

        private fun ResultSet.doubleOrNull(column: Int): Double? = getDouble(column).takeUnless { wasNull() }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

        private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        // ── SQL ─────────────────────────────────────────────────────────────────────────────────────

        /** Public chat, prayers and the Narrator's lines, with the latest triage of each message. Never `private_messages`. */
        private const val CHAT_SQL = """
            SELECT c.id, c.ts, c.channel, c.player, c.recipients, c.text, c.operator,
                   s.source, s.to_narrator, s.tone, s.tone_confidence, s.intent, s.manipulation
            FROM chat c
            LEFT JOIN (SELECT * FROM chat_signals WHERE ts > ? AND ts <= ?
                       QUALIFY row_number() OVER (PARTITION BY event_id ORDER BY ts DESC) = 1) s ON s.event_id = c.id
            WHERE c.channel IN ('public', 'pray', 'narrator', 'operator') AND c.ts > ? AND c.ts <= ?
            ORDER BY c.ts, c.id"""

        /** The Director's calls that did something or tried to (a read or a call refused for its arguments did nothing). */
        private const val TOOLS_SQL = "SELECT id, ts, tool, status, result, targets, effect FROM tool_calls " +
            "WHERE conversation_id = ? AND role = 'director' AND effect IN ('speech', 'world', 'task', 'engine') " +
            "AND status <> 'invalid' AND ts > ? AND ts <= ? ORDER BY ts, id"

        /**
         * The facts the salience routes to the Director (T18), but teleports, dimension changes, respawns and
         * loot chests; the owner of a block broken by another player. Advancements are sorted in code. (The
         * JSON arrows bind looser than comparisons in DuckDB: they stay between parentheses.)
         */
        private const val FACTS_SQL = """
            WITH e AS (
                SELECT id, ts, type, player_uuid, player, dim, x, y, z, subject, amount, other, detail,
                       (detail ->> 'placed_by') AS placed_by, (detail ->> 'first_visit') = 'true' AS first_visit
                FROM events WHERE ts > ? AND ts <= ?
            )
            SELECT e.id, e.ts, e.type, e.player_uuid, e.player, e.dim, e.x, e.y, e.z, e.subject, e.amount, e.other,
                   e.detail::VARCHAR, owner.name, coalesce(e.type = 'block_break' AND e.placed_by <> e.player_uuid::VARCHAR, false)
            FROM e
            LEFT JOIN players owner ON e.type = 'block_break' AND owner.player_uuid::VARCHAR = e.placed_by
                 AND owner.player_uuid IS DISTINCT FROM e.player_uuid
            WHERE e.type IN ('death', 'join', 'leave', 'health_low', 'advancement')
                OR (e.type = 'kill' AND (e.detail ->> 'pvp') = 'true')
                OR (e.type = 'structure_enter' AND (coalesce(e.first_visit, false) OR list_contains(?, e.subject)))
                OR (e.type = 'biome_enter' AND coalesce(e.first_visit, false))
                OR (e.type = 'block_break' AND (list_contains(?, e.subject) OR coalesce(e.placed_by <> e.player_uuid::VARCHAR, false)))
                OR (e.type = 'explosion' AND coalesce((e.detail ->> 'narrator'), 'false') <> 'true'
                    AND (coalesce(TRY_CAST((e.detail ->> 'player_blocks_hit') AS INTEGER), 0) > 0
                         OR coalesce(json_array_length((e.detail -> 'nearby')), 0) > 0))
            ORDER BY e.ts, e.id"""

        private const val OPEN_CANON_SQL = "SELECT $CANON_COLUMNS FROM canon WHERE status IN ('planned', 'active') AND NOT archived ORDER BY created_at, id"

        /** The citable rows inserted in a period, by their audit. */
        private const val INSERTED_SQL = "SELECT DISTINCT table_name, row_key FROM memory_log WHERE op = 'insert' AND ts >= ? AND ts <= ? " +
            "AND table_name IN ('canon', 'interactions', 'memories', 'notes', 'chronicle')"

        /** Canon written before a period and closed in it. */
        private const val CLOSED_CANON_SQL = "SELECT $CANON_COLUMNS FROM canon WHERE resolved_at >= ? AND resolved_at <= ? AND created_at < ? " +
            "ORDER BY resolved_at, id"

        /** What the Director of a chapter was shown (its own reads, not the dream's nor a use). */
        private const val SHOWN_SQL = "SELECT ts, ref_id, channel, event_id FROM recalls WHERE conversation_id = ? AND ts >= ? AND ts <= ? " +
            "AND channel IN ('briefing', 'join', 'reminiscence', 'recall_tool', 'profile') ORDER BY ts"

        private const val NAMED_SQL = "SELECT player_uuid::VARCHAR, name FROM players WHERE list_contains(?, lower(name)) " +
            "OR player_uuid IN (SELECT player_uuid FROM player_names WHERE list_contains(?, lower(name)))"

        /** Sessions ended in a period, with their deaths, blocks broken, kills, advancements and rare ores. */
        private const val LEFT_SQL = """
            SELECT s.player_uuid::VARCHAR, s.player, s.left_at, s.duration_s,
                   count(e.id) FILTER (WHERE e.type = 'death'), count(e.id) FILTER (WHERE e.type = 'block_break'),
                   count(e.id) FILTER (WHERE e.type = 'kill'), count(e.id) FILTER (WHERE e.type = 'advancement'),
                   list_sort(list(DISTINCT e.subject) FILTER (WHERE e.type = 'block_break' AND list_contains(?, e.subject)))
            FROM sessions s LEFT JOIN events e ON e.session_id = s.session_id
            WHERE s.left_at > ? AND s.left_at <= ?
            GROUP BY ALL ORDER BY s.left_at"""
    }
}
