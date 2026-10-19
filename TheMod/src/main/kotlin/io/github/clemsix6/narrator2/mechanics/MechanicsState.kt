package io.github.clemsix6.narrator2.mechanics

import com.mojang.serialization.Codec
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier
import java.util.UUID

/*
 * The engine's live state (M4 §3.2): the challenges and duels in progress, the active rules, the pace, the
 * duelists to put back and the bets to settle. It lives in the world's persistent global attachment
 * `narrator2:mechanics`, saved with the world at each save: a crash brings the state and the world back to
 * the same instant. It is read and written on the server thread only (`StateStore`, M4-02), never through
 * the database.
 *
 * It is saved as one JSON text (kotlinx) rather than a DFU codec field by field: the specifications are
 * nested, and they are the very classes of the tools' arguments. Each challenge, rule, return and
 * settlement is decoded on its own: an unreadable entry (a goal removed from the code, a field of the
 * wrong type) is dropped with an error in the log and kept in [MechanicsState.unreadable], never the whole
 * state. Immutable: always replaced by a new instance, never changed in place (like `GuardState`).
 */

/** The engine's live state (M4 §3.2): saved with the world in the global attachment `narrator2:mechanics`. */
@Serializable
data class MechanicsState(
    /** Challenges and duels in progress, invitations included, in the order they started. */
    val challenges: List<ChallengeState> = emptyList(),
    /** Active laws and plans. */
    val rules: List<RuleState> = emptyList(),
    /** When the last challenge started (a duel does not count), epoch milliseconds: the pace (M4 §11). */
    @SerialName("last_challenge_at") val lastChallengeAt: Long? = null,
    /** Duelists to put back where they were at their next join: offline or dead when their duel ended (M4 §5.5). */
    val returns: List<SavedPosition> = emptyList(),
    /** Bets settled while the owner of the stake was offline, applied once they are online (M4 §5.6). */
    val settlements: List<Settlement> = emptyList(),
    /**
     * What could not be read back when the state was loaded: never saved again. The engine reports each one
     * (a decision `mechanic unreadable`) and resolves its canon row `cancelled` at the reconciliation
     * (M4 §4.6), then replaces the state without them.
     */
    @Transient val unreadable: List<Unreadable> = emptyList(),
) {
    /** The challenge or duel of canon row [id], if it is in progress. */
    fun challenge(id: Long): ChallengeState? = challenges.firstOrNull { it.id == id }

    /** The rule of canon row [id], if it is active. */
    fun rule(id: Long): RuleState? = rules.firstOrNull { it.id == id }

    companion object {
        val EMPTY = MechanicsState()

        private val log = Log.mechanics

        /**
         * Reads leniently: unknown properties are ignored (a field removed from the code), absent ones take
         * their default. Writes every value, defaults included: a default changed in a later version never
         * changes the meaning of a saved state.
         */
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /** The state as the JSON text saved in the world. */
        fun encode(state: MechanicsState): String = JSON.encodeToString(serializer(), state)

        /**
         * The state of [text], each entry decoded on its own: what cannot be read is dropped, logged and kept
         * in [unreadable]. Never fails: a text that is not a JSON object at all gives an empty state with one
         * unreadable entry, `state`.
         */
        fun decode(text: String): MechanicsState {
            val raw = try {
                JSON.decodeFromString(Raw.serializer(), text)
            } catch (e: IllegalArgumentException) { // SerializationException too
                val problem = Unreadable("state", null, firstLine(e))
                log.error("the mechanics' state cannot be read, the engine starts empty: ${problem.error}; saved text: ${text.take(RAW_LOG_MAX)}")
                return MechanicsState(unreadable = listOf(problem))
            }
            val unreadable = mutableListOf<Unreadable>()
            return MechanicsState(
                challenges = each(raw.challenges, ChallengeState.serializer(), "challenge", unreadable),
                rules = each(raw.rules, RuleState.serializer(), "rule", unreadable),
                lastChallengeAt = raw.lastChallengeAt,
                returns = each(raw.returns, SavedPosition.serializer(), "return", unreadable),
                settlements = each(raw.settlements, Settlement.serializer(), "settlement", unreadable),
                unreadable = unreadable,
            )
        }

        private fun <T> each(elements: List<JsonElement>, serializer: KSerializer<T>, what: String, unreadable: MutableList<Unreadable>): List<T> =
            elements.mapNotNull { element ->
                try {
                    JSON.decodeFromJsonElement(serializer, element)
                } catch (e: IllegalArgumentException) { // SerializationException too, and the classes' require()
                    val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
                    val problem = Unreadable(what, id, firstLine(e))
                    log.error("a $what of the mechanics' state cannot be read and is dropped${id?.let { " (C-$it)" }.orEmpty()}: ${problem.error}; saved: ${element.toString().take(RAW_LOG_MAX)}")
                    unreadable += problem
                    null
                }
            }

        private fun firstLine(e: Exception): String = e.message?.lineSequence()?.first()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

        /** Longest saved text quoted in the log when it cannot be read. */
        private const val RAW_LOG_MAX = 2_000

        /** One JSON string in the world; each entry decodes alone ([decode]): a bad one is dropped and logged. */
        val CODEC: Codec<MechanicsState> = Codec.STRING.xmap(::decode, ::encode)

        /**
         * The persistent global attachment `narrator2:mechanics` (`server.globalAttachments()`, injected by Fabric
         * API on `MinecraftServer`), saved with the world. Touched by `registerMechanicsHooks`, at mod
         * initialization: a persistent type must exist before the first world loads. Lazy, so that the codec and
         * the state can be used without Fabric's registry (unit tests).
         */
        val TYPE: AttachmentType<MechanicsState> by lazy {
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "mechanics")) { builder -> builder.persistent(CODEC) }
        }
    }

    /** The state as saved, its entries still undecoded: what [decode] reads first. */
    @Serializable
    private class Raw(
        val challenges: List<JsonElement> = emptyList(),
        val rules: List<JsonElement> = emptyList(),
        @SerialName("last_challenge_at") val lastChallengeAt: Long? = null,
        val returns: List<JsonElement> = emptyList(),
        val settlements: List<JsonElement> = emptyList(),
    )
}

