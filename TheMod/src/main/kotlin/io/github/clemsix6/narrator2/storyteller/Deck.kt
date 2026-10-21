package io.github.clemsix6.narrator2.storyteller

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.ArsenalLine
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.Decay
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRow
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.PlayerSheet
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.FavorKind
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.storyteller.InspireKind
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/*
 * The deck of `inspire` (design M6 §5): ideas of the storyteller's programme, then seeds drawn by the code. A seed is a
 * lever (the real catalogue, never a file) × a hook of the memory × a twist (`twists.txt`) × a comic register used
 * least. What was done, said and dealt lately is left out (§5.4): the deck reads it from the Journal ([History]).
 * [Deck.table] gathers what a draw is made from, [Deck.draw] draws (pure: a fixed seed draws the same), [Deck.record]
 * notes what was dealt.
 */

/** Which twists fit a lever (M6 §5.3): a section of `twists.txt`. Every lever takes the `[any]` twists too; [OTHER] only those. */
enum class LeverFamily {
    CHALLENGE, PUNISHMENT, FAVOR, OTHER;

    val id: String get() = name.lowercase()
}

/**
 * A lever the Narrator can pull (M6 §5.3).
 *
 * @property key its key among the interventions ([Interventions.key]): what rests once used; `running:C-50` for a
 *   mechanic in progress, which never rests.
 * @property label what a seed shows: `favor joker`, `module tag (players 2-8; minutes 2-15 =5)`.
 * @property kinds the draws it answers: [InspireKind.ANY] and its own kind (a mechanic in progress answers `twist` only).
 * @property players the players it needs at least (a duel, two).
 * @property boost how much more it weighs in a draw.
 */
data class Lever(
    val key: String,
    val label: String,
    val family: LeverFamily,
    val kinds: Set<InspireKind>,
    val players: Int = 1,
    val boost: Double = 1.0,
)

/**
 * A hook of the memory (M6 §5.3): something of a player's story a seed hangs on, ready for the model (escaped).
 *
 * @property key what rests once dealt: the row's ref (`M-120`, `C-31`), else `trait:<uuid>:<trait>`,
 *   `stat:<uuid>:<stat>`, `attention:<uuid>`, `relation:<uuid>:<uuid>`.
 * @property player whose it is; null for the world's (a place, a gag of everyone).
 * @property ref the memory row, recorded as shown (`recalls`, channel `inspire`) once dealt to the Director.
 * @property boost how much more it weighs: a player the Narrator forgets, an unforgettable memory.
 * @property punishable whether a punishment may hang on it: not the attention a player lacks (being forgotten is no fault).
 */
data class Hook(
    val key: String,
    val text: String,
    val player: PlayerRef?,
    val ref: Ref? = null,
    val boost: Double = 1.0,
    val punishable: Boolean = true,
)

/** A twist of the deck (`twists.txt`): its [text], and the [family] of levers it fits; null for any lever. */
data class Twist(val text: String, val family: LeverFamily?) {
    /** What rests once dealt. */
    val key: String get() = "twist:$text"
}

/** One seed of a draw: [lever] × [hook] × [twist] × [register]; a hook is missing without memory. */
data class Seed(val lever: Lever, val hook: Hook?, val twist: Twist?, val register: ComicRegister?) {
    /** What it deals: its lever, hook and twist. */
    val keys: List<String> get() = listOfNotNull(lever.key, hook?.key, twist?.key)

    /** `- seed: favor joker × M-120 « Mort dans la lave » (Mimosa, 2h ago) × "the gift comes with an absurd condition" · fairy_tale`. */
    fun line(): String = buildString {
        append("- seed: ").append(lever.label)
        hook?.let { append(" × ").append(ModelText.cut(it.text, Deck.HOOK_LINE_MAX)) }
        twist?.let { append(" × ").append(ModelText.quote(it.text, Deck.TWIST_MAX)) }
        register?.let { append(" · ").append(it.id) }
    }
}

/** Something a draw cannot use now, and why (`Not now: …`), for the draws of [kinds]. */
data class Missing(val text: String, val kinds: Set<InspireKind> = InspireKind.entries.toSet())

/**
 * What the Narrator did and was dealt lately (M6 §5.4), with the world's play to tell how long ago. Ages are in
 * seconds of world play.
 *
 * @property restingArcs the closed arcs whose theme still rests (M4 §10): an idea built on one is left out.
 */
class History(
    val interventions: List<Intervention>,
    val dealt: List<Dealt>,
    val timeline: PlayTimeline,
    val restingArcs: Set<Ref> = emptySet(),
) {
    private val lastUsed: Map<String, Instant> = interventions.groupBy { it.key }.mapValues { (_, uses) -> uses.maxOf { it.at } }
    private val lastDealt: Map<String, Instant> = buildMap {
        for (draw in dealt) draw.keys.forEach { key -> merge(key, draw.at) { a, b -> maxOf(a, b) } }
    }

    /** Seconds of world play since [at]. */
    fun ago(at: Instant): Long = timeline.playedSince(at)

    /** Seconds of world play since the lever [key] was last used, on anyone; null when it was not, lately. */
    fun usedAgo(key: String): Long? = lastUsed[key]?.let(::ago)

    /** Seconds of world play since the lever [key] was last used on [player] (a name, any case); null when it was not. */
    fun usedOnAgo(key: String, player: String): Long? =
        interventions.filter { it.key == key && it.targets.any { target -> target.equals(player, ignoreCase = true) } }
            .maxOfOrNull { it.at }?.let(::ago)

    /** Seconds of world play since [key] was last dealt to the Director; null when it was not, lately. */
    fun dealtAgo(key: String): Long? = lastDealt[key]?.let(::ago)

    companion object {
        /** Nothing done, nothing dealt: what a draw uses when the Journal cannot be read. */
        fun none(now: Instant) = History(emptyList(), emptyList(), PlayTimeline.none(now))

        /** The history from [from] to [now], read on [connection] (`Journal.read`). */
        fun read(connection: Connection, from: Instant, now: Instant): History {
            val timeline = Interventions.timeline(connection, from, now)
            val arcs = connection.query(ARCS_SQL, from) { Ref(RefKind.CANON, getLong(1)) to instant(2) }
            val resting = arcs.filter { (_, closed) -> timeline.playedSince(closed) < Deck.THEME_REST_S }.map { it.first }.toSet()
            return History(Interventions.recent(connection, from), Interventions.dealt(connection, from), timeline, resting)
        }

        /** The arcs with a theme closed since the parameter: their theme rests (M4 §10). */
        private const val ARCS_SQL = "SELECT id, resolved_at FROM canon " +
            "WHERE kind = 'arc' AND theme IS NOT NULL AND resolved_at IS NOT NULL AND resolved_at >= ? AND NOT archived"
    }
}

