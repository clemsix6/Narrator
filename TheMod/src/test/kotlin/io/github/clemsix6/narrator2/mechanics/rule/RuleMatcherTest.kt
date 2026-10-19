package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.Near
import io.github.clemsix6.narrator2.mechanics.RuleConditions
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules' matching (M4 §8.2, §8.3, §8.6; task M4-18): each trigger from its fact of the bus, its conditions
 * (ids, tags, grief, place, words, players), the chance drawn with a seed, the limits (3 s per actor, 30 per 10
 * min, 200 a minute for `mob_spawn`), the rising edge of `position`, the outcome of a firing, the `Rules:` line,
 * and the load: 10 000 facts against 8 rules in less than 100 ms of CPU.
 */
class RuleMatcherTest {
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val players = PlayerStates()

    /** The tags of these tests, as a server would hold them. */
    private val tags = RuleTags { kind, tag ->
        when (kind to tag) {
            RuleIdKind.BLOCK to "#minecraft:diamond_ores" -> setOf("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")
            RuleIdKind.ENTITY to "#minecraft:skeletons" -> setOf("minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton")
            RuleIdKind.STRUCTURE to "#minecraft:village" -> setOf("minecraft:village_plains", "minecraft:village_desert")
            RuleIdKind.BIOME to "#minecraft:is_ocean" -> setOf("minecraft:ocean", "minecraft:deep_ocean")
            RuleIdKind.ITEM to "#minecraft:logs" -> setOf("minecraft:oak_log", "minecraft:birch_log")
            else -> emptySet()
        }
    }

    // ── Each trigger from its fact ──

    @Test
    fun `each fact of the bus is its trigger with its actor, its subject and its position`() {
        val cow = EntityRef("minecraft:cow", UUID.randomUUID(), null)
        val skeleton = EntityRef("minecraft:skeleton", UUID.randomUUID(), null)
        val zombie = EntityRef("minecraft:zombie", UUID.randomUUID(), "Gérard")
        val mimosaHit = EntityRef("minecraft:player", mimosa.uuid, mimosa.name, mimosa)

        val broken = fact(BlockBroken(bartok, HERE, "minecraft:diamond_ore", null, false, true, placedBy = null))
        assertEquals(RuleTrigger.BLOCK_BREAK, broken.trigger)
        assertEquals("Bartok broke diamond_ore at 10 12 -30", broken.what)
        assertEquals(bartok, broken.actor)
        assertEquals(HERE, broken.position)
        assertEquals(false, broken.grief)
        assertEquals("Bartok broke oak_planks that another player placed at 10 12 -30", fact(BlockBroken(bartok, HERE, "minecraft:oak_planks", null, false, true, mimosa.uuid)).what)
        assertEquals(false, fact(BlockBroken(bartok, HERE, "minecraft:oak_planks", null, false, true, bartok.uuid)).grief, "his own block")

        val hit = fact(DamageDealt(bartok, HERE, cow, 1.0, null, "minecraft:player_attack", 9.0))
        assertEquals(RuleSubject.Creature(cow.uuid!!, "minecraft:cow"), hit.subject)
        assertEquals("minecraft:cow", hit.creature)
        assertEquals("Bartok hit a cow at 10 12 -30", hit.what)
        assertEquals(RuleSubject.Player(mimosa), fact(DamageDealt(bartok, HERE, mimosaHit, 1.0, null, "minecraft:player_attack", 19.0)).subject)

        val arrow = fact(ProjectileHit(bartok, HERE, "minecraft:arrow", skeleton, EntityRef("minecraft:player", bartok.uuid, bartok.name, bartok), null, null))
        assertEquals(RuleSubject.Creature(skeleton.uuid!!, "minecraft:skeleton"), arrow.subject)
        assertEquals("minecraft:skeleton", arrow.creature)
        assertEquals("a skeleton's arrow hit Bartok at 10 12 -30", arrow.what)
        val shot = fact(ProjectileHit(bartok, HERE, "minecraft:arrow", EntityRef("minecraft:player", bartok.uuid, bartok.name, bartok), cow, null, null))
        assertNull(shot.subject, "his own arrow: no subject")
        assertNull(shot.creature)

        val died = fact(PlayerDied(bartok, NETHER, "minecraft:mob_attack", zombie, null, null, 3, false, false))
        assertEquals(RuleSubject.Creature(zombie.uuid!!, "minecraft:zombie"), died.subject)
        assertEquals("Bartok died killed by a zombie named « Gérard » at -12 70 40 in the_nether", died.what)
        assertNull(fact(PlayerDied(bartok, HERE, "minecraft:fall", null, null, null, 3, false, false)).subject)

        assertEquals("minecraft:zombie", fact(EntityKilled(bartok, HERE, zombie, null, pvp = false, tamed = false)).creature)
        assertEquals(RuleTrigger.DIMENSION_CHANGE, fact(DimensionChanged(bartok, NETHER, HERE, "nether_portal")).trigger)
        assertEquals("Bartok entered the_nether (-12 70 40)", fact(DimensionChanged(bartok, NETHER, HERE, "nether_portal")).what)
        assertEquals("minecraft:village_plains", fact(StructureEntered(bartok, HERE, "minecraft:village_plains", true)).structure)
        assertEquals("minecraft:desert", fact(BiomeEntered(bartok, HERE, "minecraft:desert", false)).biome)
        assertEquals("minecraft:story/mine_diamond", fact(AdvancementEarned(bartok, HERE, "minecraft:story/mine_diamond", "Diamonds!", "task")).advancement)
        assertEquals("minecraft:oak_log", fact(ItemCrafted(bartok, HERE, "minecraft:oak_log", 4, "minecraft:crafting")).item)
        assertEquals("minecraft:bread", fact(ItemConsumed(bartok, HERE, "minecraft:bread", 3, 5, 6.0, emptyList(), 10, 20.0)).item)
        assertEquals(RuleTrigger.SLEEP, fact(SleepStarted(bartok, HERE, "minecraft:red_bed")).trigger)
        assertEquals(RuleTrigger.JOIN, fact(PlayerJoined(bartok, HERE, 1, false, false)).trigger)
        assertEquals(RuleTrigger.BLOCK_PLACE, fact(BlockPlaced(bartok, HERE, "minecraft:tnt", "minecraft:tnt", PlaceKind.BLOCK)).trigger)
    }

