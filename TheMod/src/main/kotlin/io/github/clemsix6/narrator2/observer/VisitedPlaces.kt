package io.github.clemsix6.narrator2.observer

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/**
 * The biomes and structures a player has been in (ids such as `minecraft:plains`,
 * `minecraft:village_plains`). Immutable: a new visit replaces the whole value.
 */
data class Visited(val biomes: Set<String> = emptySet(), val structures: Set<String> = emptySet()) {
    companion object {
        /** Saved as `{biomes: [id…], structures: [id…]}`. */
        val CODEC: Codec<Visited> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.listOf().optionalFieldOf("biomes", emptyList()).forGetter { it.biomes.toList() },
                Codec.STRING.listOf().optionalFieldOf("structures", emptyList()).forGetter { it.structures.toList() },
            ).apply(instance) { biomes, structures -> Visited(biomes.toSet(), structures.toSet()) }
        }
    }
}

/**
 * The places each player has visited (M1_M2 §7.4), for the `firstVisit` of `BiomeEntered` and
 * `StructureEntered`. Kept in a persistent player attachment `narrator2:visited`, saved with the
 * player and copied to the new player object after a death: no database read on the server thread,
 * and it stays consistent with the world (a restored world backup restores it too).
 *
 * Server thread only.
 */
object VisitedPlaces {
    val TYPE: AttachmentType<Visited> =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "visited")) { builder ->
            builder.persistent(Visited.CODEC).copyOnDeath()
        }

    /** What [player] has visited so far. */
    fun of(player: ServerPlayer): Visited = player.getAttached(TYPE) ?: Visited()

    /** Records that [player] is in biome [id]; true when it is their first time there. */
    fun visitBiome(player: ServerPlayer, id: String): Boolean {
        val visited = of(player)
        if (id in visited.biomes) return false
        player.setAttached(TYPE, visited.copy(biomes = visited.biomes + id))
        return true
    }

    /** Records that [player] is in structure [id]; true when it is their first time in one. */
    fun visitStructure(player: ServerPlayer, id: String): Boolean {
        val visited = of(player)
        if (id in visited.structures) return false
        player.setAttached(TYPE, visited.copy(structures = visited.structures + id))
        return true
    }
}
