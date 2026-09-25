package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.RegistryOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Confiscations (M1_M2 §10.2): an item is never lost for good. A confiscation is written in the
 * player's [GuardState] in the same tick as the items leave the inventory, so both reach the player's
 * file at the same save, and a crash can only lose both or neither.
 *
 * Items come back from their due time on, as soon as the player is online and alive: into the inventory,
 * never on the ground. What does not fit stays in the ledger and comes back once there is room (the
 * player is told once). A stack that no longer reads back (its item left the game) stays in the ledger
 * too, with an error in the log and a message to the operators: nothing is ever erased silently.
 * What players entrusted to one of the Narrator's creatures comes back the same way when it expires
 * ([recover]).
 *
 * Decisions `ledger confiscated`, `returned`, `unreadable`, `recovered` and `dropped`. Server thread only.
 */
class ItemLedger(
    private val states: GuardStates,
    private val decisions: Decisions,
    private val clock: Clock,
    private val ui: () -> UiTexts,
) {
    private val log = Log.guard

    /** Confiscations whose player was told there is no room, and whose unreadable stacks were reported (once per run). */
    private val toldNoRoom = ConcurrentHashMap.newKeySet<Long>()
    private val reportedUnreadable = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Takes up to [max] items matching [match] from [player]'s inventory (armor and off hand included,
     * not the item held by the cursor), to give them back at [returnAt] (epoch milliseconds). Returns the
     * confiscation, or null when nothing matched. Every stack is written before anything is taken: a
     * stack that cannot be written makes it fail, with nothing taken.
     */
    fun confiscate(player: ServerPlayer, match: (ItemStack) -> Boolean, max: Int, returnAt: Long, reason: String?, turnId: Long?): Confiscation? {
        require(max > 0) { "max must be positive" }
        val inventory = player.inventory
        val ops = ops(player)
        val taken = mutableListOf<Pair<Int, ItemStack>>()
        var left = max
        for (slot in 0 until inventory.containerSize) {
            if (left <= 0) break
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || !match(stack)) continue
            val part = stack.copyWithCount(minOf(left, stack.count))
            taken += slot to part
            left -= part.count
        }
        if (taken.isEmpty()) return null
        val items = taken.map { (_, stack) -> encode(stack, ops) }
        val stacks = taken.map { it.second }
        val what = describe(stacks)
        val id = decisions.record(
            log, DecisionRecord.Kind.LEDGER, "confiscated", player.plainTextName,
            reason = "$what until ${clockTime(clock, returnAt)}" + (reason?.let { ": $it" } ?: ""),
            turnId = turnId,
            detail = buildJsonObject {
                put("items", what)
                put("count", stacks.sumOf { it.count })
                put("return_at", returnAt)
            },
        )
        val confiscation = Confiscation(id, items, what, clock.millis(), returnAt, reason, turnId)
        states.update(player) { it.copy(confiscations = it.confiscations + confiscation) }
        for ((slot, stack) in taken) inventory.removeItem(slot, stack.count)
        player.containerMenu.broadcastChanges()
        return confiscation
    }

    /** What the Narrator holds of [player]'s items. */
    fun held(player: ServerPlayer): List<Confiscation> = states.of(player).confiscations

    /**
     * Hands back [stacks], which players had entrusted to [from], one of the Narrator's creatures that
     * expired, so that nothing disappears with it. To [owner] when known (online): written in their
     * ledger, due now, then given back like a confiscation (what does not fit waits there); decision
     * `ledger recovered`, then `returned`. Otherwise dropped where the creature stood, never despawning;
     * decision `ledger dropped`.
     */
    fun recover(stacks: List<ItemStack>, from: Entity, owner: ServerPlayer?) {
        if (stacks.isEmpty()) return
        val what = describe(stacks)
        val creature = from.typeId
        if (owner == null) {
            drop(stacks, from)
            decisions.record(
                log, DecisionRecord.Kind.LEDGER, "dropped", creature,
                reason = "$what entrusted by players to the Narrator's $creature, which expired with no owner online; dropped at " +
                    "${from.blockPosition().toShortString()} in ${from.level().dimensionId}",
                detail = buildJsonObject { put("items", what) },
            )
            return
        }
        val ops = ops(owner)
        val now = clock.millis()
        val id = decisions.record(
            log, DecisionRecord.Kind.LEDGER, "recovered", owner.plainTextName,
            reason = "$what entrusted to the Narrator's $creature, which expired",
            detail = buildJsonObject {
                put("items", what)
                put("creature", creature)
            },
        )
        val entry = Confiscation(id, stacks.map { encode(it, ops) }, what, now, now, "entrusted to $creature", null)
        states.update(owner) { it.copy(confiscations = it.confiscations + entry) }
        giveBack(owner, listOf(entry), "items.recovered")
    }

    /**
     * Gives back to [player] what is due at [now] (epoch milliseconds). Nothing happens while they are
     * dead: it comes back after the respawn. Once a second for the players online, and at each join and
     * respawn.
     */
    fun giveBack(player: ServerPlayer, now: Long) {
        giveBack(player, states.of(player).confiscations.filter { it.returnAt <= now }, "items.returned")
    }

    /** Gives back [due] to [player], telling them with the UI text [key]. */
    private fun giveBack(player: ServerPlayer, due: List<Confiscation>, key: String) {
        if (player.isDeadOrDying || player.isRemoved) return
        if (due.isEmpty()) return
        val ops = ops(player)
        val outcomes = due.map { confiscation ->
            // Each confiscation's ledger entry changes right after its items go in: nothing can come between.
            give(player, confiscation, ops).also { outcome ->
                if (outcome.given.isNotEmpty()) {
                    states.update(player) { state ->
                        state.copy(confiscations = state.confiscations.mapNotNull { if (it.id == confiscation.id) outcome.remaining else it })
                    }
                }
            }
        }
        val given = outcomes.flatMap { it.given }
        if (given.isEmpty()) return
        player.containerMenu.broadcastChanges()
        player.sendSystemMessage(narratorLine(key, given))
        for (outcome in outcomes.filter { it.given.isNotEmpty() }) {
            decisions.record(
                log, DecisionRecord.Kind.LEDGER, "returned", player.plainTextName,
                reason = "confiscation ${outcome.confiscation.id}: ${describe(outcome.given)} given back" +
                    (outcome.remaining?.let { "; still held: ${it.what}" } ?: ""),
                turnId = outcome.confiscation.turnId,
                detail = buildJsonObject {
                    put("confiscation", outcome.confiscation.id)
                    put("items", describe(outcome.given))
                    put("still_held", outcome.remaining?.what)
                },
            )
        }
    }

    /** What giving back one confiscation did: what went back, and what is still held (null: nothing). */
    private class Outcome(val confiscation: Confiscation, val given: List<ItemStack>, val remaining: Confiscation?)

    private fun give(player: ServerPlayer, confiscation: Confiscation, ops: RegistryOps<Tag>): Outcome {
        val given = mutableListOf<ItemStack>()
        val kept = mutableListOf<String>()
        val waiting = mutableListOf<ItemStack>()
        var unreadable = 0
        for (snbt in confiscation.items) {
            val stack = decode(snbt, ops)
            if (stack == null) {
                kept += snbt
                unreadable++
                continue
            }
            val rest = stack.copy()
            insert(player.inventory, rest)
            if (rest.count < stack.count) given += stack.copyWithCount(stack.count - rest.count)
            if (!rest.isEmpty) {
                kept += encode(rest, ops)
                waiting += rest
            }
        }
        if (unreadable > 0 && reportedUnreadable.add(confiscation.id)) reportUnreadable(player, confiscation, unreadable)
        if (waiting.isNotEmpty() && toldNoRoom.add(confiscation.id)) player.sendSystemMessage(narratorLine("items.no_room", waiting))
        if (given.isEmpty()) return Outcome(confiscation, given, confiscation)
        val what = listOfNotNull(describe(waiting).ifEmpty { null }, if (unreadable > 0) "$unreadable unreadable stacks" else null).joinToString(", ")
        val remaining = if (kept.isEmpty()) null else confiscation.copy(items = kept, what = what)
        return Outcome(confiscation, given, remaining)
    }

    private fun reportUnreadable(player: ServerPlayer, confiscation: Confiscation, count: Int) {
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "unreadable", player.plainTextName,
            reason = "confiscation ${confiscation.id}: $count stacks no longer read back (item gone from the game?); they stay in the ledger",
            turnId = confiscation.turnId,
            level = Log.Level.ERROR,
        )
        val server = player.level().server
        val line = Component.literal(
            "[Narrator] $count confiscated stacks of ${player.plainTextName} (confiscation ${confiscation.id}) no longer read back; " +
                "they stay in the ledger, see the server log.",
        ).withStyle(ChatFormatting.RED)
        server.playerList.players.filter { server.playerList.isOp(it.nameAndId()) }.forEach { it.sendSystemMessage(line) }
    }

    /** `[Narrator → you] <text with the items>`, from the UI text [key] and its `{items}` placeholder. */
    private fun narratorLine(key: String, items: List<ItemStack>): Component {
        val texts = ui()
        val color = TextColor.parseColor(texts["narrator.color"]).result().orElse(null)
        val (before, after) = texts[key].split("{items}", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val names: MutableComponent = Component.empty()
        items.forEachIndexed { i, stack ->
            if (i > 0) names.append(", ")
            names.append("${stack.count}× ").append(stack.hoverName)
        }
        return Component.empty()
            .append(Component.literal(texts["narrator.whisper_prefix"] + " ").withStyle { it.withColor(color) })
            .append(before).append(names).append(after)
    }

    companion object {
        /**
         * Puts [stack] into [inventory] as far as it fits, and shrinks it by what went in. Unlike
         * `Inventory.add`, never destroys what does not fit (it does for a creative player).
         */
        fun insert(inventory: Inventory, stack: ItemStack) {
            while (!stack.isEmpty) {
                val partial = inventory.getSlotWithRemainingSpace(stack)
                if (partial >= 0) {
                    val existing = inventory.getItem(partial)
                    val moved = minOf(stack.count, existing.maxStackSize - existing.count)
                    existing.grow(moved)
                    stack.shrink(moved)
                    continue
                }
                val free = inventory.getFreeSlot()
                if (free < 0) break
                inventory.setItem(free, stack.copyAndClear())
            }
            inventory.setChanged()
        }

        /** `6x minecraft:diamond, 1x minecraft:iron_sword`. */
        fun describe(stacks: List<ItemStack>): String =
            stacks.groupBy { it.id }.map { (id, same) -> "${same.sumOf { it.count }}x $id" }.joinToString(", ")

        /** Drops [stacks] where [at] stands, as items that never despawn: nothing is lost to the 5-minute clock. */
        fun drop(stacks: List<ItemStack>, at: Entity) {
            val level = at.level() as? ServerLevel ?: return
            for (stack in stacks) at.spawnAtLocation(level, stack)?.setUnlimitedLifetime()
        }

        private fun ops(player: ServerPlayer): RegistryOps<Tag> = player.registryAccess().createSerializationContext(NbtOps.INSTANCE)

        /** [stack] in SNBT, with its components. */
        private fun encode(stack: ItemStack, ops: RegistryOps<Tag>): String = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString()

        /** The stack written by [encode]; null when it no longer reads back, whatever the reason. */
        private fun decode(snbt: String, ops: RegistryOps<Tag>): ItemStack? = try {
            ItemStack.CODEC.parse(ops, TagParser.parseCompoundFully(snbt)).result().orElse(null)
        } catch (e: Exception) {
            null
        }
    }
}
