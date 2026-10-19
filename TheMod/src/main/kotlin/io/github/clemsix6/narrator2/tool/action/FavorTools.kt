package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The favors the engine keeps (M4 §7.1, §7.2): `favor`, a `MechanicsTool` of effect `WORLD` (pardon, grace,
 * joker), which also takes an offering, only once the favor is granted. Filled by M4-16; empty until then.
 */
fun favorTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The favors of `favor` (M4 §7.1). */
@Serializable
enum class FavorKind {
    @SerialName("pardon") PARDON,
    @SerialName("grace") GRACE,
    @SerialName("joker") JOKER,
    ;

    val id: String get() = name.lowercase()
}

/** The arguments of `favor` (M4 §7.1, offerings §7.2). */
@Serializable
data class FavorArgs(
    @Doc("Name of the online player.") @MaxLen(MechanicsLimits.PLAYER_NAME) val player: String,
    val favor: FavorKind,
    @Doc("grace") @Range(1.0, 60.0) val minutes: Int = 15,
    @Doc("Their offering (O-…) you take for this favor: yours for good only if the favor is granted.")
    @MaxLen(MechanicsLimits.REF)
    val offering: String? = null,
    @Doc("offering: what it was and what you made of it, one factual line, French, third person; kept in their history.")
    @MaxLen(MechanicsLimits.OFFERING_NOTE)
    val note: String? = null,
)
