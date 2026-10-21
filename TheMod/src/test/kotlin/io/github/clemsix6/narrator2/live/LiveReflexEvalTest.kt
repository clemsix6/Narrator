package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.ReflexConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.reflex.ChatLine
import io.github.clemsix6.narrator2.reflex.ChatTriageInput
import io.github.clemsix6.narrator2.reflex.ChatVerdict
import io.github.clemsix6.narrator2.reflex.ClaudeReflexes
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.test.assertTrue

/**
 * The reflex model against the chat heuristic on the French evaluation set (REFLEX-HAIKU), through the
 * owner's LLMGW: `gradlew liveTest` only. 52 messages and 6 heartbeats, one real call each (about a cent
 * in all). For each message, whether it addresses the Narrator (the salience's decision, gray zone
 * included), its tone, its intent and a manipulation; the latency of the calls (p50, p95) and their cost.
 * The report goes to `build/reports/liveTest/reflex/`.
 *
 * The prompt is the bundled `reflex.md`, or the one in the folder named by the environment variable
 * `NARRATOR2_REFLEX_PROMPT_DIR` (to compare two prompts). The key is never printed.
 */
@Tag("live")
class LiveReflexEvalTest {
    private class Case(val id: String, val input: ChatTriageInput, val addressed: Boolean, val tones: Set<String>, val intents: Set<String>, val manipulation: Boolean)

    private class Heartbeat(val id: String, val summary: List<String>, val online: List<String>, val worth: Boolean)

    /** What a verdict got right on a case. */
    private class Score(val addressed: Boolean, val tone: Boolean, val intent: Boolean, val manipulation: Boolean)

    @Test
    fun `the reflex model against the heuristic on the French evaluation set`() {
        val key = LiveGateway.key()
        val promptDir = System.getenv("NARRATOR2_REFLEX_PROMPT_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val prompts = promptDir?.let { Prompts.load(it) } ?: Prompts.bundled()
        val thinking = System.getenv("NARRATOR2_REFLEX_THINKING") == "true"
        val timeoutMs = System.getenv("NARRATOR2_REFLEX_TIMEOUT_MS")?.toLongOrNull() ?: ReflexConfig().timeoutMs
        val label = "${promptDir?.fileName?.toString() ?: "bundled"}-${if (thinking) "thinking" else "no-thinking"}-${timeoutMs}ms"
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = LiveGateway.BASE_URL), reflex = ReflexConfig(thinking = thinking, timeoutMs = timeoutMs))
        val root = Json.parseToJsonElement(dataset()).jsonObject
        val cases = root.getValue("cases").jsonArray.map { case(it.jsonObject) }
        val heartbeats = root.getValue("heartbeats").jsonArray.map { heartbeat(it.jsonObject) }
        val heuristic = HeuristicReflexes.forLanguage("fr")

