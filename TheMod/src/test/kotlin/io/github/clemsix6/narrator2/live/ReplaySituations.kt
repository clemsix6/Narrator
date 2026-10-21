package io.github.clemsix6.narrator2.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name

/*
 * The frozen situations of the Director's replay (M6 §4.5) and the reading of the conversation transcripts (`.jsonl`,
 * `trace/Transcripts`): a situation's opening can be the exact first message of a real chapter, read from an excerpt of
 * its transcript, and the tone judge grades the lines of a real evening read from its transcripts.
 */

/**
 * A structural check of a replayed turn (M6 §4.5), as a fixture writes it.
 *
 * - `speaks`: the turn says at least one line (`say`, `show_title`, `actionbar`).
 * - `act_before_announce`: when the turn calls one of [tools] (by default [DEFAULT_ACTS]), no line comes in or before the
 *   request of the first such call: act, read the result, then announce.
 * - `calls`: the turn calls [tool], with arguments matching [pattern] when given.
 * - `no_call`: the turn calls none of [tools] with arguments matching [pattern] (any arguments without one).
 * - `at_most_lines`: the turn says at most [max] lines.
 * - `no_line_matches`: no line matches [pattern].
 * - `any_of`: one of [of] passes; `all_of`: all of [of] pass.
 *
 * Patterns are regular expressions, case-insensitive, searched in the call's JSON arguments or in the line.
 */
@Serializable
internal data class CheckSpec(
    val name: String = "",
    val kind: String,
    val tool: String? = null,
    val tools: List<String> = emptyList(),
    val pattern: String? = null,
    val max: Int? = null,
    val of: List<CheckSpec> = emptyList(),
) {
    private val regex: Regex? by lazy { pattern?.let { Regex(it, RegexOption.IGNORE_CASE) } }

    /** What is wrong with this check, or null: an unknown kind, a missing parameter, a bad pattern. */
    fun problem(): String? = when (kind) {
        "speaks" -> null
        "act_before_announce" -> null
        "calls" -> if (tool == null) "calls needs a tool" else null
        "no_call" -> if (tools.isEmpty()) "no_call needs tools" else null
        "at_most_lines" -> if (max == null) "at_most_lines needs max" else null
        "no_line_matches" -> if (pattern == null) "no_line_matches needs a pattern" else null
        "any_of", "all_of" -> if (of.isEmpty()) "$kind needs checks" else of.firstNotNullOfOrNull { it.problem() }
        else -> "unknown kind $kind"
    } ?: pattern?.let { runCatching { Regex(it) }.exceptionOrNull()?.let { e -> "bad pattern $it: ${e.message}" } }

    /** Every tool this check names. */
    fun toolNames(): Set<String> = (listOfNotNull(tool) + tools).toSet() + of.flatMap { it.toolNames() }

    fun passes(turn: ReplayTurn): Boolean = when (kind) {
        "speaks" -> turn.lines.isNotEmpty()
        "act_before_announce" -> {
            val acts = tools.ifEmpty { DEFAULT_ACTS }.toSet()
            val first = turn.calls.firstOrNull { it.name in acts }
            first == null || turn.lines.none { it.request <= first.request }
        }
        "calls" -> turn.calls.any { it.name == tool && regex?.containsMatchIn(it.input) != false }
        "no_call" -> turn.calls.none { it.name in tools && regex?.containsMatchIn(it.input) != false }
        "at_most_lines" -> turn.lines.size <= max!!
        "no_line_matches" -> turn.lines.none { regex!!.containsMatchIn(it.text) }
        "any_of" -> of.any { it.passes(turn) }
        "all_of" -> of.all { it.passes(turn) }
        else -> error("unknown check kind $kind")
    }

    companion object {
        /** What `act_before_announce` watches by default: the acts whose result decides what may be announced. */
        val DEFAULT_ACTS = listOf(
            "punish", "favor", "start_challenge", "end_challenge", "propose_duel", "create_rule", "deploy_module", "apply_effect",
            "set_attribute", "launch", "teleport", "spawn", "lightning", "explosion", "give_item", "take_item",
        )
    }
}

