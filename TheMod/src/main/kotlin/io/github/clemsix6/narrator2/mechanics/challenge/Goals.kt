package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.OutReason
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.PlaceSpec
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.oracle.Structures
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.action.shortId
import io.github.clemsix6.narrator2.tool.speech.closestIds
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.floor

/** How a challenge or a duel ends (M4 §5.2): its `mechanic` decision and its canon status. */
enum class Ending(val status: CanonStatus) {
    /** Its winners get the reward, the other participants enrolled at the start the penalty. */
    WON(CanonStatus.WON),

    /** Nobody won: every participant enrolled at the start gets the penalty. */
    LOST(CanonStatus.LOST),

    /** A judged challenge the Director did not judge in time: no stakes, the bets go back. */
    EXPIRED(CanonStatus.EXPIRED),

    /** No stakes, the bets go back. */
    CANCELLED(CanonStatus.CANCELLED),
    ;

    /** `won`: the name of its `mechanic` decision (M4 §19), which the Chronicler reads. */
    val id: String get() = name.lowercase()

    companion object {
        /** The ending an early end asks for (`end_challenge`, `/narrator challenge cancel`). */
        fun of(outcome: ChallengeOutcome): Ending = when (outcome) {
            ChallengeOutcome.WON -> WON
            ChallengeOutcome.LOST -> LOST
            ChallengeOutcome.CANCELLED -> CANCELLED
        }
    }
}

/** What the goal of a challenge decides at a moment (M4 §5.2), [Goals.verdict]. */
sealed interface Verdict {
    /** It ends [ending], [winners] in the order they made it. */
    data class Ends(val ending: Ending, val winners: List<Participant>) : Verdict

    /** A judged challenge reached its end: the Director names the winners now (`JUDGING`, a `JUDGE` event). */
    data object Judge : Verdict
}

/**
 * Where a player stands, for a `reach` goal: [dimension] its full id, [biome] and [structure] the full ids
 * of where they are (null when unknown or outside any).
 */
data class Spot(val dimension: String, val x: Double, val y: Double, val z: Double, val biome: String?, val structure: String?)

/**
 * What a challenge's goal looks for, resolved against the game's registries when it starts (with the
 * closest ids when the model wrote one that does not exist), then again from the state after a restart.
 */
sealed interface Target {
    /** `obtain`: the stacks of the item or of the tag. */
    class Items(val matches: (ItemStack) -> Boolean) : Target

    /** `kill`: the creatures of a type or of a tag, by their full type id (`minecraft:spider`). */
    class Creatures(val matches: (String) -> Boolean) : Target

    /**
     * `reach`: every field given holds at once. [dimension] a full id; the point ([x], [y], [z]) is in
     * [pointDimension], within [radius] blocks (horizontally when [y] is null); [biomes] and [structures] the
     * full ids the place names (one, or a tag's).
     */
    data class Place(
        val dimension: String?,
        val x: Int?,
        val y: Int?,
        val z: Int?,
        val radius: Int,
        val pointDimension: String,
        val yAbove: Int?,
        val yBelow: Int?,
        val biomes: Set<String>?,
        val structures: Set<String>?,
    ) : Target {
        /** Whether a player at [spot] is there. Pure. */
        fun holds(spot: Spot): Boolean {
            if (dimension != null && spot.dimension != dimension) return false
            val blockY = floor(spot.y).toInt()
            if (yAbove != null && blockY < yAbove) return false
            if (yBelow != null && blockY > yBelow) return false
            if (x != null && z != null) {
                if (spot.dimension != pointDimension) return false
                val dx = spot.x - (x + 0.5)
                val dz = spot.z - (z + 0.5)
                val dy = if (y != null) spot.y - y else 0.0
                if (dx * dx + dy * dy + dz * dz > radius.toDouble() * radius) return false
            }
            if (biomes != null && spot.biome !in biomes) return false
            if (structures != null && spot.structure !in structures) return false
            return true
        }
    }

