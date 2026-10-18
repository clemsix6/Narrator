package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemConsumed
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
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.RespawnPointSet
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.SleepEnded
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StatAwarded
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A small world history for the SQL tests, written through the real Journal (bus, mapping, writer), so
 * that views and macros are tested on exactly the rows the Journal writes. Bartok mines, kills, dies,
 * trades, sleeps, travels and chats; Mimosa builds and leaves. One event a minute, from an hour ago:
 * the macros compare with DuckDB's `now()`, the real time.
 */
internal object SqlDataset {
    val bartok = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")

    /** The private message Bartok sends to Mimosa: no model query may ever return it. */
    const val SECRET = "the base is behind the waterfall"

    private val zombie = EntityRef("minecraft:zombie", UUID.fromString("00000000-0000-4000-8000-0000000000c1"), null)
    private val skeleton = EntityRef("minecraft:skeleton", UUID.fromString("00000000-0000-4000-8000-0000000000c2"), null)
    private val villager = EntityRef("minecraft:villager", UUID.fromString("00000000-0000-4000-8000-0000000000c3"), null)

    fun ow(x: Int, y: Int, z: Int) = Pos("minecraft:overworld", x, y, z)

    fun nether(x: Int, y: Int, z: Int) = Pos("minecraft:the_nether", x, y, z)

