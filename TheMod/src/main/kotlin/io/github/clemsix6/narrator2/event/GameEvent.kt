package io.github.clemsix6.narrator2.event

import java.util.UUID

/**
 * A published event, stamped by the [EventBus].
 *
 * @property id global, strictly increasing id, shared by every journal table that stores events
 *   (`events`, `chat`, `private_messages`): an event can be cited by its id.
 * @property ts wall-clock time, epoch milliseconds.
 * @property gameTime overworld game time, in ticks.
 */
data class Envelope(val id: Long, val ts: Long, val gameTime: Long, val event: GameEvent)

/**
 * An immutable fact about the game, built on the server thread from plain values only (never a
 * reference to a stack, an entity, a player or a block state).
 *
 * Every subclass has at least one entry in [EventCatalog], which documents it for the Journal and the
 * model; `EventCatalogTest` fails otherwise. Ids, timestamps and game time are not part of the event:
 * the bus puts them in the [Envelope]. Item, block, entity, biome and dimension ids are full ids
 * (`minecraft:diamond_ore`), see `observer/GameIds.kt`. There is one source per event type.
 */
sealed interface GameEvent {
    /** Journal type, e.g. `block_break` (see [EventCatalog]). */
    val type: String

    /** The acting player, if any. */
    val player: PlayerRef?

    val pos: Pos?
}

// ── Blocks ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * A player broke a block.
 *
 * @property block the block before it broke.
 * @property tool item in the main hand; null when empty.
 * @property correctTool whether the tool can harvest the block's drops.
 * @property placedBy who placed this block, when a player did (`PlacedBlocks`).
 */
data class BlockBroken(
    override val player: PlayerRef,
    override val pos: Pos,
    val block: String,
    val tool: String?,
    val creative: Boolean,
    val correctTool: Boolean,
    val placedBy: UUID?,
) : GameEvent {
    override val type: String get() = "block_break"
}

/** What a [BlockPlaced] placed; each kind is its own journal type. */
enum class PlaceKind(val type: String) {
    /** A block item. */
    BLOCK("block_place"),

    /** Water, lava or another fluid poured from a bucket. */
    FLUID("fluid_place"),

    /** Fire lit with flint and steel. */
    FIRE("fire_lit"),

    /** Fire lit inside an obsidian frame, which created a nether portal. */
    PORTAL("portal_lit"),
}

/**
 * A player placed a block, poured a fluid or lit a fire.
 *
 * @property block the block now at [pos].
 * @property item the item used (block item, bucket, flint and steel).
 */
data class BlockPlaced(
    override val player: PlayerRef,
    override val pos: Pos,
    val block: String,
    val item: String,
    val kind: PlaceKind,
) : GameEvent {
    override val type: String get() = kind.type
}

/** A player completed an end portal frame with an eye of ender. */
data class EndPortalActivated(override val player: PlayerRef, override val pos: Pos) : GameEvent {
    override val type: String get() = "end_portal_lit"
}

/**
 * A player opened a container or another menu.
 *
 * @property pos where the block or entity is; null when unknown.
 * @property menu menu type id, e.g. `minecraft:generic_9x3`.
 * @property title custom name of the container, when it has one.
 * @property target block id or entity type id of what was opened.
 * @property spectator opened in spectator mode (looking, not taking).
 */
data class ContainerOpened(
    override val player: PlayerRef,
    override val pos: Pos?,
    val menu: String,
    val title: String?,
    val target: String?,
    val spectator: Boolean,
) : GameEvent {
    override val type: String get() = "container_open"
}

/** A player opened a container that still had its loot table (a dungeon or structure chest, first opening). */
data class LootChestOpened(override val player: PlayerRef, override val pos: Pos, val lootTable: String) : GameEvent {
    override val type: String get() = "loot_open"
}

