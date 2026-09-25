package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.StructureLeft
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.event.UndergroundChanged
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The thresholds of M1_M2 §7.4, on a [PlayerTrack] fed with readings one second apart. */
class SamplerRulesTest {
    private val overworld = "minecraft:overworld"
    private val plains = "minecraft:plains"
    private val visits = SetVisits()
    private val track = PlayerTrack(steve)

    /** Clock of the test, in milliseconds; each [read] advances it by one second first, except the first. */
    private var now = 0L
    private var started = false

    private class SetVisits : Visits {
        val biomes = mutableSetOf<String>()
        val structures = mutableSetOf<String>()

        override fun biome(id: String) = biomes.add(id)

        override fun structure(id: String) = structures.add(id)
    }

    private fun at(x: Int, y: Int = 64, z: Int = 0, dim: String = overworld) = Pos(dim, x, y, z)

    private fun obs(
        pos: Pos,
        biome: String = plains,
        structure: String? = null,
        underground: Boolean = false,
        health: Double = 20.0,
        vehicle: String? = null,
        sinceActionMs: Long = 0,
    ) = Observation(pos, biome, track.structureDue(pos, now), structure, underground, 15, 0, health, 20, vehicle, sinceActionMs)

    /** One examination a second later (the first one at time 0). */
    private fun read(
        pos: Pos,
        biome: String = plains,
        structure: String? = null,
        underground: Boolean = false,
        health: Double = 20.0,
        vehicle: String? = null,
        sinceActionMs: Long = 0,
    ): List<GameEvent> {
        if (started) now += 1_000 else started = true
        return track.examine(obs(pos, biome, structure, underground, health, vehicle, sinceActionMs), now, visits).events
    }

    /** Readings at [pos], one a second, for [seconds] seconds; returns everything published. */
    private fun stay(pos: Pos, seconds: Int): List<GameEvent> = (1..seconds).flatMap { read(pos) }

    private fun List<GameEvent>.rows() = filterIsInstance<PositionSampled>()

    private fun List<GameEvent>.row() = rows().singleOrNull()

    @Test
    fun `the first reading after a join writes a join row, then small moves write nothing`() {
        val first = read(at(0))
        assertEquals(SampleReason.JOIN, first.row()?.reason)
        assertEquals(at(0), first.row()?.pos)
        assertTrue(stay(at(1), 30).rows().isEmpty(), "moving a block is not worth a row")
    }

    @Test
    fun `16 blocks horizontally or 8 vertically is a move, capped at one row every 3 seconds`() {
        read(at(0))
        assertNull(read(at(20)).row(), "a second after the last row: capped")
        assertNull(read(at(20)).row(), "two seconds: still capped")
        assertEquals(SampleReason.MOVED, read(at(20)).row()?.reason, "three seconds: the move is written")
        repeat(2) { read(at(20)) }
        assertNull(read(at(35)).row(), "15 blocks is not a move")
        assertEquals(SampleReason.MOVED, read(at(36)).row()?.reason)
        repeat(2) { read(at(36)) }
        assertNull(read(at(36, y = 71)).row(), "7 blocks up is not a move")
        assertEquals(SampleReason.MOVED, read(at(36, y = 72)).row()?.reason, "8 blocks up is")
    }

    @Test
    fun `after a minute a move of 2 blocks is written, and after 5 minutes a still player gets a heartbeat`() {
        read(at(0))
        assertTrue(stay(at(1), 59).rows().isEmpty(), "one block in a minute")
        assertTrue(stay(at(1), 1).rows().isEmpty(), "60 s but only 1 block from the last row")
        assertEquals(SampleReason.INTERVAL, read(at(2)).row()?.reason, "60 s and 2 blocks")

        val quiet = (1..300).map { read(at(2)) }
        assertEquals(List(299) { null } + SampleReason.IDLE, quiet.map { it.row()?.reason }, "one presence row, 5 minutes after the last row")
        assertTrue(quiet.flatten().none { it is IdleChanged }, "a still player who keeps acting is not idle")
    }

