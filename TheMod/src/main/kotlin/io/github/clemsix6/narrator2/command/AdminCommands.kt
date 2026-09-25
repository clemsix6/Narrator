package io.github.clemsix6.narrator2.command

import com.anthropic.models.messages.TextBlockParam
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.BudgetExceededException
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.runtime.SettingsLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import java.util.concurrent.atomic.AtomicBoolean

/** `/narrator ping`, `status` and `reload` (M1_M2 §14.5). */
object AdminCommands {
    private val log = Log.command

    private const val DEFAULT_PING = "Ping! Can you hear me?"

    /** One ping at a time: a second one while the first waits for Claude is refused. */
    private val pingInFlight = AtomicBoolean(false)

    /** One reload at a time. */
    private val reloadInFlight = AtomicBoolean(false)

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

    /** Phase, settings, gateway, key presence (never its value), Journal, Director, folders, roles. */
    private fun status(source: CommandSourceStack): Int {
        val runtime = NarratorRuntime.current
        if (runtime == null) {
            source.sendFailure(errorLine("the Narrator is not running, see the server log."))
            return 0
        }
        val settings = runtime.settings.get()
        val config = settings.config
        val key = if (settings.claude != null) "present" else "missing"
        val health = runtime.journal.health()
        val director = runtime.director.status()
        val lines = buildList {
            add(narratorLine("status"))
            add(detailLine("Phase: ${runtime.phase.name.lowercase()} · settings: ${settings.loadError?.let { "defaults ($it)" } ?: "loaded"}"))
            add(detailLine("Gateway: ${config.gateway.baseUrl} · cache marker ${config.gateway.cacheTtl} · ${config.gateway.maxRetries} retries"))
            add(detailLine("Key: $key (${SettingsLoader.keySource(config)})"))
            add(
                detailLine(
                    if (health.available) {
                        "Journal: available · ${health.queued} queued · ${health.droppedEvents} lost"
                    } else {
                        "Journal: unavailable (${health.lastError})"
                    },
                ),
            )
            add(detailLine("Director: ${director.state.name.lowercase()}${director.reason?.let { " ($it)" } ?: ""}"))
            add(detailLine("Config: ${NarratorRuntime.configDir}"))
            add(detailLine("Data: ${runtime.dataDir}"))
            add(detailLine("Transcripts (${if (runtime.transcripts.enabled) "on" else "off"}): ${runtime.transcripts.root}"))
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
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

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
