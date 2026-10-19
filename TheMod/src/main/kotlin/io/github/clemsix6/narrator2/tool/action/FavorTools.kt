package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.Jokers
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.live
import io.github.clemsix6.narrator2.tool.memory.author
import io.github.clemsix6.narrator2.tool.online
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * The favors the engine keeps (M4 §7.1, §7.2): `favor`, a `MechanicsTool` of effect `WORLD` (pardon, grace,
 * joker), which also takes an offering, only once the favor is granted.
 */
fun favorTools(services: NarratorServices): List<NarratorTool<*>> = listOf(FavorTool())

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

/**
 * `favor` (M4 §7.1, §7.2, §12.2): a favor the engine keeps, for an online player.
 * - **pardon**: everything the Narrator inflicts on them lifted now (`Guards.pardon`); `ok: nothing to pardon` when
 *   there is nothing.
 * - **grace**: the Narrator's hostile actions against them refused, its creatures and explosions sparing them, for
 *   [FavorArgs.minutes] (`Guards.grace`: none within the hour after the last one).
 * - **joker**: a paper they play by themselves to lift one of the Narrator's punishments ([Jokers]; one a day).
 *
 * With an offering (`O-…`, the player's own, still waiting), the favor is granted first, and **only if it is**, the
 * offering is taken for good (`ItemLedger.consume`), on the server thread in the same tick: a favor refused leaves it
 * in escrow, where it goes back by itself. The player's history then gets an `offering` interaction (valence +1,
 * [FavorArgs.note]). The Director calls it, and the engine for a challenge's reward (a grace or a joker).
 *
 * A favor granted names the player in [ToolResult.targets]: the audit, and the Chronicler's `Sanctions`, which keeps
 * it in their history as a benefit (M4-08). Nothing to pardon grants nothing, and names nobody.
 */
