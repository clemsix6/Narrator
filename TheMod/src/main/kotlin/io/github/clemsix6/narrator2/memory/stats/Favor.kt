package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Favor, respect and the attention debt (M3 §6.2): computed at read time from the Journal and the
 * interactions, never stored. Pure formulas, plus [read] on a read connection (`journal.read { … }`).
 *
 * - **Favor** in ]−100, 100[: a weighted mean of the valences of the interactions that count, with a
 *   neutral prior of [PRIOR] interactions:
 *   `w = 0.7 · 2^(−age / 10 h) + 0.3 · 2^(−age / 100 h)` (age in the player's own hours of play),
 *   `favor = 100 · Σ(v · w) / (3 · (Σ w + 3))`. Its trend is the favor now minus the favor
 *   [TREND_WINDOW_SECONDS] of the player's play earlier (only the interactions known then).
 * - **Respect** in [0, 100]: `50 + 50 · (r − d)`, r and d the shares, weighted by recency (half-life of
 *   [RESPECT_HALF_LIFE] messages), of the messages to the Narrator judged respectful, and disrespectful
 *   or hostile, with a tone confidence of [TONE_CONFIDENT] at least; each interaction marked as
 *   disrespect counts as two disrespectful messages. Null under [RESPECT_MIN_MESSAGES] messages.
 * - **Attention debt**: the player's play since the Narrator last paid attention to them (an interaction
 *   noted about them, or a successful speech or world tool call that targets them).
 */
object Favor {
    const val SHORT_HALF_LIFE_HOURS = 10.0
    const val LONG_HALF_LIFE_HOURS = 100.0
    const val SHORT_SHARE = 0.7
    const val PRIOR = 3.0
    const val TREND_WINDOW_SECONDS = 5 * 3_600L

    /** A trend beyond ±this shows an arrow. */
    const val TREND_SHOWN = 5.0

    /** A message addresses the Narrator from this `to_narrator` (`ReflexThresholds.ADDRESSED`). */
    const val ADDRESSED = 0.6
    const val TONE_CONFIDENT = 0.6
    const val RESPECT_HALF_LIFE = 30.0
    const val RESPECT_MIN_MESSAGES = 5

    /** An interaction that counts toward favor: its valence and the player's play clock when it happened. */
    data class Rated(val playedSeconds: Long, val valence: Int)

    /** The weight of an interaction [ageSeconds] of the player's play old. */
    fun weight(ageSeconds: Double): Double {
        val hours = ageSeconds.coerceAtLeast(0.0) / 3_600
        return SHORT_SHARE * 2.0.pow(-hours / SHORT_HALF_LIFE_HOURS) + (1 - SHORT_SHARE) * 2.0.pow(-hours / LONG_HALF_LIFE_HOURS)
    }

    /** The favor at the player's play clock [atPlayed], from the interactions known by then; 0 without any. */
    fun favor(rated: List<Rated>, atPlayed: Long): Double {
        var sum = 0.0
        var weights = 0.0
        for (interaction in rated) {
            if (interaction.playedSeconds > atPlayed) continue
            val w = weight((atPlayed - interaction.playedSeconds).toDouble())
            sum += interaction.valence * w
            weights += w
        }
        return 100 * sum / (3 * (weights + PRIOR))
    }

    /** The favor now minus the favor [TREND_WINDOW_SECONDS] of play earlier. */
    fun trend(rated: List<Rated>, atPlayed: Long): Double = favor(rated, atPlayed) - favor(rated, atPlayed - TREND_WINDOW_SECONDS)

    /** `↗` above +[TREND_SHOWN], `↘` below −[TREND_SHOWN], else nothing. */
    fun arrow(trend: Double?): String = when {
        trend == null -> ""
        trend > TREND_SHOWN -> "↗"
        trend < -TREND_SHOWN -> "↘"
        else -> ""
    }

    /** How a message to the Narrator (or a disrespect interaction) weighs on respect. */
    enum class Judged { RESPECTFUL, DISRESPECTFUL, NEUTRAL }

    /**
     * Respect from [judged], oldest first (messages to the Narrator, each disrespect interaction twice),
     * given the number of [messages] to the Narrator among them; null under [RESPECT_MIN_MESSAGES].
     */
    fun respect(judged: List<Judged>, messages: Int): Int? {
        if (messages < RESPECT_MIN_MESSAGES || judged.isEmpty()) return null
        var respectful = 0.0
        var disrespectful = 0.0
        var weights = 0.0
        judged.asReversed().forEachIndexed { k, sample ->
            val w = 2.0.pow(-k / RESPECT_HALF_LIFE)
            weights += w
            when (sample) {
                Judged.RESPECTFUL -> respectful += w
                Judged.DISRESPECTFUL -> disrespectful += w
                Judged.NEUTRAL -> Unit
            }
        }
        return (50 + 50 * (respectful - disrespectful) / weights).roundToInt().coerceIn(0, 100)
    }

    /**
     * What [read] finds about a player.
     *
     * @property favor null without any interaction that counts; [trend] then null too.
     * @property respect null under [RESPECT_MIN_MESSAGES] [messages] to the Narrator.
     * @property disrespect interactions marked as a lack of respect.
     * @property lastAttentionAt null when the Narrator never paid attention to the player; [attentionDebtSeconds]
     *   is then all of their play.
     */
    data class Reading(
        val player: UUID,
        val playedSeconds: Long,
        val favor: Double?,
        val trend: Double?,
        val respect: Int?,
        val messages: Int,
        val disrespect: Int,
        val lastAttentionAt: Instant?,
        val attentionDebtSeconds: Long,
    )

    /** Favor, respect and attention of [player] at [now]; null for a player never seen. On a read connection. */
    fun read(connection: Connection, player: UUID, now: Instant): Reading? {
        val played = playedSeconds(connection, player, now) ?: return null
        val rated = rated(connection, player, now)
        val favor = if (rated.isEmpty()) null else favor(rated, played)
        val toward = toward(connection, player, now)
        val (lastAttention, debt) = attention(connection, player, now)
        return Reading(
            player = player,
            playedSeconds = played,
            favor = favor,
            trend = favor?.let { trend(rated, played) },
            respect = respect(toward.judged, toward.messages),
            messages = toward.messages,
            disrespect = toward.disrespect,
            lastAttentionAt = lastAttention,
            attentionDebtSeconds = debt,
        )
    }

    /**
     * The situation's lines (M3 §11.3) for the players online: `Favor: Bartok -31 ↘ · Mimosa +22 ↗` (players
     * with a favor) and `Attention: Kelvin has had nothing from you for 1h10m of play` (beyond
     * [attentionMinutes] of play). A line with nobody is left out.
     */
    fun situationLines(readings: List<Pair<PlayerRef, Reading>>, attentionMinutes: Int): List<String> = buildList {
        val favors = readings.filter { it.second.favor != null }.joinToString(" · ") { (player, reading) ->
            "${player.shownName} ${signed(reading.favor!!)}${arrow(reading.trend).let { if (it.isEmpty()) "" else " $it" }}"
        }
        if (favors.isNotEmpty()) add("Favor: $favors")
        val neglected = readings.filter { it.second.attentionDebtSeconds >= attentionMinutes * 60L }
            .sortedByDescending { it.second.attentionDebtSeconds }
        if (neglected.isNotEmpty()) {
            add(
                "Attention: " + neglected.mapIndexed { index, (player, reading) ->
                    val duration = ToolText.duration(reading.attentionDebtSeconds * 1_000)
                    if (index == 0) "${player.shownName} has had nothing from you for $duration of play" else "${player.shownName} for $duration"
                }.joinToString(" · "),
            )
        }
    }

    /** `+22`, `-31`, `0`. */
    fun signed(favor: Double): String {
        val value = favor.roundToLong()
        return if (value > 0) "+$value" else value.toString()
    }

    /** The player's play clock at [now]: closed sessions plus the running one; null for a player never seen. */
    private fun playedSeconds(connection: Connection, player: UUID, now: Instant): Long? = query(
        connection,
        "SELECT CAST(round(${SheetStats.PLAYED}) AS BIGINT) FROM players pl WHERE pl.player_uuid = {player}",
        player, now,
    ) { if (next()) getLong(1) else null }

    private fun rated(connection: Connection, player: UUID, now: Instant): List<Rated> = query(
        connection,
        "SELECT played_s, valence FROM interactions WHERE player_uuid = {player} AND counts AND NOT archived AND ts <= {now} ORDER BY ts",
        player, now,
    ) { buildList { while (next()) add(Rated(getLong(1), getInt(2))) } }

    /** The player's conduct toward the Narrator: [judged] samples oldest first, of which [messages] messages and [disrespect] interactions. */
    private class Toward(val judged: List<Judged>, val messages: Int, val disrespect: Int)

    private fun toward(connection: Connection, player: UUID, now: Instant): Toward = query(
        connection,
        """
        SELECT ts, 'message' AS what, tone, tone_confidence FROM chat_signals
        WHERE player_uuid = {player} AND ts <= {now} AND to_narrator >= $ADDRESSED
        UNION ALL
        SELECT ts, 'disrespect', NULL, NULL FROM interactions WHERE player_uuid = {player} AND disrespect AND NOT archived AND ts <= {now}
        ORDER BY ts, what DESC
        """.trimIndent(),
        player, now,
    ) {
        var messages = 0
        var disrespect = 0
        val judged = buildList {
            while (next()) {
                if (getString(2) == "disrespect") {
                    disrespect++
                    repeat(2) { add(Judged.DISRESPECTFUL) }
                    continue
                }
                messages++
                val confident = getDouble(4) >= TONE_CONFIDENT && !wasNull()
                add(
                    when {
                        !confident -> Judged.NEUTRAL
                        getString(3) == "respectful" -> Judged.RESPECTFUL
                        getString(3) == "disrespectful" || getString(3) == "hostile" -> Judged.DISRESPECTFUL
                        else -> Judged.NEUTRAL
                    },
                )
            }
        }
        Toward(judged, messages, disrespect)
    }

    /** When the Narrator last paid attention to the player, and the player's play since then (all of it when never). */
    private fun attention(connection: Connection, player: UUID, now: Instant): Pair<Instant?, Long> = query(
        connection,
        """
        WITH names AS (SELECT list(name) AS names FROM player_names WHERE player_uuid = {player}),
        last AS (SELECT max(t) AS t FROM (
            SELECT max(ts) AS t FROM interactions WHERE player_uuid = {player} AND NOT archived AND ts <= {now}
            UNION ALL
            SELECT max(c.ts) FROM tool_calls c, names
            WHERE c.status = 'ok' AND c.effect IN ('speech', 'world') AND c.ts <= {now} AND list_has_any(c.targets, names.names)))
        SELECT epoch_ms(last.t), coalesce(sum(greatest(0, epoch(least(coalesce(s.left_at, {now}), {now}) - greatest(s.joined_at, coalesce(last.t, s.joined_at))))), 0)
        FROM last LEFT JOIN sessions s ON s.player_uuid = {player} AND s.joined_at <= {now}
        GROUP BY last.t
        """.trimIndent(),
        player, now,
    ) {
        next()
        getLong(1).takeUnless { wasNull() }?.let(Instant::ofEpochMilli) to getDouble(2).roundToLong()
    }

    /** Runs [sql] with `{player}` and `{now}` bound ([SheetStats.bind]) and reads its result. */
    private fun <T> query(connection: Connection, sql: String, player: UUID, now: Instant, read: java.sql.ResultSet.() -> T): T =
        SheetStats.query(connection, SheetStats.bind(sql, player, now)) { it.read() }
}
