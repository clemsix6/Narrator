package io.github.clemsix6.narrator2.gametest

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The M2 acceptance in game (M1_M2 §17.3, T22): the real Director of the test server, with its real
 * tools, salience, chapters, workers and commands, talking to a fake Anthropic server that plays the
 * model ([ScriptedModel]). No real Claude.
 *
 * It runs in a batch of its own (the `narrator2-gametest:director` test environment): it replaces the
 * settings to give the Director a key, and every other test of the default batch expects a Director
 * that sleeps. It leaves the settings as it found them: no key, the Director disabled.
 *
 * Owned by T22. Shared helpers: `GameTestSupport.kt`, and [runScript] here (used by `LiveDirectorGameTests`).
 */
class DirectorEndToEndTests {

    /**
     * A player's chat wakes the Director, which reads the Journal with `sql` and answers with `say`; its
     * first message is the chapter's briefing, with the world and the player's live position. Then
     * `why`, `trace` and `status` explain that real turn; a question goes to the Analyst, whose Oracle
     * tools read the live player, and whose report wakes the Director; an operator pauses the Director, a
     * prayer waits, and the resume tells it with the silence; a chapter close asked by an operator ends
     * with a summary on the same session, and the next chapter's briefing carries it.
     */
    @GameTest(environment = DIRECTOR_ENVIRONMENT, maxTicks = SCRIPT_MAX_TICKS)
    fun aChatWakesTheDirectorWhoAnswersWithItsRealTools(helper: GameTestHelper) = helper.runScript(SCRIPT_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val fake = FakeAnthropic()
        val speaker = server.onServerThread { TestPlayer(helper, "de_") }
        val model = ScriptedModel(speaker.name)
        repeat(40) { fake.enqueue(model::answer) }
        try {
            // The Director gets a key: the fake gateway's, with no heartbeat in the way.
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key") { runtime.director.status().state != DirectorState.DISABLED }
            awaitQuiet(runtime)

            // 1. An addressed chat: sql, then say. The first request of the chapter carries the briefing.
            val greeting = "Narrateur, tu m'entends ? ${model.greet}"
            server.onServerThread {
                val player = speaker.player
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, greeting), player, ChatType.bind(ChatType.CHAT, player))
            }
            val wake = awaitRequest(fake, "the greeting's wake-up") { isDirector(it) && model.greet in lastUser(it).wakeText() }
            val chapterSession = wake.header("X-Session-ID")!!
            awaitQuiet(runtime)
            val answered = fake.requests.dropWhile { it !== wake }.first { it.header("X-Session-ID") == chapterSession && lastUser(it).hasToolResult() }
            check("CHAT ${speaker.name} (to you" in lastUser(wake).wakeText()) { "the addressed chat line: ${lastUser(wake).wakeText()}" }
            check("<situation now=" in lastUser(wake).wakeText()) { "the situation follows the events" }
            val opening = fake.requests.first { it.header("X-Session-ID") == chapterSession }
            val briefing = opening.json.path("messages")[0].path("content")[0].path("text").asText()
            check(briefing.startsWith("<briefing chapter=\"")) { "the chapter opens with its briefing: ${briefing.take(200)}" }
            check("<world day=\"" in briefing) { "the world line, read on the server thread: ${briefing.take(600)}" }
            check("live positions unavailable" !in briefing) { "the live positions, read on the server thread: $briefing" }
            check(Regex("${speaker.name}: overworld -?\\d+ -?\\d+ -?\\d+").containsMatchIn(briefing)) { "the live position of ${speaker.name}: $briefing" }
            val results = lastUser(answered).path("content").filter { it.path("type").asText() == "tool_result" }.map { it.resultText() }
            check(results.size == 2) { "two tool results: $results" }
            check("public|$greeting" in results[0]) { "sql reads the greeting in the Journal: ${results[0]}" }
            check(results[1].startsWith("ok: said to everyone")) { "say: ${results[1]}" }
            awaitCondition("${speaker.name} reads the Narrator's line") { speaker.chat().any { it.content().string == "[Narrateur] ${ScriptedModel.GREETING_LINE}" } }

            val turn = journalRow(runtime, "SELECT turn_id FROM chat WHERE channel = 'narrator' AND text = ?", ScriptedModel.GREETING_LINE)[0] as Long
            val row = journalRow(runtime, "SELECT trigger, outcome, llm_calls, tool_calls FROM turns WHERE turn_id = ?", turn)
            check(row == listOf("chat", "acted", 2L, 2L)) { "turn $turn: $row" }

