package io.github.clemsix6.narrator2.command

import com.anthropic.models.messages.TextBlockParam
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.BudgetExceededException
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.llm.describeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/** `/narrator …` administration commands, reserved to game masters (permission level 2). */
object NarratorCommand {
    private val log = Log.command

    private const val DEFAULT_PING = "Ping! Can you hear me?"

    /** One ping at a time: a second one while the first waits for Claude is refused. */
    private val pingInFlight = AtomicBoolean(false)

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("narrator")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("ping")
                        .executes { ping(it.source, DEFAULT_PING) }
                        .then(
                            Commands.argument("message", StringArgumentType.greedyString())
                                .executes { ping(it.source, StringArgumentType.getString(it, "message")) },
                        ),
                )
                .then(Commands.literal("status").executes { status(it.source) })
                .then(Commands.literal("reload").executes { reload(it.source) }),
        )
    }

    /**
     * Sends a one-turn conversation to Claude. The call runs in the runtime's coroutine scope, never
     * on the server thread; the answer comes back to the sender on the server thread.
     */
    private fun ping(source: CommandSourceStack, message: String): Int {
        val runtime = NarratorRuntime.current ?: return notRunning(source)
        if (!runtime.claude.isConfigured) {
            source.sendFailure(narrator("no LLMGW key: ${NarratorRuntime.missingKeyHint(runtime.config)}.", ChatFormatting.RED))
            return 0
        }
        if (!pingInFlight.compareAndSet(false, true)) {
            source.sendFailure(narrator("a ping is already waiting for Claude, try again once it answers.", ChatFormatting.RED))
            return 0
        }

        log.info("ping from ${source.textName} to ${runtime.config.gateway.baseUrl}")
        source.sendSuccess({ narrator("ping sent to Claude…", ChatFormatting.GRAY) }, false)
        val job = runtime.scope.launch {
            val answer: List<Component> = try {
                Conversation(
                    role = Role.DIRECTOR,
                    label = "Ping from ${source.textName}",
                    system = listOf(TextBlockParam.builder().text(runtime.prompts.get("ping")).build()),
                    transcripts = runtime.transcripts,
                ).use { conversation ->
                    conversation.appendUser(message)
                    val result = runtime.claude.send(conversation)
                    val stats = detail(result.stats.summary())
                    when (result) {
                        is SendResult.Refused ->
                            listOf(narrator("Claude refused to answer (${result.category ?: "no category"}).", ChatFormatting.RED), stats)
                        is SendResult.Incomplete -> listOf(narrator("incomplete answer: ${result.reason}.", ChatFormatting.RED), stats)
                        is SendResult.Reply -> listOf(narrator(result.text.ifEmpty { "(empty answer)" }), stats)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BudgetExceededException) {
                listOf(narrator("ping refused: ${e.message}.", ChatFormatting.RED))
            } catch (e: Exception) {
                listOf(narrator("ping failed: ${describeFailure(e)}", ChatFormatting.RED))
            }
            runtime.server.execute { answer.forEach { line -> source.sendSuccess({ line }, false) } }
        }
        // Also runs when the job is cancelled before it starts (the runtime was closed).
        job.invokeOnCompletion { pingInFlight.set(false) }
        return 1
    }

    /** Gateway, key presence (never its value), folders, and settings per role. */
    private fun status(source: CommandSourceStack): Int {
        val runtime = NarratorRuntime.current ?: return notRunning(source)
        val config = runtime.config
        val key = if (runtime.claude.isConfigured) "present" else "missing"
        val transcripts = if (runtime.transcripts.enabled) "on" else "off"
        val lines = buildList {
            add(narrator("status"))
            add(detail("Gateway: ${config.gateway.baseUrl} · cache TTL ${config.gateway.cacheTtl} · ${config.gateway.maxRetries} retries"))
            add(detail("Key: $key (${NarratorRuntime.keySource(config)})"))
            add(detail("Config: ${NarratorRuntime.configDir}"))
            add(detail("Data: ${runtime.dataDir}"))
            add(detail("Transcripts ($transcripts): ${runtime.transcripts.root}"))
            add(detail("Roles:"))
            config.roles.forEach { (role, settings) ->
                add(
                    detail(
                        "  ${role.id}: ${settings.model} · effort ${settings.effort} · max ${settings.maxTokens} tokens · " +
                            "timeouts ${settings.readTimeoutSeconds} s read, ${settings.totalTimeoutSeconds} s total",
                    ),
                )
            }
        }
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    private fun reload(source: CommandSourceStack): Int = try {
        val wasRunning = NarratorRuntime.current != null
        val runtime = NarratorRuntime.reload(source.server)
        val key = if (runtime.claude.isConfigured) "key present" else "no LLMGW key"
        val done = if (wasRunning) "configuration and prompts reloaded" else "the Narrator started"
        source.sendSuccess({ narrator("$done ($key).") }, true)
        1
    } catch (e: Exception) {
        log.error("reload failed", e)
        val kept = if (NarratorRuntime.current != null) "The previous configuration stays active." else "The Narrator stays off."
        source.sendFailure(narrator("reload failed: ${e.message}. $kept", ChatFormatting.RED))
        0
    }

    private fun notRunning(source: CommandSourceStack): Int {
        source.sendFailure(narrator("the Narrator is not running, see the server log.", ChatFormatting.RED))
        return 0
    }

    /** `[Narrator] text`, with a gold prefix. */
    private fun narrator(text: String, color: ChatFormatting = ChatFormatting.WHITE): Component =
        Component.empty()
            .append(Component.literal("[Narrator] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(text).withStyle(color))

    private fun detail(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GRAY)
}
