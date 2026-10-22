package io.github.clemsix6.narrator2.difficulty

import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.typeId
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.monster.ElderGuardian
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.warden.Warden
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The permanent difficulty layer (M7-BASE, `docs/research/DIFFICULTY_API.md`): hostile creatures with more health
 * that hit players harder, fewer ore blobs, slower natural regeneration, fewer diamonds and enchanted golden apples
 * in chests. It needs neither Claude nor the Narrator's runtime: its section is read at `SERVER_STARTING`, before the
 * worlds load and generate their spawn chunks, and stays for the whole launch. The mixins of `mixin/difficulty` and
 * the hooks of [registerDifficultyHooks] read it here, from the server thread and the world generation threads.
 */
object Difficulty {
    private val log = Log.difficulty

    /**
     * The difficulty's own health modifier. Its path never starts with `rule/`, `module/` nor `spawn/`: the rules'
     * bounds and removals never count nor remove it, and the Narrator's factors multiply with it.
     */
    val HEALTH_ID: Identifier = Identifier.fromNamespaceAndPath("narrator2", "difficulty/max_health")

    /** The ore features of the vanilla `worldgen/feature` registry (path) → their ore in [DifficultyConfig.ores]. */
    val ORE_FEATURES: Map<String, String> = mapOf(
        "ore_coal" to "coal", "ore_coal_buried" to "coal",
        "ore_iron" to "iron", "ore_iron_small" to "iron",
        "ore_copper_small" to "copper", "ore_copper_large" to "copper",
        // `ore_gold` is only the badlands' bonus; the usual gold is `ore_gold_buried`.
        "ore_gold" to "gold", "ore_gold_buried" to "gold", "ore_nether_gold" to "gold",
        "ore_redstone" to "redstone",
        "ore_lapis" to "lapis", "ore_lapis_buried" to "lapis",
        "ore_diamond_small" to "diamond", "ore_diamond_medium" to "diamond", "ore_diamond_large" to "diamond", "ore_diamond_buried" to "diamond",
        "ore_emerald" to "emerald",
        "ore_quartz" to "quartz",
        "ore_ancient_debris_large" to "ancient_debris", "ore_ancient_debris_small" to "ancient_debris",
    )

    /** The section in force; null before the first `SERVER_STARTING` (unit tests), when nothing changes. */
    @Volatile
    var config: DifficultyConfig? = null
        private set

    private fun active(): DifficultyConfig? = config?.takeIf { it.enabled }

    /** `SERVER_STARTING`: reads the section; its defaults when the configuration cannot be used, so the world stays hard. */
    fun load(dir: Path) {
        val loaded = try {
            ConfigStore.load(dir).difficulty
        } catch (e: Exception) {
            log.error("cannot read the configuration, the difficulty layer takes its defaults: ${e.message ?: e.javaClass.simpleName}")
            DifficultyConfig()
        }
        config = loaded
        log.info(if (loaded.enabled) "on: ${summary(loaded) ?: "every factor is vanilla"}" else "off: the world plays as vanilla")
    }

    /** The wither, the ender dragon, the warden and the elder guardian keep their vanilla health and damage. */
    fun isBoss(entity: Entity): Boolean = entity is WitherBoss || entity is EnderDragon || entity is Warden || entity is ElderGuardian

