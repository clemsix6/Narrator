package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.util.UUID

/**
 * The storyteller's programme end to end (M6 §5.6, §6.3, §7, §15.2, task M6-17), with no real Claude: the real memory,
 * Dreamer, storyteller, Director and reflexes, talking to a fake Anthropic server ([ScriptedModel]).
 *
 * 1. A player is online. `/narrator memory dream`: the Dreamer's steps (its Grimoire rewritten), then the storyteller's
 *    programme writes two ideas, signed `storyteller` (`ideas.source`, `memory_log`): a challenge for the player, a speech
 *    for everyone.
 * 2. The briefing of the next chapter shows them in `<programme>`.
 * 3. The player mines: a heartbeat (worth 0.9 by the reflexes) carries the challenge (`Idea: P-…`), dealt (`recalls`
 *    `heartbeat`); the Director answers with `inspire`, which deals the speech (`recalls` `inspire`).
 *
 * It runs in a batch of its own ([ENVIRONMENT]): it gives the Director a key, heartbeats every minute and the reflexes,
 * which every test of the default batch expects off. It leaves the settings as it found them and closes its chapters;
 * its two ideas stay open (no test reads the programme but this one; they expire after 48 hours of play).
 */
class ProgrammeEndToEndTests {

    @GameTest(environment = ENVIRONMENT, maxTicks = MAX_TICKS)
    fun aDreamWritesTheProgrammeThatTheBriefingTheHeartbeatAndInspireDeal(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        val runtime = helper.narrator()
        val memory = runtime.memory
        check(memory.available) { "the memory of the test server: ${memory.unavailableReason}" }
        val server = helper.level.server
        val fake = FakeAnthropic()
        val model = ScriptedModel()
        repeat(300) { fake.enqueue(model::answer) }
        var player: TestPlayer? = null
        try {
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [1, 1], "chatDebounceMs": 200 },
                   | "reflex": { "enabled": true, "timeoutMs": 10000 }, "dashboard": { "enabled": false } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key, the reflexes with a model") {
                runtime.director.status().state != DirectorState.DISABLED && runtime.settings.get().reflex != null
            }
            awaitQuiet(runtime, 30_000)
            val joined = server.onServerThread { TestPlayer(helper, "pg_") }
            player = joined
            model.player = joined.name
            awaitCondition("the join told") { fake.requests.any { isDirector(it) && "JOIN ${joined.name}" in textSince(it) } }
            awaitQuiet(runtime, 30_000)

            // 1. The dream: the Dreamer, then the storyteller's programme, two ideas signed storyteller.
            val queued = operatorCommands(helper, "narrator memory dream").lines.toList()
            val run = queued.firstNotNullOfOrNull { Regex("a deep dream is queued \\(run (\\d+)\\)").find(it)?.groupValues?.get(1)?.toLong() }
                ?: error("the dream: $queued")
            awaitCondition("the dream run $run", 90_000) {
                runCatchingJournal(runtime, "SELECT status FROM memory_runs WHERE run_id = ?", run)?.single().let { it != null && it != "running" }
            }
            val (status, report) = journalRow(runtime, "SELECT status, report FROM memory_runs WHERE run_id = ?", run)
            check(status == "done" && "step 4 done" in report.toString() && "step 5 done: 2 ideas written" in report.toString()) { "the dream: $status, $report" }
            val ideas = memory.openIdeas().filter { model.tag in it.title }
            val challenge = ideas.singleOrNull { it.kind == IdeaKind.CHALLENGE } ?: error("the challenge: $ideas, ${storytellerResults(fake)}")
            val speech = ideas.singleOrNull { it.kind == IdeaKind.SPEECH } ?: error("the speech: $ideas, ${storytellerResults(fake)}")
            check(ideas.all { it.source == Source.STORYTELLER } && challenge.players.map { it.name } == listOf(joined.name) && speech.players.isEmpty()) {
                "the storyteller's ideas: $ideas"
            }
            for (idea in ideas) {
                val actor = journalRow(runtime, "SELECT actor, run_id FROM memory_log WHERE table_name = 'ideas' AND row_key = ? AND op = 'insert'", idea.ref.id.toString())
                check(actor == listOf("storyteller", run)) { "${idea.ref} written by the storyteller in run $run: $actor" }
            }