/**
 * Everything one draw is made from (M6 §5.3, §5.4).
 *
 * @property players the players it centers on: the one named, else the players online; empty for nobody.
 * @property named whether [players] is the player the caller named.
 * @property levers the levers available now, every kind; [Deck.draw] keeps those of [kind].
 * @property ideas the open ideas of the programme, as read.
 * @property worldPlaySeconds the world's play clock now: an idea past its deadline is left out.
 * @property leastUsed the registers least used lately, the least first ([io.github.clemsix6.narrator2.style.StyleWatch.leastUsedRegisters]).
 * @property lastRegisters the registers of the latest labeled lines, the latest first.
 * @property crutches the crutch words at rest.
 */
class Table(
    val now: Instant,
    val players: List<PlayerRef>,
    val named: Boolean,
    val kind: InspireKind,
    val levers: List<Lever>,
    val hooks: List<Hook>,
    val ideas: List<Idea>,
    val worldPlaySeconds: Long,
    val online: List<PlayerRef>,
    val history: History,
    val leastUsed: List<ComicRegister> = emptyList(),
    val lastRegisters: List<ComicRegister> = emptyList(),
    val crutches: List<String> = emptyList(),
    val missing: List<Missing> = emptyList(),
    val twists: List<Twist> = Deck.twists,
) {
    /** [leastUsed] when some line was labeled: without a label, every register is at 0 and the ranking says nothing. */
    val measuredLeastUsed: List<ComicRegister> get() = if (lastRegisters.isEmpty()) emptyList() else leastUsed
}

/** One draw (M6 §5.2): the [ideas] of the programme first, then the [seeds]; [atRest], what was left out and why. */
class Draw(val table: Table, val ideas: List<Idea>, val seeds: List<Seed>, val atRest: List<String>) {
    /** What it dealt, for the `style inspired` decision: the ideas' refs, then each seed's keys. */
    val keys: List<String> get() = ideas.map { it.ref.toString() } + seeds.flatMap { it.keys }

    /** The tool's answer, [Deck.TEXT_MAX] characters at most: the pitches are cut until it fits. */
    fun render(): String {
        for (cut in Deck.PITCH_CUTS) render(cut).let { if (it.length <= Deck.TEXT_MAX) return it }
        return ModelText.cut(render(Deck.PITCH_CUTS.last()), Deck.TEXT_MAX)
    }

    private fun render(pitch: Int): String = buildList {
        add(header())
        ideas.forEach { add(ideaLine(it, pitch)) }
        seeds.forEach { add(it.line()) }
        if (atRest.isNotEmpty()) add("At rest (you did them lately): ${Deck.shown(atRest, Deck.AT_REST_SHOWN)}")
        val missing = table.missing.filter { table.kind in it.kinds }.map { it.text }
        if (missing.isNotEmpty()) add("Not now: ${missing.joinToString(" · ")}")
    }.joinToString("\n")

    private fun header(): String {
        val count = ideas.size + seeds.size
        val kind = if (table.kind == InspireKind.ANY) "" else "${table.kind.id} "
        val who = if (table.players.isEmpty()) "everyone" else ToolText.names(table.players)
        val least = table.measuredLeastUsed.takeIf { it.isNotEmpty() }?.let { " (least used lately: ${it.joinToString { register -> register.id }})" }.orEmpty()
        return if (count == 0) "ok: no ${kind}idea for $who now$least" else "ok: $count ${kind}idea${if (count > 1) "s" else ""} for $who$least"
    }

    /** `- P-41 challenge · Bartok, Mimosa · « La course au pic »: … (fits: …) · novelty 4 fun 4 fair 5 · game_show`. */
    private fun ideaLine(idea: Idea, pitch: Int): String = buildString {
        append("- ").append(idea.ref).append(' ').append(idea.kind.id)
        append(" · ").append(if (idea.players.isEmpty()) "everyone" else ToolText.names(idea.players))
        append(" · « ").append(ModelText.cut(ModelText.escape(idea.title), MemoryLimits.IDEA_TITLE)).append(" »: ")
        append(ModelText.cut(ModelText.escape(idea.pitch), pitch))
        append(" (fits: ").append(ModelText.cut(ModelText.escape(idea.fit), Deck.FIT_CUT)).append(')')
        append(" · novelty ${idea.novelty} fun ${idea.funScore} fair ${idea.fairness}")
        idea.register?.let { append(" · ").append(it.id) }
    }
}

/** The levers available now, every kind, and what is missing (M6 §5.3). */
class Levers(val levers: List<Lever>, val missing: List<Missing>)

object Deck {
    private val log = Log.story

    /** What a draw deals (M6 §5.1): ideas of the programme first, [IDEAS_MAX] at most, then seeds. */
    const val DRAWN = 3
    const val IDEAS_MAX = 2

    /** The longest draw (M6 §5.2). */
    const val TEXT_MAX = 1_500

    /** The longest card of one idea (`idea`): pitch 500, fit 200, six refs, and the 1 500 of a forge brief. */
    const val CARD_MAX = 3_200

    /** The longest twist (`twists.txt`). */
    const val TWIST_MAX = 90

    /** The longest hook a seed shows. */
    const val HOOK_LINE_MAX = 130

