package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import kotlinx.serialization.Serializable

/**
 * The challenges the engine tracks (M4 §5, §12): `start_challenge` (arguments `ChallengeSpec`) and
 * `end_challenge`, `MechanicsTool`s of effect `WORLD`. Filled by M4-17; empty until then.
 */
fun challengeTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `end_challenge` (M4 §12.3). */
@Serializable
data class EndChallengeArgs(
    @Doc("The challenge or duel, e.g. C-50.") @MaxLen(MechanicsLimits.REF) val id: String,
    val outcome: ChallengeOutcome,
    @Doc("won: by name.") @Items(1, 8) @MaxLen(MechanicsLimits.PLAYER_NAME) val winners: List<String>? = null,
    @Doc("Your canon's resolution: one factual sentence, French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)
