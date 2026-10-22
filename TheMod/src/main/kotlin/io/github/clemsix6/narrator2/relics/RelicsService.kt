package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.config.RelicsConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.guard.clockTime
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.oracle.PlayerReport
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.ToolText.number
import io.github.clemsix6.narrator2.tool.action.SpawnTool
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** A champion in the world: its sheet, its draft, and the tally of its fight (damage by whom, damage to whom). */
class LiveChampion(val row: ChampionRow, val draft: ChampionDraft) {
    /** Its creature now: a conversion (a zombie drowned) makes a new one. */
    @Volatile
    var entity: UUID = row.entity

    /** Damage it took, by who dealt it: a player's name, else a creature's type or a cause. */
    val hits = ConcurrentHashMap<String, Float>()

    /** Damage it dealt to players, by player. */
    val dealt = ConcurrentHashMap<String, Float>()
}

/**
 * The armorer's work in the world (M7-RELICS): champions, the relics their killers earn, and the chests' reserves of
 * relics, strange consumables and books.
 *
 * - **Champions.** `summon_champion` (or `/narrator champion`) checks the caps (one per player, `relics.maxChampions` in
 *   all, those being designed counted) and the guards ([Verdict], category `creatures`), then hands the design to the
 *   armorer ([Armorer]). Its `create_champion` summons it through the `spawn` tool, with its guards, caps and marks
 *   (a creature of the Narrator's: it vanishes when due, its blows never kill, it goes for its player alone), then
 *   dresses it (gear, lasting effects, glow, the tag [TAG]) and keeps its sheet (`champions`).
 * - **Its death** ([onDeath]): killed by a player, its gear drops whole and the armorer forges that player's relic, given
 *   at once (at their feet when the inventory is full); killed otherwise, nothing but the creature's own loot. Without
 *   Claude, or when the armorer fails, its gear alone drops. Each end is a `CHAMPION` line to the Director.
 * - **The reserves** ([onChestLoot]): objects prepared ahead (`relic_stock`), drawn into a structure chest's loot when it is
 *   first opened, each kind with its chance; the player who opened it, when known, makes a `TREASURE` line. When a
 *   reserve falls under half its target, one restock task fills them all ([maybeRestock]): at the start, then after
 *   a chest took something; one at a time, and not again within [RETRY_MS] of one that made nothing.
 *
 * Hooks run on the server thread; the database and Claude never do.
 */
class RelicsService(private val services: NarratorServices) {
    private val clock = services.clock
    private val store = RelicsStore(services.journal)
    private val armorer = Armorer(services)
    private val runner = ToolRunner(services)
    private val stock: Map<StockKind, ConcurrentLinkedDeque<StockRow>> = StockKind.entries.associateWith { ConcurrentLinkedDeque<StockRow>() }
    private val champions = ConcurrentHashMap<UUID, LiveChampion>()
    private val designing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val nextChampion = AtomicLong(1)

    @Volatile
    private var ready = false

    @Volatile
    private var failure: String? = null

    @Volatile
    private var restocking: Job? = null

    @Volatile
    private var restockFailedAt = 0L

    /** The last loot context drawn from: a chest's nested tables share it, and draw once. Server thread. */
    private var lastContext: WeakReference<LootContext>? = null

    private val config: RelicsConfig get() = services.settings.get().config.relics

    /** Why champions, relics and reserves do not work now; null when they do. */
    val unavailableReason: String?
        get() = when {
            !config.enabled -> "relics.enabled is false"
            failure != null -> failure
            !ready -> "the reserves are loading"
            services.phase != Phase.RUNNING -> "the server is stopping"
            else -> null
        }

    val available: Boolean get() = unavailableReason == null

    /** The armorer's job of the conversation [conversationId], for its tools. */
    fun job(conversationId: String): ArmorerJob? = armorer.job(conversationId)

    /** Opens the tables and loads the reserves and the champions still standing, then restocks if need be. */
    fun start() {
        services.scope.launch {
            try {
                store.open()
                load()
                ready = true
                log.info("ready: ${stockLine()}; ${champions.size} champions standing")
                maybeRestock("the server started")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "the reserves could not be loaded: ${e.message ?: e.javaClass.simpleName}"
                log.error("cannot load the reserves and champions", e)
            }
        }
    }

    private suspend fun load() {
        for (row in store.stock()) stock.getValue(row.kind).addLast(row)
        val now = clock.instant()
        for (row in store.activeChampions()) {
            val draft = decode(ChampionDraft.serializer(), row.spec)
            if (row.expiresAt.isBefore(now) || draft == null) {
                store.ended(row.id, "gone", now, null, null)
                continue
            }
            champions[row.entity] = LiveChampion(row, draft)
        }
        nextChampion.set((store.lastChampions(1).firstOrNull()?.id ?: 0) + 1)
    }

    // ── Champions ───────────────────────────────────────────────────────────────────────────────────

