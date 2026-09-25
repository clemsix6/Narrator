package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.guard.DeathReport
import io.github.clemsix6.narrator2.trace.DeathLine
import io.github.clemsix6.narrator2.trace.DecisionLine
import io.github.clemsix6.narrator2.trace.MessageFate
import io.github.clemsix6.narrator2.trace.ModelCall
import io.github.clemsix6.narrator2.trace.SaidLine
import io.github.clemsix6.narrator2.trace.TaskDetail
import io.github.clemsix6.narrator2.trace.ToldEvent
import io.github.clemsix6.narrator2.trace.ToolUse
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.trace.TurnDetail
import io.github.clemsix6.narrator2.trace.TurnSummary
import io.github.clemsix6.narrator2.trace.Usage
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `/narrator trace [n]`, `trace turn <id> [page]`, `trace task <id> [page]`, `why`, `why turn <id>`,
 * `why <player>` and `usage [hours]` (M1_M2 §14.4): what the Director was told, what it thought, what
 * it did and said, and why it said nothing. Each waits for the Journal to write what came before (one
 * second at most), then reads the trace tables through [TraceQueries], off the server thread
 * ([replyLater]). Long answers come in pages of [PAGE_LINES] lines, with a clickable link to the next.
 */
object TraceCommands {
    private val log = Log.command

    /** Adds this module's subcommands under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("trace")
                .executes { turns(it.source, DEFAULT_TURNS) }
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_TURNS)).executes { turns(it.source, IntegerArgumentType.getInteger(it, "count")) })
                .then(
                    Commands.literal("turn").then(
                        Commands.argument("id", LongArgumentType.longArg(1))
                            .executes { turn(it.source, LongArgumentType.getLong(it, "id"), 1) }
                            .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { turn(it.source, LongArgumentType.getLong(it, "id"), IntegerArgumentType.getInteger(it, "page")) }),
                    ),
                )
                .then(
                    Commands.literal("task").then(
                        Commands.argument("id", LongArgumentType.longArg(1))
                            .executes { task(it.source, LongArgumentType.getLong(it, "id"), 1) }
                            .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes { task(it.source, LongArgumentType.getLong(it, "id"), IntegerArgumentType.getInteger(it, "page")) }),
                    ),
                ),
        )
        root.then(
            Commands.literal("why")
                .executes { why(it.source, null) }
                .then(Commands.literal("turn").then(Commands.argument("id", LongArgumentType.longArg(1)).executes { why(it.source, LongArgumentType.getLong(it, "id")) }))
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { context, builder -> SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder) }
                        .executes { whyPlayer(it.source, StringArgumentType.getString(it, "player")) },
                ),
        )
        root.then(
            Commands.literal("usage")
                .executes { usage(it.source, DEFAULT_USAGE_HOURS) }
                .then(Commands.argument("hours", IntegerArgumentType.integer(1, MAX_USAGE_HOURS)).executes { usage(it.source, IntegerArgumentType.getInteger(it, "hours")) }),
        )
    }

    private fun turns(source: CommandSourceStack, count: Int): Int = read(source, "trace $count") { queries, text ->
        text.page(text.turns(queries.lastTurns(count)), 1, null)
    }

    private fun turn(source: CommandSourceStack, id: Long, page: Int): Int = read(source, "trace turn $id") { queries, text ->
        val detail = queries.turn(id) ?: return@read listOf(errorLine("no Director turn #$id in the Journal."))
        text.page(text.turn(detail), page, "narrator trace turn $id")
    }

    private fun task(source: CommandSourceStack, id: Long, page: Int): Int = read(source, "trace task $id") { queries, text ->
        val detail = queries.task(id) ?: return@read listOf(errorLine("no task T-$id in the Journal."))
        text.page(text.task(detail), page, "narrator trace task $id")
    }

    private fun why(source: CommandSourceStack, id: Long?): Int = read(source, if (id == null) "why" else "why turn $id") { queries, text ->
        val turnId = id ?: queries.lastActingTurnId() ?: return@read listOf(narratorLine("the Director has not acted yet: nothing to explain. /narrator trace shows its turns."))
        val detail = queries.turn(turnId) ?: return@read listOf(errorLine("no Director turn #$turnId in the Journal."))
        text.capped(text.why(detail))
    }

    private fun whyPlayer(source: CommandSourceStack, player: String): Int {
        val director = NarratorRuntime.current?.director?.status()
        return read(source, "why $player") { queries, text ->
            val fate = queries.lastMessage(player)
            val death = queries.lastDeath(player)
            if (fate == null && death == null) return@read listOf(narratorLine("$player never wrote in the public chat nor prayed, and no death of theirs is known."))
            val turn = fate?.turn?.let { queries.turn(it.turnId) }
            fate?.let { text.capped(text.fate(player, it, turn, director)) }.orEmpty() + death?.let { text.capped(text.death(it)) }.orEmpty()
        }
    }

    private fun usage(source: CommandSourceStack, hours: Int): Int = read(source, "usage $hours") { queries, text ->
        text.page(text.usage(queries.usage(text.now - hours * HOUR_MS), hours), 1, null)
    }

    /**
     * Runs [query] on the Journal off the server thread, once what came before is written, and sends its
     * lines back to [source]. [what] names the command in the log.
     */
    private fun read(source: CommandSourceStack, what: String, query: (TraceQueries, TraceText) -> List<Component>): Int {
        val runtime = runningNarrator(source) ?: return 0
        if (!runtime.journal.available) {
            source.sendFailure(errorLine("the Journal is unavailable (${runtime.journal.health().lastError ?: "see the server log"}): no trace can be read."))
            return 0
        }
        log.info("${source.textName}: narrator $what")
        replyLater(source, runtime, what) {
            withTimeoutOrNull(BARRIER_MS) { runtime.journal.barrier() }
            val text = TraceText(runtime.clock.zone, runtime.clock.millis())
            runtime.journal.read(READ_TIMEOUT_MS) { connection -> query(TraceQueries(connection), text) }
        }
        return 1
    }

