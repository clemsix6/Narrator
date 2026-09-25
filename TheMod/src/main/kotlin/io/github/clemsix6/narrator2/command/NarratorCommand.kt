package io.github.clemsix6.narrator2.command

import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.describeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/** `/narrator …` administration commands, reserved to game masters (permission level 2). */
object NarratorCommand {
    private val log = Log.of("Command")

    private const val DEFAULT_PING = "Ping! Can you hear me?"

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
        val gateway = runtime.config.gateway
        if (!runtime.claude.isConfigured) {
            source.sendFailure(
                narrator(
                    "no LLMGW key: set gateway.apiKey in ${NarratorRuntime.configFile} " +
                        "or the environment variable ${gateway.apiKeyEnv}, then /narrator reload.",
                    ChatFormatting.RED,
                ),
            )
            return 0
        }

        val conversation = Conversation(
            role = NarratorConfig.DIRECTOR,
            label = "Ping from ${source.textName}",
            system = listOf(TextBlockParam.builder().text(runtime.prompts.get("ping")).build()),
        )
        conversation.appendUser(message)
        log.info("ping from ${source.textName} to ${gateway.baseUrl} (${conversation.id})")
        source.sendSuccess({ narrator("ping sent to Claude…", ChatFormatting.GRAY) }, false)

        runtime.scope.launch {
            val answer: List<Component> = try {
                val reply = runtime.claude.send(conversation)
                val stats = detail(reply.stats.summary())
                when {
                    reply.stopReason == StopReason.REFUSAL -> listOf(narrator("Claude refused to answer.", ChatFormatting.RED), stats)
                    reply.text.isEmpty() -> listOf(narrator("(empty answer)"), stats)
                    else -> listOf(narrator(reply.text), stats)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(narrator("ping failed: ${describeFailure(e)}", ChatFormatting.RED))
            }
            runtime.server.execute { answer.forEach { line -> source.sendSuccess({ line }, false) } }
        }
        return 1
    }

    /** Gateway, key presence (never its value), folders, and model and effort per role. */
    private fun status(source: CommandSourceStack): Int {
        val runtime = NarratorRuntime.current ?: return notRunning(source)
        val config = runtime.config
        val key = config.gateway.apiKeySource()?.let { "present ($it)" } ?: "missing"
        val transcripts = if (runtime.transcripts.enabled) "on" else "off"
        val lines = buildList {
            add(narrator("status"))
            add(detail("Gateway: ${config.gateway.baseUrl} · key: $key"))
            add(detail("Config: ${NarratorRuntime.configDir}"))
            add(detail("Data: ${runtime.dataDir}"))
            add(detail("Transcripts ($transcripts): ${runtime.transcripts.root}"))
            add(detail("Roles:"))
            config.roles.forEach { (name, role) ->
                add(detail("  $name: ${role.model} · effort ${role.effort} · max ${role.maxTokens} tokens"))
            }
        }
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    private fun reload(source: CommandSourceStack): Int = try {
        val runtime = NarratorRuntime.reload(source.server)
        val key = if (runtime.claude.isConfigured) "key present" else "no LLMGW key"
        source.sendSuccess({ narrator("configuration and prompts reloaded ($key).") }, true)
        1
    } catch (e: Exception) {
        log.error("reload failed", e)
        source.sendFailure(narrator("reload failed: ${e.message}. The previous configuration stays active.", ChatFormatting.RED))
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
