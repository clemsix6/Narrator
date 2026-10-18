package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.CueKind
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.Salience
import io.github.clemsix6.narrator2.director.Urgency
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.search.FrenchAnalyzer
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * Automatic reminiscences (M3 §10.5, §10.6): the memories that come back by themselves, found by [Recall]
 * ([RecallPurpose.REMINISCENCE]) without any model.
 *
 * - [forWake]: the reminiscences of the events of a Director's wake-up, or of the events injected during
 *   a turn; their lines go into the `<memory>` block (M3-13, `WakeAttachments`).
 * - [placeReturn]: a player back where a memory happened, as a `RETURN` [DirectorSignal.MemoryCue] that
 *   `MemoryJobs` (M3-12) posts; called for each position sample.
 * - [anniversary]: a memory of the same day a week, a month or a year before, as an `ANNIVERSARY` cue that
 *   `MemoryJobs` posts; called at the first wake-up of a day.
 *
 * Anti-repetition (M3 §10.6):
 * - a memory already shown in the chapter is not attached again in it: in [ChapterMemory.shownRefs], or
 *   with a `recalls` row of the chapter's conversation (what `recall` and `player_profile` showed);
 * - a memory shown less than `memory.recallCooldownPlayMinutes` of world play ago is not attached, nor
 *   brought back to its place, except the place return of a memory of importance [PRIORITY_IMPORTANCE] or more;
 * - at most `memory.recallPerWake` reminiscences per call of [forWake], one per event, [PER_PLAY_HOUR] per
 *   hour of world play; `memory.recallPerWake` = 0 turns reminiscences and cues off;
 * - above the thresholds of [HybridRecall], a memory is drawn with a probability proportional to its
 *   squared score, seeded by the event's id: the best one wins often, not always, and tests are deterministic.
 *
 * Only memories come back (`M-`): the canon is in every briefing, the interactions are on the sheets.
 * Every memory shown goes into the chapter's [ChapterMemory.shownRefs] and gets a `recalls` row
 * (`reminiscence`), which reinforces it and starts its cooldown. A cue is recorded when it reaches the
 * Director, in [forWake], not when it is posted. Each reminiscence and each cue is a `memory` decision
 * (`recalled`, `cue`).
 *
 * Threads: [forWake] runs on the Director's coroutine (it touches the chapter); [placeReturn] and
 * [anniversary] on the memory jobs' coroutine. Each of the three keeps its own state, one call at a time.
 *
 * @param config the live `memory` settings.
 * @param clock its zone is the day of the anniversaries.
 */