/**
 * An entry of the saved state that could not be read back (M4 §3.2).
 *
 * @property what `challenge`, `rule`, `return`, `settlement`, or `state` when nothing could be read.
 * @property id the canon row of a challenge or a rule, when it could be read: its canon is cancelled.
 * @property error why, the first line of the decoder's message.
 */
data class Unreadable(val what: String, val id: Long?, val error: String)

/** Where a challenge or a duel stands (M4 §5); a finished one leaves the state. */
@Serializable
enum class ChallengeStatus {
    /** A duel waiting for both players to accept. */
    @SerialName("invited") INVITED,

    @SerialName("running") RUNNING,

    /** A judged challenge past its end: the Director must name the winners (M4 §5.2). */
    @SerialName("judging") JUDGING,
    ;

    val id: String get() = name.lowercase()
}

/**
 * A challenge or a duel in progress (M4 §5).
 *
 * @property id its canon row: `C-<id>`.
 * @property spec a challenge: what `start_challenge` was given, validated (M4 §5.1).
 * @property duel a duel: what `propose_duel` was given (M4 §5.5). Exactly one of [spec] and [duel] is set.
 * @property createdAt epoch milliseconds, like every time of the state.
 * @property startedAt null while a duel waits for its players.
 * @property endsAt the deadline; for an invited duel, the end of the invitation.
 * @property judgeUntil [ChallengeStatus.JUDGING]: past this, the challenge expires without stakes.
 * @property participants in the order they were entered: those named or online at the start, then the latecomers.
 * @property bets in the order they were placed: the order of the sharing out (M4 §5.6).
 * @property betsCloseAt when `/bet` closes; null without bets.
 * @property arena a duel in an arena: where each duelist was, to put them back.
 * @property turnId the Director's turn that started it; [conversationId] its chapter.
 */
@Serializable
data class ChallengeState(
    val id: Long,
    val spec: ChallengeSpec? = null,
    val duel: DuelSpec? = null,
    val status: ChallengeStatus,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("started_at") val startedAt: Long?,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("judge_until") val judgeUntil: Long? = null,
    val participants: List<Participant>,
    val bets: List<Bet> = emptyList(),
    @SerialName("bets_close_at") val betsCloseAt: Long? = null,
    val arena: List<SavedPosition> = emptyList(),
    @SerialName("turn_id") val turnId: Long?,
    @SerialName("conversation_id") val conversationId: String?,
) {
    init {
        require((spec == null) != (duel == null)) { "C-$id has ${if (spec == null) "neither a challenge nor a duel" else "both a challenge and a duel"}" }
    }

    /** Its canon reference, `C-50`. */
    val ref: Ref get() = Ref(RefKind.CANON, id)

    val isDuel: Boolean get() = duel != null

    /** Its title, on the boss bars and in the situation. */
    val title: String get() = spec?.title ?: duel!!.title

    /** The participant [uuid], if any. */
    fun participant(uuid: UUID): Participant? = participants.firstOrNull { it.uuid == uuid.toString() }
}