            // 2. The next chapter's briefing shows them.
            closeChapter(helper, runtime)
            chat(server, joined, "Narrateur, quel programme ce soir ? ${model.wake}")
            val woken = awaitRequest(fake, "the new chapter's wake-up", 30_000) { isDirector(it) && model.wake in textSince(it) }
            val briefing = messageText(fake.requests.first { it.conversation == woken.conversation }.json.path("messages")[0])
            val programme = briefing.substringAfter("<programme", "").substringBefore("</programme>")
            check(briefing.startsWith("<briefing chapter=") && "« ${challenge.title} »" in programme && "« ${speech.title} »" in programme) {
                "the briefing's programme: $programme"
            }
            awaitQuiet(runtime, 30_000)

            // 3. The player mines: a heartbeat carries the challenge, and the Director's inspire deals the speech. Mined again
            // if a wake-up of the Director took the activity first (the heartbeat then has nothing to tell).
            val carries = { request: RecordedRequest -> isDirector(request) && "<heartbeat" in textSince(request) && "Idea: P-" in textSince(request) }
            for (attempt in 1..MINING_ATTEMPTS) {
                mine(helper, joined)
                if (runCatching { awaitCondition("a heartbeat with an idea", 90_000) { fake.requests.any(carries) } }.isSuccess) break
            }
            val beat = awaitRequest(fake, "a heartbeat with an idea", 1_000, carries)
            check("Idea: ${challenge.ref} challenge (${joined.name}) « ${challenge.title} »" in textSince(beat)) { "the heartbeat's idea: ${textSince(beat)}" }
            val inspired = awaitRequest(fake, "the result of inspire", 30_000) {
                isDirector(it) && it.conversation == beat.conversation && lastUser(it).hasToolResult() && it.json.path("messages").size() > beat.json.path("messages").size()
            }
            val drawn = textSince(inspired)
            check("${speech.ref}" in drawn) { "inspire deals the speech: $drawn" }
            awaitCondition("the deals recorded") {
                recalls(runtime, challenge, "heartbeat") >= 1L && recalls(runtime, speech, "inspire") >= 1L
            }
            check(memory.openIdeas().single { it.ref == challenge.ref }.dealt >= 1) { "the challenge dealt" }
            awaitQuiet(runtime, 30_000)
            closeChapter(helper, runtime)

            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            // As found: no player, the Director asleep, the reflexes off.
            player?.let { left -> runCatching { server.onServerThread { left.close() } } }
            putTheDirectorToSleep(helper, runtime)
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /** The `recalls` rows of [idea] on [channel]. */
    private suspend fun recalls(runtime: NarratorRuntime, idea: Idea, channel: String): Long =
        runCatchingJournal(runtime, "SELECT count(*) FROM recalls WHERE ref_id = ? AND channel = ?", idea.ref.id, channel)?.single() as Long? ?: 0L

    /** What the storyteller's tools answered: why an idea is missing. */
    private fun storytellerResults(fake: FakeAnthropic): List<String> =
        fake.requests.filter { it.conversation.orEmpty().startsWith("storyteller-") && lastUser(it).hasToolResult() }.map { textSince(it) }

    /** Closes the Director's open chapter, if any, with its summary. */
    private suspend fun closeChapter(helper: GameTestHelper, runtime: NarratorRuntime) {
        awaitQuiet(runtime, 30_000)
        val chapter = runtime.director.status().conversationId ?: return
        operatorCommands(helper, "narrator chapter close")
        awaitCondition("the chapter $chapter is closed", 45_000) {
            runCatchingJournal(runtime, "SELECT ended_at FROM conversations WHERE conversation_id = ?", chapter)?.single() != null
        }
    }

    /** [player] says [text] in the public chat. */
    private suspend fun chat(server: net.minecraft.server.MinecraftServer, player: TestPlayer, text: String) = server.onServerThread {
        val live = player.live
        server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
    }

