package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The Director's tools of the Forge (M5 §9.1): `forge_module` (a commission to the forger), `deploy_module` and
 * `stop_module`. Each is a `ForgeTool`, shown only while the mechanics engine and the Forge run and no operator
 * switched the Forge off (`ToolEnv.forgeAvailable`). Empty until task M5-12 fills it; the family is already in the
 * catalog (`ToolCatalog.allTools`).
 */
fun moduleTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()

/** The arguments of `forge_module` (M5 §9.1): a commission for the forger, who knows nothing else. */
@Serializable
data class ForgeModuleArgs(
    @Doc(
        "What you want, for the forger who knows nothing else: what players see and do, who, how long, how it ends, " +
            "what it must never do.",
    )
    @MaxLen(ForgeLimits.BRIEF_MAX_CHARS)
    val brief: String,
)

/** The arguments of `deploy_module` (M5 §6.4, §9.1). [challenge]: a running judged challenge it referees. */
@Serializable
data class DeployModuleArgs(
    @Doc("A module of your arsenal (briefing), e.g. orbiting_chickens.") @MaxLen(32) val module: String,
    @Doc("A version; the latest ready when left out.") @Range(0.0, 99.0) val version: Int? = null,
    @Doc("Its parameters, as your arsenal lists them.") val params: JsonObject = JsonObject(emptyMap()),
    val kind: RuleKind,
    @Doc("What it does, one or two factual sentences in French, third person, for your canon.") @MaxLen(MechanicsLimits.STATEMENT) val statement: String,
    @Doc("Your exact words when you decreed it (law).") @MaxLen(MechanicsLimits.QUOTE) val quote: String? = null,
    // ForgeLimits.DEPLOY_MIN_MINUTES and DEPLOY_MAX_MINUTES (an annotation takes literals only).
    @Doc("How long it runs.") @Range(5.0, 10_080.0) val minutes: Int,
    @Doc("A running judged challenge (C-…) it referees: it names the winners itself.") @MaxLen(MechanicsLimits.REF) val challenge: String? = null,
)

/** The arguments of `stop_module` (M5 §9.1). */
@Serializable
data class StopModuleArgs(
    @Doc("The module, e.g. C-70.") @MaxLen(MechanicsLimits.REF) val id: String,
    @Doc("Why, one factual sentence in French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)
