package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.Serializable

/**
 * The scout's survey (M4 §14): `survey`, a read tool that finds flat, open squares around a player or a point,
 * in the loaded chunks only, for an arena or a scene. The scout's alone. Filled by M4-11; empty until then.
 */
fun surveyTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `survey` (M4 §14). */
@Serializable
data class SurveyArgs(
    @Doc("Name of the online player around whom to look; leave it out to give x, y and z instead.") @MaxLen(16) val player: String? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") val x: Int? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") val y: Int? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") val z: Int? = null,
    @Doc("Dimension of x, y and z: overworld (the default), the_nether or the_end.") @MaxLen(40) val dimension: String? = null,
    @Doc("How far to look, in blocks.") @Range(8.0, 64.0) val radius: Int = 32,
    @Doc("Side of the flat square wanted, in blocks.") @Range(5.0, 21.0) val size: Int = 9,
)
