package io.github.clemsix6.narrator2.memory.stats

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * What pairs of players did together (M3 §6.7), the factual ground of the relations the Dreamer names:
 * `pair_stats`, one row per pair (`a_uuid < b_uuid` in DuckDB's order of UUIDs, which is their text order).
 *
 * - **Minutes together**: minutes during which both were online, in the same dimension, within [RADIUS]
 *   blocks. Each online minute of a player takes their latest position sampled by the end of that minute
 *   in the same session (positions are sampled on moves and at least every 5 minutes, T05), so two players
 *   are compared to the minute. Total, and over the 7 days before `now`.
 * - **PvP kills** of each on the other, and **griefs**: blocks of one broken by the other.
 *
 * A pair has a row once they met, fought or griefed. [compute] recomputes every pair from the whole history
 * on a read connection; [write] stores them in a short `Journal.write` job.
 */
object PairStats {
    const val RADIUS = 32

    /** A row of `pair_stats`. */
    data class PlayerPair(
        val a: UUID,
        val b: UUID,
        val minutesTogether: Double,
        val minutesTogether7d: Double,
        val pvpAOnB: Int,
        val pvpBOnA: Int,
        val griefsAOnB: Int,
        val griefsBOnA: Int,
        val computedAt: Instant,
    )

    /** Every pair at [now], on a read connection, the most time together first. */
    fun compute(connection: Connection, now: Instant): List<PlayerPair> = SheetStats.query(connection, SheetStats.bind(SQL, null, now)) { rows ->
        buildList {
            while (rows.next()) {
                add(
                    PlayerPair(
                        a = rows.getObject(1) as UUID, b = rows.getObject(2) as UUID,
                        minutesTogether = rows.getDouble(3), minutesTogether7d = rows.getDouble(4),
                        pvpAOnB = rows.getInt(5), pvpBOnA = rows.getInt(6), griefsAOnB = rows.getInt(7), griefsBOnA = rows.getInt(8),
                        computedAt = now,
                    ),
                )
            }
        }
    }

    /** Writes [pairs] (inside a `Journal.write` job). */
    fun write(connection: Connection, pairs: List<PlayerPair>) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO pair_stats VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))",
        ).use { statement ->
            for (pair in pairs) {
                statement.setString(1, pair.a.toString())
                statement.setString(2, pair.b.toString())
                statement.setDouble(3, pair.minutesTogether)
                statement.setDouble(4, pair.minutesTogether7d)
                statement.setInt(5, pair.pvpAOnB)
                statement.setInt(6, pair.pvpBOnA)
                statement.setInt(7, pair.griefsAOnB)
                statement.setInt(8, pair.griefsBOnA)
                statement.setString(9, pair.computedAt.toString())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** The stored pairs [player] is in (every pair when null), the most time together first. */
    fun read(connection: Connection, player: UUID? = null): List<PlayerPair> = SheetStats.query(
        connection,
        "SELECT a_uuid, b_uuid, minutes_together, minutes_together_7d, pvp_a_on_b, pvp_b_on_a, griefs_a_on_b, griefs_b_on_a, " +
            "epoch_ms(computed_at) FROM pair_stats " +
            (if (player == null) "" else "WHERE CAST('$player' AS UUID) IN (a_uuid, b_uuid) ") +
            "ORDER BY minutes_together DESC, a_uuid, b_uuid",
    ) { rows ->
        buildList {
            while (rows.next()) {
                add(
                    PlayerPair(
                        a = rows.getObject(1) as UUID, b = rows.getObject(2) as UUID,
                        minutesTogether = rows.getDouble(3), minutesTogether7d = rows.getDouble(4),
                        pvpAOnB = rows.getInt(5), pvpBOnA = rows.getInt(6), griefsAOnB = rows.getInt(7), griefsBOnA = rows.getInt(8),
                        computedAt = Instant.ofEpochMilli(rows.getLong(9)),
                    ),
                )
            }
        }
    }

    private val SQL = """
        WITH grid AS (
            SELECT s.player_uuid, s.joined_at, m AS minute, least(m + INTERVAL 1 MINUTE, coalesce(s.left_at, {now})) AS upto
            FROM sessions s, unnest(generate_series(time_bucket(INTERVAL 1 MINUTE, s.joined_at), coalesce(s.left_at, {now}), INTERVAL 1 MINUTE)) AS t(m)
            WHERE s.joined_at <= {now} AND m < coalesce(s.left_at, {now})),
        located AS (
            SELECT g.player_uuid, g.minute, q.dim, q.x, q.y, q.z
            FROM grid g ASOF JOIN positions q ON g.player_uuid = q.player_uuid AND g.upto >= q.ts
            WHERE q.ts >= g.joined_at),
        together AS (
            SELECT a.player_uuid AS a_uuid, b.player_uuid AS b_uuid, count(*) AS minutes,
                   count(*) FILTER (WHERE a.minute >= {now} - INTERVAL 7 DAY) AS minutes_7d
            FROM located a JOIN located b ON a.minute = b.minute AND a.dim = b.dim AND a.player_uuid < b.player_uuid
            WHERE (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z) <= $RADIUS * $RADIUS
            GROUP BY 1, 2),
        kills AS (
            SELECT e.player_uuid AS killer, n.player_uuid AS victim, count(*) AS n
            FROM events e JOIN player_names n ON n.name = (e.detail ->> 'victim_name')
            WHERE e.type = 'kill' AND coalesce(CAST(e.detail ->> 'pvp' AS BOOLEAN), false) AND e.ts <= {now} AND n.player_uuid <> e.player_uuid
            GROUP BY 1, 2),
        griefs AS (
            SELECT player_uuid AS breaker, owner, count(*) AS n
            FROM (SELECT player_uuid, TRY_CAST(detail ->> 'placed_by' AS UUID) AS owner FROM events
                  WHERE type = 'block_break' AND ts <= {now} AND player_uuid IS NOT NULL)
            WHERE owner IS NOT NULL AND owner <> player_uuid
            GROUP BY 1, 2),
        pairs AS (
            SELECT a_uuid, b_uuid FROM together
            UNION SELECT least(killer, victim), greatest(killer, victim) FROM kills
            UNION SELECT least(breaker, owner), greatest(breaker, owner) FROM griefs)
        SELECT pr.a_uuid, pr.b_uuid, coalesce(t.minutes, 0), coalesce(t.minutes_7d, 0),
               coalesce(ab.n, 0), coalesce(ba.n, 0), coalesce(gab.n, 0), coalesce(gba.n, 0)
        FROM pairs pr
        LEFT JOIN together t ON t.a_uuid = pr.a_uuid AND t.b_uuid = pr.b_uuid
        LEFT JOIN kills ab ON ab.killer = pr.a_uuid AND ab.victim = pr.b_uuid
        LEFT JOIN kills ba ON ba.killer = pr.b_uuid AND ba.victim = pr.a_uuid
        LEFT JOIN griefs gab ON gab.breaker = pr.a_uuid AND gab.owner = pr.b_uuid
        LEFT JOIN griefs gba ON gba.breaker = pr.b_uuid AND gba.owner = pr.a_uuid
        ORDER BY 3 DESC, 1, 2
    """.trimIndent()
}
