package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.action.EnchantmentSpec
import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where « a relic always stays a good item » is decided (M7-RELICS): the armorer's drafts against the rules that need no
 * registry ([RelicRules]) and the bounds of their schema ([ToolArgs]); and every id of the whitelists exists in the game.
 */
class RelicRulesTest {
    private fun relic(
        enchantments: List<EnchantmentSpec> = emptyList(),
        attributes: List<RelicAttribute> = emptyList(),
        name: String = "La Pelle du Repentir",
        lore: List<String> = listOf("Forgée pour qui creuse sous sa propre maison."),
    ) = RelicDraft("iron_shovel", name, lore, enchantments, attributes)

    private fun problems(draft: RelicDraft) = RelicRules.relic(draft).joinToString(" | ")

    @Test
    fun `a relic with one bonus and at most one light malus holds`() {
        assertEquals(emptyList(), RelicRules.relic(relic(enchantments = listOf(EnchantmentSpec("efficiency", 5)))))
        val bonusAndMalus = relic(attributes = listOf(RelicAttribute(RelicAttributeKey.ATTACK_DAMAGE, 3.0), RelicAttribute(RelicAttributeKey.MAX_HEALTH, -2.0)))
        assertEquals(emptyList(), RelicRules.relic(bonusAndMalus))
        assertEquals(emptyList(), RelicRules.relic(relic(attributes = listOf(RelicAttribute(RelicAttributeKey.MOVEMENT_SPEED, 0.2)))))
    }

    @Test
    fun `a relic without any bonus is refused, even unbreakable or with a malus alone`() {
        assertTrue("at least one bonus" in problems(relic().copy(unbreakable = true)), problems(relic()))
        assertTrue("at least one bonus" in problems(relic(attributes = listOf(RelicAttribute(RelicAttributeKey.LUCK, -1.0)))))
    }

    @Test
    fun `a relic has one malus at most, light, and only where a malus is allowed`() {
        val two = relic(
            enchantments = listOf(EnchantmentSpec("unbreaking", 3)),
            attributes = listOf(RelicAttribute(RelicAttributeKey.MAX_HEALTH, -2.0), RelicAttribute(RelicAttributeKey.MOVEMENT_SPEED, -0.1)),
        )
        assertTrue("at most one malus" in problems(two), problems(two))
        val heavy = relic(enchantments = listOf(EnchantmentSpec("unbreaking", 3)), attributes = listOf(RelicAttribute(RelicAttributeKey.MAX_HEALTH, -2.5)))
        assertTrue("max_health: a malus stays light, down to -2" in problems(heavy), problems(heavy))
        val slow = relic(enchantments = listOf(EnchantmentSpec("unbreaking", 3)), attributes = listOf(RelicAttribute(RelicAttributeKey.MOVEMENT_SPEED, -0.3)))
        assertTrue("movement_speed: a malus stays light, down to -0.1" in problems(slow), problems(slow))
        val never = relic(enchantments = listOf(EnchantmentSpec("unbreaking", 3)), attributes = listOf(RelicAttribute(RelicAttributeKey.KNOCKBACK_RESISTANCE, -0.2)))
        assertTrue("knockback_resistance is never a malus" in problems(never), problems(never))
    }

    @Test
    fun `a bonus stays within its cap, and each attribute and enchantment comes once`() {
        val strong = relic(attributes = listOf(RelicAttribute(RelicAttributeKey.MAX_HEALTH, 6.5)))
        assertTrue("max_health: a bonus goes up to 6" in problems(strong), problems(strong))
        val twice = relic(attributes = listOf(RelicAttribute(RelicAttributeKey.LUCK, 1.0), RelicAttribute(RelicAttributeKey.LUCK, 2.0)))
        assertTrue("one change per attribute (repeated: luck)" in problems(twice), problems(twice))
        val enchanted = relic(enchantments = listOf(EnchantmentSpec("efficiency", 2), EnchantmentSpec("minecraft:efficiency", 3)))
        assertTrue("enchantments: each once (repeated: efficiency)" in problems(enchanted), problems(enchanted))
        val zero = relic(enchantments = listOf(EnchantmentSpec("unbreaking", 3)), attributes = listOf(RelicAttribute(RelicAttributeKey.ARMOR, 0.0)))
        assertTrue("armor: an amount of 0 changes nothing" in problems(zero), problems(zero))
    }

