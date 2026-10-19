package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.EffectLedger
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.GuardState
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.guard.TargetingPolicy
import io.github.clemsix6.narrator2.mechanics.Jokers
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.tool.ToolText
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * `/narrator protect <player> [on|off]` and `/narrator guards [player]` (M1_M2 §14.5, §10, M4 §18.1): who
 * the Narrator must leave alone, and what it did to the players that the engine will undo: effects,
 * attribute changes, confiscations, creatures, traps, running punishments, and the deaths it may still
 * cause; from M4, what limits it with each player (the pace the Director sees, the grace, the jokers) and
 * the items the guards hold for them (stakes, offerings, what they are owed). Runs on the server thread,
 * where the guards' state of a player lives.
 */
object GuardCommands {
    /** Adds this module's subcommands under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("protect").then(
                Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes { protect(it, on = true) }
                    .then(Commands.literal("on").executes { protect(it, on = true) })
                    .then(Commands.literal("off").executes { protect(it, on = false) }),
            ),
        )
        root.then(
            Commands.literal("guards")
                .executes { overview(it.source) }
                .then(Commands.argument("player", EntityArgument.player()).executes { inspect(it.source, EntityArgument.getPlayer(it, "player")) }),
        )
    }

    /**
     * Protects the players named (online or known to the server) or lifts their protection; the guards
     * save the list in `world/narrator2/protected.json` and record an `operator` decision.
     */
    private fun protect(context: CommandContext<CommandSourceStack>, on: Boolean): Int {
        val source = context.source
        val runtime = runningNarrator(source) ?: return 0
        val profiles = GameProfileArgument.getGameProfiles(context, "player")
        val configured = runtime.settings.get().config.safety.protectedPlayers
        for (profile in profiles) {
            val name = runtime.server.playerList.getPlayer(profile.id())?.plainTextName ?: profile.name()
            runtime.guards.setProtected(profile.id(), name, on)
            val reply = when {
                on -> "$name is protected: the Narrator does nothing hostile to them, and its explosions spare them."
                configured.any { it.equals(name, ignoreCase = true) } ->
                    "$name is no longer protected by /narrator protect, but safety.protectedPlayers still protects them."
                else -> "$name is no longer protected: the Narrator may act against them again."
            }
            source.sendSuccess({ narratorLine(reply) }, true)
        }
        return profiles.size
    }

