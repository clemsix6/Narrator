package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.shownName
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.roundToInt

/**
 * The sections of `inspect_player` read from the world, as text: [header], [position], [surroundings],
 * [equipment], [inventory], [effects]. Each reads the live player and loaded chunks. Server thread
 * only. (The Journal's section, `recent`, is read off the server thread by the tool.)
 */
class PlayerReport(private val player: ServerPlayer) {
    private val reader = WorldReader(player.level())
    private val feet = player.blockPosition()
    private val head = BlockPos.containing(player.eyePosition)

    /** `Bartok · survival · hp 14/20 · food 12 (saturation 3.2) · xp level 23 · armor 15 · online 1h12`. */
    fun header(onlineMs: Long?, idle: Boolean): String = buildList {
        add(player.shownName)
        add(player.gameMode().serializedName)
        if (player.isDeadOrDying) {
            add("dead")
        } else {
            val absorption = player.absorptionAmount.takeIf { it > 0f }?.let { " (+${OracleText.number(it)} absorption)" }.orEmpty()
            add("hp ${OracleText.number(player.health)}/${OracleText.number(player.maxHealth)}$absorption")
            add("food ${player.foodData.foodLevel} (saturation ${OracleText.number(player.foodData.saturationLevel)})")
        }
        add("xp level ${player.experienceLevel}")
        add("armor ${player.armorValue}")
        onlineMs?.let { add("online ${ToolText.duration(it)}") }
        if (idle) add("idle")
    }.joinToString(" · ")

    /**
     * `position: overworld -120 -58 330 · dripstone_caves · cave, 41 below surface · light sky 0, block 7 ·
     * in mineshaft`, then what the player faces and looks at.
     */
    fun position(): List<String> {
        val snapshot = PlayerSnapshot.capture(player, reader)
        val where = buildList {
            add("${ToolText.dimension(snapshot.dimension)} ${OracleText.coords(snapshot.x, snapshot.y, snapshot.z)}")
            add("biome ${OracleText.bare(snapshot.biome)}")
            add(snapshot.shelter())
            snapshot.depth?.let { add(if (it > 0) "surface y=${snapshot.y + it}" else "at the surface") }
            add("light sky ${reader.skyLight(head)}, block ${reader.blockLight(head)}")
            snapshot.structure?.let { add("in ${OracleText.bare(it)}") }
            snapshot.vehicle?.let { add("riding ${OracleText.bare(it)}") }
            if (player.isUnderWater) add("underwater")
        }
        val pitch = when {
            player.xRot > LOOK_STEEP -> ", looking down"
            player.xRot < -LOOK_STEEP -> ", looking up"
            else -> ""
        }
        val target = reader.lookingAt(player, LOOK_DISTANCE)?.let { hit ->
            val pos = hit.blockPos
            val block = OracleText.bare(reader.getBlockState(pos).block.id)
            "$block ${OracleText.relative(pos.x - feet.x, pos.y - feet.y, pos.z - feet.z)} (${OracleText.coords(pos.x, pos.y, pos.z)})"
        } ?: "nothing within ${LOOK_DISTANCE.toInt()} blocks"
        return listOf("position: " + where.joinToString(" · "), "facing ${compass(player.direction)}$pitch; looking at $target")
    }

    /** Creatures and players around, the structures of the loaded chunks nearby, and what threatens the player. */
    fun surroundings(): List<String> {
        val groups = AreaScan.entities(reader, player.position(), NEARBY_RADIUS, player)
        val players = groups.filter { it.type == "minecraft:player" }
        val mobs = groups.filter { it.type != "minecraft:player" }
        val mobText = if (mobs.isEmpty()) "no creatures" else mobs.take(MAX_GROUPS).joinToString(", ") { group ->
            val where = OracleText.relative((group.nearest.x - player.x).roundToInt(), (group.nearest.y - player.y).roundToInt(), (group.nearest.z - player.z).roundToInt())
            val named = group.names.takeIf { it.isNotEmpty() }?.let { " named ${it.take(3).joinToString { name -> OracleText.quote(name, 24) }}" }.orEmpty()
            (if (group.count > 1) "${group.count} ${OracleText.bare(group.type)} (nearest $where)" else "${OracleText.bare(group.type)} $where") + named
        } + if (mobs.size > MAX_GROUPS) ", and ${mobs.size - MAX_GROUPS} more kinds" else ""
        val playerText = players.flatMap { it.names }.let { names ->
            if (names.isEmpty()) "" else "; players: " + nearbyPlayers(names).joinToString()
        }
        val structures = Structures.nearby(reader, feet, STRUCTURE_CHUNKS).take(MAX_GROUPS)
        val structureText = if (structures.isEmpty()) "none" else structures.joinToString("; ") { sighting ->
            val at = sighting.at
            val where = if (sighting.distance == 0) "here" else OracleText.relative(at.x - feet.x, at.y - feet.y, at.z - feet.z)
            OracleText.bare(sighting.id) + " " + (if (sighting.exact) where else "~$where")
        }
        return listOf(
            "around (${NEARBY_RADIUS.toInt()} blocks): $mobText$playerText",
            "structures nearby (loaded chunks, ${STRUCTURE_CHUNKS * 16} blocks): $structureText",
            "danger: " + danger(mobs).ifEmpty { listOf("none") }.joinToString("; "),
        )
    }

    private fun nearbyPlayers(names: List<String>): List<String> = player.level().players()
        .filter { it !== player && it.plainTextName in names }
        .sortedBy { it.distanceToSqr(player) }
        .map { other ->
            val offset = other.blockPosition().subtract(feet)
            "${other.shownName} ${OracleText.relative(offset.x, offset.y, offset.z)}"
        }

