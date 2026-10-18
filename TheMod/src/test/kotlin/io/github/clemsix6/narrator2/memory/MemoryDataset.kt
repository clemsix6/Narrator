package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.FishCaught
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * A small world history for the memory's computed stats (M3-05), written through the real Journal (bus,
 * mapping, writer) like `SqlDataset`, plus the memory rows the stats read (interactions, canon) inserted
 * by `Journal.write` jobs, as the memory store writes them. Fixed times: the stats take `now`.
 *
 * - **Day 1** ([DAY1], 2026-09-25, UTC): Bartok plays 18:00–20:00 (dies 5 times, kills, mines, builds,
 *   trades, fishes, goes to the Nether 18:45–19:00); Mimosa plays 18:10–19:30 near him (they kill each
 *   other once and break each other's blocks).
 * - **Day 2** ([DAY2], 2026-10-03): Bartok online from 20:00, Kelvin from 20:10 next to him; the
 *   Narrator speaks and they react. [NOW] is 21:00 that day.
 *
 * The expected values are written in the tests, next to what makes them.
 */
internal object MemoryDataset {
    val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
    val kelvin = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b3"), "Kelvin")

    val DAY1: Instant = Instant.parse("2026-09-25T00:00:00Z")
    val DAY2: Instant = Instant.parse("2026-10-03T00:00:00Z")
    val NOW: Instant = day2("21:00")

    const val JEV = "jev:jev-1.13.0"
    const val HEURISTIC = "heuristic"

    fun day1(time: String): Instant = at(DAY1, time)

    fun day2(time: String): Instant = at(DAY2, time)

    private fun at(day: Instant, time: String): Instant {
        val parts = time.split(':').map { it.toLong() }
        return day.plusSeconds(parts[0] * 3_600 + parts[1] * 60 + parts.getOrElse(2) { 0L })
    }

    fun ow(x: Int, y: Int, z: Int) = Pos("minecraft:overworld", x, y, z)

    fun nether(x: Int, y: Int, z: Int) = Pos("minecraft:the_nether", x, y, z)

    /** Mimosa's Jev verdicts: 30 messages, numbered from 0, of which [MIMOSA_ADDRESSED] address the Narrator. */
    fun mimosaPoliteness(i: Int) = (i % 5) / 4.0

    fun mimosaHumor(i: Int) = if (i < 15) 0.2 else 0.8
    const val MIMOSA_JEV = 30
    const val MIMOSA_ADDRESSED = 3

    /** Kelvin's Jev verdicts: 29, one short of the signals' minimum. */
    const val KELVIN_JEV = 29

    /** A Journal on an in-memory database holding the dataset, committed. */
    fun journal(): JournalFixture {
        val fixture = JournalFixture(clock = TestClock(DAY1))
        fill(fixture)
        fixture.sync()
        write(fixture) { memoryRows(it) }
        return fixture
    }

    /** Runs [block] as a `Journal.write` job of the fixture's Journal (on its writer thread), after a barrier. */
    fun <T> write(fixture: JournalFixture, block: (Connection) -> T): T = runBlocking {
        fixture.journal.barrier()
        fixture.journal.write(10_000, block)
    }

    /** Runs [block] on a read connection of the fixture's Journal. */
    fun <T> read(fixture: JournalFixture, block: (Connection) -> T): T {
        fixture.sync()
        return runBlocking { fixture.journal.read(10_000, block) }
    }

    private fun fill(fixture: JournalFixture) {
        val clock = fixture.clock
        fun on(time: Instant, event: GameEvent): Long {
            clock.now = time
            return fixture.publish(event).id
        }
        fun chat(time: Instant, player: PlayerRef, channel: ChatChannel, text: String, source: String?, toNarrator: Double, tone: Tone?, confidence: Double, humor: Double? = null) {
            val id = on(time, ChatMessage(player, channel, text, emptyList()))
            if (source != null) signal(fixture, id, time, player, source, toNarrator, tone, confidence, humor = humor)
        }
        val zombie = EntityRef("minecraft:zombie", UUID.fromString("00000000-0000-4000-8000-0000000000c1"), null)
        val villager = EntityRef("minecraft:villager", UUID.fromString("00000000-0000-4000-8000-0000000000c3"), null)
        val asPlayer = { p: PlayerRef -> EntityRef("minecraft:player", p.uuid, p.name, p) }
        fun died(time: String, cause: String, killer: EntityRef?) =
            on(day1(time), PlayerDied(bartok, ow(10, -20, 10), cause, killer, null, "Bartok died", 5, keptInventory = false, narrator = false))
        fun broke(time: Instant, player: PlayerRef, pos: Pos, block: String, creative: Boolean = false, placedBy: UUID? = null) =
            on(time, BlockBroken(player, pos, block, "minecraft:iron_pickaxe", creative, correctTool = true, placedBy = placedBy))

        // ── Day 1 ──
        on(day1("18:00"), PlayerJoined(bartok, ow(0, 64, 0), sessionId = 1, firstTime = true, isOp = false))
        on(day1("18:00"), PositionSampled(bartok, ow(0, 64, 0), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        died("18:05", "minecraft:lava", null)
        died("18:06", "minecraft:lava", null)
        on(day1("18:10"), PlayerJoined(mimosa, ow(10, 64, 10), sessionId = 2, firstTime = true, isOp = false))
        on(day1("18:10"), PositionSampled(mimosa, ow(10, 64, 10), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        died("18:11", "minecraft:fall", null)
        on(day1("18:15"), EntityKilled(mimosa, ow(10, 64, 10), asPlayer(bartok), "minecraft:iron_sword", pvp = true, tamed = false))
        died("18:15", "minecraft:player_attack", asPlayer(mimosa))
        died("18:20", "minecraft:mob_attack", zombie)
        on(day1("18:25"), EntityKilled(bartok, ow(20, 64, 20), zombie, "minecraft:iron_sword", pvp = false, tamed = false))
        on(day1("18:26"), EntityKilled(bartok, ow(20, 64, 20), zombie, "minecraft:iron_sword", pvp = false, tamed = false))
        on(day1("18:27"), EntityKilled(bartok, ow(20, 64, 20), EntityRef("minecraft:skeleton", null, null), "minecraft:bow", pvp = false, tamed = false))
        on(day1("18:28"), EntityKilled(bartok, ow(20, 64, 20), EntityRef("minecraft:ender_dragon", null, null), "minecraft:bow", pvp = false, tamed = false))
        on(day1("18:28"), AdvancementEarned(bartok, ow(20, 64, 20), "minecraft:end/kill_dragon", "Free the End", "goal"))
        on(day1("18:29"), EntityKilled(bartok, ow(10, 64, 10), asPlayer(mimosa), "minecraft:iron_sword", pvp = true, tamed = false))
        on(day1("18:29"), PlayerDied(mimosa, ow(10, 64, 10), "minecraft:player_attack", asPlayer(bartok), null, "Mimosa died", 3, false, false))
        // Mining: 4 ores (2 diamonds and an iron ore in one cell, ancient debris in the Nether), 5 stones, a creative stone, Mimosa's planks.
        broke(day1("18:30"), bartok, ow(100, -50, 200), "minecraft:diamond_ore")
        broke(day1("18:31"), bartok, ow(101, -54, 201), "minecraft:deepslate_diamond_ore")
        on(day1("18:31"), AdvancementEarned(bartok, ow(101, -54, 201), "minecraft:story/mine_diamond", "Diamonds!", "task"))
        broke(day1("18:32"), bartok, ow(102, 20, 202), "minecraft:iron_ore")
        repeat(5) { broke(day1("18:33"), bartok, ow(it, 60, 0), "minecraft:stone") }
        broke(day1("18:34"), bartok, ow(9, 60, 0), "minecraft:stone", creative = true)
        on(day1("18:35"), BlockPlaced(mimosa, ow(40, 70, 40), "minecraft:oak_planks", "minecraft:oak_planks", PlaceKind.BLOCK))
        broke(day1("18:36"), bartok, ow(40, 70, 40), "minecraft:oak_planks", placedBy = mimosa.uuid)
        // Building: three cobblestones in one cell (the base), planks elsewhere.
        repeat(3) { on(day1("18:37"), BlockPlaced(bartok, ow(it, 64, 0), "minecraft:cobblestone", "minecraft:cobblestone", PlaceKind.BLOCK)) }
        on(day1("18:38"), BlockPlaced(bartok, ow(40, 70, 70), "minecraft:oak_planks", "minecraft:oak_planks", PlaceKind.BLOCK))
        // Chat on day 1 (Bartok: heuristic verdicts only; Mimosa: Jev).
        chat(day1("18:40"), bartok, ChatChannel.PUBLIC, "narrateur, t'es nul", HEURISTIC, 0.9, Tone.HOSTILE, 0.7)
        chat(day1("18:41"), bartok, ChatChannel.PUBLIC, "salut Mimosa", HEURISTIC, 0.1, Tone.NEUTRAL_NO_TARGET, 0.7)
        chat(day1("18:42"), bartok, ChatChannel.PRAY, "rends-moi mes diamants", HEURISTIC, 0.3, Tone.RESPECTFUL, 0.4)
        chat(day1("18:43"), bartok, ChatChannel.PUBLIC, "merci narrateur", HEURISTIC, 0.9, Tone.RESPECTFUL, 0.7)
        chat(day1("18:44"), bartok, ChatChannel.PUBLIC, "narrateur tu es génial", HEURISTIC, 0.9, Tone.RESPECTFUL, 0.7)
        repeat(MIMOSA_JEV) { i ->
            val time = day1("18:12").plusSeconds(60L * i)
            val addressed = i < MIMOSA_ADDRESSED
            val id = on(time, ChatMessage(mimosa, ChatChannel.PUBLIC, "message $i", emptyList()))
            signal(
                fixture, id, time, mimosa, JEV, if (addressed) 0.8 else 0.2, if (addressed) Tone.RESPECTFUL else Tone.NEUTRAL_NO_TARGET, 0.9,
                politeness = mimosaPoliteness(i), humor = mimosaHumor(i),
            )
        }
        repeat(5) { i -> signal(fixture, 9_000L + i, day1("19:20"), mimosa, HEURISTIC, 0.1, Tone.NEUTRAL_NO_TARGET, 0.7) }
        // The Nether, 18:45–19:00.
        on(day1("18:45"), DimensionChanged(bartok, nether(2, 70, 2), ow(16, 64, 16), "nether_portal"))
        on(day1("18:45"), PositionSampled(bartok, nether(2, 70, 2), "minecraft:nether_wastes", null, false, null, 0, 20.0, 20, null, SampleReason.DIMENSION))
        broke(day1("18:46"), bartok, nether(3, 15, 3), "minecraft:ancient_debris")
        on(day1("18:50"), AdvancementEarned(bartok, nether(3, 15, 3), "minecraft:adventure/kill_all_mobs", "Monsters Hunted", "challenge"))
        repeat(2) { broke(day1("18:51"), mimosa, ow(it, 64, 0), "minecraft:cobblestone", placedBy = bartok.uuid) }
        on(day1("19:00"), DimensionChanged(bartok, ow(5, 64, 5), nether(2, 70, 2), "nether_portal"))
        on(day1("19:00"), PositionSampled(bartok, ow(5, 64, 5), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.DIMENSION))
        on(day1("19:01"), BiomeEntered(bartok, ow(200, 70, 200), "minecraft:cherry_grove", firstVisit = true))
        on(day1("19:02"), BiomeEntered(bartok, ow(300, 70, 200), "minecraft:forest", firstVisit = true))
        on(day1("19:03"), StructureEntered(bartok, ow(300, 70, 200), "minecraft:village_plains", firstVisit = false))
        on(day1("19:04"), StructureEntered(bartok, ow(300, -20, 200), "minecraft:trial_chambers", firstVisit = true))
        on(day1("19:05"), ItemCrafted(bartok, ow(5, 64, 5), "minecraft:wooden_pickaxe", 1, "minecraft:crafting"))
        on(day1("19:05"), ItemCrafted(bartok, ow(5, 64, 5), "minecraft:iron_pickaxe", 1, "minecraft:crafting"))
        on(day1("19:05"), ItemCrafted(bartok, ow(5, 64, 5), "minecraft:iron_chestplate", 1, "minecraft:crafting"))
        on(day1("19:06"), ItemsPickedUp(bartok, ow(5, 64, 5), "minecraft:diamond_sword", 1, 30))
        on(day1("19:06"), ItemsPickedUp(bartok, ow(5, 64, 5), "minecraft:leather_boots", 1, 30))
        on(day1("19:06"), ItemsPickedUp(bartok, ow(5, 64, 5), "minecraft:torch", 12, 30))
        on(day1("19:07"), Traded(bartok, ow(30, 64, 30), ItemCount("minecraft:book", 1), ItemCount("minecraft:emerald", 5), null, villager, "minecraft:librarian", 1, 1, 12))
        on(day1("19:08"), Traded(bartok, ow(30, 64, 30), ItemCount("minecraft:emerald", 1), ItemCount("minecraft:paper", 24), ItemCount("minecraft:emerald", 2), villager, "minecraft:librarian", 1, 1, 12))
        on(day1("19:09"), FishCaught(bartok, ow(30, 62, 30), listOf(ItemCount("minecraft:cod", 1), ItemCount("minecraft:saddle", 1)), null))
        on(day1("19:10"), FishCaught(bartok, ow(30, 62, 30), listOf(ItemCount("minecraft:salmon", 2)), null))
        on(day1("19:11"), SleepStarted(bartok, ow(12, 64, 12), "minecraft:red_bed"))
        on(day1("19:12"), SleepStarted(bartok, ow(12, 64, 12), "minecraft:red_bed"))
        on(day1("19:13"), SleepStarted(bartok, ow(50, 64, 50), "minecraft:red_bed"))
        on(day1("19:15"), PositionSampled(mimosa, ow(500, 64, 500), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
        on(
            day1("19:20"),
            StatsSnapshot(
                bartok,
                mapOf(
                    "minecraft.custom:minecraft.walk_one_cm" to 150_000L,
                    "minecraft.custom:minecraft.sprint_one_cm" to 50_000L,
                    "minecraft.custom:minecraft.aviate_one_cm" to 20_000L,
                    "minecraft.custom:minecraft.boat_one_cm" to 40L,
                    "minecraft.custom:minecraft.fall_one_cm" to 99_900L,
                    "minecraft.mined:minecraft.stone" to 5L,
                ),
            ),
        )
        on(day1("19:30"), PlayerLeft(mimosa, ow(500, 64, 500), sessionId = 2, sessionSeconds = 4_800, reason = LeaveReason.LEAVE))
        on(day1("20:00"), PlayerLeft(bartok, ow(5, 64, 5), sessionId = 1, sessionSeconds = 7_200, reason = LeaveReason.LEAVE))

        // ── Day 2 ──
        on(day2("20:00"), PlayerJoined(bartok, ow(0, 64, 0), sessionId = 3, firstTime = false, isOp = false))
        on(day2("20:00"), PositionSampled(bartok, ow(0, 64, 0), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        on(day2("20:10"), PlayerJoined(kelvin, ow(20, 64, 0), sessionId = 4, firstTime = true, isOp = false))
        on(day2("20:10"), PositionSampled(kelvin, ow(20, 64, 0), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        repeat(KELVIN_JEV - 1) { i -> signal(fixture, 8_000L + i, day2("20:11").plusSeconds(60L * i), kelvin, JEV, 0.1, Tone.NEUTRAL_NO_TARGET, 0.9) }
        // The Narrator speaks; they react (or not).
        on(day2("20:20"), NarratorSpoke(NarratorChannel.NARRATOR, "Bartok, ta pioche pleure.", null, turnId = 41, operator = null))
        chat(day2("20:21"), bartok, ChatChannel.PUBLIC, "mdr", HEURISTIC, 0.65, Tone.MOCKING_PLAYFUL, 0.7)
        on(day2("20:25"), NarratorSpoke(NarratorChannel.NARRATOR, "Kelvin, tu dors ?", null, turnId = 42, operator = null))
        on(day2("20:35"), NarratorSpoke(NarratorChannel.NARRATOR, "Ta prière est refusée.", listOf(bartok), turnId = 43, operator = null))
        chat(day2("20:36"), bartok, ChatChannel.PUBLIC, "narrateur, t'es nul", HEURISTIC, 0.9, Tone.HOSTILE, 0.7)
        on(day2("20:45"), NarratorSpoke(NarratorChannel.NARRATOR, "Mimosa, reviens.", null, turnId = 44, operator = null))
        on(day2("20:50"), NarratorSpoke(NarratorChannel.NARRATOR, "Bartok et kelvin, au travail.", null, turnId = 45, operator = null))
        chat(day2("20:51"), bartok, ChatChannel.PUBLIC, "ok", HEURISTIC, 0.65, Tone.RESPECTFUL, 0.4)
        chat(day2("20:51:30"), kelvin, ChatChannel.PUBLIC, "excellent", JEV, 0.7, Tone.MOCKING_PLAYFUL, 0.8, humor = 0.7)
        on(day2("20:55"), NarratorSpoke(NarratorChannel.OPERATOR, "Bartok, l'opérateur te salue.", null, turnId = null, operator = "Clement"))
        toolCall(fixture, day2("20:40"), "say", ToolStatus.OK, ToolEffect.SPEECH, listOf(bartok.name))
        toolCall(fixture, day2("20:41"), "smite", ToolStatus.DENIED, ToolEffect.WORLD, listOf(kelvin.name))
        toolCall(fixture, day2("20:42"), "sql", ToolStatus.OK, ToolEffect.READ, listOf(kelvin.name))
        clock.now = NOW
    }

    private fun signal(
        fixture: JournalFixture, eventId: Long, ts: Instant, player: PlayerRef, source: String, toNarrator: Double, tone: Tone?, confidence: Double,
        politeness: Double? = null, humor: Double? = null,
    ) {
        val jev = source.startsWith("jev:")
        fixture.journal.chatSignals(
            ChatSignalsRecord(
                eventId = eventId, ts = ts, playerUuid = player.uuid, source = source, toNarrator = toNarrator, tone = tone,
                toneConfidence = confidence, intent = null, manipulation = 0.0,
                politeness = if (jev) politeness ?: 0.5 else null, boastfulness = if (jev) 0.1 else null,
                helpfulness = if (jev) 0.3 else null, aggressiveness = if (jev) 0.0 else null, humor = if (jev) humor ?: 0.2 else null,
                latencyMs = 1,
            ),
        )
    }

    private fun toolCall(fixture: JournalFixture, ts: Instant, tool: String, status: ToolStatus, effect: ToolEffect, targets: List<String>) {
        fixture.journal.toolCall(
            ToolCallRecord(
                id = fixture.journal.nextId(IdKind.TOOL_CALL), ts = ts, conversationId = "director-1", turnId = 46, llmCallId = null,
                toolUseId = "toolu_$tool", role = Role.DIRECTOR, tool = tool, args = "{}", status = status, result = "ok", durationMs = 5,
                targets = targets, effect = effect,
            ),
        )
    }

    /**
     * Interactions and canon. Bartok: an insult (I-1, disrespect, counts), his punishment, a blessing and a gift (they do not
     * count), an archived insult (ignored), a compliment on day 2 (I-6). Mimosa: a compliment. Challenges: C-10 won by Bartok
     * against Mimosa, C-11 lost by Bartok, C-12 won by Mimosa, C-13 still open.
     */
    private fun memoryRows(connection: Connection) {
        fun interaction(id: Long, ts: Instant, player: PlayerRef, kind: InteractionKind, valence: Int, playedSeconds: Long, archived: Boolean = false) {
            connection.prepareStatement(
                "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, played_s, tool_calls, source, evidence_events, archived) " +
                    "VALUES (?, CAST(? AS TIMESTAMPTZ), CAST(? AS UUID), ?, ?, ?, ?, ?, 'résumé', ?, [], 'director', [], ?)",
            ).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, ts.toString())
                statement.setString(3, player.uuid.toString())
                statement.setString(4, player.name)
                statement.setString(5, kind.name.lowercase())
                statement.setInt(6, valence)
                statement.setBoolean(7, kind.counts)
                statement.setBoolean(8, kind.isDisrespect(valence))
                statement.setLong(9, playedSeconds)
                statement.setBoolean(10, archived)
                statement.executeUpdate()
            }
        }
        interaction(1, day1("18:16"), bartok, InteractionKind.INSULT, -2, 960)
        interaction(2, day1("18:17"), bartok, InteractionKind.PUNISHMENT, 0, 1_020)
        interaction(3, day1("19:00"), bartok, InteractionKind.BLESSING, 0, 3_600)
        interaction(4, day1("19:10"), bartok, InteractionKind.GIFT, 1, 4_200)
        interaction(5, day1("19:20"), bartok, InteractionKind.INSULT, -3, 4_800, archived = true)
        interaction(6, day2("20:30"), bartok, InteractionKind.COMPLIMENT, 1, 9_000)
        interaction(7, day1("19:00"), mimosa, InteractionKind.COMPLIMENT, 2, 3_000)

        fun challenge(id: Long, players: List<PlayerRef>, status: String, winners: List<PlayerRef>?) {
            fun uuids(list: List<PlayerRef>) = list.joinToString(prefix = "[", postfix = "]") { "CAST('${it.uuid}' AS UUID)" }
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO canon (id, kind, created_at, statement, players, importance, status, winners, source, evidence_events, evidence_tools) " +
                        "VALUES ($id, 'challenge', TIMESTAMPTZ '2026-09-25 18:50:00+00', 'défi', ${uuids(players)}, 5, '$status', " +
                        "${winners?.let(::uuids) ?: "NULL"}, 'director', [], [])",
                )
            }
        }
        challenge(10, listOf(bartok, mimosa), "won", listOf(bartok))
        challenge(11, listOf(bartok), "lost", null)
        challenge(12, listOf(mimosa), "won", listOf(mimosa))
        challenge(13, listOf(bartok), "active", null)
    }
}
