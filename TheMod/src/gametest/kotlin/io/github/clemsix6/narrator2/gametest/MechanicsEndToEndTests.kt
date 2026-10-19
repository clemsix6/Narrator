package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
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
 * The mechanics end to end through the Director (M4 §21.2, M4-24), with no real Claude: the real Director of the test
 * server, with its real tools, wake-ups and chapters, talks to a fake Anthropic server that plays the model
 * ([ScriptedModel]); the server's own engine does the rest.
 *
 * 1. A player asks for a challenge: `start_challenge` (a turtle egg, a diamond for the winner); he holds the egg, the
 *    engine sees him win, pays the diamond through the real `give_item` and wakes the Director with the `CHALLENGE`,
 *    who congratulates him.
 * 2. An operator whispers for the law of the exploding diamonds: `create_rule`; the player mines a diamond ore in a
 *    booth of cobblestone: the law's explosion goes off, and no block of the booth is touched.
 * 3. The player offers ten emeralds (`/offer`): the `OFFERING` wakes the Director, who takes them with a grace
 *    (`favor` with the offering): the emeralds are the Narrator's, the player is under his grace.
 * 4. An operator closes the chapter, which ends with its summary (a test that wakes the Director closes its chapter,
 *    M3-19).
 *
 * It runs in a batch of its own (the `narrator2-gametest:mechanics_e2e` test environment): it gives the Director a key,
 * which every test of the default batch expects asleep, and shares the server's engine with the `assembly` batch. It
 * leaves everything as it found it: no key, the Director disabled, no challenge nor law running, the pace of challenges.
 * As every script ([runScript]), it starts with the minute of the server's speech limits forgotten, whatever the batch
 * before it said.
 *
 * Owned by M4-24.
 */
class MechanicsEndToEndTests {

