package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.SampleEvents
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.toPath
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The facts a module receives (M5 §4.3; task M5-06): each fact of the bus translated by `Facts`, the one translator,
 * and **never a fact from a prayer, a whisper or a private message** (design review, finding 11).
 */
class FactsTest {
    private val steve = SampleEvents.steve
    private val alex = SampleEvents.alex
    private val here = Pos("minecraft:overworld", 120, 64, -330)
    private val at = Place("overworld", 120.5, 64.0, -329.5)
    private val zombie = EntityRef("minecraft:zombie", UUID.fromString("00000000-0000-4000-8000-00000000000a"), null)
    private val namedWolf = EntityRef("farmersdelight:wolf", UUID.fromString("00000000-0000-4000-8000-00000000000c"), "Rex")
    private val alexEntity = EntityRef("minecraft:player", alex.uuid, alex.name, alex)
    private val players = PlayerStates()

    @Test
    fun `each fact of the bus becomes its fact of the SDK, ids without minecraft and at the middle of the floor of its block`() {
        val expected: List<Pair<GameEvent, GameFact>> = listOf(
            BlockBroken(steve, here, "minecraft:diamond_ore", "minecraft:iron_pickaxe", creative = false, correctTool = true, placedBy = null) to
                GameFact.BlockBroken("Steve", at, "diamond_ore", false),
            BlockBroken(steve, here, "minecraft:oak_planks", null, creative = false, correctTool = true, placedBy = alex.uuid) to
                GameFact.BlockBroken("Steve", at, "oak_planks", true),
            BlockBroken(steve, here, "minecraft:oak_planks", null, creative = false, correctTool = true, placedBy = steve.uuid) to
                GameFact.BlockBroken("Steve", at, "oak_planks", false),
            BlockPlaced(steve, here, "minecraft:cobblestone", "minecraft:cobblestone", PlaceKind.BLOCK) to GameFact.BlockPlaced("Steve", at, "cobblestone"),
            DamageDealt(steve, here, zombie, amount = 6.0, weapon = "minecraft:iron_sword", damageType = "minecraft:player_attack", victimHealth = 14.0) to
                GameFact.CreatureHit("Steve", at, Who("zombie", zombie.uuid.toString(), null), 6f, 14f),
            DamageDealt(steve, here, namedWolf, amount = 2.5, weapon = null, damageType = "minecraft:player_attack", victimHealth = 5.5) to
                GameFact.CreatureHit("Steve", at, Who("farmersdelight:wolf", namedWolf.uuid.toString(), "Rex"), 2.5f, 5.5f),
            DamageDealt(steve, here, alexEntity, amount = 3.0, weapon = null, damageType = "minecraft:player_attack", victimHealth = 17.0) to
                GameFact.PlayerHit("Steve", at, "Alex", 3f),
            DamageTaken(steve, here, "minecraft:mob_attack", amount = 7.5, hits = 3, attacker = zombie, blocked = false, healthAfter = 12.5) to
                GameFact.PlayerHurt("Steve", at, "mob_attack", Who("zombie", zombie.uuid.toString(), null), 7.5f),
            DamageTaken(steve, here, "minecraft:fall", amount = 4.0, hits = 1, attacker = null, blocked = false, healthAfter = 16.0) to
                GameFact.PlayerHurt("Steve", at, "fall", null, 4f),
            DamageTaken(steve, here, "minecraft:player_attack", amount = 2.0, hits = 1, attacker = alexEntity, blocked = false, healthAfter = 18.0) to
                GameFact.PlayerHurt("Steve", at, "player_attack", Who("player", alex.uuid.toString(), "Alex"), 2f),
            EntityKilled(steve, here, zombie, "minecraft:iron_sword", pvp = false, tamed = false) to
                GameFact.CreatureKilled("Steve", at, Who("zombie", zombie.uuid.toString(), null)),
            ProjectileHit(steve, here, "minecraft:arrow", EntityRef("minecraft:player", steve.uuid, steve.name, steve), zombie, null, "minecraft:bow") to
                GameFact.ProjectileHit("Steve", at, "arrow", Who("player", steve.uuid.toString(), "Steve"), Who("zombie", zombie.uuid.toString(), null)),
            ProjectileHit(steve, here, "minecraft:arrow", EntityRef("minecraft:skeleton", null, null), null, "minecraft:stone", null) to
                GameFact.ProjectileHit("Steve", at, "arrow", Who("skeleton", null, null), null),
            PlayerDied(steve, here, "minecraft:mob_attack", zombie, weapon = null, message = "Steve was slain by Zombie", xpLevel = 3, keptInventory = false, narrator = false) to
                GameFact.PlayerDied("Steve", at, "mob_attack", Who("zombie", zombie.uuid.toString(), null)),
            PlayerDied(steve, here, "minecraft:lava", null, weapon = null, message = null, xpLevel = 3, keptInventory = false, narrator = false) to
                GameFact.PlayerDied("Steve", at, "lava", null),
            ChatMessage(steve, ChatChannel.PUBLIC, "loup", emptyList()) to GameFact.Chat("Steve", null, "loup"),
            DimensionChanged(steve, Pos("minecraft:the_nether", 15, 70, -41), here, "nether_portal") to
                GameFact.DimensionChanged("Steve", Place("the_nether", 15.5, 70.0, -40.5), "overworld", "the_nether"),
            StructureEntered(steve, here, "minecraft:village_plains", firstVisit = true) to GameFact.StructureEntered("Steve", at, "village_plains"),
            BiomeEntered(steve, here, "minecraft:cherry_grove", firstVisit = false) to GameFact.BiomeEntered("Steve", at, "cherry_grove"),
            AdvancementEarned(steve, here, "minecraft:story/mine_stone", "Stone Age", "task") to GameFact.Advancement("Steve", at, "story/mine_stone"),
            ItemCrafted(steve, here, "minecraft:torch", 16, "minecraft:crafting") to GameFact.Crafted("Steve", at, "torch", 16),
            ItemConsumed(steve, here, "minecraft:cooked_beef", 12, nutrition = 8, saturation = 12.8, effects = emptyList(), foodBefore = 10, healthBefore = 20.0) to
                GameFact.Consumed("Steve", at, "cooked_beef"),
            SleepStarted(steve, here, "minecraft:red_bed") to GameFact.Slept("Steve", at),
            PlayerJoined(steve, here, sessionId = 7, firstTime = false, isOp = true) to GameFact.Joined("Steve", at),
            PlayerLeft(steve, here, sessionId = 7, sessionSeconds = 3_600, reason = LeaveReason.LEAVE) to GameFact.Left("Steve", at),
        )
        for ((event, fact) in expected) assertEquals(fact, Facts.of(event, players), "${event.type}: $event")
        assertEquals(Facts.EVENTS, expected.map { it.first::class }.toSet(), "every fact of the bus the modules listen to is translated, and only those")
    }