    /** What a draw cuts an idea's pitch to, the longest that lets it fit [TEXT_MAX] first. */
    val PITCH_CUTS = listOf(240, 160, 100, 60)
    const val FIT_CUT = 80

    /** Things at rest shown at most; the others are counted. */
    const val AT_REST_SHOWN = 8

    private const val HOUR = 3_600L

    // What rests, in seconds of world play (M6 §5.4).

    /** A lever used on anyone. */
    const val LEVER_REST_S = 3 * HOUR

    /** A lever used on the player a seed is for. */
    const val PLAYER_REST_S = 6 * HOUR

    /** A hook or a twist dealt to the Director. */
    const val HOOK_REST_S = 3 * HOUR

    /** An idea of the programme dealt to the Director (a heartbeat counts: `ideas.last_dealt_at`). */
    const val IDEA_REST_S = 2 * HOUR

    /** A theme, once its arc closed (M4 §10). */
    const val THEME_REST_S = MemoryLimits.THEME_REST_PLAY_HOURS * HOUR

    /** The registers of the latest labeled lines watched: one that holds half of them rests. */
    const val LAST_REGISTERS = 8

    /** The registers a draw picks from, the least used. */
    const val LEAST_USED = 3

    /** How far back the history is read, in calendar time: older is « never ». */
    val HISTORY_WINDOW: Duration = Duration.ofDays(14)

    // Within one draw, what is already in it weighs less: another family of lever, another player.
    private const val SAME_FAMILY = 0.25
    private const val SAME_PLAYER = 0.3

    // Hooks (M6 §5.3).
    private const val HOOK_PLAYERS = 4
    private const val MEMORIES = 2
    private const val MEMORY_IMPORTANCE = 5
    private const val UNFORGETTABLE = 8
    private const val SURE = 0.75
    private const val TRAITS = 2
    private const val STATS = 2
    private const val RELATIONS = 2
    private const val ATTENTION_S = HOUR
    private const val FRESH_NETHER_DAYS = 3L
    private const val WORLD_CANON = 30
    private const val HOOK_CUT = 80
    private const val LABEL_CUT = 110
    private const val BOOST = 2.0

    // Time limits: a draw is instant, and the tool has 5 s.
    private const val BARRIER_TIMEOUT_MS = 500L
    private const val HISTORY_TIMEOUT_MS = 2_000L
    private const val MEMORY_TIMEOUT_MS = 2_000L
    private const val WRITE_TIMEOUT_MS = 1_000L

    /**
     * How much an element weighs by how long ago it was served, in seconds of world play (M6 §5.3): never 4; under
     * 6 h 0, drawn only when nothing else is left.
     */
    fun weight(ageSeconds: Long?): Int = when {
        ageSeconds == null -> 4
        ageSeconds < 6 * HOUR -> 0
        ageSeconds < 12 * HOUR -> 1
        ageSeconds < 24 * HOUR -> 2
        ageSeconds < 48 * HOUR -> 3
        else -> 4
    }

    // ── The draw ────────────────────────────────────────────────────────────────────────────────────

    /** Draws from [table] with [random] (M6 §5.2 to §5.4): the same table and seed draw the same. Pure. */
    fun draw(table: Table, random: Random): Draw {
        val ideas = ideas(table)
        val resting = restingRegisters(table)
        val registers = table.measuredLeastUsed.filter { it !in resting }.take(LEAST_USED)
            .ifEmpty { (ComicRegister.VOICE - resting - table.lastRegisters.toSet()).shuffled(random).take(LEAST_USED) }
        val levers = table.levers.filter { table.kind in it.kinds && (table.history.usedAgo(it.key) ?: Long.MAX_VALUE) >= LEVER_REST_S }
        val seeds = mutableListOf<Seed>()
        repeat(DRAWN - ideas.size) { seed(table, levers, registers, seeds, random)?.let(seeds::add) }
        return Draw(table, ideas, seeds, atRest(table, resting))
    }

    /**
     * The ideas of the programme that fit (M6 §5.4): open, not a season, of the kind asked, before their deadline, for
     * the players (the one named, or those online), not dealt in the last [IDEA_REST_S] of play, not built on an arc
     * whose theme rests; those for the players first, then the best noted, the least dealt, the newest.
     */
    private fun ideas(table: Table): List<Idea> {
        val history = table.history
        val centered = table.players.map { it.uuid }.toSet()
        val online = table.online.map { it.uuid }.toSet()
        fun fits(idea: Idea): Boolean = when {
            idea.players.isEmpty() -> true
            table.named -> idea.players.any { it.uuid in centered }
            else -> online.isNotEmpty() && idea.players.all { it.uuid in online }
        }
        return table.ideas.asSequence()
            .filter { it.status == IdeaStatus.OPEN && table.kind in kinds(it.kind) && it.expiresPlaySeconds > table.worldPlaySeconds }
            .filter(::fits)
            .filter { idea -> idea.lastDealtAt?.let { history.ago(it) >= IDEA_REST_S } ?: true }
            .filter { idea -> idea.refs.none { it in history.restingArcs } }
            .sortedWith(
                compareByDescending<Idea> { idea -> idea.players.any { it.uuid in centered } }
                    .thenByDescending { it.novelty + it.funScore + it.fairness }
                    .thenBy { it.dealt }
                    .thenByDescending { it.ref.id },
            )
            .take(IDEAS_MAX)
            .toList()
    }

    /** The draws an idea of [kind] answers; a season none (the briefing shows it). */
    fun kinds(kind: IdeaKind): Set<InspireKind> = when (kind) {
        IdeaKind.SEASON -> emptySet()
        IdeaKind.CHALLENGE, IdeaKind.DUEL -> setOf(InspireKind.ANY, InspireKind.CHALLENGE)
        IdeaKind.PUNISHMENT, IdeaKind.LAW -> setOf(InspireKind.ANY, InspireKind.PUNISHMENT)
        IdeaKind.FAVOR -> setOf(InspireKind.ANY, InspireKind.FAVOR)
        IdeaKind.TWIST, IdeaKind.ARC_STEP, IdeaKind.PLAN, IdeaKind.SPEECH -> setOf(InspireKind.ANY, InspireKind.TWIST)
        IdeaKind.MODULE, IdeaKind.FORGE -> setOf(InspireKind.ANY)
    }

