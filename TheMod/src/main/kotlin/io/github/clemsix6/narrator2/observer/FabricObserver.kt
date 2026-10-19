package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.SleepEnded
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.guard.DeathAttribution
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.runtime.Phase
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRules
import java.util.UUID

/**
 * The part of the Observer fed by Fabric API events (M1_M2 §7.2): blocks broken, damage, deaths and
 * kills, dimension changes, sleep, public chat, joins, leaves and respawns, and the position sampler
 * (§7.4). Its persistent attachment type (visited places) is registered in [register].
 *
 * Every handler runs on the server thread, inside `ObserverState.hook` (an exception never reaches the
 * game), publishes on the bus (which never blocks) and ignores Fabric's fake players, the block
 * breakers and placers of other mods. The players of in-game tests are observed like real ones.
 */
object FabricObserver {
    private val log = Log.observer

    /** The running server's parts; replaced by [attach] when a server starts. */
    @Volatile
    private var attached: Parts? = null

    /** What the Observer's Fabric module keeps for one server. Server thread only. */
    private class Parts(val state: ObserverState) {
        val damage = DamageCoalescer { state.publish(it) }
        val sampler = PositionSampler(state)

        /** Open sessions, by player. */
        val sessions = HashMap<UUID, Session>()

        /** Players asleep, with the world's clock and the server tick when they lay down. */
        val sleepers = HashMap<UUID, Sleep>()

        /** The Observer's clock, in milliseconds: server ticks × 50. */
        fun now(): Long = state.services.server.tickCount * SamplerRules.MS_PER_TICK
    }

    private class Session(val id: Long, val joinedAt: Long)

    private class Sleep(val clock: Long, val tick: Int)

    /** The portals a dimension change can go through, with the name `DimensionChanged.via` gives them. */
    private val portals: List<Pair<Portal, String>> by lazy {
        listOf(
            Blocks.NETHER_PORTAL as Portal to "nether_portal",
            Blocks.END_PORTAL as Portal to "end_portal",
            Blocks.END_GATEWAY as Portal to "end_gateway",
        )
    }

    private const val OTHER_TRAVEL = "other"
    private const val END_EXIT = "end_exit"