    /**
     * `summon_champion` and `/narrator champion`: a champion for the online player [name], as [brief] says, asked by
     * [caller] ([by] for the logs). Checks the caps and the guards, then starts the design and answers at once.
     */
    suspend fun summon(name: String, brief: String, caller: Caller, by: String): ToolResult = answer {
        unavailableReason?.let { refuse(ToolResult.failed("the armorer is unavailable: $it")) }
        if (services.settings.get().claude == null) refuse(ToolResult.failed("no LLMGW key: the armorer cannot design a champion"))
        val player = services.players.byName(name)?.ref
            ?: refuse(ToolResult.invalid("${ModelText.name(name)} is not online; online: ${ToolText.names(services.players.online.map { it.ref })}"))
        capRefusal(player)?.let { refuse(it) }
        val verdict = services.server.onServerThread {
            services.server.playerList.getPlayer(player.uuid)?.let { services.guards.vet(it, caller, PunishmentCategory.CREATURES) }
        } ?: refuse(ToolResult.failed("${player.shownName} left the game"))
        if (verdict is Verdict.Denied) refuse(ToolResult.denied(verdict))
        if (!designing.add(player.uuid)) refuse(ToolResult.denied(CHAMPION_LIMIT, "a champion for ${player.shownName} is already being designed."))
        val job = ArmorerJob.Champion(player, brief.trim(), by)
        services.scope.launch { championJob(job) }
        Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_commissioned", player.name, "by $by: ${ModelText.cut(brief, 200)}")
        ToolResult.ok(
            "ok: the armorer designs a champion for ${player.shownName}; it appears near them in a minute or two, and a CHAMPION " +
                "event tells you what it looks like, then how it ends",
            targets = listOf(player),
        )
    }

    /** Why [player] cannot get a champion now (the caps), or null. */
    private fun capRefusal(player: PlayerRef): ToolResult? {
        val cfg = config
        val standing = standing()
        val theirs = standing.filter { it.row.player == player.uuid }
        if (theirs.size >= cfg.maxChampionsPerPlayer) {
            val one = theirs.first()
            return ToolResult.denied(
                CHAMPION_LIMIT,
                "${player.shownName} already faces champion « ${ModelText.escape(one.row.name)} » until ${clockTime(clock, one.row.expiresAt.toEpochMilli())}.",
            )
        }
        if (standing.size + designing.size >= cfg.maxChampions) {
            return ToolResult.denied(CHAMPION_LIMIT, "${cfg.maxChampions} champions at most at once: ${standing.size} standing, ${designing.size} being designed.")
        }
        return null
    }

    /** The champions standing now: alive and before their end. */
    private fun standing(): List<LiveChampion> {
        val now = clock.instant()
        return champions.values.filter { it.row.expiresAt.isAfter(now) }
    }

