package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.Subscription
import io.github.clemsix6.narrator2.guard.GuardStates
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the engine knows right now about a player online. Times are epoch milliseconds.
 *
 * The position sampler (`PositionSampler`) refreshes the game mode, the position fields and the
 * player's condition once a second, as read at that examination: [biome], [structure] and [underground]
 * without the hysteresis of their events (`BiomeEntered`, `UndergroundChanged`). Joins, deaths and
 * respawns update them at once.
 *
 * @property gameMode e.g. `survival`.
 * @property biome biome id at the player's position; null until the first examination.
 * @property structure structure the player is in, if any (looked up every 5 s and in each new chunk).
 * @property underground no sky light and well below sea level (`SamplerRules.isUnderground`).
 * @property vehicle entity type of the vehicle or mount, if any.
 * @property lastDeathAt last death, if any: this session's, or at the join the one saved with the player
 *   (`GuardState.lastDeathAt`, kept 5 minutes), so that a reconnection does not hide a recent death.
 * @property lastMoveAt last time the player moved (2 blocks or more) or acted.
 * @property idle no action and no move for 5 minutes (`IdleChanged`).
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
 *
 * Joins, leaves, deaths and respawns come from the bus ([subscribe]); the position sampler writes the
 * position fields with [update] and refreshes [online] once a second.
 */
class PlayerStates {
    private val players = ConcurrentHashMap<UUID, LivePlayer>()

    @Volatile
    private var snapshot: List<LivePlayer> = emptyList()

    /** Immutable snapshot of the players online, in order of arrival, refreshed by [republish]. Any thread. */
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

    /** Refreshes [online] from the latest states (joins, leaves, deaths and respawns do it at once; the sampler at most once a second). */
    fun republish() {
        snapshot = players.values.sortedBy { it.joinedAt }
    }

    /**
     * Follows joins, leaves, deaths and respawns on [bus], inline on the server thread. [online] finds
     * the live player, for what events do not carry (game mode, health, food); null when not found.
     */
    fun subscribe(bus: EventBus, online: (UUID) -> ServerPlayer?): Subscription =
        bus.subscribeSync(SUBSCRIBER, OWNER, { follows(it) }) { envelope -> apply(envelope, online) }

    /** Applies one event of [subscribe]. Server thread only. */
    fun apply(envelope: Envelope, online: (UUID) -> ServerPlayer?) {
        val at = envelope.ts
        when (val event = envelope.event) {
            is PlayerJoined -> put(joined(event, at, online(event.player.uuid)))
            is PlayerLeft -> remove(event.player.uuid)
            is PlayerDied -> {
                update(event.player.uuid) { it.copy(pos = event.pos, health = 0f, lastDeathAt = at, lastMoveAt = at, idle = false) }
                republish()
            }
            is PlayerRespawned -> {
                val live = online(event.player.uuid)
                update(event.player.uuid) {
                    it.copy(
                        pos = event.pos,
                        health = live?.health ?: it.maxHealth,
                        maxHealth = live?.maxHealth ?: it.maxHealth,
                        food = live?.foodData?.foodLevel ?: it.food,
                        vehicle = null,
                        lastMoveAt = at,
                        idle = false,
                    )
                }
                republish()
            }
            else -> Unit
        }
    }

    private fun joined(event: PlayerJoined, at: Long, live: ServerPlayer?) = LivePlayer(
        ref = event.player,
        sessionId = event.sessionId,
        joinedAt = at,
        isOp = event.isOp,
        gameMode = live?.gameMode()?.serializedName ?: "survival",
        pos = event.pos,
        biome = null,
        structure = null,
        underground = false,
        health = live?.health ?: DEFAULT_HEALTH,
        maxHealth = live?.maxHealth ?: DEFAULT_HEALTH,
        food = live?.foodData?.foodLevel ?: DEFAULT_FOOD,
        vehicle = live?.vehicle?.typeId,
        lastDeathAt = live?.let { GuardStates.read(it).lastDeathAt },
        lastMoveAt = at,
        idle = false,
    )

    private companion object {
        const val SUBSCRIBER = "player_states"
        const val OWNER = "runtime"
        const val DEFAULT_HEALTH = 20f
        const val DEFAULT_FOOD = 20

        fun follows(event: GameEvent): Boolean =
            event is PlayerJoined || event is PlayerLeft || event is PlayerDied || event is PlayerRespawned
    }
}
