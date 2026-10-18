package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import io.github.clemsix6.narrator2.testing.MemorySamples
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** The play clocks (M3 §3.2): recomputed from `sessions`, ticked with a cap, the players' own clocks. */
class DuckPlayClockTest {
    private val fixture = JournalFixture()
    private val start: Instant = fixture.clock.now
    private val store = FakeMemoryStore(fixture.clock)
    private val players = PlayerStates()
    private val clock = DuckPlayClock(store, fixture.journal, players)

    @AfterEach
    fun close() = fixture.close()

    private fun at(minutes: Long): Instant = start + Duration.ofMinutes(minutes)

    private fun ms(minutes: Long): Long = at(minutes).toEpochMilli()

    private fun session(id: Long, player: PlayerRef, from: Long, to: Long?) {
        fixture.db.writer.prepareStatement(
            "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, end_reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, id)
            statement.setObject(2, player.uuid)
            statement.setString(3, player.name)
            statement.setObject(4, OffsetDateTime.ofInstant(at(from), ZoneOffset.UTC))
            statement.setObject(5, to?.let { OffsetDateTime.ofInstant(at(it), ZoneOffset.UTC) })
            statement.setObject(6, to?.let { ((it - from) * 60).toInt() })
            statement.setString(7, if (to == null) null else "leave")
            statement.execute()
        }
    }

