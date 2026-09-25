package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.SqlAccess
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import io.github.clemsix6.narrator2.observer.dimensionId
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The M1 acceptance (M1_M2 §17.3, T07): what players do goes through the Observer, the bus and the
 * Journal, and comes back as the answers of the SQL the Narrator writes. Each test plays its own
 * players ([Actor]), with names of their own: the model asks by name, and GameTest's mock players all
 * share one. The questions go through `SqlRunner` with the model's access, as the `sql` tool will.
 *
 * Owned by T07. Shared helpers: `GameTestSupport.kt`.
 */
class JournalEndToEndTests {

    /**
     * A player's evening: he mines ores, builds, eats, crafts, sleeps, fights mobs, trades with a
     * librarian, chats, sends a private message, walks, goes to the Nether and back, is slain by a
     * zombie, respawns and leaves. Then the Narrator's questions: where does he mine, what did he
     * kill, where did he go, where does he sleep, when did he play… The private message is recorded
     * for the operators and invisible to the model.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aPlayersEveningReadsBackInSql(helper: GameTestHelper) {
        val level = helper.level
        val runtime = helper.narrator()
        val origin = helper.absolutePos(BlockPos.ZERO)
        // Both ends of a walk across the test's own chunk, which stays loaded whatever the tests around.
        val start = BlockPos(origin.x and -16, origin.y + 1, origin.z and -16)
        val end = start.offset(15, 0, 15)
        val nether = level.server.getLevel(Level.NETHER)
        helper.assertTrue(nether != null, "the test world has a Nether")
        val friend = Actor(helper, "Mimosa", helper.absolutePos(BlockPos(7, 1, 7)))
        val bartok = Actor(helper, "Bartok", start)
        val secret = "bring the ${UUID.randomUUID().toString().take(8)} rubies to the cave"
        val hello = "Evening all, ${bartok.name} is here"
        val mined = listOf(BlockPos(5, 1, 5), BlockPos(6, 1, 5), BlockPos(5, 1, 6), BlockPos(6, 1, 6)).map(helper::absolutePos)
        val bedAt = BlockPos(6, 1, 2)
        val bed = helper.absolutePos(bedAt)
        val questions = AtomicReference<Questions>()

        helper.closing(ASYNC_MAX_TICKS, friend, bartok) {
            helper.startSequence()
                .thenWaitUntil { helper.assertTrue(bartok.rows().isNotEmpty(), "first examination") }
                .thenExecute {
                    val player = bartok.player
                    // Mining, with a pickaxe: diamond, two irons and a stone.
                    for ((pos, block) in mined.zip(listOf(Blocks.DIAMOND_ORE, Blocks.IRON_ORE, Blocks.IRON_ORE, Blocks.STONE))) {
                        level.setBlockAndUpdate(pos, block.defaultBlockState())
                    }
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
                    mined.forEach { helper.assertTrue(player.gameMode.destroyBlock(it), "${bartok.name} mines $it") }

                    // Building: three planks from his hand.
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_PLANKS, 3))
                    for (x in 1..3) helper.placeAt(player, player.mainHandItem, BlockPos(x, 0, 5), Direction.UP)
                    for (x in 1..3) helper.assertBlockPresent(Blocks.OAK_PLANKS, BlockPos(x, 1, 5))

                    // A meal, then crafts in his inventory's grid (merged at the end of the tick).
                    player.foodData.foodLevel = 10
                    ItemStack(Items.COOKED_BEEF, 3).finishUsingItem(level, player)
                    repeat(2) { ItemStack(Items.OAK_PLANKS, 4).onCraftedBy(player, 4) }
                    ItemStack(Items.CRAFTING_TABLE).onCraftedBy(player, 1)

                    // A nap in a bed.
                    helper.setBlock(
                        bedAt,
                        Blocks.BED.red().defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                            .setValue(BlockStateProperties.BED_PART, BedPart.HEAD),
                    )
                    player.startSleeping(bed)
                    player.stopSleeping()

                    // A fight with a sword: a hit on a cow, then four kills.
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_SWORD))
                    val attack = level.damageSources().playerAttack(player)
                    val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 1, 2))
                    helper.assertTrue(cow.hurtServer(level, attack, 3f), "the hit on the cow lands")
                    val victims = listOf(
                        cow,
                        helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(3, 1, 2)),
                        helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(4, 1, 2)),
                        helper.spawnWithNoFreeWill(EntityTypes.SKELETON, BlockPos(5, 1, 2)),
                    )
                    victims.forEach {
                        it.hurtServer(level, attack, 1_000f)
                        helper.assertTrue(it.isDeadOrDying, "${it.type} is dead")
                    }

                    // A trade with a librarian: 24 paper for an emerald.
                    val villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BlockPos(4, 1, 3))
                    villager.villagerData = villager.villagerData.withProfession(level.registryAccess(), VillagerProfession.LIBRARIAN)
                    villager.tradingPlayer = player
                    villager.notifyTrade(MerchantOffer(ItemCost(Items.PAPER, 24), ItemStack(Items.EMERALD), 16, 2, 0.05f))
                    villager.tradingPlayer = null

                    // A word in the public chat, a private one to Mimosa (a command, handled on a later tick).
                    level.server.playerList.broadcastChatMessage(
                        PlayerChatMessage.unsigned(player.uuid, hello), player, ChatType.bind(ChatType.CHAT, player),
                    )
                    player.connection.handleChatCommand(ServerboundChatCommandPacket("msg ${friend.name} $secret"))
                }
                .thenWaitUntil {
                    helper.assertTrue(bartok.events<ItemCrafted>().size == 2, "the crafts of the tick: ${bartok.events<ItemCrafted>()}")
                    helper.assertTrue(bartok.events<CommandIssued>().isNotEmpty(), "the private message is handled")
                }
                // A walk of 21 blocks, then the Nether and back.
                .thenExecute { bartok.player.teleportTo(end.x + 0.5, end.y.toDouble(), end.z + 0.5) }
                .thenWaitUntil { helper.assertTrue(bartok.rows().any { it.reason == SampleReason.MOVED }, "a moved row") }
                .thenExecute { bartok.player.teleport(TeleportTransition(nether!!, Vec3(0.5, 8.0, 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING)) }
                .thenAwait { helper.assertTrue(bartok.rows().any { it.reason == SampleReason.DIMENSION && it.pos.dim == nether!!.dimensionId }, "sampled in the Nether") }
                .thenExecute { bartok.player.teleport(TeleportTransition(level, Vec3.atBottomCenterOf(start), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING)) }
                .thenAwait {
                    helper.assertTrue(bartok.rows().any { it.reason == SampleReason.DIMENSION && it.pos.dim == level.dimensionId }, "sampled back home")
                }
                // Slain by a zombie, then back at the spawn.
                .thenExecute {
                    val player = bartok.player
                    // What a client does: it acknowledges the trip and reports its world loaded. Till then, no damage.
                    player.hasChangedDimension()
                    player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
                    val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(1, 1, 1))
                    player.hurtServer(level, level.damageSources().mobAttack(zombie), 1_000f)
                    helper.assertTrue(player.isDeadOrDying, "${bartok.name} is dead")
                    level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
                }
                .thenAwait { helper.assertTrue(bartok.rows().any { it.reason == SampleReason.RESPAWN }, "a respawn row") }
                // He leaves, and so does Mimosa; then the Narrator asks.
                .thenExecute {
                    val players = level.server.playerList
                    players.remove(bartok.player)
                    players.remove(friend.player)
                    questions.set(Questions(runtime, eveningQuestions(bartok.name, secret)))
                }
                .thenAwait { questions.get().answers(helper) }
                .thenExecute {
                    checkEvening(helper, questions.get().answers(helper), bartok.name, friend.name, hello, secret, mined, bed, start, end)
                    friend.close()
                    bartok.close()
                }
                .thenSucceed()
        }
    }

    /** The questions of [aPlayersEveningReadsBackInSql], by key; the model's access unless said otherwise. */
    private fun eveningQuestions(name: String, secret: String): Map<String, Question> {
        val word = secret.split(' ')[2]
        return mapOf(
            // Where does he mine, and what?
            "hotspots" to Question("SELECT dim, x, z, n, y_min, y_max FROM hotspots('$name')"),
            "ores" to Question("SELECT block, count(*) AS n FROM mining WHERE player = '$name' AND ore GROUP BY block ORDER BY block"),
            "mining" to Question("SELECT count(*) AS blocks, count(DISTINCT tool) AS tools, any_value(tool) AS tool FROM mining WHERE player = '$name'"),
            // What did he kill, and with what?
            "kill_stats" to Question("SELECT victim, kills, pvp FROM kill_stats('$name')"),
            "monsters" to Question("SELECT count(*) AS monsters FROM kills WHERE player = '$name' AND victim IN ('zombie', 'skeleton', 'creeper', 'spider')"),
            "weapons" to Question("SELECT DISTINCT weapon FROM kills WHERE player = '$name'"),
            "damage" to Question("SELECT direction, damage_type, amount, other FROM damage WHERE player = '$name'"),
            // What did he build, eat, craft and buy?
            "building" to Question("SELECT block, count(*) AS n FROM building WHERE player = '$name' GROUP BY block"),
            "meals" to Question("SELECT item, nutrition FROM meals WHERE player = '$name'"),
            "crafts" to Question("SELECT item, sum(count) AS n, any_value(station) AS station FROM crafts WHERE player = '$name' GROUP BY item ORDER BY item"),
            "trades" to Question("SELECT item, count, cost_a, cost_b, profession FROM trades WHERE player = '$name'"),
            // Where does he sleep, where did he go, how did he die?
            "sleep_spots" to Question("SELECT kind, dim, x, y, z, times FROM sleep_spots('$name')"),
            "travels" to Question("SELECT via, from_dim, dim FROM travels WHERE player = '$name'"),
            "trail" to Question("SELECT reason, dim, x, z, moved FROM trail('$name')"),
            "deaths" to Question("SELECT cause, killer, narrator, message FROM deaths WHERE player = '$name'"),
            // When did he play?
            "sessions" to Question("SELECT online, end_reason, join_dim, leave_dim FROM play_sessions WHERE player = '$name'"),
            "player" to Question("SELECT sessions, is_op FROM players WHERE name = '$name'"),
            "without_session" to Question("SELECT count(*) AS n FROM events WHERE player = '$name' AND session_id IS NULL"),
            "stats" to Question(
                "SELECT stat, value FROM player_stats WHERE player = '$name' " +
                    "AND stat IN ('mined:diamond_ore', 'mined:iron_ore', 'killed:zombie', 'custom:deaths') ORDER BY stat",
            ),
            // What did he say, and what does the model see of his private message?
            "chat" to Question("SELECT channel, text FROM chat_log WHERE player = '$name'"),
            "kinds" to Question("SELECT DISTINCT kind FROM timeline WHERE player = '$name'"),
            "commands" to Question("SELECT what FROM timeline WHERE player = '$name' AND kind = 'command'"),
            "private_direct" to Question("SELECT text FROM private_messages WHERE player = '$name'"),
            "private_leaks" to Question(
                "SELECT (SELECT count(*) FROM timeline WHERE what ILIKE '%$word%') + " +
                    "(SELECT count(*) FROM chat_log WHERE text ILIKE '%$word%') + " +
                    "(SELECT count(*) FROM events WHERE CAST(detail AS VARCHAR) ILIKE '%$word%') AS n",
            ),
            "private_operator" to Question("SELECT text, recipients FROM private_messages WHERE player = '$name'", SqlAccess.OPERATOR),
        )
    }

