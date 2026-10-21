package io.github.clemsix6.narrator2.tool.storyteller

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.storyteller.Deck
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.memory.NAME_MAX
import io.github.clemsix6.narrator2.tool.memory.knownPlayers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.random.Random

/**
 * `inspire` (M6 §5), the Director's and the storyteller's: three fresh ideas, instant and without a model. Its name,
 * description, arguments, effect and availability are final (M6-01); its run draws from the deck (`storyteller/Deck.kt`).
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

    /**
     * A draw ([Deck]): the players named, else those online; the table gathered off the server thread (what cannot be
     * read is said, never failed), drawn with a seed of the clock and the turn, then recorded ([Deck.record]). With
     * [InspireArgs.idea], the whole card of that idea instead, which deals nothing.
     */
    override suspend fun run(ctx: ToolContext, args: InspireArgs): ToolResult = answer {
        try {
            val now = ctx.clock.instant()
            args.idea?.let { return@answer card(ctx, it, now) }
            val players = args.player?.let { ctx.knownPlayers(listOf(it), "player") } ?: ctx.players.online.map { it.ref }
            val table = Deck.table(ctx.services, players, named = args.player != null, kind = args.kind, now = now)
            val draw = Deck.draw(table, Random(now.toEpochMilli() * SEED_STEP + (ctx.caller.turnId ?: 0)))
            Deck.record(ctx.services, draw, ctx.caller)
            ToolResult.ok(draw.render())
        } catch (e: JournalUnavailableException) {
            ToolResult.failed(e.message ?: "the Journal is unavailable")
        } catch (e: JournalTimeoutException) {
            ToolResult.failed(e.message ?: "the Journal did not answer")
        }
    }

    /** The card of the idea [typed] (M6 §5.2): its pitch, fit, refs with their lines, module and brief. */
    private suspend fun card(ctx: ToolContext, typed: String, now: Instant): ToolResult {
        val ref = Ref.parse(typed)?.takeIf { it.kind == RefKind.IDEA }
            ?: return ToolResult.invalid("idea: ${ModelText.quote(typed, IDEA_REF_MAX)} is not an idea: write P- and its number, as your briefing or a draw shows it")
        val memory = ctx.services.memory
        if (!memory.available) return ToolResult.failed("the memory is unavailable: ${memory.unavailableReason}")
        val idea = memory.store.row(ref) as? Idea ?: run {
            val open = memory.store.ideas(IdeaStatus.OPEN).joinToString { it.ref.toString() }.ifEmpty { "none" }
            return ToolResult.invalid("idea: $ref is not an idea of your programme; open ideas: $open")
        }
        val refs = idea.refs.map { it to memory.store.row(it) }
        val module = idea.module?.let { ctx.services.forge.arsenal.entry(it) }
        log.debug { "inspire: the card of $ref for ${ctx.caller.role.id}" }
        return ToolResult.ok(Deck.card(idea, refs, module, now, memory.clock.worldPlaySeconds(now.toEpochMilli())))
    }

    private companion object {
        private val log = Log.story

        /** Spreads the draws of one turn apart from those of the next millisecond. */
        const val SEED_STEP = 1_000_003L
    }
}

/** Longest reference of an idea: `P-` and its id. */
internal const val IDEA_REF_MAX = 12
