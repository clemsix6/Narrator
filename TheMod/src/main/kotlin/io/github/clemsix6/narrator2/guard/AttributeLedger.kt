package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import java.time.Clock

/**
 * The Narrator's attribute modifiers (M1_M2 §10.2, `ORACLE_ACTIONS_API.md` §4.5): at most one per
 * attribute, `narrator2:mod/<attribute>`, always *transient* (the game never saves it), listed in the
 * player's [GuardState]. The list is the truth: the modifiers are reapplied from it after a death and at
 * each join, and removed when due, a restart in between included. A crash can therefore never leave a
 * forgotten modifier on a player.
 *
 * Decisions `ledger applied`, `removed`, `expired` and `reapplied`. Server thread only.
 */
class AttributeLedger(private val states: GuardStates, private val decisions: Decisions, private val clock: Clock) {
    private val log = Log.guard

    /** [key]'s current value on [player]. */
    fun value(player: ServerPlayer, key: AttributeKey): Double = instance(player, key).value

    /**
     * The value [key] would have on [player] with the Narrator's modifier set to [amount] [operation]
     * (replacing the one it may have now), after the game's own bounds: what `set_attribute` checks
     * against the bounds of [AttributeKey] before applying. Nothing changes on the player.
     */
    fun valueWith(player: ServerPlayer, key: AttributeKey, amount: Double, operation: AttributeModifier.Operation): Double {
        val instance = instance(player, key)
        val id = modifierId(key)
        val modifiers = instance.modifiers.filterNot { it.id() == id } + AttributeModifier(id, amount, operation)
        // AttributeInstance.calculateValue, which is private.
        var base = instance.baseValue
        modifiers.filter { it.operation() == AttributeModifier.Operation.ADD_VALUE }.forEach { base += it.amount() }
        var total = base
        modifiers.filter { it.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE }.forEach { total += base * it.amount() }
        modifiers.filter { it.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL }.forEach { total *= 1 + it.amount() }
        return instance.attribute.value().sanitizeValue(total)
    }

