package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.core.LongString
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * The guards' state of a player (M1_M2 §10.2): what the Narrator did to them that must be undone, and
 * the death guard's marks. It lives in a persistent player attachment, written in the player's file at
 * the same save as their inventory: a crash can never leave an inventory emptied without the trace of
 * its confiscation, nor a protected death without its mark.
 *
 * Ids of the game (attributes, effects, operations) are kept as plain strings, and stacks as SNBT, so
 * that decoding the state never depends on the game's registries: an item removed from the game must
 * not make a whole ledger unreadable. A stack's SNBT has no length limit in the file ([LongString]: a full
 * book, a shulker box of books), and the game's data version goes with it, so that a stack held across an
 * update of the game is brought up to date before it is given back.
 */

/**
 * One attribute modifier of the Narrator, reapplied after a death or a reconnection and removed when
 * due, even after a restart.
 *
 * @property attribute the [AttributeKey.id], e.g. `scale`.
 * @property operation the serialized name of an `AttributeModifier.Operation`: `add_value`,
 *   `add_multiplied_base` or `add_multiplied_total`.
 * @property expiresAt epoch milliseconds (real time: the player reads "5 minutes").
 * @property harmful part of a punishment (a hostile action).
 */
data class LedgerModifier(
    val attribute: String,
    val amount: Double,
    val operation: String,
    val expiresAt: Long,
    val harmful: Boolean,
    val turnId: Long?,
) {
    companion object {
        val CODEC: Codec<LedgerModifier> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("attribute").forGetter(LedgerModifier::attribute),
                Codec.DOUBLE.fieldOf("amount").forGetter(LedgerModifier::amount),
                Codec.STRING.fieldOf("operation").forGetter(LedgerModifier::operation),
                Codec.LONG.fieldOf("expires_at").forGetter(LedgerModifier::expiresAt),
                Codec.BOOL.fieldOf("harmful").forGetter(LedgerModifier::harmful),
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.ofNullable(it.turnId) },
            ).apply(instance) { attribute, amount, operation, expiresAt, harmful, turnId ->
                LedgerModifier(attribute, amount, operation, expiresAt, harmful, turnId.orElse(null))
            }
        }
    }
}

/**
 * One effect the Narrator put on a player. The effect itself is saved by the game with the player and
 * lost at death; this entry lets the engine tell the Narrator's effects from the others.
 *
 * @property effect registry id, e.g. `minecraft:slowness`.
 * @property level 1 for the effect's first level.
 * @property expiresAt epoch milliseconds when it was put on (the effect itself counts game ticks).
 */
data class LedgerEffect(val effect: String, val level: Int, val expiresAt: Long, val harmful: Boolean, val turnId: Long?) {
    companion object {
        val CODEC: Codec<LedgerEffect> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("effect").forGetter(LedgerEffect::effect),
                Codec.INT.fieldOf("level").forGetter(LedgerEffect::level),
                Codec.LONG.fieldOf("expires_at").forGetter(LedgerEffect::expiresAt),
                Codec.BOOL.fieldOf("harmful").forGetter(LedgerEffect::harmful),
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.ofNullable(it.turnId) },
            ).apply(instance) { effect, level, expiresAt, harmful, turnId -> LedgerEffect(effect, level, expiresAt, harmful, turnId.orElse(null)) }
        }
    }
}

/**
 * Why the ledger holds items of a player (M4 §5.6, §7.2). Only [CONFISCATION] is a punishment: the others are
 * shown apart by `activeOn`, never harmful, and a pardon gives back confiscations only.
 */
enum class LedgerPurpose {
    /** The Narrator's punishment (`take_item`): given back at its due time. */
    CONFISCATION,

    /** A player's bet on a challenge (`/bet`): settled when it ends, given back at its fallback deadline otherwise. */
    STAKE,

    /** A player's offering (`/offer`): the Narrator's for good only with a favor granted for it, given back otherwise. */
    OFFERING,

    /** What the player is owed (winnings, a joker) and their inventory had no room for: given as soon as there is room. */
    OWED,
    ;

    /** `stake`: its value in the player's file. */
    val id: String get() = name.lowercase()

    companion object {
        /**
         * The purpose saved as [id]. An unknown one (a purpose a later version added, then removed) reads as a
         * [CONFISCATION]: its items still go back to their owner at their due time, the safe way.
         */
        fun of(id: String): LedgerPurpose = entries.firstOrNull { it.id == id } ?: CONFISCATION
    }
}

