package io.github.clemsix6.narrator2.guard

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.npc.InventoryCarrier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * What players entrusted to one of the Narrator's creatures, which must not disappear with it when it
 * expires (`discard()` drops nothing). The game itself marks what a player gives a mob: a saddle, a
 * harness, horse or wolf armor, a carpet put on by hand or by a dispenser, the gold a piglin admires,
 * all get a guaranteed drop (`DropChances.isPreserved`, read with `javap -c`: `Equippable.equipOnTarget`,
 * `AbstractHorse.equipBodyArmor`, `EquipmentDispenseItemBehavior`, `Piglin.holdInOffHand`). The rest can
 * only come from players too: a mount's chest and what it holds, the inventory of a piglin, a lead.
 * The slots the game already kept when the Narrator marked the creature ([keptSlots]: a drowned's
 * nautilus shell, a patrol leader's banner) are its own. Server thread.
 */
object Belongings {
    /** First slot of a mount's inventory in `Entity.getSlot` (`AbstractHorse.getSlot`, javap). */
    private const val MOUNT_INVENTORY_SLOT = 500

    /** The equipment slots whose items the game keeps for good on [entity] now: saved in `NarratorMob.ownSlots` when it is marked. */
    fun keptSlots(entity: Entity): List<String> {
        if (entity !is Mob) return emptyList()
        return EquipmentSlot.VALUES.filter { entity.dropChances.isPreserved(it) && !entity.getItemBySlot(it).isEmpty }.map { it.serializedName }
    }

    /** Takes from [entity] what players entrusted to it (never the gear of [ownSlots]), and returns it. */
    fun takeFrom(entity: Entity, ownSlots: Collection<String>): List<ItemStack> {
        val taken = mutableListOf<ItemStack>()
        if (entity is Mob) {
            for (slot in EquipmentSlot.VALUES) {
                val stack = entity.getItemBySlot(slot)
                if (stack.isEmpty || !entity.dropChances.isPreserved(slot) || slot.serializedName in ownSlots) continue
                taken += stack.copy()
                entity.setItemSlot(slot, ItemStack.EMPTY)
            }
        }
        if (entity is AbstractHorse) {
            for (index in 0 until entity.inventorySize) {
                val slot = entity.getSlot(MOUNT_INVENTORY_SLOT + index) ?: continue
                val stack = slot.get()
                if (stack.isEmpty) continue
                taken += stack.copy()
                slot.set(ItemStack.EMPTY)
            }
        }
        if (entity is AbstractChestedHorse && entity.hasChest()) {
            taken += ItemStack(Items.CHEST)
            entity.setChest(false)
        }
        if (entity is InventoryCarrier) taken += entity.inventory.removeAllItems()
        if (entity is Leashable && entity.isLeashed) {
            taken += ItemStack(Items.LEAD)
            entity.removeLeash()
        }
        return taken.filterNot { it.isEmpty }
    }

    /** Who entrusted things to [entity], when the game knows: the player who tamed it, else the one holding its lead. */
    fun ownerOf(entity: Entity): UUID? =
        (entity as? OwnableEntity)?.ownerReference?.uuid ?: ((entity as? Leashable)?.leashHolder as? Player)?.uuid
}
