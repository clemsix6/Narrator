package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.ForfeitSpec
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentCatalog
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.action.shortId
import io.github.clemsix6.narrator2.tool.answer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.effect.MobEffectCategory

/**
 * The stakes of the challenges and the duels (M4 §5.3), paid by the engine through the very tools of the Director
 * ([EngineActions]): a reward (`give_item`, `apply_effect`, `favor` grace or joker) to each winner, a forfeit
 * (`punish`, severity 1 or 2) to each loser enrolled at the start. Same checks, caps, guards and server limits as
 * the Director's own calls.
 *
 * - **When a challenge starts** ([check]): each stake passes the dry checks of its tool, as the Director: an unknown
 *   or forbidden item, an item too precious for anyone, an effect that is not beneficial, a penalty at chaos 0 are
 *   refused at once, with the fix.
 * - **When it ends** ([pay]): one act of the engine per player (its own `mechanic stake` decision, its own episode
 *   for the guards), to the players online only. A stake refused (rationing, `favor_limit`, the guards of the
 *   punishments, a dead player) is said in the line, never retried. Each stake paid enters the player's history:
 *   an interaction `gift`, `blessing` or `punishment`, valence 0, canon = the challenge, by the engine (acts of
 *   the engine are no `tool_calls`: `Sanctions` never sees them). An item reward is the one stake not paid by its
 *   tool's run: `give_item` drops what finds no room at the player's feet, so the engine gives it through the
 *   ledger after the same checks and limits ([giveItem]; M4 review, finding 36).
 *
 * Off the server thread: the tools go to it themselves.
 */
class Stakes(private val services: NarratorServices, private val actions: EngineActions) : StakePayer {
    private val log = Log.mechanics

    // ── When a challenge starts ──

    override suspend fun check(reward: RewardSpec?, penalty: ForfeitSpec?, caller: Caller) {
        reward?.let { checkReward(it, caller) }
        penalty?.let { checkPenalty(it, caller) }
    }

    /** The fields of [reward]'s kind, a beneficial effect, then its tool's dry checks. Throws [MechanicRejected]. */
    private suspend fun checkReward(reward: RewardSpec, caller: Caller) {
        missing(reward)?.let { throw MechanicRejected(null, "reward: $it") }
        val what = "reward: ${reward.kind.tool} refuses ${ChallengeLines.reward(reward)}"
        if (reward.kind == RewardKind.EFFECT) notBeneficial(reward.effect!!)?.let { throw MechanicRejected(null, "$what: $it") }
        actions.check(reward.kind.tool, rewardArgs(reward, CHECKED_PLAYER), caller)?.let { throw refusal(what, it) }
    }

    /** A forfeit's severity, then `punish`'s dry checks (chaos 0, the severity the chaos level allows). Throws [MechanicRejected]. */
    private suspend fun checkPenalty(penalty: ForfeitSpec, caller: Caller) {
        if (penalty.severity !in 1..MechanicsLimits.PENALTY_MAX_SEVERITY) {
            throw MechanicRejected(null, "penalty: a challenge's penalty is a forfeit, severity 1 or ${MechanicsLimits.PENALTY_MAX_SEVERITY} (asked ${penalty.severity})")
        }
        val args = penaltyArgs(penalty, CHECKED_PLAYER, "lost a challenge")
        actions.check(PUNISH, args, caller)?.let { throw refusal("penalty: punish refuses ${forfeit(penalty)}", it) }
    }

    /** What [reward]'s kind needs and lacks, or null. */
    private fun missing(reward: RewardSpec): String? = when (reward.kind) {
        RewardKind.ITEM -> if (reward.item.isNullOrBlank()) "an item reward needs item: the id of what each winner gets, like golden_apple" else null
        RewardKind.EFFECT -> if (reward.effect.isNullOrBlank()) "an effect reward needs effect: a beneficial effect, like speed or regeneration" else null
        RewardKind.GRACE, RewardKind.JOKER -> null
    }

