package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.ConfigException
import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.difficulty.Difficulty
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * `/narrator pause`, `resume`, `say <text>`, `whisper <instruction>`, `chapter close` and
 * `chaos [0-5]` (M1_M2 §14.5): the operators' hold on the Director. Each command that changes something
 * is recorded as an `operator` decision (who, what), so that a trace always shows the hand of an
 * operator; `/narrator say` is also journaled as a Narrator line of the `operator` channel.
 */
object DirectorCommands {
    private val log = Log.command

    /** Adds this module's subcommands under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(Commands.literal("pause").executes { run(it.source) { runtime -> pause(runtime, it.source.textName) } })
        root.then(Commands.literal("resume").executes { run(it.source) { runtime -> resume(runtime, it.source.textName) } })
        root.then(
            Commands.literal("say").then(
                Commands.argument("text", StringArgumentType.greedyString()).executes { say(it.source, StringArgumentType.getString(it, "text")) },
            ),
        )
        root.then(
            Commands.literal("whisper").then(
                Commands.argument("instruction", StringArgumentType.greedyString())
                    .executes { run(it.source) { runtime -> whisper(runtime, it.source.textName, StringArgumentType.getString(it, "instruction")) } },
            ),
        )
        root.then(Commands.literal("chapter").then(Commands.literal("close").executes { run(it.source) { runtime -> closeChapter(runtime, it.source.textName) } }))
        root.then(
            Commands.literal("chaos")
                .executes { showChaos(it.source) }
                .then(
                    Commands.argument("level", IntegerArgumentType.integer(Caps.MIN_CHAOS, Caps.MAX_CHAOS))
                        .executes { setChaos(it.source, IntegerArgumentType.getInteger(it, "level")) },
                ),
        )
    }

    /**
     * What a command answers: [lines] to its sender, in red when it did nothing ([done] false); the
     * first line of a command that did something also goes to the other operators.
     */
    class Reply(val done: Boolean, val lines: List<Component>) {
        companion object {
            fun done(vararg lines: Component) = Reply(true, lines.toList())

            fun refused(text: String) = Reply(false, listOf(errorLine(text)))
        }
    }

    /** Runs [command] on the running Narrator and sends its [Reply] to [source]. */
    private fun run(source: CommandSourceStack, command: (NarratorRuntime) -> Reply): Int {
        val runtime = runningNarrator(source) ?: return 0
        val reply = command(runtime)
        reply.lines.forEachIndexed { i, line ->
            if (reply.done) source.sendSuccess({ line }, i == 0) else source.sendFailure(line)
        }
        return if (reply.done) 1 else 0
    }

    /**
     * The running turn ends first; then the Director keeps only what someone waits for and says nothing.
     * Only the Director pauses (M4 §24.3, decision K): the laws, the challenges and the traps run on, and the
     * answer says how to stop them. Refused while the Director is disabled or stopping: it is silent already,
     * and a pause that would wait there for a key would surprise whoever adds one.
     */
    internal fun pause(services: NarratorServices, operator: String): Reply {
        val before = services.director.status()
        cannotHear(before)?.let { return Reply.refused("the Director is $it: nothing to pause.") }
        if (before.state == DirectorState.PAUSED) return Reply.done(narratorLine("the Director is already paused; /narrator resume wakes it."), detailLine(STILL_RUNNING))
        services.director.post(DirectorSignal.Pause)
        record(services, "paused", operator, "the Director stays silent until /narrator resume")
        return Reply.done(narratorLine("paused: the Director ends its current turn, if any, then stays silent until /narrator resume."), detailLine(STILL_RUNNING))
    }

    /** The Director wakes up with what waited, and tells the model how long it was silent. */
    internal fun resume(services: NarratorServices, operator: String): Reply {
        val before = services.director.status()
        cannotHear(before)?.let { return Reply.refused("the Director is $it: nothing to resume.") }
        services.director.post(DirectorSignal.Resume)
        record(services, "resumed", operator, "the Director was ${before.state.name.lowercase()}")
        if (before.state == DirectorState.PAUSED) return Reply.done(narratorLine("resumed: the Director wakes up with what waited."))
        return Reply.done(narratorLine("the Director was not paused (${before.state.name.lowercase()}): nothing changes."))
    }

