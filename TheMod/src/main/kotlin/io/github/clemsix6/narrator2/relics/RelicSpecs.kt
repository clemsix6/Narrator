package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolText.number
import io.github.clemsix6.narrator2.tool.action.EnchantmentSpec
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation

/*
 * What the armorer designs (champions, relics, strange consumables, books), as the model writes it: the arguments of its
 * tools, stored as they are in the reserves (`relic_stock.spec`). The pure rules that keep a relic a good item live here
 * ([RelicRules]): whitelists and caps that need no registry. What needs the game's registries (the base item, the
 * enchantments that fit it) is checked when the item is built (`RelicItems`), on the server thread.
 */

/** What fills the chests' reserves. */
enum class StockKind(val id: String) {
    RELIC("relic"),
    CONSUMABLE("consumable"),
    BOOK("book"),
    ;

    companion object {
        fun of(id: String): StockKind? = entries.firstOrNull { it.id == id }
    }
}

// ── Relics ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * An attribute a relic may change while held or worn, with its operation and its caps: a bonus up to [bonusMax], a
 * malus down to [malusMin] (0: never a malus). Nothing touches how a player moves through the world beyond a light
 * slowdown: no `scale`, no `gravity`, no jump, no step height (a door must stay a door).
 */
@Serializable
enum class RelicAttributeKey(val id: String, val operation: Operation, val bonusMax: Double, val malusMin: Double) {
    @SerialName("attack_damage") ATTACK_DAMAGE("attack_damage", Operation.ADD_VALUE, 4.0, -1.0),
    @SerialName("attack_speed") ATTACK_SPEED("attack_speed", Operation.ADD_MULTIPLIED_BASE, 0.3, -0.1),
    @SerialName("max_health") MAX_HEALTH("max_health", Operation.ADD_VALUE, 6.0, -2.0),
    @SerialName("armor") ARMOR("armor", Operation.ADD_VALUE, 4.0, -2.0),
    @SerialName("armor_toughness") ARMOR_TOUGHNESS("armor_toughness", Operation.ADD_VALUE, 3.0, 0.0),
    @SerialName("knockback_resistance") KNOCKBACK_RESISTANCE("knockback_resistance", Operation.ADD_VALUE, 0.5, 0.0),
    @SerialName("movement_speed") MOVEMENT_SPEED("movement_speed", Operation.ADD_MULTIPLIED_BASE, 0.2, -0.1),
    @SerialName("luck") LUCK("luck", Operation.ADD_VALUE, 3.0, -2.0),
    @SerialName("block_break_speed") BLOCK_BREAK_SPEED("block_break_speed", Operation.ADD_MULTIPLIED_BASE, 0.5, -0.1),
    @SerialName("attack_knockback") ATTACK_KNOCKBACK("attack_knockback", Operation.ADD_VALUE, 1.5, 0.0),
    @SerialName("entity_interaction_range") ENTITY_INTERACTION_RANGE("entity_interaction_range", Operation.ADD_VALUE, 1.5, -0.5),
    @SerialName("block_interaction_range") BLOCK_INTERACTION_RANGE("block_interaction_range", Operation.ADD_VALUE, 2.0, -0.5),
    @SerialName("oxygen_bonus") OXYGEN_BONUS("oxygen_bonus", Operation.ADD_VALUE, 3.0, 0.0),
}

@Serializable
data class RelicAttribute(
    val attribute: RelicAttributeKey,
    @Doc(
        "Positive for a bonus, negative for the one light malus allowed. Points for attack_damage (up to 4), max_health " +
            "(up to 6, 2 points a heart), armor (4), armor_toughness (3), luck (3), attack_knockback (1.5), oxygen_bonus (3), " +
            "knockback_resistance (0.5 = half), the two interaction ranges in blocks (1.5 and 2); a fraction for attack_speed " +
            "(0.3), movement_speed (0.2) and block_break_speed (0.5): 0.1 is +10 %. A malus goes down to: max_health -2 " +
            "(one heart), armor -2, luck -2, attack_damage -1, attack_speed, movement_speed and block_break_speed -0.1, " +
            "the ranges -0.5; the others are never a malus.",
    )
    @Range(-2.0, 6.0)
    val amount: Double,
)

