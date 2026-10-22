package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.relics.RelicsService
import io.github.clemsix6.narrator2.relics.StockKind
import io.github.clemsix6.narrator2.relics.Tier
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.Prices
import kotlinx.coroutines.delay
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The real trial of M7-RELICS against the owner's **real** LLMGW server: the reserves the armorer filled when the server
 * started, a champion forced by an operator (`/narrator champion`) for a simulated player, its death by that player's
 * blow, the relic forged for them, then a structure chest that draws from the reserves. The Director stays asleep
 * (`director.enabled` false): only the armorer talks to Claude. M7-BALANCE made the player one who just began (a wooden
 * axe): the champion goes for them at once, outlasts twelve seconds of blows, and their relic is stone at most.
 *
 * Run by `gradlew liveGameTest -Pnarrator2.live.filter=narrator2-gametest:live_relics_*` only (with the key), never by
 * `runGameTest`, where it does nothing. The report goes to `build/run/liveGameTest/live-report/relics.md`, the armorer's
 * transcripts to the world's `narrator2/logs/conversations/`. Owned by M7-RELICS.
 */
class LiveRelicsGameTests {

    @GameTest(structure = FIELD, skyAccess = true, maxTicks = MAX_TICKS)
    fun trial(helper: GameTestHelper) {
        if (System.getProperty("narrator2.live") != "true") {
            helper.succeed()
            return
        }
        helper.onEachTick {
            Thread.sleep(TICK_SLEEP_MS)
            fighter?.live?.let { player ->
                player.doTick() // what a client's ticks do: the cooldown between two blows, the attack's charge
                lowest = minOf(lowest, player.health)
                if (player.health < HEAL_BELOW) player.health = player.maxHealth // the trial is about blows, not a death
            }
        }
        helper.runScript(MAX_TICKS) { RelicsTrial(helper).run() }
    }
}

/** The player the champion goes for, ticked and kept alive by the test's ticks once the fight begins. */
@Volatile
private var fighter: TestPlayer? = null

/** The lowest health [fighter] fell to. */
@Volatile
private var lowest = 20f

/** The fighter is healed below this. */
private const val HEAL_BELOW = 10f

/** Wooden-axe blows, each at full strength (a wooden axe charges in 1.25 s), twelve seconds of them. */
private const val BLOWS = 9
private const val BLOW_MS = 1_400L

/** The test's area, 32 × 32 and open to the sky; the trial lays a stone floor at its bottom. */
private const val FIELD = "narrator2-gametest:oracle_field"
private const val FIELD_SIZE = 32

/** A tick sleeps this long: about 20 ticks per second, the pace of a real server. */
private const val TICK_SLEEP_MS = 45L

/** 30 minutes at 20 ticks per second. */
private const val MAX_TICKS = 36_000

/** The Director and the reflexes asleep; every chest draws from every reserve. */
private const val CONFIG = """{ "dashboard": { "enabled": false }, "director": { "enabled": false }, "reflex": { "enabled": false },
    "relics": { "relicChance": 1.0, "consumableChance": 1.0, "bookChance": 1.0 } }"""

private class RelicsTrial(private val helper: GameTestHelper) {
    private val runtime: NarratorRuntime = helper.narrator()
    private val server = helper.level.server
    private val log = LoggerFactory.getLogger("narrator2-live")
    private val report = StringBuilder("# M7-RELICS: the real trial\n\n")

