package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.challenge.ChallengeLines
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.memory.author
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The challenges the engine tracks (M4 §5, §12): `start_challenge` (arguments `ChallengeSpec`) and
 * `end_challenge`, `MechanicsTool`s of effect `WORLD`. The engine validates, tracks, ends and pays them
 * (`ChallengeControl`); the tools only call it and answer the model.
 */
fun challengeTools(services: NarratorServices): List<NarratorTool<*>> = listOf(StartChallengeTool(), EndChallengeTool())

/** The arguments of `end_challenge` (M4 §12.3). */
@Serializable
data class EndChallengeArgs(
    @Doc("The challenge or duel, e.g. C-50.") @MaxLen(MechanicsLimits.REF) val id: String,
    val outcome: ChallengeOutcome,
    @Doc("won: by name.") @Items(1, 8) @MaxLen(MechanicsLimits.PLAYER_NAME) val winners: List<String>? = null,
    @Doc("Your canon's resolution: one factual sentence, French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)

/**
 * `start_challenge` (M4 §5.1, §12): the engine checks the challenge (the goal's fields and ids, the players online,
 * the pace of M4 §11, each stake by its tool's dry checks), writes its canon row, shows the boss bars and tracks it.
 * Answers with what started (`ok: challenge C-50 started: obtain 1 turtle_egg « Œuf de tortue » · Mimosa, Bartok ·
 * ends 20:15 (in 15m) · reward 1x golden_apple · penalty tiny (2) · bets open until 20:05; boss bars shown`), or the
 * engine's refusal with its fix.
 */
class StartChallengeTool : MechanicsTool<ChallengeSpec>(
    name = "start_challenge",
    description = "Starts a challenge the engine tracks: each participant sees a boss bar with their own progress; the " +
        "engine detects who wins (obtain an item, reach a place, kill creatures, survive, answer in the chat; judged: you " +
        "name the winners when it ends), pays the reward to the winners and the penalty to the losers under the engine's " +
        "rules, settles the players' bets, and records it in your canon (C-…). A CHALLENGE event tells you how it ended. " +
        "Announce it yourself (say), with the stakes.",
    args = ChallengeSpec.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = START_TIMEOUT_MS,
) {
    override suspend fun run(ctx: ToolContext, args: ChallengeSpec): ToolResult {
        val mechanics = ctx.services.mechanics
        if (!mechanics.available) return unavailable(mechanics)
        val view = try {
            mechanics.challenges.start(args, ctx.caller)
        } catch (e: MechanicRejected) {
            return e.toResult()
        }
        return ToolResult.ok("ok: challenge ${started(ctx, args, view)}; boss bars shown")
    }

    /**
     * The start's line (`ChallengeLines.started`), from the view of the challenge just started: it began [ChallengeSpec.minutes]
     * before its end, and its bets close when `Challenges` closes them (M4 §5.6).
     */
    private fun started(ctx: ToolContext, spec: ChallengeSpec, view: ChallengeView): String {
        val start = view.endsAt - spec.minutes * MINUTE
        val config = ctx.settings.config.mechanics
        val window = maxOf(MechanicsLimits.MIN_BET_WINDOW_MINUTES * MINUTE, minOf(config.betWindowMinutes * MINUTE, spec.minutes * MINUTE / 3))
        val state = ChallengeState(
            id = view.ref.id,
            spec = view.spec ?: spec,
            status = view.status,
            createdAt = start,
            startedAt = start,
            endsAt = view.endsAt,
            participants = view.participants,
            betsCloseAt = if (spec.bets) start + window else null,
            turnId = ctx.caller.turnId,
            conversationId = ctx.caller.conversationId,
        )
        return ChallengeLines.started(state, ctx.clock)
    }
}

/**
 * `end_challenge` (M4 §5.2, §12): ends one of the challenges or duels in progress now, `won` by the participants
 * named, `lost` or `cancelled`; the engine pays the stakes (none when cancelled), settles the bets, resolves the
 * canon with the note and writes the results. Answers with the line of its end, stakes and bets included
 * (`ok: C-53 won by Mimosa (ended by the Director); note: « … » · reward: Mimosa got 1x golden_apple · penalty:
 * Bartok tiny (2) until 20:13`); no CHALLENGE event follows (the answer is it).
 */
class EndChallengeTool : MechanicsTool<EndChallengeArgs>(
    name = "end_challenge",
    description = "Ends one of your challenges or duels now: won (name the winners: they get the reward, the others the " +
        "penalty), lost (nobody won: every participant gets the penalty) or cancelled (no stakes, bets go back). A judged " +
        "challenge needs it when its JUDGE event comes; the others end by themselves.",
    args = EndChallengeArgs.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = END_TIMEOUT_MS,
) {
    /** The id is a canon reference (`C-50`, `c50`, `50`). */
    override suspend fun check(ctx: ToolContext, args: EndChallengeArgs): ToolResult? =
        if (refOf(args.id) == null) ToolResult.invalid("id is one of your challenges or duels, like C-50 (got \"${ModelText.escape(args.id)}\")") else null

    override suspend fun run(ctx: ToolContext, args: EndChallengeArgs): ToolResult {
        check(ctx, args)?.let { return it }
        val mechanics = ctx.services.mechanics
        if (!mechanics.available) return unavailable(mechanics)
        val ref = refOf(args.id)!!
        return try {
            val winners = mechanics.challenges.list().firstOrNull { it.ref == ref }?.let { winnersOf(it, args) }.orEmpty()
            ToolResult.ok("ok: ${mechanics.challenges.end(ref, args.outcome, winners, args.note, ctx.author()).line}")
        } catch (e: MechanicRejected) {
            e.toResult()
        }
    }

    /**
     * The participants of [challenge] that [args] names as winners (by name, ignoring case), in order. Throws
     * [MechanicRejected] for a name that is no participant's, or winners with another outcome than won. A challenge
     * that is not running is left to the engine, which says so.
     */
    private fun winnersOf(challenge: ChallengeView, args: EndChallengeArgs): List<UUID> {
        val names = args.winners.orEmpty()
        if (args.outcome != ChallengeOutcome.WON) {
            if (names.isNotEmpty()) throw MechanicRejected(null, "winners only go with won: leave them out")
            return emptyList()
        }
        return names.map { name ->
            challenge.participants.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.playerId
                ?: throw MechanicRejected(null, "won needs winners among the participants: ${challenge.participants.joinToString { ModelText.name(it.name) }}")
        }.distinct()
    }

    private fun refOf(id: String): Ref? {
        val text = id.trim()
        val ref = Ref.parse(text) ?: text.toLongOrNull()?.takeIf { it > 0 }?.let { Ref(RefKind.CANON, it) }
        return ref?.takeIf { it.kind == RefKind.CANON }
    }
}

/** `FAILED: the mechanics are unavailable: …`: a chapter keeps its tools when the engine falls (M4 §12.1). */
private fun unavailable(mechanics: Mechanics): ToolResult = ToolResult.failed("the mechanics are unavailable: ${mechanics.unavailableReason}")

private const val MINUTE = 60_000L

/** A start writes the canon (a barrier of the Journal, then a write) and goes twice to the server thread. */
private const val START_TIMEOUT_MS = 10_000L

/** An end waits for the stakes, one act of the engine after the other, each a few passes on the server thread. */
private const val END_TIMEOUT_MS = 30_000L