/** A relic: a weapon, a tool, a piece of armor, a shield, a bow, a crossbow, a trident or a mace, that stays good. */
@Serializable
data class RelicDraft(
    @Doc(
        "The base item: a weapon or a tool (swords, axes, pickaxes, shovels, hoes, spears, mace, trident, bow, crossbow), " +
            "a piece of armor or a shield; never netherite. E.g. iron_sword, diamond_pickaxe, golden_helmet, shield; another " +
            "mod's weapon, tool or armor by its full id (namespace:path).",
    )
    @MaxLen(64)
    val item: String,
    @Doc("Its name, in French: funny, tied to who it is for and to the story.") @MaxLen(NAME_MAX) val name: String,
    @Doc("Its lore, in French: one to four short lines, sarcastic, saying where it comes from.")
    @Items(1, LORE_LINES)
    @MaxLen(LORE_LINE_MAX)
    val lore: List<String>,
    @Doc("Enchantments that fit the item, up to the game's usual maximum level, compatible with each other; never a curse.")
    @Items(0, 5)
    val enchantments: List<EnchantmentSpec> = emptyList(),
    @Doc("Attribute changes while held (weapons, tools) or worn (armor), one per attribute: bonuses, and at most one light malus.")
    @Items(0, 4)
    val attributes: List<RelicAttribute> = emptyList(),
    @Doc("It never wears out.") val unbreakable: Boolean = false,
    @Doc(
        "Restocks only: the players this relic waits for in the chests, by their best gear (the task says which tiers it " +
            "wants, with their caps). Left out for a champion's killer, whose tier the task gives.",
    )
    val tier: Tier? = null,
)

// ── Tiers (M7-BALANCE) ──────────────────────────────────────────────────────────────────────────────

/**
 * How far a player has come, by the material of their best gear: what a relic is measured against (M7-BALANCE). A
 * relic's base is one tier above its player's at most, and never netherite.
 */
@Serializable
enum class Tier(val id: String) {
    @SerialName("wood") WOOD("wood"),
    @SerialName("stone") STONE("stone"),
    @SerialName("iron") IRON("iron"),
    @SerialName("diamond") DIAMOND("diamond"),
    @SerialName("netherite") NETHERITE("netherite"),
    ;

    companion object {
        /**
         * The tier of the material of a tool, a weapon, a piece of armor or a relic base, by its id: wooden and leather
         * items and the bow are wood; stone, golden and copper ones and the shield, stone; iron and chainmail ones and the
         * crossbow, iron; diamond ones, the trident and the mace, diamond. Another mod's item goes by its path the same way
         * (`othermod:iron_knife` is iron). Null for anything else.
         */
        fun ofMaterial(id: String): Tier? {
            val bare = id.trim().lowercase().substringAfter(':')
            return when {
                bare.startsWith("wooden_") || bare.startsWith("leather_") || bare == "bow" -> WOOD
                bare.startsWith("stone_") || bare.startsWith("golden_") || bare.startsWith("copper_") || bare == "shield" -> STONE
                bare.startsWith("iron_") || bare.startsWith("chainmail_") || bare == "crossbow" -> IRON
                bare.startsWith("diamond_") || bare == "trident" || bare == "mace" -> DIAMOND
                bare.startsWith("netherite_") -> NETHERITE
                else -> null
            }
        }

        /**
         * A player's tier from the ids of the [tools] (swords, axes, pickaxes, shovels, hoes, spears) and the [armor] they
         * carry: their best tool or weapon, or one below their best armor (armor is found in chests early, a pickaxe is
         * crafted); wood at least.
         */
        fun ofPlayer(tools: List<String>, armor: List<String>): Tier {
            val tool = tools.mapNotNull(::ofMaterial).maxOrNull() ?: WOOD
            val worn = armor.mapNotNull(::ofMaterial).maxOrNull()?.let { entries[maxOf(it.ordinal - 1, 0)] } ?: WOOD
            return maxOf(tool, worn)
        }
    }
}

/**
 * What a relic may be for a player of [tier] (M7-BALANCE): its base at most [material], [level] enchantments at most,
 * none above level [level], attribute bonuses at most [share] of their own cap, unbreakable or not. One step up from the
 * player's tier; a [trivial] fight (the champion killed in a few seconds, or hardly hurting its killer) takes that step
 * back. Never netherite.
 */
data class RelicCaps(val tier: Tier, val trivial: Boolean = false) {
    private val step = tier.ordinal + if (trivial) 0 else 1
    val material: Tier = MATERIALS[step]
    val level: Int = LEVELS[step]
    val share: Double = SHARES[step]
    val unbreakable: Boolean = step >= UNBREAKABLE_STEP

