package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.challenge.ChallengeLines
import io.github.clemsix6.narrator2.mechanics.challenge.Goals
import io.github.clemsix6.narrator2.mechanics.punish.Primitive
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentCatalog
import io.github.clemsix6.narrator2.mechanics.punish.Step
import io.github.clemsix6.narrator2.mechanics.punish.TrapShape
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.ApplyEffectArgs
import io.github.clemsix6.narrator2.tool.action.FavorArgs
import io.github.clemsix6.narrator2.tool.action.FavorKind
import io.github.clemsix6.narrator2.tool.action.GiveItemArgs
import io.github.clemsix6.narrator2.tool.action.LaunchArgs
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.tool.action.SetAttributeArgs
import io.github.clemsix6.narrator2.tool.action.SpawnArgs
import io.github.clemsix6.narrator2.tool.action.TakeItemArgs
import io.github.clemsix6.narrator2.tool.mechanics.DeployModuleArgs
import io.github.clemsix6.narrator2.tool.speech.ActionbarArgs
import io.github.clemsix6.narrator2.tool.speech.SayArgs
import io.github.clemsix6.narrator2.tool.speech.ShowTitleArgs
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the game answers to an act of a replayed turn (M6-16, M6-18). The replay executes nothing, but an answer that says
 * `ok` alone hides what the real tool tells the Director: how long a punishment lasts, until when a law runs, what a
 * challenge pays. The Director then announces what it cannot know, and the judge notes the line illegible for a fault of
 * the replay. These are the real tools' answers, in their exact form, built from the call's arguments as if everything
 * went well, at the situation's time ([Situation.now]), with the production's chaos level ([CHAOS]):
 *
 * - arguments that the real tool would refuse get its exact refusal (`Invalid arguments: …`, [ToolArgs.decode]);
 * - `say`, `actionbar`, `show_title`, `punish` (the catalogue's real plan: its steps and its duration), `apply_effect`,
 *   `set_attribute`, `take_item`, `give_item`, `favor`, `create_rule` (with `RuleText`, as the real tool), `start_challenge`,
 *   `deploy_module`, `spawn`, `launch` answer as the game does;
 * - any other act answers `ok`, as before. A fixture's `acts` still replaces an answer.
 *
 * The references the answers give (`C-901`…) are fake and new within a turn.
 */
internal object ReplayActs {
    /** The production's chaos level (`NarratorConfig.chaos`). */
    const val CHAOS = 3

    /** Where the fake references of a turn start. */
    const val FIRST_REF = 901

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val NOW_ATTRIBUTE = Regex("""now="(?:(\d{4}-\d\d-\d\d) )?(\d\d:\d\d:\d\d)"""")
    private val ONLINE_LINE = Regex("""^([A-Za-z0-9_]{3,16}): (overworld|the_nether|the_end|nether|end)\b""")

    /** What a situation tells the answers: its day and the time of its latest `now`, and the players online. */
    class Situation(val now: LocalTime, val online: List<String>, val day: LocalDate = LocalDate.now()) {
        private var next = FIRST_REF

        /** A new fake reference of the turn. */
        fun ref(): String = "C-${next++}"

        /** [now] plus [seconds], as the tools write a time: `21:51:30`. */
        fun at(seconds: Long): String = TIME.format(now.plusSeconds(seconds))

        companion object {
            /**
             * From the opening's blocks: the latest `now="…"`, the day of the briefing's (`now="2026-10-19 22:15:30"`), and
             * the players of `<situation>` (or `<players_online>`).
             */
            fun of(blocks: List<String>): Situation {
                val text = blocks.joinToString("\n")
                val nows = NOW_ATTRIBUTE.findAll(text).toList()
                val now = nows.lastOrNull()?.groupValues?.get(2)?.let(LocalTime::parse) ?: LocalTime.of(21, 0)
                val day = nows.firstNotNullOfOrNull { it.groupValues[1].ifEmpty { null } }?.let(LocalDate::parse) ?: LocalDate.now()
                val online = text.lines().mapNotNull { ONLINE_LINE.find(it.trim())?.groupValues?.get(1) }.distinct()
                return Situation(now, online, day)
            }
        }
    }

    /** The game's answer to [tool] called with [input] (its JSON arguments) in [situation]. */
    fun answer(tool: NarratorTool<*>, input: String, situation: Situation): String {
        val args = when (val decoded = ToolArgs.decode(tool.args, input)) {
            is ToolArgs.Decoded.Invalid -> return "Invalid arguments: ${decoded.problem}"
            is ToolArgs.Decoded.Ok -> decoded.args
        }
        return when (args) {
            is SayArgs -> args.to?.let { "ok: whispered to ${names(it)}" } ?: "ok: said to everyone (${situation.online.size} online)"
            is ActionbarArgs -> "ok: shown to ${args.to?.let(::names) ?: "everyone (${situation.online.size} online)"}"
            is ShowTitleArgs -> "ok: title shown to ${args.to?.let(::names) ?: "everyone (${situation.online.size} online)"}"
            is PunishArgs -> punish(args, situation)
            is ApplyEffectArgs -> {
                val shown = "${args.effect.removePrefix("minecraft:")} ${ToolText.roman(args.level)}"
                "ok: ${names(args.players)} ${if (args.players.size == 1) "gets" else "get"} $shown for ${ToolText.duration(args.seconds * 1_000L)} (until ${situation.at(args.seconds.toLong())})"
            }
            is SetAttributeArgs -> {
                val change = args.multiply?.let { "×${ToolText.number(it)}" } ?: "${if ((args.add ?: 0.0) >= 0) "+" else ""}${ToolText.number(args.add ?: 0.0)}"
                "ok: ${ModelText.name(args.player)}'s ${args.attribute.id} $change for ${ToolText.duration(args.seconds * 1_000L)} (until ${situation.at(args.seconds.toLong())})"
            }
            is TakeItemArgs -> "ok: took ${args.count} ${item(args.item)} from ${ModelText.name(args.player)} until ${situation.at(args.minutes * 60L)} " +
                "(in ${ToolText.duration(args.minutes * 60_000L)}); they come back by themselves"
            is GiveItemArgs -> "ok: gave ${args.count} ${item(args.item)}${args.name?.let { " named « ${ModelText.escape(it)} »" }.orEmpty()} to ${ModelText.name(args.player)}"
            is FavorArgs -> when (args.favor) {
                FavorKind.JOKER -> "ok: ${ModelText.name(args.player)} got a joker; they can play it themselves to lift one of your punishments"
                FavorKind.GRACE -> "ok: ${ModelText.name(args.player)} is under your grace until ${situation.at(args.minutes * 60L)} (in ${ToolText.duration(args.minutes * 60_000L)})"
                FavorKind.PARDON -> "ok: nothing to pardon: the Narrator has no punishment running on ${ModelText.name(args.player)}"
            }
            is RuleSpec -> {
                val after = if (args.kind == RuleKind.LAW) "announce it if you like" else "it stays secret"
                "ok: rule ${situation.ref()} (${args.kind.id}) active until ${until(situation, args.minutes)} (in ${ToolText.duration(args.minutes * 60_000L)}): " +
                    "${RuleText.summary(args)}; $after"
            }
            is ChallengeSpec -> {
                val who = args.players?.let(::names) ?: "everyone online (${situation.online.joinToString()}) and whoever joins"
                listOfNotNull(
                    "ok: challenge ${situation.ref()} started: ${Goals.describe(args)} « ${ModelText.escape(args.title)} »",
                    who,
                    "ends ${until(situation, args.minutes)} (in ${ToolText.duration(args.minutes * 60_000L)})",
                    args.reward?.let { "reward ${ChallengeLines.reward(it)}" },
                    args.penalty?.let { "penalty ${it.punishment.id} (${it.severity})" },
                ).joinToString(" · ") + "; boss bars shown"
            }
            is DeployModuleArgs -> {
                val after = if (args.kind == RuleKind.LAW) "announce it if you like" else "it stays secret"
                val params = args.params.entries.joinToString(", ") { (k, v) -> "$k ${(v as? JsonPrimitive)?.content ?: v.toString()}" }
                "ok: module ${args.module} v1 deployed as ${situation.ref()} (${args.kind.id}) until ${until(situation, args.minutes)} " +
                    "(in ${ToolText.duration(args.minutes * 60_000L)})${if (params.isEmpty()) "" else ": $params"}" +
                    "${args.challenge?.let { "; it referees $it" }.orEmpty()}; $after"
            }
            is SpawnArgs -> "ok: ${args.count} ${item(args.entity)}${if (args.count > 1) "s" else ""}${args.name?.let { " named « ${ModelText.escape(it)} »" }.orEmpty()} " +
                "around ${ModelText.name(args.near)}" + args.equipment?.slots().orEmpty().let { slots ->
                    if (slots.isEmpty()) "" else ", equipped (${slots.joinToString { (slot, worn) -> "${slot.serializedName} ${worn.shown()}" }})"
                }
            is LaunchArgs -> "ok: ${ModelText.name(args.player)} launched ${args.direction.name.lowercase()} at strength ${ToolText.number(args.strength)}; " +
                "no fall damage until ${situation.at(15)}"
            else -> "ok"
        }
    }

    /**
     * `punish` as the real tool answers (`PunishTools.report`): `ok: Bartok punished, tiny (severity 2) for « … »: scale ×0.5,
     * poof particles, until 21:51:30 (in 2m)`, from the catalogue's real plan at the production's chaos level.
     */
    private fun punish(args: PunishArgs, situation: Situation): String {
        val caps = Caps.of(CHAOS)
        PunishmentCatalog.severityRefusal(args.severity, caps, CHAOS)?.let { return "Invalid arguments: $it" }
        val player = ModelText.name(args.player)
        val plan = PunishmentCatalog.plan(args.punishment, args.severity, caps, args.player, args.name)
        val done = listOfNotNull(plan.primitive?.let(::primitive)) + plan.steps.map(::step)
        val lasting = if (plan.lasting) ", until ${situation.at(plan.seconds.toLong())} (in ${ToolText.duration(plan.seconds * 1_000L)})" else ""
        return "ok: $player punished, ${plan.punishment.id} (severity ${plan.severity}) for « ${ModelText.escape(args.reason)} »: ${done.joinToString(", ")}$lasting" +
            plan.capped.joinToString("") { "; $it" }
    }

    private fun primitive(primitive: Primitive): String = when (primitive) {
        is Primitive.Shuffle -> "inventory shuffled${if (primitive.hotbar) " (hotbar)" else ""}, nothing lost"
        Primitive.TreeTop -> "put on the top of a tree nearby"
        is Primitive.Trap -> if (primitive.shape == TrapShape.CAGE) "a glass cage around them" else "cobwebs around them"
    }

    /** What a step did, as `PunishTools.phrase` writes it. */
    private fun step(step: Step): String = when (step) {
        is Step.Attribute -> "${step.args.attribute.id} ×${ToolText.number(step.args.multiply ?: 1.0)}"
        is Step.Effect -> "${step.args.effect} ${ToolText.roman(step.args.level)}"
        is Step.Particles -> "${step.args.particle.id} particles"
        is Step.Launch -> "launched up at strength ${ToolText.number(step.args.strength)} with no fall damage"
        is Step.Lightning -> "lightning struck near them"
        is Step.Spawn -> if (step.tiny) {
            "${step.args.count} tiny slimes falling around them"
        } else {
            "${step.args.count} ${item(step.args.entity)}s${step.args.name?.let { " named « ${ModelText.escape(it)} »" }.orEmpty()} going for them"
        }
    }

    /** The end of something that lasts [minutes], as `RuleText.time` writes it: `21:40` today, `10-25 20:15` another day. */
    private fun until(situation: Situation, minutes: Int): String {
        val zone = ZoneId.systemDefault()
        val start = situation.day.atTime(situation.now).atZone(zone)
        return RuleText.time(start.plusMinutes(minutes.toLong()).toInstant().toEpochMilli(), start.toInstant().toEpochMilli(), zone)
    }

    private fun names(players: List<String>): String = players.joinToString { ModelText.name(it) }

    private fun item(id: String): String = id.removePrefix("minecraft:")
}