    @Test
    fun `a chat line is where its writer was last seen, and the other facts of the bus are no facts of a module`() {
        players.put(
            LivePlayer(steve, 1, 0, false, "survival", Pos("minecraft:the_nether", 3, 40, -7), null, null, false, 20f, 20f, 20, null, null, 0, false),
        )
        assertEquals(GameFact.Chat("Steve", Place("the_nether", 3.5, 40.0, -6.5), "loup"), Facts.of(ChatMessage(steve, ChatChannel.PUBLIC, "loup", emptyList()), players))

        val none = listOf(
            BlockPlaced(steve, here, "minecraft:water", "minecraft:water_bucket", PlaceKind.FLUID),
            BlockPlaced(steve, here, "minecraft:fire", "minecraft:flint_and_steel", PlaceKind.FIRE),
            BlockPlaced(steve, here, "minecraft:nether_portal", "minecraft:flint_and_steel", PlaceKind.PORTAL),
            EntityKilled(steve, here, alexEntity, "minecraft:bow", pvp = true, tamed = false),
        ) + SampleEvents.all.filter { it::class !in Facts.EVENTS }
        for (event in none) assertNull(Facts.of(event, players), "${event.type} is no fact of a module")
        val made = SampleEvents.all.mapNotNull { Facts.of(it, players) }
        assertTrue(made.all { it.javaClass in Facts.TYPES }, "only records of GameFact: ${made.map { it.javaClass }}")
        assertEquals(20, Facts.TYPES.size, "the twenty records")
    }