    private fun checkEvening(
        helper: GameTestHelper,
        answers: Map<String, SqlOutcome>,
        name: String,
        friend: String,
        hello: String,
        secret: String,
        mined: List<BlockPos>,
        bed: BlockPos,
        start: BlockPos,
        end: BlockPos,
    ) {
        fun table(key: String) = helper.table(answers, key)
        fun rows(vararg rows: List<Any>) = rows.map { row -> row.map { it.toString() } }

        // Where he mines: the cells of `hotspots` (32 blocks by default), worked out here from the blocks he broke.
        val cells = mined.groupBy { Math.floorDiv(it.x, 32) to Math.floorDiv(it.z, 32) }.map { (cell, blocks) ->
            listOf("overworld", cell.first * 32 + 16, cell.second * 32 + 16, blocks.size, blocks.minOf { it.y }, blocks.maxOf { it.y }).map { it.toString() }
        }
        helper.assertEquals(cells.toSet(), table("hotspots").toSet(), "hotspots")
        helper.assertEquals(rows(listOf("diamond_ore", 1), listOf("iron_ore", 2)), table("ores"), "ores mined")
        helper.assertEquals(rows(listOf(4, 1, "iron_pickaxe")), table("mining"), "blocks mined and tool")

        // What he killed.
        helper.assertEquals(rows(listOf("zombie", 2, false), listOf("cow", 1, false), listOf("skeleton", 1, false)), table("kill_stats"), "kill_stats")
        helper.assertEquals(rows(listOf(3)), table("monsters"), "monsters killed")
        helper.assertEquals(rows(listOf("iron_sword")), table("weapons"), "weapon")
        helper.assertEquals(rows(listOf("dealt", "player_attack", 3, "cow")), table("damage"), "damage without the fatal hits")

        // What he built, ate, crafted and bought.
        helper.assertEquals(rows(listOf("oak_planks", 3)), table("building"), "building")
        helper.assertEquals(rows(listOf("cooked_beef", 8)), table("meals"), "meals")
        helper.assertEquals(rows(listOf("crafting_table", 1, "inventory"), listOf("oak_planks", 8, "inventory")), table("crafts"), "crafts")
        helper.assertEquals(rows(listOf("emerald", 1, "24 paper", "NULL", "librarian")), table("trades"), "trades")

        // Where he sleeps and where he went.
        helper.assertEquals(rows(listOf("bed", "overworld", bed.x, bed.y, bed.z, 1)), table("sleep_spots"), "sleep_spots")
        helper.assertEquals(
            setOf(listOf("other", "overworld", "the_nether"), listOf("other", "the_nether", "overworld")),
            table("travels").toSet(), "travels",
        )
        // Samples every minute or five of a quiet player are legitimate, but not part of this story.
        val trail = table("trail").filter { it[0] != "interval" && it[0] != "idle" }
        helper.assertEquals(listOf("dimension", "dimension", "join", "moved", "respawn"), trail.map { it[0] }.sorted(), "trail: $trail")
        helper.assertEquals(listOf("join", "overworld", "${start.x}", "${start.z}"), trail.first { it[0] == "join" }.take(4), "where he joined")
        helper.assertEquals(listOf("moved", "overworld", "${end.x}", "${end.z}", "21"), trail.first { it[0] == "moved" }, "his walk")
        helper.assertTrue(trail.any { it[0] == "dimension" && it[1] == "the_nether" }, "the Nether in his trail: $trail")

        // How he died.
        val death = table("deaths").single()
        helper.assertEquals(listOf("mob_attack", "zombie", "false"), death.take(3), "death")
        helper.assertTrue("was slain by Zombie" in death[3], "death message: ${death[3]}")

        // When he played: one session, closed by his departure; every event carries it.
        helper.assertEquals(rows(listOf(false, "leave", "overworld", "overworld")), table("sessions"), "play_sessions")
        helper.assertEquals(rows(listOf(1, false)), table("player"), "players")
        helper.assertEquals(rows(listOf(0)), table("without_session"), "events without a session")
        helper.assertEquals(
            rows(listOf("custom:deaths", 1), listOf("killed:zombie", 2), listOf("mined:diamond_ore", 1), listOf("mined:iron_ore", 2)),
            table("stats"), "vanilla stats, snapshotted at the departure",
        )

        // What he said; his private message stays out of the model's reach, but not the operators'.
        helper.assertEquals(rows(listOf("public", hello)), table("chat"), "chat_log")
        val kinds = table("kinds").map { it.single() }.toSet()
        val expected = setOf(
            "join", "block_break", "block_place", "consume", "craft", "sleep_start", "sleep_end", "damage_dealt", "kill", "trade",
            "chat:public", "command", "dimension_change", "death", "respawn", "leave",
        )
        helper.assertTrue(kinds.containsAll(expected), "timeline kinds: missing ${expected - kinds}")
        helper.assertEquals(rows(listOf("msg")), table("commands"), "the command, without its text")
        val direct = answers["private_direct"]
        helper.assertTrue(
            direct is SqlOutcome.Rejected && "private messages between players stay private" in direct.reason,
            "private_messages refused to the model: $direct",
        )
        helper.assertEquals(rows(listOf(0)), table("private_leaks"), "the private message nowhere the model can read")
        helper.assertEquals(rows(listOf(secret, "[$friend]")), table("private_operator"), "the operators read it")
    }