    @Test
    fun `a jump of 64 blocks in the same dimension is a teleport, written at once`() {
        read(at(0))
        val jump = read(at(0, z = 64))
        val teleport = jump.filterIsInstance<Teleported>().single()
        assertEquals(at(0), teleport.from)
        assertEquals(64.0, teleport.distance)
        assertEquals(SampleReason.TELEPORT, jump.row()?.reason, "not capped: a second after the join row")
        assertEquals(0, jump.indexOf(teleport), "the teleport comes before its row")
        assertTrue(read(at(0, z = 127)).filterIsInstance<Teleported>().isEmpty(), "63 blocks is a fast move, not a teleport")
    }

    @Test
    fun `a dimension change is written at once, and is no teleport`() {
        read(at(0))
        val nether = read(at(0, dim = "minecraft:the_nether"))
        assertEquals(SampleReason.DIMENSION, nether.row()?.reason)
        assertTrue(nether.filterIsInstance<Teleported>().isEmpty())
    }

    @Test
    fun `a respawn writes a respawn row at once, and its jump is no teleport`() {
        read(at(0))
        track.restart(SampleReason.RESPAWN)
        val back = read(at(500))
        assertEquals(SampleReason.RESPAWN, back.row()?.reason)
        assertTrue(back.filterIsInstance<Teleported>().isEmpty())
    }

    @Test
    fun `getting on or off a vehicle is written, within the cap`() {
        read(at(0))
        assertNull(read(at(0), vehicle = "minecraft:horse").row(), "capped")
        read(at(0), vehicle = "minecraft:horse")
        val mounted = read(at(0), vehicle = "minecraft:horse").row()
        assertEquals(SampleReason.VEHICLE, mounted?.reason)
        assertEquals("minecraft:horse", mounted?.vehicle)
        repeat(2) { read(at(1), vehicle = "minecraft:horse") }
        assertEquals(SampleReason.VEHICLE, read(at(1)).row()?.reason, "dismounted")
    }

    @Test
    fun `underground must hold for 10 seconds before it changes, and the row reads it as it is`() {
        read(at(0, y = 30))
        val below = at(0, y = 20)
        val readings = (1..11).map { read(below, underground = true) }
        assertTrue(readings.take(10).flatten().none { it is UndergroundChanged }, "under 10 s")
        assertTrue(readings[10].filterIsInstance<UndergroundChanged>().single().underground, "10 s after the first reading below")
        assertTrue(readings.flatten().rows().single().underground, "the row of the move down reads underground at once")

        repeat(5) { read(below, underground = false) }
        val back = read(below, underground = true)
        assertTrue(back.none { it is UndergroundChanged }, "5 s at the surface does not count")
    }

    @Test
    fun `underground means no sky and well below sea level`() {
        assertTrue(SamplerRules.isUnderground(canSeeSky = false, y = 54, seaLevel = 63))
        assertFalse(SamplerRules.isUnderground(canSeeSky = false, y = 55, seaLevel = 63), "a cellar just under the surface")
        assertFalse(SamplerRules.isUnderground(canSeeSky = true, y = 10, seaLevel = 63), "an open pit")
        assertFalse(SamplerRules.isUnderground(canSeeSky = false, y = -60, seaLevel = -63), "a roof in a flat world")
    }

    @Test
    fun `a new player's first biome is a first visit, a returning player's is not an event`() {
        val first = read(at(0)).filterIsInstance<BiomeEntered>().single()
        assertEquals(plains, first.biome)
        assertTrue(first.firstVisit)

        val returning = PlayerTrack(steve)
        val events = returning.examine(obs(at(0)), now, visits).events
        assertTrue(events.filterIsInstance<BiomeEntered>().isEmpty(), "already visited")
    }

