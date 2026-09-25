package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.tool.speech.closestIds
import kotlinx.serialization.Serializable
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.item.GameMasterBlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.util.Unit as GameUnit

/** An enchantment of an item the model describes. */
@Serializable
data class EnchantmentSpec(
    @Doc("Enchantment id, e.g. sharpness, protection, efficiency, mending, fire_aspect.")
    @MaxLen(64)
    val id: String,
    @Doc("Level; at most one past the game's usual maximum (sharpness 6, protection 5).")
    @Range(1.0, 10.0)
    val level: Int = 1,
)

/**
 * An item as the model describes it, checked against the game's registries and ready to be built by
 * [ItemSpecs.build]; its texts are already cleaned.
 */
class ItemSpec(
    val item: Holder.Reference<Item>,
    val count: Int,
    val name: String?,
    val lore: List<String>,
    val enchantments: List<Pair<Holder.Reference<Enchantment>, Int>>,
    val unbreakable: Boolean,
) {
    /** Anything beyond the plain item: such a gift is marked as the Narrator's. */
    val described: Boolean get() = name != null || lore.isNotEmpty() || enchantments.isNotEmpty() || unbreakable
}

/**
 * Items described in JSON by the model (`give_item`, `take_item`), turned into the game's items and
 * 26.x data components (`ORACLE_ACTIONS_API.md` §4.8): a custom name that is not italic, lore lines,
 * enchantments (stored in an enchanted book, worn by anything else), unbreakable. Ids are checked in
 * the registries, and the errors give the closest ids.
 *
 * Some items are never given ([forbidden]): operator items, blocks no survival player can break, spawn
 * eggs and spawners, and the ominous bottle (its Bad Omen starts a raid, which the Narrator's effects
 * never do). An enchantment goes one level past the game's maximum at most, and precious items are
 * rationed per player and per hour ([preciousness]).
 */
object ItemSpecs {
    /** The rule of a refused item: `DENIED (forbidden_item): …`. */
    const val FORBIDDEN_ITEM = "forbidden_item"

    private const val OPERATOR = "it is an operator's item"
    private const val UNBREAKABLE_BLOCK = "no survival player could ever break it once placed"
    private const val SPAWNER = "it summons creatures without end"

    /** Items never given, with why (M1_M2 §9.3, completed). Spawn eggs and operator block items are also refused by their class. */
    private val FORBIDDEN = mapOf(
        "minecraft:command_block" to OPERATOR,
        "minecraft:chain_command_block" to OPERATOR,
        "minecraft:repeating_command_block" to OPERATOR,
        "minecraft:command_block_minecart" to OPERATOR,
        "minecraft:structure_block" to OPERATOR,
        "minecraft:structure_void" to OPERATOR,
        "minecraft:jigsaw" to OPERATOR,
        "minecraft:test_block" to OPERATOR,
        "minecraft:test_instance_block" to OPERATOR,
        "minecraft:barrier" to OPERATOR,
        "minecraft:light" to OPERATOR,
        "minecraft:debug_stick" to OPERATOR,
        "minecraft:knowledge_book" to OPERATOR,
        "minecraft:bedrock" to UNBREAKABLE_BLOCK,
        "minecraft:reinforced_deepslate" to UNBREAKABLE_BLOCK,
        "minecraft:end_portal_frame" to UNBREAKABLE_BLOCK,
        "minecraft:spawner" to SPAWNER,
        "minecraft:trial_spawner" to SPAWNER,
        "minecraft:vault" to "a vault hands out loot again and again",
        "minecraft:ominous_bottle" to "its Bad Omen starts a raid on a village",
    )

    /** The ids of the items refused by name (not those refused by their class). */
    val forbiddenIds: Set<String> get() = FORBIDDEN.keys

    /** Precious items worth one point each. */
    private val ONE_POINT = setOf("minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:golden_apple")

    /** Precious items worth a whole hour's points, besides the netherite and diamond ones and the shulker boxes. */
    private val RARE = setOf(
        "minecraft:ancient_debris", "minecraft:elytra", "minecraft:totem_of_undying", "minecraft:enchanted_golden_apple",
        "minecraft:beacon", "minecraft:nether_star", "minecraft:conduit", "minecraft:heart_of_the_sea", "minecraft:heavy_core",
        "minecraft:mace", "minecraft:trident", "minecraft:dragon_egg", "minecraft:shulker_shell",
    )