    const val DEFAULT_TURNS = 5
    const val MAX_TURNS = 20
    const val DEFAULT_USAGE_HOURS = 24
    const val MAX_USAGE_HOURS = 24 * 30

    /** Longest wait for the Journal to write what came before. */
    private const val BARRIER_MS = 1_000L

    /** Longest wait for a read connection and the queries. */
    private const val READ_TIMEOUT_MS = 10_000L

    private const val HOUR_MS = 3_600_000L
}

/** How a line of a reply looks: sober colors, gray by default. */
internal enum class Look { DETAIL, THOUGHT, SAID, FAILURE }

/** A line of a reply before it becomes chat. */
internal data class Out(val text: String, val look: Look = Look.DETAIL)

/** A reply: its title (after the `[Narrator]` prefix) and its lines. */
internal data class Report(val title: String, val lines: List<Out>)

/** Characters of a chat line before it is wrapped, so that a line stays readable in the chat's width. */
internal const val WRAP = 96

/** Lines of a page, title and footer excluded: a page fits in the 20 lines of the open chat. */
internal const val PAGE_LINES = 15

/**
 * The text of the trace replies, in English, times in [zone], relative to [now] (epoch milliseconds).
 * Pure: tests render fixed data and compare lines.
 */
internal class TraceText(private val zone: ZoneId, val now: Long) {
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    // ── Reports ─────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator trace [n]`: one line per turn, newest first. */
    fun turns(turns: List<TurnSummary>): Report {
        if (turns.isEmpty()) return Report("no Director turn yet", emptyList())
        val lines = turns.map { turn ->
            val parts = listOfNotNull(
                "${turn.llmCalls} ${plural(turn.llmCalls, "call")}",
                turn.tools.takeIf { it.isNotEmpty() }?.let(::toolList),
                turn.endedAt?.let { duration(it.toEpochMilli() - turn.startedAt.toEpochMilli()) } ?: "for ${duration(now - turn.startedAt.toEpochMilli())}",
                turn.contextTokens?.let { "${tokens(it)} ctx" },
            )
            Out("#${turn.turnId} ${time(turn.startedAt)} ${turn.woke ?: turn.trigger} → ${turn.outcome ?: "running"} · ${parts.joinToString(" · ")}", lookOf(turn.outcome))
        }
        return Report("last ${turns.size} ${plural(turns.size, "turn")}, newest first", lines + Out("Details: /narrator trace turn <id> · /narrator why turn <id>"))
    }

