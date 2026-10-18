package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.Subscription
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.observer.PlayerStates
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The play clocks (M3 §3.2), kept in memory so that reading them never waits for the database.
 *
 * - **World clock**: seconds during which at least one player was online. It moves on [tick] (every
 *   60 s, from `MemoryJobs`) and on each join and leave ([subscribe]); each step adds the time elapsed,
 *   capped at [MAX_GAP_MS] (a frozen or sleeping server gains nothing), when someone was online. Saved in
 *   `memory_state` by [save] (every 5 min and at stop). On the first [load] it is recomputed from
 *   `sessions` (the union of their intervals); on the next ones it is the saved value plus the sessions
 *   played since the save (time played while the memory was off, or closed after the last save).
 * - **Player clock**: `players.playtime_s` (closed sessions, read at [load], then kept up by the leaves)
 *   plus the running session ([PlayerStates]).
 *
 * Call [load] once at start, before any player joins, then [subscribe].
 */
class DuckPlayClock(
    private val store: MemoryStore,
    private val journal: Journal,
    private val players: PlayerStates,
) : PlayClock {
    private val log = Log.memory
    private val lock = Any()
    private var worldMillis = 0L
    private var lastStepMillis: Long? = null
    private val closedSeconds = ConcurrentHashMap<UUID, Long>()

    /**
     * Reads the world clock (saved, or recomputed from `sessions` the first time) and the players' closed
     * play time, at [nowMillis]. Journal and store exceptions propagate.
     */
    suspend fun load(nowMillis: Long) {
        val saved = store.state(WORLD_KEY)?.toLongOrNull()
        val savedAt = store.state(SAVED_AT_KEY)?.toLongOrNull()
        val now = Instant.ofEpochMilli(nowMillis)
        val world = if (saved == null) {
            journal.read(READ_TIMEOUT_MS) { unionSeconds(it, null, now, emptySet()) }.also {
                log.info("world play clock recomputed from the sessions: ${it}s")
            }
        } else {
            val since = savedAt?.let { journal.read(READ_TIMEOUT_MS) { c -> unionSeconds(c, Instant.ofEpochMilli(it), now, emptySet()) } } ?: 0
            if (since > 0) log.info("world play clock: ${since}s played since its last save")
            saved + since
        }
        val playtimes = journal.read(READ_TIMEOUT_MS) { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_S
                statement.executeQuery("SELECT player_uuid, playtime_s FROM players").use { rows ->
                    buildMap { while (rows.next()) put(rows.getObject(1) as UUID, rows.getLong(2)) }
                }
            }
        }
        synchronized(lock) {
            worldMillis = world * 1_000
            lastStepMillis = nowMillis
        }
        closedSeconds.clear()
        closedSeconds.putAll(playtimes)
        if (saved == null) save(nowMillis)
        log.info("play clocks loaded: world ${world}s, ${playtimes.size} players")
    }

    /** Moves the world clock to [nowMillis]: the time since the last step (capped) counts if someone is online. */
    fun tick(nowMillis: Long) = step(nowMillis, players.online.isNotEmpty())

    /**
     * Follows joins and leaves: the world clock steps at each (a leave closes time played until then; a
     * join opens it), and a leave adds its session to the player's closed play time.
     */
    fun subscribe(bus: EventBus): Subscription =
        bus.subscribeSync(SUBSCRIBER, OWNER, { it is PlayerJoined || it is PlayerLeft }) { envelope ->
            when (val event = envelope.event) {
                is PlayerJoined -> step(envelope.ts, players.online.any { it.ref.uuid != event.player.uuid })
                is PlayerLeft -> {
                    step(envelope.ts, true)
                    closedSeconds.merge(event.player.uuid, event.sessionSeconds.coerceAtLeast(0), Long::plus)
                }
                else -> Unit
            }
        }

    /** Saves the world clock as read at [nowMillis] in `memory_state`. */
    suspend fun save(nowMillis: Long) {
        val world = worldPlaySeconds(nowMillis)
        store.setState(WORLD_KEY, world.toString())
        store.setState(SAVED_AT_KEY, nowMillis.toString())
        log.debug { "world play clock saved: ${world}s" }
    }

    override fun worldPlaySeconds(atMillis: Long): Long = synchronized(lock) {
        val pending = if (players.online.isNotEmpty()) gap(atMillis) else 0
        (worldMillis + pending) / 1_000
    }

    /**
     * The player's clock now, its running session read until [atMillis]: every closed session known
     * counts, even one that ended after [atMillis]. For a past instant (an interaction recorded late), read
     * [playerSecondsAt] from the sessions instead.
     */
    override fun playerPlaySeconds(player: UUID, atMillis: Long): Long {
        val closed = closedSeconds[player] ?: 0
        val live = players.get(player) ?: return closed
        return closed + (atMillis - live.joinedAt).coerceAtLeast(0) / 1_000
    }

    /**
     * Seconds of world play between [from] and [to], from `sessions` (the sessions of the players online
     * run until [to]): how long a canon entry went without mention, how far apart a gag's uses were.
     */
    suspend fun worldPlayBetween(from: Instant, to: Instant): Long {
        if (!to.isAfter(from)) return 0
        val open = players.online.map { it.sessionId }.toSet()
        return journal.read(READ_TIMEOUT_MS) { unionSeconds(it, from, to, open) }
    }

    private fun step(atMillis: Long, someoneOnline: Boolean) = synchronized(lock) {
        if (someoneOnline) worldMillis += gap(atMillis)
        lastStepMillis = maxOf(lastStepMillis ?: atMillis, atMillis)
    }

    private fun gap(atMillis: Long): Long = lastStepMillis?.let { (atMillis - it).coerceIn(0, MAX_GAP_MS) } ?: 0

    companion object {
        /** `memory_state` key of the world clock, in seconds. */
        const val WORLD_KEY = "world_play_s"

        /** `memory_state` key of when [WORLD_KEY] was saved, in epoch milliseconds. */
        const val SAVED_AT_KEY = "world_play_saved_at"

        /** The longest step of the world clock: a longer gap counts as this much. */
        const val MAX_GAP_MS = 120_000L

        const val SUBSCRIBER = "memory-play-clock"
        const val OWNER = "memory"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val QUERY_TIMEOUT_S = 20

        /**
         * Seconds [player] had played at [at], from `sessions`: each session opened by then counts until it
         * closed, or until [at] when it was still running then (the `playtime` formula of the sheet,
         * bounded at [at]). The Journal closes at its opening the sessions a crash left open, so an open
         * session is one being played. Runs on [connection] (the writer's, inside a write job).
         */
        fun playerSecondsAt(connection: Connection, player: UUID, at: Instant): Long = connection.query(
            "SELECT CAST(round(coalesce(sum(greatest(0, epoch(least(coalesce(left_at, ?), ?) - joined_at))), 0)) AS BIGINT) " +
                "FROM sessions WHERE player_uuid = ? AND joined_at <= ?",
            at, at, player, at,
        ) { getLong(1) }.single()

        /**
         * Seconds covered by at least one session between [from] (null: the beginning) and [to]: the union
         * of the session intervals, clipped ("islands and gaps"). An open session runs until [to] when its id
         * is in [open] (its player is online), else it counts for nothing (left open by a crash).
         */
        fun unionSeconds(connection: Connection, from: Instant?, to: Instant, open: Set<Long>): Long {
            val running = if (open.isEmpty()) "false" else "session_id IN (${open.joinToString(",")})"
            val sql = """
                WITH spans AS (
                    SELECT greatest(joined_at, ?) AS a,
                           least(coalesce(left_at, CASE WHEN $running THEN ? ELSE joined_at END), ?) AS b
                    FROM sessions
                ), played AS (SELECT a, b FROM spans WHERE b > a),
                marked AS (
                    SELECT a, b, CASE WHEN a <= max(b) OVER (ORDER BY a, b ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)
                                      THEN 0 ELSE 1 END AS starts
                    FROM played
                ), islands AS (
                    SELECT a, b, sum(starts) OVER (ORDER BY a, b ROWS UNBOUNDED PRECEDING) AS island FROM marked
                )
                SELECT coalesce(sum(date_diff('millisecond', s, e)), 0)
                FROM (SELECT min(a) AS s, max(b) AS e FROM islands GROUP BY island)
            """.trimIndent()
            return connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_S
                val end = OffsetDateTime.ofInstant(to, ZoneOffset.UTC)
                statement.setObject(1, OffsetDateTime.ofInstant(from ?: Instant.EPOCH, ZoneOffset.UTC))
                statement.setObject(2, end)
                statement.setObject(3, end)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1) / 1_000
                }
            }
        }
    }
}
