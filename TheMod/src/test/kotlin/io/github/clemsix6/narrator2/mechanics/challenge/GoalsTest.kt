package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.config.MechanicsConfig
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.OutReason
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.PlaceSpec
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The goals of the challenges (M4 §5.2): what each requires, how participants progress, the order of the
 * winners, `winners` 0, 1 and N, the elimination of `survive`, the deadline, the judgment; and the lines the
 * Director reads (M4 §4.4, §4.5).
 */
class GoalsTest {
    private val start = MechanicsSamples.evening
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val josiane = MechanicsSamples.josiane
    private val clock = TestClock(Instant.ofEpochMilli(start), ZoneId.of("Europe/Paris"))

    private fun participants(vararg players: PlayerRef) = players.map { Participant(it.uuid.toString(), it.name) }

    private fun running(spec: ChallengeSpec, vararg players: PlayerRef = arrayOf(bartok, mimosa)) = ChallengeState(
        id = 50,
        spec = spec,
        status = ChallengeStatus.RUNNING,
        createdAt = start,
        startedAt = start,
        endsAt = start + spec.minutes * MINUTE,
        participants = participants(*players),
        turnId = 42,
        conversationId = "director-c13",
    )

    private fun winners(verdict: Verdict?): List<String>? = (verdict as? Verdict.Ends)?.winners?.map { it.name }

    // ── What a goal requires ──

    @Test
    fun `each goal names the fields it lacks, and the samples of each goal hold`() {
        MechanicsSamples.challenges.forEach { assertNull(Goals.shape(it), it.title) }
        val egg = MechanicsSamples.turtleEgg
        assertEquals("goal obtain needs item: the item id or #tag to hold, with count", Goals.shape(egg.copy(item = null)))
        assertEquals("goal kill needs entity: a creature id or #tag, with count", Goals.shape(MechanicsSamples.spiders.copy(entity = " ")))
        assertEquals(Goals.KILL_PLAYERS, Goals.shape(MechanicsSamples.spiders.copy(entity = "Player")))
        assertEquals(Goals.KILL_PLAYERS, Goals.shape(MechanicsSamples.spiders.copy(entity = "minecraft:player")))
        assertEquals("kill cannot target players: a fight between players is a duel (propose_duel)", Goals.KILL_PLAYERS)
        val reach = MechanicsSamples.nether
        assertTrue(Goals.shape(reach.copy(place = null))!!.startsWith("goal reach needs place: "))
        assertTrue(Goals.shape(reach.copy(place = PlaceSpec()))!!.startsWith("goal reach needs place: "))
        assertEquals("place: a point needs x and z (y is optional)", Goals.shape(reach.copy(place = PlaceSpec(x = 12, y = 64))))
        assertEquals("place: a point needs x and z (y is optional)", Goals.shape(reach.copy(place = PlaceSpec(y = 64))))
        assertNull(Goals.shape(reach.copy(place = PlaceSpec(x = 12, z = -40))))
        assertEquals(
            "place: y_above 100 is above y_below 40: nobody could stand between",
            Goals.shape(reach.copy(place = PlaceSpec(yAbove = 100, yBelow = 40))),
        )
        val riddle = MechanicsSamples.riddle
        assertEquals("goal answer needs answers: the accepted answers", Goals.shape(riddle.copy(answers = null)))
        assertEquals("answers[1] has no letter nor digit: write the answer itself", Goals.shape(riddle.copy(answers = listOf("enclume", "?!"))))
    }

