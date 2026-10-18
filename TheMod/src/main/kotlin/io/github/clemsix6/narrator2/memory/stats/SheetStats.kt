package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.SheetStat
import io.github.clemsix6.narrator2.memory.doubleOrNull
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.math.round

/**
 * The statistics of a player's sheet (M3 §6.1), computed by the code from the Journal, never by a model,
 * into `sheet_stats` (one row per stat). [compute] reads them on a read connection, all in one query over
 * the player's events (by UUID: names change) up to `now`; [write] stores them in a short `Journal.write`
 * job, so the writer is never held by the reading. Timestamps in `detail` are ISO-8601 UTC strings, ids
 * have no `minecraft:`.
 *
 * | stat | value | detail |
 * |---|---|---|
 * | `playtime` | seconds played (closed sessions + the running one) | `{last7d_s, sessions, usual_hours}` (the 3 hours of day most played, ascending, Journal time zone) |
 * | `deaths` | deaths | `{top: [[cause, n]…3], last: {ts, cause, killer, dim, x, y, z}, absurd}` |
 * | `kills` | kills of mobs (no PvP) | `{top: [[victim, n]…3], bosses: […]}` |
 * | `pvp` | players killed | `{losses, last: {ts, opponent, won}}` |
 * | `mining` | blocks broken (not in creative) | `{ores: {diamond, ancient_debris, emerald, gold, iron}, avg_ore_y}` |
 * | `building` | blocks placed | `{top: [[block, n]…3]}` |
 * | `travel` | metres (vanilla `*_one_cm` stats, falls left out) | `{walk, sprint, elytra, boat, …}` in metres, the largest first, under a metre left out |
 * | `exploration` | distinct biomes | `{structures, rare_biomes, nether_at, end_at}` |
 * | `gear` | best tier 0-5 crafted or picked up | `{armor, weapon, pickaxe}` (best item of each) |
 * | `advancements` | advancements | `{goals, challenges, last: [3 titles]}` |
 * | `economy` | trades | `{emeralds_spent}` |
 * | `places` | null | `{base, bed, mine}`, each `{dim, x, y, z, n}` or null |
 * | `narrator` | messages to the Narrator (prayers, and chat with `to_narrator ≥ 0.6`) | `{prayers, punishments, blessings, gifts, challenges_won, challenges_lost}` |
 * | `luck` | index 0-100 ([Luck]) | `{rare_per_1k_mined, treasure_fish, challenges_ratio, absurd_deaths_per_h}` |
 * | `style` | null | `{explorer, fighter, builder, miner, farmer, social}` in %; value and detail null under an hour of play ([Style]) |
 * | `signals` | Jev verdicts measured | [Signals] |
 */
object SheetStats {
    /** Every stat, in the order the sheet shows them. */
    val STATS: List<String> = listOf(
        "playtime", "deaths", "kills", "pvp", "mining", "building", "travel", "exploration", "gear", "advancements",
        "economy", "places", "narrator", "luck", "style", "signals",
    )

    /** A stat query stops after this long: the reads run on the Journal's SQL pool. */
    const val QUERY_TIMEOUT_SECONDS = 30

    /** Damage types of the deaths that are the player's own fault (M3 §6.1). */
    val ABSURD_CAUSES: List<String> = ids("fall", "lava", "drown", "in_wall", "cactus", "sweet_berry_bush")

    /** Bosses listed by `kills.bosses`. */
    val BOSSES: List<String> = ids("ender_dragon", "wither", "elder_guardian", "warden")

    /** Biomes listed by `exploration.rare_biomes`: the rarest the world generates. */
    val RARE_BIOMES: List<String> = ids(
        "mushroom_fields", "ice_spikes", "eroded_badlands", "wooded_badlands", "bamboo_jungle", "sparse_jungle",
        "cherry_grove", "pale_garden", "deep_dark", "windswept_savanna",
    )

    /** Ores counted as rare by the luck index. */
    val RARE_ORES: List<String> = ids("diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore", "ancient_debris")

    /** Fishing loot counted as treasure (the vanilla treasure category). */
    val TREASURE: List<String> = ids("bow", "enchanted_book", "fishing_rod", "name_tag", "nautilus_shell", "saddle")

