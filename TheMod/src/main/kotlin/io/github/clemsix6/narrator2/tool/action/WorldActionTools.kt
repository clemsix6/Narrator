package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.tool.ToolText.number
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.chaos
import io.github.clemsix6.narrator2.tool.live
import io.github.clemsix6.narrator2.tool.online
import io.github.clemsix6.narrator2.tool.punish
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.tool.speech.closestIds
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.clock.ServerClockManager
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items as GameItems
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LightningRodBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Actions on the world (M1_M2 §9.4): `spawn`, `lightning`, `explosion`, `fireworks`, `particles`,
 * `set_weather`, `set_time`. `ToolCatalog` builds the family once per conversation; the weather and time
 * limits ([WorldLimits]) are the server's, shared by every conversation.
 *
 * Every tool follows the guards' order (`handoff/T09a.md`): arguments and the caps of the chaos level,
 * then `Guards.hostile` for a hostile action, then `armDeathGuard` for anything that can kill, then the
 * action. Nothing here ever changes a block or destroys an item:
 * - the Narrator's creatures are marked (`NarratorMobs`) before they enter the world: they vanish when
 *   due, never break a door, never pick anything up, and their explosions are the Narrator's;
 * - every explosion goes off in `ExplosionGuard.narrators` ([NarratorExplosionDamage]);
 * - a bolt is only visual, and never lands on copper or a lightning rod (which even a visual bolt changes).
 *
 * Each hostile action tells the guards its category of punishment (M4 §6.1): `creatures` for a summoning,
 * `lightning` for a bolt that hurts, `explosion` for a blast that hurts (a creature's fuse included). The
 * checks that need neither the players online nor the world are each tool's `check` (M4 §4.3), which `run`
 * starts with: the mechanics engine runs them alone to refuse a rule's effect when it is created.
 */
fun worldActionTools(services: NarratorServices): List<NarratorTool<*>> {
    val limits = services.toolLimits.world
    return listOf(SpawnTool(), LightningTool(), ExplosionTool(), FireworksTool(), ParticlesTool(), SetWeatherTool(limits), SetTimeTool(limits))
}

private const val PLAYER_DOC = "Name of the online player at whom it happens; leave it out to give x, y and z instead."
private const val COORDINATE_DOC = "Block coordinate; x, y and z together, instead of a player."
private const val DIMENSION_DOC = "Dimension of x, y and z: overworld (the default), the_nether or the_end."

// ── spawn ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Something a summoned creature wears or holds, with what makes a costume that every vanilla client shows
 * (M7-CREATURES): dyed leather, a player's face on a head, the enchanted shimmer. A bare item id stands for
 * `{"item": …}` (`ToolArgs`), as the equipment was written before.
 */
@Serializable
data class WornItem(
    @Doc("Item id.") @MaxLen(64) val item: String,
    @Doc("Leather pieces only: their dye, #RRGGBB.") @MaxLen(7) val color: String? = null,
    @Doc("player_head only: the account whose face it shows.") @MaxLen(16) val skin: String? = null,
    @Doc("Seen only if it drops.") @MaxLen(50) val name: String? = null,
    @Doc("Enchanted shimmer, for show.") val glint: Boolean = false,
) {
    /** `player_head of Bob`, `leather_chestplate #F9D71C "Toge"`, for the answer. */
    fun shown(): String =
        item + (skin?.let { " of $it" } ?: "") + (color?.let { " $it" } ?: "") + (name?.let { " \"${Speech.clean(it)}\"" } ?: "") + (if (glint) " shimmering" else "")
}

/** What a summoned creature wears and holds. */
@Serializable
data class EquipmentSpec(
    @Doc("Any head item keeps zombies and skeletons from burning by day.") val head: WornItem? = null,
    val chest: WornItem? = null,
    val legs: WornItem? = null,
    val feet: WornItem? = null,
    val mainhand: WornItem? = null,
    val offhand: WornItem? = null,
) {
    /** The slots given, with what goes there. */
    fun slots(): List<Pair<EquipmentSlot, WornItem>> = listOfNotNull(
        head?.let { EquipmentSlot.HEAD to it },
        chest?.let { EquipmentSlot.CHEST to it },
        legs?.let { EquipmentSlot.LEGS to it },
        feet?.let { EquipmentSlot.FEET to it },
        mainhand?.let { EquipmentSlot.MAINHAND to it },
        offhand?.let { EquipmentSlot.OFFHAND to it },
    )
}

/**
 * An attribute of a summoned creature, changed by a factor of its normal value, within [min]..[max]
 * (the creature is the Narrator's and vanishes with its change: no ledger). [id] is the registry path.
 */
@Serializable
enum class MobAttributeKey(val id: String, val min: Double, val max: Double) {
    @SerialName("scale") SCALE("scale", 0.25, 4.0),
    @SerialName("movement_speed") MOVEMENT_SPEED("movement_speed", 0.25, 3.0),
    @SerialName("max_health") MAX_HEALTH("max_health", 0.25, 5.0),
    @SerialName("attack_damage") ATTACK_DAMAGE("attack_damage", 0.0, 2.0),
    @SerialName("jump_strength") JUMP_STRENGTH("jump_strength", 0.0, 3.0),
    @SerialName("gravity") GRAVITY("gravity", 0.1, 2.0),
}

@Serializable
data class MobAttribute(
    val attribute: MobAttributeKey,
    @Doc(
        "Factor of the creature's normal value: 3 for three times as fast, 0.5 for half the size, 0.2 for a low gravity. " +
            "scale 0.25 to 4, movement_speed 0.25 to 3, max_health 0.25 to 5, attack_damage 0 to 2, jump_strength 0 to 3, gravity 0.1 to 2.",
    )
    @Range(0.0, 5.0)
    val multiply: Double,
)

/** Each summoned creature explodes [afterSeconds] after it appears. */
@Serializable
data class ExplodeSpec(
    @Doc("Seconds after they appear; each creature goes off a few ticks after the previous one, and vanishes in its blast.")
    @SerialName("after_seconds")
    @Range(1.0, 60.0)
    val afterSeconds: Int,
    @Doc("Explosion power: 1 a firecracker, 3 a creeper, 4 a TNT.")
    @Range(0.5, 4.0)
    val power: Double = 2.0,
    @Doc("false: a show of fire and noise that hurts and pushes nobody.")
    val hurts: Boolean = true,
)

@Serializable
data class SpawnArgs(
    @Doc("Creature id, e.g. zombie, creeper, skeleton, spider, slime, cow, chicken, pig; the minecraft: namespace is optional.")
    @MaxLen(64)
    val entity: String,
    @Doc("How many creatures.")
    @Range(1.0, 20.0)
    val count: Int = 1,
    @Doc("Name of the online player they appear around.")
    @MaxLen(16)
    val near: String,
    @Doc("How far from the player they appear, in blocks: between half this distance and this distance.")
    @Range(2.0, 24.0)
    val distance: Int = 8,
    @Doc("Name shown above each creature.")
    @MaxLen(32)
    val name: String? = null,
    @Doc("true: the creatures go straight for the player.")
    val target: Boolean = false,
    @Doc("Their costume: what they wear and hold.")
    val equipment: EquipmentSpec? = null,
    @Doc(
        "Chance that each piece of their costume drops when a player kills one; 0 by default, for a costume is not a gift " +
            "(give_item is). Precious items (diamond, netherite, totem…) never drop.",
    )
    @SerialName("drop_chance")
    @Range(0.0, 1.0)
    val dropChance: Double = 0.0,
    @Doc("They glow, seen through walls by everyone.")
    val glowing: Boolean = false,
    @Doc("Minutes before they vanish.")
    @SerialName("lifetime_minutes")
    @Range(1.0, 10.0)
    val lifetimeMinutes: Int = 5,
    @Doc("The baby form, for the creatures that have one (a baby zombie is small and fast).")
    val baby: Boolean = false,
    @Doc("Changes to their attributes, one per attribute.")
    @Items(1, 6)
    val attributes: List<MobAttribute>? = null,
    @Doc("They appear this many blocks above the ground, as high as the space allows: a rain of creatures. No fall damage for them.")
    @Range(0.0, 30.0)
    val height: Int = 0,
    @Doc("Thrown straight up as they appear, in blocks per tick: 1 flies about 6 blocks high, 2 about 20, 3 about 40. No fall damage for them.")
    @Range(0.0, 3.0)
    val launch: Double = 0.0,
    @Doc("They explode after a delay. Like every explosion of the Narrator, it breaks no block and no item.")
    val explode: ExplodeSpec? = null,
) {
    init {
        val repeated = attributes.orEmpty().groupBy { it.attribute }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "attributes: one change per attribute (repeated: ${repeated.joinToString { it.id }})" }
    }
}