    /**
     * The tags and the stacks' components are bound when a world loads (the GameTests match stacks and tags);
     * the ids are in the bootstrapped registries.
     */
    @Test
    fun `the items and the creatures of a goal are found in the registries, with the closest ids when they are not`() {
        assertTrue(Goals.items("Turtle_Egg").getOrThrow() is Target.Items)
        assertTrue(Goals.items("minecraft:turtle_egg").isSuccess)
        val unknown = Goals.items("turtle_eg").exceptionOrNull()!!.message!!
        assertTrue(unknown.startsWith("unknown item \"turtle_eg\"; closest ids: ") && "turtle_egg" in unknown, unknown)
        assertTrue(Goals.items("#nothing_at_all").exceptionOrNull()!!.message!!.startsWith("unknown item tag \"#nothing_at_all\""))

        val spiders = Goals.creatures("Minecraft:Spider").getOrThrow() as Target.Creatures
        assertTrue(spiders.matches("minecraft:spider") && !spiders.matches("minecraft:cave_spider") && !spiders.matches("not an id"))
        val misspelt = Goals.creatures("spidr").exceptionOrNull()!!.message!!
        assertTrue(misspelt.startsWith("unknown creature \"spidr\"; closest ids: ") && "spider" in misspelt, misspelt)
        assertTrue(Goals.creatures("#spooky").exceptionOrNull()!!.message!!.startsWith("unknown creature tag \"#spooky\"; closest tags: "))
    }

    // ── How participants progress ──

    @Test
    fun `obtain keeps the most held, and a participant is done once they hold the count at once`() {
        var c = running(MechanicsSamples.turtleEgg.copy(count = 3))
        c = Goals.held(c, bartok.uuid, 2, start + 1_000)
        assertEquals(2 to null, c.participants[0].let { it.progress to it.doneAt })
        c = Goals.held(c, bartok.uuid, 1, start + 2_000)
        assertEquals(2, c.participants[0].progress, "the most held so far")
        c = Goals.held(c, bartok.uuid, 4, start + 3_000)
        assertEquals(4 to start + 3_000, c.participants[0].let { it.progress to it.doneAt })
        val same = Goals.held(c, bartok.uuid, 0, start + 4_000)
        assertSame(c, same, "nothing changes for a participant who made it and drops the eggs")
        assertSame(c, Goals.held(c, josiane.uuid, 9, start), "nothing for someone who does not take part")
    }

    @Test
    fun `two participants who make it in the same moment keep the order they were seen in`() {
        var c = running(MechanicsSamples.turtleEgg)
        c = Goals.held(c, mimosa.uuid, 1, start + 5_000)
        c = Goals.held(c, bartok.uuid, 1, start + 5_000)
        assertEquals(listOf(start + 5_001, start + 5_000), c.participants.map { it.doneAt }, "Bartok first in the list, Mimosa first seen")
        assertEquals(listOf("Mimosa"), winners(Goals.verdict(c, start + 5_000)))
    }

    @Test
    fun `kill counts the kills up to the count, reach and answer are done at once`() {
        var kill = running(MechanicsSamples.spiders.copy(count = 2, players = null), mimosa)
        kill = Goals.killed(kill, mimosa.uuid, start + 1_000)
        assertEquals(1 to null, kill.participants[0].let { it.progress to it.doneAt })
        kill = Goals.killed(kill, mimosa.uuid, start + 2_000)
        assertEquals(2 to start + 2_000, kill.participants[0].let { it.progress to it.doneAt })
        assertSame(kill, Goals.killed(kill, mimosa.uuid, start + 3_000), "no count past the goal")

        val reach = Goals.reached(running(MechanicsSamples.nether), bartok.uuid, start + 7_000)
        assertEquals(1 to start + 7_000, reach.participants[0].let { it.progress to it.doneAt })
        assertSame(reach, Goals.reached(reach, bartok.uuid, start + 8_000))

        val answer = Goals.answered(running(MechanicsSamples.riddle), mimosa.uuid, start + 9_000)
        assertEquals(listOf(null, start + 9_000), answer.participants.map { it.doneAt })
        assertEquals(0, answer.participants[1].progress)
    }

    @Test
    fun `an eliminated participant never progresses again, and a challenge that is not running never changes`() {
        val naked = Goals.out(running(MechanicsSamples.naked), bartok.uuid, OutReason.DIED)
        assertEquals(OutReason.DIED, naked.participants[0].out)
        assertSame(naked, Goals.out(naked, bartok.uuid, OutReason.ARMOR), "the first reason stays")
        val judging = running(MechanicsSamples.statue).copy(status = ChallengeStatus.JUDGING)
        assertSame(judging, Goals.answered(judging, bartok.uuid, start))
    }

