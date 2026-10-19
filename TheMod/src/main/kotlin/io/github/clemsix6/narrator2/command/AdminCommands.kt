package io.github.clemsix6.narrator2.command

import com.anthropic.models.messages.TextBlockParam
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.journal.JournalHealth
import io.github.clemsix6.narrator2.llm.BudgetExceededException
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsLoader
import io.github.clemsix6.narrator2.trace.TraceQueries
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** `/narrator ping`, `status` and `reload` (M1_M2 §14.5). */
object AdminCommands {
    private val log = Log.command

    private const val DEFAULT_PING = "Ping! Can you hear me?"

    /** The model calls of `status` cover this many hours. */
    private const val USAGE_HOURS = 24

    private const val USAGE_BARRIER_MS = 1_000L
    private const val USAGE_READ_MS = 5_000L

    /** One ping at a time: a second one while the first waits for Claude is refused. */
    private val pingInFlight = AtomicBoolean(false)

    /** One reload at a time, whoever asks for it (`reload`, `chaos <level>`). */
    internal val reloadInFlight = AtomicBoolean(false)

    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("ping")
                .executes { ping(it.source, DEFAULT_PING) }
                .then(
                    Commands.argument("message", StringArgumentType.greedyString())
                        .executes { ping(it.source, StringArgumentType.getString(it, "message")) },
                ),
        )
        root.then(Commands.literal("status").executes { status(it.source) })
        root.then(Commands.literal("reload").executes { reload(it.source) })
    }

    /**
     * Sends a one-turn conversation to Claude with the client in force. The call runs in the runtime's
     * scope, never on the server thread; the answer comes back to the sender on the server thread. A
     * `/narrator reload` meanwhile does not cut it: the replaced client closes once the ping is over.
     */
    private fun ping(source: CommandSourceStack, message: String): Int {
        val runtime = runningNarrator(source) ?: return 0
        val settings = runtime.settings.get()
        val claude = settings.claude
        if (claude == null) {
            source.sendFailure(errorLine("no LLMGW key: ${SettingsLoader.missingKeyHint(settings.config)}."))
            return 0
        }
        if (!pingInFlight.compareAndSet(false, true)) {
            source.sendFailure(errorLine("a ping is already waiting for Claude, try again once it answers."))
            return 0
        }

        log.info("ping from ${source.textName} to ${settings.config.gateway.baseUrl}")
        source.sendSuccess({ narratorLine("ping sent to Claude…", ChatFormatting.GRAY) }, false)
        val job = replyLater(source, runtime, "ping") {
            Conversation(
                role = Role.DIRECTOR,
                label = "Ping from ${source.textName}",
                system = listOf(TextBlockParam.builder().text(settings.prompts.get("ping")).build()),
                transcripts = runtime.transcripts,
            ).use { conversation ->
                conversation.appendUser(message)
                val result = try {
                    claude.send(conversation)
                } catch (e: BudgetExceededException) {
                    return@replyLater listOf(errorLine("ping refused: ${e.message}."))
                }
                val stats = detailLine(result.stats.summary())
                when (result) {
                    is SendResult.Refused -> listOf(errorLine("Claude refused to answer (${result.category ?: "no category"})."), stats)
                    is SendResult.Incomplete -> listOf(errorLine("incomplete answer: ${result.reason}."), stats)
                    is SendResult.Reply -> listOf(narratorLine(result.text.ifEmpty { "(empty answer)" }), stats)
                }
            }
        }
        // Also runs when the job is cancelled before it starts (the server stops).
        job.invokeOnCompletion { pingInFlight.set(false) }
        return 1
    }

    /**
     * What runs and how well (M1_M2 §14.4): phase, settings, gateway, key presence (never its value),
     * Director, chapter, tasks, Journal, reflexes, guards, memory, folders, roles; then, read from the Journal
     * off the server thread, the model calls of the last 24 hours.
     */
    private fun status(source: CommandSourceStack): Int {
        val runtime = NarratorRuntime.current
        if (runtime == null) {
            source.sendFailure(errorLine("the Narrator is not running, see the server log."))
            return 0
        }
        statusLines(runtime, NarratorRuntime.configDir).forEach { line -> source.sendSuccess({ line }, false) }
        if (runtime.phase != Phase.RUNNING) return 1
        if (!runtime.journal.available) {
            source.sendSuccess({ detailLine("Usage: unknown, the Journal is unavailable") }, false)
            return 1
        }
        replyLater(source, runtime, "usage") {
            withTimeoutOrNull(USAGE_BARRIER_MS) { runtime.journal.barrier() }
            val text = TraceText(runtime.clock.zone, runtime.clock.millis())
            val usage = runtime.journal.read(USAGE_READ_MS) { TraceQueries(it).usage(text.now - USAGE_HOURS * 3_600_000L) }
            listOf(detailLine(text.usageSummary(usage, USAGE_HOURS)))
        }
        return 1
    }

    /** The lines of `/narrator status` that come from memory, in the order of [status]. Any thread. */
    internal fun statusLines(services: NarratorServices, configDir: Path): List<Component> {
        val settings = services.settings.get()
        val config = settings.config
        val key = if (settings.claude != null) "present" else "missing"
        val text = TraceText(services.clock.zone, services.clock.millis())
        val director = services.director.status()
        return buildList {
            add(narratorLine("status"))
            add(detailLine("Phase: ${services.phase.name.lowercase()} · settings: ${settings.loadError?.let { "defaults ($it)" } ?: "loaded"}"))
            add(detailLine("Gateway: ${config.gateway.baseUrl} · cache marker ${config.gateway.cacheTtl} · ${config.gateway.maxRetries} retries"))
            add(detailLine("Key: $key (${SettingsLoader.keySource(config)})"))
            add(detailLine(directorLine(director, text)))
            add(detailLine(chapterLine(director)))
            add(detailLine(tasksLine(services, text)))
            add(detailLine(journalLine(services.journal.health())))
            add(detailLine(reflexesLine(settings)))
            add(detailLine(guardsLine(services)))
            add(detailLine(MemoryCommands.statusLine(services.memory, text)))
            add(detailLine("Config: $configDir"))
            add(detailLine("Data: ${services.dataDir}"))
            add(detailLine("Transcripts (${if (services.transcripts.enabled) "on" else "off"}): ${services.transcripts.root}"))
            add(detailLine("Roles:"))
            config.roles.forEach { (role, settings) ->
                add(
                    detailLine(
                        "  ${role.id}: ${settings.model} · effort ${settings.effort} · max ${settings.maxTokens} tokens · " +
                            "timeouts ${settings.readTimeoutSeconds} s read, ${settings.totalTimeoutSeconds} s total",
                    ),
                )
            }
        }
    }

    /**
     * `Director: waiting until 21:32:04 · turn #42 running · turns this hour 12 of 30 (18 left) · inbox 3 (1 dropped)
     * · reminders R-3 21:40`.
     */
    private fun directorLine(director: DirectorStatus, text: TraceText): String = buildString {
        append("Director: ").append(director.state.name.lowercase())
        director.until?.let { append(" until ").append(text.time(Instant.ofEpochMilli(it))) }
        director.reason?.let { append(" (").append(it).append(')') }
        director.turnId?.let { append(" · turn #").append(it).append(" running") }
        append(" · ").append(turnsLine(director))
        append(" · inbox ").append(director.inbox)
        if (director.droppedSignals > 0) append(" (").append(director.droppedSignals).append(" dropped)")
        append(" · reminders ")
        append(if (director.reminders.isEmpty()) "none" else director.reminders.joinToString { "R-${it.id} ${text.time(Instant.ofEpochMilli(it.at))}" })
    }

    /**
     * The budget of turns (`director.turnsPerHour`): `turns this hour 12 of 30 (18 left)`, or, once spent,
     * `turns this hour 30 of 30 (none left: only addressed chat, prayers, whispers and due commitments wake it)`.
     */
    private fun turnsLine(director: DirectorStatus): String {
        val left = (director.turnsPerHour - director.turnsThisHour).coerceAtLeast(0)
        val rest = if (left > 0) "$left left" else "none left: only addressed chat, prayers, whispers and due commitments wake it"
        return "turns this hour ${director.turnsThisHour} of ${director.turnsPerHour} ($rest)"
    }

    /** `Chapter: #3 · context 61k tokens · operator channel system_message`. */
    private fun chapterLine(director: DirectorStatus): String {
        val chapter = director.chapter?.let { "#$it · context ${TraceText.tokens(director.contextTokens)} tokens" } ?: "none open"
        return "Chapter: $chapter · operator channel ${director.operatorChannel.id}"
    }

    /** `Tasks: T-13 analyst running 12 s, T-14 analyst queued`: the tasks not finished. */
    private fun tasksLine(services: NarratorServices, text: TraceText): String {
        val open = services.workers.list().filterNot { it.status.finished }
        val tasks = open.joinToString { task ->
            val since = task.startedAt?.let { " ${TraceText.duration(text.now - it.toEpochMilli())}" }.orEmpty()
            "T-${task.taskId} ${task.role.id} ${task.status.id}$since"
        }
        return "Tasks: ${tasks.ifEmpty { "none running" }}"
    }

    /** `Journal: available · 12.3 MB + 0.4 MB WAL · 1520 rows/min · last flush 12 ms (340 rows) · 0 queued · 0 lost`. */
    private fun journalLine(health: JournalHealth): String {
        if (!health.available) return "Journal: unavailable (${health.lastError}) · ${health.droppedEvents} lost"
        return "Journal: available · ${megabytes(health.fileBytes)} + ${megabytes(health.walBytes)} WAL · ${health.rowsPerMinute} rows/min · " +
            "last flush ${health.lastFlushMs} ms (${health.lastFlushRows} rows) · ${health.queued} queued · ${health.droppedEvents} lost"
    }

    /** `Reflexes: claude-haiku-5-5, active`, or the heuristic and why. */
    private fun reflexesLine(settings: Settings): String {
        val reflex = settings.reflex
        return when {
            reflex != null -> "Reflexes: ${reflex.model}, ${reflex.status()}"
            !settings.config.reflex.enabled -> "Reflexes: heuristic (reflex.enabled is false)"
            else -> "Reflexes: heuristic (no LLMGW key in the ${SettingsLoader.keySource(settings.config)})"
        }
    }

    /** `Guards: chaos 3 · creatures 2 of 16 · protected online: clemsix6`. */
    private fun guardsLine(services: NarratorServices): String {
        val guards = services.guards
        val caps = guards.caps()
        val protectedOnline = services.players.online.filter { guards.isProtected(it.ref.uuid) }.joinToString { it.ref.name }
        return "Guards: chaos ${services.settings.get().config.chaos} · creatures ${guards.mobs.count()} of ${caps.livingMobs} · " +
            "protected online: ${protectedOnline.ifEmpty { "nobody" }}"
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    /**
     * Loads the configuration, prompts and texts again, off the server thread, and installs them; the
     * services keep running. When loading fails, the settings in force stay.
     */
    private fun reload(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        if (!reloadInFlight.compareAndSet(false, true)) {
            source.sendFailure(errorLine("a reload is already running."))
            return 0
        }
        log.info("reload asked by ${source.textName}")
        val job = replyLater(source, runtime, "reload", broadcast = true) {
            val next = try {
                runtime.reload()
            } catch (e: Exception) {
                log.error("reload failed, the settings in force stay", e)
                return@replyLater listOf(errorLine("reload failed: ${e.message}. The previous settings stay active."))
            }
            val key = if (next.claude != null) "key present" else "no LLMGW key"
            listOf(narratorLine("configuration and prompts reloaded ($key)."))
        }
        job.invokeOnCompletion { reloadInFlight.set(false) }
        return 1
    }
}