    /** For the armorer: `base stone at most (wooden_, leather_, stone_…); 2 enchantments at most, level II at most; …`. */
    fun describe(): String =
        "base ${material.id} at most (${BASES.filterKeys { it <= material }.values.joinToString()}); " +
            "$level enchantment${if (level > 1) "s" else ""} at most, level ${ROMAN[level - 1]} at most; " +
            "attribute bonuses ${if (share >= 1.0) "up to their cap" else "up to ${(share * 100).toInt()} % of their cap"}; " +
            if (unbreakable) "unbreakable allowed" else "never unbreakable"

    private companion object {
        val MATERIALS = listOf(Tier.WOOD, Tier.STONE, Tier.IRON, Tier.DIAMOND, Tier.DIAMOND, Tier.DIAMOND)
        val LEVELS = listOf(1, 2, 2, 3, 4, 5)
        val SHARES = listOf(0.5, 0.5, 0.5, 0.75, 1.0, 1.0)
        const val UNBREAKABLE_STEP = 4
        val ROMAN = listOf("I", "II", "III", "IV", "V")
        val BASES = mapOf(
            Tier.WOOD to "wooden_, leather_, bow",
            Tier.STONE to "stone_, golden_, copper_, shield",
            Tier.IRON to "iron_, chainmail_, crossbow",
            Tier.DIAMOND to "diamond_, trident, mace",
        )
    }
}

// ── Consumables ─────────────────────────────────────────────────────────────────────────────────────

/**
 * An effect a consumable may give, at most [maxSeconds] long and [maxLevel] strong. Nothing that can kill: no
 * instant damage, no wither, no levitation (the fall); poison stops at half a heart in the game.
 */
@Serializable
enum class ConsumableEffectKey(val id: String, val maxSeconds: Int, val maxLevel: Int, val good: Boolean) {
    @SerialName("speed") SPEED("speed", 300, 2, true),
    @SerialName("haste") HASTE("haste", 300, 2, true),
    @SerialName("strength") STRENGTH("strength", 120, 1, true),
    @SerialName("jump_boost") JUMP_BOOST("jump_boost", 180, 2, true),
    @SerialName("regeneration") REGENERATION("regeneration", 30, 2, true),
    @SerialName("resistance") RESISTANCE("resistance", 120, 1, true),
    @SerialName("fire_resistance") FIRE_RESISTANCE("fire_resistance", 300, 1, true),
    @SerialName("water_breathing") WATER_BREATHING("water_breathing", 300, 1, true),
    @SerialName("invisibility") INVISIBILITY("invisibility", 120, 1, true),
    @SerialName("night_vision") NIGHT_VISION("night_vision", 300, 1, true),
    @SerialName("health_boost") HEALTH_BOOST("health_boost", 300, 2, true),
    @SerialName("absorption") ABSORPTION("absorption", 300, 2, true),
    @SerialName("saturation") SATURATION("saturation", 5, 1, true),
    @SerialName("luck") LUCK("luck", 600, 1, true),
    @SerialName("slow_falling") SLOW_FALLING("slow_falling", 120, 1, true),
    @SerialName("dolphins_grace") DOLPHINS_GRACE("dolphins_grace", 120, 1, true),
    @SerialName("glowing") GLOWING("glowing", 120, 1, false),
    @SerialName("slowness") SLOWNESS("slowness", 60, 1, false),
    @SerialName("mining_fatigue") MINING_FATIGUE("mining_fatigue", 60, 1, false),
    @SerialName("nausea") NAUSEA("nausea", 15, 1, false),
    @SerialName("blindness") BLINDNESS("blindness", 10, 1, false),
    @SerialName("darkness") DARKNESS("darkness", 15, 1, false),
    @SerialName("hunger") HUNGER("hunger", 60, 2, false),
    @SerialName("weakness") WEAKNESS("weakness", 60, 1, false),
    @SerialName("poison") POISON("poison", 8, 1, false),
    @SerialName("unluck") UNLUCK("unluck", 600, 1, false),
}

@Serializable
data class ConsumableEffect(
    val effect: ConsumableEffectKey,
    @Doc(
        "Seconds, within the effect's own maximum: speed, haste, fire_resistance, water_breathing, night_vision, health_boost, " +
            "absorption 300; luck, unluck 600; jump_boost 180; strength, resistance, invisibility, slow_falling, dolphins_grace, " +
            "glowing 120; slowness, mining_fatigue, hunger, weakness 60; regeneration 30; nausea, darkness 15; blindness 10; " +
            "poison 8; saturation 5.",
    )
    @Range(1.0, 600.0)
    val seconds: Int,
    @Doc("Strength: 2 at most for speed, haste, jump_boost, regeneration, health_boost, absorption, hunger; 1 for the others.")
    @Range(1.0, 2.0)
    val level: Int = 1,
    @Doc("The chance it happens, 0.05 to 1: chances that add up to about 1 make one effect among several.")
    @Range(0.05, 1.0)
    val chance: Double = 1.0,
)

