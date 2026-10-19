package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.director.Salience.Route
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.ChatTriage
import io.github.clemsix6.narrator2.reflex.ChatVerdict
import io.github.clemsix6.narrator2.reflex.HeuristicReflexes
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.testing.FakeReflexes
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.alexEntity
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.nether
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.SampleEvents.zombie
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SalienceTest {
    private val clock = TestClock()
    private val reflexes = FakeReflexes()
    private val services = TestServices(clock = clock, reflexes = reflexes)
    private val posted = CopyOnWriteArrayList<DirectorSignal>()
    private val salience = Salience(services, ActivityDigest(clock), posted::add)
    private var ids = 100L

    private fun envelope(event: GameEvent) = Envelope(ids++, clock.millis(), 0, event)

    private fun routeOf(event: GameEvent): Any = when (val route = Salience.route(event)) {
        is Route.Forward -> route.urgency
        else -> route
    }

    private fun broken(block: String, placedBy: java.util.UUID? = null) =
        BlockBroken(steve, here, block, "minecraft:iron_pickaxe", creative = false, correctTool = true, placedBy = placedBy)

    private fun advancement(id: String, frame: String) = AdvancementEarned(steve, here, id, "Title", frame)

    private fun explosion(playerBlocks: Int, nearby: Boolean, narrator: Boolean) =
        ExplosionHappened(here, "minecraft:creeper", zombie, 3.0, "destroy", 10, playerBlocks, if (nearby) listOf(steve) else emptyList(), narrator)

    @Test
    fun `the salience table, row by row`() {
        val rows: List<Pair<GameEvent, Any>> = listOf(
            // Chat: prayers and public chat are triaged; private messages never go anywhere.
            ChatMessage(steve, ChatChannel.PRAY, "rends-moi mes diamants", emptyList()) to Route.Triage,
            ChatMessage(steve, ChatChannel.PUBLIC, "narrateur t'es nul", emptyList()) to Route.Triage,
            ChatMessage(steve, ChatChannel.WHISPER, "la base est derrière la cascade", listOf("Alex")) to Route.Never,
            // What an operator made the Narrator say; the Narrator's own lines are already in its conversation.
            NarratorSpoke(NarratorChannel.OPERATOR, "Silence.", null, null, "Clement") to Urgency.NEXT,
            NarratorSpoke(NarratorChannel.NARRATOR, "Steve, ta pioche pleure.", null, 42, null) to Route.Batch,
            // Deaths, PvP, critical health, arrivals and departures.
            PlayerDied(steve, here, "minecraft:fall", null, null, null, 3, false, false) to Urgency.SOON,
            EntityKilled(steve, here, alexEntity, "minecraft:bow", pvp = true, tamed = false) to Urgency.SOON,
            EntityKilled(steve, here, zombie, "minecraft:iron_sword", pvp = false, tamed = false) to Route.Batch,
            HealthCritical(steve, here, 3.0) to Urgency.SOON,
            PlayerJoined(steve, here, 7, firstTime = false, isOp = false) to Urgency.SOON,
            PlayerLeft(steve, here, 7, 3_600, LeaveReason.LEAVE) to Urgency.SOON,
            // Advancements: goals, challenges and the milestones of a world.
            advancement("minecraft:story/root", "goal") to Urgency.SOON,
            advancement("minecraft:end/respawn_dragon", "challenge") to Urgency.SOON,
            advancement("minecraft:story/mine_diamond", "task") to Urgency.SOON,
            advancement("minecraft:nether/obtain_ancient_debris", "task") to Urgency.SOON,
            advancement("minecraft:story/mine_stone", "task") to Route.Batch,
            // Structures: first visit, or a notable one.
            StructureEntered(steve, here, "minecraft:village_plains", firstVisit = true) to Urgency.SOON,
            StructureEntered(steve, here, "minecraft:ancient_city", firstVisit = false) to Urgency.SOON,
            StructureEntered(steve, here, "minecraft:village_plains", firstVisit = false) to Route.Batch,
            // Biomes: the first visit of a rare one wakes, of another one waits.
            BiomeEntered(steve, here, "minecraft:mushroom_fields", firstVisit = true) to Urgency.SOON,
            BiomeEntered(steve, here, "minecraft:plains", firstVisit = true) to Urgency.NEXT,
            BiomeEntered(steve, here, "minecraft:mushroom_fields", firstVisit = false) to Route.Batch,
            // Blocks of another player: griefs, counted before being told.
            broken("minecraft:oak_planks", placedBy = alex.uuid) to Route.Grief,
            broken("minecraft:oak_planks", placedBy = steve.uuid) to Route.Batch,
            // Explosions not caused by the Narrator, near a player or on built blocks.
            explosion(playerBlocks = 0, nearby = true, narrator = false) to Urgency.SOON,
            explosion(playerBlocks = 4, nearby = false, narrator = false) to Urgency.SOON,
            explosion(playerBlocks = 4, nearby = true, narrator = true) to Route.Batch,
            explosion(playerBlocks = 0, nearby = false, narrator = false) to Route.Batch,
            // Precious ores, loot chests, jumps, dimensions, respawns: with the next wake-up.
            broken("minecraft:diamond_ore") to Urgency.NEXT,
            broken("minecraft:deepslate_diamond_ore") to Urgency.NEXT,
            broken("minecraft:ancient_debris") to Urgency.NEXT,
            broken("minecraft:emerald_ore") to Urgency.NEXT,
            broken("minecraft:stone") to Route.Batch,
            LootChestOpened(steve, here, "minecraft:chests/simple_dungeon") to Urgency.NEXT,
            Teleported(steve, here.copy(x = 900), here, 780.0) to Urgency.NEXT,
            DimensionChanged(steve, nether, here, "nether_portal") to Urgency.NEXT,
            PlayerRespawned(steve, here, alive = false) to Urgency.NEXT,
        )
        rows.forEach { (event, expected) -> assertEquals(expected, routeOf(event), "$event") }
    }

    @Test
    fun `everything else is only summarized`() {
        val listed = setOf(
            "chat", "whisper", "narrator_say", "death", "kill", "health_low", "join", "leave", "advancement", "structure_enter",
            "biome_enter", "block_break", "explosion", "loot_open", "teleport", "dimension_change", "respawn",
        )
        SampleEvents.all.filter { it.type !in listed }.forEach { assertEquals(Route.Batch, Salience.route(it), "$it") }
    }

    @Test
    fun `a prayer always wakes the Director, a public message only when addressed`() {
        val addressed = ChatTriage(FakeReflexes.verdict(0.92), addressed = true, reason = "fake 0.92 >= 0.60")
        val between = ChatTriage(FakeReflexes.verdict(0.1), addressed = false, reason = "fake 0.10 < 0.35")
        assertEquals(Urgency.IMMEDIATE, Salience.chatUrgency(ChatChannel.PRAY, between))
        assertEquals(Urgency.IMMEDIATE, Salience.chatUrgency(ChatChannel.PRAY, null))
        assertEquals(Urgency.IMMEDIATE, Salience.chatUrgency(ChatChannel.PUBLIC, addressed))
        assertEquals(Urgency.NEXT, Salience.chatUrgency(ChatChannel.PUBLIC, between))
        assertEquals(Urgency.NEXT, Salience.chatUrgency(ChatChannel.PUBLIC, null))
    }

    @Test
    fun `chat annotations`() {
        fun triage(verdict: ChatVerdict, addressed: Boolean) = ChatTriage(verdict, addressed, "reason")
        val model = FakeReflexes.verdict(0.92, Tone.HOSTILE, ChatIntent.PROVOCATION)
        assertEquals("(to you 0.92, hostile, provocation)", Salience.annotation(ChatChannel.PUBLIC, triage(model, true)))
        assertEquals("(to you 0.92, provocation)", Salience.annotation(ChatChannel.PUBLIC, triage(model.copy(toneConfidence = 0.4), true)))
        assertEquals(
            "(to you 0.97, mocking_playful, request) !manipulation 0.81",
            Salience.annotation(ChatChannel.PRAY, triage(FakeReflexes.verdict(0.97, Tone.MOCKING_PLAYFUL, ChatIntent.REQUEST, manipulation = 0.81), true)),
        )
        val heuristic = model.copy(source = HeuristicReflexes.SOURCE)
        assertEquals("(to you: heuristic)", Salience.annotation(ChatChannel.PUBLIC, triage(heuristic, true)))
        assertEquals("(between players)", Salience.annotation(ChatChannel.PUBLIC, triage(FakeReflexes.verdict(0.1), false)))
        assertEquals("(to you 0.10, other)", Salience.annotation(ChatChannel.PRAY, triage(FakeReflexes.verdict(0.1), false)))
    }

    @Test
    fun `a chat message is triaged in its own coroutine, then posted with its annotation and a decision`() = runTest {
        reflexes.chat = { FakeReflexes.verdict(0.92, Tone.HOSTILE, ChatIntent.PROVOCATION) }
        val spoke = envelope(NarratorSpoke(NarratorChannel.NARRATOR, "Steve, ta pioche pleure.", null, 42, null))
        salience.handle(this, spoke)
        salience.handle(this, envelope(ChatMessage(alex, ChatChannel.PUBLIC, "salut Steve", emptyList())))
        clock.advance(java.time.Duration.ofSeconds(5))
        val chat = envelope(ChatMessage(steve, ChatChannel.PUBLIC, "narrateur t'es nul", emptyList()))
        salience.handle(this, chat)
        testScheduler.advanceUntilIdle()

        val event = assertIs<DirectorSignal.Event>(posted.last())
        assertEquals(chat, event.envelope)
        assertEquals(Urgency.IMMEDIATE, event.urgency)
        assertEquals("(to you 0.92, hostile, provocation)", event.annotation)
        val input = reflexes.chatInputs.last()
        assertEquals(listOf("salut Steve"), input.recent.map { it.text }, "the public lines before it")
        assertEquals("Steve, ta pioche pleure.", input.narratorLast, "the Narrator's line it may answer")
        val decision = services.traces.decisions.last { it.kind == DecisionRecord.Kind.SALIENCE }
        assertEquals("immediate", decision.decision)
        assertEquals("Steve", decision.subject)
        assertEquals(chat.id, decision.eventId)
        assertTrue(decision.reason!!.startsWith("addressed: fake 0.92"), decision.reason)
        assertEquals(chat.id, services.traces.chatSignals.last().eventId, "the triage wrote its chat_signals row")
    }

    @Test
    fun `the Narrator's line is not given to the triage once it is a minute old`() = runTest {
        salience.handle(this, envelope(NarratorSpoke(NarratorChannel.NARRATOR, "Tremblez.", null, 42, null)))
        clock.advance(java.time.Duration.ofSeconds(61))
        salience.handle(this, envelope(ChatMessage(steve, ChatChannel.PUBLIC, "ok", emptyList())))
        testScheduler.advanceUntilIdle()
        assertNull(reflexes.chatInputs.single().narratorLast)
    }

    @Test
    fun `private messages are never triaged, posted nor counted`() = runTest {
        salience.handle(this, envelope(ChatMessage(steve, ChatChannel.WHISPER, "secret", listOf("Alex"))))
        testScheduler.advanceUntilIdle()
        assertTrue(posted.isEmpty())
        assertTrue(reflexes.chatInputs.isEmpty())
        assertTrue(services.traces.chatSignals.isEmpty())
    }

    @Test
    fun `the blocks of another player broken within 10 s are told once, as a grief`() = runTest {
        salience.handle(this, envelope(PlayerJoined(alex, here, 3, firstTime = false, isOp = false)))
        posted.clear()
        val first = envelope(broken("minecraft:oak_planks", placedBy = alex.uuid))
        salience.handle(this, first)
        testScheduler.advanceTimeBy(4_000)
        salience.handle(this, envelope(broken("minecraft:oak_planks", placedBy = alex.uuid)))
        salience.handle(this, envelope(broken("minecraft:glass", placedBy = alex.uuid)))
        testScheduler.advanceTimeBy(5_999)
        testScheduler.runCurrent()
        assertTrue(posted.isEmpty(), "counted for 10 s first")

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        val grief = assertIs<DirectorSignal.Event>(posted.single())
        assertEquals(first, grief.envelope)
        assertEquals(Urgency.SOON, grief.urgency)
        assertEquals("broke 3 blocks placed by Alex within 10s: oak_planks 2, glass 1", grief.annotation)
        assertEquals("grief: broke 3 blocks placed by Alex within 10s: oak_planks 2, glass 1", services.traces.decisions.last().reason)

        salience.handle(this, envelope(broken("minecraft:glass", placedBy = java.util.UUID.randomUUID())))
        testScheduler.advanceTimeBy(10_001)
        testScheduler.runCurrent()
        assertEquals("broke 1 block placed by a player offline within 10s: glass 1", (posted.last() as DirectorSignal.Event).annotation)
    }

    @Test
    fun `a player wakes the Director at once 6 times per 10 minutes, then waits like a salient event`() = runTest {
        reflexes.chat = { FakeReflexes.verdict(0.92, Tone.HOSTILE, ChatIntent.PROVOCATION) }
        fun say(player: io.github.clemsix6.narrator2.event.PlayerRef, channel: ChatChannel): Urgency {
            salience.handle(this, envelope(ChatMessage(player, channel, "narrateur !", emptyList())))
            testScheduler.advanceUntilIdle()
            return (posted.last() as DirectorSignal.Event).urgency
        }
        // Four addressed messages and two prayers, one every 30 s: the budget is shared.
        val first = listOf(ChatChannel.PUBLIC, ChatChannel.PUBLIC, ChatChannel.PRAY, ChatChannel.PUBLIC, ChatChannel.PRAY, ChatChannel.PUBLIC).map { channel ->
            say(steve, channel).also { clock.advance(java.time.Duration.ofSeconds(30)) }
        }
        assertEquals(List(6) { Urgency.IMMEDIATE }, first)

        assertEquals(Urgency.SOON, say(steve, ChatChannel.PRAY), "the 7th within 10 minutes waits")
        val throttled = services.traces.decisions.last()
        assertEquals(listOf("throttled", "Steve"), listOf(throttled.decision, throttled.subject))
        assertTrue(throttled.reason!!.startsWith("prayer: fake 0.92"), throttled.reason)
        assertTrue(throttled.reason!!.endsWith("; Steve already woke the Narrator 6 times within 10 minutes: it waits like a salient event"), throttled.reason)
        assertEquals(Urgency.SOON, say(steve, ChatChannel.PUBLIC))
        assertEquals(Urgency.IMMEDIATE, say(alex, ChatChannel.PUBLIC), "another player has their own budget")

        clock.advance(java.time.Duration.ofSeconds(420)) // 10 minutes after the first message
        assertEquals(Urgency.IMMEDIATE, say(steve, ChatChannel.PUBLIC), "the first wake-up left the window")
        assertEquals(Urgency.SOON, say(steve, ChatChannel.PUBLIC), "the second has not yet")
    }

    @Test
    fun `while the budget of turns is spent, salient events are throttled, and a player's throttled message says so`() = runTest {
        val budget = TurnBudget { 1 }
        val salience = Salience(services, ActivityDigest(clock), posted::add, budget)
        salience.handle(this, envelope(PlayerDied(steve, here, "minecraft:fall", null, null, null, 3, false, false)))
        assertEquals("soon", services.traces.decisions.last().decision, "budget left")

        budget.started(clock.millis())
        val death = envelope(PlayerDied(alex, here, "minecraft:lava", null, null, null, 3, false, false))
        salience.handle(this, death)
        assertEquals(Urgency.SOON, (posted.last() as DirectorSignal.Event).urgency, "still posted: it waits for a turn")
        val decision = services.traces.decisions.last()
        assertEquals(listOf("throttled", "Alex", death.id), listOf(decision.decision, decision.subject, decision.eventId))
        assertEquals("death; the budget of 1 turn per hour is spent: it waits for a turn to leave the hour", decision.reason)

        reflexes.chat = { FakeReflexes.verdict(0.92) }
        salience.handle(this, envelope(ChatMessage(alex, ChatChannel.PUBLIC, "narrateur ?", emptyList())))
        testScheduler.advanceUntilIdle()
        assertEquals(Urgency.IMMEDIATE, (posted.last() as DirectorSignal.Event).urgency, "addressed chat still wakes the Director")
        assertEquals("immediate", services.traces.decisions.last().decision)
    }

    @Test
    fun `every event feeds the digest, salient or not`() = runTest {
        val digest = ActivityDigest(clock)
        val salience = Salience(services, digest, posted::add)
        repeat(3) { salience.handle(this, envelope(broken("minecraft:stone"))) }
        salience.handle(this, envelope(broken("minecraft:diamond_ore")))
        assertEquals("mined 4 (1 diamond_ore y=64, stone 3)", digest.recent(steve.uuid, 5))
        assertEquals(1, posted.size, "only the diamond is posted")
    }
}