class SpawnTool : NarratorTool<SpawnArgs>(
    name = "spawn",
    description = "Summons creatures around a player: named, in costume, faster, bigger, glowing, targeting the player, raining " +
        "from the sky, thrown into the air, or set to explode. They vanish at the end of their lifetime and never break a block. " +
        "Every creature that can hurt (monsters, but also wolves, bees, bears, golems, goats, llamas, pufferfish…), and any " +
        "creature sent at the player, is a punishment of that player; only harmless animals are not. Those go for that " +
        "player alone, and their blows never kill. Caps on how many, per call and alive at once, depend on the chaos level.\n" +
        "Your creatures are characters, almost never bare mobs: a name and a costume drawn from what is happening, so that " +
        "players see the joke, each time a new one. For a rude player: {\"entity\": \"zombie\", \"count\": 3, \"near\": \"Bob\", " +
        "\"name\": \"Comité de Politesse\", \"target\": true, \"lifetime_minutes\": 2, \"equipment\": {\"head\": {\"item\": " +
        "\"leather_helmet\", \"color\": \"#FFFFFF\"}, \"chest\": {\"item\": \"leather_chestplate\", \"color\": \"#B02E26\"}, " +
        "\"mainhand\": \"bell\"}}. For a boaster, small fans wearing his face: {\"entity\": \"zombie\", \"count\": 4, " +
        "\"near\": \"Bob\", \"name\": \"Fan de Bob\", \"baby\": true, \"glowing\": true, \"equipment\": {\"head\": {\"item\": " +
        "\"player_head\", \"skin\": \"Bob\"}, \"offhand\": \"poppy\"}}. Armor shows on zombies, skeletons, piglins and their " +
        "kin; a head item also on illagers and wandering traders.",
    args = SpawnArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /**
     * The caps of the chaos level, the attribute changes within their bounds, a name to show, a creature that is
     * known and never forbidden, and equipment that may be given. Whether the creature may be summoned in the
     * player's world (enabled, at its difficulty, a creature at all) is the spawn policy's, on the server thread.
     */
    override suspend fun check(ctx: ToolContext, args: SpawnArgs): ToolResult? = dryCheck {
        val caps = ctx.guards.caps()
        val chaos = ctx.chaos
        if (caps.spawnsPerAction == 0) refuse(ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: the Narrator summons nothing."))
        if (args.count > caps.spawnsPerAction) {
            refuse(ToolResult.invalid("at chaos $chaos one call summons at most ${caps.spawnsPerAction} creatures (asked ${args.count})"))
        }
        args.explode?.let { explode ->
            if (explode.power > caps.explosionPower) {
                refuse(ToolResult.invalid("at chaos $chaos an explosion has a power of at most ${number(caps.explosionPower)} (asked ${number(explode.power)})"))
            }
        }
        for (change in args.attributes.orEmpty()) {
            val key = change.attribute
            if (change.multiply < key.min || change.multiply > key.max) {
                refuse(ToolResult.invalid("${key.id} multiplies by ${number(key.min)} to ${number(key.max)} (asked ${number(change.multiply)})"))
            }
        }
        if (args.name != null && Speech.clean(args.name).isEmpty()) {
            refuse(ToolResult.invalid("name has nothing to show once formatting codes are removed; write plain text"))
        }
        val id = EntityType.getKey(entityType(args.entity)).toString()
        SpawnPolicy.FORBIDDEN[id]?.let { refuse(ToolResult.denied(SpawnPolicy.FORBIDDEN_CREATURE, "$id is not summoned: $it.")) }
        args.equipment?.slots()?.forEach { (_, raw) -> equipmentItem(raw) }
    }

    override suspend fun run(ctx: ToolContext, args: SpawnArgs): ToolResult = run(ctx, args, punishment = null)

    /**
     * [run], for the creatures of [punishment] of the catalogue (`punish`'s step; null for a plain `spawn`). The
     * creatures of a punishment, and those of a plain `spawn` that can hurt or are sent at the player, are marked with
     * it ([NarratorMob.punishment], [NarratorMob.SPAWN]): they are the player's alone, and never kill (M4-POST,
     * M4-LEFTOVERS).
     */
    suspend fun run(ctx: ToolContext, args: SpawnArgs, punishment: PunishmentId?): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val caps = ctx.guards.caps()
        val name = args.name?.let { Speech.clean(it) }
        val alive = ctx.guards.mobs.count()
        if (alive + args.count > caps.livingMobs) {
            val left = (caps.livingMobs - alive).coerceAtLeast(0)
            refuse(
                ToolResult.denied(
                    MOB_CAP,
                    "the Narrator has $alive creatures in the world, at most ${caps.livingMobs} at chaos ${ctx.chaos}: $left more can be " +
                        "summoned now; the others vanish at the end of their lifetime.",
                ),
            )
        }
        val near = ctx.online(args.near)
        ctx.onServer { server -> answer { summon(ctx, server, near, args, name, punishment) } }
    }

    private fun summon(ctx: ToolContext, server: MinecraftServer, near: PlayerRef, args: SpawnArgs, name: String?, punishment: PunishmentId?): ToolResult {
        val player = server.live(near)
        val level = player.level()
        val type = entityType(args.entity)
        val id = EntityType.getKey(type).toString()
        ctx.guards.spawns.check(type, level).let { if (it is Verdict.Denied) refuse(ToolResult.denied(it)) }
        val equipment = args.equipment?.slots().orEmpty().map { (slot, raw) -> slot to equipmentItem(raw) }
        val caps = ctx.guards.caps()
        val creeperRadius = floor(min(caps.explosionPower, CREEPER_POWER)).toInt()

        // The riders count against the cap: the game's extras are dropped when they would go over it.
        val room = caps.livingMobs - ctx.guards.mobs.count()
        val mobs = mutableListOf<Mob>()
        repeat(args.count) {
            val mob = prepare(level, type, player, args, name, equipment, creeperRadius) ?: return@repeat
            if (mobs.sumOf { group(it).size } + group(mob).size > room) dropRiders(level, mob)
            mobs += mob
        }
        if (mobs.isEmpty()) {
            refuse(ToolResult.failed("no free ground for $id within ${args.distance} blocks of ${player.shownName}; try another distance or creature"))
        }

        val now = ctx.clock.millis()
        val expiresAt = now + args.lifetimeMinutes * 60_000L
        // Anything but a harmless creature (a whitelist): a wolf, a bee or a pufferfish is a punishment too.
        val hostile = args.target || mobs.flatMap(::group).any { !SpawnPolicy.isHarmless(it.type) }
        if (hostile) {
            val verdict = ctx.guards.hostile(player, ctx.caller, expiresAt, PunishmentCategory.CREATURES)
            if (verdict is Verdict.Denied) {
                mobs.forEach { dropRiders(level, it) } // a mount the game already added must not stay behind
                refuse(ToolResult.denied(verdict))
            }
            ctx.guards.armDeathGuard(player.uuid, args.lifetimeMinutes * 60)
        }
        // A punishment's creatures, and the hostile ones of a plain spawn, are the player's alone and never kill.
        val bound = punishment?.id ?: NarratorMob.SPAWN.takeIf { hostile }
        val added = mutableListOf<Mob>()
        for (mob in mobs) {
            if (!enter(ctx.guards, level, mob, expiresAt, player.uuid, hostile, bound)) continue
            if (args.target) mob.target = player
            args.explode?.let { Fuses.light(ctx.services, ctx.caller, mob, it, added.size) }
            added += mob
        }
        if (added.isEmpty()) refuse(ToolResult.failed("the world refused the creatures"))
        // Every creature born, the mounts and riders the game gave them included: what a module's act makes its own (M5 §4.4).
        val born = added.flatMap(::group).map { it.uuid }
        return ToolResult.ok(describe(ctx, args, id, player, added, expiresAt, name, hostile), listOf(player.ref()), born)
    }

    /**
     * One creature, ready to be added (not in the world yet): created, finalized as a command would (with
     * the mount or riders the game may give it: a spider jockey's skeleton, a drowned's zombie nautilus, a
     * strider's rider), reshaped, placed on free ground around [player] with them, equipped, named. Null
     * when there is no room.
     */
    private fun prepare(
        level: ServerLevel,
        type: EntityType<*>,
        player: ServerPlayer,
        args: SpawnArgs,
        name: String?,
        equipment: List<Pair<EquipmentSlot, ItemStack>>,
        creeperRadius: Int,
    ): Mob? {
        val mob = create(level, type, creeperRadius) ?: return null
        mob.snapTo(player.x, player.y, player.z, level.random.nextFloat() * 360f, 0f)
        // A zombie's group data: the baby form only when asked, and no chicken jockey (the game may take a
        // player's chicken for the mount). The other jockeys are the game's own creatures: they are kept.
        val groupData = if (mob is Zombie) Zombie.ZombieGroupData(args.baby, false) else null
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(player.blockPosition()), EntitySpawnReason.COMMAND, groupData)
        if (args.baby) mob.setBaby(true)
        for (change in args.attributes.orEmpty()) {
            val attribute = mob.getAttribute(attributeHolder(change.attribute.id))
            if (attribute == null) {
                dropRiders(level, mob)
                refuse(ToolResult.invalid("${EntityType.getKey(type)} has no ${change.attribute.id}; leave that change out"))
            }
            attribute.addPermanentModifier(modifier(change.attribute.id, change.multiply - 1.0))
        }
        val root = mob.rootVehicle
        if (args.height > 0 || args.launch > 0) {
            for (member in group(mob)) {
                (member as? LivingEntity)?.getAttribute(attributeHolder("fall_damage_multiplier"))?.addPermanentModifier(modifier("fall_damage_multiplier", -1.0))
            }
        }
        val riderHeight = group(mob).filter { it !== root }.sumOf { it.bbHeight.toDouble() }
        val spot = findSpot(level, root, riderHeight, player.position(), args.distance, args.height)
        if (spot == null) {
            dropRiders(level, mob)
            return null
        }
        root.snapTo(spot.x, spot.y, spot.z, mob.yRot, 0f)
        root.indirectPassengers.forEach { it.vehicle?.positionRider(it) }
        for ((slot, stack) in equipment) mob.setItemSlot(slot, stack.copy())
        // What it starts with never drops (no diamond sword farm), but its costume at the chance asked, never a precious
        // piece (gifts are rationed); what a player gives it later keeps the game's chances.
        EquipmentSlot.entries.filterNot { mob.getItemBySlot(it).isEmpty }.forEach { mob.setDropChance(it, 0f) }
        if (args.dropChance > 0) {
            equipment.filter { (_, stack) -> ItemSpecs.preciousness(stack.typeHolder()) == 0 }.forEach { (slot, _) -> mob.setDropChance(slot, args.dropChance.toFloat()) }
        }
        if (args.glowing) mob.setGlowingTag(true)
        mob.health = mob.maxHealth
        if (name != null) {
            mob.customName = Component.literal(name)
            mob.isCustomNameVisible = true
        }
        if (args.launch > 0) {
            root.setDeltaMovement(level.random.triangle(0.0, LAUNCH_SPREAD), args.launch, level.random.triangle(0.0, LAUNCH_SPREAD))
            root.needsSync = true
        }
        return mob
    }

    /**
     * A new creature of [type], not in the world. A creeper's blast is capped by the chaos level through
     * its own save data (`ExplosionRadius`, read like `/summon` reads it): a creeper explodes on its own.
     */
    private fun create(level: ServerLevel, type: EntityType<*>, creeperRadius: Int): Mob? {
        val entity = if (type === EntityTypes.CREEPER) {
            val data = CompoundTag().apply { putByte("ExplosionRadius", creeperRadius.toByte()) }
            EntityType.loadEntityRecursive(type, data, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP)
        } else {
            type.create(level, EntitySpawnReason.COMMAND)
        }
        return entity as? Mob
    }

    /**
     * A free spot for [root] (a creature, or the mount of a jockey), as big as it is now and with
     * [riderHeight] blocks of riders on top, around [center]: on the ground, between half [distance] and
     * [distance] away, then [height] blocks up as far as the space above is free. Water creatures appear
     * in water.
     */
    private fun findSpot(level: ServerLevel, root: Entity, riderHeight: Double, center: Vec3, distance: Int, height: Int): Vec3? {
        val aquatic = root.type.category in AQUATIC
        repeat(SPOT_TRIES) {
            val angle = level.random.nextDouble() * 2 * Math.PI
            val radius = distance * (0.5 + 0.5 * level.random.nextDouble())
            val x = floor(center.x + cos(angle) * radius).toInt()
            val z = floor(center.z + sin(angle) * radius).toInt()
            val ground = groundSpot(level, root, riderHeight, x, z, floor(center.y).toInt(), aquatic) ?: return@repeat
            var spot = ground
            val box = box(root, riderHeight, ground)
            for (up in 1..height) {
                if (!level.noCollision(root, box.move(0.0, up.toDouble(), 0.0))) break
                spot = ground.add(0.0, up.toDouble(), 0.0)
            }
            return spot
        }
        return null
    }

    private fun describe(
        ctx: ToolContext,
        args: SpawnArgs,
        id: String,
        player: ServerPlayer,
        mobs: List<Mob>,
        expiresAt: Long,
        name: String?,
        hostile: Boolean,
    ): String {
        val details = buildList {
            if (name != null) add("named \"$name\"")
            if (args.baby) add(if (mobs.all { it.isBaby }) "baby" else "no baby form for $id")
            args.attributes?.forEach { add("${it.attribute.id} x${number(it.multiply)}") }
            val slots = args.equipment?.slots().orEmpty()
            if (slots.isNotEmpty()) add("equipped (${slots.joinToString { (slot, worn) -> "${slot.serializedName} ${worn.shown()}" }})")
            if (args.glowing) add("glowing")
            if (args.dropChance > 0 && slots.isNotEmpty()) {
                val precious = slots.mapNotNull { (_, worn) -> ItemSpecs.item(worn.item)?.takeIf { ItemSpecs.preciousness(it) > 0 }?.let(::shortId) }
                add("each piece drops at ${(args.dropChance * 100).roundToInt()}% when a player kills one" + if (precious.isEmpty()) "" else " (never ${precious.joinToString()}: precious)")
            }
            if (args.target) add("going for ${player.shownName}")
            if (args.height > 0) add("up to ${args.height} blocks in the air")
            if (args.launch > 0) add("thrown up at ${number(args.launch)}")
            args.explode?.let { add("exploding after ${duration(it.afterSeconds * 1_000L)} (power ${number(min(it.power, ctx.guards.caps().explosionPower))}${if (it.hurts) "" else ", harmless"})") }
        }
        val fewer = if (mobs.size < args.count) " (only ${mobs.size} of ${args.count}: no more free ground)" else ""
        val extras = mobs.flatMap { mob -> group(mob).filter { it !== mob } }.groupingBy { it.type.toShortString() }.eachCount()
        val jockeys = if (extras.isEmpty()) "" else "; the game gave them mounts or riders, the Narrator's too: " +
            extras.entries.joinToString { (type, count) -> "$count minecraft:$type" }
        val positions = mobs.joinToString("; ") { "${it.blockX} ${it.blockY} ${it.blockZ}" }
        val punishment = if (hostile) "; a punishment of ${player.shownName}" else ""
        return "ok: ${mobs.size} $id summoned around ${player.shownName}$fewer, until ${ctx.at(expiresAt)} (in ${duration(args.lifetimeMinutes * 60_000L)})" +
            (if (details.isEmpty()) "" else ": ${details.joinToString(", ")}") + "; at $positions$jockeys$punishment"
    }

    companion object {
        /** The rule of the cap on the Narrator's creatures alive at once: `DENIED (mob_cap): …`. */
        const val MOB_CAP = "mob_cap"

        /** A creeper's own blast (radius 3), before the chaos cap. */
        private const val CREEPER_POWER = 3.0

        /** Random columns tried for each creature. */
        private const val SPOT_TRIES = 12

        /** Sideways spread of a launch, in blocks per tick. */
        private const val LAUNCH_SPREAD = 0.15

        /** Creatures that live in water: they appear in water. */
        private val AQUATIC = setOf(MobCategory.WATER_CREATURE, MobCategory.WATER_AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.AXOLOTLS)

        /** The creature called [raw]; refuses with the closest ids. */
        private fun entityType(raw: String): EntityType<*> =
            parseId(raw)?.let { BuiltInRegistries.ENTITY_TYPE.get(it).orElse(null)?.value() }
                ?: refuse(
                    ToolResult.invalid(
                        "unknown creature \"$raw\"; closest ids: ${closestIds(raw, BuiltInRegistries.ENTITY_TYPE.keySet().map(::shortId)).joinToString()}",
                    ),
                )

        /** The leather pieces, the only items a creature wears dyed ([WornItem.color]). */
        private val DYEABLE = setOf("minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots")

        private val COLOR = Regex("#?([0-9a-fA-F]{6})")

        /** A Minecraft account name, for a player_head's [WornItem.skin]. */
        private val ACCOUNT = Regex("[A-Za-z0-9_]{1,16}")

        /**
         * What a creature wears or holds, as [worn] describes it: the item, refused like a gift ([ItemSpecs.forbidden]: a
         * creature can drop or spit what it holds), with its dye, face, name and shimmer. A face is the account's name alone:
         * each client looks its skin up (26.3 resolves a player head's profile on the client, `PlayerSkinRenderCache`).
         * Any thread.
         */
        private fun equipmentItem(worn: WornItem): ItemStack {
            val item = ItemSpecs.item(worn.item) ?: refuse(ToolResult.invalid(ItemSpecs.unknownItem(worn.item)))
            val id = shortId(item)
            ItemSpecs.forbidden(item)?.let { refuse(ToolResult.denied(ItemSpecs.FORBIDDEN_ITEM, "$id is never given: $it.")) }
            val stack = ItemStack(item)
            worn.color?.let { raw ->
                if (item.registeredName !in DYEABLE) {
                    refuse(ToolResult.invalid("color dyes leather only (leather_helmet, leather_chestplate, leather_leggings, leather_boots), not $id"))
                }
                val rgb = COLOR.matchEntire(raw.trim())?.groupValues?.get(1)?.toInt(16)
                    ?: refuse(ToolResult.invalid("color is written #RRGGBB, e.g. #F9D71C for yellow (got \"$raw\")"))
                stack.set(DataComponents.DYED_COLOR, DyedItemColor(rgb))
            }
            worn.skin?.let { skin ->
                if (item.value() !== GameItems.PLAYER_HEAD) refuse(ToolResult.invalid("skin is for a player_head, not $id"))
                if (!ACCOUNT.matches(skin)) refuse(ToolResult.invalid("skin is a Minecraft account name: letters, digits and _ (got \"$skin\")"))
                stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(skin))
            }
            worn.name?.let { raw ->
                val name = Speech.clean(raw)
                if (name.isEmpty()) refuse(ToolResult.invalid("the name of the $id has nothing to show once formatting codes are removed; write plain text"))
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
            }
            if (worn.glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return stack
        }

        private fun attributeHolder(path: String) =
            BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(path)).orElseThrow { IllegalStateException("no attribute minecraft:$path") }

        /** A creature's own permanent modifier (saved with it, gone with it), multiplying the total by 1 + [amount]. */
        private fun modifier(path: String, amount: Double) =
            AttributeModifier(Identifier.fromNamespaceAndPath("narrator2", "spawn/$path"), amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

        /** [mob] with the mount and the riders `finalizeSpawn` gave it: its root vehicle and every passenger. */
        fun group(mob: Mob): List<Entity> = mob.rootVehicle.selfAndPassengers.toList()

        /**
         * Brings [mob] into the world with the mount and riders the game gave it (a spider jockey's
         * skeleton, a drowned's zombie nautilus, a strider's rider), all of them the
         * Narrator's creatures until [expiresAt], summoned for [player] (by [punishment], if any: its
         * [NarratorMob.punishment]). Each is marked, the creature last,
         * once it is equipped (T09c: the gear it holds then is its own); one the game already added to the
         * world (a jockey's mount made by `finalizeSpawn`) is registered at once, since its load event came
         * before its mark. Then [mob] enters with its riders. When the world refuses it, the mount and
         * riders are removed and the answer is false. Server thread.
         */
        fun enter(guards: Guards, level: ServerLevel, mob: Mob, expiresAt: Long, player: UUID, harmful: Boolean, punishment: String? = null): Boolean {
            for (member in group(mob).filter { it !== mob } + mob) {
                guards.mobs.mark(member, expiresAt, player, harmful, punishment)
                if (level.getEntity(member.uuid) === member) guards.mobs.onLoad(member)
            }
            if (level.tryAddFreshEntityWithPassengers(mob)) return true
            dropRiders(level, mob)
            return false
        }

        /** Makes [mob] stand alone: its riders get off, and a mount the game already added to the world is removed. Server thread. */
        private fun dropRiders(level: ServerLevel, mob: Mob) {
            val extras = group(mob).filter { it !== mob }
            mob.ejectPassengers()
            mob.stopRiding()
            extras.filter { level.getEntity(it.uuid) === it }.forEach { it.discard() }
        }

        /** The box of [entity] standing at [spot], with [riderHeight] blocks of riders on top. */
        private fun box(entity: Entity, riderHeight: Double, spot: Vec3): AABB =
            entity.getDimensions(entity.pose).makeBoundingBox(spot).expandTowards(0.0, riderHeight, 0.0)

        /**
         * A free spot for [entity] (with [riderHeight] blocks of riders) standing in column (x, z), from
         * [fromY] + 4 down to [fromY] - 10, in a loaded chunk only: room for its box, no liquid, a floor it
         * can stand on that does not hurt. In water for a water creature ([aquatic]).
         */
        private fun groundSpot(level: ServerLevel, entity: Entity, riderHeight: Double, x: Int, z: Int, fromY: Int, aquatic: Boolean): Vec3? {
            if (!level.isColumnLoaded(x, z)) return null
            val cursor = BlockPos.MutableBlockPos()
            for (y in fromY + 4 downTo fromY - 10) {
                if (!level.isInsideBuildHeight(y - 1)) continue
                cursor.set(x, y, z)
                val spot = Vec3.atBottomCenterOf(cursor)
                val box = box(entity, riderHeight, spot)
                if (!level.worldBorder.isWithinBounds(box) || !level.noCollision(entity, box)) continue
                if (aquatic) {
                    if (level.getFluidState(cursor).`is`(FluidTags.WATER)) return spot
                    continue
                }
                if (level.containsAnyLiquid(box)) continue
                val below = cursor.below()
                val floor = level.getBlockState(below)
                if (floor.getCollisionShape(level, below).isEmpty || entity.type.isBlockDangerous(floor)) continue
                return spot
            }
            return null
        }
    }
}