    @Test
    fun `what no rule sees gives no fact`() {
        assertNull(RuleFacts.of(BlockPlaced(bartok, HERE, "minecraft:water", "minecraft:water_bucket", PlaceKind.FLUID), players), "a fluid poured")
        assertNull(RuleFacts.of(EntityKilled(bartok, HERE, EntityRef("minecraft:player", mimosa.uuid, mimosa.name, mimosa), null, pvp = true, tamed = false), players), "PvP")
        assertNull(RuleFacts.of(ChatMessage(bartok, ChatChannel.PRAY, "Ô Narrateur", emptyList()), players), "a prayer")
        assertNull(RuleFacts.of(ChatMessage(bartok, ChatChannel.WHISPER, "chut", listOf("Mimosa")), players), "a whisper")
    }

    @Test
    fun `a chat is where its writer stands, and its text is never in what the rule tells`() {
        val chat = ChatMessage(bartok, ChatChannel.PUBLIC, "Le Narrateur est NUL !", emptyList())
        assertNull(fact(chat).position, "a writer the sampler has not seen")
        online(bartok, HERE)
        val seen = fact(chat)
        assertEquals(HERE, seen.position)
        assertEquals("Bartok spoke in the public chat", seen.what)
        assertEquals("Le Narrateur est NUL !", seen.text)
    }

    // ── Conditions ──

    @Test
    fun `ids and tags of blocks, creatures, items, structures and biomes`() {
        val diamonds = matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(block = "#minecraft:diamond_ores"))
        assertTrue(diamonds.matches(fact(BlockBroken(bartok, HERE, "minecraft:deepslate_diamond_ore", null, false, true, null))))
        assertFalse(diamonds.matches(fact(BlockBroken(bartok, HERE, "minecraft:stone", null, false, true, null))))
        assertFalse(diamonds.matches(fact(BlockPlaced(bartok, HERE, "minecraft:diamond_ore", "minecraft:diamond_ore", PlaceKind.BLOCK))), "another trigger")

