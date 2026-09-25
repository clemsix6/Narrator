package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.SqlDataset.SECRET
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every view and macro of `views.sql` (M1_M2 §6.4) on the rows the Journal writes for [SqlDataset]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlViewsTest {
    private val fixture = SqlDataset.journal()

    @AfterAll
    fun close() = fixture.close()

    private fun values(sql: String) = fixture.values(sql)

    // ── Views ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `timeline has events and chat, without pickups, stats or private messages`() {
        val kinds = values("SELECT DISTINCT kind FROM timeline").map { it.single() as String }.toSet()
        assertTrue(kinds.containsAll(listOf("join", "block_break", "kill", "death", "chat:public", "chat:pray", "chat:narrator")), "$kinds")
        assertTrue(kinds.none { it in setOf("pickup", "stat", "whisper", "chat:whisper") }, "$kinds")
        assertEquals(
            listOf(listOf<Any?>("Bartok", "block_break", "diamond_ore → iron_pickaxe", "overworld", 100, -40, 200)),
            values("SELECT player, kind, what, dim, x, y, z FROM timeline WHERE kind = 'block_break' ORDER BY ts LIMIT 1"),
        )
        assertEquals(
            listOf(listOf<Any?>("NARRATOR", "Bartok, ta pioche pleure.", null)),
            values("SELECT player, what, dim FROM timeline WHERE kind = 'chat:narrator'"),
        )
        assertFalse(values("SELECT * FROM timeline").flatten().any { it == SECRET })
    }

    @Test
    fun `chat_log has public chat, prayers and the Narrator, never private messages`() {
        assertEquals(
            listOf(
                listOf<Any?>("public", "Bartok", null, "narrateur, t'es nul"),
                listOf<Any?>("pray", "Bartok", null, "rends-moi mes diamants"),
                listOf<Any?>("narrator", "NARRATOR", null, "Bartok, ta pioche pleure."),
            ),
            values("SELECT channel, player, recipients, text FROM chat_log ORDER BY ts"),
        )
    }

    @Test
    fun `narrator_actions keeps speech and world calls only`() {
        assertEquals(
            listOf(listOf<Any?>("say", listOf("Bartok"), "ok", 42L), listOf<Any?>("strike_lightning", listOf("Bartok"), "ok", 42L)),
            values("SELECT tool, targets, status, turn_id FROM narrator_actions ORDER BY tool"),
        )
    }

    @Test
    fun `mining flags ores and ancient debris`() {
        assertEquals(
            listOf(
                listOf<Any?>("diamond_ore", true, "iron_pickaxe", "overworld", -40),
                listOf<Any?>("deepslate_iron_ore", true, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("oak_planks", false, null, "overworld", 64),
                listOf<Any?>("dirt", false, null, "overworld", 64),
                listOf<Any?>("ancient_debris", true, "diamond_pickaxe", "the_nether", 15),
            ),
            values("SELECT block, ore, tool, dim, y FROM mining WHERE player = 'Bartok' ORDER BY ts"),
        )
    }

    @Test
    fun `building, deaths, kills and damage`() {
        assertEquals(
            listOf(listOf<Any?>("Mimosa", "oak_planks", 10), listOf<Any?>("Bartok", "cobblestone", 1)),
            values("SELECT player, block, x FROM building ORDER BY ts"),
        )
        assertEquals(
            listOf(listOf<Any?>("Bartok", "arrow", "skeleton", "Bartok was shot by Skeleton", false, "overworld", 25)),
            values("SELECT player, cause, killer, message, narrator, dim, x FROM deaths"),
        )
        assertEquals(
            listOf(
                listOf<Any?>("zombie", null, "iron_sword", false, false),
                listOf<Any?>("zombie", null, "iron_sword", false, false),
                listOf<Any?>("skeleton", null, "bow", false, false),
                listOf<Any?>("player", "Mimosa", "iron_sword", true, false),
                listOf<Any?>("zombie", null, "iron_sword", false, false),
            ),
            values("SELECT victim, victim_name, weapon, pvp, tamed FROM kills ORDER BY ts"),
        )
        assertEquals(
            listOf(listOf<Any?>("taken", "arrow", 6.5, "skeleton"), listOf<Any?>("dealt", "player_attack", 4.0, "skeleton")),
            values("SELECT direction, damage_type, amount, other FROM damage ORDER BY direction DESC"),
        )
    }

    @Test
    fun `trades, meals, crafts and advancements`() {
        assertEquals(listOf(listOf<Any?>("emerald", 1, "24 paper", null, "librarian")), values("SELECT item, count, cost_a, cost_b, profession FROM trades"))
        assertEquals(listOf(listOf<Any?>("cooked_beef", 8)), values("SELECT item, nutrition FROM meals"))
        assertEquals(listOf(listOf<Any?>("torch", 16, "crafting")), values("SELECT item, count, station FROM crafts"))
        assertEquals(listOf(listOf<Any?>("story/mine_diamond", "Diamonds!", "task")), values("SELECT advancement, title, frame FROM advancements"))
    }

    @Test
    fun `visits and travels`() {
        assertEquals(
            listOf(listOf<Any?>("biome", "cherry_grove", true, 200), listOf<Any?>("structure", "village_plains", false, 210)),
            values("SELECT kind, place, first_visit, x FROM visits ORDER BY ts"),
        )
        assertEquals(
            listOf(
                listOf<Any?>("nether_portal", "overworld", 16, 64, 16, "the_nether", 2, 70, 2, null),
                listOf<Any?>("teleport", "the_nether", 3, 70, 3, "the_nether", 900, 70, 2, 897.0),
            ),
            values("SELECT via, from_dim, from_x, from_y, from_z, dim, x, y, z, distance FROM travels ORDER BY ts"),
        )
    }

    @Test
    fun `griefs are blocks another player had placed`() {
        assertEquals(
            listOf(listOf<Any?>("Bartok", "oak_planks", "Mimosa", "overworld", 10, 64, 10)),
            values("SELECT player, block, owner, dim, x, y, z FROM griefs"),
        )
    }

    @Test
    fun `sessions, play time and stats`() {
        assertEquals(
            listOf(listOf<Any?>("Bartok", true, null, "overworld", null), listOf<Any?>("Mimosa", false, "leave", "overworld", "overworld")),
            values("SELECT player, online, end_reason, join_dim, leave_dim FROM play_sessions ORDER BY player"),
        )
        assertEquals(listOf(listOf<Any?>(60.0)), values("SELECT minutes FROM play_sessions WHERE player = 'Mimosa'"))
        assertEquals(
            listOf(listOf<Any?>("Bartok", 1L), listOf<Any?>("Mimosa", 1L)),
            values("SELECT player, CAST(sum(sessions) AS BIGINT) FROM playtime GROUP BY player ORDER BY player"),
        )
        assertEquals(listOf(listOf<Any?>(1.0)), values("SELECT sum(hours) FROM playtime WHERE player = 'Mimosa'"))
        assertEquals(
            listOf(listOf<Any?>("Bartok", "killed:zombie", 2L), listOf<Any?>("Bartok", "mined:stone", 1_204L)),
            values("SELECT player, stat, value FROM player_stats ORDER BY stat"),
        )
    }

    // ── Macros ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `recent is a player's timeline over the last minutes, whatever the case of the name`() {
        val expected = listOf(listOf<Any?>("chat:public"), listOf<Any?>("chat:pray"), listOf<Any?>("kill"))
        assertEquals(expected, values("SELECT kind FROM recent('bartok', 15)"))
        assertEquals(expected, values("SELECT kind FROM recent('BARTOK', 15)"))
        assertTrue(values("SELECT kind FROM recent('Bartok')").size >= 3, "30 minutes by default")
    }

    @Test
    fun `hotspots finds where a player mines, or does anything else`() {
        assertEquals(
            listOf(
                listOf<Any?>("overworld", 112L, 208L, 5L, -41, -40),
                listOf<Any?>("overworld", 16L, 16L, 2L, 64, 64),
                listOf<Any?>("the_nether", 16L, 16L, 1L, 15, 15),
            ),
            values("SELECT dim, x, z, n, y_min, y_max FROM hotspots('Bartok')"),
        )
        assertEquals(listOf(listOf<Any?>("overworld", 24L, 24L, 5L)), values("SELECT dim, x, z, n FROM hotspots('bartok', 'kill', 16)"))
        assertEquals(listOf(listOf<Any?>(7L), listOf<Any?>(1L)), values("SELECT n FROM hotspots('Bartok', 'block_break', 1000)"))
    }

    @Test
    fun `kill_stats counts kills by victim, since a time`() {
        assertEquals(
            listOf(listOf<Any?>("zombie", 3L, false), listOf<Any?>("player", 1L, true), listOf<Any?>("skeleton", 1L, false)),
            values("SELECT victim, kills, pvp FROM kill_stats('Bartok')"),
        )
        assertEquals(listOf(listOf<Any?>("zombie", 1L)), values("SELECT victim, kills FROM kill_stats('Bartok', now() - INTERVAL 15 MINUTE)"))
        assertEquals(emptyList(), values("SELECT * FROM kill_stats('Mimosa')"))
    }

    @Test
    fun `sleep_spots lists beds and respawn points`() {
        assertEquals(
            listOf(listOf<Any?>("bed", "overworld", 12, 64, 12, 2L), listOf<Any?>("respawn_point", "overworld", 12, 64, 12, 1L)),
            values("SELECT kind, dim, x, y, z, times FROM sleep_spots('bartok')"),
        )
    }

    @Test
    fun `trail follows a player's sampled positions`() {
        assertEquals(
            listOf(
                listOf<Any?>("overworld", 0, 0, "plains", "join", null),
                listOf<Any?>("overworld", 30, 40, "plains", "moved", 50L),
                listOf<Any?>("the_nether", 2, 2, "nether_wastes", "dimension", null),
            ),
            values("SELECT dim, x, z, biome, reason, moved FROM trail('Bartok', 120)"),
        )
        assertEquals(emptyList(), values("SELECT * FROM trail('Bartok', 1)"))
    }

    @Test
    fun `columns_of describes a table or a view, never a hidden table`() {
        assertEquals(
            listOf("id", "ts", "player", "block", "ore", "y", "dim", "x", "z", "tool"),
            values("SELECT name FROM columns_of('Mining')").map { it.single() },
        )
        assertEquals(listOf(listOf<Any?>("ore", "BOOLEAN", "true for ores and ancient debris")), values("SELECT * FROM columns_of('mining') WHERE name = 'ore'"))
        assertEquals(listOf(listOf<Any?>("players", "latest known name")), values("SELECT 'players', comment FROM columns_of('players') WHERE name = 'name'"))
        Journal.HIDDEN_FROM_MODEL.forEach { table ->
            assertEquals(emptyList(), values("SELECT * FROM columns_of('$table')"), table)
            assertEquals(emptyList(), values("SELECT * FROM columns_of(upper('$table'))"), table)
        }
    }

    @Test
    fun `ago and bare`() {
        assertEquals(
            listOf(listOf<Any?>("42s ago", "5m ago", "3h ago", "4d ago", null, "0s ago")),
            values(
                "SELECT ago(now() - INTERVAL 42 SECOND), ago(now() - INTERVAL 5 MINUTE), ago(now() - INTERVAL 3 HOUR), " +
                    "ago(now() - INTERVAL 4 DAY), ago(NULL), ago(now() + INTERVAL 1 MINUTE)",
            ),
        )
        assertEquals(listOf(listOf<Any?>("stone", "create:brass_ore", null)), values("SELECT bare('minecraft:stone'), bare('create:brass_ore'), bare(NULL)"))
    }

    // ── Order ───────────────────────────────────────────────────────────────────────────────────────

    /** Facts of one tick share their millisecond (T07): `ts, id` keeps the order they happened in, in views and macros. */
    @Test
    fun `facts that share a timestamp keep their order`() =
        JournalFixture(clock = TestClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))).use { journal ->
            // The clock never moves: every fact below has the same ts.
            val bartok = SqlDataset.bartok
            journal.publish(PlayerJoined(bartok, SqlDataset.ow(0, 64, 0), sessionId = 1, firstTime = false, isOp = false))
            val blocks = listOf("stone", "dirt", "gravel", "sand", "clay", "andesite")
            blocks.forEach { block ->
                journal.publish(BlockBroken(bartok, SqlDataset.ow(0, 64, 0), "minecraft:$block", null, creative = false, correctTool = true, placedBy = null))
            }
            listOf("one", "two", "three").forEach { text -> journal.publish(ChatMessage(bartok, ChatChannel.PUBLIC, text, emptyList())) }
            // A straight walk, 50 blocks a step.
            val steps = 20
            repeat(steps) { step ->
                val pos = SqlDataset.ow(30 * step, 64, 40 * step)
                journal.publish(PositionSampled(bartok, pos, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
            }

            val order = listOf(listOf<Any?>("join", null)) + blocks.map { listOf<Any?>("block_break", it) } +
                listOf("one", "two", "three").map { listOf<Any?>("chat:public", it) }
            assertEquals(order, journal.values("SELECT kind, what FROM timeline ORDER BY ts, id"))
            assertEquals(order.map { it[1] }, journal.values("SELECT what FROM recent('Bartok')").map { it.single() })
            assertEquals(listOf(listOf<Any?>("andesite")), journal.values("SELECT block FROM mining ORDER BY ts DESC, id DESC LIMIT 1"))
            assertEquals(listOf(listOf<Any?>("three")), journal.values("SELECT text FROM chat_log ORDER BY ts DESC, id DESC LIMIT 1"))
            assertEquals(
                (0 until steps).map { listOf<Any?>(30 * it, if (it == 0) null else 50L) },
                journal.values("SELECT x, moved FROM trail('Bartok')"),
            )
        }

    // ── Privacy ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no view reads a hidden table, and only columns_of names one, to leave it out`() {
        val views = values("SELECT view_name, sql FROM duckdb_views() WHERE NOT internal")
        assertEquals(18, views.size, "$views")
        views.forEach { (name, sql) ->
            Journal.HIDDEN_FROM_MODEL.forEach { table -> assertFalse(table in (sql as String).lowercase(), "view $name reads $table") }
        }
        val macros = values(
            "SELECT function_name, macro_definition FROM duckdb_functions() WHERE NOT internal AND function_type IN ('macro', 'table_macro')",
        )
        assertEquals(
            setOf("bare", "ago", "recent", "hotspots", "kill_stats", "sleep_spots", "trail", "columns_of"),
            macros.map { it[0] }.toSet(),
        )
        val naming = macros.filter { (_, definition) -> Journal.HIDDEN_FROM_MODEL.any { it in (definition as String).lowercase() } }
        assertEquals(listOf("columns_of"), naming.map { it[0] })
        assertContains(naming.single()[1] as String, "NOT IN ('private_messages')")
    }
}