    /**
     * M1_M2 §17.3: a block placed is in `building` within 3 seconds, without waiting on the Journal
     * (`barrier`): its writer commits every second, and the model's query sees it.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aPlacedBlockIsInBuildingWithinThreeSeconds(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val mason = Actor(helper, "Mason", helper.absolutePos(BlockPos(1, 1, 1)))
        helper.closing(ASYNC_MAX_TICKS, mason) {
            val player = mason.player
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BRICKS))
            helper.placeAt(player, player.mainHandItem, BlockPos(3, 0, 3), Direction.UP)
            val at = helper.absolutePos(BlockPos(3, 1, 3))
            helper.assertBlockPresent(Blocks.BRICKS, BlockPos(3, 1, 3))
            val seen = Poll(runtime, "SELECT block, x, y, z FROM building WHERE player = '${mason.name}'") { it.isNotEmpty() }
            helper.startSequence()
                .thenAwait { seen.result(helper) }
                .thenExecute {
                    val (rows, ms) = seen.result(helper)
                    log.info("[T07] a placed block is in building after $ms ms")
                    helper.assertEquals(listOf(listOf("bricks", "${at.x}", "${at.y}", "${at.z}")), rows, "the block where it was placed")
                    helper.assertTrue(ms < 3_000, "visible after $ms ms, over 3 s")
                    mason.close()
                }
                .thenSucceed()
        }
    }

    /**
     * M1_M2 §17.3: 10,000 events published on the bus are all in the Journal, and readable in SQL,
     * within 2 seconds, without any loss.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun tenThousandEventsAreReadableWithinTwoSecondsWithoutLoss(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val journal = runtime.journal
        val lostBefore = journal.health().droppedEvents
        val ghost = PlayerRef(UUID.randomUUID(), "Ghost_" + UUID.randomUUID().toString().take(6))
        val at = helper.absolutePos(BlockPos(1, 1, 1))
        val dim = helper.level.dimensionId
        val started = System.nanoTime()
        repeat(SYNTHETIC_EVENTS) { i ->
            val pos = Pos(dim, at.x + i % 8, at.y - i / 8 % 32, at.z + i / 256 % 8)
            val published = runtime.bus.publish(BlockBroken(ghost, pos, "minecraft:stone", "minecraft:iron_pickaxe", false, true, null))
            helper.assertTrue(published != null, "the bus is open")
        }
        val publishMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val counted = Poll(runtime, "SELECT count(*) AS n FROM mining WHERE player = '${ghost.name}'", started) {
            it.single().single() == "$SYNTHETIC_EVENTS"
        }
        helper.startSequence()
            .thenAwait { counted.result(helper) }
            .thenExecute {
                val (_, ms) = counted.result(helper)
                val health = journal.health()
                log.info(
                    "[T07] $SYNTHETIC_EVENTS events published in $publishMs ms, all readable in SQL after $ms ms; " +
                        "last flush ${health.lastFlushMs} ms (${health.lastFlushRows} rows), ${health.rowsPerMinute} rows/min, " +
                        "${health.droppedEvents} lost, file ${health.fileBytes} B, WAL ${health.walBytes} B",
                )
                helper.assertTrue(ms < 2_000, "readable after $ms ms, over 2 s")
                helper.assertTrue(health.available, "the Journal is available: ${health.lastError}")
                helper.assertEquals(lostBefore, health.droppedEvents, "records lost")
                val subscription = runtime.bus.stats().single { it.name == "journal" && it.owner == "journal" }
                helper.assertEquals(0L, subscription.dropped, "events the Journal's subscription dropped")
            }
            .thenSucceed()
    }

    /**
     * M1_M2 §6.6: `/narrator sql` answers an operator in the chat, refuses what is not a single
     * `SELECT`, and records each query as an `operator` decision.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun operatorsReadTheJournalWithNarratorSql(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val herald = Actor(helper, "Herald", helper.absolutePos(BlockPos(1, 1, 1)))
        helper.closing(ASYNC_MAX_TICKS, herald) {
            val text = "Hear ye, ${herald.name} speaks"
            val player = herald.player
            server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, text), player, ChatType.bind(ChatType.CHAT, player))
            val written = AtomicBoolean()
            CoroutineScope(Dispatchers.IO).launch {
                runtime.journal.barrier()
                written.set(true)
            }
            val output = CommandOutput()
            val source = server.createCommandSourceStack().withSource(output)
            val decisions = AtomicReference<Questions>()
            helper.startSequence()
                .thenAwait { helper.assertTrue(written.get(), "the chat line is written") }
                .thenExecute {
                    server.commands.performPrefixedCommand(source, "narrator sql SELECT channel, text FROM chat_log WHERE player = '${herald.name}'")
                    server.commands.performPrefixedCommand(source, "narrator sql DELETE FROM chat WHERE player = '${herald.name}'")
                }
                .thenAwait {
                    helper.assertTrue(output.lines.any { it == "public|$text" }, "the answer: ${output.lines}")
                    helper.assertTrue(output.lines.any { it.startsWith("[Narrator] query refused: only a single read-only SELECT") }, "the refusal: ${output.lines}")
                }
                .thenExecute {
                    helper.assertTrue(output.lines.any { it.startsWith("now ") && it.endsWith(" ms") && "· 1 row ·" in it }, "the header: ${output.lines}")
                    decisions.set(
                        Questions(
                            runtime,
                            mapOf(
                                "decisions" to Question(
                                    "SELECT decision, subject, starts_with(reason, 'refused') AS refused FROM engine_decisions " +
                                        "WHERE kind = 'operator' AND reason LIKE '%${herald.name}%' ORDER BY refused",
                                    SqlAccess.OPERATOR,
                                ),
                                "kept" to Question("SELECT count(*) AS n FROM chat_log WHERE player = '${herald.name}'"),
                            ),
                        ),
                    )
                }
                .thenAwait { decisions.get().answers(helper) }
                .thenExecute {
                    val answers = decisions.get().answers(helper)
                    helper.assertEquals(
                        listOf(listOf("sql", "Server", "false"), listOf("sql", "Server", "true")),
                        helper.table(answers, "decisions"), "operator decisions",
                    )
                    helper.assertEquals(listOf(listOf("1")), helper.table(answers, "kept"), "the refused DELETE deleted nothing")
                    herald.close()
                }
                .thenSucceed()
        }
    }

    /**
     * The `gameTest` run blanks the real keys (build.gradle.kts): the in-game tests never reach LLMGW or
     * Jev, even on a machine where the keys are set.
     */
    @GameTest
    fun theInGameTestsSeeNoRealKey(helper: GameTestHelper) {
        for (name in listOf("NARRATOR_LLMGW_KEY", "TYPESAFE_API_KEY")) {
            helper.assertTrue(System.getenv(name).isNullOrEmpty(), "$name is set in the gameTest run")
        }
        helper.assertTrue(helper.narrator().settings.get().jev == null, "Jev is on without a key")
        helper.succeed()
    }