/**
 * The creatures of `spawn` that explode ([ExplodeSpec]), on the server thread. When one's time comes, it
 * vanishes in one of the Narrator's explosions, capped by the chaos level in force then. It goes like any
 * of the Narrator's creatures (`NarratorMobs.retire`): its riders are set down first, and what players
 * entrusted to it comes back. A hurting blast is hostile against each player near it, checked at that
 * moment for the turn that summoned it: if one of them may not be hurt (dead a moment ago, punished by
 * another turn…), the blast is harmless.
 *
 * In memory only: after a restart, a creature that had not exploded yet stays until its lifetime ends. A
 * creature killed, unloaded or expired before its time never explodes.
 */
internal object Fuses {
    private val log = Log.tool

    private class Fuse(val services: NarratorServices, val caller: Caller, val mob: Mob, val dueTick: Int, val power: Double, val hurts: Boolean)

    /** Server thread only, like everything in the world. */
    private val pending = mutableListOf<Fuse>()
    private var ticking = false

    /** The [index]th creature of a call explodes as [spec] says, a few ticks after the previous one. Server thread. */
    fun light(services: NarratorServices, caller: Caller, mob: Mob, spec: ExplodeSpec, index: Int) {
        if (!ticking) {
            // Registered on first use, once for the JVM: the fuses need a tick only when there are some.
            ticking = true
            ServerTickEvents.END_SERVER_TICK.register { server -> tick(server) }
        }
        val server = services.server
        pending += Fuse(services, caller, mob, server.tickCount + spec.afterSeconds * TICKS_PER_SECOND + index * STAGGER_TICKS, spec.power, spec.hurts)
    }