    @Test
    fun `a new biome must be read three times in a row`() {
        read(at(0))
        val desert = "minecraft:desert"
        assertTrue(read(at(1), biome = desert).filterIsInstance<BiomeEntered>().isEmpty())
        assertTrue(read(at(2), biome = plains).filterIsInstance<BiomeEntered>().isEmpty(), "back in plains: starts over")
        assertTrue(read(at(3), biome = desert).filterIsInstance<BiomeEntered>().isEmpty())
        assertTrue(read(at(4), biome = desert).filterIsInstance<BiomeEntered>().isEmpty())
        val entered = read(at(5), biome = desert).filterIsInstance<BiomeEntered>().single()
        assertEquals(desert, entered.biome)
        assertTrue(entered.firstVisit)

        val back = (1..3).flatMap { read(at(6), biome = plains) }.filterIsInstance<BiomeEntered>()
        assertEquals(listOf(false), back.map { it.firstVisit }, "plains again after three readings, not a first visit")
    }

    @Test
    fun `structures are looked up every 5 seconds or in a new chunk, and entries and exits are published`() {
        val village = "minecraft:village_plains"
        read(at(0))
        assertFalse(track.structureDue(at(1), now + 1_000), "same chunk, 1 s later")
        assertTrue(track.structureDue(at(16), now + 1_000), "another chunk")
        assertTrue(track.structureDue(at(1), now + 5_000), "5 s later")

        now += 4_000
        val entered = read(at(20), structure = village).filterIsInstance<StructureEntered>().single()
        assertEquals(village, entered.structure)
        assertTrue(entered.firstVisit)
        assertEquals(village, track.structure)

        now += 4_000
        val events = read(at(40), structure = null)
        assertEquals(village, events.filterIsInstance<StructureLeft>().single().structure)
        assertNull(track.structure)
        now += 4_000
        assertEquals(listOf(false), read(at(20), structure = village).filterIsInstance<StructureEntered>().map { it.firstVisit })
    }

    @Test
    fun `critical health is announced once and re-armed at 8`() {
        read(at(0))
        assertEquals(4.0, read(at(0), health = 4.0).filterIsInstance<HealthCritical>().single().health)
        assertTrue(read(at(0), health = 2.0).filterIsInstance<HealthCritical>().isEmpty(), "still low: announced once")
        assertTrue(read(at(0), health = 7.0).filterIsInstance<HealthCritical>().isEmpty())
        assertTrue(read(at(0), health = 3.0).filterIsInstance<HealthCritical>().isEmpty(), "not re-armed below 8")
        read(at(0), health = 8.0)
        assertEquals(1, read(at(0), health = 3.5).filterIsInstance<HealthCritical>().size, "re-armed")
    }

    @Test
    fun `idle after 5 minutes without action nor move, active again at the next action`() {
        read(at(0))
        // The player acts until second 10, then nothing.
        val waiting = (1..310).map { second -> read(at(0), sinceActionMs = if (second <= 10) 0 else (second - 10) * 1_000L) }
        assertTrue(waiting.take(309).flatten().none { it is IdleChanged }, "under 5 minutes")
        val idle = waiting.last().filterIsInstance<IdleChanged>().single()
        assertTrue(idle.idle)
        assertEquals(300, idle.idleSeconds)
        assertTrue(track.idle)

        val back = read(at(0), sinceActionMs = 0).filterIsInstance<IdleChanged>().single()
        assertFalse(back.idle)
        assertEquals(301, back.idleSeconds, "idle from second 10 to the action at second 311")
        assertFalse(track.idle)
    }

    @Test
    fun `a player who moves is never idle, whatever the actions say`() {
        read(at(0))
        val walking = (1..400).flatMap { read(at(it * 3), sinceActionMs = it * 1_000L) }
        assertTrue(walking.filterIsInstance<IdleChanged>().isEmpty())
    }

    @Test
    fun `a night is skipped when the clock jumps further than the ticks elapsed`() {
        assertTrue(SamplerRules.nightSkipped(clockAdvance = 11_000, ticksElapsed = 120))
        assertFalse(SamplerRules.nightSkipped(clockAdvance = 300, ticksElapsed = 300), "got up after 15 s")
        assertFalse(SamplerRules.nightSkipped(clockAdvance = 0, ticksElapsed = 600), "time does not advance")
    }
}
