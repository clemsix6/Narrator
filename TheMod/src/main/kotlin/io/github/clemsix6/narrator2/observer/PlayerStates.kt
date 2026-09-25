package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the engine knows right now about a player online. Times are epoch milliseconds.
 *
 * @property gameMode e.g. `survival`.
 * @property structure structure the player is in, if any.
 * @property lastDeathAt last death this session, if any.
 * @property lastMoveAt last time the player moved or acted.
 */
data class LivePlayer(
    val ref: PlayerRef,
    val sessionId: Long,
    val joinedAt: Long,
    val isOp: Boolean,
    val gameMode: String,
    val pos: Pos,
    val biome: String?,
    val structure: String?,
    val underground: Boolean,
    val health: Float,
    val maxHealth: Float,
    val food: Int,
    val vehicle: String?,
    val lastDeathAt: Long?,
    val lastMoveAt: Long,
    val idle: Boolean,
)

/**
 * The live state of the players online, read by the Director, the tools and the guards from any
 * thread, written on the server thread only.
 */
class PlayerStates {
    private val players = ConcurrentHashMap<UUID, LivePlayer>()

    @Volatile
    private var snapshot: List<LivePlayer> = emptyList()

    /** Immutable snapshot of the players online, refreshed by [republish]. Any thread. */
    val online: List<LivePlayer> get() = snapshot

    /** The latest state of a player online. Any thread. */
    fun get(uuid: UUID): LivePlayer? = players[uuid]

    /** A player online, by name, ignoring case. Any thread. */
    fun byName(name: String): LivePlayer? = players.values.firstOrNull { it.ref.name.equals(name, ignoreCase = true) }

    /** A player joined. Server thread only. */
    fun put(player: LivePlayer) {
        players[player.ref.uuid] = player
        republish()
    }

    /** A player left. Server thread only. */
    fun remove(uuid: UUID) {
        players.remove(uuid)
        republish()
    }

    /** Changes the state of a player online; does nothing for a player who is not. Server thread only. */
    fun update(uuid: UUID, change: (LivePlayer) -> LivePlayer) {
        players.computeIfPresent(uuid) { _, player -> change(player) }
    }

    /** Refreshes [online] from the latest states (joins and leaves do it at once; the sampler at most once a second). */
    fun republish() {
        snapshot = players.values.sortedBy { it.joinedAt }
    }
}
