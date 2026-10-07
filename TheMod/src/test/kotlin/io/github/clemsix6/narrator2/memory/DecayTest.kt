package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.testing.MemorySamples
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decay and reinforcement formulas (M3 §9), and the 500-hour simulation of §9.4. */
class DecayTest {
    private val hour = 3_600L

    @Test
    fun `the initial half-life grows with the importance`() {
        val expected = mapOf(1 to 4L, 3 to 12L, 5 to 38L, 7 to 118L, 8 to 207L, 10 to 640L)
        expected.forEach { (importance, hours) -> assertEquals(hours, Decay.initialStability(importance).roundToLong(), "importance $importance") }
        assertEquals(Decay.initialStability(1), Decay.initialStability(0), "clamped to 1..10")
        assertEquals(Decay.initialStability(10), Decay.initialStability(12))
    }

    @Test
    fun `the strength halves every half-life, down to its floor`() {
        assertEquals(1.0, Decay.strength(0.0, 10.0))
        assertEquals(0.5, Decay.strength(10.0, 10.0), 1e-12)
        assertEquals(0.25, Decay.strength(20.0, 10.0), 1e-12)
        assertEquals(Decay.FLOOR, Decay.strength(1_000.0, 10.0), "never below the floor")
        assertEquals(1.0, Decay.strength(-5.0, 10.0), "a clock behind the row counts as no age")
        assertEquals(0.5, Decay.strength(4.0, 10 * hour, 14 * hour), 1e-12, "seconds of the world clock")
        assertEquals(Decay.hoursToFloor(4.0), 4.0 * 5.643856, 1e-5)
        assertEquals(Decay.FLOOR, Decay.strength(Decay.hoursToFloor(4.0), 4.0), 1e-12)
    }

    @Test
    fun `a reinforcement grows the half-life, the more so when the memory was nearly forgotten`() {
        assertEquals(15.0, Decay.reinforce(10.0, 1.0, Decay.GAIN_USED), 1e-12, "fresh: × 1.5")
        assertEquals(24.8, Decay.reinforce(10.0, Decay.FLOOR, Decay.GAIN_USED), 1e-12, "at the floor: × 2.48")
        assertEquals(10.0 * (1 + 0.5 * 1.0), Decay.reinforce(10.0, 0.5, Decay.GAIN_RECALL_TOOL), 1e-12)
        assertEquals(10.0 * (1 + 0.2 * 1.0), Decay.reinforce(10.0, 0.5, Decay.GAIN_SHOWN), 1e-12)
        assertEquals(Decay.MAX_STABILITY_HOURS, Decay.reinforce(4_000.0, 0.02, 1.0), "capped at 5 000 h")
    }

    @Test
    fun `each channel has its gain, and the dream does not reinforce`() {
        assertEquals(1.0, Decay.gain(RecallChannel.USED))
        assertEquals(0.5, Decay.gain(RecallChannel.RECALL_TOOL))
        listOf(RecallChannel.BRIEFING, RecallChannel.JOIN, RecallChannel.REMINISCENCE, RecallChannel.PROFILE)
            .forEach { assertEquals(0.2, Decay.gain(it), it.id) }
        assertEquals(0.0, Decay.gain(RecallChannel.DREAM))
        assertNull(Decay.reinforce(10.0, 0, 50 * hour, RecallChannel.DREAM))

        val used = Decay.reinforce(10.0, 0, 10 * hour, RecallChannel.USED)!!
        assertEquals(10.0 * (1 + 1.0 * (0.5 + 0.5)), used.stabilityHours, 1e-9)
        assertEquals(10 * hour, used.reinforcedPlaySeconds, "the strength starts again from 1")
        assertEquals(1.0, Decay.strength(used.stabilityHours, used.reinforcedPlaySeconds, 10 * hour))
    }

    @Test
    fun `a trivial memory is archivable 50 hours after reaching the floor`() {
        val toFloor = Decay.hoursToFloor(4.0)
        fun at(hours: Double) = (hours * hour).roundToLong()
        assertTrue(Decay.archivable(1, 4.0, 0, at(toFloor + 50.5)))
        assertTrue(Decay.archivable(2, 4.0, at(10.0), at(10.0 + toFloor + 51)))
        assertFalse(Decay.archivable(2, 4.0, 0, at(toFloor + 49.5)), "not yet 50 h at the floor")
        assertFalse(Decay.archivable(3, 4.0, 0, at(1_000.0)), "importance 3 is never archived")
    }