    @Test
    fun `a latecomer joins a challenge open to everyone, never a closed one, and never twice`() {
        val open = running(MechanicsSamples.nether, bartok)
        val joined = Goals.joined(open, josiane.uuid, "Josiane")
        assertEquals(Participant(josiane.uuid.toString(), "Josiane", late = true), joined.participants.last())
        assertSame(joined, Goals.joined(joined, josiane.uuid, "Josiane"))
        assertSame(open, Goals.joined(open, bartok.uuid, "Bartok"), "a participant from the start")
        val closed = running(MechanicsSamples.turtleEgg)
        assertSame(closed, Goals.joined(closed, josiane.uuid, "Josiane"), "named players only")
        val left = Goals.out(running(MechanicsSamples.naked, bartok), bartok.uuid, OutReason.LEFT)
        assertSame(left, Goals.joined(left, bartok.uuid, "Bartok"), "who left a survive stays out")
        assertSame(MechanicsSamples.invitedDuel, Goals.joined(MechanicsSamples.invitedDuel, josiane.uuid, "Josiane"), "a duel takes nobody")
    }

    // ── When it ends ──

    @Test
    fun `winners 1 ends with the first, N with the first N in order, 0 at the deadline with everyone who made it`() {
        val three = arrayOf(bartok, mimosa, josiane)
        var one = running(MechanicsSamples.turtleEgg.copy(players = null), *three)
        assertNull(Goals.verdict(one, start + 60_000))
        one = Goals.held(one, josiane.uuid, 1, start + 60_000)
        assertEquals(listOf("Josiane"), winners(Goals.verdict(one, start + 60_000)))

        var two = running(MechanicsSamples.turtleEgg.copy(players = null, winners = 2), *three)
        two = Goals.held(two, mimosa.uuid, 1, start + 1_000)
        assertNull(Goals.verdict(two, start + 1_000), "one of two")
        two = Goals.held(two, bartok.uuid, 1, start + 2_000)
        two = Goals.held(two, josiane.uuid, 1, start + 2_000)
        assertEquals(listOf("Mimosa", "Bartok"), winners(Goals.verdict(two, start + 2_000)), "the first two, in the order they made it")

        var all = running(MechanicsSamples.nether, *three) // winners 0, open to everyone
        all = Goals.reached(all, josiane.uuid, start + 1_000)
        all = Goals.reached(all, bartok.uuid, start + 2_000)
        assertNull(Goals.verdict(all, all.endsAt - 1), "everyone who makes it before the end")
        assertEquals(listOf("Josiane", "Bartok"), winners(Goals.verdict(all, all.endsAt)))
        val nobody = running(MechanicsSamples.nether)
        assertEquals(Verdict.Ends(Ending.LOST, emptyList()), Goals.verdict(nobody, nobody.endsAt))
    }

    @Test
    fun `a closed list ends as soon as every participant made it, and the deadline gives the win to those who did`() {
        var named = running(MechanicsSamples.turtleEgg.copy(winners = 0))
        named = Goals.held(named, mimosa.uuid, 1, start + 1_000)
        assertNull(Goals.verdict(named, start + 1_000), "Bartok may still make it")
        named = Goals.held(named, bartok.uuid, 1, start + 3_000)
        assertEquals(listOf("Mimosa", "Bartok"), winners(Goals.verdict(named, start + 3_000)), "nobody else can join")

        var three = running(MechanicsSamples.turtleEgg.copy(winners = 3))
        three = Goals.held(three, bartok.uuid, 1, start + 4_000)
        assertNull(Goals.verdict(three, start + 4_000))
        assertEquals(listOf("Bartok"), winners(Goals.verdict(three, three.endsAt)), "fewer than three made it by the end: they win")
    }

