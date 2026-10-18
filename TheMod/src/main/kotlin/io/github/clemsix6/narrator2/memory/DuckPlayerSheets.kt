package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.memory.stats.PairStats
import io.github.clemsix6.narrator2.memory.stats.SheetStats
import io.github.clemsix6.narrator2.observer.PlayerStates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Player sheets from the database (M3 §3.5): [sheet] gathers, in one read on the Journal's read pool, what
 * the code computed (identity, `sheet_stats`, `pair_stats`, favor, respect and attention), what the Dreamer
 * and the Chronicler wrote (documents, traits, skills, relations) and the history (interactions, open canon,
 * the strongest memories); [render] is [SheetRenderer]'s.
 *
 * - Traits: not retired, and only the evidence still standing (a memory or interaction archived for
 *   another reason than a merge no longer proves anything); a trait left without evidence is not on the
 *   sheet. Their confidence is the one set: the decay is the renderer's, on the player's play clock.
 * - Stats: `sheet_stats` as stored, plus [SheetRenderer.PAIRS] built from `pair_stats` (time spent with each
 *   other player, PvP and griefs between them), the facts the relations rest on.
 * - History: [RECENT] latest interactions; [PlayerSheet.marked] the latest of valence ±3 ([MARKED]);
 *   [PlayerSheet.disrespect] every lack of respect not archived, newest first (its size is the count the
 *   sheet shows); the open canon about the player (nicknames and titles apart, [OPEN_CANON]); the
 *   [MEMORIES] strongest memories about them (importance × strength on the world clock).
 *
 * Reads only: the reader records what it shows (`recalls`). [zone] dates the rendering.
 */
