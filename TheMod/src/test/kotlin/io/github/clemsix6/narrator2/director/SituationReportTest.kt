package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.memory.DisabledMemory
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMechanics
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** A stake and what is owed tell their purpose, with no deadline (M4-LEFTOVERS); an effect keeps its own. */
    @Test
    fun `a stake and what is owed tell their purpose among the active effects`() {
        players.put(live("Bartok", onlineFor = 60_000L))
        val names = { _: UUID -> null }
        guards.active[ref("Bartok").uuid] = listOf(
            ActiveEffect("effect", "slowness II", now + 80_000, harmful = true),
            Escrow.active(
                Confiscation(812, emptyList(), "2x minecraft:emerald", now, now + 7 * 86_400_000L, "a bet that Mimosa wins", null, LedgerPurpose.STAKE, canon = 50), now, names,
            ),
            Escrow.active(Confiscation(813, emptyList(), "1x minecraft:golden_apple", now, now, "the reward of the challenge C-50", null, LedgerPurpose.OWED), now, names),
        )
        val line = report.render(now, world, emptyList(), emptyList()).lines().single { it.startsWith("Your active effects: ") }
        assertEquals(
            "Your active effects: Bartok slowness II until 21:34, Bartok 2x minecraft:emerald staked on C-50: a bet that Mimosa wins, " +
                "Bartok 1x minecraft:golden_apple owed to them (given once their inventory has room): the reward of the challenge C-50",
            line,
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

    // ── Mechanics (M4 §4.5) ─────────────────────────────────────────────────────────────────────────

    private val challengesLine =
        "Challenges: C-50 obtain « Œuf de tortue » → all · Mimosa 0/1, Bartok 0/1 · ends in 12m · bets open 3m (2 bets) · next challenge possible in 6m"
    private val rulesLine =
        "Rules: C-60 law block_break grief → punish (2 fired, until 10-25 20:15) · C-61 plan once dimension_change Bartok → spawn, notify (until 21:40)"

    /** Mimosa's offering O-812 of 10 diamonds, to answer by 21:42. */
    private val offering = Confiscation(812, listOf("minecraft:diamond"), "10x minecraft:diamond", now - 60_000, now + 559_000, null, null, LedgerPurpose.OFFERING)

    /**
     * Bartok punished twice this hour, size and air resting, his offering O-790 past its time (it only waits for
     * room to go back); Mimosa under grace until 21:45, with an offering.
     */
    private fun evening() {
        players.put(live("Bartok", onlineFor = 72 * 60_000L))
        players.put(live("Mimosa", onlineFor = 25 * 60_000L))
        guards.pacingOf[ref("Bartok").uuid] = PlayerPacing(
            ref("Bartok").uuid, 2, 4, mapOf(PunishmentCategory.AIR to now + 1_099_000, PunishmentCategory.SIZE to now + 379_000), null,
            listOf(offering.copy(id = 790, returnAt = now - 1_000)),
        )
        guards.pacingOf[ref("Mimosa").uuid] = PlayerPacing(ref("Mimosa").uuid, 0, 4, emptyMap(), now + 739_000, listOf(offering))
    }

    private fun render(mechanics: Mechanics, memory: Memory = FakeMemory(clock), guards: Guards = this.guards) =
        SituationReport(TestServices(clock = clock, players = players, guards = guards, memory = memory, mechanics = mechanics), digest, ZoneOffset.UTC)
            .render(now, world, emptyList(), emptyList()).lines()

    @Test
    fun `the mechanics' lines follow the last actions, before the memory's (M4 §4-5)`() {
        evening()
        val mechanics = FakeMechanics(clock).apply { lines = listOf(challengesLine, rulesLine) }
        val memory = FakeMemory(clock).apply { situation = listOf("Favor: Bartok -31 ↘ · Mimosa +22 ↗") }

        assertEquals(
            listOf(
                "Your last actions: none",
                challengesLine,
                rulesLine,
                "Pacing: punished this hour Bartok 2/4, Mimosa 0/4 · recent kinds Bartok size (again at 21:39), air (21:51) · grace Mimosa until 21:45",
                "Offerings: O-812 Mimosa 10x diamond (answer by 21:42)",
                "Favor: Bartok -31 ↘ · Mimosa +22 ↗",
                "</situation>",
            ),
            render(mechanics, memory).takeLast(7),
        )
    }

    @Test
    fun `nothing to say, no line - without the engine, the guards' pacing is still there`() {
        players.put(live("Bartok", onlineFor = 72 * 60_000L))
        assertEquals(listOf("Your last actions: none", "</situation>"), render(FakeMechanics(clock)).takeLast(2), "nobody punished, nothing resting, no grace, no offering")

        evening()
        val off = FakeMechanics(clock).apply {
            lines = listOf(challengesLine)
            available = false
        }
        val lines = render(off)
        assertFalse(lines.any { it.startsWith("Challenges:") }, "the engine is off: its lines are not read")
        assertTrue(lines.any { it.startsWith("Pacing: punished this hour Bartok 2/4") }, "the categories and the grace are guards")
        assertTrue(lines.any { it.startsWith("Offerings: O-812") })
        assertEquals(lines, render(DisabledMechanics("not wired yet")))
    }

    @Test
    fun `a source that fails says so on its line`() {
        evening()
        val failing = object : Mechanics by FakeMechanics(clock) {
            override fun situationLines(now: Long): List<String> = throw IllegalStateException("the snapshot is not ready")
        }
        assertEquals("Mechanics: unavailable (the snapshot is not ready)", render(failing).first { it.startsWith("Mechanics") })
        val noPacing = object : Guards by guards {
            override fun pacing(players: List<UUID>, now: Long): List<PlayerPacing> = throw IllegalStateException("the episodes are locked")
        }
        val lines = render(FakeMechanics(clock), guards = noPacing)
        assertEquals("Pacing: unavailable (the episodes are locked)", lines.single { it.startsWith("Pacing") })
        assertFalse(lines.any { it.startsWith("Offerings") })
    }

    @Test
    fun `the mechanics' lines keep to their budgets - 400 characters each, 1 200 together`() {
        // Twelve players online, all punished, every category resting, all under grace, each with two offerings.
        val names = (1..12).map { "Player_with_1${it.toString().padStart(2, '0')}" }
        names.forEachIndexed { index, name ->
            players.put(live(name, onlineFor = 60_000L))
            val uuid = ref(name).uuid
            guards.pacingOf[uuid] = PlayerPacing(
                uuid, 4, 4, PunishmentCategory.entries.filter { it.rests }.associateWith { now + 600_000 }, now + 900_000,
                listOf(offering.copy(id = 900L + index), offering.copy(id = 1_900L + index)),
            )
        }
        val long = FakeMechanics(clock).apply { lines = listOf("Challenges: " + "c".repeat(700), "Rules: " + "<r>".repeat(300)) }
        val shown = render(long).dropWhile { !it.startsWith("Your last actions") }.drop(1).dropLast(1)

        assertEquals(listOf("Challenges", "Rules", "Pacing", "Offerings"), shown.map { it.substringBefore(':') })
        shown.forEach { assertTrue(it.length <= MechanicsLimits.SITUATION_LINE, "${it.length} characters: $it") }
        assertTrue(shown.sumOf { it.length } <= MechanicsLimits.SITUATION_TOTAL, "${shown.sumOf { it.length }} characters together")
        assertEquals(shown[0].length, shown[1].length, "the engine's two long lines get the same share")
        assertTrue(shown[0].length in 300 until MechanicsLimits.SITUATION_LINE, "all four are long: they share the total (${shown[0].length})")
        assertTrue(Regex(" \\(\\+\\d+ more\\)$").containsMatchIn(shown[2]), shown[2])
        assertTrue(Regex(" \\(\\+\\d+ more\\)$").containsMatchIn(shown[3]), shown[3])
        assertFalse("<r>" in shown[1], "the engine's lines are escaped again")

        // With only two of them, each takes its 400 characters.
        val two = render(FakeMechanics(clock).apply { lines = listOf("Challenges: " + "c".repeat(700)) }, guards = FakeGuards())
            .filter { it.startsWith("Challenges") }
        assertEquals(MechanicsLimits.SITUATION_LINE, two.single().length)
        val chunks = listOf("a".repeat(10), ", " + "b".repeat(10), ", " + "c".repeat(10))
        assertEquals("Pacing: (+3 more)", LineBudget.fit("Pacing: ", chunks, 20))
        assertEquals("Pacing: aaaaaaaaaa (+2 more)", LineBudget.fit("Pacing: ", chunks, 30))
        assertEquals("Pacing: aaaaaaaaaa, bbbbbbbbbb, cccccccccc", LineBudget.fit("Pacing: ", chunks, 42))
        assertEquals("Pacing: aaaaaaaaaa, bbbbbbbbbb, cccccccccc (+4 more)", LineBudget.fit("Pacing: ", chunks, 60, more = 4), "items left out already")
        assertEquals("Pacing: aaaaaaaaaa, bbbbbbbbbb (+5 more)", LineBudget.fit("Pacing: ", chunks, 50, more = 4))
        assertEquals(null, LineBudget.fit("Pacing: ", chunks, 10), "no room even for the count")
        assertEquals(null, LineBudget.fit("Pacing: ", emptyList(), 400))
        assertEquals(
            listOf("a".repeat(10), "b".repeat(45), "c".repeat(44) + "…"),
            LineBudget.share(listOf({ "a".repeat(10) }, { max -> "b".repeat(minOf(45, max)) }, { max -> "c".repeat(200).take(max) + "x" }), 100, 100),
            "the short ones whole, the long ones share the rest (a line its builder cannot fit is cut)",
        )
    }

    @Test
    fun `the world's clock`() {
        assertEquals(WorldTime(0, "day", "clear"), WorldTime.of(0, raining = false, thundering = false))
        assertEquals(WorldTime(37, "dusk", "rain"), WorldTime.of(37 * 24_000L + 12_500, raining = true, thundering = false))
        assertEquals(WorldTime(37, "night", "thunder"), WorldTime.of(37 * 24_000L + 18_000, raining = true, thundering = true))
        assertEquals(WorldTime(2, "dawn", "clear"), WorldTime.of(2 * 24_000L + 23_500, raining = false, thundering = false))
    }
}
