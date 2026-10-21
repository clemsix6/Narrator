package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.style.LineLabels
import io.github.clemsix6.narrator2.style.LineReviewer
import io.github.clemsix6.narrator2.style.ToneWatch
import io.github.clemsix6.narrator2.testing.FakeStyle
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertTrue

/**
 * T1 of design M6 §15.4: the line review (Haiku 5.5, `prompts/line_review.md`) against 60 lines of the Narrator labeled
 * by hand (`narrator2/style/lines-eval.json`: 40 real lines of the acceptance runs of M4 and M5, 20 fabricated), through
 * the owner's LLMGW: `gradlew liveTest` only, one real call per line (a few cents). Each line goes through
 * [LineReviewer.review], the code of the server: its context (the 5 lines said before it) and the crutch candidates
 * come from a [FakeStyle].
 *
 * Thresholds: the register right for 75 % of the lines; every fabricated dark and cruel line found (a score over the
 * thresholds of [ToneWatch], M6 §4.3); false alerts on 10 % of the good lines at most (a flaw over its
 * threshold on a real line, unless the fixture calls it unsure); « céleste » found as a crutch in 8 of the 11 real lines
 * that hold it. The report (with each flaw's scores, to set `ToneWatch`'s thresholds) goes to
 * `build/reports/liveTest/line-review/`. The prompt is the bundled one, or the one in the folder named by
 * `NARRATOR2_LINE_REVIEW_PROMPT_DIR`. The key is never printed.
 */
@Tag("live")
class LiveLineReviewEvalTest {
    private class Case(
        val id: String,
        val text: String,
        val to: List<String>?,
        val recent: List<String>,
        val registers: Set<String>,
        val flaws: Set<String>,
        val unsure: Set<String>,
        val crutches: Set<String>,
        val crutchesOk: Set<String>,
    ) {
        val real: Boolean get() = id.startsWith("r")
    }

    @Test
    fun `the line review on the hand-labeled lines of the Narrator`() {
        val key = LiveGateway.key()
        val promptDir = System.getenv("NARRATOR2_LINE_REVIEW_PROMPT_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val prompts = promptDir?.let { Prompts.load(it) } ?: Prompts.bundled()
        val label = promptDir?.fileName?.toString() ?: "bundled"
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = LiveGateway.BASE_URL))
        val root = Json.parseToJsonElement(dataset()).jsonObject
        val frequent = root.getValue("frequent_words").jsonArray.map { it.jsonPrimitive.content }
        val evenings = root.getValue("evenings").jsonObject.mapValues { (_, lines) -> lines.jsonArray.map { it.jsonPrimitive.content } }
        val cases = root.getValue("lines").jsonArray.map { case(it.jsonObject, evenings) }
        check(cases.size == 60 && cases.count { it.real } == 40) { "the fixture holds 60 lines, 40 of them real" }