/** Why a participant is out of a challenge before its end (M4 §5.2, §5.5). */
@Serializable
enum class OutReason {
    /** `survive`: they died. */
    @SerialName("died") DIED,

    /** `survive` with `no_armor`: they wore armor. */
    @SerialName("armor") ARMOR,

    /** `survive`, or a duel: they left the server. */
    @SerialName("left") LEFT,

    /** A duel: they declined the invitation. */
    @SerialName("declined") DECLINED,
    ;

    val id: String get() = name.lowercase()
}

/**
 * One player of a challenge or a duel.
 *
 * @property uuid the player's UUID, as text.
 * @property name their name when they were entered.
 * @property progress obtain: the most held at once so far; kill: kills; reach: 1 once there; survive and answer: 0.
 * @property doneAt when they reached the goal, epoch milliseconds: the order of the winners.
 * @property out why they are out before the end.
 * @property accepted a duelist who accepted.
 * @property late entered after the start (a challenge open to everyone online): they may win, never lose,
 *   so the penalty spares them (M4 §5.2, decision H).
 */
@Serializable
data class Participant(
    val uuid: String,
    val name: String,
    val progress: Int = 0,
    @SerialName("done_at") val doneAt: Long? = null,
    val out: OutReason? = null,
    val accepted: Boolean = false,
    val late: Boolean = false,
) {
    val playerId: UUID get() = UUID.fromString(uuid)
}

/**
 * A bet (M4 §5.6).
 *
 * @property uuid and [name]: who bet.
 * @property on the participant bet on (UUID as text), or null for "nobody wins".
 * @property stake the id of its escrow in the bettor's ledger (a `Confiscation` of purpose `stake`).
 * @property what the stake, readable: `12x iron_ingot`.
 */
@Serializable
data class Bet(val uuid: String, val name: String, val on: String?, val stake: Long, val what: String)

/**
 * Where a player was, to put them back (a duel in an arena, M4 §5.5). [uuid]: the player, as text. [savedAt]: when,
 * epoch milliseconds; a return too old is dropped (M4 review, finding 21), one without it is put back.
 */
@Serializable
data class SavedPosition(
    val uuid: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    @SerialName("saved_at") val savedAt: Long? = null,
)

/**
 * What to do with [owner]'s stake [stake] (the id of its escrow) once they are online (M4 §5.6): give it
 * back ([to] null), or cede it [to] a winner (UUID as text). Applying it twice does nothing more: the
 * ledger knows the stake by its id.
 */
@Serializable
data class Settlement(val owner: String, val stake: Long, val to: String? = null)

/**
 * An active law or plan (M4 §8).
 *
 * @property id its canon row (`law` or `plan`): `C-<id>`.
 * @property spec validated when it was created (M4 §8.5).
 * @property playerIds the UUIDs (as text) of `when.players`, resolved from the names when it was created: a
 *   player who changes their name stays aimed at.
 * @property fired firings with at least one effect done.
 * @property skipped firings throttled, or with every effect refused or skipped.
 * @property failures consecutive firings that failed (M4 §8.6): [MechanicsLimits.RULE_FAILURES_TO_DISABLE]
 *   disable the rule.
 */
@Serializable
data class RuleState(
    val id: Long,
    val spec: RuleSpec,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("turn_id") val turnId: Long?,
    @SerialName("conversation_id") val conversationId: String?,
    @SerialName("player_ids") val playerIds: List<String> = emptyList(),
    val fired: Int = 0,
    val skipped: Int = 0,
    val failures: Int = 0,
    @SerialName("last_fired_at") val lastFiredAt: Long? = null,
) {
    /** Its canon reference, `C-60`. */
    val ref: Ref get() = Ref(RefKind.CANON, id)
}