/**
 * Items the ledger holds for a player, given back from [returnAt] on: a confiscation of the Narrator, or,
 * from M4, a stake, an offering or what is owed ([purpose]).
 *
 * @property id the id of its `ledger confiscated` decision (`ledger held` for a stake or an offering:
 *   an offering is `O-<id>`).
 * @property items the stacks still held, each an `ItemStack` in SNBT, of any length (a long one is saved in
 *   chunks, [LongString]). A stack that no longer reads back (its item left the game) stays here: nothing is
 *   ever erased silently.
 * @property what the stacks still held, for people and the model: `6x minecraft:diamond`.
 * @property takenAt and [returnAt]: epoch milliseconds. A stake's [returnAt] is its fallback deadline, never
 *   `Long.MAX_VALUE`: nothing stays held for ever (M4 §5.6).
 * @property canon a stake: the challenge or duel bet on, `C-<canon>`.
 * @property owedTo a stake lost: the player it goes to, as soon as both are online.
 * @property dataVersion the game's data version when [items] were written: older stacks are brought up to date by
 *   the game's data fixer before they are given back. Null: written without it, read as they are.
 * @property settledAt a stake: when its settlement came (given back, or ceded to a winner), epoch milliseconds.
 *   Null while it waits for one: a stake that comes back then comes back at its fallback deadline.
 */
data class Confiscation(
    val id: Long,
    val items: List<String>,
    val what: String,
    val takenAt: Long,
    val returnAt: Long,
    val reason: String?,
    val turnId: Long?,
    val purpose: LedgerPurpose = LedgerPurpose.CONFISCATION,
    val canon: Long? = null,
    val owedTo: UUID? = null,
    val dataVersion: Int? = null,
    val settledAt: Long? = null,
) {
    companion object {
        val CODEC: Codec<Confiscation> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("id").forGetter(Confiscation::id),
                LongString.CODEC.listOf().fieldOf("items").forGetter(Confiscation::items),
                Codec.STRING.fieldOf("what").forGetter(Confiscation::what),
                Codec.LONG.fieldOf("taken_at").forGetter(Confiscation::takenAt),
                Codec.LONG.fieldOf("return_at").forGetter(Confiscation::returnAt),
                Codec.STRING.optionalFieldOf("reason").forGetter { Optional.ofNullable(it.reason) },
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.ofNullable(it.turnId) },
                Codec.STRING.xmap({ LedgerPurpose.of(it) }, { it.id })
                    .optionalFieldOf("purpose", LedgerPurpose.CONFISCATION).forGetter(Confiscation::purpose),
                Codec.LONG.optionalFieldOf("canon").forGetter { Optional.ofNullable(it.canon) },
                UUIDUtil.CODEC.optionalFieldOf("owed_to").forGetter { Optional.ofNullable(it.owedTo) },
                Codec.INT.optionalFieldOf("data_version").forGetter { Optional.ofNullable(it.dataVersion) },
                Codec.LONG.optionalFieldOf("settled_at").forGetter { Optional.ofNullable(it.settledAt) },
            ).apply(instance) { id, items, what, takenAt, returnAt, reason, turnId, purpose, canon, owedTo, dataVersion, settledAt ->
                Confiscation(
                    id, items, what, takenAt, returnAt, reason.orElse(null), turnId.orElse(null), purpose, canon.orElse(null), owedTo.orElse(null),
                    dataVersion.orElse(null), settledAt.orElse(null),
                )
            }
        }
    }
}

/**
 * A stake handed over to the player who won it (M4 §5.6): [stake] the id of its escrow, which its winnings keep, and
 * [at] when, epoch milliseconds. Kept [MechanicsLimits.CEDED_RESCUE_DAYS] days, as long as the loser's file may still
 * hold the stake: a hand-over replayed after a crash finds it here and gives nothing twice, even once the winnings
 * were delivered.
 */
data class ReceivedStake(val stake: Long, val at: Long) {
    companion object {
        val CODEC: Codec<ReceivedStake> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("stake").forGetter(ReceivedStake::stake),
                Codec.LONG.fieldOf("at").forGetter(ReceivedStake::at),
            ).apply(instance, ::ReceivedStake)
        }
    }
}