    private fun tick(server: MinecraftServer) {
        if (pending.isEmpty()) return
        // Gone before its time (killed, unloaded, expired), or left over from a stopped server.
        pending.removeIf { it.mob.isRemoved || it.services.phase != Phase.RUNNING || it.services.server !== server }
        val due = pending.filter { server.tickCount >= it.dueTick }
        pending.removeAll(due)
        for (fuse in due) {
            try {
                detonate(fuse)
            } catch (e: Exception) {
                log.error("a creature of the Narrator failed to explode: ${fuse.mob.type.toShortString()}", e)
            }
        }
    }

    private fun detonate(fuse: Fuse) {
        val mob = fuse.mob
        if (mob.isRemoved || !mob.isAlive) return
        val level = mob.level() as ServerLevel
        val guards = fuse.services.guards
        val center = mob.position().add(0.0, mob.bbHeight / 2.0, 0.0)
        val power = min(fuse.power, guards.caps().explosionPower)
        guards.mobs.retire(mob, fuse.services.clock.millis())
        if (power <= 0.0) {
            level.sendParticles(ParticleTypes.POOF, center.x, center.y, center.z, 20, 0.3, 0.3, 0.3, 0.05)
            return
        }
        var hurts = fuse.hurts
        if (hurts) {
            val until = fuse.services.clock.millis() + NarratorExplosionDamage.GUARD_SECONDS * 1_000L
            val verdict = NarratorExplosionDamage.allow(NarratorExplosionDamage.bystanders(level, center), guards, fuse.caller, until, PunishmentCategory.EXPLOSION)
            if (verdict is Verdict.Denied) {
                hurts = false
                log.info("a ${mob.type.toShortString()} of the Narrator explodes harmlessly: ${verdict.rule}: ${verdict.message}")
            }
        }
        NarratorExplosionDamage.explode(level, center, power, hurts)
    }