    /** `survive`, `answer`, `judged`: nothing in the registries. */
    data object Nothing : Target
}

/**
 * The goals of the challenges (M4 §5.2): what each requires, what it looks for in the game, how a
 * participant progresses, and when a challenge ends ([verdict]).
 *
 * The progress functions ([held], [killed], [reached], [answered], [out], [joined]) and [verdict] are pure,
 * on the state's own classes: they return a new [ChallengeState], or the same instance when nothing
 * changes. A participant who made it keeps it ([Participant.doneAt]); one who is out ([Participant.out])
 * never progresses again. Two participants who make it at the same moment are ordered by when they were
 * seen: each new [Participant.doneAt] is at least a millisecond after the last one.
 */
object Goals {
    private const val OVERWORLD = "minecraft:overworld"

    // ── What a goal requires (M4 §5.1, §5.2) ──

    /**
     * The problem of [spec] that needs no world: a field its goal requires and lacks, a place without any
     * field or with a half point, answers that have no word. Null when it holds. Pure.
     */
    fun shape(spec: ChallengeSpec): String? = when (spec.goal) {
        GoalKind.OBTAIN -> if (spec.item.isNullOrBlank()) "goal obtain needs item: the item id or #tag to hold, with count" else null
        GoalKind.KILL -> when {
            spec.entity.isNullOrBlank() -> "goal kill needs entity: a creature id or #tag, with count"
            isPlayer(spec.entity) -> KILL_PLAYERS
            else -> null
        }
        GoalKind.REACH -> placeShape(spec.place)
        GoalKind.ANSWER -> {
            val answers = spec.answers
            val empty = answers?.let(Answers::firstEmpty)
            when {
                answers.isNullOrEmpty() -> "goal answer needs answers: the accepted answers"
                empty != null -> "answers[${empty.index}] has no letter nor digit: write the answer itself"
                else -> null
            }
        }
        GoalKind.SURVIVE, GoalKind.JUDGED -> null
    }

    /** The answer of a `kill` goal on players: a fight between players is a duel, which both must accept. */
    const val KILL_PLAYERS = "kill cannot target players: a fight between players is a duel (propose_duel)"

    private fun placeShape(place: PlaceSpec?): String? = when {
        place == null || place.isEmpty ->
            "goal reach needs place: a structure, a biome, a dimension, a point (x and z, y if you like) or a height (y_above, y_below)"
        (place.x == null) != (place.z == null) || (place.y != null && place.x == null) -> "place: a point needs x and z (y is optional)"
        place.yAbove != null && place.yBelow != null && place.yAbove > place.yBelow ->
            "place: y_above ${place.yAbove} is above y_below ${place.yBelow}: nobody could stand between"
        else -> null
    }

    private fun isPlayer(raw: String): Boolean = parseId(raw.trim())?.let { it == BuiltInRegistries.ENTITY_TYPE.getKey(EntityTypes.PLAYER) } == true

    /**
     * What [spec]'s goal looks for in [server]'s registries; a failure carries the message for the model
     * (unknown id with the closest ones, unknown dimension). [shape] must hold. Server thread (the structures
     * and the biomes are registries of the world).
     */
    fun target(spec: ChallengeSpec, server: MinecraftServer): Result<Target> = when (spec.goal) {
        GoalKind.OBTAIN -> items(spec.item!!)
        GoalKind.KILL -> creatures(spec.entity!!)
        GoalKind.REACH -> place(spec.place!!, server)
        else -> Result.success(Target.Nothing)
    }

    /** An `obtain` target: the item or the item tag [raw] names (`#logs`). Any thread. */
    internal fun items(raw: String): Result<Target> {
        val name = raw.trim()
        return if (name.startsWith("#")) {
            val tag = ItemSpecs.itemTag(name) ?: return failure(ItemSpecs.unknownTag(name))
            Result.success(Target.Items { it.`is`(tag) })
        } else {
            val item = ItemSpecs.item(name) ?: return failure(ItemSpecs.unknownItem(name))
            Result.success(Target.Items { it.`is`(item) })
        }
    }