/**
 * A strange consumable: a food with a name, a lore and effects of its own, never deadly. Any food of the game's registry,
 * other mods' included (M7-NOTES), but a precious one; `RelicItems` checks it on the server thread.
 */
@Serializable
data class ConsumableDraft(
    @Doc(
        "The food it is made from: any food item, of the game (bread, cooked_beef, sweet_berries…) or of another mod " +
            "(its full id, namespace:path); never a golden apple. Its own effects give way to yours.",
    )
    @MaxLen(64)
    val food: String,
    @Doc("Its name, in French.") @MaxLen(NAME_MAX) val name: String,
    @Doc("Its lore, in French: one to four short lines; it may warn, lie, or do neither.")
    @Items(1, LORE_LINES)
    @MaxLen(LORE_LINE_MAX)
    val lore: List<String>,
    @Doc("What it does when eaten or drunk, one entry per effect.") @Items(1, 4) val effects: List<ConsumableEffect>,
)

// ── Books ───────────────────────────────────────────────────────────────────────────────────────────

/** A written book found in a chest: the server's history, a prophecy, a rumor. */
@Serializable
data class BookDraft(
    @Doc("The title on its cover, in French.") @MaxLen(32) val title: String,
    @Doc("Who signs it: the Narrator, an anonymous witness, a scribe, a rival…") @MaxLen(32) val author: String,
    @Doc("Its pages, plain French text: newlines are kept, and a page too long for the book continues on the next one.")
    @Items(1, 8)
    @MaxLen(700)
    val pages: List<String>,
)

// ── Champions ───────────────────────────────────────────────────────────────────────────────────────

/** The hostile creatures a champion may be: ones that fight on foot or with a bow, none that explodes or teleports away. */
@Serializable
enum class ChampionMob(val id: String) {
    @SerialName("zombie") ZOMBIE("zombie"),
    @SerialName("husk") HUSK("husk"),
    @SerialName("drowned") DROWNED("drowned"),
    @SerialName("skeleton") SKELETON("skeleton"),
    @SerialName("stray") STRAY("stray"),
    @SerialName("bogged") BOGGED("bogged"),
    @SerialName("wither_skeleton") WITHER_SKELETON("wither_skeleton"),
    @SerialName("spider") SPIDER("spider"),
    @SerialName("cave_spider") CAVE_SPIDER("cave_spider"),
    @SerialName("witch") WITCH("witch"),
    @SerialName("vindicator") VINDICATOR("vindicator"),
    @SerialName("pillager") PILLAGER("pillager"),
    @SerialName("zombified_piglin") ZOMBIFIED_PIGLIN("zombified_piglin"),
}

/** A lasting effect on a champion, at most [maxLevel] strong. */
@Serializable
enum class ChampionEffectKey(val id: String, val maxLevel: Int) {
    @SerialName("speed") SPEED("speed", 1),
    @SerialName("strength") STRENGTH("strength", 1),
    @SerialName("resistance") RESISTANCE("resistance", 1),
    @SerialName("regeneration") REGENERATION("regeneration", 1),
    @SerialName("fire_resistance") FIRE_RESISTANCE("fire_resistance", 1),
    @SerialName("water_breathing") WATER_BREATHING("water_breathing", 1),
    @SerialName("slow_falling") SLOW_FALLING("slow_falling", 1),
    @SerialName("jump_boost") JUMP_BOOST("jump_boost", 2),
    @SerialName("absorption") ABSORPTION("absorption", 2),
}

@Serializable
data class ChampionEffect(
    val effect: ChampionEffectKey,
    @Doc("Strength: 2 at most for jump_boost and absorption, 1 for the others.") @Range(1.0, 2.0) val level: Int = 1,
)

/** One piece of a champion's gear: what it wears or holds, dropped when a player kills it. */
@Serializable
data class GearPiece(
    @Doc("Item id; armor goes to its own slot. Never netherite.") @MaxLen(64) val item: String,
    @Doc("Its name, in French, if it has one.") @MaxLen(NAME_MAX) val name: String? = null,
    @Doc("Enchantments that fit it, up to the game's usual maximum level; never a curse.") @Items(0, 4) val enchantments: List<EnchantmentSpec> = emptyList(),
)

