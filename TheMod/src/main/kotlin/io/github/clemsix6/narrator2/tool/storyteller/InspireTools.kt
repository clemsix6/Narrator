package io.github.clemsix6.narrator2.tool.storyteller

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.memory.NAME_MAX
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `inspire` (M6 §5), the Director's and the storyteller's: three fresh ideas, instant and without a model. Its name,
 * description, arguments, effect and availability are final; its run is not wired yet and answers `FAILED`.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun inspireTools(services: NarratorServices): List<NarratorTool<*>> = listOf(InspireTool())

/** The lever an `inspire` draw centers on (M6 §5.2); [ANY] for any. */
@Serializable
enum class InspireKind {
    @SerialName("challenge") CHALLENGE,
    @SerialName("punishment") PUNISHMENT,
    @SerialName("favor") FAVOR,
    @SerialName("twist") TWIST,
    @SerialName("any") ANY,
    ;

    val id: String get() = name.lowercase()
}

@Serializable
data class InspireArgs(
    @Doc("A player to center the ideas on; the players online when left out.")
    @MaxLen(NAME_MAX)
    val player: String? = null,
    @Doc("challenge, punishment, favor, twist, or any.")
    val kind: InspireKind = InspireKind.ANY,
    @Doc("One idea of your programme to read in full, e.g. P-41: its pitch, references and brief.")
    @MaxLen(IDEA_REF_MAX)
    val idea: String? = null,
)

/**
 * `inspire`: ideas of the storyteller's programme that fit the players online, then seeds drawn by the code (a lever ×
 * a hook of the memory × a twist × a register used least), leaving out what was done, said and dealt lately (M6 §5.2
 * to §5.4). Shown while the storyteller can work and the Journal runs ([ToolEnv.storytellerAvailable]); reads only.
 */
class InspireTool : NarratorTool<InspireArgs>(
    name = "inspire",
    description = "Draws three fresh ideas when you lack one: ideas of your programme (P-…, prepared by your storyteller) " +
        "that fit the players online, then seeds: a lever you have not used lately × something from your memory × a twist, " +
        "with a comic register you have used least. Leaves out what you did, said and were dealt lately. Instant; reads " +
        "only. Ideas, not orders: use one, bend it, or ignore them all.",
    args = InspireArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override fun isAvailable(env: ToolEnv): Boolean = env.journalAvailable && env.storytellerAvailable

    override suspend fun run(ctx: ToolContext, args: InspireArgs): ToolResult = ToolResult.failed("not wired yet")
}

/** Longest reference of an idea: `P-` and its id. */
internal const val IDEA_REF_MAX = 12
