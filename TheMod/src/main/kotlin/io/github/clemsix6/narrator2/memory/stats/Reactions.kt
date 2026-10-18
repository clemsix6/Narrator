package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.doubleOrNull
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.strings
import io.github.clemsix6.narrator2.memory.uuid
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * How players took the Narrator's lines (M3 §6.4), for the Dreamer's review: for each line of the Narrator
 * (`chat` channel `narrator`) addressed to players, what each of them wrote within [WINDOW_SECONDS].
 *
 * A line is addressed to its recipients when it was private, else to the players it names. Each of them:
 * [Kind.LAUGHED] (humor of [HUMOR] at least from Jev, or a laugh of the heuristic's word list),
 * [Kind.ANNOYED] (a hostile or disrespectful tone, confident), [Kind.REPLIED] (anything else), or
 * [Kind.IGNORED] when nothing came although they were online. A player offline then is left out.
 * Nothing more is asked of Jev.
 */
object Reactions {
    const val WINDOW_SECONDS = 120L
    const val HUMOR = 0.6

    enum class Kind {
        LAUGHED, ANNOYED, REPLIED, IGNORED;

        val id: String get() = name.lowercase()
    }

    /** The reaction of [player] to the Narrator's line [lineId], said at [ts]. */
    data class Reaction(val lineId: Long, val ts: Instant, val line: String, val player: PlayerRef, val kind: Kind)

    /** The reactions to the Narrator's lines said from [from] (included) to [to] (excluded), in order; on a read connection. */
    fun read(connection: Connection, from: Instant, to: Instant, words: HeuristicWords): List<Reaction> {
        val lines = lines(connection, from, to)
        if (lines.isEmpty()) return emptyList()
        val names = names(connection)
        val replies = replies(connection, from, to.plusSeconds(WINDOW_SECONDS))
        val sessions = sessions(connection, from, to)
        return lines.flatMap { line ->
            targets(line, names).mapNotNull { player ->
                val said = replies.filter { it.player == player.uuid && it.ts > line.ts && it.ts <= line.ts.plusSeconds(WINDOW_SECONDS) }
                val kind = when {
                    said.any { (it.humor ?: 0.0) >= HUMOR || words.matches(HeuristicReflexes.Category.LAUGHTER, HeuristicWords.normalize(it.text)) } -> Kind.LAUGHED
                    said.any { it.tone in ANNOYED_TONES && (it.toneConfidence ?: 0.0) >= Favor.TONE_CONFIDENT } -> Kind.ANNOYED
                    said.isNotEmpty() -> Kind.REPLIED
                    sessions.any { it.player == player.uuid && !it.joinedAt.isAfter(line.ts) && (it.leftAt == null || it.leftAt.isAfter(line.ts)) } -> Kind.IGNORED
                    else -> null
                }
                kind?.let { Reaction(line.id, line.ts, line.text, player, it) }
            }
        }
    }

    /** `laughed 3 · annoyed 1 · replied 5 · ignored 7`, kinds without any left out; `none` for no reaction. */
    fun summary(reactions: List<Reaction>): String =
        Kind.entries.mapNotNull { kind -> reactions.count { it.kind == kind }.takeIf { it > 0 }?.let { "${kind.id} $it" } }
            .joinToString(" · ").ifEmpty { "none" }

    private val ANNOYED_TONES = setOf("hostile", "disrespectful")

    private class Line(val id: Long, val ts: Instant, val text: String, val recipients: List<String>?)

    private class Reply(val player: UUID, val ts: Instant, val text: String, val humor: Double?, val tone: String?, val toneConfidence: Double?)

    private class Session(val player: UUID, val joinedAt: Instant, val leftAt: Instant?)

    /** The players a line is addressed to: its recipients, else the known names it contains as words. */
    private fun targets(line: Line, names: Map<String, UUID>): List<PlayerRef> {
        val named = line.recipients ?: names.keys.filter { name ->
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(line.text)
        }
        return named.mapNotNull { name -> names.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let { PlayerRef(it.value, it.key) } }
            .distinctBy { it.uuid }
    }

    private fun lines(connection: Connection, from: Instant, to: Instant): List<Line> = connection.query(
        "SELECT id, epoch_ms(ts), text, recipients FROM chat WHERE channel = 'narrator' AND ts >= ? AND ts < ? ORDER BY ts, id",
        from, to,
        timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
    ) {
        // No recipients (null) for a line said to everyone; a private line lists them.
        Line(getLong(1), Instant.ofEpochMilli(getLong(2)), getString(3), getArray(4)?.let { strings(4) })
    }

    /** Every name a player used, the latest owner of a name winning. */
    private fun names(connection: Connection): Map<String, UUID> = connection.query(
        "SELECT name, player_uuid FROM player_names ORDER BY first_seen",
        timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
    ) { getString(1) to uuid(2) }.toMap()

    private fun replies(connection: Connection, from: Instant, to: Instant): List<Reply> = connection.query(
        """
        SELECT c.player_uuid, epoch_ms(c.ts), c.text, s.humor, s.tone, s.tone_confidence
        FROM chat c LEFT JOIN chat_signals s ON s.event_id = c.id
        WHERE c.channel IN ('public', 'pray') AND c.player_uuid IS NOT NULL AND c.ts > ? AND c.ts <= ?
        ORDER BY c.ts, c.id
        """.trimIndent(),
        from, to,
        timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
    ) {
        Reply(
            player = uuid(1), ts = Instant.ofEpochMilli(getLong(2)), text = getString(3), humor = doubleOrNull(4), tone = getString(5),
            toneConfidence = doubleOrNull(6),
        )
    }

    private fun sessions(connection: Connection, from: Instant, to: Instant): List<Session> = connection.query(
        "SELECT player_uuid, epoch_ms(joined_at), epoch_ms(left_at) FROM sessions WHERE joined_at < ? AND (left_at IS NULL OR left_at >= ?)",
        to, from,
        timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
    ) { Session(uuid(1), Instant.ofEpochMilli(getLong(2)), longOrNull(3)?.let(Instant::ofEpochMilli)) }
}
