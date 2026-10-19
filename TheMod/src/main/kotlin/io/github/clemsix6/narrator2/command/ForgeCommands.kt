package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.command.DirectorCommands.Reply
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/narrator forge …` and `/narrator module …` (M5 §14): the arsenal, the review of a pending version, the Forge's
 * switch, the running modules and their stop. They replace the `/narrator module list | unload | unload-all` of
 * `ARCHITECTURE.md` §13. Gray English lines (refusals in red); long lists come in pages that fit the open chat
 * ([TraceText.page]).
 *
 * - `forge` = `forge status`: the Forge, its compiler, its arsenal, the modules running, the switch, the forger.
 * - `forge list [page]`, `forge show <name> [version]`: the arsenal's versions, one in full.
 * - `forge approve|reject <name> [version]`: a pending version becomes `ready` or `rejected` ([Forge.review]).
 * - `forge off [reason]`, `forge on`: the switch (M5 §3.7). `off` stops every module now, cancels the forger's task,
 *   and keeps the Forge off across restarts ([io.github.clemsix6.narrator2.mechanics.ModuleControl.halt], the world's
 *   state); only `on`, typed by an operator, lifts it.
 * - `module` = `module list [page]`, `module stop <id> [note]`: the deployments, and the stop of one as the operator.
 *
 * Without the Forge ([Forge.available] false) the arsenal's commands are refused at once with the reason; the
 * modules' commands and the switch go to the modules' control, which refuses what it cannot do, and `forge status`
 * always answers (it says why the Forge is off). Lists read snapshots on the server thread; what changes something
 * runs off the server thread ([replyOn]) and is an `operator` decision ([FORGE_OFF], [FORGE_ON], [FORGE_APPROVE],
 * [FORGE_REJECT], [MODULE_STOP]); the Forge and the engine record their own.
 */
object ForgeCommands {
    private val log = Log.command

    /** The `operator` decisions of the commands that act. */
    const val FORGE_OFF = "forge_off"
    const val FORGE_ON = "forge_on"
    const val FORGE_APPROVE = "forge_approve"
    const val FORGE_REJECT = "forge_reject"
    const val MODULE_STOP = "module_stop"

    /** The switch's reason when the operator gives none. */
    const val NO_REASON = "no reason given"

    /** The canon's resolution of a module stopped without a note: the engine's own words are English too. */
    const val STOP_NOTE = "stopped by an operator"

    /** Characters of the switch's reason: kept in the world's state and shown to the operators. */
    const val REASON_CHARS = 200

    /** Module names listed when the one asked is unknown; the rest are counted. */
    private const val KNOWN_NAMES = 10

    /** The shortest summary a line of `forge list` keeps, cut further only by the chat's wrapping. */
    private const val MIN_SUMMARY_CHARS = 30

    /** Adds `forge` and `module` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(forgeCommand())
        root.then(moduleCommand())
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    private fun forgeCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("forge")
        .executes { context -> show(context.source, "forge status") { status(it) } }
        .then(literal("status").executes { context -> show(context.source, "forge status") { status(it) } })
        .then(
            literal("list")
                .executes { context -> show(context.source, "forge list") { arsenalList(it, 1) } }
                .then(page().executes { context -> show(context.source, "forge list") { arsenalList(it, page(context)) } }),
        )
        .then(
            literal("show").then(
                argument("name", StringArgumentType.word()).suggests(NAMES)
                    .executes { context -> showOf(context, null) }
                    .then(version().executes { context -> showOf(context, version(context)) }),
            ),
        )
        .then(reviewCommand("approve", approve = true))
        .then(reviewCommand("reject", approve = false))
        .then(
            literal("off")
                .executes { context -> change(context.source, "forge off") { services, operator -> off(services, operator, null) } }
                .then(
                    argument("reason", StringArgumentType.greedyString())
                        .executes { context -> change(context.source, "forge off") { services, operator -> off(services, operator, string(context, "reason")) } },
                ),
        )
        .then(literal("on").executes { context -> change(context.source, "forge on") { services, operator -> on(services, operator) } })

    private fun showOf(context: CommandContext<CommandSourceStack>, version: Int?): Int {
        val name = string(context, "name")
        return show(context.source, "forge show $name${version?.let { " $it" }.orEmpty()}") { showEntry(it, name, version) }
    }

    private fun reviewCommand(verb: String, approve: Boolean): LiteralArgumentBuilder<CommandSourceStack> = literal(verb).then(
        argument("name", StringArgumentType.word()).suggests(PENDING)
            .executes { context -> reviewOf(context, verb, approve, null) }
            .then(version().executes { context -> reviewOf(context, verb, approve, version(context)) }),
    )

    private fun reviewOf(context: CommandContext<CommandSourceStack>, verb: String, approve: Boolean, version: Int?): Int {
        val name = string(context, "name")
        return change(context.source, "forge $verb $name${version?.let { " $it" }.orEmpty()}") { services, operator -> review(services, operator, name, version, approve) }
    }

    private fun moduleCommand(): LiteralArgumentBuilder<CommandSourceStack> = literal("module")
        .executes { context -> show(context.source, "module list") { moduleList(it, 1) } }
        .then(
            literal("list")
                .executes { context -> show(context.source, "module list") { moduleList(it, 1) } }
                .then(page().executes { context -> show(context.source, "module list") { moduleList(it, page(context)) } }),
        )
        .then(
            literal("stop").then(
                argument("id", StringArgumentType.word()).suggests(RUNNING)
                    .executes { context -> stopOf(context, null) }
                    .then(argument("note", StringArgumentType.greedyString()).executes { context -> stopOf(context, string(context, "note")) }),
            ),
        )

    private fun stopOf(context: CommandContext<CommandSourceStack>, note: String?): Int {
        val id = string(context, "id")
        return change(context.source, "module stop $id") { services, operator -> stopModule(services, operator, id, note) }
    }

    /** Brigadier's own builders, as in [MechanicsCommands]: the tree builds and parses without a bootstrapped game. */
    private fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal(name)

    private fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> = RequiredArgumentBuilder.argument(name, type)

    private fun page(): RequiredArgumentBuilder<CommandSourceStack, Int> = argument("page", IntegerArgumentType.integer(1))

    private fun page(context: CommandContext<CommandSourceStack>): Int = IntegerArgumentType.getInteger(context, "page")

    private fun version(): RequiredArgumentBuilder<CommandSourceStack, Int> = argument("version", IntegerArgumentType.integer(0))

    private fun version(context: CommandContext<CommandSourceStack>): Int = IntegerArgumentType.getInteger(context, "version")

    private fun string(context: CommandContext<CommandSourceStack>, name: String): String = StringArgumentType.getString(context, name)

    // ── Running a command ───────────────────────────────────────────────────────────────────────────

    /** Runs [command] on the server thread (it reads snapshots only) and sends its [Reply] to [source] alone. */
    private fun show(source: CommandSourceStack, what: String, command: (NarratorServices) -> Reply): Int {
        val runtime = runningNarrator(source) ?: return 0
        log.info("${source.textName}: narrator $what")
        val reply = command(runtime)
        reply.lines.forEach { line -> if (reply.done) source.sendSuccess({ line }, false) else source.sendFailure(line) }
        return if (reply.done) 1 else 0
    }

    /**
     * Runs the change [work] off the server thread (it waits for the Forge's files or the canon), as the operator who
     * typed it, and sends its [Reply] to [source]: refusals in red; the first line of a change that was made also goes
     * to the other operators.
     */
    private fun change(source: CommandSourceStack, what: String, work: suspend (NarratorServices, String) -> Reply): Int {
        val runtime = runningNarrator(source) ?: return 0
        val operator = source.textName
        log.info("$operator: narrator $what")
        val done = AtomicBoolean()
        var first = true // touched on the server thread only
        replyOn(runtime.server, runtime.scope, what, { line ->
            if (done.get()) source.sendSuccess({ line }, first) else source.sendFailure(line)
            first = false
        }) { work(runtime, operator).also { done.set(it.done) }.lines }
        return 1
    }

    /** An `operator` decision: [operator] did [decision]; the switch's two are logged by the Forge's subsystem. */
    private fun record(services: NarratorServices, decision: String, operator: String, reason: String, detail: JsonObject? = null) {
        val switch = decision == FORGE_OFF || decision == FORGE_ON
        Decisions(services.trace, services.clock).record(
            if (switch) Log.forge else log, DecisionRecord.Kind.OPERATOR, decision, subject = operator, reason = reason, detail = detail,
            level = if (decision == FORGE_OFF) Log.Level.WARN else Log.Level.INFO,
        )
    }

    /** A refusal of the Forge or of the modules' control, as the operator reads it: `refused: <its message>.` */
    private fun refusedText(e: Exception): String = "refused: ${e.message.orEmpty().trimEnd('.')}."

    private fun refusedBy(e: Exception): Reply = Reply.refused(refusedText(e))

    /** Why the arsenal cannot be shown or changed: the Forge does not run, and why; null when it runs. */
    internal fun arsenalRefusal(forge: Forge): String? {
        if (forge.available) return null
        return "the Forge is unavailable (${forge.unavailableReason ?: "no reason given"}): no arsenal to show or change."
    }

    /** Why no module can run: the engine or the Forge is unavailable, and why; null when both run. */
    internal fun unavailable(services: NarratorServices): String? = when {
        !services.mechanics.available -> "the mechanics are unavailable (${services.mechanics.unavailableReason ?: "no reason given"})"
        !services.forge.available -> "the Forge is unavailable (${services.forge.unavailableReason ?: "no reason given"})"
        else -> null
    }

    // ── forge status ────────────────────────────────────────────────────────────────────────────────

    /**
     * The `Forge:` line of `/narrator status`: `Forge: on · 2 modules running`, `Forge: off (forge.enabled is false) · 0
     * modules running`, or `Forge: switched off by Clement at 21:04:10 · 0 modules running`. From snapshots: any thread.
     */
    fun statusLine(services: NarratorServices): String {
        val running = services.mechanics.modules.list().size
        return "Forge: ${stateOf(services, text(services))} · $running ${TraceText.plural(running, "module")} running"
    }

    /**
     * `forge status`: `Forge: on · ECJ 3.46.100 · arsenal 9 (2 forged, 1 pending, 0 broken) · 2 running · compiler idle
     * · leaks 0`, then the switch, then the settings and the forger's tasks. Always answers: it is where an operator
     * sees why the Forge is off.
     */
    fun status(services: NarratorServices): Reply {
        val forge = services.forge
        val modules = services.mechanics.modules
        val text = text(services)
        val compiler = forge.status()
        val arsenal = forge.arsenal.list()
        val config = services.settings.get().config.forge
        val forger = services.workers.list().filter { it.role == Role.FORGER && !it.status.finished }.joinToString { task ->
            val since = task.startedAt?.let { " ${TraceText.duration(text.now - it.toEpochMilli())}" }.orEmpty()
            "T-${task.taskId} ${task.status.id}$since"
        }
        val switch = modules.halted?.let { halt ->
            "Switch: set by ${halt.by} at ${whenOf(halt, text)}: « ${halt.reason} ». Nothing is deployed nor restarted until /narrator forge on lifts it."
        } ?: "Switch: not set. /narrator forge off stops every module now and keeps the Forge off, across restarts, until /narrator forge on."
        return Reply.done(
            narratorLine("forge status"),
            detailLine(
                "Forge: ${stateOf(services, text)} · ECJ ${compiler.ecj ?: "unknown"} · arsenal ${arsenal.size} ${breakdown(arsenal)} · " +
                    "${modules.list().size} running · compiler ${compiler.compiler} · leaks ${modules.leaks}",
            ),
            detailLine(switch),
            detailLine("Approval: ${config.approval} · ${config.maxActive} modules at most · forger: ${forger.ifEmpty { "idle" }}"),
        )
    }

    /**
     * `on`; `off (<why>)` when the Forge does not run; `switched off by Clement at 21:04:10` while the switch is set;
     * both when both.
     */
    private fun stateOf(services: NarratorServices, text: TraceText): String {
        val forge = services.forge
        val switch = services.mechanics.modules.halted?.let { "switched off by ${it.by} at ${whenOf(it, text)}" }
        if (forge.available) return switch ?: "on"
        return "off (${forge.unavailableReason ?: "no reason given"})" + switch?.let { ", $it" }.orEmpty()
    }

    /** `21:04:10`, or `an unknown time` for a switch read back from a damaged state. */
    private fun whenOf(halt: ForgeHalt, text: TraceText): String = if (halt.at > 0) text.time(Instant.ofEpochMilli(halt.at)) else "an unknown time"

    /** `(2 forged, 1 pending, 0 broken)`: what the versions of the arsenal, built-in ones included, are. */
    private fun breakdown(entries: List<ArsenalEntry>): String =
        "(${entries.count { !it.builtIn }} forged, ${entries.count { it.status == ModuleStatus.PENDING }} pending, " +
            "${entries.count { it.status == ModuleStatus.BROKEN }} broken)"

    // ── forge list, show ────────────────────────────────────────────────────────────────────────────

    /** `forge list [page]`: every version of the arsenal, newest first, one line each: name, version, status, author, summary. */
    fun arsenalList(services: NarratorServices, page: Int): Reply {
        arsenalRefusal(services.forge)?.let { return Reply.refused(it) }
        val entries = services.forge.arsenal.list()
        if (entries.isEmpty()) return Reply.done(narratorLine("arsenal · empty"))
        val text = text(services)
        val lines = entries.map { Out(entryLine(it, text)) } + buildList {
            add(Out("Show one: /narrator forge show <name> [version]"))
            if (entries.any { it.status == ModuleStatus.PENDING }) add(Out("Review a pending one: /narrator forge approve|reject <name> [version]"))
        }
        val title = "arsenal · ${entries.size} ${TraceText.plural(entries.size, "version")} ${breakdown(entries)}"
        return Reply(true, text.page(Report(title, lines), page, "narrator forge list"))
    }

    /** `statues v2 pending · forger T-31 · 21:04:10 · Players freeze…`; `tag v0 ready · built-in · …`. */
    private fun entryLine(entry: ArsenalEntry, text: TraceText): String {
        val head = "${entry.name} v${entry.version} ${entry.status.id} · " + if (entry.builtIn) "built-in" else "${entry.by} · ${text.time(entry.savedAt)}"
        return "$head · ${TraceText.abbreviate(entry.summary, (WRAP - head.length - 3).coerceAtLeast(MIN_SUMMARY_CHARS))}"
    }

    /**
     * `forge show <name> [version]`: [version] of [name], or its newest version whatever its status: manifest,
     * parameters, scenarios, build, hash, where its source is, its problem, its other versions. At most 20 lines.
     */
    fun showEntry(services: NarratorServices, name: String, version: Int?): Reply {
        arsenalRefusal(services.forge)?.let { return Reply.refused(it) }
        val arsenal = services.forge.arsenal
        val all = arsenal.list()
        val versions = all.filter { it.name == name }.sortedByDescending { it.version }
        if (versions.isEmpty()) {
            val names = all.map { it.name }.distinct().sorted()
            val known = names.take(KNOWN_NAMES).joinToString() + if (names.size > KNOWN_NAMES) " and ${names.size - KNOWN_NAMES} more" else ""
            return Reply.refused("no module $name in the arsenal; it has: ${known.ifEmpty { "nothing" }}.")
        }
        val entry = if (version == null) versions.first() else versions.firstOrNull { it.version == version }
            ?: return Reply.refused("$name has no v$version; its versions: ${versions.joinToString { "v${it.version}" }}.")
        val text = text(services)
        val line = { label: String, value: String -> Out(TraceText.abbreviate("$label: $value", WRAP)) }
        val lines = buildList {
            add(line("Summary", entry.summary))
            entry.note?.let { add(line("Note", it)) }
            entry.params.forEach { add(Out(paramLine(it))) }
            add(line("Scenarios", entry.scenarios.joinToString().ifEmpty { "none" }))
            add(Out("Built: " + if (entry.classes > 0) "SDK ${entry.sdk} · ${entry.classes} classes, ${entry.bytes} bytes" else "not yet (SDK ${entry.sdk})"))
            add(Out("Hash: ${entry.hash}"))
            add(Out("Source: " + (arsenal.file(entry.name, entry.version)?.toString() ?: "in the mod's jar (narrator2/forge/arsenal/${entry.name}.java)")))
            entry.problem?.let { add(line("Problem", it)) }
            add(line("Versions", versions.joinToString { "v${it.version} ${it.status.id}" }))
            if (entry.status == ModuleStatus.PENDING) add(Out("Review it: /narrator forge approve|reject ${entry.name} ${entry.version}"))
        }
        val by = if (entry.builtIn) "built-in" else "${entry.by} · saved ${text.time(entry.savedAt)}"
        return Reply(true, text.page(Report("${entry.name} v${entry.version} · ${entry.status.id} · $by", lines), 1, null))
    }

    /** `Param minutes: integer, default 5, 2 to 15 · How long it lasts.`, on one line. */
    private fun paramLine(param: ParamSpec): String {
        val traits = buildList {
            add(param.type.name.lowercase())
            add(if (param.required) "required" else "default ${param.fallback ?: "none"}")
            val min = param.min?.let(::number)
            val max = param.max?.let(::number)
            when {
                min != null && max != null -> add("$min to $max")
                min != null -> add("at least $min")
                max != null -> add("at most $max")
            }
            if (param.choices.isNotEmpty()) add("one of ${param.choices.joinToString()}")
        }
        return TraceText.abbreviate("Param ${param.name}: ${traits.joinToString(", ")} · ${param.doc}", WRAP)
    }

    /** `8`, or `0.5`. */
    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    // ── forge approve, reject ───────────────────────────────────────────────────────────────────────

    /**
     * `forge approve|reject <name> [version]`: [name] at [version] (its latest pending version when null) becomes `ready`
     * (the Director may deploy it) or `rejected`, as [operator]. Off the server thread.
     */
    suspend fun review(services: NarratorServices, operator: String, name: String, version: Int?, approve: Boolean): Reply {
        arsenalRefusal(services.forge)?.let { return Reply.refused(it) }
        val entry = try {
            services.forge.review(name, version, approve, operator)
        } catch (e: ForgeRejected) {
            return refusedBy(e)
        }
        val verdict = if (approve) "approved" else "rejected"
        record(
            services, if (approve) FORGE_APPROVE else FORGE_REJECT, operator, "${entry.name} v${entry.version} $verdict",
            buildJsonObject {
                put("module", entry.name)
                put("version", entry.version)
            },
        )
        val effect = if (approve) "the Director can deploy it" else "the Director will not see it"
        return Reply.done(narratorLine("${entry.name} v${entry.version} $verdict by $operator: $effect."))
    }

    // ── forge off, on ───────────────────────────────────────────────────────────────────────────────

    /**
     * `forge off [reason]`: the switch (M5 §3.7). Every module ends now (the modules' control stops them, resolves their
     * canon and tells the Director), the switch is kept in the world's state, so a restart starts nothing again, and the
     * forger's tasks, running or queued, are cancelled. Until `forge on`, nothing is deployed nor restarted. Off the
     * server thread.
     */
    suspend fun off(services: NarratorServices, operator: String, reason: String?): Reply {
        val modules = services.mechanics.modules
        modules.halted?.let { halt ->
            return Reply.refused("the Forge is already switched off (by ${halt.by} at ${whenOf(halt, text(services))}: « ${halt.reason} »); /narrator forge on lifts it.")
        }
        val why = reason?.trim()?.takeIf { it.isNotEmpty() } ?: NO_REASON
        if (why.length > REASON_CHARS) return Reply.refused("the reason has ${why.length} characters; $REASON_CHARS at most.")
        val stopped = try {
            modules.halt(why, operator)
        } catch (e: MechanicRejected) {
            return Reply.refused(unavailable(services)?.let { "$it: no module can run, nothing to switch off." } ?: refusedText(e))
        }
        val cancelled = cancelForger(services.workers)
        val modulesText = if (stopped.isEmpty()) "no module was running" else "${stopped.size} ${TraceText.plural(stopped.size, "module")} stopped (${stopped.joinToString { "${it.ref} ${it.module}" }})"
        val tasksText = if (cancelled.isEmpty()) "" else ", the forger's ${TraceText.plural(cancelled.size, "task")} ${cancelled.joinToString { "T-$it" }} cancelled"
        record(
            services, FORGE_OFF, operator, "the Forge switched off: « $why »; $modulesText$tasksText",
            buildJsonObject {
                put("reason", why)
                putJsonArray("stopped") { stopped.forEach { add(it.ref.toString()) } }
                putJsonArray("cancelled") { cancelled.forEach { add(it) } }
            },
        )
        return Reply.done(
            narratorLine("the Forge is switched off by $operator: $modulesText$tasksText."),
            detailLine("Nothing is deployed nor restarted, across restarts too, until /narrator forge on."),
        )
    }

    /** Cancels the forger's tasks not finished; their ids. */
    private fun cancelForger(workers: WorkersControl): List<Long> =
        workers.list().filter { it.role == Role.FORGER && !it.status.finished }.mapNotNull { task ->
            workers.cancel(task.taskId).fold(
                onSuccess = { task.taskId },
                onFailure = { e ->
                    log.warn("forge off: the forger's task T-${task.taskId} was not cancelled: ${e.message}")
                    null
                },
            )
        }

    /**
     * `forge on`: lifts the switch, as [operator]; modules may be deployed again. The modules it stopped stay stopped.
     * Off the server thread.
     */
    suspend fun on(services: NarratorServices, operator: String): Reply {
        val modules = services.mechanics.modules
        val halt = modules.halted
            ?: return Reply.refused(unavailable(services)?.let { "$it: its switch is not set, nothing to lift." } ?: "the Forge is not switched off: nothing to lift.")
        try {
            modules.resume(operator)
        } catch (e: MechanicRejected) {
            return refusedBy(e)
        }
        val before = "switched off by ${halt.by} at ${whenOf(halt, text(services))}: « ${halt.reason} »"
        record(
            services, FORGE_ON, operator, "the Forge switched back on; it was $before",
            buildJsonObject {
                put("by", halt.by)
                put("at", halt.at)
                put("reason", halt.reason)
            },
        )
        return Reply.done(
            narratorLine("the Forge is switched back on by $operator: modules may be deployed again."),
            detailLine("It was $before. The modules it stopped stay stopped."),
        )
    }

    // ── module list, stop ───────────────────────────────────────────────────────────────────────────

    /** `module list [page]`: the running modules, oldest first: their line of the situation, version, acts, faults, time per second. */
    fun moduleList(services: NarratorServices, page: Int): Reply {
        val modules = services.mechanics.modules
        val views = modules.list()
        val text = text(services)
        if (views.isEmpty()) {
            modules.halted?.let { halt ->
                return Reply.done(narratorLine("modules · none running · the Forge is switched off by ${halt.by} at ${whenOf(halt, text)}; /narrator forge on lifts it"))
            }
            unavailable(services)?.let { return Reply.refused("$it: no module runs.") }
            return Reply.done(narratorLine("modules · none running"))
        }
        val lines = views.map { view ->
            Out(
                "${view.line} · v${view.version} · ${view.acts} ${TraceText.plural(view.acts, "act")}, ${view.faults} ${TraceText.plural(view.faults, "fault")} · " +
                    String.format(Locale.ROOT, "%.1f ms/s", view.msPerSecond),
            )
        } + Out("Stop one: /narrator module stop <id> [note] · all: /narrator forge off [reason]")
        val max = services.settings.get().config.forge.maxActive
        return Reply(true, text.page(Report("modules · ${views.size} running ($max at most)", lines), page, "narrator module list"))
    }

    /**
     * `module stop <id> [note]`: ends the running module [id] now, as [operator] (its creatures back to normal, its canon
     * retired or cancelled with [note], French, third person, or [STOP_NOTE]); the Director is told. The note is checked
     * here, before the module is stopped. Off the server thread.
     */
    suspend fun stopModule(services: NarratorServices, operator: String, id: String, note: String?): Reply {
        val modules = services.mechanics.modules
        val running = modules.list()
        if (running.isEmpty()) unavailable(services)?.let { return Reply.refused("$it: no module runs.") }
        val ref = Ref.canonOf(id) ?: return Reply.refused("$id is not a module's reference (C-70, for instance).")
        val view = running.firstOrNull { it.ref == ref }
            ?: return Reply.refused("$ref is not a running module; running: ${running.joinToString { it.ref.toString() }.ifEmpty { "none" }}.")
        val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: STOP_NOTE
        val names = services.players.online.map { it.ref.name }
        VoiceGuard.check(text, "note", MemoryLimits.RESOLUTION, names, boundQuotes = false)?.let { return Reply.refused("refused: $it") }
        val ended = try {
            modules.stop(ref, text, Author(Source.OPERATOR, operator = operator))
        } catch (e: MechanicRejected) {
            return refusedBy(e)
        }
        record(services, MODULE_STOP, operator, "$ref ${view.module} stopped: « $text »", buildJsonObject { put("ref", ref.toString()) })
        return Reply.done(narratorLine("$ref ${view.module} stopped by $operator: its creatures go back to normal."), detailLine(ended.line))
    }

    private fun text(services: NarratorServices) = TraceText(services.clock.zone, services.clock.millis())

    // ── Suggestions ─────────────────────────────────────────────────────────────────────────────────

    /** The running Narrator of the command's server, if any. */
    private fun ownNarrator(context: CommandContext<CommandSourceStack>): NarratorRuntime? = NarratorRuntime.current?.takeIf { it.server === context.source.server }

    private val NAMES = SuggestionProvider<CommandSourceStack> { context, builder ->
        val names = ownNarrator(context)?.forge?.takeIf { it.available }?.arsenal?.list().orEmpty().map { it.name }.distinct()
        SharedSuggestionProvider.suggest(names, builder)
    }

    private val PENDING = SuggestionProvider<CommandSourceStack> { context, builder ->
        val pending = ownNarrator(context)?.forge?.takeIf { it.available }?.arsenal?.list().orEmpty().filter { it.status == ModuleStatus.PENDING }.map { it.name }.distinct()
        SharedSuggestionProvider.suggest(pending, builder)
    }

    private val RUNNING = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(ownNarrator(context)?.mechanics?.modules?.list().orEmpty().map { it.ref.toString() }, builder)
    }
}