    private suspend fun championJob(job: ArmorerJob.Champion) {
        try {
            val opening = services.server.onServerThread { championOpening(job) }
            if (opening == null) {
                post(MechanicSignal.CHAMPION, "the armorer made no champion for ${job.player.shownName}: they left the game")
                return
            }
            val ending = armorer.run(job, opening, CHAMPION_STEPS, JOB_TIMEOUT_MS)
            if (job.champion == null) {
                val why = job.lastRefusal ?: ending.text
                log.warn("no champion for ${job.player.name}: $why")
                post(MechanicSignal.CHAMPION, "the armorer made no champion for ${job.player.shownName}: ${ModelText.cut(why, 300)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("the champion of ${job.player.name} failed", e)
        } finally {
            designing.remove(job.player.uuid)
        }
    }

    /** The first message of a champion's task: who asked what, the player as they are now. Null when they left. Server thread. */
    private fun championOpening(job: ArmorerJob.Champion): String? {
        val player = services.server.playerList.getPlayer(job.player.uuid) ?: return null
        val report = PlayerReport(player)
        val brief = job.brief.ifEmpty { "(no brief: surprise them)" }
        return buildString {
            append("<task kind=\"champion\" for=\"${ModelText.attribute(job.player.name)}\" asked_by=\"${ModelText.attribute(job.by)}\">\n")
            append(ModelText.lines(brief)).append("\n</task>\n")
            append("<player>\n").append(report.header(null, false)).append('\n')
            report.position().forEach { append(it).append('\n') }
            append(report.equipment()).append("\n</player>\n")
            append(context())
        }
    }

    /**
     * `create_champion`: the champion of the task in [ctx], made from [draft] (`RelicRules`, the gear through `RelicItems`),
     * summoned by `spawn` with its guards, then dressed and kept.
     */
    suspend fun createChampion(ctx: ToolContext, draft: ChampionDraft): ToolResult = answer {
        val job = armorer.job(ctx.caller.conversationId) as? ArmorerJob.Champion
            ?: refuse(ToolResult.invalid("this task asks for no champion: ${taskSays(ctx)}"))
        if (job.champion != null) refuse(ToolResult.invalid("your champion already stands: end with your one-line report"))
        val problems = RelicRules.champion(draft)
        if (problems.isNotEmpty()) refuse(ToolResult.invalid(problems.joinToString("; ")))
        val gear = ctx.onServer { server ->
            RelicRules.pieces(draft.gear).map { (slot, piece) -> EquipmentSlot.byName(slot).let { it to RelicItems.gear(server, piece, it) } }
        }
        val minutes = config.championMinutes
        val name = Speech.fit(Speech.clean(draft.name), NAME_SHOWN_MAX)
        val spawned = runner.invoke(SpawnTool(), ctx.caller, spawnArgs(job, draft, name, minutes))
        if (spawned.status != ToolStatus.OK) refuse(spawned)
        val now = clock.instant()
        val row = ChampionRow(
            nextChampion.getAndIncrement(), UUID(0, 0), job.player.uuid, job.player.name, draft.mob.id, name,
            json.encodeToString(ChampionDraft.serializer(), draft), now, now.plusSeconds(minutes * 60L), "active",
        )
        val placed = ctx.onServer { server -> dress(server, spawned, draft, gear, row) }
            ?: refuse(ToolResult.failed("the champion vanished as it appeared"))
        val live = placed.first
        job.champion = live
        store.addChampion(live.row.copy(entity = live.entity), ctx.caller.conversationId)
        val until = clockTime(clock, row.expiresAt.toEpochMilli())
        val look = RelicItems.describe(draft)
        Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_summoned", job.player.name, "$look at ${placed.second} until $until")
        post(
            MechanicSignal.CHAMPION,
            "${ModelText.escape(look)} appeared near ${job.player.shownName} (${placed.second}) until $until, and goes for them: " +
                "« ${ModelText.escape(ModelText.cut(Speech.clean(draft.lore), 300))} »; whoever kills it gets a relic",
        )
        ToolResult.ok("ok: champion ${ModelText.escape(look)} stands near ${job.player.shownName} at ${placed.second} until $until; the Narrator is told. End with your one-line report.", listOf(job.player))
    }

    /** The arguments of `spawn` for a champion: its creature, its name, its factors, sent at its player. */
    private fun spawnArgs(job: ArmorerJob.Champion, draft: ChampionDraft, name: String, minutes: Int): String = buildJsonObject {
        put("entity", draft.mob.id)
        put("near", job.player.name)
        put("distance", SPAWN_DISTANCE)
        put("name", name)
        put("target", true)
        put("lifetime_minutes", minutes)
        putJsonArray("attributes") {
            addJsonObject {
                put("attribute", "max_health")
                put("multiply", draft.health)
            }
            if (draft.damage > 1.0) {
                addJsonObject {
                    put("attribute", "attack_damage")
                    put("multiply", draft.damage)
                }
            }
            if (draft.speed > 1.0) {
                addJsonObject {
                    put("attribute", "movement_speed")
                    put("multiply", draft.speed)
                }
            }
        }
    }.toString()

    /**
     * The champion among what `spawn` brought ([spawned]'s creatures: the one of its kind, not a jockey's mount), dressed:
     * its gear (dropped by this service, whole, when a player kills it: the game drops none), its lasting effects, its
     * glow and tag; then kept. Its place, or null when it is gone already. Server thread.
     */
    private fun dress(server: MinecraftServer, spawned: ToolResult, draft: ChampionDraft, gear: List<Pair<EquipmentSlot, ItemStack>>, row: ChampionRow): Pair<LiveChampion, String>? {
        val mobs = spawned.entities.mapNotNull { uuid -> server.allLevels.firstNotNullOfOrNull { it.getEntity(uuid) } as? Mob }
        val mob = mobs.firstOrNull { EntityType.getKey(it.type).path == draft.mob.id } ?: mobs.firstOrNull() ?: return null
        for ((slot, stack) in gear) {
            mob.setItemSlot(slot, stack)
            mob.setDropChance(slot, 0f)
        }
        for (entry in draft.effects) mob.addEffect(MobEffectInstance(RelicItems.effect(entry.effect.id), MobEffectInstance.INFINITE_DURATION, entry.level - 1, false, true))
        mob.health = mob.maxHealth
        mob.addTag(TAG)
        mob.setGlowingTag(true)
        val live = LiveChampion(row.copy(entity = mob.uuid), draft)
        champions[mob.uuid] = live
        val where = "${ToolText.dimension(mob.level().dimension().identifier().toString())} ${mob.blockX} ${mob.blockY} ${mob.blockZ}"
        return live to where
    }

    /**
     * A creature died (`AFTER_DEATH`). A champion killed by a player drops its gear whole, and the armorer forges that
     * player's relic; killed otherwise, nothing more. Server thread.
     */
    fun onDeath(entity: LivingEntity, source: DamageSource) {
        if (champions.isEmpty()) return
        val live = champions.remove(entity.uuid) ?: return
        val level = entity.level() as? ServerLevel ?: return
        val killer = (source.entity as? ServerPlayer) ?: (entity.lastHurtByPlayer as? ServerPlayer)
        if (killer != null) {
            for (slot in EquipmentSlot.VALUES) {
                val stack = entity.getItemBySlot(slot)
                if (stack.isEmpty) continue
                entity.spawnAtLocation(level, stack.copy())
                entity.setItemSlot(slot, ItemStack.EMPTY)
            }
        }
        val now = clock.instant()
        val fight = fight(live, source, killer, now)
        val where = "${ToolText.dimension(level.dimension().identifier().toString())} ${entity.blockX} ${entity.blockY} ${entity.blockZ}"
        val ref = killer?.ref()
        services.scope.launch { ended(live, ref, fight, where, now) }
    }

    /** How the fight went, in one line: its length, who hit it how hard, what it dealt, the last blow. */
    private fun fight(live: LiveChampion, source: DamageSource, killer: ServerPlayer?, now: Instant): String {
        val took = ToolText.duration(now.toEpochMilli() - live.row.spawnedAt.toEpochMilli())
        val hits = live.hits.entries.sortedByDescending { it.value }.joinToString { "${ModelText.name(it.key)} ${number(it.value.toDouble())}" }.ifEmpty { "nobody" }
        val dealt = live.dealt.entries.sortedByDescending { it.value }.joinToString { "${ModelText.name(it.key)} ${number(it.value.toDouble())}" }.ifEmpty { "nobody" }
        val blow = source.typeHolder().registeredName.removePrefix("minecraft:") +
            (killer?.mainHandItem?.takeUnless { it.isEmpty }?.let { " with ${it.typeHolder().registeredName.removePrefix("minecraft:")}" } ?: "")
        return "lasted $took; damage it took: $hits; damage it dealt: $dealt; last blow: $blow"
    }

    /** The end of [live] at [now]: recorded, then the relic of its [killer], and the Director told. */
    private suspend fun ended(live: LiveChampion, killer: PlayerRef?, fight: String, where: String, now: Instant) {
        val name = "« ${ModelText.escape(live.row.name)} » (${live.row.mob})"
        try {
            store.ended(live.row.id, if (killer != null) "slain" else "died", now, killer?.name, fight)
            if (killer == null) {
                Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_died", live.row.playerName, "${live.row.name}: $fight")
                post(MechanicSignal.CHAMPION, "$name died at $where without a player's blow: no relic, its gear lost ($fight)")
                return
            }
            Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_slain", killer.name, "${live.row.name} at $where: $fight")
            val head = "${killer.shownName} slew $name at $where ($fight); its gear dropped"
            val absent = unavailableReason ?: if (services.settings.get().claude == null) "no LLMGW key" else null
            if (absent != null) {
                post(MechanicSignal.CHAMPION, "$head; no relic: the armorer cannot work ($absent)")
                return
            }
            val job = ArmorerJob.Relic(killer, live, fight)
            val opening = services.server.onServerThread { relicOpening(job) }
            val ending = armorer.run(job, opening, RELIC_STEPS, JOB_TIMEOUT_MS)
            val given = job.given
            post(MechanicSignal.CHAMPION, if (given != null) "$head; relic given to ${killer.shownName}: $given" else "$head; no relic: ${ModelText.cut(job.lastRefusal ?: ending.text, 300)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("the end of champion ${live.row.id} failed", e)
        }
    }

    /** The first message of a relic's task: the champion, the fight, the killer as they are now. Server thread. */
    private fun relicOpening(job: ArmorerJob.Relic): String {
        val champion = job.champion
        val draft = champion.draft
        val killer = services.server.playerList.getPlayer(job.killer.uuid)
        return buildString {
            append("<task kind=\"relic\" for=\"${ModelText.attribute(job.killer.name)}\">\n")
            append("${job.killer.shownName} slew the champion below: forge the relic they earned.\n</task>\n")
            append("<champion name=\"${ModelText.attribute(champion.row.name)}\" made_for=\"${ModelText.attribute(champion.row.playerName)}\">\n")
            append(ModelText.escape(RelicItems.describe(draft))).append('\n')
            append(ModelText.lines(Speech.clean(draft.lore))).append("\n</champion>\n")
            append("<fight>").append(job.fight).append("</fight>\n")
            if (killer != null) {
                val report = PlayerReport(killer)
                append("<player>\n").append(report.header(null, false)).append('\n').append(report.equipment()).append("\n</player>\n")
            }
            append(context())
        }
    }

    /** A creature took damage (`AFTER_DAMAGE`): the tally of a champion's fight. Server thread. */
    fun onDamage(entity: LivingEntity, source: DamageSource, taken: Float) {
        if (champions.isEmpty() || taken <= 0f) return
        champions[entity.uuid]?.let { live ->
            val who = (source.entity as? ServerPlayer)?.plainTextName ?: source.entity?.let { EntityType.getKey(it.type).path } ?: source.typeHolder().registeredName.removePrefix("minecraft:")
            live.hits.merge(who, taken, Float::plus)
            return
        }
        if (entity !is ServerPlayer) return
        val attacker = source.entity ?: return
        champions[attacker.uuid]?.dealt?.merge(entity.plainTextName, taken, Float::plus)
    }

    /** A creature was removed (`ENTITY_UNLOAD`): a champion sent away (its time, its player died or left) is gone. Server thread. */
    fun onUnload(entity: Entity) {
        if (champions.isEmpty() || entity.removalReason != Entity.RemovalReason.DISCARDED) return
        val live = champions.remove(entity.uuid) ?: return
        val now = clock.instant()
        services.scope.launch {
            store.ended(live.row.id, "gone", now, null, null)
            Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_gone", live.row.playerName, live.row.name)
            if (now.isBefore(live.row.expiresAt.plusSeconds(GONE_TOLD_SECONDS))) {
                val why = if (now.isBefore(live.row.expiresAt)) "sent away before its time (its player died, left or was pardoned)" else "its time ran out"
                post(MechanicSignal.CHAMPION, "« ${ModelText.escape(live.row.name)} » (${live.row.mob}) is gone, unslain: $why")
            }
        }
    }

    /** A creature converted (`MOB_CONVERSION`: a zombie drowned): a champion stays one in its new body. Server thread. */
    fun onConverted(previous: Mob, converted: Mob) {
        if (champions.isEmpty()) return
        val live = champions.remove(previous.uuid) ?: return
        live.entity = converted.uuid
        converted.addTag(TAG)
        converted.setGlowingTag(true)
        EquipmentSlot.VALUES.forEach { converted.setDropChance(it, 0f) }
        champions[converted.uuid] = live
        services.scope.launch { store.moved(live.row.id, converted.uuid) }
    }

    // ── Relics, consumables, books ──────────────────────────────────────────────────────────────────

    /** `create_relic`: a relic task's relic, given to the killer; a restock's, put in the reserve. */
    suspend fun createRelic(ctx: ToolContext, draft: RelicDraft): ToolResult = answer {
        when (val job = armorer.job(ctx.caller.conversationId)) {
            is ArmorerJob.Relic -> give(ctx, job, draft)
            is ArmorerJob.Restock -> stockUp(ctx, job, StockKind.RELIC, Speech.clean(draft.name), json.encodeToString(RelicDraft.serializer(), draft), RelicItems.describe(draft)) { server ->
                RelicItems.relic(server, draft, 0)
            }
            else -> refuse(ToolResult.invalid("this task asks for no relic: ${taskSays(ctx)}"))
        }
    }

    /** `create_consumable`: a restock's strange consumable, put in the reserve. */
    suspend fun createConsumable(ctx: ToolContext, draft: ConsumableDraft): ToolResult = answer {
        val job = armorer.job(ctx.caller.conversationId) as? ArmorerJob.Restock
            ?: refuse(ToolResult.invalid("this task asks for no consumable: ${taskSays(ctx)}"))
        stockUp(ctx, job, StockKind.CONSUMABLE, Speech.clean(draft.name), json.encodeToString(ConsumableDraft.serializer(), draft), RelicItems.describe(draft)) { server ->
            RelicItems.consumable(server, draft, 0)
        }
    }

    /** `write_book`: a restock's book, put in the reserve. */
    suspend fun writeBook(ctx: ToolContext, draft: BookDraft): ToolResult = answer {
        val job = armorer.job(ctx.caller.conversationId) as? ArmorerJob.Restock
            ?: refuse(ToolResult.invalid("this task asks for no book: ${taskSays(ctx)}"))
        stockUp(ctx, job, StockKind.BOOK, Speech.clean(draft.title), json.encodeToString(BookDraft.serializer(), draft), RelicItems.describe(draft)) { _ ->
            RelicItems.book(draft, 0)
        }
    }

    /** Gives the relic of [draft] to the killer of a relic task: in their inventory, at their feet when it is full; to the reserve when they left. */
    private suspend fun give(ctx: ToolContext, job: ArmorerJob.Relic, draft: RelicDraft): ToolResult {
        if (job.given != null) refuse(ToolResult.invalid("the relic is already given: end with your one-line report"))
        val dropped = ctx.onServer { server ->
            val stack = RelicItems.relic(server, draft, 0)
            server.playerList.getPlayer(job.killer.uuid)?.let { give(it, stack) }
        }
        val look = RelicItems.describe(draft)
        val lore = ModelText.escape(ModelText.cut(Speech.clean(draft.lore.first()), 120))
        if (dropped == null) {
            val id = store.addStock(StockKind.RELIC, Speech.clean(draft.name), json.encodeToString(RelicDraft.serializer(), draft), clock.instant(), ctx.caller.conversationId)
            stock.getValue(StockKind.RELIC).addLast(StockRow(id, StockKind.RELIC, Speech.clean(draft.name), json.encodeToString(RelicDraft.serializer(), draft), clock.instant()))
            job.given = "none: ${job.killer.shownName} left, ${ModelText.escape(look)} went to the chests' reserve"
            return ToolResult.ok("ok: ${job.killer.shownName} left the game: ${ModelText.escape(look)} goes to the chests' reserve instead. End with your one-line report.")
        }
        val how = if (dropped) "dropped at ${job.killer.shownName}'s feet (inventory full)" else "given to ${job.killer.shownName}"
        job.given = "${ModelText.escape(look)} « $lore » ($how)"
        store.relicGiven(job.champion.row.id, look)
        Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "relic_given", job.killer.name, "$look ($how)")
        return ToolResult.ok("ok: ${ModelText.escape(look)} $how; the Narrator is told. End with your one-line report.", listOf(job.killer))
    }

    /** Puts [stack] in [player]'s inventory, or at their feet when it is full, as `give_book` does. True when dropped. Server thread. */
    private fun give(player: ServerPlayer, stack: ItemStack): Boolean {
        val rest = stack.copy()
        ItemLedger.insert(player.inventory, rest)
        val dropped = !rest.isEmpty
        if (dropped) {
            player.createItemStackToDrop(rest, false, false)?.let { item ->
                item.setNoPickUpDelay()
                item.setTarget(player.uuid)
                player.level().addFreshEntity(item)
            }
        } else {
            val pitch = ((player.random.nextFloat() - player.random.nextFloat()) * 0.7f + 1f) * 2f
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, pitch)
        }
        player.containerMenu.broadcastChanges()
        return dropped
    }

    /** One object for the reserve of [kind]: built once to check it ([build], server thread), then kept. */
    private suspend fun stockUp(
        ctx: ToolContext,
        job: ArmorerJob.Restock,
        kind: StockKind,
        name: String,
        spec: String,
        look: String,
        build: (MinecraftServer) -> ItemStack,
    ): ToolResult {
        if (job.left(kind) <= 0) refuse(ToolResult.invalid("the reserve wants no more ${plural(kind)}: still wanted: ${wanted(job)}"))
        ctx.onServer { server -> build(server) }
        val now = clock.instant()
        val id = store.addStock(kind, name, spec, now, ctx.caller.conversationId)
        stock.getValue(kind).addLast(StockRow(id, kind, name, spec, now))
        job.made.merge(kind, 1, Int::plus)
        log.info("${kind.id} #$id in the reserve: $look")
        return ToolResult.ok("ok: ${kind.id} #$id ${ModelText.escape(look)} in the reserve; still wanted: ${wanted(job)}")
    }

    /** `2 relics, 5 consumables, no book`. */
    private fun wanted(job: ArmorerJob.Restock): String = StockKind.entries.joinToString { kind ->
        val left = job.left(kind).coerceAtLeast(0)
        if (left == 0) "no ${kind.id}" else "$left ${if (left == 1) kind.id else plural(kind)}"
    }

    // ── The reserves ────────────────────────────────────────────────────────────────────────────────

    /**
     * A loot table was drawn (`MODIFY_DROPS`): a structure chest's (`chests/…`, the bonus chest left out) takes, with each
     * kind's chance, the oldest object of its reserve; a chest's nested tables draw once. Server thread, quick.
     */
    fun onChestLoot(table: Holder<LootTable>, context: LootContext, drops: MutableList<ItemStack>) {
        val id = table.unwrapKey().orElse(null)?.identifier() ?: return
        if (id.namespace != "minecraft" || !id.path.startsWith(CHESTS) || id.path == BONUS_CHEST) return
        if (stock.values.all { it.isEmpty() } || !available) return
        if (lastContext?.get() === context) return
        lastContext = WeakReference(context)
        val cfg = config
        val found = mutableListOf<Pair<StockRow, Boolean>>()
        for (kind in StockKind.entries) {
            if (context.random.nextDouble() >= chance(cfg, kind)) continue
            val row = stock.getValue(kind).pollFirst() ?: continue
            val stack = try {
                build(context.level.server, row)
            } catch (e: Exception) {
                log.warn("${kind.id} #${row.id} « ${row.name} » could not be built, left out: ${e.message}")
                found += row to false
                continue
            }
            drops += stack
            found += row to true
        }
        if (found.isEmpty()) return
        val opener = context.getOptional(LootContextParams.THIS_ENTITY) as? ServerPlayer
        val player = opener?.ref()
        val table = id.path
        val now = clock.instant()
        services.scope.launch { taken(found, player, table, now) }
    }

    /** The objects a chest took: recorded, the Director told who found what, and the reserves filled again if need be. */
    private suspend fun taken(found: List<Pair<StockRow, Boolean>>, player: PlayerRef?, table: String, now: Instant) {
        try {
            for ((row, built) in found) {
                store.taken(row.id, if (built) "found" else "broken", now, player?.name, table)
                if (!built) continue
                Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "treasure_found", player?.name, "${row.kind.id} #${row.id} « ${row.name} » in $table")
                if (player != null) {
                    val what = when (row.kind) {
                        StockKind.RELIC -> "a relic"
                        StockKind.CONSUMABLE -> "a strange consumable"
                        StockKind.BOOK -> "a book"
                    }
                    post(MechanicSignal.TREASURE, "${player.shownName} found $what, « ${ModelText.escape(row.name)} », in a chest (${table.removePrefix(CHESTS)}): ${ModelText.escape(summary(row))}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("the objects a chest took were not recorded", e)
        }
        maybeRestock("a chest took ${found.size}")
    }

    /** What an object of the reserve is, in a few words, for the Director: its make, or a book's first words. */
    private fun summary(row: StockRow): String = when (row.kind) {
        StockKind.RELIC -> decode(RelicDraft.serializer(), row.spec)?.let { RelicItems.describe(it) + " « " + Speech.clean(it.lore.first()) + " »" }
        StockKind.CONSUMABLE -> decode(ConsumableDraft.serializer(), row.spec)?.let { RelicItems.describe(it) }
        StockKind.BOOK -> decode(BookDraft.serializer(), row.spec)?.let { RelicItems.describe(it) + ": " + ModelText.cut(Speech.clean(it.pages.first()), 160) }
    } ?: row.name

    /** The item of [row], built from its draft. Server thread; throws when it no longer holds. */
    private fun build(server: MinecraftServer, row: StockRow): ItemStack = when (row.kind) {
        StockKind.RELIC -> RelicItems.relic(server, decode(RelicDraft.serializer(), row.spec) ?: error("unreadable relic"), row.id)
        StockKind.CONSUMABLE -> RelicItems.consumable(server, decode(ConsumableDraft.serializer(), row.spec) ?: error("unreadable consumable"), row.id)
        StockKind.BOOK -> RelicItems.book(decode(BookDraft.serializer(), row.spec) ?: error("unreadable book"), row.id)
    }

    /**
     * Starts the restock task when a reserve is under half its target, none runs, Claude can work, and the last one did
     * not make nothing less than [RETRY_MS] ago; it asks for every reserve up to its target. [force] (an operator):
     * whenever a reserve is under its target, whatever the last one did. Returns what it did, for the operator.
     */
    @Synchronized
    fun maybeRestock(why: String, force: Boolean = false): String {
        unavailableReason?.let { return "no restock: $it" }
        if (services.settings.get().claude == null) return "no restock: no LLMGW key"
        if (restocking?.isActive == true) return "a restock already runs"
        if (!force && clock.millis() - restockFailedAt < RETRY_MS) return "no restock: the last one made nothing, less than ${RETRY_MS / 60_000} minutes ago"
        val cfg = config
        val counts = StockKind.entries.associateWith { stock.getValue(it).size }
        val low = StockKind.entries.filter { if (force) counts.getValue(it) < target(cfg, it) else counts.getValue(it) * 2 < target(cfg, it) }
        if (low.isEmpty()) return "no restock: the reserves are full enough (${stockLine()})"
        val wanted = StockKind.entries.associateWith { (target(cfg, it) - counts.getValue(it)).coerceAtLeast(0) }.filterValues { it > 0 }
        val job = ArmorerJob.Restock(wanted)
        restocking = services.scope.launch { restock(job, why) }
        return "restock started: ${wanted(job)}"
    }

    private suspend fun restock(job: ArmorerJob.Restock, why: String) {
        try {
            log.info("restock ($why): ${wanted(job)}")
            val opening = restockOpening(job)
            val ending = armorer.run(job, opening, RESTOCK_STEPS, RESTOCK_TIMEOUT_MS)
            val made = StockKind.entries.joinToString { "${job.made[it] ?: 0} ${plural(it)}" }
            if (job.made.values.sum() == 0) restockFailedAt = clock.millis()
            Decisions(services.trace, clock).record(
                log, DecisionRecord.Kind.MECHANIC, "restocked", null,
                "$made made ($why); ${if (ending.done) "report" else "ended"}: ${ModelText.cut(ending.text, 200)}; reserves now ${stockLine()}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restockFailedAt = clock.millis()
            log.error("the restock failed", e)
        }
    }

    /** The first message of a restock: what is wanted, what the reserves hold, the players the server knows. */
    private suspend fun restockOpening(job: ArmorerJob.Restock): String {
        val players = try {
            services.journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query("SELECT name, last_seen FROM players ORDER BY last_seen DESC LIMIT $PLAYERS_SHOWN") {
                    "${ModelText.name(getString(1))} (last seen ${getObject(2)?.toString()?.take(16) ?: "?"})"
                }
            }.joinToString().ifEmpty { "nobody yet" }
        } catch (e: Exception) {
            "(unknown: ${e.message})"
        }
        return buildString {
            append("<task kind=\"restock\">\nThe chests of the world need: ${wanted(job)}.\n</task>\n")
            append("<reserves>\n")
            for (kind in StockKind.entries) {
                val rows = stock.getValue(kind).toList()
                append("${plural(kind)} (${rows.size}): ").append(rows.joinToString { "« ${ModelText.escape(it.name)} »" }.ifEmpty { "none" }).append('\n')
            }
            append("</reserves>\n<players>").append(players).append("</players>\n")
            append(context())
        }
    }

    /** `<context now="…" players_online="…"/>`, as a worker's task gets it. */
    private fun context(): String {
        val zone = services.settings.get().config.journal.zone(clock.zone)
        val now = STAMP.withZone(zone).format(clock.instant())
        val online = services.players.online.joinToString(", ") { ModelText.attribute(it.ref.name) }.ifEmpty { "none" }
        return "<context now=\"$now\" players_online=\"$online\"/>"
    }

    /** What the task of [ctx]'s conversation asks for, for a tool called out of place. */
    private fun taskSays(ctx: ToolContext): String = when (val job = armorer.job(ctx.caller.conversationId)) {
        is ArmorerJob.Champion -> "it asks for a champion (create_champion)"
        is ArmorerJob.Relic -> "it asks for one relic (create_relic)"
        is ArmorerJob.Restock -> "it asks for ${wanted(job)}"
        null -> "this conversation has no task of the armorer"
    }

    // ── For the operators ───────────────────────────────────────────────────────────────────────────

    /** `/narrator relics`: the reserves, the champions standing and being designed, what the armorer does. */
    fun statusLines(): List<String> = buildList {
        add(unavailableReason?.let { "unavailable: $it" } ?: "available")
        val cfg = config
        for (kind in StockKind.entries) {
            val rows = stock.getValue(kind).toList()
            add("${plural(kind)} ${rows.size}/${target(cfg, kind)} (chance ${number(chance(cfg, kind) * 100)}%): " + rows.joinToString { "#${it.id} ${it.name}" }.ifEmpty { "none" })
        }
        val standing = standing()
        add("champions standing ${standing.size}/${cfg.maxChampions}: " + standing.joinToString { "« ${it.row.name} » ${it.row.mob} for ${it.row.playerName} until ${clockTime(clock, it.row.expiresAt.toEpochMilli())}" }.ifEmpty { "none" })
        if (designing.isNotEmpty()) add("being designed for: " + designing.joinToString { uuid -> services.players.online.firstOrNull { it.ref.uuid == uuid }?.ref?.name ?: uuid.toString() })
        add("armorer: " + armorer.running().joinToString { it.label }.ifEmpty { "idle" } + if (restockFailedAt > 0) "; last empty restock at ${clockTime(clock, restockFailedAt)}" else "")
    }

    /** The last champions, for `/narrator relics`. */
    suspend fun lastChampions(limit: Int): List<ChampionRow> = store.lastChampions(limit)

    /** Objects waiting in the reserve of [kind]. */
    fun stocked(kind: StockKind): Int = stock.getValue(kind).size

    /** Whether the armorer works now: a task running, or a restock. */
    val busy: Boolean get() = armorer.running().isNotEmpty() || restocking?.isActive == true

    /** `relics 2/5, consumables 8/8, books 1/5`. */
    private fun stockLine(): String {
        val cfg = config
        return StockKind.entries.joinToString { "${plural(it)} ${stock.getValue(it).size}/${target(cfg, it)}" }
    }

    private fun post(kind: MechanicSignal, line: String) {
        services.director.post(DirectorSignal.Mechanic(kind, line, clock.millis(), priority = false))
    }

    private fun <T> decode(serializer: KSerializer<T>, spec: String): T? = try {
        json.decodeFromString(serializer, spec)
    } catch (e: Exception) {
        log.warn("an object of the reserves could not be read: ${e.message}")
        null
    }

    companion object {
        val log = Log.of("Relics")

        /** The tag of every champion (`/tag`, entity `Tags`), besides the Narrator's own. */
        const val TAG = "narrator2_champion"

        /** The rule of the caps on champions: `DENIED (champion_limit): …`. */
        const val CHAMPION_LIMIT = "champion_limit"

        /** Messages of a task: a champion or a relic takes two or three, a restock several calls a message. */
        const val CHAMPION_STEPS = 5
        const val RELIC_STEPS = 5
        const val RESTOCK_STEPS = 8

        const val JOB_TIMEOUT_MS = 4 * 60_000L
        const val RESTOCK_TIMEOUT_MS = 8 * 60_000L

        /** After a restock that made nothing, none starts by itself for this long. */
        const val RETRY_MS = 30 * 60_000L

        /** How far from their player a champion appears, in blocks (`spawn`'s `distance`). */
        private const val SPAWN_DISTANCE = 10

        /** Longest name shown above a champion (`spawn`'s `name`). */
        private const val NAME_SHOWN_MAX = 32

        /** A champion gone this long after its time is not told to the Director: that news is stale. */
        private const val GONE_TOLD_SECONDS = 120L

        private const val CHESTS = "chests/"
        private const val BONUS_CHEST = "chests/spawn_bonus_chest"
        private const val READ_TIMEOUT_MS = 5_000L
        private const val PLAYERS_SHOWN = 12

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        fun chance(config: RelicsConfig, kind: StockKind): Double = when (kind) {
            StockKind.RELIC -> config.relicChance
            StockKind.CONSUMABLE -> config.consumableChance
            StockKind.BOOK -> config.bookChance
        }

        fun target(config: RelicsConfig, kind: StockKind): Int = when (kind) {
            StockKind.RELIC -> config.relicStock
            StockKind.CONSUMABLE -> config.consumableStock
            StockKind.BOOK -> config.bookStock
        }

        fun plural(kind: StockKind): String = kind.id + "s"
    }
}
