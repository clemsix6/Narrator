package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.MemoryService
import io.github.clemsix6.narrator2.memory.Quotes
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.createMemory
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertTrue

/**
 * The memory's live evals E1 to E6 (M3 §15.2, M3-19), against the owner's LLMGW. Run by `gradlew liveTest`
 * only; skipped without a key or a tunnel, and once the live budget is spent ([LiveReports]).
 *
 * - **E1, E2, E3** (nothing invented in quotes): the real Chronicler, through the real scheduler
 *   (`createMemory`, an operator's request), reads two hand-written evenings (`eval/e1e2-*.json`) on the real
 *   Journal; its commitments and interactions are compared to what each evening expects.
 * - **E5**: three evenings where the Narrator contradicts a law of its canon (`eval/e5-*.json`); a batch must
 *   leave a `coherence` note.
 * - **E6**: the real Director, whose briefing lists its overused openers (`eval/e6-tics.json`), answers five
 *   wake-ups; at most one reply may reuse a listed opener.
 *
 * E3's numbers and E4 (the voice judge) need a dream: they are measured on the dream of `LiveMemoryCycleTest`.
 * Assertions are structural; what the model wrote goes to `build/reports/liveTest/memory/evals.md`.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
class LiveMemoryEvalTest {
    private val report = StringBuilder("# Live evals of the memory (M3-19)\n\n")
    private val batches = mutableListOf<BatchOutcome>()

    @AfterAll
    fun writeReport() {
        if (report.length > 60) LiveReports.write("evals.md", report.toString())
    }

    @Test
    fun `e1_e2 the Chronicler records the forgotten commitments and the interactions that matter`() {
        val key = LiveGateway.key()
        LiveReports.requireBudget(0.6)
        val outcomes = selected("e1e2-evening-a", "e1e2-evening-b").map { runBatch(BatchFixture.load(it), key) }
        batches += outcomes

        val commitments = Scores.commitments(outcomes)
        val interactions = Scores.interactions(outcomes)
        report.append("## E1 · commitments found by a batch (effort ${outcomes.first().effort})\n\n").append(commitments.text).append("\n")
        report.append("## E2 · interactions with the right sign\n\n").append(interactions.text).append("\n")
        outcomes.forEach { report.append(it.markdown()) }
        writeReport()

        assertTrue(outcomes.all { it.status == "done" }, "every batch done: ${outcomes.map { "${it.fixture.name} ${it.status} ${it.error}" }}")
        assertTrue(commitments.recall >= 0.9, "E1 recall ${commitments.recall} < 0.90:\n${commitments.text}")
        assertTrue(commitments.precision >= 0.9, "E1 precision ${commitments.precision} < 0.90:\n${commitments.text}")
        assertTrue(interactions.recall >= 0.8, "E2 ${interactions.recall} < 0.80:\n${interactions.text}")
    }

    @Test
    fun `e5 a contradiction of a law becomes a coherence note`() {
        val key = LiveGateway.key()
        LiveReports.requireBudget(0.5)
        val outcomes = selected("e5-sleep", "e5-sheep", "e5-dirt").map { runBatch(BatchFixture.load(it), key) }
        batches += outcomes
        val noted = outcomes.filter { outcome -> outcome.notes.any { it.kind == "coherence" } }
        report.append("## E5 · contradictions noted: ${noted.size} of ${outcomes.size}\n\n")
        outcomes.forEach { outcome ->
            val notes = outcome.notes.filter { it.kind == "coherence" }
            val citesLaw = notes.any { note -> outcome.knownRefs.any { it in note.refs } }
            report.append("- ${outcome.fixture.name}: ${if (notes.isEmpty()) "no coherence note" else "${notes.size} note(s)${if (citesLaw) ", citing the law" else ", not citing the law"}"}\n")
        }
        report.append("\n")
        outcomes.forEach { report.append(it.markdown()) }
        writeReport()
        assertTrue(noted.size >= 2, "E5: ${noted.size} of 3 contradictions noted")
    }

    @Test
    fun `e6 the Director told of its tics does not repeat them`() {
        val key = LiveGateway.key()
        LiveReports.requireBudget(0.8)
        val fixture = TicsFixture.load()
        LiveWorld("e6-tics", key).use { world ->
            val today = LocalDate.now(world.zone)
            fixture.history.forEach { line ->
                val at = today.minusDays(line.daysAgo.toLong()).atTime(LocalTime.parse(line.at)).atZone(world.zone).toInstant()
                world.at(at) { world.publish(NarratorSpoke(NarratorChannel.NARRATOR, line.text, null, 1, null)) }
            }
            fixture.players.forEach { world.join(it) }
            runBlocking { world.journal.barrier() }
            world.start()
            world.memory.jobs.pause("operator:eval") // E6 measures the Director alone: no batch of the chapter

            val wakes = fixture.wakes.map { wake ->
                val before = world.said.size
                val tone = Tone.valueOf(wake.tone.uppercase(Locale.ROOT))
                val intent = ChatIntent.valueOf(wake.intent.uppercase(Locale.ROOT))
                if (wake.channel == "pray") world.pray(wake.player, wake.text) else world.chat(wake.player, wake.text, 0.93, tone, intent)
                awaitTurn(world, before)
                world.awaitQuiet("the reply to ${wake.player}")
                wake to world.said.drop(before)
            }

            val chapter = world.director.status().conversationId ?: error("no chapter opened")
            val opening = world.firstUserText(chapter)
            val styleLine = opening.lines().firstOrNull { it.startsWith("Style: overused") }
            val listed = styleLine?.let { line -> Regex("« ([^»]+) »").findAll(line).map { it.groupValues[1] }.toList() }.orEmpty()
            val tics = (listed + fixture.openers + fixture.phrases).map(::plain).filter { it.length >= 3 }.distinct()
            val repeats = wakes.count { (_, lines) -> lines.any { said -> tics.any { tic -> plain(said.text).startsWith(tic) || tic in plain(said.text) } } }
            val calls = LiveCall.read(world.journal)
            LiveReports.record("LiveMemoryEvalTest", "E6", calls)

            report.append("## E6 · tics: $repeats of ${wakes.size} replies reuse a listed opener or phrase\n\n")
            report.append("Style line of the briefing: `${styleLine ?: "(none)"}`\n\n")
            wakes.forEach { (wake, lines) ->
                report.append("- ${wake.player}: « ${wake.text} »\n")
                if (lines.isEmpty()) report.append("  - (no say)\n")
                lines.forEach { said ->
                    val hit = tics.firstOrNull { tic -> plain(said.text).startsWith(tic) || tic in plain(said.text) }
                    report.append("  - ${if (hit != null) "**REPEAT** ($hit) " else ""}→${said.to?.joinToString(",") ?: "all"}: « ${said.text} »\n")
                }
            }
            val first = calls.firstOrNull { it.conversation == chapter }
            report.append("\nFirst call of the chapter: ${first?.let { "input ${it.input}, cache read ${it.read}, cache write ${it.write}" }}; " +
                "first message ${opening.length} characters\n\n")
            report.append("Costs: ${LiveCall.summary(calls)}\n\n").append(LiveCall.table(calls)).append("\n")
            report.append("<details><summary>The chapter's first message</summary>\n\n```\n$opening\n```\n</details>\n\n")
            writeReport()

            assertTrue(styleLine != null, "the briefing lists the tics: ${opening.take(3000)}")
            assertTrue(wakes.count { it.second.isNotEmpty() } >= 4, "the Director answered: ${wakes.map { it.second.size }}")
            assertTrue(repeats <= 1, "E6: $repeats of 5 replies reuse a listed tic")
        }
    }

    @Test
    fun `e9_e3 no quote is invented`() {
        assumeTrue(batches.isNotEmpty()) { "no batch ran" }
        val invented = batches.flatMap { outcome -> outcome.inventedQuotes().map { "${outcome.fixture.name}: $it" } }
        val refused = batches.sumOf { it.rejected }
        report.append("## E3 · nothing invented (batches)\n\n")
        report.append("- quotes written that no line of the evening holds: ${invented.size}${invented.joinToString("") { "\n  - $it" }}\n")
        report.append("- tool calls refused (all reasons, the store's included): $refused\n")
        batches.forEach { outcome ->
            outcome.toolCalls.filter { it.status != "ok" }.forEach { report.append("  - ${outcome.fixture.name} ${it.tool} ${it.status}: ${it.result?.take(300)}\n") }
        }
        report.append("\n")
        val all = batches.flatMap { calls -> calls.calls }
        report.append("## Costs of the batches\n\n${LiveCall.summary(all)}\n\n").append(LiveCall.table(all)).append("\n")
        writeReport()
        assertTrue(invented.isEmpty(), "E3: invented quotes written: $invented")
    }

    // ── A batch on a hand-written evening ───────────────────────────────────────────────────────────

    /** Plays [fixture] on a fresh Journal, then has the real scheduler run the Chronicler's batch over it. */
    private fun runBatch(fixture: BatchFixture, key: String): BatchOutcome = BatchWorld(fixture, key).use { world ->
        world.load()
        val started = System.nanoTime()
        val outcome = world.batch()
        LiveReports.record("LiveMemoryEvalTest", fixture.name, outcome.calls)
        println("[live] ${fixture.name}: ${outcome.status} in ${(System.nanoTime() - started) / 1_000_000_000} s · ${LiveCall.summary(outcome.calls)}")
        outcome
    }

    /** Waits until the Director starts a turn after [said] lines were said, or 30 s. */
    private fun awaitTurn(world: LiveWorld, said: Int) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            if (world.director.status().turnId != null || world.said.size > said) return
            Thread.sleep(200)
        }
    }

    /**
     * A fresh Journal in memory at the fixture's date (UTC, the clock stopped at each event, then at `now`), the
     * real memory (`createMemory`) and Chronicler with the real client; the Director is a fake whose open chapter
     * is the fixture's.
     */
    private class BatchWorld(val fixture: BatchFixture, key: String) : AutoCloseable {
        val clock = TestClock(fixture.at(fixture.start), ZoneOffset.UTC)
        val journal = JournalFixture(clock = clock)
        val config = LiveGateway.config(ZoneOffset.UTC)
        val client = ClaudeClient(config, key)
        val chapter = "director-c${fixture.chapter}"
        private val fakeDirector = FakeDirector(clock)
        private val director = object : DirectorControl by fakeDirector {
            override fun status() = fakeDirector.status().copy(chapter = fixture.chapter, conversationId = chapter)
        }
        private val late = LateMemory()
        val dir: Path = Path.of("build/live-memory/${fixture.name}-${System.currentTimeMillis()}").toAbsolutePath().also { Files.createDirectories(it) }
        val players = PlayerStates().also { it.subscribe(journal.bus) { null } }
        val services = TestServices(
            clock = clock, dataDir = dir, settings = testSettings(config, client), bus = journal.bus, journal = journal.journal,
            trace = journal.journal, transcripts = Transcripts(dir.resolve("transcripts"), enabled = true), players = players,
            director = director, memory = late,
        )
        lateinit var memory: MemoryService
        private var turn = 100L
        private var lastNarratorLine = 0L
        val lines = mutableListOf<Pair<String, String>>() // channel, text
        val knownRefs = mutableListOf<Long>()
        val eventAt = HashMap<String, Long>()

        fun ref(name: String) = PlayerRef(UUID.nameUUIDFromBytes("narrator2-eval-$name".toByteArray()), name)

        /** The evening: the chapter's row, the known canon (said in an earlier chapter), then each event at its time. */
        fun load() {
            services.phase = Phase.STARTING
            memory = createMemory(services) as? MemoryService ?: error("the memory did not start")
            late.memory = memory
            journal.journal.conversation(ConversationRecord(chapter, Role.DIRECTOR, "Chapter ${fixture.chapter}", fixture.chapter, null, fixture.at(fixture.start), null, null, null, null))
            fixture.known.forEach { known ->
                clock.now = fixture.at(known.at)
                val said = known.said?.let { narrator(it, null) }
                journal.sync()
                clock.now = fixture.at(known.at).plusSeconds(1)
                val draft = CanonDraft(CanonKind.valueOf(known.kind.uppercase(Locale.ROOT)), known.statement, known.quote, known.players.map { ref(it).uuid }, evidenceEvents = listOfNotNull(said))
                val added = runBlocking { memory.store.addCanon(draft, Author(Source.DIRECTOR, turnId = turn, conversationId = "director-c${fixture.chapter - 1}")) }
                knownRefs += added.row.ref.id
            }
            fixture.events.forEach(::play)
            journal.sync()
            clock.now = fixture.at(fixture.now)
            services.phase = Phase.RUNNING
        }

        private fun play(event: FixtureEvent) {
            clock.now = fixture.at(event.at)
            when {
                event.join != null -> journal.publish(PlayerJoined(ref(event.join), POS, journal.journal.nextId(IdKind.SESSION), false, false))
                event.chat != null || event.pray != null -> {
                    val player = ref(event.chat ?: event.pray!!)
                    val channel = if (event.pray != null) ChatChannel.PRAY else ChatChannel.PUBLIC
                    val text = event.text ?: error("${event.at}: a chat line without text")
                    val id = journal.publish(ChatMessage(player, channel, text, emptyList())).id
                    eventAt[event.at] = id
                    lines += channel.name.lowercase() to text
                    journal.journal.chatSignals(
                        ChatSignalsRecord(
                            id, clock.instant(), player.uuid, "jev:jev-1.13.0", event.addressed ?: 0.5,
                            event.tone?.let { Tone.valueOf(it.uppercase(Locale.ROOT)) }, 0.85,
                            event.intent?.let { ChatIntent.valueOf(it.uppercase(Locale.ROOT)) }, event.manipulation ?: 0.02, null, null, null, null, null, 120,
                        ),
                    )
                }
                event.narrator != null -> {
                    turn++
                    lastNarratorLine = narrator(event.narrator, event.to, event.operator)
                    eventAt[event.at] = lastNarratorLine
                }
                event.tool != null -> {
                    val id = journal.journal.nextId(IdKind.TOOL_CALL)
                    val effect = if (event.tool == "remember") ToolEffect.ENGINE else ToolEffect.WORLD
                    journal.journal.toolCall(
                        ToolCallRecord(id, clock.instant(), chapter, turn, null, "toolu_$id", Role.DIRECTOR, event.tool, "{}", ToolStatus.OK, event.result, 40, event.targets, effect),
                    )
                    if (event.hostile) {
                        event.targets.forEach { target ->
                            journal.journal.decision(
                                DecisionRecord(journal.journal.nextId(IdKind.DECISION), clock.instant(), DecisionRecord.Kind.GUARD, "allowed", target, "hostile action", null, chapter, turn, null),
                            )
                        }
                    }
                }
                event.grief != null -> {
                    val breaker = ref(event.grief)
                    val owner = ref(event.owner ?: error("${event.at}: a grief without owner")).uuid
                    repeat(event.count) { i ->
                        clock.now = fixture.at(event.at).plusMillis(i * 400L)
                        journal.publish(BlockBroken(breaker, Pos("minecraft:overworld", 300 + i % 4, 70 + i / 4, 120), event.block ?: "minecraft:oak_planks", "minecraft:iron_axe", false, true, owner))
                    }
                }
                event.remembered != null -> {
                    journal.sync()
                    val known = event.remembered
                    val kind = CanonKind.valueOf(known.kind.uppercase(Locale.ROOT))
                    val draft = CanonDraft(kind, known.statement, known.quote, known.players.map { ref(it).uuid }, evidenceEvents = listOf(lastNarratorLine))
                    val added = runBlocking { memory.store.addCanon(draft, Author(Source.DIRECTOR, turnId = turn, conversationId = chapter)) }
                    knownRefs += added.row.ref.id
                    val id = journal.journal.nextId(IdKind.TOOL_CALL)
                    val result = "ok: remembered ${added.row.ref} (${kind.id} → ${known.players.joinToString().ifEmpty { "all" }})"
                    journal.journal.toolCall(
                        ToolCallRecord(id, clock.instant(), chapter, turn, null, "toolu_$id", Role.DIRECTOR, "remember", "{}", ToolStatus.OK, result, 12, known.players, ToolEffect.ENGINE),
                    )
                }
                else -> error("${event.at}: an event of no known kind")
            }
        }

        private fun narrator(text: String, to: List<String>?, operator: String? = null): Long {
            val channel = if (operator == null) NarratorChannel.NARRATOR else NarratorChannel.OPERATOR
            lines += channel.name.lowercase() to text
            return journal.publish(NarratorSpoke(channel, text, to?.map(::ref), if (operator == null) turn else null, operator)).id
        }

        /** Asks for the batch of the open chapter, as an operator does, and reads back what it did. */
        fun batch(): BatchOutcome {
            memory.jobs.requestChronicle("operator:eval").onFailure { check(it.message!!.startsWith("nothing to chronicle")) { it.message!! } }
            val end = System.currentTimeMillis() + 15 * 60_000L
            var run: List<String?>
            while (true) {
                run = journal.rows("SELECT run_id, status, error, report, conversations::VARCHAR, rejected, writes FROM memory_runs WHERE kind = 'batch'") {
                    listOf(getLong(1).toString(), getString(2), getString(3), getString(4), getString(5), getInt(6).toString(), getInt(7).toString())
                }.singleOrNull() ?: listOf(null)
                if (run.size > 1 && run[1] != "running") break
                check(System.currentTimeMillis() < end) { "the batch of ${fixture.name} did not end: $run" }
                Thread.sleep(1_000)
            }
            val runId = run[0]!!.toLong()
            val conversations = run[4]!!.removeSurrounding("[", "]").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val names = fixture.players.associateBy { ref(it).uuid.toString() }
            fun players(sql: String?): List<String> =
                sql?.removeSurrounding("[", "]")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { names[it] ?: it }.orEmpty()

            val canon = journal.rows("SELECT id, kind, statement, quote, players::VARCHAR, due_at, stakes, source FROM canon WHERE run_id = $runId ORDER BY id") {
                CanonRow(getLong(1), getString(2), getString(3), getString(4), players(getString(5)), getObject(6, java.time.OffsetDateTime::class.java)?.toInstant(), getString(7), getString(8))
            }
            val interactions = journal.rows(
                "SELECT id, player, kind, valence, summary, quote, source, evidence_events::VARCHAR FROM interactions WHERE run_id = $runId ORDER BY id",
            ) {
                val evidence = getString(8).removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toLongOrNull() }
                InteractionRow(getLong(1), getString(2), getString(3), getInt(4), getString(5), getString(6), getString(7), evidence)
            }
            val notes = journal.rows("SELECT id, kind, text, refs::VARCHAR FROM notes WHERE run_id = $runId ORDER BY id") {
                NoteRow(getLong(1), getString(2), getString(3), getString(4).removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toLongOrNull() })
            }
            val where = if (conversations.isEmpty()) "false" else "conversation_id IN (${conversations.joinToString { "'$it'" }})"
            val toolCalls = journal.rows("SELECT tool, status, args::VARCHAR, result FROM tool_calls WHERE $where ORDER BY id") {
                ToolRow(getString(1), getString(2), getString(3), getString(4))
            }
            val calls = LiveCall.read(journal.journal, where)
            val opening = conversations.firstOrNull()?.let { firstUserText(dir.resolve("transcripts"), it) }
            return BatchOutcome(
                fixture, config.role(Role.CHRONICLER).effort, run[1]!!, run[2], run[3], run[5]!!.toInt(), run[6]!!.toInt(), canon, interactions, notes, toolCalls,
                calls, opening, lines.toList(), knownRefs.toList(), eventAt.toMap(),
            )
        }

        override fun close() {
            runBlocking { withTimeoutOrNull(15_000) { if (late.started) memory.stop() } }
            services.phase = Phase.STOPPING
            services.scope.cancel()
            journal.close()
            client.close()
        }

        companion object {
            val POS = Pos("minecraft:overworld", 100, 64, -200)

            /** The text of the first `user` message of [conversation], from its transcript under [root]. */
            fun firstUserText(root: Path, conversation: String): String? {
                val file = Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "$conversation.jsonl" }.findFirst().orElse(null) } ?: return null
                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                return Files.readAllLines(file).map { mapper.readTree(it) }
                    .filter { it.path("kind").asText() == "sent" || it.path("kind").asText() == "message" }
                    .map { it.path("data") }
                    .firstOrNull { it.path("role").asText() == "user" }
                    ?.let(LiveWorld::textOf)
            }
        }
    }

    // ── What a batch did ────────────────────────────────────────────────────────────────────────────

    data class CanonRow(
        val id: Long, val kind: String, val statement: String, val quote: String?, val players: List<String>, val due: Instant?, val stakes: String?, val source: String,
    )

    data class InteractionRow(
        val id: Long, val player: String, val kind: String, val valence: Int, val summary: String, val quote: String?, val source: String, val evidence: List<Long>,
    )

    data class NoteRow(val id: Long, val kind: String, val text: String, val refs: List<Long>)

    data class ToolRow(val tool: String, val status: String, val args: String?, val result: String?)

    internal class BatchOutcome(
        val fixture: BatchFixture, val effort: String, val status: String, val error: String?, val reportText: String?, val rejected: Int, val writes: Int,
        val canon: List<CanonRow>, val interactions: List<InteractionRow>, val notes: List<NoteRow>, val toolCalls: List<ToolRow>, val calls: List<LiveCall>,
        val opening: String?, val lines: List<Pair<String, String>>, val knownRefs: List<Long>, val eventAt: Map<String, Long>,
    ) {
        /** The Chronicler's quotes that no line of the evening holds (a commitment: a Narrator line; an interaction: a player's). */
        fun inventedQuotes(): List<String> {
            val narrator = lines.filter { it.first == "narrator" || it.first == "operator" }.map { Quotes.normalize(it.second) }
            val players = lines.filter { it.first == "public" || it.first == "pray" }.map { Quotes.normalize(it.second) }
            val canonQuotes = canon.filter { it.source == "chronicler" }.mapNotNull { it.quote }.filterNot { quote -> narrator.any { Quotes.normalize(quote) in it } }
            val chatQuotes = interactions.filter { it.source == "chronicler" }.mapNotNull { it.quote }.filterNot { quote -> players.any { Quotes.normalize(quote) in it } }
            return canonQuotes.map { "canon « $it »" } + chatQuotes.map { "interaction « $it »" }
        }

        fun markdown(): String = buildString {
            append("### ${fixture.name} — batch $status${error?.let { " ($it)" }.orEmpty()}\n\n")
            append("${fixture.description}\n\n")
            append("Effort `$effort` · ${LiveCall.summary(calls)} · writes $writes · refused $rejected\n\n")
            append("Report of the Chronicler: ${reportText ?: "(none)"}\n\n")
            append("Canon written (${canon.size}):\n")
            canon.forEach { append("- C-${it.id} ${it.kind} → ${it.players.ifEmpty { listOf("all") }.joinToString()}${it.due?.let { due -> ", due ${due.toString().substring(11, 16)}" }.orEmpty()}${it.stakes?.let { s -> ", stakes « $s »" }.orEmpty()}: ${it.statement} · quote « ${it.quote} » (${it.source})\n") }
            append("\nInteractions written (${interactions.size}):\n")
            interactions.forEach { append("- I-${it.id} ${it.player} ${it.kind} ${it.valence}: ${it.summary}${it.quote?.let { q -> " · « $q »" }.orEmpty()} (${it.source}, evidence ${it.evidence.joinToString { e -> "e$e" }})\n") }
            append("\nNotes written (${notes.size}):\n")
            notes.forEach { append("- N-${it.id} ${it.kind}: ${it.text} (refs ${it.refs})\n") }
            append("\nTool calls of the Chronicler:\n")
            toolCalls.forEach { append("- `${it.tool}` ${it.status}: `${it.args?.take(600)}` → ${it.result?.lineSequence()?.firstOrNull()?.take(300)}\n") }
            append("\n<details><summary>What the Chronicler read</summary>\n\n```\n${opening ?: "(no transcript)"}\n```\n</details>\n\n")
            append(LiveCall.table(calls)).append("\n")
        }
    }

    /** E1 and E2 scores over several batches, with their detail for the report. */
    private class Score(val recall: Double, val precision: Double, val text: String)

    private object Scores {
        fun commitments(outcomes: List<BatchOutcome>): Score {
            val text = StringBuilder()
            var expected = 0
            var found = 0
            var written = 0
            var matched = 0
            outcomes.forEach { outcome ->
                val rows = outcome.canon.filter { it.source == "chronicler" }.toMutableList()
                written += rows.size
                outcome.fixture.expect.commitments.forEach { exp ->
                    expected++
                    val row = rows.firstOrNull { matches(it, exp) }
                    if (row != null) {
                        found++
                        matched++
                        rows.remove(row)
                        val due = exp.due?.let { want ->
                            val target = outcome.fixture.at("$want:00")
                            val got = row.due
                            if (got != null && Duration.between(target, got).abs() <= Duration.ofMinutes(10)) ", due ok (${got.toString().substring(11, 16)})" else ", due ${got?.toString()?.substring(11, 16) ?: "missing"} (expected $want)"
                        }.orEmpty()
                        text.append("- found ${exp.id}: C-${row.id} ${row.kind} « ${row.quote} »$due\n")
                    } else {
                        text.append("- **missed** ${exp.id} (${exp.kinds.joinToString("/")} → ${exp.players.joinToString().ifEmpty { "all" }})\n")
                    }
                }
                rows.forEach { text.append("- **extra** in ${outcome.fixture.name}: C-${it.id} ${it.kind} → ${it.players.joinToString().ifEmpty { "all" }} « ${it.quote} »: ${it.statement}\n") }
            }
            val recall = if (expected == 0) 1.0 else found.toDouble() / expected
            val precision = if (written == 0) 1.0 else matched.toDouble() / written
            text.insert(0, "Recall %d/%d = %.2f · precision %d/%d = %.2f (thresholds 0.90 and 0.90)\n\n".format(Locale.ROOT, found, expected, recall, matched, written, precision))
            return Score(recall, precision, text.toString())
        }

        private fun matches(row: CanonRow, exp: ExpectedCommitment): Boolean {
            if (row.kind !in exp.kinds) return false
            if (!exp.anyPlayers && row.players.sorted() != exp.players.sorted()) return false
            val said = Quotes.normalize(row.quote ?: row.statement)
            return exp.quote.any { Quotes.normalize(it) in said }
        }

        fun interactions(outcomes: List<BatchOutcome>): Score {
            val text = StringBuilder()
            var expected = 0
            var right = 0
            var rows = 0
            outcomes.forEach { outcome ->
                val left = outcome.interactions.filter { it.source == "chronicler" }.toMutableList()
                rows += left.size
                outcome.fixture.expect.interactions.forEach { exp ->
                    expected++
                    val event = exp.at?.let { outcome.eventAt[it] }
                    val row = left.firstOrNull { it.player == exp.player && event != null && event in it.evidence }
                        ?: left.firstOrNull { it.player == exp.player && it.kind in exp.kinds }
                    if (row == null) {
                        text.append("- **missed** ${exp.id} (${exp.player} ${exp.kinds.joinToString("/")}, ${exp.sign})\n")
                        return@forEach
                    }
                    left.remove(row)
                    val signOk = when (exp.sign) {
                        "negative" -> row.valence < 0
                        "positive" -> row.valence > 0
                        "nonnegative" -> row.valence >= 0
                        else -> error("unknown sign ${exp.sign}")
                    }
                    if (signOk) right++
                    val kindNote = if (row.kind in exp.kinds) "" else " (kind ${row.kind}, expected ${exp.kinds.joinToString("/")})"
                    text.append("- ${if (signOk) "found" else "**wrong sign**"} ${exp.id}: I-${row.id} ${row.kind} ${row.valence}$kindNote: ${row.summary}\n")
                }
                left.forEach { text.append("- extra in ${outcome.fixture.name}: I-${it.id} ${it.player} ${it.kind} ${it.valence}: ${it.summary}\n") }
            }
            val recall = if (expected == 0) 1.0 else right.toDouble() / expected
            text.insert(0, "Right sign %d/%d = %.2f (threshold 0.80); %d interactions written by the Chronicler\n\n".format(Locale.ROOT, right, expected, recall, rows))
            return Score(recall, 1.0, text.toString())
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    @Serializable
    data class BatchFixture(
        val name: String,
        val evals: List<String>,
        val description: String,
        val date: String,
        val chapter: Int,
        val start: String,
        val now: String,
        val players: List<String>,
        val known: List<KnownCanon> = emptyList(),
        val events: List<FixtureEvent>,
        val expect: Expect,
    ) {
        /** [time] (`HH:mm:ss`) on the fixture's date, UTC. */
        fun at(time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC)

        companion object {
            fun load(name: String): BatchFixture = JSON.decodeFromString(serializer(), resource("$name.json"))
        }
    }

    @Serializable
    data class KnownCanon(
        val at: String = "",
        val kind: String,
        val statement: String,
        val quote: String? = null,
        val players: List<String> = emptyList(),
        val said: String? = null,
    )

    @Serializable
    data class FixtureEvent(
        val at: String,
        val join: String? = null,
        val chat: String? = null,
        val pray: String? = null,
        val text: String? = null,
        val addressed: Double? = null,
        val tone: String? = null,
        val intent: String? = null,
        val manipulation: Double? = null,
        val narrator: String? = null,
        val to: List<String>? = null,
        val operator: String? = null,
        val tool: String? = null,
        val targets: List<String> = emptyList(),
        val result: String? = null,
        val hostile: Boolean = false,
        val grief: String? = null,
        val owner: String? = null,
        val block: String? = null,
        val count: Int = 0,
        val remembered: KnownCanon? = null,
    )

    @Serializable
    data class Expect(
        val commitments: List<ExpectedCommitment> = emptyList(),
        val interactions: List<ExpectedInteraction> = emptyList(),
        val coherence: String? = null,
    )

    @Serializable
    data class ExpectedCommitment(
        val id: String,
        val kinds: List<String>,
        val players: List<String>,
        val anyPlayers: Boolean = false,
        val quote: List<String>,
        val due: String? = null,
    )

    @Serializable
    data class ExpectedInteraction(val id: String, val player: String, val kinds: List<String>, val sign: String, val at: String? = null)

    @Serializable
    data class TicsFixture(
        val name: String,
        val evals: List<String>,
        val description: String,
        val players: List<String>,
        val history: List<HistoryLine>,
        val openers: List<String>,
        val phrases: List<String>,
        val wakes: List<Wake>,
    ) {
        companion object {
            fun load(): TicsFixture = JSON.decodeFromString(serializer(), resource("e6-tics.json"))
        }
    }

    @Serializable
    data class HistoryLine(val daysAgo: Int, val at: String, val text: String)

    @Serializable
    data class Wake(val player: String, val text: String, val channel: String = "public", val tone: String, val intent: String)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = false }

        /** [names], less those `NARRATOR2_LIVE_ONLY` (fixtures, comma-separated) leaves out; skips the test when none is left. */
        fun selected(vararg names: String): List<String> {
            val only = System.getenv("NARRATOR2_LIVE_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val kept = if (only == null) names.toList() else names.filter { it in only }
            assumeTrue(kept.isNotEmpty()) { "NARRATOR2_LIVE_ONLY leaves none of ${names.toList()}" }
            return kept
        }

        fun resource(name: String): String =
            LiveMemoryEvalTest::class.java.getResource("/narrator2/memory/eval/$name")?.readText() ?: error("no fixture $name")

        /** Lower case, one kind of apostrophe, single spaces: how replies are compared to the listed tics. */
        fun plain(text: String): String = text.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ").trim()
    }
}
