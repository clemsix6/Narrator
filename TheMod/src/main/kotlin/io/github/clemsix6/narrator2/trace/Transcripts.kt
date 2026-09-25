package io.github.clemsix6.narrator2.trace

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Full, append-only transcripts of every conversation with Claude, for debugging. For each
 * conversation, `<root>/<date>/<conversation-id>.jsonl` holds the exact API payloads (enough to replay
 * every request: system prompt and tools, each message, then each request's parameters), and the
 * `.md` next to it reads like a script: what the model received, its thinking summary, each tool call
 * with its arguments and result, tokens and latency. Timestamps carry the server's time zone.
 *
 * Tracing never breaks a call: a transcript that cannot be written is logged and skipped. The LLMGW
 * key is never written: it is not part of a conversation or of a request's parameters.
 */
class Transcripts(val root: Path, val enabled: Boolean) {
    private val mapper = jsonMapper()
    private val log = Log.trace

    private class State(val jsonl: Path, val markdown: Path)

    private val states = ConcurrentHashMap<String, State>()

    /** A message appended to [conversation] by its owner (Claude's replies are written by [response]). */
    fun message(conversation: Conversation, message: MessageParam) = write(conversation) { state ->
        val node = mapper.valueToTree<JsonNode>(message)
        appendJsonl(state, "message", node)
        appendMarkdown(state, renderMessage(node))
    }

    /**
     * The parameters of a request about to be sent: everything but the system prompt, the tools and
     * the messages, already in the transcript. [messageCount] says how many messages it carries.
     */
    fun request(conversation: Conversation, params: MessageCreateParams, messageCount: Int) = write(conversation) { state ->
        val body = mapper.valueToTree<ObjectNode>(params._body())
        body.remove(listOf("messages", "system", "tools"))
        body.put("stream", true)
        val headers = body.putObject("headers")
        val additional = params._additionalHeaders()
        additional.names().forEach { name -> headers.put(name, additional.values(name).joinToString(", ")) }
        body.put("message_count", messageCount)
        appendJsonl(state, "request", body)
        val effort = body.path("output_config").path("effort").asText("?")
        appendMarkdown(
            state,
            "\n### ${time()} · REQUEST — `${body.path("model").asText()}` · effort `$effort` · " +
                "$messageCount messages · max ${body.path("max_tokens").asText()} tokens\n",
        )
    }

    /** A failed attempt that will be retried. */
    fun retry(conversation: Conversation, attempt: Int, reason: String, delayMs: Long) = write(conversation) { state ->
        val node = mapper.createObjectNode().put("attempt", attempt).put("reason", reason).put("delay_ms", delayMs)
        appendJsonl(state, "retry", node)
        appendMarkdown(state, "\n> *${time()} · attempt $attempt failed ($reason), retrying in $delayMs ms*\n")
    }

    /**
     * Claude's reply with its measurements. [appended] says whether it joined the conversation: a
     * refusal or an incomplete reply does not.
     */
    fun response(conversation: Conversation, message: Message, stats: CallStats, appended: Boolean) =
        write(conversation) { state ->
            val node = mapper.valueToTree<ObjectNode>(message)
            node.set<JsonNode>("narrator2_stats", statsNode(stats))
            node.put("narrator2_appended", appended)
            appendJsonl(state, "response", node)
            appendMarkdown(state, renderResponse(node, stats, appended))
        }

    /** A failed call, after its last attempt. */
    fun error(conversation: Conversation, error: Throwable, stats: CallStats) = write(conversation) { state ->
        val node = mapper.createObjectNode()
            .put("error", error.javaClass.name)
            .put("message", error.message.orEmpty())
        node.set<JsonNode>("narrator2_stats", statsNode(stats))
        appendJsonl(state, "error", node)
        appendMarkdown(
            state,
            "\n### ${time()} · ERROR — ${stats.error} (${stats.ids()}, ${stats.durationMs} ms)\n\n`${error.javaClass.simpleName}`\n",
        )
    }

    /** An engine-side note in the transcript (e.g. why a turn was triggered). */
    fun note(conversation: Conversation, text: String) = write(conversation) { state ->
        appendJsonl(state, "note", mapper.createObjectNode().put("text", text))
        appendMarkdown(state, "\n> *${time()} · $text*\n")
    }

    /** Forgets [conversation]; a later write would start a new transcript file. */
    fun close(conversation: Conversation) {
        states.remove(conversation.id)
    }

    private inline fun write(conversation: Conversation, block: (State) -> Unit) {
        if (!enabled) return
        try {
            val state = stateOf(conversation)
            synchronized(state) { block(state) }
        } catch (e: Exception) {
            log.warn("transcript failed for ${conversation.id}", e)
        }
    }

    private fun stateOf(conversation: Conversation): State =
        states.computeIfAbsent(conversation.id) {
            val started = OffsetDateTime.now()
            val dir = root.resolve(started.toLocalDate().toString())
            Files.createDirectories(dir)
            val state = State(dir.resolve("${conversation.id}.jsonl"), dir.resolve("${conversation.id}.md"))
            appendMarkdown(state, header(conversation, started))
            val header = mapper.createObjectNode()
                .put("conversation", conversation.id)
                .put("role", conversation.role.id)
                .put("label", conversation.label)
            header.set<JsonNode>("system", mapper.valueToTree(conversation.system))
            header.set<JsonNode>("tools", mapper.valueToTree(conversation.tools))
            appendJsonl(state, "conversation", header)
            log.debug { "transcript: ${state.markdown}" }
            state
        }

    private fun header(conversation: Conversation, started: OffsetDateTime): String = buildString {
        append("# ${conversation.label}\n\n")
        append("`${conversation.id}` · role `${conversation.role.id}` · started ${started.format(STAMP)}\n\n")
        val toolNames = conversation.tools.mapNotNull { tool -> tool.tool().map { it.name() }.orElse(null) }
        if (toolNames.isNotEmpty()) append("Tools: ${toolNames.joinToString(", ") { "`$it`" }}\n\n")
        append("<details><summary>System prompt</summary>\n\n")
        conversation.system.forEach { append(it.text()).append("\n\n") }
        append("</details>\n")
    }

    private fun renderMessage(node: JsonNode): String = buildString {
        val title = when (val role = node.path("role").asText()) {
            "user" -> "RECEIVED"
            "system" -> "SYSTEM INSTRUCTION"
            else -> role.uppercase()
        }
        append("\n### ${time()} · $title\n\n")
        val content = node.path("content")
        if (content.isTextual) {
            append(content.asText()).append("\n")
            return@buildString
        }
        content.forEach { block ->
            when (block.path("type").asText()) {
                "text" -> append(block.path("text").asText()).append("\n\n")
                "tool_result" -> {
                    val error = if (block.path("is_error").asBoolean(false)) " (error)" else ""
                    append("**← result** `${block.path("tool_use_id").asText()}`$error\n\n")
                    append("```\n").append(textOf(block.path("content"))).append("\n```\n\n")
                }
                else -> append("```json\n").append(block.toPrettyString()).append("\n```\n\n")
            }
        }
    }

    private fun renderResponse(node: JsonNode, stats: CallStats, appended: Boolean): String = buildString {
        append("\n### ${time()} · CLAUDE — ${stats.summary()}\n\n")
        append("*${stats.ids()}*\n\n")
        if (!appended) {
            val why = if (stats.refusalCategory != null || stats.stopReason == "refusal") "refused" else "incomplete"
            append("**Not appended to the conversation ($why).**\n\n")
            node.path("stop_details").path("explanation").takeIf { it.isTextual }?.let {
                append("> *Refusal: ${it.asText()}*\n\n")
            }
        }
        node.path("content").forEach { block ->
            when (block.path("type").asText()) {
                "thinking" -> {
                    val summary = block.path("thinking").asText()
                    if (summary.isNotBlank()) append(summary.lines().joinToString("\n") { "> $it" }).append("\n\n")
                }
                "redacted_thinking" -> append("> *(redacted thinking)*\n\n")
                "text" -> append("*Text:* ").append(block.path("text").asText()).append("\n\n")
                "tool_use" -> {
                    append("**→ ${block.path("name").asText()}** `${block.path("id").asText()}`\n\n")
                    append("```json\n").append(block.path("input").toPrettyString()).append("\n```\n\n")
                }
                else -> append("```json\n").append(block.toPrettyString()).append("\n```\n\n")
            }
        }
    }

    /** Built by hand: the SDK's mapper only serializes annotated properties, so CallStats would come out empty. */
    private fun statsNode(stats: CallStats): ObjectNode = mapper.createObjectNode()
        .put("conversation", stats.conversationId)
        .put("role", stats.role.id)
        .put("model", stats.model)
        .put("effort", stats.effort)
        .put("started_at", stats.startedAt.toString())
        .put("attempts", stats.attempts)
        .put("duration_ms", stats.durationMs)
        .put("time_to_first_token_ms", stats.timeToFirstTokenMs)
        .put("request_id", stats.requestId)
        .put("message_id", stats.messageId)
        .put("input_tokens", stats.inputTokens)
        .put("cache_read_tokens", stats.cacheReadTokens)
        .put("cache_write_tokens", stats.cacheWriteTokens)
        .put("output_tokens", stats.outputTokens)
        .put("context_tokens", stats.contextTokens)
        .put("stop_reason", stats.stopReason)
        .put("refusal_category", stats.refusalCategory)
        .put("error", stats.error)

    private fun textOf(content: JsonNode): String = when {
        content.isTextual -> content.asText()
        content.isArray -> content.joinToString("\n") { if (it.has("text")) it.path("text").asText() else it.toString() }
        else -> content.toString()
    }

    private fun appendJsonl(state: State, kind: String, payload: JsonNode) {
        val line = mapper.createObjectNode().put("ts", OffsetDateTime.now().toString()).put("kind", kind)
        line.set<JsonNode>("data", payload)
        append(state.jsonl, mapper.writeValueAsString(line) + "\n")
    }

    private fun appendMarkdown(state: State, text: String) = append(state.markdown, text)

    private fun append(file: Path, text: String) {
        try {
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            log.warn("cannot write to $file", e)
        }
    }

    private fun time(): String = OffsetDateTime.now().format(TIME)

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
