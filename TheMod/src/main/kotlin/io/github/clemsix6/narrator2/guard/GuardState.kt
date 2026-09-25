package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
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
 * not make a whole ledger unreadable.
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
 * Items the Narrator took from a player, given back from [returnAt] on.
 *
 * @property id the id of its `ledger confiscated` decision.
 * @property items the stacks still held, each an `ItemStack` in SNBT. A stack that no longer reads back
 *   (its item left the game) stays here: nothing is ever erased silently.
 * @property what the stacks still held, for people and the model: `6x minecraft:diamond`.
 * @property takenAt and [returnAt]: epoch milliseconds.
 */
data class Confiscation(
    val id: Long,
    val items: List<String>,
    val what: String,
    val takenAt: Long,
    val returnAt: Long,
    val reason: String?,
    val turnId: Long?,
) {
    companion object {
        val CODEC: Codec<Confiscation> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("id").forGetter(Confiscation::id),
                Codec.STRING.listOf().fieldOf("items").forGetter(Confiscation::items),
                Codec.STRING.fieldOf("what").forGetter(Confiscation::what),
                Codec.LONG.fieldOf("taken_at").forGetter(Confiscation::takenAt),
                Codec.LONG.fieldOf("return_at").forGetter(Confiscation::returnAt),
                Codec.STRING.optionalFieldOf("reason").forGetter { Optional.ofNullable(it.reason) },
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.ofNullable(it.turnId) },
            ).apply(instance) { id, items, what, takenAt, returnAt, reason, turnId ->
                Confiscation(id, items, what, takenAt, returnAt, reason.orElse(null), turnId.orElse(null))
            }
        }
    }
}

/**
 * The last punishment opened against a player, kept so that `Episodes` can be rebuilt when they join
 * after a restart.
 *
 * @property until epoch milliseconds.
 */
data class OpenEpisode(val turnId: Long?, val until: Long) {
    companion object {
        val CODEC: Codec<OpenEpisode> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.optionalFieldOf("turn_id").forGetter { Optional.ofNullable(it.turnId) },
                Codec.LONG.fieldOf("until").forGetter(OpenEpisode::until),
            ).apply(instance) { turnId, until -> OpenEpisode(turnId.orElse(null), until) }
        }
    }
}

/**
 * The guards' state of one player. Immutable: always replaced by a new instance (`setAttached`), never
 * changed in place.
 *
 * @property armedUntil the death guard is armed until this value of the player's `PLAY_TIME` stat
 *   (ticks played): it counts the same ticks as the effects it covers, and pauses while they are away.
 * @property protectedDeath the player died under the death guard and keeps their inventory: set when
 *   the death is allowed, cleared when they respawn. Read by the keep-inventory mixins.
 * @property narratorKills the player's `PLAY_TIME` at each death the Narrator caused within the last
 *   played hour.
 * @property lastDeathAt when the player last died, whatever killed them, epoch milliseconds (real time,
 *   like the no-piling-on rule); forgotten once that rule no longer applies. Saved with the player, so a
 *   player who dies, leaves and comes back within the 5 minutes is still spared.
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
) {
    companion object {
        val EMPTY = GuardState()

        /** Every field is optional, so a state saved before a field existed still reads back. */
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
            ).apply(instance) { modifiers, effects, confiscations, episode, armedUntil, protectedDeath, narratorKills, lastDeathAt ->
                GuardState(modifiers, effects, confiscations, episode.orElse(null), armedUntil, protectedDeath, narratorKills, lastDeathAt.orElse(null))
            }
        }

        /**
         * The persistent player attachment `narrator2:guard_state`, copied to the new player object after
         * a death. Registered by `registerGuardHooks`, before any world loads.
         */
        val TYPE: AttachmentType<GuardState> =
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "guard_state")) { builder ->
                builder.persistent(CODEC).copyOnDeath()
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
