package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.difficulty.Difficulty
import io.github.clemsix6.narrator2.tool.ToolText.number
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.enchantment.EnchantmentHelper

/**
 * How hard a champion is for its player (M7-BALANCE), whatever factors the armorer chose and whatever the difficulty layer
 * gives every hostile creature (×1 to ×10):
 * - **health**: at least [FLOOR_SECONDS] of blows with the best weapon the player carries (its damage, enchantments
 *   against this champion included, times its attack speed), and at most [CEILING_SECONDS] of them, [MAX_HEALTH] in all;
 * - **its own blow** (before its weapon and the layer's factor on damage): enough to take [MIN_BLOW] health through the
 *   player's armor, [MAX_BARE_DAMAGE] at most.
 *
 * The pure arithmetic is here, tested; [size] applies it to a champion as it stands, and [tierOf] reads a player's tier.
 */
object Threat {
    const val FLOOR_SECONDS = 20.0
    const val CEILING_SECONDS = 60.0
    const val MAX_HEALTH = 400.0

    /** Health a champion's bare blow takes from its player, armor counted. */
    const val MIN_BLOW = 3.0

    /** The most the floor makes of a champion's bare blow. */
    const val MAX_BARE_DAMAGE = 10.0

    /** A bow or a crossbow, about one full shot a second. */
    private const val RANGED_DPS = 6.0

    private val HEALTH_ID = Identifier.fromNamespaceAndPath("narrator2", "champion/threat_health")
    private val DAMAGE_ID = Identifier.fromNamespaceAndPath("narrator2", "champion/threat_damage")

    private val TOOLS = listOf(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SPEARS)
    private val ARMOR = listOf(ItemTags.HEAD_ARMOR, ItemTags.CHEST_ARMOR, ItemTags.LEG_ARMOR, ItemTags.FOOT_ARMOR)

    /** A champion's health: [current] brought within [dps] × [FLOOR_SECONDS] and [dps] × [CEILING_SECONDS], [MAX_HEALTH] at most. */
    fun health(current: Double, dps: Double): Double {
        val ceiling = minOf(MAX_HEALTH, dps * CEILING_SECONDS)
        return current.coerceIn(minOf(dps * FLOOR_SECONDS, ceiling), ceiling)
    }

    /** What is left of a blow of [damage] through [armor] and [toughness], as the game counts it (`CombatRules`, enchantments aside). */
    fun afterArmor(damage: Double, armor: Double, toughness: Double): Double {
        val absorbed = (armor - damage / (2.0 + toughness / 4.0)).coerceIn(armor * 0.2, 20.0)
        return damage * (1.0 - absorbed / 25.0)
    }

    /** The bare blow (by half points) that takes [MIN_BLOW] through [armor] and [toughness] once the layer's [factor] applies; [MAX_BARE_DAMAGE] at most. */
    fun bareDamage(armor: Double, toughness: Double, factor: Double): Double {
        var bare = 0.5
        while (bare < MAX_BARE_DAMAGE && afterArmor(bare * factor, armor, toughness) < MIN_BLOW) bare += 0.5
        return bare
    }

    /** [player]'s tier by the tools, weapons and armor they carry ([Tier.ofPlayer]). Server thread. */
    fun tierOf(player: ServerPlayer): Tier {
        val tools = mutableListOf<String>()
        val armor = mutableListOf<String>()
        for (stack in carried(player)) {
            when {
                TOOLS.any { stack.`is`(it) } -> tools += stack.typeHolder().registeredName
                ARMOR.any { stack.`is`(it) } -> armor += stack.typeHolder().registeredName
            }
        }
        return Tier.ofPlayer(tools, armor)
    }

    /**
     * Brings [mob], a champion just dressed, to what [player] calls for: its health ([health], kept full) and its bare blow
     * ([bareDamage]). Returns what it did, for the logs and the Director. Server thread.
     */
    fun size(mob: Mob, player: ServerPlayer): String {
        val (dps, weapon) = bestDps(player, mob)
        val health = mob.getAttribute(Attributes.MAX_HEALTH) ?: return "no health to size"
        val before = health.value
        val after = health(before, dps)
        if (after != before) health.addOrReplacePermanentModifier(AttributeModifier(HEALTH_ID, after / before - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        mob.health = mob.maxHealth
        val line = "health ${number(before)} → ${number(mob.maxHealth.toDouble())} against ${number(dps)} damage a second ($weapon)"
        val damage = mob.getAttribute(Attributes.ATTACK_DAMAGE)?.takeIf { it.baseValue > 0 } ?: return line
        val factor = Difficulty.config?.takeIf { it.enabled }?.mobDamage ?: 1.0
        val needed = bareDamage(player.armorValue.toDouble(), player.getAttributeValue(Attributes.ARMOR_TOUGHNESS), factor)
        val bare = damage.value
        if (bare < needed) {
            // Added before the champion's own factor (spawn's multiplier): its weapon is not multiplied by the floor.
            val multiplier = bare / damage.baseValue
            damage.addOrReplacePermanentModifier(AttributeModifier(DAMAGE_ID, needed / multiplier - damage.baseValue, AttributeModifier.Operation.ADD_VALUE))
        }
        return "$line; bare blow ${number(bare)} → ${number(damage.value)} against armor ${player.armorValue}"
    }

    /** The damage a second [player] deals to [target] with the best weapon they carry (fists at least), and that weapon. */
    private fun bestDps(player: ServerPlayer, target: Mob): Pair<Double, String> {
        val level = player.level()
        val source = level.damageSources().playerAttack(player)
        val baseDamage = player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
        val baseSpeed = player.getAttributeBaseValue(Attributes.ATTACK_SPEED)
        var best = baseDamage * baseSpeed to "fists"
        for (stack in carried(player)) {
            val dps = if (stack.`is`(Items.BOW) || stack.`is`(Items.CROSSBOW)) {
                RANGED_DPS
            } else {
                val modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                val damage = modifiers.compute(Attributes.ATTACK_DAMAGE, baseDamage, EquipmentSlot.MAINHAND)
                if (damage <= baseDamage) continue
                val speed = modifiers.compute(Attributes.ATTACK_SPEED, baseSpeed, EquipmentSlot.MAINHAND)
                EnchantmentHelper.modifyDamage(level, stack, target, source, damage.toFloat()) * speed
            }
            if (dps > best.first) best = dps to stack.typeHolder().registeredName.removePrefix("minecraft:")
        }
        return best
    }

    /** Every stack [player] carries: inventory, armor, offhand. */
    private fun carried(player: ServerPlayer): List<ItemStack> =
        (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.filterNot { it.isEmpty }
}