    @Test
    fun `survive is lost once nobody is still in, and won at the deadline by everyone still in, whatever winners says`() {
        var c = running(MechanicsSamples.naked.copy(winners = 1), bartok, mimosa, josiane)
        c = Goals.out(c, bartok.uuid, OutReason.DIED)
        c = Goals.out(c, mimosa.uuid, OutReason.ARMOR)
        assertNull(Goals.verdict(c, c.endsAt - 1))
        assertEquals(listOf("Josiane"), winners(Goals.verdict(c, c.endsAt)))
        c = Goals.out(c, josiane.uuid, OutReason.LEFT)
        assertEquals(Verdict.Ends(Ending.LOST, emptyList()), Goals.verdict(c, start + 1_000), "nobody left: it ends at once")
        val alive = running(MechanicsSamples.naked, bartok, mimosa)
        assertEquals(listOf("Bartok", "Mimosa"), winners(Goals.verdict(alive, alive.endsAt)))
    }

    @Test
    fun `a judged challenge is judged at its deadline and expires past its judging, a duel is left to its part`() {
        val statue = running(MechanicsSamples.statue)
        assertNull(Goals.verdict(statue, statue.endsAt - 1))
        assertEquals(Verdict.Judge, Goals.verdict(statue, statue.endsAt))
        val judging = statue.copy(status = ChallengeStatus.JUDGING, judgeUntil = statue.endsAt + 15 * MINUTE)
        assertNull(Goals.verdict(judging, judging.endsAt + 5 * MINUTE))
        assertEquals(Verdict.Ends(Ending.EXPIRED, emptyList()), Goals.verdict(judging, judging.judgeUntil!!))
        assertNull(Goals.verdict(MechanicsSamples.invitedDuel, start + 999 * MINUTE))
        assertNull(Goals.verdict(MechanicsSamples.invitedDuel.copy(status = ChallengeStatus.RUNNING), start + 999 * MINUTE))
    }

    @Test
    fun `endings close the canon with its statuses, and an early end asks for one of three`() {
        assertEquals(listOf(CanonStatus.WON, CanonStatus.LOST, CanonStatus.EXPIRED, CanonStatus.CANCELLED), Ending.entries.map { it.status })
        assertEquals(listOf("won", "lost", "expired", "cancelled"), Ending.entries.map { it.id })
        assertEquals(listOf(Ending.WON, Ending.LOST, Ending.CANCELLED), ChallengeOutcome.entries.map(Ending::of))
    }

    // ── Where a player stands ──

    @Test
    fun `a place holds when every field given holds at once`() {
        fun spot(dimension: String = "minecraft:overworld", x: Double = 0.5, y: Double = 64.0, z: Double = 0.5, biome: String? = null, structure: String? = null) =
            Spot(dimension, x, y, z, biome, structure)
        val nether = Target.Place("minecraft:the_nether", null, null, null, 8, "minecraft:the_nether", null, null, null, null)
        assertTrue(nether.holds(spot("minecraft:the_nether")))
        assertFalse(nether.holds(spot()))

        val point = Target.Place(null, 12, null, -40, 8, "minecraft:overworld", null, null, null, null)
        assertTrue(point.holds(spot(x = 12.5 + 7.9, y = 200.0, z = -39.5)), "horizontally within 8, at any height")
        assertFalse(point.holds(spot(x = 12.5 + 8.1, z = -39.5)))
        assertFalse(point.holds(spot("minecraft:the_nether", x = 12.5, z = -39.5)), "a point is in its dimension")
        val point3d = point.copy(y = 70)
        assertTrue(point3d.holds(spot(x = 12.5, y = 74.0, z = -39.5)))
        assertFalse(point3d.holds(spot(x = 12.5, y = 79.0, z = -39.5)), "with y, the distance counts the height")

        val heights = Target.Place(null, null, null, null, 8, "minecraft:overworld", 120, 130, null, null)
        assertTrue(heights.holds(spot(y = 120.0)) && heights.holds(spot(y = 130.9)))
        assertFalse(heights.holds(spot(y = 119.9)) || heights.holds(spot(y = 131.0)))

        val village = Target.Place(null, null, null, null, 8, "minecraft:overworld", null, null, setOf("minecraft:plains"), setOf("minecraft:village_plains"))
        assertTrue(village.holds(spot(biome = "minecraft:plains", structure = "minecraft:village_plains")))
        assertFalse(village.holds(spot(biome = "minecraft:plains", structure = null)), "the structure is not known yet")
        assertFalse(village.holds(spot(biome = "minecraft:desert", structure = "minecraft:village_plains")))
    }