class FavorTool : MechanicsTool<FavorArgs>(
    name = "favor",
    description = "Grants a favor the engine keeps: pardon (lifts now everything you inflicted on them: effects, changes, " +
        "confiscations, creatures, traps), grace (you and your creatures leave them alone for some minutes: your hostile " +
        "actions against them are refused), or joker (a paper they can play later, by themselves, to lift one of your " +
        "punishments). With offering (OFFERING event, O-…), it is what you give in exchange for that offering: its items " +
        "become yours for good only if the favor is granted. An offering you do not take goes back by itself.",
    args = FavorArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /** With an offering: its id (`O-812` or `812`), and the note for the player's history, factual and in the third person. */
    override suspend fun check(ctx: ToolContext, args: FavorArgs): ToolResult? = dryCheck {
        val offering = args.offering
        if (offering != null) {
            if (Escrow.offeringId(offering) == null) refuse(ToolResult.invalid("offering is the id of an OFFERING event, like O-812 (got \"$offering\")"))
            val note = noteOf(args)
            if (note.isEmpty()) {
                refuse(ToolResult.invalid("note is required with offering: what it was and what you made of it, one factual line in French, third person"))
            }
            VoiceGuard.check(note, "note", MechanicsLimits.OFFERING_NOTE, listOf(args.player))?.let { refuse(ToolResult.invalid(it)) }
        }
    }

    override suspend fun run(ctx: ToolContext, args: FavorArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val mechanics = ctx.services.mechanics
        if (!mechanics.available) return@answer ToolResult.failed("the mechanics are unavailable: ${mechanics.unavailableReason}")
        val ref = ctx.online(args.player)
        val offeringId = args.offering?.let(Escrow::offeringId)
        val granted = ctx.onServer { server ->
            val player = server.live(ref)
            val offering = offeringId?.let { id -> ctx.guards.items.offering(player, id) ?: refuse(notWaiting(server, player, id, ctx.clock.millis())) }
            val text = grant(ctx, player, args, offering) ?: return@onServer null
            // Granted: only now is the offering taken, in the same tick (the same save as the favor).
            Granted(text, offering, offering?.let { ctx.guards.items.consume(player, it.id, "favor ${args.favor.id}") })
        } ?: return@answer ToolResult.ok("ok: nothing to pardon: you inflict nothing on ${ref.shownName}") // no favor: no target
        val offering = granted.offering ?: return@answer ToolResult.ok(granted.text, listOf(ref))
        val taken = granted.taken
            ?: return@answer ToolResult.ok("${granted.text}; ${Escrow.ref(offering.id)} could not be taken (no longer waiting): the favor is a gift", listOf(ref))
        ToolResult.ok(
            "${granted.text}; their offering ${Escrow.ref(taken.id)} (${Escrow.short(taken.what)}) is yours for good${keep(ctx, ref, noteOf(args))}",
            listOf(ref),
        )
    }

    /** What granting did: the answer's first part, the offering asked for, and the offering taken. */
    private class Granted(val text: String, val offering: Confiscation?, val taken: Confiscation?)

    /**
     * Grants [args]'s favor to [player], or refuses (a refusal with [offering] says that it stays in escrow); null when
     * there was nothing to pardon (and no offering). Server thread.
     */
    private fun grant(ctx: ToolContext, player: ServerPlayer, args: FavorArgs, offering: Confiscation?): String? {
        val name = player.shownName
        val now = ctx.clock.millis()
        val untaken = offering?.let { "; their offering ${Escrow.ref(it.id)} is not taken: grant another favor, or let it go back" }.orEmpty()
        fun refused(verdict: Verdict.Denied): Nothing = refuse(ToolResult.denied(verdict.rule, verdict.message.trimEnd('.') + untaken))
        return when (args.favor) {
            FavorKind.PARDON -> {
                val lifted = ctx.guards.pardon(player, ctx.caller)
                when {
                    lifted.isNotEmpty() -> "ok: $name pardoned: ${Jokers.lifted(lifted)}"
                    offering != null -> refuse(ToolResult.invalid("nothing to pardon on $name$untaken"))
                    else -> null
                }
            }
            FavorKind.GRACE -> {
                val until = now + args.minutes * MINUTE_MS
                val verdict = ctx.guards.grace(player, until, ctx.caller)
                if (verdict is Verdict.Denied) refused(verdict)
                val running = if (ctx.guards.isPunished(player)) "; what you already inflict on them runs on (a pardon lifts it)" else ""
                "ok: $name is under your grace until ${ctx.at(until)} (in ${ToolText.duration(until - now)})$running"
            }
            FavorKind.JOKER -> {
                (Jokers.allowed(ctx.services, player, ctx.caller, now) as? Verdict.Denied)?.let(::refused)
                val waiting = Jokers.give(ctx.services, player, ctx.caller, now)
                "ok: $name got a joker; they can play it themselves to lift one of your punishments" +
                    if (waiting) " (their inventory is full: it waits there for room)" else ""
            }
        }
    }

    /** `Invalid arguments: O-812 is not a waiting offering of Mimosa; waiting: O-815 (Bartok)`. Server thread. */
    private fun notWaiting(server: MinecraftServer, player: ServerPlayer, id: Long, now: Long): ToolResult {
        val waiting = server.playerList.players.mapNotNull { other ->
            Escrow.pendingOffering(GuardStates.read(other), now)?.let { "${Escrow.ref(it.id)} (${other.shownName})" }
        }
        return ToolResult.invalid("${Escrow.ref(id)} is not a waiting offering of ${player.shownName}; waiting: ${waiting.joinToString().ifEmpty { "none" }}")
    }

    /**
     * The `offering` interaction of [ref] (valence +1, [note]): what the offering bought is in their history. Empty
     * when kept; else what the answer adds (the favor is granted and the offering taken all the same).
     */
    private suspend fun keep(ctx: ToolContext, ref: PlayerRef, note: String): String = try {
        ctx.services.memory.store.addInteraction(InteractionDraft(ref.uuid, InteractionKind.OFFERING, OFFERING_VALENCE, note), ctx.author())
        ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("the offering of ${ref.name} is taken, but not kept in their history: ${e.message}")
        "; not kept in their history (${e.message})"
    }

    private companion object {
        val log: Log = Log.tool
        const val MINUTE_MS = 60_000L

        /** An offering is a gesture toward the Narrator: +1, like a prayer heard. */
        const val OFFERING_VALENCE = 1

        fun noteOf(args: FavorArgs): String = args.note?.let { Speech.clean(it) }.orEmpty()
    }
}
