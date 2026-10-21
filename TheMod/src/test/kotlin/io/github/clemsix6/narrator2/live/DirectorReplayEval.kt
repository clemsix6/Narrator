package io.github.clemsix6.narrator2.live

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Exchange
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertTrue

/*
 * The Director's replay (M6 §4.5): frozen situations played by the real Director with two versions of `director.md`,
 * A and B, then compared on structural checks and on the tone judge's grid. It decides whether a retouch of the prompt
 * (M6-14's compaction first) may be merged. Live tests only (`gradlew liveTest`).
 */

/** One version of `director.md` in a replay. */
internal class PromptVersion(val label: String, val text: String, val source: String) {
    val sha: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}

/** How a replayed turn ended. */
internal enum class TurnEnd(val id: String) {
    /** The Director ended its turn (a reply without a tool call). */
    ENDED("ended"),

    /** The [ReplayRunner.MAX_REQUESTS]th request still called tools: they are recorded, not answered. */
    REQUEST_CAP("request cap"),
    REFUSED("refused"),
    INCOMPLETE("incomplete"),
    FAILED("failed"),

    /** The budget stopped the turn before one of its requests. */
    BUDGET("budget"),
}

/** One replayed turn: a situation, a version, a sample. */
internal class ReplaySample(
    val situation: String,
    val version: String,
    val sample: Int,
    override val calls: List<ReplayCall>,
    override val lines: List<ReplayLine>,
    val requests: Int,
    val end: TurnEnd,
    val detail: String?,
    val stats: List<CallStats>,
) : ReplayTurn {
    val dollars: Double get() = stats.sumOf { cost(it) }

    /** `sql → say | punish, say` : the tools of each request, in order. */
    fun toolLine(): String = (1..requests).joinToString(" | ") { r -> calls.filter { it.request == r }.joinToString(", ") { it.name }.ifEmpty { "(end)" } }

    companion object {
        fun cost(stats: CallStats): Double = LivePrices.dollars(stats.inputTokens, stats.cacheReadTokens, stats.cacheWriteTokens, stats.outputTokens)
    }
}

/**
 * Replays one situation with one version of the prompt: the chapter's first message, then up to [MAX_REQUESTS] requests
 * (M6 review, finding 6). Nothing is executed: a read gets the situation's frozen result at its first call in the turn
 * ([ReplaySituation.read]), then [NOTHING_MORE]; an act gets `ok` (or the fixture's answer). Every request is charged to
 * [budget], and none is sent that could cross it.
 */
