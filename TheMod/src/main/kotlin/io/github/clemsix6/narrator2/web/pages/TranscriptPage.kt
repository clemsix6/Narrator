package io.github.clemsix6.narrator2.web.pages

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.web.ConversationRow
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.ModelTokens
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.costOf
import io.github.clemsix6.narrator2.web.link
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `/conversations/<id>` (M6 §8.4): one conversation with Claude, from the Journal (role, chapter or task, start and end,
 * requests and their cost, summary), then its transcript rendered from its `.jsonl` (`trace/Transcripts`): the system
 * prompt and the tools folded, each message received or sent, each reply with its thinking summary, tool calls and
 * measurements, the tool results, the notes and the errors, and what was **not committed** (a refusal, a cut reply, an
 * error) marked as such. A request a safety classifier stopped or handed over shows as in `/narrator trace`.
 *
 * The transcript is the file `conversations.transcript` names (the `.md`; the `.jsonl` is next to it), resolved under
 * the transcripts' folder (`world/narrator2/logs/conversations/`) and read only if it lies there, links followed
 * (M6 §8.3). It is read as a stream, [PART_BYTES] at most per page, the rest a link away (`?from=<byte>`). A transcript
 * holds what was sent to and received from Claude: never a private message, which no model ever reads.
 */
object TranscriptPage {
    private val log = Log.web

    /** The most of a transcript one page reads, in bytes (M6 §8.5). */
    const val PART_BYTES = 4 * 1024 * 1024

    /** The transcript of [conversationId]; null when there is no such conversation (404). */
    fun render(conversationId: String, request: WebRequest, context: PageContext): Page? {
        val (conversation, tokens) = context.read { connection ->
            val queries = DashboardQueries(connection)
            val conversation = queries.conversation(conversationId) ?: return@read null
            conversation to queries.conversationTokens(conversationId)
        } ?: return null
        val from = request.long("from")?.coerceAtLeast(0) ?: 0
        return Page(conversation.label) {
            header(conversation, context, context.prices.costOf(tokens).text, tokens.sumOf { it.calls })
            h2("Transcript")
            transcript(conversation, from, request, context)
        }
    }

    private fun Html.header(conversation: ConversationRow, context: PageContext, cost: String, calls: Long) = dl {
        item("Conversation") { code(conversation.conversationId) }
        item("Role", conversation.role)
        conversation.chapter?.let { chapter ->
            item("Chapter") {
                text("$chapter · ")
                a(link("turns", query = mapOf("chapter" to chapter)), "its ${conversation.turns} ${TraceText.plural(conversation.turns.toInt(), "turn")}")
            }
        }
        conversation.taskId?.let { task -> item("Task") { a(link("tasks", task), "T-$task") } }
        item("Started", context.time(conversation.startedAt))
        item("Ended") {
            val end = conversation.endedAt
            if (end == null) {
                text("open for ${TraceText.duration(context.now - conversation.startedAt.toEpochMilli())}")
            } else {
                text("${context.time(end)}, after ${TraceText.duration(end.toEpochMilli() - conversation.startedAt.toEpochMilli())}")
                conversation.endReason?.let { span(" ($it)", if (it in ConversationsPage.FAILED_ENDS) "failure" else null) }
            }
        }
        item("Requests", "$calls · $cost")
        conversation.summary?.let { summary -> item(if (conversation.role == "director") "Summary" else "Result") { folded(summary) } }
    }

    private fun Html.transcript(conversation: ConversationRow, from: Long, request: WebRequest, context: PageContext) {
        val recorded = conversation.transcript ?: return p("No transcript: transcripts were off (logging.transcripts) when it ran.", "muted")
        when (val file = locate(context.services, recorded)) {
            is Located.Outside -> {
                log.warn("the transcript of ${conversation.conversationId} lies outside the transcripts' folder: not read")
                p("failure") {
                    text("The transcript's recorded path lies outside the transcripts' folder: it is not read. Recorded: ")
                    code(recorded)
                }
            }
            is Located.Missing -> p("warning") {
                text("The transcript is not on the disk: ")
                code(recorded.removeSuffix(".md") + ".jsonl")
            }
            is Located.Found -> {
                val part = try {
                    read(file.path, from)
                } catch (e: Exception) {
                    log.warn("the transcript of ${conversation.conversationId} cannot be read: ${e.message}")
                    return p("The transcript cannot be read: ${e.javaClass.simpleName}: ${e.message}.", "failure")
                }
                p("muted") {
                    code(recorded.removeSuffix(".md") + ".jsonl")
                    text(" · ${megabytes(part.size)}")
                    if (from > 0) {
                        text(" · from byte $from · ")
                        a(request.with("from" to null), "from the start")
                    }
                }
                part.entries.forEach { entry(it, context) }
                if (part.entries.isEmpty()) p("Nothing here.", "muted")
                part.next?.let { next ->
                    p("pager") {
                        text("The transcript goes on (${megabytes(part.size - next)} more): ")
                        a(request.with("from" to next), "next part →")
                    }
                }
            }
        }
    }

