package io.github.clemsix6.narrator2.guard

import com.mojang.datafixers.DataFixer
import com.mojang.serialization.Dynamic
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.mixin.guard.PlayerListAccessor
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.RegistryOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.fixes.References
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ContainerComponent
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The ledger of the items the Narrator holds for players (M1_M2 §10.2, M4 §5.6, §7.2): an item is never lost for
 * good. Every entry is written in the player's [GuardState] in the same tick as the items leave the inventory, so
 * both reach the player's file at the same save, and a crash can only lose both or neither. A stack of any size
 * fits in the file (a full book, a shulker box of books: [GuardState]), and one held across an update of the game is
 * brought up to date by its data fixer before it comes back.
 *
 * It holds, by [LedgerPurpose]:
 * - **confiscations**, the Narrator's punishment (`take_item`), given back at their due time;
 * - **stakes** of bets (`/bet`), given back when [release]d, handed over to a winner once both are online when
 *   [forfeit]ed, and given back to their owner at their fallback deadline otherwise ([Escrow]);
 * - **offerings** (`/offer`), the Narrator's for good only when [consume]d for a favor granted, given back at their
 *   deadline otherwise (and the Director told, without priority);
 * - what is **owed** to a player (winnings, a joker) and found no room ([owe]), delivered as soon as there is room.
 *
 * Items come back from their due time on, as soon as the player is online and alive: into the inventory, **never on
 * the ground** (an item entity despawns, burns, and is saved with its chunk, not with the player). What does not fit
 * stays in the ledger and comes back once there is room (the player is told once). A stack that no longer reads back
 * (its item left the game) stays in the ledger too, with an error in the log and a message to the operators: nothing
 * is ever erased silently. What players entrusted to one of the Narrator's creatures comes back the same way when it
 * expires ([recover]).
 *
 * Decisions `ledger confiscated`, `held`, `released`, `forfeited`, `transferred`, `offered`, `owed`, `returned`,
 * `delivered`, `unreadable`, `recovered` and `dropped`. Server thread only.
 *
 * @param tell where the ledger's news for the Director goes (an offering given back untaken): by default the
 *   Director of the running Narrator on the player's server.
 */