internal class ReplayRunner(
    private val client: ClaudeClient,
    private val tools: ToolSet,
    private val transcripts: Transcripts?,
    private val budget: LiveBudget,
    /** Dollars [budget] keeps for what follows the replay (the judge). */
    private val reserve: Double = 0.0,
) {
    private val mapper = jsonMapper()

    fun run(situation: ReplaySituation, version: PromptVersion, sample: Int): ReplaySample = runBlocking {
        val conversation = Conversation(
            Role.DIRECTOR, "Replay ${situation.id} ${version.label} #$sample", listOf(TextBlockParam.builder().text(version.text).build()),
            tools.definitions, transcripts,
        )
        val calls = mutableListOf<ReplayCall>()
        val lines = mutableListOf<ReplayLine>()
        val stats = mutableListOf<CallStats>()
        val served = mutableSetOf<String>()
        var pending = opening(situation)
        fun done(requests: Int, end: TurnEnd, detail: String? = null) = ReplaySample(situation.id, version.label, sample, calls, lines, requests, end, detail, stats)
        try {
            for (request in 1..MAX_REQUESTS) {
                if (!budget.allows(REQUEST_ESTIMATE_DOLLARS + reserve)) return@runBlocking done(request - 1, TurnEnd.BUDGET, "${ToneJudge.dollars(budget.spent)} spent")
                val result = when (val exchange = client.exchange(conversation, pending)) {
                    is Exchange.Failed -> {
                        stats += exchange.stats
                        budget.charge(ReplaySample.cost(exchange.stats))
                        return@runBlocking done(request, TurnEnd.FAILED, exchange.stats.error)
                    }
                    is Exchange.Answered -> exchange.result
                }
                stats += result.stats
                budget.charge(ReplaySample.cost(result.stats))
                val reply = when (result) {
                    is SendResult.Refused -> return@runBlocking done(request, TurnEnd.REFUSED, result.category)
                    is SendResult.Incomplete -> return@runBlocking done(request, TurnEnd.INCOMPLETE, result.reason)
                    is SendResult.Reply -> result
                }
                val uses = reply.toolUses
                if (uses.isEmpty()) return@runBlocking done(request, TurnEnd.ENDED)
                uses.forEach { use -> record(use, request, calls, lines) }
                if (request == MAX_REQUESTS) return@runBlocking done(request, TurnEnd.REQUEST_CAP)
                pending = listOf(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(uses.map { result(it, situation, served) }).build())
            }
            error("unreachable")
        } finally {
            conversation.close()
        }
    }

    private fun record(use: ToolUseBlock, request: Int, calls: MutableList<ReplayCall>, lines: MutableList<ReplayLine>) {
        val input = mapper.writeValueAsString(use._input())
        calls += ReplayCall(request, use.name(), input)
        val arguments = runCatching { Json.parseToJsonElement(input) as? JsonObject }.getOrNull() ?: return
        ReplaySituations.lineOf(use.name(), arguments)?.let { lines += ReplayLine(request, use.name(), it) }
    }

    private fun result(use: ToolUseBlock, situation: ReplaySituation, served: MutableSet<String>): ContentBlockParam {
        val name = use.name()
        val effect = tools[name]?.effect
        val text = when {
            effect == null -> "FAILED: unknown tool $name"
            effect == ToolEffect.READ -> situation.read(name)?.takeIf { served.add(name) } ?: NOTHING_MORE
            else -> situation.spec.acts[name] ?: "ok"
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(effect == null).build())
    }

    private fun opening(situation: ReplaySituation): List<MessageParam> = listOfNotNull(
        MessageParam.builder().role(MessageParam.Role.USER)
            .contentOfBlockParams(situation.blocks.map { ContentBlockParam.ofText(TextBlockParam.builder().text(it).build()) })
            .build(),
        situation.operator?.let { MessageParam.builder().role(MessageParam.Role.SYSTEM).content(it).build() },
    )

    companion object {
        /** Requests of one replayed turn at most (M6 §4.5). */
        const val MAX_REQUESTS = 4

        const val NOTHING_MORE = "ok: nothing more to read in this replay"

        /** The most a request costs, a little more than measured (a chapter's first request writes its prefix in the cache). */
        const val REQUEST_ESTIMATE_DOLLARS = 0.25
    }
}

/** How often a version passed a check of a situation, over its samples. */
internal data class CheckTally(val situation: String, val check: String, val a: Int, val aSamples: Int, val b: Int, val bSamples: Int) {
    /** A passed it on every sample, B did not (the verdict's rule, M6 §4.5). */
    val lost: Boolean get() = aSamples > 0 && a == aSamples && b < bSamples

    /** B passed it on every sample, A did not. */
    val gained: Boolean get() = bSamples > 0 && b == bSamples && a < aSamples

    /** A passed it on every sample, B on none: a loss that noise alone hardly explains. */
    val lostClearly: Boolean get() = lost && b == 0
}

/**
 * The verdict of a replay (M6 §4.5): B passes when it loses no structural check that A passed on all its samples, loses
 * no more than [FUNNY_TOLERANCE] of mean « funny », and has no more eliminatory failure (dark, cruel) than A; and only
 * when the replay is complete (every planned sample played, every line graded).
 */
