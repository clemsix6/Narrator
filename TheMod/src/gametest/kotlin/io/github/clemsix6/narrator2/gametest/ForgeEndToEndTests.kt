package io.github.clemsix6.narrator2.gametest

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.TaskRecord
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Items
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Forge end to end through the Director and the forger (M5 §16.4, §17.2 M5-22), with no real Claude: the real
 * Director and the real forger of the test server, with their real prompts, tools, wake-ups, tasks and chapters, talk to
 * a fake Anthropic server that plays both models ([ScriptedModel]); the server's own Forge (compiler, guard, bench,
 * arsenal) and engine (the modules' part, the challenges, the guards) do the rest.
 *
 * 1. A player prays for a new game: the Director commissions it (`forge_module`), a task of the forger starts.
 * 2. The forger reads its arsenal (`module_list`), tests a first source that does not compile (`forge_test`: the
 *    diagnostic, its line and column), tests the fixed one (`ok`: smoke run and scenarios), saves it (`forge_save`:
 *    `password_race v1 ready`, by `forger T-…`), and reports.
 * 3. Its `TASK` wakes the Director, who starts a judged challenge between the two players, then deploys the new module
 *    as a law that referees it (`deploy_module … challenge`), and announces it.
 * 4. A player asks what this game is: the Director's situation shows the module running (`Modules:`); the module shows
 *    the password backwards in the players' action bar.
 * 5. The players play: a wrong word, then the right one. The module names the winner: the challenge is won, its reward
 *    paid by the engine (`CHALLENGE` to the Director, who congratulates), then the module ends by itself (canon closed by
 *    the engine, `module_ended` fulfilled, `MODULE … ended by itself` to the Director).
 * 6. An operator closes the chapter, which ends with its summary (a test that wakes the Director closes its chapter).
 *
 * It runs in a batch of its own (the `narrator2-gametest:forge_e2e` test environment): like the Director's own batch
 * (`DirectorEndToEndTests`), it gives the Director a key, which every other test expects asleep — and two tests that
 * replace the settings side by side would talk to each other's fake gateway. It shares the server's Forge, engine and
 * switch with the `forge_assembly` batch. Its server ticks at about 20 a second (each tick sleeps), so that the module's
 * timers and action bar go at the pace of a real server. It leaves things as it found them — no key, the Director
 * disabled, no challenge nor module of its own running, the pace of challenges — but the version it saved, which stays
 * in the server's arsenal (an arsenal keeps every version: `ForgeAssemblyGameTests` counts the forged ones it finds).
 *
 * Owned by M5-22.
 */
class ForgeEndToEndTests {

    @GameTest(environment = FORGE_E2E_ENVIRONMENT, maxTicks = E2E_MAX_TICKS)
    fun aGameIsForgedDeployedPlayedAndEndedThroughTheDirectorAndTheForger(helper: GameTestHelper) {
        // The server's ticks so far: what the module's timers bring about is waited for in ticks (FLAKY-5).
        val ticks = AtomicInteger()
        helper.onEachTick {
            ticks.incrementAndGet()
            Thread.sleep(TICK_SLEEP_MS)
        }
        helper.runScript(E2E_MAX_TICKS) {
            val runtime = helper.narrator()
            val server = helper.level.server
            val forge = runtime.forge
            check(forge is ForgeService && forge.available) { "the server's Forge runs: ${forge.unavailableReason}" }
            forge.start().join() // its start, as the server started: the arsenal read, the built-in modules built
            val fake = FakeAnthropic()
            val (finder, loser) = server.onServerThread {
                floor(helper)
                TestPlayer(helper, "fw_").also { it.standAt(BlockPos(2, 1, 2)) } to TestPlayer(helper, "fl_").also { it.standAt(BlockPos(5, 1, 5)) }
            }
            val model = ScriptedModel(finder.name, loser.name)
            repeat(80) { fake.enqueue(model::answer) }
            val pace = server.onServerThread { StateStore(server).get().lastChallengeAt }
            var challenge: Ref? = null
            var deployed: Ref? = null
            try {
                Files.writeString(
                    NarratorRuntime.configFile,
                    """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                       | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 }, "reflex": { "enabled": false },
                       | "mechanics": { "challengeGapMinutes": 0 } }""".trimMargin(),
                )
                runtime.reload()
                awaitCondition("the Director wakes up with a key") { runtime.director.status().state != DirectorState.DISABLED }
                awaitQuiet(runtime, 30_000)

                // 1. A prayer for a new game: the Director commissions it from the forger.
                server.onServerThread { server.commands.performPrefixedCommand(loser.live.createCommandSourceStack(), "pray Narrateur, invente-nous un jeu ! ${model.game}") }
                val prayed = awaitRequest(fake, "the prayer wakes the Director", 30_000) { isDirector(it) && model.game in lastUser(it).wakeText() }
                val chapter = prayed.conversation!!
                val opening = fake.requests.first { it.conversation == chapter }
                val directorTools = opening.json.path("tools").map { it.path("name").asText() }
                check(directorTools.size == 49 && directorTools.containsAll(FORGE_TOOLS + "inspire" + "summon_champion")) {
                    "the Director's 49 tools, the Forge's three, inspire (the memory runs: the storyteller works, M6-08) and summon_champion (M7-RELICS) among them: $directorTools"
                }
                val briefing = opening.json.path("messages")[0].path("content")[0].path("text").asText()
                check(briefing.startsWith("<briefing chapter=\"") && "\nArsenal (" in briefing && MODULE !in briefing) {
                    "the chapter's briefing shows the arsenal, without the module yet: ${briefing.substringAfter("<engine_state").take(1_500)}"
                }
                awaitCondition("the forger's task", 30_000) { runtime.workers.list().any { it.role.id == "forger" && model.marker in it.brief } }
                val task = runtime.workers.list().single { it.role.id == "forger" && model.marker in it.brief }

                // 2. The forger: its arsenal, a source that does not compile, the fixed one, its save, its report.
                awaitCondition("the forger's report", 60_000) { runtime.workers.list().single { it.taskId == task.taskId }.status.finished }
                val done = runtime.workers.list().single { it.taskId == task.taskId }
                check(done.status == TaskRecord.Status.DONE && done.result == ScriptedModel.REPORT) { "the forger's task: $done" }
                val forger = fake.requests.filter { it.conversation == done.conversationId }
                check(forger.size == 5) { "five requests of the forger: ${forger.size}" }
                val system = forger[0].json.path("system").map { it.path("text").asText() }
                check(system.size == 2 && "Forgeron" in system[0] && system[1].startsWith("# The Forge SDK, version 1")) { "the forger's two system blocks: ${system.map { it.take(80) }}" }
                val forgerTools = forger[0].json.path("tools").map { it.path("name").asText() }.toSet()
                check(forgerTools == setOf("module_list", "module_read", "game_ids", "forge_test", "forge_save")) { "the forger's five tools: $forgerTools" }
                check("<task id=\"T-${task.taskId}\" from=\"director\">\n${task.brief}\n</task>" in lastUser(forger[0]).wakeText()) { "the brief opens the task: ${lastUser(forger[0]).wakeText()}" }
                val results = forger.drop(1).map { toolResults(lastUser(it)).single() }
                check(results[0].startsWith("arsenal: ") && " v0 ready (built-in) · " in results[0]) { "module_list: ${results[0]}" }
                check(results[1].startsWith("Invalid arguments: does not compile: 1 error") && "[1] error PasswordRace.java:35:20: The method actionbar(null, String) is undefined for the type Acts" in results[1]) {
                    "forge_test of the broken source and its diagnostic: ${results[1]}"
                }
                check(results[2].startsWith("ok: compiled (") && "guard clean, smoke: start ok" in results[2] && results[2].endsWith("scenarios 3/3")) { "forge_test of the fixed source: ${results[2]}" }
                check(results[3].startsWith("ok: saved $MODULE v1 (ready): params players (players 2-8)")) { "forge_save: ${results[3]}" }
                val entry = forge.arsenal.entry(MODULE)
                check(entry != null && entry.version == 1 && entry.status == ModuleStatus.READY && entry.by == "forger T-${task.taskId}" && entry.scenarios.size == 3) { "the arsenal's new version: $entry" }
                check(forge.arsenal.source(MODULE, 1) == ScriptedModel.SOURCE) { "the saved source" }
                val forgeDecisions = journalRows(runtime, "SELECT decision, subject FROM engine_decisions WHERE kind = 'forge' AND decision IN ('tested', 'refused', 'saved') AND subject IN (?, ?) ORDER BY id", MODULE, "$MODULE v1")
                check(forgeDecisions == listOf(listOf("tested", MODULE), listOf("tested", MODULE), listOf("tested", MODULE), listOf("saved", "$MODULE v1"))) { "the Forge's decisions: $forgeDecisions" }

                // 3. The TASK wakes the Director: a judged challenge, then the module deployed to referee it, announced.
                val told = awaitRequest(fake, "the TASK wakes the Director", 30_000) { isDirector(it) && "TASK T-${task.taskId} forger done" in lastUser(it).wakeText() }
                check("Module : $MODULE v1 (ready)" in lastUser(told).wakeText()) { "the forger's report in the TASK line: ${lastUser(told).wakeText()}" }
                awaitCondition("the module deployed", 30_000) { runtime.mechanics.modules.list().any { it.module == MODULE } }
                val view = runtime.mechanics.modules.list().single { it.module == MODULE }
                deployed = view.ref
                challenge = view.challenge
                check(view.version == 1 && challenge != null && runtime.mechanics.challenges.list().any { it.ref == challenge }) { "the module referees a running challenge: $view" }
                val canon = runtime.memory.store.canon(view.ref)
                check(canon?.mechanic == CanonMechanic.MODULE && canon.status == CanonStatus.ACTIVE) { "its canon row: $canon" }
                awaitQuiet(runtime, 30_000)
                val deployResult = fake.requests.mapNotNull { r -> toolResults(lastUser(r)).firstOrNull { it.startsWith("ok: module $MODULE") } }.first()
                check(deployResult.startsWith("ok: module $MODULE v1 deployed as ${view.ref} (law) until ") && "it referees $challenge" in deployResult) { "deploy_module: $deployResult" }
                awaitCondition("the announcement") { finder.chat().any { ScriptedModel.ANNOUNCEMENT in it.content().string } }

                // 4. What is this game? The situation shows the module; the action bar shows the password backwards.
                // The engine's situation lines are those its last second built: the question waits for the module's.
                awaitCondition("the module in the engine's situation") { runtime.mechanics.situationLines(runtime.clock.millis()).any { it.startsWith("Modules: ${view.ref} ") } }
                say(helper, finder, "Narrateur, c'est quoi ce jeu ? ${model.what}")
                val asked = awaitRequest(fake, "the question wakes the Director", 30_000) { isDirector(it) && model.what in lastUser(it).wakeText() }
                check("\nModules: ${view.ref} $MODULE law " in lastUser(asked).wakeText() && "referees $challenge" in lastUser(asked).wakeText()) {
                    "the situation shows the module: ${lastUser(asked).wakeText()}"
                }
                // The module shows it every 20 ticks: five times over, and 10 s of the wall clock for its act.
                awaitTicks("the password backwards in the action bar", 5 * 20, { ticks.get() }, 10_000) {
                    finder.packets.filterIsInstance<ClientboundSystemChatPacket>().any { it.overlay() && "ENRETNAL" in it.content().string }
                }
                awaitQuiet(runtime, 30_000)

                // 5. The game: a wrong word, then the right one. The challenge is won, its reward paid, then the module ends.
                say(helper, loser, "lampe")
                delay(1_500)
                say(helper, finder, "Lanterne !")
                awaitCondition("$challenge won", 30_000) { runtime.memory.store.canon(challenge)?.status == CanonStatus.WON }
                awaitCondition("the reward, paid by the engine") { server.onServerThread { count(finder.live, Items.COOKIE) } == 3 }
                awaitCondition("${view.ref} ended by itself", 30_000) { runtime.mechanics.modules.list().none { it.ref == view.ref } && ended(runtime, view.ref) == "fulfilled" }
                val closed = runtime.memory.store.canon(view.ref)
                check(closed?.status == CanonStatus.RETIRED && closed.resolution == "${finder.name} a trouvé le mot de passe « lanterne »") { "the law ended by its module: $closed" }
                awaitRequest(fake, "the CHALLENGE wakes the Director", 30_000) { isDirector(it) && "CHALLENGE $challenge" in it.body && "won by ${finder.name}" in it.body }
                awaitRequest(fake, "the MODULE line reaches the Director", 30_000) {
                    isDirector(it) && "MODULE ${view.ref} $MODULE ended by itself: « ${finder.name} a trouvé le mot de passe « lanterne » »" in it.body.replace("\\\"", "\"")
                }
                awaitCondition("the congratulations") { finder.chat().any { ScriptedModel.CONGRATULATIONS in it.content().string } }
                check(cookies(server, loser) == 0) { "nothing for the loser" }
                awaitQuiet(runtime, 30_000)

                // 6. The chapter closes with its summary.
                operatorCommands(helper, "narrator chapter close")
                awaitCondition("the chapter closed with its summary", 45_000) {
                    runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ?", chapter)?.firstOrNull()?.toString()?.contains(ScriptedModel.SUMMARY) == true
                }
                check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
                check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
            } finally {
                // As found: the Director asleep, nothing of this test running on the server's engine, the pace.
                putTheDirectorToSleep(helper, runtime)
                for (view in runtime.mechanics.modules.list().filter { it.ref == deployed || it.module == MODULE }) {
                    runCatching { runtime.mechanics.modules.stop(view.ref, "Le test est fini.", CLEANUP) }
                }
                for (view in runtime.mechanics.challenges.list().filter { it.ref == challenge }) {
                    runCatching { runtime.mechanics.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé à la fin d'un test.", CLEANUP) }
                }
                server.onServerThread {
                    StateStore(server).update { it.copy(lastChallengeAt = pace) }
                    finder.close()
                    loser.close()
                }
                fake.close()
            }
            awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
        }
    }

    /**
     * Plays both models: each answer is chosen from the request itself, whatever the order in which the Director's and
     * the forger's requests come. The forger's steps follow its last tool result; the Director's follow what the request
     * adds since he last spoke (his wake-up, an event, a tool's result). The memory's jobs get a plain text.
     */
    private class ScriptedModel(private val finder: String, private val loser: String) {
        private val id = UUID.randomUUID().toString().take(6)
        val game = "jeu-$id"
        val what = "quoi-$id"
        val marker = "course-$id"

        fun answer(request: RecordedRequest): FakeResponse {
            if (isForger(request)) return forger(request)
            val last = lastUser(request)
            val messages = request.json.path("messages")
            val spoke = (messages.size() - 1 downTo 0).firstOrNull { messages[it].path("role").asText() == "assistant" } ?: -1
            val text = (spoke + 1 until messages.size()).joinToString("\n") { messages[it].toString() }
            val started = toolResults(last).firstNotNullOfOrNull { STARTED.find(it)?.groupValues?.get(1) }
            return when {
                isChronicler(request) || isDreamer(request) -> reply { text("Nothing to record.") }
                "<chapter_end chapter=" in text -> reply { text("Joueurs vus : $finder, $loser. $SUMMARY") }
                started != null -> reply {
                    toolUse("deploy_module", deploy(started))
                    toolUse("say", buildJsonObject { put("text", ANNOUNCEMENT) }.toString())
                }
                last.hasToolResult() -> reply { text("Fin du tour.") }
                game in text -> reply {
                    toolUse("forge_module", buildJsonObject { put("brief", brief()) }.toString())
                }
                "TASK T-" in text && "forger done" in text -> reply { toolUse("start_challenge", contest()) }
                what in text -> reply { text("Le jeu tourne, je les regarde.") }
                "CHALLENGE C-" in text -> reply { toolUse("say", buildJsonObject { put("text", CONGRATULATIONS) }.toString()) }
                else -> reply { text("Rien à dire.") }
            }
        }

        /** The forger: its arsenal, a broken source, the fixed one, its save, its report; each step after the last result. */
        private fun forger(request: RecordedRequest): FakeResponse {
            val last = lastUser(request)
            if (!last.hasToolResult()) return reply { toolUse("module_list") }
            val result = toolResults(last).single()
            return when {
                result.startsWith("arsenal: ") -> reply { toolUse("forge_test", source(BROKEN)) }
                "does not compile" in result -> reply { toolUse("forge_test", source(SOURCE)) }
                result.startsWith("ok: compiled (") -> reply {
                    toolUse("forge_save", buildJsonObject {
                        put("name", MODULE)
                        put("source", SOURCE)
                        put("note", "A race to write a password shown backwards; it referees a judged challenge.")
                    }.toString())
                }
                result.startsWith("ok: saved ") -> reply { text(REPORT) }
                else -> reply { text("Échec : $result") }
            }
        }

        private fun source(source: String) = buildJsonObject {
            put("name", MODULE)
            put("source", source)
        }.toString()

        private fun brief() = "Un jeu de mot de passe ($marker) pour $finder et $loser : la barre d'action montre un mot écrit à l'envers ; " +
            "le premier qui l'écrit à l'endroit dans le chat gagne. Deux minutes au plus ; il arbitre un concours. Jamais de dégâts. Ton taquin."

        private fun contest() = buildJsonObject {
            put("title", "Le mot de passe")
            put("statement", "Le Narrateur a lancé un concours du mot de passe entre $finder et $loser.")
            put("goal", "judged")
            putJsonArray("players") {
                add(finder)
                add(loser)
            }
            put("minutes", 5)
            putJsonObject("reward") {
                put("kind", "item")
                put("item", "cookie")
                put("count", 3)
            }
        }.toString()

        private fun deploy(challenge: String) = buildJsonObject {
            put("module", MODULE)
            putJsonObject("params") {
                putJsonArray("players") {
                    add(finder)
                    add(loser)
                }
                put("word", "Lanterne")
            }
            put("kind", "law")
            put("statement", "Le Narrateur a lancé une course au mot de passe entre $finder et $loser.")
            put("quote", ANNOUNCEMENT)
            put("minutes", 5)
            put("challenge", JsonPrimitive(challenge))
        }.toString()

        companion object {
            val STARTED = Regex("""^ok: challenge (C-\d+) started""")
            const val ANNOUNCEMENT = "Le mot de passe est écrit à l'envers sous vos yeux. Le premier qui le dit à l'endroit gagne."
            const val CONGRATULATIONS = "Lanterne, à l'endroit. Trois biscuits pour l'érudit, rien pour la lampe."
            const val SUMMARY = "Résumé de chapitre de la Forge. Faits marquants : aucun. Interventions du Narrateur : aucune. Engagements en cours : aucun."
            const val REPORT = "Module : $MODULE v1 (ready).\n" +
                "Paramètres : players (2-8 joueurs), word (le mot de passe, un mot), seconds (30-600, 120 par défaut).\n" +
                "Ce que voient les joueurs : la barre d'action montre le mot de passe à l'envers et le temps qui reste ; le premier qui l'écrit " +
                "à l'endroit dans le chat gagne (un titre pour tous).\n" +
                "Testé : la bonne réponse gagne le concours ; un titre refusé n'empêche pas la fin ; personne ne trouve à temps.\n" +
                "Limites : à déployer avec challenge pour qu'il désigne le gagnant ; sans concours, pas d'enjeux."

            /** The module the forger writes: a race to write a password the action bar shows backwards. */
            val SOURCE = """
                import io.github.clemsix6.narrator2.forge.api.*;
                import io.github.clemsix6.narrator2.forge.api.test.Bench;
                import java.util.*;

                @ModuleInfo(name = "password_race", summary = "The action bar shows a password backwards; the first player to write it right in the chat wins.")
                @Param(name = "players", type = ParamType.PLAYERS, doc = "Who races.", min = 2, max = 8)
                @Param(name = "word", type = ParamType.TEXT, doc = "The password, one French word.", max = 20)
                @Param(name = "seconds", type = ParamType.INTEGER, doc = "Time to find it.", required = false, fallback = "120", min = 30, max = 600)
                public final class PasswordRace implements ForgeModule {
                    private ModuleContext ctx;
                    private List<String> players;
                    private String word;
                    private String shown;
                    private long deadline;
                    private boolean over;
                    private Scheduled display;

                    @Override
                    public void start(ModuleContext ctx) {
                        this.ctx = ctx;
                        players = ctx.args().players("players");
                        word = Text.normalize(ctx.args().text("word"));
                        shown = new StringBuilder(word).reverse().toString().toUpperCase(Locale.ROOT);
                        deadline = Math.min(ctx.now() + ctx.args().integer("seconds") * 1000L, ctx.endsAt() - 15_000);
                        ctx.events().on(GameFact.Chat.class, this::heard);
                        display = ctx.timers().every(20, this::show);
                    }

                    private void show() {
                        if (over) return;
                        if (ctx.now() >= deadline) {
                            timeUp();
                            return;
                        }
                        ctx.acts().hud(null, Text.cut("Mot de passe à l'envers : " + shown + " (" + Text.duration(deadline - ctx.now()) + ")", 80));
                    }

                    private void heard(GameFact.Chat chat) {
                        if (over || !players.contains(chat.player()) || !Text.words(chat.text()).contains(word)) return;
                        over = true;
                        display.cancel();
                        String winner = chat.player();
                        Pending last = ctx.acts().title(null, winner + " a trouvé", "le mot de passe était " + word);
                        Game game = ctx.game();
                        if (game != null && game.running()) last = game.win(List.of(winner), winner + " a écrit « " + word + " » le premier");
                        last.then(o -> ctx.end(winner + " a trouvé le mot de passe « " + word + " »"));
                    }

                    private void timeUp() {
                        over = true;
                        display.cancel();
                        String why = "personne n'a trouvé le mot de passe « " + word + " »";
                        Game game = ctx.game();
                        if (game != null && game.running()) game.lose(why).then(o -> ctx.end(why));
                        else ctx.end(why);
                    }

                    public static final class Scenarios {
                        private static final Place HERE = new Place("overworld", 0.5, 64, 0.5);

                        public void theFirstRightAnswerWins(Bench b) {
                            b.player("Bartok", HERE);
                            b.player("Mimosa", HERE.offset(4, 0, 0));
                            b.challenge("Bartok", "Mimosa");
                            b.start("players=Bartok,Mimosa", "word=Lanterne");
                            b.run(25);
                            b.check(b.acts().stream().anyMatch(a -> a.startsWith("hud everyone: Mot de passe à l'envers : ENRETNAL")), "the password backwards: " + b.acts());
                            b.fact(new GameFact.Chat("Bartok", HERE, "lampe"));
                            b.fact(new GameFact.Chat("Mimosa", HERE, "Lanterne !"));
                            b.run(5);
                            b.check(b.acts().contains("game won: Mimosa"), "Mimosa wins: " + b.acts());
                            b.check(b.ended() != null, "it ends once the win is paid");
                        }

                        public void aRefusedTitleStillEndsIt(Bench b) {
                            b.player("Bartok", HERE);
                            b.player("Mimosa", HERE.offset(4, 0, 0));
                            b.refuseNext("show_title", "DENIED (speech_limit): 8 lines in the last minute");
                            b.start("players=Bartok,Mimosa", "word=enclume");
                            b.run(2);
                            b.fact(new GameFact.Chat("Bartok", HERE, "enclume"));
                            b.run(5);
                            b.check(b.acts().stream().anyMatch(a -> a.startsWith("title") && a.endsWith("→ denied")), "the title is refused: " + b.acts());
                            b.check(b.ended() != null, "it ends all the same: " + b.notes());
                        }

                        public void nobodyFindsItInTime(Bench b) {
                            b.player("Bartok", HERE);
                            b.player("Mimosa", HERE.offset(4, 0, 0));
                            b.start("players=Bartok,Mimosa", "word=enclume", "seconds=30");
                            b.run(30 * 20 + 40);
                            b.check(b.ended() != null && b.ended().contains("personne"), "nobody found it: " + b.ended());
                        }
                    }
                }
            """.trimIndent()

            /** Its first draft, against an action bar the SDK calls `hud`: it does not compile. */
            val BROKEN = SOURCE.replace("ctx.acts().hud(null,", "ctx.acts().actionbar(null,")
        }
    }

    private companion object {
        /** The batch of this test: it gives the Director a key, and shares the server's Forge and engine. */
        const val FORGE_E2E_ENVIRONMENT = "narrator2-gametest:forge_e2e"

        /** At least two minutes of wall-clock time; the ticks sleep, so many more in fact. */
        const val E2E_MAX_TICKS = 6_000

        /** A tick sleeps this long: about 20 ticks a second, the pace of a real server. */
        const val TICK_SLEEP_MS = 45L

        const val MODULE = "password_race"

        val FORGE_TOOLS = listOf("forge_module", "deploy_module", "stop_module")

        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")

        fun isForger(request: RecordedRequest) = request.conversation.orEmpty().startsWith("forger-")

        /** The texts of the tool results of a `user` message, in order. */
        fun toolResults(message: JsonNode): List<String> = message.path("content").filter { it.path("type").asText() == "tool_result" }.map { it.resultText() }

        /** [player] says [text] in the public chat, as the client would. */
        suspend fun say(helper: GameTestHelper, player: TestPlayer, text: String) {
            val server = helper.level.server
            server.onServerThread {
                val live = player.live
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
            }
        }

        /** How many cookies [player] holds. */
        suspend fun cookies(server: MinecraftServer, player: TestPlayer): Int = server.onServerThread { count(player.live, Items.COOKIE) }

        /** How [ref] ended, from its decision `mechanic module_ended`; null before it ends. */
        suspend fun ended(runtime: NarratorRuntime, ref: Ref): String? = runCatchingJournal(
            runtime, "SELECT json_extract_string(detail, '$.ending') FROM engine_decisions WHERE kind = 'mechanic' AND decision = ? AND subject = ?",
            ModuleEngine.ENDED, ref.toString(),
        )?.single()?.toString()

        /** Every row of [sql] in the Journal, once what came before is written; its numbers as `Long`. */
        suspend fun journalRows(runtime: NarratorRuntime, sql: String, vararg params: Any): List<List<Any?>> {
            runtime.journal.barrier()
            return runtime.journal.read(5_000) { connection ->
                connection.prepareStatement(sql).use { statement ->
                    params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add((1..rows.metaData.columnCount).map { column -> rows.getObject(column)?.let { if (it is Number) it.toLong() else it.toString() } })
                        }
                    }
                }
            }
        }
    }
}