    @Test
    fun `nothing touches how a player moves through the world`() {
        val ids = RelicAttributeKey.entries.map { it.id }
        assertTrue(ids.none { it in setOf("scale", "gravity", "jump_strength", "step_height", "safe_fall_distance", "fall_damage_multiplier") }, "$ids")
        val movers = RelicAttributeKey.entries.filter { it.malusMin < 0 && it.id.contains("movement") }
        assertTrue(movers.all { it.malusMin >= -0.1 }, "a slowdown is light: $movers")
    }

    @Test
    fun `a relic needs a name and a lore that show`() {
        assertTrue("name has nothing to show" in problems(relic(enchantments = listOf(EnchantmentSpec("unbreaking", 1)), name = " §c ")))
        assertTrue("lore: at least one line" in problems(relic(enchantments = listOf(EnchantmentSpec("unbreaking", 1)), lore = listOf("  "))))
    }

    @Test
    fun `a strange consumable keeps its effects within their caps`() {
        val apple = ConsumableDraft(
            "apple", "Pomme douteuse", listOf("Une chance sur deux. Au moins."),
            listOf(ConsumableEffect(ConsumableEffectKey.SPEED, 60, 2, 0.5), ConsumableEffect(ConsumableEffectKey.NAUSEA, 10, 1, 0.5)),
        )
        assertEquals(emptyList(), RelicRules.consumable(apple))
        val long = apple.copy(effects = listOf(ConsumableEffect(ConsumableEffectKey.BLINDNESS, 30)))
        assertEquals(listOf("blindness lasts 10 s at most (got 30)"), RelicRules.consumable(long))
        val strong = apple.copy(effects = listOf(ConsumableEffect(ConsumableEffectKey.STRENGTH, 30, 2)))
        assertEquals(listOf("strength goes up to level 1 (got 2)"), RelicRules.consumable(strong))
        val ids = ConsumableEffectKey.entries.map { it.id }
        assertTrue(ids.none { it in setOf("instant_damage", "wither", "levitation", "bad_omen") }, "nothing deadly: $ids")
    }

    @Test
    fun `a champion's factors and effects are capped`() {
        fun gerard(mob: String = "zombie", health: String = "2.5", damage: String = "1.2", speed: String = "1.1") =
            """{"mob": "$mob", "name": "Gérard le Persistant", "lore": "Il revient toujours.", "health": $health, "damage": $damage, "speed": $speed}"""
        val ok = ToolArgs.decode(ChampionDraft.serializer(), gerard())
        assertTrue(ok is ToolArgs.Decoded.Ok, "$ok")
        assertEquals(emptyList(), RelicRules.champion(ok.args))
        for ((field, json) in listOf("health" to gerard(health = "3.5"), "damage" to gerard(damage = "2"), "speed" to gerard(speed = "1.6"))) {
            val decoded = ToolArgs.decode(ChampionDraft.serializer(), json)
            assertTrue(decoded is ToolArgs.Decoded.Invalid && field in decoded.problem, "$field: $decoded")
        }
        val creeper = ToolArgs.decode(ChampionDraft.serializer(), gerard(mob = "creeper"))
        assertTrue(creeper is ToolArgs.Decoded.Invalid, "a creeper is no champion: $creeper")
        val draft = ok.args
        assertEquals(
            listOf("speed goes up to level 1 on a champion (got 2)"),
            RelicRules.champion(draft.copy(effects = listOf(ChampionEffect(ChampionEffectKey.SPEED, 2), ChampionEffect(ChampionEffectKey.ABSORPTION, 2)))),
        )
        assertTrue(RelicRules.champion(draft.copy(name = "  ")).single().startsWith("name has nothing to show"))
    }

    @Test
    fun `a book needs a title, an author and a page that show`() {
        assertEquals(emptyList(), RelicRules.book(BookDraft("Chroniques de Bartok", "Un témoin anonyme", listOf("Il était une fois un trou."))))
        assertEquals(3, RelicRules.book(BookDraft(" ", "§k", listOf(" "))).size)
    }

    @Test
    fun `every id of the whitelists exists in the game`() {
        fun has(registry: net.minecraft.core.Registry<*>, id: String) = registry.containsKey(Identifier.withDefaultNamespace(id))
        assertEquals(emptyList(), RelicAttributeKey.entries.filterNot { has(BuiltInRegistries.ATTRIBUTE, it.id) })
        assertEquals(emptyList(), ConsumableEffectKey.entries.filterNot { has(BuiltInRegistries.MOB_EFFECT, it.id) })
        assertEquals(emptyList(), ChampionEffectKey.entries.filterNot { has(BuiltInRegistries.MOB_EFFECT, it.id) })
        assertEquals(emptyList(), ChampionMob.entries.filterNot { has(BuiltInRegistries.ENTITY_TYPE, it.id) })
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
