package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.ForfeitSpec
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.action.ApplyEffectArgs
import io.github.clemsix6.narrator2.tool.action.FavorArgs
import io.github.clemsix6.narrator2.tool.action.FavorKind
import io.github.clemsix6.narrator2.tool.action.GiveItemArgs
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The stakes of the challenges (M4 §5.3, M4-17): checked by their tools' dry checks when a challenge starts, as the
 * Director; paid when it ends in one act of the engine per player, to the players online only, each refusal said in
 * the line, each stake paid kept in the player's history. The four tools are fakes with the real arguments: what
 * they do in the world is `ChallengeStakesGameTests`.
 */
class StakesTest {
    private val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening))
    private val memory = FakeMemory(clock)
    private val guards = FakeGuards()
    private val players = PlayerStates()
    private val services = TestServices(clock = clock, players = players, memory = memory, guards = guards)
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val josiane = MechanicsSamples.josiane

    /** One run of a fake tool: who called it, for whom, with what arguments. */
    private data class Run(val tool: String, val caller: Caller, val player: String, val args: Any)

    private val runs = CopyOnWriteArrayList<Run>()
    private val dryChecks = CopyOnWriteArrayList<Pair<String, Caller>>()

    /** What a tool's dry checks answer (null: they pass), by tool. */
    private val dry = ConcurrentHashMap<String, ToolResult>()

    /** What a run answers, by tool and player; `ok: <tool> for <player>` when not set. */
    private val answers = ConcurrentHashMap<Pair<String, String>, ToolResult>()

    private inner class FakeTool<A : Any>(name: String, args: KSerializer<A>, private val playerOf: (A) -> String) :
        NarratorTool<A>(name, "A fake $name.", args, ToolEffect.WORLD) {
        override suspend fun check(ctx: ToolContext, args: A): ToolResult? {
            dryChecks += name to ctx.caller
            return dry[name]
        }

        override suspend fun run(ctx: ToolContext, args: A): ToolResult {
            val player = playerOf(args)
            runs += Run(name, ctx.caller, player, args)
            return answers[name to player] ?: ToolResult.ok("ok: $name for $player\nsecond line", listOfNotNull(players.byName(player)?.ref))
        }
    }

    private val actions = EngineActions(
        services,
        listOf(
            FakeTool("give_item", GiveItemArgs.serializer()) { it.player },
            FakeTool("apply_effect", ApplyEffectArgs.serializer()) { it.players.single() },
            FakeTool("favor", FavorArgs.serializer()) { it.player },
            FakeTool("punish", PunishArgs.serializer()) { it.player },
        ),
    )
    private val stakes = Stakes(services, actions)

    init {
        online(bartok)
        online(mimosa)
    }

    private fun online(player: PlayerRef) = players.put(
        LivePlayer(
            ref = player, sessionId = 1, joinedAt = clock.millis(), isOp = false, gameMode = "survival", pos = Pos("minecraft:overworld", 0, 64, 0),
            biome = null, structure = null, underground = false, health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null,
            lastMoveAt = clock.millis(), idle = false,
        ),
    )

    private fun participant(player: PlayerRef, late: Boolean = false) = Participant(player.uuid.toString(), player.name, late = late)

    /** C-50, the turtle egg, between Bartok, Mimosa and Josiane, with [reward] and [penalty]. */
    private fun challenge(reward: RewardSpec?, penalty: ForfeitSpec?) = MechanicsSamples.runningChallenge.copy(
        spec = MechanicsSamples.turtleEgg.copy(reward = reward, penalty = penalty),
        participants = listOf(participant(bartok), participant(mimosa), participant(josiane)),
    )

    private fun pay(challenge: ChallengeState, winners: List<PlayerRef>, losers: List<PlayerRef>): List<String> = runBlocking {
        stakes.pay(challenge, winners.map { participant(it) }, losers.map { participant(it) })
    }

    private fun refusal(reward: RewardSpec?, penalty: ForfeitSpec?): MechanicRejected =
        assertFailsWith<MechanicRejected> { runBlocking { stakes.check(reward, penalty, MechanicsSamples.director) } }

    private val interactions: List<Interaction> get() = memory.store.rows.values.filterIsInstance<Interaction>().sortedBy { it.ref.id }

    private fun decisions(name: String) = services.traces.decisions.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

    // ── When a challenge starts ──

    @Test
    fun `each stake passes the dry checks of its tool, as the Director, and nothing runs`() = runBlocking<Unit> {
        stakes.check(MechanicsSamples.goldenApple, MechanicsSamples.tinyForfeit, MechanicsSamples.director)
        stakes.check(MechanicsSamples.grace, null, MechanicsSamples.director)
        stakes.check(MechanicsSamples.joker, MechanicsSamples.slowHandsForfeit, MechanicsSamples.director)
        stakes.check(null, null, MechanicsSamples.director)

        assertEquals(listOf("give_item", "punish", "favor", "favor", "punish"), dryChecks.map { it.first })
        assertTrue(dryChecks.all { it.second == MechanicsSamples.director }, "the Director's caller: $dryChecks")
        assertTrue(runs.isEmpty() && services.traces.decisions.isEmpty(), "a check runs nothing and records nothing")
    }

    @Test
    fun `a stake its tool refuses refuses the challenge, its rule kept`() {
        dry["give_item"] = ToolResult.denied("precious_limit", "5x diamond is worth 5 precious points, more than the 4 a player may get in an hour")
        val precious = refusal(RewardSpec(RewardKind.ITEM, item = "diamond", count = 5), null)
        assertEquals("precious_limit", precious.rule)
        assertEquals(
            "DENIED (precious_limit): reward: give_item refuses 5x diamond: 5x diamond is worth 5 precious points, more than the 4 a player may get in an hour",
            precious.toResult().text,
        )

        dry["give_item"] = ToolResult.invalid("netherite_block is never given: …")
        assertEquals(
            "Invalid arguments: reward: give_item refuses 1x netherite_block: netherite_block is never given: …",
            refusal(RewardSpec(RewardKind.ITEM, item = "netherite_block"), null).toResult().text,
        )

        dry.remove("give_item")
        dry["punish"] = ToolResult.denied("chaos_0", "the chaos level is 0: no hostile action against any player.")
        assertEquals(
            "DENIED (chaos_0): penalty: punish refuses tiny (2): the chaos level is 0: no hostile action against any player.",
            refusal(MechanicsSamples.goldenApple, MechanicsSamples.tinyForfeit).toResult().text,
        )
    }

    @Test
    fun `a reward has what its kind needs, an effect is beneficial, and a forfeit is severity 1 or 2`() {
        assertEquals(
            "Invalid arguments: reward: an item reward needs item: the id of what each winner gets, like golden_apple",
            refusal(RewardSpec(RewardKind.ITEM), null).toResult().text,
        )
        assertEquals(
            "Invalid arguments: reward: an effect reward needs effect: a beneficial effect, like speed or regeneration",
            refusal(RewardSpec(RewardKind.EFFECT, effect = " "), null).toResult().text,
        )
        assertEquals(
            "Invalid arguments: reward: apply_effect refuses poison I for 2m: poison is not a beneficial effect, and a reward only gives one " +
                "(speed, regeneration, haste, luck…)",
            refusal(RewardSpec(RewardKind.EFFECT, effect = "minecraft:poison"), null).toResult().text,
        )
        assertTrue(refusal(RewardSpec(RewardKind.EFFECT, effect = "glowing"), null).message!!.contains("glowing is not a beneficial effect"), "neutral is no reward")
        runBlocking { stakes.check(MechanicsSamples.speed, null, MechanicsSamples.director) }
        assertEquals(listOf("apply_effect"), dryChecks.map { it.first }, "only a beneficial effect reaches the tool")

        assertEquals(
            "Invalid arguments: penalty: a challenge's penalty is a forfeit, severity 1 or 2 (asked 4)",
            refusal(null, ForfeitSpec(PunishmentId.SMITE, severity = 4)).toResult().text,
        )
    }

    // ── When it ends ──

    @Test
    fun `the stakes are paid in one act per player, to the players online, and kept in their history`() {
        val segments = pay(challenge(MechanicsSamples.speed, MechanicsSamples.tinyForfeit), listOf(mimosa), listOf(bartok, josiane))

        assertEquals(listOf("reward: Mimosa got speed II for 5m", "penalty: Bartok tiny (2) until 18:02, Josiane offline, not paid"), segments)
        val opened = decisions("stake")
        assertEquals(listOf("Mimosa", "Bartok"), opened.map { it.subject }, "one act per player online, none for Josiane")
        assertEquals(
            listOf("C-50 reward to Mimosa: speed II for 5m", "C-50 penalty to Bartok: tiny (2)"),
            opened.map { it.reason },
        )
        assertTrue(opened.all { it.conversationId == "mechanics:C-50" && it.turnId == null })
        assertEquals(listOf("reward", "penalty"), opened.map { it.detail!!.getValue("stake").jsonPrimitive.content })
        assertEquals(
            opened.map { Caller(io.github.clemsix6.narrator2.llm.Role.DIRECTOR, "mechanics:C-50", null, null, firingId = it.id) },
            runs.map { it.caller },
            "each stake runs as its own act",
        )
        assertEquals(opened.map { -it.id }, decisions("action").map { it.detail!!.getValue("act").jsonPrimitive.long })

        assertEquals(ApplyEffectArgs(listOf("Mimosa"), "speed", 300, 2), runs[0].args)
        assertEquals(PunishArgs("Bartok", PunishmentId.TINY, 2, "lost the challenge C-50"), runs[1].args)

        val (blessing, punishment) = interactions
        assertEquals(
            listOf(mimosa.uuid to InteractionKind.BLESSING, bartok.uuid to InteractionKind.PUNISHMENT),
            interactions.map { it.player.uuid to it.kind },
        )
        assertEquals("challenge reward: apply_effect for Mimosa", blessing.summary, "the tool's first line")
        assertEquals("challenge penalty: punish for Bartok", punishment.summary)
        assertTrue(interactions.all { it.valence == 0 && it.canon == MechanicsSamples.canon(50) && it.source == Source.ENGINE })
        assertTrue(memory.store.writes.all { it.endsWith("by engine") }, "${memory.store.writes}")
    }

    @Test
    fun `a stake refused is said in the line, kept out of the history, and the others are still paid`() {
        answers["favor" to "Bartok"] = ToolResult.denied(
            "favor_limit",
            "Bartok got a joker 5h00m ago, and a player gets 1 a day at most; the next one is allowed at 10-20 10:10 (in 19h00m)",
        )
        answers["punish" to "Mimosa"] = ToolResult.failed("Mimosa is dead (on the respawn screen); nothing was done")

        val segments = pay(challenge(MechanicsSamples.joker, MechanicsSamples.slowHandsForfeit), listOf(bartok, mimosa), emptyList()) +
            pay(challenge(MechanicsSamples.joker, MechanicsSamples.slowHandsForfeit), emptyList(), listOf(mimosa))

        assertEquals(
            listOf(
                "reward: Bartok refused (favor_limit: Bartok got a joker 5h00m ago, and a player gets 1 a day at most; the next one is allowed at " +
                    "10-20 10:10 (in 19h00m)), Mimosa got a joker",
                "penalty: Mimosa not paid (FAILED: Mimosa is dead (on the respawn screen); nothing was done)",
            ),
            segments,
        )
        assertEquals(FavorArgs("Mimosa", FavorKind.JOKER), runs[1].args, "a joker by favor, without offering")
        assertEquals(listOf(mimosa.uuid to InteractionKind.BLESSING), interactions.map { it.player.uuid to it.kind }, "only what was paid")
    }

    @Test
    fun `each reward goes to its tool with the arguments the model would write`() {
        val decoded = listOf(
            MechanicsSamples.namedPickaxe to GiveItemArgs.serializer(),
            MechanicsSamples.speed to ApplyEffectArgs.serializer(),
            MechanicsSamples.grace to FavorArgs.serializer(),
            MechanicsSamples.joker to FavorArgs.serializer(),
        ).map { (reward, serializer) -> decode(serializer, Stakes.rewardArgs(reward, "Mimosa")) }
        assertEquals(
            listOf(
                GiveItemArgs("Mimosa", "diamond_pickaxe", 1, name = "La Pioche du Pardon"),
                ApplyEffectArgs(listOf("Mimosa"), "speed", 300, 2),
                FavorArgs("Mimosa", FavorKind.GRACE, minutes = 15),
                FavorArgs("Mimosa", FavorKind.JOKER),
            ),
            decoded,
        )
        assertEquals(
            PunishArgs("Bartok", PunishmentId.SLOW_HANDS, 1, "lost the duel C-52"),
            decode(PunishArgs.serializer(), Stakes.penaltyArgs(MechanicsSamples.slowHandsForfeit, "Bartok", "lost the duel C-52")),
        )

        val paid = pay(challenge(MechanicsSamples.speed, null), listOf(mimosa), listOf(bartok)) + pay(challenge(MechanicsSamples.grace, null), listOf(bartok), emptyList())
        assertEquals(listOf("reward: Mimosa got speed II for 5m", "reward: Bartok got grace for 15m"), paid, "no penalty, no segment")
        assertEquals(listOf(InteractionKind.BLESSING, InteractionKind.BLESSING), interactions.map { it.kind })
    }

    @Test
    fun `a duel pays its winner's reward only, and a challenge without stakes pays nothing`() {
        val duel = MechanicsSamples.invitedDuel.copy(
            duel = MechanicsSamples.duel.copy(reward = MechanicsSamples.joker),
            participants = listOf(participant(bartok), participant(mimosa)),
        )
        assertEquals(listOf("reward: Mimosa got a joker"), pay(duel, listOf(mimosa), listOf(bartok)))
        assertEquals("duel reward: favor for Mimosa", interactions.single().summary)
        assertEquals(MechanicsSamples.canon(52), interactions.single().canon)

        assertEquals(emptyList(), pay(challenge(null, null), listOf(mimosa), listOf(bartok)))
        assertEquals(emptyList(), pay(challenge(MechanicsSamples.goldenApple, MechanicsSamples.tinyForfeit), emptyList(), emptyList()), "nobody to pay")
        assertEquals(1, decisions("stake").size, "no act without a stake to pay")
    }

    @Test
    fun `without the memory the stakes are still paid, only not kept`() {
        memory.available = false
        assertEquals(
            listOf("reward: Mimosa got a joker", "penalty: Bartok tiny (2) until 18:02"),
            pay(challenge(MechanicsSamples.joker, MechanicsSamples.tinyForfeit), listOf(mimosa), listOf(bartok)),
        )
        assertTrue(interactions.isEmpty())
        assertEquals(2, runs.size)
    }

    /**
     * M4 review, finding 36: `give_item` drops what finds no room at the player's feet, where it burns or vanishes. An
     * item reward passes `give_item`'s dry checks as its act, then goes through the ledger (`GameTest`s), never through
     * the tool's run; its act's `action` decision is written all the same.
     */
    @Test
    fun `an item reward passes give_item's checks as its act, and never its run`() {
        dry["give_item"] = ToolResult.denied("precious_limit", "1x golden_apple is worth more than you think")

        val segments = pay(challenge(MechanicsSamples.goldenApple, null), listOf(mimosa), emptyList())

        assertEquals(listOf("reward: Mimosa refused (precious_limit: 1x golden_apple is worth more than you think)"), segments)
        assertTrue(runs.isEmpty(), "give_item never runs: $runs")
        val act = decisions("stake").single()
        assertEquals("give_item" to Caller(io.github.clemsix6.narrator2.llm.Role.DIRECTOR, "mechanics:C-50", null, null, firingId = act.id), dryChecks.single())
        val action = decisions("action").single()
        assertEquals("give_item denied: DENIED (precious_limit): 1x golden_apple is worth more than you think", action.reason)
        assertEquals(-act.id, action.detail!!.getValue("act").jsonPrimitive.long)
        assertEquals("mechanics:C-50", action.conversationId)
        assertTrue(interactions.isEmpty(), "nothing given, nothing kept")
    }

    private fun <A : Any> decode(serializer: KSerializer<A>, json: JsonObject): A {
        val decoded = ToolArgs.decode(serializer, json.toString())
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "$json: $decoded")
        return decoded.args
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
