package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText.number
import io.github.clemsix6.narrator2.tool.action.EnchantmentSpec
import io.github.clemsix6.narrator2.tool.action.GiftLimiter
import io.github.clemsix6.narrator2.tool.action.ItemSpec
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.speech.Speech
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.Filterable
import net.minecraft.tags.EnchantmentTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.component.Consumable
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.WrittenBookContent
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect
import net.minecraft.world.item.enchantment.Enchantment

/**
 * The armorer's drafts made into the game's items (`DIFFICULTY_API.md` §4.2, §4.3): each checked against [RelicRules]
 * first, then against the registries (`ItemSpecs.resolve`: the item, forbidden items, the texts, the enchantments' ids),
 * then against what makes a relic a relic (its base, enchantments that fit, no curse, the game's maximum level). A
 * refusal goes back to the model as `Invalid arguments: …` ([refuse]), for it to fix. Server thread (the enchantments
 * are a dynamic registry).
 *
 * Each item is marked `CUSTOM_DATA {narrator2: {kind: "relic" | "consumable" | "found_book" | "champion_gear", id}}`,
 * never `kind: "item"`: a relic found is never taken for a gift of the Narrator.
 */
object RelicItems {
    /**
     * The tags of the items a relic may be made from, besides [BASE_ITEMS]: the game's, and the conventional ones other mods
     * put their weapons, tools and armor in (M7-NOTES).
     */
    private val BASE_TAGS = listOf(
        ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SPEARS,
        ItemTags.HEAD_ARMOR, ItemTags.CHEST_ARMOR, ItemTags.LEG_ARMOR, ItemTags.FOOT_ARMOR,
        ConventionalItemTags.MELEE_WEAPON_TOOLS, ConventionalItemTags.RANGED_WEAPON_TOOLS, ConventionalItemTags.MINING_TOOL_TOOLS,
        ConventionalItemTags.HUMANOID_ARMORS,
    )
    private val BASE_ITEMS = setOf(Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.MACE)

    /** The relic of [draft], marked with its reserve [id] (0 for a relic forged for a champion's killer). */
    fun relic(server: MinecraftServer, draft: RelicDraft, id: Long): ItemStack {
        invalidIf(RelicRules.relic(draft))
        val spec = resolve(server, draft.item, draft.name, draft.lore, draft.enchantments, draft.unbreakable)
        val item = spec.item
        if (!isBase(item)) {
            refuse(ToolResult.invalid("${short(item)} is not a relic base: a weapon, a tool, a piece of armor, a shield, a bow, a crossbow, a trident or a mace"))
        }
        val stack = ItemSpecs.build(spec, null)
        enchantmentsFit(spec, stack)
        val group = slotGroup(stack)
        var modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
        for (change in draft.attributes) {
            val key = change.attribute
            val modifier = AttributeModifier(Identifier.fromNamespaceAndPath("narrator2", "relic/${group.serializedName}/${key.id}"), change.amount, key.operation)
            modifiers = modifiers.withModifierAdded(attribute(key.id), modifier, group)
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers)
        stack.set(DataComponents.RARITY, Rarity.EPIC)
        mark(stack, RELIC_KIND, id)
        return stack
    }

    /**
     * The strange consumable of [draft], marked with its reserve [id]: any food of the registry, other mods' included
     * (M7-NOTES), but a precious one ([ItemSpecs.preciousness]: golden apples).
     */
    fun consumable(server: MinecraftServer, draft: ConsumableDraft, id: Long): ItemStack {
        invalidIf(RelicRules.consumable(draft))
        val spec = resolve(server, draft.food, draft.name, draft.lore, emptyList(), false)
        if (ItemSpecs.preciousness(spec.item) > 0) refuse(ToolResult.invalid("${short(spec.item)} is too precious to become a consumable: take a plain food"))
        val stack = ItemSpecs.build(spec, null)
        if (!stack.has(DataComponents.FOOD)) refuse(ToolResult.invalid("${short(spec.item)} is not a food: a consumable is made from something to eat"))
        val base = stack.get(DataComponents.CONSUMABLE) ?: Consumable.builder().build()
        val effects = draft.effects.map { entry ->
            val instance = MobEffectInstance(effect(entry.effect.id), entry.seconds * TICKS_PER_SECOND, entry.level - 1)
            ApplyStatusEffectsConsumeEffect(instance, entry.chance.toFloat())
        }
        stack.set(DataComponents.CONSUMABLE, Consumable(base.consumeSeconds(), base.animation(), base.sound(), base.hasConsumeParticles(), effects))
        stack.set(DataComponents.RARITY, Rarity.UNCOMMON)
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        mark(stack, "consumable", id)
        return stack
    }