    /** A `kill` target: the creature or the creature tag [raw] names (`#skeletons`). Any thread. */
    internal fun creatures(raw: String): Result<Target> {
        val name = raw.trim()
        val registry = BuiltInRegistries.ENTITY_TYPE
        if (name.startsWith("#")) {
            val id = parseId(name.removePrefix("#"))
            val tag = id?.let { TagKey.create(Registries.ENTITY_TYPE, it) }?.takeIf { registry.get(it).isPresent }
                ?: return failure(
                    "unknown creature tag \"$name\"; closest tags: " +
                        closestIds(name.removePrefix("#"), registry.getTags().map { "#" + shortId(it.key().location()) }.toList()).joinToString(),
                )
            return Result.success(Target.Creatures { type -> Identifier.tryParse(type)?.let { registry.get(it).orElse(null) }?.`is`(tag) == true })
        }
        val id = parseId(name)?.takeIf { registry.get(it).isPresent }
            ?: return failure("unknown creature \"$name\"; closest ids: ${closestIds(name, registry.keySet().map(::shortId)).joinToString()}")
        val full = id.toString()
        return Result.success(Target.Creatures { it == full })
    }

    private fun place(place: PlaceSpec, server: MinecraftServer): Result<Target> {
        val dimension = place.dimension?.let { raw ->
            dimensionId(server, raw) ?: return failure(
                "place: unknown dimension \"$raw\"; dimensions: ${server.levelKeys().joinToString { ToolText.dimension(it.identifier().toString()) }}",
            )
        }
        val pointDimension = dimension ?: OVERWORLD
        val level = server.overworld()
        val biomes = place.biome?.let { raw ->
            Structures.biomeTarget(level, raw).getOrElse { return failure("place: ${it.message}") }.holders.map { it.registeredName }.toSet()
        }
        val structures = place.structure?.let { raw ->
            Structures.structureTarget(level, raw).getOrElse { return failure("place: ${it.message}") }.holders.map { it.registeredName }.toSet()
        }
        return Result.success(Target.Place(dimension, place.x, place.y, place.z, place.radius, pointDimension, place.yAbove, place.yBelow, biomes, structures))
    }

