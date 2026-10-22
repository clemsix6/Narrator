package io.github.clemsix6.narrator2.gametest

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.delay
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import java.nio.file.Files
import java.util.UUID

/**
 * The style assembled in game (M6 §3, §4.3, §15.2, task M6-17), with the reflexes on and no real Claude: the fake
 * Anthropic server ([ScriptedModel]) plays the reflexes' model (the chat's triage and the line review, requests without
 * `stream` on the session `narrator2-reflex`) and the Director.
 *
 * 1. Three lines of the Director, from the bus, are each reviewed `courtroom`: their labels go to `line_labels`.
 * 2. A chat wakes the Director (the triage says it is addressed), which says a line: the review labels it `courtroom`,
 *    dark 0.9, once the turn's last request went out.
 * 3. The next wake-up carries both: its situation's `Style:` line counts the four `courtroom` lines, and the note of tone
 *    `Engine: tone check: your line « … » reads dark` comes with it.
 * 4. A short line of `say` (under 25 code points) and a title are never reviewed.
 *
 * It runs in a batch of its own ([ENVIRONMENT]): it gives the Director a key and turns the reflexes on, which every test of
 * the default batch expects off; it leaves the settings as it found them and closes its chapter.
 */
class StyleAssemblyGameTests {

    @GameTest(environment = ENVIRONMENT, maxTicks = MAX_TICKS)
    fun aLineIsReviewedAndItsLabelsReachTheNextWakeUp(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val fake = FakeAnthropic()
        val player = server.onServerThread { TestPlayer(helper, "sa_") }
        val model = ScriptedModel(fake)
        repeat(80) { fake.enqueue(model::answer) }
        try {
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 },
                   | "reflex": { "enabled": true, "timeoutMs": 10000 }, "dashboard": { "enabled": false } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key, the reflexes with a model") {
                runtime.director.status().state != DirectorState.DISABLED && runtime.settings.get().reflex != null
            }
            awaitQuiet(runtime, 30_000)

