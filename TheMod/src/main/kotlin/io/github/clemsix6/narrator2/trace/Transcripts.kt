package io.github.clemsix6.narrator2.trace

import com.anthropic.core.ObjectMappers
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

    fun summary(): String =
        "${latencyMs / 1000.0} s · entrée $inputTokens (+$cacheReadTokens cache lu, $cacheWriteTokens écrit) · " +
            "sortie $outputTokens · $stopReason"
}

/**
 * Full, append-only transcripts of every conversation with Claude, for debugging. For each
 * conversation, `<root>/<date>/<conversation-id>.jsonl` holds the exact API payloads (replayable),
 * and the `.md` next to it reads like a script: what the model received, its thinking summary,
 * each tool call with its arguments and result, tokens and latency.
 */
class Transcripts(private val root: Path, private val enabled: Boolean) {
    private val mapper = ObjectMappers.jsonMapper()
    private val log = Log.of("Trace")

    /** Per conversation: its files, and how many history messages are already written. */
    private class State(val jsonl: Path, val markdown: Path) {
        var written = 0
    }

    private val states = ConcurrentHashMap<String, State>()

    /** Writes the messages appended to [conversation] since the previous request. */
    fun beforeRequest(conversation: Conversation, model: String, effort: String) {
        if (!enabled) return
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
    fun afterResponse(conversation: Conversation, message: Message, stats: CallStats) {
        if (!enabled) return
        val state = states[conversation.id] ?: return
        synchronized(state) {
            val node = mapper.valueToTree<ObjectNode>(message)
            node.set<JsonNode>("narrator2_stats", mapper.valueToTree(stats))
            appendJsonl(state, "response", node)
            appendMarkdown(state, renderResponse(node, stats))
            state.written = conversation.size
        }
    }

    fun afterError(conversation: Conversation, error: Throwable, latencyMs: Long) {
        if (!enabled) return
        val state = states[conversation.id] ?: return
        synchronized(state) {
            val node = mapper.createObjectNode()
                .put("error", error.javaClass.simpleName)
                .put("message", error.message.orEmpty())
                .put("latency_ms", latencyMs)
            appendJsonl(state, "error", node)
            appendMarkdown(state, "\n### ${time()} · ERREUR\n\n`${error.javaClass.simpleName}` : ${error.message}\n")
        }
    }

    /** An engine-side note in the transcript (e.g. why a turn was triggered). */
    fun note(conversation: Conversation, text: String) {
        if (!enabled) return
        val state = states[conversation.id] ?: return
        synchronized(state) {
            appendJsonl(state, "note", mapper.createObjectNode().put("text", text))
            appendMarkdown(state, "\n> *${time()} · $text*\n")
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
            log.debug { "transcription : ${state.markdown}" }
            state
        }

    private fun header(conversation: Conversation, model: String, effort: String): String = buildString {
        append("# ${conversation.label}\n\n")
        append("`${conversation.id}` · démarrée le ${LocalDateTime.now().format(STAMP)} · `$model` · effort `$effort`\n\n")
        val toolNames = conversation.tools.mapNotNull { tool -> tool.tool().map { it.name() }.orElse(null) }
        if (toolNames.isNotEmpty()) append("Outils : ${toolNames.joinToString(", ") { "`$it`" }}\n\n")
        append("<details><summary>Prompt système</summary>\n\n")
        conversation.system.forEach { append(it.text()).append("\n\n") }
        append("</details>\n")
    }

    private fun renderMessage(node: JsonNode): String = buildString {
        val role = node.path("role").asText()
        val title = when (role) {
            "user" -> "REÇU"
            "system" -> "CONSIGNE SYSTÈME"
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
                    val error = if (block.path("is_error").asBoolean(false)) " (erreur)" else ""
                    append("**← résultat** `${block.path("tool_use_id").asText()}`$error\n\n")
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
                "redacted_thinking" -> append("> *(réflexion masquée)*\n\n")
                "text" -> append("*Note interne :* ").append(block.path("text").asText()).append("\n\n")
                "tool_use" -> {
                    append("**→ ${block.path("name").asText()}** `${block.path("id").asText()}`\n\n")
                    append("```json\n").append(block.path("input").toPrettyString()).append("\n```\n\n")
                }
                else -> append("```json\n").append(block.toPrettyString()).append("\n```\n\n")
            }
        }
    }

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
            log.warn("écriture impossible dans $file", e)
        }
    }

    private fun time(): String = LocalDateTime.now().format(TIME)

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