    /**
     * Sets the Narrator's modifier of [key] on [player] to [amount] [operation] until [expiresAt] (epoch
     * milliseconds), replacing the previous one, and returns the attribute's new value. The ledger entry
     * is written first, then the modifier applied.
     *
     * @param harmful part of a punishment (the change goes in the hostile direction of [AttributeKey]).
     */
    fun apply(
        player: ServerPlayer,
        key: AttributeKey,
        amount: Double,
        operation: AttributeModifier.Operation,
        expiresAt: Long,
        harmful: Boolean,
        turnId: Long?,
    ): Double {
        val instance = instance(player, key)
        val entry = LedgerModifier(key.id, amount, operation.serializedName, expiresAt, harmful, turnId)
        states.update(player) { state -> state.copy(modifiers = state.modifiers.filterNot { it.attribute == key.id } + entry) }
        // The game's update leaves the previous modifier of the same id in place when its operation differs
        // (an add, then a multiply, would both count, and the add would outlive the ledger): removed first.
        instance.removeModifier(modifierId(key))
        instance.addOrUpdateTransientModifier(AttributeModifier(modifierId(key), amount, operation))
        val value = instance.value
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "applied", player.plainTextName,
            reason = "${describe(entry)} until ${timeOf(expiresAt)}: ${key.id} is now ${round(value)}",
            turnId = turnId,
            detail = detail(entry),
        )
        return value
    }

    /** Removes the Narrator's modifier of [key] from [player]; false when there was none. */
    fun reset(player: ServerPlayer, key: AttributeKey): Boolean {
        val entry = states.of(player).modifiers.firstOrNull { it.attribute == key.id }
        val removed = instance(player, key).removeModifier(modifierId(key))
        if (entry == null && !removed) return false
        states.update(player) { state -> state.copy(modifiers = state.modifiers.filterNot { it.attribute == key.id }) }
        decisions.record(log, DecisionRecord.Kind.LEDGER, "removed", player.plainTextName, reason = "${entry?.let(::describe) ?: key.id} lifted before its time")
        return true
    }

    /** Removes every modifier of the Narrator from [player]; returns the attributes it changed. */
    fun resetAll(player: ServerPlayer): List<AttributeKey> = AttributeKey.entries.filter { reset(player, it) }

    /** The Narrator's modifiers on [player], from the ledger. */
    fun on(player: ServerPlayer): List<LedgerModifier> = states.of(player).modifiers

    /** Removes [player]'s modifiers that are due at [now] (epoch milliseconds). Once a second, for the players online. */
    fun expire(player: ServerPlayer, now: Long) {
        val due = states.of(player).modifiers.filter { it.expiresAt <= now }
        if (due.isEmpty()) return
        states.update(player) { state -> state.copy(modifiers = state.modifiers - due.toSet()) }
        for (entry in due) {
            val key = keyOf(entry)
            if (key != null) instance(player, key).removeModifier(modifierId(key))
            decisions.record(log, DecisionRecord.Kind.LEDGER, "expired", player.plainTextName, reason = "${describe(entry)} is over", turnId = entry.turnId)
        }
    }

    /**
     * Puts the ledger back on a new player object (a join, a respawn): the Narrator's modifiers that are
     * not in the ledger are removed, the due ones are dropped, and the others applied.
     */
    fun reapply(player: ServerPlayer, now: Long) {
        val (due, live) = states.of(player).modifiers.partition { it.expiresAt <= now }
        val kept = live.mapNotNull { entry ->
            val key = keyOf(entry)
            val operation = operationOf(entry)
            if (key == null || operation == null) {
                log.warn("dropped an unusable modifier of ${player.plainTextName} from the ledger: $entry")
                null
            } else {
                Triple(entry, key, operation)
            }
        }
        if (kept.size != live.size || due.isNotEmpty()) {
            states.update(player) { state -> state.copy(modifiers = kept.map { it.first }) }
        }
        due.forEach {
            decisions.record(log, DecisionRecord.Kind.LEDGER, "expired", player.plainTextName, reason = "${describe(it)} ended while away", turnId = it.turnId)
        }
        for (key in AttributeKey.entries) {
            val instance = player.getAttribute(holder(key)) ?: continue
            val stray = instance.modifiers.filter { it.id().namespace == NAMESPACE && it.id().path.startsWith(MODIFIER_PREFIX) }
                .filter { modifier -> kept.none { (_, keptKey) -> modifierId(keptKey) == modifier.id() } }
            for (modifier in stray) {
                instance.removeModifier(modifier.id())
                log.info("removed a stray modifier ${modifier.id()} from ${player.plainTextName}: not in the ledger")
            }
        }
        for ((entry, key, operation) in kept) {
            instance(player, key).addOrUpdateTransientModifier(AttributeModifier(modifierId(key), entry.amount, operation))
        }
        if (kept.isNotEmpty()) {
            decisions.record(
                log, DecisionRecord.Kind.LEDGER, "reapplied", player.plainTextName,
                reason = kept.joinToString { (entry) -> "${describe(entry)} until ${timeOf(entry.expiresAt)}" },
            )
        }
    }

    private fun instance(player: ServerPlayer, key: AttributeKey): AttributeInstance =
        player.getAttribute(holder(key)) ?: error("${player.plainTextName} has no attribute ${key.id}")

    private fun timeOf(epochMs: Long): String = clockTime(clock, epochMs)

    private fun detail(entry: LedgerModifier) = buildJsonObject {
        put("attribute", entry.attribute)
        put("amount", entry.amount)
        put("operation", entry.operation)
        put("expires_at", entry.expiresAt)
        put("harmful", entry.harmful)
    }

    companion object {
        /** Namespace of the Narrator's modifier ids. */
        const val NAMESPACE = "narrator2"

        /** Path prefix of the Narrator's modifier ids. */
        private const val MODIFIER_PREFIX = "mod/"

        /** Id of the Narrator's modifier of [key]: `narrator2:mod/<attribute>`. */
        fun modifierId(key: AttributeKey): Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, MODIFIER_PREFIX + key.id)

        /** The game's attribute behind [key]. */
        fun holder(key: AttributeKey): Holder<Attribute> =
            BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(key.id)).orElseThrow { IllegalStateException("no attribute minecraft:${key.id}") }

        /** The [AttributeKey] of a ledger entry; null when it no longer exists. */
        fun keyOf(entry: LedgerModifier): AttributeKey? = AttributeKey.entries.firstOrNull { it.id == entry.attribute }

        /** The operation of a ledger entry; null when unknown. */
        fun operationOf(entry: LedgerModifier): AttributeModifier.Operation? =
            AttributeModifier.Operation.entries.firstOrNull { it.serializedName == entry.operation }

        /** `scale x0.5`, `max_health +4`, `movement_speed +50% of base`. */
        fun describe(entry: LedgerModifier): String = when (entry.operation) {
            "add_multiplied_total" -> "${entry.attribute} x${round(1 + entry.amount)}"
            "add_multiplied_base" -> "${entry.attribute} ${signed(entry.amount * 100)}% of base"
            else -> "${entry.attribute} ${signed(entry.amount)}"
        }

        private fun signed(value: Double): String = if (value >= 0) "+${round(value)}" else round(value)

        private fun round(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }
}
