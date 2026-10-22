package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.memory.uuid
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** An object of the reserves: [spec] is the armorer's draft as JSON, rebuilt into an item when a chest takes it. */
data class StockRow(val id: Long, val kind: StockKind, val name: String, val spec: String, val createdAt: Instant)

/** A champion's sheet, as kept in the database. [status]: `active`, `slain` (by a player), `died` (otherwise), `gone`. */
data class ChampionRow(
    val id: Long,
    val entity: UUID,
    val player: UUID,
    val playerName: String,
    val mob: String,
    val name: String,
    val spec: String,
    val spawnedAt: Instant,
    val expiresAt: Instant,
    val status: String,
    val killer: String? = null,
    val relic: String? = null,
)

/**
 * The armorer's tables in the Journal (`world/narrator2/narrator.duckdb`): `relic_stock` (the chests' reserves and
 * what became of each object) and `champions` (each champion's sheet and end). Created by [open] when the Narrator
 * starts, not by a migration of the Journal: nothing else reads them, and the Journal's schema stays as it is. Writes go
 * through the Journal's writer ([Journal.write]), reads through its pool; never on the server thread.
 */
class RelicsStore(private val journal: Journal) {
    /** Creates the tables when they do not exist yet. */
    suspend fun open() = journal.write(TIMEOUT_MS) { connection ->
        connection.createStatement().use { statement ->
            statement.execute(STOCK_TABLE)
            statement.execute(CHAMPIONS_TABLE)
        }
    }

    /** The objects waiting in the reserves, oldest first. */
    suspend fun stock(): List<StockRow> = journal.read(TIMEOUT_MS) { connection ->
        connection.query("SELECT id, kind, name, spec, created_at FROM relic_stock WHERE status = 'stock' ORDER BY id") {
            StockRow(getLong(1), StockKind.of(getString(2)) ?: StockKind.RELIC, getString(3), getString(4), instant(5))
        }
    }

    /** Puts an object of [kind] in the reserves, and returns its id. */
    suspend fun addStock(kind: StockKind, name: String, spec: String, at: Instant, conversationId: String): Long = journal.write(TIMEOUT_MS) { connection ->
        val id = nextId(connection, "relic_stock")
        connection.update(
            "INSERT INTO relic_stock (id, kind, name, spec, created_at, conversation_id, status) VALUES (?, ?, ?, ?, ?, ?, 'stock')",
            id, kind.id, name, spec, at, conversationId,
        )
        id
    }

    /** The object [id] left the reserves at [at]: [status] `found` in a chest ([table]) by [by], or `broken` (it could not be built). */
    suspend fun taken(id: Long, status: String, at: Instant, by: String?, table: String?) = journal.write(TIMEOUT_MS) { connection ->
        connection.update("UPDATE relic_stock SET status = ?, taken_at = ?, taken_by = ?, loot_table = ? WHERE id = ?", status, at, by, table, id)
    }

    /** The champions still `active`. */
    suspend fun activeChampions(): List<ChampionRow> = journal.read(TIMEOUT_MS) { connection ->
        connection.query(
            "SELECT id, entity_uuid, player_uuid, player, mob, name, spec, spawned_at, expires_at, status FROM champions WHERE status = 'active' ORDER BY id",
        ) {
            ChampionRow(getLong(1), uuid(2), uuid(3), getString(4), getString(5), getString(6), getString(7), instant(8), instant(9), getString(10))
        }
    }

    /** Records a champion that just appeared, and returns its id. */
    suspend fun addChampion(row: ChampionRow, conversationId: String): Long = journal.write(TIMEOUT_MS) { connection ->
        val id = nextId(connection, "champions")
        connection.update(
            "INSERT INTO champions (id, entity_uuid, player_uuid, player, mob, name, spec, spawned_at, expires_at, status, conversation_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)",
            id, row.entity, row.player, row.playerName, row.mob, row.name, row.spec, row.spawnedAt, row.expiresAt, conversationId,
        )
        id
    }