/**
 * A frozen situation, as its fixture writes it (`src/test/resources/narrator2/replay/NN-id.json`).
 *
 * @property context one line for the judge: the event, and its target.
 * @property opening the blocks of the chapter's first message (briefing, then the wake-up), each a list of lines;
 *   empty when [transcript] gives them.
 * @property operator an operator's message sent with the opening (`Operator <name>: …`), as a system message.
 * @property transcript an excerpt of a real transcript (`.jsonl`, next to the fixtures) whose first request gives the
 *   opening and the operator's message, exactly as sent.
 * @property previous the Narrator's lines just before, oldest first (those of the briefing's recent chat), for the judge.
 * @property reads the frozen result of a read tool (its lines), served at its first call in a turn.
 * @property acts what an act answers instead of `ok`, by tool.
 * @property silenceOk a turn without a line is a good answer here.
 */
@Serializable
internal data class SituationSpec(
    val id: String,
    val title: String,
    val source: String,
    val context: String,
    val opening: List<List<String>> = emptyList(),
    val operator: String? = null,
    val transcript: String? = null,
    val previous: List<String> = emptyList(),
    val reads: Map<String, List<String>> = emptyMap(),
    val acts: Map<String, String> = emptyMap(),
    val checks: List<CheckSpec>,
    @SerialName("silence_ok") val silenceOk: Boolean = false,
)

/** A situation ready to replay: [blocks] and [operator] resolved from its fixture or its transcript excerpt. */
internal class ReplaySituation(val spec: SituationSpec, val blocks: List<String>, val operator: String?) {
    val id: String get() = spec.id

    /** The frozen result of [tool], or null. */
    fun read(tool: String): String? = spec.reads[tool]?.joinToString("\n")
}

/** One tool call of a replayed turn: in which request (from 1), its tool and its JSON arguments. */
internal data class ReplayCall(val request: Int, val name: String, val input: String)

/** One line a replayed turn said: in which request, through which tool, its text (a title: `TITLE / subtitle`). */
internal data class ReplayLine(val request: Int, val tool: String, val text: String)

/** What a replayed turn did, as the structural checks read it. */
internal interface ReplayTurn {
    val calls: List<ReplayCall>
    val lines: List<ReplayLine>
}

/** A line of a real evening, read from its transcripts: what was said, through which tool, and the turn's situation. */
internal data class SpokenLine(val conversation: String, val turn: String, val tool: String, val text: String, val context: String)

internal object ReplaySituations {
    /** Where the fixtures are, among the test resources. */
    const val DIR = "/narrator2/replay"

    private val JSON = Json { ignoreUnknownKeys = false }

    /** Every situation of [DIR], in the order of their file names. */
    fun load(): List<ReplaySituation> {
        val dir = Paths.get(ReplaySituations::class.java.getResource(DIR)!!.toURI())
        val files = Files.list(dir).use { stream -> stream.filter { it.extension == "json" }.sorted().toList() }
        return files.map { file -> resolve(JSON.decodeFromString(SituationSpec.serializer(), Files.readString(file)), dir) }
    }

    private fun resolve(spec: SituationSpec, dir: Path): ReplaySituation {
        val transcript = spec.transcript ?: return ReplaySituation(spec, spec.opening.map { it.joinToString("\n") }, spec.operator)
        require(spec.opening.isEmpty() && spec.operator == null) { "${spec.id}: an opening comes from the fixture or from its transcript, not both" }
        val opening = opening(Files.readAllLines(dir.resolve(transcript)))
        return ReplaySituation(spec, opening.blocks, opening.operator)
    }

    /** The first message of a conversation and the system message sent with it, if any. */
    class Opening(val blocks: List<String>, val operator: String?)