internal class ReplayVerdict(
    val tallies: List<CheckTally>,
    val gridA: GridSummary,
    val gridB: GridSummary,
    val complete: Boolean,
    val incompleteWhy: List<String>,
) {
    val lost: List<CheckTally> get() = tallies.filter { it.lost }
    val funnyDrop: Double get() = gridA.funnyMean - gridB.funnyMean
    val moreEliminatory: Int get() = gridB.eliminatory - gridA.eliminatory

    /** Checks whose pass counts differ between A and B: with two copies of one prompt, pure noise. */
    val flips: List<CheckTally> get() = tallies.filter { it.a != it.b }

    val passes: Boolean get() = complete && lost.isEmpty() && funnyDrop <= FUNNY_TOLERANCE + 1e-9 && moreEliminatory <= 0

    fun reasons(): List<String> = buildList {
        if (!complete) add("incomplete: ${incompleteWhy.joinToString("; ")}")
        lost.forEach { add("B loses ${it.situation} · ${it.check} (A ${it.a}/${it.aSamples}, B ${it.b}/${it.bSamples})") }
        if (funnyDrop > FUNNY_TOLERANCE + 1e-9) add("funny drops by ${GridSummary.num(funnyDrop)} (${GridSummary.num(gridA.funnyMean)} → ${GridSummary.num(gridB.funnyMean)}, ${FUNNY_TOLERANCE} allowed)")
        if (moreEliminatory > 0) add("$moreEliminatory more eliminatory failure(s) in B (${gridA.eliminatory} → ${gridB.eliminatory})")
    }

    companion object {
        const val FUNNY_TOLERANCE = 0.2

        fun of(
            situations: List<ReplaySituation>,
            samples: List<ReplaySample>,
            a: String,
            b: String,
            grades: Map<String, Grade>,
            planned: Int,
            problems: List<String>,
        ): ReplayVerdict {
            val tallies = situations.flatMap { situation ->
                val ofA = samples.filter { it.situation == situation.id && it.version == a && it.end != TurnEnd.BUDGET }
                val ofB = samples.filter { it.situation == situation.id && it.version == b && it.end != TurnEnd.BUDGET }
                if (ofA.isEmpty() && ofB.isEmpty()) return@flatMap emptyList()
                situation.spec.checks.map { check ->
                    CheckTally(situation.id, check.name.ifEmpty { check.kind }, ofA.count { check.passes(it) }, ofA.size, ofB.count { check.passes(it) }, ofB.size)
                }
            }
            fun gridOf(version: String) = GridSummary.of(samples.filter { it.version == version }.flatMap { s -> s.lines.indices.mapNotNull { grades[lineId(s, it)] } })
            val played = samples.count { it.end != TurnEnd.BUDGET }
            val why = buildList {
                if (played < planned) add("${planned - played} of $planned samples not played (budget)")
                samples.filter { it.end == TurnEnd.FAILED }.forEach { add("${it.situation} ${it.version} #${it.sample} failed: ${it.detail}") }
                addAll(problems)
            }
            return ReplayVerdict(tallies, gridOf(a), gridOf(b), why.isEmpty(), why)
        }

        /** The judge's id of a sample's [index]th line. */
        fun lineId(sample: ReplaySample, index: Int) = "${sample.situation}/${sample.version}/${sample.sample}/${index + 1}"
    }
}

/**
 * The replay itself, against the owner's LLMGW (`gradlew liveTest --tests "*DirectorReplayEval*"`, tunnel open). Settings,
 * from the environment:
 *
 * - `NARRATOR2_REPLAY_A`, `NARRATOR2_REPLAY_B`: the two `director.md` files; the bundled one when not set (B = A then: the
 *   pass that measures the noise of the replay).
 * - `NARRATOR2_REPLAY_SAMPLES`: samples per situation and version, 2 by default.
 * - `NARRATOR2_REPLAY_ONLY`: the situations to play, by id, comma-separated; all of them by default.
 * - `NARRATOR2_REPLAY_BUDGET`: the run's budget in dollars, [DEFAULT_BUDGET] by default and never above [HARD_CAP], judge
 *   included. A situation is not started when its four turns could cross it, nor a request.
 * - `NARRATOR2_REPLAY_STORYTELLER`: `true` to declare `inspire`, the 48th tool (once M6-16 is merged).
 *
 * Turns run one after the other: A then B for each sample of a situation, so that a stop by the budget leaves whole
 * situations. The report goes to `build/reports/liveTest/replay/<run>/`, with every turn's transcript.
 */
