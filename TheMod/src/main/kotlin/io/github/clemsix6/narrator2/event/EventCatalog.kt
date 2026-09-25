package io.github.clemsix6.narrator2.event

import kotlin.reflect.KClass

/** Journal table where the events of a type are stored. */
enum class EventTable(val sqlName: String, val visibleToModel: Boolean = true) {
    /** Generic columns `subject`, `amount`, `other` and a `detail` JSON object, documented per type. */
    EVENTS("events"),
    CHAT("chat"),

    /** Private messages between players: journaled, never readable by the model. */
    PRIVATE_MESSAGES("private_messages", visibleToModel = false),
    POSITIONS("positions"),
    STAT_TOTALS("stat_totals"),
}

/**
 * One journal type: what it means and, for the `events` table, what its generic columns hold.
 *
 * In [subject], [amount], [other] and [detail], an entity is written as the player's name for a
 * player, else as its entity type id.
 *
 * @property name the value of [GameEvent.type], snake_case.
 * @property summary one English sentence.
 * @property subject meaning of the `subject` column; null when unused.
 * @property amount meaning of the `amount` column; null when unused.
 * @property other meaning of the `other` column; null when unused.
 * @property detail keys of the `detail` JSON object → meaning, in the order they are written.
 */
data class EventType(
    val name: String,
    val eventClass: KClass<out GameEvent>,
    val table: EventTable,
    val summary: String,
    val subject: String? = null,
    val amount: String? = null,
    val other: String? = null,
    val detail: Map<String, String> = emptyMap(),
)

/**
 * Every journal type, with its documentation. This is the only description of the event types given
 * to the model (the `sql` tool's schema) and the reference for the Journal's mapping: a type without
 * an entry here fails `EventCatalogTest`.
 */