    /** `/narrator trace turn <id>`: everything the turn was told, thought, did and said. */
    fun turn(detail: TurnDetail): Report {
        val turn = detail.summary
        val lines = buildList {
            add(Out("Woke for: ${turn.woke ?: turn.trigger} (trigger ${turn.trigger})"))
            if (detail.woken.isNotEmpty()) {
                add(Out("Told at wake-up:"))
                detail.woken.forEach { add(Out("  ${event(it)}")) }
            }
            if (detail.injected.isNotEmpty()) {
                add(Out("Told between tool calls:"))
                detail.injected.forEach { add(Out("  ${event(it)}")) }
            }
            detail.calls.forEachIndexed { i, call ->
                add(Out("Request ${i + 1} · ${request(call)}", if (call.error != null || call.refusalCategory != null) Look.FAILURE else Look.DETAIL))
                add(thought(call, THOUGHT_CHARS_IN_TRACE, "  "))
                call.tools.forEach { add(tool(it)) }
            }
            detail.unmatchedTools.forEach { add(tool(it)) }
            detail.said.forEach { add(said(it)) }
            detail.decisions.forEach { add(decision(it)) }
            detail.transcript?.let { add(Out("Transcript: $it")) }
        }
        return Report("turn #${turn.turnId}${turn.chapter?.let { " · chapter $it" }.orEmpty()} · ${time(turn.startedAt)} · ${outcome(turn)}", lines)
    }

    /** `/narrator trace task <id>`: a worker's task, its requests and its result. */
    fun task(task: TaskDetail): Report {
        val lines = buildList {
            add(Out(listOfNotNull("Queued ${time(task.createdAt)}", task.startedAt?.let { "started ${time(it)}" }, task.finishedAt?.let { "ended ${time(it)}" }, task.requestedByTurn?.let { "asked by turn #$it" }).joinToString(" · ")))
            add(Out("Brief: ${quote(task.brief, BRIEF_CHARS)}"))
            task.calls.forEachIndexed { i, call ->
                add(Out("Request ${i + 1} · ${request(call)}", if (call.error != null || call.refusalCategory != null) Look.FAILURE else Look.DETAIL))
                add(thought(call, THOUGHT_CHARS_IN_TRACE, "  "))
                call.tools.forEach { add(tool(it)) }
            }
            task.result?.let { add(Out("Result: ${quote(it, RESULT_CHARS)}")) }
            task.error?.let { add(Out("Error: $it", Look.FAILURE)) }
            task.transcript?.let { add(Out("Transcript: $it")) }
        }
        val took = task.startedAt?.let { start -> duration((task.finishedAt?.toEpochMilli() ?: now) - start.toEpochMilli()) }
        val status = when {
            task.finishedAt != null && took != null -> "${task.status} in $took"
            took != null -> "${task.status} for $took"
            else -> task.status
        }
        return Report("task T-${task.taskId} · ${task.role} · $status", lines)
    }

    /** `/narrator why`: why a turn acted, in short: what woke it, what it thought, what it did and said. */
    fun why(detail: TurnDetail): Report {
        val turn = detail.summary
        return Report("why turn #${turn.turnId} · ${time(turn.startedAt)}, ${ago(turn.startedAt)} · ${turn.outcome ?: "running"}", whyBody(detail))
    }

