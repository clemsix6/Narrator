package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import java.nio.file.Files
import java.util.UUID

/**
 * The refusal of a resting opener in game (M6 §3.5, §15.2, M6-03), with no real Claude: the server's real watch of the
 * style learns four lines of the Director that open alike from the bus; the real Director, talking to a fake Anthropic
 * server ([ScriptedModel]), says a fifth: `say` refuses it (`Invalid arguments`, a decision `style opener_refused`), and
 * the same line said again in the same turn passes (one refusal a turn). Then a law's line that opens alike is said by
 * the engine, never checked, and the watch does not count it.
 *
 * It runs in a batch of its own (the `narrator2-gametest:style` test environment): it gives the Director a key, which
 * every test of the default batch expects asleep, and makes a law on the server's engine. It leaves everything as it
 * found it: no key, the Director disabled, no law. The four lines and its opener are its own (a random word): no other
 * test opens so.
 *
 * Owned by M6-03.
 */
class StyleGameTests {

    @GameTest(environment = STYLE_ENVIRONMENT, maxTicks = STYLE_MAX_TICKS)
    fun aRestingOpenerIsRefusedOnceAndALawsLineNever(helper: GameTestHelper) = helper.runScript(STYLE_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val fake = FakeAnthropic()
        val player = server.onServerThread { TestPlayer(helper, "sy_") }
        val model = ScriptedModel(player.name)
        repeat(40) { fake.enqueue(model::answer) }
        try {
            // 1. Four lines of the Director that open alike, from the bus: the opener rests.
            repeat(4) { i ->
                val line = "${model.opener} : la ligne d'essai numéro ${i + 1} du Narrateur."
                runtime.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, line, null, runtime.trace.nextId(IdKind.TURN), operator = null))
            }
            awaitCondition("the opener at rest") { runtime.style.restingOpener(model.line)?.count == 4 }

