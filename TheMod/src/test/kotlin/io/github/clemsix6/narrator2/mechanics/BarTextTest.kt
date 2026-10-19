package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a challenge's boss bar shows (M4 §5.4): names within 80 characters, each progress, bar, colors. */
class BarTextTest {
    @TempDir
    lateinit var dir: Path

    private val ui = UiTexts.load("fr")
    private val start = MechanicsSamples.evening
    private val bartok = Participant(MechanicsSamples.bartok.uuid.toString(), "Bartok")

    /** C-50 of the samples, with [spec] as its challenge (15 minutes from [start]). */
    private fun challenge(
        spec: ChallengeSpec,
        status: ChallengeStatus = ChallengeStatus.RUNNING,
        judgeUntil: Long? = null,
    ): ChallengeState = MechanicsSamples.runningChallenge.copy(
        spec = spec,
        status = status,
        endsAt = start + spec.minutes * MINUTE,
        judgeUntil = judgeUntil,
    )

    private fun bar(challenge: ChallengeState, participant: Participant = bartok, now: Long = start): BarView =
        BarText.of(challenge, participant, now, ui)!!

    private fun length(text: String) = text.codePointCount(0, text.length)

    @Test
    fun `the names read as the players see them`() {
        val egg = challenge(MechanicsSamples.turtleEgg)
        assertEquals("Œuf de tortue · 0/1 · 15:00", bar(egg).name)
        assertEquals("Œuf de tortue · réussi · 05:48", bar(egg, bartok.copy(progress = 1, doneAt = start), start + 9 * MINUTE + 12_300).name)

        val naked = challenge(MechanicsSamples.naked)
        assertEquals("Survivre nu · en lice · 10:00", bar(naked).name)
        assertEquals("Survivre nu · éliminé · 04:00", bar(naked, bartok.copy(out = OutReason.ARMOR), start + 6 * MINUTE).name)

        assertEquals("Porte des Enfers · à toi de jouer · 20:00", bar(challenge(MechanicsSamples.nether)).name, "a goal nothing counts")
        assertEquals("Énigme · à toi de jouer · 05:00", bar(challenge(MechanicsSamples.riddle)).name)

        val statue = challenge(MechanicsSamples.statue, ChallengeStatus.JUDGING, judgeUntil = start + 30 * MINUTE)
        assertEquals("La plus belle statue · en lice · le Narrateur juge", bar(statue, now = start + 16 * MINUTE).name, "while it is judged")

        val duel = MechanicsSamples.invitedDuel.copy(status = ChallengeStatus.RUNNING, startedAt = start, endsAt = start + 5 * MINUTE)
        assertEquals("Duel au soleil · 04:30", bar(duel, now = start + 30_000).name)
    }

    @Test
    fun `the longest name fits in 80 characters, for each progress`() {
        // 40 code points, accents and a character outside the BMP included: the longest title a model may write.
        val base = "Épreuve du très long titre 🐢 à rallonge"
        val title = base + "x".repeat(MechanicsLimits.TITLE - length(base))
        assertEquals(MechanicsLimits.TITLE, length(title))
        val longest = MechanicsSamples.turtleEgg.copy(title = title, count = 256, minutes = 180)
        val names = listOf(
            bar(challenge(longest), bartok.copy(progress = 256)).name, // count, 256/256
            bar(challenge(longest), bartok.copy(doneAt = start)).name, // done
            bar(challenge(longest.copy(goal = GoalKind.SURVIVE)), bartok.copy(out = OutReason.DIED)).name, // out
            bar(challenge(longest.copy(goal = GoalKind.SURVIVE))).name, // alive
            bar(challenge(longest.copy(goal = GoalKind.ANSWER))).name, // waiting
            bar(challenge(longest.copy(goal = GoalKind.JUDGED), ChallengeStatus.JUDGING, start + 200 * MINUTE), now = start + 181 * MINUTE).name, // judging
            bar(MechanicsSamples.invitedDuel.copy(duel = MechanicsSamples.duel.copy(title = title), status = ChallengeStatus.RUNNING, startedAt = start)).name,
        )
        names.forEach { name ->
            assertTrue(length(name) <= MechanicsLimits.BAR_NAME, "${length(name)} characters: $name")
            assertContains(name, title, message = "the bundled texts leave room for the whole title")
        }
        assertEquals("$title · 256/256 · 180:00", names[0])
    }

    @Test
    fun `texts an operator lengthened are cut, the title first`() {
        dir.resolve("fr.properties").writeText("challenge.bar=Défi du Narrateur : {title} — progrès {progress}, il reste {time}\n")
        val lengthened = UiTexts.load("fr", dir)
        val title = "Œuf de tortue " + "x".repeat(26) // 40 characters: 10 too many with these texts

        val name = BarText.of(challenge(MechanicsSamples.turtleEgg.copy(title = title)), bartok, start, lengthened)!!.name
        assertEquals(MechanicsLimits.BAR_NAME, length(name), name)
        assertTrue(name.endsWith("progrès 0/1, il reste 15:00"), "the progress and the time stay readable: $name")
        assertContains(name, "Défi du Narrateur : Œuf de tortue ${"x".repeat(15)}… — progrès", message = "the title is cut")

        dir.resolve("fr.properties").writeText("challenge.bar=${"Lisez bien ceci. ".repeat(6)}{title} {progress} {time}\n")
        val whole = BarText.of(challenge(MechanicsSamples.turtleEgg), bartok, start, UiTexts.load("fr", dir))!!.name
        assertEquals(MechanicsLimits.BAR_NAME, length(whole), "then the whole name: $whole")
        assertTrue(whole.endsWith("…"), whole)
    }