    /** Every item whose id starts so is worth a whole hour's points: netherite, and diamond gear and blocks. */
    private val RARE_PREFIXES = listOf("minecraft:netherite_", "minecraft:diamond_")

    /**
     * How precious one [item] is (M2 review, finding 13): the Narrator slows progression down, so a
     * player gets at most [GiftLimiter.PRECIOUS_POINTS_PER_HOUR] points of precious items an hour. A
     * diamond or a golden apple is one point; netherite, diamond gear and blocks, elytra, totems, enchanted
     * golden apples, beacons, shulker boxes and the like are the whole hour's; anything else is 0.
     */
    fun preciousness(item: Holder<Item>): Int {
        val id = item.registeredName
        return when {
            id in ONE_POINT -> 1
            id in RARE || RARE_PREFIXES.any { id.startsWith(it) } || item.`is`(ItemTags.SHULKER_BOXES) -> GiftLimiter.PRECIOUS_POINTS_PER_HOUR
            else -> 0
        }
    }

    /** The item called [raw] (`diamond_sword`, `minecraft:diamond_sword`); null when there is none. Any thread. */
    fun item(raw: String): Holder.Reference<Item>? =
        parseId(raw)?.let { BuiltInRegistries.ITEM.get(it).orElse(null) }?.takeUnless { it.value() === Items.AIR }

    /** The problem of an unknown item id, with the closest ids. */
    fun unknownItem(raw: String): String =
        "unknown item \"$raw\"; closest ids: ${closestIds(raw, BuiltInRegistries.ITEM.keySet().map(::shortId)).joinToString()}"

    /** The item tag called [raw] (`#minecraft:logs`, `#logs`), if it exists. Any thread. */
    fun itemTag(raw: String): TagKey<Item>? {
        val id = parseId(raw.trim().removePrefix("#")) ?: return null
        return TagKey.create(Registries.ITEM, id).takeIf { BuiltInRegistries.ITEM.get(it).isPresent }
    }

    /** The problem of an unknown item tag, with the closest tags. */
    fun unknownTag(raw: String): String {
        val tags = BuiltInRegistries.ITEM.getTags().map { "#" + shortId(it.key().location()) }.toList()
        return "unknown item tag \"$raw\"; closest tags: ${closestIds(raw.trim().removePrefix("#"), tags).joinToString()}"
    }

    /** Why [item] is never given, or null when it may be. */
    fun forbidden(item: Holder<Item>): String? = when {
        item.registeredName in FORBIDDEN -> FORBIDDEN.getValue(item.registeredName)
        item.value() is SpawnEggItem -> "a spawn egg summons creatures outside the spawn tool and its caps"
        item.value() is GameMasterBlockItem -> OPERATOR
        else -> null
    }