    /** Blocks whose breaking is harvest, for the farmer's share of the style. */
    val CROPS: List<String> = ids(
        "wheat", "carrots", "potatoes", "beetroots", "nether_wart", "melon", "pumpkin", "sugar_cane", "cocoa",
        "sweet_berry_bush", "torchflower_crop", "pitcher_crop",
    )

    /** A computed stat, as written: [detail] is JSON text. */
    data class Computed(val stat: String, val value: Double?, val detail: String?)

    /**
     * Every stat of [player] at [now], on a read connection; null for a player the Journal has never seen.
     * [rates] are every player's, for the luck and the style ([Rates.read], once per refresh).
     */
    internal fun compute(connection: Connection, player: UUID, now: Instant, rates: List<Rates>): List<Computed>? {
        if (!known(connection, player)) return null
        val computed = connection.query(bind(QUERY, player, now), timeoutSeconds = QUERY_TIMEOUT_SECONDS) {
            Computed(getString(1), doubleOrNull(2), getString(3))
        }
        val mine = rates.firstOrNull { it.player == player }
        val luck = mine?.let { Luck.of(it, rates) }
        val style = mine?.let { Style.of(it, rates) }
        return computed +
            Computed("luck", luck?.first, luck?.second?.toString()) +
            Computed("style", null, style?.toString())
    }

