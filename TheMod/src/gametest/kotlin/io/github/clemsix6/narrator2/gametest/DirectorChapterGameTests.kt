package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.delay
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import java.nio.file.Files

/**
 * The opening of a chapter on an arrival, never on a departure nor on an empty server (M6-18, finding 4; task
 * M6-FIX-11), with the real Director, salience, memory and commands of the test server, talking to a fake Anthropic server
 * ([ScriptedModel]). No real Claude.
 *
 * The evening of M6-18: the console closes the chapter, then every player leaves; their `LEAVE` opened a chapter on the
 * empty server (≈ 0.21 $ of cache written to answer « Fin. »), and the players back 30 s later arrived in it, mute. Here
 * the departures open nothing, and the players who come back get the opening: a new chapter, its briefing, their `JOIN`.
 *
 * It runs in a batch of its own ([ENVIRONMENT]): it gives the Director a key and needs a server it alone fills with
 * players. It leaves the settings as it found them: no key, no player, no chapter open.
 */
class DirectorChapterGameTests {

    @GameTest(environment = ENVIRONMENT, maxTicks = MAX_TICKS)
    fun departuresAfterAClosedChapterOpenNoneAndTheReturnGetsTheOpening(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val fake = FakeAnthropic()
        val model = ScriptedModel()
        repeat(60) { fake.enqueue(model::answer) }
        // The players of the evening; closing one removes whoever plays under its name, back or not.
        val first = mutableListOf<TestPlayer>()
        try {
            check(runtime.players.online.isEmpty()) { "the batch needs an empty server: ${runtime.players.online.map { it.ref.name }}" }
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 }, "reflex": { "enabled": false },
                   | "dashboard": { "enabled": false } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key") { runtime.director.status().state != DirectorState.DISABLED }
            awaitQuiet(runtime)

            // An evening: two players arrive, and their arrival opens a chapter.
            server.onServerThread { repeat(2) { first += TestPlayer(helper, "dc_") } }
            val (a, b) = first.map { it.name }
            val opening = awaitRequest(fake, "the first chapter's opening", timeoutMs = 30_000) { isDirector(it) && "JOIN $a" in lastUser(it).wakeText() }
            val closed = opening.conversation!!
            awaitQuiet(runtime, 30_000)

            // The console closes the chapter, then everyone leaves.
            operatorCommands(helper, "narrator chapter close")
            awaitCondition("the chapter $closed closed with its summary", 45_000) {
                runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ? AND ended_at IS NOT NULL", closed)
                    ?.single()?.toString()?.contains(ScriptedModel.SUMMARY) == true
            }
            val before = fake.requests.count(::isDirector)
            server.onServerThread { first.forEach { it.close() } }
            awaitCondition("the server is empty") { runtime.players.online.isEmpty() }
            // Longer than a salient event waits to wake the Director (3 s after the last one, 10 s at most).
            delay(12_000)
            val sent = fake.requests.filter(::isDirector).drop(before)
            check(sent.isEmpty()) { "the departures opened a chapter on an empty server: ${sent.map { lastUser(it).wakeText().take(300) }}" }
            val status = runtime.director.status()
            check(status.chapter == null && status.state == DirectorState.IDLE && status.reason == "nobody online") { "the Director waits for an arrival: $status" }

            // They come back: their arrival opens a new chapter, with its briefing and their JOIN, and the opening speaks.
            server.onServerThread { first.forEach { rejoin(helper, it) } }
            val reopening = awaitRequest(fake, "the opening of the players' return", timeoutMs = 30_000) {
                isDirector(it) && it.conversation != closed && "JOIN $a" in lastUser(it).wakeText()
            }
            val briefing = reopening.json.path("messages")[0].path("content")[0].path("text").asText()
            check(briefing.startsWith("<briefing chapter=\"")) { "the return opens a chapter, with its briefing: ${briefing.take(300)}" }
            check(ScriptedModel.SUMMARY in briefing) { "the briefing carries the summary of the closed chapter: ${briefing.take(600)}" }
            val events = lastUser(reopening).wakeText()
            check("JOIN $b" in events && "LEAVE" !in events) { "the arrivals, and no departure kept for the opening: $events" }
            val said = awaitRequest(fake, "the opening's line") { it.conversation == reopening.conversation && lastUser(it).hasToolResult() }
            val result = lastUser(said).path("content").first { it.path("type").asText() == "tool_result" }.resultText()
            check(result.startsWith("ok: said to everyone (2 online)")) { "the opening speaks to the players back: $result" }
            awaitQuiet(runtime, 30_000)

            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            putTheDirectorToSleep(helper, runtime)
            runCatching { server.onServerThread { first.forEach { it.close() } } }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /** [player], who left, joins again as a new player object, as a client reconnecting does. Server thread. */
    private fun rejoin(helper: GameTestHelper, player: TestPlayer): ServerPlayer {
        val server = helper.level.server
        val profile = GameProfile(player.uuid, player.name)
        val back = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, back, CommonListenerCookie.createInitial(profile, false))
        return back
    }

    /**
     * Plays the model: a chapter's first request is answered with a line when it tells an arrival, any other turn ends
     * at once, a summary is a summary; the memory's jobs get a plain text.
     */
    private class ScriptedModel {
        fun answer(request: RecordedRequest): FakeResponse {
            val last = lastUser(request)
            val text = last.wakeText()
            val opening = request.json.path("messages").count { it.path("role").asText() == "user" } == 1
            return when {
                !isDirector(request) -> reply { text("Nothing to record.") }
                "<chapter_end chapter=" in text -> reply { text(SUMMARY) }
                last.hasToolResult() -> reply { text("Fin.") }
                opening && "JOIN " in text -> reply { toolUse("say", """{"text": "$LINE"}""") }
                else -> reply { text("Fin.") }
            }
        }

        companion object {
            const val LINE = "Revoilà les fuyards. Le désert vous a gardé une place."
            const val SUMMARY = "Résumé de chapitre de recette. Faits marquants : deux arrivées. Interventions du Narrateur : un accueil. Engagements en cours : aucun."
        }
    }

    private companion object {
        const val ENVIRONMENT = "narrator2-gametest:director_chapters"

        /** At least two and a half minutes (see [runScript]): two openings, a summary, 12 s of an empty server. */
        const val MAX_TICKS = 150_000
    }
}