    /**
     * Checks what the model described and resolves it: the item (known, enabled in this world, not
     * [forbidden]), the count against its stack size, the texts once cleaned, the enchantments. Refuses
     * ([refuse]) with the answer to give otherwise. Server thread (enchantments are a dynamic registry).
     */
    fun resolve(
        registries: RegistryAccess,
        features: FeatureFlagSet,
        raw: String,
        count: Int,
        name: String?,
        lore: List<String>?,
        enchantments: List<EnchantmentSpec>?,
        unbreakable: Boolean,
    ): ItemSpec {
        val item = item(raw) ?: refuse(ToolResult.invalid(unknownItem(raw)))
        val id = shortId(item)
        forbidden(item)?.let { refuse(ToolResult.denied(FORBIDDEN_ITEM, "$id is never given: $it.")) }
        if (!item.value().isEnabled(features)) refuse(ToolResult.invalid("$id is not enabled in this world"))
        val stackSize = item.value().defaultMaxStackSize
        if (count > stackSize) refuse(ToolResult.invalid("$id stacks to $stackSize: count must be at most $stackSize (got $count)"))
        val cleanName = name?.let { Speech.clean(it) }
        if (cleanName != null && cleanName.isEmpty()) {
            refuse(ToolResult.invalid("name has nothing to show once formatting codes and invisible characters are removed"))
        }
        val registry = registries.lookupOrThrow(Registries.ENCHANTMENT)
        val resolved = enchantments.orEmpty().map { spec ->
            val enchantment = parseId(spec.id)?.let { registry.get(it).orElse(null) }
                ?: refuse(
                    ToolResult.invalid(
                        "unknown enchantment \"${spec.id}\"; closest ids: ${closestIds(spec.id, registry.keySet().map(::shortId)).joinToString()}",
                    ),
                )
            // One level past the game's own maximum at most (M2 review, finding 13): a gag, never a weapon.
            val cap = enchantment.value().maxLevel + 1
            if (spec.level > cap) {
                refuse(ToolResult.invalid("${shortId(enchantment)} goes up to level $cap, one past the game's own maximum (asked ${spec.level})"))
            }
            enchantment to spec.level
        }
        if (resolved.map { it.first }.distinct().size < resolved.size) refuse(ToolResult.invalid("an enchantment is listed twice"))
        val lines = lore.orEmpty().map { Speech.clean(it) }.filter { it.isNotEmpty() }
        return ItemSpec(item, count, cleanName, lines, resolved, unbreakable)
    }

    /**
     * The stack of [spec]: its name (not italic), lore, enchantments and unbreakable components. A
     * described item is marked `CUSTOM_DATA {narrator2: {kind: "item", turn: <turnId>}}`; a plain one is
     * left plain, so that it stacks with the player's own.
     */
    fun build(spec: ItemSpec, turnId: Long?): ItemStack {
        val stack = ItemStack(spec.item, spec.count)
        spec.name?.let { stack.set(DataComponents.CUSTOM_NAME, Component.literal(it).withStyle { style -> style.withItalic(false) }) }
        if (spec.lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(spec.lore.map { Component.literal(it) }))
        if (spec.enchantments.isNotEmpty()) {
            val enchantments = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
            spec.enchantments.forEach { (enchantment, level) -> enchantments.set(enchantment, level) }
            // An enchanted book stores its enchantments for the anvil; anything else wears them.
            val component = if (stack.`is`(Items.ENCHANTED_BOOK)) DataComponents.STORED_ENCHANTMENTS else DataComponents.ENCHANTMENTS
            stack.set(component, enchantments.toImmutable())
        }
        if (spec.unbreakable) stack.set(DataComponents.UNBREAKABLE, GameUnit.INSTANCE)
        if (spec.described) {
            val mark = CompoundTag()
            mark.putString("kind", "item")
            turnId?.let { mark.putLong("turn", it) }
            val data = CompoundTag()
            data.put(Speech.CUSTOM_DATA_KEY, mark)
            CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
        }
        return stack
    }

    /** `1x diamond_sword "Excalibur" (sharpness 5, unbreakable)`, for the model. */
    fun describe(spec: ItemSpec): String {
        val extras = spec.enchantments.map { (enchantment, level) -> "${shortId(enchantment)} $level" } +
            listOfNotNull(
                when (spec.lore.size) {
                    0 -> null
                    1 -> "1 line of lore"
                    else -> "${spec.lore.size} lines of lore"
                },
                if (spec.unbreakable) "unbreakable" else null,
            )
        return "${spec.count}x ${shortId(spec.item)}" +
            (spec.name?.let { " \"$it\"" } ?: "") +
            (if (extras.isEmpty()) "" else " (${extras.joinToString()})")
    }
}

/** [raw] as an id: trimmed, lower case, spaces as underscores; the `minecraft:` namespace is optional. */
internal fun parseId(raw: String): Identifier? = Identifier.tryParse(raw.trim().lowercase().replace(' ', '_'))

/** `diamond` for `minecraft:diamond`; other namespaces are kept. */
internal fun shortId(id: Identifier): String = if (id.namespace == Identifier.DEFAULT_NAMESPACE) id.path else id.toString()

/** The short id of what [holder] holds: `slowness`, `diamond_sword`. */
internal fun shortId(holder: Holder<*>): String = holder.registeredName.removePrefix("${Identifier.DEFAULT_NAMESPACE}:")