    /** A Journal on an in-memory database holding the dataset, committed. */
    fun journal(): JournalFixture {
        val fixture = JournalFixture(clock = TestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(60))))
        fill(fixture)
        remember(fixture)
        return fixture
    }

    /** The world play clock of [MEMORY_ROWS]: 100 hours. */
    const val WORLD_PLAY_S = 360_000L

    /**
     * What the memory holds about the hour above, as the memory store writes it (M3 §13): one row of each sort
     * the `mem_*` views show, plus rows they leave out (archived, superseded). Memory M-1 is at half strength
     * (40 h of world play since it was reinforced, half-life 40 h), M-2 at the floor.
     */
    private val MEMORY_ROWS = """
        INSERT INTO memory_state VALUES ('world_play_s', '$WORLD_PLAY_S', now());
        INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, importance, valence,
                              stability_h, reinforced_play_s, source, evidence_events, evidence_tools, archived, merged_into) VALUES
            (1, 'episode', now(), now() - INTERVAL 40 MINUTE, now() - INTERVAL 39 MINUTE, 'Tué par un squelette',
             'Bartok est mort sous les flèches d''un squelette, puis a insulté le Narrateur.', ['${bartok.uuid}'::UUID],
             ['minecraft:skeleton'], ['death', 'mob', 'insult'], 5, -2, 40.0, ${WORLD_PLAY_S - 40 * 3_600}, 'chronicler', [], [], false, NULL),
            (2, 'observation', now(), now() - INTERVAL 50 MINUTE, now() - INTERVAL 50 MINUTE, 'Mimosa bâtit à côté de Bartok',
             'Mimosa pose ses planches à dix blocs de Bartok.', ['${mimosa.uuid}'::UUID, '${bartok.uuid}'::UUID, '00000000-0000-4000-8000-0000000000ff'::UUID],
             [], ['build'], 2, 1, 4.0, 0, 'chronicler', [], [], false, NULL),
            (3, 'episode', now(), now() - INTERVAL 45 MINUTE, now() - INTERVAL 45 MINUTE, 'Oublié', 'Bartok a creusé.', ['${bartok.uuid}'::UUID],
             [], ['mining'], 1, 0, 4.0, 0, 'chronicler', [], [], true, NULL),
            (4, 'episode', now(), now() - INTERVAL 45 MINUTE, now() - INTERVAL 45 MINUTE, 'Fusionné', 'Bartok est mort.', ['${bartok.uuid}'::UUID],
             [], ['death'], 3, -1, 12.0, 0, 'chronicler', [], [], true, 1);
        INSERT INTO canon (id, kind, created_at, statement, quote, players, importance, status, due_at, resolved_at, resolution, source,
                           evidence_events, evidence_tools, archived) VALUES
            (10, 'promise', now() - INTERVAL 20 MINUTE, 'Le Narrateur a promis à Mimosa de lui rendre ses planches.', 'tu auras tes planches',
             ['${mimosa.uuid}'::UUID], 6, 'active', now() + INTERVAL 1 DAY, NULL, NULL, 'director', [], [], false),
            (11, 'nickname', now() - INTERVAL 30 MINUTE, 'Bartok est surnommé « le Pleureur ».', 'le Pleureur', ['${bartok.uuid}'::UUID], 4,
             'retired', NULL, now() - INTERVAL 5 MINUTE, 'remplacé par un autre surnom', 'director', [], [], false),
            (12, 'law', now() - INTERVAL 30 MINUTE, 'Nul ne creuse sous le village.', NULL, [], 7, 'active', NULL, NULL, NULL, 'director', [], [], true);
        INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, quote, played_s, tool_calls, source,
                                  evidence_events, archived) VALUES
            (20, now() - INTERVAL 10 MINUTE, '${bartok.uuid}', 'Bartok_', 'insult', -2, true, true, 'a traité le Narrateur de nul',
             'narrateur, t''es nul', 3000, [], 'director', [], false),
            (21, now() - INTERVAL 9 MINUTE, '${bartok.uuid}', 'Bartok', 'punishment', 0, false, false, 'foudroyé pour son insulte', NULL, 3060,
             [], 'director', [], false),
            (22, now() - INTERVAL 30 MINUTE, '${mimosa.uuid}', 'Mimosa', 'compliment', 2, true, false, 'a remercié le Narrateur', NULL, 1800,
             [], 'director', [], false),
            (23, now() - INTERVAL 30 MINUTE, '${mimosa.uuid}', 'Mimosa', 'insult', -3, true, true, 'insulte retirée', NULL, 1700,
             [], 'director', [], true);
        INSERT INTO chronicle (id, level, period_from, period_to, title, summary, covers, conversation_id, open, superseded_by, written_at, source) VALUES
            (30, 'chapter', now() - INTERVAL 60 MINUTE, now() - INTERVAL 5 MINUTE, 'Brouillon', 'Une première version.', [], 'director-1', false, 31,
             now(), 'chronicler'),
            (31, 'chapter', now() - INTERVAL 60 MINUTE, now() - INTERVAL 5 MINUTE, 'La pioche qui pleure',
             'Bartok mine, meurt sous les flèches et insulte le Narrateur.', [], 'director-1', false, NULL, now(), 'chronicler');
        INSERT INTO sheet_stats VALUES ('${bartok.uuid}', 'deaths', 1, '{"top":[["arrow",1]],"absurd":0}', now());
    """.trimIndent()

    private fun remember(fixture: JournalFixture) = runBlocking<Unit> {
        fixture.journal.write(10_000) { connection -> connection.createStatement().use { it.execute(MEMORY_ROWS) } }
    }

    private fun fill(fixture: JournalFixture) {
        val clock = fixture.clock
        fun at(event: GameEvent) {
            fixture.publish(event)
            clock.advance(Duration.ofMinutes(1))
        }
        fun broke(pos: Pos, block: String, tool: String?, placedBy: UUID? = null) =
            at(BlockBroken(bartok, pos, block, tool, creative = false, correctTool = true, placedBy = placedBy))

        at(PlayerJoined(bartok, ow(0, 64, 0), sessionId = 1, firstTime = true, isOp = false))
        at(PlayerJoined(mimosa, ow(5, 64, 5), sessionId = 2, firstTime = false, isOp = false))
        at(PositionSampled(bartok, ow(0, 64, 0), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
        at(BlockPlaced(mimosa, ow(10, 64, 10), "minecraft:oak_planks", "minecraft:oak_planks", PlaceKind.BLOCK))
        at(PositionSampled(bartok, ow(30, 64, 40), "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
        broke(ow(100, -40, 200), "minecraft:diamond_ore", "minecraft:iron_pickaxe")
        broke(ow(101, -41, 201), "minecraft:deepslate_iron_ore", "minecraft:iron_pickaxe")
        repeat(3) { broke(ow(102 + it, -41, 202), "minecraft:stone", "minecraft:iron_pickaxe") }
        broke(ow(10, 64, 10), "minecraft:oak_planks", null, placedBy = mimosa.uuid)
        broke(ow(0, 64, 0), "minecraft:dirt", null, placedBy = bartok.uuid)
        at(BlockPlaced(bartok, ow(1, 65, 1), "minecraft:cobblestone", "minecraft:cobblestone", PlaceKind.BLOCK))
        at(EntityKilled(bartok, ow(20, 64, 20), zombie, "minecraft:iron_sword", pvp = false, tamed = false))
        at(EntityKilled(bartok, ow(21, 64, 20), zombie, "minecraft:iron_sword", pvp = false, tamed = false))
        at(EntityKilled(bartok, ow(22, 64, 20), skeleton, "minecraft:bow", pvp = false, tamed = false))
        at(EntityKilled(bartok, ow(23, 64, 20), EntityRef("minecraft:player", mimosa.uuid, mimosa.name, mimosa), "minecraft:iron_sword", pvp = true, tamed = false))
        at(DamageTaken(bartok, ow(24, 64, 20), "minecraft:arrow", amount = 6.5, hits = 2, attacker = skeleton, blocked = false, healthAfter = 13.5))
        at(DamageDealt(bartok, ow(24, 64, 20), skeleton, amount = 4.0, weapon = "minecraft:iron_sword", damageType = "minecraft:player_attack", victimHealth = 16.0))
        at(
            PlayerDied(
                bartok, ow(25, 64, 20), "minecraft:arrow", skeleton, weapon = "minecraft:bow", message = "Bartok was shot by Skeleton",
                xpLevel = 7, keptInventory = false, narrator = false,
            ),
        )
        at(PlayerRespawned(bartok, ow(0, 64, 0), alive = false))
        at(Traded(bartok, ow(30, 64, 30), ItemCount("minecraft:emerald", 1), ItemCount("minecraft:paper", 24), null, villager, "minecraft:librarian", 2, 3, 16))
        at(ItemConsumed(bartok, ow(30, 64, 30), "minecraft:cooked_beef", 12, nutrition = 8, saturation = 12.8, effects = emptyList(), foodBefore = 10, healthBefore = 20.0))
        at(ItemCrafted(bartok, ow(30, 64, 30), "minecraft:torch", 16, "minecraft:crafting"))
        at(AdvancementEarned(bartok, ow(30, 64, 30), "minecraft:story/mine_diamond", "Diamonds!", "task"))
        at(BiomeEntered(bartok, ow(200, 70, 200), "minecraft:cherry_grove", firstVisit = true))
        at(StructureEntered(bartok, ow(210, 70, 210), "minecraft:village_plains", firstVisit = false))
        at(ItemsPickedUp(bartok, ow(210, 70, 210), "minecraft:cobblestone", 64, 30))
        at(StatAwarded(bartok, ow(210, 70, 210), "minecraft.custom:minecraft.bell_ring", 1))
        at(SleepStarted(bartok, ow(12, 64, 12), "minecraft:red_bed"))
        at(RespawnPointSet(bartok, ow(12, 64, 12), ow(12, 64, 12), forced = false, cleared = false))
        at(SleepEnded(bartok, ow(12, 64, 12), nightSkipped = true))
        at(SleepStarted(bartok, ow(12, 64, 12), "minecraft:red_bed"))
        at(DimensionChanged(bartok, nether(2, 70, 2), ow(16, 64, 16), "nether_portal"))
        at(PositionSampled(bartok, nether(2, 70, 2), "minecraft:nether_wastes", null, false, null, 0, 20.0, 20, null, SampleReason.DIMENSION))
        broke(nether(3, 15, 3), "minecraft:ancient_debris", "minecraft:diamond_pickaxe")
        at(Teleported(bartok, nether(900, 70, 2), nether(3, 70, 3), 897.0))
        at(StatsSnapshot(bartok, mapOf("minecraft.mined:minecraft.stone" to 1_204L, "minecraft.killed:minecraft.zombie" to 2L)))
        at(BlockBroken(mimosa, ow(-50, 12, 80), "minecraft:coal_ore", "minecraft:stone_pickaxe", creative = false, correctTool = true, placedBy = null))
        at(PlayerLeft(mimosa, ow(5, 64, 5), sessionId = 2, sessionSeconds = 3_600, reason = LeaveReason.LEAVE))
        // The last quarter of an hour: what recent(p, 15) sees.
        clock.now = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(10))
        at(ChatMessage(bartok, ChatChannel.PUBLIC, "narrateur, t'es nul", emptyList()))
        at(ChatMessage(bartok, ChatChannel.PRAY, "rends-moi mes diamants", emptyList()))
        at(ChatMessage(bartok, ChatChannel.WHISPER, SECRET, listOf(mimosa.name)))
        at(NarratorSpoke(NarratorChannel.NARRATOR, "Bartok, ta pioche pleure.", null, turnId = 42, operator = null))
        at(EntityKilled(bartok, ow(20, 64, 20), zombie, "minecraft:iron_sword", pvp = false, tamed = false))

        listOf(
            Triple("say", ToolEffect.SPEECH, """{"text":"Bartok, ta pioche pleure."}"""),
            Triple("strike_lightning", ToolEffect.WORLD, """{"player":"Bartok"}"""),
            Triple("sql", ToolEffect.READ, """{"query":"SELECT 1"}"""),
        ).forEachIndexed { index, (tool, effect, args) ->
            fixture.journal.toolCall(
                ToolCallRecord(
                    id = fixture.journal.nextId(IdKind.TOOL_CALL), ts = clock.instant(), conversationId = "director-1", turnId = 42,
                    llmCallId = null, toolUseId = "toolu_$index", role = Role.DIRECTOR, tool = tool, args = args, status = ToolStatus.OK,
                    result = "ok", durationMs = 12, targets = listOf(bartok.name), effect = effect,
                ),
            )
        }
        fixture.sync()
    }
}