        val skeletons = matcher(RuleTrigger.PROJECTILE_HIT, RuleConditions(entity = "#minecraft:skeletons"))
        val stray = EntityRef("minecraft:stray", UUID.randomUUID(), null)
        assertTrue(skeletons.matches(fact(ProjectileHit(bartok, HERE, "minecraft:arrow", stray, null, null, null))))
        assertFalse(skeletons.matches(fact(ProjectileHit(bartok, HERE, "minecraft:arrow", EntityRef("minecraft:player", bartok.uuid, "Bartok", bartok), null, null, null))))

        val cows = matcher(RuleTrigger.DAMAGE_DEALT, RuleConditions(entity = "minecraft:cow"))
        assertTrue(cows.matches(fact(DamageDealt(bartok, HERE, EntityRef("minecraft:cow", UUID.randomUUID(), null), 1.0, null, "x", 9.0))))
        assertFalse(cows.matches(fact(DamageDealt(bartok, HERE, EntityRef("minecraft:pig", UUID.randomUUID(), null), 1.0, null, "x", 9.0))))

        val logs = matcher(RuleTrigger.CRAFT, RuleConditions(item = "#minecraft:logs"))
        assertTrue(logs.matches(fact(ItemCrafted(bartok, HERE, "minecraft:birch_log", 1, "x"))))
        assertFalse(logs.matches(fact(ItemCrafted(bartok, HERE, "minecraft:oak_planks", 4, "x"))))

        val villages = matcher(RuleTrigger.STRUCTURE_ENTER, RuleConditions(structure = "#minecraft:village"))
        assertTrue(villages.matches(fact(StructureEntered(bartok, HERE, "minecraft:village_desert", false))))
        assertFalse(villages.matches(fact(StructureEntered(bartok, HERE, "minecraft:stronghold", false))))

        val oceans = matcher(RuleTrigger.BIOME_ENTER, RuleConditions(biome = "#minecraft:is_ocean"))
        assertTrue(oceans.matches(fact(BiomeEntered(bartok, HERE, "minecraft:deep_ocean", false))))
        assertFalse(oceans.matches(fact(BiomeEntered(bartok, HERE, "minecraft:plains", false))))

        val diamond = matcher(RuleTrigger.ADVANCEMENT, RuleConditions(advancement = "minecraft:story/mine_diamond"))
        assertTrue(diamond.matches(fact(AdvancementEarned(bartok, HERE, "minecraft:story/mine_diamond", "Diamonds!", "task"))))
        assertFalse(diamond.matches(fact(AdvancementEarned(bartok, HERE, "minecraft:story/mine_stone", "Stone Age", "task"))))

