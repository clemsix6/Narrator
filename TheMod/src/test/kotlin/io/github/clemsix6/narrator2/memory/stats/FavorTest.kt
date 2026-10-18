package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.day1
import io.github.clemsix6.narrator2.memory.MemoryDataset.day2
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import io.github.clemsix6.narrator2.memory.stats.Favor.Judged
import io.github.clemsix6.narrator2.memory.stats.Favor.Rated
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Favor, respect and attention (M3 §6.2), with the values measured for the design (§15.1: −25, −62, −41 → +15). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FavorTest {
    private val fixture = MemoryDataset.journal()

    @AfterAll
    fun close() = fixture.close()

    private fun hours(h: Double) = (h * 3_600).roundToInt().toLong()

    @Test
    fun `one insult alone is -25, five in a row -62`() {
        assertEquals(-25.0, Favor.favor(listOf(Rated(0, -3)), 0), 1e-9)
        val five = (0 until 5).map { Rated(hours(it * 0.2), -3) }
        assertEquals(-62, Favor.favor(five, hours(1.0)).roundToInt())
    }

    /**
     * The design's measure (`.research/spikes/m3/DecaySim.java`, `decay-results.txt`): a player insolent for 20 h of play
     * (70 % of -2, else +1), then polite for 80 h (80 % of +1, else -1), three interactions an hour. The spike drew them
     * from `Random(42)` after its decay simulation; the same draws are replayed here, so the values are the spike's.
     */
    @Test
    fun `an insolent player falls to -41, then climbs back to 0 and settles at +15`() {
        val random = Random(42)
        var memories = 0
        var t = 0.0
        while (t < 500) { // the decay simulation's draws, which come first
            random.nextInt(20)
            memories++
            if (random.nextDouble() < 0.5) {
                repeat(30) { random.nextInt(memories) }
                random.nextDouble()
                random.nextDouble()
            }
            t += 0.1
        }
        val rated = mutableListOf<Rated>()
        val favorAt = mutableMapOf<Int, Int>()
        var h = 0.0
        while (h <= 100) {
            val valence = if (h < 20) (if (random.nextDouble() < 0.7) -2 else 1) else (if (random.nextDouble() < 0.8) 1 else -1)
            rated += Rated(hours(h), valence)
            if (abs(h - Math.round(h)) < 1e-9 && Math.round(h) % 10 == 0L) favorAt[Math.round(h).toInt()] = Favor.favor(rated, hours(h)).roundToInt()
            h += 1.0 / 3
        }
        assertEquals(mapOf(0 to 8, 10 to -34, 20 to -41, 30 to -13, 40 to 0, 50 to 8, 60 to 10, 70 to 13, 80 to 14, 90 to 15, 100 to 15), favorAt)
        assertEquals(8, Favor.favor(rated, hours(500.0)).roundToInt()) // 400 h without interaction: back toward neutral
    }

    @Test
    fun `favor never saturates and an interaction in the future of the clock is not counted`() {
        val many = (0 until 1_000).map { Rated(it * 60L, -3) }
        assertTrue(Favor.favor(many, 60_000) > -100)
        assertEquals(0.0, Favor.favor(listOf(Rated(100, -3)), 50))
    }

    @Test
    fun `the trend compares with five hours of play earlier`() {
        val rated = listOf(Rated(0, 2), Rated(hours(6.0), -3))
        val trend = Favor.trend(rated, hours(6.0))
        assertEquals(Favor.favor(rated, hours(6.0)) - Favor.favor(rated, hours(1.0)), trend, 1e-9)
        assertEquals("↘", Favor.arrow(trend))
        assertEquals("↗", Favor.arrow(5.1))
        assertEquals("", Favor.arrow(-5.0))
        assertEquals("", Favor.arrow(null))
    }

    @Test
    fun `respect weighs the latest messages most and needs five messages`() {
        assertNull(Favor.respect(List(4) { Judged.RESPECTFUL }, 4))
        assertEquals(100, Favor.respect(List(5) { Judged.RESPECTFUL }, 5))
        assertEquals(0, Favor.respect(List(5) { Judged.DISRESPECTFUL }, 5))
        assertEquals(50, Favor.respect(List(5) { Judged.NEUTRAL }, 5))
        val lateInsult = Favor.respect(List(5) { Judged.RESPECTFUL } + Judged.DISRESPECTFUL, 6)!!
        val earlyInsult = Favor.respect(listOf(Judged.DISRESPECTFUL) + List(5) { Judged.RESPECTFUL }, 6)!!
        assertTrue(lateInsult < earlyInsult, "$lateInsult < $earlyInsult")
    }

    @Test
    fun `Bartok's reading`() {
        val reading = MemoryDataset.read(fixture) { Favor.read(it, bartok.uuid, NOW) }!!
        assertEquals(10_800, reading.playedSeconds)
        // Counted: the insult (-2 at 960 s of play) and the compliment (+1 at 9000 s). Not counted: the punishment, the
        // blessing and the gift (the Narrator's answers), the archived insult.
        val counted = listOf(Rated(960, -2), Rated(9_000, 1))
        assertEquals(Favor.favor(counted, 10_800), reading.favor!!, 1e-9)
        assertEquals(-5, reading.favor!!.roundToInt())
        assertEquals(reading.favor!!, reading.trend!!, 1e-9) // nothing 5 h of play earlier: neutral
        // Newest first: ok (low confidence), « t'es nul » (hostile), mdr (mocking), two respectful, « t'es nul », the
        // disrespectful insult twice. 50 + 50 × (0.2497 − 0.4858) = 38.
        assertEquals(6, reading.messages)
        assertEquals(1, reading.disrespect)
        assertEquals(38, reading.respect)
        // Last attention: the say at 20:40 (the denied smite and the sql read do not count) → 20 min of play.
        assertEquals(day2("20:40"), reading.lastAttentionAt)
        assertEquals(1_200, reading.attentionDebtSeconds)
    }

    @Test
    fun `Mimosa has too few messages for respect, Kelvin never had any attention`() {
        val (mimosaReading, kelvinReading) = MemoryDataset.read(fixture) { listOf(Favor.read(it, mimosa.uuid, NOW)!!, Favor.read(it, kelvin.uuid, NOW)!!) }
        assertEquals(MemoryDataset.MIMOSA_ADDRESSED, mimosaReading.messages)
        assertNull(mimosaReading.respect)
        assertEquals(day1("19:00"), mimosaReading.lastAttentionAt)
        assertEquals(1_800, mimosaReading.attentionDebtSeconds) // her play from 19:00 to 19:30
        assertNull(kelvinReading.favor)
        assertNull(kelvinReading.trend)
        assertNull(kelvinReading.lastAttentionAt)
        assertEquals(3_000, kelvinReading.attentionDebtSeconds)
        assertNull(MemoryDataset.read(fixture) { Favor.read(it, UUID.randomUUID(), NOW) })
    }

    @Test
    fun `situation lines`() {
        val readings = MemoryDataset.read(fixture) { connection ->
            listOf(bartok, mimosa, kelvin).map { it to Favor.read(connection, it.uuid, NOW)!! }
        }
        assertEquals(
            listOf("Favor: Bartok -5 ↘ · Mimosa +16 ↗", "Attention: Kelvin has had nothing from you for 50m of play · Mimosa for 30m"),
            Favor.situationLines(readings, attentionMinutes = 25),
        )
        assertEquals(listOf("Favor: Bartok -5 ↘ · Mimosa +16 ↗"), Favor.situationLines(readings, attentionMinutes = 60))
        assertEquals(emptyList(), Favor.situationLines(emptyList(), 40))
    }

    @Test
    fun `the SQL formula given to the mem_favor view gives the same favor`() {
        val sql = """
            WITH clock AS (
                SELECT pl.player_uuid, pl.playtime_s + coalesce((SELECT sum(epoch(CAST(? AS TIMESTAMPTZ) - s.joined_at)) FROM sessions s
                    WHERE s.player_uuid = pl.player_uuid AND s.left_at IS NULL), 0) AS played_s
                FROM players pl),
            w AS (
                SELECT i.player_uuid, i.valence,
                       0.7 * pow(2, -(c.played_s - i.played_s) / 3600.0 / 10) + 0.3 * pow(2, -(c.played_s - i.played_s) / 3600.0 / 100) AS w
                FROM interactions i JOIN clock c USING (player_uuid)
                WHERE i.counts AND NOT i.archived AND i.played_s <= c.played_s)
            SELECT player_uuid, 100 * sum(valence * w) / (3 * (sum(w) + 3)) AS favor FROM w GROUP BY 1
        """.trimIndent()
        val fromSql = MemoryDataset.read(fixture) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, NOW.toString())
                statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1) as UUID, rows.getDouble(2)) } }
            }
        }
        assertEquals(setOf(bartok.uuid, mimosa.uuid), fromSql.keys)
        val fromKotlin = MemoryDataset.read(fixture) { connection -> fromSql.keys.associateWith { Favor.read(connection, it, NOW)!!.favor!! } }
        fromSql.forEach { (player, favor) -> assertEquals(fromKotlin.getValue(player), favor, 1e-9) }
    }
}