        ReflexClient(config, key).use { client ->
            val services = TestServices(settings = Settings(config, prompts, UiTexts.load(config.language), claude = null, reflex = client))
            val reflexes = ClaudeReflexes(services)
            val model = cases.associate { case -> case.id to runBlocking { reflexes.triageChat(case.input) } }
            val beats = heartbeats.associate { beat -> beat.id to runBlocking { reflexes.heartbeat(5, beat.summary, beat.online) } }
            val rules = cases.associate { it.id to heuristic.judge(it.input) }

            val modelScores = cases.associate { it.id to score(it, model.getValue(it.id)) { heuristic.judge(it.input).toNarrator } }
            val ruleScores = cases.associate { it.id to score(it, rules.getValue(it.id)) { 0.0 } }
            val answered = model.values.count { it.source.startsWith(ClaudeReflexes.SOURCE_PREFIX) }
            val latencies = model.values.filter { it.source != HeuristicReflexes.SOURCE }.map { it.latencyMs }.sorted()
            val calls = services.traces.llmCalls
            val dollars = calls.sumOf { LivePrices.dollars(it) }
            val beatsRight = heartbeats.count { (beats.getValue(it.id).worth >= ReflexThresholds.HEARTBEAT_WORTH) == it.worth }

            fun rate(scores: Map<String, Score>, pick: (Score) -> Boolean) = scores.values.count(pick)
            val n = cases.size
            val summary = buildString {
                append("# Reflex evaluation · prompt $label · ${client.model}\n\n")
                append("| | model | heuristic |\n|---|---|---|\n")
                append("| addressed (salience's decision) | ${rate(modelScores) { it.addressed }}/$n | ${rate(ruleScores) { it.addressed }}/$n |\n")
                append("| tone | ${rate(modelScores) { it.tone }}/$n | ${rate(ruleScores) { it.tone }}/$n |\n")
                append("| intent | ${rate(modelScores) { it.intent }}/$n | ${rate(ruleScores) { it.intent }}/$n |\n")
                append("| manipulation | ${rate(modelScores) { it.manipulation }}/$n | ${rate(ruleScores) { it.manipulation }}/$n |\n\n")
                append("Answered by the model: $answered/$n · heartbeats right: $beatsRight/${heartbeats.size}\n")
                append("Latency of the chat calls: p50 ${percentile(latencies, 50)} ms · p95 ${percentile(latencies, 95)} ms · max ${latencies.lastOrNull()} ms\n")
                append(
                    "Calls: ${calls.size} · input %d · cache read %d · cache write %d · output %d · $%.4f in all, $%.6f per call\n\n".format(
                        Locale.ROOT, calls.sumOf { it.inputTokens ?: 0 }, calls.sumOf { it.cacheReadTokens ?: 0 }, calls.sumOf { it.cacheWriteTokens ?: 0 },
                        calls.sumOf { it.outputTokens ?: 0 }, dollars, dollars / calls.size.coerceAtLeast(1),
                    ),
                )
                append("Cache read/write of the first calls: ${calls.take(8).joinToString { "${it.cacheReadTokens}/${it.cacheWriteTokens}" }}\n")
                val chatCalls = calls.filter { it.purpose == LlmCallRecord.Purpose.CHAT_TRIAGE && it.outputTokens != null }
                append("Output tokens per chat call (thinking included): ${chatCalls.sumOf { it.outputTokens ?: 0 } / chatCalls.size.coerceAtLeast(1)}\n")
                append("Failed calls: ${calls.mapNotNull { it.error }.ifEmpty { listOf("none") }.joinToString("; ")}\n\n")
                // Calibration: the raw probability of the model's own answers against the truth, threshold by threshold.
                val measured = cases.filter { model.getValue(it.id).source != HeuristicReflexes.SOURCE }
                append("Raw threshold on `addressed` (model answers only, no gray zone): ")
                append(
                    (2..18).joinToString(" · ") { step ->
                        val t = step * 0.05
                        "${p(t)} ${measured.count { (model.getValue(it.id).toNarrator >= t - 1e-9) == it.addressed }}/${measured.size}"
                    },
                )
                append("\n\n")
                append("| case | text | model: to you, tone, intent, manip | heuristic | misses (model / heuristic) |\n|---|---|---|---|---|\n")
                cases.forEach { case ->
                    val v = model.getValue(case.id)
                    val h = rules.getValue(case.id)
                    append("| ${case.id} | ${case.input.message.text.replace("|", "/")} | ${p(v.toNarrator)}, ${v.tone.id}, ${v.intent.id}, ${p(v.manipulation)} | ")
                    append("${p(h.toNarrator)}, ${h.tone.id}, ${h.intent.id} | ${misses(modelScores.getValue(case.id))} / ${misses(ruleScores.getValue(case.id))} |\n")
                }
                append("\n| heartbeat | expected | worth, hook |\n|---|---|---|\n")
                heartbeats.forEach { append("| ${it.id} | ${it.worth} | ${p(beats.getValue(it.id).worth)}, ${beats.getValue(it.id).hook} |\n") }
            }
            println(summary)
            val dir = Path.of("build/reports/liveTest/reflex").toAbsolutePath().also { Files.createDirectories(it) }
            val run = "$label-${Instant.now().epochSecond}"
            Files.writeString(dir.resolve("eval-$run.md"), summary)
            // The raw answers, for a calibration across runs.
            Files.writeString(
                dir.resolve("eval-$run.tsv"),
                "case\texpected\tsource\taddressed\tmanipulation\n" + cases.joinToString("") {
                    val v = model.getValue(it.id)
                    "${it.id}\t${it.addressed}\t${v.source}\t${v.toNarrator}\t${v.manipulation}\n"
                },
            )

            assertTrue(answered >= n - 2, "the model answered $answered of $n messages")
            assertTrue(rate(modelScores) { it.addressed } >= n * 0.85, "addressed: ${rate(modelScores) { it.addressed }}/$n")
            assertTrue(rate(modelScores) { it.intent } >= n * 0.75, "intent: ${rate(modelScores) { it.intent }}/$n")
            assertTrue(rate(modelScores) { it.tone } >= n * 0.75, "tone: ${rate(modelScores) { it.tone }}/$n")
            assertTrue(rate(modelScores) { it.manipulation } >= n * 0.9, "manipulation: ${rate(modelScores) { it.manipulation }}/$n")
            assertTrue(beatsRight >= heartbeats.size - 1, "heartbeats: $beatsRight/${heartbeats.size}")
        }
    }

    private fun score(case: Case, verdict: ChatVerdict, gray: () -> Double) = Score(
        addressed = ReflexThresholds.triage(verdict, gray).addressed == case.addressed,
        tone = verdict.tone.id in case.tones,
        intent = verdict.intent.id in case.intents,
        manipulation = (verdict.manipulation >= ReflexThresholds.MANIPULATION) == case.manipulation,
    )

    private fun misses(score: Score): String = listOfNotNull(
        "addressed".takeUnless { score.addressed }, "tone".takeUnless { score.tone }, "intent".takeUnless { score.intent }, "manip".takeUnless { score.manipulation },
    ).joinToString(" ").ifEmpty { "-" }

    private fun case(case: JsonObject): Case {
        val expected = case.getValue("expected").jsonObject
        val recent = (case["recent"] as? JsonArray)?.map { ChatLine(it.jsonObject.text("player"), it.jsonObject.text("text")) }.orEmpty()
        return Case(
            id = case.text("id"),
            input = ChatTriageInput(
                ChatLine(case.text("player"), case.text("text")),
                recent,
                case["narrator_last"]?.jsonPrimitive?.content,
                prayer = case["prayer"]?.jsonPrimitive?.booleanOrNull ?: false,
            ),
            addressed = expected.getValue("addressed").jsonPrimitive.boolean,
            tones = options(expected.getValue("tone")),
            intents = options(expected.getValue("intent")),
            manipulation = expected.getValue("manipulation").jsonPrimitive.boolean,
        )
    }

    private fun heartbeat(beat: JsonObject) = Heartbeat(
        beat.text("id"),
        beat.getValue("summary").jsonArray.map { it.jsonPrimitive.content },
        beat.getValue("online").jsonArray.map { it.jsonPrimitive.content },
        beat.getValue("worth").jsonPrimitive.boolean,
    )

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** A label or a list of acceptable labels. */
    private fun options(value: JsonElement): Set<String> =
        if (value is JsonArray) value.map { it.jsonPrimitive.content }.toSet() else setOf(value.jsonPrimitive.content)

    private fun dataset(): String = javaClass.getResourceAsStream(DATASET)!!.use { it.readBytes().decodeToString() }

    private fun percentile(sorted: List<Long>, percent: Int): Long? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * percent + 50) / 100]

    private fun p(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private companion object {
        const val DATASET = "/narrator2/reflex/chat-triage-eval.json"
    }
}