/**
 * The last punishment opened against a player, kept so that `Episodes` can be rebuilt when they join
 * after a restart.
 *
 * @property act the act that opened it (`Caller.act`: a Director turn, or minus an engine firing, M4 §4.3);
 *   read from `turn_id` in a state saved before M4.
 * @property until epoch milliseconds.
 */
data class OpenEpisode(val act: Long?, val until: Long) {
    companion object {
        val CODEC: Codec<OpenEpisode> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.optionalFieldOf("act").forGetter { Optional.ofNullable(it.act) },
                // Written before M4, when an episode was a turn's: read, never written again.
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.empty() },
                Codec.LONG.fieldOf("until").forGetter(OpenEpisode::until),
            ).apply(instance) { act, turnId, until -> OpenEpisode(act.or { turnId }.orElse(null), until) }
        }
    }
}

/**
 * The guards' state of one player. Immutable: always replaced by a new instance (`setAttached`), never
 * changed in place.
 *
 * @property armedUntil the death guard is armed, for some [perils], until this value of the player's
 *   `PLAY_TIME` stat (ticks played): it counts the same ticks as the effects it covers, and pauses while
 *   they are away.
 * @property protectedDeath the player died under the death guard and keeps their inventory: set when
 *   the death is allowed, cleared when they respawn. Read by the keep-inventory mixins.
 * @property narratorKills the player's `PLAY_TIME` at each death the Narrator caused within the last
 *   played hour.
 * @property lastDeathAt when the player last died, whatever killed them, epoch milliseconds (real time,
 *   like the no-piling-on rule); forgotten once that rule no longer applies. Saved with the player, so a
 *   player who dies, leaves and comes back within the 5 minutes is still spared.
 * @property perils what the death guard covers: for each [Peril] id, until which value of the `PLAY_TIME`
 *   stat; [armedUntil] is the latest of them.
 * @property rescues when the death guard rescued the player beyond the quota, epoch milliseconds (real
 *   time), within the last [DeathGuard.RESCUE_WINDOW_MS]: too many is a warning for the operators.
 * @property graceUntil the end of the Narrator's grace on the player (M4 §7.1), epoch milliseconds.
 * @property lastGraceEnd when their last grace ended (or will end): a new one comes an hour after it at the earliest.
 * @property jokersGivenAt when the Narrator gave them a joker within the last 24 hours, epoch milliseconds.
 * @property lastOfferAt when they last made an offering (`/offer`), epoch milliseconds: one per
 *   `mechanics.offeringMinutes` (M4 §7.2).
 * @property receivedStakes the stakes lately handed over to them, won from another player ([ReceivedStake]).
 * @property shield the Narrator leaves them alone until it ends: they were saved from a death ([Shields], M4 review,
 *   finding 18). Saved with them, so that it holds across a restart, like [lastDeathAt].
 */