@Tag("live")
class DirectorReplayEval {
    @Test
    fun `director md B against A on the frozen situations`() {
        val key = LiveGateway.key()
        val bundled = Prompts.bundled().get(Role.DIRECTOR.id)
        val a = version("A", System.getenv("NARRATOR2_REPLAY_A"), bundled)
        val b = version("B", System.getenv("NARRATOR2_REPLAY_B"), bundled)
        val samplesPer = System.getenv("NARRATOR2_REPLAY_SAMPLES")?.toIntOrNull()?.coerceIn(1, 4) ?: 2
        val only = System.getenv("NARRATOR2_REPLAY_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val cap = (System.getenv("NARRATOR2_REPLAY_BUDGET")?.toDoubleOrNull() ?: DEFAULT_BUDGET).coerceAtMost(HARD_CAP)
        val storyteller = System.getenv("NARRATOR2_REPLAY_STORYTELLER") == "true"
        val situations = ReplaySituations.load().filter { only == null || it.id in only }
        assertTrue(situations.isNotEmpty(), "no situation to play (NARRATOR2_REPLAY_ONLY: $only)")

        val tools = ToolCatalog.toolSetFor(Role.DIRECTOR, directorEnv(storyteller), TestServices())
        val run = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val dir = Path.of("build/reports/liveTest/replay/$run").toAbsolutePath().also { Files.createDirectories(it) }
        val transcripts = Transcripts(dir.resolve("transcripts"), enabled = true)
        val budget = LiveBudget(cap)
        val planned = situations.size * samplesPer * 2
        println("[replay] ${situations.size} situations × $samplesPer samples × 2 versions = $planned turns · ${tools.names.size} tools · budget ${ToneJudge.dollars(cap)} · A ${a.source} (${a.sha}) · B ${b.source} (${b.sha})")

        val samples = mutableListOf<ReplaySample>()
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = LiveGateway.BASE_URL, maxRetries = 2))
        ClaudeClient(config, key).use { client ->
            val runner = ReplayRunner(client, tools, transcripts, budget, JUDGE_RESERVE_DOLLARS)
            var played = 0
            for (situation in situations) {
                val perSituation = if (played == 0) samplesPer * 2 * ReplayRunner.REQUEST_ESTIMATE_DOLLARS else samples.sumOf { it.dollars } / played
                if (!budget.allows(perSituation + JUDGE_RESERVE_DOLLARS)) {
                    println("[replay] ${situation.id}: not started, the budget could be crossed (${ToneJudge.dollars(budget.spent)} spent, ${ToneJudge.dollars(perSituation)} per situation)")
                    continue
                }
                for (n in 1..samplesPer) {
                    for (version in listOf(a, b)) {
                        val sample = runner.run(situation, version, n)
                        samples += sample
                        println(
                            "[replay] ${situation.id} ${version.label}#$n · ${sample.end.id} after ${sample.requests} request(s) · ${sample.toolLine()} · " +
                                "${sample.lines.size} line(s) · ${ToneJudge.dollars(sample.dollars)} (total ${ToneJudge.dollars(budget.spent)})",
                        )
                    }
                }
                played++
            }
        }