        ReflexClient(config, key).use { client ->
            val style = FakeStyle().also { it.candidates = frequent }
            val services = TestServices(settings = Settings(config, prompts, UiTexts.load(config.language), claude = null, reflex = client), style = style)
            val results = cases.associate { case -> case.id to review(services, style, case) }

            val answered = results.values.count { it != null }
            val registerRight = cases.count { results[it.id]?.register?.id in it.registers }
            val realRegisterRight = cases.filter { it.real }.count { results[it.id]?.register?.id in it.registers }
            val dark = cases.filter { DARK in it.flaws }
            val cruel = cases.filter { CRUEL in it.flaws }
            val darkFound = dark.count { found(results[it.id], DARK) }
            val cruelFound = cruel.count { found(results[it.id], CRUEL) }
            val good = cases.filter { it.real && it.flaws.isEmpty() }
            val falseAlerts = good.filter { alerts(it, results[it.id]).isNotEmpty() }
            val celeste = cases.filter { it.real && CELESTE in it.crutches }
            val celesteFound = celeste.count { CELESTE in results[it.id]?.crutches.orEmpty() }
            val crutchMisses = cases.sumOf { case -> case.crutches.count { it !in results[case.id]?.crutches.orEmpty() } }
            val crutchFalse = cases.flatMap { case -> results[case.id]?.crutches.orEmpty().filter { it !in case.crutches && it !in case.crutchesOk }.map { "${case.id} $it" } }
            val calls = services.traces.llmCalls
            val dollars = calls.sumOf {
                ((it.inputTokens ?: 0) * INPUT + (it.cacheReadTokens ?: 0) * CACHE_READ + (it.cacheWriteTokens ?: 0) * CACHE_WRITE + (it.outputTokens ?: 0) * OUTPUT) / 1_000_000.0
            }
            val latencies = results.values.mapNotNull { it?.latencyMs?.toLong() }.sorted()
            val n = cases.size

            val summary = buildString {
                append("# Line review evaluation (T1) · prompt $label · ${client.model}\n\n")
                append("| measure | result | threshold |\n|---|---|---|\n")
                append("| register right | $registerRight/$n (${pct(registerRight, n)}); real lines $realRegisterRight/40 | ≥ 75 % |\n")
                append("| fabricated dark found (dark ≥ ${p(THRESHOLDS.getValue(DARK))}) | $darkFound/${dark.size} | 100 % |\n")
                append("| fabricated cruel found (cruel ≥ ${p(THRESHOLDS.getValue(CRUEL))}) | $cruelFound/${cruel.size} | 100 % |\n")
                append("| false alerts on the good lines | ${falseAlerts.size}/${good.size} (${pct(falseAlerts.size, good.size)}): ${falseAlerts.joinToString { "${it.id} ${alerts(it, results[it.id]).joinToString("+")}" }.ifEmpty { "none" }} | ≤ 10 % |\n")
                append("| « céleste » found as a crutch in the real lines that hold it | $celesteFound/${celeste.size} | ≥ 8/11 |\n")
                append("| answered | $answered/$n | |\n\n")
                append("Other fabricated flaws found: ")
                append(
                    listOf(OUT_OF_CHARACTER, FLAT, REPEATS).joinToString(" · ") { flaw ->
                        val expected = cases.filter { flaw in it.flaws }
                        "$flaw ${expected.count { found(results[it.id], flaw) }}/${expected.size}"
                    },
                )
                append(" · crutch words missed: $crutchMisses · crutch words taken wrongly: ${crutchFalse.size} (${crutchFalse.joinToString().ifEmpty { "none" }})\n\n")
                append("## Scores by flaw (for the thresholds of `ToneWatch`)\n\n")
                append("| flaw | threshold | lines that have it | good lines (real, not unsure): max · count ≥ 0.3 · ≥ 0.5 |\n|---|---|---|---|\n")
                FLAWS.forEach { flaw ->
                    val have = cases.filter { flaw in it.flaws }.map { "${it.id} ${score(results[it.id], flaw)?.let(::p) ?: "-"}" }
                    val clean = good.filter { flaw !in it.unsure }.mapNotNull { score(results[it.id], flaw) }
                    append("| $flaw | ${p(THRESHOLDS.getValue(flaw))} | ${have.joinToString().ifEmpty { "-" }} | ")
                    append("${clean.maxOrNull()?.let(::p) ?: "-"} · ${clean.count { it >= 0.3 }} · ${clean.count { it >= 0.5 }} |\n")
                }
                append("\nLatency: p50 ${percentile(latencies, 50)} ms · p95 ${percentile(latencies, 95)} ms · max ${latencies.lastOrNull()} ms\n")
                append(
                    "Calls: ${calls.size} · input %d · cache read %d · cache write %d · output %d · $%.4f in all, $%.6f per call\n".format(
                        Locale.ROOT, calls.sumOf { it.inputTokens ?: 0 }, calls.sumOf { it.cacheReadTokens ?: 0 }, calls.sumOf { it.cacheWriteTokens ?: 0 },
                        calls.sumOf { it.outputTokens ?: 0 }, dollars, dollars / calls.size.coerceAtLeast(1),
                    ),
                )
                append("Failed calls: ${calls.mapNotNull { it.error }.ifEmpty { listOf("none") }.joinToString("; ")}\n\n")
                append("| line | register (expected) | dark | cruel | flat | ooc | repeats | crutches | misses | reason |\n|---|---|---|---|---|---|---|---|---|---|\n")
                cases.forEach { case ->
                    val r = results[case.id]
                    val misses = misses(case, r)
                    append("| ${case.id} | ${r?.register?.id ?: "-"} (${case.registers.joinToString("/")}) | ")
                    append(FLAWS.joinToString(" | ") { flaw -> score(r, flaw)?.let(::p) ?: "-" })
                    append(" | ${r?.crutches?.joinToString().orEmpty()} | ${misses.ifEmpty { "-" }} | ${r?.reason.orEmpty().replace("|", "/")} |\n")
                }
            }
            println(summary)
            val dir = Path.of("build/reports/liveTest/line-review").toAbsolutePath().also { Files.createDirectories(it) }
            val run = "$label-${Instant.now().epochSecond}"
            Files.writeString(dir.resolve("eval-$run.md"), summary)
            // The raw scores, for a calibration across runs.
            Files.writeString(
                dir.resolve("eval-$run.tsv"),
                "line\tflaws\tunsure\tregister\tdark\tcruel\tflat\tout_of_character\trepeats\tcrutches\n" + cases.joinToString("") { case ->
                    val r = results[case.id]
                    "${case.id}\t${case.flaws.joinToString(",")}\t${case.unsure.joinToString(",")}\t${r?.register?.id}\t" +
                        FLAWS.joinToString("\t") { score(r, it)?.toString() ?: "" } + "\t${r?.crutches?.joinToString(",").orEmpty()}\n"
                },
            )

            assertTrue(answered >= n - 2, "the model answered $answered of $n lines")
            assertTrue(registerRight >= n * 0.75, "register: $registerRight/$n")
            assertTrue(darkFound == dark.size, "dark: $darkFound/${dark.size}")
            assertTrue(cruelFound == cruel.size, "cruel: $cruelFound/${cruel.size}")
            assertTrue(falseAlerts.size <= good.size * 0.10, "false alerts: ${falseAlerts.size}/${good.size}")
            assertTrue(celesteFound >= 8, "céleste: $celesteFound/${celeste.size}")
        }
    }

    /** One line through the server's review, with its own context. A new reviewer each time: a failure pauses nothing. */
    private fun review(services: TestServices, style: FakeStyle, case: Case): LineLabels? {
        style.lines.clear()
        case.recent.forEachIndexed { i, line -> style.lines += (i + 1L) to line }
        val recipients = case.to?.map { PlayerRef(UUID.nameUUIDFromBytes(it.toByteArray()), it) }
        val envelope = Envelope(LINE_ID, System.currentTimeMillis(), 0, NarratorSpoke(NarratorChannel.NARRATOR, case.text, recipients, 1, null))
        return runBlocking { LineReviewer(services).review(envelope) }
    }

    /** The flaws [labels] raise over their threshold on [case] that it neither has nor leaves unsure. */
    private fun alerts(case: Case, labels: LineLabels?): List<String> =
        FLAWS.filter { it !in case.flaws && it !in case.unsure && found(labels, it) }

    private fun misses(case: Case, labels: LineLabels?): String {
        if (labels == null) return "no answer"
        return listOfNotNull(
            "register".takeUnless { labels.register.id in case.registers },
            *case.flaws.filter { !found(labels, it) }.map { "missed $it" }.toTypedArray(),
            *alerts(case, labels).map { "alert $it" }.toTypedArray(),
            *case.crutches.filter { it !in labels.crutches }.map { "missed crutch $it" }.toTypedArray(),
        ).joinToString(", ")
    }

    private fun found(labels: LineLabels?, flaw: String): Boolean = (score(labels, flaw) ?: 0.0) >= THRESHOLDS.getValue(flaw) - 1e-9

    private fun score(labels: LineLabels?, flaw: String): Double? = labels?.let {
        when (flaw) {
            DARK -> it.dark
            CRUEL -> it.cruel
            FLAT -> it.flat
            OUT_OF_CHARACTER -> it.outOfCharacter
            REPEATS -> it.repeats
            else -> error("unknown flaw $flaw")
        }
    }

    private fun case(case: JsonObject, evenings: Map<String, List<String>>): Case {
        val id = case.text("id")
        val text = case.text("text")
        val recent = when (val evening = case["evening"]?.jsonPrimitive?.content) {
            null -> case["recent"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            else -> {
                val lines = evenings[evening] ?: error("$id: no evening $evening")
                val index = lines.indexOf(text).also { check(it >= 0) { "$id: the line is not in the evening $evening" } }
                // The evenings' short lines are titles recorded without their kind: the lines the review reads.
                lines.take(index).filter { Tics.opens(it) }.takeLast(CONTEXT)
            }
        }
        return Case(
            id = id,
            text = text,
            to = (case["to"] as? JsonArray)?.map { it.jsonPrimitive.content },
            recent = recent,
            registers = set(case, "registers"),
            flaws = set(case, "flaws").onEach { check(it in FLAWS) { "$id: unknown flaw $it" } },
            unsure = set(case, "unsure"),
            crutches = set(case, "crutches"),
            crutchesOk = set(case, "crutches_ok"),
        )
    }

    private fun set(case: JsonObject, key: String): Set<String> = (case.getValue(key) as JsonArray).map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun dataset(): String = javaClass.getResourceAsStream(DATASET)!!.use { it.readBytes().decodeToString() }

    private fun percentile(sorted: List<Long>, percent: Int): Long? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * percent + 50) / 100]

    private fun pct(count: Int, of: Int) = if (of == 0) "-" else "${count * 100 / of} %"

    private fun p(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private companion object {
        const val DATASET = "/narrator2/style/lines-eval.json"

        /** The lines of context a real line gets: the reviewer's own count. */
        const val CONTEXT = 5

        const val LINE_ID = 100L

        const val DARK = "dark"
        const val CRUEL = "cruel"
        const val FLAT = "flat"
        const val OUT_OF_CHARACTER = "out_of_character"
        const val REPEATS = "repeats"
        const val CELESTE = "céleste"

        val FLAWS = listOf(DARK, CRUEL, FLAT, OUT_OF_CHARACTER, REPEATS)

        /** The thresholds of [ToneWatch] (M6 §4.3): a line over one of them is flagged. */
        val THRESHOLDS = mapOf(
            DARK to ToneWatch.DARK, CRUEL to ToneWatch.CRUEL, OUT_OF_CHARACTER to ToneWatch.OUT_OF_CHARACTER, FLAT to ToneWatch.FLAT,
            REPEATS to ToneWatch.REPEATS,
        )

        // Haiku 5.5, dollars per million tokens: cache writes at the 1 h rate (LLMGW places `ttl=1h` markers).
        const val INPUT = 0.10
        const val OUTPUT = 0.50
        const val CACHE_READ = 0.01
        const val CACHE_WRITE = 0.20
    }
}
