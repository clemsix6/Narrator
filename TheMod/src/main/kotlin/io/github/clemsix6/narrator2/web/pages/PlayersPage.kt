package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.doubleOrNull
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.intOrNull
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.uuid
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import java.sql.Connection
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * `/players` and `/players/<name>` (M6 §8.4): every player ever seen, then one player: who they are, what they are
 * doing now (from the snapshot of the players online), the Narrator's effects on them (the guards' snapshot), their
 * full sheet as the model reads it (`player_profile`, [SheetDetail.FULL]) and their timeline: events, public chat,
 * prayers, and the Narrator's lines addressed to them or naming them.
 *
 * A name is the current one, a former one (the latest player seen under it), or a UUID. Never the private messages:
 * they are in no table this page reads. The sheet is read by the memory's own sheets, in the dashboard's turn
 * ([PageContext.inTurn]); the rest in one read of the Journal.
 */
object PlayersPage : WebPage {
    override val root = "players"

    override val label = "Players"

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> list(request, context)
        1 -> player(request.path.single(), request, context)
        else -> null
    }

    // ── Every player ────────────────────────────────────────────────────────────────────────────────

    /** A row of `players`, with the favor the memory computes (`mem_favor`). */
    private class Known(
        val uuid: UUID, val name: String, val firstSeen: Instant, val lastSeen: Instant, val sessions: Int, val playedSeconds: Long,
        val op: Boolean, val favor: Double?, val disrespect: Long?,
    )

    private fun list(request: WebRequest, context: PageContext): Page {
        val paging = Paging.of(request)
        val where = Where()
        request.param("name")?.let { name ->
            where.add("(contains(lower(p.name), lower(?)) OR p.player_uuid IN (SELECT player_uuid FROM player_names WHERE contains(lower(name), lower(?))))", name, name)
        }
        val (rows, more) = paging.rows(
            context.read { c ->
                c.query(
                    "SELECT p.player_uuid, p.name, p.first_seen, p.last_seen, p.sessions, p.playtime_s, p.is_op, f.favor, f.disrespect " +
                        "FROM players p LEFT JOIN mem_favor f ON f.player = p.name WHERE $where ORDER BY p.last_seen DESC, p.name LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { Known(uuid(1), getString(2), instant(3), instant(4), getInt(5), getLong(6), getBoolean(7), doubleOrNull(8), longOrNull(9)) }
            },
        )
        val online = context.services.players
        return Page("Players") {
            filters(link(root), request, listOf(Filter.Field("name", "Name")))
            if (rows.isEmpty()) p("No player seen yet.", "muted")
            else table(listOf("Player", "State", "First seen", "Last seen", "#Sessions", "#Played", "#Favor", "#Disrespect")) {
                rows.forEach { known ->
                    val live = online.get(known.uuid)
                    row(if (live == null) "muted" else null) {
                        cell {
                            a(link(root, known.name), known.name)
                            if (known.op) small(" op")
                        }
                        cell(state(live))
                        cell(context.time(known.firstSeen))
                        cell(context.time(known.lastSeen))
                        number(known.sessions)
                        number(TraceText.duration(1_000 * (known.playedSeconds + running(live, context))))
                        number(known.favor?.let { String.format(Locale.ROOT, "%.0f", it) })
                        number(known.disrespect)
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    // ── One player ──────────────────────────────────────────────────────────────────────────────────

    /** Who a player is, from `players` and `player_names`; for a player online not yet written, from the snapshot. */
    private class Identity(
        val ref: PlayerRef, val firstSeen: Instant?, val lastSeen: Instant?, val sessions: Int, val playedSeconds: Long, val op: Boolean,
        val formerNames: List<String>,
    )

    /** One line of the timeline: an event, or a line of the chat (`chat:public`, `chat:pray`, `chat:narrator`…). */
    private class Entry(val ts: Instant, val kind: String, val what: String?, val dim: String?, val x: Int?, val y: Int?, val z: Int?)

    private fun player(typed: String, request: WebRequest, context: PageContext): Page? {
        val services = context.services
        val asUuid = runCatching { UUID.fromString(typed) }.getOrNull()
        val live = services.players.byName(typed) ?: asUuid?.let { services.players.get(it) }
        val show = request.choice("show", SHOW)
        val paging = Paging.of(request)
        val read = context.read { c ->
            val identity = identity(c, live?.ref?.uuid ?: asUuid, typed) ?: return@read null
            identity to timeline(c, identity.ref, show, paging)
        }
        val identity = read?.first ?: live?.let { Identity(it.ref, null, null, 0, 0, it.isOp, emptyList()) } ?: return null
        val (entries, more) = paging.rows(read?.second.orEmpty())
        val sheet = sheet(identity.ref.uuid, context)
        return Page(identity.ref.name) {
            dl {
                item("Player") {
                    text(identity.ref.name)
                    if (identity.formerNames.isNotEmpty()) small(" formerly ${identity.formerNames.joinToString()}")
                    if (identity.op) small(" · operator")
                }
                item("UUID", identity.ref.uuid.toString())
                identity.firstSeen?.let { item("First seen", context.time(it)) }
                identity.lastSeen?.let { item("Last seen", context.time(it)) }
                item("Played", "${TraceText.duration(1_000 * (identity.playedSeconds + running(live, context)))} in ${identity.sessions} ${TraceText.plural(identity.sessions, "session")}")
                item("Now", state(live))
                live?.let { now(it) }
            }
            effects(identity.ref.uuid, context)
            h2("Sheet")
            p("As the model reads it (player_profile).", "muted")
            when (sheet) {
                is Sheet.Shown -> pre(sheet.text)
                is Sheet.Missing -> p(sheet.why, "muted")
            }
            h2("Timeline")
            filters(link(root, identity.ref.name), request, listOf(Filter.Choice("show", "Show", SHOW)))
            p(SHOWN[show].orEmpty(), "muted")
            if (entries.isEmpty()) p("Nothing yet.", "muted")
            else table(listOf("When", "Kind", "What", "Where")) {
                entries.forEach { entry ->
                    row(if (entry.kind.startsWith(CHAT)) null else "muted") {
                        cell(context.time(entry.ts))
                        cell(entry.kind)
                        cell(entry.what)
                        cell(entry.dim?.let { "$it ${entry.x} ${entry.y} ${entry.z}" })
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    /** The player of [uuid], else the one named [typed] (current name first, then a former one, the latest seen first). */
    private fun identity(c: Connection, uuid: UUID?, typed: String): Identity? {
        val found = if (uuid != null) {
            c.query("SELECT player_uuid, name, first_seen, last_seen, sessions, playtime_s, is_op FROM players WHERE player_uuid = ?", uuid, timeoutSeconds = QUERY_SECONDS) { identityRow() }
        } else {
            c.query(
                "SELECT player_uuid, name, first_seen, last_seen, sessions, playtime_s, is_op FROM players WHERE lower(name) = lower(?) " +
                    "OR player_uuid IN (SELECT player_uuid FROM player_names WHERE lower(name) = lower(?)) " +
                    "ORDER BY lower(name) = lower(?) DESC, last_seen DESC LIMIT 1",
                typed, typed, typed, timeoutSeconds = QUERY_SECONDS,
            ) { identityRow() }
        }.firstOrNull() ?: return null
        val former = c.query(
            "SELECT name FROM player_names WHERE player_uuid = ? AND name <> ? ORDER BY first_seen, name", found.ref.uuid, found.ref.name,
            timeoutSeconds = QUERY_SECONDS,
        ) { getString(1) }
        return Identity(found.ref, found.firstSeen, found.lastSeen, found.sessions, found.playedSeconds, found.op, former)
    }

    private fun java.sql.ResultSet.identityRow() = Identity(PlayerRef(uuid(1), getString(2)), instant(3), instant(4), getInt(5), getLong(6), getBoolean(7), emptyList())

    /**
     * The timeline of [player], the latest first: their events (without the busiest kinds unless [show] is `events`),
     * their public chat and prayers, and the Narrator's lines addressed to them or naming them.
     */
    private fun timeline(c: Connection, player: PlayerRef, show: String?, paging: Paging): List<Entry> {
        val events = "SELECT id, ts, type AS kind, " +
            "CASE WHEN subject IS NULL THEN bare(other) WHEN other IS NULL THEN bare(subject) ELSE bare(subject) || ' → ' || bare(other) END AS what, " +
            "bare(dim) AS dim, x, y, z FROM events WHERE player_uuid = ? AND type NOT IN (${(if (show == EVENTS) NEVER else BUSY).joinToString { "'$it'" }})"
        val chat = "SELECT id, ts, 'chat:' || channel AS kind, text AS what, NULL AS dim, NULL AS x, NULL AS y, NULL AS z FROM chat " +
            "WHERE (channel IN ('public', 'pray') AND player_uuid = ?) " +
            "OR (channel IN ('narrator', 'operator') AND (list_contains(recipients, ?) OR contains(lower(text), lower(?))))"
        val (union, values) = when (show) {
            EVENTS -> events to arrayOf<Any?>(player.uuid)
            CHAT_ONLY -> chat to arrayOf<Any?>(player.uuid, player.name, player.name)
            else -> "$events UNION ALL $chat" to arrayOf<Any?>(player.uuid, player.uuid, player.name, player.name)
        }
        return c.query(
            "SELECT ts, kind, what, dim, x, y, z FROM ($union) ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
            *values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
        ) { Entry(instant(1), getString(2), getString(3), getString(4), intOrNull(5), intOrNull(6), intOrNull(7)) }
    }

    /** What the page says of the sheet: the text the model reads, or why there is none. */
    private sealed interface Sheet {
        class Shown(val text: String) : Sheet

        class Missing(val why: String) : Sheet
    }

    private fun sheet(player: UUID, context: PageContext): Sheet {
        val memory = context.services.memory
        if (!memory.available) return Sheet.Missing("The memory is off (${memory.unavailableReason ?: "no reason given"}): no sheet.")
        return try {
            val sheet = context.inTurn { memory.sheets.sheet(player, context.now) } ?: return Sheet.Missing("Never seen by the memory: no sheet yet.")
            Sheet.Shown(memory.sheets.render(sheet, SheetDetail.FULL, context.now))
        } catch (e: JournalUnavailableException) {
            Sheet.Missing("The sheet could not be read: ${e.message}.")
        } catch (e: JournalTimeoutException) {
            Sheet.Missing("The sheet could not be read: ${e.message}; reload the page in a moment.")
        }
    }

    /** What a player online is doing: mode, health, food, where. */
    private fun Html.now(live: LivePlayer) {
        item("Mode", live.gameMode)
        item("Health", "${live.health.toInt()}/${live.maxHealth.toInt()} · food ${live.food}")
        item("Where", "${live.pos.dim.removePrefix("minecraft:")} ${live.pos.x} ${live.pos.y} ${live.pos.z}" + (live.biome?.let { " · ${it.removePrefix("minecraft:")}" }.orEmpty()))
        live.vehicle?.let { item("Riding", it) }
    }

    /** The Narrator's effects on the player now (the guards' snapshot): nothing after the purpose of one without an end. */
    private fun Html.effects(player: UUID, context: PageContext) {
        val effects = context.services.guards.activeOn(player)
        h2("The Narrator's effects")
        if (effects.isEmpty()) {
            p("None.", "muted")
            return
        }
        table(listOf("Kind", "What", "Until")) {
            effects.forEach { effect ->
                row(if (effect.harmful) "warning" else null) {
                    cell(effect.kind)
                    cell(effect.what)
                    cell(effect.until?.let { context.time(Instant.ofEpochMilli(it)) })
                }
            }
        }
    }

    /** `online, active`, `online, idle`, or `offline`. */
    private fun state(live: LivePlayer?): String = when {
        live == null -> "offline"
        live.idle -> "online, idle"
        else -> "online, active"
    }

    /** Seconds of the running session of a player online. */
    private fun running(live: LivePlayer?, context: PageContext): Long = live?.let { ((context.now - it.joinedAt) / 1_000).coerceAtLeast(0) } ?: 0

    private const val CHAT = "chat:"

    /** The timeline's filter: the chat only, or the events only (every kind but item pickups and stats). */
    private const val CHAT_ONLY = "chat"
    private const val EVENTS = "events"
    private val SHOW = listOf(CHAT_ONLY, EVENTS)

    private val SHOWN = mapOf(
        null to "Events (but the busiest: blocks broken and placed, damage dealt, pickups), public chat, prayers, and the Narrator's lines to or about them.",
        CHAT_ONLY to "Public chat, prayers, and the Narrator's lines to or about them.",
        EVENTS to "Every event but item pickups and stats.",
    )

    /** Event kinds never in a timeline (as in the `timeline` view), and those left out unless the events are asked alone. */
    private val NEVER = listOf("pickup", "stat")
    private val BUSY = NEVER + listOf("block_break", "block_place", "damage_dealt", "projectile_hit", "container_open")
}
