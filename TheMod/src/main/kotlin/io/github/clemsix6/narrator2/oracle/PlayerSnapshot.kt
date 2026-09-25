package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.observer.typeId
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

/**
 * Where a player is and how they are, read on the server thread in one go ([capture]) and readable from
 * any thread afterwards. `list_players`, the situation report and the briefing write it with [describe].
 *
 * @property dimension full id, e.g. `minecraft:the_nether`.
 * @property biome full id at the feet, e.g. `minecraft:dripstone_caves`.
 * @property structure the structure the player is in, full id; null outside any.
 * @property depth blocks from the feet up to the surface (see [EnclosureProbe.depth]); null under a ceiling.
 * @property vehicle entity type of the vehicle or mount, full id.
 */
data class PlayerSnapshot(
    val ref: PlayerRef,
    val gameMode: String,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val biome: String,
    val structure: String?,
    val enclosure: Enclosure,
    val depth: Int?,
    val health: Float,
    val maxHealth: Float,
    val food: Int,
    val vehicle: String?,
) {
    val dead: Boolean get() = health <= 0f

    /**
     * One line, e.g. `overworld -120 -58 330 · dripstone_caves · cave, 41 below surface · in mineshaft ·
     * hp 14/20 food 12`; the game mode is named unless it is survival.
     */
    fun describe(): String = buildList {
        add("${OracleText.dimension(dimension)} ${OracleText.coords(x, y, z)}")
        add(OracleText.bare(biome))
        add(shelter())
        structure?.let { add("in ${OracleText.bare(it)}") }
        vehicle?.let { add("riding ${OracleText.bare(it)}") }
        add(if (dead) "dead" else "hp ${OracleText.number(health)}/${OracleText.number(maxHealth)} food $food")
        if (gameMode != "survival") add(gameMode)
    }.joinToString(" · ")

    /** `cave, 41 below surface`, `open_sky`, `sheltered`, `enclosed`. */
    fun shelter(): String = Enclosure.label(enclosure, depth)

    /** Blocks between this player and [other], or null in another dimension. */
    fun distanceTo(other: PlayerSnapshot): Int? =
        if (other.dimension != dimension) null else OracleText.distance(other.x - x, other.y - y, other.z - z)

    companion object {
        /** Reads [player] and around them. Server thread only; reads loaded chunks only. */
        fun capture(player: ServerPlayer): PlayerSnapshot = capture(player, WorldReader(player.level()))

        /** Like [capture], with a [reader] of the player's level (shared between the players of one level). */
        fun capture(player: ServerPlayer, reader: WorldReader): PlayerSnapshot {
            val feet = player.blockPosition()
            val head = BlockPos.containing(player.eyePosition)
            val probe = Enclosure.probe(reader, feet, head)
            return PlayerSnapshot(
                ref = player.ref(),
                gameMode = player.gameMode().serializedName,
                dimension = reader.level.dimensionId,
                x = feet.x,
                y = feet.y,
                z = feet.z,
                biome = reader.biome(feet),
                structure = Structures.at(reader, feet),
                enclosure = Enclosure.classify(probe),
                depth = probe.depth,
                health = player.health,
                maxHealth = player.maxHealth,
                food = player.foodData.foodLevel,
                vehicle = player.vehicle?.typeId,
            )
        }
    }
}