    /** Design review, finding 11: a prayer, a private message and an operator's whisper never make a fact, nor leak into one. */
    @Test
    fun `a prayer, a whisper and a private message never make a fact, and no fact carries their words`() {
        val secret = "s3cr3t-" + UUID.randomUUID()
        val private = listOf(
            ChatMessage(steve, ChatChannel.PRAY, "Narrateur, $secret", emptyList()),
            ChatMessage(steve, ChatChannel.WHISPER, "la base est derrière la cascade $secret", listOf("Alex")),
            ChatMessage(alex, ChatChannel.WHISPER, secret, listOf("Steve", "Clement")),
            CommandIssued(steve, here, "msg", null),
            CommandIssued(steve, here, "narrator", null),
            CommandIssued(steve, here, "pray", "pray $secret"),
            CommandIssued(steve, here, "tell", "tell Alex $secret"),
        )
        for (event in private) assertNull(Facts.of(event, players), "no fact from $event")
        for (channel in ChatChannel.entries.filter { it != ChatChannel.PUBLIC }) {
            assertNull(Facts.of(ChatMessage(steve, channel, secret, listOf("Alex")), players), "no fact from the ${channel.id} channel")
        }
        val everything = (SampleEvents.all + private).mapNotNull { Facts.of(it, players) }
        assertTrue(everything.none { secret in it.toString() }, "no fact carries a private word")
        assertTrue(
            everything.filterIsInstance<GameFact.Chat>().map { it.text } == listOf("narrateur, t'es nul"),
            "the one chat fact is the public line: ${everything.filterIsInstance<GameFact.Chat>()}",
        )
    }

    /**
     * Design review, finding 11: `Facts` is the one translator. No other class of the mod both reads the bus (a class of
     * `io.github.clemsix6.narrator2.event`) and builds a fact (`new GameFact$…`), so no second path could forget the
     * privacy rule. The bench (`forge/sim`) may build facts: it never sees the bus.
     */
    @Test
    fun `Facts is the only class of the mod that turns the bus into facts`() {
        val roots = listOf(Facts::class.java, GameFact::class.java).map { it.protectionDomain.codeSource.location.toURI().toPath() }.distinct()
        val translators = roots.flatMap { root ->
            Files.walk(root).use { paths -> paths.filter { it.extension == "class" }.toList() }.filter { file ->
                val reader = ClassReader(file.readBytes())
                val seen = Seen()
                reader.accept(seen, ClassReader.SKIP_DEBUG)
                seen.buildsFacts && seen.readsBus
            }.map { it.relativeTo(root).toString().replace('\\', '/') }
        }
        assertTrue(translators.isNotEmpty() && translators.all { it.startsWith("io/github/clemsix6/narrator2/forge/sdk/Facts") }, "translators: $translators")
    }

    /** Whether a class builds a fact of the SDK, and whether it names a class of the bus. */
    private class Seen : ClassVisitor(Opcodes.ASM9) {
        var buildsFacts = false
        var readsBus = false

        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            note(descriptor)
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (opcode == Opcodes.NEW && type.startsWith(FACT)) buildsFacts = true
                    note(type)
                }

                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                    note(owner)
                    note(descriptor)
                }

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                    note(owner)
                    note(descriptor)
                }
            }
        }

        private fun note(text: String?) {
            if (text != null && BUS in text) readsBus = true
        }

        companion object {
            const val FACT = "io/github/clemsix6/narrator2/forge/api/GameFact$"
            const val BUS = "io/github/clemsix6/narrator2/event/"
        }
    }
}