    /** `/narrator why <player>`: what became of the player's last message, and why. */
    fun fate(player: String, fate: MessageFate, turn: TurnDetail?, director: DirectorStatus?): Report {
        val message = fate.message
        val lines = buildList {
            add(Out("${if (message.kind == "chat:pray") "Prayer" else "Public chat"}: ${quote(message.what.orEmpty(), QUOTE_CHARS)}"))
            fate.triage?.let { triage ->
                val parts = listOfNotNull(triage.toNarrator?.let { "to the Narrator ${p(it)}" }, triage.tone, triage.intent, triage.manipulation?.takeIf { it >= 0.5 }?.let { "manipulation ${p(it)}" }, triage.source)
                add(Out("Reflexes: ${parts.joinToString(" · ")}"))
            }
            add(Out("Salience: ${message.salience ?: "not classified yet"}"))
            when {
                fate.turn != null -> {
                    val told = fate.turn
                    add(Out("Told in turn #${told.turnId} ${if (fate.atWake) "at wake-up" else "between two tool calls"}, ${time(told.startedAt)} → ${told.outcome ?: "running"}", lookOf(told.outcome)))
                    turn?.let { addAll(whyBody(it, withEvents = false)) }
                }
                fate.dropped != null -> add(Out("Dropped: ${fate.dropped.reason}", Look.FAILURE))
                else -> add(waiting(director, message.salience))
            }
        }
        val who = message.player ?: player
        return Report("why: $who's last message · ${time(message.ts)}, ${ago(message.ts)}", lines)
    }

    /**
     * `/narrator why <player>`, after the last message: the player's last death as the death guard
     * explained it (M2 review, finding 18): cause and blame, the guard's arming and quota, and what the
     * Narrator did to them just before. In red, a death not blamed on the Narrator that follows its actions.
     */
    fun death(line: DeathLine): Report {
        val report = line.detail?.let(DeathReport::parse)
            ?: return Report("why: ${line.player}'s last death · ${time(line.ts)}, ${ago(line.ts)}", listOf(Out(line.reason.orEmpty())))
        val died = Instant.ofEpochMilli(report.at)
        val actions = report.actions
        val window = "${DeathReport.WINDOW_MS / 1_000} s"
        val lines = buildList {
            val blame = if (report.narrator) "caused by the Narrator" else "not the Narrator's"
            val suspicious = !report.narrator && !actions.isNullOrEmpty()
            add(Out("Cause: ${report.cause} · $blame · inventory ${if (report.keptInventory) "kept" else "dropped"}", if (suspicious) Look.FAILURE else Look.DETAIL))
            add(Out("Death guard: ${report.guard()} · ${report.quota()}"))
            when {
                actions == null -> add(Out("The Narrator's actions of the $window before are unknown: ${report.actionsUnknown ?: "not read"}", Look.FAILURE))
                actions.isEmpty() -> add(Out("No world action of the Narrator on ${report.player} in the $window before."))
                else -> {
                    add(Out("The Narrator acted on ${report.player} in the $window before:"))
                    actions.forEach { action ->
                        val turn = action.turnId?.let { " (turn #$it)" }.orEmpty()
                        val result = action.result?.let { ": ${abbreviate(it, WHY_RESULT_CHARS)}" }.orEmpty()
                        add(Out("  -${report.secondsBefore(action)} s ${action.tool}$turn ${action.status}$result", if (action.status == "ok") Look.DETAIL else Look.FAILURE))
                    }
                    actions.mapNotNull { it.turnId }.distinct().takeIf { it.isNotEmpty() }?.let { turns ->
                        add(Out("More: ${turns.joinToString(" · ") { "/narrator why turn $it" }}"))
                    }
                }
            }
        }
        return Report("why: ${report.player}'s last death · ${time(died)}, ${ago(died)}", lines)
    }

    /** `/narrator usage [hours]`: model calls per provider and role. */
    fun usage(rows: List<Usage>, hours: Int): Report {
        val lines = rows.map { row ->
            val failed = if (row.failed > 0) " · ${row.failed} failed" else ""
            if (row.provider == "anthropic") {
                Out("${row.role}: ${row.calls} ${plural(row.calls, "call")} · ${tokensIn(row)} · ${tokens(row.outputTokens)} out · ${seconds(row.averageLatencyMs)} avg$failed", if (row.failed > 0) Look.FAILURE else Look.DETAIL)
            } else {
                Out("${row.provider} (${row.role}): ${row.calls} ${plural(row.calls, "call")} · ${row.averageLatencyMs} ms avg$failed", if (row.failed > 0) Look.FAILURE else Look.DETAIL)
            }
        }
        return Report("usage over the last $hours h", lines.ifEmpty { listOf(Out("no model call")) })
    }