    /**
     * `ENTITY_LOAD` of [mob], born or loaded from a chunk: a hostile creature gets the permanent health modifier (or
     * loses it when the layer is off), and keeps the same share of its health, so a fresh one is at its new maximum.
     * Idempotent: a creature loaded again already has the right modifier. Server thread.
     */
    fun onMobLoad(mob: Mob) {
        val config = config ?: return
        if (mob !is Enemy || isBoss(mob)) return
        val factor = if (config.enabled) config.mobHealth else 1.0
        val instance = mob.getAttribute(Attributes.MAX_HEALTH) ?: return
        if ((instance.getModifier(HEALTH_ID)?.amount() ?: 0.0) == factor - 1) return
        val before = mob.maxHealth
        if (factor == 1.0) {
            instance.removeModifier(HEALTH_ID)
        } else {
            instance.addOrReplacePermanentModifier(AttributeModifier(HEALTH_ID, factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        }
        mob.health = mob.health * mob.maxHealth / before
        log.debug { "${mob.typeId} max health ${number(before.toDouble())} -> ${number(mob.maxHealth.toDouble())}, health ${number(mob.health.toDouble())}" }
    }

    /** Mixin of `ServerPlayer.hurtServer`: what a hostile creature (not a boss) inflicts on a player, scaled. */
    @JvmStatic
    fun scaleDamage(source: DamageSource, amount: Float): Float {
        val config = active() ?: return amount
        val attacker = source.entity
        if (config.mobDamage == 1.0 || attacker !is Enemy || isBoss(attacker)) return amount
        return (amount * config.mobDamage).toFloat()
    }

    /** Mixin of `FoodData.tick`: an interval of natural regeneration ([ticks], 10 or 80), lengthened. */
    @JvmStatic
    fun regenerationTicks(ticks: Int): Int {
        val config = active() ?: return ticks
        return (ticks * config.regenerationSlowdown).roundToInt()
    }

    /**
     * Mixin of `FeaturePlacer`: whether this blob of an ore feature at [pos] is not placed. A draw from the world's
     * [seed], the position and the feature, which leaves the chunk's random untouched: the blobs kept are where vanilla
     * puts them, with their vanilla size. World generation threads.
     */
    @JvmStatic
    fun skipsOre(placed: PlacedFeature, seed: Long, pos: BlockPos): Boolean {
        val config = active() ?: return false
        val id = placed.feature().unwrapKey().orElse(null)?.identifier() ?: return false
        val factor = oreFactor(config, id) ?: return false
        return factor < 1.0 && draw(seed, pos.x.toLong(), pos.y.toLong(), pos.z.toLong(), id.path.hashCode().toLong()) >= factor
    }

    /** The share of blobs kept of the ore feature [feature]; null for anything that is not a vanilla ore feature. */
    internal fun oreFactor(config: DifficultyConfig, feature: Identifier): Double? {
        if (feature.namespace != Identifier.DEFAULT_NAMESPACE) return null
        val ore = ORE_FEATURES[feature.path] ?: return null
        return config.ores[ore] ?: 1.0
    }

    /** A deterministic draw in [0, 1) from a seed, a position and a salt (SplitMix64's finalizer). */
    internal fun draw(seed: Long, x: Long, y: Long, z: Long, salt: Long): Double {
        var h = seed xor (x * -0x61c8864680b583ebL) xor (y * -0x3d4d51c2d82b14b1L) xor (z * 0x165667b19e3779f9L) xor (salt * -0x2917014799a6026dL)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return (h ushr 11) * (1.0 / (1L shl 53))
    }

    /**
     * `MODIFY_DROPS` of the loot table [table] (path): in a chest, each diamond and each enchanted golden apple is
     * kept with its chance. No chest table nests another one in vanilla 26.3, so nothing is thinned twice. Server thread.
     */
    fun thinChest(table: String, random: RandomSource, drops: MutableList<ItemStack>) {
        val config = active() ?: return
        if (!table.startsWith("chests/")) return
        var changed = false
        for (stack in drops) {
            val chance = when (stack.item) {
                Items.DIAMOND -> config.chestDiamonds
                Items.ENCHANTED_GOLDEN_APPLE -> config.chestEnchantedGoldenApples
                else -> continue
            }
            if (chance >= 1.0) continue
            val item = stack.item
            val before = stack.count
            val kept = (0 until before).count { random.nextDouble() < chance }
            stack.count = kept
            log.debug { "$table: $item $before -> $kept" }
            changed = true
        }
        if (changed) drops.removeIf(ItemStack::isEmpty)
    }

    /** The `difficulty="…"` attribute of the briefing's `<world/>` line, with its leading space; empty when nothing changes. */
    fun briefingAttribute(): String = active()?.let(::summary)?.let { " difficulty=\"$it\"" }.orEmpty()

    /** What [config] changes, compact and in English, for the Narrator and the log; null when everything is vanilla. */
    internal fun summary(config: DifficultyConfig): String? {
        val parts = buildList {
            val mobs = listOfNotNull(
                config.mobHealth.takeIf { it != 1.0 }?.let { "health x${number(it)}" },
                config.mobDamage.takeIf { it != 1.0 }?.let { "damage to players x${number(it)}" },
            )
            if (mobs.isNotEmpty()) add("hostile mobs ${mobs.joinToString(", ")} (bosses vanilla)")
            val ores = config.ores.filterValues { it != 1.0 }.entries.groupBy({ it.value }, { it.key })
            if (ores.isNotEmpty()) add("ores " + ores.entries.sortedBy { it.key }.joinToString(", ") { (factor, names) -> "${names.joinToString("/")} x${number(factor)}" })
            if (config.regenerationSlowdown != 1.0) add("natural regeneration x${number(1 / config.regenerationSlowdown)}")
            val chests = listOfNotNull(
                config.chestDiamonds.takeIf { it != 1.0 }?.let { "diamonds x${number(it)}" },
                config.chestEnchantedGoldenApples.takeIf { it != 1.0 }?.let { "enchanted golden apples x${number(it)}" },
            )
            if (chests.isNotEmpty()) add("chests ${chests.joinToString(", ")}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