            // 2. The Director gets a key: the fake gateway's, with no heartbeat in the way.
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 }, "reflex": { "enabled": false } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key") { runtime.director.status().state != DirectorState.DISABLED }
            awaitQuiet(runtime, 30_000)

            // 3. A chat wakes it: its line is refused once, then said.
            chat(helper, player, "Narrateur, dis quelque chose ! ${model.wake}")
            val refused = awaitRequest(fake, "the refusal of the opener", 30_000) { isDirector(it) && results(it).any { REFUSAL in it } }
            val refusal = results(refused).single()
            check(refusal.startsWith("$REFUSAL 4 of your latest lines (« ${model.opener} »): open another way")) { "the refusal: $refusal" }
            val said = awaitRequest(fake, "the line said again", 30_000) { isDirector(it) && results(it).any { it.startsWith("ok: said to everyone") } }
            check(said.conversation == refused.conversation) { "the same chapter" }
            awaitCondition("${player.name} reads the line") { player.chat().any { model.line in it.content().string } }
            awaitQuiet(runtime, 30_000)
            check(player.chat().count { model.line in it.content().string } == 1) { "said once: ${player.chat().map { it.content().string }}" }
            val turn = journalRow(runtime, "SELECT turn_id FROM chat WHERE channel = 'narrator' AND text = ?", model.line)[0] as Long
            val calls = journalRow(
                runtime, "SELECT string_agg(status, ',' ORDER BY id) FROM tool_calls WHERE turn_id = ? AND tool = 'say'", turn,
            )[0]
            check(calls == "invalid,ok") { "the turn's two lines: $calls" }
            val decision = journalRow(runtime, "SELECT subject, conversation_id FROM engine_decisions WHERE kind = 'style' AND decision = 'opener_refused' AND turn_id = ?", turn)
            check(decision == listOf("« ${model.opener} »", refused.conversation)) { "the decision: $decision" }
            awaitCondition("the watch counts the line said") { runtime.style.restingOpener(model.line)?.count == 5 }

            // 4. A law whose line opens alike: the engine says it, unchecked, and the watch does not count it.
            runtime.journal.barrier() // the player's join row, which the law's players are checked against
            val law = ToolRunner(runtime).invoke(CreateRuleTool(), Caller(Role.DIRECTOR, "director-gametest-style", runtime.trace.nextId(IdKind.TURN), null), model.law)
            check(law.status == ToolStatus.OK) { "the law: ${law.text}" }
            chat(helper, player, "Voici le mot : ${model.word}.")
            awaitCondition("the law's line") { player.chat().any { model.lawLine in it.content().string } }
            awaitQuiet(runtime, 30_000)
            val lawTurn = journalRow(runtime, "SELECT turn_id IS NULL, count(*) OVER () FROM chat WHERE channel = 'narrator' AND text = ?", model.lawLine)
            check(lawTurn == listOf("true", 1L)) { "a line of the engine, without a turn: $lawTurn" }
            val refusals = journalRow(runtime, "SELECT count(*) FROM engine_decisions WHERE kind = 'style' AND decision = 'opener_refused' AND subject = ?", "« ${model.opener} »")
            check(refusals == listOf(1L)) { "one refusal in all: $refusals" }
            check(runtime.style.restingOpener(model.line)?.count == 5) { "the law's line is not the Director's: ${runtime.style.restingOpener(model.line)}" }

            // 5. The chapter closes with its summary (a test that wakes the Director closes its chapter, M3-19).
            operatorCommands(helper, "narrator chapter close")
            awaitCondition("the chapter closed with its summary", 45_000) {
                runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ?", refused.conversation!!)?.firstOrNull()?.toString()
                    ?.contains(ScriptedModel.SUMMARY) == true
            }
            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            // As found: no key, the Director asleep, no law of this test.
            Files.writeString(NarratorRuntime.configFile, "{}")
            runCatching { runtime.reload() }
            for (rule in runtime.mechanics.rules.list().filter { it.spec.statement == ScriptedModel.STATEMENT }) {
                runCatching { runtime.mechanics.rules.remove(rule.ref, "Levée à la fin d'un test.", CLEANUP) }
            }
            server.onServerThread { player.close() }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /** [player] says [text] in the public chat. */
    private suspend fun chat(helper: GameTestHelper, player: TestPlayer, text: String) {
        val server = helper.level.server
        server.onServerThread {
            val live = player.live
            server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
        }
    }

    /** The texts of the tool results [request] sends back. */
    private fun results(request: RecordedRequest): List<String> =
        lastUser(request).path("content").filter { it.path("type").asText() == "tool_result" }.map { it.resultText() }

    /**
     * Plays the model: each answer is chosen from what the request adds since the model last spoke. The wake-up's chat
     * gets [line] (whose opener rests); its refusal gets the same line again; the rest a plain text. The memory's jobs
     * get a plain text.
     */
    private class ScriptedModel(private val player: String) {
        /** A word of this run alone: letters only, so that it is neither a number nor a name. */
        private val tag = UUID.randomUUID().toString().filter { it in 'a'..'f' }.padEnd(6, 'e').take(6)
        val opener = "Avis $tag"
        val wake = "eveil-$tag"
        val word = "verbe$tag"
        val line = "$opener : le Narrateur parle encore, et il insiste."
        val lawLine = "$opener : la loi parle, et nul ne la refuse."
        val law = buildJsonObject {
            put("kind", "law")
            put("statement", STATEMENT)
            put("trigger", "chat")
            put(
                "when",
                buildJsonObject {
                    put("players", JsonArray(listOf(JsonPrimitive(player))))
                    put("words", JsonArray(listOf(JsonPrimitive(word))))
                },
            )
            val effect = buildJsonObject {
                put("tool", "say")
                put("at", "actor")
                put("args", buildJsonObject { put("text", lawLine) })
            }
            put("effects", JsonArray(listOf(effect)))
            put("minutes", 30)
        }.toString()

        fun answer(request: RecordedRequest): FakeResponse {
            val last = lastUser(request)
            val messages = request.json.path("messages")
            val spoke = (messages.size() - 1 downTo 0).firstOrNull { messages[it].path("role").asText() == "assistant" } ?: -1
            val text = (spoke + 1 until messages.size()).joinToString("\n") { messages[it].toString() }
            val say = buildJsonObject { put("text", line) }.toString()
            return when {
                isChronicler(request) || isDreamer(request) -> reply { text("Nothing to record.") }
                "<chapter_end chapter=" in text -> reply { text("Joueurs vus : $player. $SUMMARY") }
                last.hasToolResult() && REFUSAL in text -> reply { toolUse("say", say) }
                last.hasToolResult() -> reply { text("Fin du tour.") }
                wake in text -> reply { toolUse("say", say) }
                else -> reply { text("Rien à dire.") }
            }
        }

        companion object {
            const val STATEMENT = "Le Narrateur a décrété une loi d'essai du style."
            const val SUMMARY = "Résumé de chapitre du style."
        }
    }

    private companion object {
        /** The batch of this test: it gives the Director a key, and makes a law on the server's engine. */
        const val STYLE_ENVIRONMENT = "narrator2-gametest:style"

        /** At least two minutes of wall-clock time (see [runScript]). */
        const val STYLE_MAX_TICKS = 120_000

        /** How the refusal of a resting opener starts. */
        const val REFUSAL = "Invalid arguments: this line opens like"

        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")
    }
}
