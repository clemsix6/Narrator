package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import kotlinx.serialization.Serializable

/**
 * The laws and plans (M4 §8, §12): `create_rule` (arguments `RuleSpec`) and `remove_rule`, `MechanicsTool`s of
 * effect `ENGINE`. Filled by M4-14; empty until then.
 */
fun ruleTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `remove_rule` (M4 §12.3). */
@Serializable
data class RemoveRuleArgs(
    @Doc("The rule, e.g. C-60.") @MaxLen(MechanicsLimits.REF) val id: String,
    @Doc("Why: your canon's resolution, one factual sentence, French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)