    /** Writes [stats] of [player], computed at [now], inside a `Journal.write` job. */
    fun write(connection: Connection, player: UUID, stats: List<Computed>, now: Instant) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO sheet_stats (player_uuid, stat, value, detail, computed_at) " +
                "VALUES (CAST(? AS UUID), ?, ?, CAST(? AS JSON), CAST(? AS TIMESTAMPTZ))",
        ).use { statement ->
            for (stat in stats) {
                statement.setString(1, player.toString())
                statement.setString(2, stat.stat)
                if (stat.value == null) statement.setNull(3, java.sql.Types.DOUBLE) else statement.setDouble(3, stat.value)
                statement.setString(4, stat.detail)
                statement.setString(5, now.toString())
                statement.executeUpdate() // no JDBC batch: DuckDB's would open a transaction inside the job's
            }
        }
    }

    /** The stats of [player] as stored, by name; empty before the first refresh. */
    fun read(connection: Connection, player: UUID): Map<String, SheetStat> = connection.query(
        "SELECT stat, value, CAST(detail AS VARCHAR), epoch_ms(computed_at) FROM sheet_stats WHERE player_uuid = ${uuid(player)}",
        timeoutSeconds = QUERY_TIMEOUT_SECONDS,
    ) {
        val detail = getString(3)?.let { Json.parseToJsonElement(it) as? JsonObject }
        SheetStat(getString(1), doubleOrNull(2), detail, Instant.ofEpochMilli(getLong(4)))
    }.associateBy { it.stat }

    /**
     * [sql] with `{player}` and `{now}` replaced by literals. Literals rather than parameters or a one-row CTE:
     * DuckDB then filters and joins on constants (with a CTE `p` crossed in, the pairs' query took 11 s
     * instead of 30 ms on 500 hours of play). Both values are typed, never text from a player.
     */
    internal fun bind(sql: String, player: UUID?, now: Instant): String {
        val withNow = sql.replace("{now}", "CAST('$now' AS TIMESTAMPTZ)")
        return if (player == null) withNow else withNow.replace("{player}", uuid(player))
    }

    /** [expression] (a TIMESTAMPTZ) as an ISO-8601 UTC string. */
    internal fun iso(expression: String) = "strftime(($expression) AT TIME ZONE 'UTC', '%Y-%m-%dT%H:%M:%SZ')"

    /** A SQL list of string literals. */
    internal fun sqlList(values: List<String>) = values.joinToString(prefix = "(", postfix = ")") { "'$it'" }

    /** A rate rounded to [decimals], or JSON null. */
    internal fun rounded(value: Double?, decimals: Int): JsonElement {
        if (value == null || value.isNaN()) return JsonNull
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        return JsonPrimitive(round(value * scale) / scale)
    }

    /** The play clock of the player `pl` (a `players` row) at `{now}`: closed sessions plus the running one, in seconds. */
    internal const val PLAYED = "(pl.playtime_s + coalesce((SELECT sum(epoch({now} - s.joined_at)) FROM sessions s " +
        "WHERE s.player_uuid = pl.player_uuid AND s.left_at IS NULL AND s.joined_at <= {now}), 0))"

    private fun uuid(player: UUID) = "CAST('$player' AS UUID)"

    private fun ids(vararg names: String) = names.map { "minecraft:$it" }

    private fun known(connection: Connection, player: UUID): Boolean = connection.query(
        "SELECT count(*) FROM players WHERE player_uuid = ${uuid(player)}",
        timeoutSeconds = QUERY_TIMEOUT_SECONDS,
    ) { getLong(1) }.single() > 0

    /** One stat of the query: a `SELECT` of its name, [value] and [detail], reading the CTEs. */
    internal class Stat(val name: String, val value: String, val detail: String, val ctes: List<String> = emptyList()) {
        val select: String get() = "SELECT '$name' AS stat, CAST($value AS DOUBLE) AS value, CAST($detail AS VARCHAR) AS detail"
    }

    private const val NOT_CREATIVE = "NOT coalesce(CAST(detail ->> 'creative' AS BOOLEAN), false)"
    private const val PVP = "coalesce(CAST(detail ->> 'pvp' AS BOOLEAN), false)"

    /** A death whose killer is a player: player names never hold a colon, entity types always do. */
    private const val KILLED_BY_PLAYER = "other IS NOT NULL AND NOT contains(other, ':')"
    private const val ORE = "(ends_with(subject, '_ore') OR subject = 'minecraft:ancient_debris')"

    /** The [n] most frequent values of [column] among `e` rows of [where], as `[[value, n]…]`. */
    private fun top(column: String, where: String, n: Int = 3) =
        "(SELECT coalesce(list(json_array(k, n) ORDER BY n DESC, k), []) FROM " +
            "(SELECT $column AS k, count(*) AS n FROM e WHERE $where GROUP BY 1 ORDER BY n DESC, k LIMIT $n))"

    /** The busiest cell of 32 blocks among `e` rows of [where], as `{dim, x, y, z, n}` (x, z: the centre; y: the mean). */
    private fun cell(where: String) =
        "(SELECT {'dim': bare(dim), 'x': cx * 32 + 16, 'y': CAST(round(avg_y) AS INTEGER), 'z': cz * 32 + 16, 'n': n} FROM " +
            "(SELECT dim, CAST(floor(x / 32) AS BIGINT) AS cx, CAST(floor(z / 32) AS BIGINT) AS cz, avg(y) AS avg_y, count(*) AS n, max(ts) AS last " +
            "FROM e WHERE $where AND x IS NOT NULL GROUP BY 1, 2, 3) ORDER BY n DESC, last DESC LIMIT 1)"

    /** The first time the player was in [dimension]: arrival there, or a position sampled there. */
    private fun firstIn(dimension: String) =
        "(SELECT ${iso("min(ts)")} FROM (SELECT ts FROM e WHERE type = 'dimension_change' AND subject = '$dimension' " +
            "UNION ALL SELECT ts FROM q WHERE dim = '$dimension'))"

    private val STATS_SQL: List<Stat> = listOf(
        Stat(
            "playtime",
            value = "(SELECT round($PLAYED) FROM players pl WHERE pl.player_uuid = {player})",
            ctes = listOf("s AS (SELECT * FROM sessions WHERE player_uuid = {player} AND joined_at <= {now})"),
            detail = """to_json({
                'last7d_s': (SELECT CAST(round(coalesce(sum(greatest(0, epoch(least(coalesce(left_at, {now}), {now}) - greatest(joined_at, {now} - INTERVAL 7 DAY)))), 0)) AS BIGINT) FROM s),
                'sessions': (SELECT count(*) FROM s),
                'usual_hours': (SELECT coalesce(list(h ORDER BY h), []) FROM (
                    SELECT hour(m) AS h, sum(epoch(least(m + INTERVAL 1 HOUR, coalesce(left_at, {now})) - greatest(m, joined_at))) AS played
                    FROM s, unnest(generate_series(date_trunc('hour', joined_at), coalesce(left_at, {now}), INTERVAL 1 HOUR)) AS t(m)
                    GROUP BY 1 HAVING played > 0 ORDER BY played DESC, h LIMIT 3))
            })""",
        ),
        Stat(
            "deaths",
            value = "(SELECT count(*) FROM e WHERE type = 'death')",
            detail = """to_json({
                'top': ${top("bare(subject)", "type = 'death'")},
                'last': (SELECT {'ts': ${iso("ts")}, 'cause': bare(subject), 'killer': bare(other), 'dim': bare(dim), 'x': x, 'y': y, 'z': z}
                         FROM e WHERE type = 'death' ORDER BY ts DESC, id DESC LIMIT 1),
                'absurd': (SELECT count(*) FROM e WHERE type = 'death' AND subject IN ${sqlList(ABSURD_CAUSES)})
            })""",
        ),
        Stat(
            "kills",
            value = "(SELECT count(*) FROM e WHERE type = 'kill' AND NOT $PVP)",
            detail = """to_json({
                'top': ${top("bare(subject)", "type = 'kill' AND NOT $PVP")},
                'bosses': (SELECT coalesce(list(DISTINCT bare(subject) ORDER BY bare(subject)), []) FROM e WHERE type = 'kill' AND subject IN ${sqlList(BOSSES)})
            })""",
        ),
        Stat(
            "pvp",
            value = "(SELECT count(*) FROM e WHERE type = 'kill' AND $PVP)",
            detail = """to_json({
                'losses': (SELECT count(*) FROM e WHERE type = 'death' AND $KILLED_BY_PLAYER),
                'last': (SELECT {'ts': ${iso("ts")}, 'opponent': opponent, 'won': won} FROM (
                    SELECT ts, id, detail ->> 'victim_name' AS opponent, true AS won FROM e WHERE type = 'kill' AND $PVP
                    UNION ALL SELECT ts, id, other, false FROM e WHERE type = 'death' AND $KILLED_BY_PLAYER
                ) ORDER BY ts DESC, id DESC LIMIT 1)
            })""",
        ),
        Stat(
            "mining",
            value = "(SELECT count(*) FROM m)",
            ctes = listOf("m AS (SELECT subject, y FROM e WHERE type = 'block_break' AND $NOT_CREATIVE)"),
            detail = """to_json({
                'ores': (SELECT {
                    'diamond': count(*) FILTER (WHERE ends_with(subject, 'diamond_ore')),
                    'ancient_debris': count(*) FILTER (WHERE subject = 'minecraft:ancient_debris'),
                    'emerald': count(*) FILTER (WHERE ends_with(subject, 'emerald_ore')),
                    'gold': count(*) FILTER (WHERE ends_with(subject, 'gold_ore')),
                    'iron': count(*) FILTER (WHERE ends_with(subject, 'iron_ore'))
                } FROM m),
                'avg_ore_y': (SELECT CAST(round(avg(y)) AS INTEGER) FROM m WHERE $ORE)
            })""",
        ),
        Stat(
            "building",
            value = "(SELECT count(*) FROM e WHERE type = 'block_place')",
            detail = "to_json({'top': ${top("bare(subject)", "type = 'block_place'")}})",
        ),
        Stat(
            "travel",
            ctes = listOf(
                """t AS (
                SELECT replace(regexp_extract(stat, '^minecraft\.custom:minecraft\.(.+)_one_cm$', 1), 'aviate', 'elytra') AS kind, value
                FROM stat_totals WHERE player_uuid = {player} AND regexp_matches(stat, '^minecraft\.custom:minecraft\..+_one_cm$')
                  AND stat <> 'minecraft.custom:minecraft.fall_one_cm')""",
            ),
            value = "(SELECT round(coalesce(sum(value), 0) / 100) FROM t)",
            detail = "(SELECT coalesce(json_group_object(kind, metres), '{}') FROM " +
                "(SELECT kind, CAST(round(value / 100) AS BIGINT) AS metres FROM t WHERE value >= 100 ORDER BY value DESC, kind))",
        ),
        Stat(
            "exploration",
            ctes = listOf("b AS (SELECT subject AS b FROM e WHERE type = 'biome_enter' UNION ALL SELECT biome FROM q WHERE biome IS NOT NULL)"),
            value = "(SELECT count(DISTINCT b) FROM b)",
            detail = """to_json({
                'structures': (SELECT coalesce(list(DISTINCT bare(subject) ORDER BY bare(subject)), []) FROM e WHERE type = 'structure_enter'),
                'rare_biomes': (SELECT coalesce(list(DISTINCT bare(b) ORDER BY bare(b)), []) FROM b WHERE b IN ${sqlList(RARE_BIOMES)}),
                'nether_at': ${firstIn("minecraft:the_nether")},
                'end_at': ${firstIn("minecraft:the_end")}
            })""",
        ),
        Stat(
            "gear",
            ctes = listOf(
                """g AS (
                SELECT item,
                    CASE WHEN starts_with(item, 'netherite_') THEN 5 WHEN starts_with(item, 'diamond_') THEN 4 WHEN starts_with(item, 'iron_') THEN 3
                         WHEN starts_with(item, 'stone_') OR starts_with(item, 'golden_') OR starts_with(item, 'chainmail_') OR starts_with(item, 'copper_') THEN 2
                         WHEN starts_with(item, 'leather_') OR starts_with(item, 'wooden_') THEN 1 END AS tier,
                    CASE WHEN regexp_matches(item, '_(helmet|chestplate|leggings|boots)$') THEN 'armor'
                         WHEN regexp_matches(item, '_(sword|axe)$') THEN 'weapon'
                         WHEN ends_with(item, '_pickaxe') THEN 'pickaxe' END AS slot
                FROM (SELECT DISTINCT bare(subject) AS item FROM e WHERE type IN ('craft', 'pickup') AND subject IS NOT NULL))""",
                "gs AS (SELECT * FROM g WHERE tier IS NOT NULL AND slot IS NOT NULL)",
            ),
            value = "(SELECT coalesce(max(tier), 0) FROM gs)",
            detail = """to_json({
                'armor': (SELECT item FROM gs WHERE slot = 'armor' ORDER BY tier DESC, item LIMIT 1),
                'weapon': (SELECT item FROM gs WHERE slot = 'weapon' ORDER BY tier DESC, item LIMIT 1),
                'pickaxe': (SELECT item FROM gs WHERE slot = 'pickaxe' ORDER BY tier DESC, item LIMIT 1)
            })""",
        ),
        Stat(
            "advancements",
            value = "(SELECT count(DISTINCT subject) FROM e WHERE type = 'advancement')",
            detail = """to_json({
                'goals': (SELECT count(DISTINCT subject) FROM e WHERE type = 'advancement' AND other = 'goal'),
                'challenges': (SELECT count(DISTINCT subject) FROM e WHERE type = 'advancement' AND other = 'challenge'),
                'last': (SELECT coalesce(list(title ORDER BY ts DESC, id DESC), []) FROM
                    (SELECT ts, id, coalesce(detail ->> 'title', bare(subject)) AS title FROM e WHERE type = 'advancement' ORDER BY ts DESC, id DESC LIMIT 3))
            })""",
        ),
        Stat(
            "economy",
            value = "(SELECT count(*) FROM e WHERE type = 'trade')",
            detail = """to_json({'emeralds_spent': (SELECT coalesce(sum(
                CASE WHEN (detail -> 'cost_a' ->> 'item') = 'minecraft:emerald' THEN CAST(detail -> 'cost_a' ->> 'count' AS BIGINT) ELSE 0 END +
                CASE WHEN (detail -> 'cost_b' ->> 'item') = 'minecraft:emerald' THEN CAST(detail -> 'cost_b' ->> 'count' AS BIGINT) ELSE 0 END), 0)
                FROM e WHERE type = 'trade')})""",
        ),
        Stat(
            "places",
            value = "NULL",
            detail = """to_json({
                'base': ${cell("type = 'block_place'")},
                'bed': (SELECT {'dim': bare(dim), 'x': x, 'y': y, 'z': z, 'n': count(*)} FROM e WHERE type = 'sleep_start' AND x IS NOT NULL
                        GROUP BY dim, x, y, z ORDER BY count(*) DESC, max(ts) DESC LIMIT 1),
                'mine': ${cell("type = 'block_break' AND $NOT_CREATIVE AND $ORE")}
            })""",
        ),
        Stat(
            "narrator",
            ctes = listOf(
                """c AS (
                SELECT c.channel, s.to_narrator FROM chat c LEFT JOIN chat_signals s ON s.event_id = c.id
                WHERE c.player_uuid = {player} AND c.ts <= {now} AND c.channel IN ('public', 'pray'))""",
                "i AS (SELECT kind FROM interactions WHERE player_uuid = {player} AND ts <= {now} AND NOT archived)",
                """ch AS (SELECT status, list_contains(coalesce(winners, []), {player}) AS winner, list_contains(players, {player}) AS player
                FROM canon WHERE kind = 'challenge' AND NOT archived AND status IN ('won', 'lost') AND created_at <= {now})""",
            ),
            value = "(SELECT count(*) FROM c WHERE channel = 'pray' OR to_narrator >= ${Favor.ADDRESSED})",
            detail = """to_json({
                'prayers': (SELECT count(*) FROM c WHERE channel = 'pray'),
                'punishments': (SELECT count(*) FROM i WHERE kind = 'punishment'),
                'blessings': (SELECT count(*) FROM i WHERE kind = 'blessing'),
                'gifts': (SELECT count(*) FROM i WHERE kind = 'gift'),
                'challenges_won': (SELECT count(*) FROM ch WHERE status = 'won' AND winner),
                'challenges_lost': (SELECT count(*) FROM ch WHERE player AND NOT winner)
            })""",
        ),
        Signals.STAT,
    )

    /**
     * The whole query: the player's events (`e`) and positions (`q`) read once, then one `SELECT` per stat.
     * `{player}` and `{now}` are [bind]'s.
     */
    private val QUERY: String = buildString {
        append("WITH e AS MATERIALIZED (SELECT * FROM events WHERE player_uuid = {player} AND ts <= {now}),\n")
        append("q AS MATERIALIZED (SELECT ts, dim, biome FROM positions WHERE player_uuid = {player} AND ts <= {now})")
        STATS_SQL.flatMap { it.ctes }.forEach { append(",\n").append(it) }
        append('\n')
        append(STATS_SQL.joinToString("\nUNION ALL\n") { it.select })
    }
}

