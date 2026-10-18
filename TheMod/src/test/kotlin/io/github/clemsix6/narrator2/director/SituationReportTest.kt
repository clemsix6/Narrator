package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.DisabledMemory
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeWorkers
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.TaskRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class SituationReportTest {
    private val clock = TestClock(Instant.parse("2026-09-25T21:32:41Z"))
    private val now = clock.millis()
    private val players = PlayerStates()
    private val guards = FakeGuards()
    private val tasks = mutableListOf<TaskRecord>()
    private val workers = object : WorkersControl by FakeWorkers() {
        override fun list(): List<TaskRecord> = tasks.toList()
    }
    private val services = TestServices(clock = clock, players = players, guards = guards, workers = workers)
    private val digest = ActivityDigest(clock)
    private val report = SituationReport(services, digest, ZoneOffset.UTC)
    private val world = Result.success(WorldTime(37, "night", "clear"))

    private fun ref(name: String) = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name)

    private fun live(
        name: String,
        onlineFor: Long,
        pos: Pos = Pos("minecraft:overworld", 12, -58, 40),
        gameMode: String = "survival",
        biome: String? = "minecraft:dripstone_caves",
        structure: String? = null,
        underground: Boolean = true,
        health: Float = 14f,
        food: Int = 12,
        lastDeathAt: Long? = null,
        idle: Boolean = false,
    ) = LivePlayer(ref(name), 1, now - onlineFor, false, gameMode, pos, biome, structure, underground, health, 20f, food, null, lastDeathAt, now, idle)

    @Test
    fun `the situation, reference`() {
        players.put(live("Bartok", onlineFor = 72 * 60_000L))
        players.put(live("Mimosa", onlineFor = 25 * 60_000L, health = 0f, lastDeathAt = now - 60_000))
        players.put(
            live(
                "clemsix6", onlineFor = 5 * 60_000L, pos = Pos("minecraft:the_nether", 3, 72, 9), gameMode = "creative", biome = "minecraft:nether_wastes",
                structure = "minecraft:fortress", underground = false, health = 20f, food = 20, idle = true,
            ),
        )
        guards.protectedPlayers += ref("clemsix6").uuid
        guards.active[ref("Bartok").uuid] = listOf(ActiveEffect("effect", "slowness II", now + 80_000, harmful = true))
        // Chaos 3: one death per played hour; Mimosa's was spent, clemsix6 is protected.
        guards.deaths[ref("Mimosa").uuid] = 0
        listOf(-54, -51, -52).forEach { y -> digest.record(Envelope(1, now, 0, BlockBroken(ref("Bartok"), Pos("minecraft:overworld", 0, y, 0), "minecraft:diamond_ore", null, false, true, null))) }
        repeat(2) { digest.record(Envelope(1, now, 0, BlockBroken(ref("Bartok"), Pos("minecraft:overworld", 0, -50, 0), "minecraft:stone", null, false, true, null))) }
        tasks += TaskRecord(13, Role.ANALYST, "where does Bartok mine?", TaskRecord.Status.RUNNING, Instant.ofEpochMilli(now - 15_000), Instant.ofEpochMilli(now - 12_000), null, 40, "analyst-1", null, null)
        tasks += TaskRecord(14, Role.ANALYST, "who did Mimosa trade with?", TaskRecord.Status.QUEUED, Instant.ofEpochMilli(now - 2_000), null, null, 41, null, null, null)
        tasks += TaskRecord(12, Role.ANALYST, "done already", TaskRecord.Status.DONE, Instant.ofEpochMilli(now - 60_000), null, null, 39, null, "ok", null)
        val reminders = listOf(PendingReminder(3, "check Bob's village", now + 439_000))
        val actions = listOf(DirectorAction(now - 12 * 60_000L, "say", emptyList()), DirectorAction(now - 8 * 60_000L, "apply_effect", listOf("Bartok")))

        assertEquals(
            """
            <situation now="21:32:41" day="37" time="night" weather="clear">
            Bartok: overworld underground y=-58 dripstone_caves · hp 14/20 food 12 · online 1h12m · last 5m: mined 5 (3 diamond_ore y=-54..-51, stone 2)
            Mimosa: overworld, dead · online 25m · protected from hostile actions until 21:36 (died)
            clemsix6: the_nether creative in fortress y=72 nether_wastes · hp 20/20 food 20 · online 5m, idle · protected from every hostile action (operators)
            Your active effects: Bartok slowness II until 21:34
            Deaths you may still cause: Bartok 1, Mimosa 0, clemsix6 0
            Tasks: T-13 analyst running 12s, T-14 analyst queued · Reminders: R-3 at 21:40 "check Bob's village"
            Your last actions: -12m say · -8m apply_effect→Bartok
            </situation>
            """.trimIndent(),
            report.render(now, world, reminders, actions),
        )
    }

    @Test
    fun `empty sections say so, and a world that cannot be read says why`() {
        assertEquals(
            """
            <situation now="21:32:41">
            World: unavailable (the server thread did not answer in time)
            No player online.
            Your active effects: none
            Deaths you may still cause: none
            Tasks: none · Reminders: none
            Your last actions: none
            </situation>
            """.trimIndent(),
            report.render(now, Result.failure(IllegalStateException("the server thread did not answer in time")), emptyList(), emptyList()),
        )
    }

    @Test
    fun `the death protection ends 5 minutes after the death`() {
        players.put(live("Mimosa", onlineFor = 60_000L, lastDeathAt = now - 5 * 60_000L))
        assertEquals(
            "Mimosa: overworld underground y=-58 dripstone_caves · hp 14/20 food 12 · online 1m",
            report.render(now, world, emptyList(), emptyList()).lines()[1],
        )
    }

    @Test
    fun `the memory's lines end the situation, none without a memory, and a failing one says why`() {
        val memory = FakeMemory(clock).apply {
            situation = listOf(
                "Favor: Bartok -31 ↘ · Mimosa +22 ↗ · clemsix6 +4",
                "Due soon: C-45 promise → Mimosa in 12m · C-50 challenge ends in 40m",
                "Attention: <Kelvin> has had nothing from you for 1h10m of play",
            )
        }
        fun render(memory: Memory) = SituationReport(TestServices(clock = clock, players = players, memory = memory), digest, ZoneOffset.UTC)
            .render(now, world, emptyList(), emptyList()).lines()

        assertEquals(
            listOf(
                "Your last actions: none",
                "Favor: Bartok -31 ↘ · Mimosa +22 ↗ · clemsix6 +4",
                "Due soon: C-45 promise → Mimosa in 12m · C-50 challenge ends in 40m",
                "Attention: ‹Kelvin› has had nothing from you for 1h10m of play",
                "</situation>",
            ),
            render(memory).takeLast(5),
        )
        assertEquals(listOf("Your last actions: none", "</situation>"), render(DisabledMemory("not wired yet")).takeLast(2))
        val failing = object : Memory by memory {
            override fun situationLines(now: Long): List<String> = throw IllegalStateException("the cache is not ready")
        }
        assertEquals(listOf("Memory: unavailable (the cache is not ready)", "</situation>"), render(failing).takeLast(2))
    }

    @Test
    fun `the world's clock`() {
        assertEquals(WorldTime(0, "day", "clear"), WorldTime.of(0, raining = false, thundering = false))
        assertEquals(WorldTime(37, "dusk", "rain"), WorldTime.of(37 * 24_000L + 12_500, raining = true, thundering = false))
        assertEquals(WorldTime(37, "night", "thunder"), WorldTime.of(37 * 24_000L + 18_000, raining = true, thundering = true))
        assertEquals(WorldTime(2, "dawn", "clear"), WorldTime.of(2 * 24_000L + 23_500, raining = false, thundering = false))
    }
}