class Reminiscence(
    private val recall: Recall,
    private val store: MemoryStore,
    private val journal: Journal,
    private val playClock: PlayClock,
    private val config: () -> MemoryConfig,
    private val clock: Clock,
    trace: TraceSink = journal,
) {
    private val log = Log.memory
    private val decisions = Decisions(trace, clock)

    /**
     * What one event of a wake-up looks for in the memory (M3 §10.5). [kind] is the event's keyword in
     * `<events>` (`DEATH`, `PVP`…) and [player] the player it is about; [text], [players], [tags] and
     * [entities] make the search. [text] holds French words ([CueWords]), the tags and the game ids, so that
     * the full text finds a memory whatever its sentence.
     */
    data class Cue(
        val eventId: Long,
        val at: Long,
        val kind: String,
        val player: PlayerRef,
        val text: String,
        val players: List<UUID>,
        val tags: List<String>,
        val entities: List<String>,
    ) {
        /** `DEATH Bartok`: how the line of its reminiscence names it (the name escaped). */
        val label: String get() = "$kind ${ModelText.name(player.name)}"
    }

    /**
     * A memory brought back by the event [eventId]. [line] is ready for the `<memory>` block, escaped:
     * `RECALLED for DEATH Bartok: M-120 · 09-16 20:00 (3w ago) · Bartok · ★7 · Mort dans la lave: « … »`.
     */
    data class Recalled(val ref: Ref, val eventId: Long, val line: String)

    // ── forWake: the Director's coroutine ───────────────────────────────────────────────────────────

    /** The reminiscence of each recent event, by event id: attached again when the request that held it failed. */
    private val attached: MutableMap<Long, Recalled> = recent()

    /** The cues that reached the Director, recorded once. */
    private val delivered: MutableSet<DirectorSignal.MemoryCue> = Collections.newSetFromMap(recent())

    /** World play seconds of the reminiscences attached, for [PER_PLAY_HOUR]. */
    private val attachedAt = ArrayDeque<Long>()

    // ── placeReturn: the memory jobs' coroutine ─────────────────────────────────────────────────────

    /** Memories with a place, by dimension, then by cell of [GRID_CELL] blocks. */
    private var grid: Map<String, Map<Long, List<Spot>>> = emptyMap()
    private var gridLoadedAt: Long? = null

    /** World play seconds of the last return of each memory to each player. */
    private val returned = HashMap<Pair<Ref, UUID>, Long>()

    /** World play seconds of the last return of each player. */
    private val lastReturn = HashMap<UUID, Long>()

    /**
     * The reminiscences of [signals] (M3 §10.5): at most `memory.recallPerWake`, one per event, the most
     * telling events first (deaths, kills, griefs, prayers, addressed chat, then places and milestones).
     * Each one shown goes into [chapter]'s [ChapterMemory.shownRefs] and gets a `recalls` row. The
     * [DirectorSignal.MemoryCue]s among [signals] are reaching the Director: their memories count as shown
     * in the chapter, and are recorded once.
     *
     * Called again with the same events after a request that was not accepted (M3-15 then removes what it
     * showed from [chapter]), it gives each event the memory it had, without recording nor counting it a
     * second time. Never throws for the memory's sake: a failing read leaves reminiscences out (`WARN`).
     *
     * @param now epoch milliseconds.
     */
    suspend fun forWake(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): List<Recalled> {
        val started = System.nanoTime()
        delivered(signals, chapter)
        val perWake = config().recallPerWake
        val cues = signals.mapNotNull(::cueOf)
            .sortedWith(compareBy<Cue> { KINDS.indexOf(it.kind) }.thenByDescending { it.at }.thenByDescending { it.eventId })
            .take(MAX_SEARCHES)
        if (perWake <= 0 || cues.isEmpty()) return emptyList()
        val world = playClock.worldPlaySeconds(now)
        val seen = safely("reading the memories seen lately") { seen(world, chapter.conversationId) } ?: return emptyList()
        val recalled = ArrayList<Recalled>()
        for (cue in cues) {
            if (recalled.size >= perWake) break
            val again = attached[cue.eventId]
            if (again != null) {
                if (chapter.shownRefs.add(again.ref)) recalled += again
                continue
            }
            if (!hourAllows(world)) continue
            val hit = search(cue, chapter.shownRefs + seen) ?: continue
            val one = Recalled(hit.ref, cue.eventId, "RECALLED for ${cue.label}: ${hit.line}")
            chapter.shownRefs += hit.ref
            attached[cue.eventId] = one
            attachedAt.addLast(world)
            recalled += one
            record(hit.ref, RecallContext(chapter.conversationId, null, cue.eventId))
            decisions.record(
                log, DecisionRecord.Kind.MEMORY, "recalled", subject = cue.player.name,
                reason = "${hit.ref} for ${cue.kind} (score ${p(hit.score)}, relevance ${p(hit.relevance)})",
                eventId = cue.eventId, conversationId = chapter.conversationId,
            )
        }
        log.debug {
            "reminiscence: ${cues.size} cues (${cues.joinToString { it.kind }}), recalled ${recalled.joinToString { "${it.ref}" }.ifEmpty { "nothing" }} " +
                "in ${(System.nanoTime() - started) / 1_000_000} ms"
        }
        return recalled
    }

    /** The cues of [signals] are shown: in the chapter, and recorded the first time they arrive. */
    private suspend fun delivered(signals: List<DirectorSignal>, chapter: ChapterMemory) {
        signals.filterIsInstance<DirectorSignal.MemoryCue>().forEach { cue ->
            chapter.shownRefs += cue.ref
            if (delivered.add(cue)) record(cue.ref, RecallContext(chapter.conversationId, null, null))
        }
    }

    /**
     * The memories not to bring back: those shown less than the cooldown ago, in world play time (their last
     * recall reinforced them then, M3 §9.2), and those the chapter's [conversationId] has seen whenever,
     * through any channel (`recall` and `player_profile` cannot fill [ChapterMemory.shownRefs]: they leave
     * `recalls` rows of the Director's conversation, M3-09).
     */
    private suspend fun seen(world: Long, conversationId: String): Set<Ref> {
        val since = world - config().recallCooldownPlayMinutes * 60L
        return journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(
                "SELECT id FROM memories WHERE last_recalled_at IS NOT NULL AND reinforced_play_s > ? " +
                    "UNION SELECT ref_id FROM recalls WHERE conversation_id = ? AND ref_id IN (SELECT id FROM memories)",
                since, conversationId,
            ) { Ref(RefKind.MEMORY, getLong(1)) }.toSet()
        }
    }

    /** Fewer than [PER_PLAY_HOUR] reminiscences within the last hour of world play. */
    private fun hourAllows(world: Long): Boolean {
        while (attachedAt.isNotEmpty() && world - attachedAt.first() >= HOUR_S) attachedAt.removeFirst()
        return attachedAt.size < PER_PLAY_HOUR
    }

    /** The memory drawn for [cue], at most one, but none of [exclude]. */
    private suspend fun search(cue: Cue, exclude: Set<Ref>): RecallHit? = safely("the search for ${cue.kind} (event ${cue.eventId})") {
        val query = RecallQuery(
            cue.text, players = cue.players, entities = cue.entities, tags = cue.tags, kinds = MEMORIES, limit = 1,
            purpose = RecallPurpose.REMINISCENCE, exclude = exclude, seed = cue.eventId,
        )
        recall.search(query).firstOrNull()
    }

    /** A `recalls` row for [ref]: it reinforces the memory and starts its cooldown. */
    private suspend fun record(ref: Ref, context: RecallContext) {
        safely("recording $ref (shown, but no cooldown will follow)") { store.recorded(listOf(ref), RecallChannel.REMINISCENCE, context) }
    }

    // ── Place returns ───────────────────────────────────────────────────────────────────────────────

    /** A memory with a place, of importance [RETURN_MIN_IMPORTANCE] or more. */
    private class Spot(val ref: Ref, val pos: Pos, val importance: Int, val players: List<UUID>, val occurredTo: Long) {
        fun distance2(to: Pos): Long {
            val dx = (pos.x - to.x).toLong()
            val dy = (pos.y - to.y).toLong()
            val dz = (pos.z - to.z).toLong()
            return dx * dx + dy * dy + dz * dz
        }

        /** It is about [player], about a player [online], or about the whole server. */
        fun concerns(player: UUID, online: Collection<UUID>): Boolean = players.isEmpty() || player in players || players.any { it in online }
    }

    /**
     * A place return (M3 §10.5): [player], sampled at [pos], is within [RETURN_RADIUS] blocks of where a
     * memory of importance [RETURN_MIN_IMPORTANCE] or more happened, at least [RETURN_MIN_AGE_MS] ago, a
     * memory about them, about a player [online] or about the whole server. Each memory comes back to each
     * player once per [RETURN_AGAIN_S] of world play, and each player has one return per [RETURN_GAP_S] of
     * world play. A memory in its cooldown (shown less than `memory.recallCooldownPlayMinutes` of world play
     * ago) does not come back, except one of importance [PRIORITY_IMPORTANCE] or more, which also passes the
     * player's gap and comes first. Then the most important, the nearest.
     *
     * `MemoryJobs` calls it for each position sample, one at a time, and posts the cue it returns:
     * `RETURN Mimosa is where M-77 happened (1mo ago, ★7): « … »`. Memories with a place are kept in a grid
     * of [GRID_CELL] blocks per dimension, read again every [GRID_REFRESH_MS]. Null when nothing comes back,
     * or when the memory cannot be read (`WARN`).
     *
     * @param now epoch milliseconds.
     */
    suspend fun placeReturn(player: PlayerRef, pos: Pos, online: Collection<UUID>, now: Long): DirectorSignal.MemoryCue? {
        if (config().recallPerWake <= 0) return null
        val spots = nearby(pos, now).filter { it.occurredTo <= now - RETURN_MIN_AGE_MS && it.concerns(player.uuid, online) }
        if (spots.isEmpty()) return null
        val world = playClock.worldPlaySeconds(now)
        val cooldown = config().recallCooldownPlayMinutes * 60L
        val playerFree = lastReturn[player.uuid]?.let { world - it >= RETURN_GAP_S } ?: true
        val order = compareByDescending<Spot> { it.importance }.thenBy { it.distance2(pos) }.thenByDescending { it.ref.id }
        for (spot in spots.sortedWith(order)) {
            val priority = spot.importance >= PRIORITY_IMPORTANCE
            if (!priority && !playerFree) continue
            if (returned[spot.ref to player.uuid]?.let { world - it < RETURN_AGAIN_S } == true) continue
            val memory = safely("place return of ${player.name}") { store.memory(spot.ref) } ?: continue
            if (memory.archived || memory.mergedInto != null) continue
            if (!priority && memory.lastRecalledAt != null && world - memory.reinforcedPlaySeconds < cooldown) continue
            returned.entries.removeIf { world - it.value >= RETURN_AGAIN_S }
            returned[spot.ref to player.uuid] = world
            lastReturn[player.uuid] = world
            val ago = HybridRecall.ago(now - memory.occurredTo.toEpochMilli())
            val line = "${ModelText.name(player.name)} is where ${memory.ref} happened ($ago, ★${memory.importance}): « ${ModelText.escape(memory.content)} »"
            val distance = sqrt(spot.distance2(pos).toDouble()).toInt()
            decisions.record(
                log, DecisionRecord.Kind.MEMORY, "cue", subject = player.name,
                reason = "RETURN ${memory.ref} at $distance blocks (★${memory.importance}${if (priority) ", priority" else ""})",
            )
            return DirectorSignal.MemoryCue(CueKind.RETURN, memory.ref, player, line, now)
        }
        return null
    }

    /** The memories with a place within [RETURN_RADIUS] blocks of [pos]. */
    private suspend fun nearby(pos: Pos, now: Long): List<Spot> {
        refreshGrid(now)
        val cells = grid[pos.dim] ?: return emptyList()
        val radius2 = RETURN_RADIUS.toLong() * RETURN_RADIUS
        val near = ArrayList<Spot>()
        for (cx in (pos.x - RETURN_RADIUS).floorDiv(GRID_CELL)..(pos.x + RETURN_RADIUS).floorDiv(GRID_CELL)) {
            for (cz in (pos.z - RETURN_RADIUS).floorDiv(GRID_CELL)..(pos.z + RETURN_RADIUS).floorDiv(GRID_CELL)) {
                cells[cell(cx, cz)]?.filterTo(near) { it.distance2(pos) <= radius2 }
            }
        }
        return near
    }

    private suspend fun refreshGrid(now: Long) {
        gridLoadedAt?.let { if (now - it < GRID_REFRESH_MS) return }
        gridLoadedAt = now
        val spots = safely("place grid") {
            journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query(
                    "SELECT id, dim, x, y, z, importance, players, epoch_ms(occurred_to) FROM memories " +
                        "WHERE dim IS NOT NULL AND importance >= ? AND NOT archived AND merged_into IS NULL",
                    RETURN_MIN_IMPORTANCE,
                ) { Spot(Ref(RefKind.MEMORY, getLong(1)), Pos(getString(2), getInt(3), getInt(4), getInt(5)), getInt(6), uuids(7), getLong(8)) }
            }
        } ?: return
        grid = spots.groupBy { it.pos.dim }.mapValues { (_, inDim) ->
            inDim.groupBy { cell(it.pos.x.floorDiv(GRID_CELL), it.pos.z.floorDiv(GRID_CELL)) }
        }
        log.debug { "reminiscence: ${spots.size} memories with a place in the grid" }
    }

    // ── Anniversaries ───────────────────────────────────────────────────────────────────────────────

    /** How long before today a memory happened, when today is its anniversary. [rank]: years before months before weeks. */
    internal class Span(val count: Int, val unit: String, val rank: Int) {
        /** `one month`, `3 weeks`. */
        val label: String get() = if (count == 1) "one $unit" else "$count ${unit}s"
    }

    /**
     * The anniversary of the day (M3 §10.5): a memory of importance [ANNIVERSARY_IMPORTANCE] or more that
     * happened the same day 1 to 3 weeks before, 1 to 11 months before, or a whole number of years before
     * (in [clock]'s zone); the most important, then the oldest kind of anniversary (years, months, weeks).
     * One per day at most, whatever happens to the server: the day is kept in `memory_state`
     * ([ANNIVERSARY_STATE]), and taken by the first call of the day, even when no memory fits.
     *
     * `MemoryJobs` calls it at the first wake-up of a day (the first player of the day) and posts the cue:
     * `ANNIVERSARY M-12, one month ago today (★9): « … »`. Null when the day had its call, when no memory fits,
     * or when the memory cannot be read (`WARN`).
     *
     * @param now epoch milliseconds.
     */
    suspend fun anniversary(now: Long): DirectorSignal.MemoryCue? {
        if (config().recallPerWake <= 0) return null
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), clock.zone)
        return safely("anniversary of $today") {
            if (store.state(ANNIVERSARY_STATE) == today.toString()) return@safely null
            store.setState(ANNIVERSARY_STATE, today.toString())
            val dates = journal.read(READ_TIMEOUT_MS) { connection ->
                connection.query(
                    "SELECT id, importance, epoch_ms(occurred_from) FROM memories WHERE importance >= ? AND NOT archived AND merged_into IS NULL",
                    ANNIVERSARY_IMPORTANCE,
                ) { Triple(Ref(RefKind.MEMORY, getLong(1)), getInt(2), LocalDate.ofInstant(Instant.ofEpochMilli(getLong(3)), clock.zone)) }
            }
            val best = dates.mapNotNull { (ref, importance, day) -> span(day, today)?.let { Triple(ref, importance, it) } }
                .maxWithOrNull(compareBy<Triple<Ref, Int, Span>> { it.second }.thenBy { it.third.rank }.thenBy { it.first.id })
                ?: return@safely null
            val memory = store.memory(best.first) ?: return@safely null
            val span = best.third
            decisions.record(log, DecisionRecord.Kind.MEMORY, "cue", reason = "ANNIVERSARY ${memory.ref}, ${span.label} ago today (★${memory.importance})")
            val line = "${memory.ref}, ${span.label} ago today (★${memory.importance}): « ${ModelText.escape(memory.content)} »"
            DirectorSignal.MemoryCue(CueKind.ANNIVERSARY, memory.ref, null, line, now)
        }
    }

    /** [block], or null when it fails for another reason than a cancellation (`WARN`, saying [what]). */
    private suspend fun <T> safely(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("reminiscence: $what failed: ${e.message}")
        null
    }

    companion object {
        /** At most this many searches per call of [forWake], the most telling events first. */
        const val MAX_SEARCHES = 6

        /** Reminiscences per hour of world play, at most. */
        const val PER_PLAY_HOUR = 4

        /** A place return of a memory this important passes the cooldown and the player's gap, and comes first. */
        const val PRIORITY_IMPORTANCE = 9

        /** A place return: within this many blocks of where the memory happened… */
        const val RETURN_RADIUS = 16

        /** …of a memory at least this important… */
        const val RETURN_MIN_IMPORTANCE = 6

        /** …that happened at least this long ago (wall clock)… */
        const val RETURN_MIN_AGE_MS = 24 * 3_600_000L

        /** …comes back to the same player once per this much world play (seconds)… */
        const val RETURN_AGAIN_S = 6 * 3_600L

        /** …and a player has one return per this much world play (seconds). */
        const val RETURN_GAP_S = 3_600L

        /** The side of a cell of the grid of places, in blocks. */
        const val GRID_CELL = 64

        /** The grid of places is read again this often (wall clock). */
        const val GRID_REFRESH_MS = 10 * 60_000L

        /** An anniversary is a memory at least this important. */
        const val ANNIVERSARY_IMPORTANCE = 8

        /** The `memory_state` key of the last day that had its anniversary call (`2026-10-18`). */
        const val ANNIVERSARY_STATE = "anniversary_day"

        /** The events that bring memories back (M3 §10.5), the most telling first. */
        val KINDS: List<String> = listOf("DEATH", "PVP", "GRIEF", "PRAY", "CHAT", "ADVANCEMENT", "STRUCTURE", "BIOME", "DIMENSION")

        private const val HOUR_S = 3_600L
        private const val READ_TIMEOUT_MS = 2_000L
        private const val RECENT = 128

        /** The longest message searched, in characters. */
        private const val CHAT_CHARS = 300

        private const val OVERWORLD = "minecraft:overworld"
        private const val ADDRESSED = "(to you"
        private val MEMORIES = setOf(RefKind.MEMORY)
        private val WHITESPACE = Regex("\\s+")
        private val NOISE: Set<String> = FrenchAnalyzer.terms(CueWords.CHAT_NOISE).toSet()

        private val FALL_CAUSES = setOf(
            "minecraft:fall", "minecraft:ender_pearl", "minecraft:fly_into_wall", "minecraft:stalagmite", "minecraft:out_of_world",
        )
        private val EXPLOSION_CAUSES = setOf("minecraft:explosion", "minecraft:player_explosion", "minecraft:fireworks", "minecraft:bad_respawn_point")
        private val DIMENSION_TAGS = mapOf("minecraft:the_nether" to "nether", "minecraft:the_end" to "end")

        /**
         * What [signal] looks for in the memory (M3 §10.5); null when it brings nothing back:
         *
         * | Event | Tags | Ids | Words | Players |
         * |---|---|---|---|---|
         * | `DEATH` | `death` + the cause's (`fall`, `lava`, `drowning`, `explosion`), `mob`, `pvp` | cause, killing mob | death, cause, mob | the dead, the killing player |
         * | `PVP` | `pvp`, `duel` | | fight | killer, victim |
         * | `STRUCTURE` (first visit or notable) | | structure | its French name | the player |
         * | `BIOME` (rare) | | biome | its French name | the player |
         * | `DIMENSION` (Nether, End) | `nether`, `end` | dimension | its French words | the player |
         * | `ADVANCEMENT` (notable) | | advancement | its French words, its title | the player |
         * | `GRIEF` | `grief`, `build` | | grief | breaker, owner |
         * | `CHAT` addressed, `PRAY` | | | the message, without [CueWords.CHAT_NOISE] | the player |
         *
         * `JOIN` brings nothing back (the sheet does it, M3 §11.2), nor anything else. Notable structures
         * and advancements are those the salience forwards ([Salience.route]); rare biomes are
         * [Salience.RARE_BIOMES]. A message left without a word that means something brings nothing back:
         * a search without text would bring back any memory of the player.
         */
        fun cueOf(signal: DirectorSignal): Cue? {
            if (signal !is DirectorSignal.Event) return null
            val envelope = signal.envelope
            return when (val event = envelope.event) {
                is PlayerDied -> death(envelope, event)
                is EntityKilled -> if (event.pvp) pvp(envelope, event) else null
                is BlockBroken -> grief(signal, event)
                is ChatMessage -> chat(signal, event)
                is StructureEntered ->
                    if (Salience.route(event) is Salience.Route.Forward) {
                        cue(envelope, "STRUCTURE", event.player, listOf(CueWords.structure(event.structure)), entities = listOf(event.structure))
                    } else {
                        null
                    }
                is BiomeEntered ->
                    if (event.biome in Salience.RARE_BIOMES) {
                        cue(envelope, "BIOME", event.player, listOf(CueWords.biome(event.biome)), entities = listOf(event.biome))
                    } else {
                        null
                    }
                is DimensionChanged -> {
                    val dim = event.pos.dim
                    if (dim == OVERWORLD) {
                        null
                    } else {
                        cue(envelope, "DIMENSION", event.player, listOf(CueWords.dimension(dim)), tags = listOfNotNull(DIMENSION_TAGS[dim]), entities = listOf(dim))
                    }
                }
                is AdvancementEarned ->
                    if (Salience.route(event) is Salience.Route.Forward) {
                        val words = listOf(CueWords.advancement(event.advancement), event.title)
                        cue(envelope, "ADVANCEMENT", event.player, words, entities = listOf(event.advancement))
                    } else {
                        null
                    }
                else -> null
            }
        }

        private fun death(envelope: Envelope, died: PlayerDied): Cue {
            val killer = died.killer
            val mob = killer?.takeIf { it.player == null }
            val byPlayer = killer?.player?.takeIf { it.uuid != died.player.uuid }
            val tags = listOfNotNull("death", causeTag(died.cause), "mob".takeIf { mob != null }, "pvp".takeIf { byPlayer != null })
            val words = listOf(CueWords.DEATH, CueWords.death(died.cause), if (mob != null || byPlayer != null) CueWords.KILLED else "", mob?.let { CueWords.mob(it.type) }.orEmpty())
            return cue(envelope, "DEATH", died.player, words, listOfNotNull(byPlayer?.uuid), tags, listOfNotNull(died.cause, mob?.type))
        }

        private fun causeTag(cause: String): String? = when (cause) {
            in FALL_CAUSES -> "fall"
            "minecraft:lava" -> "lava"
            "minecraft:drown" -> "drowning"
            in EXPLOSION_CAUSES -> "explosion"
            else -> null
        }

        private fun pvp(envelope: Envelope, kill: EntityKilled): Cue =
            cue(envelope, "PVP", kill.player, listOf(CueWords.PVP), listOfNotNull(kill.victim.player?.uuid), listOf("pvp", "duel"))

        /** A break the salience counted as a grief (it annotates it): a block another player placed. */
        private fun grief(signal: DirectorSignal.Event, broken: BlockBroken): Cue? {
            val owner = broken.placedBy ?: return null
            if (signal.annotation == null || owner == broken.player.uuid) return null
            return cue(signal.envelope, "GRIEF", broken.player, listOf(CueWords.GRIEF), listOf(owner), listOf("grief", "build"))
        }

        /** A prayer, or a public message addressed to the Narrator (immediate, or annotated `(to you…` when throttled). */
        private fun chat(signal: DirectorSignal.Event, message: ChatMessage): Cue? {
            val addressed = when (message.channel) {
                ChatChannel.PRAY -> true
                ChatChannel.PUBLIC -> signal.urgency == Urgency.IMMEDIATE || signal.annotation?.startsWith(ADDRESSED) == true
                ChatChannel.WHISPER -> false
            }
            if (!addressed) return null
            val words = message.text.take(CHAT_CHARS).split(WHITESPACE).filter { word -> FrenchAnalyzer.terms(word).any { it !in NOISE } }
            if (words.isEmpty()) return null
            return cue(signal.envelope, if (message.channel == ChatChannel.PRAY) "PRAY" else "CHAT", message.player, words)
        }

        /** A cue about [player] (always among its players); its text is [words], [tags] and [entities]. */
        private fun cue(
            envelope: Envelope,
            kind: String,
            player: PlayerRef,
            words: List<String>,
            others: List<UUID> = emptyList(),
            tags: List<String> = emptyList(),
            entities: List<String> = emptyList(),
        ): Cue {
            val text = (words + tags + entities).filter { it.isNotBlank() }.joinToString(" ")
            return Cue(envelope.id, envelope.ts, kind, player, text, (listOf(player.uuid) + others).distinct(), tags.distinct(), entities.distinct())
        }

        /** How long before [today] [day] was, when [today] is its anniversary: 1 to 3 weeks, 1 to 11 months, whole years. */
        internal fun span(day: LocalDate, today: LocalDate): Span? {
            (1..3).firstOrNull { day.plusWeeks(it.toLong()) == today }?.let { return Span(it, "week", 100 + it) }
            val months = ChronoUnit.MONTHS.between(day, today)
            listOf(months, months + 1).firstOrNull { it in 1..11 && day.plusMonths(it) == today }?.let { return Span(it.toInt(), "month", 200 + it.toInt()) }
            val years = ChronoUnit.YEARS.between(day, today)
            listOf(years, years + 1).firstOrNull { it >= 1 && day.plusYears(it) == today }?.let { return Span(it.toInt(), "year", 300 + it.toInt()) }
            return null
        }

        private fun cell(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFF_FFFFL)

        private fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        /** A map that keeps its [RECENT] latest entries. */
        private fun <K, V> recent(): MutableMap<K, V> = object : LinkedHashMap<K, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > RECENT
        }
    }
}
