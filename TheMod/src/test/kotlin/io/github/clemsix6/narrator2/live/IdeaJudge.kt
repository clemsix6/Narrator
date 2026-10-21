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

/*
 * The offline judge of the storyteller's ideas (M6 §15.4, T4 and T5): Opus 5.5 at effort low grades each idea on a grid of
 * ideas (novelty, fun, fairness, feasible with the Narrator's levers, fitting the players' real progress, never dark),
 * against the context the storyteller had. Structured output, like the tone judge ([ToneJudge]). An evaluation tool of the
 * live runs, never in the jar.
 */

/**
 * An idea of the storyteller to grade: [card] is everything it wrote (kind, title, pitch, fit, players, register, its own
 * notes, references, module or brief), as one text.
 */
data class JudgedIdea(val id: String, val card: String)

/** The judge's grade of one idea; [reason] first, written before deciding. */
data class IdeaGrade(
    val reason: String,
    val novelty: Int,
    val funScore: Int,
    val fair: Fairness,
    val feasible: Boolean,
    val fitsProgress: Boolean,
    val dark: Boolean,
) {
    /** What T4 counts: feasible with the game's levers, and fair. */
    val feasibleAndFair: Boolean get() = feasible && fair == Fairness.OK && !dark

    fun summary(): String = "novelty $novelty · fun $funScore · fair ${fair.id}${if (feasible) "" else " · NOT FEASIBLE"}" +
        "${if (fitsProgress) "" else " · does not fit the progress"}${if (dark) " · DARK" else ""}"
}

/** What a grading gave: a grade per idea id, the calls, and what went wrong. */
class JudgedIdeas(val grades: Map<String, IdeaGrade>, val calls: List<JudgeCall>, val problems: List<String>) {
    val dollars: Double get() = calls.sumOf { it.dollars }
}

/**
 * The judge of ideas: one request for all the ideas of one grading (a programme has 5 to 8, a commission 3), with the
 * context the storyteller worked from ([grade]'s `context`: its first message, or the Narrator's brief and the players'
 * sheets). Charged to [budget]; a grading that could cross it is not sent. The key stays in the SDK's header.
 */