    /** Why the effect [raw] is no reward (a harmful or neutral one); null when it is beneficial or unknown (its tool says so). */
    private fun notBeneficial(raw: String): String? {
        val effect = parseId(raw)?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) } ?: return null
        if (effect.value().category == MobEffectCategory.BENEFICIAL) return null
        return "${shortId(effect)} is not a beneficial effect, and a reward only gives one (speed, regeneration, haste, luck…)"
    }

    /**
     * A tool's refusal of a stake, for `start_challenge`: `DENIED (precious_limit): reward: give_item refuses 5x
     * diamond: …` keeps its rule; `Invalid arguments: reward: …` otherwise.
     */
    private fun refusal(what: String, result: ToolResult): MechanicRejected {
        val denied = DENIED.matchEntire(result.text)
        return when {
            result.status == ToolStatus.DENIED && denied != null -> MechanicRejected(denied.groupValues[1], "$what: ${denied.groupValues[2]}")
            result.status == ToolStatus.INVALID -> MechanicRejected(null, "$what: ${result.text.removePrefix(INVALID_PREFIX)}")
            else -> MechanicRejected(null, "$what: ${result.text}")
        }
    }

    // ── When it ends ──

    override suspend fun pay(challenge: ChallengeState, winners: List<Participant>, losers: List<Participant>): List<String> {
        val reward = challenge.spec?.reward ?: challenge.duel?.reward
        val penalty = challenge.spec?.penalty
        val segments = mutableListOf<String>()
        if (reward != null && winners.isNotEmpty()) segments += "reward: " + winners.map { safely(challenge, it) { payReward(challenge, it, reward) } }.joinToString()
        if (penalty != null && losers.isNotEmpty()) segments += "penalty: " + losers.map { safely(challenge, it) { payPenalty(challenge, it, penalty) } }.joinToString()
        return segments
    }

    /** [participant]'s reward, in an act of its own: `Mimosa got 1x golden_apple`, `Bartok refused (favor_limit: …)`. */
    private suspend fun payReward(challenge: ChallengeState, participant: Participant, reward: RewardSpec): String {
        val what = ChallengeLines.reward(reward)
        val name = onlineName(participant) ?: return offline(challenge, participant, "reward")
        val result = if (reward.kind == RewardKind.ITEM) {
            giveItem(challenge, participant, name, reward, what)
        } else {
            act(challenge, name, REWARD, reward.kind.tool, reward.kind.id, what, rewardArgs(reward, name))
        }
        if (result.status != ToolStatus.OK) return "${shown(participant)} ${notPaid(result)}"
        val kind = if (reward.kind == RewardKind.ITEM) InteractionKind.GIFT else InteractionKind.BLESSING
        remember(challenge, participant, kind, REWARD, result, what)
        return "${shown(participant)} got $what"
    }

    /** [participant]'s forfeit, in an act of its own: `Bartok tiny (2) until 20:13`, `Bartok refused (already_punished: …)`. */
    private suspend fun payPenalty(challenge: ChallengeState, participant: Participant, penalty: ForfeitSpec): String {
        val what = forfeit(penalty)
        val name = onlineName(participant) ?: return offline(challenge, participant, "penalty")
        val plan = PunishmentCatalog.plan(penalty.punishment, penalty.severity, services.guards.caps(), name)
        val at = services.clock.millis()
        val args = penaltyArgs(penalty, name, "lost the ${kindOf(challenge)} ${challenge.ref}")
        val result = act(challenge, name, PENALTY, PUNISH, penalty.punishment.id, what, args)
        if (result.status != ToolStatus.OK) return "${shown(participant)} ${notPaid(result)}"
        remember(challenge, participant, InteractionKind.PUNISHMENT, PENALTY, result, what)
        val until = if (plan.lasting) " until ${ChallengeLines.hourMinute(at + plan.seconds * 1_000L, services.clock)}" else ""
        return "${shown(participant)} $what$until"
    }

    /**
     * One act of the engine for [challenge]'s [stake] (`reward` or `penalty`) to [name]: its `mechanic stake`
     * decision, then [tool] with [args] as that act.
     */
    private suspend fun act(challenge: ChallengeState, name: String, stake: String, tool: String, kind: String, what: String, args: JsonObject): ToolResult =
        actions.run(open(challenge, name, stake, tool, kind, what), tool, args)

    /** The act of [challenge]'s [stake] to [name]: its `mechanic stake` decision. */
    private fun open(challenge: ChallengeState, name: String, stake: String, tool: String, kind: String, what: String): Firing {
        val detail = buildJsonObject {
            put("stake", stake)
            put("kind", kind)
            put("tool", tool)
        }
        return actions.open(STAKE, challenge.ref, name, detail, reason = "${challenge.ref} $stake to ${ModelText.name(name)}: $what", level = Log.Level.INFO)
    }

    /**
     * An item reward to [participant] ([name] online), in an act of its own (M4 review, finding 36): `give_item`'s own
     * dry checks (the item, how precious it is) and the server's limits of the gifts, as for the Director's gifts; then
     * into the inventory through the ledger ([io.github.clemsix6.narrator2.guard.ItemLedger.owe]), like a joker: what
     * finds no room waits there and comes once there is room, never on the ground, where it would burn or vanish
     * ([EngineActions.runInstead]: the act's `mechanic action` decision is the one of a tool's call).
     */
    private suspend fun giveItem(challenge: ChallengeState, participant: Participant, name: String, reward: RewardSpec, what: String): ToolResult {
        val firing = open(challenge, name, REWARD, GIVE_ITEM, reward.kind.id, what)
        return actions.runInstead(firing, GIVE_ITEM, rewardArgs(reward, name)) {
            try {
                services.server.onServerThread { answer { intoLedger(challenge, participant, reward) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
    }

    /** [reward] built as `give_item` builds it, counted by the server's limits of the gifts, owed to [participant]. Server thread. */
    private fun intoLedger(challenge: ChallengeState, participant: Participant, reward: RewardSpec): ToolResult {
        val server = services.server
        val player = server.playerList.getPlayer(participant.playerId) ?: return ToolResult.failed("${shown(participant)} is not online")
        val spec = ItemSpecs.resolve(server.registryAccess(), player.level().enabledFeatures(), reward.item.orEmpty(), reward.count, reward.name, null, null, false)
        val ref = player.ref()
        val gift = "${spec.count}x ${shortId(spec.item)}"
        val verdict = services.toolLimits.gifts.take(ref, ItemSpecs.preciousness(spec.item) * spec.count, gift)
        if (verdict is Verdict.Denied) return ToolResult.denied(verdict)
        val waiting = services.guards.items.owe(player, listOf(ItemSpecs.build(spec, null)), "the reward of the ${kindOf(challenge)} ${challenge.ref}")
        val where = if (waiting.isEmpty()) "" else "; it waits in their ledger until their inventory has room"
        return ToolResult.ok("ok: gave ${ItemSpecs.describe(spec)} to ${ModelText.name(ref.name)}$where", listOf(ref))
    }

    /** One player's stake, whatever happens: an error is logged and said, and the next players are paid. */
    private suspend fun safely(challenge: ChallengeState, participant: Participant, pay: suspend () -> String): String = try {
        pay()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("${challenge.ref}: the stake of ${participant.name} failed", e)
        "${shown(participant)} not paid (an error: ${ModelText.cut(e.message ?: e.javaClass.simpleName, DETAIL_MAX)})"
    }

    /** A participant offline when [challenge] ends gets nothing (M4 §5.3): said in the line. */
    private fun offline(challenge: ChallengeState, participant: Participant, stake: String): String {
        log.info("${challenge.ref}: ${participant.name} is offline, their $stake is not paid")
        return "${shown(participant)} offline, not paid"
    }

    /** The interaction of a stake paid (M4 §5.3): valence 0, summary from the tool's first line, canon = the challenge, by the engine. */
    private suspend fun remember(challenge: ChallengeState, participant: Participant, kind: InteractionKind, stake: String, result: ToolResult, what: String) {
        if (!services.memory.available) {
            log.warn("${challenge.ref}: the memory is unavailable, ${participant.name}'s $stake is not kept in their history")
            return
        }
        val head = "${kindOf(challenge)} $stake: "
        val told = result.text.lineSequence().first().removePrefix(OK_PREFIX)
        val author = Author(Source.ENGINE, conversationId = EngineActions.conversationOf(challenge.ref))
        val store = services.memory.store
        fun draft(summary: String) = InteractionDraft(participant.playerId, kind, 0, ModelText.cut(summary, MemoryLimits.INTERACTION_SUMMARY), canon = challenge.ref)
        try {
            try {
                store.addInteraction(draft(head + told), author)
            } catch (e: MemoryRejected) {
                log.info("${challenge.ref}: the memory refused the summary of ${participant.name}'s $stake (${e.message}); kept in the engine's words")
                store.addInteraction(draft(head + what), author)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("${challenge.ref}: ${participant.name}'s $stake is not kept in their history: ${e.message}")
        }
    }

    /** The name [participant] goes by online now; null when offline. */
    private fun onlineName(participant: Participant): String? = services.players.get(participant.playerId)?.ref?.name

    companion object {
        /** The decision of each stake's act (M4 §19). */
        const val STAKE = "stake"

        /** The two kinds of stakes, in the decisions and the interactions' summaries. */
        const val REWARD = "reward"
        const val PENALTY = "penalty"

        private const val PUNISH = "punish"
        private const val GIVE_ITEM = "give_item"

        /** The player named in the dry checks: none of them looks at the players (M4-03). */
        private const val CHECKED_PLAYER = "player"

        /** A refusal's text in the line, at most. */
        private const val DETAIL_MAX = 160

        private const val OK_PREFIX = "ok: "
        private const val INVALID_PREFIX = "Invalid arguments: "
        private val DENIED = Regex("""DENIED \(([^)]*)\): (.*)""", RegexOption.DOT_MATCHES_ALL)

        /** The arguments of the tool that pays [reward] to [player], as the model would write them. */
        fun rewardArgs(reward: RewardSpec, player: String): JsonObject = buildJsonObject {
            when (reward.kind) {
                RewardKind.ITEM -> {
                    put("player", player)
                    put("item", reward.item)
                    put("count", reward.count)
                    reward.name?.let { put("name", it) }
                }
                RewardKind.EFFECT -> {
                    putJsonArray("players") { add(player) }
                    put("effect", reward.effect)
                    put("seconds", reward.seconds)
                    put("level", reward.level)
                }
                RewardKind.GRACE -> {
                    put("player", player)
                    put("favor", "grace")
                    put("minutes", reward.minutes)
                }
                RewardKind.JOKER -> {
                    put("player", player)
                    put("favor", "joker")
                }
            }
        }

        /** The arguments of `punish` for [penalty] on [player], with [reason] (the engine's words, for their history). */
        fun penaltyArgs(penalty: ForfeitSpec, player: String, reason: String): JsonObject = buildJsonObject {
            put("player", player)
            put("punishment", penalty.punishment.id)
            put("severity", penalty.severity)
            put("reason", reason)
        }

        /** `tiny (2)`. */
        private fun forfeit(penalty: ForfeitSpec): String = "${penalty.punishment.id} (${penalty.severity})"

        private fun kindOf(challenge: ChallengeState): String = if (challenge.isDuel) "duel" else "challenge"

        private fun shown(participant: Participant): String = ModelText.name(participant.name)

        /** A stake not paid, after the player's name: `refused (favor_limit: …)` for a rule, `not paid (FAILED: …)` otherwise. */
        private fun notPaid(result: ToolResult): String {
            val first = result.text.lineSequence().first()
            val denied = DENIED.matchEntire(first)
            return if (result.status == ToolStatus.DENIED && denied != null) {
                "refused (${denied.groupValues[1]}: ${ModelText.cut(denied.groupValues[2], DETAIL_MAX)})"
            } else {
                "not paid (${ModelText.cut(first, DETAIL_MAX)})"
            }
        }
    }
}