data class GuardState(
    val modifiers: List<LedgerModifier> = emptyList(),
    val effects: List<LedgerEffect> = emptyList(),
    val confiscations: List<Confiscation> = emptyList(),
    val episode: OpenEpisode? = null,
    val armedUntil: Long = 0,
    val protectedDeath: Boolean = false,
    val narratorKills: List<Long> = emptyList(),
    val lastDeathAt: Long? = null,
    val perils: Map<String, Long> = emptyMap(),
    val rescues: List<Long> = emptyList(),
    val graceUntil: Long? = null,
    val lastGraceEnd: Long? = null,
    val jokersGivenAt: List<Long> = emptyList(),
    val lastOfferAt: Long? = null,
    val receivedStakes: List<ReceivedStake> = emptyList(),
    val shield: Shield? = null,
) {
    companion object {
        val EMPTY = GuardState()

        /**
         * Every field is optional, so a state saved before a field existed still reads back. 16 fields, all that a
         * record codec takes: a new one goes into a nested record.
         */
        val CODEC: Codec<GuardState> = RecordCodecBuilder.create { instance ->
            instance.group(
                LedgerModifier.CODEC.listOf().optionalFieldOf("modifiers", emptyList()).forGetter(GuardState::modifiers),
                LedgerEffect.CODEC.listOf().optionalFieldOf("effects", emptyList()).forGetter(GuardState::effects),
                Confiscation.CODEC.listOf().optionalFieldOf("confiscations", emptyList()).forGetter(GuardState::confiscations),
                OpenEpisode.CODEC.optionalFieldOf("episode").forGetter { Optional.ofNullable(it.episode) },
                Codec.LONG.optionalFieldOf("armed_until", 0L).forGetter(GuardState::armedUntil),
                Codec.BOOL.optionalFieldOf("protected_death", false).forGetter(GuardState::protectedDeath),
                Codec.LONG.listOf().optionalFieldOf("narrator_kills", emptyList()).forGetter(GuardState::narratorKills),
                Codec.LONG.optionalFieldOf("last_death_at").forGetter { Optional.ofNullable(it.lastDeathAt) },
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("perils", emptyMap()).forGetter(GuardState::perils),
                Codec.LONG.listOf().optionalFieldOf("rescues", emptyList()).forGetter(GuardState::rescues),
                Codec.LONG.optionalFieldOf("grace_until").forGetter { Optional.ofNullable(it.graceUntil) },
                Codec.LONG.optionalFieldOf("last_grace_end").forGetter { Optional.ofNullable(it.lastGraceEnd) },
                Codec.LONG.listOf().optionalFieldOf("jokers_given_at", emptyList()).forGetter(GuardState::jokersGivenAt),
                Codec.LONG.optionalFieldOf("last_offer_at").forGetter { Optional.ofNullable(it.lastOfferAt) },
                ReceivedStake.CODEC.listOf().optionalFieldOf("received_stakes", emptyList()).forGetter(GuardState::receivedStakes),
                Shield.CODEC.optionalFieldOf("shield").forGetter { Optional.ofNullable(it.shield) },
            ).apply(instance) { modifiers, effects, confiscations, episode, armedUntil, protectedDeath, narratorKills, lastDeathAt, perils, rescues,
                                graceUntil, lastGraceEnd, jokersGivenAt, lastOfferAt, receivedStakes, shield ->
                GuardState(
                    modifiers, effects, confiscations, episode.orElse(null), armedUntil, protectedDeath, narratorKills, lastDeathAt.orElse(null),
                    perils, rescues, graceUntil.orElse(null), lastGraceEnd.orElse(null), jokersGivenAt, lastOfferAt.orElse(null), receivedStakes,
                    shield.orElse(null),
                )
            }
        }

        /**
         * The persistent player attachment `narrator2:guard_state`, copied to the new player object after
         * a death. Registered by `registerGuardHooks`, before any world loads. Lazy, so that the codec can be
         * used without Fabric's registry (unit tests).
         */
        val TYPE: AttachmentType<GuardState> by lazy {
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "guard_state")) { builder ->
                builder.persistent(CODEC).copyOnDeath()
            }
        }
    }
}

/**
 * Reads and replaces the guards' state of players, and keeps a copy of each online player's, readable
 * from any thread (for `activeOn`). Reads and writes of the attachment happen on the server thread.
 */
class GuardStates {
    private val snapshots = ConcurrentHashMap<UUID, GuardState>()

    /** [player]'s state. Server thread. */
    fun of(player: Player): GuardState = read(player)

    /** Replaces [player]'s state with [change] of it, and returns the new state. Server thread. */
    fun update(player: ServerPlayer, change: (GuardState) -> GuardState): GuardState {
        val next = change(read(player))
        write(player, next)
        snapshots[player.uuid] = next
        return next
    }

    /** Refreshes the copy of [player]'s state (a join, a respawn). Server thread. */
    fun refresh(player: ServerPlayer) {
        snapshots[player.uuid] = read(player)
    }

    /** Drops the copy of a player who left. */
    fun forget(uuid: UUID) {
        snapshots.remove(uuid)
    }

    /** The state of a player online, as the server thread last saw it; empty for a player offline. Any thread. */
    fun snapshot(uuid: UUID): GuardState = snapshots[uuid] ?: GuardState.EMPTY

    companion object {
        /** [player]'s state, straight from the attachment. Server thread. */
        fun read(player: Player): GuardState = player.getAttachedOrElse(GuardState.TYPE, GuardState.EMPTY)

        /** Replaces [player]'s state; an empty state removes the attachment, so the player file stays clean. Server thread. */
        fun write(player: Player, state: GuardState) {
            if (state == read(player)) return
            if (state == GuardState.EMPTY) player.removeAttached(GuardState.TYPE) else player.setAttached(GuardState.TYPE, state)
        }
    }
}
