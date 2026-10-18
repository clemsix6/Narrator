package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.memory.MemoryService
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.reply
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files

/**
 * The memory end to end on the test server (M3-19), without a model: the real Director, memory and Observer,
 * a fake Anthropic server that answers every request with a plain text. A player joins: the Director's
 * wake-up carries their sheet. They mine and die: once they leave, `sheet_stats` holds what they did.
 * `/narrator memory status` answers.
 *
 * It runs in a batch of its own (the `narrator2-gametest:memory` test environment): it gives the Director a key,
 * which every test of the default batch expects it not to have, and leaves the settings as it found them.
 *
 * Owned by M3-19. Shared helpers: `GameTestSupport.kt`, and `runScript` (`DirectorEndToEndTests.kt`).
 */
class MemoryEndToEndTests {

    @GameTest(environment = MEMORY_ENVIRONMENT, maxTicks = MEMORY_SCRIPT_MAX_TICKS)
    fun aPlayerWhoJoinsHasTheirSheetAndTheirStatsFollowWhatTheyDid(helper: GameTestHelper) = helper.runScript(MEMORY_SCRIPT_MAX_TICKS) {
        val runtime = helper.narrator()
        val memory = runtime.memory
        check(memory is MemoryService && memory.available) { "the memory of the test server: ${memory.unavailableReason ?: memory.javaClass.simpleName}" }
        val server = helper.level.server
        val fake = FakeAnthropic()
        repeat(40) { fake.enqueue(::answer) }
        var player: TestPlayer? = null
        try {
            Files.writeString(
                NarratorRuntime.configFile,
                """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 },
                   | "director": { "heartbeatMinutes": [60, 60], "chatDebounceMs": 200 } }""".trimMargin(),
            )
            runtime.reload()
            awaitCondition("the Director wakes up with a key") { runtime.director.status().state != DirectorState.DISABLED }
            awaitQuiet(runtime)

            // 1. A player joins: the wake-up carries their sheet, in the briefing of a new chapter or in <memory>.
            val joined = server.onServerThread { TestPlayer(helper, "me_") }
            player = joined
            val wake = awaitRequest(fake, "the wake-up of ${joined.name}'s arrival", timeoutMs = 30_000) {
                isDirector(it) && "JOIN ${joined.name}" in lastUser(it).wakeText()
            }
            val told = lastUser(wake).path("content").filter { it.path("type").asText() == "text" }.joinToString("\n") { it.path("text").asText() }
            val sheets = told.substringAfter("<sheets", "").substringBefore("</sheets>")
            check("SHEET ${joined.name}" in told || "${joined.name} " in sheets) { "the wake-up carries the sheet of ${joined.name}: $told" }
            awaitQuiet(runtime, 30_000)

            // 2. They mine five blocks with a pickaxe, in survival, then die.
            val mined = (1..5).map { helper.absolutePos(BlockPos(it, 1, 2)) }
            server.onServerThread {
                joined.makeVulnerable()
                val level = helper.level
                mined.forEachIndexed { i, pos -> level.setBlockAndUpdate(pos, (if (i < 2) Blocks.IRON_ORE else Blocks.STONE).defaultBlockState()) }
                val live = joined.live
                live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
                mined.forEach { check(live.gameMode.destroyBlock(it)) { "${joined.name} mines $it" } }
                live.hurtServer(level, level.damageSources().generic(), 1_000f)
                check(live.isDeadOrDying) { "${joined.name} is dead" }
                level.server.playerList.respawn(live, false, Entity.RemovalReason.KILLED)
            }
            awaitCondition("the Journal has the blocks and the death of ${joined.name}") {
                val broken = runCatchingJournal(runtime, "SELECT count(*) FROM events WHERE type = 'block_break' AND player_uuid = ?", joined.uuid)?.single() as Long?
                val deaths = runCatchingJournal(runtime, "SELECT count(*) FROM deaths WHERE player_uuid = ?", joined.uuid)?.single() as Long?
                (broken ?: 0) >= 5 && (deaths ?: 0) >= 1
            }

            // 3. They leave: their stats are computed again, and the sheet's stats follow.
            server.onServerThread { joined.close() }
            player = null
            awaitCondition("the stats of ${joined.name} after the departure", timeoutMs = 30_000) {
                val mining = runCatchingJournal(runtime, "SELECT value FROM sheet_stats WHERE player_uuid = ? AND stat = 'mining'", joined.uuid)?.single()
                val deaths = runCatchingJournal(runtime, "SELECT value FROM sheet_stats WHERE player_uuid = ? AND stat = 'deaths'", joined.uuid)?.single()
                (mining?.toString()?.toDoubleOrNull() ?: 0.0) >= 5.0 && (deaths?.toString()?.toDoubleOrNull() ?: 0.0) >= 1.0
            }
            val ores = journalRow(runtime, "SELECT detail::VARCHAR FROM sheet_stats WHERE player_uuid = ? AND stat = 'mining'", joined.uuid).single().toString()
            check("\"iron\":2" in ores.replace(" ", "")) { "the iron ores of ${joined.name}: $ores" }

            // 4. /narrator memory status answers.
            val output = operatorCommands(helper, "narrator memory status")
            awaitCondition("the answer of /narrator memory status") { output.lines.any { it.startsWith("Index: ") } }
            check(output.lines.any { it.startsWith("Memory: available · jobs running · world clock ") }) { "memory status: ${output.lines}" }

            check(fake.rejections.isEmpty()) { "requests the strict fake gateway rejected: ${fake.rejections}" }
        } finally {
            // As found: no key, the Director asleep.
            Files.writeString(NarratorRuntime.configFile, "{}")
            runCatching { runtime.reload() }
            player?.let { server.onServerThread { it.close() } }
            fake.close()
        }
        awaitCondition("the Director sleeps again") { runtime.director.status().state == DirectorState.DISABLED }
    }

    /** The model: a plain text for everyone (the Director, the Chronicler, the Dreamer): nothing to do. */
    private fun answer(request: RecordedRequest): FakeResponse =
        if (isDirector(request) && "<chapter_end chapter=" in lastUser(request).wakeText()) reply { text("Joueurs vus : aucun fait marquant.") } else reply { text("Rien à dire.") }
}

/** The test environment of [MemoryEndToEndTests]: a batch of its own, apart from the tests that expect a sleeping Director. */
const val MEMORY_ENVIRONMENT = "narrator2-gametest:memory"

/** Budget of the script: at least two minutes of wall-clock time (see `runScript`). */
private const val MEMORY_SCRIPT_MAX_TICKS = 120_000