object EventCatalog {
    val all: List<EventType> = listOf(
        // Blocks
        events(
            "block_break", BlockBroken::class, "A player broke a block.",
            subject = "block", other = "tool in the main hand (null: bare hand)",
            detail = mapOf(
                "creative" to "broken in creative mode",
                "correct_tool" to "the tool harvests the block's drops",
                "placed_by" to "UUID of the player who had placed this block, if any",
            ),
        ),
        events("block_place", BlockPlaced::class, "A player placed a block.", subject = "block placed", other = "item used"),
        events("fluid_place", BlockPlaced::class, "A player poured a fluid from a bucket.", subject = "fluid block", other = "bucket"),
        events("fire_lit", BlockPlaced::class, "A player lit a fire with flint and steel.", subject = "fire block", other = "item used"),
        events("portal_lit", BlockPlaced::class, "A player lit a nether portal.", subject = "portal block", other = "item used"),
        events("end_portal_lit", EndPortalActivated::class, "A player completed an end portal frame."),
        events(
            "container_open", ContainerOpened::class, "A player opened a container or another menu.",
            subject = "menu type", other = "block or entity opened",
            detail = mapOf("title" to "custom name of the container", "spectator" to "opened in spectator mode"),
        ),
        events("loot_open", LootChestOpened::class, "A player opened a structure chest for the first time.", subject = "loot table"),
        events(
            "explosion", ExplosionHappened::class, "An explosion near players or touching player-built blocks.",
            subject = "direct source (tnt, creeper…; null for beds and anchors)", amount = "radius",
            other = "who caused it",
            detail = mapOf(
                "interaction" to "keep, destroy, destroy_with_decay or trigger_block",
                "blocks_hit" to "blocks destroyed",
                "player_blocks_hit" to "of which placed by players",
                "nearby" to "names of the players nearby",
                "narrator" to "caused by the Narrator",
            ),
        ),
        // Combat
        events(
            "damage_taken", DamageTaken::class, "A player took damage (hits of one type within 5 s merged).",
            subject = "damage type", amount = "damage before armor, summed", other = "attacker",
            detail = mapOf("hits" to "hits merged", "blocked" to "a shield blocked a hit", "health_after" to "health after the last hit"),
        ),
        events(
            "damage_dealt", DamageDealt::class, "A player damaged an entity without killing it.",
            subject = "victim type", amount = "damage", other = "weapon",
            detail = mapOf("damage_type" to "damage type", "victim_name" to "victim's name, if any", "victim_health" to "victim's health after"),
        ),
        events(
            "death", PlayerDied::class, "A player died.",
            subject = "damage type", amount = "XP level", other = "killer",
            detail = mapOf(
                "message" to "death message",
                "weapon" to "killer's weapon",
                "kept_inventory" to "the player kept their inventory",
                "narrator" to "caused by the Narrator",
            ),
        ),
        events(
            "kill", EntityKilled::class, "A player killed an entity.",
            subject = "victim type", other = "weapon",
            detail = mapOf("pvp" to "the victim is a player", "victim_name" to "victim's name, if any", "tamed" to "the victim was a tamed animal"),
        ),
        events(
            "projectile_hit", ProjectileHit::class, "A projectile shot by or hitting a player landed.",
            subject = "projectile", other = "entity hit",
            detail = mapOf("shooter" to "who shot it", "block" to "block hit", "weapon" to "bow, crossbow…"),
        ),
        // Items
        events(
            "consume", ItemConsumed::class, "A player ate or drank something.",
            subject = "item", amount = "stack size before",
            detail = mapOf(
                "nutrition" to "food points",
                "saturation" to "saturation",
                "effects" to "potion effects",
                "food_before" to "food level before (0-20)",
                "health_before" to "health before",
            ),
        ),
        events("craft", ItemCrafted::class, "A player crafted or smelted items.", subject = "item", amount = "count", other = "station"),
        events(
            "anvil", AnvilUsed::class, "A player used an anvil.",
            subject = "result", amount = "XP levels paid", other = "left input",
            detail = mapOf("right" to "right input", "renamed_to" to "new name"),
        ),
        events("grindstone", GrindstoneUsed::class, "A player removed enchantments with a grindstone.", subject = "result"),
        events(
            "enchant", ItemEnchanted::class, "A player enchanted an item.",
            subject = "item", amount = "XP levels paid", detail = mapOf("enchantments" to "enchantment → level"),
        ),
        events(
            "trade", Traded::class, "A player traded with a villager.",
            subject = "item received", amount = "count received", other = "villager profession",
            detail = mapOf(
                "cost_a" to "first payment",
                "cost_b" to "second payment",
                "level" to "villager level",
                "uses" to "offer uses",
                "max_uses" to "offer max uses",
                "villager" to "villager's UUID",
            ),
        ),
        events(
            "pickup", ItemsPickedUp::class, "Items a player picked up, summed over a window.",
            subject = "item", amount = "count", detail = mapOf("window_s" to "window in seconds"),
        ),
        events("drop", ItemsDropped::class, "A player threw items away.", subject = "item", amount = "count"),
        // Animals
        events(
            "fish", FishCaught::class, "A player reeled in a catch.",
            subject = "first item caught", amount = "items caught", other = "entity hooked",
            detail = mapOf("loot" to "items caught"),
        ),
        events("breed", AnimalsBred::class, "A player bred two animals.", subject = "animal"),
        events("tame", AnimalTamed::class, "A player tamed an animal.", subject = "animal", detail = mapOf("name" to "its name")),
        // Progress
        events("stat", StatAwarded::class, "A notable vanilla stat was awarded.", subject = "stat name", amount = "increment"),
        events(
            "advancement", AdvancementEarned::class, "A player earned an advancement.",
            subject = "advancement id", other = "task, goal or challenge", detail = mapOf("title" to "displayed title"),
        ),
        // Movement and places
        events(
            "dimension_change", DimensionChanged::class, "A player changed dimension; the position is the arrival.",
            subject = "arrival dimension", other = "departure dimension",
            detail = mapOf("via" to "nether_portal, end_portal, end_gateway, end_exit or other", "from" to "departure position"),
        ),
        events(
            "teleport", Teleported::class, "A player jumped 64+ blocks within a dimension; the position is the arrival.",
            amount = "distance", detail = mapOf("from" to "departure position"),
        ),
        events("sleep_start", SleepStarted::class, "A player lay down in a bed.", subject = "bed"),
        events("sleep_end", SleepEnded::class, "A player got up.", detail = mapOf("night_skipped" to "the night was skipped")),
        events(
            "spawn_set", RespawnPointSet::class, "A player's respawn point changed.",
            subject = "dimension of the point",
            detail = mapOf("forced" to "set by a command", "cleared" to "the point was removed", "point" to "the new point"),
        ),
        events("mount", MountChanged::class, "A player got on a vehicle or mount.", subject = "vehicle", detail = mapOf("vehicle_uuid" to "its UUID")),
        events("dismount", MountChanged::class, "A player got off a vehicle or mount.", subject = "vehicle", detail = mapOf("vehicle_uuid" to "its UUID")),
        events(
            "biome_enter", BiomeEntered::class, "A player entered a biome.",
            subject = "biome", detail = mapOf("first_visit" to "first time this player is in it"),
        ),
        events(
            "structure_enter", StructureEntered::class, "A player entered a structure.",
            subject = "structure", detail = mapOf("first_visit" to "first time this player is in one"),
        ),
        events("structure_leave", StructureLeft::class, "A player left a structure.", subject = "structure"),
        events("underground", UndergroundChanged::class, "A player went underground.", amount = "y"),
        events("surface", UndergroundChanged::class, "A player came back to the surface.", amount = "y"),
        // Player state
        events("health_low", HealthCritical::class, "A player's health fell to 4 or less.", amount = "health"),
        events("idle", IdleChanged::class, "A player became idle.", amount = "seconds idle"),
        events("active", IdleChanged::class, "A player is active again after being idle.", amount = "seconds idle"),
        events(
            "join", PlayerJoined::class, "A player joined (a session starts).",
            detail = mapOf("first_time" to "first time on this world"),
        ),
        events("leave", PlayerLeft::class, "A player left (the session ends).", amount = "session seconds", other = "leave or server_stop"),
        events(
            "respawn", PlayerRespawned::class, "A player respawned.",
            detail = mapOf("alive" to "not after a death: leaving the End"),
        ),
        events(
            "command", CommandIssued::class, "A player ran a command.",
            subject = "command root", detail = mapOf("text" to "the command line (null when redacted or a private message)"),
        ),
        // Other tables
        EventType("chat", ChatMessage::class, EventTable.CHAT, "A player wrote in the public chat or prayed (/pray)."),
        EventType("narrator_say", NarratorSpoke::class, EventTable.CHAT, "The Narrator (or an operator in its name) spoke."),
        EventType("whisper", ChatMessage::class, EventTable.PRIVATE_MESSAGES, "A private message between players."),
        EventType("position", PositionSampled::class, EventTable.POSITIONS, "A sampled player position and state."),
        EventType("stats_snapshot", StatsSnapshot::class, EventTable.STAT_TOTALS, "Vanilla stat totals that changed."),
    )

    private val byName: Map<String, EventType> = all.associateBy { it.name }

    /** The entry of [type]; null for an unknown type. */
    fun of(type: String): EventType? = byName[type]

    /** The entry of [event]'s type. Fails for a type missing from the catalog, which `EventCatalogTest` rules out. */
    fun of(event: GameEvent): EventType = byName[event.type] ?: error("event type missing from EventCatalog: ${event.type}")

    /** Types stored in [table], in catalog order. */
    fun types(table: EventTable): List<EventType> = all.filter { it.table == table }

    private fun events(
        name: String,
        eventClass: KClass<out GameEvent>,
        summary: String,
        subject: String? = null,
        amount: String? = null,
        other: String? = null,
        detail: Map<String, String> = emptyMap(),
    ) = EventType(name, eventClass, EventTable.EVENTS, summary, subject, amount, other, detail)
}