            // 1. Three lines of the Director, from the bus: each reviewed, courtroom.
            for (line in model.earlier) {
                runtime.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, line, null, runtime.trace.nextId(IdKind.TURN), operator = null))
            }
            awaitCondition("the three earlier lines labeled") { labeled(runtime, model.tag) == 3L }

            // 2. A chat wakes the Director, which says a line: reviewed dark once the turn's last request went out.
            chat(server, player, "Narrateur, tu juges qui ce soir ? ${model.wake}")
            val triage = awaitRequest(fake, "the chat's triage") { isReflex(it) && model.wake in userText(it) }
            check(triage.json.path("stream").isMissingNode || !triage.json.path("stream").asBoolean()) { "the reflexes ask without stream" }
            awaitCondition("the line labeled", 30_000) { labeled(runtime, model.tag) == 4L }
            val labels = journalRow(
                runtime, "SELECT l.register, l.dark::VARCHAR, l.source FROM line_labels l JOIN chat c ON c.id = l.event_id WHERE c.channel = 'narrator' AND c.text = ?",
                model.line,
            )
            check(labels == listOf("courtroom", "0.9", "reflex:claude-haiku-5-5")) { "the line's labels: $labels" }
            awaitCondition("the decision style tone_noted") {
                runCatchingJournal(runtime, "SELECT count(*) FROM engine_decisions WHERE kind = 'style' AND decision = 'tone_noted' AND reason LIKE ?", "%${model.tag}%")
                    ?.single() == 1L
            }
            awaitQuiet(runtime, 30_000)
            val said = fake.requests.filter { isDirector(it) }
            check(said.none { NOTE in textSince(it) }) { "the note comes after the turn of its line" }

            // 3. The next wake-up: the Style: line of its situation, and the note of tone.
            chat(server, player, "Narrateur, et maintenant ? ${model.again}")
            val next = awaitRequest(fake, "the next wake-up", 30_000) { isDirector(it) && model.again in textSince(it) }
            val told = textSince(next)
            check(Regex("Style: [^\\n]*your last 4 lines: courtroom 4").containsMatchIn(told)) { "the Style: line of the situation: $told" }
            check("$NOTE your line « ${model.line} » reads dark" in told) { "the note of tone: $told" }

            // 4. A short line and a title: never reviewed.
            awaitCondition("the short line and the title shown") {
                player.chat().any { ScriptedModel.SHORT in it.content().string } &&
                    runCatchingJournal(runtime, "SELECT count(*) FROM chat WHERE channel = 'narrator' AND text LIKE ?", "[title] ${ScriptedModel.TITLE}%")?.single() == 1L
            }
            awaitQuiet(runtime, 30_000)
            delay(2_000)
            val reviews = fake.requests.filter { isReflex(it) && userText(it).startsWith(REVIEW) }
            check(reviews.size == 4) { "four lines reviewed: ${reviews.map { userText(it).lines().last() }}" }
            check(reviews.none { ScriptedModel.SHORT in it.lastLine() || ScriptedModel.TITLE in it.lastLine() }) { "neither the short line nor the title" }
            check(labeled(runtime, model.tag) == 4L) { "no label more" }

            // 5. The chapter closes with its summary (a test that wakes the Director closes its chapter).
            val chapter = next.conversation!!
            operatorCommands(helper, "narrator chapter close")
            awaitCondition("the chapter closed with its summary", 45_000) {
                runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ?", chapter)?.firstOrNull()?.toString()
                    ?.contains(ScriptedModel.SUMMARY) == true
            }
            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            // As found: the Director asleep, the reflexes off.
            putTheDirectorToSleep(helper, runtime)
            server.onServerThread { player.close() }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /** The lines of this run (they hold its [tag]) that the review labeled. */
    private suspend fun labeled(runtime: NarratorRuntime, tag: String): Long =
        runCatchingJournal(runtime, "SELECT count(*) FROM line_labels l JOIN chat c ON c.id = l.event_id WHERE c.text LIKE ?", "%$tag%")?.single() as Long? ?: 0L

    /** [player] says [text] in the public chat. */
    private suspend fun chat(server: net.minecraft.server.MinecraftServer, player: TestPlayer, text: String) = server.onServerThread {
        val live = player.live
        server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
    }

    /** The line a review request labels: its last line, `<line>…</line>`. */
    private fun RecordedRequest.lastLine(): String = userText(this).lines().last()

    /**
     * Plays the reflexes' model and the Director. The reflexes: the chat's triage says addressed; the review labels each
     * line `courtroom`, and [line] dark 0.9 once the Director's request after its `say` went out (so the note of tone can
     * only come with the next wake-up). The Director: [wake] gets `say` [line]; [again] gets a short line and a title; the
     * rest a plain text. The memory's jobs get a plain text.
     */
    private class ScriptedModel(private val fake: FakeAnthropic) {
        /** A word of this run alone: letters only, neither a number nor a name. */
        val tag = UUID.randomUUID().toString().filter { it in 'a'..'f' }.padEnd(6, 'e').take(6)
        val wake = "eveil-$tag"
        val again = "encore-$tag"
        val earlier = listOf(
            "Attendu que le prévenu $tag creuse sous sa propre maison, la cour prend note.",
            "Considérant que le prévenu $tag saute dans chaque trou, la cour soupire.",
            "Vu le dossier du prévenu $tag, la cour réclame un plan de la mine.",
        )
        val line = "La cour déclare le prévenu $tag coupable de jardinage nocturne aggravé."

        fun answer(request: RecordedRequest): FakeResponse {
            if (isReflex(request)) return reflex(request)
            val text = textSince(request)
            return when {
                isChronicler(request) || isDreamer(request) -> reply { text("Nothing to record.") }
                "<chapter_end chapter=" in text -> reply { text("Joueurs vus : un prévenu. $SUMMARY") }
                lastUser(request).hasToolResult() -> reply { text("Fin du tour.") }
                wake in text -> reply { toolUse("say", """{"text": "$line"}""") }
                again in text -> reply {
                    toolUse("say", """{"text": "$SHORT"}""")
                    toolUse("show_title", """{"title": "$TITLE"}""")
                }
                else -> reply { text("Rien à dire.") }
            }
        }

        private fun reflex(request: RecordedRequest): FakeResponse {
            val user = userText(request)
            return when {
                user.startsWith(REVIEW) -> {
                    val dark = line in user.lines().last()
                    if (dark) awaitTurnEnd()
                    reply { text(labels(dark = if (dark) 0.9 else 0.0)) }
                }
                user.startsWith("Label this chat message.") -> reply { text(ADDRESSED) }
                else -> reply { text("""{"worth": 0.1, "hook": "other"}""") }
            }
        }

        /** Waits (8 s at most) for the Director's request that carries the result of its `say` [line]. */
        private fun awaitTurnEnd() {
            val end = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < end) {
                if (fake.requests.any { isDirector(it) && lastUser(it).hasToolResult() && line in it.json.path("messages").toString() }) return
                Thread.sleep(20)
            }
        }

        private fun labels(dark: Double) =
            """{"reason": "La cour juge en style de tribunal.", "register": "courtroom", "dark": $dark, "cruel": 0.0, "flat": 0.0, "out_of_character": 0.0, "obscure": 0.0, "repeats": 0.0, "crutches": []}"""

        companion object {
            /** A summary of 100 characters or more: a shorter one is no summary, and asked again (M6-FIX-9). */
            const val SUMMARY = "Résumé de chapitre de l'assemblage du style : la cour a jugé un prévenu pour jardinage nocturne, puis s'est retirée pour délibérer."
            const val SHORT = "Silence, prévenu."
            const val TITLE = "LA COUR DÉLIBÈRE"

            const val ADDRESSED =
                """{"reason": "Il interroge le Narrateur.", "addressed": 0.95, "tone": "respectful", "tone_confidence": 0.9, "intent": "question", "manipulation": 0.0, """ +
                    """"politeness": 0.5, "boastfulness": 0.0, "helpfulness": 0.0, "aggressiveness": 0.0, "humor": 0.1}"""
        }
    }

    private companion object {
        /** The batch of this test: it gives the Director a key and turns the reflexes on. */
        const val ENVIRONMENT = "narrator2-gametest:style_assembly"

        /** At least two minutes of wall-clock time (see [runScript]). */
        const val MAX_TICKS = 120_000

        /** How a request of the line review starts. */
        const val REVIEW = "Label this line of the Narrator."

        /** How the note of tone starts, as the Director tells it. */
        const val NOTE = "Engine: tone check:"
    }
}

/** A request of the reflexes (chat triage, heartbeat, line review): one message, no conversation, the reflexes' session. */
fun isReflex(request: RecordedRequest) = request.session == ReflexClient.SESSION

/** The text of the (single) user message of a reflexes' request. */
fun userText(request: RecordedRequest): String = messageText(request.json.path("messages")[0])

/** The texts of the messages [request] adds since the model last spoke: the wake-up, tool results, notes of the engine. */
fun textSince(request: RecordedRequest): String {
    val messages = request.json.path("messages")
    val spoke = (messages.size() - 1 downTo 0).firstOrNull { messages[it].path("role").asText() == "assistant" } ?: -1
    return (spoke + 1 until messages.size()).joinToString("\n") { messageText(messages[it]) }
}

/** A message's text: its content when a string, else its blocks' texts and tool results. */
fun messageText(message: JsonNode): String {
    val content = message.path("content")
    if (content.isTextual) return content.asText()
    return content.joinToString("\n") { block -> if (block.path("type").asText() == "tool_result") block.resultText() else block.path("text").asText() }
}