/**
 * An explosion near players or touching player-built blocks (several in the same tick and area are merged).
 *
 * @property source type of the direct source, e.g. `minecraft:tnt`, `minecraft:creeper`; null for
 *   blocks such as beds and respawn anchors.
 * @property author the entity behind it (indirect source): the player who lit the TNT, the ghast that
 *   shot the fireball. [player] is the author when it is a player.
 * @property interaction what the explosion does to blocks: `keep`, `destroy`, `destroy_with_decay`,
 *   `trigger_block`.
 * @property blocksHit blocks destroyed.
 * @property playerBlocksHit among them, blocks placed by players.
 * @property nearby players close enough to be affected.
 * @property narrator the explosion was caused by the Narrator.
 */
data class ExplosionHappened(
    override val pos: Pos,
    val source: String?,
    val author: EntityRef?,
    val radius: Double,
    val interaction: String,
    val blocksHit: Int,
    val playerBlocksHit: Int,
    val nearby: List<PlayerRef>,
    val narrator: Boolean,
) : GameEvent {
    override val type: String get() = "explosion"
    override val player: PlayerRef? get() = author?.player
}

// ── Combat ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * A player took damage. Hits of the same damage type within 5 seconds are merged into one event.
 *
 * @property damageType damage type id, e.g. `minecraft:fall`, `minecraft:mob_attack`.
 * @property amount damage before armor, summed over [hits].
 * @property attacker the entity that dealt it, if any (the shooter for a projectile).
 * @property blocked a shield blocked at least one of the hits.
 * @property healthAfter health after the last hit.
 */
data class DamageTaken(
    override val player: PlayerRef,
    override val pos: Pos,
    val damageType: String,
    val amount: Double,
    val hits: Int,
    val attacker: EntityRef?,
    val blocked: Boolean,
    val healthAfter: Double,
) : GameEvent {
    override val type: String get() = "damage_taken"
}

/**
 * A player damaged another entity (not fatally: a fatal hit is an [EntityKilled]).
 *
 * @property pos where the victim was.
 * @property weapon item used, if any.
 * @property victimHealth the victim's health after the hit.
 */
data class DamageDealt(
    override val player: PlayerRef,
    override val pos: Pos,
    val victim: EntityRef,
    val amount: Double,
    val weapon: String?,
    val damageType: String,
    val victimHealth: Double,
) : GameEvent {
    override val type: String get() = "damage_dealt"
}

/**
 * A player died.
 *
 * @property cause damage type id of the fatal hit.
 * @property killer the entity credited with the kill, if any.
 * @property weapon item the killer used, if any.
 * @property message the full death message, in the server's language (English).
 * @property keptInventory the player keeps their inventory (game rule, or a death caused by the Narrator).
 * @property narrator the death is attributed to the Narrator (`Guards.deathAttribution`).
 */
data class PlayerDied(
    override val player: PlayerRef,
    override val pos: Pos,
    val cause: String,
    val killer: EntityRef?,
    val weapon: String?,
    val message: String?,
    val xpLevel: Int,
    val keptInventory: Boolean,
    val narrator: Boolean,
) : GameEvent {
    override val type: String get() = "death"
}

/**
 * A player killed an entity. A player killed by a player is also a [PlayerDied].
 *
 * @property pos where the victim died.
 * @property victim the victim; its name is set for a player or a named mob.
 * @property pvp the victim is a player.
 * @property tamed the victim was someone's tamed animal.
 */
data class EntityKilled(
    override val player: PlayerRef,
    override val pos: Pos,
    val victim: EntityRef,
    val weapon: String?,
    val pvp: Boolean,
    val tamed: Boolean,
) : GameEvent {
    override val type: String get() = "kill"
}

/**
 * A projectile shot by or hitting a player landed (fishing hooks excluded).
 *
 * @property player the shooting player, else the player who was hit.
 * @property pos where it landed.
 * @property projectile projectile entity type, e.g. `minecraft:arrow`.
 * @property target the entity hit, if any.
 * @property block the block hit, if any.
 * @property weapon item that fired it, if known.
 */
data class ProjectileHit(
    override val player: PlayerRef,
    override val pos: Pos,
    val projectile: String,
    val shooter: EntityRef?,
    val target: EntityRef?,
    val block: String?,
    val weapon: String?,
) : GameEvent {
    override val type: String get() = "projectile_hit"
}