    /** `disabled (no LLMGW key)` when the Director cannot take a signal; null when it can. */
    private fun cannotHear(status: DirectorStatus): String? = when (status.state) {
        DirectorState.DISABLED, DirectorState.STOPPED -> status.state.name.lowercase() + status.reason?.let { " ($it)" }.orEmpty()
        else -> null
    }

    /** The Narrator says [text] to everyone, as a line of the operator ([NarratorChannel.OPERATOR]). */
    private fun say(source: CommandSourceStack, text: String): Int {
        val runtime = runningNarrator(source) ?: return 0
        val line = Speech.clean(text, Speech.CHAT_LINES)
        if (line.isEmpty()) {
            source.sendFailure(errorLine("nothing to say once formatting codes and invisible characters are removed."))
            return 0
        }
        val operator = source.textName
        val heard = Speech.say(runtime.server, runtime.bus, runtime.settings.get().ui, line, to = null, NarratorChannel.OPERATOR, turnId = null, operator = operator)
        record(
            runtime, "said", operator, TraceText.quote(line, REASON_CHARS),
            detail = buildJsonObject {
                put("text", line)
                put("heard_by", heard.size)
            },
        )
        source.sendSuccess({ detailLine("Said as the Narrator to ${heard.size} ${TraceText.plural(heard.size, "player")} online; the Director sees it as said through you.") }, false)
        return 1
    }

    /**
     * An instruction to the Director: a `system` message (or an `<operator>` tag) with its next request.
     * It wakes the Director; while paused, blocked or backing off, it waits in the inbox. Refused while the
     * Director is disabled or stopping: nobody would read it.
     */
    internal fun whisper(services: NarratorServices, operator: String, text: String): Reply {
        val instruction = text.trim()
        val status = services.director.status()
        cannotHear(status)?.let { return Reply.refused("the Director is $it: nobody would read this instruction.") }
        services.director.post(DirectorSignal.OperatorWhisper(operator, instruction, services.clock.millis()))
        record(services, "whispered", operator, TraceText.quote(instruction, REASON_CHARS), detail = buildJsonObject { put("text", instruction) })
        val reply = when (status.state) {
            DirectorState.RUNNING -> "sent: the Director reads it with the next request of its running turn."
            DirectorState.PAUSED, DirectorState.BLOCKED, DirectorState.BACKOFF ->
                "queued: the Director is ${status.state.name.lowercase()}${status.reason?.let { " ($it)" }.orEmpty()} and reads it when it wakes up."
            else -> "sent: the Director wakes up to read it."
        }
        return Reply.done(narratorLine(reply))
    }