    @Test
    fun `a trait's confidence halves every 200 hours of its player's play`() {
        assertEquals(0.8, Decay.traitConfidence(0.8, 100 * hour, 100 * hour), 1e-12)
        assertEquals(0.4, Decay.traitConfidence(0.8, 100 * hour, 300 * hour), 1e-12)
        assertEquals(0.2, Decay.traitConfidence(0.8, 0, 400 * hour), 1e-12)
        assertTrue(Decay.traitVisible(Decay.traitConfidence(0.8, 0, 400 * hour)))
        assertFalse(Decay.traitVisible(Decay.traitConfidence(0.8, 0, 401 * hour)), "below 0.2: not shown")
        val trait = MemorySamples.trait.copy(confidence = 0.6, setPlayedSeconds = 50 * hour)
        assertEquals(0.3, Decay.traitConfidence(trait, 250 * hour), 1e-12)
    }

    @Test
    fun `changing pole caps the confidence at one half`() {
        assertEquals(0.5, Decay.confidenceOnSet(0.9, -1, 1))
        assertEquals(0.3, Decay.confidenceOnSet(0.3, 1, -1), "already lower")
        assertEquals(0.9, Decay.confidenceOnSet(0.9, 1, 1), "same pole")
        assertEquals(0.9, Decay.confidenceOnSet(0.9, null, -1), "a new trait")
        assertEquals(0.9, Decay.confidenceOnSet(0.9, 0, 0), "a free trait has no pole")
    }

    @Test
    fun `a gag served three times within 20 hours of world play is worn`() {
        assertTrue(Decay.gagWorn(3, 19.9))
        assertTrue(Decay.gagWorn(5, 0.5))
        assertFalse(Decay.gagWorn(3, 20.0))
        assertFalse(Decay.gagWorn(2, 1.0), "fewer than three uses")
    }

    /**
     * The simulation of M3 §9.4 (`.research/spikes/m3/DecaySim.java`, same seed and draws) run on [Decay]:
     * 500 hours of world play, 10 memories an hour, 5 recalls an hour among 30 candidates weighted by score.
     */
    @Test
    fun `nothing saturates in 500 hours`() {
        class Mem(val importance: Int, var stability: Double, var reinforcedAt: Double) {
            fun strength(t: Double) = Decay.strength(t - reinforcedAt, stability)
        }
        val random = Random(42)
        val buckets = intArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 8, 8, 9, 10)
        val memories = ArrayList<Mem>()
        var t = 0.0
        while (t < 500) {
            val importance = buckets[random.nextInt(buckets.size)]
            memories += Mem(importance, Decay.initialStability(importance), t)
            if (random.nextDouble() < 0.5) {
                val candidates = List(30) { memories[random.nextInt(memories.size)] }
                val weights = candidates.map { Math.pow((0.4 + 0.4 * it.importance / 10.0) * it.strength(t), 2.0) }
                var r = random.nextDouble() * weights.sum()
                var pick = candidates[0]
                for (c in candidates.indices) {
                    r -= weights[c]
                    if (r <= 0) {
                        pick = candidates[c]
                        break
                    }
                }
                val gain = if (random.nextDouble() < 0.5) Decay.GAIN_USED else Decay.GAIN_SHOWN
                pick.stability = Decay.reinforce(pick.stability, pick.strength(t), gain)
                pick.reinforcedAt = t
            }
            t += 0.1
        }

        fun near(expected: Int, actual: Int, what: String) =
            assertTrue(abs(actual - expected) <= expected * 0.10 + 1, "$what: expected $expected ± 10 %, got $actual")

        val end = 500.0
        assertEquals(5_000, memories.size)
        near(1_988, memories.count { it.strength(end) > 0.2 }, "memories above 0.2")
        near(2_230, memories.count { it.strength(end) <= Decay.FLOOR + 1e-9 }, "memories at the floor")
        val created = mapOf(1 to 481, 3 to 489, 5 to 757, 7 to 510, 8 to 507, 10 to 274)
        val strong = mapOf(1 to 7, 3 to 22, 5 to 161, 7 to 390, 8 to 506, 10 to 274)
        for ((importance, count) in created) {
            val ofImportance = memories.filter { it.importance == importance }
            near(count, ofImportance.size, "importance $importance created")
            near(strong.getValue(importance), ofImportance.count { it.strength(end) > 0.2 }, "importance $importance above 0.2")
        }
        assertTrue(memories.any { it.stability == Decay.MAX_STABILITY_HOURS }, "a memory often recalled reaches the cap")
        assertTrue(memories.all { it.stability <= Decay.MAX_STABILITY_HOURS })
    }
}