    private const val TICKS_PER_SECOND = 20
    private const val STAGGER_TICKS = 4
}

// ── lightning ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class LightningArgs(
    @Doc(PLAYER_DOC) @MaxLen(16) val player: String? = null,
    @Doc(COORDINATE_DOC) val x: Int? = null,
    @Doc(COORDINATE_DOC) val y: Int? = null,
    @Doc(COORDINATE_DOC) val z: Int? = null,
    @Doc(DIMENSION_DOC) @MaxLen(40) val dimension: String? = null,
    @Doc("Strike this many blocks away from the player, in a random direction; 0 strikes them.")
    @Range(0.0, 16.0)
    val offset: Int = 0,
    @Doc("Hearts of damage to the player struck; 0 is a harmless warning. Only on a player struck directly (offset 0).")
    @SerialName("damage_hearts")
    @Range(0.0, 5.0)
    val damageHearts: Double = 0.0,
) {
    init {
        requireOneTarget(player, x, y, z, dimension)
        require(damageHearts == 0.0 || (player != null && offset == 0)) { "damage_hearts needs a player struck directly (offset 0)" }
    }
}

class LightningTool : NarratorTool<LightningArgs>(
    name = "lightning",
    description = "Strikes a bolt of lightning on or near a player, or at coordinates: thunder and flash, and no fire. It hurts " +
        "only when asked, only the player struck directly, and that is a punishment of that player.",
    args = LightningArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /** A bolt that hurts: not at chaos 0, and no more damage than the chaos level allows. */
    override suspend fun check(ctx: ToolContext, args: LightningArgs): ToolResult? {
        if (args.damageHearts <= 0) return null
        val caps = ctx.guards.caps()
        if (!caps.hostileAllowed) return ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: no hostile action against any player.")
        if (args.damageHearts <= caps.lightningHearts) return null
        return ToolResult.invalid("at chaos ${ctx.chaos} lightning does at most ${number(caps.lightningHearts)} hearts of damage (asked ${number(args.damageHearts)})")
    }

    override suspend fun run(ctx: ToolContext, args: LightningArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val named = args.player?.let { ctx.online(it) }
        ctx.onServer { server -> answer { strike(ctx, server, named, args) } }
    }

    private fun strike(ctx: ToolContext, server: MinecraftServer, named: PlayerRef?, args: LightningArgs): ToolResult {
        val target = server.target(named, args.x, args.y, args.z, args.dimension)
        val level = target.level
        val ground = if (args.offset == 0) target.pos else groundNear(level, target.pos, args.offset)
        val boltY = boltHeight(level, ground)
            ?: refuse(ToolResult.failed("copper or a lightning rod everywhere above ${target.label}: a bolt would change them; strike elsewhere"))
        val victim = target.player?.takeIf { args.damageHearts > 0 }
        if (victim != null) {
            ctx.punish(victim, ctx.clock.millis() + NarratorExplosionDamage.GUARD_SECONDS * 1_000L, PunishmentCategory.LIGHTNING)
            ctx.guards.armDeathGuard(victim.uuid, NarratorExplosionDamage.GUARD_SECONDS)
        }
        val bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED) ?: refuse(ToolResult.failed("the world refused the bolt"))
        bolt.snapTo(ground.x, boltY, ground.z)
        bolt.setVisualOnly(true)
        if (!level.addFreshEntity(bolt)) refuse(ToolResult.failed("the world refused the bolt"))
        val where = target.where(args.offset)
        if (victim == null) return ToolResult.ok("ok: lightning struck $where, harmless", listOfNotNull(target.player?.ref()))
        val before = victim.health
        victim.hurtServer(level, level.damageSources().lightningBolt(), (args.damageHearts * 2).toFloat())
        val after = if (victim.isDeadOrDying) "dead" else number(victim.health.toDouble())
        return ToolResult.ok("ok: lightning struck $where for ${number(args.damageHearts)} hearts (health ${number(before.toDouble())} → $after)", listOf(victim.ref()))
    }

    companion object {
        /** Blocks a bolt may be raised to avoid copper or a lightning rod under it. */
        private const val MAX_BOLT_LIFT = 4

        /**
         * The height of a bolt at [ground]: just above it, raised a block at a time while the block it would
         * strike is copper or a lightning rod. Even a visual bolt powers a lightning rod (redstone of the
         * players' builds) and scrapes the oxidation and the wax off copper for good (read in the bytecode of
         * `LightningBolt.tick`: only its fire and its hits on entities depend on `visualOnly`). Null when it
         * cannot be avoided, or the chunk is not loaded.
         */
        private fun boltHeight(level: ServerLevel, ground: Vec3): Double? {
            for (lift in 0..MAX_BOLT_LIFT) {
                val y = ground.y + lift + BOLT_RAISE
                val struck = BlockPos.containing(ground.x, y - 1.0E-6, ground.z)
                if (!level.isColumnLoaded(struck.x, struck.z)) return null
                if (!changedByLightning(level.getBlockState(struck).block)) return y
            }
            return null
        }

        private fun changedByLightning(block: Block): Boolean =
            block is LightningRodBlock || block is WeatheringCopper || HoneycombItem.WAX_OFF_BY_BLOCK.get().containsKey(block)

        /** A bolt stands this much above the ground it strikes, so that it strikes the block of the feet, not the floor. */
        private const val BOLT_RAISE = 0.01
    }
}

// ── explosion ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class ExplosionArgs(
    @Doc(PLAYER_DOC) @MaxLen(16) val player: String? = null,
    @Doc(COORDINATE_DOC) val x: Int? = null,
    @Doc(COORDINATE_DOC) val y: Int? = null,
    @Doc(COORDINATE_DOC) val z: Int? = null,
    @Doc(DIMENSION_DOC) @MaxLen(40) val dimension: String? = null,
    @Doc("Explosion power: 1 a firecracker, 3 a creeper, 4 a TNT. It hurts within about twice this many blocks.")
    @Range(0.5, 4.0)
    val power: Double,
    @Doc("Blocks away from the player, in a random direction; 0 is on them.")
    @Range(0.0, 16.0)
    val offset: Int = 3,
    @Doc("false: a show of fire and noise that hurts and pushes nobody.")
    val hurts: Boolean = true,
) {
    init {
        requireOneTarget(player, x, y, z, dimension)
    }
}