    private companion object {
        val log = LoggerFactory.getLogger("narrator2.gametest.JournalEndToEndTests")

        const val SYNTHETIC_EVENTS = 10_000
    }
}

/** The caps of the Director's `sql` tool (M1_M2 §6.3): 50 rows, 5 seconds. */
private val SQL_LIMITS = SqlLimits(maxRows = SqlLimits.MODEL_DEFAULT_ROWS, timeoutSeconds = 5)

/** A query and who runs it. */
private data class Question(val sql: String, val access: SqlAccess = SqlAccess.MODEL)

/**
 * [questions] asked of the Journal off the server thread, once it has written everything published
 * before them (`Journal.barrier`).
 */
private class Questions(runtime: NarratorRuntime, questions: Map<String, Question>) {
    private val answers = AtomicReference<Map<String, SqlOutcome>>()
    private val failure = AtomicReference<Throwable>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runtime.journal.barrier()
                val runner = SqlRunner(runtime.journal, runtime.clock)
                answers.set(questions.mapValues { (_, question) -> runner.query(question.sql, SQL_LIMITS, question.access) })
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
    }

    /** The answers by key; fails the step until they are all in, and the test if asking failed. */
    fun answers(helper: GameTestHelper): Map<String, SqlOutcome> {
        failure.get()?.let { throw IllegalStateException("asking the Journal failed", it) }
        val done = answers.get()
        helper.assertTrue(done != null, "the Journal has not answered yet")
        return done!!
    }
}

