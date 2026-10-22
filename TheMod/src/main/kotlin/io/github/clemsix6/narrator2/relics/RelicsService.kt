package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.config.RelicsConfig
import io.github.clemsix6.narrator2.config.WorldNotes
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.CreatureShield
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
import kotlin.math.abs

/** A champion in the world: its sheet, its draft, and the tally of its fight (damage by whom, damage to whom). */
class LiveChampion(val row: ChampionRow, val draft: ChampionDraft) {
    /** Its creature now: a conversion (a zombie drowned) makes a new one. */
    @Volatile
    var entity: UUID = row.entity

    /** Damage it took, by who dealt it: a player's name, else a creature's type or a cause. */
    val hits = ConcurrentHashMap<String, Float>()

    /** Damage it dealt to players, by player. */
    val dealt = ConcurrentHashMap<String, Float>()

    /** When a player first hurt it, epoch milliseconds; 0 before (M7-BALANCE: how long the fight really lasted). */
    @Volatile
    var firstBlowAt = 0L

    /** Why it was sent away, when the service did it ([RelicsService.onSecond]); null otherwise. */
    @Volatile
    var sentAway: String? = null

    /** What it is and where it stands, once dressed: `husk « … » (…) at overworld 1 64 2 until 19:42, 112 health`. */
    @Volatile
    var summary: String = ""
}