class ExplosionTool : NarratorTool<ExplosionArgs>(
    name = "explosion",
    description = "Sets off an explosion near a player or at coordinates. It never breaks a block nor starts a fire, and spares " +
        "items, animals and pets: it hurts and pushes players and monsters only. A hurting explosion close to players is a " +
        "punishment of each of them; a harmless one (hurts false) is a pure show. The strongest power depends on the chaos level.",
    args = ExplosionArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /** Not at chaos 0, even a harmless one; no stronger than the chaos level allows. */
    override suspend fun check(ctx: ToolContext, args: ExplosionArgs): ToolResult? {
        val caps = ctx.guards.caps()
        if (caps.explosionPower <= 0.0) return ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: no explosion at all.")
        if (args.power <= caps.explosionPower) return null
        return ToolResult.invalid("at chaos ${ctx.chaos} an explosion has a power of at most ${number(caps.explosionPower)} (asked ${number(args.power)})")
    }

    override suspend fun run(ctx: ToolContext, args: ExplosionArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val named = args.player?.let { ctx.online(it) }
        ctx.onServer { server -> answer { blast(ctx, server, named, args) } }
    }

    private fun blast(ctx: ToolContext, server: MinecraftServer, named: PlayerRef?, args: ExplosionArgs): ToolResult {
        val target = server.target(named, args.x, args.y, args.z, args.dimension)
        val level = target.level
        val center = (if (target.player != null && args.offset > 0) aside(level, target.pos, args.offset) else target.pos).add(0.0, 0.5, 0.0)
        if (args.hurts) {
            val bystanders = NarratorExplosionDamage.bystanders(level, center)
            val until = ctx.clock.millis() + NarratorExplosionDamage.GUARD_SECONDS * 1_000L
            val verdict = NarratorExplosionDamage.allow(bystanders, ctx.guards, ctx.caller, until, PunishmentCategory.EXPLOSION)
            if (verdict is Verdict.Denied) refuse(ToolResult.denied(verdict))
        }
        val reach = args.power * 2 + 1
        val around = level.players().filter { it.position().distanceTo(center) <= reach }
        val before = around.associateWith { it.health }
        NarratorExplosionDamage.explode(level, center, args.power, args.hurts)
        val where = if (target.player != null && args.offset == 0) "on ${target.label}" else target.where(args.offset)
        val at = if (target.player == null) "" else " (at ${floor(center.x).toInt()} ${floor(center.y).toInt()} ${floor(center.z).toInt()})"
        val effects = if (!args.hurts) {
            "harmless"
        } else {
            val hurt = around.filter { (before[it] ?: 0f) > it.health }
            if (hurt.isEmpty()) {
                "nobody hurt"
            } else {
                "hurt: " + hurt.joinToString { "${it.shownName} ${number(before.getValue(it).toDouble())} → ${if (it.isDeadOrDying) "dead" else number(it.health.toDouble())}" }
            }
        }
        return ToolResult.ok("ok: explosion of power ${number(args.power)}, $where$at: $effects; no block touched", around.map { it.ref() })
    }
}

// ── fireworks ───────────────────────────────────────────────────────────────────────────────────

@Serializable
enum class FireworkShape {
    @SerialName("small_ball") SMALL_BALL,
    @SerialName("large_ball") LARGE_BALL,
    @SerialName("star") STAR,
    @SerialName("creeper") CREEPER,
    @SerialName("burst") BURST,
}

@Serializable
data class FireworksArgs(
    @Doc(PLAYER_DOC) @MaxLen(16) val player: String? = null,
    @Doc(COORDINATE_DOC) val x: Int? = null,
    @Doc(COORDINATE_DOC) val y: Int? = null,
    @Doc(COORDINATE_DOC) val z: Int? = null,
    @Doc(DIMENSION_DOC) @MaxLen(40) val dimension: String? = null,
    @Doc("How many rockets.")
    @Range(1.0, 8.0)
    val count: Int = 3,
    @Doc("Shape of the burst.")
    val shape: FireworkShape = FireworkShape.LARGE_BALL,
    @Doc("Colors: dye names (red, orange, yellow, lime, green, cyan, light_blue, blue, purple, magenta, pink, white, light_gray, gray, black, brown) or #rrggbb.")
    @Items(1, 4)
    @MaxLen(16)
    val colors: List<String>? = null,
    @Doc("A trail behind the sparks.")
    val trail: Boolean = false,
    @Doc("The sparks crackle and twinkle.")
    val twinkle: Boolean = false,
) {
    init {
        requireOneTarget(player, x, y, z, dimension)
    }
}

class FireworksTool : NarratorTool<FireworksArgs>(
    name = "fireworks",
    description = "Launches firework rockets around a player or at coordinates, for a celebration or a mock one. They go up from " +
        "open ground away from the players and burst high, where they hurt nobody.",
    args = FireworksArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    /** Colors that exist. */
    override suspend fun check(ctx: ToolContext, args: FireworksArgs): ToolResult? = dryCheck { args.colors?.forEach { color(it) } }

    override suspend fun run(ctx: ToolContext, args: FireworksArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val named = args.player?.let { ctx.online(it) }
        ctx.onServer { server -> answer { launch(server, named, args) } }
    }

    private fun launch(server: MinecraftServer, named: PlayerRef?, args: FireworksArgs): ToolResult {
        val colors = args.colors?.map { color(it) } ?: DEFAULT_COLORS
        val target = server.target(named, args.x, args.y, args.z, args.dimension)
        val level = target.level
        val players = level.players().filter { !it.isSpectator }
        val (nearest, farthest) = if (target.player != null) RING_AROUND_PLAYER else RING_AT_COORDINATES
        val spots = mutableListOf<Vec3>()
        repeat(args.count * TRIES_PER_ROCKET) {
            if (spots.size == args.count) return@repeat
            val angle = level.random.nextDouble() * 2 * Math.PI
            val radius = nearest + level.random.nextDouble() * (farthest - nearest)
            val x = floor(target.pos.x + cos(angle) * radius).toInt()
            val z = floor(target.pos.z + sin(angle) * radius).toInt()
            val spot = launchPad(level, x, z, target.pos.y) ?: return@repeat
            if (players.all { horizontal(it.position(), spot) >= CLEARANCE } && clearAbove(level, spot)) spots += spot
        }
        if (spots.isEmpty()) {
            refuse(ToolResult.failed("no open ground at least $CLEARANCE blocks from every player around ${target.label}; try elsewhere"))
        }
        val shape = when (args.shape) {
            FireworkShape.SMALL_BALL -> FireworkExplosion.Shape.SMALL_BALL
            FireworkShape.LARGE_BALL -> FireworkExplosion.Shape.LARGE_BALL
            FireworkShape.STAR -> FireworkExplosion.Shape.STAR
            FireworkShape.CREEPER -> FireworkExplosion.Shape.CREEPER
            FireworkShape.BURST -> FireworkExplosion.Shape.BURST
        }
        val burst = FireworkExplosion(shape, IntArrayList(colors), IntArrayList(), args.trail, args.twinkle)
        for (spot in spots) {
            // Flight 1 or 2: bursts 9 to 35 blocks above the pad, never lower (FireworkRocketEntity's lifetime, bytecode).
            val stack = ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET)
            stack.set(DataComponents.FIREWORKS, Fireworks(1 + level.random.nextInt(2), listOf(burst)))
            val rocket = FireworkRocketEntity(level, spot.x, spot.y, spot.z, stack)
            rocket.addTag(NarratorMobs.TAG) // a death by the burst is the Narrator's (T09a)
            level.addFreshEntity(rocket)
        }
        val fewer = if (spots.size < args.count) " (only ${spots.size} of ${args.count}: no more open ground away from the players)" else ""
        val below = target.player?.let { player -> spots.maxOf { it.y } - player.y }?.takeIf { it > UNDERGROUND_BLOCKS }
            ?.let { "; ${target.label} is ${it.toInt()} blocks below the pads and may not see them" } ?: ""
        val look = "${args.shape.name.lowercase()}; ${args.colors?.joinToString() ?: "gold, white"}"
        return ToolResult.ok("ok: ${spots.size} fireworks ($look) launched around ${target.label}$fewer$below", listOfNotNull(target.player?.ref()))
    }

    companion object {
        /** Blocks a rocket goes up from every player, horizontally: it bursts farther than its 5-block reach. */
        const val CLEARANCE = 6

        private val RING_AROUND_PLAYER = 6.0 to 12.0
        private val RING_AT_COORDINATES = 0.0 to 4.0
        private const val TRIES_PER_ROCKET = 8

        /** Pads this far above the player: they are underground and may not see the show. */
        private const val UNDERGROUND_BLOCKS = 12.0

        /** Gold and white, the Narrator's colors. */
        private val DEFAULT_COLORS = listOf(0xFFAA00, 0xFFFFFF)

        private val HEX = Regex("#[0-9a-f]{6}")

        /** A color: `#rrggbb`, or a dye name and its firework color. */
        private fun color(raw: String): Int {
            val value = raw.trim().lowercase()
            if (HEX.matches(value)) return value.substring(1).toInt(16)
            val dye = DyeColor.entries.firstOrNull { it.serializedName == value }
                ?: refuse(ToolResult.invalid("unknown color \"$raw\"; use #rrggbb or a dye name: ${DyeColor.entries.joinToString { it.serializedName }}"))
            return dye.fireworkColor
        }

        /**
         * Where a rocket goes up in column (x, z): on the highest block, with open sky above it; in a
         * dimension with a ceiling (the Nether) or over an empty column, at [fromY]. Null when the chunk is
         * not loaded.
         */
        private fun launchPad(level: ServerLevel, x: Int, z: Int, fromY: Double): Vec3? {
            if (!level.isColumnLoaded(x, z)) return null
            val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            val y = if (level.dimensionType().hasCeiling() || surface <= level.minY) fromY else surface.toDouble()
            return Vec3(x + 0.5, y, z + 0.5)
        }

        private fun horizontal(a: Vec3, b: Vec3): Double = Math.hypot(a.x - b.x, a.z - b.z)

        /** No creature in the rocket's way up: a rocket that hits one bursts right there, next to it. */
        private fun clearAbove(level: ServerLevel, pad: Vec3): Boolean =
            level.getEntitiesOfClass(LivingEntity::class.java, AABB(pad.x - 1, pad.y, pad.z - 1, pad.x + 1, pad.y + ROCKET_RISE, pad.z + 1)).isEmpty()

        /** The highest a rocket goes before it bursts (flight 2). */
        private const val ROCKET_RISE = 36.0
    }
}