/**
 * Runs [sql] for the model every few milliseconds, off the server thread and without `barrier`, until
 * its rows satisfy [until]; [result] then gives them and the milliseconds since [since] (a
 * `System.nanoTime()`, the creation by default). Gives up after 10 seconds.
 */
private class Poll(runtime: NarratorRuntime, sql: String, since: Long = System.nanoTime(), until: (List<List<String>>) -> Boolean) {
    private val found = AtomicReference<Pair<List<List<String>>, Long>>()
    private val last = AtomicReference<SqlOutcome>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val runner = SqlRunner(runtime.journal, runtime.clock)
            while (System.nanoTime() - since < TimeUnit.SECONDS.toNanos(10)) {
                val outcome = runner.query(sql, SQL_LIMITS)
                last.set(outcome)
                val rows = (outcome as? SqlOutcome.Rows)?.let(::cells)
                if (rows != null && until(rows)) {
                    found.set(rows to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since))
                    return@launch
                }
                delay(10)
            }
        }
    }

    /** The rows and when they were seen; fails the step until then. */
    fun result(helper: GameTestHelper): Pair<List<List<String>>, Long> {
        val result = found.get()
        helper.assertTrue(result != null, "not seen yet; last answer: ${last.get()}")
        return result!!
    }
}

/** The cells of a result's rows: its text without the header line and the column names. */
private fun cells(rows: SqlOutcome.Rows): List<List<String>> = rows.text.lines().drop(2).map { it.split('|') }