    @GameTest(environment = E2E_ENVIRONMENT, maxTicks = E2E_MAX_TICKS)
    fun aChallengeALawAndAnOfferingThroughTheDirector(helper: GameTestHelper) = helper.runScript(E2E_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val fake = FakeAnthropic()
        val player = server.onServerThread {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
            TestPlayer(helper, "me_").also { it.makeVulnerable() }
        }
        val model = ScriptedModel(player.name)
        repeat(60) { fake.enqueue(model::answer) }
        val pace = server.onServerThread { StateStore(server).get().lastChallengeAt }
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

            // 1. A challenge asked for, won, its reward paid, its end told.
            server.onServerThread {
                val live = player.live
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, "Narrateur, lance-moi un défi ! ${model.challenge}"), live, ChatType.bind(ChatType.CHAT, live))
            }
            awaitCondition("the challenge started by the Director", 30_000) { runtime.mechanics.challenges.list().any { view -> view.participants.any { it.name == player.name } } }
            val challenge = runtime.mechanics.challenges.list().single { view -> view.participants.any { it.name == player.name } }.ref
            awaitQuiet(runtime, 30_000)
            server.onServerThread { player.live.inventory.add(ItemStack(Items.TURTLE_EGG)) }
            awaitCondition("$challenge won") { runtime.memory.store.canon(challenge)?.status == CanonStatus.WON }
            awaitCondition("the diamond, paid by give_item") { server.onServerThread { count(player.live, Items.DIAMOND) } == 1 }
            val told = awaitRequest(fake, "the CHALLENGE wakes the Director", 30_000) { isDirector(it) && "CHALLENGE $challenge" in lastUser(it).wakeText() }
            check("won by ${player.name}" in lastUser(told).wakeText()) { "the CHALLENGE line: ${lastUser(told).wakeText()}" }
            awaitCondition("the congratulations") { player.chat().any { ScriptedModel.CONGRATULATIONS in it.content().string } }
            awaitQuiet(runtime, 30_000)

            // 2. The law of the exploding diamonds: the explosion, and no block touched.
            operatorCommands(helper, "narrator whisper Décrète la loi des diamants. ${model.law}")
            awaitCondition("the law made by the Director", 30_000) { runtime.mechanics.rules.list().any { it.spec.statement == ScriptedModel.LAW } }
            val law = runtime.mechanics.rules.list().single { it.spec.statement == ScriptedModel.LAW }.ref
            awaitQuiet(runtime, 30_000)
            val ore = helper.absolutePos(BlockPos(4, 1, 6))
            val booth = server.onServerThread {
                player.standAt(BlockPos(4, 1, 2))
                for (dx in -1..1) for (dy in 0..2) for (dz in -1..1) {
                    val pos = ore.offset(dx, dy, dz)
                    val block = when {
                        pos == ore -> Blocks.DIAMOND_ORE
                        dy == 2 || dx != 0 || dz != 0 -> Blocks.COBBLESTONE
                        else -> Blocks.AIR
                    }
                    helper.level.setBlockAndUpdate(pos, block.defaultBlockState())
                }
                BlockPos.betweenClosed(ore.offset(-3, -1, -3), ore.offset(3, 3, 3)).map { it.immutable() }.filter { it != ore }.associateWith { helper.level.getBlockState(it) }
            }
            server.onServerThread {
                player.live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
                player.live.gameMode.destroyBlock(ore)
            }
            awaitCondition("the law's explosion") {
                runCatchingJournal(
                    runtime, "SELECT count(*) FROM engine_decisions WHERE kind = 'mechanic' AND decision = 'action' AND conversation_id = ? AND reason LIKE 'explosion ok%'", "mechanics:$law",
                )?.get(0) == 1L
            }
            val changed = server.onServerThread { booth.filter { (pos, state) -> helper.level.getBlockState(pos) != state }.keys }
            check(changed.isEmpty()) { "no block of the booth touched: $changed" }
            check(runtime.mechanics.rules.list().single { it.ref == law }.fired == 1) { "the law fired once" }

            // 3. An offering taken against a grace.
            server.onServerThread {
                if (player.live.isDeadOrDying) error("the explosion killed ${player.name}")
                player.live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.EMERALD, 10))
                server.commands.performPrefixedCommand(player.live.createCommandSourceStack(), "offer")
            }
            val offering = server.onServerThread { runtime.guards.items.held(player.live).single { it.purpose == LedgerPurpose.OFFERING } }
            awaitRequest(fake, "the OFFERING wakes the Director", 30_000) { isDirector(it) && "OFFERING O-${offering.id} " in lastUser(it).wakeText() }
            awaitCondition("the offering taken") { server.onServerThread { runtime.guards.items.held(player.live).none { it.id == offering.id } } }
            awaitQuiet(runtime, 30_000)
            server.onServerThread {
                check(count(player.live, Items.EMERALD) == 0) { "the emeralds are the Narrator's: ${count(player.live, Items.EMERALD)} back" }
                val grace = GuardStates.read(player.live).graceUntil ?: 0L
                check(grace > runtime.clock.millis()) { "${player.name} is under the Narrator's grace: $grace" }
            }
            val history = runtime.memory.store.interactions(player.uuid, 20)
            check(history.any { it.kind == InteractionKind.OFFERING }) { "the offering in ${player.name}'s history: $history" }

            // 4. The chapter closes with its summary.
            val chapter = told.conversation!!
            operatorCommands(helper, "narrator chapter close")
            awaitCondition("the chapter closed with its summary", 45_000) {
                runCatchingJournal(runtime, "SELECT summary FROM conversations WHERE conversation_id = ?", chapter)?.firstOrNull()?.toString()?.contains(ScriptedModel.SUMMARY) == true
            }
            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
            check(fake.unscripted == 0) { "unscripted requests: ${fake.unscripted}" }
        } finally {
            // As found: no key, the Director asleep, nothing of this test running on the server's engine, the pace.
            Files.writeString(NarratorRuntime.configFile, "{}")
            runCatching { runtime.reload() }
            for (view in runtime.mechanics.challenges.list().filter { view -> view.participants.any { it.name == player.name } }) {
                runCatching { runtime.mechanics.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé à la fin d'un test.", CLEANUP) }
            }
            for (rule in runtime.mechanics.rules.list().filter { it.spec.statement == ScriptedModel.LAW }) {
                runCatching { runtime.mechanics.rules.remove(rule.ref, "Levée à la fin d'un test.", CLEANUP) }
            }
            server.onServerThread {
                StateStore(server).update { it.copy(lastChallengeAt = pace) }
                player.close()
            }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /**
     * Plays the model: each answer is chosen from what the request adds since the model last spoke (the wake-up, and an
     * operator's whisper, which comes as a system message), whatever the order in which the requests come. The memory's
     * jobs get a plain text.
     */
    private class ScriptedModel(private val player: String) {
        private val id = UUID.randomUUID().toString().take(6)
        val challenge = "defi-$id"
        val law = "loi-$id"

        fun answer(request: RecordedRequest): FakeResponse {
            val last = lastUser(request)
            val messages = request.json.path("messages")
            val spoke = (messages.size() - 1 downTo 0).firstOrNull { messages[it].path("role").asText() == "assistant" } ?: -1
            val text = (spoke + 1 until messages.size()).joinToString("\n") { messages[it].toString() }
            val offering = OFFERING.find(text)?.groupValues?.get(1)
            return when {
                isChronicler(request) || isDreamer(request) -> reply { text("Nothing to record.") }
                "<chapter_end chapter=" in text -> reply { text("Joueurs vus : $player. $SUMMARY") }
                last.hasToolResult() -> reply { text("Fin du tour.") }
                challenge in text -> reply {
                    toolUse(
                        "start_challenge",
                        """{"title": "L'œuf de tortue", "statement": "Le Narrateur a défié $player de tenir un œuf de tortue.", "goal": "obtain",
                           "item": "turtle_egg", "players": ["$player"], "minutes": 5, "reward": {"kind": "item", "item": "diamond"}}""",
                    )
                    toolUse("say", """{"text": "Défi accepté : un œuf de tortue en main, et un diamant t'attend."}""")
                }
                "CHALLENGE C-" in text -> reply { toolUse("say", """{"text": "$CONGRATULATIONS"}""") }
                law in text -> reply {
                    toolUse(
                        "create_rule",
                        """{"kind": "law", "statement": "$LAW", "trigger": "block_break", "when": {"block": "#diamond_ores"},
                           "effects": [{"tool": "explosion", "at": "position", "args": {"power": 3}}], "minutes": 30}""",
                    )
                }
                offering != null -> reply {
                    toolUse(
                        "favor",
                        """{"player": "$player", "favor": "grace", "minutes": 5, "offering": "$offering",
                           "note": "a offert dix émeraudes ; le Narrateur lui a accordé cinq minutes de grâce"}""",
                    )
                    toolUse("say", """{"text": "Offrande acceptée. Cinq minutes de grâce.", "to": ["$player"]}""")
                }
                else -> reply { text("Rien à dire.") }
            }
        }

        companion object {
            val OFFERING = Regex("""OFFERING (O-\d+) """)
            const val CONGRATULATIONS = "L'œuf est à toi, le diamant aussi. Bravo, mortel."
            const val LAW = "Le Narrateur a décrété que les minerais de diamant explosent quand on les mine."
            const val SUMMARY = "Résumé de chapitre des mécaniques."
        }
    }

    private companion object {
        /** The batch of this test: it gives the Director a key, and shares the server's engine. */
        const val E2E_ENVIRONMENT = "narrator2-gametest:mechanics_e2e"

        /** At least two minutes of wall-clock time (see [runScript]). */
        const val E2E_MAX_TICKS = 120_000

        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")
    }
}