    /** The registers of the voice that hold half of the latest [LAST_REGISTERS] labeled lines (M6 §5.4). */
    private fun restingRegisters(table: Table): Set<ComicRegister> =
        table.lastRegisters.take(LAST_REGISTERS).filter { it in ComicRegister.VOICE }.groupingBy { it }.eachCount()
            .filterValues { it * 2 >= LAST_REGISTERS }.keys

    /**
     * One seed: a lever and a hook drawn together (a lever rests on the seed's player for [PLAYER_REST_S]), weighted by
     * how long ago each was served ([weight]: a lever used or dealt, a hook dealt), each family of levers weighing as
     * much as another, then a twist of the lever's family and a register. Nothing already in the draw comes again;
     * another family of lever and another player weigh more.
     */
    private fun seed(table: Table, levers: List<Lever>, registers: List<ComicRegister>, taken: List<Seed>, random: Random): Seed? {
        val history = table.history
        val takenLevers = taken.map { it.lever.key }.toSet()
        val takenHooks = taken.mapNotNull { it.hook?.key }.toSet()
        val takenPlayers = taken.mapNotNull { it.hook?.player?.uuid }.toSet()
        val takenFamilies = taken.map { it.lever.family }.toSet()
        val hooks = table.hooks.filter { it.key !in takenHooks && (history.dealtAgo(it.key) ?: Long.MAX_VALUE) >= HOOK_REST_S }
        val family = levers.groupingBy { it.family }.eachCount() // a family weighs as much as another, whatever its number of levers
        val pairs = levers.filter { it.key !in takenLevers && fitsPlayers(table, it) }.flatMap { lever ->
            val fit = hooks.filter { (it.punishable || lever.family != LeverFamily.PUNISHMENT) && restsNotOn(table, lever, it.player) }
            when {
                fit.isNotEmpty() -> fit.map { lever to it }
                restsNotOn(table, lever, null) -> listOf(lever to null)
                else -> emptyList()
            }
        }
        val (lever, hook) = pick(pairs, random) { (lever, hook) ->
            val leverAge = listOfNotNull(history.usedAgo(lever.key), history.dealtAgo(lever.key)).minOrNull()
            val leverWeight = weight(leverAge) * lever.boost / family.getValue(lever.family) * (if (lever.family in takenFamilies) SAME_FAMILY else 1.0)
            val hookWeight = hook?.let { weight(history.dealtAgo(it.key)) * it.boost * (if (it.player?.uuid in takenPlayers) SAME_PLAYER else 1.0) } ?: 1.0
            leverWeight * hookWeight
        } ?: return null
        val takenTwists = taken.mapNotNull { it.twist?.key }.toSet()
        val twists = table.twists.filter { twist ->
            (twist.family == null || twist.family == lever.family) && twist.key !in takenTwists &&
                (history.dealtAgo(twist.key) ?: Long.MAX_VALUE) >= HOOK_REST_S
        }
        val twist = pick(twists, random) { weight(history.dealtAgo(it.key)).toDouble() }
        val free = registers - taken.mapNotNull { it.register }.toSet()
        val register = (free.ifEmpty { registers }).takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
        return Seed(lever, hook, twist, register)
    }

    /** Whether [lever] has the players it needs: those the draw centers on, or those online. */
    private fun fitsPlayers(table: Table, lever: Lever): Boolean = lever.players <= maxOf(table.players.size, table.online.size, 1)

    /** Whether [lever] does not rest on the seed's player: [player], else the one player the draw centers on. */
    private fun restsNotOn(table: Table, lever: Lever, player: PlayerRef?): Boolean {
        val target = player ?: table.players.singleOrNull() ?: return true
        return (table.history.usedOnAgo(lever.key, target.name) ?: Long.MAX_VALUE) >= PLAYER_REST_S
    }

    /** One of [items] drawn by [weight]; when every weight is 0, one of them all (the last resort, M6 §5.3). */
    private fun <T> pick(items: List<T>, random: Random, weight: (T) -> Double): T? {
        if (items.isEmpty()) return null
        val weights = items.map(weight)
        val total = weights.sum()
        if (total <= 0.0) return items[random.nextInt(items.size)]
        var left = random.nextDouble() * total
        for ((index, item) in items.withIndex()) {
            left -= weights[index]
            if (left < 0) return item
        }
        return items[weights.indexOfLast { it > 0 }] // rounding left a crumb
    }

    /**
     * What the draw left out, as the model reads it: the levers used lately (on anyone for [LEVER_REST_S], on the
     * players it centers on for [PLAYER_REST_S]), the latest first, `punish tiny (Bartok, 40m ago)`; then the registers
     * at rest; then the crutch words at rest, `« céleste »`.
     */
    private fun atRest(table: Table, registers: Set<ComicRegister>): List<String> {
        val history = table.history
        val names = table.players.map { it.name }
        val levers = history.interventions.filter { !it.key.startsWith(SAY) }.groupBy { it.key }.mapNotNull { (key, uses) ->
            val latest = uses.maxBy { it.at }
            val onPlayer = uses.filter { use -> use.targets.any { target -> names.any { it.equals(target, ignoreCase = true) } } }.maxByOrNull { it.at }
            when {
                history.ago(latest.at) < LEVER_REST_S -> key to latest
                onPlayer != null && history.ago(onPlayer.at) < PLAYER_REST_S -> key to onPlayer
                else -> null
            }
        }.sortedByDescending { it.second.at }.map { (key, use) ->
            val who = use.targets.take(2).joinToString { ModelText.name(it) }.let { if (it.isEmpty()) "" else "$it, " }
            "${Interventions.label(key)} ($who${ToolText.duration(history.ago(use.at) * 1_000)} ago)"
        }
        return levers + registers.map { it.id } + table.crutches.map { "« ${ModelText.escape(it)} »" }
    }

