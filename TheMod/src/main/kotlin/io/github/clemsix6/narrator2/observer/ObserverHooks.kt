package io.github.clemsix6.narrator2.observer

import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.AnimalTamed
import io.github.clemsix6.narrator2.event.AnimalsBred
import io.github.clemsix6.narrator2.event.AnvilUsed
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.ContainerOpened
import io.github.clemsix6.narrator2.event.EndPortalActivated
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.FishCaught
import io.github.clemsix6.narrator2.event.GrindstoneUsed
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemEnchanted
import io.github.clemsix6.narrator2.event.ItemsDropped
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.MountChanged
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.RespawnPointSet
import io.github.clemsix6.narrator2.event.StatAwarded
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.guard.ExplosionGuard
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.commands.arguments.selector.EntitySelector
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stat
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.npc.villager.VillagerDataHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerExplosion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * The part of the Observer fed by mixins (M1_M2 §7.2): blocks placed, stats (pickups, drops, notable
 * stats), consumption, crafting, anvil, grindstone, enchanting, trades, containers, advancements,
 * commands, respawn points, mounts, fishing, breeding, taming, projectiles, explosions, end portals and
 * death messages; and the stat snapshots and import (§7.6).
 *
 * The mixins of `narrator2.observer.mixins.json` call its `@JvmStatic` entry points on the server thread.
 * Each entry point is light: it copies plain values out of the game objects, publishes on the bus
 * (which never blocks) or hands them to an aggregator, and never throws into the game (everything runs
 * inside `ObserverState.hook`). Fabric's fake players are ignored; the players of in-game tests are
 * observed like real ones.
 */
object ObserverHooks {
    /** The running server's parts; replaced by [attach] when a server starts. */
    @Volatile
    private var attached: Parts? = null

    /** What this module keeps for one server. Server thread only. */
    private class Parts(val state: ObserverState) {
        val crafts = CraftCoalescer { state.publish(it) }
        val pickups = ItemFlowAggregator({ state.publish(it) }, ::now)
        val explosions = ExplosionCoalescer { state.publish(it) }
        val stats = StatsTracker { state.publish(it) }
        val lastUse = LastUse()

        fun tick(): Int = state.services.server.tickCount

        /** The Observer's clock, in milliseconds: server ticks × 50. */
        fun now(): Long = tick() * SamplerRules.MS_PER_TICK
    }

    /** Listeners that must run before the other modules' (T05's `PlayerLeft`, `DimensionChanged`). */
    private val BEFORE_OTHERS: Identifier = Identifier.fromNamespaceAndPath("narrator2", "observer_before")

    /** Listeners that must run after the other modules'. */
    private val AFTER_OTHERS: Identifier = Identifier.fromNamespaceAndPath("narrator2", "observer_after")

    /** Players within this distance of an explosion, in blocks, are "nearby". */
    private const val NEARBY_BLOCKS = 32.0

    /** Station of the crafting grid of the player's own inventory, which has no menu type. */
    private const val INVENTORY_STATION = "minecraft:inventory"

    /**
     * Registers the Fabric callbacks of this module. Once, at mod initialization, after
     * `FabricObserver.register()`. Handlers reach the running server through [ObserverState.current] and
     * do nothing without one.
     *
     * Order at a departure: this module's pending pickups and crafts are published before T05's
     * `PlayerLeft` (so the Journal gives them the session), the stats snapshot after it (so that every
     * subscriber sees the events in id order: publishing from a bus handler would deliver the snapshot
     * before `PlayerLeft` to the subscribers after it). Same for a dimension change: the pickups come
     * before `DimensionChanged`.
     */
    fun register() {
        UseBlockCallback.EVENT.register { player, level, _, hit -> usedBlock(player, level, hit) }
        UseEntityCallback.EVENT.register { player, level, _, entity, _ -> usedEntity(player, level, entity) }
        ServerTickEvents.END_SERVER_TICK.register { server -> on("observer_tick") { ticked(it, server) } }

        ServerPlayerEvents.LEAVE.addPhaseOrdering(BEFORE_OTHERS, Event.DEFAULT_PHASE)
        ServerPlayerEvents.LEAVE.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_OTHERS)
        ServerPlayerEvents.LEAVE.register(BEFORE_OTHERS) { player -> on("leave_flush") { leaving(it, player) } }
        ServerPlayerEvents.LEAVE.register(AFTER_OTHERS) { player -> on("leave_stats") { it.stats.left(player) } }

        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.addPhaseOrdering(BEFORE_OTHERS, Event.DEFAULT_PHASE)
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(BEFORE_OTHERS) { player, _, _ ->
            on("dimension_flush") { it.pickups.flush(player.uuid) }
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { old, player, _ -> on("respawn_point") { respawned(it, old, player) } }
    }

