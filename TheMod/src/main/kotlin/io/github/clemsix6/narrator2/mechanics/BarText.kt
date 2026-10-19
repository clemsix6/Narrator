package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.tool.speech.Speech
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay

/**
 * One player's boss bar for a challenge or a duel (M4 §5.4): what [Bossbars.show] sends.
 *
 * @property name `challenge.bar` or `duel.bar` filled in, [MechanicsLimits.BAR_NAME] characters at most.
 * @property progress the filled part of the bar, in [0, 1].
 */
data class BarView(val name: String, val progress: Float, val color: BossBarColor, val overlay: BossBarOverlay)

/**
 * What a challenge's boss bar shows to one participant (M4 §5.4): their own progress, the time left, a color
 * per goal. Pure, any thread; the texts come from [UiTexts].
 *
 * - Name: `challenge.bar` = `{title} · {progress} · {time}`, or `duel.bar` = `{title} · {time}` for a duel.
 *   The progress is `challenge.progress.count` (`{have}/{need}`) for a goal that counts (`obtain`, `kill`),
 *   `.done` once the goal is reached, `.out` when eliminated, `.alive` for a survivor (and for every
 *   participant while a judged challenge is judged), `.waiting` for a goal nothing measures yet (`reach`,
 *   `answer`, `judged`). The time is `mm:ss`, rounded up (`00:00` only at the deadline), or
 *   `challenge.judging` while the Narrator judges.
 * - Bar: `have / need` for a goal that counts; else the share of the time left (of the judging window while
 *   it is judged).
 * - Color: `obtain` green, `reach` blue, `kill` red, `survive` purple, `answer` yellow, `judged` white, a duel
 *   red. Notches for a goal that counts when there is one per item (6, 10, 12 or 20); a plain bar otherwise.
 */
object BarText {
    /**
     * [participant]'s bar for [challenge] at [now]; null when there is none: a duel waiting for its players
     * has no bar yet.
     */
    fun of(challenge: ChallengeState, participant: Participant, now: Long, ui: UiTexts): BarView? {
        val title = title(challenge)
        val duel = challenge.duel
        if (duel != null) {
            if (challenge.status != ChallengeStatus.RUNNING) return null
            val name = fill(ui, "duel.bar", title, "time" to clock(challenge.endsAt - now))
            return BarView(name, timeLeft(challenge.startedAt, challenge.endsAt, now), BossBarColor.RED, BossBarOverlay.PROGRESS)
        }
        val spec = challenge.spec!!
        val counted = spec.goal == GoalKind.OBTAIN || spec.goal == GoalKind.KILL
        val judging = challenge.status == ChallengeStatus.JUDGING
        val progress = when {
            participant.doneAt != null -> ui["challenge.progress.done"]
            participant.out != null -> ui["challenge.progress.out"]
            counted -> ui.format("challenge.progress.count", "have" to participant.progress.coerceIn(0, spec.count), "need" to spec.count)
            spec.goal == GoalKind.SURVIVE || judging -> ui["challenge.progress.alive"]
            else -> ui["challenge.progress.waiting"]
        }
        val time = if (judging) ui["challenge.judging"] else clock(challenge.endsAt - now)
        val bar = when {
            counted -> participant.progress.toFloat() / spec.count
            judging -> timeLeft(challenge.endsAt, challenge.judgeUntil ?: challenge.endsAt, now)
            else -> timeLeft(challenge.startedAt, challenge.endsAt, now)
        }
        val name = fill(ui, "challenge.bar", title, "progress" to progress, "time" to time)
        return BarView(name, bar.coerceIn(0f, 1f), color(spec.goal), if (counted) notches(spec.count) else BossBarOverlay.PROGRESS)
    }

    /** The color of a challenge's bars by its goal (a duel's are red). */
    fun color(goal: GoalKind): BossBarColor = when (goal) {
        GoalKind.OBTAIN -> BossBarColor.GREEN
        GoalKind.REACH -> BossBarColor.BLUE
        GoalKind.KILL -> BossBarColor.RED
        GoalKind.SURVIVE -> BossBarColor.PURPLE
        GoalKind.ANSWER -> BossBarColor.YELLOW
        GoalKind.JUDGED -> BossBarColor.WHITE
    }

    /** [millis] left as `mm:ss`, rounded up to the second, never negative; the minutes may go past 59 (`180:00`). */
    fun clock(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    /** One notch per item when the game has that many notches; a plain bar otherwise. */
    private fun notches(count: Int): BossBarOverlay = when (count) {
        6 -> BossBarOverlay.NOTCHED_6
        10 -> BossBarOverlay.NOTCHED_10
        12 -> BossBarOverlay.NOTCHED_12
        20 -> BossBarOverlay.NOTCHED_20
        else -> BossBarOverlay.PROGRESS
    }

    /** The share of the time from [from] to [until] still left at [now], in [0, 1]; full when [from] is unknown or the span empty. */
    private fun timeLeft(from: Long?, until: Long, now: Long): Float {
        if (from == null || until <= from) return 1f
        return ((until - now).toFloat() / (until - from)).coerceIn(0f, 1f)
    }

    /** The model's title as plain text on one line (no `§` code, no line break); its reference when nothing is left. */
    private fun title(challenge: ChallengeState): String = Speech.clean(challenge.title).ifEmpty { challenge.ref.toString() }

    /**
     * [key] filled with [values] and [title], [MechanicsLimits.BAR_NAME] characters at most. Only texts an
     * operator lengthened can go past it (the bundled ones leave room for the longest title): the title is cut
     * first, so that the progress and the time stay readable, then the whole name if need be. The title is
     * filled last, so that a `{time}` in it stays as written.
     */
    private fun fill(ui: UiTexts, key: String, title: String, vararg values: Pair<String, Any>): String {
        val full = ui.format(key, *values, "title" to title)
        val excess = full.codePointCount(0, full.length) - MechanicsLimits.BAR_NAME
        if (excess <= 0) return full
        val shorter = ModelText.cut(title, (title.codePointCount(0, title.length) - excess).coerceAtLeast(1))
        return ModelText.cut(ui.format(key, *values, "title" to shorter), MechanicsLimits.BAR_NAME)
    }
}
