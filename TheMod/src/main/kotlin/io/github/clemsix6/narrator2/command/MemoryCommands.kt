package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.DirectorCommands.Reply
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryDoc
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryLogRow
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.MemoryRow
import io.github.clemsix6.narrator2.memory.MemoryRun
import io.github.clemsix6.narrator2.memory.MemoryStatus
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.SheetPart
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.enumOfId
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/narrator sheet <player> [part] [page]` and `/narrator memory …` (M3 §14): what the Narrator remembers,
 * shown to the operators (level 2, like every `/narrator` command), and the few things they may change.
 *
 * - While the memory is unavailable (`DisabledMemory`), every command is refused at once, with the reason.
 * - Reads run off the server thread ([replyLater]): the store, the sheets and recall read the Journal on
 *   its read pool. Viewing reinforces nothing and records no `recalls` row: an operator is not the Narrator.
 * - Changes (`forget`, `restore`, `note`, `rollback`, `revert`) go through the store as
 *   `Author(OPERATOR, operator = <name>)`, so `memory_log` keeps who did what, and are recorded as
 *   `operator` decisions; so are the jobs an operator starts (`chronicle`, `dream`). `pause` and `resume`
 *   are recorded by the jobs themselves (`memory paused` / `resumed`, with the operator's name).
 * - Long answers come in pages of [PAGE_LINES] lines, with a clickable link to the next page, as
 *   `/narrator trace` does ([TraceText.page]).
 */
object MemoryCommands {
    private val log = Log.command

    /** Adds `sheet` and `memory` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(sheetCommand())
        root.then(memoryCommand())
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    private fun sheetCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("sheet").then(
        argument("player", StringArgumentType.word()).suggests(ONLINE_PLAYERS)
            .executes { context -> sheetOf(context, null, 1) }
            .then(
                argument("part", StringArgumentType.word()).suggests(SHEET_PARTS)
                    .executes { context -> sheetOf(context, string(context, "part"), 1) }
                    .then(page().executes { context -> sheetOf(context, string(context, "part"), page(context)) }),
            ),
    )

    private fun sheetOf(context: CommandContext<CommandSourceStack>, part: String?, page: Int): Int {
        val player = string(context, "player")
        return read(context.source, "sheet $player") { sheet(it, player, part, page) }
    }

    private fun memoryCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("memory")
        .executes { context -> read(context.source, "memory status") { status(it) } }
        .then(literal("status").executes { context -> read(context.source, "memory status") { status(it) } })
        .then(
            literal("canon")
                .executes { context -> read(context.source, "memory canon") { canon(it, all = false, null, 1) } }
                .then(canonScope("open", all = false))
                .then(canonScope("all", all = true)),
        )
        .then(
            literal("ideas")
                .executes { context -> ideasOf(context, all = false, 1) }
                .then(page().executes { context -> ideasOf(context, all = false, page(context)) })
                .then(
                    literal("all")
                        .executes { context -> ideasOf(context, all = true, 1) }
                        .then(page().executes { context -> ideasOf(context, all = true, page(context)) }),
                ),
        )
        .then(
            literal("recall").then(
                argument("query", StringArgumentType.greedyString()).executes { context ->
                    val query = string(context, "query")
                    read(context.source, "memory recall") { recall(it, query) }
                },
            ),
        )
        .then(
            literal("show").then(
                argument("ref", StringArgumentType.word())
                    .executes { context -> showRef(context, 1) }
                    .then(page().executes { context -> showRef(context, page(context)) }),
            ),
        )
        .then(archiveCommand("forget", archived = true))
        .then(archiveCommand("restore", archived = false))
        .then(
            literal("note").then(
                argument("text", StringArgumentType.greedyString()).executes { context ->
                    val text = string(context, "text")
                    change(context.source, "memory note") { services, operator -> note(services, operator, text) }
                },
            ),
        )
        .then(
            literal("docs").then(
                argument("key", StringArgumentType.greedyString()).suggests(DOC_KEYS).executes { context ->
                    val typed = string(context, "key")
                    read(context.source, "memory docs $typed") { docs(it, typed) }
                },
            ),
        )
        .then(
            literal("rollback").then(
                argument("key_and_version", StringArgumentType.greedyString()).suggests(DOC_KEYS).executes { context ->
                    val typed = string(context, "key_and_version")
                    change(context.source, "memory rollback $typed") { services, operator -> rollback(services, operator, typed) }
                },
            ),
        )
        .then(
            literal("revert").then(
                argument("run", LongArgumentType.longArg(1)).executes { context ->
                    val run = LongArgumentType.getLong(context, "run")
                    change(context.source, "memory revert $run") { services, operator -> revert(services, operator, run) }
                },
            ),
        )
        .then(literal("chronicle").executes { context -> atOnce(context.source, "memory chronicle", ::chronicle) })
        .then(literal("dream").executes { context -> atOnce(context.source, "memory dream", ::dream) })
        .then(literal("pause").executes { context -> atOnce(context.source, "memory pause", ::pause) })
        .then(literal("resume").executes { context -> atOnce(context.source, "memory resume", ::resume) })

    /**
     * `canon open|all [page]` and `canon open|all <player> [page]`. The page comes before the player among
     * the children: a number is read as a page, any other word as a player.
     */
    private fun canonScope(scope: String, all: Boolean): LiteralArgumentBuilder<CommandSourceStack> = literal(scope)
        .executes { context -> read(context.source, "memory canon $scope") { canon(it, all, null, 1) } }
        .then(page().executes { context -> canonOf(context, scope, all, null) })
        .then(
            argument("player", StringArgumentType.word()).suggests(ONLINE_PLAYERS)
                .executes { context -> canonOf(context, scope, all, string(context, "player"), 1) }
                .then(page().executes { context -> canonOf(context, scope, all, string(context, "player")) }),
        )

    private fun canonOf(context: CommandContext<CommandSourceStack>, scope: String, all: Boolean, player: String?, page: Int = page(context)): Int =
        read(context.source, "memory canon $scope${player?.let { " $it" }.orEmpty()}") { canon(it, all, player, page) }

    private fun ideasOf(context: CommandContext<CommandSourceStack>, all: Boolean, page: Int): Int =
        read(context.source, "memory ideas${if (all) " all" else ""}") { ideas(it, all, page) }

    private fun showRef(context: CommandContext<CommandSourceStack>, page: Int): Int {
        val ref = string(context, "ref")
        return read(context.source, "memory show $ref") { show(it, ref, page) }
    }

    /** `forget <ref> [reason]` or `restore <ref> [reason]`. */
    private fun archiveCommand(name: String, archived: Boolean): LiteralArgumentBuilder<CommandSourceStack> = literal(name).then(
        argument("ref", StringArgumentType.word())
            .executes { context -> archiveRef(context, archived, null) }
            .then(
                argument("reason", StringArgumentType.greedyString())
                    .executes { context -> archiveRef(context, archived, string(context, "reason")) },
            ),
    )

    private fun archiveRef(context: CommandContext<CommandSourceStack>, archived: Boolean, reason: String?): Int {
        val ref = string(context, "ref")
        return change(context.source, "memory ${if (archived) "forget" else "restore"} $ref") { services, operator -> archive(services, operator, ref, archived, reason) }
    }

    /**
     * Brigadier's own builders, which `Commands.literal` and `Commands.argument` return: the tree then
     * builds and parses without a bootstrapped game (the unit test of the syntaxes).
     */
    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal(name)

    private fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> = RequiredArgumentBuilder.argument(name, type)

    private fun page(): RequiredArgumentBuilder<CommandSourceStack, Int> = argument("page", IntegerArgumentType.integer(1))

    private fun page(context: CommandContext<CommandSourceStack>): Int = IntegerArgumentType.getInteger(context, "page")

    private fun string(context: CommandContext<CommandSourceStack>, name: String): String = StringArgumentType.getString(context, name)

    // ── Running a command ───────────────────────────────────────────────────────────────────────────

    /** Why no memory command can run: the memory is unavailable, and why; null when it is available. */
    internal fun refusal(memory: Memory): Component? {
        if (memory.available) return null
        return errorLine("the memory is unavailable (${memory.unavailableReason ?: "no reason given"}): nothing to read nor change.")
    }

    /** The running Narrator, when it has a memory; otherwise tells [source] why not and returns null. */
    private fun withMemory(source: CommandSourceStack): NarratorRuntime? {
        val runtime = runningNarrator(source) ?: return null
        refusal(runtime.memory)?.let { line ->
            source.sendFailure(line)
            return null
        }
        return runtime
    }

    /** Runs the reading [work] off the server thread and sends its lines to [source]. [what] names it in the log (no free text). */
    private fun read(source: CommandSourceStack, what: String, work: suspend (NarratorServices) -> List<Component>): Int {
        val runtime = withMemory(source) ?: return 0
        log.info("${source.textName}: narrator $what")
        replyLater(source, runtime, what) { work(runtime) }
        return 1
    }

    /**
     * Runs the change [work] off the server thread, as the operator who typed it, and sends its [Reply] to
     * [source]; the first line of a change that was made also goes to the other operators.
     */
    private fun change(source: CommandSourceStack, what: String, work: suspend (NarratorServices, String) -> Reply): Int {
        val runtime = withMemory(source) ?: return 0
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

    /** Runs [command] on the server thread (it only touches the jobs' queue) and sends its [Reply] to [source]. */
    private fun atOnce(source: CommandSourceStack, what: String, command: (NarratorServices, String) -> Reply): Int {
        val runtime = withMemory(source) ?: return 0
        log.info("${source.textName}: narrator $what")
        val reply = command(runtime, source.textName)
        reply.lines.forEachIndexed { i, line ->
            if (reply.done) source.sendSuccess({ line }, i == 0) else source.sendFailure(line)
        }
        return if (reply.done) 1 else 0
    }

    /** A command that cannot go on, with the reason for the operator. */
    private class Refused(message: String) : Exception(message)

    private fun refuse(message: String): Nothing = throw Refused(message)

    /** The lines of [block], or a red line saying why it was refused. */
    private inline fun answer(block: () -> List<Component>): List<Component> = try {
        block()
    } catch (e: Refused) {
        listOf(errorLine(e.message.orEmpty()))
    }

    /** The [Reply] of [block]; refusals, the command's or the store's, are red. */
    private inline fun changing(block: () -> Reply): Reply = try {
        block()
    } catch (e: Refused) {
        Reply.refused(e.message.orEmpty())
    } catch (e: MemoryRejected) {
        Reply.refused("refused: ${e.message}")
    }

    private fun text(services: NarratorServices) = TraceText(services.clock.zone, services.clock.millis())

    private fun author(operator: String) = Author(Source.OPERATOR, operator = operator)

    /** An `operator` decision: [operator] did [decision] (`memory_forget`…). */
    private fun record(services: NarratorServices, decision: String, operator: String, reason: String) {
        Decisions(services.trace, services.clock).record(log, DecisionRecord.Kind.OPERATOR, decision, subject = operator, reason = reason)
    }

    // ── /narrator status ────────────────────────────────────────────────────────────────────────────

    /**
     * The `Memory:` line of `/narrator status`: `Memory: available · jobs running · nothing queued · last
     * dream 10-01 23:05 done`, or `Memory: unavailable (memory.enabled is false)`. From memory, never the
     * database: any thread.
     */
    internal fun statusLine(memory: Memory, text: TraceText): String {
        if (!memory.available) return "Memory: unavailable (${memory.unavailableReason ?: "no reason given"})"
        val status = memory.jobs.status()
        val parts = buildList {
            add("available")
            add(jobs(status, text))
            status.running?.let { add("running: ${it.kind.id} run ${it.runId}") }
            add(if (status.queued.isEmpty()) "nothing queued" else "queued: ${status.queued.joinToString { it.id }}")
            add(status.lastDream?.let { "last dream ${text.time(it.endedAt ?: it.startedAt)} ${it.status.id}" } ?: "no dream yet")
        }
        return "Memory: ${parts.joinToString(" · ")}"
    }

    /** `jobs running`, `jobs paused (/narrator memory resume)` or `jobs blocked until 21:00:00 (LLMGW budget spent)`. */
    private fun jobs(status: MemoryStatus, text: TraceText): String = when {
        status.paused -> "jobs paused (/narrator memory resume)"
        status.blockedUntil != null -> "jobs blocked until ${text.time(status.blockedUntil)} (LLMGW budget spent)"
        else -> "jobs running"
    }

    // ── memory status ───────────────────────────────────────────────────────────────────────────────

    /**
     * `/narrator memory status`: the jobs (their state, the running one, the queue, the last batch, nap
     * and dream, the next dream), the world play clock, the refusals of the last 24 hours, and, read from
     * the Journal, the rows of each table and the size of the index.
     */
    internal suspend fun status(services: NarratorServices): List<Component> {
        val text = text(services)
        val status = services.memory.jobs.status()
        val counts = try {
            services.journal.read(READ_TIMEOUT_MS) { connection -> connection.query(COUNTS_SQL) { Counts.of(this) }.single() }
        } catch (e: Exception) {
            log.warn("memory status: the rows could not be counted: ${describeFailure(e)}")
            null
        }
        val lines = buildList {
            add(Out("Memory: available · ${jobs(status, text)} · world clock ${played(status.worldPlaySeconds)} of play"))
            if (counts == null) {
                add(Out("Rows: unknown (the Journal could not be read, see the server log)", Look.FAILURE))
            } else {
                addAll(counts.lines())
            }
            status.running?.let { add(Out("Running: ${it.kind.id} run ${it.runId} (${it.trigger.id}) for ${TraceText.duration(text.now - it.startedAt.toEpochMilli())}")) }
            add(Out("Queued: ${status.queued.joinToString { it.id }.ifEmpty { "nothing" }}"))
            addAll(run("Last batch", status.lastBatch, text))
            addAll(run("Last nap", status.lastNap, text))
            addAll(run("Last dream", status.lastDream, text))
            add(Out("Next dream: ${status.nextDreamAt?.let { "${text.time(it)} (${until(it, text)})" } ?: "not planned yet (a dream follows a nap)"}"))
            val refusals = "${status.rejected24h} ${TraceText.plural(status.rejected24h, "write")} refused · " +
                "${status.voiceWarnings24h} voice ${TraceText.plural(status.voiceWarnings24h, "warning")}"
            add(Out("Last 24 h: $refusals", if (status.rejected24h > 0) Look.FAILURE else Look.DETAIL))
        }
        return text.page(Report("memory status", lines), 1, null)
    }

    /**
     * `Last batch: run 13 done 21:40:00 after 1m05s (timer)`, then `  12k tokens in, 900 out · 3 writes, 0
     * refused` and the error, if any; in red when the run failed or was interrupted.
     */
    private fun run(label: String, run: MemoryRun?, text: TraceText): List<Out> {
        run ?: return listOf(Out("$label: none yet"))
        val start = run.startedAt.toEpochMilli()
        val head = when (val end = run.endedAt) {
            null -> "run ${run.runId} ${run.status.id} since ${text.time(run.startedAt)} (${TraceText.duration(text.now - start)})"
            else -> "run ${run.runId} ${run.status.id} ${text.time(end)} after ${TraceText.duration(end.toEpochMilli() - start)}"
        }
        val details = buildList {
            if (run.inputTokens != null || run.outputTokens != null) {
                add("${TraceText.tokens(run.inputTokens ?: 0)} tokens in, ${TraceText.tokens(run.outputTokens ?: 0)} out")
            }
            add("${run.writes} ${TraceText.plural(run.writes, "write")}, ${run.rejected} refused")
            run.error?.let { add(TraceText.abbreviate(it, ERROR_CHARS)) }
        }
        val look = if (run.status == RunStatus.FAILED || run.status == RunStatus.INTERRUPTED) Look.FAILURE else Look.DETAIL
        return listOf(Out("$label: $head (${run.trigger.id})", look), Out("  ${details.joinToString(" · ")}", look))
    }

    /** What the memory holds, counted in one read (`/narrator memory status`). */
    private data class Counts(
        val memories: Long,
        val archivedMemories: Long,
        val openCanon: Long,
        val canon: Long,
        val interactions: Long,
        val chronicle: Long,
        val openNotes: Long,
        val traits: Long,
        val skills: Long,
        val relations: Long,
        val docKeys: Long,
        val docVersions: Long,
        val indexed: Long,
        val postings: Long,
        val terms: Long,
        val changes: Long,
    ) {
        /** Four short lines: the chat wraps at [WRAP] characters, and these stay under it with six-digit counts. */
        fun lines(): List<Out> = listOf(
            Out("Memories: $memories ($archivedMemories archived) · interactions: $interactions · chronicle entries: $chronicle"),
            Out("Canon: $openCanon open of $canon · notes for the Director: $openNotes open"),
            Out("Sheets: ${count(traits, "trait")} · ${count(skills, "skill")} · ${count(relations, "relation")} · ${count(docKeys, "document")} (${count(docVersions, "version")})"),
            Out("Index: ${count(indexed, "row")} · ${count(postings, "posting")} · ${count(terms, "term")} · audit: ${count(changes, "change")}"),
        )

        companion object {
            fun of(rows: java.sql.ResultSet) = Counts(
                rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5), rows.getLong(6), rows.getLong(7),
                rows.getLong(8), rows.getLong(9), rows.getLong(10), rows.getLong(11), rows.getLong(12), rows.getLong(13), rows.getLong(14),
                rows.getLong(15), rows.getLong(16),
            )

            private fun count(count: Long, one: String) = "$count ${if (count == 1L) one else "${one}s"}"
        }
    }

    // ── sheet ───────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator sheet <player> [part] [page]`: the full sheet (`FULL`, as `player_profile` gives it), or one part. */
    internal suspend fun sheet(services: NarratorServices, name: String, part: String?, page: Int): List<Component> = answer {
        val parts = when (part) {
            null, ALL_PARTS -> SheetPart.ALL
            else -> setOf(enumOfId<SheetPart>(part) ?: refuse("unknown part $part: $ALL_PARTS, ${SheetPart.entries.joinToString { it.id }}."))
        }
        val player = findPlayer(services, name) ?: refuse("no player named $name has ever played here.")
        val now = services.clock.millis()
        val sheets = services.memory.sheets
        val sheet = sheets.sheet(player.uuid, now) ?: refuse("${player.name} was never seen by the memory: no sheet yet.")
        val body = sheets.render(sheet, SheetDetail.FULL, now, parts).lines().map { Out(it) }
        val shown = part ?: ALL_PARTS
        text(services).page(Report("sheet of ${player.name} · ${if (parts == SheetPart.ALL) "full" else shown}", body), page, "narrator sheet ${player.name} $shown")
    }

    // ── canon ───────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator memory canon [open|all] [player] [page]`: open entries by default; due first, then importance, then newest. */
    internal suspend fun canon(services: NarratorServices, all: Boolean, name: String?, page: Int): List<Component> = answer {
        val player = name?.let { findPlayer(services, it) ?: refuse("no player named $it has ever played here.") }
        val entries = services.memory.store.canon(CanonFilter(open = if (all) null else true, player = player?.uuid, limit = CANON_LIMIT))
        val text = text(services)
        val scope = if (all) "all" else "open"
        val about = player?.let { " about ${it.name} (and everyone)" }.orEmpty()
        val count = when {
            entries.size >= CANON_LIMIT -> "the first $CANON_LIMIT entries"
            entries.size == 1 -> "1 entry"
            else -> "${entries.size} entries"
        }
        val lines = entries.map { canonLine(it, text) }.ifEmpty { listOf(Out(if (all) "No canon entry." else "No open canon entry.")) }
        text.page(Report("canon$about · $scope · $count", lines), page, "narrator memory canon $scope${player?.let { " ${it.name}" }.orEmpty()}")
    }

    /**
     * `C-45 promise → Mimosa · active · due 21:00:00 (in 12m) · ★7: Le Narrateur a promis… · said « … »`;
     * a closed entry says how and when it closed.
     */
    private fun canonLine(entry: CanonEntry, text: TraceText): Out {
        val parts = buildList {
            add("${entry.ref} ${entry.kind.id} → ${names(entry.players)}")
            add(entry.status.id + (entry.resolvedAt?.let { " ${text.time(it)}" }.orEmpty()))
            if (entry.status.open) entry.dueAt?.let { add(due(it, text)) }
            if (entry.freshness != null || entry.uses > 0) add(listOfNotNull(entry.freshness?.id, "${entry.uses} ${TraceText.plural(entry.uses, "use")}").joinToString(", "))
            add("★${entry.importance}: ${TraceText.abbreviate(entry.statement, STATEMENT_CHARS)}")
        }
        val quote = entry.quote?.let { " · said « ${TraceText.abbreviate(it, QUOTE_CHARS)} »" }.orEmpty()
        val resolution = entry.resolution?.let { " · ${TraceText.quote(it, QUOTE_CHARS)}" }.orEmpty()
        return Out(parts.joinToString(" · ") + quote + resolution)
    }

    // ── ideas ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * `/narrator memory ideas [all] [page]` (M6 §13): the storyteller's open ideas, or all of them, the newest first, each
     * with what became of it: dealt how many times, how long it stays open, or how and why it closed.
     */
    internal suspend fun ideas(services: NarratorServices, all: Boolean, page: Int): List<Component> = answer {
        val ideas = services.memory.store.ideas(if (all) null else IdeaStatus.OPEN, IDEAS_LIMIT)
        val text = text(services)
        val world = services.memory.clock.worldPlaySeconds(text.now)
        val scope = if (all) "all" else "open"
        val count = when {
            ideas.size >= IDEAS_LIMIT -> "the latest $IDEAS_LIMIT"
            ideas.size == 1 -> "1 idea"
            else -> "${ideas.size} ideas"
        }
        val none = if (all) "No idea yet: the storyteller writes them in the dream's programme, or when the Director asks." else "No open idea."
        val lines = ideas.map { ideaLine(it, world, text) }.ifEmpty { listOf(Out(none)) }
        text.page(Report("ideas · $scope · $count", lines + Out("Details: /narrator memory show <ref>")), page, "narrator memory ideas${if (all) " all" else ""}")
    }

    /**
     * `P-41 challenge → Bartok, Mimosa · open · 30h00m of play left · dealt 2 (last 21:40) · n4 f4 e5: « La course au
     * pic »`; a closed idea says when it closed and why.
     */
    private fun ideaLine(idea: Idea, world: Long, text: TraceText): Out {
        val parts = buildList {
            add("${idea.ref} ${idea.kind.id} → ${names(idea.players)}")
            add(idea.status.id + (idea.closedAt?.let { " ${text.time(it)}" }.orEmpty()))
            if (idea.status == IdeaStatus.OPEN) {
                val left = idea.expiresPlaySeconds - world
                add(if (left > 0) "${played(left)} of play left" else "due, the hygiene closes it")
            }
            add(if (idea.dealt == 0) "never dealt" else "dealt ${idea.dealt}${idea.lastDealtAt?.let { " (last ${text.time(it)})" }.orEmpty()}")
            add("n${idea.novelty} f${idea.funScore} e${idea.fairness}: « ${TraceText.abbreviate(idea.title, TITLE_CHARS)} »")
        }
        val reason = idea.closeReason?.let { " · ${TraceText.quote(it, REASON_CHARS)}" }.orEmpty()
        return Out(parts.joinToString(" · ") + reason)
    }

    // ── recall ──────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator memory recall <query>`: what the `recall` tool would find, best first; nothing is recorded nor reinforced. */
    internal suspend fun recall(services: NarratorServices, query: String): List<Component> = answer {
        val words = query.trim()
        val hits = services.memory.recall.search(RecallQuery(text = words, purpose = RecallPurpose.TOOL, limit = RECALL_LIMIT))
        val lines = hits.map { Out(TraceText.abbreviate("${score(it.score)} ${it.line}", HIT_CHARS)) }
        val title = "recall ${TraceText.quote(words, QUERY_CHARS)} · ${if (hits.isEmpty()) "nothing found" else "${hits.size} found"}"
        val footer = if (hits.isEmpty()) Out("Try other words; recall matches French words and game ids.") else Out("Details: /narrator memory show <ref>")
        text(services).page(Report(title, lines + footer), 1, null)
    }

    private fun score(value: Double): String = String.format(Locale.ROOT, "[%.2f]", value)

    // ── show ────────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator memory show <ref> [page]`: one row, where it comes from (source, job, turn, evidence) and its changes. */
    internal suspend fun show(services: NarratorServices, typed: String, page: Int): List<Component> = answer {
        val ref = ref(typed)
        val store = services.memory.store
        val row = store.row(ref) ?: refuse("$ref does not exist.")
        val history = store.log(ref)
        val text = text(services)
        val (title, body) = describe(row, text)
        val lines = body + provenance(row, history) + history(history, text)
        text.page(Report(title, lines), page, "narrator memory show $ref")
    }

    /** The title and the lines that say what [row] holds. */
    private fun describe(row: MemoryRow, text: TraceText): Pair<String, List<Out>> = when (row) {
        is MemoryEntry -> {
            val state = row.mergedInto?.let { " · merged into $it" } ?: if (row.archived) " · archived" else ""
            "${row.ref} · memory ${row.kind.id} · ★${row.importance}$state" to buildList {
                add(Out("Title: ${row.title}"))
                add(Out("Content: ${row.content}"))
                add(Out("When: ${text.time(row.occurredFrom)} → ${text.time(row.occurredTo)} · players: ${names(row.players)} · valence ${signed(row.valence)}"))
                add(Out("Tags: ${row.tags.joinToString().ifEmpty { "none" }} · entities: ${row.entities.joinToString().ifEmpty { "none" }}"))
                row.place?.let { add(Out("Place: ${place(it)}")) }
                val recalled = row.lastRecalledAt?.let { " (last ${text.time(it)})" }.orEmpty()
                add(
                    Out(
                        "Strength: stability ${ToolText.number(row.stabilityHours)}h · reinforced at ${played(row.reinforcedPlaySeconds)} of world play · " +
                            "recalled ${row.recalls} ${TraceText.plural(row.recalls, "time")}$recalled",
                    ),
                )
            }
        }
        is CanonEntry -> "${row.ref} · canon ${row.kind.id} · ${row.status.id}${if (row.archived) " · archived" else ""}" to buildList {
            add(Out("Statement: ${row.statement}"))
            row.quote?.let { add(Out("Quote: « $it »", Look.SAID)) }
            val due = row.dueAt?.let { " · ${if (row.status.open) due(it, text) else "due ${text.time(it)}"}" }.orEmpty()
            add(Out("To: ${names(row.players)} · ★${row.importance} · set ${text.time(row.createdAt)}$due"))
            row.stakes?.let { add(Out("Stakes: $it")) }
            row.resolvedAt?.let { at ->
                val winners = if (row.winners.isEmpty()) "" else " · winners: ${names(row.winners)}"
                add(Out("Closed ${text.time(at)}: ${row.resolution ?: "no resolution"}$winners"))
            }
            if (row.uses > 0 || row.freshness != null) {
                val last = row.lastUsedAt?.let { ", last ${text.time(it)}" }.orEmpty()
                add(Out("Uses: ${row.uses}$last${row.freshness?.let { " · ${it.id}" }.orEmpty()}"))
            }
            row.replaces?.let { add(Out("Replaces: $it")) }
            row.place?.let { add(Out("Place: ${place(it)}")) }
        }
        is Interaction -> "${row.ref} · interaction ${row.kind.id} ${signed(row.valence)} · ${row.player.name}${if (row.archived) " · archived" else ""}" to buildList {
            val flags = listOfNotNull("counts toward favor".takeIf { row.counts }, "disrespect".takeIf { row.disrespect })
            add(Out("When: ${text.time(row.ts)} · at ${played(row.playedSeconds)} of ${row.player.name}'s play${flags.joinToString("") { " · $it" }}"))
            add(Out("Summary: ${row.summary}"))
            row.quote?.let { add(Out("Quote: « $it »", Look.SAID)) }
            val links = listOfNotNull(row.canon?.let { "canon $it" }, row.memory?.let { "memory $it" }, row.toolCalls.takeIf { it.isNotEmpty() }?.let { calls -> "tool calls ${calls.joinToString { "t$it" }}" })
            if (links.isNotEmpty()) add(Out("Links: ${links.joinToString(" · ")}"))
        }
        is ChronicleEntry -> "${row.ref} · chronicle ${row.level.id} · ${text.time(row.from)} → ${text.time(row.to)}" to buildList {
            add(Out("Title: ${row.title}"))
            row.summary.lines().forEachIndexed { i, line -> add(Out(if (i == 0) "Summary: $line" else line)) }
            if (row.covers.isNotEmpty()) add(Out("Covers: ${row.covers.joinToString()}"))
            row.conversationId?.let { add(Out("Chapter: $it")) }
            when {
                row.supersededBy != null -> add(Out("Superseded by ${row.supersededBy}"))
                row.open -> add(Out("Open: rewritten as the period goes on"))
            }
        }
        is Note -> "${row.ref} · note ${row.kind.id} · ${row.status.id}" to buildList {
            add(Out("Text: ${row.text}"))
            add(Out("Created ${text.time(row.createdAt)} · shown ${row.shownCount} ${TraceText.plural(row.shownCount, "time")}"))
            if (row.players.isNotEmpty() || row.refs.isNotEmpty()) {
                add(Out("About: ${listOfNotNull(row.players.takeIf { it.isNotEmpty() }?.let(::names), row.refs.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · ")}"))
            }
        }
        is Idea -> "${row.ref} · idea ${row.kind.id} · ${row.status.id}" to buildList {
            add(Out("Title: ${row.title}"))
            add(Out("Pitch: ${row.pitch}"))
            add(Out("Fits: ${row.fit} · for: ${names(row.players)}${row.register?.let { " · ${it.id}" }.orEmpty()}"))
            add(Out("Notes: novelty ${row.novelty} · fun ${row.funScore} · fairness ${row.fairness}${if (row.refs.isEmpty()) "" else " · builds on ${row.refs.joinToString()}"}"))
            row.module?.let { add(Out("Module: $it")) }
            row.brief?.let { add(Out("Brief: $it")) }
            val dealt = row.lastDealtAt?.let { " (last ${text.time(it)})" }.orEmpty()
            add(Out("Created ${text.time(row.createdAt)} · dealt ${row.dealt} ${TraceText.plural(row.dealt, "time")}$dealt"))
            row.closedAt?.let { add(Out("Closed ${text.time(it)}: ${row.closeReason ?: "no reason"}")) }
        }
    }

    /**
     * `Source: chronicler · run 7 · turn #42 · conversation chronicler-7` and `Evidence: e1234, e1240 · t88`,
     * from the row's columns as the audit last saw them (its latest `memory_log` row).
     */
    private fun provenance(row: MemoryRow, history: List<MemoryLogRow>): List<Out> {
        val columns = history.lastOrNull()?.let { it.after ?: it.before }
        val source = columns?.string("source") ?: when (row) {
            is MemoryEntry -> row.source.id
            is CanonEntry -> row.source.id
            is Interaction -> row.source.id
            is ChronicleEntry -> row.source.id
            is Note -> null
            is Idea -> row.source.id
        }
        val origin = listOfNotNull(
            source,
            columns?.long("run_id")?.let { "run $it" },
            columns?.long("turn_id")?.let { "turn #$it" },
            columns?.string("conversation_id")?.takeIf { row !is ChronicleEntry }?.let { "conversation $it" },
        )
        val evidence = listOfNotNull(
            columns?.longs("evidence_events")?.takeIf { it.isNotEmpty() }?.joinToString { "e$it" },
            columns?.longs("evidence_tools")?.takeIf { it.isNotEmpty() }?.joinToString { "t$it" },
        )
        return buildList {
            if (origin.isNotEmpty()) add(Out("Source: ${origin.joinToString(" · ")}"))
            if (evidence.isNotEmpty()) add(Out("Evidence: ${evidence.joinToString(" · ")}"))
        }
    }

    /** `History (3 changes):`, then `10-01 21:40 update by director (turn #45): status active → fulfilled · "reason"`, oldest first. */
    private fun history(history: List<MemoryLogRow>, text: TraceText): List<Out> {
        if (history.isEmpty()) return listOf(Out("History: no change recorded"))
        return listOf(Out("History (${history.size} ${TraceText.plural(history.size, "change")}):")) + history.map { change ->
            val job = listOfNotNull(change.runId?.let { "run $it" }, change.turnId?.let { "turn #$it" }).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
            val diff = changes(change.before, change.after).let { if (it.isEmpty()) "" else ": $it" }
            val reason = change.reason?.let { " · ${TraceText.quote(it, REASON_CHARS)}" }.orEmpty()
            Out("  ${text.time(change.ts)} ${change.op.id} by ${change.actor}$job$diff$reason")
        }
    }

    /** `status active → fulfilled, archived false → true`: the columns that changed, [MAX_CHANGES] at most. */
    private fun changes(before: JsonObject?, after: JsonObject?): String {
        if (before == null || after == null) return if (after == null && before != null) "row removed" else ""
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        val shown = changed.take(MAX_CHANGES).joinToString(", ") { "$it ${value(before[it])} → ${value(after[it])}" }
        return if (changed.size > MAX_CHANGES) "$shown, +${changed.size - MAX_CHANGES} more" else shown
    }

    private fun value(element: JsonElement?): String = when (element) {
        null, JsonNull -> "–"
        is JsonPrimitive -> TraceText.abbreviate(element.content, VALUE_CHARS)
        else -> TraceText.abbreviate(element.toString(), VALUE_CHARS)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.longs(key: String): List<Long> = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()

    // ── forget, restore, note ───────────────────────────────────────────────────────────────────────

    /**
     * `/narrator memory forget <ref> [reason]` (archives: out of the briefings, the reminiscences and `recall`,
     * even with `include_closed`; never deleted, so the raw tables stay readable by `sql`) and
     * `restore <ref> [reason]` (the reverse). Memories, canon entries and interactions only.
     */
    internal suspend fun archive(services: NarratorServices, operator: String, typed: String, archived: Boolean, reason: String?): Reply = changing {
        val ref = ref(typed)
        val why = reason?.trim()?.ifEmpty { null } ?: if (archived) "forgotten by an operator" else "restored by an operator"
        services.memory.store.archive(ref, archived, why, author(operator))
        record(services, if (archived) "memory_forget" else "memory_restore", operator, "$ref: $why")
        if (archived) {
            Reply.done(narratorLine("$ref forgotten: archived, out of the briefings, reminiscences and recall (even with include_closed); the raw tables stay readable by sql; /narrator memory restore $ref brings it back."))
        } else {
            Reply.done(narratorLine("$ref restored: the Narrator may recall it again."))
        }
    }

    /** `/narrator memory note <text>`: a note for the Director, shown in its next briefings until it is done. */
    internal suspend fun note(services: NarratorServices, operator: String, text: String): Reply = changing {
        val note = services.memory.store.addNote(NoteDraft(NoteKind.OPERATOR, text.trim()), author(operator)).row
        record(services, "memory_note", operator, "${note.ref}: ${TraceText.quote(note.text, REASON_CHARS)}")
        Reply.done(narratorLine("${note.ref} written: the Director reads it at its next chapter's briefing."))
    }

    // ── docs, rollback ──────────────────────────────────────────────────────────────────────────────

    /**
     * `/narrator memory docs <key> [version] [page]`: the versions of a document, newest first, or one
     * version's text. The key is `grimoire`, `review` or `sheet:<player>:<section>`, the player by name.
     */
    internal suspend fun docs(services: NarratorServices, typed: String): List<Component> = answer {
        val words = typed.trim().split(WHITESPACE)
        val (key, shown) = docKey(services, words[0])
        val version = words.getOrNull(1)?.let { it.toIntOrNull() ?: refuse("$it is not a version number.") }
        val page = words.getOrNull(2)?.let { it.toIntOrNull()?.takeIf { page -> page >= 1 } ?: refuse("$it is not a page number.") } ?: 1
        val store = services.memory.store
        val text = text(services)
        if (version == null) {
            val versions = store.docVersions(key.key)
            if (versions.isEmpty()) refuse("$shown has no version yet.")
            val lines = versions.take(DOC_VERSIONS).mapIndexed { i, doc -> Out(versionLine(doc, text) + if (i == 0) " (current)" else "") }
            val older = versions.size - DOC_VERSIONS
            val more = if (older > 0) listOf(Out("… and $older older ${TraceText.plural(older, "version")}")) else emptyList()
            text.page(Report("document $shown · ${versions.size} ${TraceText.plural(versions.size, "version")}", lines + more + Out("Read one: /narrator memory docs $shown <version>")), 1, null)
        } else {
            val doc = store.doc(key.key, version) ?: refuse("$shown has no version $version${store.doc(key.key)?.let { " (latest: ${it.version})" }.orEmpty()}.")
            val evidence = doc.evidence.takeIf { it.isNotEmpty() }?.let { refs -> Out("Evidence: ${refs.take(MAX_EVIDENCE).joinToString()}${if (refs.size > MAX_EVIDENCE) ", +${refs.size - MAX_EVIDENCE}" else ""}") }
            val lines = doc.content.lines().map { Out(it) } + listOfNotNull(evidence)
            text.page(Report("$shown ${versionLine(doc, text)}", lines), page, "narrator memory docs $shown $version")
        }
    }

    /** `v3 · 10-02 23:20 · dreamer · 8412 chars`, and `· restored from v1` for a rollback. */
    private fun versionLine(doc: MemoryDoc, text: TraceText): String =
        "v${doc.version} · ${text.time(doc.writtenAt)} · ${doc.source.id} · ${doc.content.length} chars" + (doc.restoredFrom?.let { " · restored from v$it" }.orEmpty())

    /** `/narrator memory rollback <key> <version>`: the version is copied as the document's new version. */
    internal suspend fun rollback(services: NarratorServices, operator: String, typed: String): Reply = changing {
        val words = typed.trim().split(WHITESPACE)
        if (words.size != 2) refuse("rollback takes a document and a version: /narrator memory rollback grimoire 3.")
        val (key, shown) = docKey(services, words[0])
        val version = words[1].toIntOrNull() ?: refuse("${words[1]} is not a version number.")
        val doc = services.memory.store.rollbackDoc(key.key, version, author(operator))
        record(services, "memory_rollback", operator, "$shown: version $version restored as version ${doc.version}")
        Reply.done(narratorLine("$shown: version $version restored as version ${doc.version}; /narrator memory docs $shown lists them."))
    }

    /** The document an operator typed (`grimoire`, `review`, `sheet:<player>:<section>`) and how to show it (with the player's name). */
    private suspend fun docKey(services: NarratorServices, typed: String): Pair<DocKey, String> {
        when (typed.lowercase()) {
            DocKey.Grimoire.key -> return DocKey.Grimoire to DocKey.Grimoire.key
            DocKey.Review.key -> return DocKey.Review to DocKey.Review.key
        }
        val parts = typed.split(':')
        if (parts.size != 3 || !parts[0].equals("sheet", ignoreCase = true)) {
            refuse("unknown document $typed: grimoire, review or sheet:<player>:<section> (${SheetSection.entries.joinToString { it.id }}).")
        }
        val section = enumOfId<SheetSection>(parts[2].lowercase()) ?: refuse("unknown sheet section ${parts[2]}: ${SheetSection.entries.joinToString { it.id }}.")
        val player = findPlayer(services, parts[1]) ?: refuse("no player named ${parts[1]} has ever played here.")
        return DocKey.Sheet(player.uuid, section) to "sheet:${player.name}:${section.id}"
    }

    // ── revert ──────────────────────────────────────────────────────────────────────────────────────

    /** `/narrator memory revert <run>`: every write of a memory job undone, newest first, from the audit. */
    internal suspend fun revert(services: NarratorServices, operator: String, runId: Long): Reply = changing {
        val running = services.memory.jobs.status().running
        if (running?.runId == runId) refuse("run $runId is running: wait for its end (/narrator memory pause keeps the next ones from starting).")
        val undone = services.memory.store.revertRun(runId, author(operator))
        record(services, "memory_revert", operator, "run $runId: $undone ${TraceText.plural(undone, "write")} undone")
        if (undone == 0) {
            Reply.done(narratorLine("run $runId wrote nothing in the memory: nothing to undo."))
        } else {
            Reply.done(narratorLine("run $runId reverted: $undone ${TraceText.plural(undone, "write")} undone, as if it never ran."))
        }
    }

    // ── chronicle, dream, pause, resume ─────────────────────────────────────────────────────────────

    /** `/narrator memory chronicle`: the Chronicler catches up now (naps of the closed chapters, a batch of the open one). */
    internal fun chronicle(services: NarratorServices, operator: String): Reply = started(services, operator, "memory_chronicle", services.memory.jobs.requestChronicle(operator)) { run ->
        "the Chronicler is queued (run $run): it notes what the Narrator said and did that it has not read yet."
    }

    /** `/narrator memory dream`: a deep dream now (sheets, the Narrator's review, the chronicle, the Grimoire, the storyteller's programme). */
    internal fun dream(services: NarratorServices, operator: String): Reply = started(services, operator, "memory_dream", services.memory.jobs.requestDream(operator)) { run ->
        "a deep dream is queued (run $run): it rewrites the sheets, the review of the Narrator, the chronicle and the Grimoire, then the storyteller writes its programme."
    }

    /** The answer to a job [result]: queued (with a word when the jobs wait), or why not. */
    private fun started(services: NarratorServices, operator: String, decision: String, result: Result<Long>, done: (Long) -> String): Reply {
        val run = result.getOrElse { return Reply.refused(it.message ?: "the memory jobs refused") }
        record(services, decision, operator, "run $run")
        val status = services.memory.jobs.status()
        val waits = when {
            status.paused -> detailLine("The memory jobs are paused: it starts after /narrator memory resume.")
            status.blockedUntil != null -> detailLine("The LLMGW budget is spent: it starts at ${text(services).time(status.blockedUntil)}.")
            status.running != null -> detailLine("It starts once ${status.running.kind.id} run ${status.running.runId} ends.")
            else -> null
        }
        return Reply(true, listOfNotNull(narratorLine(done(run)), waits))
    }

    /** `/narrator memory pause`: no batch, nap nor dream starts until `resume`; the code's work goes on. */
    internal fun pause(services: NarratorServices, operator: String): Reply {
        val jobs = services.memory.jobs
        if (jobs.status().paused) return Reply.done(narratorLine("the memory jobs are already paused; /narrator memory resume starts them again."))
        jobs.pause(operator)
        return Reply.done(
            narratorLine("memory jobs paused: no batch, nap nor dream starts until /narrator memory resume (a running one ends first)."),
            detailLine("The code's work goes on: play clock, due times, stats, hygiene."),
        )
    }

    /** `/narrator memory resume`: what waited starts. */
    internal fun resume(services: NarratorServices, operator: String): Reply {
        val jobs = services.memory.jobs
        if (!jobs.status().paused) return Reply.done(narratorLine("the memory jobs were not paused: nothing changes."))
        jobs.resume(operator)
        return Reply.done(narratorLine("memory jobs resumed: what waited starts now."))
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────────

    /** The ref an operator typed (`C-45`, `c45`). */
    private fun ref(typed: String): Ref = Ref.parse(typed) ?: refuse("$typed is not a memory ref: M-, C-, I-, H-, N- or P- and a number, like C-45.")

    /**
     * The player named [name]: online, else by their current or a former name in the Journal (the latest
     * seen first), ignoring case; a UUID is taken as it is. Null when nobody ever played under that name.
     */
    internal suspend fun findPlayer(services: NarratorServices, name: String): PlayerRef? {
        val uuid = runCatching { UUID.fromString(name) }.getOrNull()
        if (uuid == null) {
            services.players.byName(name)?.let { online ->
                withTimeoutOrNull(BARRIER_MS) { services.journal.barrier() } // a player who just joined is written
                return online.ref
            }
        }
        if (!services.journal.available) return uuid?.let { services.players.get(it)?.ref ?: PlayerRef(it, it.toString().take(8)) }
        return services.journal.read(READ_TIMEOUT_MS) { connection ->
            if (uuid != null) {
                connection.query("SELECT name FROM players WHERE player_uuid = ?", uuid) { PlayerRef(uuid, getString(1)) }.firstOrNull()
                    ?: PlayerRef(uuid, uuid.toString().take(8))
            } else {
                connection.query(PLAYER_SQL, name, name, name) { PlayerRef(UUID.fromString(getString(1)), getString(2)) }.firstOrNull()
            }
        }
    }

    private fun names(players: List<PlayerRef>): String = players.joinToString { it.name }.ifEmpty { "everyone" }

    /** `due 21:00:00 (in 12m)`, or `overdue since 20:00:00 (12m)`. */
    private fun due(at: Instant, text: TraceText): String {
        val left = at.toEpochMilli() - text.now
        return if (left >= 0) "due ${text.time(at)} (in ${ToolText.wait(left)})" else "overdue since ${text.time(at)} (${ToolText.duration(-left)})"
    }

    private fun until(at: Instant, text: TraceText): String {
        val left = at.toEpochMilli() - text.now
        return if (left > 0) "in ${ToolText.wait(left)}" else "now"
    }

    /** Play time in hours: `45m`, `61h12m`. */
    private fun played(seconds: Long): String =
        if (seconds < 3_600) ToolText.duration(seconds * 1_000) else "${seconds / 3_600}h${(seconds % 3_600 / 60).toString().padStart(2, '0')}m"

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun place(pos: Pos): String = "${ToolText.dimension(pos.dim)} ${pos.x} ${pos.y} ${pos.z}"

    private val ONLINE_PLAYERS = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    private val SHEET_PARTS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(listOf(ALL_PARTS) + SheetPart.entries.map { it.id }, builder)
    }

    /** `grimoire`, `review`, and the sheet sections of the players online. */
    private val DOC_KEYS = SuggestionProvider<CommandSourceStack> { context, builder ->
        val sheets = context.source.onlinePlayerNames.flatMap { name -> SheetSection.entries.map { "sheet:$name:${it.id}" } }
        SharedSuggestionProvider.suggest(listOf(DocKey.Grimoire.key, DocKey.Review.key) + sheets, builder)
    }

    /** `/narrator sheet <player> all`: every part. */
    private const val ALL_PARTS = "all"

    /** Canon entries listed at most. */
    private const val CANON_LIMIT = 300

    /** Ideas listed at most, and the characters of a title in their list. */
    private const val IDEAS_LIMIT = 200
    private const val TITLE_CHARS = 60

    /** Hits of `recall`: two chat lines each at most, so the answer fits in the chat. */
    private const val RECALL_LIMIT = 8
    private const val HIT_CHARS = 2 * WRAP - 4

    /** Versions listed by `docs <key>`. */
    private const val DOC_VERSIONS = 12

    private const val MAX_EVIDENCE = 20
    private const val MAX_CHANGES = 4
    private const val VALUE_CHARS = 40
    private const val STATEMENT_CHARS = 140
    private const val QUOTE_CHARS = 100
    private const val QUERY_CHARS = 60
    private const val REASON_CHARS = 120
    private const val ERROR_CHARS = 120
    private const val READ_TIMEOUT_MS = 10_000L
    private const val BARRIER_MS = 1_000L

    private val WHITESPACE = Regex("\\s+")

    /** A player by their current name first, then by a former one, the latest seen first (as `player_profile` finds them). */
    private const val PLAYER_SQL = "SELECT player_uuid::VARCHAR, name FROM players WHERE lower(name) = lower(?) " +
        "OR player_uuid IN (SELECT player_uuid FROM player_names WHERE lower(name) = lower(?)) " +
        "ORDER BY lower(name) = lower(?) DESC, last_seen DESC LIMIT 1"

    /** What `/narrator memory status` counts, in [Counts] order. */
    private const val COUNTS_SQL = "SELECT " +
        "(SELECT count(*) FROM memories WHERE NOT archived), (SELECT count(*) FROM memories WHERE archived), " +
        "(SELECT count(*) FROM canon WHERE NOT archived AND status IN ('planned', 'active')), (SELECT count(*) FROM canon WHERE NOT archived), " +
        "(SELECT count(*) FROM interactions WHERE NOT archived), (SELECT count(*) FROM chronicle WHERE superseded_by IS NULL), " +
        "(SELECT count(*) FROM notes WHERE status <> 'done'), " +
        "(SELECT count(*) FROM player_traits WHERE NOT retired), (SELECT count(*) FROM player_skills), (SELECT count(*) FROM player_relations), " +
        "(SELECT count(DISTINCT key) FROM memory_docs), (SELECT count(*) FROM memory_docs), " +
        "(SELECT count(*) FROM search_docs), (SELECT count(*) FROM search_postings), (SELECT count(DISTINCT term) FROM search_postings), " +
        "(SELECT count(*) FROM memory_log)"
}