/** The rows of the answer [key]; fails the test when it is not a complete result. */
private fun GameTestHelper.table(answers: Map<String, SqlOutcome>, key: String): List<List<String>> {
    val outcome = answers[key]
    assertTrue(outcome is SqlOutcome.Rows, "$key: $outcome")
    outcome as SqlOutcome.Rows
    assertTrue(outcome.total == outcome.shown.toLong(), "$key: rows missing from ${outcome.text}")
    return cells(outcome)
}

/** Runs [block] with [actors], which leave when the test ends, however it ends (see [closingOnFailure]). */
private fun GameTestHelper.closing(maxTicks: Int, vararg actors: Actor, block: () -> Unit) {
    val all = AutoCloseable { actors.forEach { it.close() } }
    closingOnFailure(all, maxTicks) { block() }
}

/**
 * A player of this test, with a name of its own ([base] and a random suffix, at most 16 characters),
 * who joins through the player list like a real connection and stands at [at] in survival mode, and
 * the events published about it. [close] stops the recording and removes the player if still online;
 * closing twice is harmless.
 */
private class Actor(private val helper: GameTestHelper, base: String, at: BlockPos) : AutoCloseable {
    val uuid: UUID = UUID.randomUUID()
    val name: String = base + "_" + uuid.toString().take(6)
    private val runtime = helper.narrator()
    private val owner = "journal-e2e-test-$uuid"

    /** Every event about this player, from its join on. */
    val recorded = CopyOnWriteArrayList<GameEvent>()

    init {
        runtime.bus.subscribeSync("recorder", owner, { it.player?.uuid == uuid }) { recorded += it.event }
        val server = helper.level.server
        val cookie = CommonListenerCookie.createInitial(GameProfile(uuid, name), false)
        val player = ServerPlayer(server, helper.level, cookie.gameProfile(), cookie.clientInformation())
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, cookie)
        player.setGameMode(GameType.SURVIVAL)
        player.teleportTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
    }

    /** The current player object (a respawn replaces it); fails when the player is not online. */
    val player: ServerPlayer get() = checkNotNull(helper.level.server.playerList.getPlayer(uuid)) { "$name is not online" }

    inline fun <reified T : GameEvent> events(): List<T> = recorded.filterIsInstance<T>()

    fun rows(): List<PositionSampled> = events()

    override fun close() {
        runtime.bus.unsubscribeOwner(owner)
        val players = helper.level.server.playerList
        players.getPlayer(uuid)?.let { players.remove(it) }
    }
}