            // 2. why, trace and status on that real turn.
            val output = operatorCommands(helper, "narrator why", "narrator why ${speaker.name}", "narrator trace turn $turn", "narrator trace turn $turn 2", "narrator status")
            awaitCondition("the answers of why, trace and status") {
                output.lines.any { it.startsWith("Usage (24 h): Claude ") } && output.lines.any { it.startsWith("[Narrator] turn #$turn") } &&
                    output.lines.any { it.startsWith("Told in turn #$turn at wake-up") }
            }
            val lines = output.lines.toList()
            check(lines.any { it == "Thought: ${ScriptedModel.THOUGHT}" }) { "why shows the thinking summary: $lines" }
            check(lines.any { it.startsWith("Did: say") && "ok: said to everyone" in it }) { "why shows the say: $lines" }
            check(lines.any { it == "Said to everyone: \"${ScriptedModel.GREETING_LINE}\"" }) { "why shows the line: $lines" }
            check(lines.any { "CHAT ${speaker.name}" in it && model.greet in it }) { "trace turn shows the chat that woke it: $lines" }
            check(lines.any { it.trimStart().startsWith("→ sql") } && lines.any { it.trimStart().startsWith("→ say") }) { "trace turn shows both tools: $lines" }
            val calls = Regex("Usage \\(24 h\\): Claude (\\d+) calls").find(lines.joinToString("\n"))?.groupValues?.get(1)?.toInt() ?: 0
            // The greeting's turn made 2 calls; earlier batches may add some, but the batches run in no fixed order.
            check(calls >= 2) { "status counts the fake gateway's calls: $lines" }
            check(lines.any { it.startsWith("Director: ") } && lines.any { it.startsWith("Chapter: #") }) { "status follows the Director: $lines" }