    /** The written book of [draft], laid out as the book screen shows it (`Speech.layOut`), marked with its reserve [id]. */
    fun book(draft: BookDraft, id: Long): ItemStack {
        invalidIf(RelicRules.book(draft))
        val pages = Speech.layOut(draft.pages)
        val content = WrittenBookContent(
            Filterable.passThrough(Speech.fit(Speech.clean(draft.title), WrittenBookContent.TITLE_MAX_LENGTH)),
            Speech.fit(Speech.clean(draft.author), AUTHOR_MAX),
            0,
            pages.map { Filterable.passThrough(Component.literal(it)) },
            true, // plain text: nothing to resolve when the book is opened
        )
        val stack = ItemStack(Items.WRITTEN_BOOK)
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content)
        mark(stack, "found_book", id)
        return stack
    }

    /**
     * One piece of a champion's gear for [slot]: an item that may be given (`ItemSpecs`), never netherite, armor in its own
     * slot, enchantments that fit it. Marked `champion_gear` when it is described (a name, enchantments). Its killer gets it:
     * nothing precious but a relic base (M7-FIX: no elytra, totem, enchanted golden apple, beacon…), which `give_item`
     * rations ([ItemSpecs.preciousness]).
     */
    fun gear(server: MinecraftServer, piece: GearPiece, slot: EquipmentSlot): ItemStack {
        val spec = resolve(server, piece.item, piece.name, null, piece.enchantments, false)
        if (ItemSpecs.preciousness(spec.item) >= GiftLimiter.PRECIOUS_POINTS_PER_HOUR && !isBase(spec.item)) {
            refuse(ToolResult.invalid("gear.${slot.serializedName}: ${short(spec.item)} is too precious to drop from a champion; give it a weapon, a tool, armor or a shield"))
        }
        val stack = ItemSpecs.build(spec, null)
        val worn = stack.get(DataComponents.EQUIPPABLE)?.slot()
        if (slot in ARMOR_SLOTS && worn != slot) {
            refuse(ToolResult.invalid("gear.${slot.serializedName}: ${short(spec.item)} is not worn there${worn?.let { " (it goes to ${it.serializedName})" }.orEmpty()}"))
        }
        enchantmentsFit(spec, stack)
        if (spec.described) mark(stack, "champion_gear", 0)
        return stack
    }

    /** `diamond_sword « Name » (sharpness 5; attack_damage +2, max_health -2; unbreakable)`, for the model and the logs. */
    fun describe(draft: RelicDraft): String {
        val parts = listOfNotNull(
            draft.enchantments.joinToString { "${bare(it.id)} ${it.level}" }.ifEmpty { null },
            draft.attributes.joinToString { "${it.attribute.id} ${signed(it.amount)}" }.ifEmpty { null },
            if (draft.unbreakable) "unbreakable" else null,
        )
        return "${bare(draft.item)} « ${Speech.clean(draft.name)} »" + if (parts.isEmpty()) "" else " (${parts.joinToString("; ")})"
    }

    /** `apple « Name » (speed II 30s, nausea I 10s 50%)`. */
    fun describe(draft: ConsumableDraft): String =
        "${bare(draft.food)} « ${Speech.clean(draft.name)} » (" + draft.effects.joinToString { e ->
            "${e.effect.id} ${roman(e.level)} ${e.seconds}s" + if (e.chance < 1.0) " ${(e.chance * 100).toInt()}%" else ""
        } + ")"

    /** `« Title » by Author, 3 pages`. */
    fun describe(draft: BookDraft): String =
        "« ${Speech.clean(draft.title)} » by ${Speech.clean(draft.author)}, ${draft.pages.size} ${if (draft.pages.size == 1) "page" else "pages"}"

    /** `zombie « Name » (health x3, damage x1.5, speed x1.2; gear: diamond_sword « … », iron_helmet; effects: strength I)`. */
    fun describe(draft: ChampionDraft): String {
        val factors = "health x${number(draft.health)}, damage x${number(draft.damage)}, speed x${number(draft.speed)}"
        val gear = RelicRules.pieces(draft.gear).joinToString { (_, piece) -> bare(piece.item) + (piece.name?.let { " « ${Speech.clean(it)} »" } ?: "") }
        val effects = draft.effects.joinToString { "${it.effect.id} ${roman(it.level)}" }
        return "${draft.mob.id} « ${Speech.clean(draft.name)} » ($factors" +
            (if (gear.isEmpty()) "" else "; gear: $gear") + (if (effects.isEmpty()) "" else "; effects: $effects") + ")"
    }

    /** The attribute called `minecraft:<path>`. */
    fun attribute(path: String): Holder<Attribute> =
        BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(path)).orElseThrow { IllegalStateException("no attribute minecraft:$path") }

    /** The effect called `minecraft:<path>`. */
    fun effect(path: String): Holder<MobEffect> =
        BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace(path)).orElseThrow { IllegalStateException("no effect minecraft:$path") }

    /** Whether [item] may be a relic: a weapon, a tool, a piece of armor, a shield, a bow, a crossbow, a trident or a mace. */
    private fun isBase(item: Holder<Item>): Boolean = BASE_TAGS.any { item.`is`(it) } || item.value() in BASE_ITEMS

    /** Refuses with every problem of a draft, joined, when there is any. */
    private fun invalidIf(problems: List<String>) {
        if (problems.isNotEmpty()) refuse(ToolResult.invalid(problems.joinToString("; ")))
    }

    /**
     * [ItemSpecs.resolve] for one item, of any mod, then no netherite, another mod's included: a relic or a champion's gear
     * never skips a whole stage of the game.
     */
    private fun resolve(server: MinecraftServer, raw: String, name: String?, lore: List<String>?, enchantments: List<EnchantmentSpec>, unbreakable: Boolean): ItemSpec {
        val spec = ItemSpecs.resolve(server.registryAccess(), server.worldData.enabledFeatures(), raw, 1, name, lore, enchantments, unbreakable)
        if (short(spec.item).substringAfter(':').startsWith("netherite_")) refuse(ToolResult.invalid("${short(spec.item)}: never netherite, which would skip a whole stage of the game; take diamond or less"))
        return spec
    }

    /** The enchantments of [spec] fit [stack]: no curse, the game's own maximum level at most, compatible with each other. */
    private fun enchantmentsFit(spec: ItemSpec, stack: ItemStack) {
        for ((enchantment, level) in spec.enchantments) {
            val id = short(enchantment)
            if (enchantment.`is`(EnchantmentTags.CURSE)) refuse(ToolResult.invalid("$id is a curse: never on a relic nor a champion's gear"))
            val max = enchantment.value().maxLevel
            if (level > max) refuse(ToolResult.invalid("$id goes up to level $max (asked $level)"))
            if (!enchantment.value().canEnchant(stack)) refuse(ToolResult.invalid("$id does not fit ${short(spec.item)}"))
        }
        val holders: List<Holder<Enchantment>> = spec.enchantments.map { it.first }
        for (i in holders.indices) {
            for (j in i + 1 until holders.size) {
                if (!Enchantment.areCompatible(holders[i], holders[j])) {
                    refuse(ToolResult.invalid("${short(holders[i])} and ${short(holders[j])} do not go together on one item"))
                }
            }
        }
    }

    /** Where a relic's attribute changes apply: the slot an armor piece is worn in, a shield in either hand, else the main hand. */
    private fun slotGroup(stack: ItemStack): EquipmentSlotGroup {
        val worn = stack.get(DataComponents.EQUIPPABLE)?.slot()
        return when {
            worn != null && worn in ARMOR_SLOTS -> EquipmentSlotGroup.bySlot(worn)
            stack.`is`(Items.SHIELD) -> EquipmentSlotGroup.HAND
            else -> EquipmentSlotGroup.MAINHAND
        }
    }

    /** The mark of a relic: `{narrator2: {kind: "relic"}}`. */
    const val RELIC_KIND = "relic"

    /** Whether [stack] is one of the Narrator's relics, marked [RELIC_KIND]: what `/narrator relics revoke` takes back. */
    fun isRelic(stack: ItemStack): Boolean =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty(Speech.CUSTOM_DATA_KEY)?.getStringOr("kind", "") == RELIC_KIND

    /** Marks [stack] `{narrator2: {kind, id}}`, replacing the mark `ItemSpecs.build` gave a described item. */
    private fun mark(stack: ItemStack, kind: String, id: Long) {
        val mark = CompoundTag()
        mark.putString("kind", kind)
        if (id > 0) mark.putLong("id", id)
        val data = CompoundTag()
        data.put(Speech.CUSTOM_DATA_KEY, mark)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
    }

    private fun short(holder: Holder<*>): String = holder.registeredName.removePrefix("minecraft:")

    private fun bare(id: String): String = id.trim().lowercase().removePrefix("minecraft:")

    private fun signed(value: Double): String = if (value > 0) "+${number(value)}" else number(value)

    private fun roman(level: Int): String = if (level == 2) "II" else "I"

    private const val TICKS_PER_SECOND = 20

    /** The slots a player's armor is worn in. */
    private val ARMOR_SLOTS = setOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /** Longest author of a book: the codec has no limit of its own; a player's name is 16. */
    private const val AUTHOR_MAX = 32
}