    /** The consumption line of `/narrator status`: `Usage (24 h): Claude 48 calls · … · Jev 120 calls`. */
    fun usageSummary(rows: List<Usage>, hours: Int): String {
        val claude = rows.filter { it.provider == "anthropic" }
        val others = rows.filter { it.provider != "anthropic" }.groupBy { it.provider }
        val parts = buildList {
            if (claude.isNotEmpty()) {
                val total = Usage("anthropic", "all", claude.sumOf { it.calls }, claude.sumOf { it.failed }, claude.sumOf { it.inputTokens }, claude.sumOf { it.cacheReadTokens }, claude.sumOf { it.cacheWriteTokens }, claude.sumOf { it.outputTokens }, 0)
                add("Claude ${total.calls} ${plural(total.calls, "call")} · ${tokensIn(total)} · ${tokens(total.outputTokens)} out" + if (total.failed > 0) " · ${total.failed} failed" else "")
            }
            others.forEach { (provider, list) ->
                val calls = list.sumOf { it.calls }
                val failed = list.sumOf { it.failed }
                add("${provider.replaceFirstChar { it.uppercase() }} $calls ${plural(calls, "call")}" + if (failed > 0) " ($failed failed)" else "")
            }
        }
        return "Usage ($hours h): ${parts.joinToString(" · ").ifEmpty { "no model call" }}"
    }

    // ── Chat components ─────────────────────────────────────────────────────────────────────────────

    /**
     * Page [page] of [report]: the title, at most [PAGE_LINES] wrapped lines, and, when there are several
     * pages, a footer with the next page's command ([command] without `/` and without the page number),
     * clickable. A page past the last shows the last. Without [command], the report is short by
     * construction (`trace [n]`, `usage`) and comes whole.
     */
    fun page(report: Report, page: Int, command: String?): List<Component> {
        val lines = report.lines.flatMap(::wrap)
        if (command == null) return listOf(narratorLine(report.title)) + lines.map(::component)
        val pages = ((lines.size + PAGE_LINES - 1) / PAGE_LINES).coerceAtLeast(1)
        val shown = page.coerceIn(1, pages)
        val title = if (pages > 1) "${report.title} · page $shown/$pages" else report.title
        val body = lines.drop((shown - 1) * PAGE_LINES).take(PAGE_LINES).map(::component)
        val footer = if (pages > 1) listOf(footer(shown, pages, command)) else emptyList()
        return listOf(narratorLine(title)) + body + footer
    }

