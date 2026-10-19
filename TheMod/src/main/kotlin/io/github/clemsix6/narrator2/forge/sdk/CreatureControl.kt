package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.Creature
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.guard.CreatureShield
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.rule.MarkOwner
import io.github.clemsix6.narrator2.mechanics.rule.MobAttributeArgs
import io.github.clemsix6.narrator2.mechanics.rule.MobAttributeTool
import io.github.clemsix6.narrator2.mechanics.rule.MobEffectArgs
import io.github.clemsix6.narrator2.mechanics.rule.MobEffectTool
import io.github.clemsix6.narrator2.mechanics.rule.RuleMarks
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Refusal
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.TraceableEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Team
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The creatures of one deployment (M5 §4.5): those its `spawn` acts summon ([adopt]) and those it [claim]s, at most
 * [ForgeLimits.OWN_CREATURES] together (the summonings on their way counted, [reserve]). While they are its own they
 * are the Narrator's ([ModuleCreatures]: they push nobody, aim at none but the player an act sent them to, never kill a
 * bystander), take no fall damage, and are steered at once by their handles ([Creature]); when the deployment ends
 * ([releaseAll]) a summoned one vanishes and a claimed one gets back to normal, back where it was claimed.
 *
 * Server thread only, from the module's own calls ([ActQueue.invocation]) and from the engine (task M5-11): [adopt]
 * (through the summoning's [Act.settle]), [adoptLoaded] (`onMobLoad`, through [ModuleCreatures.onMobLoad]), [sweep]
 * once a second, [releaseAll] at the end. At the server's stop nothing is given back: the creatures are saved with their
 * marks, and come back to the deployment as they load.
 */
class CreatureControl(private val services: NarratorServices, val deployment: Long, private val queue: ActQueue) : Creatures {
    private val log = Log.forge
    private val owner = MarkOwner.module(deployment)
    private val ref = Ref(RefKind.CANON, deployment)

    /** What the deployment knows of one of its creatures. */
    private inner class Held(val uuid: UUID, var type: String) {
        val handle = Handle(uuid)
        var seen: WeakReference<Mob>? = null
        var at: Place? = null
        var name: String? = null

        /** Unloaded since the last [sweep] told it. */
        var away = false

        /** The effects its module put on it (registry ids), taken off when it is given back (in memory: a restart forgets them, they run out by themselves). */
        val effects = mutableSetOf<String>()

        fun see(mob: Mob) {
            seen = WeakReference(mob)
            at = placeOf(mob)
            type = shortType(mob)
            name = mob.customName?.string
            away = false
        }
    }

    /** Its creatures, in the order they became its own, loaded or not. */
    private val held = LinkedHashMap<UUID, Held>()

    /** Creatures of summonings on their way ([reserve]). */
    private var reserved = 0

    private var released = false

    /** Its creatures, loaded or not (the unloaded ones come back as they load). */
    val count: Int get() = held.size

    /** The creatures of its summonings on their way ([reserve]). */
    val coming: Int get() = reserved

    override fun own(): List<Creature> = java.util.List.copyOf(held.values.filter { live(it.uuid) != null }.map { it.handle })

    override fun get(uuid: String?): Creature? = parse(uuid)?.let { held[it] }?.takeIf { live(it.uuid) != null }?.handle

    /**
     * Claims the wild creature [uuid] (M5 §4.5): its own while it lives, or null when [ModuleCreatures.claimRefusal]
     * refuses it, when [ForgeLimits.OWN_CREATURES] are its own already, or once the deployment ended. One of its own
     * already: its handle.
     */
    override fun claim(uuid: String?): Creature? {
        val id = parse(uuid) ?: return null
        held[id]?.let { return it.handle.takeIf { live(id) != null } }
        if (released || held.size + reserved >= ForgeLimits.OWN_CREATURES) return null
        val mob = find(id) ?: return null
        ModuleCreatures.claimRefusal(mob)?.let {
            log.debug { "$ref may not claim ${shortType(mob)} $id: $it" }
            return null
        }
        take(mob)
        mob.addTag(ModuleCreatures.originTag(placeOf(mob)))
        log.info("$ref claimed ${shortType(mob)} $id at ${placeOf(mob).let { "${it.x().toInt()} ${it.y().toInt()} ${it.z().toInt()} in ${it.dimension()}" }}")
        return held.getValue(id).handle
    }

    /** Room for [count] more creatures of a summoning on its way; false at [ForgeLimits.OWN_CREATURES], or once the deployment ended. */
    fun reserve(count: Int): Boolean {
        if (released || held.size + reserved + count > ForgeLimits.OWN_CREATURES) return false
        reserved += count
        return true
    }

    /**
     * The creatures [born] of a summoning that had [reservedCount] reserved: its own now, marked ([take]); the hostile
     * ones (and all of them when it targeted) aim at the player [near] they were summoned around, until their lifetime
     * ends, the guards having let it be a punishment of that player. Once the deployment ended, they go
     * (`NarratorMobs.retire`). Server thread.
     */
    fun adopt(born: List<UUID>, reservedCount: Int, near: String?, targeting: Boolean) {
        reserved = (reserved - reservedCount).coerceAtLeast(0)
        if (born.isEmpty()) return
        val now = services.clock.millis()
        val player = near?.let { services.server.playerList.getPlayerByName(it) }
        for (uuid in born) {
            val mob = find(uuid) ?: continue
            if (released || held.size >= ForgeLimits.OWN_CREATURES) {
                services.guards.mobs.retire(mob, now)
                continue
            }
            take(mob)
            val expiresAt = mob.getAttached(NarratorMob.TYPE)?.expiresAt
            if (player != null && expiresAt != null && (targeting || !SpawnPolicy.isHarmless(mob.type))) ModuleCreatures.aim(mob, player, expiresAt)
        }
    }

    /**
     * Its creature [mob] loaded again (its chunk, or the server's restart): its own again, while there is room;
     * else given back. Server thread.
     */
    fun adoptLoaded(mob: Mob) {
        held[mob.uuid]?.let {
            it.see(mob)
            ModuleCreatures.join(mob)
            return
        }
        if (released || held.size >= ForgeLimits.OWN_CREATURES) {
            ModuleCreatures.giveBack(mob, owner, emptyList(), services.guards, services.clock.millis())
            return
        }
        take(mob)
    }

    /**
     * Once a second: its creatures gone since the last time, as the facts the module gets ([GameFact.CreatureGone]):
     * `died`, `vanished` (its lifetime ran out, it was removed) — no longer its own — or `unloaded` (told once; still its
     * own, back when it loads). Server thread.
     */
    fun sweep(): List<GameFact.CreatureGone> {
        val gone = mutableListOf<GameFact.CreatureGone>()
        for (creature in held.values.toList()) {
            val mob = live(creature.uuid)
            if (mob != null) {
                creature.see(mob)
                continue
            }
            // The fact as the bus's are translated (`Facts.gone`, one source); from what was last seen once the entity is collected.
            val fact = creature.seen?.get()?.let(Facts::gone)
                ?: GameFact.CreatureGone(null, creature.at, Who(creature.type, creature.uuid.toString(), creature.name), Facts.VANISHED)
            if (fact.why() == Facts.UNLOADED) {
                if (creature.away) continue
                creature.away = true
            } else {
                held.remove(creature.uuid)
                ModuleCreatures.calm(creature.uuid)
            }
            gone += fact
        }
        return gone
    }

    /** Whether [entity] is one of its creatures (`WorldView`'s `own`, `CreatureInfo.own`): its mark, loaded or not. */
    fun owns(entity: Entity): Boolean = ModuleCreatures.ownerOf(entity) == deployment

    /**
     * The deployment ended: every creature of its own that is loaded is given back now ([ModuleCreatures.giveBack]);
     * those in unloaded chunks are, as they load. Nothing is its own any more. Returns how many were given back.
     * Server thread.
     */
    fun releaseAll(): Int {
        released = true
        val now = services.clock.millis()
        var given = 0
        for (creature in held.values) {
            val mob = live(creature.uuid) ?: continue
            ModuleCreatures.giveBack(mob, owner, creature.effects, services.guards, now)
            given++
        }
        held.clear()
        if (given > 0) log.info("$ref gave back $given creatures")
        return given
    }

    /** Makes [mob] its own: the Narrator's and the deployment's tags, the team that pushes nobody, no fall damage. */
    private fun take(mob: Mob) {
        RuleMarks.mark(mob, owner)
        ModuleCreatures.join(mob)
        mob.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER)
            ?.addOrReplacePermanentModifier(AttributeModifier(owner.modifier(FALL), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        held[mob.uuid] = Held(mob.uuid, shortType(mob)).also { it.see(mob) }
    }

    /** Its creature [uuid], alive and loaded; null otherwise. */
    private fun live(uuid: UUID): Mob? = if (uuid in held) find(uuid) else null

    private fun find(uuid: UUID): Mob? = (services.server.overworld().getEntityInAnyDimension(uuid) as? Mob)?.takeIf { it.isAlive && !it.isRemoved }

    private fun parse(uuid: String?): UUID? = uuid?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /**
     * One creature of the deployment, as the module steers it (M5 §4.5): at once, on the server thread, in the module's
     * own call. Not its own any more (released, or the deployment over): [ForgeRefusal] but for [uuid], [info] and
     * [alive]; gone (dead, vanished, unloaded): nothing happens.
     */
    private inner class Handle(private val id: UUID) : Creature {
        /** Released by its module ([release]): not its own any more, even if it is claimed again (with a new handle). */
        var given = false
        override fun uuid(): String = id.toString()

        override fun info(): CreatureInfo? = live(id)?.let { mob ->
            CreatureInfo(id.toString(), shortType(mob), placeOf(mob), mob.health, mob.maxHealth, mob.customName?.string, true)
        }

        override fun alive(): Boolean = live(id) != null

        override fun moveTo(to: Place?, speed: Double) {
            val mob = steered() ?: return
            if (to == null) throw ForgeRefusal("moveTo needs a place")
            if (speed.isNaN() || speed < ForgeLimits.SPEED_MIN || speed > ForgeLimits.SPEED_MAX) {
                throw ForgeRefusal("a creature walks at a speed of ${ToolText.number(ForgeLimits.SPEED_MIN)} to ${ToolText.number(ForgeLimits.SPEED_MAX)} (asked ${ToolText.number(speed)})")
            }
            sameDimension(mob, to, "walks")
            if (!SpawnPolicy.isHarmless(mob.type)) {
                ModuleCreatures.sparedNear(mob.level() as ServerLevel, Vec3(to.x(), to.y(), to.z()))?.let {
                    throw ForgeRefusal("${it.plainTextName} is spared by the Narrator now: a module's hostile creature keeps away from them")
                }
            }
            mob.navigation.moveTo(to.x(), to.y(), to.z(), speed)
        }

        override fun velocity(x: Double, y: Double, z: Double) {
            val mob = steered() ?: return
            bounded("velocity", x, y, z)
            mob.setDeltaMovement(x, y, z)
            mob.needsSync = true
        }

        override fun push(x: Double, y: Double, z: Double) {
            val mob = steered() ?: return
            bounded("push", x, y, z)
            val motion = mob.deltaMovement.add(x, y, z)
            // Its momentum stays bounded however often it is pushed: a creature is never a projectile.
            val max = ForgeLimits.VELOCITY_MAX
            mob.setDeltaMovement(motion.x.coerceIn(-max, max), motion.y.coerceIn(-max, max), motion.z.coerceIn(-max, max))
            mob.needsSync = true
        }

        override fun lookAt(at: Place?) {
            val mob = steered() ?: return
            if (at == null) throw ForgeRefusal("lookAt needs a place")
            sameDimension(mob, at, "looks")
            mob.lookControl.setLookAt(at.x(), at.y(), at.z())
        }

        override fun gravity(on: Boolean) {
            steered()?.isNoGravity = !on
        }

        override fun glow(on: Boolean) {
            steered()?.setGlowingTag(on)
        }

        override fun name(name: String?) {
            val mob = steered() ?: return
            if (name == null) {
                mob.customName = null
                mob.isCustomNameVisible = false
                return
            }
            val shown = Speech.fit(Speech.clean(name), ForgeLimits.CREATURE_NAME_CHARS)
            if (shown.isEmpty()) throw ForgeRefusal("a creature's name has nothing to show once formatting codes are removed")
            mob.customName = Component.literal(shown)
            mob.isCustomNameVisible = true
        }

        override fun effect(effect: String?, seconds: Int, level: Int) {
            val mob = steered() ?: return
            if (effect == null) throw ForgeRefusal("effect needs an effect id")
            val holder = refused { MobEffectTool.lookup(effect) }
            val shown = holder.registeredName.removePrefix("minecraft:")
            MobEffectTool.forbidden(holder)?.let { throw ForgeRefusal("$shown is never put on a module's creature: $it") }
            if (seconds !in 1..MAX_EFFECT_SECONDS || level !in 1..MAX_EFFECT_LEVEL) {
                throw ForgeRefusal("an effect lasts 1 to $MAX_EFFECT_SECONDS seconds, level 1 to $MAX_EFFECT_LEVEL (asked $seconds s, level $level)")
            }
            val caps = services.guards.caps()
            if (!caps.hostileAllowed) throw ForgeRefusal("the chaos level is 0: no creature of the Narrator's is changed")
            if ((!holder.value().isInstantaneous && seconds > caps.harmfulSeconds) || level > caps.harmfulLevel) {
                throw ForgeRefusal(
                    "at chaos ${services.settings.get().config.chaos} an effect on a creature lasts at most ${caps.harmfulSeconds} s and goes up to level " +
                        "${caps.harmfulLevel} (asked $seconds s, level $level)",
                )
            }
            val result = refused { MobEffectTool.apply(mob, owner, MobEffectArgs(effect, seconds, level)) }
            if (result.status != ToolStatus.OK) throw ForgeRefusal(result.text)
            held[id]?.effects?.add(holder.registeredName)
        }

        override fun attribute(attribute: String?, multiply: Double) {
            val mob = steered() ?: return
            val key = MobAttributeKey.entries.firstOrNull { it.id == attribute?.trim()?.lowercase()?.removePrefix("minecraft:") }
                ?: throw ForgeRefusal("unknown attribute \"$attribute\"; a creature's are ${MobAttributeKey.entries.joinToString { it.id }}")
            val bounds = MobAttributeTool.bounds(key)
            if (multiply.isNaN() || multiply !in bounds) {
                throw ForgeRefusal("${key.id} multiplies by ${ToolText.number(bounds.start)} to ${ToolText.number(bounds.endInclusive)} on a module's creature (asked ${ToolText.number(multiply)})")
            }
            if (!services.guards.caps().hostileAllowed) throw ForgeRefusal("the chaos level is 0: no creature of the Narrator's is changed")
            val result = refused { MobAttributeTool.apply(mob, owner, MobAttributeArgs(key, multiply)) }
            if (result.status != ToolStatus.OK) throw ForgeRefusal(result.text)
        }

        override fun attack(player: String?): Pending {
            mine()
            val args = buildJsonObject {
                put("creature", id.toString())
                put("player", player)
            }
            val gone = if (live(id) == null) ToolResult.failed("${held[id]?.type ?: "the creature"} $id is gone") else null
            return queue.ask(Act(CREATURE_ATTACK, args, player, refusal = gone))
        }

        override fun calm() {
            val mob = steered() ?: return
            ModuleCreatures.calm(id)
            mob.target = null
        }

        override fun release() {
            mine()
            given = true
            val creature = held.remove(id) ?: return
            ModuleCreatures.calm(id)
            val mob = find(id) ?: return
            ModuleCreatures.giveBack(mob, owner, creature.effects, services.guards, services.clock.millis())
        }

        /** It is still the deployment's own (loaded or gone), or a [ForgeRefusal]: released, or the deployment over. */
        private fun mine() {
            if (released || given) throw ForgeRefusal("$id is not one of $ref's creatures any more")
        }

        /** The creature to steer: its own and loaded; null when it is gone (nothing happens). */
        private fun steered(): Mob? {
            mine()
            return live(id)
        }

        private fun bounded(what: String, x: Double, y: Double, z: Double) {
            val max = ForgeLimits.VELOCITY_MAX
            if (listOf(x, y, z).any { it.isNaN() || it < -max || it > max }) {
                throw ForgeRefusal("$what: each component within -${ToolText.number(max)} and ${ToolText.number(max)} blocks a tick (asked ${ToolText.number(x)} ${ToolText.number(y)} ${ToolText.number(z)})")
            }
        }

        private fun sameDimension(mob: Mob, at: Place, does: String) {
            val here = placeOf(mob).dimension()
            if (at.dimension() != here) throw ForgeRefusal("a creature $does within its own dimension: it is in $here, not ${at.dimension()}")
        }
    }

    companion object {
        /** The tool of [Creature.attack] (`mechanics/module/ModuleOnlyTools.kt`). */
        const val CREATURE_ATTACK = "creature_attack"

        /** A creature's effect, as `mob_effect`'s: 1 to 900 seconds, level 1 to 5. */
        private const val MAX_EFFECT_SECONDS = 900
        private const val MAX_EFFECT_LEVEL = 5

        /** The attribute of the deployment's modifier that spares its creatures any fall damage. */
        const val FALL = "fall_damage_multiplier"

        /** [mob]'s type id without `minecraft:` (`zombie`). */
        fun shortType(mob: Entity): String = EntityType.getKey(mob.type).toString().removePrefix("minecraft:")

        /** Where [entity]'s feet are, as the SDK writes a place. */
        fun placeOf(entity: Entity): Place = Place(entity.level().dimension().identifier().toString(), entity.x, entity.y, entity.z)

        /** [block]'s result, or its refusal ([Refusal], from the rules' own checks) as a [ForgeRefusal]. */
        private inline fun <T> refused(block: () -> T): T = try {
            block()
        } catch (e: Refusal) {
            throw ForgeRefusal(e.result.text)
        }
    }
}

/**
 * The creatures of every deployment, on the server (M5 §4.5, design review findings 3 and 4): what makes them safe,
 * whatever their module asks.
 *
 * - **Marks**: the Narrator's tag, `narrator2_module_<id>` ([MarkOwner.module]), the deployment's modifiers, and for a
 *   claimed creature where it was claimed ([originTag]). [ownerOf] reads them.
 * - **They push nobody**: each is on the team [TEAM], whose collision rule is `never`; the game pushes no entity into
 *   another then, on the server and on the clients alike (`EntitySelector.pushableBy`, read with javap). A module that
 *   wants to act on a player goes through `attack`, an act that the guards check.
 * - **They aim at their target only**: the player of an accepted `attack` (`creature_attack`), or of the summoning that
 *   punished them ([aim]), until it ends; never anyone else, player or not ([keepsAway], by `CreatureShield` and its
 *   mixins), and their blows, projectiles, fangs and potions never reach another player ([spares], [sparesFrom]).
 *   Their aims live in memory: after a restart they aim at nobody until their module sends them again.
 * - **Never a bystander's death**: a player who dies by one of them, or within [DEATH_RADIUS] blocks of one, and is no
 *   creature's target, is rescued, never counted ([allowDeath], the engine's `ALLOW_DEATH`, task M5-11); a target's
 *   death is the death guard's, which the attack armed.
 * - **Never a creature that blasts everyone around** ([FORBIDDEN]), nor a boss nor one out of scale ([SpawnPolicy]).
 *
 * Server thread only.
 */
object ModuleCreatures {
    private val log = Log.forge

    /** The team of the modules' creatures: collision `never`, so that they push nobody and nobody pushes them. */
    const val TEAM = "narrator2_modules"

    /** The rule of a creature a module never has: `DENIED (forbidden_creature): …`, as `spawn` says it. */
    const val FORBIDDEN_CREATURE = SpawnPolicy.FORBIDDEN_CREATURE

    /** The rule of a summoning beyond [ForgeLimits.OWN_CREATURES]: `DENIED (module_creatures): …`. */
    const val OWN_LIMIT = "module_creatures"

    /**
     * Creatures a module never summons nor claims, by type id, with why: their blast hits or throws every player
     * around, not only the target the guards let them have. (The bosses and the creatures out of scale are
     * [SpawnPolicy.FORBIDDEN]'s, refused to everyone.)
     */
    val FORBIDDEN: Map<String, String> = mapOf(
        "minecraft:creeper" to "its blast hits every player around, not only its target",
        "minecraft:breeze" to "its wind charges throw every player around, not only its target",
    )

    /** A player who dies within this many blocks of a module's creature, and is no creature's target, is rescued. */
    const val DEATH_RADIUS = 16.0

    /** A module's hostile creature never walks to within this many blocks of a player the Narrator spares (M5 design review, finding 3). */
    const val KEEP_AWAY_BLOCKS = 3.0

    /** The start of the tag of a claimed creature's origin: `narrator2_origin|overworld|12.5|64.0|-30.5`. */
    private const val ORIGIN_PREFIX = "narrator2_origin|"

    /** Whom one creature may aim at, until when (epoch milliseconds, wall clock, as the guards' traps). */
    private class Aim(val player: UUID, val until: Long)

    private val aims = ConcurrentHashMap<UUID, Aim>()

    /** Whether [entity] is a creature a module holds now (`MobPickUpGuardMixin`: it never picks up an item). */
    @JvmStatic
    fun isModules(entity: Entity?): Boolean = ownerOf(entity) != null

    /** The deployment whose creature [entity] is (`narrator2_module_<id>`), or null. */
    fun ownerOf(entity: Entity?): Long? {
        if (!NarratorMobs.isNarrators(entity)) return null
        return MarkOwner.on(entity!!, MarkOwner.Kind.MODULE).firstOrNull()?.id
    }

    /** The module's creature behind [entity]: itself, or the owner of its arrow, potion, fangs or vex; null for anything else. */
    private fun behind(entity: Entity?): Entity? = when {
        ownerOf(entity) != null -> entity
        entity is TraceableEntity && ownerOf(entity.owner) != null -> entity.owner
        else -> null
    }

    /** [creature] may go for [player] until [until] (epoch milliseconds): an attack or a summoning the guards accepted. */
    fun aim(creature: Entity, player: ServerPlayer, until: Long) {
        aims[creature.uuid] = Aim(player.uuid, until)
    }

    /** [creature] aims at nobody now. */
    fun calm(creature: UUID) {
        aims.remove(creature)
    }

    /** The player [creature] may go for now, or null. */
    private fun aimOf(creature: Entity): UUID? {
        val aim = aims[creature.uuid] ?: return null
        if (aim.until > System.currentTimeMillis()) return aim.player
        aims.remove(creature.uuid, aim)
        return null
    }

    /** Whether some module's creature may go for [player] now: they are a punishment's target, not a bystander. */
    private fun isAimedAt(player: UUID): Boolean {
        val now = System.currentTimeMillis()
        return aims.values.any { it.player == player && it.until > now }
    }

    /**
     * Whether [attacker], a module's creature, must leave [target] alone: anyone but the player it aims at now
     * (M4 review finding 17, design review finding 3). False for any other attacker. Called by `CreatureShield.keepsAway`.
     */
    @JvmStatic
    fun keepsAway(attacker: Entity?, target: LivingEntity): Boolean = ownerOf(attacker) != null && aimOf(attacker!!) != target.uuid

    /** Whether a blow ([source]) of a module's creature, or of what it owns, must spare [victim]: anyone but its target. Called by `CreatureShield.allowsDamage`. */
    fun spares(victim: Player, source: DamageSource): Boolean {
        val by = behind(source.entity) ?: behind(source.directEntity) ?: return false
        return aimOf(by) != victim.uuid
    }

    /** Whether an effect from [source] (a module's creature, or what it owns) must spare [victim]: anyone but its target. Called by `CreatureShield.blocksEffect`. */
    fun sparesFrom(victim: Player, source: Entity?): Boolean {
        val by = behind(source) ?: return false
        return aimOf(by) != victim.uuid
    }

    /**
     * `ALLOW_DEATH` of [player] by [source], for the engine (task M5-11, `ModuleEngine.allowDeath`, before the guards'):
     * a death by a module's creature, or within [DEATH_RADIUS] blocks of one, of a player no module's creature aims at is
     * a bystander's: refused, the player rescued ([Guards.rescue]: out of the danger, never counted, shielded a while).
     * The death of a creature's target goes on to the death guard, which its attack armed (quota, then rescue). Another
     * player's blow and an operator's `/kill` are never the Narrator's. Server thread.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource, guards: Guards): Boolean {
        if (source.`is`(DamageTypes.GENERIC_KILL)) return true
        val killer = source.entity
        if (killer is Player && killer !== player) return true
        val creature = behind(source.entity) ?: behind(source.directEntity) ?: nearest(player) ?: return true
        if (isAimedAt(player.uuid)) return true
        val cause = source.typeHolder().registeredName.removePrefix("minecraft:")
        guards.rescue(player, "a bystander of C-${ownerOf(creature)}'s ${CreatureControl.shortType(creature)} ($cause): a module's creatures never kill a bystander")
        return false
    }

    /** The nearest module's creature within [DEATH_RADIUS] blocks of [player]; null when there is none. */
    private fun nearest(player: ServerPlayer): Entity? =
        player.level().getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(DEATH_RADIUS)) { ownerOf(it) != null }.minByOrNull { it.distanceToSqr(player) }

    /** A player near [at] that the Narrator spares now (protected, in grace, shielded, dueling, dead lately), or null. */
    fun sparedNear(level: ServerLevel, at: Vec3): ServerPlayer? =
        level.players().firstOrNull { !it.isSpectator && it.position().distanceTo(at) <= KEEP_AWAY_BLOCKS && CreatureShield.spares(it) }

    /**
     * What decides a claim (M5 §4.5), read off a creature ([factsOf]): its [type]; whether it is the Narrator's already
     * ([narrators]: summoned, or changed by a rule or a module), [tamed], [named] by a player, on a lead ([leashed]),
     * riding or ridden ([riding]), on a team ([team]), floating or glowing already ([floating]), and within
     * `Limits.MAX_CLAIM_DISTANCE` blocks of an online player ([near]).
     */
    data class ClaimFacts(
        val type: EntityType<*>,
        val narrators: Boolean = false,
        val tamed: Boolean = false,
        val named: Boolean = false,
        val leashed: Boolean = false,
        val riding: Boolean = false,
        val team: Boolean = false,
        val floating: Boolean = false,
        val near: Boolean = true,
    )

    /**
     * Why a module may not claim a creature with [facts] (M5 §4.5), or null: only a hostile creature
     * (`MobCategory.MONSTER`, javap) that a module may have ([FORBIDDEN], [SpawnPolicy.FORBIDDEN]: never a boss nor one
     * out of scale, design review finding 4), within reach of a player; never one the Narrator has already, a tamed one,
     * one a player named, one on a lead, riding or ridden, on a team, or floating or glowing already (it could not be
     * given back as it was). Any thread, once the game booted.
     */
    fun claimRefusal(facts: ClaimFacts): String? {
        val type = EntityType.getKey(facts.type).toString()
        return when {
            facts.narrators -> "it is the Narrator's already (summoned, or changed by a rule or a module)"
            facts.type.category != MobCategory.MONSTER -> "only a hostile creature is claimed, never an animal, a villager, a trader nor a golem: summon animals"
            SpawnPolicy.FORBIDDEN[type] != null -> "$type is never claimed: ${SpawnPolicy.FORBIDDEN[type]}"
            FORBIDDEN[type] != null -> "$type is never a module's: ${FORBIDDEN[type]}"
            facts.tamed -> "it is tamed"
            facts.named -> "a player named it"
            facts.leashed -> "it is on a lead"
            facts.riding -> "it rides or carries another"
            facts.team -> "it is on a team"
            facts.floating -> "it floats or glows already"
            !facts.near -> "it is farther than ${ForgeLimits.CLAIM_DISTANCE} blocks from every player"
            else -> null
        }
    }

    /** Why a module may not claim [mob] now ([claimRefusal] of its facts, then whether `spawn` could summon it in its world), or null. Server thread. */
    fun claimRefusal(mob: Mob): String? {
        val level = mob.level() as? ServerLevel ?: return "it is not in a world"
        if (!mob.isAlive) return "it is dead"
        claimRefusal(factsOf(mob, level))?.let { return it }
        return SpawnPolicy.check(mob.type, level).let { if (it is Verdict.Denied) it.message else null }
    }

    /** What decides a claim of [mob], in [level]. Server thread. */
    private fun factsOf(mob: Mob, level: ServerLevel): ClaimFacts {
        val reach = ForgeLimits.CLAIM_DISTANCE.toDouble()
        return ClaimFacts(
            type = mob.type,
            narrators = NarratorMobs.isNarrators(mob) || mob.hasAttached(NarratorMob.TYPE),
            tamed = (mob as? OwnableEntity)?.ownerReference != null,
            named = mob.hasCustomName(),
            leashed = (mob as? Leashable)?.isLeashed == true,
            riding = mob.isVehicle || mob.isPassenger,
            team = mob.team != null,
            floating = mob.isNoGravity || mob.hasGlowingTag(),
            near = level.players().any { !it.isSpectator && it.distanceToSqr(mob) <= reach * reach },
        )
    }

    /**
     * Gives [mob] back from [owner] (M5 §4.5): a summoned one vanishes (`NarratorMobs.retire`); a claimed one gets back
     * to normal (its tags and modifiers taken off, the [effects] its module put on it, its gravity, glow, name, target
     * and team) and back where it was claimed when that place is loaded and safe for it; the Narrator's tag goes when
     * no rule's nor module's is left. Server thread.
     */
    fun giveBack(mob: Mob, owner: MarkOwner, effects: Collection<String>, guards: Guards, now: Long) {
        calm(mob.uuid)
        if (mob.hasAttached(NarratorMob.TYPE)) {
            guards.mobs.retire(mob, now)
            return
        }
        val origin = originOf(mob)
        mob.removeTag(owner.tag)
        mob.entityTags().filter { it.startsWith(ORIGIN_PREFIX) }.forEach(mob::removeTag)
        for (path in MobAttributeKey.entries.map { it.id } + CreatureControl.FALL) {
            val holder = BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(path)).orElse(null) ?: continue
            mob.getAttribute(holder)?.removeModifier(owner.modifier(path))
        }
        for (id in effects) BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id)).orElse(null)?.let(mob::removeEffect)
        if (mob.health > mob.maxHealth) mob.health = mob.maxHealth
        mob.isNoGravity = false
        mob.setGlowingTag(false)
        mob.customName = null
        mob.isCustomNameVisible = false
        mob.target = null
        mob.navigation.stop()
        leave(mob)
        if (MarkOwner.Kind.entries.all { MarkOwner.on(mob, it).isEmpty() }) mob.removeTag(NarratorMobs.TAG)
        val back = origin?.let { back(mob, it) } == true
        log.info("${CreatureControl.shortType(mob)} ${mob.uuid} given back from C-${owner.id}" + if (back) ", back where it was claimed" else "")
    }

    /**
     * A creature of a module loaded (task M5-11, `onMobLoad`; and once at the engine's start for those already
     * loaded): its own again when [controlOf] knows its deployment, else given back ([giveBack]: its deployment ended
     * while it was unloaded). A summoned one goes, and a claimed one moves, at the next task of the server: never while
     * its chunk loads. Server thread.
     */
    fun onMobLoad(mob: Mob, controlOf: (Long) -> CreatureControl?, guards: Guards, now: Long) {
        val owner = MarkOwner.on(mob, MarkOwner.Kind.MODULE).firstOrNull() ?: return
        val control = controlOf(owner.id)
        if (control != null) {
            control.adoptLoaded(mob)
            return
        }
        val server = mob.level().server ?: return
        // Queued, never run in place: `execute` runs at once on the server thread.
        server.schedule(TickTask(server.tickCount, Runnable { if (mob.isAlive && !mob.isRemoved) giveBack(mob, owner, emptyList(), guards, now) }))
    }

    /** The team of the modules' creatures on [server], made when missing; its collision rule is always `never`. */
    private fun team(server: MinecraftServer): PlayerTeam {
        val board = server.scoreboard
        val team = board.getPlayerTeam(TEAM) ?: board.addPlayerTeam(TEAM)
        if (team.collisionRule != Team.CollisionRule.NEVER) team.collisionRule = Team.CollisionRule.NEVER
        return team
    }

    /** [mob] joins the team that pushes nobody. */
    fun join(mob: Mob) {
        val server = mob.level().server ?: return
        val team = team(server)
        if (mob.team !== team) server.scoreboard.addPlayerToTeam(mob.scoreboardName, team)
    }

    /** [mob] leaves the modules' team (and no other). */
    private fun leave(mob: Mob) {
        val server = mob.level().server ?: return
        val team = server.scoreboard.getPlayersTeam(mob.scoreboardName)?.takeIf { it.name == TEAM } ?: return
        server.scoreboard.removePlayerFromTeam(mob.scoreboardName, team)
    }

    /** The tag of where a creature was claimed. */
    fun originTag(at: Place): String = ORIGIN_PREFIX + listOf(at.dimension(), at.x(), at.y(), at.z()).joinToString("|")

    /** Where [mob] was claimed, from its [originTag]; null when it has none or it does not read. */
    private fun originOf(mob: Mob): Place? {
        val parts = mob.entityTags().firstOrNull { it.startsWith(ORIGIN_PREFIX) }?.removePrefix(ORIGIN_PREFIX)?.split('|') ?: return null
        if (parts.size != 4) return null
        val (x, y, z) = parts.drop(1).map { it.toDoubleOrNull() ?: return null }
        return runCatching { Place(parts[0], x, y, z) }.getOrNull()
    }

    /** Takes [mob] back to [origin] when it is in the same dimension, loaded, free for it and dry; whether it went. */
    private fun back(mob: Mob, origin: Place): Boolean {
        val level = mob.level() as? ServerLevel ?: return false
        if (CreatureControl.placeOf(mob).dimension() != origin.dimension()) return false
        val x = kotlin.math.floor(origin.x()).toInt()
        val z = kotlin.math.floor(origin.z()).toInt()
        if (level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) == null) return false
        val spot = Vec3(origin.x(), origin.y(), origin.z())
        val box = mob.getDimensions(mob.pose).makeBoundingBox(spot)
        if (!level.noCollision(mob, box) || level.containsAnyLiquid(box)) return false
        mob.teleportTo(spot.x, spot.y, spot.z)
        return true
    }

    /** [dimension] (the SDK's form: `overworld`) as a level of [server]; null when it has none. */
    fun levelOf(server: MinecraftServer, dimension: String): ServerLevel? =
        Identifier.tryParse(ToolText.dimensionId(dimension))?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
}