@Serializable
data class ChampionGear(
    val head: GearPiece? = null,
    val chest: GearPiece? = null,
    val legs: GearPiece? = null,
    val feet: GearPiece? = null,
    val mainhand: GearPiece? = null,
    val offhand: GearPiece? = null,
)

/**
 * A champion: a hostile creature far harder than its kind. Its factors multiply what the world already gives every
 * hostile creature (the difficulty layer: health x1.5, damage x1.25 by default): health x3 is x4.5 in all, damage x1.5
 * is about x1.9. Hence the caps.
 */
@Serializable
data class ChampionDraft(
    val mob: ChampionMob,
    @Doc("The name shown above it, in French.") @MaxLen(32) val name: String,
    @Doc("Its legend, in French, two or three sentences: who it is, why it hunts this player.") @MaxLen(300) val lore: String,
    @Doc("Factor of its health, 1.5 to 3, on top of what the world already gives every hostile creature.") @Range(1.5, 3.0) val health: Double,
    @Doc("Factor of its damage, 1 to 1.5.") @Range(1.0, 1.5) val damage: Double = 1.0,
    @Doc("Factor of its speed, 1 to 1.3.") @Range(1.0, 1.3) val speed: Double = 1.0,
    @Doc("What it wears and holds; all of it drops when a player kills it.") val gear: ChampionGear = ChampionGear(),
    @Doc("Lasting effects, one per kind.") @Items(0, 3) val effects: List<ChampionEffect> = emptyList(),
)

// ── The rules ───────────────────────────────────────────────────────────────────────────────────────

/** Longest name of a relic, a consumable or a piece of gear. */
const val NAME_MAX = 40

/** Lines of lore at most, and the longest line. */
const val LORE_LINES = 4
const val LORE_LINE_MAX = 80

/**
 * The rules of what the armorer designs that need no registry: a relic always good (at least one bonus, at most one
 * light malus, every change within its caps), texts that show, effects within their caps. Each function returns every
 * problem, one English sentence each, for the model to fix; empty when the draft holds. What needs the game (the base
 * item, the enchantments that fit it) is `RelicItems`'s.
 */