    /** The chaos level, the Narrator's creatures, and one line per player online. */
    private fun overview(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        val guards = runtime.guards
        val config = runtime.settings.get().config
        val caps = guards.caps()
        val now = runtime.clock.millis()
        val players = runtime.server.playerList.players.sortedBy { it.plainTextName.lowercase() }
        val lines = buildList {
            add(narratorLine("guards · chaos ${config.chaos} of ${Caps.MAX_CHAOS}"))
            add(detailLine("The Narrator's creatures alive: ${guards.mobs.count()} of ${caps.livingMobs}"))
            add(detailLine("Protected by the configuration: ${config.safety.protectedPlayers.joinToString().ifEmpty { "nobody" }}"))
            if (players.isEmpty()) add(detailLine("Nobody is online."))
            players.take(MAX_PLAYERS).forEach { player -> add(detailLine("${player.plainTextName}: ${summary(runtime, player, now)}")) }
            if (players.size > MAX_PLAYERS) add(detailLine("… and ${players.size - MAX_PLAYERS} more: /narrator guards <player>"))
        }
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    /** One player's line of the overview: protection, grace, what the Narrator did to them, deaths it may still cause. */
    private fun summary(runtime: NarratorRuntime, player: ServerPlayer, now: Long): String {
        val guards = runtime.guards
        val state = GuardStates.read(player)
        val parts = buildList {
            if (guards.isProtected(player.uuid)) add("protected")
            state.graceUntil?.takeIf { it > now }?.let { add("under grace until ${time(it, runtime.clock.zone)}") }
            val active = guards.activeOn(player.uuid)
            if (active.isEmpty()) add("nothing of the Narrator's") else add(active.joinToString { "${it.what} (${left(it.until, now)})" })
            val cap = guards.caps().narratorKillsPerHour
            add("${DeathGuard.quotaLeft(state.narratorKills, DeathGuard.playTime(player), cap)} of $cap deaths left")
        }
        return parts.joinToString(" · ")
    }

    /** Everything the guards hold about [player]. */
    private fun inspect(source: CommandSourceStack, player: ServerPlayer): Int {
        val runtime = runningNarrator(source) ?: return 0
        val guards = runtime.guards
        val state = GuardStates.read(player)
        val caps = guards.caps()
        val now = runtime.clock.millis()
        val zone = runtime.clock.zone
        val name = player.plainTextName
        val lines = buildList<Component> {
            add(narratorLine("guards on $name · chaos ${runtime.settings.get().config.chaos} of ${Caps.MAX_CHAOS}"))
            add(detailLine("Protected: ${if (guards.isProtected(player.uuid)) "yes, the Narrator does nothing hostile to them" else "no"}"))
            val died = runtime.players.get(player.uuid)?.lastDeathAt
            if (died != null && now - died < NO_PILE_ON_MS) {
                add(detailLine("Died ${ToolText.duration(now - died)} ago: no hostile action until ${time(died + NO_PILE_ON_MS, zone)}"))
            }
            val episode = state.episode?.takeIf { it.until > now }
            add(
                detailLine(
                    episode?.let { "Punishment running until ${time(it.until, zone)} (${left(it.until, now)})${it.act?.let { act -> ", opened by ${actName(act)}" }.orEmpty()}" }
                        ?: "No punishment running",
                ),
            )
            val pacing = guards.pacing(listOf(player.uuid), now).singleOrNull()
            favorLines(state, pacing, jokersHeld(player), now, zone).forEach { add(detailLine(it)) }
            state.effects.forEach { add(detailLine("Effect: ${EffectLedger.describe(it)}, until ${time(it.expiresAt, zone)}${tags(it.harmful, it.turnId)}")) }
            state.modifiers.forEach { add(detailLine("Attribute: ${AttributeLedger.describe(it)}, until ${time(it.expiresAt, zone)}${tags(it.harmful, it.turnId)}")) }
            val names = { uuid: UUID -> runtime.server.playerList.getPlayer(uuid)?.plainTextName ?: uuid.toString() }
            state.confiscations.forEach { add(detailLine(ledgerLine(it, now, zone, names))) }
            val active = guards.activeOn(player.uuid)
            active.filter { it.kind == "mob" }.forEach { add(detailLine("Creatures: ${it.what}, until ${time(it.until, zone)}${tags(it.harmful, null)}")) }
            // A trap's deadline is wall clock (BlockRestorer), like the runtime's clock in game.
            active.filter { it.kind == "trap" }.forEach { add(detailLine("Trap: ${it.what}, until ${time(it.until, zone)} (${left(it.until, now)})")) }
            val confiscated = state.confiscations.any { it.purpose == LedgerPurpose.CONFISCATION }
            if (state.effects.isEmpty() && state.modifiers.isEmpty() && !confiscated && active.none { it.kind == "mob" || it.kind == "trap" }) {
                add(detailLine("Nothing of the Narrator's on them."))
            }
            val play = DeathGuard.playTime(player)
            val recent = DeathGuard.recentKills(state.narratorKills, play)
            val cap = caps.narratorKillsPerHour
            val next = recent.minOrNull()?.takeIf { recent.size >= cap && cap > 0 }?.let { first ->
                "; the next is allowed in ${ToolText.wait((first + DeathGuard.HOUR_TICKS - play) * MS_PER_TICK)} of play"
            }.orEmpty()
            add(detailLine("Deaths caused by the Narrator in the last played hour: ${recent.size} of $cap$next"))
            add(
                detailLine(
                    if (state.armedUntil > play) {
                        "Death guard: armed for ${ToolText.wait((state.armedUntil - play) * MS_PER_TICK)} of play"
                    } else {
                        "Death guard: not armed"
                    },
                ),
            )
            if (state.protectedDeath) add(detailLine("Died under the death guard: keeps the inventory at respawn"))
        }
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    /**
     * What limits the Narrator with the player of [state] (M4 §18.1): the pace the Director sees ([pacing]: punished
     * this hour, categories resting, offerings waiting for an answer), their grace, and the jokers given in the last
     * day, with the [held] ones in their inventory. Pure: [now] and [zone] say the time.
     */
    internal fun favorLines(state: GuardState, pacing: PlayerPacing?, held: Int, now: Long, zone: ZoneId): List<String> = buildList {
        val text = TraceText(zone, now)
        fun at(epochMs: Long) = text.time(Instant.ofEpochMilli(epochMs))
        if (pacing != null) {
            val resting = pacing.resting.entries.sortedBy { it.value }.joinToString { (category, until) -> "${category.id} until ${at(until)}" }
            val offerings = pacing.offerings.joinToString { "${Escrow.ref(it.id)} until ${at(it.returnAt)}" }
            add(
                "Pace: punished ${pacing.punishedThisHour} of ${pacing.perHour} this hour · categories resting: ${resting.ifEmpty { "none" }}" +
                    if (offerings.isEmpty()) "" else " · offerings waiting: $offerings",
            )
        }
        val grace = state.graceUntil?.takeIf { it > now }
        val last = state.lastGraceEnd ?: state.graceUntil
        val next = last?.plus(GRACE_GAP_MS)?.takeIf { it > now }
        add(
            "Grace: " + when {
                grace != null -> "until ${at(grace)} (${left(grace, now)}): the Narrator does nothing hostile to them" + next?.let { "; the next from ${at(it)}" }.orEmpty()
                last != null && next != null -> "none; the last ended at ${at(last)}, the next is allowed from ${at(next)}"
                else -> "none"
            },
        )
        val given = state.jokersGivenAt.filter { now - it < Jokers.DAY_MS }.sorted()
        val nextJoker = given.takeIf { it.size >= MechanicsLimits.JOKERS_PER_DAY }?.let { it[it.size - MechanicsLimits.JOKERS_PER_DAY] + Jokers.DAY_MS }
        add(
            "Jokers: " + (if (given.isEmpty()) "none given in the last 24 h" else "${given.size} given in the last 24 h (${given.joinToString { at(it) }})") +
                nextJoker?.let { "; the next is allowed from ${at(it)}" }.orEmpty() + " · $held in their inventory",
        )
    }

    /**
     * One entry of a player's ledger (M4 §5.6, §7.2) and when it comes back: a confiscation of the Narrator, a stake
     * on a challenge, an offering, or what they are owed. [name] names a winner from their UUID. Pure.
     */
    internal fun ledgerLine(entry: Confiscation, now: Long, zone: ZoneId, name: (UUID) -> String): String {
        val text = TraceText(zone, now)
        fun at(epochMs: Long) = text.time(Instant.ofEpochMilli(epochMs))
        val due = entry.returnAt <= now
        val challenge = entry.canon?.let { Ref(RefKind.CANON, it).toString() }
        return when (entry.purpose) {
            LedgerPurpose.CONFISCATION -> {
                val back = if (due) "due, waiting for room in the inventory" else "back at ${at(entry.returnAt)} (${left(entry.returnAt, now)})"
                "Confiscation #${entry.id}: ${entry.what}, $back" + entry.reason?.let { " · reason \"$it\"" }.orEmpty() + tags(null, entry.turnId)
            }
            LedgerPurpose.STAKE -> "Stake #${entry.id}" + challenge?.let { " on $it" }.orEmpty() + ": ${entry.what}, " + when {
                due -> "due back, waiting for room in the inventory"
                entry.owedTo != null -> "lost to ${name(entry.owedTo)}, handed over once both are online; back to its owner at ${at(entry.returnAt)} otherwise"
                else -> "settled when ${challenge ?: "its challenge"} ends; back at ${at(entry.returnAt)} at the latest"
            }
            LedgerPurpose.OFFERING -> "Offering ${Escrow.ref(entry.id)}: ${entry.what}, " +
                if (due) "not taken, going back as soon as there is room" else "waiting for the Director's answer until ${at(entry.returnAt)} (${left(entry.returnAt, now)})"
            LedgerPurpose.OWED -> "Owed #${entry.id}: ${entry.what}" + challenge?.let { ", won on $it" }.orEmpty() + ", waiting for room in the inventory"
        }
    }

    /** The jokers in [player]'s inventory, armor and off hand included. Server thread. */
    private fun jokersHeld(player: ServerPlayer): Int {
        val inventory = player.inventory
        return (0 until inventory.containerSize).sumOf { slot -> inventory.getItem(slot).takeIf { ItemLedger.isJoker(it) }?.count ?: 0 }
    }

    /** ` · harmful · turn #42`, leaving out what is unknown. */
    private fun tags(harmful: Boolean?, turnId: Long?): String =
        listOfNotNull(harmful?.let { if (it) "harmful" else "beneficial" }, turnId?.let { "turn #$it" }).joinToString("") { " · $it" }

    /** `1m12s left`, or `due`. */
    private fun left(until: Long, now: Long): String = if (until <= now) "due" else "${ToolText.wait(until - now)} left"

    /** An act (`Caller.act`): a Director turn, or minus a firing of the mechanics engine (M4 §4.3). */
    private fun actName(act: Long): String = if (act >= 0) "turn #$act" else "the engine's act #${-act}"

    private fun time(epochMs: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    private const val MAX_PLAYERS = 15
    private const val MS_PER_TICK = 50L
    private const val NO_PILE_ON_MS = TargetingPolicy.NO_PILE_ON_MINUTES * 60_000L
    private const val GRACE_GAP_MS = MechanicsLimits.GRACE_GAP_MINUTES * 60_000L
}
