package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.ago
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.dim
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.id
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.number
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.quote
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.who
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The briefing that opens a chapter (M1_M2 §12.11): the first block of the chapter's first message,
 * everything the Narrator knows when its conversation starts afresh. English, written by the engine:
 *
 * - `<previous_chapters>`: the last three chapters of the Director, with their factual summaries
 *   (commitments included) or why they have none;
 * - `<world …/>`: the Overworld's day, hour and weather;
 * - `<players_online>`: each player online with their dimension, position and state, how long they have
 *   played and what they did in the last minutes;
 * - `<players_recent>`: who else played in the last 24 hours;
 * - `<recent_chat>`: the last lines of public chat and prayers **with their text**, the Narrator's own
 *   lines included (never a private message: it reads the `chat_log` view);
 * - `<your_recent_actions>`: the Narrator's last speech and world tool calls that took effect;
 * - `<engine_state>`: its effects on the players online, the protections, the deaths it may still cause
 *   each of them, its tasks and reminders.
 *
 * Every section is always there. A source that fails is never passed over in silence: its section says
 * `(unavailable: <reason>)` and an `ERROR` is logged; the other sections are still written. An empty
 * source says so (`(no chat in the last 24 hours)`). That is the lesson of the first version, whose
 * briefing was built from empty inputs without anyone noticing.
 *
 * Each section has a fixed budget: the whole stays within [MAX_CHARS] (about 10,000 tokens at 3.5
 * characters per token), whatever the history. Reads from the Journal come after a barrier, so the
 * facts of the last second are in; they run side by side, each within [READ_TIMEOUT_MS]. After a first
 * message too long for the model's context, the briefing is sent again in its reduced form: the last
 * summarized chapter only, and a few lines of chat, actions and past players.
 *
 * The three places of `<previous_chapters>` go to chapters **with a summary** (M2 review, finding 28):
 * chapters closed without one (an error, a crash) never push the last summaries, and their promises,
 * out of the briefing. The newest of them are still named, one line each, after the summaries.
 *
 * Player names and whatever players wrote go through the common escape (`core/ModelText`).
 *
 * @param world the world and the players online, read on the server thread.
 * @param activity one line on what a player did in the last [SituationReport.RECENT_MINUTES] minutes, or null.
 */