    /**
     * Registers the Fabric event handlers and the visited places' attachment type. Once, at mod
     * initialization, before any world loads. Handlers reach the running server through
     * [ObserverState.current] and do nothing without one.
     */
    fun register() {
        // Touching the type registers it: a persistent type must exist before the first world loads.
        val visited = VisitedPlaces.TYPE
        log.debug { "attachment ${visited.identifier()}" }
        PlayerBlockBreakEvents.AFTER.register { level, player, pos, state, _ -> on("block_break") { blockBroken(it, level, player, pos, state) } }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, blocked -> on("damage") { damaged(it, entity, source, taken, blocked) } }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source -> on("death") { died(it, entity, source) } }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, origin, destination ->
            on("dimension") { changedLevel(it, player, origin, destination) }
        }
        EntitySleepEvents.START_SLEEPING.register { entity, pos -> on("sleep_start") { startedSleeping(it, entity, pos) } }
        EntitySleepEvents.STOP_SLEEPING.register { entity, pos -> on("sleep_end") { stoppedSleeping(it, entity, pos) } }
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, params -> on("chat") { chatted(it, message, sender, params) } }
        ServerPlayerEvents.JOIN.register { player -> on("join") { joined(it, player) } }
        ServerPlayerEvents.LEAVE.register { player -> on("leave") { left(it, player) } }
        ServerPlayerEvents.AFTER_RESPAWN.register { old, player, alive -> on("respawn") { respawned(it, old, player, alive) } }
        ServerTickEvents.END_SERVER_TICK.register { server -> on("tick") { ticked(it, server) } }
    }

    /** Creates the per-server parts (damage coalescer, sampler, sessions) for [state]. When a server starts. */
    fun attach(state: ObserverState) {
        val parts = Parts(state)
        state.onStop(parts.damage)
        attached = parts
    }

    /** Runs [body] for hook [name] with the running server's parts, inside `ObserverState.hook`; nothing without a running server. */
    private inline fun on(name: String, body: (Parts) -> Unit) {
        val state = ObserverState.current() ?: return
        val parts = attached?.takeIf { it.state === state } ?: return
        state.hook(name) { body(parts) }
    }

    private fun blockBroken(parts: Parts, level: Level, player: Player, pos: BlockPos, state: BlockState) {
        val serverLevel = level as? ServerLevel ?: return
        val breaker = player as? ServerPlayer ?: return
        // The index forgets the block whoever broke it: it is gone, even when a mod's fake player broke it.
        // The world already holds air here (AFTER): the entry is checked against the broken state.
        val placedBy = PlacedBlocks.unmark(serverLevel, pos, state)
        if (breaker is FakePlayer) return
        parts.state.publish(
            BlockBroken(
                breaker.ref(), pos.toPos(level), state.blockId,
                tool = breaker.mainHandItem.idOrNull,
                creative = breaker.isCreative,
                correctTool = breaker.hasCorrectToolForDrops(state),
                placedBy = placedBy,
            ),
        )
    }

    /** Not called for a fatal hit (see [died]). */
    private fun damaged(parts: Parts, victim: LivingEntity, source: DamageSource, amount: Float, blocked: Boolean) {
        val damageType = source.typeHolder().registeredName
        val attacker = source.entity
        if (victim is ServerPlayer && victim !is FakePlayer) {
            parts.damage.taken(
                victim.ref(), victim.pos(), damageType, amount.toDouble(), attacker?.let { EntityRef.of(it) }, blocked,
                victim.health.toDouble(), parts.now(),
            )
        }
        if (attacker is ServerPlayer && attacker !is FakePlayer && attacker !== victim && victim !is FakePlayer) {
            parts.state.publish(
                DamageDealt(
                    attacker.ref(), victim.blockPosition().toPos(victim.level()), EntityRef.of(victim), amount.toDouble(),
                    weapon = source.weaponItem?.idOrNull, damageType = damageType, victimHealth = victim.health.toDouble(),
                ),
            )
        }
    }

    /** A death: `PlayerDied` for a player, `EntityKilled` when a player gets the kill (both in PvP). */
    private fun died(parts: Parts, victim: LivingEntity, source: DamageSource) {
        val level = victim.level() as? ServerLevel ?: return
        if (victim is FakePlayer) return
        val killer = source.entity ?: victim.killCredit
        val weapon = source.weaponItem?.idOrNull
        if (victim is ServerPlayer) {
            parts.damage.flush(victim.uuid)
            val attribution = attribution(parts, victim, source)
            parts.state.publish(
                PlayerDied(
                    victim.ref(), victim.pos(), source.typeHolder().registeredName,
                    killer = killer?.let { EntityRef.of(it) },
                    weapon = weapon,
                    // Read at the head of `die` by T06's mixin, before the combat tracker forgets the context.
                    message = DeathMessages.take(victim.uuid) ?: source.getLocalizedDeathMessage(victim).string,
                    xpLevel = victim.experienceLevel,
                    keptInventory = attribution.keptInventory || level.gameRules.get(GameRules.KEEP_INVENTORY),
                    narrator = attribution.narrator,
                ),
            )
        }
        if (killer is ServerPlayer && killer !is FakePlayer && killer !== victim) {
            parts.state.publish(
                EntityKilled(
                    killer.ref(), victim.blockPosition().toPos(level), EntityRef.of(victim), weapon,
                    pvp = victim is ServerPlayer,
                    tamed = (victim as? OwnableEntity)?.ownerReference != null,
                ),
            )
        }
    }

    /** The guards' view of a death; a failure there must not lose the death itself. */
    private fun attribution(parts: Parts, victim: ServerPlayer, source: DamageSource): DeathAttribution =
        try {
            parts.state.guards.deathAttribution(victim, source)
        } catch (e: Exception) {
            log.error("death attribution failed for ${victim.plainTextName}; recorded as not the Narrator's", e)
            DeathAttribution(narrator = false, keptInventory = false)
        }

    private fun changedLevel(parts: Parts, player: ServerPlayer, origin: ServerLevel, destination: ServerLevel) {
        if (player is FakePlayer) return
        val portal = portalTaken(player, origin)
        val arrival = player.blockPosition()
        // The live state holds the last sampled position (at most a second old). It is in another dimension only
        // after two trips within a second: then the origin dimension at least is right.
        val from = portal?.second?.toPos(origin)
            ?: parts.state.players.get(player.uuid)?.pos?.takeIf { it.dim == origin.dimensionId }
            ?: arrival.toPos(origin)
        parts.state.publish(DimensionChanged(player.ref(), arrival.toPos(destination), from, portal?.first ?: OTHER_TRAVEL))
    }

    /**
     * The portal [player] just went through, and where they entered it; null for another travel (a
     * command). The portal countdown stays set for a while after a trip, so only a countdown past the
     * portal's delay means that this travel is the portal's.
     */
    private fun portalTaken(player: ServerPlayer, origin: ServerLevel): Pair<String, BlockPos>? {
        val process = player.portalProcess ?: return null
        for ((portal, name) in portals) {
            if (process.isSamePortal(portal) && process.portalTime > portal.getPortalTransitionTime(origin, player)) {
                return name to process.entryPosition
            }
        }
        return null
    }

    private fun startedSleeping(parts: Parts, entity: LivingEntity, pos: BlockPos) {
        val player = entity as? ServerPlayer ?: return
        if (player is FakePlayer) return
        val level = player.level()
        parts.sleepers[player.uuid] = Sleep(level.defaultClockTime, level.server.tickCount)
        parts.state.publish(SleepStarted(player.ref(), pos.toPos(level), level.getBlockState(pos).blockId))
    }

    /** Fabric only calls it for an entity that was asleep. */
    private fun stoppedSleeping(parts: Parts, entity: LivingEntity, pos: BlockPos) {
        val player = entity as? ServerPlayer ?: return
        if (player is FakePlayer) return
        val level = player.level()
        val nightSkipped = parts.sleepers.remove(player.uuid)?.let {
            SamplerRules.nightSkipped(level.defaultClockTime - it.clock, (level.server.tickCount - it.tick).toLong())
        }
        parts.state.publish(SleepEnded(player.ref(), pos.toPos(level), nightSkipped))
    }

    /** Public chat only: `/say` and `/me` come through the command hook, private messages never reach this event. */
    private fun chatted(parts: Parts, message: PlayerChatMessage, sender: ServerPlayer, params: ChatType.Bound) {
        if (sender is FakePlayer || !params.chatType().`is`(ChatType.CHAT)) return
        parts.state.publish(ChatMessage(sender.ref(), ChatChannel.PUBLIC, message.signedContent(), emptyList()))
    }

    private fun joined(parts: Parts, player: ServerPlayer) {
        if (player is FakePlayer) return
        val services = parts.state.services
        parts.sessions.remove(player.uuid)?.let { log.warn("${player.plainTextName} joined again without leaving session ${it.id}; that session is dropped") }
        val session = Session(services.journal.nextId(IdKind.SESSION), services.clock.millis())
        parts.sessions[player.uuid] = session
        val firstTime = player.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0
        val isOp = services.server.playerList.isOp(player.nameAndId())
        parts.state.publish(PlayerJoined(player.ref(), player.pos(), session.id, firstTime, isOp))
        parts.sampler.joined(player)
    }

    /** Also at the server stop, between `SERVER_STOPPING` and `SERVER_STOPPED`. Once per session: a second call finds no session. */
    private fun left(parts: Parts, player: ServerPlayer) {
        if (player is FakePlayer) return
        val uuid = player.uuid
        parts.damage.flush(uuid)
        parts.sleepers.remove(uuid)
        parts.sampler.left(uuid)
        val session = parts.sessions.remove(uuid) ?: return
        val services = parts.state.services
        val seconds = ((services.clock.millis() - session.joinedAt) / 1000).coerceAtLeast(0)
        val reason = if (services.phase == Phase.STOPPING) LeaveReason.SERVER_STOP else LeaveReason.LEAVE
        parts.state.publish(PlayerLeft(player.ref(), player.pos(), session.id, seconds, reason))
    }

    /**
     * [player] is the new player object. `alive` is the way out of the End after the credits, which
     * goes through a respawn and not through a dimension change event.
     */
    private fun respawned(parts: Parts, old: ServerPlayer, player: ServerPlayer, alive: Boolean) {
        if (player is FakePlayer) return
        val leftTheEnd = alive && old.level() !== player.level()
        if (leftTheEnd) parts.state.publish(DimensionChanged(player.ref(), player.pos(), old.pos(), END_EXIT))
        parts.state.publish(PlayerRespawned(player.ref(), player.pos(), alive))
        parts.sampler.restart(player, if (leftTheEnd) SampleReason.DIMENSION else SampleReason.RESPAWN)
    }

    private fun ticked(parts: Parts, server: MinecraftServer) {
        if (server !== parts.state.services.server) return
        val now = parts.now()
        parts.damage.tick(now)
        parts.sampler.tick(server, now)
    }
}
