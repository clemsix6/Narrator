package io.github.clemsix6.narrator2.live

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * The tone judge against the owner's LLMGW (M6 §4.4, eval T2 of §15.4): `gradlew liveTest --tests "*LiveToneEvalTest*"`,
 * tunnel open. Reports go to `build/reports/liveTest/tone/`.
 *
 * - **Calibration** (T2): 20 good real lines of the M3 and M4 acceptance runs and 20 bad ones written for the test, each
 *   with its expected defect (`narrator2/tone/judge-calibration.json`). The judge must catch at least 18 bad lines (the
 *   expected defect found) and accept at least 18 good ones (no defect found). About 0.25 $.
 * - **An evening**: the lines of a real run, read from the Director's transcripts in the folder named by
 *   `NARRATOR2_TONE_EVENING` (a `liveGameTest` run's `conversations/`, or a copy in `.research/`), through the grid, with
 *   the five worst lines and their reasons. Skipped without it. About 0.01 $ a line.
 */
@Tag("live")
class LiveToneEvalTest {
    @Serializable
    private data class CalibrationLine(
        val id: String,
        /** `good`, or the defect the line was written with ([Defect.id]). */
        val expected: String,
        val text: String,
        val context: String,
        val previous: List<String> = emptyList(),
        val source: String,
    )

    @Serializable
    private data class Calibration(val lines: List<CalibrationLine>)

    @Test
    fun `T2 the judge catches the bad lines and accepts the good ones`() {
        val key = LiveGateway.key()
        val calibration = Json.decodeFromString(Calibration.serializer(), resource(CALIBRATION))
        val lines = calibration.lines.map { JudgedLine(it.id, it.text, it.context, it.previous) }.shuffled(Random(SEED))
        val judged = ToneJudge(LiveGateway.BASE_URL, key, LiveBudget(CALIBRATION_BUDGET)).use { it.grade(lines) }

        val good = calibration.lines.filter { it.expected == GOOD }
        val bad = calibration.lines.filter { it.expected != GOOD }
        fun defects(line: CalibrationLine) = judged.grades[line.id]?.defects
        val accepted = good.filter { defects(it)?.isEmpty() == true }
        val caught = bad.filter { line -> defects(line)?.contains(Defect.of(line.expected)) == true }
        val flaggedOtherwise = bad.filter { it !in caught && defects(it)?.isNotEmpty() == true }
        val report = buildString {
            append("# Tone judge calibration (T2)\n\n")
            append("- good lines accepted (no defect): **${accepted.size}/${good.size}** (threshold 18)\n")
            append("- bad lines caught (the expected defect found): **${caught.size}/${bad.size}** (threshold 18)")
            if (flaggedOtherwise.isNotEmpty()) append(" · flagged for another defect: ${flaggedOtherwise.joinToString { it.id }}")
            append("\n- grid of the good lines: ${GridSummary.of(good.mapNotNull { judged.grades[it.id] }).line()}\n")
            append("- judge: ${judged.calls.size} requests · ${ToneJudge.dollars(judged.dollars)} · input ${judged.calls.sumOf { it.input }}, cache read ${judged.calls.sumOf { it.read }}, ")
            append("cache write ${judged.calls.sumOf { it.write }}, output ${judged.calls.sumOf { it.output }} · ${judged.calls.joinToString { "%.1f s".format(java.util.Locale.ROOT, it.latencyMs / 1000.0) }}\n")
            if (judged.problems.isNotEmpty()) append("- problems: ${judged.problems.joinToString("; ")}\n")
            append("\n| line | expected | verdict | grade | reason | text |\n|---|---|---|---|---|---|\n")
            calibration.lines.forEach { line ->
                val grade = judged.grades[line.id]
                val verdict = when {
                    grade == null -> "not graded"
                    line.expected == GOOD -> if (line in accepted) "accepted" else "rejected: ${grade.defects.joinToString { it.id }}"
                    line in caught -> "caught"
                    else -> "missed: ${grade.defects.joinToString { it.id }.ifEmpty { "no defect" }}"
                }
                append("| ${line.id} | ${line.expected} | $verdict | ${grade?.summary().orEmpty()} | ${grade?.reason.orEmpty().cell()} | ${line.text.cell()} |\n")
            }
        }
        write("calibration", report)
        println(report)
        assertTrue(judged.problems.isEmpty(), "the judge graded every line: ${judged.problems}")
        assertTrue(caught.size >= THRESHOLD, "bad lines caught: ${caught.size}/${bad.size}")
        assertTrue(accepted.size >= THRESHOLD, "good lines accepted: ${accepted.size}/${good.size}")
    }

    @Test
    fun `the lines of an evening through the grid`() {
        val folder = System.getenv("NARRATOR2_TONE_EVENING")?.takeIf { it.isNotBlank() }
        assumeTrue(folder != null) { "set NARRATOR2_TONE_EVENING to a folder of Director transcripts" }
        val key = LiveGateway.key()
        val spoken = ReplaySituations.spokenLines(Path.of(folder!!))
        assumeTrue(spoken.isNotEmpty()) { "no line of the Narrator under $folder" }
        val lines = spoken.mapIndexed { i, line -> JudgedLine("L${i + 1}", line.text, line.context, spoken.take(i).takeLast(ToneJudge.PREVIOUS).map { it.text }) }
        val budget = LiveBudget((System.getenv("NARRATOR2_TONE_BUDGET")?.toDoubleOrNull() ?: 1.0).coerceAtMost(EVENING_CAP))
        val judged = ToneJudge(LiveGateway.BASE_URL, key, budget).use { it.grade(lines) }
        val graded = lines.mapNotNull { line -> judged.grades[line.id]?.let { line to it } }
        val summary = GridSummary.of(graded.map { it.second })
        val worst = graded.sortedWith(compareByDescending<Pair<JudgedLine, Grade>> { it.second.eliminatory }.thenByDescending { it.second.defects.size }.thenBy { it.second.funny }).take(5)
        val report = buildString {
            append("# An evening through the grid (M6 §4.2)\n\n")
            append("- transcripts: $folder\n- ${summary.line()}\n")
            append("- thresholds of an evening: ${summary.missed().ifEmpty { listOf("all met") }.joinToString("; ")}\n")
            append("- judge: ${judged.calls.size} requests · ${ToneJudge.dollars(judged.dollars)}\n")
            if (judged.problems.isNotEmpty()) append("- problems: ${judged.problems.joinToString("; ")}\n")
            append("\n## The five worst lines\n\n")
            worst.forEach { (line, grade) -> append("- « ${line.text} » (${line.context.take(160)}) — ${grade.summary()} — ${grade.reason}\n") }
            append("\n## Every line\n\n| # | turn | grade | reason | text |\n|---|---|---|---|---|\n")
            lines.forEachIndexed { i, line ->
                val grade = judged.grades[line.id]
                append("| ${line.id} | ${spoken[i].turn.cell()} | ${grade?.summary() ?: "not graded"} | ${grade?.reason.orEmpty().cell()} | ${line.text.cell()} |\n")
            }
        }
        write("evening", report)
        println(report)
        assertTrue(judged.problems.isEmpty(), "the judge graded every line: ${judged.problems}")
    }

    private fun resource(path: String): String = javaClass.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }

    private fun write(name: String, report: String) {
        val dir = Path.of("build/reports/liveTest/tone").toAbsolutePath().also { Files.createDirectories(it) }
        val file = dir.resolve("$name-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())}.md")
        Files.writeString(file, report)
        println("[tone] report: $file")
    }

    private fun String.cell() = replace("|", "/").replace("\n", " ")

    private companion object {
        const val CALIBRATION = "/narrator2/tone/judge-calibration.json"
        const val GOOD = "good"
        const val THRESHOLD = 18
        const val SEED = 609
        const val CALIBRATION_BUDGET = 1.0
        const val EVENING_CAP = 3.0
    }
}