            // 3. A question for the Analyst: delegate, the Analyst's Oracle on the live player, its report wakes the Director.
            val tasksBefore = fake.requests.count { isAnalyst(it) }
            server.onServerThread {
                val player = speaker.player
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, "Narrateur, où suis-je ? ${model.where}"), player, ChatType.bind(ChatType.CHAT, player))
            }
            val report = awaitRequest(fake, "the Director reads the Analyst's report") { isDirector(it) && "TASK T-" in lastUser(it).wakeText() }
            val analyst = fake.requests.filter { isAnalyst(it) }.drop(tasksBefore)
            check(analyst.size == 2) { "two requests of the Analyst: ${analyst.size}" }
            val analystTools = analyst[0].json.path("tools").map { it.path("name").asText() }
            check(analystTools == ANALYST_TOOLS) { "the Analyst's tools: $analystTools" }
            val oracle = lastUser(analyst[1]).path("content")[0]
            check(!oracle.path("is_error").asBoolean(false) && "overworld" in oracle.resultText()) { "inspect_player on the live player: $oracle" }
            check("analyst done" in lastUser(report).wakeText() && ScriptedModel.REPORT in lastUser(report).wakeText()) { "the TASK line: ${lastUser(report).wakeText()}" }
            awaitQuiet(runtime)

            // 4. Pause: a prayer waits. Resume: it is told, with the silence note.
            val pause = operatorCommands(helper, "narrator pause")
            awaitCondition("the Director pauses") { runtime.director.status().state == DirectorState.PAUSED }
            check(pause.lines.any { it.startsWith("[Narrator] paused") }) { "pause: ${pause.lines}" }
            server.onServerThread { server.commands.performPrefixedCommand(speaker.player.createCommandSourceStack(), "pray Pitié, grand Narrateur ${model.prayer}") }
            val beforeResume = fake.requests.size
            delay(3_000)
            check(fake.requests.size == beforeResume) { "a paused Director sends nothing" }
            operatorCommands(helper, "narrator resume")
            val resumed = awaitRequest(fake, "the prayer after the resume") { isDirector(it) && model.prayer in lastUser(it).wakeText() }
            check("PRAY ${speaker.name}" in lastUser(resumed).wakeText()) { "the prayer: ${lastUser(resumed).wakeText()}" }
            val note = resumed.json.path("messages").last()
            check(note.path("role").asText() == "system" && "(paused by an operator)" in note.path("content").asText()) { "the silence note: $note" }
            awaitQuiet(runtime)
            val trigger = journalRow(runtime, "SELECT trigger FROM turns WHERE conversation_id = ? ORDER BY turn_id DESC LIMIT 1", chapterSession)
            check(trigger == listOf("pray")) { "the resumed turn's trigger: $trigger" }

            // 5. Chapter close: a summary on the same session at the quiet moment, carried by the next briefing.
            val close = operatorCommands(helper, "narrator chapter close")
            check(close.lines.any { "closes at the next quiet moment" in it }) { "chapter close: ${close.lines}" }
            val summaryRequest = awaitRequest(fake, "the summary request", timeoutMs = 45_000) { "<chapter_end chapter=" in lastUser(it).wakeText() }
            check(summaryRequest.header("X-Session-ID") == chapterSession) { "the summary is asked on the chapter's own session" }
            awaitCondition("the chapter is closed with its summary") {
                runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ?", chapterSession)?.firstOrNull()?.toString()?.contains(ScriptedModel.SUMMARY) == true
            }
            server.onServerThread {
                val player = speaker.player
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, "Narrateur, encore là ? ${model.again}"), player, ChatType.bind(ChatType.CHAT, player))
            }
            val next = awaitRequest(fake, "the next chapter's first request") { isDirector(it) && model.again in lastUser(it).wakeText() }
            check(next.header("X-Session-ID") != chapterSession) { "a new chapter, a new session" }
            val nextBriefing = next.json.path("messages")[0].path("content")[0].path("text").asText()
            check(nextBriefing.startsWith("<briefing chapter=\"") && ScriptedModel.SUMMARY in nextBriefing) { "the briefing carries the summary: ${nextBriefing.take(400)}" }
            check("NARRATOR→all: \"${ScriptedModel.GREETING_LINE}\"" in nextBriefing) { "the briefing's chat has the Narrator's line: $nextBriefing" }
            awaitQuiet(runtime)

            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            // As found: no key, the Director asleep.
            Files.writeString(NarratorRuntime.configFile, "{}")
            runCatching { runtime.reload() }
            server.onServerThread { speaker.close() }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /**
     * Plays the model for the scenario above, whatever the order in which the Director's and the
     * Analyst's requests arrive: each answer is chosen from the request itself.
     */
    private class ScriptedModel(private val player: String) {
        private val id = UUID.randomUUID().toString().take(6)
        val greet = "salut-$id"
        val where = "ou-$id"
        val prayer = "priere-$id"
        val again = "encore-$id"

        fun answer(request: RecordedRequest): FakeResponse {
            val last = lastUser(request)
            val text = last.wakeText()
            return when {
                isAnalyst(request) && !last.hasToolResult() ->
                    reply { toolUse("inspect_player", """{"player": "$player", "include": ["position"]}""") }
                isAnalyst(request) -> reply { text("Réponse : $player est en overworld. $REPORT") }
                "<chapter_end chapter=" in text -> reply { text("Joueurs vus : $player. $SUMMARY") }
                last.hasToolResult() -> reply { text("Fin du tour.") }
                greet in text -> reply {
                    thinking(THOUGHT)
                    toolUse("sql", """{"query": "SELECT channel, text FROM chat_log WHERE player = '$player' ORDER BY ts DESC, id DESC LIMIT 1"}""")
                    toolUse("say", """{"text": "$GREETING_LINE"}""")
                }
                where in text -> reply {
                    toolUse("delegate", """{"role": "analyst", "brief": "Where is $player right now? Read the live player with the Oracle."}""")
                    toolUse("say", """{"text": "Un instant, je consulte mes archives.", "to": ["$player"]}""")
                }
                "TASK T-" in text -> reply { toolUse("say", """{"text": "Mes archives disent : overworld.", "to": ["$player"]}""") }
                prayer in text -> reply { toolUse("say", """{"text": "Prière reçue, dossier ouvert.", "to": ["$player"]}""") }
                again in text -> reply { toolUse("say", """{"text": "Toujours là, hélas pour toi."}""") }
                else -> reply { text("Rien à dire.") }
            }
        }

        companion object {
            const val THOUGHT = "A greeting: check what the player said, then answer in public."
            const val GREETING_LINE = "Je t'entends, mortel. Le ciel a de l'oreille."
            const val REPORT = "Rapport de l'Analyste."
            const val SUMMARY = "Résumé de chapitre de recette."
        }
    }
}

/** The test environment of [DirectorEndToEndTests]: a batch of its own, apart from the tests that expect a sleeping Director. */
const val DIRECTOR_ENVIRONMENT = "narrator2-gametest:director"

/** Budget of a script test: at least two minutes of wall-clock time (see [runScript]). */
private const val SCRIPT_MAX_TICKS = 120_000

private val ANALYST_TOOLS = listOf("area_info", "inspect_player", "list_players", "locate", "player_stats", "scan_area", "sql")

private val scriptLog = LoggerFactory.getLogger("narrator2-gametest")