        val unknownTag = matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(block = "#minecraft:gone_from_the_game"))
        assertFalse(unknownTag.matches(fact(BlockBroken(bartok, HERE, "minecraft:stone", null, false, true, null))), "a tag no longer known holds nothing")
    }

    @Test
    fun `grief, the killer, the dimension of arrival and the players by their uuid`() {
        val grief = matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(grief = true))
        assertTrue(grief.matches(fact(BlockBroken(bartok, HERE, "minecraft:oak_planks", null, false, true, mimosa.uuid))))
        assertFalse(grief.matches(fact(BlockBroken(bartok, HERE, "minecraft:oak_planks", null, false, true, bartok.uuid))), "his own")
        assertFalse(grief.matches(fact(BlockBroken(bartok, HERE, "minecraft:stone", null, false, true, null))), "nobody's")
        val notGrief = matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(grief = false))
        assertTrue(notGrief.matches(fact(BlockBroken(bartok, HERE, "minecraft:stone", null, false, true, null))))

        val zombies = matcher(RuleTrigger.DEATH, RuleConditions(entity = "minecraft:zombie"))
        assertTrue(zombies.matches(fact(PlayerDied(bartok, HERE, "minecraft:mob_attack", EntityRef("minecraft:zombie", UUID.randomUUID(), null), null, null, 0, false, false))))
        assertFalse(zombies.matches(fact(PlayerDied(bartok, HERE, "minecraft:fall", null, null, null, 0, false, false))))

        val welcome = matcher(RuleTrigger.DIMENSION_CHANGE, RuleConditions(players = listOf("Bartok"), dimension = "minecraft:the_nether"), players = listOf(bartok.uuid))
        assertTrue(welcome.matches(fact(DimensionChanged(bartok, NETHER, HERE, "nether_portal"))))
        assertTrue(welcome.matches(fact(DimensionChanged(bartok.copy(name = "Bartok2"), NETHER, HERE, "other"))), "renamed: still him, by his uuid")
        assertFalse(welcome.matches(fact(DimensionChanged(mimosa, NETHER, HERE, "nether_portal"))), "another player")
        assertFalse(welcome.matches(fact(DimensionChanged(bartok, HERE, NETHER, "nether_portal"))), "back to the overworld")
    }

    @Test
    fun `heights, a radius around a point in its dimension, and words of the chat`() {
        val deep = matcher(RuleTrigger.BLOCK_PLACE, RuleConditions(yBelow = 12, yAbove = -20))
        assertTrue(deep.matches(fact(BlockPlaced(bartok, Pos(OVERWORLD, 0, 12, 0), "minecraft:tnt", "minecraft:tnt", PlaceKind.BLOCK))), "y ≤ 12, inclusive")
        assertFalse(deep.matches(fact(BlockPlaced(bartok, Pos(OVERWORLD, 0, 13, 0), "minecraft:tnt", "minecraft:tnt", PlaceKind.BLOCK))))
        assertTrue(deep.matches(fact(BlockPlaced(bartok, Pos(OVERWORLD, 0, -20, 0), "minecraft:tnt", "minecraft:tnt", PlaceKind.BLOCK))), "y ≥ -20, inclusive")
        assertFalse(deep.matches(fact(BlockPlaced(bartok, Pos(OVERWORLD, 0, -21, 0), "minecraft:tnt", "minecraft:tnt", PlaceKind.BLOCK))))

        val spawn = matcher(RuleTrigger.SLEEP, RuleConditions(near = Near(100, 64, 100, radius = 5)))
        assertTrue(spawn.matches(fact(SleepStarted(bartok, Pos(OVERWORLD, 103, 64, 104), "minecraft:red_bed"))), "5 blocks away exactly")
        assertFalse(spawn.matches(fact(SleepStarted(bartok, Pos(OVERWORLD, 103, 65, 104), "minecraft:red_bed"))), "a block too far, counting the height")
        assertFalse(spawn.matches(fact(SleepStarted(bartok, Pos(NETHER_ID, 100, 64, 100), "minecraft:red_bed"))), "the overworld's point")
        val netherSpawn = matcher(RuleTrigger.SLEEP, RuleConditions(near = Near(100, 64, 100, "minecraft:the_nether", 5)))
        assertTrue(netherSpawn.matches(fact(SleepStarted(bartok, Pos(NETHER_ID, 100, 64, 100), "minecraft:red_bed"))))

        val insult = matcher(RuleTrigger.CHAT, RuleConditions(words = listOf("nul", "pacotille")))
        assertTrue(insult.matches(fact(ChatMessage(bartok, ChatChannel.PUBLIC, "Le Narrateur est NUL !", emptyList()))))
        assertTrue(insult.matches(fact(ChatMessage(bartok, ChatChannel.PUBLIC, "un dieu de PACOTILLE", emptyList()))))
        assertFalse(insult.matches(fact(ChatMessage(bartok, ChatChannel.PUBLIC, "nullement", emptyList()))), "a whole word")

        val near = matcher(RuleTrigger.CHAT, RuleConditions(near = Near(10, 12, -30, radius = 3)))
        assertFalse(near.matches(fact(ChatMessage(bartok, ChatChannel.PUBLIC, "salut", emptyList()))), "nowhere known: no place holds")
        online(bartok, HERE)
        assertTrue(near.matches(fact(ChatMessage(bartok, ChatChannel.PUBLIC, "salut", emptyList()))))
    }

    @Test
    fun `the chance is drawn once everything else holds, the same with the same seed`() {
        val rare = matcher(RuleTrigger.JOIN, RuleConditions(chance = 0.3))
        val draws = List(1_000) { rare.drawn(Random(it.toLong())) }
        assertEquals(draws, List(1_000) { rare.drawn(Random(it.toLong())) }, "the same seed, the same draw")
        val count = draws.count { it }
        assertTrue(count in 250..350, "about 30%: $count")
        val always = matcher(RuleTrigger.JOIN, RuleConditions())
        val random = Random(7)
        assertTrue((1..100).all { always.drawn(random) })
        assertEquals(Random(7).nextDouble(), random.nextDouble(), "a chance of 1 draws nothing")
    }

    // ── Limits ──

    @Test
    fun `one firing per rule and per actor every 3 s, 30 per rule in 10 min`() {
        val limits = RuleLimits()
        assertTrue(limits.allow(60, RuleTrigger.BLOCK_BREAK, bartok.uuid, T0))
        assertFalse(limits.allow(60, RuleTrigger.BLOCK_BREAK, bartok.uuid, T0 + 2_999), "within 3 s")
        assertTrue(limits.allow(60, RuleTrigger.BLOCK_BREAK, mimosa.uuid, T0 + 2_999), "another actor")
        assertTrue(limits.allow(61, RuleTrigger.BLOCK_BREAK, bartok.uuid, T0 + 2_999), "another rule")
        assertTrue(limits.allow(60, RuleTrigger.BLOCK_BREAK, bartok.uuid, T0 + 3_000), "3 s later")

        val window = RuleLimits()
        val times = (0 until 30).map { T0 + it * 3_000L }
        times.forEach { assertTrue(window.allow(70, RuleTrigger.BLOCK_BREAK, bartok.uuid, it)) }
        val last = times.last() + 3_000
        assertFalse(window.allow(70, RuleTrigger.BLOCK_BREAK, bartok.uuid, last), "the 31st in 10 min")
        assertFalse(window.allow(70, RuleTrigger.BLOCK_BREAK, mimosa.uuid, last), "per rule, whoever acts")
        assertTrue(window.allow(70, RuleTrigger.BLOCK_BREAK, bartok.uuid, T0 + MechanicsLimits.RULE_WINDOW_MS), "the first one left the window")

        val spawns = RuleLimits()
        repeat(200) { assertTrue(spawns.allow(80, RuleTrigger.MOB_SPAWN, null, T0 + it)) }
        assertFalse(spawns.allow(80, RuleTrigger.MOB_SPAWN, null, T0 + 200), "200 a minute")
        assertTrue(spawns.allow(80, RuleTrigger.MOB_SPAWN, null, T0 + 60_000), "a minute later")
        spawns.forget(80)
        repeat(200) { assertTrue(spawns.allow(80, RuleTrigger.MOB_SPAWN, null, T0 + 60_001)) }
    }

    @Test
    fun `position fires on the rising edge, never for where a player already is`() {
        val edges = PositionEdges()
        assertFalse(edges.rose(60, bartok.uuid, true), "already there when first seen: no firing")
        assertFalse(edges.rose(60, bartok.uuid, true), "still there")
        assertFalse(edges.rose(60, bartok.uuid, false), "left: armed again")
        assertTrue(edges.rose(60, bartok.uuid, true), "came back")
        assertFalse(edges.rose(60, bartok.uuid, true))
        assertFalse(edges.rose(60, mimosa.uuid, false), "first seen elsewhere")
        assertTrue(edges.rose(60, mimosa.uuid, true), "then came")

        edges.forgetPlayer(bartok.uuid)
        assertFalse(edges.rose(60, bartok.uuid, false), "reconnected: a baseline again")
        assertTrue(edges.rose(60, bartok.uuid, true))
        edges.forgetRule(60)
        assertFalse(edges.rose(60, mimosa.uuid, true), "a rule made again starts from a baseline")
    }

    @Test
    fun `a position holds where the rule watches, the structure and the biome included`() {
        val village = matcher(RuleTrigger.POSITION, RuleConditions(structure = "#minecraft:village", yAbove = 60))
        assertTrue(village.matches(RuleFacts.position(bartok, Pos(OVERWORLD, 0, 64, 0), "minecraft:village_plains", "minecraft:plains")))
        assertFalse(village.matches(RuleFacts.position(bartok, Pos(OVERWORLD, 0, 64, 0), null, "minecraft:plains")))
        assertFalse(village.matches(RuleFacts.position(bartok, Pos(OVERWORLD, 0, 50, 0), "minecraft:village_plains", "minecraft:plains")))
        val ocean = matcher(RuleTrigger.POSITION, RuleConditions(biome = "#minecraft:is_ocean"))
        assertTrue(ocean.matches(RuleFacts.position(bartok, Pos(OVERWORLD, 0, 62, 0), null, "minecraft:ocean")))
        assertEquals("Bartok came at 0 64 0 in village_plains", RuleFacts.position(bartok, Pos(OVERWORLD, 0, 64, 0), "minecraft:village_plains", "minecraft:plains").what)
    }

    @Test
    fun `a new creature matches by its type, its dimension and a radius`() {
        val creepers = matcher(RuleTrigger.MOB_SPAWN, RuleConditions(entity = "minecraft:creeper", dimension = "minecraft:overworld"))
        val uuid = UUID.randomUUID()
        assertTrue(creepers.matches(RuleFacts.spawned(uuid, "minecraft:creeper", HERE)))
        assertFalse(creepers.matches(RuleFacts.spawned(uuid, "minecraft:zombie", HERE)))
        assertFalse(creepers.matches(RuleFacts.spawned(uuid, "minecraft:creeper", NETHER)))
        assertEquals(RuleSubject.Creature(uuid, "minecraft:creeper"), RuleFacts.spawned(uuid, "minecraft:creeper", HERE).subject)
        assertEquals("a creeper appeared at 10 12 -30", RuleFacts.spawned(uuid, "minecraft:creeper", HERE).what)
    }

    // ── Outcomes and the situation ──

    @Test
    fun `a firing is fired with one effect done, failed with one failed and none done, skipped otherwise`() {
        fun run(vararg outcomes: EffectOutcome) = FiringOutcome.of(outcomes.map { EffectRun("x", it, "") })
        assertEquals(FiringOutcome.FIRED, run(EffectOutcome.FAILED, EffectOutcome.DONE), "the cows' fireworks failed, the rest flew")
        assertEquals(FiringOutcome.FAILED, run(EffectOutcome.REFUSED, EffectOutcome.FAILED))
        assertEquals(FiringOutcome.SKIPPED, run(EffectOutcome.REFUSED, EffectOutcome.SKIPPED))
    }

    @Test
    fun `the Rules line lists the rules oldest first, within its 400 characters`() {
        val zone = ZoneOffset.UTC
        val now = MechanicsSamples.evening
        assertNull(RuleEngine.situation(emptyList(), now, zone), "nothing to say")
        val two = listOf(MechanicsSamples.state.rules[0], MechanicsSamples.state.rules[1])
        val line = assertNotNull(RuleEngine.situation(two, now, zone))
        assertTrue(line.startsWith("Rules: C-60 law block_break grief → punish, actionbar ("), line)
        assertTrue(" · C-61 plan once dimension_change " in line, line)

        val many = (1..8).map { MechanicsSamples.state.rules[0].copy(id = 60L + it, fired = 1_000 + it) }
        val cut = assertNotNull(RuleEngine.situation(many, now, zone))
        assertTrue(cut.length <= MechanicsLimits.SITUATION_LINE, "${cut.length}")
        assertTrue(Regex("· \\(\\+\\d more\\)$").containsMatchIn(cut), cut)
        assertTrue(cut.startsWith("Rules: C-61 law"), cut)
    }

    // ── Load ──

    @Test
    fun `10 000 facts against 8 rules take less than 100 ms of CPU`() {
        online(bartok, HERE)
        val rules = listOf(
            matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(block = "#minecraft:diamond_ores")),
            matcher(RuleTrigger.BLOCK_BREAK, RuleConditions(grief = true)),
            matcher(RuleTrigger.DAMAGE_DEALT, RuleConditions(entity = "minecraft:cow")),
            matcher(RuleTrigger.PROJECTILE_HIT, RuleConditions(entity = "#minecraft:skeletons")),
            matcher(RuleTrigger.CHAT, RuleConditions(words = listOf("nul", "pacotille", "ridicule"), near = Near(10, 12, -30, radius = 64))),
            matcher(RuleTrigger.DIMENSION_CHANGE, RuleConditions(players = listOf("Bartok"), dimension = "minecraft:the_nether"), players = listOf(bartok.uuid)),
            matcher(RuleTrigger.BLOCK_PLACE, RuleConditions(yBelow = 0, chance = 0.5)),
            matcher(RuleTrigger.CRAFT, RuleConditions(item = "#minecraft:logs")),
        ).groupBy { it.trigger }
        val cow = EntityRef("minecraft:cow", UUID.randomUUID(), null)
        val facts: List<GameEvent> = List(10_000) { i ->
            when (i % 6) {
                0 -> BlockBroken(bartok, HERE, if (i % 12 == 0) "minecraft:diamond_ore" else "minecraft:stone", null, false, true, if (i % 18 == 0) mimosa.uuid else null)
                1 -> DamageDealt(bartok, HERE, cow, 1.0, null, "minecraft:player_attack", 9.0)
                2 -> ChatMessage(bartok, ChatChannel.PUBLIC, "le Narrateur est un dieu de pacotille, vraiment ridicule $i", emptyList())
                3 -> BlockPlaced(bartok, Pos(OVERWORLD, i, -5, i), "minecraft:stone", "minecraft:stone", PlaceKind.BLOCK)
                4 -> DimensionChanged(bartok, NETHER, HERE, "nether_portal")
                else -> ItemCrafted(bartok, HERE, "minecraft:oak_log", 1, "minecraft:crafting")
            }
        }
        val random = Random(42)
        fun pass(): Int {
            var matched = 0
            for (event in facts) {
                val matchers = rules[RuleFacts.triggerOf(event)] ?: continue
                val fact = RuleFacts.of(event, players) ?: continue
                for (matcher in matchers) if (matcher.matches(fact) && matcher.drawn(random)) matched++
            }
            return matched
        }
        repeat(3) { pass() } // warm up
        val threads = ManagementFactory.getThreadMXBean()
        val start = threads.currentThreadCpuTime
        val matched = pass()
        val ms = (threads.currentThreadCpuTime - start) / 1_000_000.0
        assertTrue(matched > 5_000, "most facts match a rule: $matched")
        assertTrue(ms < 100.0, "10 000 facts against 8 rules took $ms ms of CPU")
    }

    // ── Helpers ──

    private fun fact(event: GameEvent): RuleFact = assertNotNull(RuleFacts.of(event, players), "a fact of $event")

    private fun matcher(trigger: RuleTrigger, conditions: RuleConditions, players: List<UUID> = emptyList()): RuleMatcher {
        val spec = RuleSpec(
            kind = RuleKind.LAW,
            statement = "Le Narrateur a décrété une loi d'essai.",
            trigger = trigger,
            conditions = conditions,
            effects = listOf(RuleEffect("notify", EffectTarget.ACTOR)),
            minutes = 60,
        )
        return RuleMatcher(RuleState(60, spec, T0, T0 + 3_600_000, null, null, players.map { it.toString() }), tags)
    }

    private fun online(player: PlayerRef, pos: Pos) {
        players.put(LivePlayer(player, 1, T0, false, "survival", pos, null, null, false, 20f, 20f, 20, null, null, T0, false))
    }

    private companion object {
        const val T0 = 1_760_900_000_000L
        const val OVERWORLD = "minecraft:overworld"
        const val NETHER_ID = "minecraft:the_nether"
        val HERE = Pos(OVERWORLD, 10, 12, -30)
        val NETHER = Pos(NETHER_ID, -12, 70, 40)
    }
}