    /** The full id of the dimension [raw] names (`the_nether`, `nether`, `minecraft:the_end`), when the server has it. */
    private fun dimensionId(server: MinecraftServer, raw: String): String? {
        val id = Identifier.tryParse(ToolText.dimensionId(raw)) ?: return null
        return id.toString().takeIf { server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) != null }
    }

    private fun failure(message: String): Result<Target> = Result.failure(IllegalArgumentException(message))

    // ── What a goal sees in the game (server thread) ──

    /** How many of [player]'s items [items] matches, slot by slot (the 36 slots, then the armor and the off hand, M4 §5.2). */
    fun count(player: ServerPlayer, items: Target.Items): Int {
        val inventory = player.inventory
        var total = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && items.matches(stack)) total += stack.count
        }
        return total
    }

    /** Whether [player] wears anything in an armor slot (head, chest, legs, feet): an elytra or a pumpkin counts. */
    fun wearsArmor(player: ServerPlayer): Boolean = ARMOR.any { !player.getItemBySlot(it).isEmpty }

    private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /** Where [player] stands, for a `reach` goal; [structure] the one `PlayerStates` saw last (every 5 s and in each new chunk). */
    fun spot(player: ServerPlayer, structure: String?): Spot {
        val level = player.level()
        return Spot(
            dimension = level.dimension().identifier().toString(),
            x = player.x,
            y = player.y,
            z = player.z,
            biome = level.getBiome(player.blockPosition()).registeredName,
            structure = structure,
        )
    }

    // ── How participants progress (pure) ──

    /** `obtain`: [uuid] holds [count] at once at [now]; done once it reaches the goal's count. */
    fun held(challenge: ChallengeState, uuid: UUID, count: Int, now: Long): ChallengeState {
        val need = challenge.spec?.count ?: return challenge
        return change(challenge, uuid) { p ->
            val progress = maxOf(p.progress, count)
            p.copy(progress = progress, doneAt = p.doneAt ?: if (progress >= need) doneAt(challenge, now) else null)
        }
    }

    /** `kill`: [uuid] killed one more creature of the goal at [now]. */
    fun killed(challenge: ChallengeState, uuid: UUID, now: Long): ChallengeState {
        val need = challenge.spec?.count ?: return challenge
        return change(challenge, uuid) { p ->
            if (p.doneAt != null) return@change p
            val progress = p.progress + 1
            p.copy(progress = progress, doneAt = if (progress >= need) doneAt(challenge, now) else null)
        }
    }

    /** `reach`: [uuid] is there at [now]. */
    fun reached(challenge: ChallengeState, uuid: UUID, now: Long): ChallengeState =
        change(challenge, uuid) { p -> if (p.doneAt != null) p else p.copy(progress = 1, doneAt = doneAt(challenge, now)) }

    /** `answer`: [uuid] gave an accepted answer at [now]. */
    fun answered(challenge: ChallengeState, uuid: UUID, now: Long): ChallengeState =
        change(challenge, uuid) { p -> if (p.doneAt != null) p else p.copy(doneAt = doneAt(challenge, now)) }

    /** `survive`: [uuid] is out for [reason] (died, wore armor, left). */
    fun out(challenge: ChallengeState, uuid: UUID, reason: OutReason): ChallengeState = change(challenge, uuid) { p -> p.copy(out = reason) }

    /**
     * [uuid] ([name]) joined the server: a new participant of a challenge open to everyone ([ChallengeSpec.players]
     * left out), entered [Participant.late] (they may win, never lose); the same challenge otherwise, a
     * participant already in included (one who left a `survive` stays out).
     */
    fun joined(challenge: ChallengeState, uuid: UUID, name: String): ChallengeState {
        val spec = challenge.spec ?: return challenge
        if (spec.players != null || challenge.status != ChallengeStatus.RUNNING || challenge.participant(uuid) != null) return challenge
        return challenge.copy(participants = challenge.participants + Participant(uuid.toString(), name, late = true))
    }

    /** [challenge] with participant [uuid] changed by [change], when they are in and not out. */
    private inline fun change(challenge: ChallengeState, uuid: UUID, change: (Participant) -> Participant): ChallengeState {
        if (challenge.status != ChallengeStatus.RUNNING) return challenge
        val index = challenge.participants.indexOfFirst { it.uuid == uuid.toString() }
        if (index < 0) return challenge
        val before = challenge.participants[index]
        if (before.out != null) return challenge
        val after = change(before)
        if (after == before) return challenge
        return challenge.copy(participants = challenge.participants.toMutableList().also { it[index] = after })
    }

    /** When a participant who makes it at [now] made it: a millisecond after the last one at least, so that the order is kept. */
    private fun doneAt(challenge: ChallengeState, now: Long): Long = maxOf(now, (challenge.participants.mapNotNull { it.doneAt }.maxOrNull() ?: Long.MIN_VALUE) + 1)

    // ── When it ends (pure) ──

    /**
     * What [challenge]'s goal decides at [now] (M4 §5.2); null while it goes on, and for a duel (M4-19).
     *
     * - `judged`: at its end, [Verdict.Judge]; once judging, past [ChallengeState.judgeUntil], expired.
     * - `survive`: lost as soon as nobody is still in; at the end, won by everyone still in ([ChallengeSpec.winners]
     *   is ignored).
     * - the others: won as soon as [ChallengeSpec.winners] made it (1: the first; N: the first N), or when every
     *   participant of a closed list (named players) made it; at the end, won by those who made it, lost when
     *   nobody did. With winners 0, everyone who makes it before the end wins.
     */
    fun verdict(challenge: ChallengeState, now: Long): Verdict? {
        val spec = challenge.spec ?: return null
        return when (challenge.status) {
            ChallengeStatus.INVITED -> null
            ChallengeStatus.JUDGING -> if (now >= (challenge.judgeUntil ?: challenge.endsAt)) Verdict.Ends(Ending.EXPIRED, emptyList()) else null
            ChallengeStatus.RUNNING -> when (spec.goal) {
                GoalKind.JUDGED -> if (now >= challenge.endsAt) Verdict.Judge else null
                GoalKind.SURVIVE -> {
                    val alive = challenge.participants.filter { it.out == null }
                    when {
                        alive.isEmpty() -> Verdict.Ends(Ending.LOST, emptyList())
                        now >= challenge.endsAt -> Verdict.Ends(Ending.WON, alive)
                        else -> null
                    }
                }
                else -> {
                    val done = challenge.participants.filter { it.doneAt != null }.sortedBy { it.doneAt }
                    val closed = spec.players != null && challenge.participants.all { it.doneAt != null || it.out != null }
                    when {
                        spec.winners > 0 && done.size >= spec.winners -> Verdict.Ends(Ending.WON, done.take(spec.winners))
                        closed || now >= challenge.endsAt -> if (done.isEmpty()) Verdict.Ends(Ending.LOST, emptyList()) else Verdict.Ends(Ending.WON, done)
                        else -> null
                    }
                }
            }
        }
    }

    // ── How a goal reads (English, for the model) ──

    /** [raw] as the model reads an id: trimmed, lower case, without `minecraft:` (`turtle_egg`, `#logs`). */
    fun label(raw: String): String {
        val name = raw.trim().lowercase()
        return if (name.startsWith("#")) "#" + name.removePrefix("#").removePrefix("minecraft:") else name.removePrefix("minecraft:")
    }

    /** What [spec] asks, in a few words: `obtain 1 turtle_egg`, `reach the_nether`, `kill 10 spider`, `survive without armor`, `answer`, `judged`. */
    fun describe(spec: ChallengeSpec): String = when (spec.goal) {
        GoalKind.OBTAIN -> "obtain ${spec.count} ${label(spec.item.orEmpty())}"
        GoalKind.REACH -> "reach ${spec.place?.let(::describe) ?: "?"}"
        GoalKind.KILL -> "kill ${spec.count} ${label(spec.entity.orEmpty())}"
        GoalKind.SURVIVE -> if (spec.noArmor) "survive without armor" else "survive"
        GoalKind.ANSWER -> "answer"
        GoalKind.JUDGED -> "judged"
    }

    /** Where [place] leads: `trial_chambers in the_nether`, `x 12 y 64 z -40 (within 8)`, `y 120 or higher`. */
    fun describe(place: PlaceSpec): String {
        val parts = listOfNotNull(
            place.structure?.let(::label),
            place.biome?.let(::label),
            if (place.x != null && place.z != null) {
                (if (place.y != null) "x ${place.x} y ${place.y} z ${place.z}" else "x ${place.x} z ${place.z}") + " (within ${place.radius})"
            } else {
                null
            },
            place.yAbove?.let { "y $it or higher" },
            place.yBelow?.let { "y $it or lower" },
        )
        val dimension = place.dimension?.let { ToolText.dimension(ToolText.dimensionId(it)) }
        return when {
            parts.isEmpty() -> dimension ?: "?"
            dimension == null -> parts.joinToString(", ")
            else -> parts.joinToString(", ") + " in $dimension"
        }
    }

    /** A participant's progress toward a goal that counts: `turtle_egg 1/1`; null for the other goals. */
    fun measure(spec: ChallengeSpec, participant: Participant): String? = when (spec.goal) {
        GoalKind.OBTAIN -> "${label(spec.item.orEmpty())} ${participant.progress.coerceAtMost(spec.count)}/${spec.count}"
        GoalKind.KILL -> "${label(spec.entity.orEmpty())} ${participant.progress.coerceAtMost(spec.count)}/${spec.count}"
        else -> null
    }
}