/**
 * What each player did per hour of play, for the stats that compare a player with the server ([Luck],
 * [Style]): one row per player of the Journal, read at `now`.
 */
internal data class Rates(
    val player: UUID,
    val playedSeconds: Long,
    val mined: Long,
    val rare: Long,
    /** Blocks broken below y 50 outside the End: the miner's share. */
    val dug: Long,
    /** Crops broken, animals bred and tamed. */
    val farmed: Long,
    val absurd: Long,
    val placed: Long,
    val kills: Long,
    val damage: Double,
    /** Biomes and structures entered for the first time. */
    val discoveries: Long,
    val caught: Long,
    val treasure: Long,
    val messages: Long,
    val metres: Double,
    val won: Long,
    val decided: Long,
    /** Minutes within 32 blocks of another player (`pair_stats`). */
    val together: Double,
) {
    val hours: Double get() = playedSeconds / 3_600.0

    companion object {
        private const val NOT_CREATIVE = "NOT coalesce(CAST(detail ->> 'creative' AS BOOLEAN), false)"

        private val SQL = """
            WITH ev AS (
                SELECT player_uuid, type, subject, amount, y, dim,
                       type = 'block_break' AND $NOT_CREATIVE AS mined,
                       type IN ('biome_enter', 'structure_enter') AND coalesce(CAST(detail ->> 'first_visit' AS BOOLEAN), false) AS discovery
                FROM events WHERE ts <= {now} AND player_uuid IS NOT NULL AND type IN
                    ('block_break', 'breed', 'tame', 'death', 'block_place', 'kill', 'damage_dealt', 'biome_enter', 'structure_enter')),
            a AS (SELECT player_uuid,
                count(*) FILTER (WHERE mined) AS mined,
                count(*) FILTER (WHERE mined AND subject IN ${SheetStats.sqlList(SheetStats.RARE_ORES)}) AS rare,
                count(*) FILTER (WHERE mined AND y < 50 AND dim <> 'minecraft:the_end') AS dug,
                count(*) FILTER (WHERE (type = 'block_break' AND subject IN ${SheetStats.sqlList(SheetStats.CROPS)}) OR type IN ('breed', 'tame')) AS farmed,
                count(*) FILTER (WHERE type = 'death' AND subject IN ${SheetStats.sqlList(SheetStats.ABSURD_CAUSES)}) AS absurd,
                count(*) FILTER (WHERE type = 'block_place') AS placed,
                count(*) FILTER (WHERE type = 'kill') AS kills,
                coalesce(sum(amount) FILTER (WHERE type = 'damage_dealt'), 0) AS damage,
                count(*) FILTER (WHERE discovery) AS discoveries
                FROM ev GROUP BY 1),
            f AS (SELECT e.player_uuid, sum(CAST(l ->> 'count' AS BIGINT)) AS caught,
                coalesce(sum(CAST(l ->> 'count' AS BIGINT)) FILTER (WHERE (l ->> 'item') IN ${SheetStats.sqlList(SheetStats.TREASURE)}), 0) AS treasure
                FROM events e, unnest(CAST(e.detail -> 'loot' AS JSON[])) AS u(l)
                WHERE e.type = 'fish' AND e.ts <= {now} AND e.player_uuid IS NOT NULL GROUP BY 1),
            c AS (SELECT player_uuid, count(*) AS messages FROM chat WHERE channel = 'public' AND ts <= {now} AND player_uuid IS NOT NULL GROUP BY 1),
            t AS (SELECT player_uuid, sum(value) / 100 AS metres FROM stat_totals
                WHERE regexp_matches(stat, '^minecraft\.custom:minecraft\..+_one_cm$') AND stat <> 'minecraft.custom:minecraft.fall_one_cm' GROUP BY 1),
            b AS (SELECT u.id AS player_uuid, count(*) FILTER (WHERE list_contains(coalesce(winners, []), u.id)) AS won, count(*) AS decided
                FROM canon, unnest(list_distinct(list_concat(players, coalesce(winners, [])))) AS u(id)
                WHERE kind = 'challenge' AND status IN ('won', 'lost') AND NOT archived AND created_at <= {now} GROUP BY 1),
            tg AS (SELECT u AS player_uuid, sum(minutes_together) AS together FROM
                (SELECT a_uuid AS u, minutes_together FROM pair_stats UNION ALL SELECT b_uuid, minutes_together FROM pair_stats) GROUP BY 1)
            SELECT pl.player_uuid, CAST(round(${SheetStats.PLAYED}) AS BIGINT),
                coalesce(a.mined, 0), coalesce(a.rare, 0), coalesce(a.dug, 0), coalesce(a.farmed, 0), coalesce(a.absurd, 0),
                coalesce(a.placed, 0), coalesce(a.kills, 0), coalesce(a.damage, 0), coalesce(a.discoveries, 0),
                coalesce(f.caught, 0), coalesce(f.treasure, 0), coalesce(c.messages, 0), coalesce(t.metres, 0),
                coalesce(b.won, 0), coalesce(b.decided, 0), coalesce(tg.together, 0)
            FROM players pl
            LEFT JOIN a ON a.player_uuid = pl.player_uuid LEFT JOIN f ON f.player_uuid = pl.player_uuid
            LEFT JOIN c ON c.player_uuid = pl.player_uuid LEFT JOIN t ON t.player_uuid = pl.player_uuid
            LEFT JOIN b ON b.player_uuid = pl.player_uuid LEFT JOIN tg ON tg.player_uuid = pl.player_uuid
            WHERE pl.first_seen <= {now}
            ORDER BY pl.player_uuid
        """.trimIndent()

        /** The rates of every player seen by [now], on a read connection. */
        fun read(connection: Connection, now: Instant): List<Rates> =
            connection.query(SheetStats.bind(SQL, null, now), timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS) {
                Rates(
                    player = uuid(1), playedSeconds = getLong(2), mined = getLong(3), rare = getLong(4), dug = getLong(5),
                    farmed = getLong(6), absurd = getLong(7), placed = getLong(8), kills = getLong(9), damage = getDouble(10),
                    discoveries = getLong(11), caught = getLong(12), treasure = getLong(13), messages = getLong(14),
                    metres = getDouble(15), won = getLong(16), decided = getLong(17), together = getDouble(18),
                )
            }
    }
}

