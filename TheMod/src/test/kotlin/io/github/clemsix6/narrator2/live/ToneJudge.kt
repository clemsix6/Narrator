package io.github.clemsix6.narrator2.live

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.llm.ClaudeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.util.Locale

/*
 * The offline tone judge (M6 §4.2, §4.4): Opus 5.5 at effort low grades the Narrator's lines on the grid, at most
 * [ToneJudge.BATCH] lines per request. It answers through structured output (`output_config.format`), never through
 * a `grade` tool: Opus 5.5 refuses a forced `tool_choice`, and `auto` does not guarantee the call. An evaluation tool
 * of the live tests (`gradlew liveTest`), never in the jar.
 */

/**
 * A line of the Narrator to grade.
 *
 * @property id unique within one grading.
 * @property text what the players read (a title: `TITLE / subtitle`).
 * @property context one line: the event that provoked it, and its target.
 * @property previous the Narrator's lines just before it, oldest first ([ToneJudge.PREVIOUS] at most are sent).
 * @property acts the Narrator's acts of the same turn up to this line, each with the game's answer
 *   (`punish → ok: Bartok punished, … (in 4m)`, [ReplaySituations.actLine]): what the line announces really happened, and
 *   for how long (M6-18).
 */
data class JudgedLine(val id: String, val text: String, val context: String, val previous: List<String> = emptyList(), val acts: List<String> = emptyList())

/** Does the severity fit the situation (M6 §4.2)? */
enum class Fairness(val id: String) {
    OK("ok"),
    TOO_HARSH("too_harsh"),
    TOO_SOFT("too_soft"),
}

/**
 * Does the player know what happens to them, why and when it ends (a punishment, a challenge, a law)? A time, a duration
 * or a number of rounds or steps tells the end, as `director.md` asks of a challenge (M6-FIX-14).
 */
enum class Legibility(val id: String) {
    YES("yes"),
    NO("no"),
    NOT_APPLICABLE("n_a"),
}

/** The six defects the grid finds in a line (M6 §4.2); fairness is reported apart. */
enum class Defect(val id: String) {
    DARK("dark"),
    CRUEL("cruel"),
    OUT_OF_CHARACTER("out_of_character"),
    FLAT("flat"),
    REPEATS("repeats"),
    ILLEGIBLE("illegible"),
    ;

    companion object {
        fun of(id: String): Defect? = entries.firstOrNull { it.id == id }
    }
}

/** The judge's grade of one line, on the grid of M6 §4.2. [reason] comes first: the judge writes it before deciding. */
data class Grade(
    val reason: String,
    val funny: Int,
    val fair: Fairness,
    val dark: Boolean,
    val cruel: Boolean,
    val voice: Boolean,
    val repeats: Boolean,
    val legible: Legibility,
    /**
     * Whether a player who knows nothing of the mod understands the line at the first read (M7-CLARITY). Reported apart,
     * like fairness: no defect of the grid's calibration.
     */
    val clear: Boolean = true,
) {
    /** Dark or cruel: an eliminatory failure. */
    val eliminatory: Boolean get() = dark || cruel

    val defects: Set<Defect>
        get() = buildSet {
            if (dark) add(Defect.DARK)
            if (cruel) add(Defect.CRUEL)
            if (!voice) add(Defect.OUT_OF_CHARACTER)
            if (funny == 0) add(Defect.FLAT)
            if (repeats) add(Defect.REPEATS)
            if (legible == Legibility.NO) add(Defect.ILLEGIBLE)
        }

    /** `funny 2 · fair ok · voice · legible n_a`, with the defects. */
    fun summary(): String = buildString {
        append("funny $funny · fair ${fair.id}")
        if (dark) append(" · DARK")
        if (cruel) append(" · CRUEL")
        append(if (voice) " · voice" else " · OUT OF VOICE")
        if (repeats) append(" · repeats")
        append(" · legible ${legible.id}")
        if (!clear) append(" · UNCLEAR")
    }
}