    /** A champion's creature changed (a conversion: a zombie drowned): its new [entity]. */
    suspend fun moved(id: Long, entity: UUID) = journal.write(TIMEOUT_MS) { connection ->
        connection.update("UPDATE champions SET entity_uuid = ? WHERE id = ?", entity, id)
    }

    /** Champion [id] ended at [at]: `slain` by [killer] (with the [fight]), `died` otherwise, or `gone`. */
    suspend fun ended(id: Long, status: String, at: Instant, killer: String?, fight: String?) = journal.write(TIMEOUT_MS) { connection ->
        connection.update("UPDATE champions SET status = ?, ended_at = ?, killer = ?, fight = ? WHERE id = ? AND status = 'active'", status, at, killer, fight, id)
    }

    /** The relic forged for champion [id]'s killer. */
    suspend fun relicGiven(id: Long, relic: String) = journal.write(TIMEOUT_MS) { connection ->
        connection.update("UPDATE champions SET relic = ? WHERE id = ?", relic, id)
    }

    /** The last champions, the latest first, for `/narrator relics`. */
    suspend fun lastChampions(limit: Int): List<ChampionRow> = journal.read(TIMEOUT_MS) { connection ->
        connection.query(
            "SELECT id, entity_uuid, player_uuid, player, mob, name, spec, spawned_at, expires_at, status, killer, relic FROM champions ORDER BY id DESC LIMIT ?",
            limit,
        ) {
            ChampionRow(
                getLong(1), uuid(2), uuid(3), getString(4), getString(5), getString(6), getString(7), instant(8), instant(9), getString(10),
                getString(11), getString(12),
            )
        }
    }

    /** How many objects of each kind were found in chests since [since]. */
    suspend fun foundSince(since: Instant): Map<StockKind, Int> = journal.read(TIMEOUT_MS) { connection ->
        connection.query("SELECT kind, count(*) FROM relic_stock WHERE status = 'found' AND taken_at >= ? GROUP BY kind", since) {
            (StockKind.of(getString(1)) ?: StockKind.RELIC) to getInt(2)
        }.toMap()
    }

    private fun nextId(connection: Connection, table: String): Long =
        connection.query("SELECT coalesce(max(id), 0) + 1 FROM $table") { getLong(1) }.single()

    private companion object {
        const val TIMEOUT_MS = 10_000L

        const val STOCK_TABLE = """
            CREATE TABLE IF NOT EXISTS relic_stock (
                id              BIGINT PRIMARY KEY,
                kind            VARCHAR NOT NULL,       -- relic | consumable | book
                name            VARCHAR NOT NULL,
                spec            VARCHAR NOT NULL,       -- the armorer's draft, JSON
                created_at      TIMESTAMPTZ NOT NULL,
                conversation_id VARCHAR,
                status          VARCHAR NOT NULL,       -- stock | found | broken
                taken_at        TIMESTAMPTZ,
                taken_by        VARCHAR,                -- the player who opened the chest, when known
                loot_table      VARCHAR
            )"""

        const val CHAMPIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS champions (
                id              BIGINT PRIMARY KEY,
                entity_uuid     UUID NOT NULL,
                player_uuid     UUID NOT NULL,          -- the player it was made for
                player          VARCHAR NOT NULL,
                mob             VARCHAR NOT NULL,
                name            VARCHAR NOT NULL,
                spec            VARCHAR NOT NULL,       -- the armorer's draft, JSON
                spawned_at      TIMESTAMPTZ NOT NULL,
                expires_at      TIMESTAMPTZ NOT NULL,
                status          VARCHAR NOT NULL,       -- active | slain | died | gone
                ended_at        TIMESTAMPTZ,
                killer          VARCHAR,
                fight           VARCHAR,
                relic           VARCHAR,                -- the relic its killer got, described
                conversation_id VARCHAR
            )"""
    }
}