class Briefing(
    private val services: NarratorServices,
    private val world: suspend () -> WorldSnapshot,
    private val activity: (UUID) -> String?,
) {
    private val log = Log.director
    private val zone = services.clock.zone
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone)
    private val hours = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)
    private val dated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone)

    /**
     * The briefing of chapter [number] at [now].
     *
     * @param conversationId the chapter's own conversation, left out of the previous chapters.
     * @param chatBefore chat said from this time on is left out: the wake-up message that follows tells it
     *   as events (epoch milliseconds).
     * @param reminders the reminders waiting.
     * @param closed the chapters closed since the server started, newest last: they complete the Journal's
     *   rows, which may lag behind or be unavailable.
     * @param reduced the reduced form, after a first message that did not fit in the context.
     */
    suspend fun render(
        number: Int,
        conversationId: String,
        now: Long,
        chatBefore: Long,
        reminders: List<PendingReminder>,
        closed: List<ConversationRecord>,
        reduced: Boolean = false,
    ): String = coroutineScope {
        if (services.journal.available) {
            withTimeoutOrNull(BARRIER_TIMEOUT_MS) { services.journal.barrier() } ?: log.warn("briefing: the Journal barrier timed out, the last facts may be missing")
        }
        val limits = if (reduced) Limits.REDUCED else Limits.FULL
        val snapshot = async { attempt("world") { world() } }
        val chapters = async { attempt("previous chapters") { storedChapters(conversationId) } }
        val recent = async { attempt("recent players") { recentPlayers(now, limits) } }
        val chat = async { attempt("recent chat") { chat(now, chatBefore, limits) } }
        val actions = async { attempt("recent actions") { actions(now, limits) } }
        val captured = snapshot.await()
        buildString {
            append("<briefing chapter=\"$number\" now=\"${stamp.format(Instant.ofEpochMilli(now))}\"${if (reduced) " reduced=\"true\"" else ""}>\n")
            section("previous_chapters", previousChapters(chapters.await(), closed, now, limits))
            append(worldLine(captured)).append('\n')
            section("players_online", playersOnline(captured, now))
            section("players_recent window=\"24h\"", recent.await().fold({ it.ifEmpty { listOf("(no other player in the last 24 hours)") } }, ::unavailableLines))
            section("recent_chat", chat.await().fold({ it.ifEmpty { listOf("(no chat in the last 24 hours)") } }, ::unavailableLines))
            section("your_recent_actions", actions.await().fold({ it.ifEmpty { listOf("(none yet)") } }, ::unavailableLines))
            section("engine_state", engineState(now, reminders))
            append("</briefing>")
        }
    }

    // ── Previous chapters ───────────────────────────────────────────────────────────────────────────

    /** A chapter of the Director that is over (or was left open by a crash). */
    private data class PastChapter(val id: String, val number: Int?, val startedAt: Long, val endedAt: Long?, val reason: String?, val summary: String?) {
        companion object {
            fun of(record: ConversationRecord) = PastChapter(
                record.conversationId, record.chapter, record.startedAt.toEpochMilli(), record.endedAt?.toEpochMilli(), record.endReason?.id, record.summary,
            )
        }
    }

    /** The last [PREVIOUS_CHAPTERS] chapters with a summary, and as many of the last without one. */
    private suspend fun storedChapters(current: String): List<PastChapter> {
        val columns = "conversation_id, chapter, epoch_ms(started_at), epoch_ms(ended_at), end_reason, summary"
        val director = "FROM conversations WHERE role = 'director' AND conversation_id <> ?"
        return query(
            "(SELECT $columns $director AND coalesce(trim(summary), '') <> '' ORDER BY started_at DESC LIMIT $PREVIOUS_CHAPTERS) UNION ALL " +
                "(SELECT $columns $director AND coalesce(trim(summary), '') = '' ORDER BY started_at DESC LIMIT $PREVIOUS_CHAPTERS)",
            current,
            current,
        ) {
            PastChapter(getString(1), getObject(2)?.let { getInt(2) }, getLong(3), getObject(4)?.let { getLong(4) }, getString(5), getString(6))
        }
    }

    /**
     * The last summarized chapters ([Limits.chapters] places), oldest first, then the chapters closed
     * without a summary since the last of them, one line each ([PREVIOUS_CHAPTERS] at most).
     */
    private fun previousChapters(stored: Result<List<PastChapter>>, closed: List<ConversationRecord>, now: Long, limits: Limits): List<String> {
        val byId = LinkedHashMap<String, PastChapter>()
        stored.getOrNull()?.forEach { byId[it.id] = it }
        closed.forEach { byId[it.conversationId] = PastChapter.of(it) }
        val all = byId.values.sortedBy { it.startedAt }
        val summarized = all.filter { !it.summary.isNullOrBlank() }.takeLast(limits.chapters)
        val lastSummarized = summarized.lastOrNull()?.startedAt ?: Long.MIN_VALUE
        val unsummarized = all.filter { it.summary.isNullOrBlank() && it.startedAt > lastSummarized }.takeLast(PREVIOUS_CHAPTERS)
        val lines = mutableListOf<String>()
        val empty = summarized.isEmpty() && unsummarized.isEmpty()
        stored.exceptionOrNull()?.let { lines += "(${if (empty) "" else "older chapters "}unavailable: ${reason(it)})" }
        if (empty && stored.isSuccess) lines += "(no previous chapter)"
        summarized.forEach { chapter ->
            lines += "${head(chapter, now)}:"
            lines += text(chapter.summary.orEmpty(), SUMMARY_MAX).lines().filter { it.isNotBlank() }
        }
        unsummarized.forEach { chapter -> lines += "${head(chapter, now)}: no summary; the recent chat and actions below cover what it said and did last" }
        return lines
    }

    /** `#12 19:00–20:30 (token_limit)`. */
    private fun head(chapter: PastChapter, now: Long): String =
        "#${chapter.number ?: "?"} ${span(chapter.startedAt, chapter.endedAt, now)} (${chapter.reason ?: "unfinished"})"

    /** `20:02–21:38`, with the date when it is not today's. */
    private fun span(start: Long, end: Long?, now: Long): String {
        val from = if (sameDay(start, now)) hours.format(Instant.ofEpochMilli(start)) else dated.format(Instant.ofEpochMilli(start))
        val to = when {
            end == null -> "…"
            sameDay(end, start) -> hours.format(Instant.ofEpochMilli(end))
            else -> dated.format(Instant.ofEpochMilli(end))
        }
        return "$from–$to"
    }

    private fun sameDay(a: Long, b: Long) = day.format(Instant.ofEpochMilli(a)) == day.format(Instant.ofEpochMilli(b))

    // ── World and players ───────────────────────────────────────────────────────────────────────────

    private fun worldLine(snapshot: Result<WorldSnapshot>): String = snapshot.fold(
        { "<world day=\"${it.day}\" time=\"${it.time}\" clock=\"${it.clock}\" weather=\"${it.weather}\"/>" },
        { "<world>(unavailable: ${reason(it)})</world>" },
    )

    /**
     * Each player online: where they are (the snapshot of the server thread; the last sampled position
     * when it has none), how long they have played and what they did lately.
     */
    private fun playersOnline(snapshot: Result<WorldSnapshot>, now: Long): List<String> {
        val live = services.players.online
        val captured = snapshot.getOrNull()?.players.orEmpty()
        val byUuid = captured.associateBy { it.ref.uuid }
        val known = live.map { it.ref.uuid }.toSet()
        val lines = live.map { player ->
            val place = byUuid[player.ref.uuid]?.describe() ?: sampled(player)
            val recent = activity(player.ref.uuid)?.let { " · last ${SituationReport.RECENT_MINUTES}m: ${ModelText.escape(it)}" }.orEmpty()
            "${who(player.ref)}: $place · online ${ago(now - player.joinedAt)}${if (player.idle) ", idle" else ""}$recent"
        } + captured.filter { it.ref.uuid !in known }.map { "${who(it.ref)}: ${it.describe()}" }
        val notes = snapshot.exceptionOrNull()?.let { listOf("(live positions unavailable: ${reason(it)}; these are the last sampled ones)") }.orEmpty()
        if (lines.isEmpty()) return notes + "(nobody is online)"
        return notes + lines.take(ONLINE_MAX).map { cut(it, PLAYER_LINE_MAX) } +
            listOfNotNull(if (lines.size > ONLINE_MAX) "(+${lines.size - ONLINE_MAX} more players online)" else null)
    }

    /** `overworld 12 -58 40 · dripstone_caves · underground · hp 14/20 food 12`, from the position sampler. */
    private fun sampled(player: LivePlayer): String = buildList {
        add("${dim(player.pos.dim)} ${player.pos.x} ${player.pos.y} ${player.pos.z}")
        player.biome?.let { add(id(it)) }
        if (player.underground) add("underground")
        player.structure?.let { add("in ${id(it)}") }
        player.vehicle?.let { add("riding ${id(it)}") }
        add(if (player.health <= 0f) "dead" else "hp ${number(player.health.toDouble())}/${number(player.maxHealth.toDouble())} food ${player.food}")
        if (player.gameMode != "survival") add(player.gameMode)
    }.joinToString(" · ")

    /** Players seen in the last 24 hours who are not online: `Mimosa: last seen 3h05m ago, played 1h40m`. */
    private suspend fun recentPlayers(now: Long, limits: Limits): List<String> {
        val online = services.players.online.map { it.ref.uuid.toString() }.toSet()
        val rows = query(
            "SELECT CAST(player_uuid AS VARCHAR), arg_max(player, joined_at), epoch_ms(max(coalesce(left_at, joined_at))), " +
                "arg_max(duration_s, joined_at) FROM sessions WHERE coalesce(left_at, joined_at) >= ? " +
                "GROUP BY player_uuid ORDER BY 3 DESC LIMIT ${limits.recentPlayers + online.size}",
            Instant.ofEpochMilli(now - WINDOW_MS),
        ) {
            val played = getObject(4)?.let { " played ${ago(getLong(4) * 1_000)}" }.orEmpty()
            getString(1) to "${ModelText.name(getString(2).orEmpty())}: last seen ${ago(now - getLong(3))} ago${if (played.isEmpty()) "" else ",$played"}"
        }
        return rows.filter { it.first !in online }.take(limits.recentPlayers).map { it.second }
    }

    // ── Chat and actions ────────────────────────────────────────────────────────────────────────────

    /**
     * The last lines of the last 24 hours before [before], oldest first: public chat, prayers and the
     * Narrator's lines, each with its whole text (the Narrator rereads its own lines not to repeat
     * itself). At most [CHAT_LINES] lines and [CHAT_MAX_CHARS] characters. Private messages are not in `chat_log`.
     */
    private suspend fun chat(now: Long, before: Long, limits: Limits): List<String> {
        val newestFirst = query(
            "SELECT epoch_ms(ts), channel, player, recipients, text FROM chat_log WHERE ts >= ? AND ts < ? ORDER BY ts DESC LIMIT ${limits.chatLines}",
            Instant.ofEpochMilli(now - WINDOW_MS),
            Instant.ofEpochMilli(before),
        ) {
            val speaker = when (getString(2)) {
                "pray" -> "${ModelText.name(getString(3).orEmpty())} (prayer)"
                "narrator" -> "NARRATOR→${addressees(names(4))}"
                "operator" -> "NARRATOR (through an operator)→${addressees(names(4))}"
                else -> ModelText.name(getString(3).orEmpty())
            }
            "[-${ago(now - getLong(1))}] $speaker: ${quote(getString(5))}"
        }
        var chars = 0
        return newestFirst.takeWhile { line -> (chars + line.length + 1 <= limits.chatChars).also { if (it) chars += line.length + 1 } }.reversed()
    }

    /** `all`, `Bartok`, `Bartok,Mimosa`, `Bartok,Mimosa +3`. */
    private fun addressees(names: List<String>): String = when {
        names.isEmpty() -> "all"
        names.size <= 2 -> names.joinToString(",") { ModelText.name(it) }
        else -> names.take(2).joinToString(",") { ModelText.name(it) } + " +${names.size - 2}"
    }

    /** The Narrator's last speech and world tool calls that took effect (or may have), oldest first. */
    private suspend fun actions(now: Long, limits: Limits): List<String> = query(
        "SELECT epoch_ms(ts), tool, targets, result FROM narrator_actions WHERE status IN ('ok', 'unknown') ORDER BY ts DESC LIMIT ${limits.actions}",
    ) {
        val targets = names(3)
        val result = getString(4)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { ": ${text(it, RESULT_MAX)}" }.orEmpty()
        val to = if (targets.isEmpty()) "" else "→${ModelText.escape(cut(targets.joinToString(","), TARGETS_MAX))}"
        "[-${ago(now - getLong(1))}] ${getString(2)}$to$result"
    }.reversed()

    // ── Engine state ────────────────────────────────────────────────────────────────────────────────

    private fun engineState(now: Long, reminders: List<PendingReminder>): List<String> {
        val online = services.players.online
        return listOf(
            line("Your active effects") {
                val effects = online.flatMap { player ->
                    services.guards.activeOn(player.ref.uuid).map { "${who(player.ref)} ${ModelText.escape(it.what)} (${it.kind}) until ${time(it.until)}" }
                }
                list(effects, EFFECTS_MAX)
            },
            line("Protected") {
                val protected = online.mapNotNull { player ->
                    val died = player.lastDeathAt?.let { it + SituationReport.NO_PILE_ON_MS }?.takeIf { it > now }
                    when {
                        services.guards.isProtected(player.ref.uuid) -> "${who(player.ref)} from every hostile action (operators)"
                        died != null -> "${who(player.ref)} from hostile actions until ${time(died)} (died)"
                        else -> null
                    }
                }
                list(protected, ONLINE_MAX)
            },
            line("Deaths you may still cause") { list(online.map { "${who(it.ref)} ${services.guards.deathsLeft(it.ref.uuid)}" }, ONLINE_MAX) },
            line("Tasks") {
                val open = services.workers.list().filter { !it.status.finished }.map { task ->
                    val state = if (task.status == TaskRecord.Status.RUNNING) "running ${ago(now - (task.startedAt ?: task.createdAt).toEpochMilli())}" else task.status.id
                    "T-${task.taskId} ${task.role.id} $state"
                }
                list(open, TASKS_MAX)
            },
            line("Reminders") { list(reminders.sortedBy { it.at }.map { "R-${it.id} at ${time(it.at)} ${quote(it.note, REMINDER_QUOTE_MAX)}" }, REMINDERS_MAX) },
        )
    }

    /** `<label>: <what>`, or `<label>: (unavailable: …)` when [what] fails. */
    private fun line(label: String, what: () -> String): String = try {
        "$label: ${what()}"
    } catch (e: Exception) {
        log.error("briefing: ${label.lowercase()} unavailable: ${reason(e)}")
        "$label: (unavailable: ${reason(e)})"
    }

    private fun list(items: List<String>, max: Int): String = when {
        items.isEmpty() -> "none"
        items.size <= max -> items.joinToString(", ")
        else -> items.take(max).joinToString(", ") + " (+${items.size - max} more)"
    }

    private fun time(ms: Long): String = hours.format(Instant.ofEpochMilli(ms))

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun StringBuilder.section(tag: String, lines: List<String>) {
        append('<').append(tag).append(">\n")
        lines.forEach { append(it).append('\n') }
        append("</").append(tag.substringBefore(' ')).append(">\n")
    }

    /** Reads one source; a failure is logged and kept for its section. */
    private suspend fun <T> attempt(source: String, read: suspend () -> T): Result<T> = try {
        Result.success(read())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("briefing: $source unavailable: ${reason(e)}")
        Result.failure(e)
    }

    private fun unavailableLines(error: Throwable): List<String> = listOf("(unavailable: ${reason(error)})")

    /** The names in the `VARCHAR[]` column [index] of the current row; empty for NULL. */
    private fun ResultSet.names(index: Int): List<String> =
        (getObject(index) as? java.sql.Array)?.let { array -> (array.array as Array<*>).mapNotNull { it?.toString() } }.orEmpty()

    /** Every row of [sql] with [parameters], read by [row], on a read connection of the Journal. */
    private suspend fun <T> query(sql: String, vararg parameters: Any, row: ResultSet.() -> T): List<T> =
        services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = (READ_TIMEOUT_MS / 1_000).toInt()
                parameters.forEachIndexed { index, value ->
                    statement.setObject(index + 1, if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value)
                }
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
            }
        }

    companion object {
        /** The whole briefing stays within this many characters: about 10,000 tokens at 3.5 characters per token. */
        const val MAX_CHARS = 35_000

        /** How far back the players and the chat go. */
        const val WINDOW_MS = 24 * 3_600_000L

        const val PREVIOUS_CHAPTERS = 3

        /** A chapter summary is kept to this many characters (the length `conversations.summary` holds). */
        const val SUMMARY_MAX = 4_000
        const val ONLINE_MAX = 12
        const val PLAYER_LINE_MAX = 300
        const val RECENT_PLAYERS = 10
        const val CHAT_LINES = 40
        const val CHAT_MAX_CHARS = 10_000
        const val ACTIONS = 20
        const val RESULT_MAX = 120
        const val TARGETS_MAX = 40
        const val EFFECTS_MAX = 12
        const val TASKS_MAX = 6
        const val REMINDERS_MAX = 5
        const val REMINDER_QUOTE_MAX = 100

        const val READ_TIMEOUT_MS = 5_000L
        const val BARRIER_TIMEOUT_MS = 2_000L

        private fun reason(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        /**
         * Text written by the model or the engine, kept on its lines ([ModelText.lines]): control
         * characters become spaces, `<` `>` become `‹` `›` (nothing inside the briefing can pass for a
         * tag), cut to [max] characters.
         */
        fun text(value: String, max: Int): String = ModelText.cut(ModelText.lines(value), max)

        private fun cut(value: String, max: Int): String = ModelText.cut(value, max)
    }

    /** What each section may hold: the full briefing, or its reduced form after a first message too long for the context. */
    private enum class Limits(val chapters: Int, val recentPlayers: Int, val chatLines: Int, val chatChars: Int, val actions: Int) {
        FULL(PREVIOUS_CHAPTERS, RECENT_PLAYERS, CHAT_LINES, CHAT_MAX_CHARS, ACTIONS),
        REDUCED(1, 3, 10, 2_500, 5),
    }
}
