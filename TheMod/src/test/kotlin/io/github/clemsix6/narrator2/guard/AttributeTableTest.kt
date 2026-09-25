package io.github.clemsix6.narrator2.guard

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attributes the Narrator may change (M1_M2 §10.5): bounds and hostile direction. That the bounds
 * lie within the game's own range is checked in game (`GuardPolicyGameTests`).
 */
class AttributeTableTest {
    @Test
    fun `the 21 attributes of the design with their bounds`() {
        val table = AttributeKey.entries.associate { it.id to (it.min to it.max) }
        assertEquals(
            mapOf(
                "scale" to (0.25 to 3.0), "gravity" to (0.01 to 0.2), "jump_strength" to (0.0 to 1.5),
                "movement_speed" to (0.02 to 0.3), "block_break_speed" to (0.1 to 4.0), "safe_fall_distance" to (0.0 to 30.0),
                "step_height" to (0.0 to 3.0), "block_interaction_range" to (2.0 to 10.0), "entity_interaction_range" to (1.0 to 8.0),
                "mining_efficiency" to (0.0 to 20.0), "luck" to (-5.0 to 5.0), "max_health" to (4.0 to 60.0),
                "attack_speed" to (1.0 to 16.0), "knockback_resistance" to (-1.0 to 1.0), "oxygen_bonus" to (0.0 to 10.0),
                "burning_time" to (0.0 to 4.0), "fall_damage_multiplier" to (0.0 to 3.0), "bounciness" to (0.0 to 1.0),
                "friction_modifier" to (0.2 to 4.0), "air_drag_modifier" to (0.2 to 4.0), "sneaking_speed" to (0.1 to 1.0),
            ),
            table,
        )
        AttributeKey.entries.forEach { assertTrue(it.min < it.max, "${it.id}: min below max") }
    }

    @Test
    fun `the beneficial direction of each attribute`() {
        val never = AttributeKey.entries.filter { it.beneficial == BeneficialWhen.NEVER }.map { it.id }
        val lower = AttributeKey.entries.filter { it.beneficial == BeneficialWhen.LOWER }.map { it.id }
        assertEquals(listOf("scale", "gravity", "bounciness", "friction_modifier", "air_drag_modifier"), never)
        assertEquals(listOf("burning_time", "fall_damage_multiplier"), lower)
        assertEquals(21 - never.size - lower.size, AttributeKey.entries.count { it.beneficial == BeneficialWhen.HIGHER })
    }

    @Test
    fun `a change is hostile when it goes the wrong way`() {
        assertTrue(AttributeKey.SCALE.harmful(1.0, 2.0), "scale: a giant is still a punishment")
        assertTrue(AttributeKey.SCALE.harmful(1.0, 0.5), "scale: so is a dwarf")
        assertFalse(AttributeKey.MOVEMENT_SPEED.harmful(0.1, 0.15), "faster is a gift")
        assertTrue(AttributeKey.MOVEMENT_SPEED.harmful(0.1, 0.05), "slower is a punishment")
        assertFalse(AttributeKey.MAX_HEALTH.harmful(20.0, 20.0), "no change is no punishment")
        assertFalse(AttributeKey.FALL_DAMAGE_MULTIPLIER.harmful(1.0, 0.0), "no fall damage is a gift")
        assertTrue(AttributeKey.BURNING_TIME.harmful(1.0, 3.0), "burning longer is a punishment")
        assertTrue(AttributeKey.KNOCKBACK_RESISTANCE.harmful(0.0, -1.0), "amplified knockback is a punishment")
    }

    @Test
    fun `a value out of the bounds is refused with the bounds, never clipped`() {
        assertNull(AttributeKey.SCALE.outOfBounds(0.25), "the lower bound is allowed")
        assertNull(AttributeKey.SCALE.outOfBounds(3.0), "the upper bound is allowed")
        assertEquals("scale must stay between 0.25 and 3 (would be 5)", AttributeKey.SCALE.outOfBounds(5.0))
        assertEquals("gravity must stay between 0.01 and 0.2 (would be 0.001)", AttributeKey.GRAVITY.outOfBounds(0.001))
        assertEquals("luck must stay between -5 and 5 (would be -6)", AttributeKey.LUCK.outOfBounds(-6.0))
        assertFalse(AttributeKey.MAX_HEALTH.allows(3.9))
        assertTrue(AttributeKey.MAX_HEALTH.allows(60.0))
    }

    @Test
    fun `the tool names are the registry paths`() {
        AttributeKey.entries.forEach {
            assertEquals("\"${it.id}\"", Json.encodeToString(AttributeKey.serializer(), it), "serial name of ${it.name}")
            assertEquals(it, AttributeKey.of(it.id))
            assertTrue(it.id.matches(Regex("[a-z_]+")), it.id)
        }
        assertNull(AttributeKey.of("flying_speed"), "not on a player")
        assertNull(AttributeKey.of("armor"), "not in the Narrator's arsenal")
    }
}