    /**
     * The opening of the conversation transcribed in [lines] (a `.jsonl` transcript): the text blocks of its first user
     * message, and the system message sent with it, both exactly as sent; everything up to its first request.
     */
    fun opening(lines: List<String>): Opening {
        val blocks = mutableListOf<String>()
        var operator: String? = null
        for (record in records(lines)) {
            if (record.kind == "request") break
            if (record.kind != "sent") continue
            val content = record.data["content"]
            when (record.data.text("role")) {
                "user" -> (content as? JsonArray)?.forEach { block -> (block as? JsonObject)?.text("text")?.let(blocks::add) }
                "system" -> operator = (content as? JsonPrimitive)?.contentOrNull
            }
        }
        require(blocks.isNotEmpty()) { "no first user message in the transcript" }
        return Opening(blocks, operator)
    }

    /**
     * The lines the Narrator said in the Director's transcripts under [dir] (`director-*.jsonl`, searched at any depth),
     * in the order of the conversations' first records then of the replies: `say`, `show_title` and `actionbar`. Each
     * comes with its turn (the transcript's `turn N · trigger …` note) and the situation that provoked it: the turn's
     * operator message, else the last line of its `<events>` block, else its heartbeat.
     */
    fun spokenLines(dir: Path): List<SpokenLine> {
        val files = Files.walk(dir).use { stream -> stream.filter { it.name.startsWith("director-") && it.extension == "jsonl" }.toList() }
        val transcripts = files.map { it to records(Files.readAllLines(it)) }.sortedBy { (_, records) -> records.firstOrNull()?.ts.orEmpty() }
        return transcripts.flatMap { (file, records) -> spokenLines(file.name.removeSuffix(".jsonl"), records) }
    }

    private fun spokenLines(conversation: String, records: List<Record>): List<SpokenLine> {
        val out = mutableListOf<SpokenLine>()
        var turn = ""
        var context = ""
        for (record in records) {
            when (record.kind) {
                "note" -> record.data.text("text")?.takeIf { it.startsWith("turn ") }?.let {
                    turn = it
                    context = ""
                }
                "sent" -> situationOf(record.data)?.let { context = it }
                "response" -> (record.data["content"] as? JsonArray)?.forEach { block ->
                    val use = block as? JsonObject ?: return@forEach
                    if (use.text("type") != "tool_use") return@forEach
                    val name = use.text("name") ?: return@forEach
                    val text = lineOf(name, use["input"] as? JsonObject ?: return@forEach) ?: return@forEach
                    out += SpokenLine(conversation, turn, name, text, context.ifEmpty { turn })
                }
            }
        }
        return out
    }

    /** What a sent message tells of the turn's situation: an operator's message, the last event, or the heartbeat. */
    private fun situationOf(data: JsonObject): String? {
        val content = data["content"]
        if (data.text("role") == "system") return (content as? JsonPrimitive)?.contentOrNull?.lineSequence()?.firstOrNull()?.takeIf { it.startsWith("Operator") }
        val texts = (content as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("text") }.orEmpty()
        for (text in texts) {
            (blockLines(text, "events") + blockLines(text, "new_events")).lastOrNull()?.let { return it }
            blockLines(text, "heartbeat").takeIf { it.isNotEmpty() }?.let { return "HEARTBEAT " + it.joinToString(" · ") }
        }
        return null
    }

    /** The lines inside `<tag …>…</tag>` in [text]. */
    private fun blockLines(text: String, tag: String): List<String> {
        val start = text.indexOf("<$tag")
        if (start < 0) return emptyList()
        val body = text.substring(text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 })
        return body.substringBefore("</$tag>").lines().filter { it.isNotBlank() }
    }

    /** The text the players read from a call of [tool]; null when it is not a line. */
    fun lineOf(tool: String, input: JsonObject): String? = when (tool) {
        "say", "actionbar" -> input.text("text")
        "show_title" -> listOfNotNull(input.text("title"), input.text("subtitle")).joinToString(" / ").ifEmpty { null }
        else -> null
    }

    private class Record(val ts: String, val kind: String, val data: JsonObject)

    private fun records(lines: List<String>): List<Record> = lines.filter { it.isNotBlank() }.map { line ->
        val obj = Json.parseToJsonElement(line).jsonObject
        Record(obj["ts"]?.jsonPrimitive?.contentOrNull.orEmpty(), obj.getValue("kind").jsonPrimitive.content, obj["data"]?.jsonObject ?: JsonObject(emptyMap()))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