    /** [player] mines three blocks of stone with a pickaxe, in survival: the activity of the next heartbeat. */
    private suspend fun mine(helper: GameTestHelper, player: TestPlayer) = helper.level.server.onServerThread {
        player.makeVulnerable()
        val level = helper.level
        val live = player.live
        live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
        for (x in 1..3) {
            val pos = helper.absolutePos(BlockPos(x, 1, 2))
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState())
            check(live.gameMode.destroyBlock(pos)) { "${player.name} mines $pos" }
        }
    }

    /**
     * Plays every model of the scenario. The reflexes: a chat is addressed, a heartbeat worth 0.9, a line plain. The Dreamer:
     * the Grimoire's step writes it, the others say there is nothing new. The storyteller: two `propose_idea`, a challenge
     * for [player] and a speech for everyone. The Director: a heartbeat that carries an idea gets `inspire`; a chapter's end
     * its summary; the rest a plain text. The Chronicler: a plain text.
     */
    private class ScriptedModel {
        /** A word of this run alone: letters only, neither a number nor a name. */
        val tag = UUID.randomUUID().toString().filter { it in 'a'..'f' }.padEnd(6, 'e').take(6)
        val wake = "programme-$tag"

        @Volatile
        var player: String = ""

        fun answer(request: RecordedRequest): FakeResponse {
            if (isReflex(request)) return reflex(userText(request))
            val conversation = request.conversation.orEmpty()
            val resulted = lastUser(request).hasToolResult()
            return when {
                isChronicler(request) -> reply { text("Nothing to record.") }
                isDreamer(request) && resulted -> reply { text("Fait.") }
                isDreamer(request) && "task=\"grimoire\"" in messageText(request.json.path("messages")[0]) ->
                    reply { toolUse("write_grimoire", """{"text": ${json(grimoire())}}""") }
                isDreamer(request) -> reply { text("Rien de neuf.") }
                conversation.startsWith("storyteller-") && resulted -> reply { text("Programme écrit : une course et une prophétie.") }
                conversation.startsWith("storyteller-") -> reply {
                    toolUse(
                        "propose_idea",
                        """{"kind": "challenge", "title": "La course du greffe $tag", "pitch": "Course au sommet du pic enneigé au nord du spawn ; le premier arrivé gagne un joker.",
                           "fit": "$player en ligne, en début de soirée", "players": ["$player"], "register": "sports_commentary", "novelty": 4, "fun": 4, "fairness": 4}""",
                    )
                    toolUse(
                        "propose_idea",
                        """{"kind": "speech", "title": "La prophétie des poules $tag", "pitch": "Une prophétie absurde annonce une averse de poules sur le village pour la fin de la soirée.",
                           "fit": "n'importe quand", "novelty": 3, "fun": 3, "fairness": 5}""",
                    )
                }
                !isDirector(request) -> reply { text("Rien à dire.") }
                // 100 characters or more: a shorter summary is no summary, and asked again (M6-FIX-9).
                "<chapter_end chapter=" in textSince(request) ->
                    reply { text("Joueurs vus : $player. Résumé de chapitre du programme : le joueur a miné un peu de pierre, et le Narrateur a tiré des idées.") }
                resulted -> reply { text("Fin du tour.") }
                "<heartbeat" in textSince(request) && "Idea: P-" in textSince(request) -> reply { toolUse("inspire", "{}") }
                else -> reply { text("Rien à dire.") }
            }
        }

        private fun reflex(user: String): FakeResponse = when {
            user.startsWith("Label this chat message.") -> reply { text(ADDRESSED) }
            user.startsWith("Label this activity summary.") -> reply { text("""{"worth": 0.9, "hook": "progress"}""") }
            else -> reply {
                text("""{"reason": "Une réplique directe.", "register": "plain", "dark": 0.0, "cruel": 0.0, "flat": 0.0, "out_of_character": 0.0, "repeats": 0.0, "crutches": []}""")
            }
        }

        /** A Grimoire of the six sections (`GrimoireFormat`), factual and in the third person. */
        private fun grimoire(): String = listOf(
            "## Players", "- $player : mineur appliqué, nouveau venu.", "## Running gags", "- aucun", "## Arcs", "- aucun",
            "## What works", "- aucun encore", "## What falls flat", "- aucun", "## Watch out", "- $player découvre le serveur : pas de châtiment gratuit.",
        ).joinToString("\n")

        /** [text] as a JSON string. */
        private fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        companion object {
            const val ADDRESSED =
                """{"reason": "Il interroge le Narrateur.", "addressed": 0.95, "tone": "respectful", "tone_confidence": 0.9, "intent": "question", "manipulation": 0.0, """ +
                    """"politeness": 0.5, "boastfulness": 0.0, "helpfulness": 0.0, "aggressiveness": 0.0, "humor": 0.1}"""
        }
    }

    private companion object {
        /** The batch of this test: it gives the Director a key, heartbeats every minute and the reflexes. */
        const val ENVIRONMENT = "narrator2-gametest:programme"

        /** At least six minutes (see [runScript]): a dream, then a heartbeat a minute after the last turn (90 s a try). */
        const val MAX_TICKS = 360_000

        /** The player mines again when no heartbeat came with an idea within 90 s. */
        const val MINING_ATTEMPTS = 2
    }
}