    /**
     * Creates the per-server parts for [state], registers the aggregators to flush at the stop, and
     * starts the import of an existing world's stats. When a server starts.
     */
    fun attach(state: ObserverState) {
        val parts = Parts(state)
        state.onStop(parts.crafts)
        state.onStop(parts.pickups)
        state.onStop(parts.explosions)
        attached = parts
        StatsImport.start(state.services) { state.publish(it) }
    }

    /** Runs [body] for hook [name] with the running server's parts, inside `ObserverState.hook`; nothing without a running server. */
    private inline fun on(name: String, body: (Parts) -> Unit) {
        val state = ObserverState.current() ?: return
        val parts = attached?.takeIf { it.state === state } ?: return
        state.hook(name) { body(parts) }
    }

    // ── Fabric callbacks ────────────────────────────────────────────────────────────────────────────

    /** Never interferes: always PASS. */
    private fun usedBlock(player: Player, level: Level, hit: BlockHitResult): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer && player !is FakePlayer) {
            on("use_block") { it.lastUse.block(player.uuid, hit.blockPos.toPos(level), it.tick()) }
        }
        return InteractionResult.PASS
    }

    /** Never interferes: always PASS. */
    private fun usedEntity(player: Player, level: Level, entity: Entity): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer && player !is FakePlayer) {
            on("use_entity") { it.lastUse.entity(player.uuid, EntityRef.of(entity), entity.blockPosition().toPos(level), it.tick()) }
        }
        return InteractionResult.PASS
    }

    private fun ticked(parts: Parts, server: MinecraftServer) {
        if (server !== parts.state.services.server) return
        parts.crafts.flush()
        parts.explosions.flush()
        parts.pickups.tick()
        if (server.tickCount % 20 == 0) parts.stats.check(server.playerList.players, parts.now())
    }

    private fun leaving(parts: Parts, player: ServerPlayer) {
        parts.crafts.flush(player.uuid)
        parts.pickups.flush(player.uuid)
        parts.lastUse.forget(player.uuid)
    }

    /**
     * A respawn copies the respawn point to the new player object (not a change, see [onRespawnPoint]),
     * except when its bed or anchor is gone: the point is then lost without any call to
     * `setRespawnPosition`.
     */
    private fun respawned(parts: Parts, old: ServerPlayer, player: ServerPlayer) {
        if (player is FakePlayer || old.respawnConfig == null || player.respawnConfig != null) return
        parts.state.publish(RespawnPointSet(player.ref(), player.pos(), point = null, forced = false, cleared = true))
    }

    // ── ServerPlayer ────────────────────────────────────────────────────────────────────────────────

    /** The hottest entry point: most stats only feed the totals and leave at the first test. */
    @JvmStatic
    fun onStat(player: ServerPlayer, stat: Stat<*>, amount: Int) {
        val route = StatRoutes.of(stat)
        if (route == StatRoute.TOTALS || amount <= 0) return
        on("stat") { parts ->
            when (route) {
                StatRoute.PICKUP -> parts.pickups.pickedUp(player.ref(), player.pos(), (stat.value as Item).id, amount)
                StatRoute.DROP -> parts.state.publish(ItemsDropped(player.ref(), player.pos(), (stat.value as Item).id, amount))
                StatRoute.ONE_BY_ONE -> parts.state.publish(StatAwarded(player.ref(), player.pos(), stat.name, amount))
                StatRoute.TOTALS -> Unit
            }
        }
    }

    /**
     * Where the menu is: the block entity or the entity that provides it, else what the player used
     * during this tick (a crafting table, an anvil, an ender chest, a double chest, a villager).
     */
    @JvmStatic
    fun onMenuOpened(player: ServerPlayer, provider: MenuProvider) = on("menu") { parts ->
        if (player is FakePlayer) return@on
        val menu = player.containerMenu
        val menuId = menuId(menu) ?: return@on
        val level = player.level()
        var pos: Pos? = null
        var target: String? = null
        when (provider) {
            is BlockEntity -> {
                pos = provider.blockPos.toPos(level)
                target = provider.blockState.blockId
            }
            is Entity -> {
                pos = provider.blockPosition().toPos(level)
                target = provider.typeId
            }
            else -> parts.lastUse.at(player.uuid, parts.tick())?.let { use ->
                pos = use.pos
                target = use.entity?.type ?: level.getBlockState(BlockPos(use.pos.x, use.pos.y, use.pos.z)).blockId
            }
        }
        // A default name is a translation key ("Chest", "Crafting"); a custom one is plain text.
        val title = provider.displayName.takeIf { it.contents !is TranslatableContents }?.string
        parts.state.publish(ContainerOpened(player.ref(), pos, menuId, title, target, player.isSpectator))
    }

    /**
     * [config] replaces the player's respawn point (null clears it). Skipped when it does not move, and
     * when the player object is not the one in the player list: that is a respawn copying the old
     * object's point to the new one (`PlayerList.respawn` → `copyRespawnPosition`), not a change.
     */
    @JvmStatic
    fun onRespawnPoint(player: ServerPlayer, config: ServerPlayer.RespawnConfig?) = on("respawn_point") { parts ->
        if (player is FakePlayer || player.level().server.playerList.getPlayer(player.uuid) !== player) return@on
        val old = player.respawnConfig
        val event = when {
            config == null && old == null -> return@on
            config == null -> RespawnPointSet(player.ref(), player.pos(), point = null, forced = false, cleared = true)
            old != null && old.isSamePosition(config) -> return@on
            else -> {
                val data = config.respawnData()
                val point = data.pos().let { Pos(data.dimension().identifier().toString(), it.x, it.y, it.z) }
                RespawnPointSet(player.ref(), player.pos(), point, config.forced(), cleared = false)
            }
        }
        parts.state.publish(event)
    }

    @JvmStatic
    fun onMounted(player: ServerPlayer, vehicle: Entity) = on("mount") { parts ->
        if (player is FakePlayer) return@on
        parts.state.publish(MountChanged(player.ref(), player.pos(), EntityRef.of(vehicle), mounted = true))
    }

    /** Also called at a death, a dimension change or a teleport, all real dismounts; not for a player who disconnected. */
    @JvmStatic
    fun onDismounting(player: ServerPlayer) = on("dismount") { parts ->
        val vehicle = player.vehicle ?: return@on
        if (player is FakePlayer || player.hasDisconnected()) return@on
        parts.state.publish(MountChanged(player.ref(), player.pos(), EntityRef.of(vehicle), mounted = false))
    }

    /**
     * The player is about to die. Their pending pickups and crafts are published first, and the full
     * death message is kept for T05's `PlayerDied` (`DeathMessages`): at the end of `die`, the combat
     * tracker has forgotten the fight's context.
     */
    @JvmStatic
    fun onDying(player: ServerPlayer) = on("dying") { parts ->
        if (player is FakePlayer) return@on
        parts.pickups.flush(player.uuid)
        parts.crafts.flush(player.uuid)
        DeathMessages.put(player.uuid, player.combatTracker.deathMessage.string)
    }

    // ── Blocks ──────────────────────────────────────────────────────────────────────────────────────

    /** The block is in place: what is at [pos] is what was placed (a nether portal when fire lit one). */
    @JvmStatic
    fun onPlaced(player: ServerPlayer, pos: BlockPos, stack: ItemStack) = on("place") { parts ->
        if (player is FakePlayer) return@on
        val level = player.level()
        val placed = level.getBlockState(pos)
        val kind = when (stack.item) {
            is BucketItem -> PlaceKind.FLUID
            is FlintAndSteelItem -> if (placed.`is`(Blocks.NETHER_PORTAL)) PlaceKind.PORTAL else PlaceKind.FIRE
            else -> PlaceKind.BLOCK
        }
        parts.state.publish(BlockPlaced(player.ref(), pos.toPos(level), placed.blockId, stack.id, kind))
        if (kind == PlaceKind.BLOCK) PlacedBlocks.mark(level, pos, placed, player.uuid)
    }

    @JvmStatic
    fun onEndPortalLit(context: UseOnContext) = on("end_portal") { parts ->
        val player = context.player as? ServerPlayer ?: return@on
        if (player is FakePlayer) return@on
        parts.state.publish(EndPortalActivated(player.ref(), context.clickedPos.toPos(context.level)))
    }

    /** A container still had its loot table: located by what the player used this tick, else where they stand. */
    @JvmStatic
    fun onLootOpened(player: ServerPlayer, lootTable: ResourceKey<LootTable>) = on("loot") { parts ->
        if (player is FakePlayer) return@on
        val pos = parts.lastUse.at(player.uuid, parts.tick())?.pos ?: player.pos()
        parts.state.publish(LootChestOpened(player.ref(), pos, lootTable.identifier().toString()))
    }

    // ── Items ───────────────────────────────────────────────────────────────────────────────────────

    /** Before the effects and before the stack shrinks. */
    @JvmStatic
    fun onConsumed(player: ServerPlayer, stack: ItemStack) = on("consume") { parts ->
        if (player is FakePlayer) return@on
        val food = stack.get(DataComponents.FOOD)
        val effects = ArrayList<String>(0)
        stack.get(DataComponents.POTION_CONTENTS)?.allEffects?.forEach { effects += it.effect.registeredName }
        stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS)?.effects()?.forEach { effects += it.effect().registeredName }
        parts.state.publish(
            ItemConsumed(
                player.ref(), player.pos(), stack.id, stack.count,
                nutrition = food?.nutrition(),
                saturation = food?.saturation()?.toDouble(),
                effects = effects,
                foodBefore = player.foodData.foodLevel,
                healthBefore = player.health.toDouble(),
            ),
        )
    }

    /** Merged per tick by [CraftCoalescer]. Trades also count as crafts in vanilla: they are [onTrade]'s. */
    @JvmStatic
    fun onCrafted(player: ServerPlayer, result: ItemStack, amount: Int) = on("craft") { parts ->
        if (player is FakePlayer) return@on
        val menu = player.containerMenu
        if (menu is MerchantMenu) return@on
        val station = if (menu is InventoryMenu) INVENTORY_STATION else menuId(menu) ?: return@on
        parts.crafts.add(player.ref(), player.pos(), result.id, amount, station)
    }

    /** Both inputs and the cost are still in the menu. */
    @JvmStatic
    fun onAnvil(player: ServerPlayer, menu: AnvilMenu, result: ItemStack) = on("anvil") { parts ->
        if (player is FakePlayer || result.isEmpty) return@on
        val left = menu.getSlot(AnvilMenu.INPUT_SLOT).item
        val right = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).item
        val name = result.get(DataComponents.CUSTOM_NAME)
        val renamedTo = name?.takeIf { it != left.get(DataComponents.CUSTOM_NAME) }?.string
        parts.state.publish(AnvilUsed(player.ref(), player.pos(), result.id, left.id, right.idOrNull, menu.cost, renamedTo))
    }

    @JvmStatic
    fun onGrindstone(player: ServerPlayer, result: ItemStack) = on("grindstone") { parts ->
        if (player is FakePlayer || result.isEmpty) return@on
        parts.state.publish(GrindstoneUsed(player.ref(), player.pos(), result.id))
    }

    /** The item is enchanted already; a book keeps its enchantments as stored ones. */
    @JvmStatic
    fun onEnchanted(player: ServerPlayer, stack: ItemStack, cost: Int) = on("enchant") { parts ->
        if (player is FakePlayer) return@on
        val enchantments = stack.get(DataComponents.ENCHANTMENTS)?.takeUnless { it.isEmpty } ?: stack.get(DataComponents.STORED_ENCHANTMENTS)
        val levels = LinkedHashMap<String, Int>()
        enchantments?.entrySet()?.forEach { levels[it.key.registeredName] = it.intValue }
        parts.state.publish(ItemEnchanted(player.ref(), player.pos(), stack.id, levels, cost))
    }

    /** The offer's uses are not increased yet: this trade is use `uses + 1`. */
    @JvmStatic
    fun onTrade(player: ServerPlayer, villager: AbstractVillager, offer: MerchantOffer) = on("trade") { parts ->
        if (player is FakePlayer) return@on
        val data = (villager as? VillagerDataHolder)?.villagerData
        val costB = offer.costB
        parts.state.publish(
            Traded(
                player.ref(), villager.blockPosition().toPos(villager.level()),
                result = offer.result.let { ItemCount(it.id, it.count) },
                costA = offer.costA.let { ItemCount(it.id, it.count) },
                costB = if (costB.isEmpty) null else ItemCount(costB.id, costB.count),
                villager = EntityRef.of(villager),
                profession = data?.profession()?.registeredName,
                level = data?.level(),
                uses = offer.uses + 1,
                maxUses = offer.maxUses,
            ),
        )
    }

    // ── Progress and commands ───────────────────────────────────────────────────────────────────────

    /** Recipes are advancements without display: not told. */
    @JvmStatic
    fun onAdvancement(player: ServerPlayer, advancement: AdvancementHolder) = on("advancement") { parts ->
        if (player is FakePlayer) return@on
        val display = advancement.value().display().orElse(null) ?: return@on
        parts.state.publish(
            AdvancementEarned(player.ref(), player.pos(), advancement.id().toString(), display.title().string, display.type().serializedName),
        )
    }

    /**
     * A command typed by a player, valid or not (an attempt is a fact). Its text is kept only for the
     * vanilla commands that carry no private message, anywhere in the line ([CommandPrivacy]); the
     * text of a vanilla private message only goes to a [ChatChannel.WHISPER] message, never shown to
     * the model.
     */
    @JvmStatic
    fun onCommand(player: ServerPlayer, command: String) = on("command") { parts ->
        if (player is FakePlayer) return@on
        val line = command.trim().removePrefix("/")
        val root = line.substringBefore(' ').lowercase()
        if (root.isEmpty()) return@on
        val config = parts.state.config
        val withheld = (config.privateMessageCommands + config.redactedCommands).mapTo(HashSet(), CommandPrivacy::bare)
        val text = CommandPrivacy.text(line, withheld) { player.level().server.commands.dispatcher.parse(line, player.createCommandSourceStack()) }
        parts.state.publish(CommandIssued(player.ref(), player.pos(), root, text))
        if (CommandPrivacy.bare(root) in CommandIssued.PRIVATE_MESSAGE_COMMANDS) whisper(player, line)?.let { parts.state.publish(it) }
    }

    /**
     * The private message of [line] (`msg`, `tell`, `w`, `teammsg`, `tm`), parsed with the server's own
     * commands: its text and the names of its recipients. Null when the command would not send anything
     * (unknown player, no team, syntax error). Only for these commands, which are rare.
     */
    private fun whisper(player: ServerPlayer, line: String): ChatMessage? {
        val server = player.level().server
        val source = player.createCommandSourceStack()
        var context = server.commands.dispatcher.parse(line, source).context
        // `tell`, `w` and `tm` redirect to `msg` and `teammsg`: their arguments are in the last child.
        while (context.child != null) context = context.child
        val message = context.arguments["message"] ?: return null
        val recipients = when (val targets = context.arguments["targets"]?.result) {
            is EntitySelector -> try {
                targets.findPlayers(source)
            } catch (e: CommandSyntaxException) {
                return null
            }
            else -> {
                val team = player.team ?: return null
                server.playerList.players.filter { it !== player && it.team === team }
            }
        }
        if (recipients.isEmpty()) return null
        return ChatMessage(player.ref(), ChatChannel.WHISPER, message.range.get(line), recipients.map { it.plainTextName })
    }

    // ── Animals ─────────────────────────────────────────────────────────────────────────────────────

    /** Empty loot: an entity was reeled in. */
    @JvmStatic
    fun onFished(player: ServerPlayer, hook: FishingHook, loot: Collection<ItemStack>) = on("fish") { parts ->
        if (player is FakePlayer) return@on
        val items = loot.filterNot { it.isEmpty }.map { ItemCount(it.id, it.count) }
        parts.state.publish(FishCaught(player.ref(), player.pos(), items, hook.hookedIn?.let { EntityRef.of(it) }))
    }

    @JvmStatic
    fun onBred(player: ServerPlayer, parent: Animal) = on("breed") { parts ->
        if (player is FakePlayer) return@on
        parts.state.publish(AnimalsBred(player.ref(), parent.blockPosition().toPos(parent.level()), parent.typeId))
    }

    @JvmStatic
    fun onTamed(player: ServerPlayer, animal: Animal) = on("tame") { parts ->
        if (player is FakePlayer) return@on
        parts.state.publish(AnimalTamed(player.ref(), animal.blockPosition().toPos(animal.level()), animal.typeId, animal.customName?.string))
    }

    // ── Projectiles and explosions ──────────────────────────────────────────────────────────────────

    /** Only projectiles shot by a player or hitting one; fishing hooks are the fishing's. */
    @JvmStatic
    fun onProjectileHit(projectile: Projectile, hit: HitResult) {
        val level = projectile.level() as? ServerLevel ?: return
        if (projectile is FishingHook) return
        on("projectile") { parts ->
            val shooter = projectile.owner
            val target = (hit as? EntityHitResult)?.entity
            val player = (shooter as? ServerPlayer)?.takeUnless { it is FakePlayer }
                ?: (target as? ServerPlayer)?.takeUnless { it is FakePlayer }
                ?: return@on
            val blockPos = (hit as? BlockHitResult)?.blockPos
            val pos = target?.blockPosition() ?: blockPos ?: BlockPos.containing(hit.location)
            parts.state.publish(
                ProjectileHit(
                    player.ref(), pos.toPos(level), projectile.typeId,
                    shooter = shooter?.let { EntityRef.of(it) },
                    target = target?.let { EntityRef.of(it) },
                    block = blockPos?.let { level.getBlockState(it).blockId },
                    weapon = projectile.weaponItem?.idOrNull,
                ),
            )
        }
    }

    /**
     * Counts the blocks an explosion is about to hit (the positions still hold a block) and, among them,
     * those placed by players. Returns `[hit, placed]`, or null when the Observer is not running.
     */
    @JvmStatic
    fun explodedBlocks(level: ServerLevel, positions: List<BlockPos>): IntArray? {
        var counts: IntArray? = null
        on("explosion_blocks") {
            var hit = 0
            var placed = 0
            for (pos in positions) {
                if (level.getBlockState(pos).isAir) continue
                hit++
                if (PlacedBlocks.placerAt(level, pos) != null) placed++
            }
            counts = intArrayOf(hit, placed)
        }
        return counts
    }

    /**
     * An explosion went off; [blocks] is what [explodedBlocks] counted (null when it touched no block).
     * Kept when a player caused it, a player is nearby, or a player's block was hit; the small "trigger"
     * explosions of wind charges and breezes only when a player caused them. Merged per tick and zone.
     */
    @JvmStatic
    fun onExplosion(explosion: ServerExplosion, blocks: IntArray?) = on("explosion") { parts ->
        val level = explosion.level()
        val center = explosion.center()
        val direct = explosion.directSourceEntity
        val author = explosion.indirectSourceEntity
        val byPlayer = author is ServerPlayer && author !is FakePlayer
        val interaction = explosion.blockInteraction
        if (interaction == Explosion.BlockInteraction.TRIGGER_BLOCK && !byPlayer) return@on
        val nearby = level.players().filter { it !is FakePlayer && it.distanceToSqr(center) <= NEARBY_BLOCKS * NEARBY_BLOCKS }.map { it.ref() }
        val playerBlocksHit = blocks?.get(1) ?: 0
        if (!byPlayer && nearby.isEmpty() && playerBlocksHit == 0) return@on
        parts.explosions.add(
            ExplosionHappened(
                BlockPos.containing(center).toPos(level),
                source = direct?.typeId,
                author = author?.let { EntityRef.of(it) },
                radius = explosion.radius().toDouble(),
                interaction = interaction.name.lowercase(),
                blocksHit = blocks?.get(0) ?: 0,
                playerBlocksHit = playerBlocksHit,
                nearby = nearby,
                // The Narrator's entities carry its tag (its creatures, whatever its tools spawn); the explosion
                // tool has no source entity, and goes off inside ExplosionGuard.narrators, which we are still in.
                narrator = ExplosionGuard.isNarrators(explosion),
            ),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** Registry id of [menu]'s type; null for a menu without type (the player's own inventory). */
    private fun menuId(menu: AbstractContainerMenu): String? {
        if (menu is InventoryMenu) return null
        return try {
            BuiltInRegistries.MENU.getKey(menu.type)?.toString()
        } catch (e: UnsupportedOperationException) {
            null
        }
    }
}