        val lines = samples.flatMap { sample ->
            val situation = situations.first { it.id == sample.situation }
            sample.lines.mapIndexed { i, line ->
                JudgedLine(ReplayVerdict.lineId(sample, i), line.text, situation.spec.context, situation.spec.previous + sample.lines.take(i).map { it.text })
            }
        }.shuffled(Random(JUDGE_SEED))
        val judged = ToneJudge(LiveGateway.BASE_URL, key, budget).use { it.grade(lines) }
        val verdict = ReplayVerdict.of(situations, samples, a.label, b.label, judged.grades, planned, judged.problems)
        val report = report(situations, samples, a, b, judged, lines, verdict, budget, samplesPer, tools.names.size)
        Files.writeString(dir.resolve("report.md"), report)
        Files.writeString(dir.resolve("samples.json"), samplesJson(samples, judged.grades).toString())
        println(report)
        println("[replay] report: ${dir.resolve("report.md")}")
        assertTrue(verdict.passes, "B does not pass against A: ${verdict.reasons().joinToString("; ")}")
    }

    private fun version(label: String, path: String?, bundled: String): PromptVersion =
        if (path.isNullOrBlank()) PromptVersion(label, bundled, "bundled director.md") else PromptVersion(label, Files.readString(Path.of(path)), path)

    companion object {
        const val DEFAULT_BUDGET = 7.0
        const val HARD_CAP = 8.0

        /** What the budget keeps for the judge while the turns are played. */
        const val JUDGE_RESERVE_DOLLARS = 0.8

        /** The lines go to the judge shuffled, A and B mixed in every batch, always in the same order for the same lines. */
        const val JUDGE_SEED = 6009

        /** What the Director has on a server where everything is wired (`Chapters.open`): its 47 tools, 48 with [storyteller]. */
        fun directorEnv(storyteller: Boolean) = ToolEnv(
            journalAvailable = true, workerRoles = setOf(Role.ANALYST, Role.SCOUT, Role.FORGER), memoryAvailable = true,
            mechanicsAvailable = true, forgeAvailable = true, storytellerAvailable = storyteller,
        )

        internal fun report(
            situations: List<ReplaySituation>,
            samples: List<ReplaySample>,
            a: PromptVersion,
            b: PromptVersion,
            judged: Judged,
            lines: List<JudgedLine>,
            verdict: ReplayVerdict,
            budget: LiveBudget,
            samplesPer: Int,
            tools: Int,
        ): String = buildString {
            fun pct(n: Int, of: Int) = if (of == 0) "-" else "%.0f %%".format(Locale.ROOT, 100.0 * n / of)
            append("# Director replay (M6 §4.5)\n\n")
            append("- A: ${a.source} · ${a.text.length} characters · sha ${a.sha}\n")
            append("- B: ${b.source} · ${b.text.length} characters · sha ${b.sha}\n")
            append("- ${situations.size} situations · $samplesPer samples per version · $tools tools declared · at most ${ReplayRunner.MAX_REQUESTS} requests per turn\n\n")
            append("## Verdict: ${if (verdict.passes) "B PASSES" else "B FAILS"}\n\n")
            verdict.reasons().ifEmpty { listOf("no structural check lost, funny within ${ReplayVerdict.FUNNY_TOLERANCE}, no more eliminatory failure") }.forEach { append("- $it\n") }
            append("\n| | A | B |\n|---|---|---|\n")
            append("| grid | ${verdict.gridA.line()} | ${verdict.gridB.line()} |\n")
            val byVersion = listOf(a.label, b.label).associateWith { v -> samples.filter { it.version == v && it.end != TurnEnd.BUDGET } }
            fun cell(f: (List<ReplaySample>) -> String) = "| ${f(byVersion.getValue(a.label))} | ${f(byVersion.getValue(b.label))} |"
            append("| turns played ${cell { it.size.toString() }}\n")
            append("| turns without a line ${cell { s -> "${s.count { it.lines.isEmpty() }} (${pct(s.count { it.lines.isEmpty() }, s.size)})" }}\n")
            val silenceOk = situations.filter { it.spec.silenceOk }.map { it.id }.toSet()
            append("| … where a line was expected ${cell { s -> s.filter { it.situation !in silenceOk }.let { e -> "${e.count { it.lines.isEmpty() }} of ${e.size}" } }}\n")
            append("| lines ${cell { s -> s.sumOf { it.lines.size }.toString() }}\n")
            append("| requests per turn ${cell { s -> GridSummary.num(s.map { it.requests }.average()) }}\n")
            append("| turns at the request cap ${cell { s -> s.count { it.end == TurnEnd.REQUEST_CAP }.toString() }}\n")
            append("| refused, incomplete, failed ${cell { s -> "${s.count { it.end == TurnEnd.REFUSED }}, ${s.count { it.end == TurnEnd.INCOMPLETE }}, ${s.count { it.end == TurnEnd.FAILED }}" }}\n")
            append("| cost of the turns ${cell { s -> ToneJudge.dollars(s.sumOf { it.dollars }) }}\n\n")

            append("## Noise (A against B)\n\n")
            val tallies = verdict.tallies
            append("- checks whose pass counts differ: ${verdict.flips.size} of ${tallies.size}")
            if (verdict.flips.isNotEmpty()) append(" (${verdict.flips.joinToString { "${it.situation} · ${it.check} ${it.a}/${it.aSamples} → ${it.b}/${it.bSamples}" }})")
            append("\n- lost by B (A all, B not all): ${verdict.lost.size} · clearly lost (A all, B none): ${tallies.count { it.lostClearly }} · gained by B: ${tallies.count { it.gained }}\n")
            append("- funny: A ${GridSummary.num(verdict.gridA.funnyMean)}, B ${GridSummary.num(verdict.gridB.funnyMean)}, difference ${GridSummary.num(abs(verdict.funnyDrop))}\n")
            append("- eliminatory failures: A ${verdict.gridA.eliminatory}, B ${verdict.gridB.eliminatory}\n\n")

            append("## Cost\n\n")
            val turns = samples.sumOf { it.dollars }
            val stats = samples.flatMap { it.stats }
            append("- turns: ${stats.size} requests · ${ToneJudge.dollars(turns)} · input ${stats.sumOf { it.inputTokens }}, cache read ${stats.sumOf { it.cacheReadTokens }}, cache write ${stats.sumOf { it.cacheWriteTokens }}, output ${stats.sumOf { it.outputTokens }}\n")
            val firsts = samples.mapNotNull { it.stats.firstOrNull() }
            append("- first request of a turn: cache read ${firsts.map { it.cacheReadTokens }.average().toLong()} · cache write ${firsts.map { it.cacheWriteTokens }.average().toLong()} tokens on average\n")
            append("- judge: ${judged.calls.size} requests for ${lines.size} lines · ${ToneJudge.dollars(judged.dollars)} · input ${judged.calls.sumOf { it.input }}, cache read ${judged.calls.sumOf { it.read }}, cache write ${judged.calls.sumOf { it.write }}, output ${judged.calls.sumOf { it.output }}\n")
            append("- total: ${ToneJudge.dollars(budget.spent)} (budget ${ToneJudge.dollars(budget.capDollars)}); prices of Opus 5.5 (4 / 20 / 0.20 / 8 $ per million, cache writes at the 1 h rate)\n")
            if (judged.problems.isNotEmpty()) append("- judge problems: ${judged.problems.joinToString("; ")}\n")
            append("\n## Situations\n\n")
            for (situation in situations) {
                val ofSituation = samples.filter { it.situation == situation.id }
                append("### ${situation.id} — ${situation.spec.title}\n\n")
                if (ofSituation.isEmpty()) {
                    append("Not played (budget).\n\n")
                    continue
                }
                append("${situation.spec.context}${if (situation.spec.silenceOk) " · silence is a good answer here" else ""}\n\n")
                append("| check | A | B |\n|---|---|---|\n")
                tallies.filter { it.situation == situation.id }.forEach { append("| ${it.check} | ${it.a}/${it.aSamples} | ${it.b}/${it.bSamples} |\n") }
                append("\n")
                for (sample in ofSituation) {
                    append("- **${sample.version}#${sample.sample}** · ${sample.end.id}${sample.detail?.let { " ($it)" }.orEmpty()} · ${sample.toolLine()} · ${ToneJudge.dollars(sample.dollars)}\n")
                    sample.calls.filter { it.name !in SPEECH }.forEach { call ->
                        append("  - `${call.name}` ${call.input.take(300)}\n")
                    }
                    sample.lines.forEachIndexed { i, line ->
                        val grade = judged.grades[ReplayVerdict.lineId(sample, i)]
                        append("  - ${line.tool}: « ${line.text} » — ${grade?.summary() ?: "not graded"}${grade?.let { " — ${it.reason}" }.orEmpty()}\n")
                    }
                }
                append("\n")
            }
        }

        private val SPEECH = setOf("say", "show_title", "actionbar")

        /** Every sample, its calls, its lines and their grades: the raw results, for a later look. */
        internal fun samplesJson(samples: List<ReplaySample>, grades: Map<String, Grade>): JsonObject = buildJsonObject {
            putJsonArray("samples") {
                samples.forEach { sample ->
                    addJsonObject {
                        put("situation", sample.situation)
                        put("version", sample.version)
                        put("sample", sample.sample)
                        put("end", sample.end.id)
                        sample.detail?.let { put("detail", it) }
                        put("requests", sample.requests)
                        put("dollars", sample.dollars)
                        putJsonArray("calls") { sample.calls.forEach { call -> addJsonObject { put("request", call.request); put("tool", call.name); put("input", call.input) } } }
                        putJsonArray("lines") {
                            sample.lines.forEachIndexed { i, line ->
                                addJsonObject {
                                    put("tool", line.tool)
                                    put("text", line.text)
                                    grades[ReplayVerdict.lineId(sample, i)]?.let { g ->
                                        put("funny", g.funny); put("fair", g.fair.id); put("dark", g.dark); put("cruel", g.cruel)
                                        put("voice", g.voice); put("repeats", g.repeats); put("legible", g.legible.id); put("reason", g.reason)
                                    }
                                }
                            }
                        }
                        putJsonArray("stats") { sample.stats.forEach { s -> add("in ${s.inputTokens} read ${s.cacheReadTokens} write ${s.cacheWriteTokens} out ${s.outputTokens} stop ${s.stopReason}") } }
                    }
                }
            }
        }
    }
}