/** The [p]-quantile of [values] with linear interpolation (DuckDB's `quantile_cont`); null for no value. */
internal fun quantile(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val position = p * (sorted.size - 1)
    val below = position.toInt()
    val above = minOf(below + 1, sorted.lastIndex)
    return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
}

/**
 * The luck index (M3 §6.1): `clamp(50 + 12 · (z_rare + z_fish + z_bets − z_absurd), 0, 100)`, each `z` the
 * player's distance to the server's median divided by the interquartile range. A `z` is 0 when the player
 * has played less than [MIN_HOURS], when fewer than [MIN_PLAYERS] players with that much play have a value,
 * when the player has none, or when the range is 0: no luck is made up from three throws.
 */
internal object Luck {
    const val MIN_HOURS = 5.0
    const val MIN_PLAYERS = 3

    private class Component(val key: String, val sign: Int, val decimals: Int, val value: (Rates) -> Double?)

    private val COMPONENTS = listOf(
        Component("rare_per_1k_mined", 1, 2) { if (it.mined > 0) it.rare * 1_000.0 / it.mined else null },
        Component("treasure_fish", 1, 3) { if (it.caught > 0) it.treasure.toDouble() / it.caught else null },
        Component("challenges_ratio", 1, 2) { if (it.decided > 0) it.won.toDouble() / it.decided else null },
        Component("absurd_deaths_per_h", -1, 3) { if (it.playedSeconds > 0) it.absurd / it.hours else null },
    )

