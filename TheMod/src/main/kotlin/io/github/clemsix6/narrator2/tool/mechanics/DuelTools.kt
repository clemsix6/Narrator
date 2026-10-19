package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.challenge.DuelLines
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult

/**
 * The duels (M4 §5.5, §12): `propose_duel` (arguments `DuelSpec`), a `MechanicsTool` of effect `WORLD`; nobody
 * is ever made to fight, nobody dies in a duel.
 */
fun duelTools(services: NarratorServices): List<NarratorTool<*>> = listOf(ProposeDuelTool())

/**
 * `propose_duel` (M4 §5.5, §12.2, §12.3): proposes a duel between two players online, through the engine
 * (`ChallengeControl.proposeDuel`), which validates it, writes its canon row and invites both. Its refusals are the
 * engine's ([MechanicRejected.toResult]): `DENIED (pvp_off)`, `DENIED (challenge_limit)`, `Invalid arguments: …`.
 * Its arena may take a few seconds to load: a longer timeout than the default.
 */
class ProposeDuelTool : MechanicsTool<DuelSpec>(
    name = "propose_duel",
    description = "Proposes a duel between two players. Each gets a clickable invitation and must accept it in time: nobody is " +
        "ever made to fight. Optionally in an arena in their own dimension, such as a flat spot the scout found (they are " +
        "put back afterwards). Nobody dies in a duel: a fatal blow from the opponent ends it, the loser left at 1 health; " +
        "any other deadly harm is a rescue, and a loss, but one dealt from outside the duel (another player, you, your " +
        "creatures) cancels it. DUEL events tell you whether it starts and who won. Recorded in your canon (C-…).",
    args = DuelSpec.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = 15_000,
) {
    override suspend fun run(ctx: ToolContext, args: DuelSpec): ToolResult {
        val mechanics = ctx.services.mechanics
        if (!mechanics.available) return ToolResult.failed("the mechanics are unavailable: ${mechanics.unavailableReason}")
        val view = try {
            mechanics.challenges.proposeDuel(args, ctx.caller)
        } catch (e: MechanicRejected) {
            return e.toResult()
        }
        return ToolResult.ok("ok: ${DuelLines.proposed(view, ctx.clock.millis(), ctx.clock)}")
    }
}