/** One request of the judge, measured; [error] when it brought no grades; [model] is the one that answered. */
data class JudgeCall(
    val lines: Int,
    val input: Long,
    val read: Long,
    val write: Long,
    val output: Long,
    val latencyMs: Long,
    val stop: String?,
    val error: String? = null,
    val model: String = ToneJudge.MODEL,
) {
    val dollars: Double get() = LivePrices.dollars(model, input, read, write, output)
}

/** What a grading gave: a grade per line id, the calls, and what went wrong (lines left ungraded, bad values). */
class Judged(val grades: Map<String, Grade>, val calls: List<JudgeCall>, val problems: List<String>) {
    val dollars: Double get() = calls.sumOf { it.dollars }
}

/**
 * A hard cap on what one live run spends, in dollars: every call is charged, and a call that could cross the cap
 * is not sent. Not thread-safe: the live runs are sequential.
 */
class LiveBudget(val capDollars: Double) {
    var spent: Double = 0.0
        private set

    /** Whether [estimate] more dollars stay under the cap. */
    fun allows(estimate: Double): Boolean = spent + estimate <= capDollars

    fun charge(dollars: Double) {
        spent += dollars
    }
}

/**
 * The grid's numbers over [grades] (M6 §4.2), and the thresholds of an evening: dark 0, cruel 0, voice ≥ 95 %,
 * repeats ≤ 15 %, funny ≥ 1.5 on average, legible ≥ 90 % of the lines concerned, fair `ok` ≥ 85 %.
 */
data class GridSummary(
    val lines: Int,
    val funnyMean: Double,
    val dark: Int,
    val cruel: Int,
    val voice: Double,
    val repeats: Double,
    val legibleConcerned: Int,
    val legible: Double,
    val fairOk: Double,
    /** The share of the lines a player understands at the first read (M7-CLARITY); no threshold of an evening. */
    val clear: Double = 1.0,
) {
    val eliminatory: Int get() = dark + cruel

    /** The thresholds of an evening this summary misses, empty when it meets them all. */
    fun missed(): List<String> = buildList {
        if (lines == 0) return@buildList
        if (dark > 0) add("dark $dark (0 allowed)")
        if (cruel > 0) add("cruel $cruel (0 allowed)")
        if (voice < 0.95) add("voice ${pct(voice)} (≥ 95 %)")
        if (repeats > 0.15) add("repeats ${pct(repeats)} (≤ 15 %)")
        if (funnyMean < 1.5) add("funny ${num(funnyMean)} (≥ 1.5)")
        if (legibleConcerned > 0 && legible < 0.90) add("legible ${pct(legible)} of $legibleConcerned (≥ 90 %)")
        if (fairOk < 0.85) add("fair ok ${pct(fairOk)} (≥ 85 %)")
    }

    fun line(): String =
        "$lines lines · funny ${num(funnyMean)} · dark $dark · cruel $cruel · voice ${pct(voice)} · repeats ${pct(repeats)} · " +
            "legible ${pct(legible)} of $legibleConcerned · fair ok ${pct(fairOk)} · clear ${pct(clear)}"

    companion object {
        fun of(grades: Collection<Grade>): GridSummary {
            val n = grades.size.coerceAtLeast(1).toDouble()
            val concerned = grades.filter { it.legible != Legibility.NOT_APPLICABLE }
            return GridSummary(
                lines = grades.size,
                funnyMean = grades.sumOf { it.funny } / n,
                dark = grades.count { it.dark },
                cruel = grades.count { it.cruel },
                voice = grades.count { it.voice } / n,
                repeats = grades.count { it.repeats } / n,
                legibleConcerned = concerned.size,
                legible = if (concerned.isEmpty()) 1.0 else concerned.count { it.legible == Legibility.YES } / concerned.size.toDouble(),
                fairOk = grades.count { it.fair == Fairness.OK } / n,
                clear = grades.count { it.clear } / n,
            )
        }

        fun pct(value: Double) = "%.0f %%".format(Locale.ROOT, value * 100)

        fun num(value: Double) = "%.2f".format(Locale.ROOT, value)
    }
}

