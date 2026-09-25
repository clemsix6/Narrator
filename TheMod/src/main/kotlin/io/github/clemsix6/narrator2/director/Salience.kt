package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.id
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.reflex.ChatLine
import io.github.clemsix6.narrator2.reflex.ChatTriage
import io.github.clemsix6.narrator2.reflex.JevQuestions
import io.github.clemsix6.narrator2.reflex.triageChatMessage
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Decides which game events reach the Director, and how soon (M1_M2 §12.3). An asynchronous subscriber
 * of the bus ([start]), owned by `ShutdownSequence.DIRECTOR_OWNER` so that it closes when the server
 * stops. Every event feeds the [digest]; the salient ones are [post]ed to the Director's inbox as a
 * [DirectorSignal.Event] with their [Urgency] ([route]).
 *
 * Public chat and prayers are triaged by the reflexes (`triageChatMessage`, which writes the
 * `chat_signals` row), each in its own child coroutine so that a slow classifier never holds up the
 * other events. Private messages between players never enter this subscription, and never reach the
 * Director. Each chat verdict and each event classified [Urgency.SOON] or [Urgency.IMMEDIATE] is a
 * `salience` decision.
 */
class Salience(
    private val services: NarratorServices,
    private val digest: ActivityDigest,
    private val post: (DirectorSignal) -> Unit,
) {
    private val log = Log.director
    private val decisions = Decisions(services.trace, services.clock)

    // Touched by the subscriber's coroutine only, except the griefs (also flushed by their timers).
    private val recentChat = ArrayDeque<ChatLine>()
    private var narratorToAll: Spoken? = null
    private val narratorTo = HashMap<UUID, Spoken>()
    private val names = HashMap<UUID, String>()
    private val griefs = HashMap<Pair<UUID, UUID>, Grief>() // guarded by itself

    /** Where an event goes. */
    sealed interface Route {
        /** Posted at once with [urgency]; [reason] explains it in decisions. */
        data class Forward(val urgency: Urgency, val reason: String) : Route

        /** Public chat and prayers: the reflexes decide ([chatUrgency]). */
        data object Triage : Route

        /** A block placed by another player was broken: counted per breaker and owner for [GRIEF_WINDOW_MS], then one [Urgency.SOON]. */
        data object Grief : Route

        /** Only summarized by the activity digest (heartbeats, situation). */
        data object Batch : Route

        /** Never reaches the Director: private messages between players. */
        data object Never : Route
    }

    /** Subscribes to the bus and handles its events in [scope] until the subscription closes. */
    fun start(scope: CoroutineScope): Job {
        val subscription = services.bus.subscribeAsync(SUBSCRIBER, ShutdownSequence.DIRECTOR_OWNER, CAPACITY, ::subscribed)
        return scope.launch(CoroutineName("narrator2-salience")) {
            for (envelope in subscription.events) {
                try {
                    handle(this, envelope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("salience: event ${envelope.id} (${envelope.event.type}) not handled", e)
                }
            }
        }
    }

    /** Handles one event; chat triages and grief timers run as children of [scope]. */
    fun handle(scope: CoroutineScope, envelope: Envelope) {
        val event = envelope.event
        event.player?.let { names[it.uuid] = it.name }
        digest.record(envelope)
        if (event is NarratorSpoke) remember(event, envelope.ts)
        when (val route = route(event)) {
            is Route.Forward -> {
                post(DirectorSignal.Event(envelope, route.urgency, null))
                if (route.urgency != Urgency.NEXT) record(route.urgency, event.player?.name, route.reason, envelope.id)
            }
            Route.Triage -> triage(scope, envelope, event as ChatMessage)
            Route.Grief -> grief(scope, envelope, event as BlockBroken)
            Route.Batch, Route.Never -> Unit
        }
    }

    private fun triage(scope: CoroutineScope, envelope: Envelope, message: ChatMessage) {
        val recent = recentChat.toList()
        val narratorLast = narratorLastFor(message.player.uuid, envelope.ts)
        if (message.channel == ChatChannel.PUBLIC) {
            recentChat.addLast(ChatLine(message.player.name, message.text))
            while (recentChat.size > JevQuestions.RECENT_LINES) recentChat.removeFirst()
        }
        scope.launch {
            val triage = try {
                triageChatMessage(services, envelope, recent, narratorLast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("salience: chat event ${envelope.id} could not be triaged", e)
                null
            }
            val urgency = chatUrgency(message.channel, triage)
            val kind = when {
                message.channel == ChatChannel.PRAY -> "prayer"
                urgency == Urgency.IMMEDIATE -> "addressed"
                else -> "between players"
            }
            record(urgency, message.player.name, "$kind: ${triage?.reason ?: "triage failed"}", envelope.id)
            post(DirectorSignal.Event(envelope, urgency, annotation(message.channel, triage)))
        }
    }

    private fun grief(scope: CoroutineScope, envelope: Envelope, event: BlockBroken) {
        val key = event.player.uuid to event.placedBy!!
        val opened = synchronized(griefs) {
            val window = griefs[key]
            window?.add(event)
            if (window != null) {
                false
            } else {
                griefs[key] = Grief(envelope, names[key.second] ?: "a player offline").also { it.add(event) }
                true
            }
        }
        if (!opened) return
        scope.launch {
            delay(GRIEF_WINDOW_MS)
            val window = synchronized(griefs) { griefs.remove(key) } ?: return@launch
            val annotation = window.describe()
            record(Urgency.SOON, event.player.name, "grief: $annotation", envelope.id)
            post(DirectorSignal.Event(window.first, Urgency.SOON, annotation))
        }
    }

    private fun remember(spoken: NarratorSpoke, at: Long) {
        val line = Spoken(spoken.text, at)
        val recipients = spoken.recipients
        if (recipients == null) narratorToAll = line else recipients.forEach { narratorTo[it.uuid] = line }
    }

    /** The Narrator's last line seen by [player], if it is recent enough to be answered. */
    private fun narratorLastFor(player: UUID, at: Long): String? =
        listOfNotNull(narratorToAll, narratorTo[player])
            .maxByOrNull { it.at }
            ?.takeIf { at - it.at <= JevQuestions.NARRATOR_LAST_MAX_AGE_SECONDS * 1_000L }
            ?.text

    private fun record(urgency: Urgency, subject: String?, reason: String, eventId: Long) {
        decisions.record(
            log,
            DecisionRecord.Kind.SALIENCE,
            urgency.name.lowercase(Locale.ROOT),
            subject = subject,
            reason = reason,
            eventId = eventId,
            level = if (urgency == Urgency.NEXT) Log.Level.DEBUG else Log.Level.INFO,
        )
    }

    private class Spoken(val text: String, val at: Long)

    /** Blocks of one owner broken by one player within a window. */
    private class Grief(val first: Envelope, val owner: String) {
        private val blocks = LinkedHashMap<String, Int>()

        fun add(event: BlockBroken) {
            val block = id(event.block)
            blocks[block] = (blocks[block] ?: 0) + 1
        }

        /** `broke 14 blocks placed by Alex within 10s: oak_planks 10, glass 4`. */
        fun describe(): String {
            val count = blocks.values.sum()
            val top = blocks.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key} ${it.value}" }
            return "broke $count block${if (count > 1) "s" else ""} placed by $owner within ${GRIEF_WINDOW_MS / 1_000}s: $top"
        }
    }

    companion object {
        const val SUBSCRIBER = "salience"

        /** Queue of the bus subscription. */
        const val CAPACITY = 8_192

        /** Breaks of another player's blocks are counted over this window, then told once. */
        const val GRIEF_WINDOW_MS = 10_000L

        /** Advancements that wake the Director whatever their frame: the milestones of a world. */
        val NOTABLE_ADVANCEMENTS: Set<String> = setOf(
            "minecraft:story/mine_diamond", "minecraft:story/enter_the_nether", "minecraft:story/follow_ender_eye",
            "minecraft:story/enter_the_end", "minecraft:story/shiny_gear", "minecraft:nether/find_fortress",
            "minecraft:nether/find_bastion", "minecraft:nether/obtain_ancient_debris", "minecraft:nether/netherite_armor",
            "minecraft:end/kill_dragon", "minecraft:end/find_end_city", "minecraft:end/elytra",
            "minecraft:adventure/minecraft_trials_edition",
        )

        /** Structures whose entry wakes the Director even when the player has been in one before. */
        val NOTABLE_STRUCTURES: Set<String> = setOf(
            "minecraft:ancient_city", "minecraft:stronghold", "minecraft:end_city", "minecraft:fortress", "minecraft:bastion_remnant",
            "minecraft:trial_chambers", "minecraft:mansion", "minecraft:monument", "minecraft:desert_pyramid", "minecraft:jungle_pyramid",
        )

        /** Biomes whose first visit wakes the Director; the first visit of another biome waits for the next wake-up. */
        val RARE_BIOMES: Set<String> = setOf(
            "minecraft:mushroom_fields", "minecraft:deep_dark", "minecraft:cherry_grove", "minecraft:ice_spikes",
            "minecraft:eroded_badlands", "minecraft:pale_garden", "minecraft:bamboo_jungle", "minecraft:lush_caves",
            "minecraft:mangrove_swamp", "minecraft:warped_forest", "minecraft:basalt_deltas",
        )

        /** Blocks whose mining the Director hears about at its next wake-up. */
        val NOTABLE_BLOCKS: Set<String> = setOf(
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
        )

        /** The salience table (M1_M2 §12.3): where [event] goes before any chat triage. */
        fun route(event: GameEvent): Route = when (event) {
            is ChatMessage -> if (event.channel == ChatChannel.WHISPER) Route.Never else Route.Triage
            is NarratorSpoke -> if (event.channel == NarratorChannel.OPERATOR) Route.Forward(Urgency.NEXT, "said through an operator") else Route.Batch
            is PlayerDied -> Route.Forward(Urgency.SOON, "death")
            is EntityKilled -> if (event.pvp) Route.Forward(Urgency.SOON, "player killed by ${event.player.name}") else Route.Batch
            is HealthCritical -> Route.Forward(Urgency.SOON, "critical health")
            is PlayerJoined -> Route.Forward(Urgency.SOON, "joined")
            is PlayerLeft -> Route.Forward(Urgency.SOON, "left")
            is AdvancementEarned -> when {
                event.frame == "goal" || event.frame == "challenge" -> Route.Forward(Urgency.SOON, "${event.frame} ${id(event.advancement)}")
                event.advancement in NOTABLE_ADVANCEMENTS -> Route.Forward(Urgency.SOON, "milestone ${id(event.advancement)}")
                else -> Route.Batch
            }
            is StructureEntered -> when {
                event.firstVisit -> Route.Forward(Urgency.SOON, "first visit of ${id(event.structure)}")
                event.structure in NOTABLE_STRUCTURES -> Route.Forward(Urgency.SOON, "entered ${id(event.structure)}")
                else -> Route.Batch
            }
            is BiomeEntered -> when {
                !event.firstVisit -> Route.Batch
                event.biome in RARE_BIOMES -> Route.Forward(Urgency.SOON, "first visit of the rare biome ${id(event.biome)}")
                else -> Route.Forward(Urgency.NEXT, "first visit of ${id(event.biome)}")
            }
            is BlockBroken -> when {
                event.placedBy != null && event.placedBy != event.player.uuid -> Route.Grief
                event.block in NOTABLE_BLOCKS -> Route.Forward(Urgency.NEXT, "mined ${id(event.block)}")
                else -> Route.Batch
            }
            is ExplosionHappened ->
                if (!event.narrator && (event.playerBlocksHit > 0 || event.nearby.isNotEmpty())) {
                    Route.Forward(Urgency.SOON, "explosion")
                } else {
                    Route.Batch
                }
            is LootChestOpened -> Route.Forward(Urgency.NEXT, "loot chest")
            is Teleported -> Route.Forward(Urgency.NEXT, "teleport")
            is DimensionChanged -> Route.Forward(Urgency.NEXT, "dimension change")
            is PlayerRespawned -> Route.Forward(Urgency.NEXT, "respawn")
            else -> Route.Batch
        }

        /** A prayer always wakes the Director; a public message when it is addressed to the Narrator. */
        fun chatUrgency(channel: ChatChannel, triage: ChatTriage?): Urgency =
            if (channel == ChatChannel.PRAY || triage?.addressed == true) Urgency.IMMEDIATE else Urgency.NEXT

        /**
         * How a chat line is annotated for the Director (M1_M2 §12.6): `(to you 0.92, hostile, provocation)`
         * with a classifier (the tone left out when unsure), `(to you: heuristic)` with the heuristic,
         * `(between players)` for a public message that is not addressed, then ` !manipulation 0.81`
         * for a possible manipulation. A prayer is always to the Narrator.
         */
        fun annotation(channel: ChatChannel, triage: ChatTriage?): String {
            if (triage == null) return if (channel == ChatChannel.PRAY) "(to you)" else "(between players)"
            val toYou = channel == ChatChannel.PRAY || triage.addressed
            val verdict = triage.verdict
            val head = when {
                !toYou -> "(between players)"
                triage.byHeuristic -> "(to you: heuristic)"
                else -> "(to you ${p(verdict.toNarrator)}${triage.tone?.let { ", ${it.id}" }.orEmpty()}, ${verdict.intent.id})"
            }
            return if (triage.manipulation) "$head !manipulation ${p(verdict.manipulation)}" else head
        }

        /** What the salience never receives: private messages between players, and stat totals. */
        private fun subscribed(event: GameEvent): Boolean =
            event !is StatsSnapshot && !(event is ChatMessage && event.channel == ChatChannel.WHISPER)

        private fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