    /** The open chapter closes at the next quiet moment; the next wake-up opens a new one. */
    internal fun closeChapter(services: NarratorServices, operator: String): Reply {
        val chapter = services.director.status().chapter ?: return Reply.refused("no chapter is open: the next wake-up opens one anyway.")
        services.director.post(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR))
        record(services, "chapter_close", operator, "chapter $chapter closes at the next quiet moment")
        return Reply.done(narratorLine("chapter $chapter closes at the next quiet moment; the next wake-up opens a new one."))
    }

    /** The chaos level in force and what it allows. */
    private fun showChaos(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        chaosLines(runtime.settings.get().config.chaos).forEach { line -> source.sendSuccess({ line }, false) }
        source.sendSuccess({ detailLine("/narrator chaos <0-5> writes the level to ${ConfigStore.FILE_NAME}, then reloads.") }, false)
        return 1
    }

    /**
     * Writes [level] to the `chaos` setting of `narrator2.json` (the rest of the file as it was), then
     * reloads the settings like `/narrator reload`: the level survives a restart, and the configuration
     * file stays the one place that says it. Off the server thread; one reload at a time.
     */
    private fun setChaos(source: CommandSourceStack, level: Int): Int {
        val runtime = runningNarrator(source) ?: return 0
        if (!AdminCommands.reloadInFlight.compareAndSet(false, true)) {
            source.sendFailure(errorLine("a reload is already running."))
            return 0
        }
        val operator = source.textName
        val before = runtime.settings.get().config.chaos
        log.info("chaos $level asked by $operator")
        val job = replyLater(source, runtime, "chaos", broadcast = true) {
            try {
                writeChaos(NarratorRuntime.configFile, level)
            } catch (e: Exception) {
                return@replyLater listOf(errorLine("cannot write ${ConfigStore.FILE_NAME}: ${e.message}. The chaos stays at $before."))
            }
            val next = try {
                runtime.reload()
            } catch (e: Exception) {
                log.error("reload after the chaos change failed, the settings in force stay", e)
                return@replyLater listOf(errorLine("chaos $level written to ${ConfigStore.FILE_NAME}, but the reload failed: ${e.message}. The chaos stays at $before until a reload succeeds."))
            }
            // Like `/narrator reload`, the difficulty read again is in force (M7-RELOAD).
            runtime.server.onServerThread { Difficulty.reload(runtime.server, next.config.difficulty) }
            record(runtime, "chaos", operator, "chaos $before → ${next.config.chaos}", detail = buildJsonObject {
                put("before", before)
                put("after", next.config.chaos)
            })
            listOf(narratorLine("chaos $before → ${next.config.chaos}, written to ${ConfigStore.FILE_NAME} and in force.")) + chaosLines(next.config.chaos).drop(1)
        }
        job.invokeOnCompletion { AdminCommands.reloadInFlight.set(false) }
        return 1
    }

    /** `[Narrator] chaos 3 of 5`, then what the engine allows at [level] (its caps). */
    internal fun chaosLines(level: Int) = buildList {
        val caps = Caps.of(level)
        add(narratorLine("chaos $level of ${Caps.MAX_CHAOS}"))
        if (!caps.hostileAllowed) {
            add(detailLine("No hostile action at all: no harmful effect, no punishment, no death caused by the Narrator."))
            return@buildList
        }
        add(
            detailLine(
                "Harmful effects: up to ${caps.harmfulSeconds} s, level ${caps.harmfulLevel} · creatures: ${caps.spawnsPerAction} per action, " +
                    "${caps.livingMobs} alive · explosions: power ${number(caps.explosionPower)} · lightning: ${number(caps.lightningHearts)} " +
                    if (caps.lightningHearts == 1.0) "heart" else "hearts",
            ),
        )
        add(
            detailLine(
                "Launch: strength ${number(caps.launchStrength)} · teleport: ${caps.teleportBlocks} blocks · confiscation: ${caps.confiscatedItems} items · " +
                    "deaths caused: ${caps.narratorKillsPerHour} per played hour",
            ),
        )
    }

    /** `3`, `1.5`. */
    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    /**
     * Sets `chaos` to [level] in the configuration [file], keeping every other setting as the operator
     * wrote it; written through a temporary file, so a crash never leaves half a file. A missing file gets
     * `{"chaos": level}`: the loader fills in the defaults.
     */
    internal fun writeChaos(file: Path, level: Int) {
        val text = if (Files.exists(file)) Files.readString(file) else "{}"
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, ConfigStore.FILE_NAME, ".tmp")
        try {
            Files.writeString(temp, withChaos(text, level))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** The configuration [text] with `chaos` set to [level], the other settings kept in their order. */
    internal fun withChaos(text: String, level: Int): String {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ConfigException("${ConfigStore.FILE_NAME} is not valid JSON; fix it by hand")
        }
        if (root !is JsonObject) throw ConfigException("${ConfigStore.FILE_NAME} must be a JSON object")
        val updated = JsonObject(root.toMutableMap().apply { put(CHAOS, JsonPrimitive(level)) })
        return PRETTY.encodeToString(JsonElement.serializer(), updated) + "\n"
    }

    /** An `operator` decision: [operator] did [decision]. */
    private fun record(services: NarratorServices, decision: String, operator: String, reason: String, detail: JsonObject? = null) {
        Decisions(services.trace, services.clock).record(log, DecisionRecord.Kind.OPERATOR, decision, subject = operator, reason = reason, detail = detail)
    }

    private const val CHAOS = "chaos"
    private const val REASON_CHARS = 200

    /** What a pause leaves running (decision K), and how to stop it. */
    private const val STILL_RUNNING =
        "The laws, challenges and traps run on: /narrator rule remove, /narrator challenge cancel and /narrator traps restore stop them."

    private val PRETTY = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