/**
 * Runs [script] off the server thread, as a story told in real time, and ends the test with it: success
 * when it returns, failure with its first error (Kotlin's `check` is the assertion). Steps that touch the
 * game go through `server.onServerThread`. The test's budget is [maxTicks], at least as many
 * milliseconds: each tick that waits for the script sleeps 1 ms ([thenAwait]).
 */
fun GameTestHelper.runScript(maxTicks: Int, script: suspend CoroutineScope.() -> Unit) {
    val outcome = AtomicReference<Result<Unit>?>()
    val job = CoroutineScope(Dispatchers.IO + CoroutineName("narrator2-gametest-script")).launch {
        val result = runCatching { coroutineScope { script() } }
        result.exceptionOrNull()?.let { scriptLog.error("the script of the test failed", it) }
        outcome.set(result)
    }
    runAfterDelay((maxTicks - 1).toLong()) { job.cancel() }
    startSequence()
        .thenAwait { assertTrue(outcome.get() != null, "the script is still running") }
        .thenExecute { outcome.get()!!.exceptionOrNull()?.let { fail("${it.javaClass.simpleName}: ${it.message}") } }
        .thenSucceed()
}

/** Waits until [condition] holds, checking every 50 ms; fails after [timeoutMs]. */
suspend fun awaitCondition(what: String, timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
        delay(50)
    }
}

/**
 * Waits until the Director has nothing running nor due: no turn, and idle (not `WAITING` for a wake-up).
 * Its inbox may still hold events that wake nobody (`NEXT`): they wait for the next wake-up.
 */
suspend fun awaitQuiet(runtime: NarratorRuntime, timeoutMs: Long = 20_000) = awaitCondition("a quiet Director", timeoutMs) {
    val status = runtime.director.status()
    status.turnId == null && status.state == DirectorState.IDLE
}

/** Runs [commands] as the server console, one after the other on the server thread; their answers go to the returned output. */
suspend fun operatorCommands(helper: GameTestHelper, vararg commands: String): CommandOutput {
    val server = helper.level.server
    val output = CommandOutput()
    server.onServerThread {
        val source = server.createCommandSourceStack().withSource(output)
        commands.forEach { server.commands.performPrefixedCommand(source, it) }
    }
    return output
}

/** The first row of [sql] in the Journal, once what came before is written; its numbers as `Long`. */
suspend fun journalRow(runtime: NarratorRuntime, sql: String, vararg params: Any): List<Any?> =
    runCatchingJournal(runtime, sql, *params) ?: error("no row for: $sql ${params.toList()}")

/** Like [journalRow], null when there is no row. */
suspend fun runCatchingJournal(runtime: NarratorRuntime, sql: String, vararg params: Any): List<Any?>? {
    runtime.journal.barrier()
    return runtime.journal.read(5_000) { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else (1..rows.metaData.columnCount).map { column -> rows.getObject(column)?.let { if (it is Number) it.toLong() else it.toString() } }
            }
        }
    }
}

/** Waits for a request of the fake gateway that [accept] picks, and returns the last such one. */
suspend fun awaitRequest(fake: FakeAnthropic, what: String, timeoutMs: Long = 15_000, accept: (RecordedRequest) -> Boolean): RecordedRequest {
    awaitCondition(what, timeoutMs) { fake.requests.any(accept) }
    return fake.requests.last(accept)
}

fun isDirector(request: RecordedRequest) = request.header("X-Session-ID").orEmpty().startsWith("director-")

fun isAnalyst(request: RecordedRequest) = request.header("X-Session-ID").orEmpty().startsWith("analyst-")

/** The last `user` message of [request]. */
fun lastUser(request: RecordedRequest): JsonNode = request.json.path("messages").last { it.path("role").asText() == "user" }

/**
 * The last text block of a message: the wake-up (`<events>`, `<situation>`) after a chapter's briefing,
 * the `<new_events>` after tool results, or the `<chapter_end>` of a summary request; empty when none.
 */
fun JsonNode.wakeText(): String {
    val content = path("content")
    return if (content.isTextual) content.asText() else content.lastOrNull { it.path("type").asText() == "text" }?.path("text")?.asText().orEmpty()
}

fun JsonNode.hasToolResult(): Boolean = path("content").any { it.path("type").asText() == "tool_result" }

/** The text of a `tool_result` block: its content, a string or text blocks. */
fun JsonNode.resultText(): String {
    val content = path("content")
    return if (content.isTextual) content.asText() else content.joinToString("\n") { it.path("text").asText() }
}
