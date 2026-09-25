package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.nether
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.SampleEvents.zombie
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hits of one player and damage type within 5 seconds of the first become one `DamageTaken`. */
class DamageCoalescerTest {
    private val published = mutableListOf<GameEvent>()
    private val coalescer = DamageCoalescer { published += it }
    private val lava = "minecraft:lava"
    private val mob = "minecraft:mob_attack"

    private fun taken() = published.filterIsInstance<DamageTaken>()

    @Test
    fun `hits of one type are summed until the window is 5 seconds old`() {
        coalescer.taken(steve, here, lava, 4.0, attacker = null, blocked = false, healthAfter = 16.0, now = 0)
        coalescer.taken(steve, nether, lava, 4.0, attacker = null, blocked = false, healthAfter = 12.0, now = 1_000)
        coalescer.taken(steve, nether, lava, 4.0, attacker = null, blocked = false, healthAfter = 8.0, now = 4_950)
        coalescer.tick(4_950)
        assertTrue(published.isEmpty(), "the window is still open")

        coalescer.tick(5_000)
        val merged = taken().single()
        assertEquals(DamageTaken(steve, here, lava, 12.0, hits = 3, attacker = null, blocked = false, healthAfter = 8.0), merged)
    }

    @Test
    fun `continuous damage gives one event per window`() {
        for (second in 0 until 12) coalescer.taken(steve, here, lava, 4.0, null, false, 20.0, now = second * 1_000L)
        coalescer.tick(12_000)
        assertEquals(listOf(5, 5), taken().map { it.hits }, "two full windows")
        coalescer.flush()
        assertEquals(listOf(5, 5, 2), taken().map { it.hits })
    }

    @Test
    fun `types and players are merged apart`() {
        coalescer.taken(steve, here, lava, 4.0, null, false, 16.0, now = 0)
        coalescer.taken(steve, here, mob, 3.0, zombie, true, 13.0, now = 100)
        coalescer.taken(alex, here, mob, 2.0, zombie, false, 18.0, now = 200)
        coalescer.taken(steve, here, mob, 3.0, null, false, 10.0, now = 300)
        coalescer.tick(5_300)

        assertEquals(listOf(steve to lava, steve to mob, alex to mob), taken().map { it.player to it.damageType }, "in the order they opened")
        val steveMob = taken()[1]
        assertEquals(6.0, steveMob.amount)
        assertEquals(zombie, steveMob.attacker, "the last known attacker")
        assertTrue(steveMob.blocked, "a shield blocked one of the hits")
        assertEquals(10.0, steveMob.healthAfter)
    }

    @Test
    fun `a player's pending damage is published before their death or departure, and only theirs`() {
        coalescer.taken(steve, here, lava, 4.0, null, false, 16.0, now = 0)
        coalescer.taken(alex, here, lava, 4.0, null, false, 16.0, now = 0)
        coalescer.flush(steve.uuid)
        assertEquals(listOf(steve), taken().map { it.player })

        coalescer.taken(steve, here, lava, 1.0, null, false, 15.0, now = 1_000)
        coalescer.tick(5_000)
        assertEquals(listOf(steve, alex), taken().map { it.player }, "alex's window closes on time; steve's new one is still open")
    }

    @Test
    fun `a hit after the window closed opens a new one even before the tick`() {
        coalescer.taken(steve, here, lava, 4.0, null, false, 16.0, now = 0)
        coalescer.taken(steve, here, lava, 4.0, null, false, 12.0, now = 5_000)
        assertEquals(listOf(1), taken().map { it.hits }, "the old window is published at once")
        coalescer.flush()
        assertEquals(listOf(1, 1), taken().map { it.hits })
    }
}
