package io.github.clemsix6.narrator2.tool.pacing

import io.github.clemsix6.narrator2.guard.clockTime
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pacing tools (M1_M2 §9.5): `remind_me`. `ToolCatalog` builds the family once per conversation.
 * Doing nothing is not a tool: a turn that ends without acting is a valid decision.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun pacingTools(services: NarratorServices): List<NarratorTool<*>> = listOf(RemindMeTool())

@Serializable
data class RemindMeArgs(
    @Doc("When, in seconds from now.")
    @Range(30.0, 3600.0)
    @SerialName("in_seconds")
    val inSeconds: Int,
    @Doc("The note you will get back, written for yourself later: who, what, why, and what to check then.")
    @MaxLen(200)
    val note: String,
)

/**
 * `remind_me`: schedules a wake-up of the Director through `DirectorControl.scheduleReminder`. When it
 * is due, the reminder reaches the Director's `<events>` as `REMINDER R-n` with its note, like any
 * event: a tool cannot block the turn, it schedules a wake-up instead. At most [MAX_PENDING] reminders
 * wait at once (`DirectorStatus.reminders`); the Director keeps them in memory, so a restart loses them.
 */
class RemindMeTool : NarratorTool<RemindMeArgs>(
    name = "remind_me",
    description = "Wakes you up later with a note to yourself: a challenge's deadline, a punishment's end to comment on, a " +
        "prophecy to fulfil, a promise to keep, a player who must have obeyed by then. When it is due, it arrives in " +
        "<events> as REMINDER with your note. At most $MAX_PENDING reminders wait at once; a server restart forgets them. " +
        "Not a polling loop: events already wake you when something happens.",
    args = RemindMeArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun run(ctx: ToolContext, args: RemindMeArgs): ToolResult {
        val note = args.note.trim()
        if (note.isEmpty()) return ToolResult.invalid("note is empty: say what to do when the reminder comes")
        val director = ctx.services.director
        val pending = director.status().reminders.sortedBy { it.at }
        if (pending.size >= MAX_PENDING) {
            val list = pending.joinToString { "R-${it.id} at ${clockTime(ctx.clock, it.at)}" }
            return ToolResult.denied(
                LIMIT_RULE,
                "$MAX_PENDING reminders are already waiting ($list); a new one can be set when one of them comes due",
            )
        }
        val at = ctx.clock.millis() + args.inSeconds * 1_000L
        return director.scheduleReminder(args.inSeconds, note, ctx.caller).fold(
            onSuccess = { id -> ToolResult.ok("ok: reminder R-$id at ${clockTime(ctx.clock, at)} (in ${describe(args.inSeconds)})") },
            onFailure = { ToolResult.failed("no reminder set: ${it.message}") },
        )
    }

    companion object {
        const val MAX_PENDING = 5

        /** The rule of the pending reminders' cap, in `DENIED (reminder_limit): …`. */
        const val LIMIT_RULE = "reminder_limit"

        /** `45s`, `10m`, `12m30s`. */
        private fun describe(seconds: Int): String = when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0 -> "${seconds / 60}m"
            else -> "${seconds / 60}m${seconds % 60}s"
        }
    }
}