    suspend fun run() {
        val relics = checkNotNull(runtime.relics) { "no armorer: the Journal is unavailable" }
        try {
            Files.writeString(NarratorRuntime.configFile, CONFIG)
            runtime.reload()

            // 1. The reserves the armorer filled when the server started.
            awaitCondition("the restock of the server's start", 12 * 60_000) { !relics.busy && StockKind.entries.any { relics.stocked(it) > 0 } }
            section("Reserves after the start", relics.statusLines())

            // 2. A player who just began (M7-BALANCE: a wooden axe, nothing else), and a champion an operator forces on them.
            val player = server.onServerThread {
                for (x in 0 until FIELD_SIZE) for (z in 0 until FIELD_SIZE) helper.level.setBlockAndUpdate(helper.absolutePos(BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState())
                TestPlayer(helper, "rl_").also {
                    it.standAt(BlockPos(16, 1, 16))
                    it.makeVulnerable()
                    it.live.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.WOODEN_AXE))
                }
            }
            fighter = player
            try {
                val output = CommandOutput()
                server.onServerThread {
                    val source = player.live.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
                    server.commands.performPrefixedCommand(source, "narrator champion ${player.name} Il a cassé les torches de ses voisins, deux fois.")
                }
                awaitCondition("the champion stands", 5 * 60_000) { champion() != null }
                section("`/narrator champion`", output.lines)
                val champion = checkNotNull(champion())
                section(
                    "The champion",
                    server.onServerThread {
                        listOf(
                            "${champion.type.toShortString()} « ${champion.customName?.string} » health ${champion.health}/${champion.maxHealth}, " +
                                "attack damage ${champion.getAttributeValue(Attributes.ATTACK_DAMAGE)}",
                        ) + EquipmentSlot.entries.map { it to champion.getItemBySlot(it) }.filterNot { it.second.isEmpty }.map { (slot, stack) -> "$slot: ${describe(stack)}" } +
                            champion.activeEffects.map { "effect ${it.effect.registeredName} ${it.amplifier + 1}" }
                    },
                )

                // 3. It goes for the player at once: their health drops (they are healed below half, see `fighter`).
                awaitCondition("the champion's first blow on its player", 60_000) { lowest < 20f }
                section("Its first blow", listOf("the player fell to $lowest health"))

                // 4. Twelve seconds of wooden-axe blows, each at full strength: it does not fall in a few blows.
                val blows = mutableListOf<String>()
                repeat(BLOWS) {
                    delay(BLOW_MS)
                    blows += server.onServerThread {
                        player.live.attack(champion)
                        "blow ${it + 1}: champion ${champion.health}/${champion.maxHealth}, player ${player.live.health}"
                    }
                }
                section("Wooden-axe blows", blows)
                check(server.onServerThread { champion.isAlive }) { "the champion fell to $BLOWS blows of a wooden axe" }

                // 5. Its death by the player's blow, and the relic forged for them: a wood-tier player's, stone at most.
                server.onServerThread { champion.hurtServer(helper.level, helper.level.damageSources().playerAttack(player.live), 100_000f) }
                awaitCondition("the relic in the player's inventory", 5 * 60_000) { server.onServerThread { relicOf(player) } != null }
                val relic = server.onServerThread { relicOf(player)!! }
                section("The relic", server.onServerThread { listOf(describe(relic)) })
                val material = Tier.ofMaterial(relic.typeHolder().registeredName) ?: Tier.DIAMOND
                check(material <= Tier.STONE) { "a wood-tier player got a ${material.id} relic" }

                // 6. A structure chest, first opened by the player: it draws from every reserve, a relic of their tier, one off at most.
                val chest = server.onServerThread {
                    val pos = helper.absolutePos(BlockPos(20, 1, 20))
                    helper.level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
                    val entity = helper.level.getBlockEntity(pos) as ChestBlockEntity
                    entity.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/simple_dungeon")), 42L)
                    entity.unpackLootTable(player.live)
                    (0 until entity.containerSize).map { entity.getItem(it) }.filter { mark(it) != null }.map { describe(it) }
                }
                section("The chest", chest)
                awaitCondition("the reserves restocked after the chest", 12 * 60_000) { !relics.busy }
                section("Reserves at the end", relics.statusLines())
            } finally {
                fighter = null
                server.onServerThread { player.close() }
            }
            section("Champions", relics.lastChampions(3).map { "#${it.id} « ${it.name} » ${it.mob} for ${it.playerName}: ${it.status}, killer ${it.killer}, relic ${it.relic}" })
            section("Cost", cost())
        } finally {
            Files.writeString(NarratorRuntime.configFile, "{ \"dashboard\": { \"enabled\": false } }\n")
            runCatching { runtime.reload() }
            val file = Path.of("live-report", "relics.md")
            Files.createDirectories(file.parent)
            Files.writeString(file, report.toString())
            log.info("M7-RELICS trial report: ${file.toAbsolutePath()}")
        }
    }

    /** The champion standing in the test's level. Server thread or not: a snapshot. */
    private suspend fun champion(): Mob? = server.onServerThread {
        helper.level.allEntities.firstOrNull { RelicsService.TAG in it.entityTags() && it.isAlive } as? Mob
    }

    /** The relic in [player]'s inventory, if any. Server thread. */
    private fun relicOf(player: TestPlayer): ItemStack? =
        (0 until player.live.inventory.containerSize).map { player.live.inventory.getItem(it) }.firstOrNull { mark(it) == "relic" }

    /** The `kind` of the Narrator's mark on [stack]. */
    private fun mark(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompound(Speech.CUSTOM_DATA_KEY)?.orElse(null)?.getString("kind")?.orElse(null)

    /** An item as a player reads it: its name, lore, enchantments, attribute changes, effects, pages. */
    private fun describe(stack: ItemStack): String = buildString {
        append("${stack.typeHolder().registeredName} « ${stack.hoverName.string} »")
        stack.get(DataComponents.LORE)?.lines()?.forEach { append("\n  lore: ${it.string}") }
        stack.get(DataComponents.ENCHANTMENTS)?.entrySet()?.forEach { append("\n  enchantment: ${it.key.registeredName} ${it.intValue}") }
        stack.get(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers()?.forEach { append("\n  attribute: ${it.attribute().registeredName} ${it.modifier().amount()} ${it.modifier().operation()} (${it.slot()})") }
        stack.get(DataComponents.CONSUMABLE)?.onConsumeEffects()?.forEach { append("\n  on consume: $it") }
        stack.get(DataComponents.WRITTEN_BOOK_CONTENT)?.let { book ->
            append("\n  book by ${book.author()}, ${book.pages().size} pages")
            book.pages().forEach { append("\n  | ${it.raw().string.replace("\n", " / ")}") }
        }
        if (stack.has(DataComponents.UNBREAKABLE)) append("\n  unbreakable")
    }

    /** Tokens and dollars of the armorer's calls. */
    private suspend fun cost(): List<String> {
        runtime.journal.barrier()
        val rows = runtime.journal.read(30_000) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT role, model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0), " +
                        "coalesce(sum(output_tokens), 0) FROM llm_calls WHERE provider = 'anthropic' GROUP BY role, model ORDER BY role",
                ).use { result ->
                    buildList { while (result.next()) add(listOf(result.getString(1), result.getString(2)) + (3..7).map { result.getLong(it) }) }
                }
            }
        }
        val prices = Prices.of(runtime.settings.get().config)
        return rows.map { row ->
            val tokens = row.drop(2).map { it as Long }
            val dollars = prices.cost(row[1] as String, tokens[1], tokens[4], tokens[2], tokens[3]) ?: 0.0
            "${row[0]} ${row[1]}: ${tokens[0]} calls, input ${tokens[1]}, cache read ${tokens[2]}, cache write ${tokens[3]}, output ${tokens[4]}: " +
                "%.3f \$".format(Locale.ROOT, dollars)
        }
    }

    private fun section(title: String, lines: List<String>) {
        report.append("## $title\n\n```\n").append(lines.joinToString("\n")).append("\n```\n\n")
        log.info("$title:\n${lines.joinToString("\n")}")
    }
}