    // ── Where the transcript is ─────────────────────────────────────────────────────────────────────

    /** Where a transcript is: found, missing, or recorded outside the transcripts' folder. */
    internal sealed interface Located {
        data class Found(val path: Path) : Located

        data object Missing : Located

        data object Outside : Located
    }

    /**
     * The `.jsonl` of the transcript recorded as [recorded] (its `.md`, relative to the data folder or absolute): read
     * only when it lies under the transcripts' folder, once normalized and once its links are followed.
     */
    internal fun locate(services: NarratorServices, recorded: String): Located {
        val root = services.transcripts.root.toAbsolutePath().normalize()
        val markdown = try {
            services.dataDir.resolve(recorded).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return Located.Outside
        }
        val name = markdown.fileName?.toString().orEmpty()
        if (!markdown.startsWith(root) || markdown == root || !name.endsWith(".md")) return Located.Outside
        val jsonl = markdown.resolveSibling(name.removeSuffix(".md") + ".jsonl")
        if (!Files.isRegularFile(jsonl)) return Located.Missing
        val real = try {
            jsonl.toRealPath() to root.toRealPath()
        } catch (_: IOException) {
            return Located.Missing
        }
        return if (real.first.startsWith(real.second)) Located.Found(real.first) else Located.Outside
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    /** A part of a transcript: its [entries], the file's [size], and where the next part starts ([next]; null at the end). */
    internal class Part(val entries: List<Entry>, val size: Long, val next: Long?)

    /**
     * The part of [file] that starts at byte [from] (the start of the line it falls in, or the next one), [budget]
     * bytes at most: whole lines only, one at least; a line longer than [budget] is skipped and said. A last line without
     * its end is being written: left for later.
     */
    internal fun read(file: Path, from: Long, budget: Int = PART_BYTES): Part = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        if (from >= size) return Part(emptyList(), size, null)
        var offset = from
        if (from > 0 && !startsLine(channel, from)) {
            channel.position(from)
            val input = Channels.newInputStream(channel).buffered()
            var b = input.read()
            while (b != -1 && b != '\n'.code) {
                offset++
                b = input.read()
            }
            offset++
        }
        if (offset >= size) return Part(emptyList(), size, null)
        channel.position(offset)
        val input = BufferedInputStream(Channels.newInputStream(channel), BUFFER_BYTES)
        val entries = mutableListOf<Entry>()
        var used = 0L
        while (true) {
            val start = offset
            val line = ByteArrayOutputStream()
            var length = 0L
            var b = input.read()
            if (b == -1) break
            while (b != -1 && b != '\n'.code) {
                if (length < budget) line.write(b)
                length++
                b = input.read()
            }
            val consumed = length + if (b == -1) 0 else 1
            if (used + consumed > budget && entries.isNotEmpty()) return Part(entries.withCommits(), size, start)
            offset += consumed
            used += consumed
            when {
                b == -1 -> break
                length > budget -> entries += Entry.Unreadable(start, "a line of ${megabytes(length)}, more than a page holds")
                length > 0 -> entries += parse(line.toString(StandardCharsets.UTF_8), start)
            }
        }
        Part(entries.withCommits(), size, null)
    }

    private fun startsLine(channel: FileChannel, at: Long): Boolean {
        val previous = ByteBuffer.allocate(1)
        return channel.read(previous, at - 1) == 1 && previous.get(0) == '\n'.code.toByte()
    }

