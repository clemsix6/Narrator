package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.style.DisabledStyle
import io.github.clemsix6.narrator2.style.Diversity
import io.github.clemsix6.narrator2.style.StyleReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.math.roundToInt

/**
 * `/narrator style` (M6 §13): the Narrator's style as the code sees it now, on one page of the chat.
 *
 * - What runs: the watch, the line review, the notes of tone, the refusal of resting openers.
 * - From the watch ([NarratorServices.style], in memory): its `Style:` line, and the diversity of the Director's latest
 *   lines ([Diversity]).
 * - From the Journal ([StyleReport]): how many of the Director's latest [LATEST_LINES] lines the review labeled, and the
 *   latest [FLAGS] lines flagged for their tone.
 * - From the bus: the events its subscribers lost to a full queue (the watch and the review drop a line rather than wait).
 *
 * The Journal is read off the server thread ([replyLater]), once what came before is written (one second at most);
 * without it, the rest is still shown.
 */
object StyleCommands {
    private val log = Log.command

    /** The Director's latest lines whose review is counted. */
    const val LATEST_LINES = 50

    /** Tone flags shown, the latest first. */
    const val FLAGS = 5

    /** Longest wait for the Journal to write what came before. */
    private const val BARRIER_MS = 1_000L

    /** Longest wait for a read connection and the queries. */
    private const val READ_TIMEOUT_MS = 10_000L

    /** Adds `style` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(LiteralArgumentBuilder.literal<CommandSourceStack>("style").executes { show(it.source) })
    }

    private fun show(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        log.info("${source.textName}: narrator style")
        replyLater(source, runtime, "style") { report(runtime) }
        return 1
    }

    /** The reply of `/narrator style`: a title and at most [TraceText.MAX_LINES] lines. Reads the Journal: never on the server thread. */
    internal suspend fun report(services: NarratorServices): List<Component> {
        val text = TraceText(services.clock.zone, services.clock.millis())
        val watch = services.style
        val lines = buildList {
            add(Out(settingsLine(services)))
            if (watch !== DisabledStyle) {
                add(Out(watch.situationLine(text.now) ?: "Style: nothing at rest, no register overused"))
                add(Out(diversityLine(watch.diversity())))
            }
            addAll(journalLines(services, text))
            add(busLine(services.bus))
        }
        return text.page(Report("style of the Director", lines), 1, null)
    }

    /** `Watch: on · line review on · tone notes on · resting openers refused`, each with why it is off. */
    private fun settingsLine(services: NarratorServices): String {
        val settings = services.settings.get()
        val style = settings.config.style
        val watch = if (services.style === DisabledStyle) "off (no Journal, or not wired: see the server log)" else "on"
        val review = when {
            !style.lineReview -> "off (style.lineReview is false)"
            settings.reflex == null -> "off (no reflex model: see /narrator status)"
            else -> "on"
        }
        val notes = if (style.toneNotes) "on" else "off"
        val openers = if (style.refuseRestingOpeners) "refused" else "only named"
        return "Watch: $watch · line review $review · tone notes $notes · resting openers $openers"
    }

    /**
     * `Latest 50 lines: openers distinct 0.84 · top register divine_bureaucracy 38% · register entropy 0.71 · crutch
     * « céleste » in 6 lines · 3 tics`: the watch's [Diversity].
     */
    internal fun diversityLine(diversity: Diversity): String {
        if (diversity.lines == 0) return "Latest lines: none measured yet"
        val register = diversity.topRegister?.let { "top register ${it.id} ${percent(diversity.topRegisterShare)} · register entropy ${decimal(diversity.registerEntropy)}" }
            ?: "no line labeled"
        val crutch = diversity.crutches.firstOrNull()?.let { (word, lines) -> "crutch « $word » in $lines ${TraceText.plural(lines, "line")}" } ?: "no crutch word"
        return "Latest ${diversity.lines} ${TraceText.plural(diversity.lines, "line")}: openers distinct ${decimal(diversity.openersDistinct)} · " +
            "$register · $crutch · ${diversity.tics} ${TraceText.plural(diversity.tics, "tic")}"
    }

    /** The review of the latest lines and the latest tone flags, from the Journal; why not, when it cannot be read. */
    private suspend fun journalLines(services: NarratorServices, text: TraceText): List<Out> {
        val journal = services.journal
        if (!journal.available) {
            return listOf(Out("Review: unknown (the Journal is unavailable: ${journal.health().lastError ?: "see the server log"})", Look.FAILURE))
        }
        val (coverage, flags) = try {
            withTimeoutOrNull(BARRIER_MS) { journal.barrier() }
            journal.read(READ_TIMEOUT_MS) { connection -> StyleReport.coverage(connection, LATEST_LINES) to StyleReport.latestFlags(connection, FLAGS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("style: the Journal could not be read: ${describeFailure(e)}")
            return listOf(Out("Review: unknown (the Journal could not be read, see the server log)", Look.FAILURE))
        }
        return buildList {
            add(
                Out(
                    if (coverage.lines == 0) "Review: the Director has said nothing yet"
                    else "Review: ${coverage.reviewed} of the Director's latest ${coverage.lines} ${TraceText.plural(coverage.lines, "line")} labeled",
                ),
            )
            if (flags.isEmpty()) {
                add(Out("Tone flags: none (no flaw at ${StyleReport.FLAG} or more)"))
            } else {
                add(Out("Tone flags, the latest ${flags.size} (a flaw at ${StyleReport.FLAG} or more):"))
                flags.forEach { add(flagLine(it, text)) }
            }
        }
    }

    /**
     * `  21:30:12 dark 0.80 (+1): "Le guichet…"`: the line's most serious flag, how many others, and as much of the line
     * as holds on one line of the chat.
     */
    private fun flagLine(line: StyleReport.Line, text: TraceText): Out {
        val (flaw, score) = line.flags.first()
        val others = if (line.flags.size > 1) " (+${line.flags.size - 1})" else ""
        val head = "  ${text.time(line.ts)} ${flaw.id} ${decimal(score)}$others: "
        return Out(head + TraceText.quote(line.text, WRAP - head.length - 2), Look.WARNING)
    }

    /** `Bus: no event lost to a full queue`, or the subscribers that lost some and how many. */
    internal fun busLine(bus: EventBus): Out {
        val lost = bus.stats().filter { it.dropped > 0 }
        if (lost.isEmpty()) return Out("Bus: no event lost to a full queue")
        return Out("Bus: lost to a full queue: ${lost.joinToString(", ") { "${it.name} ${it.dropped}" }}", Look.WARNING)
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
}
