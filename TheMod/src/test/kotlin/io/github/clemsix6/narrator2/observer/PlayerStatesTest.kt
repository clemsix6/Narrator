package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.nether
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The player states follow joins, deaths, respawns and leaves from the bus (no live player in unit tests). */
class PlayerStatesTest {
    private val clock = TestClock()
    private val bus = EventBus(clock, { 0L }, 1)
    private val states = PlayerStates().also { it.subscribe(bus) { null } }

    private fun died(at: Pos) = PlayerDied(steve, at, "minecraft:fall", killer = null, weapon = null, message = null, xpLevel = 3, keptInventory = false, narrator = false)

    @Test
    fun `a join makes the player online, with the session of the event`() {
        bus.publish(PlayerJoined(steve, here, sessionId = 42, firstTime = true, isOp = true))

        val live = assertNotNull(states.get(steve.uuid))
        assertEquals(42, live.sessionId)
        assertEquals(clock.millis(), live.joinedAt)
        assertEquals(here, live.pos)
        assertEquals(true, live.isOp)
        assertEquals("survival", live.gameMode)
        assertSame(live, states.byName("STEVE"), "names match ignoring case")
        assertEquals(listOf(live), states.online)
    }

    @Test
    fun `a death is dated and a respawn moves the player`() {
        bus.publish(PlayerJoined(steve, here, sessionId = 1, firstTime = false, isOp = false))
        clock.advance(Duration.ofMinutes(3))
        bus.publish(died(nether))

        val dead = assertNotNull(states.get(steve.uuid))
        assertEquals(clock.millis(), dead.lastDeathAt)
        assertEquals(0f, dead.health)
        assertEquals(nether, dead.pos)
        assertEquals(clock.millis(), states.online.single().lastDeathAt, "the snapshot follows at once")

        clock.advance(Duration.ofSeconds(10))
        bus.publish(PlayerRespawned(steve, here, alive = false))
        val back = assertNotNull(states.get(steve.uuid))
        assertEquals(here, back.pos)
        assertEquals(back.maxHealth, back.health)
        assertEquals(dead.lastDeathAt, back.lastDeathAt, "the death stays known after the respawn")
    }

    @Test
    fun `a leave removes the player, and events about players offline change nothing`() {
        bus.publish(PlayerJoined(steve, here, sessionId = 1, firstTime = false, isOp = false))
        clock.advance(Duration.ofSeconds(1))
        bus.publish(PlayerJoined(alex, nether, sessionId = 2, firstTime = false, isOp = false))
        assertEquals(listOf(steve, alex), states.online.map { it.ref }, "in order of arrival")

        bus.publish(PlayerLeft(steve, here, sessionId = 1, sessionSeconds = 60, reason = LeaveReason.LEAVE))
        bus.publish(died(here))

        assertNull(states.get(steve.uuid))
        assertEquals(listOf(alex), states.online.map { it.ref })
    }

    @Test
    fun `other events are not delivered to the player states`() {
        bus.publish(PlayerJoined(steve, here, sessionId = 1, firstTime = false, isOp = false))
        val before = states.get(steve.uuid)
        bus.publish(
            EntityKilled(steve, nether, EntityRef("minecraft:zombie", null, null), weapon = null, pvp = false, tamed = false),
        )
        assertSame(before, states.get(steve.uuid))
        assertEquals(1, bus.stats().single { it.name == "player_states" }.delivered)
    }
}