    private fun playtime(player: PlayerRef, seconds: Long) {
        fixture.db.writer.prepareStatement("INSERT INTO players VALUES (?, ?, ?, ?, 1, ?, false)").use { statement ->
            statement.setObject(1, player.uuid)
            statement.setString(2, player.name)
            statement.setObject(3, OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
            statement.setObject(4, OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
            statement.setLong(5, seconds)
            statement.execute()
        }
    }

    private fun online(player: PlayerRef, sessionId: Long, joinedAt: Long) = players.put(
        LivePlayer(
            player, sessionId, joinedAt, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false,
            20f, 20f, 20, null, null, joinedAt, false,
        ),
    )

    @Test
    fun `the first load recomputes the world clock from overlapping sessions`() = runBlocking {
        session(1, bartok, 0, 60)
        session(2, mimosa, 30, 90) // overlaps the first
        session(3, mimosa, 10, 20) // inside the first
        session(4, bartok, 100, 110)
        session(5, mimosa, 120, 150)
        session(6, bartok, 200, null) // left open by a crash: counts for nothing
        clock.load(ms(300))
        assertEquals((90 + 10 + 30) * 60L, clock.worldPlaySeconds(ms(300)))
        assertEquals("7800", store.state[DuckPlayClock.WORLD_KEY], "saved at once")
        assertEquals(ms(300).toString(), store.state[DuckPlayClock.SAVED_AT_KEY])
    }

    @Test
    fun `a saved clock adds the sessions played since its save`() = runBlocking {
        session(1, bartok, 0, 60)
        session(2, mimosa, 95, 110) // straddles the save: 10 minutes after it
        session(3, bartok, 120, 150)
        store.state[DuckPlayClock.WORLD_KEY] = "1000"
        store.state[DuckPlayClock.SAVED_AT_KEY] = ms(100).toString()
        clock.load(ms(300))
        assertEquals(1_000 + (10 + 30) * 60L, clock.worldPlaySeconds(ms(300)))
    }

    @Test
    fun `the world clock counts while someone is online, by steps capped at two minutes`() = runBlocking {
        clock.load(ms(0))
        clock.tick(ms(1))
        assertEquals(0, clock.worldPlaySeconds(ms(1)), "nobody online")
        online(bartok, 1, ms(1))
        clock.tick(ms(2))
        assertEquals(60, clock.worldPlaySeconds(ms(2)))
        clock.tick(ms(12)) // ten minutes without a tick: a frozen server
        assertEquals(60 + 120, clock.worldPlaySeconds(ms(12)))
        assertEquals(60 + 120 + 30, clock.worldPlaySeconds(ms(12) + 30_000), "read between two ticks")
        assertEquals(60 + 120 + 120, clock.worldPlaySeconds(ms(60)), "the pending time is capped too")
        players.remove(bartok.uuid)
        assertEquals(180, clock.worldPlaySeconds(ms(60)), "nobody online: nothing pending")
        clock.save(ms(60))
        assertEquals("180", store.state[DuckPlayClock.WORLD_KEY])
    }

    @Test
    fun `joins and leaves step the clocks`() = runBlocking {
        playtime(bartok, 3_600)
        clock.load(ms(0))
        val bus = EventBus(fixture.clock, { 0L }, 1)
        clock.subscribe(bus)
        fixture.clock.now = at(5) // empty server for five minutes: they do not count
        bus.publish(PlayerJoined(bartok, Pos("minecraft:overworld", 0, 64, 0), 1, false, false))
        online(bartok, 1, ms(5))
        fixture.clock.now = at(5) + Duration.ofSeconds(90)
        assertEquals(3_600 + 90, clock.playerPlaySeconds(bartok.uuid, fixture.clock.millis()), "closed sessions plus the running one")
        bus.publish(PlayerLeft(bartok, Pos("minecraft:overworld", 0, 64, 0), 1, 90, LeaveReason.LEAVE))
        players.remove(bartok.uuid)
        assertEquals(90, clock.worldPlaySeconds(ms(30)), "time played until the leave, nothing after")
        assertEquals(3_600 + 90, clock.playerPlaySeconds(bartok.uuid, ms(30)), "the session joined the closed time")
        assertEquals(0, clock.playerPlaySeconds(mimosa.uuid, ms(30)), "a player never seen")
        bus.close()
    }

    @Test
    fun `a player's clock adds the running session to the closed ones`() = runBlocking {
        playtime(bartok, 3_600)
        playtime(mimosa, 120)
        clock.load(ms(0))
        online(bartok, 7, ms(10))
        assertEquals(3_600 + 30 * 60L, clock.playerPlaySeconds(bartok.uuid, ms(40)))
        assertEquals(120, clock.playerPlaySeconds(mimosa.uuid, ms(40)), "offline: closed sessions only")
    }

    @Test
    fun `a player's clock at a past time counts the sessions until then, not those closed after`() {
        session(1, bartok, 0, 60)
        session(2, bartok, 100, null) // being played
        session(3, mimosa, 30, 90)
        fun clockAt(player: PlayerRef, minutes: Long) = DuckPlayClock.playerSecondsAt(fixture.db.writer, player.uuid, at(minutes))
        assertEquals(50 * 60L, clockAt(bartok, 50), "inside a session that closed later")
        assertEquals(60 * 60L, clockAt(bartok, 80), "between two sessions")
        assertEquals((60 + 20) * 60L, clockAt(bartok, 120), "the running session until then")
        assertEquals(10 * 60L, clockAt(mimosa, 40))
        assertEquals(0L, clockAt(mimosa, 20), "before the first session")
        assertEquals(0L, DuckPlayClock.playerSecondsAt(fixture.db.writer, java.util.UUID(7, 7), at(120)), "a player never seen")
    }

    @Test
    fun `world play between two times clips the sessions and runs those of players online`() = runBlocking {
        session(1, bartok, 0, 60)
        session(2, mimosa, 100, null) // Mimosa is online
        session(3, bartok, 110, null) // left open by a crash
        clock.load(ms(0))
        online(mimosa, 2, ms(100))
        assertEquals((30 + 30) * 60L, clock.worldPlayBetween(at(30), at(130)))
        assertEquals(0, clock.worldPlayBetween(at(130), at(30)))
    }

    private companion object {
        val bartok = MemorySamples.bartok
        val mimosa = MemorySamples.mimosa
    }
}
