package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.serialization.Serializable

/**
 * The laws and plans (M4 §8, §12): `create_rule` (arguments `RuleSpec`) and `remove_rule`, `MechanicsTool`s of
 * effect `ENGINE`, shown only while the engine runs. They hand the work to the engine's rules
 * (`services.mechanics.rules`, [RuleBook]): validation, canon, state. A refusal of the engine is
 * `Invalid arguments: …` (with the fix) or `DENIED (rule): …`; a Journal that cannot be reached is `FAILED`.
 */
fun ruleTools(services: NarratorServices): List<NarratorTool<*>> = listOf(CreateRuleTool(), RemoveRuleTool())

/** The arguments of `remove_rule` (M4 §12.3). */
@Serializable
data class RemoveRuleArgs(
    @Doc("The rule, e.g. C-60.") @MaxLen(MechanicsLimits.REF) val id: String,
    @Doc("Why: your canon's resolution, one factual sentence, French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)

/** Runs [block], an engine's call: its refusals and the Journal's failures become the answer. */
private suspend fun engine(tool: String, block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: MechanicRejected) {
    e.toResult()
} catch (e: JournalUnavailableException) {
    Log.mechanics.warn("$tool: ${e.message}")
    ToolResult.failed(e.message.orEmpty())
} catch (e: JournalTimeoutException) {
    Log.mechanics.warn("$tool: ${e.message}")
    ToolResult.failed(e.message.orEmpty())
}

/** `create_rule` (M4 §8, §12.2): a law or a plan the engine runs without the Director. */
class CreateRuleTool : MechanicsTool<RuleSpec>(
    name = "create_rule",
    description = "Changes how the world behaves without you: when the trigger happens and the conditions (when) hold, the engine " +
        "runs the effects. A law is your public decree, for up to a week; a plan is your secret intention, often once: a trap, " +
        "a welcome, a reaction prepared in advance. Each effect is one of your tools with its usual arguments (args), and at " +
        "says whom or where: actor (the player of the trigger), subject (its creature, or the player hurt), position (where it " +
        "happened) or everyone; the engine fills the tool's target with it. Tools: say, actionbar, show_title, play_sound, " +
        "particles, fireworks, lightning, explosion, apply_effect, set_attribute, launch, teleport, spawn, punish (traps: " +
        "punish cage or web); and for rules only: mob_effect {effect, seconds, level} and mob_attribute {attribute, multiply} " +
        "on the creature of the trigger (at subject), and notify {note} (wakes you with your note). Effects obey the engine's " +
        "rules like your own calls. Recorded in your canon (C-…).",
    args = RuleSpec.serializer(),
    effect = ToolEffect.ENGINE,
    timeoutMs = TIMEOUT_MS,
) {
    override suspend fun run(ctx: ToolContext, args: RuleSpec): ToolResult = engine(name) {
        val view = ctx.services.mechanics.rules.create(args, ctx.caller)
        val now = ctx.clock.millis()
        val until = RuleText.time(view.endsAt, now, ctx.clock.zone)
        val after = if (view.spec.kind == RuleKind.LAW) "announce it if you like" else "it stays secret"
        ToolResult.ok(
            "ok: rule ${view.ref} (${view.spec.kind.id}) active until $until (in ${ToolText.duration(view.endsAt - now)}): " +
                "${RuleText.summary(view.spec)}; $after",
        )
    }
}

/** `remove_rule` (M4 §12.2): ends a law (retired) or a plan (cancelled) now. */
class RemoveRuleTool : MechanicsTool<RemoveRuleArgs>(
    name = "remove_rule",
    description = "Ends one of your laws or plans now. Creatures it changed get back to normal.",
    args = RemoveRuleArgs.serializer(),
    effect = ToolEffect.ENGINE,
    timeoutMs = TIMEOUT_MS,
) {
    override suspend fun run(ctx: ToolContext, args: RemoveRuleArgs): ToolResult = engine(name) {
        val rules = ctx.services.mechanics.rules
        val ref = Ref.parse(args.id)?.takeIf { it.kind == RefKind.CANON }
            ?: return@engine ToolResult.invalid("${args.id} is not a rule; active: ${rules.list().joinToString { it.ref.toString() }.ifEmpty { "none" }}")
        val author = Author(Source.DIRECTOR, turnId = ctx.caller.turnId, conversationId = ctx.caller.conversationId)
        val view = rules.remove(ref, args.note.trim(), author)
        val ended = if (view.spec.kind == RuleKind.LAW) "retired" else "cancelled"
        val skipped = if (view.skipped > 0) ", ${view.skipped} skipped" else ""
        ToolResult.ok("ok: rule ${view.ref} (${view.spec.kind.id}) $ended (${view.fired} fired$skipped)")
    }
}

/** The rules' tools wait for the Journal (a barrier, the players, the canon), then the server thread. */
private const val TIMEOUT_MS = 15_000L
