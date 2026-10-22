package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.config.WorldNotes
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.ArsenalLine
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.BriefText
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.Decay
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.DuckPlayClock
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.IDEA_COLUMNS
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.PlayerSheet
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.ideas
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.memory.uuid
import io.github.clemsix6.narrator2.reflex.HeuristicWords
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.storyteller.Deck
import io.github.clemsix6.narrator2.storyteller.Intervention
import io.github.clemsix6.narrator2.storyteller.Interventions
import io.github.clemsix6.narrator2.style.StyleReport
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.mechanics.forgeAvailable
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.storyteller.InspireKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * What the programme reads (M6 §6.3): the first message of step 5 of a dream, the storyteller's writing room, built by the
 * code from the memory, the Journal, the Forge and the deck of `inspire`, every block within its budget (the constants of
 * the companion, checked at their maximum by a test). It starts with `<job kind="dream" run="…" step="5" of="6"
 * task="programme"/>`, then:
 *
 * - `<server_notes>`: the operator's notes on this server ([WorldNotes], M7-NOTES), left out without any;
 * - `<grimoire>` and `<review>`: the Grimoire the Dreamer just rewrote, the review of step 2;
 * - `<players>`: the players active in the last [ACTIVE_DAYS] days, the most active first, [PLAYERS_COUNT] at most: who
 *   they are, their sure traits and skills, their progression (gear, dimensions, bosses, diamonds, deaths), their favor
 *   and the attention the Narrator owes them, their recap and hooks;
 * - `<canon>`: the open arcs with their themes, the themes at rest (M4 §10: a new arc takes none of them), the running
 *   gags and their freshness, the laws and plans in force;
 * - `<history>`: what the Narrator did in the latest [HISTORY_SESSIONS] server sessions, by lever ([Interventions]) and by
 *   register, with how players took each register ([StyleReport]);
 * - `<ideas>`: every open idea, then those closed since the last programme without their pitch, each part in its own
 *   room: each time one was dealt to the Director, and what followed within [FOLLOW_UP] (a lever of its kind, or nothing);
 *   a storyteller's task reads the open ones too, in short ([taskIdeas]);
 * - `<arsenal>`: the modules the Director may deploy ([ArsenalLine], the briefing's line), or why there are none;
 * - `<season>`: the Chronicle's season in progress and the open idea of kind season;
 * - `<draws>`: three seeds of the deck ([Deck]), to shake the model's habits; recorded nowhere, dealt to nobody.
 *
 * Everything a player or a model wrote is escaped ([ModelText]). Reads only; never on the server thread. Null when no
 * player played in the last [ACTIVE_DAYS] days: there is nobody to write a programme for.
 */
internal class ProgrammeInputs(private val services: NarratorServices, private val inputs: DreamInputs) {
    private val log = Log.memory
    private val memory get() = services.memory
    private val journal get() = services.journal

    /** The first message of the programme of the dream [runId] at [now]; [since]: when the last programme ended (the previous dream). */
    suspend fun opening(runId: Long, now: Instant, since: Instant?): String? {
        val nowMs = now.toEpochMilli()
        val store = memory.store
        val active = journal.read(READ_TIMEOUT_MS) { activePlayers(it, now) }
        if (active.isEmpty()) return null
        val worldPlay = memory.clock.worldPlaySeconds(nowMs)
        val open = store.ideas(IdeaStatus.OPEN, OPEN_READ)
        val closedSince = since ?: now.minus(CLOSED_WINDOW)
        val words = HeuristicWords.bundled(services.settings.get().config.language) ?: HeuristicWords.bundled(DEFAULT_LANGUAGE)
        val read = journal.read(READ_TIMEOUT_MS) { connection ->
            val sessions = sessions(connection, now)
            val from = sessions.firstOrNull()?.first ?: now.minus(HISTORY_WINDOW)
            val period = StyleReport.Period(minOf(from, now), now)
            val closed = connection.ideas(CLOSED_SQL, closedSince, CLOSED_READ)
            val deals = deals(connection, open + closed)
            val firstDeal = deals.values.flatten().minOfOrNull { it.at }
            val lines = StyleReport.lines(connection, period)
            Read(
                sessions = sessions.size,
                from = period.from,
                interventions = Interventions.recent(connection, listOfNotNull(period.from, firstDeal).min()),
                lines = lines.size,
                registers = words?.let { StyleReport.registers(connection, period, it, lines) }.orEmpty(),
                closed = closed,
                deals = deals,
                resting = restingThemes(connection, now),
            )
        }
        return listOfNotNull(
            inputs.job(runId, STEP, "programme"),
            WorldNotes.render(services.settings.get().config.worldNotes),
            inputs.document("grimoire", store.doc(DocKey.Grimoire.key), GRIMOIRE_MAX, "(none yet)"),
            inputs.document("review", store.doc(DocKey.Review.key), REVIEW_MAX, "(none yet)"),
            players(active, nowMs),
            canon(read.resting),
            history(read),
            ideas(open, read, since, closedSince, worldPlay),
            arsenal(nowMs),
            season(open.firstOrNull { it.kind == IdeaKind.SEASON }, worldPlay),
            draws(runId, active.map { it.first }, now),
        ).joinToString("\n")
    }

    /**
     * What the programme reads from the Journal, on one connection: [lines], the Director's lines the review reads
     * ([StyleReport.lines]); [closed], the ideas closed since the last programme, those dealt first.
     */
    private class Read(
        val sessions: Int,
        val from: Instant,
        val interventions: List<Intervention>,
        val lines: Int,
        val registers: List<StyleReport.RegisterUse>,
        val closed: List<Idea>,
        val deals: Map<Long, List<Deal>>,
        val resting: List<String>,
    )

    /** One time an idea was put before the Director: when, and by what (`inspire`, `heartbeat`…). */
    private class Deal(val at: Instant, val channel: String)

    // ── <players> ───────────────────────────────────────────────────────────────────────────────────

    /** The players with play in the last [ACTIVE_DAYS] days and the seconds they played then, the most first. */
    private fun activePlayers(connection: Connection, now: Instant): List<Pair<PlayerRef, Long>> {
        val from = now.minus(Duration.ofDays(ACTIVE_DAYS))
        return connection.query(ACTIVE_SQL, from, now, now, now, from, PLAYERS_COUNT) { PlayerRef(uuid(1), getString(2)) to getLong(3) }
    }

    private suspend fun players(active: List<Pair<PlayerRef, Long>>, nowMs: Long): String {
        val share = (PLAYERS_MAX - BLOCK_ROOM) / active.size
        val items = active.map { (player, played) ->
            val sheet = memory.sheets.sheet(player.uuid, nowMs)
            if (sheet == null) "- ${player.shownName}: (no sheet yet)" else player(sheet, played, share)
        }
        return inputs.block("players", "count=\"${active.size}\" days=\"$ACTIVE_DAYS\" order=\"the most active first\"", items, PLAYERS_MAX, "")
    }

    /**
     * One player in [share] characters at most: `- Bartok « le Plongeur » · 1h50m played in 7 days (61h in all) · last seen
     * 10-01 21:45 · favor -31 ↘ · last attention 2h00m of play ago`, then their sure traits and skills, their progression,
     * their recap and hooks (cut to what is left).
     */
    private fun player(sheet: PlayerSheet, played: Long, share: Int): String {
        val name = sheet.player.shownName + (sheet.nickname?.let { " « ${BriefText.named(it)} »" } ?: "")
        val head = listOf(
            name,
            "${ToolText.duration(played * 1_000)} played in $ACTIVE_DAYS days (${ToolText.duration(sheet.playedSeconds * 1_000)} in all)",
            "last seen ${inputs.dayTime(sheet.lastSeen)}",
            sheet.favor?.let { "favor ${Favor.signed(it)}" + Favor.arrow(sheet.favorTrend).let { arrow -> if (arrow.isEmpty()) "" else " $arrow" } } ?: "favor none yet",
            sheet.lastAttentionAgoPlayedSeconds?.let { "last attention ${ToolText.duration(it * 1_000)} of play ago" } ?: "never had the Narrator's attention",
        ).joinToString(" · ")
        val traits = sheet.traits.filter { !it.retired && Decay.traitConfidence(it, sheet.playedSeconds) >= SURE }.sortedByDescending { it.intensity }
            .joinToString(", ") { trait ->
                val pole = TraitAxis.of(trait.trait)?.pole(trait.side) ?: ModelText.escape(trait.label ?: trait.trait.removePrefix(TraitAxis.FREE_PREFIX))
                "$pole ${trait.intensity}/5"
            }.ifEmpty { "none sure yet" }
        val skills = sheet.skills.joinToString(", ") { "${it.skill.id} ${it.score}" }.ifEmpty { "none rated yet" }
        val fixed = listOf(
            "- $head",
            "  traits (sure): $traits · skills: $skills",
            "  progress: ${progress(sheet)}",
        ).map { ModelText.cut(it, LINE_MAX) }
        val room = share - fixed.sumOf { it.length + 1 } - DOC_LABELS
        val recap = doc(sheet, SheetSection.RECAP, maxOf(MIN_DOC, room * 2 / 3))
        val hooks = doc(sheet, SheetSection.HOOKS, maxOf(MIN_DOC, room - room * 2 / 3))
        return ModelText.cut((fixed + "  recap: $recap" + "  hooks: $hooks").joinToString("\n"), share)
    }

    private fun doc(sheet: PlayerSheet, section: SheetSection, max: Int): String =
        sheet.docs[section]?.let { ModelText.cut(ModelText.escape(it.content), max) } ?: "(not written yet)"

    /** `gear 3/5 (iron_chestplate, diamond_sword) · Nether since 09-28 · End never · bosses: elder_guardian · 41 diamonds · 87 deaths (mostly lava) · 34 advancements`. */
    private fun progress(sheet: PlayerSheet): String {
        val stats = sheet.stats
        val parts = buildList {
            stats["gear"]?.value?.let { tier ->
                val detail = stats["gear"]?.detail
                add("gear ${tier.toLong()}/5 (${listOfNotNull(detail?.text("armor") ?: "no armor", detail?.text("weapon")).joinToString(", ")})")
            }
            stats["exploration"]?.detail?.let { exploration ->
                add(exploration.text("nether_at")?.let { "Nether since ${day(it)}" } ?: "Nether never")
                add(exploration.text("end_at")?.let { "End since ${day(it)}" } ?: "End never")
            }
            (stats["kills"]?.detail?.get("bosses") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }
                ?.let { add("bosses: ${it.joinToString { boss -> ModelText.escape(boss) }}") }
            ((stats["mining"]?.detail?.get("ores") as? JsonObject)?.get("diamond") as? JsonPrimitive)?.longOrNull?.let { add("$it diamonds") }
            stats["deaths"]?.value?.toLong()?.let { deaths ->
                val top = ((stats["deaths"]?.detail?.get("top") as? JsonArray)?.firstOrNull() as? JsonArray)?.firstOrNull() as? JsonPrimitive
                add("$deaths death${if (deaths == 1L) "" else "s"}" + (top?.content?.takeIf { deaths > 0 }?.let { " (mostly ${ModelText.escape(it)})" } ?: ""))
            }
            stats["advancements"]?.value?.toLong()?.let { add("$it advancements") }
        }
        return parts.joinToString(" · ").ifEmpty { "(not computed yet)" }
    }

    /** The day of a time of the stats (ISO, already escaped), `09-28`; the text itself when it does not read. */
    private fun day(iso: String): String = runCatching { Instant.parse(iso) }.getOrNull()?.let(inputs::day) ?: iso

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(ModelText::escape)

    // ── <canon> ─────────────────────────────────────────────────────────────────────────────────────

    /** The open arcs (with their themes), the themes at rest, the running gags (with their freshness), the laws and plans in force. */
    private suspend fun canon(resting: List<String>): String {
        val open = memory.store.canon(CanonFilter(kinds = CANON_KINDS, open = true, limit = CANON_READ))
        val arcs = open.filter { it.kind == CanonKind.ARC }
        val gags = open.filter { it.kind == CanonKind.RUNNING_GAG }
        val rules = open.filter { it.kind == CanonKind.LAW || it.kind == CanonKind.PLAN }
        val rest = resting.takeIf { it.isNotEmpty() }
            ?.let { "Themes at rest (${MemoryLimits.THEME_REST_PLAY_HOURS}h of world play once their arc closed): ${it.joinToString(" · ")}" }
            ?: "Themes at rest: none"
        val items = inputs.shared(arcs.map(inputs::canonItem) + gags.map(::gagItem) + rules.map(inputs::canonItem), CANON_MAX - rest.length)
        val arcLines = items.take(arcs.size).ifEmpty { listOf("(no arc open)") }
        return inputs.block(
            "canon", "arcs=\"${arcs.size}\" gags=\"${gags.size}\" laws_and_plans=\"${rules.size}\"",
            arcLines + rest + items.drop(arcs.size), CANON_MAX, "",
        )
    }

    /** `- C-27 running_gag → Bartok (worn, 5 uses, last 10-01 21:00): ` and its statement. */
    private fun gagItem(entry: CanonEntry): Pair<String, String> {
        val to = entry.players.takeIf { it.isNotEmpty() }?.let { " → ${ToolText.names(it)}" }.orEmpty()
        val state = listOfNotNull(
            (entry.freshness ?: GagState.FRESH).id,
            "${entry.uses} use${if (entry.uses == 1) "" else "s"}",
            entry.lastUsedAt?.let { "last ${inputs.dayTime(it)}" },
        ).joinToString(", ")
        return "- ${entry.ref} running_gag$to ($state): " to ModelText.escape(entry.statement)
    }

    /**
     * The themes whose last arc closed less than [MemoryLimits.THEME_REST_PLAY_HOURS] hours of world play ago
     * (M4 §10, as the store counts them: the sessions played since, an open one up to now), the latest
     * closed first, [RESTING_COUNT] at most: `vengeance_du_warden until about 10-02 15:40 (C-60 closed 10-01
     * 19:40)`. A theme an open arc holds is left out: that arc's line names it.
     */
    private fun restingThemes(connection: Connection, now: Instant): List<String> {
        val closed = connection.query(CLOSED_THEMES_SQL) { Triple(getString(1), getLong(2), instant(3)) }
        if (closed.isEmpty()) return emptyList()
        val running = connection.query("SELECT session_id FROM sessions WHERE left_at IS NULL") { getLong(1) }.toSet()
        val rest = Duration.ofHours(MemoryLimits.THEME_REST_PLAY_HOURS.toLong()).seconds
        val themes = mutableListOf<String>()
        for ((theme, id, closedAt) in closed) {
            val left = rest - DuckPlayClock.unionSeconds(connection, closedAt, now, running)
            if (left <= 0) break // the themes closed before it have been played over longer still
            themes += "${ModelText.escape(theme)} until about ${inputs.dayTime(now.plusSeconds(left))} (C-$id closed ${inputs.dayTime(closedAt)})"
            if (themes.size >= RESTING_COUNT) break
        }
        return themes
    }

    // ── <history> ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The starts and ends of the latest [HISTORY_SESSIONS] server sessions (play with nobody away for
     * [DreamInputs.SESSION_GAP] or more), the latest last, within [HISTORY_WINDOW].
     */
    private fun sessions(connection: Connection, now: Instant): List<Pair<Instant, Instant>> {
        val spans = connection.query(SESSIONS_SQL, now, now, now.minus(HISTORY_WINDOW)) { instant(1) to instant(2) }
        val islands = mutableListOf<Pair<Instant, Instant>>()
        for ((from, to) in spans) {
            val last = islands.lastOrNull()
            if (last != null && from <= last.second.plus(DreamInputs.SESSION_GAP)) islands[islands.size - 1] = last.first to maxOf(last.second, to) else islands += from to to
        }
        return islands.takeLast(HISTORY_SESSIONS)
    }

    private fun history(read: Read): String {
        val levers = read.interventions.filter { it.at >= read.from && !it.key.startsWith(SAY) }.groupBy { it.key }.entries
            .sortedByDescending { (_, uses) -> uses.maxOf { it.at } }
            .take(LEVERS_SHOWN)
            .map { (key, uses) ->
                val targets = uses.flatMap { it.targets }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .joinToString(", ") { (name, count) -> if (uses.size == 1) ModelText.name(name) else "${ModelText.name(name)} $count" }
                    .let { if (it.isEmpty()) "everyone" else it }
                "- ${Interventions.label(key)} ×${uses.size} ($targets), last ${inputs.dayTime(uses.maxOf { it.at })}"
            }
        val reviewed = read.registers.sumOf { it.lines }
        val lines = buildList {
            add("Levers pulled, the latest first:")
            addAll(levers.ifEmpty { listOf("- none") })
            add("Lines: ${read.lines} said, $reviewed reviewed; by register, and how players took them:")
            addAll(read.registers.map(StyleReport::registerLine).ifEmpty { listOf("- (no line reviewed)") })
        }
        return inputs.block("history", "from=\"${inputs.dayTime(read.from)}\" sessions=\"${read.sessions}\"", lines, HISTORY_MAX, "")
    }

    // ── <ideas> ─────────────────────────────────────────────────────────────────────────────────────

    /** Each time one of [ideas] was put before the Director (`recalls`), by idea id, the oldest first. */
    private fun deals(connection: Connection, ideas: List<Idea>): Map<Long, List<Deal>> {
        if (ideas.isEmpty()) return emptyMap()
        return connection.query(DEALS_SQL, longArray(ideas.map { it.ref.id })) { getLong(1) to Deal(instant(2), getString(3)) }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * `<ideas open="12" closed="4" closed_since="10-01 21:30">`, in two parts, neither taking the other's room (M6 review 2,
     * finding 1): the [open] ideas, the latest first, all of them, their pitches cut so that they fit (water-filling,
     * [OPEN_PITCH] at most); then the ideas closed since the last programme, without their pitch, those dealt first (what
     * followed them is the storyteller's feedback), [CLOSED_MAX] characters of them at most, the rest counted.
     */
    private fun ideas(open: List<Idea>, read: Read, since: Instant?, closedSince: Instant, worldPlay: Long): String {
        val after = since?.let { " closed_since=\"${inputs.dayTime(it)}\"" }.orEmpty()
        if (open.isEmpty() && read.closed.isEmpty()) return "<ideas open=\"0\"$after>(none open, none closed lately)</ideas>"
        val head = listOf("<ideas open=\"${open.size}\" closed=\"${read.closed.size}\"$after>", if (open.isEmpty()) "Open: none" else "Open, the latest first:")
        val part = "Closed since ${inputs.dayTime(closedSince)}"
        val closed = mutableListOf(if (read.closed.isEmpty()) "$part: none" else "$part, those dealt first:")
        var room = CLOSED_MAX
        for (idea in read.closed) {
            val line = ModelText.cut(closedLine(idea, read), CLOSED_LINE_MAX)
            if (line.length + 1 > room) break
            closed += line
            room -= line.length + 1
        }
        val left = read.closed.size - (closed.size - 1)
        if (left > 0) closed += "(+$left more closed)"
        val tail = closed + "</ideas>"
        val lines = open.map { openLine(it, read, worldPlay) }
        val caps = BriefText.share(lines.map { it.length }, IDEAS_MAX - (head + tail).sumOf { it.length + 1 } - lines.size)
        return (head + lines.mapIndexed { i, line -> ModelText.cut(line, caps[i]) } + tail).joinToString("\n")
    }

    /**
     * `- P-41 challenge · Bartok, Mimosa · « La course au pic » · 2d0h of play left · n4 f4 e4 sports_commentary · dealt 2:
     * …: ` and its pitch, cut to [OPEN_PITCH]: its [head], then the rest; the pitch comes last, the first cut when the room
     * is short.
     */
    private fun openLine(idea: Idea, read: Read, worldPlay: Long): String {
        val left = ToolText.duration((idea.expiresPlaySeconds - worldPlay).coerceAtLeast(0) * 1_000)
        val notes = "n${idea.novelty} f${idea.funScore} e${idea.fairness}" + (idea.register?.let { " ${it.id}" } ?: "")
        return "- ${head(idea)} · $left of play left · $notes · ${dealt(idea, read)}: " + ModelText.cut(ModelText.escape(idea.pitch), OPEN_PITCH)
    }

    /** `- P-38 law « La loi des bottes » · dropped 10-01 22:12 (Mimosa ne perd plus ses bottes.) · never dealt`: what became of it, no pitch. */
    private fun closedLine(idea: Idea, read: Read): String {
        val reason = ModelText.cut(ModelText.escape(idea.closeReason.orEmpty()), REASON_MAX)
        return "- ${idea.ref} ${idea.kind.id} « ${ModelText.escape(idea.title)} » · ${idea.status.id} ${idea.closedAt?.let(inputs::dayTime).orEmpty()} ($reason) · ${dealt(idea, read)}"
    }

    /** `dealt 2: 10-01 20:31 inspire → challenge obtain 4m later; 10-01 21:12 heartbeat → nothing`, the latest [DEALS_SHOWN]. */
    private fun dealt(idea: Idea, read: Read): String {
        val deals = read.deals[idea.ref.id].orEmpty()
        if (deals.isEmpty()) return if (idea.dealt == 0) "never dealt" else "dealt ${idea.dealt}"
        val shown = deals.takeLast(DEALS_SHOWN).joinToString("; ") { deal ->
            val then = read.interventions.filter { it.at > deal.at && it.at <= deal.at.plus(FOLLOW_UP) && follows(idea, it.key) }.minByOrNull { it.at }
            val after = then?.let { "${Interventions.label(it.key)} ${ToolText.duration(it.at.toEpochMilli() - deal.at.toEpochMilli())} later" } ?: "nothing"
            "${inputs.dayTime(deal.at)} ${deal.channel} → $after"
        }
        return "dealt ${maxOf(idea.dealt, deals.size)}: $shown" + if (deals.size > DEALS_SHOWN) " (+${deals.size - DEALS_SHOWN} before)" else ""
    }

    /**
     * Whether a lever [key] carries out an idea of its kind: a challenge by a challenge, a punishment by a punishment…;
     * for a kind without a lever of its own (season, arc_step, forge, twist), any lever pulled, but a line said: the
     * Narrator speaks within the minute of almost anything (M6 review 2, finding 2).
     */
    private fun follows(idea: Idea, key: String): Boolean = when (idea.kind) {
        IdeaKind.CHALLENGE -> key.startsWith("challenge:")
        IdeaKind.DUEL -> key == Interventions.DUEL
        IdeaKind.PUNISHMENT -> key.startsWith("punish:")
        IdeaKind.FAVOR -> key.startsWith("favor:") || key == Interventions.ITEM || key == Interventions.BOOK
        IdeaKind.LAW -> key == Interventions.LAW
        IdeaKind.PLAN -> key == Interventions.PLAN
        IdeaKind.MODULE -> key == "module:${idea.module}"
        IdeaKind.SPEECH -> key == Interventions.BOOK || key == Interventions.PROPHECY
        IdeaKind.SEASON, IdeaKind.ARC_STEP, IdeaKind.FORGE, IdeaKind.TWIST -> !key.startsWith(SAY)
    }

    // ── <arsenal>, <season>, <draws> ────────────────────────────────────────────────────────────────

    /** The briefing's `Arsenal` line (M5 §9.3), or why there is no module to deploy nor order. */
    private fun arsenal(nowMs: Long): String {
        val line = when {
            !forgeAvailable(services) -> "(the Forge is off now: no idea of kind module nor forge)"
            else -> try {
                ArsenalLine.render(services.forge.arsenal.list(), nowMs)
            } catch (e: Exception) {
                log.warn("programme: the arsenal unavailable: ${e.message ?: e.javaClass.simpleName}")
                "(unavailable: ${e.message ?: e.javaClass.simpleName})"
            }
        }
        return inputs.block("arsenal", "", listOf(line), ARSENAL_MAX, "")
    }

    /** The Chronicle's season in progress, then the open idea of kind season (and whether the Chronicle's season began after it). */
    private suspend fun season(idea: Idea?, worldPlay: Long): String {
        val season = memory.store.chronicle(ChronicleLevel.SEASON, SEASONS_READ).firstOrNull { it.open }
        val lines = buildList {
            if (season == null) {
                add("Chronicle: no season open yet (the first sessions start one)")
            } else {
                add(
                    "Chronicle: ${season.ref} « ${ModelText.escape(season.title)} » ${inputs.dayTime(season.from)}–${inputs.dayTime(season.to)}, " +
                        "${season.covers.size} session${if (season.covers.size == 1) "" else "s"} (it closes at ${DreamInputs.SEASON_SESSIONS} sessions or ${DreamInputs.SEASON_DAYS} days)",
                )
                add(ModelText.cut(ModelText.escape(season.summary), SEASON_SUMMARY))
            }
            if (idea == null) {
                add("Season idea: none open")
            } else {
                val left = ToolText.duration((idea.expiresPlaySeconds - worldPlay).coerceAtLeast(0) * 1_000)
                val older = if (season != null && idea.createdAt < season.from) " (written before this season of the Chronicle began)" else ""
                add(
                    "Season idea: ${idea.ref} « ${ModelText.escape(idea.title)} », written ${inputs.dayTime(idea.createdAt)}, $left of play left$older: " +
                        ModelText.cut(ModelText.escape(idea.pitch), SEASON_PITCH),
                )
            }
        }
        return inputs.block("season", "", lines, SEASON_MAX, "")
    }

    /**
     * Three seeds of the deck for [players] (`inspire`'s draw without the programme's ideas, which `<ideas>` shows): a lever
     * × a hook of the memory × a twist × a register. The hooks are cut until the three fit [DRAWS_MAX]. Nothing is recorded:
     * the storyteller's reading deals nothing to the Director.
     */
    private suspend fun draws(runId: Long, players: List<PlayerRef>, now: Instant): String {
        val lines = try {
            val table = Deck.table(services, players, named = true, InspireKind.ANY, now).copy(ideas = emptyList())
            val seeds = Deck.draw(table, Random(runId * SEED_FACTOR + now.toEpochMilli())).seeds
            if (seeds.isEmpty()) {
                listOf("(no seed: ${table.missing.joinToString(" · ") { it.text }.ifEmpty { "no lever available" }})")
            } else {
                HOOK_CUTS.map { cut -> seeds.map { seed -> seed.copy(hook = seed.hook?.let { it.copy(text = ModelText.cut(it.text, cut)) }).line() } }
                    .firstOrNull { lines -> lines.sumOf { it.length + 1 } + DRAWS_TAGS <= DRAWS_MAX } ?: seeds.map { ModelText.cut(it.line(), DRAWS_LINE) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("programme: no draw: ${e.message ?: e.javaClass.simpleName}")
            listOf("(unavailable: ${e.message ?: e.javaClass.simpleName})")
        }
        return inputs.block("draws", "", lines, DRAWS_MAX, "")
    }

    companion object {
        /** The step of the programme in a dream. */
        const val STEP = 5

        /**
         * `P-41 challenge · Bartok, Mimosa · « La course au pic »`: an open idea in short (`everyone` for whoever is online),
         * the head of its line in `<ideas>`.
         */
        fun head(idea: Idea): String {
            val who = if (idea.players.isEmpty()) "everyone" else ToolText.names(idea.players)
            return "${idea.ref} ${idea.kind.id} · $who · « ${ModelText.escape(idea.title)} »"
        }

        /**
         * The [open] ideas as a storyteller's task reads them, after its brief (M6-FIX-14): `<ideas open="3">`, then the
         * [head] of each, the latest first, as the programme's `<ideas>` lists them. No pitch nor notes: enough for a new
         * idea to take neither the title nor the substance of an open one, which the Director already has. Twelve ideas
         * stay open at most: about 1 000 characters.
         */
        fun taskIdeas(open: List<Idea>): String {
            if (open.isEmpty()) return "<ideas open=\"0\">(none open)</ideas>"
            val top = listOf("<ideas open=\"${open.size}\">", "Open, the latest first (the Narrator already has them):")
            return (top + open.map { "- ${head(it)}" } + "</ideas>").joinToString("\n")
        }

        /** A player active in the last days, for whom the programme writes. */
        const val ACTIVE_DAYS = 7L
        const val PLAYERS_COUNT = 8

        /** Server sessions `<history>` covers, looked for within [HISTORY_WINDOW]. */
        const val HISTORY_SESSIONS = 3
        val HISTORY_WINDOW: Duration = Duration.ofDays(30)

        /** Without a previous dream, the closed ideas shown are those of this window. */
        val CLOSED_WINDOW: Duration = Duration.ofDays(7)

        /** A lever pulled within this after an idea was dealt carries it out (M6 §6.3). */
        val FOLLOW_UP: Duration = Duration.ofMinutes(15)

        /** Themes at rest named in `<canon>`, at most: about 80 characters each. */
        const val RESTING_COUNT = 8

        // Budgets in characters (M6 §6.3), tags included.
        const val GRIMOIRE_MAX = DreamInputs.GRIMOIRE_MAX
        const val REVIEW_MAX = DreamInputs.REVIEW_MAX
        const val PLAYERS_MAX = 6_000
        const val CANON_MAX = 3_000
        const val HISTORY_MAX = 2_500
        const val IDEAS_MAX = 4_000

        /** Of [IDEAS_MAX], the room of the closed ideas' lines (M6 review 2, D5): four of [CLOSED_LINE_MAX] at least. */
        const val CLOSED_MAX = 1_000
        const val CLOSED_LINE_MAX = 240

        /** Characters of an open idea's pitch at most, less when the room is short. */
        const val OPEN_PITCH = 100
        const val ARSENAL_MAX = 1_250
        const val SEASON_MAX = 1_500
        const val DRAWS_MAX = 800

        /** The longest opening: the sum of its blocks, plus the job line. */
        const val STEP_MAX = 300 + WorldNotes.BLOCK_MAX + GRIMOIRE_MAX + REVIEW_MAX + PLAYERS_MAX + CANON_MAX + HISTORY_MAX + IDEAS_MAX + ARSENAL_MAX + SEASON_MAX + DRAWS_MAX

        private const val READ_TIMEOUT_MS = 15_000L
        private const val DEFAULT_LANGUAGE = "fr"
        private const val SAY = "say:"

        /** A sure trait (as `inspire`'s hooks count it): its effective confidence from this. */
        private const val SURE = 0.75
        private const val BLOCK_ROOM = 100
        private const val LINE_MAX = 400
        private const val DOC_LABELS = 20
        private const val MIN_DOC = 60
        private const val CANON_READ = 200
        private const val LEVERS_SHOWN = 12
        /** Open ideas read: the store keeps twelve open (M6 §6.2), this leaves room. */
        private const val OPEN_READ = 50

        /** Closed ideas read at most: a programme writes eight at most, a task three; this leaves room. */
        private const val CLOSED_READ = 100
        private const val DEALS_SHOWN = 3
        private const val REASON_MAX = 80
        private const val SEASONS_READ = 50
        private const val SEASON_SUMMARY = 700
        private const val SEASON_PITCH = 300
        private const val SEED_FACTOR = 1_000_003L
        private const val DRAWS_TAGS = 17
        private const val DRAWS_LINE = 255
        private val HOOK_CUTS = listOf(Deck.HOOK_LINE_MAX, 100, 70, 45)
        private val CANON_KINDS = setOf(CanonKind.ARC, CanonKind.RUNNING_GAG, CanonKind.LAW, CanonKind.PLAN)

        /** The players' play in the window (an open session runs to now), the most first, then by name. */
        private const val ACTIVE_SQL = """
            SELECT player_uuid, name, played FROM (
                SELECT s.player_uuid, max(p.name) AS name,
                       sum(greatest(date_diff('second', greatest(s.joined_at, ?), least(coalesce(s.left_at, ?), ?)), 0))::BIGINT AS played
                FROM sessions s JOIN players p ON p.player_uuid = s.player_uuid
                WHERE coalesce(s.left_at, ?) > ?
                GROUP BY s.player_uuid
            ) WHERE played > 0 ORDER BY played DESC, name LIMIT ?"""

        /** The sessions of the players that end after the window starts (an open one ends now), by start. */
        private const val SESSIONS_SQL = "SELECT joined_at, coalesce(left_at, ?) FROM sessions WHERE coalesce(left_at, ?) > ? ORDER BY joined_at"

        /** Each time an idea was put before the Director: `recalls` rows of the ideas (the storyteller's own reading leaves none). */
        private const val DEALS_SQL = "SELECT ref_id, ts, channel FROM recalls WHERE list_contains(?, ref_id) ORDER BY ts"

        /** The ideas closed since a time, however old: those dealt first (what followed them is the feedback), then the latest closed. */
        private const val CLOSED_SQL = "SELECT $IDEA_COLUMNS FROM ideas WHERE status <> 'open' AND closed_at >= ? " +
            "ORDER BY dealt > 0 DESC, closed_at DESC, id DESC LIMIT ?"

        /**
         * The last closed arc of each theme no open arc holds (archived arcs left out, as the store does), the
         * latest closed first: theme, id, when it closed.
         */
        private const val CLOSED_THEMES_SQL = """
            SELECT theme, arg_max(id, resolved_at), max(resolved_at) FROM canon
            WHERE kind = 'arc' AND theme IS NOT NULL AND resolved_at IS NOT NULL AND NOT archived
              AND theme NOT IN (SELECT theme FROM canon WHERE kind = 'arc' AND theme IS NOT NULL AND status IN ('planned', 'active') AND NOT archived)
            GROUP BY theme ORDER BY 3 DESC, 1"""
    }
}
