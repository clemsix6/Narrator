package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.event.PlayerRef
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

    private fun lines(connection: Connection, from: Instant, to: Instant): List<Line> = connection.prepareStatement(
        "SELECT id, epoch_ms(ts), text, recipients FROM chat WHERE channel = 'narrator' AND ts >= CAST(? AS TIMESTAMPTZ) AND ts < CAST(? AS TIMESTAMPTZ) ORDER BY ts, id",
    ).use { statement ->
        statement.setString(1, from.toString())
        statement.setString(2, to.toString())
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val recipients = (rows.getObject(4) as? java.sql.Array)?.let { array -> (array.array as Array<*>).mapNotNull { it?.toString() } }
                    add(Line(rows.getLong(1), Instant.ofEpochMilli(rows.getLong(2)), rows.getString(3), recipients))
                }
            }
        }
    }

    /** Every name a player used, the latest owner of a name winning. */
    private fun names(connection: Connection): Map<String, UUID> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name, player_uuid FROM player_names ORDER BY first_seen").use { rows ->
            buildMap { while (rows.next()) put(rows.getString(1), rows.getObject(2) as UUID) }
        }
    }

    private fun replies(connection: Connection, from: Instant, to: Instant): List<Reply> = connection.prepareStatement(
        """
        SELECT c.player_uuid, epoch_ms(c.ts), c.text, s.humor, s.tone, s.tone_confidence
        FROM chat c LEFT JOIN chat_signals s ON s.event_id = c.id
        WHERE c.channel IN ('public', 'pray') AND c.player_uuid IS NOT NULL AND c.ts > CAST(? AS TIMESTAMPTZ) AND c.ts <= CAST(? AS TIMESTAMPTZ)
        ORDER BY c.ts, c.id
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, from.toString())
        statement.setString(2, to.toString())
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        Reply(
                            player = rows.getObject(1) as UUID,
                            ts = Instant.ofEpochMilli(rows.getLong(2)),
                            text = rows.getString(3),
                            humor = rows.getDouble(4).takeUnless { rows.wasNull() },
                            tone = rows.getString(5),
                            toneConfidence = rows.getDouble(6).takeUnless { rows.wasNull() },
                        ),
                    )
                }
            }
        }
    }

    private fun sessions(connection: Connection, from: Instant, to: Instant): List<Session> = connection.prepareStatement(
        "SELECT player_uuid, epoch_ms(joined_at), epoch_ms(left_at) FROM sessions WHERE joined_at < CAST(? AS TIMESTAMPTZ) AND (left_at IS NULL OR left_at >= CAST(? AS TIMESTAMPTZ))",
    ).use { statement ->
        statement.setString(1, to.toString())
        statement.setString(2, from.toString())
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        Session(
                            rows.getObject(1) as UUID,
                            Instant.ofEpochMilli(rows.getLong(2)),
                            rows.getLong(3).takeUnless { rows.wasNull() }?.let(Instant::ofEpochMilli),
                        ),
                    )
                }
            }
        }
    }

}