    private fun danger(mobs: List<EntityGroup>): List<String> = buildList {
        if (!player.isDeadOrDying && player.health <= LOW_HEALTH) add("low health")
        if (player.isInLava) add("in lava") else if (player.isOnFire) add("burning")
        if (player.isUnderWater && player.airSupply < player.maxAirSupply / 3) add("drowning")
        if (player.fallDistance > FALL_WARNING) add("falling (${player.fallDistance.toInt()} blocks)")
        val lava = Ores.find(Ores.copy(reader, feet, LAVA_RADIUS, IS_LAVA), feet, LAVA_RADIUS, IS_LAVA, 1)
        lava.nearest.firstOrNull()?.let { hit ->
            val offset = hit.pos.subtract(feet)
            add("lava ${OracleText.relative(offset.x, offset.y, offset.z)}")
        }
        val hostiles = mobs.filter { it.category == MobCategory.MONSTER.serializedName }.sumOf { it.count }
        if (hostiles >= MANY_HOSTILES) add("$hostiles hostiles within ${NEARBY_RADIUS.toInt()} blocks")
    }

    /** `equipment: mainhand diamond_pickaxe (efficiency V; 71%) · offhand 32 torch · head iron_helmet (40%)`. */
    fun equipment(): String {
        val worn = SLOTS.mapNotNull { slot ->
            val stack = player.getItemBySlot(slot)
            if (stack.isEmpty) null else "${slot.serializedName} ${describe(stack)}"
        }
        return "equipment: " + worn.ifEmpty { listOf("nothing") }.joinToString(" · ")
    }

    /** `inventory (27 of 36 slots used): 128 cobblestone, 12 diamond, diamond_sword "Excalibur" (sharpness V)`. */
    fun inventory(): String {
        val stacks = player.inventory.nonEquipmentItems.filterNot { it.isEmpty }
        if (stacks.isEmpty()) return "inventory: empty"
        // Plain stacks are summed by item; named, enchanted or worn items are listed one by one.
        val (plain, special) = stacks.partition { it.customName == null && it.enchantments.isEmpty && !it.isDamaged }
        val summed = plain.groupBy { it.id }.map { (id, list) -> list.sumOf { it.count } to OracleText.bare(id) }
            .sortedByDescending { it.first }.map { "${it.first} ${it.second}" }
        val all = summed + special.map { describe(it) }
        val shown = all.take(MAX_ITEMS).joinToString() + if (all.size > MAX_ITEMS) ", and ${all.size - MAX_ITEMS} more" else ""
        return "inventory (${stacks.size} of ${player.inventory.nonEquipmentItems.size} slots used): $shown"
    }

    /**
     * `effects: speed II 1:30 · night_vision 7:40`, then the effects the Narrator put on the player
     * ([mine], from the guards' ledgers), which include attributes, confiscations and creatures.
     */
    fun effects(mine: List<ActiveEffect>, now: Long): List<String> {
        val active = player.activeEffects.sortedBy { it.effect.registeredName }.map { describe(it) }
        val yours = mine.map { effect ->
            val left = effect.until - now
            val end = if (left > 0) "ends in ${ToolText.duration(left)}" else "overdue"
            "${effect.what} (${effect.kind}, $end${if (effect.harmful) ", punishment" else ""})"
        }
        return listOf(
            "effects: " + active.ifEmpty { listOf("none") }.joinToString(" · "),
            "yours on them: " + yours.ifEmpty { listOf("none") }.joinToString(" · "),
        )
    }

    private fun describe(effect: MobEffectInstance): String {
        val level = if (effect.amplifier > 0) " ${ToolText.roman(effect.amplifier + 1)}" else ""
        val time = if (effect.isInfiniteDuration) "infinite" else (effect.duration / TICKS_PER_SECOND).let { "%d:%02d".format(it / 60, it % 60) }
        return "${OracleText.bare(effect.effect.registeredName)}$level $time"
    }

    private companion object {
        const val LOOK_DISTANCE = 20.0
        const val LOOK_STEEP = 45f
        const val NEARBY_RADIUS = 24.0
        const val STRUCTURE_CHUNKS = 6
        const val MAX_GROUPS = 10
        const val MAX_ITEMS = 30
        const val LOW_HEALTH = 6f
        const val FALL_WARNING = 10.0
        const val LAVA_RADIUS = 5
        const val MANY_HOSTILES = 3
        const val TICKS_PER_SECOND = 20
        val SLOTS = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        val IS_LAVA: (BlockState) -> Boolean = { it.fluidState.`is`(FluidTags.LAVA) }

        fun compass(direction: Direction): String = direction.serializedName.first().uppercase()

        /** `32 torch`, `diamond_pickaxe "Digger" (efficiency V, fortune III; 71%)`. */
        fun describe(stack: ItemStack): String {
            val count = if (stack.count > 1) "${stack.count} " else ""
            val name = stack.customName?.let { " " + OracleText.quote(it.string, 32) }.orEmpty()
            val enchantments = stack.enchantments.entrySet().map { "${OracleText.bare(it.key.registeredName)} ${ToolText.roman(it.intValue)}" }
            val wear = if (stack.isDamageableItem && stack.maxDamage > 0) listOf("${(stack.maxDamage - stack.damageValue) * 100 / stack.maxDamage}%") else emptyList()
            val details = listOf(enchantments.joinToString(), wear.joinToString()).filter { it.isNotEmpty() }
            return "$count${OracleText.bare(stack.id)}$name" + if (details.isEmpty()) "" else " (${details.joinToString("; ")})"
        }
    }
}