class IdeaJudge(baseUrl: String, apiKey: String, private val budget: LiveBudget? = null) : AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey).maxRetries(2).build()

    fun grade(context: String, ideas: List<JudgedIdea>): JudgedIdeas {
        if (ideas.isEmpty()) return JudgedIdeas(emptyMap(), emptyList(), emptyList())
        if (budget != null && !budget.allows(ESTIMATE_DOLLARS)) {
            return JudgedIdeas(emptyMap(), emptyList(), listOf("not sent: the budget would be crossed (${ToneJudge.dollars(budget.spent)} spent of ${ToneJudge.dollars(budget.capDollars)})"))
        }
        val (call, answer) = send(context, ideas)
        budget?.charge(call.dollars)
        if (answer == null) return JudgedIdeas(emptyMap(), listOf(call), listOf("no grades: ${call.error}"))
        val problems = mutableListOf<String>()
        val grades = linkedMapOf<String, IdeaGrade>()
        val root = runCatching { JSON.parseToJsonElement(answer.substring(answer.indexOf('{').coerceAtLeast(0))) as? JsonObject }.getOrNull()
        val items = root?.get("grades") as? JsonArray
        if (items == null) {
            problems += "unreadable answer: ${answer.take(200)}"
        } else {
            items.forEach { element ->
                val item = element as? JsonObject
                val id = (item?.get("id") as? JsonPrimitive)?.intOrNull
                val idea = id?.let { ideas.getOrNull(it - 1) }
                val grade = item?.let(::gradeOf)
                when {
                    idea == null -> problems += "an entry without a valid id: ${element.toString().take(120)}"
                    grade == null -> problems += "idea $id: invalid grade ${element.toString().take(200)}"
                    idea.id in grades -> problems += "idea $id graded twice"
                    else -> grades[idea.id] = grade
                }
            }
        }
        ideas.filter { it.id !in grades }.takeIf { it.isNotEmpty() }?.let { missing -> problems += "${missing.size} idea(s) without a grade: ${missing.joinToString { it.id }}" }
        return JudgedIdeas(grades, listOf(call), problems)
    }

    override fun close() = client.close()

    private fun send(context: String, ideas: List<JudgedIdea>): Pair<JudgeCall, String?> {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        val message: Message = try {
            client.messages().create(params(context, ideas), OPTIONS)
        } catch (e: Exception) {
            return JudgeCall(ideas.size, 0, 0, 0, 0, elapsed(), null, error = "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}") to null
        }
        val usage = message.usage()
        val stop = message.stopReason().map { it.asString() }.orElse(null)
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        val error = when {
            stop == "refusal" -> "refused"
            stop == "max_tokens" -> "max_tokens reached"
            text.isBlank() -> "no text in the answer"
            else -> null
        }
        val call = JudgeCall(
            ideas.size, usage.inputTokens(), usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L),
            usage.outputTokens(), elapsed(), stop, error, message.model().asString(),
        )
        return call to text.takeIf { error == null }
    }

    private fun params(context: String, ideas: List<JudgedIdea>): MessageCreateParams = MessageCreateParams.builder()
        .model(ToneJudge.MODEL)
        .maxTokens(ToneJudge.MAX_TOKENS)
        .thinking(ThinkingConfigAdaptive.builder().build())
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(ToneJudge.EFFORT)).format(JsonOutputFormat.builder().schema(SCHEMA).build()).build())
        .system(PROMPT)
        .addUserMessage(request(context, ideas))
        .putAdditionalHeader(ClaudeClient.SESSION_HEADER, ToneJudge.SESSION)
        .build()

    companion object {
        /** What one grading may cost at most, a little more than a programme's measured. */
        const val ESTIMATE_DOLLARS = 0.15

        private val OPTIONS: RequestOptions = RequestOptions.builder().timeout(Timeout.builder().request(Duration.ofMinutes(5)).build()).build()

        private val JSON = Json { ignoreUnknownKeys = true }

        val PROMPT = """
            You review the ideas a writer prepared for « the Narrator », the god of a French Minecraft server, played by an AI:
            sarcastic, theatrical, funny first and severe second, never creepy. The ideas are working notes the Narrator may take,
            adapt or ignore; it plays them later, in the game, with its levers. You get the context the writer had (the players,
            their real progress, the canon, what the Narrator did lately, or the Narrator's request), then the ideas, numbered.

            The Narrator's levers: words (a chat line, a title, a book with a prophecy, a nickname, a running gag); challenges the
            engine follows (obtain an item, reach a place or a dimension, kill creatures, survive, answer a quiz, a judged contest,
            a duel between two volunteers), with a reward and a light forfeit; favors (a named item, a book, a beneficial effect or
            attribute, a pardon, a grace, a joker); punishments of a catalogue (tiny, moon gravity, slow hands, heavy feet, hunger,
            butterfingers, costumed guests, slime rain, skyward, tree top, smite, a glass cage, cobwebs) or composed (attributes,
            effects, a launch, a teleport, named creatures, items taken away for a few minutes); a law or a secret plan for a few
            hours (a trigger such as a block broken, a kill, a death, a dimension change, a chat word, with effects); staging
            (weather, sounds, particles, titles, fireworks); the ready modules of the arsenal the context lists, or a new one ordered
            from a forger with a precise brief. Out of reach: the Narrator builds and destroys nothing (no block of a player is
            touched), nothing is lost for good, no fight between players is forced, forfeits stay light, two challenges at a time.

            Grade each idea:
            - reason: one short English sentence on what decides your grade. Write it first.
            - novelty: 1 to 5, against what the context says the Narrator did lately and the other ideas. 3 is average.
            - fun: 1 to 5, how much the players would enjoy it, or laugh. 3 is average.
            - fair: ok when the severity fits the players (their faults, merits, bad luck) and nobody is singled out unfairly; too_harsh
              when it punishes or mocks more than they deserve, or piles on a player who is down; too_soft when an insult or a
              cheat gets a reward.
            - feasible: true when the Narrator can play it with its levers as listed (a module of the arsenal named exactly, or a
              forge idea with a precise brief); false when it needs something out of reach.
            - fits_progress: true when it matches the players' real progress in the context (no Nether challenge for who never
              went there unless it is about going there for the first time, no diamonds for who has forty); true when progress does
              not matter to it.
            - dark: true for horror, a serious threat, creepy surveillance, despair or fatalism.

            Judge each idea independently: there is no quota of good or bad ideas.
        """.trimIndent()

        /** The answer: `{"grades": [{id, reason, novelty, fun, fair, feasible, fits_progress, dark}, …]}`, one entry per idea. */
        val SCHEMA: JsonOutputFormat.Schema = run {
            val grade = linkedMapOf<String, Any>(
                "id" to mapOf("type" to "integer"),
                "reason" to mapOf("type" to "string"),
                "novelty" to mapOf("type" to "integer", "enum" to listOf(1, 2, 3, 4, 5)),
                "fun" to mapOf("type" to "integer", "enum" to listOf(1, 2, 3, 4, 5)),
                "fair" to mapOf("type" to "string", "enum" to Fairness.entries.map { it.id }),
                "feasible" to mapOf("type" to "boolean"),
                "fits_progress" to mapOf("type" to "boolean"),
                "dark" to mapOf("type" to "boolean"),
            )
            val item = mapOf("type" to "object", "properties" to grade, "required" to grade.keys.toList(), "additionalProperties" to false)
            JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(mapOf("grades" to mapOf("type" to "array", "items" to item))))
                .putAdditionalProperty("required", JsonValue.from(listOf("grades")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()
        }

        /** The user message: the context, then each idea numbered from 1. */
        fun request(context: String, ideas: List<JudgedIdea>): String = buildString {
            append("Grade these ${ideas.size} ideas, one entry each, with its number as id.\n\n<context>\n")
            append(ModelText.escape(context)).append("\n</context>\n")
            ideas.forEachIndexed { i, idea -> append("\n<idea id=\"${i + 1}\">\n${ModelText.escape(idea.card)}\n</idea>\n") }
        }

        private fun gradeOf(item: JsonObject): IdeaGrade? {
            fun string(key: String) = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun bool(key: String) = (item[key] as? JsonPrimitive)?.booleanOrNull
            fun score(key: String) = (item[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..5 }
            return IdeaGrade(
                reason = string("reason") ?: return null,
                novelty = score("novelty") ?: return null,
                funScore = score("fun") ?: return null,
                fair = Fairness.entries.firstOrNull { it.id == string("fair") } ?: return null,
                feasible = bool("feasible") ?: return null,
                fitsProgress = bool("fits_progress") ?: return null,
                dark = bool("dark") ?: return null,
            )
        }
    }
}