// ── Items ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * A player finished eating or drinking something.
 *
 * @property countBefore stack size before consuming.
 * @property nutrition and [saturation]: for food.
 * @property effects effect ids of a potion, e.g. `minecraft:night_vision`.
 * @property foodBefore food level before consuming (0-20).
 * @property healthBefore health before consuming.
 */
data class ItemConsumed(
    override val player: PlayerRef,
    override val pos: Pos,
    val item: String,
    val countBefore: Int,
    val nutrition: Int?,
    val saturation: Double?,
    val effects: List<String>,
    val foodBefore: Int,
    val healthBefore: Double,
) : GameEvent {
    override val type: String get() = "consume"
}

/**
 * A player took crafted or smelted items out of a result slot (merged per tick).
 *
 * @property station menu type id (crafting table, furnace, smithing table…), or `minecraft:inventory`
 *   for the 2×2 grid.
 */
data class ItemCrafted(
    override val player: PlayerRef,
    override val pos: Pos,
    val item: String,
    val count: Int,
    val station: String,
) : GameEvent {
    override val type: String get() = "craft"
}

/**
 * A player took the result of an anvil.
 *
 * @property left and [right]: the inputs.
 * @property cost XP levels paid.
 * @property renamedTo new name, when the item was renamed.
 */
data class AnvilUsed(
    override val player: PlayerRef,
    override val pos: Pos,
    val result: String,
    val left: String,
    val right: String?,
    val cost: Int,
    val renamedTo: String?,
) : GameEvent {
    override val type: String get() = "anvil"
}

/** A player took the result of a grindstone (enchantments removed). */
data class GrindstoneUsed(override val player: PlayerRef, override val pos: Pos, val result: String) : GameEvent {
    override val type: String get() = "grindstone"
}

/**
 * A player enchanted an item at an enchanting table.
 *
 * @property enchantments enchantment id → level.
 * @property cost XP levels paid.
 */
data class ItemEnchanted(
    override val player: PlayerRef,
    override val pos: Pos,
    val item: String,
    val enchantments: Map<String, Int>,
    val cost: Int,
) : GameEvent {
    override val type: String get() = "enchant"
}

/**
 * A player traded with a villager or a wandering trader.
 *
 * @property result what the player received.
 * @property costA and [costB]: what the player paid.
 * @property profession villager profession id, when it has one.
 * @property level villager level (1-5), when it has one.
 * @property uses times this offer was used, after this trade, out of [maxUses].
 */
data class Traded(
    override val player: PlayerRef,
    override val pos: Pos,
    val result: ItemCount,
    val costA: ItemCount,
    val costB: ItemCount?,
    val villager: EntityRef,
    val profession: String?,
    val level: Int?,
    val uses: Int,
    val maxUses: Int,
) : GameEvent {
    override val type: String get() = "trade"
}

/**
 * Items a player picked up, summed per item over a window (30 s, cut short by a dimension change or
 * a disconnection).
 */
data class ItemsPickedUp(
    override val player: PlayerRef,
    override val pos: Pos,
    val item: String,
    val count: Int,
    val windowSeconds: Int,
) : GameEvent {
    override val type: String get() = "pickup"
}

/** A player threw items away. */
data class ItemsDropped(override val player: PlayerRef, override val pos: Pos, val item: String, val count: Int) : GameEvent {
    override val type: String get() = "drop"
}

// ── Animals ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * A player reeled in a catch.
 *
 * @property loot items caught.
 * @property hooked entity pulled by the hook, if any.
 */
data class FishCaught(
    override val player: PlayerRef,
    override val pos: Pos,
    val loot: List<ItemCount>,
    val hooked: EntityRef?,
) : GameEvent {
    override val type: String get() = "fish"
}

/** A player bred two animals of type [animal]. */
data class AnimalsBred(override val player: PlayerRef, override val pos: Pos, val animal: String) : GameEvent {
    override val type: String get() = "breed"
}

/** A player tamed an animal of type [animal]; [name] is its custom name, if any. */
data class AnimalTamed(override val player: PlayerRef, override val pos: Pos, val animal: String, val name: String?) : GameEvent {
    override val type: String get() = "tame"
}

// ── Progress ────────────────────────────────────────────────────────────────────────────────────────