// ── particles ───────────────────────────────────────────────────────────────────────────────────

/** The particles the Narrator may show: simple ones (no options), by registry path. */
@Serializable
enum class ParticleKey(val id: String) {
    @SerialName("heart") HEART("heart"),
    @SerialName("angry_villager") ANGRY_VILLAGER("angry_villager"),
    @SerialName("happy_villager") HAPPY_VILLAGER("happy_villager"),
    @SerialName("flame") FLAME("flame"),
    @SerialName("soul_fire_flame") SOUL_FIRE_FLAME("soul_fire_flame"),
    @SerialName("smoke") SMOKE("smoke"),
    @SerialName("large_smoke") LARGE_SMOKE("large_smoke"),
    @SerialName("campfire_signal_smoke") CAMPFIRE_SIGNAL_SMOKE("campfire_signal_smoke"),
    @SerialName("cloud") CLOUD("cloud"),
    @SerialName("poof") POOF("poof"),
    @SerialName("explosion") EXPLOSION("explosion"),
    @SerialName("explosion_emitter") EXPLOSION_EMITTER("explosion_emitter"),
    @SerialName("firework") FIREWORK("firework"),
    @SerialName("totem_of_undying") TOTEM_OF_UNDYING("totem_of_undying"),
    @SerialName("enchant") ENCHANT("enchant"),
    @SerialName("enchanted_hit") ENCHANTED_HIT("enchanted_hit"),
    @SerialName("crit") CRIT("crit"),
    @SerialName("witch") WITCH("witch"),
    @SerialName("portal") PORTAL("portal"),
    @SerialName("reverse_portal") REVERSE_PORTAL("reverse_portal"),
    @SerialName("end_rod") END_ROD("end_rod"),
    @SerialName("lava") LAVA("lava"),
    @SerialName("soul") SOUL("soul"),
    @SerialName("sculk_soul") SCULK_SOUL("sculk_soul"),
    @SerialName("glow") GLOW("glow"),
    @SerialName("electric_spark") ELECTRIC_SPARK("electric_spark"),
    @SerialName("note") NOTE("note"),
    @SerialName("snowflake") SNOWFLAKE("snowflake"),
    @SerialName("ash") ASH("ash"),
    @SerialName("white_ash") WHITE_ASH("white_ash"),
    @SerialName("crimson_spore") CRIMSON_SPORE("crimson_spore"),
    @SerialName("warped_spore") WARPED_SPORE("warped_spore"),
    @SerialName("cherry_leaves") CHERRY_LEAVES("cherry_leaves"),
    @SerialName("squid_ink") SQUID_INK("squid_ink"),
    @SerialName("sonic_boom") SONIC_BOOM("sonic_boom"),
    @SerialName("gust") GUST("gust"),
    @SerialName("dust_plume") DUST_PLUME("dust_plume"),
    @SerialName("trial_omen") TRIAL_OMEN("trial_omen"),
    @SerialName("raid_omen") RAID_OMEN("raid_omen"),
    @SerialName("elder_guardian") ELDER_GUARDIAN("elder_guardian"),
    @SerialName("sneeze") SNEEZE("sneeze"),
    @SerialName("damage_indicator") DAMAGE_INDICATOR("damage_indicator"),
    @SerialName("firefly") FIREFLY("firefly"),
    ;

    /** The game's particle type; fails for an id the game does not have (checked by an in-game test). */
    fun type(): SimpleParticleType =
        BuiltInRegistries.PARTICLE_TYPE.get(Identifier.withDefaultNamespace(id)).orElseThrow { IllegalStateException("no particle $id") }
            .value() as SimpleParticleType
}

@Serializable
data class ParticlesArgs(
    @Doc(PLAYER_DOC) @MaxLen(16) val player: String? = null,
    @Doc(COORDINATE_DOC) val x: Int? = null,
    @Doc(COORDINATE_DOC) val y: Int? = null,
    @Doc(COORDINATE_DOC) val z: Int? = null,
    @Doc(DIMENSION_DOC) @MaxLen(40) val dimension: String? = null,
    @Doc("The particle. elder_guardian shows the elder guardian's face to the players near, with its sound.")
    val particle: ParticleKey,
    @Doc("How many particles.")
    @Range(1.0, 200.0)
    val count: Int = 30,
    @Doc("How far they spread around the point, in blocks.")
    @Range(0.0, 5.0)
    val spread: Double = 1.0,
) {
    init {
        requireOneTarget(player, x, y, z, dimension)
    }
}

class ParticlesTool : NarratorTool<ParticlesArgs>(
    name = "particles",
    description = "Shows particles around a player or at coordinates: hearts, angry clouds, flames, souls, sparks, portal " +
        "swirls, totem confetti… Seen by the players within 32 blocks.",
    args = ParticlesArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: ParticlesArgs): ToolResult = answer {
        val named = args.player?.let { ctx.online(it) }
        ctx.onServer { server ->
            answer {
                val target = server.target(named, args.x, args.y, args.z, args.dimension)
                val at = target.pos.add(0.0, if (target.player != null) PLAYER_MIDDLE else 0.5, 0.0)
                val seen = target.level.sendParticles(args.particle.type(), at.x, at.y, at.z, args.count, args.spread, args.spread, args.spread, SPEED)
                val who = if (seen == 0) "nobody is within 32 blocks to see them" else "seen by $seen players"
                ToolResult.ok("ok: ${args.count} ${args.particle.id} particles around ${target.label}; $who", listOfNotNull(target.player?.ref()))
            }
        }
    }

    private companion object {
        /** Around a player's chest. */
        const val PLAYER_MIDDLE = 1.0
        const val SPEED = 0.02
    }
}

// ── set_weather, set_time ───────────────────────────────────────────────────────────────────────

/**
 * The weather the Narrator may set. No thunderstorm: its bolts are real ones, which start fires, hurt, and
 * turn horses into skeleton traps, outside every rule; a storm is rain and the `lightning` tool.
 */
@Serializable
enum class WeatherKind {
    @SerialName("clear") CLEAR,
    @SerialName("rain") RAIN,
}

@Serializable
data class SetWeatherArgs(
    @Doc("The weather, everywhere: clear, or rain (snow where it is cold).") val weather: WeatherKind,
    @Doc("How long it lasts, in minutes; the usual weather comes back after.") @Range(1.0, 30.0) val minutes: Int = 10,
)