    private const val SAY = "say:"

    /** [items] joined, the first [max] of them, the others counted: `a · b · (+3 more)`. */
    internal fun shown(items: List<String>, max: Int): String =
        (items.take(max) + listOfNotNull(if (items.size > max) "(+${items.size - max} more)" else null)).joinToString(" · ")

    // ── The levers (M6 §5.3) ────────────────────────────────────────────────────────────────────────

    /**
     * The levers the Narrator can pull now, from the real catalogue (never a file): the goals of a challenge, a duel,
     * the punishments of the catalogue, the favors, a law, a plan, the ready modules of the arsenal with their
     * parameters (the mechanics engine running; the punishments not at chaos 0; the modules with the Forge on); a
     * named item, a book and a prophecy, the raw tools, always; and for a twist, the mechanics in progress. Any thread.
     */
    fun levers(services: NarratorServices): Levers {
        val levers = mutableListOf<Lever>()
        val missing = mutableListOf<Missing>()
        val mechanics = services.mechanics
        fun answering(kind: InspireKind) = setOf(InspireKind.ANY, kind)
        if (mechanics.available) {
            GoalKind.entries.forEach { levers += Lever("challenge:${it.id}", "challenge ${it.id}", LeverFamily.CHALLENGE, answering(InspireKind.CHALLENGE)) }
            levers += Lever(Interventions.DUEL, "duel", LeverFamily.CHALLENGE, answering(InspireKind.CHALLENGE), players = 2)
            if (services.guards.caps().hostileAllowed) {
                PunishmentId.entries.forEach { levers += Lever("punish:${it.id}", "punish ${it.id}", LeverFamily.PUNISHMENT, answering(InspireKind.PUNISHMENT)) }
            } else {
                missing += Missing("punishments (chaos 0)", answering(InspireKind.PUNISHMENT))
            }
            FavorKind.entries.forEach { levers += Lever("favor:${it.id}", "favor ${it.id}", LeverFamily.FAVOR, answering(InspireKind.FAVOR)) }
            levers += Lever(Interventions.LAW, "law", LeverFamily.OTHER, answering(InspireKind.PUNISHMENT))
            levers += Lever(Interventions.PLAN, "plan", LeverFamily.OTHER, answering(InspireKind.TWIST))
            val forge = services.forge
            when {
                !forge.available -> missing += Missing("modules (the Forge is unavailable: ${forge.unavailableReason})")
                mechanics.modules.halted != null -> missing += Missing("modules (the Forge is switched off)")
                else -> levers += modules(forge.arsenal.list())
            }
            levers += running(services)
        } else {
            missing += Missing("challenges, duels, punishments, favors, laws, plans and modules (the mechanics are unavailable: ${mechanics.unavailableReason})")
        }
        levers += Lever(Interventions.ITEM, Interventions.label(Interventions.ITEM), LeverFamily.FAVOR, answering(InspireKind.FAVOR))
        levers += Lever(Interventions.BOOK, "book", LeverFamily.FAVOR, setOf(InspireKind.ANY, InspireKind.FAVOR, InspireKind.TWIST))
        levers += Lever(Interventions.PROPHECY, "prophecy", LeverFamily.OTHER, answering(InspireKind.TWIST))
        return Levers(levers, missing)
    }

    /**
     * The ready modules of the arsenal (its latest ready version of each name): a game for several players is a
     * challenge, any other a twist.
     */
    fun modules(arsenal: List<ArsenalEntry>): List<Lever> = arsenal.filter { it.status == ModuleStatus.READY }
        .sortedByDescending { it.version }.distinctBy { it.name }.sortedBy { it.name }.map { entry ->
            val game = entry.params.any { it.type == ParamType.PLAYERS }
            val kind = if (game) InspireKind.CHALLENGE else InspireKind.TWIST
            Lever("module:${entry.name}", "module ${module(entry)}", if (game) LeverFamily.CHALLENGE else LeverFamily.OTHER, setOf(InspireKind.ANY, kind))
        }

    /** A module as a seed shows it, its parameters as the briefing's `Arsenal` line writes them: `tag (players 2-8; minutes 2-15 =5)`. */
    private fun module(entry: ArsenalEntry): String {
        val params = entry.params.takeIf { it.isNotEmpty() }?.joinToString("; ", " (", ")") { ArsenalLine.param(it) }.orEmpty()
        return ModelText.cut("${ModelText.escape(entry.name)}$params", LABEL_CUT)
    }

    /** The mechanics in progress, to twist (`twist` draws only): challenges and duels, rules, modules. */
    private fun running(services: NarratorServices): List<Lever> {
        val mechanics = services.mechanics
        fun lever(ref: Ref, line: String, family: LeverFamily) =
            Lever("running:$ref", ModelText.cut("in progress: ${ModelText.escape(line)}", LABEL_CUT), family, setOf(InspireKind.TWIST), boost = BOOST)
        return mechanics.challenges.list().map { lever(it.ref, it.line, LeverFamily.CHALLENGE) } +
            mechanics.rules.list().map { lever(it.ref, it.line, if (it.spec.kind == RuleKind.LAW) LeverFamily.PUNISHMENT else LeverFamily.OTHER) } +
            mechanics.modules.list().map { lever(it.ref, it.line, LeverFamily.OTHER) }
    }

    // ── The hooks (M6 §5.3) ─────────────────────────────────────────────────────────────────────────