    /** The index of [me] among [all] and its detail (the player's raw values). */
    fun of(me: Rates, all: List<Rates>): Pair<Double, JsonObject> {
        val population = all.filter { it.hours >= MIN_HOURS }
        val sum = COMPONENTS.sumOf { component -> component.sign * z(component.value(me), population.mapNotNull(component.value), me) }
        val detail = JsonObject(COMPONENTS.associate { it.key to SheetStats.rounded(it.value(me), it.decimals) })
        return round((50 + 12 * sum).coerceIn(0.0, 100.0)) to detail
    }

    private fun z(mine: Double?, values: List<Double>, me: Rates): Double {
        if (mine == null || me.hours < MIN_HOURS || values.size < MIN_PLAYERS) return 0.0
        val range = quantile(values, 0.75)!! - quantile(values, 0.25)!!
        return if (range > 0) (mine - quantile(values, 0.5)!!) / range else 0.0
    }
}

/**
 * The play style (M3 §6.1): for each activity, the player's volume per hour of play divided by the server's
 * median (the mean when the median is 0: one farmer among three players still farms), averaged over the
 * activity's measures, then brought to 100 %. Null under [MIN_HOURS] of play, or when the player did nothing.
 */
internal object Style {
    const val MIN_HOURS = 1.0

    private val ACTIVITIES: List<Pair<String, List<(Rates) -> Double>>> = listOf(
        "explorer" to listOf({ it.discoveries / it.hours }, { it.metres / it.hours }),
        "fighter" to listOf({ it.kills / it.hours }, { it.damage / it.hours }),
        "builder" to listOf({ it.placed / it.hours }),
        "miner" to listOf({ it.dug / it.hours }),
        "farmer" to listOf({ it.farmed / it.hours }),
        "social" to listOf({ it.messages / it.hours }, { it.together / it.hours }),
    )

    /** The shares of [me]'s play, by activity, in %. */
    fun of(me: Rates, all: List<Rates>): JsonObject? {
        if (me.hours < MIN_HOURS) return null
        val population = all.filter { it.hours >= MIN_HOURS }
        val scores = ACTIVITIES.map { (name, measures) ->
            val ratios = measures.mapNotNull { measure ->
                val values = population.map(measure)
                val reference = quantile(values, 0.5)?.takeIf { it > 0 } ?: values.average().takeIf { it > 0 }
                reference?.let { measure(me) / it }
            }
            name to (if (ratios.isEmpty()) 0.0 else ratios.average())
        }
        val total = scores.sumOf { it.second }
        if (total <= 0) return null
        return JsonObject(scores.associate { (name, score) -> name to JsonPrimitive(round(100 * score / total).toInt()) })
    }
}