    // ── What the Director reads ──

    @Test
    fun `a goal reads in a few words`() {
        assertEquals(
            listOf("obtain 1 turtle_egg", "reach the_nether", "kill 10 spider", "survive without armor", "answer", "judged"),
            MechanicsSamples.challenges.map(Goals::describe),
        )
        assertEquals("trial_chambers, x 12 y 64 z -40 (within 8) in the_nether", Goals.describe(PlaceSpec(structure = "minecraft:Trial_Chambers", dimension = "nether", x = 12, y = 64, z = -40)))
        assertEquals("#village, y 120 or higher", Goals.describe(PlaceSpec(structure = "#minecraft:village", yAbove = 120)))
        assertEquals("spider 4/10", Goals.measure(MechanicsSamples.spiders, Participant(mimosa.uuid.toString(), "Mimosa", progress = 4)))
        assertEquals("turtle_egg 1/1", Goals.measure(MechanicsSamples.turtleEgg, Participant(mimosa.uuid.toString(), "Mimosa", progress = 3)))
        assertNull(Goals.measure(MechanicsSamples.riddle, Participant(mimosa.uuid.toString(), "Mimosa")))
    }

    @Test
    fun `the end of a challenge reads as the Director's event says it`() {
        var egg = running(MechanicsSamples.turtleEgg)
        egg = Goals.held(egg, mimosa.uuid, 1, start + 552_000)
        val won = (Goals.verdict(egg, start + 552_000) as Verdict.Ends).winners
        assertEquals("won by Mimosa in 9m12s (turtle_egg 1/1)", ChallengeLines.summary(egg, Ending.WON, won, start + 552_000, null))

        val nether = running(MechanicsSamples.nether)
        assertEquals("lost: nobody reached the_nether in 20m", ChallengeLines.summary(nether, Ending.LOST, emptyList(), nether.endsAt + 400, null))

        var naked = running(MechanicsSamples.naked)
        naked = Goals.out(naked, bartok.uuid, OutReason.DIED)
        naked = Goals.out(naked, mimosa.uuid, OutReason.LEFT)
        assertEquals(
            "lost: nobody is still in after 3m; out: Bartok (died), Mimosa (left)",
            ChallengeLines.summary(naked, Ending.LOST, emptyList(), start + 3 * MINUTE, null),
        )

        val judging = running(MechanicsSamples.statue).copy(status = ChallengeStatus.JUDGING, judgeUntil = start + 30 * MINUTE)
        assertEquals("expired: not judged within 15m; no stakes", ChallengeLines.summary(judging, Ending.EXPIRED, emptyList(), judging.judgeUntil!!, null))
        assertEquals(
            "won by Mimosa (ended by the Director)",
            ChallengeLines.summary(judging, Ending.WON, judging.participants.drop(1), start, Author(Source.DIRECTOR, turnId = 42)),
        )
        assertEquals("cancelled (ended by the operator Clement)", ChallengeLines.summary(egg, Ending.CANCELLED, emptyList(), start, Author(Source.OPERATOR, operator = "Clement")))
        assertEquals(
            "C-50 « La plus belle statue » ended: judge it now with end_challenge C-50 (winners); participants: Bartok, Mimosa; you have 15m",
            ChallengeLines.judge(judging, start + 15 * MINUTE),
        )
    }