/**
 * The armorer's work in the world (M7-RELICS): champions, the relics their killers earn, and the chests' reserves of
 * relics, strange consumables and books.
 *
 * - **Champions.** `summon_champion` (or `/narrator champion`) checks the caps (one per player, `relics.maxChampions` in
 *   all, those being designed counted) and the guards ([Verdict], category `creatures`), then hands the design to the
 *   armorer ([Armorer]). Its `create_champion` summons it through the `spawn` tool, with its guards, caps and marks
 *   (a creature of the Narrator's: it goes for its player alone, and goes when they die or leave), then dresses it
 *   (gear, lasting effects, glow, the tag [TAG]), keeps it `relics.championMinutes` (past the 10 minutes of `spawn`) and
 *   keeps its sheet (`champions`). Unlike the Narrator's other creatures, its fatal blow kills, within the Narrator's
 *   quota of deaths (M7-FIX, `GuardsImpl.allowDeath`). M7-BALANCE: its health and blow are sized to its player's gear
 *   ([Threat]), it goes for its player every second, and leaves when the Narrator spares them ([onSecond]).
 * - **Its death** ([onDeath]): killed by a player, its gear drops whole and the armorer forges that player's relic, given
 *   at once (at their feet when the inventory is full), within the caps of their tier and of the fight ([RelicCaps]);
 *   killed otherwise, nothing but the creature's own loot. Without Claude, or when the armorer fails, its gear alone
 *   drops. Each end is a `CHAMPION` line to the Director.
 * - **The reserves** ([onChestLoot]): objects prepared ahead (`relic_stock`), drawn into a structure chest's loot when it is
 *   first opened, each kind with its chance, a relic only for an opener of its tier, one off at most; the player who
 *   opened it, when known, makes a `TREASURE` line. When a reserve (the relics: one per tier) falls under half its
 *   target, one restock task fills them all ([maybeRestock]): at the start, then after a chest took something; one at a
 *   time, and not again within [RETRY_MS] of one that made nothing.
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
        for (row in store.stock()) stock.getValue(row.kind).addLast(withTier(row))
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
            append("<player tier=\"${Threat.tierOf(player).id}\">\n").append(report.header(null, false)).append('\n')
            report.position().forEach { append(it).append('\n') }
            append(report.equipment()).append("\n</player>\n")
            append(context())
        }
    }

    /**
     * `create_champion`: the champion of the task in [ctx], made from [draft] and standing near the task's player ([stand]).
     */
    suspend fun createChampion(ctx: ToolContext, draft: ChampionDraft): ToolResult = answer {
        val job = armorer.job(ctx.caller.conversationId) as? ArmorerJob.Champion
            ?: refuse(ToolResult.invalid("this task asks for no champion: ${taskSays(ctx)}"))
        if (job.champion != null) refuse(ToolResult.invalid("your champion already stands: end with your one-line report"))
        val live = stand(ctx.caller, job.player, draft)
        job.champion = live
        ToolResult.ok("ok: champion ${ModelText.escape(live.summary)} stands near ${job.player.shownName}; the Narrator is told. End with your one-line report.", listOf(job.player))
    }

    /**
     * The champion of [draft] for [player], asked by [caller]: checked (`RelicRules`, the gear through `RelicItems`),
     * summoned by `spawn` with its guards, dressed, sized to its player ([Threat.size]), recorded and told to the Director.
     * Throws a refusal ([refuse]) with the reason when it cannot stand; inside a tool's [answer], that is the answer.
     */
    suspend fun stand(caller: Caller, player: PlayerRef, draft: ChampionDraft): LiveChampion {
        val problems = RelicRules.champion(draft)
        if (problems.isNotEmpty()) refuse(ToolResult.invalid(problems.joinToString("; ")))
        val gear = services.server.onServerThread {
            RelicRules.pieces(draft.gear).map { (slot, piece) -> EquipmentSlot.byName(slot).let { it to RelicItems.gear(services.server, piece, it) } }
        }
        val minutes = config.championMinutes
        val name = Speech.fit(Speech.clean(draft.name), NAME_SHOWN_MAX)
        val spawned = runner.invoke(SpawnTool(), caller, spawnArgs(player, draft, name, minOf(minutes, SPAWN_MAX_MINUTES)))
        if (spawned.status != ToolStatus.OK) refuse(spawned)
        val now = clock.instant()
        val row = ChampionRow(
            nextChampion.getAndIncrement(), UUID(0, 0), player.uuid, player.name, draft.mob.id, name,
            json.encodeToString(ChampionDraft.serializer(), draft), now, now.plusSeconds(minutes * 60L), "active",
        )
        val live = services.server.onServerThread { dress(services.server, spawned, draft, gear, row) }
            ?: refuse(ToolResult.failed("the champion vanished as it appeared"))
        store.addChampion(live.row, caller.conversationId)
        Decisions(services.trace, clock).record(log, DecisionRecord.Kind.MECHANIC, "champion_summoned", player.name, live.summary)
        post(
            MechanicSignal.CHAMPION,
            "${ModelText.escape(live.summary)} appeared near ${player.shownName}, and goes for them: " +
                "« ${ModelText.escape(ModelText.cut(Speech.clean(draft.lore), 300))} »; whoever kills it gets a relic",
        )
        return live
    }

    /** The arguments of `spawn` for a champion: its creature, its name, its factors, sent at its player. */
    private fun spawnArgs(player: PlayerRef, draft: ChampionDraft, name: String, minutes: Int): String = buildJsonObject {
        put("entity", draft.mob.id)
        put("near", player.name)
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
     * glow and tag, its health and blow sized to its player ([Threat.size]); then kept until the end of [row], with its
     * mount or riders. Null when it is gone already. Server thread.
     */
    private fun dress(server: MinecraftServer, spawned: ToolResult, draft: ChampionDraft, gear: List<Pair<EquipmentSlot, ItemStack>>, row: ChampionRow): LiveChampion? {
        val mobs = spawned.entities.mapNotNull { uuid -> server.allLevels.firstNotNullOfOrNull { it.getEntity(uuid) } as? Mob }
        val mob = mobs.firstOrNull { EntityType.getKey(it.type).path == draft.mob.id } ?: mobs.firstOrNull() ?: return null
        mobs.forEach { services.guards.mobs.extend(it, row.expiresAt.toEpochMilli()) }
        for ((slot, stack) in gear) {
            mob.setItemSlot(slot, stack)
            mob.setDropChance(slot, 0f)
        }
        for (entry in draft.effects) mob.addEffect(MobEffectInstance(RelicItems.effect(entry.effect.id), MobEffectInstance.INFINITE_DURATION, entry.level - 1, false, true))
        mob.health = mob.maxHealth
        server.playerList.getPlayer(row.player)?.let { player -> log.info("champion for ${row.playerName} sized: ${Threat.size(mob, player)}") }
        mob.addTag(TAG)
        mob.setGlowingTag(true)
        val live = LiveChampion(row.copy(entity = mob.uuid), draft)
        val where = "${ToolText.dimension(mob.level().dimension().identifier().toString())} ${mob.blockX} ${mob.blockY} ${mob.blockZ}"
        live.summary = "${RelicItems.describe(draft)} at $where until ${clockTime(clock, row.expiresAt.toEpochMilli())}, ${number(mob.maxHealth.toDouble())} health"
        champions[mob.uuid] = live
        return live
    }

    /**
     * Once a second (server thread, M7-BALANCE): each champion goes for its player, from the moment it stands to its end;
     * one whose player the Narrator now spares (saved from a death, protected, in grace or in a duel: its blows would be
     * refused) is sent away, for a champion never stands harmless while its killer earns a relic.
     */
    fun onSecond(server: MinecraftServer) {
        if (champions.isEmpty()) return
        for (live in champions.values.toList()) {
            val mob = server.allLevels.firstNotNullOfOrNull { it.getEntity(live.entity) } as? Mob ?: continue
            val player = server.playerList.getPlayer(live.row.player) ?: continue
            if (!mob.isAlive || player.isSpectator || player.isCreative) continue
            if (CreatureShield.spares(player)) {
                live.sentAway = "its player is spared now (saved from a death, protected, in grace or in a duel): a champion never stands harmless"
                log.info("champion « ${live.row.name} » sent away: ${live.row.playerName} is spared now")
                services.guards.mobs.retire(mob, clock.millis())
                continue
            }
            if (mob.level() === player.level() && mob.target !== player) mob.target = player
        }
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
        // What the killer's relic may be (M7-BALANCE): their tier now, one step lower after a fight that was too easy.
        val caps = killer?.let {
            val fought = if (live.firstBlowAt == 0L) 0L else now.toEpochMilli() - live.firstBlowAt
            RelicCaps(Threat.tierOf(it), RelicRules.trivial(fought, (live.dealt[it.plainTextName] ?: 0f).toDouble()))
        }
        val ref = killer?.ref()
        services.scope.launch { ended(live, ref, fight, where, now, caps) }
    }

    /** How the fight went, in one line: its length (from its coming, from the first blow), who hit it how hard, what it dealt, the last blow. */
    private fun fight(live: LiveChampion, source: DamageSource, killer: ServerPlayer?, now: Instant): String {
        val took = ToolText.duration(now.toEpochMilli() - live.row.spawnedAt.toEpochMilli())
        val fought = if (live.firstBlowAt == 0L) "killed at the first blow" else "fought ${ToolText.duration(now.toEpochMilli() - live.firstBlowAt)} from the first blow"
        val hits = live.hits.entries.sortedByDescending { it.value }.joinToString { "${ModelText.name(it.key)} ${number(it.value.toDouble())}" }.ifEmpty { "nobody" }
        val dealt = live.dealt.entries.sortedByDescending { it.value }.joinToString { "${ModelText.name(it.key)} ${number(it.value.toDouble())}" }.ifEmpty { "nobody" }
        val blow = source.typeHolder().registeredName.removePrefix("minecraft:") +
            (killer?.mainHandItem?.takeUnless { it.isEmpty }?.let { " with ${it.typeHolder().registeredName.removePrefix("minecraft:")}" } ?: "")
        return "lasted $took, $fought; damage it took: $hits; damage it dealt: $dealt; last blow: $blow"
    }

    /** The end of [live] at [now]: recorded, then the relic of its [killer] within [caps], and the Director told. */
    private suspend fun ended(live: LiveChampion, killer: PlayerRef?, fight: String, where: String, now: Instant, caps: RelicCaps?) {
        val name = "« ${ModelText.escape(live.row.name)} » (${live.row.mob})"
        try {
            store.ended(live.row.id, if (killer != null) "slain" else "died", now, killer?.name, fight)
            if (killer == null || caps == null) {
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
            val job = ArmorerJob.Relic(killer, live, fight, caps)
            log.info("relic for ${killer.name}: ${caps.tier.id} tier${if (caps.trivial) ", a fight too easy" else ""}: ${caps.describe()}")
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
                append("<player tier=\"${job.caps.tier.id}\">\n").append(report.header(null, false)).append('\n').append(report.equipment()).append("\n</player>\n")
            }
            val easy = if (job.caps.trivial) " fight=\"too easy: killed within ${RelicRules.TRIVIAL_MS / 1000} s of the first blow, or hurting them by less than ${number(RelicRules.TRIVIAL_DAMAGE)}\"" else ""
            append("<caps tier=\"${job.caps.tier.id}\"$easy>").append(job.caps.describe()).append("</caps>\n")
            append(context())
        }
    }

    /** A creature took damage (`AFTER_DAMAGE`): the tally of a champion's fight. Server thread. */
    fun onDamage(entity: LivingEntity, source: DamageSource, taken: Float) {
        if (champions.isEmpty() || taken <= 0f) return
        champions[entity.uuid]?.let { live ->
            val player = source.entity as? ServerPlayer
            if (player != null && live.firstBlowAt == 0L) live.firstBlowAt = clock.millis()
            val who = player?.plainTextName ?: source.entity?.let { EntityType.getKey(it.type).path } ?: source.typeHolder().registeredName.removePrefix("minecraft:")
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
                val why = live.sentAway ?: if (now.isBefore(live.row.expiresAt)) "sent away before its time (its player died, left or was pardoned)" else "its time ran out"
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

    /**
     * `create_relic`: a relic task's relic, within its killer's caps, given to them; a restock's, for the tier it names
     * and within that tier's caps, put in the reserve (M7-BALANCE).
     */
    suspend fun createRelic(ctx: ToolContext, draft: RelicDraft): ToolResult = answer {
        when (val job = armorer.job(ctx.caller.conversationId)) {
            is ArmorerJob.Relic -> give(ctx, job, draft.copy(tier = null))
            is ArmorerJob.Restock -> {
                val tier = draft.tier ?: refuse(ToolResult.invalid("say which players this relic waits for (tier); still wanted: ${wanted(job)}"))
                if (job.left(tier) <= 0) refuse(ToolResult.invalid("no more relics wanted for ${tier.id}-tier players; still wanted: ${wanted(job)}"))
                RelicRules.withinCaps(draft, RelicCaps(tier)).takeIf { it.isNotEmpty() }?.let { refuse(ToolResult.invalid(it.joinToString("; "))) }
                stockUp(ctx, job, StockKind.RELIC, Speech.clean(draft.name), json.encodeToString(RelicDraft.serializer(), draft), "${tier.id} tier: ${RelicItems.describe(draft)}", tier) { server ->
                    RelicItems.relic(server, draft, 0)
                }
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

    /**
     * Gives the relic of [draft], within the killer's caps, to the killer of a relic task: in their inventory, at their feet
     * when it is full; to the reserve, for their tier, when they left.
     */
    private suspend fun give(ctx: ToolContext, job: ArmorerJob.Relic, draft: RelicDraft): ToolResult {
        if (job.given != null) refuse(ToolResult.invalid("the relic is already given: end with your one-line report"))
        RelicRules.withinCaps(draft, job.caps).takeIf { it.isNotEmpty() }?.let { refuse(ToolResult.invalid(it.joinToString("; "))) }
        val dropped = ctx.onServer { server ->
            val stack = RelicItems.relic(server, draft, 0)
            server.playerList.getPlayer(job.killer.uuid)?.let { give(it, stack) }
        }
        val look = RelicItems.describe(draft)
        val lore = ModelText.escape(ModelText.cut(Speech.clean(draft.lore.first()), 120))
        if (dropped == null) {
            val spec = json.encodeToString(RelicDraft.serializer(), draft.copy(tier = job.caps.tier))
            val id = store.addStock(StockKind.RELIC, Speech.clean(draft.name), spec, clock.instant(), ctx.caller.conversationId)
            stock.getValue(StockKind.RELIC).addLast(StockRow(id, StockKind.RELIC, Speech.clean(draft.name), spec, clock.instant(), job.caps.tier))
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

    /** One object for the reserve of [kind] (a relic for [tier]): built once to check it ([build], server thread), then kept. */
    private suspend fun stockUp(
        ctx: ToolContext,
        job: ArmorerJob.Restock,
        kind: StockKind,
        name: String,
        spec: String,
        look: String,
        tier: Tier? = null,
        build: (MinecraftServer) -> ItemStack,
    ): ToolResult {
        if (job.left(kind) <= 0) refuse(ToolResult.invalid("the reserve wants no more ${plural(kind)}: still wanted: ${wanted(job)}"))
        ctx.onServer { server -> build(server) }
        val now = clock.instant()
        val id = store.addStock(kind, name, spec, now, ctx.caller.conversationId)
        stock.getValue(kind).addLast(StockRow(id, kind, name, spec, now, tier))
        job.made.merge(kind, 1, Int::plus)
        tier?.let { job.madeTiers.merge(it, 1, Int::plus) }
        log.info("${kind.id} #$id in the reserve: $look")
        return ToolResult.ok("ok: ${kind.id} #$id ${ModelText.escape(look)} in the reserve; still wanted: ${wanted(job)}")
    }

    /** `2 relics (1 wood-tier, 1 iron-tier), 5 consumables, no book`. */
    private fun wanted(job: ArmorerJob.Restock): String = StockKind.entries.joinToString { kind ->
        val left = job.left(kind).coerceAtLeast(0)
        val tiers = if (kind != StockKind.RELIC || left == 0) "" else " (" + Tier.entries.filter { job.left(it) > 0 }.joinToString { "${job.left(it)} ${it.id}-tier" } + ")"
        if (left == 0) "no ${kind.id}" else "$left ${if (left == 1) kind.id else plural(kind)}$tiers"
    }

    /** [row] with its tier when it is a relic: the one its draft names, else the lowest it fits (a relic made before the tiers). */
    private fun withTier(row: StockRow): StockRow {
        if (row.kind != StockKind.RELIC) return row
        val draft = decode(RelicDraft.serializer(), row.spec) ?: return row
        return row.copy(tier = draft.tier ?: RelicRules.fittingTier(draft))
    }

    // ── The reserves ────────────────────────────────────────────────────────────────────────────────

    /**
     * A loot table was drawn (`MODIFY_DROPS`): a structure chest's (`chests/…`, the bonus chest left out) takes, with each
     * kind's chance, the oldest object of its reserve; a chest's nested tables draw once. Only a draw for a player (the
     * loot's `this_entity`: a chest they open, a vault's reward) takes anything: an active vault also draws its table
     * every second to pick the item it shows, then throws the draw away (M7-FIX). Server thread, quick.
     */
    fun onChestLoot(table: Holder<LootTable>, context: LootContext, drops: MutableList<ItemStack>) {
        val id = table.unwrapKey().orElse(null)?.identifier() ?: return
        if (id.namespace != "minecraft" || !id.path.startsWith(CHESTS) || id.path == BONUS_CHEST) return
        val opener = context.getOptional(LootContextParams.THIS_ENTITY) as? ServerPlayer ?: return
        if (stock.values.all { it.isEmpty() } || !available) return
        if (lastContext?.get() === context) return
        lastContext = WeakReference(context)
        val cfg = config
        val found = mutableListOf<Pair<StockRow, Boolean>>()
        for (kind in StockKind.entries) {
            if (context.random.nextDouble() >= chance(cfg, kind)) continue
            val row = (if (kind == StockKind.RELIC) relicFor(Threat.tierOf(opener)) else stock.getValue(kind).pollFirst()) ?: continue
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
        val player = opener.ref()
        val table = id.path
        val now = clock.instant()
        services.scope.launch { taken(found, player, table, now) }
    }

    /**
     * Takes from the reserve the oldest relic for a player of [tier], one tier off at most (M7-BALANCE: a relic made ahead
     * does not know who finds it); null when there is none.
     */
    private fun relicFor(tier: Tier): StockRow? {
        val reserve = stock.getValue(StockKind.RELIC)
        val row = reserve.firstOrNull { it.tier != null && abs(it.tier.ordinal - tier.ordinal) <= 1 } ?: return null
        return row.takeIf { reserve.remove(it) }
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
        // Each reserve as (count, target): the consumables, the books, and the relics of each tier (M7-BALANCE).
        val kinds = listOf(StockKind.CONSUMABLE, StockKind.BOOK).associateWith { stock.getValue(it).size to target(cfg, it) }
        val tiers = tierTargets(cfg.relicStock).mapValues { (tier, target) -> stock.getValue(StockKind.RELIC).count { it.tier == tier } to target }
        val low = { reserve: Pair<Int, Int> -> if (force) reserve.first < reserve.second else reserve.first * 2 < reserve.second }
        if (kinds.values.none(low) && tiers.values.none(low)) return "no restock: the reserves are full enough (${stockLine()})"
        val job = ArmorerJob.Restock(missing(kinds), missing(tiers))
        restocking = services.scope.launch { restock(job, why) }
        return "restock started: ${wanted(job)}"
    }

    /** What each reserve (count, target) misses to reach its target; the full ones left out. */
    private fun <K> missing(reserves: Map<K, Pair<Int, Int>>): Map<K, Int> =
        reserves.mapValues { (_, it) -> (it.second - it.first).coerceAtLeast(0) }.filterValues { it > 0 }

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
            if (job.tiers.isNotEmpty()) {
                append("<relic_tiers>\nEach relic names its tier (tier) and stays within its caps; a chest gives it to a player of that tier, one off at most.\n")
                for (tier in job.tiers.keys) append("${tier.id}: ").append(RelicCaps(tier).describe()).append('\n')
                append("</relic_tiers>\n")
            }
            append("<reserves>\n")
            for (kind in StockKind.entries) {
                val rows = stock.getValue(kind).toList()
                append("${plural(kind)} (${rows.size}): ")
                append(rows.joinToString { "« ${ModelText.escape(it.name)} »" + (it.tier?.let { tier -> " (${tier.id})" } ?: "") }.ifEmpty { "none" }).append('\n')
            }
            append("</reserves>\n<players>").append(players).append("</players>\n")
            append(context())
        }
    }

    /**
     * `<context now="…" players_online="…"/>`, as a worker's task gets it, after the operator's `<server_notes>` when there
     * are any (M7-NOTES): the other mods' items and what they change.
     */
    private fun context(): String {
        val config = services.settings.get().config
        val zone = config.journal.zone(clock.zone)
        val now = STAMP.withZone(zone).format(clock.instant())
        val online = services.players.online.joinToString(", ") { ModelText.attribute(it.ref.name) }.ifEmpty { "none" }
        val notes = WorldNotes.render(config.worldNotes)?.let { "$it\n" }.orEmpty()
        return "$notes<context now=\"$now\" players_online=\"$online\"/>"
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
            add("${plural(kind)} ${rows.size}/${target(cfg, kind)} (chance ${number(chance(cfg, kind) * 100)}%): " + rows.joinToString { "#${it.id} ${it.name}" + (it.tier?.let { tier -> " (${tier.id})" } ?: "") }.ifEmpty { "none" })
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
        val log = Log.relics

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

        /** The longest lifetime `spawn` takes (`lifetime_minutes`); a champion is kept longer afterwards ([dress]). */
        private const val SPAWN_MAX_MINUTES = 10

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

        /** How many relics each tier's reserve keeps (M7-BALANCE): `relics.relicStock` spread over the tiers, the lowest first. */
        fun tierTargets(total: Int): Map<Tier, Int> =
            Tier.entries.associateWith { total / Tier.entries.size + if (it.ordinal < total % Tier.entries.size) 1 else 0 }
    }
}