    /**
     * The hooks of one player's [sheet] at [now] ([playerPlaySeconds]: their play clock, for the traits' decay): their
     * strongest recent memories, their named places and fresh gags, their sure traits, facts of their progression, the
     * Narrator's attention they lack, their relations. Pure.
     */
    fun hooks(sheet: PlayerSheet, playerPlaySeconds: Long, now: Instant): List<Hook> = buildList {
        val player = sheet.player
        val name = player.shownName
        sheet.memories.filter { it.importance >= MEMORY_IMPORTANCE && !it.archived }.sortedByDescending { it.occurredTo }.take(MEMORIES)
            .forEach { memory ->
                val ago = HybridRecall.ago(now.toEpochMilli() - memory.occurredTo.toEpochMilli())
                val text = "${memory.ref} « ${cut(memory.title)} » ($name, $ago)"
                add(Hook(memory.ref.toString(), text, player, memory.ref, if (memory.importance >= UNFORGETTABLE) BOOST else 1.0))
            }
        sheet.openCanon.filter(::makesHook).forEach { add(canonHook(it, player)) }
        sheet.traits.filter { !it.retired && Decay.traitConfidence(it, playerPlaySeconds) >= SURE }.sortedByDescending { it.intensity }.take(TRAITS)
            .forEach { trait ->
                val pole = TraitAxis.of(trait.trait)?.pole(trait.side) ?: cut(trait.label ?: trait.trait.removePrefix(TraitAxis.FREE_PREFIX))
                add(Hook("trait:${player.uuid}:${trait.trait}", "$name $pole ${trait.intensity}/5", player))
            }
        progress(sheet, now).take(STATS).forEach { (stat, fact) -> add(Hook("stat:${player.uuid}:$stat", "$name: $fact", player)) }
        val attention = sheet.lastAttentionAgoPlayedSeconds
        if (attention == null || attention >= ATTENTION_S) {
            val text = attention?.let { "$name: none of your attention for ${ToolText.duration(it * 1_000)} of play" } ?: "$name: never had your attention"
            add(Hook("attention:${player.uuid}", text, player, boost = BOOST, punishable = false))
        }
        sheet.relations.filter { it.kind != RelationKind.STRANGERS }.take(RELATIONS).forEach { relation ->
            add(Hook("relation:${player.uuid}:${relation.other.uuid}", "$name ${relation.kind.id} of ${relation.other.shownName} « ${cut(relation.note)} »", player))
        }
    }

    /** The named places and fresh gags of [entries] (open canon) for [players], or for everyone; whose they are. Pure. */
    fun worldHooks(entries: List<CanonEntry>, players: List<PlayerRef>): List<Hook> {
        val ids = players.map { it.uuid }.toSet()
        return entries.filter(::makesHook).mapNotNull { entry ->
            val theirs = entry.players.firstOrNull { it.uuid in ids }
            when {
                entry.players.isEmpty() -> canonHook(entry, null)
                theirs != null -> canonHook(entry, players.first { it.uuid == theirs.uuid })
                else -> null
            }
        }
    }

    /** Whether [entry] makes a hook: a named place, or a running gag still fresh; open and not archived. */
    private fun makesHook(entry: CanonEntry): Boolean = entry.status.open && !entry.archived && when (entry.kind) {
        CanonKind.PLACE -> true
        CanonKind.RUNNING_GAG -> entry.freshness != GagState.WORN && entry.freshness != GagState.RETIRED
        else -> false
    }

    /** `C-31 place « la Canopée »`, `C-27 running gag « Le Narrateur commente… »`. */
    private fun canonHook(entry: CanonEntry, player: PlayerRef?): Hook {
        val what = if (entry.kind == CanonKind.PLACE) "place" else "running gag"
        val text = if (entry.kind == CanonKind.PLACE) entry.quote ?: entry.statement else entry.statement
        return Hook(entry.ref.toString(), "${entry.ref} $what « ${cut(text)} »", player, entry.ref)
    }

    /**
     * Facts of the player's progression, from `sheet_stats` (M3 §6.1), the most telling first: the Nether (never, or
     * the first time lately), the End, no armor yet, the bosses beaten, the diamonds, the deaths.
     */
    private fun progress(sheet: PlayerSheet, now: Instant): List<Pair<String, String>> = buildList {
        val stats = sheet.stats
        stats["exploration"]?.detail?.let { exploration ->
            val nether = exploration.text("nether_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            when {
                nether == null -> add("nether" to "never been to the Nether")
                Duration.between(nether, now) < Duration.ofDays(FRESH_NETHER_DAYS) ->
                    add("nether" to "first went to the Nether ${HybridRecall.ago(now.toEpochMilli() - nether.toEpochMilli())}")
                exploration.text("end_at") == null -> add("end" to "never been to the End")
            }
        }
        stats["gear"]?.let { gear -> if (gear.detail?.text("armor") == null) add("gear" to "no armor yet") }
        (stats["kills"]?.detail?.get("bosses") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }?.let { bosses ->
            add("kills" to "has beaten the ${bosses.joinToString { ModelText.escape(it) }}")
        }
        (stats["mining"]?.detail?.get("ores") as? JsonObject)?.long("diamond")?.takeIf { it > 0 }?.let { add("mining" to "$it diamonds mined") }
        stats["deaths"]?.let { deaths ->
            val count = deaths.value?.toLong()?.takeIf { it > 0 } ?: return@let
            val top = (deaths.detail?.get("top") as? JsonArray)?.firstOrNull() as? JsonArray
            val cause = (top?.getOrNull(0) as? JsonPrimitive)?.content?.let { ", mostly ${ModelText.escape(it)}" }.orEmpty()
            add("deaths" to "$count deaths$cause")
        }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun cut(text: String): String = ModelText.cut(ModelText.escape(text), HOOK_CUT)

    // ── The twists (M6 §5.3) ────────────────────────────────────────────────────────────────────────

    /** Resource path of the twists. */
    const val TWISTS = "/narrator2/inspire/twists.txt"

    /** The twists shipped in the jar ([TWISTS]), read once. */
    val twists: List<Twist> by lazy {
        val text = Deck::class.java.getResourceAsStream(TWISTS)?.use { it.readBytes().decodeToString() } ?: error("$TWISTS is missing from the jar")
        parseTwists(text, TWISTS)
    }

    private val SECTION = Regex("\\[([a-z]+)]")

    /**
     * Reads a twist file (format in `twists.txt`). Throws [IllegalArgumentException] naming the line for an unknown
     * section, a twist outside a section, too long or twice, and for a section without twists.
     */
    fun parseTwists(text: String, source: String): List<Twist> {
        val sections = mapOf("any" to null) + LeverFamily.entries.filter { it != LeverFamily.OTHER }.associateBy { it.id }
        val twists = mutableListOf<Twist>()
        val seen = mutableSetOf<String>()
        var current: String? = null
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val where = "$source:${index + 1}"
            SECTION.matchEntire(line)?.let { match ->
                val name = match.groupValues[1]
                require(name in sections) { "$where: unknown section [$name]" }
                current = name
                return@forEachIndexed
            }
            val section = current ?: throw IllegalArgumentException("$where: twist outside a section")
            require(line.length <= TWIST_MAX) { "$where: a twist holds $TWIST_MAX characters at most" }
            require(seen.add(line.lowercase())) { "$where: « $line » appears twice" }
            twists += Twist(line, sections.getValue(section))
        }
        val empty = sections.entries.filter { (_, family) -> twists.none { it.family == family } }.map { it.key }
        require(empty.isEmpty()) { "$source: no twist in ${empty.joinToString { "[$it]" }}" }
        return twists
    }