class SetWeatherTool(private val limits: WorldLimits) : NarratorTool<SetWeatherArgs>(
    name = "set_weather",
    description = "Changes the weather of the whole server for a while: clear or rain. There is no thunderstorm: for one, " +
        "bring the rain and strike with the lightning tool, whose bolts start no fire. One change every 10 minutes.",
    args = SetWeatherArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: SetWeatherArgs): ToolResult = answer {
        limits.weather.check()?.let { refuse(ToolResult.denied(WorldLimits.WEATHER_LIMIT, "the weather ${it}")) }
        val ticks = args.minutes * 60 * 20
        ctx.onServer { server ->
            when (args.weather) {
                WeatherKind.CLEAR -> server.setWeatherParameters(ticks, 0, false, false)
                WeatherKind.RAIN -> server.setWeatherParameters(0, ticks, true, false)
            }
        }
        limits.weather.record()
        val until = ctx.clock.millis() + args.minutes * 60_000L
        ToolResult.ok("ok: ${args.weather.name.lowercase()} for ${duration(args.minutes * 60_000L)} (until ${ctx.at(until)})")
    }
}

@Serializable
enum class TimeOfDay {
    @SerialName("day") DAY,
    @SerialName("noon") NOON,
    @SerialName("night") NIGHT,
    @SerialName("midnight") MIDNIGHT,
}

@Serializable
data class SetTimeArgs(
    @Doc("The time to jump to: day (morning), noon, night (dusk) or midnight.") val time: TimeOfDay,
)

class SetTimeTool(private val limits: WorldLimits) : NarratorTool<SetTimeArgs>(
    name = "set_time",
    description = "Jumps the time of day of the whole server forward to the next morning, noon, dusk or midnight. Night brings " +
        "the monsters out. One change every 20 minutes.",
    args = SetTimeArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun run(ctx: ToolContext, args: SetTimeArgs): ToolResult = answer {
        limits.time.check()?.let { refuse(ToolResult.denied(WorldLimits.TIME_LIMIT, "the time ${it}")) }
        val name = args.time.name.lowercase()
        val moved = ctx.onServer { server ->
            val clock = server.overworld().dimensionType().defaultClock().orElse(null) ?: refuse(ToolResult.failed("the overworld has no clock"))
            val marker = when (args.time) {
                TimeOfDay.DAY -> ClockTimeMarkers.DAY
                TimeOfDay.NOON -> ClockTimeMarkers.NOON
                TimeOfDay.NIGHT -> ClockTimeMarkers.NIGHT
                TimeOfDay.MIDNIGHT -> ClockTimeMarkers.MIDNIGHT
            }
            server.clockManager().moveToTimeMarker(clock, marker)
        }
        when (moved) {
            ServerClockManager.MoveResult.MOVED -> {
                limits.time.record()
                ToolResult.ok("ok: it is now $name")
            }
            ServerClockManager.MoveResult.NOT_MOVED -> ToolResult.ok("ok: it is already $name; nothing changed")
            else -> ToolResult.failed("the overworld's clock has no $name")
        }
    }
}

/**
 * The limits of the world tools that act on the whole server: one weather change every 10 minutes, one
 * time change every 20. Counted once the change is made. The server's own is `ToolLimits.world`, so a new
 * chapter does not reset them; in memory only.
 */
class WorldLimits(clock: Clock) {
    val weather = Cooldown(clock, WEATHER_COOLDOWN_MS)
    val time = Cooldown(clock, TIME_COOLDOWN_MS)

    /** At most one change every [ms]. Any thread. */
    class Cooldown(private val clock: Clock, private val ms: Long) {
        private var last: Long? = null

        /** Null when a change is allowed now; else why not (`changed 4m ago; …`). */
        @Synchronized
        fun check(): String? {
            val last = last ?: return null
            val ago = clock.millis() - last
            if (ago >= ms) return null
            return "changed ${ToolText.duration(ago)} ago; the next change is possible in ${ToolText.wait(ms - ago)} (one every ${ToolText.duration(ms)})"
        }

        @Synchronized
        fun record() {
            last = clock.millis()
        }
    }

    companion object {
        /** `DENIED (weather_limit): …` */
        const val WEATHER_LIMIT = "weather_limit"

        /** `DENIED (time_limit): …` */
        const val TIME_LIMIT = "time_limit"

        const val WEATHER_COOLDOWN_MS = 10 * 60_000L
        const val TIME_COOLDOWN_MS = 20 * 60_000L
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────

/** A world tool takes a player, or x, y and z (and maybe their dimension). */
private fun requireOneTarget(player: String?, x: Int?, y: Int?, z: Int?, dimension: String?) {
    val coordinates = listOf(x, y, z).count { it != null }
    require((player == null && coordinates == 3) || (player != null && coordinates == 0)) { "give either player, or x, y and z" }
    require(player == null || dimension == null) { "dimension goes with x, y and z; a player's is their own" }
}

/** Where a world effect happens: at [pos] in [level], which is [player]'s position when a player was named. */
private class Target(val level: ServerLevel, val pos: Vec3, val player: ServerPlayer?, val label: String) {
    /** Where it happened, for a result: `Bob`, `3 blocks from Bob`, or `at x 12, y 64, z -3`. */
    fun where(offset: Int): String = when {
        player == null -> "at $label"
        offset > 0 -> "$offset blocks from $label"
        else -> label
    }
}

/** The target of a call: [named]'s position, or x, y, z in [dimension]; refuses when it is not loaded. Server thread. */
private fun MinecraftServer.target(named: PlayerRef?, x: Int?, y: Int?, z: Int?, dimension: String?): Target {
    if (named != null) {
        val player = live(named)
        return Target(player.level(), player.position(), player, player.shownName)
    }
    val level = dimensionLevel(dimension)
    val pos = BlockPos(x!!, y!!, z!!)
    val label = "x $x, y $y, z $z" + if (level === overworld()) "" else " in ${ToolText.dimension(level.dimension().identifier().toString())}"
    if (!level.isInsideBuildHeight(pos.y) || !level.isColumnLoaded(pos.x, pos.z)) refuse(ToolResult.failed("$label is not loaded: no player is near enough to see anything"))
    return Target(level, Vec3.atBottomCenterOf(pos), null, label)
}

/**
 * The level of [dimension] in any of the forms the model writes (`the_nether`, `nether`,
 * `minecraft:the_end`: [ToolText.dimensionId]), the overworld when null; refuses an unknown one.
 */
private fun MinecraftServer.dimensionLevel(dimension: String?): ServerLevel {
    if (dimension == null) return overworld()
    val level = Identifier.tryParse(ToolText.dimensionId(dimension))?.let { getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
    return level ?: refuse(
        ToolResult.invalid("unknown dimension \"$dimension\"; dimensions: ${levelKeys().joinToString { ToolText.dimension(it.identifier().toString()) }}"),
    )
}

/** A point [distance] blocks from [from], in a random direction, at the same height. */
private fun aside(level: ServerLevel, from: Vec3, distance: Int): Vec3 {
    val angle = level.random.nextDouble() * 2 * Math.PI
    return Vec3(from.x + cos(angle) * distance, from.y, from.z + sin(angle) * distance)
}

/**
 * A point on the ground [distance] blocks from [from] in a random direction: the top of the first block
 * with a collision below [from]'s height + 2, 12 blocks down at most; [from]'s height when there is none.
 */
private fun groundNear(level: ServerLevel, from: Vec3, distance: Int): Vec3 {
    val point = aside(level, from, distance)
    val cursor = BlockPos.MutableBlockPos()
    val x = floor(point.x).toInt()
    val z = floor(point.z).toInt()
    if (!level.isColumnLoaded(x, z)) return point
    val top = floor(from.y).toInt() + 2
    for (y in top downTo top - 14) {
        cursor.set(x, y - 1, z)
        if (!level.isInsideBuildHeight(y - 1)) break
        val below = level.getBlockState(cursor)
        if (!below.getCollisionShape(level, cursor).isEmpty && level.getBlockState(cursor.above()).getCollisionShape(level, cursor.above()).isEmpty) {
            return Vec3(point.x, y.toDouble(), point.z)
        }
    }
    return point
}

/** Whether the chunk of column ([x], [z]) is loaded; never loads it (`ORACLE_ACTIONS_API.md` §2.2). Server thread. */
private fun ServerLevel.isColumnLoaded(x: Int, z: Int): Boolean =
    chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) != null