/**
 * The judge: Opus 5.5, effort low, adaptive thinking, one request per batch of [BATCH] lines, a JSON object answered
 * through structured output ([SCHEMA]). Every request is charged to [budget] when there is one, and a batch that could
 * cross it is not sent (its lines are left ungraded, with the reason in [Judged.problems]). The key stays in the SDK's
 * header: it is in no log line, report or exception message of this class.
 */
class ToneJudge(baseUrl: String, apiKey: String, private val budget: LiveBudget? = null) : AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey).maxRetries(2).build()

    /** Grades [lines], [BATCH] per request, in the given order. */
    fun grade(lines: List<JudgedLine>): Judged {
        require(lines.map { it.id }.toSet().size == lines.size) { "line ids must be unique" }
        val grades = linkedMapOf<String, Grade>()
        val calls = mutableListOf<JudgeCall>()
        val problems = mutableListOf<String>()
        lines.chunked(BATCH).forEachIndexed { index, batch ->
            val estimate = BATCH_ESTIMATE_DOLLARS * batch.size / BATCH
            if (budget != null && !budget.allows(estimate)) {
                problems += "batch ${index + 1} (${batch.size} lines) not sent: the budget would be crossed " +
                    "(${dollars(budget.spent)} spent of ${dollars(budget.capDollars)})"
                return@forEachIndexed
            }
            val (call, answer) = send(batch)
            budget?.charge(call.dollars)
            calls += call
            if (answer == null) {
                problems += "batch ${index + 1} (${batch.size} lines): ${call.error}"
                return@forEachIndexed
            }
            val parsed = parse(answer, batch.size)
            parsed.problems.forEach { problems += "batch ${index + 1}: $it" }
            parsed.grades.forEach { (number, grade) -> grades[batch[number - 1].id] = grade }
        }
        lines.filter { it.id !in grades }.takeIf { it.isNotEmpty() }?.let { missing ->
            problems += "${missing.size} line(s) without a grade: ${missing.joinToString { it.id }}"
        }
        return Judged(grades, calls, problems)
    }

    override fun close() = client.close()

    /** One request: the call, and the answer's text when the model answered. */
    private fun send(batch: List<JudgedLine>): Pair<JudgeCall, String?> {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        val message: Message = try {
            client.messages().create(params(batch), OPTIONS)
        } catch (e: Exception) {
            return JudgeCall(batch.size, 0, 0, 0, 0, elapsed(), null, error = "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}") to null
        }
        val usage = message.usage()
        val stop = message.stopReason().map { it.asString() }.orElse(null)
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        val error = when {
            stop == "refusal" -> "refused (${message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse("no category")})"
            stop == "max_tokens" -> "max_tokens ($MAX_TOKENS) reached"
            text.isBlank() -> "no text in the answer"
            else -> null
        }
        val call = JudgeCall(
            batch.size, usage.inputTokens(), usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L),
            usage.outputTokens(), elapsed(), stop, error, message.model().asString(),
        )
        return call to text.takeIf { error == null }
    }

    private fun params(batch: List<JudgedLine>): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(MAX_TOKENS)
        .thinking(ThinkingConfigAdaptive.builder().build())
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(EFFORT)).format(JsonOutputFormat.builder().schema(SCHEMA).build()).build())
        .system(PROMPT)
        .addUserMessage(request(batch))
        .putAdditionalHeader(ClaudeClient.SESSION_HEADER, SESSION)
        .build()

    /** The grades read from [text], by line number (1 to [count]), and what was wrong in it. */
    class Parsed(val grades: Map<Int, Grade>, val problems: List<String>)

    companion object {
        /** Lines per request (M6 §4.4). */
        const val BATCH = 20

        /** The previous lines sent with each line. */
        const val PREVIOUS = 5

        const val MODEL = "claude-opus-5-5"
        const val EFFORT = "low"
        const val MAX_TOKENS = 16_000L

        /** The gateway session of the judge (`X-Session-ID`): its prompt stays cached on one upstream account. */
        const val SESSION = "narrator2-judge"

        /** A full batch, a little more than measured (M6-09): what the budget keeps for it before sending. */
        const val BATCH_ESTIMATE_DOLLARS = 0.20

        private val OPTIONS: RequestOptions = RequestOptions.builder().timeout(Timeout.builder().request(Duration.ofMinutes(5)).build()).build()

        private val JSON = Json { ignoreUnknownKeys = true }

        val PROMPT = """
            You review the lines of « the Narrator », the god of a French Minecraft server, played by an AI. Its voice: a cartoon
            villain, sarcastic, theatrical and deadpan, funny first and severe second, never creepy. It narrates the players in the
            third person and talks to them directly when it judges, challenges or answers them. Its lines are short chat messages,
            titles (« TITLE / subtitle ») or action-bar asides, in French.

            Each line comes with the situation that provoked it (the event, and its target) and with the Narrator's previous lines,
            oldest first. A line may also come with the Narrator's acts of the same turn before it, each with the game's answer
            (what was really done to whom, for how long or until when): the players saw them happen, they did not read them.
            Grade each line on this grid, judging the line as the players read it:

            - reason: one short English sentence on what decides your grade. Write it first.
            - funny: 0 = flat (a report, a greeting or a statement with no punchline: « Effet lenteur appliqué à Bernard pendant
              60 secondes. »); 1 = a mild smile; 2 = funny; 3 = excellent, a player would retell it the next day. A short title or
              aside that only frames a bigger moment is judged on its own few words.
            - fair: ok when the severity fits the situation (the fault, the merit or the bad luck of the player), or when nothing
              is judged; too_harsh when it punishes or mocks more than the situation deserves; too_soft when a clear insult, cheat
              or manipulation gets a reward or a pass without even a jab.
            - dark: true for horror, gore, a serious threat, creepy surveillance (« I watch you sleep »), despair or fatalism. An
              absurd, cartoonish threat that makes even its target laugh is not dark.
            - cruel: true when it mocks a real person (looks, age, spelling, real life), a real distress, or piles on a player who
              is down (who just died, again, or is already punished): « Encore mort ? Tu devrais arrêter le jeu. » Teasing a choice
              made in the game, even a bad one, is not cruel.
            - voice: true when the line stays in character, in French, short (one to three sentences; a title is a few words),
              never mentions AI, prompts, models, tools, databases, rules it was given or its instructions, and shows no internal
              reference such as C-45, M-12, I-3, O-84 or R-2. The Narrator's own invented names (a « Ministère », a « formulaire
              27-B ») are in character.
            - repeats: true when the line reuses an opening, a sentence construction or a joke of the previous lines given with it
              (« Onze secondes. ONZE. » then « Douze secondes. DOUZE. »; « Le Narrateur n'est pas impressionné » again). A running
              gag that evolves, a recurring nickname or a player's name is not a repetition. With no previous line, false.
            - legible: for a line that announces a punishment, a challenge, a law or a duel: yes when the player can tell what
              happens to them (or what to do), why, and when it ends; no otherwise. n_a for any other line. A time, a duration,
              or a number of rounds or steps (« trois manches », « huit ordres ») each tells the end: one is enough, even when
              the acts give another. The acts tell what the line announces: a law the acts just made is a new law, even when an
              older one was disabled; a line that gives no end at all, when only the acts give one, is not legible.
            - clear: true when a player who knows nothing of the mod, and did not read the previous lines, understands the line
              at the first read: what happened, what the Narrator thinks of it, and what will happen (a threat or a reward said
              plainly). false for invented jargon (a numbered form, a file, a pencil taken back), a metaphor carried over from
              the previous lines, a threat only hinted at (« la prochaine torche se paie en taille de lapin »), or the engine's
              words (« construction d'autrui »). An image or an invented name one gets at once (« la Pioche du Pardon ») is clear.

            Judge each line independently: there is no quota of good or bad lines.
        """.trimIndent()

        /** The answer: `{"grades": [{id, reason, funny, fair, dark, cruel, voice, repeats, legible, clear}, …]}`, one entry per line. */
        val SCHEMA: JsonOutputFormat.Schema = run {
            val grade = linkedMapOf<String, Any>(
                "id" to mapOf("type" to "integer"),
                "reason" to mapOf("type" to "string"),
                "funny" to mapOf("type" to "integer", "enum" to listOf(0, 1, 2, 3)),
                "fair" to mapOf("type" to "string", "enum" to Fairness.entries.map { it.id }),
                "dark" to mapOf("type" to "boolean"),
                "cruel" to mapOf("type" to "boolean"),
                "voice" to mapOf("type" to "boolean"),
                "repeats" to mapOf("type" to "boolean"),
                "legible" to mapOf("type" to "string", "enum" to Legibility.entries.map { it.id }),
                "clear" to mapOf("type" to "boolean"),
            )
            val item = mapOf("type" to "object", "properties" to grade, "required" to grade.keys.toList(), "additionalProperties" to false)
            JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(mapOf("grades" to mapOf("type" to "array", "items" to item))))
                .putAdditionalProperty("required", JsonValue.from(listOf("grades")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()
        }

        /** The user message of a batch: each line numbered from 1, with its situation and its previous lines. */
        fun request(batch: List<JudgedLine>): String = buildString {
            append("Grade these ${batch.size} lines, one entry each, with its number as id.\n")
            batch.forEachIndexed { i, line ->
                append("\n<line id=\"${i + 1}\">\n")
                append("<situation>${ModelText.escape(line.context)}</situation>\n")
                val previous = line.previous.takeLast(PREVIOUS)
                if (previous.isEmpty()) {
                    append("<previous>(none)</previous>\n")
                } else {
                    append("<previous>\n")
                    previous.forEach { append("- ").append(ModelText.escape(it)).append('\n') }
                    append("</previous>\n")
                }
                if (line.acts.isNotEmpty()) {
                    append("<acts>\n")
                    line.acts.forEach { append("- ").append(ModelText.escape(it)).append('\n') }
                    append("</acts>\n")
                }
                append("<text>${ModelText.escape(line.text)}</text>\n</line>\n")
            }
        }

        /** Reads the judge's JSON [text] for a batch of [count] lines: every grade checked, what is wrong said. */
        fun parse(text: String, count: Int): Parsed {
            val problems = mutableListOf<String>()
            val root = runCatching { JSON.parseToJsonElement(text.substring(text.indexOf('{').coerceAtLeast(0))) as? JsonObject }.getOrNull()
            val items = root?.get("grades") as? JsonArray ?: return Parsed(emptyMap(), listOf("unreadable answer: ${text.take(200)}"))
            val grades = linkedMapOf<Int, Grade>()
            items.forEach { element ->
                val item = element as? JsonObject
                val id = (item?.get("id") as? JsonPrimitive)?.intOrNull
                if (item == null || id == null || id !in 1..count) {
                    problems += "an entry without a valid id: ${element.toString().take(120)}"
                    return@forEach
                }
                if (id in grades) {
                    problems += "line $id graded twice: the first grade is kept"
                    return@forEach
                }
                val grade = gradeOf(item)
                if (grade == null) problems += "line $id: invalid grade ${item.toString().take(200)}" else grades[id] = grade
            }
            return Parsed(grades, problems)
        }

        private fun gradeOf(item: JsonObject): Grade? {
            fun string(key: String) = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun bool(key: String) = (item[key] as? JsonPrimitive)?.booleanOrNull
            val funny = (item["funny"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..3 } ?: return null
            return Grade(
                reason = string("reason") ?: return null,
                funny = funny,
                fair = Fairness.entries.firstOrNull { it.id == string("fair") } ?: return null,
                dark = bool("dark") ?: return null,
                cruel = bool("cruel") ?: return null,
                voice = bool("voice") ?: return null,
                repeats = bool("repeats") ?: return null,
                legible = Legibility.entries.firstOrNull { it.id == string("legible") } ?: return null,
                clear = bool("clear") ?: return null,
            )
        }

        fun dollars(value: Double) = "$%.3f".format(Locale.ROOT, value)
    }
}