object RelicRules {
    fun relic(draft: RelicDraft): List<String> = buildList {
        texts(draft.name, draft.lore)
        val repeated = draft.attributes.groupBy { it.attribute }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) add("attributes: one change per attribute (repeated: ${repeated.joinToString { it.id }})")
        for (change in draft.attributes) {
            val key = change.attribute
            when {
                change.amount == 0.0 -> add("${key.id}: an amount of 0 changes nothing; leave it out")
                change.amount > key.bonusMax -> add("${key.id}: a bonus goes up to ${number(key.bonusMax)} (got ${number(change.amount)})")
                change.amount < 0 && key.malusMin == 0.0 -> add("${key.id} is never a malus: pick the malus among max_health, armor, luck, attack_damage, attack_speed, movement_speed, block_break_speed, the ranges")
                change.amount < key.malusMin -> add("${key.id}: a malus stays light, down to ${number(key.malusMin)} (got ${number(change.amount)})")
            }
        }
        val maluses = draft.attributes.count { it.amount < 0 }
        if (maluses > 1) add("at most one malus, and a light one ($maluses given): a relic stays a good item")
        val bonuses = draft.enchantments.size + draft.attributes.count { it.amount > 0 }
        if (bonuses == 0) add("a relic needs at least one bonus: an enchantment or a positive attribute")
        val enchantments = draft.enchantments.groupBy { it.id.trim().lowercase().removePrefix("minecraft:") }.filterValues { it.size > 1 }.keys
        if (enchantments.isNotEmpty()) add("enchantments: each once (repeated: ${enchantments.joinToString()})")
    }

    /**
     * What makes [draft] too strong for a player under [caps] (M7-BALANCE): its base, its enchantments (how many, how
     * high), its attribute bonuses, unbreakable. Each problem says the cap; empty when it fits. An unknown base counts as
     * diamond.
     */
    fun withinCaps(draft: RelicDraft, caps: RelicCaps): List<String> {
        val problems = buildList {
            val material = Tier.ofMaterial(draft.item) ?: Tier.DIAMOND
            if (material > caps.material) add("base: ${caps.material.id} at most (${draft.item.trim()} is ${material.id})")
            if (draft.enchantments.size > caps.level) add("${draft.enchantments.size} enchantments: ${caps.level} at most")
            val high = draft.enchantments.filter { it.level > caps.level }
            if (high.isNotEmpty()) add("enchantments up to level ${caps.level} (${high.joinToString { "${it.id.trim().removePrefix("minecraft:")} ${it.level}" }})")
            for (change in draft.attributes) {
                val max = change.attribute.bonusMax * caps.share
                if (change.amount > max + 1e-9) add("${change.attribute.id}: a bonus up to ${number(max)} here (got ${number(change.amount)})")
            }
            if (draft.unbreakable && !caps.unbreakable) add("unbreakable: not at this tier")
        }
        if (problems.isEmpty()) return problems
        val fight = if (caps.trivial) ", after a fight that was too easy" else ""
        return listOf("too strong for a ${caps.tier.id}-tier player$fight: " + problems.joinToString("; ") + "; caps: ${caps.describe()}")
    }

    /** The lowest tier whose caps [draft] fits: the players a relic of the reserves made before the tiers waits for. */
    fun fittingTier(draft: RelicDraft): Tier = Tier.entries.firstOrNull { withinCaps(draft, RelicCaps(it)).isEmpty() } ?: Tier.NETHERITE

    /**
     * Whether a champion's fight was too easy to earn a full relic (M7-BALANCE): killed less than [TRIVIAL_MS] after the
     * first player's blow ([foughtMs]), or hurting its killer by less than [TRIVIAL_DAMAGE] in all ([dealt]).
     */
    fun trivial(foughtMs: Long, dealt: Double): Boolean = foughtMs < TRIVIAL_MS || dealt < TRIVIAL_DAMAGE

    const val TRIVIAL_MS = 10_000L
    const val TRIVIAL_DAMAGE = 4.0

    fun consumable(draft: ConsumableDraft): List<String> = buildList {
        texts(draft.name, draft.lore)
        val repeated = draft.effects.groupBy { it.effect }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) add("effects: one entry per effect (repeated: ${repeated.joinToString { it.id }})")
        for (entry in draft.effects) {
            val key = entry.effect
            if (entry.seconds > key.maxSeconds) add("${key.id} lasts ${key.maxSeconds} s at most (got ${entry.seconds})")
            if (entry.level > key.maxLevel) add("${key.id} goes up to level ${key.maxLevel} (got ${entry.level})")
        }
    }

    fun book(draft: BookDraft): List<String> = buildList {
        if (Speech.clean(draft.title).isEmpty()) add("title has nothing to show once cleaned: write plain text")
        if (Speech.clean(draft.author).isEmpty()) add("author has nothing to show once cleaned: write plain text")
        if (draft.pages.none { Speech.clean(it, maxLines = Int.MAX_VALUE).isNotEmpty() }) add("pages: every page is empty once cleaned")
    }

    fun champion(draft: ChampionDraft): List<String> = buildList {
        if (Speech.clean(draft.name).isEmpty()) add("name has nothing to show once cleaned: write plain text")
        if (Speech.clean(draft.lore).isEmpty()) add("lore has nothing to show once cleaned: write its legend")
        val repeated = draft.effects.groupBy { it.effect }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) add("effects: one entry per effect (repeated: ${repeated.joinToString { it.id }})")
        for (entry in draft.effects) {
            if (entry.level > entry.effect.maxLevel) add("${entry.effect.id} goes up to level ${entry.effect.maxLevel} on a champion (got ${entry.level})")
        }
        for ((slot, piece) in pieces(draft.gear)) {
            if (piece.name != null && Speech.clean(piece.name).isEmpty()) add("gear.$slot.name has nothing to show once cleaned")
        }
    }

    /** The pieces of [gear] given, by slot name (`head`, `mainhand`…). */
    fun pieces(gear: ChampionGear): List<Pair<String, GearPiece>> = listOfNotNull(
        gear.head?.let { "head" to it },
        gear.chest?.let { "chest" to it },
        gear.legs?.let { "legs" to it },
        gear.feet?.let { "feet" to it },
        gear.mainhand?.let { "mainhand" to it },
        gear.offhand?.let { "offhand" to it },
    )

    private fun MutableList<String>.texts(name: String, lore: List<String>) {
        if (Speech.clean(name).isEmpty()) add("name has nothing to show once cleaned: write plain text")
        if (lore.none { Speech.clean(it).isNotEmpty() }) add("lore: at least one line that shows once cleaned")
    }
}
