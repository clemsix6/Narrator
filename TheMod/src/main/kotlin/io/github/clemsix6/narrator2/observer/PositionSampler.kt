package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.SampleReason
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.UUID
import java.util.function.Predicate

/**
 * Where the players are and how they are doing (M1_M2 §7.4): each online player is examined once a
 * second, the load spread over the ticks (the player at index `i` on ticks where `tick % 20 == i % 20`).
 * An examination reads the world into an [Observation], lets the player's [PlayerTrack] decide what to
 * publish (`positions` rows, teleports, biomes, structures, underground, critical health, idleness),
 * and writes the position fields of [PlayerStates], whose snapshot is refreshed once a second.
 *
 * Reads only loaded chunks: a player whose chunk is not loaded yet is skipped until it is. Server
 * thread only.
 */
class PositionSampler(private val state: ObserverState) {
    private val tracks = HashMap<UUID, PlayerTrack>()
    private var changed = false

    /** [player] joined: their first examination writes a `join` row. */
    fun joined(player: ServerPlayer) {
        tracks[player.uuid] = PlayerTrack(player.ref())
    }

    /** [player] respawned or left the End: their next examination writes a row for [reason], and their jump is no teleport. */
    fun restart(player: ServerPlayer, reason: SampleReason) {
        tracks.getOrPut(player.uuid) { PlayerTrack(player.ref()) }.restart(reason)
    }

    /** The player left. */
    fun left(uuid: UUID) {
        tracks.remove(uuid)
    }

    /** End of a server tick: examines the players whose turn it is. [now] is the Observer's clock. */
    fun tick(server: MinecraftServer, now: Long) {
        val slot = server.tickCount % SamplerRules.EXAMINE_EVERY_TICKS
        val players = server.playerList.players
        var index = slot
        while (index < players.size) {
            examine(players[index], now)
            index += SamplerRules.EXAMINE_EVERY_TICKS
        }
        if (slot == 0 && changed) {
            changed = false
            state.players.republish()
        }
    }

    private fun examine(player: ServerPlayer, now: Long) {
        if (player is FakePlayer || player.isRemoved || player.isDeadOrDying) return
        val level = player.level()
        val at = player.blockPosition()
        if (!level.isLoaded(at)) return
        val track = tracks.getOrPut(player.uuid) { PlayerTrack(player.ref()) }
        val pos = at.toPos(level)
        val structureDue = track.structureDue(pos, now)
        val obs = Observation(
            pos = pos,
            biome = level.getBiome(at).registeredName,
            structureChecked = structureDue,
            structure = if (structureDue) structureAt(level, at) else null,
            underground = SamplerRules.isUnderground(level.canSeeSky(at), at.y, level.seaLevel),
            skyLight = level.getBrightness(LightLayer.SKY, at),
            blockLight = level.getBrightness(LightLayer.BLOCK, at),
            health = player.health.toDouble(),
            food = player.foodData.foodLevel,
            vehicle = player.vehicle?.typeId,
            sinceActionMs = Util.getMillis() - player.lastActionTime,
        )
        val exam = track.examine(obs, now, PlayerVisits(player))
        exam.events.forEach { state.publish(it) }

        val lastMoveAt = state.services.clock.millis() - exam.inactiveMs
        state.players.update(player.uuid) {
            it.copy(
                gameMode = player.gameMode().serializedName,
                pos = pos,
                biome = obs.biome,
                structure = track.structure,
                underground = obs.underground,
                health = player.health,
                maxHealth = player.maxHealth,
                food = obs.food,
                vehicle = obs.vehicle,
                lastMoveAt = lastMoveAt,
                idle = track.idle,
            )
        }
        changed = true
    }

    /** The structure with a piece at [at], e.g. `minecraft:village_plains`; null outside any. */
    private fun structureAt(level: ServerLevel, at: BlockPos): String? {
        val start = level.structureManager().getStructureWithPieceAt(at, ANY_STRUCTURE)
        if (!start.isValid) return null
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.structure)?.toString()
    }

    /** First visits, kept on the player ([VisitedPlaces]). */
    private class PlayerVisits(private val player: ServerPlayer) : Visits {
        override fun biome(id: String) = VisitedPlaces.visitBiome(player, id)

        override fun structure(id: String) = VisitedPlaces.visitStructure(player, id)
    }

    private companion object {
        val ANY_STRUCTURE = Predicate<Holder<Structure>> { true }
    }
}