/**
 * A vanilla stat worth logging one by one was awarded (most stats only feed the totals, see [StatsSnapshot]).
 *
 * @property stat stat name, e.g. `minecraft.custom:minecraft.bell_ring`.
 * @property amount increment.
 */
data class StatAwarded(override val player: PlayerRef, override val pos: Pos, val stat: String, val amount: Int) : GameEvent {
    override val type: String get() = "stat"
}

/**
 * A player earned an advancement (recipes excluded).
 *
 * @property title displayed title, in the server's language.
 * @property frame `task`, `goal` or `challenge`.
 */
data class AdvancementEarned(
    override val player: PlayerRef,
    override val pos: Pos,
    val advancement: String,
    val title: String,
    val frame: String,
) : GameEvent {
    override val type: String get() = "advancement"
}

// ── Movement and places ─────────────────────────────────────────────────────────────────────────────

/**
 * A player changed dimension; [pos] is where they arrived.
 *
 * @property via `nether_portal`, `end_portal`, `end_gateway`, `end_exit` (leaving the End after the
 *   credits) or `other` (a command, another mod).
 */
data class DimensionChanged(override val player: PlayerRef, override val pos: Pos, val from: Pos, val via: String) : GameEvent {
    override val type: String get() = "dimension_change"
}

/** A player jumped 64 blocks or more within the same dimension (derived by the sampler); [pos] is where they arrived. */
data class Teleported(override val player: PlayerRef, override val pos: Pos, val from: Pos, val distance: Double) : GameEvent {
    override val type: String get() = "teleport"
}

/** A player lay down in a bed; [pos] is the bed, [bed] its block id. */
data class SleepStarted(override val player: PlayerRef, override val pos: Pos, val bed: String) : GameEvent {
    override val type: String get() = "sleep_start"
}

/** A player got up; [nightSkipped] tells whether the night was skipped, when known. */
data class SleepEnded(override val player: PlayerRef, override val pos: Pos, val nightSkipped: Boolean?) : GameEvent {
    override val type: String get() = "sleep_end"
}

/**
 * A player's respawn point changed (bed, respawn anchor, command).
 *
 * @property pos where the player was.
 * @property point the new respawn point; null when [cleared].
 * @property forced set by a command, valid even without a bed.
 */
data class RespawnPointSet(
    override val player: PlayerRef,
    override val pos: Pos,
    val point: Pos?,
    val forced: Boolean,
    val cleared: Boolean,
) : GameEvent {
    override val type: String get() = "spawn_set"
}

/** A player got on ([mounted]) or off a vehicle or mount. */
data class MountChanged(override val player: PlayerRef, override val pos: Pos, val vehicle: EntityRef, val mounted: Boolean) : GameEvent {
    override val type: String get() = if (mounted) "mount" else "dismount"
}

/** A player entered a biome (stable over three checks); [firstVisit] if this player had never been in it. */
data class BiomeEntered(override val player: PlayerRef, override val pos: Pos, val biome: String, val firstVisit: Boolean) : GameEvent {
    override val type: String get() = "biome_enter"
}

/** A player entered a structure, e.g. `minecraft:ancient_city`; [firstVisit] if this player had never been in one. */
data class StructureEntered(override val player: PlayerRef, override val pos: Pos, val structure: String, val firstVisit: Boolean) : GameEvent {
    override val type: String get() = "structure_enter"
}

/** A player left a structure. */
data class StructureLeft(override val player: PlayerRef, override val pos: Pos, val structure: String) : GameEvent {
    override val type: String get() = "structure_leave"
}

/** A player went underground (no sky above, well below sea level, for 10 s) or back to the surface. */
data class UndergroundChanged(override val player: PlayerRef, override val pos: Pos, val underground: Boolean) : GameEvent {
    override val type: String get() = if (underground) "underground" else "surface"
}

// ── Player state ────────────────────────────────────────────────────────────────────────────────────

/** A player's health fell to 4 or less (re-armed at 8). */
data class HealthCritical(override val player: PlayerRef, override val pos: Pos, val health: Double) : GameEvent {
    override val type: String get() = "health_low"
}