    // ── Gathering and recording ─────────────────────────────────────────────────────────────────────

    /**
     * Gathers a draw's [Table] for [players] ([named] when the caller named them) and [kind] at [now]: the history from
     * the Journal (after a barrier, so that a draw just recorded counts), the levers, the open ideas (the memory's cache)
     * and the players' hooks (2 s at most). What cannot be read is said in [Table.missing], never failed. Never on the
     * server thread.
     */
    suspend fun table(services: NarratorServices, players: List<PlayerRef>, named: Boolean, kind: InspireKind, now: Instant): Table {
        val started = System.nanoTime()
        val (history, memory) = coroutineScope {
            val history = async { history(services, now) }
            val memory = async { memory(services.memory, players, now) }
            history.await() to memory.await()
        }
        val levers = levers(services)
        val style = services.style
        val table = Table(
            now = now,
            players = players,
            named = named,
            kind = kind,
            levers = levers.levers,
            hooks = memory.hooks,
            ideas = memory.ideas,
            worldPlaySeconds = memory.worldPlaySeconds,
            online = services.players.online.map { it.ref },
            history = history.first,
            leastUsed = style.leastUsedRegisters(LEAST_USED),
            lastRegisters = style.lastRegisters(LAST_REGISTERS),
            crutches = style.atRest().crutches,
            missing = levers.missing + listOfNotNull(history.second, memory.missing),
        )
        log.debug {
            "inspire table: ${table.levers.size} levers, ${table.hooks.size} hooks, ${table.ideas.size} open ideas, " +
                "${table.history.interventions.size} interventions, ${table.history.dealt.size} draws in ${(System.nanoTime() - started) / 1_000_000}ms"
        }
        return table
    }

    /** The history from the Journal, after a barrier; without it, nothing is left out, and the draw says why. */
    private suspend fun history(services: NarratorServices, now: Instant): Pair<History, Missing?> = try {
        withTimeoutOrNull(BARRIER_TIMEOUT_MS) { services.journal.barrier() }
        services.journal.read(HISTORY_TIMEOUT_MS) { History.read(it, now.minus(HISTORY_WINDOW), now) } to null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = e.message ?: e.javaClass.simpleName
        log.warn("inspire draws without what was done lately: $why")
        History.none(now) to Missing("what you did lately (unreadable: $why): nothing is left out")
    }

    /** What the memory gives a draw: the players' hooks, the open ideas, the world's play clock; or why it gives nothing. */
    private class FromMemory(val hooks: List<Hook>, val ideas: List<Idea>, val worldPlaySeconds: Long, val missing: Missing? = null) {
        companion object {
            fun none(why: String) = FromMemory(emptyList(), emptyList(), 0, Missing("hooks and programme ($why)"))
        }
    }

    private suspend fun memory(memory: Memory, players: List<PlayerRef>, now: Instant): FromMemory {
        if (!memory.available) return FromMemory.none("the memory is unavailable: ${memory.unavailableReason}")
        return try {
            withTimeoutOrNull(MEMORY_TIMEOUT_MS) { readMemory(memory, players, now) }
                ?: FromMemory.none("the memory did not answer within ${ToolText.duration(MEMORY_TIMEOUT_MS)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            log.warn("inspire draws without the memory: $why")
            FromMemory.none("the memory could not be read: $why")
        }
    }

    private suspend fun readMemory(memory: Memory, players: List<PlayerRef>, now: Instant): FromMemory {
        val millis = now.toEpochMilli()
        val ideas = memory.openIdeas() // the cache, read again by the store after each dealt: never stale for a draw
        val own = players.take(HOOK_PLAYERS).flatMap { player ->
            memory.sheets.sheet(player.uuid, millis)?.let { hooks(it, memory.clock.playerPlaySeconds(player.uuid, millis), now) }.orEmpty()
        }
        val world = worldHooks(memory.store.canon(CanonFilter(kinds = setOf(CanonKind.PLACE, CanonKind.RUNNING_GAG), limit = WORLD_CANON)), players)
        val keys = own.map { it.key }.toSet()
        return FromMemory(own + world.filter { it.key !in keys }, ideas, memory.clock.worldPlaySeconds(millis))
    }

    /**
     * Notes what [draw] dealt [caller] (M6 §5.4): a `style inspired` decision with its keys, which later draws read back;
     * for the Director, the ideas dealt (`ideas.dealt`, `recalls`) and the memories and canon rows of its hooks shown
     * (`recalls`, channel `inspire`, which reinforces them). The storyteller's own draws reach no player: they are
     * recorded, never dealt. A write that fails or lingers is logged; the draw stands.
     */
    suspend fun record(services: NarratorServices, draw: Draw, caller: Caller) {
        val ideas = draw.ideas.map { it.ref }
        val hooks = draw.seeds.mapNotNull { it.hook?.ref }.distinct()
        val who = if (draw.table.players.isEmpty()) "everyone" else draw.table.players.joinToString { it.name }
        Decisions(services.trace, services.clock).record(
            log,
            DecisionRecord.Kind.STYLE,
            Interventions.INSPIRED,
            subject = who,
            reason = "${draw.ideas.size + draw.seeds.size} ideas to the ${caller.role.id}: " +
                (ideas.map { it.toString() } + draw.seeds.map { seed -> listOfNotNull(seed.lever.key, seed.hook?.key).joinToString(" × ") })
                    .joinToString(" · ").ifEmpty { "none" },
            conversationId = caller.conversationId,
            turnId = caller.turnId,
            detail = buildJsonObject {
                put("role", caller.role.id)
                put("kind", draw.table.kind.id)
                putJsonArray("players") { draw.table.players.forEach { add(it.name) } }
                putJsonArray("ideas") { ideas.forEach { add(it.toString()) } }
                putJsonArray("seeds") {
                    draw.seeds.forEach { seed ->
                        add(
                            buildJsonObject {
                                put("lever", seed.lever.key)
                                seed.hook?.let { put("hook", it.key) }
                                seed.twist?.let { put("twist", it.text) }
                                seed.register?.let { put("register", it.id) }
                            },
                        )
                    }
                }
                putJsonArray("keys") { draw.keys.forEach { add(it) } }
            },
        )
        val memory = services.memory
        if (caller.role != Role.DIRECTOR || !memory.available || (ideas.isEmpty() && hooks.isEmpty())) return
        val context = RecallContext(caller.conversationId, caller.turnId, eventId = null)
        val done = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            if (ideas.isNotEmpty()) safely("the ideas ${ideas.joinToString()} were dealt but not recorded") { memory.store.dealt(ideas, RecallChannel.INSPIRE, context) }
            if (hooks.isNotEmpty()) safely("the hooks ${hooks.joinToString()} were dealt but not recorded") { memory.store.recorded(hooks, RecallChannel.INSPIRE, context) }
        }
        if (done == null) log.warn("what inspire dealt was not recorded within ${ToolText.duration(WRITE_TIMEOUT_MS)}: ${(ideas + hooks).joinToString()}")
    }

