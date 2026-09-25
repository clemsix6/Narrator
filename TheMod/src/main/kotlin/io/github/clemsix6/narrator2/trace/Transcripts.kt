package io.github.clemsix6.narrator2.trace

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Measurements of one call to Claude. */
data class CallStats(
    val model: String,
    val effort: String,
    val latencyMs: Long,
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val stopReason: String = "",
) {
    /** Whole context seen by the model: uncached input plus cache reads and writes. */
    val contextTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens

    /** One line for logs and operators, e.g. `2.4 s · input 120 (+900 cache read, 60 cache write) · output 45 · end_turn`. */
    fun summary(): String =
        "%.1f s · input %d (+%d cache read, %d cache write) · output %d · %s".format(
            Locale.ROOT, latencyMs / 1000.0, inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, stopReason,
        )
}

/**
 * Full, append-only transcripts of every conversation with Claude, for debugging. For each
 * conversation, `<root>/<date>/<conversation-id>.jsonl` holds the exact API payloads (replayable),
 * and the `.md` next to it reads like a script: what the model received, its thinking summary,
 * each tool call with its arguments and result, tokens and latency.
 *
 * Tracing never breaks a call: a transcript that cannot be written is logged and skipped.
 */
class Transcripts(val root: Path, val enabled: Boolean) {
    private val mapper = jsonMapper()
    private val log = Log.of("Trace")

    /** Per conversation: its files, and how many history messages are already written. */
    private class State(val jsonl: Path, val markdown: Path) {
        var written = 0
    }

    private val states = ConcurrentHashMap<String, State>()

    /** Writes the messages appended to [conversation] since the previous request. */
    fun beforeRequest(conversation: Conversation, model: String, effort: String) = guarded(conversation) {
        val state = stateOf(conversation, model, effort)
        synchronized(state) {
            val messages = conversation.messages
            for (index in state.written until messages.size) {
                val node = mapper.valueToTree<JsonNode>(messages[index])
                appendJsonl(state, "message", node)
                appendMarkdown(state, renderMessage(node))
            }
            state.written = messages.size
        }
    }

    /** Writes Claude's reply; the caller has already appended it to [conversation]. */
    fun afterResponse(conversation: Conversation, message: Message, stats: CallStats) = guarded(conversation) {
        val state = states[conversation.id] ?: return@guarded
        synchronized(state) {
            val node = mapper.valueToTree<ObjectNode>(message)
            node.set<JsonNode>("narrator2_stats", statsNode(stats))
            appendJsonl(state, "response", node)
            appendMarkdown(state, renderResponse(node, stats))
            state.written = conversation.size
        }
    }

    /** Writes a failed call; [description] is the operator-facing summary of [error]. */
    fun afterError(conversation: Conversation, error: Throwable, description: String, latencyMs: Long) =
        guarded(conversation) {
            val state = states[conversation.id] ?: return@guarded
            synchronized(state) {
                val node = mapper.createObjectNode()
                    .put("error", error.javaClass.simpleName)
                    .put("description", description)
                    .put("message", error.message.orEmpty())
                    .put("latency_ms", latencyMs)
                appendJsonl(state, "error", node)
                appendMarkdown(
                    state,
                    "\n### ${time()} · ERROR after $latencyMs ms\n\n`${error.javaClass.simpleName}`: $description\n",
                )
            }
        }

    /** An engine-side note in the transcript (e.g. why a turn was triggered). */
    fun note(conversation: Conversation, text: String) = guarded(conversation) {
        val state = states[conversation.id] ?: return@guarded
        synchronized(state) {
            appendJsonl(state, "note", mapper.createObjectNode().put("text", text))
            appendMarkdown(state, "\n> *${time()} · $text*\n")
        }
    }

    private inline fun guarded(conversation: Conversation, block: () -> Unit) {
        if (!enabled) return
        try {
            block()
        } catch (e: Exception) {
            log.warn("transcript failed for ${conversation.id}", e)
        }
    }

    private fun stateOf(conversation: Conversation, model: String, effort: String): State =
        states.computeIfAbsent(conversation.id) {
            val dir = root.resolve(LocalDate.now().toString())
            Files.createDirectories(dir)
            val state = State(dir.resolve("${conversation.id}.jsonl"), dir.resolve("${conversation.id}.md"))
            appendMarkdown(state, header(conversation, model, effort))
            val header = mapper.createObjectNode()
                .put("conversation", conversation.id)
                .put("role", conversation.role)
                .put("label", conversation.label)
                .put("model", model)
                .put("effort", effort)
            header.set<JsonNode>("system", mapper.valueToTree(conversation.system))
            header.set<JsonNode>("tools", mapper.valueToTree(conversation.tools))
            appendJsonl(state, "conversation", header)
            log.debug { "transcript: ${state.markdown}" }
            state
        }

    private fun header(conversation: Conversation, model: String, effort: String): String = buildString {
        append("# ${conversation.label}\n\n")
        append("`${conversation.id}` · started ${LocalDateTime.now().format(STAMP)} · `$model` · effort `$effort`\n\n")
        val toolNames = conversation.tools.mapNotNull { tool -> tool.tool().map { it.name() }.orElse(null) }
        if (toolNames.isNotEmpty()) append("Tools: ${toolNames.joinToString(", ") { "`$it`" }}\n\n")
        append("<details><summary>System prompt</summary>\n\n")
        conversation.system.forEach { append(it.text()).append("\n\n") }
        append("</details>\n")
    }

    private fun renderMessage(node: JsonNode): String = buildString {
        val role = node.path("role").asText()
        val title = when (role) {
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

    private fun renderResponse(node: JsonNode, stats: CallStats): String = buildString {
        append("\n### ${time()} · CLAUDE — ${stats.summary()}\n\n")
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
        .put("model", stats.model)
        .put("effort", stats.effort)
        .put("latency_ms", stats.latencyMs)
        .put("input_tokens", stats.inputTokens)
        .put("cache_read_tokens", stats.cacheReadTokens)
        .put("cache_write_tokens", stats.cacheWriteTokens)
        .put("output_tokens", stats.outputTokens)
        .put("context_tokens", stats.contextTokens)
        .put("stop_reason", stats.stopReason)

    private fun textOf(content: JsonNode): String = when {
        content.isTextual -> content.asText()
        content.isArray -> content.joinToString("\n") { if (it.has("text")) it.path("text").asText() else it.toString() }
        else -> content.toString()
    }

    private fun appendJsonl(state: State, kind: String, payload: JsonNode) {
        val line = mapper.createObjectNode().put("ts", LocalDateTime.now().toString()).put("kind", kind)
        line.set<JsonNode>("data", payload)
        write(state.jsonl, mapper.writeValueAsString(line) + "\n")
    }

    private fun appendMarkdown(state: State, text: String) = write(state.markdown, text)

    private fun write(file: Path, text: String) {
        try {
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            log.warn("cannot write to $file", e)
        }
    }

    private fun time(): String = LocalDateTime.now().format(TIME)

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
