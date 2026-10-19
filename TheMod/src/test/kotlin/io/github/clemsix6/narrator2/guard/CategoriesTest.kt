package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.guard.PunishmentCategory.AIR
import io.github.clemsix6.narrator2.guard.PunishmentCategory.COMBAT
import io.github.clemsix6.narrator2.guard.PunishmentCategory.CREATURES
import io.github.clemsix6.narrator2.guard.PunishmentCategory.FOOD
import io.github.clemsix6.narrator2.guard.PunishmentCategory.GRAVITY
import io.github.clemsix6.narrator2.guard.PunishmentCategory.HARM
import io.github.clemsix6.narrator2.guard.PunishmentCategory.INVENTORY
import io.github.clemsix6.narrator2.guard.PunishmentCategory.LIGHTNING
import io.github.clemsix6.narrator2.guard.PunishmentCategory.MINING
import io.github.clemsix6.narrator2.guard.PunishmentCategory.MOVEMENT
import io.github.clemsix6.narrator2.guard.PunishmentCategory.OTHER
import io.github.clemsix6.narrator2.guard.PunishmentCategory.SENSES
import io.github.clemsix6.narrator2.guard.PunishmentCategory.SIZE
import io.github.clemsix6.narrator2.guard.PunishmentCategory.TELEPORT
import io.github.clemsix6.narrator2.guard.PunishmentCategory.TRAP
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The categories of punishment (M4 §6.1): every attribute, harmful effect and punishment of the catalogue has its own. */
class CategoriesTest {
    @Test
    fun `every attribute the Narrator may change has the category of the table`() {
        val expected = mapOf(
            AttributeKey.SCALE to SIZE,
            AttributeKey.GRAVITY to GRAVITY, AttributeKey.JUMP_STRENGTH to GRAVITY, AttributeKey.SAFE_FALL_DISTANCE to GRAVITY,
            AttributeKey.FALL_DAMAGE_MULTIPLIER to GRAVITY, AttributeKey.BOUNCINESS to GRAVITY,
            AttributeKey.MOVEMENT_SPEED to MOVEMENT, AttributeKey.SNEAKING_SPEED to MOVEMENT, AttributeKey.STEP_HEIGHT to MOVEMENT,
            AttributeKey.FRICTION_MODIFIER to MOVEMENT, AttributeKey.AIR_DRAG_MODIFIER to MOVEMENT,
            AttributeKey.BLOCK_BREAK_SPEED to MINING, AttributeKey.MINING_EFFICIENCY to MINING, AttributeKey.BLOCK_INTERACTION_RANGE to MINING,
            AttributeKey.ATTACK_SPEED to COMBAT, AttributeKey.ENTITY_INTERACTION_RANGE to COMBAT, AttributeKey.KNOCKBACK_RESISTANCE to COMBAT,
            AttributeKey.MAX_HEALTH to COMBAT,
            AttributeKey.OXYGEN_BONUS to HARM, AttributeKey.BURNING_TIME to HARM,
            AttributeKey.LUCK to OTHER,
        )
        assertEquals(AttributeKey.entries.toSet(), expected.keys, "every attribute is in the table")
        expected.forEach { (key, category) -> assertEquals(category, PunishmentCategory.ofAttribute(key), key.id) }
    }

    @Test
    fun `every harmful effect the Narrator may give has the category of the table, with or without its namespace`() {
        val expected = mapOf(
            "slowness" to MOVEMENT, "mining_fatigue" to MINING, "weakness" to COMBAT,
            "blindness" to SENSES, "darkness" to SENSES, "nausea" to SENSES, "glowing" to SENSES,
            "hunger" to FOOD, "poison" to HARM, "wither" to HARM, "instant_damage" to HARM, "levitation" to AIR, "unluck" to OTHER,
        )
        expected.forEach { (effect, category) ->
            assertEquals(category, PunishmentCategory.ofEffect(effect), effect)
            assertEquals(category, PunishmentCategory.ofEffect("minecraft:$effect"), effect)
        }
        assertEquals(HARM, PunishmentCategory.ofEffect("othermod:curse"), "any other harmful effect")
    }

    @Test
    fun `every punishment of the catalogue has the category of its table`() {
        val expected = mapOf(
            PunishmentId.TINY to SIZE, PunishmentId.MOON_GRAVITY to GRAVITY, PunishmentId.SLOW_HANDS to MINING, PunishmentId.HEAVY_FEET to MOVEMENT,
            PunishmentId.HUNGER to FOOD, PunishmentId.BUTTERFINGERS to INVENTORY, PunishmentId.GUESTS to CREATURES,
            PunishmentId.SLIME_RAIN to CREATURES, PunishmentId.SKYWARD to AIR, PunishmentId.TREE_TOP to TELEPORT, PunishmentId.SMITE to LIGHTNING,
            PunishmentId.CAGE to TRAP, PunishmentId.WEB to TRAP,
        )
        assertEquals(PunishmentId.entries.toSet(), expected.keys)
        expected.forEach { (punishment, category) -> assertEquals(category, PunishmentCategory.of(punishment), punishment.id) }
    }

    @Test
    fun `every category but other rests, and reads back from its id`() {
        PunishmentCategory.entries.forEach {
            assertEquals(it, PunishmentCategory.ofId(it.id))
            assertEquals(it != OTHER, it.rests)
            assertTrue(it.phrase.isNotBlank() && it.phrase == it.phrase.lowercase(), it.id)
        }
        assertFalse(OTHER.rests)
        assertEquals("size", SIZE.id)
        assertEquals(null, PunishmentCategory.ofId("Size"))
    }
}
