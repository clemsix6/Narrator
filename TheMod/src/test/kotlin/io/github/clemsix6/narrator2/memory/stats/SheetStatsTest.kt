package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import io.github.clemsix6.narrator2.memory.SheetStat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [StatsRefresher] does, step by step on the fixture: the pairs, the rates, then each player's stats read
 * on the pool and written on a second connection. Returns the players refreshed.
 */
internal fun refreshStats(fixture: io.github.clemsix6.narrator2.journal.JournalFixture, players: List<UUID>): Int {
    val pairs = MemoryDataset.read(fixture) { PairStats.compute(it, NOW) }
    MemoryDataset.write(fixture) { PairStats.write(it, pairs) }
    val rates = MemoryDataset.read(fixture) { Rates.read(it, NOW) }
    return players.count { player ->
        val stats = MemoryDataset.read(fixture) { SheetStats.compute(it, player, NOW, rates) } ?: return@count false
        MemoryDataset.write(fixture) { SheetStats.write(it, player, stats, NOW) }
        true
    }
}

/** Every stat of the sheet (M3 §6.1) with its exact value, on the lines the real Journal writes ([MemoryDataset]). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SheetStatsTest {
    private val fixture = MemoryDataset.journal()
    private lateinit var stats: Map<UUID, Map<String, SheetStat>>

    @BeforeAll
    fun refresh() {
        assertEquals(3, refreshStats(fixture, listOf(bartok.uuid, mimosa.uuid, kelvin.uuid)))
        stats = MemoryDataset.read(fixture) { connection -> listOf(bartok, mimosa, kelvin).associate { it.uuid to SheetStats.read(connection, it.uuid) } }
    }

    @AfterAll
    fun close() = fixture.close()

    private fun stat(player: UUID, name: String): SheetStat = stats.getValue(player).getValue(name)

    private fun assertStat(name: String, value: Double?, detail: String?, player: UUID = bartok.uuid) {
        val stat = stat(player, name)
        assertEquals(value, stat.value, "value of $name")
        assertEquals(detail?.let { Json.parseToJsonElement(it) as JsonObject }, stat.detail, "detail of $name")
        assertEquals(NOW, stat.computedAt)
    }

    @Test
    fun `every stat is written for every player`() {
        for (player in listOf(bartok, mimosa, kelvin)) assertEquals(SheetStats.STATS.toSet(), stats.getValue(player.uuid).keys)
    }

    @Test
    fun `a player never seen gets nothing`() {
        val stranger = UUID.fromString("00000000-0000-4000-8000-0000000000ff")
        assertNull(MemoryDataset.read(fixture) { SheetStats.compute(it, stranger, NOW, emptyList()) })
        assertTrue(MemoryDataset.read(fixture) { SheetStats.read(it, stranger) }.isEmpty())
    }

    @Test
    fun playtime() {
        // Day 1 18:00-20:00 (closed), day 2 from 20:00, running at 21:00; only day 2 is within 7 days.
        assertStat("playtime", 10_800.0, """{"last7d_s":3600,"sessions":2,"usual_hours":[18,19,20]}""")
        assertStat("playtime", 3_000.0, """{"last7d_s":3000,"sessions":1,"usual_hours":[20]}""", kelvin.uuid)
    }

    @Test
    fun deaths() {
        assertStat(
            "deaths", 5.0,
            """{"top":[["lava",2],["fall",1],["mob_attack",1]],
               "last":{"ts":"2026-09-25T18:20:00Z","cause":"mob_attack","killer":"zombie","dim":"overworld","x":10,"y":-20,"z":10},
               "absurd":3}""",
        )
    }

    @Test
    fun kills() {
        assertStat("kills", 4.0, """{"top":[["zombie",2],["ender_dragon",1],["skeleton",1]],"bosses":["ender_dragon"]}""")
    }

    @Test
    fun pvp() {
        assertStat("pvp", 1.0, """{"losses":1,"last":{"ts":"2026-09-25T18:29:00Z","opponent":"Mimosa","won":true}}""")
        assertStat("pvp", 1.0, """{"losses":1,"last":{"ts":"2026-09-25T18:29:00Z","opponent":"Bartok","won":false}}""", mimosa.uuid)
    }

    @Test
    fun mining() {
        // 4 ores, 5 stones and Mimosa's planks; the creative stone is left out. Ores at y -50, -54, 20 and 15.
        assertStat("mining", 10.0, """{"ores":{"diamond":2,"ancient_debris":1,"emerald":0,"gold":0,"iron":1},"avg_ore_y":-17}""")
    }

    @Test
    fun building() {
        assertStat("building", 4.0, """{"top":[["cobblestone",3],["oak_planks",1]]}""")
    }

    @Test
    fun travel() {
        // walk 1500 m + sprint 500 + elytra 200 + boat 0.4; falls left out; under a metre left out of the detail.
        assertStat("travel", 2_200.0, """{"walk":1500,"sprint":500,"elytra":200}""")
        assertStat("travel", 0.0, "{}", mimosa.uuid)
    }

    @Test
    fun exploration() {
        // plains and nether_wastes (positions), cherry_grove and forest (biome_enter).
        assertStat(
            "exploration", 4.0,
            """{"structures":["trial_chambers","village_plains"],"rare_biomes":["cherry_grove"],"nether_at":"2026-09-25T18:45:00Z","end_at":null}""",
        )
    }

    @Test
    fun gear() {
        assertStat("gear", 4.0, """{"armor":"iron_chestplate","weapon":"diamond_sword","pickaxe":"iron_pickaxe"}""")
        assertStat("gear", 0.0, """{"armor":null,"weapon":null,"pickaxe":null}""", kelvin.uuid)
    }

    @Test
    fun advancements() {
        assertStat("advancements", 3.0, """{"goals":1,"challenges":1,"last":["Monsters Hunted","Diamonds!","Free the End"]}""")
    }

    @Test
    fun economy() {
        assertStat("economy", 2.0, """{"emeralds_spent":7}""")
    }

    @Test
    fun places() {
        assertStat(
            "places", null,
            """{"base":{"dim":"overworld","x":16,"y":64,"z":16,"n":3},
               "bed":{"dim":"overworld","x":12,"y":64,"z":12,"n":2},
               "mine":{"dim":"overworld","x":112,"y":-28,"z":208,"n":3}}""",
        )
    }

    @Test
    fun narrator() {
        // Six messages to the Narrator (to_narrator >= 0.6) and a prayer; "salut Mimosa" is not one.
        assertStat("narrator", 7.0, """{"prayers":1,"punishments":1,"blessings":1,"gifts":1,"challenges_won":1,"challenges_lost":1}""")
        assertStat("narrator", 3.0, """{"prayers":0,"punishments":0,"blessings":0,"gifts":0,"challenges_won":1,"challenges_lost":1}""", mimosa.uuid)
    }

    @Test
    fun `luck has no z without three players of five hours`() {
        // 3 rare ores of 10 blocks, a saddle among 4 fish, 1 challenge of 2, 3 absurd deaths in 3 h.
        assertStat("luck", 50.0, """{"rare_per_1k_mined":300.0,"treasure_fish":0.25,"challenges_ratio":0.5,"absurd_deaths_per_h":1.0}""")
    }

    @Test
    fun `style sums to a hundred and needs an hour of play`() {
        val style = stat(bartok.uuid, "style")
        assertNull(style.value)
        val shares = style.detail!!.mapValues { it.value.jsonPrimitive.int }
        assertEquals(listOf("explorer", "fighter", "builder", "miner", "farmer", "social"), shares.keys.toList())
        assertTrue(shares.values.sum() in 99..101, "$shares")
        assertEquals(SheetStat("style", null, null, NOW), stat(kelvin.uuid, "style")) // 50 minutes of play
    }

    @Test
    fun signals() {
        assertStat("signals", 0.0, null) // Bartok: heuristic verdicts only
        assertStat("signals", 29.0, null, kelvin.uuid) // one short
        assertEquals(30.0, stat(mimosa.uuid, "signals").value) // her heuristic rows are left out (SignalsTest)
    }
}