    @Test
    fun `the title is plain text on one line, and a placeholder in it stays as written`() {
        val name = bar(challenge(MechanicsSamples.turtleEgg.copy(title = "§cRouge\n{time} {progress}"))).name
        assertEquals("Rouge {time} {progress} · 0/1 · 15:00", name)
        assertEquals("C-50 · 0/1 · 15:00", bar(challenge(MechanicsSamples.turtleEgg.copy(title = "§l§k"))).name, "nothing left: its reference")
    }

    @Test
    fun `the bar fills with the count and empties with the time, always within 0 and 1`() {
        val four = challenge(MechanicsSamples.turtleEgg.copy(count = 4))
        assertEquals(0f, bar(four).progress)
        assertEquals(0.5f, bar(four, bartok.copy(progress = 2)).progress)
        assertEquals(1f, bar(four, bartok.copy(progress = 9, doneAt = start)).progress, "the most held may go past the count")
        assertEquals("Œuf de tortue · 4/4 · 15:00", bar(four, bartok.copy(progress = 9)).name)

        val nether = challenge(MechanicsSamples.nether) // 20 minutes
        assertEquals(1f, bar(nether).progress)
        assertEquals(0.75f, bar(nether, now = start + 5 * MINUTE).progress)
        assertEquals(0f, bar(nether, now = start + 25 * MINUTE).progress, "past the deadline")
        assertEquals("Porte des Enfers · à toi de jouer · 00:00", bar(nether, now = start + 25 * MINUTE).name)
        assertEquals(1f, bar(nether, now = start - MINUTE).progress, "before the start")

        val judging = challenge(MechanicsSamples.statue, ChallengeStatus.JUDGING, judgeUntil = start + 25 * MINUTE) // ended at +15
        assertEquals(0.5f, bar(judging, now = start + 20 * MINUTE).progress, "the judging window")
        assertEquals(1f, bar(challenge(MechanicsSamples.statue, ChallengeStatus.JUDGING), now = start + 20 * MINUTE).progress, "no window known")
    }

    @Test
    fun `each goal has its color, and a count its notches when there is one per item`() {
        val colors = GoalKind.entries.associateWith { BarText.color(it) }
        assertEquals(
            mapOf(
                GoalKind.OBTAIN to BossBarColor.GREEN, GoalKind.REACH to BossBarColor.BLUE, GoalKind.KILL to BossBarColor.RED,
                GoalKind.SURVIVE to BossBarColor.PURPLE, GoalKind.ANSWER to BossBarColor.YELLOW, GoalKind.JUDGED to BossBarColor.WHITE,
            ),
            colors,
        )
        MechanicsSamples.challenges.forEach { spec -> assertEquals(BarText.color(spec.goal), bar(challenge(spec)).color, spec.title) }

        assertEquals(BossBarOverlay.NOTCHED_10, bar(challenge(MechanicsSamples.spiders)).overlay, "ten spiders, ten notches")
        assertEquals(BossBarOverlay.NOTCHED_6, bar(challenge(MechanicsSamples.turtleEgg.copy(count = 6))).overlay)
        assertEquals(BossBarOverlay.NOTCHED_12, bar(challenge(MechanicsSamples.turtleEgg.copy(count = 12))).overlay)
        assertEquals(BossBarOverlay.NOTCHED_20, bar(challenge(MechanicsSamples.turtleEgg.copy(count = 20))).overlay)
        assertEquals(BossBarOverlay.PROGRESS, bar(challenge(MechanicsSamples.turtleEgg)).overlay, "one egg: no notch")
        assertEquals(BossBarOverlay.PROGRESS, bar(challenge(MechanicsSamples.turtleEgg.copy(count = 64))).overlay)
        assertEquals(BossBarOverlay.PROGRESS, bar(challenge(MechanicsSamples.nether)).overlay, "time")
    }

    @Test
    fun `a duel has a red bar of its time once both accepted, none while it waits`() {
        assertNull(BarText.of(MechanicsSamples.invitedDuel, bartok, start, ui), "an invitation")
        val running = MechanicsSamples.invitedDuel.copy(status = ChallengeStatus.RUNNING, startedAt = start, endsAt = start + 5 * MINUTE)
        val bar = bar(running, now = start + MINUTE)
        assertEquals("Duel au soleil · 04:00", bar.name)
        assertEquals(0.8f, bar.progress)
        assertEquals(BossBarColor.RED, bar.color)
        assertEquals(BossBarOverlay.PROGRESS, bar.overlay)
    }

    @Test
    fun `the time left is mm-ss, rounded up to the second`() {
        assertEquals("00:00", BarText.clock(0))
        assertEquals("00:00", BarText.clock(-5_000))
        assertEquals("00:01", BarText.clock(1))
        assertEquals("01:01", BarText.clock(60_500))
        assertEquals("15:00", BarText.clock(15 * MINUTE))
        assertEquals("180:00", BarText.clock(180 * MINUTE))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