    @Test
    fun `the situation shows each challenge, the pace, and stays within its budget`() {
        val config = MechanicsConfig()
        var egg = running(MechanicsSamples.turtleEgg).copy(betsCloseAt = start + 5 * MINUTE)
        egg = Goals.held(egg, bartok.uuid, 1, start + 60_000)
        val state = MechanicsState(challenges = listOf(egg), lastChallengeAt = start)
        assertEquals(
            "Challenges: C-50 obtain 1 turtle_egg « Œuf de tortue » · Bartok 1/1 (done), Mimosa 0/1 · ends in 12m · bets open 2m (0 bets) · " +
                "next challenge possible at 20:10 (in 7m)",
            ChallengeLines.situation(state, start + 3 * MINUTE, config, clock),
        )
        assertNull(ChallengeLines.situation(MechanicsState(lastChallengeAt = start), start + 10 * MINUTE, config, clock), "nothing to say")
        assertEquals("Challenges: none running · next challenge possible at 20:10 (in 1m)", ChallengeLines.situation(MechanicsState(lastChallengeAt = start), start + 9 * MINUTE, config, clock))

        val open = running(MechanicsSamples.nether, bartok).let { Goals.joined(it, josiane.uuid, "Josiane") }
        val both = MechanicsState(challenges = listOf(open, MechanicsSamples.invitedDuel), lastChallengeAt = start)
        assertEquals(
            "Challenges: C-50 reach the_nether « Porte des Enfers » → all · Bartok, Josiane (late) · ends in 15m; " +
                "C-52 duel « Duel au soleil » Bartok vs Mimosa · invited, Bartok accepted, waiting for Mimosa · answer by 20:06 (in 1m) · " +
                "no new challenge until one ends (2/2)",
            ChallengeLines.situation(both, start + 5 * MINUTE, config, clock),
        )

        // Eight participants with long names in two challenges: counted, then a challenge left out if need be.
        val crowd = (1..8).map { Participant("00000000-0000-0000-0000-00000000000$it".takeLast(36), "Joueur_Bavard_$it") }
        val busy = running(MechanicsSamples.turtleEgg.copy(title = "Un titre de quarante caractères tout pile")).copy(participants = crowd)
        val crowded = MechanicsState(challenges = listOf(busy, busy.copy(id = 51)), lastChallengeAt = start)
        val line = ChallengeLines.situation(crowded, start + MINUTE, config, clock)!!
        assertTrue(line.length <= MechanicsLimits.SITUATION_LINE, "${line.length}: $line")
        assertTrue("8 players" in line && "C-51" in line, line)
    }

    @Test
    fun `the canon's stakes and the start read as the design says`() {
        assertEquals("reward 1x golden_apple · penalty tiny (2) · bets", ChallengeLines.stakes(MechanicsSamples.goldenApple, MechanicsSamples.tinyForfeit, true))
        assertEquals("reward 1x diamond_pickaxe (named)", ChallengeLines.stakes(MechanicsSamples.namedPickaxe, null, false))
        assertEquals("reward speed II for 5m", ChallengeLines.stakes(MechanicsSamples.speed, null, false))
        assertEquals("reward grace for 15m · penalty slow_hands (1)", ChallengeLines.stakes(MechanicsSamples.grace, MechanicsSamples.slowHandsForfeit, false))
        assertEquals("reward a joker", ChallengeLines.stakes(MechanicsSamples.joker, null, false))
        assertNull(ChallengeLines.stakes(null, null, false))
        val egg = running(MechanicsSamples.turtleEgg).copy(betsCloseAt = start + 5 * MINUTE)
        assertEquals(
            "C-50 started: obtain 1 turtle_egg « Œuf de tortue » · Bartok, Mimosa · ends 20:15 (in 15m) · reward 1x golden_apple · " +
                "penalty tiny (2) · bets open until 20:05",
            ChallengeLines.started(egg, clock),
        )
        assertTrue(ChallengeLines.started(running(MechanicsSamples.nether, bartok), clock).contains(" · everyone online (Bartok) and whoever joins · "))
    }

    private companion object {
        const val MINUTE = 60_000L

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