/**
 * A player became idle (no action and under 2 blocks moved in 5 minutes) or active again.
 *
 * @property idleSeconds how long the player had been idle.
 */
data class IdleChanged(override val player: PlayerRef, override val pos: Pos, val idle: Boolean, val idleSeconds: Int) : GameEvent {
    override val type: String get() = if (idle) "idle" else "active"
}

/**
 * A player joined; a session starts.
 *
 * @property sessionId from `journal.nextId(IdKind.SESSION)`.
 * @property firstTime first time this player joins this world.
 * @property isOp the player is an operator.
 */
data class PlayerJoined(
    override val player: PlayerRef,
    override val pos: Pos,
    val sessionId: Long,
    val firstTime: Boolean,
    val isOp: Boolean,
) : GameEvent {
    override val type: String get() = "join"
}

/** Why a session ended. */
enum class LeaveReason {
    /** The player left or was disconnected. */
    LEAVE,

    /** The server is stopping. */
    SERVER_STOP,
    ;

    /** Value stored in the Journal, e.g. `server_stop`. */
    val id: String get() = name.lowercase()
}

/** A player left; the session [sessionId] ends after [sessionSeconds]. */
data class PlayerLeft(
    override val player: PlayerRef,
    override val pos: Pos,
    val sessionId: Long,
    val sessionSeconds: Long,
    val reason: LeaveReason,
) : GameEvent {
    override val type: String get() = "leave"
}

/**
 * A player respawned; [pos] is where.
 *
 * @property alive the player was not dead: they left the End through its exit portal.
 */
data class PlayerRespawned(override val player: PlayerRef, override val pos: Pos, val alive: Boolean) : GameEvent {
    override val type: String get() = "respawn"
}

// ── Chat and commands ───────────────────────────────────────────────────────────────────────────────

/**
 * A player ran a command.
 *
 * @property root the command's first word, lowercase, e.g. `tp`.
 * @property text the whole command line, without `/`; null unless every command the line runs (its
 *   root, each command behind `execute … run`, each command a redirect lands in) is one of
 *   [TEXT_KEPT_ROOTS] and none is withheld by the configuration (`observer.privateMessageCommands`,
 *   `observer.redactedCommands`); null too when the line does not parse to its end. The text of a
 *   private message is only in its [ChatMessage] of channel [ChatChannel.WHISPER], never shown to the model.
 */
data class CommandIssued(override val player: PlayerRef, override val pos: Pos, val root: String, val text: String?) : GameEvent {
    override val type: String get() = "command"

    companion object {
        /**
         * The vanilla commands that send a private message between players; they also publish a
         * [ChatMessage] ([ChatChannel.WHISPER]). Other mods' are in `observer.privateMessageCommands`.
         */
        val PRIVATE_MESSAGE_COMMANDS: Set<String> = setOf("msg", "tell", "w", "teammsg", "tm")

        /**
         * The only roots whose text may be kept: vanilla 26.3 commands, and `/pray` (the prayer is also a
         * chat line to the Narrator). Left out: the private messages, the texts shown to chosen players
         * (`tellraw`, `title`, `dialog`, `bossbar`, the reasons of `kick` and `ban`), the development
         * commands, `narrator` (the operators' instructions and queries) and every other mod's command.
         */
        val TEXT_KEPT_ROOTS: Set<String> = setOf(
            // Administration.
            "stop", "save-all", "save-off", "save-on", "op", "deop", "banlist", "pardon", "pardon-ip", "whitelist",
            "transfer", "debug", "perf", "jfr", "setidletimeout", "publish", "unpublish", "reload", "fetchprofile", "seed",
            // The world and its rules, functions.
            "gamerule", "difficulty", "defaultgamemode", "gamemode", "worldborder", "forceload", "tick", "setworldspawn",
            "spawnpoint", "datapack", "function", "schedule",
            // Blocks, items and progress.
            "fill", "fillbiome", "setblock", "clone", "place", "data", "clear", "item", "loot", "give", "enchant", "xp",
            "experience", "advancement", "recipe",
            // Entities.
            "kill", "damage", "summon", "tp", "teleport", "spreadplayers", "ride", "effect", "attribute", "tag", "team",
            "rotate", "spectate", "posteffect",
            // Public speech, and the weather, time, places, particles and sounds.
            "say", "me", "pray", "weather", "time", "locate", "particle", "playsound", "stopsound",
            // Everything else a player or an operator may type.
            "execute", "return", "scoreboard", "trigger", "waypoint", "random", "compute", "stopwatch", "swing", "help",
            "list", "version",
        )
    }
}

