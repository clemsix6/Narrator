package io.github.clemsix6.narrator2.tool.forge

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The forger's tools (M5 §8.3): `module_list` (no arguments), `module_read`, `game_ids`, `forge_test`, `forge_save`;
 * the forger's alone (`ToolCatalog`), each a `ForgeTool` (shown only while the engine and the Forge run, so that the
 * server without the Forge, until M5-21, shows none). Empty until task M5-10 fills it; the family is already in the
 * catalog (`ToolCatalog.allTools`).
 */
fun forgerTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `module_read`. */
@Serializable
data class ModuleReadArgs(
    @Doc("A module of the arsenal.") @MaxLen(32) val name: String,
    @Doc("Its version; the latest when left out.") @Range(0.0, 99.0) val version: Int? = null,
)

/** The registries `game_ids` lists (M5 §8.3): the ids a module may write, frozen once the server runs. */
@Serializable
enum class GameIdKind {
    @SerialName("entity") ENTITY,
    @SerialName("item") ITEM,
    @SerialName("block") BLOCK,
    @SerialName("particle") PARTICLE,
    @SerialName("sound") SOUND,
    @SerialName("effect") EFFECT,
    @SerialName("attribute") ATTRIBUTE,
    @SerialName("biome") BIOME,
    @SerialName("structure") STRUCTURE,
    @SerialName("dimension") DIMENSION,
    @SerialName("punishment") PUNISHMENT,
    @SerialName("launch_direction") LAUNCH_DIRECTION,
    ;

    val id: String get() = name.lowercase()
}

/** The arguments of `game_ids`. */
@Serializable
data class GameIdsArgs(
    val kind: GameIdKind,
    @Doc("Only the ids that contain this.") @MaxLen(40) val contains: String? = null,
)

/** The arguments of `forge_test`. */
@Serializable
data class ForgeTestArgs(
    @Doc("The module's name, snake_case.") @MaxLen(32) val name: String,
    @Doc("The whole Java file.") @MaxLen(ForgeLimits.SOURCE_CHARS) val source: String,
)

/** The arguments of `forge_save`: the test again, then a new version. */
@Serializable
data class ForgeSaveArgs(
    @Doc("The module's name, snake_case.") @MaxLen(32) val name: String,
    @Doc("The whole Java file.") @MaxLen(ForgeLimits.SOURCE_CHARS) val source: String,
    @Doc("What it is or what changed, one English line for the Director and the operators.") @MaxLen(300) val note: String,
)
