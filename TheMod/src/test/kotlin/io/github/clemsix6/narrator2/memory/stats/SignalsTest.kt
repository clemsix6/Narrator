package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reflex model's signals gathered (M3 §6.3): mean, moving average and count, never from the heuristic, nothing under 30 messages. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignalsTest {
    private val fixture = MemoryDataset.journal()

    @AfterAll
    fun close() = fixture.close()

    private fun signals(player: java.util.UUID): io.github.clemsix6.narrator2.memory.SheetStat {
        refreshStats(fixture, listOf(player))
        return MemoryDataset.read(fixture) { SheetStats.read(it, player).getValue("signals") }
    }

    @Test
    fun `Mimosa's thirty measured verdicts, without her five heuristic ones`() {
        val stat = signals(mimosa.uuid)
        assertEquals(30.0, stat.value)
        val detail = stat.detail!!
        assertEquals(Signals.NAMES, detail.keys.toList())
        // Message i (oldest 0) has politeness (i % 5) / 4 and humor 0.2 then 0.8; the latest weighs 2^(-k/100), k from the newest.
        fun expected(value: (Int) -> Double): Pair<Double, Double> {
            val values = (0 until MemoryDataset.MIMOSA_MEASURED).map(value)
            val weights = values.indices.map { i -> 2.0.pow(-(values.lastIndex - i) / Signals.HALF_LIFE_MESSAGES) }
            return values.average() to values.indices.sumOf { values[it] * weights[it] } / weights.sum()
        }
        for ((name, value) in listOf(
            "politeness" to MemoryDataset::mimosaPoliteness,
            "humor" to MemoryDataset::mimosaHumor,
            "boastfulness" to { _: Int -> 0.1 },
        )) {
            val (mean, ewma) = expected(value)
            val signal = detail.getValue(name).jsonObject
            assertEquals(mean, signal.getValue("mean").jsonPrimitive.double, 0.0011, "$name mean")
            assertEquals(ewma, signal.getValue("ewma").jsonPrimitive.double, 0.0011, "$name ewma")
            assertEquals(30, signal.getValue("n").jsonPrimitive.int)
        }
        // Humor rose lately: the moving average is above the mean.
        val humor = detail.getValue("humor").jsonObject
        assertTrue(humor.getValue("ewma").jsonPrimitive.double > humor.getValue("mean").jsonPrimitive.double)
    }

    @Test
    fun `heuristic verdicts give no signal and under thirty messages there is none`() {
        val heuristicOnly = signals(bartok.uuid)
        assertEquals(0.0, heuristicOnly.value)
        assertNull(heuristicOnly.detail)
        val tooFew = signals(kelvin.uuid)
        assertEquals(MemoryDataset.KELVIN_MEASURED.toDouble(), tooFew.value)
        assertNull(tooFew.detail)
    }
}
