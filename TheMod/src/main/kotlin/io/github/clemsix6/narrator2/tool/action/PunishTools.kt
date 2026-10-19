package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.Serializable

/**
 * The catalogue of punishments (M4 §6.3): `punish`, a `MechanicsTool` of effect `WORLD` that opens its own
 * episode with the punishment's category, arms the death guard for its perils, then runs its steps through
 * the real tools (or the catalogue's primitives) in the same act. Filled by M4-13; empty until then.
 */
fun punishTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `punish` (M4 §6.3). */
@Serializable
data class PunishArgs(
    @Doc("Name of the online player.") @MaxLen(MechanicsLimits.PLAYER_NAME) val player: String,
    val punishment: PunishmentId,
    @Doc("1 a pinch … 5 the full wrath; capped by the chaos level.") @Range(1.0, 5.0) val severity: Int,
    @Doc("Why, one factual line, French, third person (« a traité le Narrateur de dieu de pacotille »); kept in their history.")
    @MaxLen(MechanicsLimits.REASON)
    val reason: String,
    @Doc("guests: their name.") @MaxLen(32) val name: String? = null,
)