    /** [report] on one page of at most [MAX_LINES] lines; the rest is cut, with a pointer to `trace turn`. */
    fun capped(report: Report): List<Component> {
        val lines = report.lines.flatMap(::wrap)
        val body = if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES - 1) + Out("… cut: /narrator trace turn shows everything")
        return listOf(narratorLine(report.title)) + body.map(::component)
    }

    private fun footer(page: Int, pages: Int, command: String): Component {
        val line = Component.literal("page $page/$pages").withStyle(ChatFormatting.DARK_GRAY)
        if (page < pages) line.append(Component.literal(" · next: ").withStyle(ChatFormatting.DARK_GRAY)).append(link("/$command ${page + 1}"))
        if (page > 1) line.append(Component.literal(" · previous: ").withStyle(ChatFormatting.DARK_GRAY)).append(link("/$command ${page - 1}"))
        return line
    }

    private fun link(command: String): Component = Component.literal(command).withStyle { style ->
        style.withColor(ChatFormatting.GRAY)
            .withClickEvent(ClickEvent.RunCommand(command))
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to run $command")))
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────────

    /** The lines of `why` after its title; without what woke the turn when [withEvents] is false. */
    private fun whyBody(detail: TurnDetail, withEvents: Boolean = true): List<Out> = buildList {
        val turn = detail.summary
        if (withEvents) {
            add(Out("Woke for: ${turn.woke ?: turn.trigger}"))
            detail.woken.take(WHY_EVENTS).forEach { add(Out("  ${event(it)}")) }
            if (detail.woken.size > WHY_EVENTS) add(Out("  … and ${detail.woken.size - WHY_EVENTS} more"))
            detail.injected.take(WHY_EVENTS).forEach { add(Out("  then: ${event(it)}")) }
        }
        val thinking = detail.calls.filter { !it.thinking.isNullOrBlank() }
        thinking.take(WHY_CALLS).forEach { add(thought(it, THOUGHT_CHARS_IN_WHY, "")) }
        if (thinking.size > WHY_CALLS) add(Out("… ${thinking.size - WHY_CALLS} more thinking summaries", Look.THOUGHT))
        if (thinking.isEmpty()) add(Out("Thought: (no summary)", Look.THOUGHT))
        val tools = detail.calls.flatMap { it.tools } + detail.unmatchedTools
        val (acting, reading) = tools.partition { it.effect != "read" }
        acting.forEach { use ->
            val targets = use.targets.takeIf { it.isNotEmpty() }?.let { " → ${it.joinToString(", ")}" }.orEmpty()
            add(Out("Did: ${use.tool}$targets (${use.status}): ${abbreviate(use.result.orEmpty(), WHY_RESULT_CHARS)}", if (use.status == "ok") Look.DETAIL else Look.FAILURE))
        }
        if (reading.isNotEmpty()) add(Out("Looked: ${toolList(reading.map { it.tool })}"))
        if (acting.isEmpty()) add(Out("Did nothing: ${if (detail.calls.isEmpty()) "no request was answered" else "no speech, world or task tool was called"}"))
        detail.said.forEach { add(said(it)) }
        detail.decisions.filter { it.decision in REFUSALS }.forEach { add(decision(it)) }
        add(Out("More: /narrator trace turn ${turn.turnId}${detail.transcript?.let { " · $it" }.orEmpty()}"))
    }

    /** Why a message was not told yet, from the Director's live status: red when the Director cannot hear it. */
    private fun waiting(director: DirectorStatus?, salience: String?): Out {
        fun silent(why: String) = Out("Not told to the Director: $why", Look.FAILURE)
        fun pending(why: String) = Out("Not told to the Director yet: $why")
        director ?: return silent("the Narrator is not running.")
        val reason = director.reason?.let { " ($it)" }.orEmpty()
        val until = director.until?.let { " until ${time(Instant.ofEpochMilli(it))}" }.orEmpty()
        return when (director.state) {
            DirectorState.DISABLED -> silent("the Director is disabled$reason; what it misses is not kept.")
            DirectorState.STOPPED -> silent("the server is stopping.")
            DirectorState.PAUSED -> silent("the Director is paused$reason; a message to it waits for /narrator resume.")
            DirectorState.BLOCKED, DirectorState.BACKOFF -> silent("the Director is ${director.state.name.lowercase()}$until$reason.")
            DirectorState.RUNNING -> pending("a turn is running; it gets it with its next request.")
            DirectorState.WAITING -> pending("a wake-up is scheduled${director.until?.let { " at ${time(Instant.ofEpochMilli(it))}" }.orEmpty()}.")
            else -> if (salience?.startsWith("next") == true) {
                pending("it waits for the next wake-up (it does not wake the Director by itself).")
            } else if (salience?.startsWith("throttled") == true) {
                pending("it waits: the Narrator's wake-ups are throttled (see Salience).")
            } else {
                pending("the Director is ${director.state.name.lowercase()}$reason.")
            }
        }
    }

    private fun event(event: ToldEvent): String {
        val body = when (event.kind) {
            "chat:public" -> "CHAT ${event.player}: ${quote(event.what.orEmpty(), QUOTE_CHARS)}"
            "chat:pray" -> "PRAY ${event.player}: ${quote(event.what.orEmpty(), QUOTE_CHARS)}"
            "chat:narrator", "chat:operator" -> "NARRATOR (${event.kind.removePrefix("chat:")}): ${quote(event.what.orEmpty(), QUOTE_CHARS)}"
            else -> listOfNotNull(event.kind, event.player).joinToString(" ") + event.what?.let { ": $it" }.orEmpty() + event.where?.let { " ($it)" }.orEmpty()
        }
        return "${time(event.ts)} $body${event.salience?.let { " · $it" }.orEmpty()}"
    }

    private fun request(call: ModelCall): String {
        val parts = buildList {
            add(time(call.startedAt))
            add(seconds(call.latencyMs.toLong()))
            if (call.purpose != "turn" && call.purpose != "worker") add(call.purpose)
            call.inputTokens?.let { input ->
                val context = input.toLong() + (call.cacheReadTokens ?: 0) + (call.cacheWriteTokens ?: 0)
                add("${tokens(context)} in (${tokens((call.cacheReadTokens ?: 0).toLong())} cached)")
            }
            call.outputTokens?.let { add("${tokens(it.toLong())} out") }
            call.stopReason?.let { add(it) }
            call.refusalCategory?.let { add("refused: $it") }
            call.error?.let { add("error: $it") }
        }
        return parts.joinToString(" · ")
    }

    private fun thought(call: ModelCall, max: Int, indent: String): Out {
        val thinking = call.thinking?.trim().orEmpty()
        return Out("${indent}Thought: ${if (thinking.isEmpty()) "(no summary)" else abbreviate(thinking, max)}", Look.THOUGHT)
    }

    private fun tool(use: ToolUse): Out {
        val args = use.args?.let { " ${abbreviate(it, ARGS_CHARS)}" }.orEmpty()
        val result = use.result?.let { ": ${abbreviate(it, RESULT_LINE_CHARS)}" }.orEmpty()
        return Out("  → ${use.tool}$args · ${use.status} ${use.durationMs} ms$result", if (use.status == "ok") Look.DETAIL else Look.FAILURE)
    }

    private fun said(line: SaidLine): Out {
        val to = line.recipients?.joinToString(", ") ?: "everyone"
        val through = line.operator?.let { " (through operator $it)" }.orEmpty()
        return Out("Said to $to$through: ${quote(line.text, QUOTE_CHARS)}", Look.SAID)
    }

    private fun decision(decision: DecisionLine): Out {
        val text = listOfNotNull("${decision.kind} ${decision.decision}", decision.subject).joinToString(" ") + decision.reason?.let { ": $it" }.orEmpty()
        return Out("Engine: $text", if (decision.decision in REFUSALS) Look.FAILURE else Look.DETAIL)
    }

    private fun outcome(turn: TurnSummary): String {
        val end = turn.endedAt ?: return "running for ${duration(now - turn.startedAt.toEpochMilli())}"
        return "${turn.outcome ?: "ended"} in ${duration(end.toEpochMilli() - turn.startedAt.toEpochMilli())}"
    }

    private fun lookOf(outcome: String?): Look = if (outcome == null || outcome in QUIET_OUTCOMES) Look.DETAIL else Look.FAILURE

    private fun tokensIn(usage: Usage): String {
        val total = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
        val cached = if (total == 0L) 0 else (usage.cacheReadTokens * 100 / total).toInt()
        return "${tokens(total)} in ($cached% cached)"
    }

    // ── Formats ─────────────────────────────────────────────────────────────────────────────────────

    /** `21:32:41` today, `09-24 21:32` another day. */
    fun time(at: Instant): String {
        val local = at.atZone(zone)
        return if (local.toLocalDate() == today) TIME.format(local) else DAY_TIME.format(local)
    }

    /** `42s ago`, `5m ago`, `3h ago`, `2d ago`. */
    fun ago(at: Instant): String {
        val ms = (now - at.toEpochMilli()).coerceAtLeast(0)
        return when {
            ms < 60_000 -> "${ms / 1_000}s ago"
            ms < 3_600_000 -> "${ms / 60_000}m ago"
            ms < 86_400_000 -> "${ms / 3_600_000}h ago"
            else -> "${ms / 86_400_000}d ago"
        }
    }

    /** Wraps [line] at spaces into lines of at most [WRAP] characters; the next ones are indented. */
    fun wrap(line: Out): List<Out> {
        if (line.text.length <= WRAP) return listOf(line)
        val lead = line.text.takeWhile { it == ' ' }
        val indent = lead + " ".repeat(CONTINUATION_INDENT)
        val lines = mutableListOf<String>()
        val current = StringBuilder(lead)
        var start = lead.length
        fun breakLine() {
            lines += current.toString()
            current.setLength(0)
            current.append(indent)
            start = indent.length
        }
        for (word in line.text.substring(lead.length).split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.isNotEmpty()) {
                val spaced = current.length > start
                val room = WRAP - current.length - if (spaced) 1 else 0
                when {
                    rest.length <= room -> {
                        if (spaced) current.append(' ')
                        current.append(rest)
                        rest = ""
                    }
                    spaced -> breakLine()
                    else -> { // a word longer than a line is cut
                        current.append(rest.take(room))
                        rest = rest.drop(room)
                        breakLine()
                    }
                }
            }
        }
        if (current.length > start) lines += current.toString()
        return lines.map { Out(it, line.look) }
    }

    private fun component(line: Out): Component = when (line.look) {
        Look.DETAIL -> Component.literal(line.text).withStyle(ChatFormatting.GRAY)
        Look.THOUGHT -> Component.literal(line.text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        Look.SAID -> Component.literal(line.text).withStyle(ChatFormatting.WHITE)
        Look.FAILURE -> Component.literal(line.text).withStyle(ChatFormatting.RED)
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

        /** Lines of a reply that is not paged. */
        const val MAX_LINES = 18

        private const val CONTINUATION_INDENT = 4
        private const val QUOTE_CHARS = 200
        private const val ARGS_CHARS = 120
        private const val RESULT_LINE_CHARS = 160
        private const val WHY_RESULT_CHARS = 100
        private const val BRIEF_CHARS = 600
        private const val RESULT_CHARS = 1_500
        private const val THOUGHT_CHARS_IN_TRACE = 1_500
        private const val THOUGHT_CHARS_IN_WHY = 300
        private const val WHY_EVENTS = 3
        private const val WHY_CALLS = 3

        /** Outcomes of a turn that went as planned. */
        private val QUIET_OUTCOMES = setOf("acted", "idle")

        /** Decisions that stopped something, shown in red. */
        private val REFUSALS = setOf("denied", "refused", "prevented", "dropped", "closed", "backed off", "blocked", "resent")

        /** `sql ×2, say`: tools in order of first use, with their count. */
        fun toolList(tools: List<String>): String =
            tools.groupingBy { it }.eachCount().entries.joinToString(", ") { (tool, count) -> if (count > 1) "$tool ×$count" else tool }

        /** `812`, `61k`, `1.2M`. */
        fun tokens(count: Long): String = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "${(count + 500) / 1_000}k"
            else -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0)
        }

        /** `8.1 s` under 10 s, `41 s`, `5m05s`, `1h05m`. */
        fun duration(ms: Long): String = when {
            ms < 10_000 -> seconds(ms)
            ms < 60_000 -> "${ms / 1_000} s"
            ms < 3_600_000 -> String.format(Locale.ROOT, "%dm%02ds", ms / 60_000, ms / 1_000 % 60)
            else -> String.format(Locale.ROOT, "%dh%02dm", ms / 3_600_000, ms / 60_000 % 60)
        }

        /** `8.1 s`. */
        fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1_000.0)

        /** [text] on one line, cut to [max] characters with `…`. */
        fun abbreviate(text: String, max: Int): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
        }

        /** [text] between quotes, on one line, cut to [max] characters. */
        fun quote(text: String, max: Int): String = "\"${abbreviate(text, max)}\""

        fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

        private fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