/** Channel of a [ChatMessage] sent by a player. */
enum class ChatChannel {
    /** The public chat. */
    PUBLIC,

    /** `/pray`: a private message to the Narrator. */
    PRAY,

    /**
     * A private message between players (`/msg`, `/tell`, `/w`, `/teammsg`). Journaled in the
     * `private_messages` table, and **never shown to the model**: not in the Director's inbox, not in
     * any table or view the `sql` tool can read.
     */
    WHISPER,
    ;

    /** Value stored in the Journal, e.g. `public`. */
    val id: String get() = name.lowercase()
}

/**
 * A player wrote in the chat, prayed, or sent a private message.
 *
 * @property text what the player wrote, as sent.
 * @property recipients names of the players a [ChatChannel.WHISPER] went to; empty for the public
 *   chat and prayers.
 */
data class ChatMessage(
    override val player: PlayerRef,
    val channel: ChatChannel,
    val text: String,
    val recipients: List<String>,
) : GameEvent {
    /** `whisper` for private messages, which go to their own table; `chat` otherwise. */
    override val type: String get() = if (channel == ChatChannel.WHISPER) "whisper" else "chat"
    override val pos: Pos? get() = null
}

/** Who a [NarratorSpoke] line comes from. */
enum class NarratorChannel {
    /** The Director, through its speech tools. */
    NARRATOR,

    /** An operator, through `/narrator say`: shown as a Narrator line. */
    OPERATOR,
    ;

    /** Value stored in the Journal, e.g. `narrator`. */
    val id: String get() = name.lowercase()
}

/**
 * The Narrator said something in the chat.
 *
 * @property recipients players it was sent to; null when it went to everyone.
 * @property turnId Director turn that said it ([NarratorChannel.NARRATOR]).
 * @property operator name of the operator ([NarratorChannel.OPERATOR]).
 */
data class NarratorSpoke(
    val channel: NarratorChannel,
    val text: String,
    val recipients: List<PlayerRef>?,
    val turnId: Long?,
    val operator: String?,
) : GameEvent {
    override val type: String get() = "narrator_say"
    override val player: PlayerRef? get() = null
    override val pos: Pos? get() = null
}

// ── Samples and totals (their own tables) ───────────────────────────────────────────────────────────

/** Why a position was sampled. */
enum class SampleReason {
    JOIN, RESPAWN, DIMENSION, MOVED, INTERVAL, IDLE, TELEPORT, VEHICLE;

    /** Value stored in the Journal, e.g. `moved`. */
    val id: String get() = name.lowercase()
}

/**
 * Where a player is and how they are doing, sampled by the position sampler.
 *
 * @property structure structure the player is in, if any.
 * @property skyLight and [blockLight]: light levels (0-15) at the player's position, when read.
 * @property food food level (0-20).
 * @property vehicle entity type of the vehicle or mount, if any.
 */
data class PositionSampled(
    override val player: PlayerRef,
    override val pos: Pos,
    val biome: String?,
    val structure: String?,
    val underground: Boolean,
    val skyLight: Int?,
    val blockLight: Int?,
    val health: Double,
    val food: Int,
    val vehicle: String?,
    val reason: SampleReason,
) : GameEvent {
    override val type: String get() = "position"
}

/**
 * Vanilla stat totals of a player that changed since the previous snapshot (every 5 minutes, when the
 * player leaves, and in full when an existing world's stats are imported).
 *
 * @property changed stat name (`minecraft.mined:minecraft.stone`) → new total.
 */
data class StatsSnapshot(override val player: PlayerRef, val changed: Map<String, Long>) : GameEvent {
    override val type: String get() = "stats_snapshot"
    override val pos: Pos? get() = null
}
