package io.github.clemsix6.narrator2.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Talks to Claude through LLMGW, which speaks the Anthropic Messages API: the official SDK is simply
 * pointed at the gateway with an LLMGW project key.
 *
 * Every call streams (so long replies never hit an HTTP timeout), asks for summarized thinking (logged
 * for debugging; Opus 5.5 never returns raw reasoning), sets the role's effort, enables automatic
 * prompt caching, and sends a stable `X-Session-ID` so the gateway keeps the conversation on the same
 * upstream account, where its prompt cache lives.
 */
class ClaudeClient(private val config: NarratorConfig, private val transcripts: Transcripts) : AutoCloseable {
    private val log = Log.llm

    private val apiKey: String? = config.gateway.resolveApiKey()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl(config.gateway.baseUrl)
        .apiKey(apiKey ?: "missing-llmgw-key")
        .timeout(Duration.ofSeconds(config.gateway.timeoutSeconds))
        .maxRetries(config.gateway.maxRetries)
        .build()

    /** False when no LLMGW key is configured: calls would be refused by the gateway. */
    val isConfigured: Boolean get() = apiKey != null

    /**
     * Sends [conversation] to Claude, appends the reply to it, and returns the reply.
     * Blocking network I/O runs on [Dispatchers.IO].
     */
    suspend fun send(conversation: Conversation): Reply = withContext(Dispatchers.IO) {
        val role = config.role(conversation.role)
        val params = MessageCreateParams.builder()
            .model(role.model)
            .maxTokens(role.maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(role.effort)).build())
            .systemOfTextBlockParams(conversation.system)
            .messages(conversation.messages)
            .cacheControl(CacheControlEphemeral.builder().build())
            .putAdditionalHeader("X-Session-ID", conversation.id)
            .apply { if (conversation.tools.isNotEmpty()) tools(conversation.tools) }
            .build()

        transcripts.beforeRequest(conversation, role.model, role.effort)
        val started = System.nanoTime()
        val message = try {
            val accumulator = MessageAccumulator.create()
            client.messages().createStreaming(params).use { stream ->
                stream.stream().forEach { accumulator.accumulate(it) }
            }
            accumulator.message()
        } catch (e: Exception) {
            val latency = (System.nanoTime() - started) / 1_000_000
            val description = describeFailure(e)
            transcripts.afterError(conversation, e, description, latency)
            log.warn("${conversation.id} · failed after $latency ms: $description")
            throw e
        }

        val usage = message.usage()
        val stats = CallStats(
            model = role.model,
            effort = role.effort,
            latencyMs = (System.nanoTime() - started) / 1_000_000,
            inputTokens = usage.inputTokens(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
            cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
            outputTokens = usage.outputTokens(),
            stopReason = message.stopReason().map { it.asString() }.orElse("?"),
        )
        conversation.append(message.toParam())
        conversation.contextTokens = stats.contextTokens
        transcripts.afterResponse(conversation, message, stats)
        log.info("${conversation.id} · ${role.model}/${role.effort} · ${stats.summary()}")
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            log.warn("${conversation.id} · the model refused: ${message.stopDetails().map { it.toString() }.orElse("?")}")
        }
        Reply(message, stats)
    }

    override fun close() = client.close()
}

/** A short explanation of a failed call, for logs, transcripts and operators. */
fun describeFailure(error: Throwable): String = when (error) {
    is UnauthorizedException -> "key rejected by the gateway (HTTP 401)"
    is PermissionDeniedException -> "access denied by the gateway (HTTP 403)"
    is RateLimitException -> "rate limit reached (HTTP 429)"
    is SseException -> "error in the response stream: ${error.message}"
    is AnthropicServiceException -> "HTTP ${error.statusCode()}: ${error.message}"
    is AnthropicIoException -> "gateway unreachable: ${error.message}"
    else -> "${error.javaClass.simpleName}: ${error.message}"
}

/** Claude's reply and what it cost. */
data class Reply(val message: Message, val stats: CallStats) {
    /** Concatenated visible text blocks (thinking excluded). */
    val text: String
        get() = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()

    val stopReason: StopReason? get() = message.stopReason().orElse(null)
}