class ItemLedger(
    private val states: GuardStates,
    private val decisions: Decisions,
    private val clock: Clock,
    private val tell: (ServerPlayer, DirectorSignal) -> Unit = ::tellRunningDirector,
    private val ui: () -> UiTexts,
) {
    private val log = Log.guard

    /** Entries whose player was told there is no room, whose unreadable stacks were reported, and offerings announced back (once per run). */
    private val toldNoRoom = ConcurrentHashMap.newKeySet<Long>()
    private val reportedUnreadable = ConcurrentHashMap.newKeySet<Long>()
    private val announcedBack = ConcurrentHashMap.newKeySet<Long>()

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
        val confiscation = Confiscation(id, items, what, clock.millis(), returnAt, reason, turnId, dataVersion = DATA_VERSION)
        states.update(player) { it.copy(confiscations = it.confiscations + confiscation) }
        for ((slot, stack) in taken) inventory.removeItem(slot, stack.count)
        player.containerMenu.broadcastChanges()
        return confiscation
    }

    /**
     * Holds the whole stack in [player]'s [hand] in escrow (M4 §5.6, §7.2): a [LedgerPurpose.STAKE] on challenge
     * [canon], or a [LedgerPurpose.OFFERING] (`O-<id>`), which also dates their last `/offer`. Given back at
     * [returnAt] (epoch milliseconds) unless settled or taken before: a stake's is its fallback deadline
     * ([Escrow.stakeDeadline]), never `Long.MAX_VALUE`. Returns the entry, or null when the hand is empty or holds a
     * joker, even inside a shulker box or a bundle ([holdsJoker]: it is neither staked nor offered). The entry is
     * written before the hand is emptied. Decision `ledger held`.
     */
    fun hold(player: ServerPlayer, hand: InteractionHand, purpose: LedgerPurpose, returnAt: Long, canon: Long?, reason: String): Confiscation? {
        val stack = player.getItemInHand(hand)
        if (stack.isEmpty || holdsJoker(stack)) return null
        val items = listOf(encode(stack, ops(player)))
        val what = describe(listOf(stack))
        val id = decisions.record(
            log, DecisionRecord.Kind.LEDGER, "held", player.plainTextName,
            reason = "$what held as ${purpose.id}" + (canon?.let { " on C-$it" } ?: "") + " until ${moment(returnAt)}: $reason",
            detail = buildJsonObject {
                put("purpose", purpose.id)
                put("items", what)
                put("count", stack.count)
                put("canon", canon)
                put("return_at", returnAt)
            },
        )
        val entry = Confiscation(id, items, what, clock.millis(), returnAt, reason, turnId = null, purpose = purpose, canon = canon, dataVersion = DATA_VERSION)
        states.update(player) { Escrow.hold(it, entry) }
        player.setItemInHand(hand, ItemStack.EMPTY)
        player.containerMenu.broadcastChanges()
        return entry
    }

    /**
     * Gives stake [id] back to [player], its owner, at once (what does not fit waits): their bet won, nobody won, or
     * the challenge was cancelled (M4 §5.6). False when there is nothing to do (already given back or due): applying
     * a settlement twice gives nothing more. Decision `ledger released`.
     */
    fun release(player: ServerPlayer, id: Long, now: Long): Boolean {
        val next = Escrow.release(states.of(player), id, now) ?: return false
        states.update(player) { next }
        val stake = next.confiscations.first { it.id == id }
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "released", player.plainTextName,
            reason = "stake $id (${stake.what}) goes back to its owner",
            detail = buildJsonObject {
                put("stake", id)
                put("items", stake.what)
                put("canon", stake.canon)
            },
        )
        giveBack(player, now)
        return true
    }

    /**
     * Cedes stake [id] of [player], who lost their bet, to the winner [to] (M4 §5.6): handed over as soon as both are
     * online (at once if they are), into the winner's inventory, what does not fit waiting in their ledger. If they
     * never are, it goes back to [player] after [Escrow.cededDeadline]. False when there is nothing to do (no such
     * stake, or already ceded to [to]). Decision `ledger forfeited`.
     */
    fun forfeit(player: ServerPlayer, id: Long, to: UUID, now: Long): Boolean {
        require(to != player.uuid) { "a stake is never ceded to its owner" }
        val next = Escrow.forfeit(states.of(player), id, to, now) ?: return false
        states.update(player) { next }
        val stake = next.confiscations.first { it.id == id }
        val winner = player.level().server.playerList.getPlayer(to)?.plainTextName ?: to.toString()
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "forfeited", player.plainTextName,
            reason = "stake $id (${stake.what}) goes to $winner once both are online; back to its owner at ${moment(stake.returnAt)} otherwise",
            detail = buildJsonObject {
                put("stake", id)
                put("items", stake.what)
                put("canon", stake.canon)
                put("to", to.toString())
                put("return_at", stake.returnAt)
            },
        )
        giveBack(player, now)
        return true
    }

    /**
     * Takes offering [id] of [player] for good (M4 §7.2): call it only once the favor granted for it is (`favor` with
     * `offering`), the player online. Null when it is not an offering of theirs still waiting for an answer (taken, or
     * past its deadline). [reason]: what it was taken for, English. Tells the player (`offer.accepted`). Decision
     * `ledger offered`.
     */
    fun consume(player: ServerPlayer, id: Long, reason: String): Confiscation? {
        val offering = Escrow.offering(states.of(player), id, clock.millis()) ?: return null
        states.update(player) { Escrow.without(it, id) }
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "offered", player.plainTextName,
            reason = "${Escrow.ref(id)}: ${offering.what} the Narrator's for good, for a favor granted: $reason",
            detail = buildJsonObject {
                put("offering", id)
                put("items", offering.what)
            },
        )
        player.sendSystemMessage(line("offer.accepted", stacks(offering, player), offering.what))
        return offering
    }

    /**
     * Gives [stacks] (a joker, winnings) to [player]: into the inventory as far as they fit, the rest waits in their
     * ledger ([LedgerPurpose.OWED], due at once) and comes back as soon as there is room; never on the ground. The
     * caller tells the player what they got; they are told once when something waits. Returns what waits (empty when
     * everything fit). Decision `ledger owed` when something waits.
     */
    fun owe(player: ServerPlayer, stacks: List<ItemStack>, reason: String): List<ItemStack> {
        val ops = ops(player)
        stacks.forEach { encode(it, ops) } // a stack that cannot be written fails here, before anything is given
        // A dead player's inventory is not theirs to fill: everything waits for the respawn.
        val alive = !player.isDeadOrDying && !player.isRemoved
        val rest = stacks.mapNotNull { stack ->
            val left = stack.copy()
            if (alive) insert(player.inventory, left)
            left.takeUnless { it.isEmpty }
        }
        if (alive) player.containerMenu.broadcastChanges()
        if (rest.isEmpty()) return emptyList()
        val what = describe(rest)
        val now = clock.millis()
        val id = decisions.record(
            log, DecisionRecord.Kind.LEDGER, "owed", player.plainTextName,
            reason = "$what waits for room in the inventory: $reason",
            detail = buildJsonObject { put("items", what) },
        )
        val entry = Confiscation(id, rest.map { encode(it, ops) }, what, now, now, reason, turnId = null, purpose = LedgerPurpose.OWED, dataVersion = DATA_VERSION)
        states.update(player) { it.copy(confiscations = it.confiscations + entry) }
        if (alive && toldNoRoom.add(id)) player.sendSystemMessage(line("items.no_room", rest))
        return rest
    }

    /**
     * Gives entry [id] back to [player], its owner, now, whatever it is ([Escrow.returnNow]: an operator's
     * `/narrator guards <player> release <id>`); what does not fit waits, as always, and an offering is told to the
     * Director as one not taken. The entry as it was; null when [player] has no such entry, or it is due already
     * (waiting for room in the inventory). Decision `ledger returned`, as any entry given back.
     */
    fun returnNow(player: ServerPlayer, id: Long, now: Long): Confiscation? {
        val state = states.of(player)
        val next = Escrow.returnNow(state, id, now) ?: return null
        states.update(player) { next }
        giveBack(player, now)
        return state.confiscations.first { it.id == id }
    }

    /** What the Narrator holds of [player]'s items: confiscations, stakes, offerings and what is owed. */
    fun held(player: ServerPlayer): List<Confiscation> = states.of(player).confiscations

    /** The offering [id] of [player] still waiting for the Narrator's answer; null when gone or past its deadline. */
    fun offering(player: ServerPlayer, id: Long): Confiscation? = Escrow.offering(states.of(player), id, clock.millis())

    /** Why [player] may not `/offer` at [now], offerings being [minutes] apart; null when they may. */
    fun offerRefusal(player: ServerPlayer, now: Long, minutes: Int): OfferRefusal? = Escrow.offerRefusal(states.of(player), now, minutes)

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
        val entry = Confiscation(id, stacks.map { encode(it, ops) }, what, now, now, "entrusted to $creature", null, dataVersion = DATA_VERSION)
        states.update(owner) { it.copy(confiscations = it.confiscations + entry) }
        deliver(owner, listOf(entry), now) { "items.recovered" }
    }

    /**
     * Gives back to [player] what is due at [now] (epoch milliseconds), and hands over the stakes they ceded to a
     * winner online. Nothing happens while they are dead: it comes back after the respawn. Once a second for the
     * players online, and at each join and respawn.
     */
    fun giveBack(player: ServerPlayer, now: Long) {
        if (player.isDeadOrDying || player.isRemoved) return
        handOver(player, now)
        deliver(player, Escrow.due(states.of(player), now), now)
    }

    /** The stakes [owner] ceded to a winner who is online and alive now, handed over together ([transfer]). */
    private fun handOver(owner: ServerPlayer, now: Long) {
        val players = owner.level().server.playerList
        val handed = Escrow.ceded(states.of(owner), now).mapNotNull { stake ->
            players.getPlayer(stake.owedTo ?: return@mapNotNull null)?.takeUnless { it.isDeadOrDying || it.isRemoved }?.let { stake to it }
        }
        if (handed.isNotEmpty()) transfer(owner, handed, now)
    }

    /**
     * Hands the [handed] stakes over from [owner] to their winners, all online, so that a crash at any point loses
     * nothing and a replay gives nothing twice (M4 §5.6):
     * 1. the winnings go into each winner's ledger under the stake's own id, and the stake into their received stakes,
     *    unless they received it already (a replay: [Escrow.receive]); each winner who got something has their file
     *    written: it holds the winnings before the stakes leave the owner's;
     * 2. the stakes leave the owner's ledger, and the owner's file is written: it no longer holds them before any
     *    winner gets anything;
     * 3. the winnings are delivered like anything owed: into the inventory, the rest waits in the winner's ledger.
     *
     * Only these players' files are written, once each ([save]): never every player online, on the server thread.
     */
    private fun transfer(owner: ServerPlayer, handed: List<Pair<Confiscation, ServerPlayer>>, now: Long) {
        val replayed = handed.map { (stake, winner) ->
            val received = Escrow.receive(states.of(winner), Escrow.winnings(stake, owner.plainTextName, now), now)
            if (received != null) states.update(winner) { received }
            received == null
        }
        handed.filterIndexed { i, _ -> !replayed[i] }.map { it.second }.distinctBy { it.uuid }.forEach(::save)
        states.update(owner) { state -> handed.fold(state) { kept, (stake, _) -> Escrow.without(kept, stake.id) } }
        save(owner)
        handed.forEachIndexed { i, (stake, winner) ->
            decisions.record(
                log, DecisionRecord.Kind.LEDGER, "transferred", owner.plainTextName,
                reason = "stake ${stake.id} (${stake.what}) lost" + (stake.canon?.let { " on C-$it" } ?: "") + ": handed over to ${winner.plainTextName}" +
                    (if (replayed[i]) " (already received: a hand-over replayed)" else ""),
                detail = buildJsonObject {
                    put("stake", stake.id)
                    put("items", stake.what)
                    put("canon", stake.canon)
                    put("to", winner.plainTextName)
                    put("replayed", replayed[i])
                },
            )
            owner.sendSystemMessage(line("bet.lost", stacks(stake, owner), stake.what))
            deliver(winner, states.of(winner).confiscations.filter { it.id == stake.id && it.purpose == LedgerPurpose.OWED }, now)
        }
    }

    /**
     * Writes [player]'s file now: their `.dat` alone, which holds the ledger (no statistics, no advancements), a few
     * milliseconds on the server thread. `PlayerList.save` is protected and writes those too: the storage is reached
     * by an accessor.
     */
    private fun save(player: ServerPlayer) {
        (player.level().server.playerList as PlayerListAccessor).`narrator2$playerIo`().save(player)
    }

    /**
     * Gives [due] to [player] at [now], each entry telling them with the UI text [key] of it, and records a decision
     * per entry: `delivered` for what was owed, `returned` for the rest. An offering that comes back is told to the
     * Director.
     */
    private fun deliver(player: ServerPlayer, due: List<Confiscation>, now: Long, key: (Confiscation) -> String = Escrow::arrivalKey) {
        if (player.isDeadOrDying || player.isRemoved) return
        if (due.isEmpty()) return
        val outcomes = due.map { entry ->
            // Each entry changes right after its items go in: nothing can come between.
            give(player, entry).also { outcome ->
                if (outcome.given.isNotEmpty()) {
                    states.update(player) { state ->
                        state.copy(confiscations = state.confiscations.mapNotNull { if (it.id == entry.id) outcome.remaining else it })
                    }
                }
            }
        }.filter { it.given.isNotEmpty() }
        if (outcomes.isEmpty()) return
        player.containerMenu.broadcastChanges()
        outcomes.groupBy { key(it.entry) }.forEach { (text, same) -> player.sendSystemMessage(line(text, same.flatMap { it.given })) }
        for (outcome in outcomes) {
            val entry = outcome.entry
            val owed = entry.purpose == LedgerPurpose.OWED
            val fallback = Escrow.isFallback(entry)
            decisions.record(
                log, DecisionRecord.Kind.LEDGER, if (owed) "delivered" else "returned", player.plainTextName,
                reason = "${Escrow.label(entry)}: ${describe(outcome.given)} ${if (owed) "delivered" else "given back"}" +
                    when {
                        !fallback -> ""
                        entry.owedTo != null -> " at its fallback deadline (its winner was never online with its owner)"
                        else -> " at its fallback deadline (its settlement never came)"
                    } +
                    (outcome.remaining?.let { "; still held: ${it.what}" } ?: ""),
                turnId = entry.turnId,
                detail = buildJsonObject {
                    put("confiscation", entry.id)
                    put("purpose", entry.purpose.id)
                    put("items", describe(outcome.given))
                    put("still_held", outcome.remaining?.what)
                    put("fallback", fallback)
                },
                level = if (fallback) Log.Level.WARN else Log.Level.INFO,
            )
            if (entry.purpose == LedgerPurpose.OFFERING && announcedBack.add(entry.id)) {
                tell(player, DirectorSignal.Mechanic(MechanicSignal.OFFERING, Escrow.offeringBackLine(entry, player.plainTextName), now, priority = false))
            }
        }
    }

    /** What giving back one entry did: what went back, and what is still held (null: nothing). */
    private class Outcome(val entry: Confiscation, val given: List<ItemStack>, val remaining: Confiscation?)

    /**
     * Gives [entry]'s stacks to [player] as far as they fit. What is still held is written again at the game's data
     * version: the stacks that did not fit as they are now, those that no longer read back as the data fixer left them.
     */
    private fun give(player: ServerPlayer, entry: Confiscation): Outcome {
        val ops = ops(player)
        val fixer = player.level().server.fixerUpper
        val given = mutableListOf<ItemStack>()
        val kept = mutableListOf<String>()
        val waiting = mutableListOf<ItemStack>()
        var unreadable = 0
        for (snbt in entry.items) {
            val tag = upToDate(snbt, entry.dataVersion, fixer)
            val stack = tag?.let { read(it, ops) }
            if (stack == null) {
                kept += tag?.toString() ?: snbt
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
        if (unreadable > 0 && reportedUnreadable.add(entry.id)) reportUnreadable(player, entry, unreadable)
        if (waiting.isNotEmpty() && toldNoRoom.add(entry.id)) player.sendSystemMessage(line("items.no_room", waiting))
        if (given.isEmpty()) return Outcome(entry, given, entry)
        val what = listOfNotNull(describe(waiting).ifEmpty { null }, if (unreadable > 0) "$unreadable unreadable stacks" else null).joinToString(", ")
        val remaining = if (kept.isEmpty()) null else entry.copy(items = kept, what = what, dataVersion = DATA_VERSION)
        return Outcome(entry, given, remaining)
    }

    private fun reportUnreadable(player: ServerPlayer, entry: Confiscation, count: Int) {
        decisions.record(
            log, DecisionRecord.Kind.LEDGER, "unreadable", player.plainTextName,
            reason = "${Escrow.label(entry)}: $count stacks no longer read back (item gone from the game?); they stay in the ledger",
            turnId = entry.turnId,
            level = Log.Level.ERROR,
        )
        val server = player.level().server
        val line = Component.literal(
            "[Narrator] $count stacks the Narrator holds for ${player.plainTextName} (${Escrow.label(entry)}) no longer read back; " +
                "they stay in the ledger, see the server log.",
        ).withStyle(ChatFormatting.RED)
        server.playerList.players.filter { server.playerList.isOp(it.nameAndId()) }.forEach { it.sendSystemMessage(line) }
    }

    /** [at] for a decision: its time of day, with its date when it is a day or more away (a fallback deadline). */
    private fun moment(at: Long): String =
        if (abs(at - clock.millis()) < DAY_MS) clockTime(clock, at) else DATE_TIME.format(Instant.ofEpochMilli(at).atZone(clock.zone))

    /** [entry]'s stacks that still read back, for what [player] reads. */
    private fun stacks(entry: Confiscation, player: ServerPlayer): List<ItemStack> {
        val ops = ops(player)
        val fixer = player.level().server.fixerUpper
        return entry.items.mapNotNull { snbt -> upToDate(snbt, entry.dataVersion, fixer)?.let { read(it, ops) } }
    }

    /**
     * `[Narrator → you] <text with the items>`, from the UI text [key] and its `{items}` placeholder: each stack's
     * count and name (hover shows the item). [what] stands for the items when none reads back.
     */
    fun line(key: String, items: List<ItemStack>, what: String? = null): Component {
        val texts = ui()
        val color = TextColor.parseColor(texts["narrator.color"]).result().orElse(null)
        val (before, after) = texts[key].split("{items}", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val names: MutableComponent = Component.empty()
        items.forEachIndexed { i, stack ->
            if (i > 0) names.append(", ")
            names.append("${stack.count}× ").append(stack.hoverName)
        }
        if (items.isEmpty() && what != null) names.append(what)
        return Component.empty()
            .append(Component.literal(texts["narrator.whisper_prefix"] + " ").withStyle { it.withColor(color) })
            .append(before).append(names).append(after)
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1_000L
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** The key of a joker's mark in its `CUSTOM_DATA` component: `{narrator2: {joker: 1b}}` (M4 §7.1). */
        const val JOKER_KEY = "joker"

        /** Whether [stack] is one of the Narrator's jokers (`CUSTOM_DATA {narrator2: {joker: 1b}}`, M4 §7.1): it is neither staked nor offered. */
        fun isJoker(stack: ItemStack): Boolean =
            stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty(Speech.CUSTOM_DATA_KEY)?.getBooleanOr(JOKER_KEY, false) == true

        /**
         * Whether [stack] is a joker or holds one, however deep: in a shulker box, a bundle, any container of items.
         * Such a stack is neither staked nor offered: an offering taken would take the joker with it.
         */
        fun holdsJoker(stack: ItemStack): Boolean =
            isJoker(stack) || stack.components.any { component -> (component.value() as? ContainerComponent<*>)?.itemCopies()?.anyMatch { holdsJoker(it) } == true }

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

        /** The game's data version, written with the stacks ([Confiscation.dataVersion]). */
        private val DATA_VERSION: Int by lazy { SharedConstants.getCurrentVersion().dataVersion().version() }

        private fun ops(player: ServerPlayer): RegistryOps<Tag> = player.registryAccess().createSerializationContext(NbtOps.INSTANCE)

        /** [stack] in SNBT, with its components. */
        private fun encode(stack: ItemStack, ops: RegistryOps<Tag>): String = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString()

        /**
         * The stack written by [encode] at data [version], as a tag brought up to the game's data version by [fixer]
         * (null [version]: as it is); null when it is not even a tag.
         */
        private fun upToDate(snbt: String, version: Int?, fixer: DataFixer): Tag? = try {
            val tag = TagParser.parseCompoundFully(snbt)
            if (version == null || version >= DATA_VERSION) tag else fixer.update(References.ITEM_STACK, Dynamic<Tag>(NbtOps.INSTANCE, tag), version, DATA_VERSION).value
        } catch (e: Exception) {
            null
        }

        /** The stack of [tag]; null when it no longer reads back, whatever the reason (its item left the game). */
        private fun read(tag: Tag, ops: RegistryOps<Tag>): ItemStack? = try {
            ItemStack.CODEC.parse(ops, tag).result().orElse(null)
        } catch (e: Exception) {
            null
        }
    }
}

/** Posts [signal] to the Director of the running Narrator, when [player] is on its server (the ledger's default [ItemLedger] `tell`). */
private fun tellRunningDirector(player: ServerPlayer, signal: DirectorSignal) {
    NarratorRuntime.current?.takeIf { it.server === player.level().server }?.director?.post(signal)
}