    /**
     * The messages sent with a request, marked with whether its reply was committed: the response that follows the
     * request says so, an error or a cancellation (a note) says they were not. Unknown (null) when the request has no
     * outcome in this part.
     */
    private fun List<Entry>.withCommits(): List<Entry> {
        val marked = toMutableList()
        val pending = mutableListOf<Int>()
        var requested = false
        fun settle(committed: Boolean?) {
            pending.forEach { i -> marked[i] = (marked[i] as Entry.Message).copy(committed = committed) }
            pending.clear()
            requested = false
        }
        forEachIndexed { i, entry ->
            when (entry) {
                is Entry.Message -> if (entry.sent) {
                    if (requested) settle(null)
                    pending += i
                }
                is Entry.Request -> {
                    if (requested) settle(null)
                    requested = true
                }
                is Entry.Response -> settle(entry.notCommitted == null)
                is Entry.Failure -> settle(false)
                is Entry.Note -> if (NOT_COMMITTED in entry.text) settle(false)
                else -> Unit
            }
        }
        return marked
    }

    /** One line of the `.jsonl` (`{"ts", "kind", "data"}`) as an entry; a line that cannot be read, said as such. */
    private fun parse(line: String, offset: Long): Entry = try {
        val node = MAPPER.readTree(line)
        val ts = time(node.path("ts").asText(""))
        val data = node.path("data")
        when (val kind = node.path("kind").asText()) {
            "conversation" -> Entry.Header(
                system = data.path("system").map { it.path("text").asText("") },
                tools = data.path("tools").map { tool ->
                    Entry.Tool(tool.path("name").asText("?"), tool.path("description").textOrNull(), tool.path("input_schema").takeUnless { it.isMissingNode }?.toPrettyString())
                },
            )
            "message", "sent" -> Entry.Message(
                ts = ts,
                kind = when {
                    data.path("role").asText() == "system" -> Entry.Kind.OPERATOR
                    kind == "sent" -> Entry.Kind.SENT
                    else -> Entry.Kind.RECEIVED
                },
                sent = kind == "sent",
                blocks = blocks(data.path("content")),
                committed = null,
            )
            "request" -> Entry.Request(
                ts, data.path("model").asText("?"), data.path("output_config").path("effort").textOrNull(),
                data.path("message_count").takeIf { it.isNumber }?.asInt(), data.path("max_tokens").takeIf { it.isNumber }?.asInt(),
            )
            "retry" -> Entry.Retry(ts, data.path("attempt").asInt(), data.path("reason").asText(""), data.path("delay_ms").asLong())
            "response" -> Entry.Response(
                ts = ts,
                stats = stats(data.path("narrator2_stats")),
                notCommitted = data.path("narrator2_not_committed").textOrNull() ?: if (data.path("narrator2_appended").asBoolean(true)) null else "not committed",
                refusal = data.path("stop_details").path("explanation").textOrNull(),
                blocks = blocks(data.path("content")),
            )
            "error" -> Entry.Failure(ts, data.path("error").asText("?").substringAfterLast('.'), data.path("message").asText(""), stats(data.path("narrator2_stats")))
            "note" -> Entry.Note(ts, data.path("text").asText(""))
            else -> Entry.Unreadable(offset, "an entry of an unknown kind ${kind.take(40)}")
        }
    } catch (e: Exception) {
        Entry.Unreadable(offset, "${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }

    /** A message's or a reply's content: a text, or a list of blocks. */
    private fun blocks(content: JsonNode): List<Block> = when {
        content.isTextual -> listOf(Block.Text(content.asText()))
        content.isArray -> content.map(::block)
        content.isMissingNode || content.isNull -> emptyList()
        else -> listOf(Block.Raw("?", content.toPrettyString()))
    }

    private fun block(block: JsonNode): Block = when (val type = block.path("type").asText()) {
        "text" -> Block.Text(block.path("text").asText(""))
        "thinking" -> Block.Thinking(block.path("thinking").asText(""))
        "redacted_thinking" -> Block.Redacted
        "tool_use" -> Block.ToolUse(block.path("name").asText("?"), block.path("id").asText(""), block.path("input").toPrettyString())
        "tool_result" -> Block.ToolResult(block.path("tool_use_id").asText(""), block.path("is_error").asBoolean(false), resultText(block.path("content")))
        "fallback" -> Block.Fallback(
            from = block.path("from").path("model").asText("?"),
            to = block.path("to").path("model").asText("?"),
            trigger = listOfNotNull(block.path("trigger").path("type").textOrNull(), block.path("trigger").path("category").textOrNull()).joinToString(", "),
        )
        else -> Block.Raw(type.ifEmpty { "?" }, block.toPrettyString())
    }

    private fun resultText(content: JsonNode): String = when {
        content.isTextual -> content.asText()
        content.isArray -> content.joinToString("\n") { if (it.has("text")) it.path("text").asText() else it.toPrettyString() }
        content.isMissingNode || content.isNull -> ""
        else -> content.toPrettyString()
    }

    private fun stats(node: JsonNode): Stats? = node.takeIf { it.isObject }?.let {
        Stats(
            model = it.path("model").asText("?"),
            durationMs = it.path("duration_ms").asLong(),
            attempts = it.path("attempts").asInt(1),
            input = it.path("input_tokens").asLong(),
            cacheRead = it.path("cache_read_tokens").asLong(),
            cacheWrite = it.path("cache_write_tokens").asLong(),
            output = it.path("output_tokens").asLong(),
            stopReason = it.path("stop_reason").textOrNull(),
            refusalCategory = it.path("refusal_category").textOrNull(),
            error = it.path("error").textOrNull(),
        )
    }

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual }?.asText()

    /** `21:30:12` from the entry's zoned stamp, as the `.md` writes it; the stamp itself when it cannot be read. */
    private fun time(stamp: String): String = runCatching { OffsetDateTime.parse(stamp).format(TIME) }.getOrDefault(stamp)

    // ── Rendering ───────────────────────────────────────────────────────────────────────────────────

    private fun Html.entry(entry: Entry, context: PageContext) {
        when (entry) {
            is Entry.Header -> {
                details("System prompt · ${entry.system.sumOf { it.length }} characters") { pre(entry.system.joinToString("\n\n")) }
                if (entry.tools.isNotEmpty()) {
                    details("Tools · ${entry.tools.size}: ${entry.tools.joinToString(", ") { it.name }}") {
                        entry.tools.forEach { tool ->
                            h3(tool.name)
                            tool.description?.let { p(it) }
                            tool.schema?.let { details("input schema") { pre(it) } }
                        }
                    }
                }
            }
            is Entry.Message -> div("card") {
                h3("${entry.ts} · ${entry.kind.title}")
                if (entry.committed == false) p("Not committed: the reply to this request was not kept, and this message did not join the conversation.", "failure")
                entry.blocks.forEach { block(it) }
            }
            is Entry.Request -> p("muted") {
                text("${entry.ts} · request to ${entry.model}" + listOfNotNull(entry.effort?.let { "effort $it" }, entry.messages?.let { "$it messages" }, entry.maxTokens?.let { "max $it tokens" }).joinToString("") { " · $it" })
            }
            is Entry.Retry -> p("${entry.ts} · attempt ${entry.attempt} failed (${entry.reason}), retried in ${entry.delayMs} ms", "warning")
            is Entry.Response -> div("card") {
                val stats = entry.stats
                h3("${entry.ts} · CLAUDE" + (stats?.let { " · ${it.model}" } ?: ""))
                val safety = TraceText.safety(stats?.stopReason, stats?.refusalCategory, stats?.error, stats?.model ?: "?", ended = entry.notCommitted == REFUSED)
                p(if (entry.notCommitted != null) "failure" else safety.look.cls) {
                    val parts = buildList {
                        stats?.let {
                            add(TraceText.seconds(it.durationMs) + if (it.attempts > 1) " (${it.attempts} attempts)" else "")
                            add("${TraceText.tokens(it.input + it.cacheRead + it.cacheWrite)} in (${TraceText.tokens(it.cacheRead)} cached, ${TraceText.tokens(it.cacheWrite)} written)")
                            add("${TraceText.tokens(it.output)} out")
                            it.stopReason?.let(::add)
                        }
                        safety.note?.let(::add)
                        stats?.let { add(context.prices.costOf(listOf(it.tokens())).text) }
                        add(entry.notCommitted?.let { "NOT COMMITTED ($it)" } ?: "committed")
                    }
                    text(parts.joinToString(" · "))
                }
                entry.refusal?.let { p("Refusal: $it", "failure") }
                entry.blocks.forEach { block(it) }
            }
            is Entry.Failure -> div("card") {
                h3("${entry.ts} · ERROR")
                p("failure") {
                    text("${entry.error}: ${entry.message}")
                    entry.stats?.error?.let { text(" ($it)") }
                    text(" · NOT COMMITTED (error)")
                }
            }
            is Entry.Note -> p("muted") {
                em("${entry.ts} · note: ")
                text(entry.text)
            }
            is Entry.Unreadable -> p("The line at byte ${entry.offset} cannot be read: ${entry.why}.", "warning")
        }
    }

    private fun Html.block(block: Block) {
        when (block) {
            is Block.Text -> pre(block.text)
            is Block.Thinking -> if (block.text.isNotBlank()) details("Thought", open = true) { pre(block.text, "muted") }
            Block.Redacted -> p("(redacted thinking)", "muted")
            is Block.ToolUse -> {
                p {
                    strong("→ ${block.name}")
                    text(" ")
                    small(block.id)
                }
                pre(block.input)
            }
            is Block.ToolResult -> {
                p(if (block.error) "failure" else null) {
                    strong("← result")
                    text(" ")
                    small(block.id)
                    if (block.error) text(" (error)")
                }
                folded(block.content)
            }
            is Block.Fallback -> p("server-side fallback: ${block.from} → ${block.to}" + (if (block.trigger.isEmpty()) "" else " (${block.trigger})"), "warning")
            is Block.Raw -> details("block ${block.type}") { pre(block.json) }
        }
    }

    // ── The model ───────────────────────────────────────────────────────────────────────────────────

    /** One entry of a transcript, as `trace/Transcripts` writes them. [ts] is its time, `21:30:12`. */
    internal sealed interface Entry {
        /** The conversation's first line: its system prompt and its tools. */
        data class Header(val system: List<String>, val tools: List<Tool>) : Entry

        data class Tool(val name: String, val description: String?, val schema: String?)

        /**
         * A message appended by the engine, or [sent] with a request: then [committed] says whether its reply was kept, and
         * it with it (null when this part does not tell).
         */
        data class Message(val ts: String, val kind: Kind, val sent: Boolean, val blocks: List<Block>, val committed: Boolean?) : Entry

        data class Request(val ts: String, val model: String, val effort: String?, val messages: Int?, val maxTokens: Int?) : Entry

        data class Retry(val ts: String, val attempt: Int, val reason: String, val delayMs: Long) : Entry

        /** Claude's reply; [notCommitted] says why it was not kept (`refusal`, a cut reply), null when it was. */
        data class Response(val ts: String, val stats: Stats?, val notCommitted: String?, val refusal: String?, val blocks: List<Block>) : Entry

        /** A call that failed after its last attempt: nothing was committed. */
        data class Failure(val ts: String, val error: String, val message: String, val stats: Stats?) : Entry

        /** A note of the engine: why a turn started, a fallback, a chapter closing… */
        data class Note(val ts: String, val text: String) : Entry

        data class Unreadable(val offset: Long, val why: String) : Entry

        enum class Kind(val title: String) { RECEIVED("RECEIVED"), SENT("SENT"), OPERATOR("OPERATOR / ENGINE NOTE") }
    }

    /** One block of a message or a reply. */
    internal sealed interface Block {
        data class Text(val text: String) : Block

        data class Thinking(val text: String) : Block

        data object Redacted : Block

        data class ToolUse(val name: String, val id: String, val input: String) : Block

        data class ToolResult(val id: String, val error: Boolean, val content: String) : Block

        /** A server-side fallback: a safety classifier handed the request from [from] to [to] (CLAUDE-FALLBACK). */
        data class Fallback(val from: String, val to: String, val trigger: String) : Block

        /** A block of another type, shown as JSON. */
        data class Raw(val type: String, val json: String) : Block
    }

    /** A reply's measurements (`narrator2_stats`): [model] is the model that answered. */
    internal data class Stats(
        val model: String,
        val durationMs: Long,
        val attempts: Int,
        val input: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val output: Long,
        val stopReason: String?,
        val refusalCategory: String?,
        val error: String?,
    ) {
        fun tokens() = ModelTokens(model, 1, if (error != null) 1 else 0, input, cacheRead, cacheWrite, output)
    }

    /** Why a refused reply was not committed (`llm/ClaudeClient`): the request that ended its turn or task refused. */
    private const val REFUSED = "refusal"

    /** What a note says of a call cancelled before its reply (`llm/ClaudeClient`). */
    private const val NOT_COMMITTED = "NOT COMMITTED"

    private const val BUFFER_BYTES = 64 * 1024

    private val MAPPER = jsonMapper()

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    private fun megabytes(bytes: Long): String =
        if (bytes < 1_048_576) "${(bytes + 1_023) / 1_024} kB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
}