class DuckPlayerSheets(
    private val journal: Journal,
    private val playClock: PlayClock,
    private val players: PlayerStates,
    zone: ZoneId,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
) : PlayerSheets {
    private val log = Log.memory
    private val renderer = SheetRenderer(zone)

    override suspend fun sheet(player: UUID, now: Long): PlayerSheet? {
        val started = System.nanoTime()
        val world = playClock.worldPlaySeconds(now)
        val played = playClock.playerPlaySeconds(player, now)
        val online = players.get(player) != null
        val sheet = journal.read(readTimeoutMs) { assemble(it, player, Instant.ofEpochMilli(now), world, played, online) }
        log.debug {
            if (sheet == null) {
                "sheet of $player: never seen"
            } else {
                "sheet of ${sheet.player.name}: ${sheet.docs.size} docs, ${sheet.traits.size} traits, ${sheet.skills.size} skills, " +
                    "${sheet.relations.size} relations, ${sheet.stats.size} stats, ${sheet.recent.size} interactions, " +
                    "${sheet.openCanon.size} open canon, ${sheet.memories.size} memories in ${(System.nanoTime() - started) / 1_000_000} ms"
            }
        }
        return sheet
    }

    override fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart>): String =
        renderer.render(sheet, detail, now, parts)

    private fun assemble(connection: Connection, player: UUID, now: Instant, world: Long, played: Long, online: Boolean): PlayerSheet? {
        val identity = connection.query(
            "SELECT name, first_seen, last_seen, sessions, is_op FROM players WHERE player_uuid = ?", player,
        ) { Identity(getString(1), instant(2), instant(3), getInt(4), getBoolean(5)) }.singleOrNull() ?: return null
        val names = Names.of(connection)
        val named = connection.query(
            "SELECT $CANON_COLUMNS FROM canon WHERE kind IN ('${CanonKind.NICKNAME.id}', '${CanonKind.TITLE.id}') " +
                "AND status = '${CanonStatus.ACTIVE.id}' AND NOT archived AND list_contains(players, ?) ORDER BY created_at DESC, id DESC",
            player,
        ) { canonEntry(names) }
        val reading = Favor.read(connection, player, now)
        return PlayerSheet(
            player = PlayerRef(player, identity.name),
            formerNames = connection.query(
                "SELECT name FROM player_names WHERE player_uuid = ? AND name <> ? ORDER BY first_seen, name", player, identity.name,
            ) { getString(1) },
            isOp = identity.isOp,
            firstSeen = identity.firstSeen,
            lastSeen = identity.lastSeen,
            sessions = identity.sessions,
            playedSeconds = played,
            online = online,
            nickname = named.firstOrNull { it.kind == CanonKind.NICKNAME },
            title = named.firstOrNull { it.kind == CanonKind.TITLE },
            docs = SheetSection.entries.mapNotNull { section ->
                MemoryDocs.read(connection, DocKey.Sheet(player, section).key)?.let { section to it }
            }.toMap(),
            traits = standing(connection, connection.traits("player_uuid = ? AND NOT retired", player)),
            skills = connection.skills("player_uuid = ?", player),
            relations = connection.relations("player_uuid = ?", player),
            stats = SheetStats.read(connection, player) + pairs(connection, player, names),
            favor = reading?.favor,
            favorTrend = reading?.trend,
            respect = reading?.respect,
            disrespect = interactions(connection, player, "disrespect", null),
            lastAttentionAgoPlayedSeconds = reading?.takeIf { it.lastAttentionAt != null }?.attentionDebtSeconds,
            recent = interactions(connection, player, "true", RECENT),
            marked = interactions(connection, player, "abs(valence) = 3", MARKED),
            openCanon = connection.query(
                "SELECT $CANON_COLUMNS FROM canon WHERE status IN ('${CanonStatus.PLANNED.id}', '${CanonStatus.ACTIVE.id}') AND NOT archived " +
                    "AND list_contains(players, ?) AND kind NOT IN ('${CanonKind.NICKNAME.id}', '${CanonKind.TITLE.id}') " +
                    "ORDER BY due_at ASC NULLS LAST, importance DESC, created_at DESC, id DESC LIMIT ?",
                player, OPEN_CANON,
            ) { canonEntry(names) },
            memories = connection.query(
                "SELECT $MEMORY_COLUMNS FROM memories WHERE list_contains(players, ?) AND NOT archived " +
                    "ORDER BY importance * greatest(${Decay.FLOOR}, pow(2, -greatest(0, ? - reinforced_play_s) / 3600.0 / stability_h)) DESC, " +
                    "occurred_to DESC, id DESC LIMIT ?",
                player, world, MEMORIES,
            ) { memoryEntry(names) },
        )
    }

    private class Identity(val name: String, val firstSeen: Instant, val lastSeen: Instant, val sessions: Int, val isOp: Boolean)

    /** The player's interactions that are not archived and match [where], newest first; all of them when [limit] is null. */
    private fun interactions(connection: Connection, player: UUID, where: String, limit: Int?): List<Interaction> = connection.query(
        "SELECT $INTERACTION_COLUMNS FROM interactions WHERE player_uuid = ? AND NOT archived AND ($where) ORDER BY ts DESC, id DESC" +
            (limit?.let { " LIMIT $it" } ?: ""),
        player,
    ) { interaction() }

    /**
     * [traits] with only their evidence still standing: a memory not archived, or merged into another (which
     * carries it on), an interaction not archived. A trait with none left is dropped.
     */
    private fun standing(connection: Connection, traits: List<Trait>): List<Trait> {
        val ids = traits.flatMap { trait -> trait.evidence.map { it.id } }.toSet()
        if (ids.isEmpty()) return emptyList()
        val valid = connection.query(
            "SELECT id FROM memories WHERE list_contains(?, id) AND (NOT archived OR merged_into IS NOT NULL) " +
                "UNION ALL SELECT id FROM interactions WHERE list_contains(?, id) AND NOT archived",
            longArray(ids), longArray(ids),
        ) { getLong(1) }.toSet()
        return traits.map { trait -> trait.copy(evidence = trait.evidence.filter { it.id in valid }) }.filter { it.evidence.isNotEmpty() }
    }

    /** The `pairs` stat: what the player did with each other player (`pair_stats`), most time together first; none when they met nobody. */
    private fun pairs(connection: Connection, player: UUID, names: Names): Map<String, SheetStat> {
        val pairs = PairStats.read(connection, player)
        if (pairs.isEmpty()) return emptyMap()
        val detail = buildJsonObject {
            put(
                SheetRenderer.PAIRS,
                JsonArray(
                    pairs.map { pair ->
                        val mine = pair.a == player
                        val other = if (mine) pair.b else pair.a
                        JsonObject(
                            mapOf(
                                "uuid" to JsonPrimitive(other.toString()),
                                "name" to JsonPrimitive(names.ref(other).name),
                                "minutes" to JsonPrimitive(pair.minutesTogether),
                                "minutes_7d" to JsonPrimitive(pair.minutesTogether7d),
                                "pvp_won" to JsonPrimitive(if (mine) pair.pvpAOnB else pair.pvpBOnA),
                                "pvp_lost" to JsonPrimitive(if (mine) pair.pvpBOnA else pair.pvpAOnB),
                                "griefs_done" to JsonPrimitive(if (mine) pair.griefsAOnB else pair.griefsBOnA),
                                "griefs_suffered" to JsonPrimitive(if (mine) pair.griefsBOnA else pair.griefsAOnB),
                            ),
                        )
                    },
                ),
            )
        }
        val computedAt = pairs.maxOf { it.computedAt }
        return mapOf(SheetRenderer.PAIRS to SheetStat(SheetRenderer.PAIRS, pairs.size.toDouble(), detail, computedAt))
    }

    companion object {
        /** Latest interactions on a sheet. */
        const val RECENT = 20

        /** Latest interactions of valence ±3 read for the "marked" lines (those already among the latest are not repeated). */
        const val MARKED = 30

        const val OPEN_CANON = 12
        const val MEMORIES = 8
        private const val READ_TIMEOUT_MS = 10_000L
    }
}