    private suspend fun safely(what: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("$what: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── The card of one idea (M6 §5.2) ──────────────────────────────────────────────────────────────

    /**
     * The whole card of [idea] at [now] ([worldPlaySeconds]: the world's play clock, for its deadline): its pitch, when
     * it fits, its notes, each reference with its line ([refs]: the rows read, null for one that is gone), its module
     * as the arsenal knows it ([module]) and the brief of a forge idea, ready for `forge_module`. [CARD_MAX] at most.
     */
    fun card(idea: Idea, refs: List<Pair<Ref, MemoryRow?>>, module: ArsenalEntry?, now: Instant, worldPlaySeconds: Long): String {
        val millis = now.toEpochMilli()
        val head = buildList {
            add("ok: ${idea.ref} ${idea.kind.id}")
            add(idea.status.id + (idea.closedAt?.let { " ${HybridRecall.ago(millis - it.toEpochMilli())}" }.orEmpty()) +
                (idea.closeReason?.let { ": ${ModelText.escape(it)}" }.orEmpty()))
            add("for ${if (idea.players.isEmpty()) "everyone" else ToolText.names(idea.players)}")
            add(if (idea.dealt == 0) "never dealt" else "dealt ${idea.dealt}×" + (idea.lastDealtAt?.let { " (last ${HybridRecall.ago(millis - it.toEpochMilli())})" }.orEmpty()))
            if (idea.status == IdeaStatus.OPEN) {
                val left = idea.expiresPlaySeconds - worldPlaySeconds
                add(if (left > 0) "expires in ${ToolText.duration(left * 1_000)} of play" else "past its deadline")
            }
        }.joinToString(" · ")
        val lines = buildList {
            add(head)
            add("« ${ModelText.escape(idea.title)} » · novelty ${idea.novelty} fun ${idea.funScore} fair ${idea.fairness}" + (idea.register?.let { " · ${it.id}" }.orEmpty()))
            add("Pitch: ${ModelText.escape(idea.pitch)}")
            add("Fits: ${ModelText.escape(idea.fit)}")
            if (refs.isNotEmpty()) {
                add("Refs:")
                refs.forEach { (ref, row) -> add("- " + (row?.let { refLine(it, millis) } ?: "$ref (gone)")) }
            }
            idea.module?.let { name ->
                val ready = module?.takeIf { it.status == ModuleStatus.READY }
                add("Module: " + (ready?.let(::module) ?: "${ModelText.escape(name)} (not ready in the arsenal now)"))
            }
            idea.brief?.let { add("Brief: ${ModelText.escape(it)}") }
        }
        return ModelText.cut(lines.joinToString("\n"), CARD_MAX)
    }

    /** One line of a row an idea builds on: `M-120 « Mort dans la lave du Nether » (Bartok, Mimosa, 2d ago)`, `C-60 arc → Bartok · active: …`. */
    private fun refLine(row: MemoryRow, now: Long): String = when (row) {
        is MemoryEntry -> "${row.ref} « ${cut(row.title)} » (${ToolText.names(row.players)}, ${HybridRecall.ago(now - row.occurredTo.toEpochMilli())})"
        is CanonEntry -> "${row.ref} ${row.kind.id}" + (if (row.players.isEmpty()) "" else " → ${ToolText.names(row.players)}") +
            " · ${row.status.id}: ${ModelText.cut(ModelText.escape(row.statement), REF_CUT)}"
        is Interaction -> "${row.ref} ${row.player.shownName} ${row.kind.id} ${row.valence}: ${ModelText.cut(ModelText.escape(row.summary), REF_CUT)}"
        is ChronicleEntry -> "${row.ref} ${row.level.id} « ${cut(row.title)} »: ${ModelText.cut(ModelText.escape(row.summary), REF_CUT)}"
        is Note -> "${row.ref} note: ${ModelText.cut(ModelText.escape(row.text), REF_CUT)}"
        is Idea -> "${row.ref} idea ${row.kind.id} « ${cut(row.title)} » (${row.status.id})"
    }

    private const val REF_CUT = 140
}
