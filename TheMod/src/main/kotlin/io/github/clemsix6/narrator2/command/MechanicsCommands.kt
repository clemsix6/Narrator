package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.DirectorCommands.Reply
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/narrator challenge …`, `/narrator rule …` and `/narrator traps …` (M4 §18.1): what the mechanics engine
 * runs, and the operators' ways to stop it. Gray English lines; long lists come in pages that fit the open
 * chat ([TraceText.page]).
 *
 * - `challenge [list] [page]`, `challenge cancel <id> [note]`: the challenges and duels in progress; a cancel
 *   ends one without stakes, its bets given back (`ChallengeControl.end`, as the operator).
 * - `rule [list] [page]`, `rule remove <id> [note]`: the active laws and plans; a removal ends one now
 *   (`RuleControl.remove`, as the operator).
 * - `traps`, `traps restore`: the trap blocks waiting to be taken away, by player; `restore` takes them all
 *   away now ([BlockRestorer.restoreAll]). The traps are the guards', not the engine's: these two work with
 *   the engine off, and even when the Narrator did not start.
 *
 * While the engine is unavailable (`DisabledMechanics`), `challenge` and `rule` are refused at once, with the
 * reason. Lists read the engine's snapshot on the server thread; a cancel or a removal waits for the canon, so
 * it runs off the server thread ([replyOn]). Each command that acts is an `operator` decision
 * ([CHALLENGE_CANCEL], [RULE_REMOVE], [TRAPS_RESTORE]); the engine records its own (`mechanic cancelled`,
 * `removed`), and the guards theirs (`trap restored`).
 */
object MechanicsCommands {
    private val log = Log.command

    /** The `operator` decisions of the commands that act. */
    const val CHALLENGE_CANCEL = "challenge_cancel"
    const val RULE_REMOVE = "rule_remove"
    const val TRAPS_RESTORE = "traps_restore"

    /** The canon's resolution of a cancel or a removal without a note: the engine's own words are English too. */
    const val CANCEL_NOTE = "cancelled by an operator"
    const val REMOVE_NOTE = "removed by an operator"

    /** Characters of a rule's statement in `rule list`: with its indent and quotes, one line of the chat ([WRAP]). */
    private const val STATEMENT_CHARS = 90

    /** Players named in `traps`; the rest are counted. */
    private const val MAX_TRAPPED = 15

    /** Adds `challenge`, `rule` and `traps` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(challengeCommand())
        root.then(ruleCommand())
        root.then(trapsCommand())
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    private fun challengeCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("challenge")
        .executes { context -> show(context.source, "challenge list") { challengeList(it, 1) } }
        .then(
            literal("list")
                .executes { context -> show(context.source, "challenge list") { challengeList(it, 1) } }
                .then(page().executes { context -> show(context.source, "challenge list") { challengeList(it, page(context)) } }),
        )
        .then(
            literal("cancel").then(
                argument("id", StringArgumentType.word()).suggests(RUNNING)
                    .executes { context -> cancelOf(context, null) }
                    .then(argument("note", StringArgumentType.greedyString()).executes { context -> cancelOf(context, string(context, "note")) }),
            ),
        )

    private fun cancelOf(context: CommandContext<CommandSourceStack>, note: String?): Int {
        val id = string(context, "id")
        return change(context.source, "challenge cancel $id") { services, operator -> cancel(services, operator, id, note) }
    }

    private fun ruleCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("rule")
        .executes { context -> show(context.source, "rule list") { ruleList(it, 1) } }
        .then(
            literal("list")
                .executes { context -> show(context.source, "rule list") { ruleList(it, 1) } }
                .then(page().executes { context -> show(context.source, "rule list") { ruleList(it, page(context)) } }),
        )
        .then(
            literal("remove").then(
                argument("id", StringArgumentType.word()).suggests(ACTIVE_RULES)
                    .executes { context -> removeOf(context, null) }
                    .then(argument("note", StringArgumentType.greedyString()).executes { context -> removeOf(context, string(context, "note")) }),
            ),
        )

    private fun removeOf(context: CommandContext<CommandSourceStack>, note: String?): Int {
        val id = string(context, "id")
        return change(context.source, "rule remove $id") { services, operator -> removeRule(services, operator, id, note) }
    }

    private fun trapsCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("traps")
        .executes { context -> traps(context.source) }
        .then(literal("restore").executes { context -> restore(context.source) })

    /** Brigadier's own builders, as in [MemoryCommands]: the tree builds and parses without a bootstrapped game. */
    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal(name)

    private fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> = RequiredArgumentBuilder.argument(name, type)

    private fun page(): RequiredArgumentBuilder<CommandSourceStack, Int> = argument("page", IntegerArgumentType.integer(1))

    private fun page(context: CommandContext<CommandSourceStack>): Int = IntegerArgumentType.getInteger(context, "page")

    private fun string(context: CommandContext<CommandSourceStack>, name: String): String = StringArgumentType.getString(context, name)

    // ── Running a command ───────────────────────────────────────────────────────────────────────────

    /** Runs [command] on the server thread (it reads snapshots only) and sends its [Reply] to [source] alone. */
    private fun show(source: CommandSourceStack, what: String, command: (NarratorServices) -> Reply): Int {
        val runtime = runningNarrator(source) ?: return 0
        log.info("${source.textName}: narrator $what")
        return send(source, command(runtime), broadcast = false)
    }

    /**
     * Runs the change [work] off the server thread (it waits for the canon), as the operator who typed it, and
     * sends its [Reply] to [source]; the first line of a change that was made also goes to the other operators.
     * Refused at once, on the server thread, while the engine is unavailable.
     */
    private fun change(source: CommandSourceStack, what: String, work: suspend (NarratorServices, String) -> Reply): Int {
        val runtime = runningNarrator(source) ?: return 0
        refusal(runtime.mechanics)?.let { return send(source, Reply.refused(it), broadcast = false) }
        val operator = source.textName
        log.info("$operator: narrator $what")
        val done = AtomicBoolean()
        var first = true // touched on the server thread only
        replyOn(runtime.server, runtime.scope, what, { line ->
            source.sendSuccess({ line }, done.get() && first)
            first = false
        }) { work(runtime, operator).also { done.set(it.done) }.lines }
        return 1
    }

    /** Sends [reply] to [source], refusals in red; its first line to the other operators too when [broadcast] and done. */
    private fun send(source: CommandSourceStack, reply: Reply, broadcast: Boolean): Int {
        reply.lines.forEachIndexed { i, line ->
            if (reply.done) source.sendSuccess({ line }, broadcast && i == 0) else source.sendFailure(line)
        }
        return if (reply.done) 1 else 0
    }

    /** Why neither `challenge` nor `rule` can run: the engine is unavailable, and why; null when it is available. */
    internal fun refusal(mechanics: Mechanics): String? {
        if (mechanics.available) return null
        return "the mechanics are unavailable (${mechanics.unavailableReason ?: "no reason given"}): no challenge nor rule to show or change."
    }

    /** An `operator` decision: [operator] did [decision] on [ref]. */
    private fun record(services: NarratorServices, decision: String, operator: String, reason: String, ref: Ref? = null) {
        Decisions(services.trace, services.clock).record(
            log, DecisionRecord.Kind.OPERATOR, decision, subject = operator, reason = reason,
            detail = ref?.let { buildJsonObject { put("ref", it.toString()) } },
        )
    }

    // ── /narrator status ────────────────────────────────────────────────────────────────────────────

    /**
     * The `Mechanics:` line of `/narrator status`: `Mechanics: on · 1 challenge, 1 duel · 3 rules · 12 trap blocks
     * pending`, or `Mechanics: off (mechanics.enabled is false) · 0 trap blocks pending` (the traps are the guards': they run
     * with the engine off). [pending]: [BlockRestorer.pending]. From snapshots: any thread.
     */
    fun statusLine(mechanics: Mechanics, pending: Int): String {
        val traps = "$pending trap ${TraceText.plural(pending, "block")} pending"
        if (!mechanics.available) return "Mechanics: off (${mechanics.unavailableReason ?: "no reason given"}) · $traps"
        val running = mechanics.challenges.list()
        val duels = running.count { it.duel != null }
        val challenges = running.size - duels
        val rules = mechanics.rules.list().size
        val shown = "$challenges ${TraceText.plural(challenges, "challenge")}" + if (duels > 0) ", $duels ${TraceText.plural(duels, "duel")}" else ""
        return "Mechanics: on · $shown · $rules ${TraceText.plural(rules, "rule")} · $traps"
    }

    // ── challenge ───────────────────────────────────────────────────────────────────────────────────

    /** `challenge list [page]`: the challenges and duels in progress, invitations and judgings included, each by its line of the situation. */
    fun challengeList(services: NarratorServices, page: Int): Reply {
        refusal(services.mechanics)?.let { return Reply.refused(it) }
        val views = services.mechanics.challenges.list()
        if (views.isEmpty()) return Reply(true, listOf(narratorLine("challenges and duels · none in progress")))
        val lines = views.map { Out(it.line) } + Out("Cancel one (no stakes, the bets go back): /narrator challenge cancel <id> [note]")
        return Reply(true, text(services).page(Report("challenges and duels · ${views.size} in progress", lines), page, "narrator challenge list"))
    }

    /**
     * `challenge cancel <id> [note]`: ends the challenge or duel [id] now, cancelled (no stakes, bets given back),
     * as [operator]; [note] (French, third person: the canon's resolution, checked by the engine) or
     * [CANCEL_NOTE]. Off the server thread.
     */
    suspend fun cancel(services: NarratorServices, operator: String, id: String, note: String?): Reply {
        refusal(services.mechanics)?.let { return Reply.refused(it) }
        val ref = Ref.canonOf(id) ?: return Reply.refused("$id is not a challenge's reference (C-50, for instance).")
        val running = services.mechanics.challenges.list()
        if (running.none { it.ref == ref }) {
            return Reply.refused("$ref is not a challenge in progress; in progress: ${running.joinToString { it.ref.toString() }.ifEmpty { "none" }}.")
        }
        val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: CANCEL_NOTE
        val view = try {
            services.mechanics.challenges.end(ref, ChallengeOutcome.CANCELLED, emptyList(), text, Author(Source.OPERATOR, operator = operator))
        } catch (e: MechanicRejected) {
            return Reply.refused("refused: ${e.message}")
        }
        record(services, CHALLENGE_CANCEL, operator, "$ref cancelled: « $text »", ref)
        return Reply.done(narratorLine("$ref cancelled by $operator: no stakes, its bets go back."), detailLine(view.line))
    }

    // ── rule ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * `rule list [page]`: the active laws and plans, oldest first: kind, end and counters, then trigger,
     * conditions and effects ([RuleText.summary]), then the statement.
     */
    fun ruleList(services: NarratorServices, page: Int): Reply {
        refusal(services.mechanics)?.let { return Reply.refused(it) }
        val rules = services.mechanics.rules.list()
        val max = services.settings.get().config.mechanics.maxRules
        if (rules.isEmpty()) return Reply(true, listOf(narratorLine("rules · none active ($max at most)")))
        val now = services.clock.millis()
        val zone = services.clock.zone
        val lines = rules.flatMap { rule ->
            val kind = rule.spec.kind.id + if (rule.spec.once) ", once" else ""
            listOf(
                Out(
                    "${rule.ref} $kind · until ${RuleText.time(rule.endsAt, now, zone)} (in ${ToolText.wait(rule.endsAt - now)}) · " +
                        "${rule.fired} fired, ${rule.skipped} skipped, ${rule.failures} failed in a row",
                ),
                Out("  ${RuleText.summary(rule.spec)}"),
                Out("  « ${TraceText.abbreviate(rule.spec.statement, STATEMENT_CHARS)} »"),
            )
        } + Out("Remove one: /narrator rule remove <id> [note]")
        return Reply(true, text(services).page(Report("rules · ${rules.size} active ($max at most)", lines), page, "narrator rule list"))
    }

    /**
     * `rule remove <id> [note]`: ends the law or plan [id] now (a law retired, a plan cancelled), as [operator];
     * [note] (French, third person: the canon's resolution) or [REMOVE_NOTE], checked here, since the canon would
     * refuse it only once the rule is gone. Off the server thread.
     */
    suspend fun removeRule(services: NarratorServices, operator: String, id: String, note: String?): Reply {
        refusal(services.mechanics)?.let { return Reply.refused(it) }
        val ref = Ref.canonOf(id) ?: return Reply.refused("$id is not a rule's reference (C-60, for instance).")
        val active = services.mechanics.rules.list()
        val rule = active.firstOrNull { it.ref == ref }
            ?: return Reply.refused("$ref is not an active rule; active: ${active.joinToString { it.ref.toString() }.ifEmpty { "none" }}.")
        val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: REMOVE_NOTE
        VoiceGuard.check(text, "note", MemoryLimits.RESOLUTION, rule.spec.conditions.players.orEmpty(), boundQuotes = false)?.let { return Reply.refused("refused: $it") }
        val view = try {
            services.mechanics.rules.remove(ref, text, Author(Source.OPERATOR, operator = operator))
        } catch (e: MechanicRejected) {
            return Reply.refused("refused: ${e.message}")
        }
        val status = if (view.spec.kind == RuleKind.LAW) "retired" else "cancelled"
        record(services, RULE_REMOVE, operator, "$ref ${view.spec.kind.id} $status: « $text »", ref)
        return Reply.done(
            narratorLine("$ref ${view.spec.kind.id} $status by $operator: it fires no more."),
            detailLine("${view.fired} fired, ${view.skipped} skipped · ${RuleText.summary(view.spec)}"),
        )
    }

    // ── traps ───────────────────────────────────────────────────────────────────────────────────────

    /** `traps`: the trap blocks of each player. Works without the Narrator: the traps are the guards'. */
    private fun traps(source: CommandSourceStack): Int {
        val zone = ownNarrator(source.server)?.clock?.zone ?: ZoneId.systemDefault()
        // The traps' deadlines are wall clock (BlockRestorer).
        val lines = trapList(BlockRestorer.byPlayer(), BlockRestorer.pending(), nameIn(source.server), System.currentTimeMillis(), zone)
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    /**
     * `traps restore`: every trap block of the loaded chunks taken away now, but those a player changed since (left
     * as they are). An `operator` decision when the Narrator runs. Server thread.
     */
    private fun restore(source: CommandSourceStack): Int {
        val operator = source.textName
        val before = BlockRestorer.pending()
        val restored = if (before > 0) BlockRestorer.restoreAll() else 0
        val left = BlockRestorer.pending()
        log.info("$operator: narrator traps restore: $restored of $before taken away, $left left")
        val reply = restoreReply(before, restored, left)
        if (before > 0) ownNarrator(source.server)?.let { record(it, TRAPS_RESTORE, operator, reply.lines.first().string.removePrefix("[Narrator] ")) }
        return send(source, reply, broadcast = before > 0)
    }

    /**
     * The lines of `traps`: `[Narrator] traps · 25 blocks pending in the loaded chunks`, then one line per player
     * ([name] of their UUID): `Bartok: 22 minecraft:glass until 21:40:12 (8s left)`. [now] is wall clock, like
     * the traps' deadlines.
     */
    internal fun trapList(traps: Map<UUID, List<ActiveEffect>>, pending: Int, name: (UUID) -> String, now: Long, zone: ZoneId): List<Component> {
        if (pending == 0 && traps.isEmpty()) return listOf(narratorLine("traps · no block pending in the loaded chunks"))
        val players = traps.map { (uuid, effects) -> name(uuid) to effects }.sortedBy { it.first.lowercase() }
        return buildList {
            add(narratorLine("traps · $pending ${TraceText.plural(pending, "block")} pending in the loaded chunks"))
            players.take(MAX_TRAPPED).forEach { (player, effects) ->
                add(detailLine("$player: " + effects.joinToString { "${it.what} until ${TraceText(zone, now).time(Instant.ofEpochMilli(it.until))} (${left(it.until, now)})" }))
            }
            if (players.size > MAX_TRAPPED) add(detailLine("… and ${players.size - MAX_TRAPPED} more players"))
            add(detailLine("/narrator traps restore takes them all away now."))
        }
    }

    /** What `traps restore` answers: [restored] of the [before] blocks pending taken away, [left] still pending (a chunk not ready). */
    internal fun restoreReply(before: Int, restored: Int, left: Int): Reply {
        if (before == 0) return Reply(true, listOf(narratorLine("no trap block pending in the loaded chunks: nothing to take away.")))
        val changed = (before - restored - left).coerceAtLeast(0)
        val text = buildString {
            append("$restored trap ${TraceText.plural(restored, "block")} taken away now")
            if (changed > 0) append("; $changed changed by a player since set, left as ${if (changed == 1) "it is" else "they are"}")
            if (left > 0) append("; $left in a chunk still loading, taken away at the next pass")
            append('.')
        }
        return Reply.done(narratorLine(text))
    }

    /** `8s left`, or `due` (taken away within a second). */
    private fun left(until: Long, now: Long): String = if (until <= now) "due" else "${ToolText.wait(until - now)} left"

    /** A player's name when online on [server], else their UUID. */
    private fun nameIn(server: MinecraftServer): (UUID) -> String = { uuid -> server.playerList.getPlayer(uuid)?.plainTextName ?: uuid.toString() }

    /** The running Narrator of [server], if any. */
    private fun ownNarrator(server: MinecraftServer): NarratorRuntime? = NarratorRuntime.current?.takeIf { it.server === server }

    private fun text(services: NarratorServices) = TraceText(services.clock.zone, services.clock.millis())

    // ── Suggestions ─────────────────────────────────────────────────────────────────────────────────

    private val RUNNING = SuggestionProvider<CommandSourceStack> { context, builder ->
        val running = ownNarrator(context.source.server)?.mechanics?.takeIf { it.available }?.challenges?.list().orEmpty()
        SharedSuggestionProvider.suggest(running.map { it.ref.toString() }, builder)
    }

    private val ACTIVE_RULES = SuggestionProvider<CommandSourceStack> { context, builder ->
        val active = ownNarrator(context.source.server)?.mechanics?.takeIf { it.available }?.rules?.list().orEmpty()
        SharedSuggestionProvider.suggest(active.map { it.ref.toString() }, builder)
    }
}
