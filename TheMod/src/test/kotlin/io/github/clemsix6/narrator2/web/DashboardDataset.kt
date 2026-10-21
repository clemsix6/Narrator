package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.RuleConditions
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.memory.DuckPlayerSheets
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.memory.uuidArray
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.style.AtRest
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.style.Diversity
import io.github.clemsix6.narrator2.style.RestingOpener
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeStyle
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Two evenings of a small server for the pages of M6-12, written through the real Journal (bus, mapping, writer) and,
 * for the memory, by `Journal.write` jobs as the store writes its rows. Every text a player or a model could write holds
 * a trap ([SCRIPT_TRAP], [QUOTE_TRAP]); the private messages, a file of the server and the dashboard's token hold
 * secrets that no page may show ([PRIVATE_SECRET], [FILE_SECRET]).
 *
 * - **Yesterday** (18:00–19:00): Bartok plays alone; the Narrator speaks twice (divine_bureaucracy, customer_service,
 *   « céleste » as a crutch).
 * - **Today** (from 20:00, [NOW] 21:00): Bartok and Mimosa online, [TRAPPED] for ten minutes; the Narrator speaks five
 *   times (sports_commentary, game_show, plain flat 0.8, a dark line to Bartok alone, one never reviewed); Bartok laughs,
 *   chats a trap and whispers the secret to Mimosa (a private message); Mimosa prays and dies; Bartok mines and earns an
 *   advancement. The memory holds a row of each kind, ideas open and closed, documents, jobs, an audit and recalls; the
 *   Director pulled two levers; the engine and the Forge made decisions.
 *
 * [services] wraps it in [TestServices]: the real Journal, sheets read by the real `DuckPlayerSheets`, and fakes for
 * what runs (the engine's views, the arsenal, the style watch, the guards' effects) holding traps too. [file]: a Journal
 * on disk, whose own files hold the private messages in raw bytes.
 */
internal class DashboardDataset(val file: Path? = null) : AutoCloseable {
    val fixture = JournalFixture(file = file, clock = TestClock(at(YESTERDAY, "17:59")))
    private val journal = fixture.journal
    private val clock = fixture.clock

    /** The ids of the facts the tests look for. */
    var death = 0L
    var whisper = 0L

    init {
        fill()
        fixture.clock.now = NOW
        fixture.sync()
    }

    private fun on(day: Instant, time: String, event: GameEvent): Envelope {
        clock.now = at(day, time)
        return fixture.publish(event)
    }

    private var turn = 40L

    private fun say(day: Instant, time: String, text: String, to: List<PlayerRef>? = null) =
        on(day, time, NarratorSpoke(NarratorChannel.NARRATOR, text, to, turn++, null))

    private fun label(line: Envelope, register: ComicRegister, dark: Double = 0.0, flat: Double = 0.0, crutches: List<String> = emptyList()) =
        journal.lineLabels(
            LineLabelsRecord(
                line.id, Instant.ofEpochMilli(line.ts).plusSeconds(1), "reflex:claude-haiku-5-5", register.id, dark, 0.0, flat, 0.0, 0.0,
                crutches, "Une phrase du relecteur.", 900,
            ),
        )

    private fun fill() {
        // Yesterday: Bartok alone, an hour.
        on(YESTERDAY, "18:00", PlayerJoined(BARTOK, HOME, 1, firstTime = true, isOp = false))
        label(say(YESTERDAY, "18:10", "Le guichet céleste exige un formulaire pour chaque pioche, Bartok."), ComicRegister.DIVINE_BUREAUCRACY, crutches = listOf("céleste"))
        label(say(YESTERDAY, "18:20", "Bartok, le service client céleste vous remercie de votre patience."), ComicRegister.CUSTOMER_SERVICE, crutches = listOf("céleste"))
        on(YESTERDAY, "19:00", PlayerLeft(BARTOK, HOME, 1, 3_600, LeaveReason.LEAVE))

        // Today, from 20:00.
        on(TODAY, "20:00", PlayerJoined(BARTOK, HOME, 3, firstTime = false, isOp = false))
        on(TODAY, "20:05", PlayerJoined(MIMOSA, HOME, 4, firstTime = true, isOp = false))
        on(TODAY, "20:06", PlayerJoined(TRAPPED, HOME, 5, firstTime = true, isOp = false))
        label(say(TODAY, "20:10", "Et Bartok s'élance, il glisse, il tombe : quel plongeon magnifique !"), ComicRegister.SPORTS_COMMENTARY)
        on(TODAY, "20:11", ChatMessage(BARTOK, ChatChannel.PUBLIC, "mdr", emptyList()))
        on(TODAY, "20:12", ChatMessage(BARTOK, ChatChannel.PUBLIC, "le chat $SCRIPT_TRAP", emptyList()))
        whisper = on(TODAY, "20:13", ChatMessage(BARTOK, ChatChannel.WHISPER, "psst $PRIVATE_SECRET", listOf(MIMOSA.name))).id
        on(TODAY, "20:13", CommandIssued(BARTOK, HOME, "msg", null))
        label(say(TODAY, "20:15", "Mimosa remporte la manche, le public est en délire total ce soir."), ComicRegister.GAME_SHOW)
        on(TODAY, "20:16", ChatMessage(MIMOSA, ChatChannel.PRAY, "pitié Narrateur", emptyList()))
        on(TODAY, "20:16", PlayerLeft(TRAPPED, HOME, 5, 600, LeaveReason.LEAVE))
        death = on(TODAY, "20:17", PlayerDied(MIMOSA, HOME, "minecraft:lava", null, null, "Mimosa tried to swim in lava", 3, false, false)).id
        on(TODAY, "20:18", BlockBroken(BARTOK, Pos("minecraft:overworld", 3, 12, 4), "minecraft:diamond_ore", "minecraft:iron_pickaxe", false, true, null))
        on(TODAY, "20:19", AdvancementEarned(BARTOK, HOME, "minecraft:story/mine_diamond", "Diamonds!", "task"))
        label(say(TODAY, "20:20", "Effet lenteur appliqué à Bartok, pour la forme et pour le principe."), ComicRegister.PLAIN, flat = 0.8)
        label(say(TODAY, "20:25", "Bartok, ta pioche pleure en silence $QUOTE_TRAP depuis ce matin.", listOf(BARTOK)), ComicRegister.COURTROOM, dark = 0.9)
        say(TODAY, "20:30", "Pas encore relue, celle-ci, et pourtant elle est longue.")

        clock.now = at(TODAY, "20:40")
        val decisions = Decisions(journal, clock)
        decisions.record(Log.mechanics, DecisionRecord.Kind.MECHANIC, "started", "C-511", "C-511 challenge started: obtain $SCRIPT_TRAP", turnId = 42)
        decisions.record(Log.mechanics, DecisionRecord.Kind.MECHANIC, "fired", "C-513", "C-513 fired on Bartok")
        decisions.record(Log.forge, DecisionRecord.Kind.FORGE, "tested", MODULE, "ok: compiled, guard clean, smoke run passed")
        decisions.record(Log.forge, DecisionRecord.Kind.FORGE, "saved", "$MODULE v1", "ready, by forger T-31: a game of tag")
        tool("punish", """{"player":"Bartok","punishment":"tiny"}""", listOf(BARTOK.name))
        tool("start_challenge", """{"goal":"obtain","item":"minecraft:turtle_egg"}""", emptyList())
        runBlocking { journal.write(10_000) { memory(it) } }
    }

    private fun tool(name: String, args: String, targets: List<String>) = journal.toolCall(
        ToolCallRecord(journal.nextId(IdKind.TOOL_CALL), clock.instant(), CHAPTER, 42, null, "toolu_$name", Role.DIRECTOR, name, args, ToolStatus.OK, "ok", 30, targets, ToolEffect.WORLD),
    )

    /** The memory's rows, as its store writes them (columns of `V2__memory.sql` to `V4__style_ideas.sql`). */
    private fun memory(c: Connection) {
        val t = at(TODAY, "20:30")
        fun memory(id: Long, title: String, archived: Boolean) = c.update(
            "INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, importance, valence, " +
                "stability_h, reinforced_play_s, source, evidence_events, evidence_tools, archived) VALUES (?, 'episode', ?, ?, ?, ?, ?, ?, ?, ?, 7, -2, 38.0, 0, 'chronicler', ?, ?, ?)",
            id, t, t, t, title, "Mimosa a nagé dans la lave. $SCRIPT_TRAP", uuidArray(listOf(MIMOSA.uuid)), stringArray(listOf("minecraft:lava")),
            stringArray(listOf("death", "lava")), longArray(listOf(death)), longArray(emptyList()), archived,
        )
        memory(501, "Le plongeon $QUOTE_TRAP", archived = false)
        memory(502, "Un souvenir oublié", archived = true)
        fun canon(id: Long, kind: String, statement: String, status: String, mechanic: String?, resolution: String? = null) = c.update(
            "INSERT INTO canon (id, kind, created_at, statement, quote, players, importance, status, due_at, resolved_at, resolution, source, " +
                "evidence_events, evidence_tools, mechanic) VALUES (?, ?, ?, ?, ?, ?, 6, ?, ?, ?, ?, 'director', ?, ?, ?)",
            id, kind, t, statement, "Je tiendrai parole $QUOTE_TRAP", uuidArray(listOf(MIMOSA.uuid)), status,
            if (status == "active" && mechanic == null) NOW.plusSeconds(3_600) else null, if (resolution != null) t else null, resolution,
            longArray(emptyList()), longArray(emptyList()), mechanic,
        )
        canon(510, "promise", "Le Narrateur a promis une couronne à Mimosa. $SCRIPT_TRAP", "active", null)
        canon(511, "challenge", "Le Narrateur a défié tout le monde de trouver un œuf de tortue.", "active", "challenge")
        canon(512, "law", "Le Narrateur a interdit de casser les blocs des autres.", "retired", "rule", "removed by an operator")
        canon(513, "law", "Le Narrateur a lancé une partie de loup.", "active", "module")
        c.update(
            "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, quote, played_s, tool_calls, source, evidence_events) " +
                "VALUES (520, ?, ?, 'Bartok', 'insult', -2, true, true, ?, ?, 1200, ?, 'chronicler', ?)",
            t, BARTOK.uuid, "Bartok a insulté le Narrateur.", "t'es nul $SCRIPT_TRAP", longArray(listOf(77L)), longArray(emptyList()),
        )
        for (i in 0 until EXTRA_INTERACTIONS) {
            c.update(
                "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, played_s, tool_calls, source, evidence_events) " +
                    "VALUES (?, ?, ?, 'Mimosa', 'prayer', 1, true, false, ?, 600, [], 'chronicler', [])",
                600L + i, t.minusSeconds(60L * i), MIMOSA.uuid, "Mimosa a prié, la fois $i.",
            )
        }
        c.update(
            "INSERT INTO notes (id, created_at, kind, text, players, refs, status, shown_count, source) VALUES (530, ?, 'coherence', ?, ?, ?, 'open', 1, 'chronicler')",
            t, "Le Narrateur a promis deux couronnes $QUOTE_TRAP.", uuidArray(listOf(MIMOSA.uuid)), longArray(listOf(510L)),
        )
        fun chronicle(id: Long, level: String, title: String, open: Boolean, superseded: Long?) = c.update(
            "INSERT INTO chronicle (id, level, period_from, period_to, title, summary, covers, conversation_id, open, superseded_by, written_at, source) " +
                "VALUES (?, ?, ?, ?, ?, ?, [], ?, ?, ?, ?, 'chronicler')",
            id, level, at(TODAY, "20:00"), t, title, "Une soirée de lave. $SCRIPT_TRAP", CHAPTER, open, superseded, t,
        )
        chronicle(540, "chapter", "Le chapitre de la lave", false, null)
        chronicle(541, "session", "La soirée, première écriture", false, 542)
        chronicle(542, "session", "La soirée", true, null)
        fun idea(id: Long, kind: String, title: String, status: String, reason: String?, module: String? = null) = c.update(
            "INSERT INTO ideas (id, created_at, kind, title, pitch, fit, players, register, novelty, fun, fairness, refs, module, status, dealt, " +
                "last_dealt_at, expires_play_s, closed_at, close_reason, source) VALUES (?, ?, ?, ?, ?, ?, ?, 'game_show', 4, 4, 5, ?, ?, ?, ?, ?, 172800, ?, ?, 'storyteller')",
            id, t, kind, title, "Une course au sommet du pic enneigé. $SCRIPT_TRAP", "quand les deux sont là", uuidArray(listOf(BARTOK.uuid, MIMOSA.uuid)),
            longArray(listOf(510L)), module, status, if (status == "open") 2 else 0, if (status == "open") t else null, if (reason != null) t else null, reason,
        )
        idea(550, "challenge", "La course au pic $QUOTE_TRAP", "open", null)
        idea(551, "season", "Les Grandes Manœuvres", "dropped", "replaced by the season P-552")
        idea(552, "season", "Le Siège du Moulin", "open", null)
        idea(553, "module", "Le loup", "expired", "its hours of world play are over", MODULE)
        fun doc(key: String, version: Int, content: String) = c.update(
            "INSERT INTO memory_docs (key, version, content, evidence, written_at, source) VALUES (?, ?, ?, ?, ?, 'dreamer')",
            key, version, content, longArray(listOf(510L)), t,
        )
        doc("grimoire", 1, "# Grimoire\nPremier jet.")
        doc("grimoire", 2, "# Grimoire\nLa lave revient. $SCRIPT_TRAP")
        doc("review", 1, "Ce qui marche : le sport.")
        doc("sheet:${BARTOK.uuid}:recap", 1, "Bartok creuse sans relâche.")
        c.update(
            "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, conversations, writes, rejected, " +
                "input_tokens, output_tokens, report) VALUES (1, 'nap', 'chapter_closed', 'done', ?, ?, ?, ?, ?, ?, 3, 0, 12000, 900, 'three rows written')",
            t, t.plusSeconds(65), at(TODAY, "20:00"), t, CHAPTER, stringArray(listOf("chronicler-1")),
        )
        c.update(
            "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, ended_at, conversations, error) VALUES (2, 'dream', 'server_empty', 'failed', ?, ?, [], ?)",
            t, t.plusSeconds(5), "LLMGW refused $SCRIPT_TRAP",
        )
        c.update(
            "INSERT INTO memory_log VALUES (?, 'director', NULL, 42, 'canon', '510', 'insert', NULL, ?::JSON, NULL)",
            t, """{"id":510,"status":"planned","source":"director","turn_id":42,"evidence_events":[$death],"evidence_tools":[]}""",
        )
        c.update(
            "INSERT INTO memory_log VALUES (?, 'operator:Clement', NULL, NULL, 'canon', '510', 'update', ?::JSON, ?::JSON, ?)",
            t.plusSeconds(1), """{"id":510,"status":"planned","source":"director","turn_id":42,"evidence_events":[$death],"evidence_tools":[]}""",
            """{"id":510,"status":"active","source":"director","turn_id":42,"evidence_events":[$death],"evidence_tools":[]}""", "started $QUOTE_TRAP",
        )
        c.update("INSERT INTO recalls VALUES (?, 510, 'briefing', ?, 43, NULL)", t, CHAPTER)
        c.update("INSERT INTO recalls VALUES (?, 510, 'recall_tool', ?, 44, NULL)", t.plusSeconds(30), CHAPTER)
    }

    /**
     * The services of the pages: this Journal, its real sheets, Bartok online with two effects of the Narrator (one without
     * an end), and the fakes of the engine, the Forge and the style watch, holding traps. [journal] replaces the Journal
     * (a wrapper of it in a test).
     */
    fun services(journal: Journal = fixture.journal, config: NarratorConfig = NarratorConfig()): TestServices {
        val testClock = TestClock(NOW)
        val players = PlayerStates().also { it.put(live(BARTOK)) }
        val fake = FakeMemory(testClock)
        val sheets = DuckPlayerSheets(journal, fake.clock, players, ZoneOffset.UTC)
        val memory = object : Memory by fake {
            override val sheets: PlayerSheets = sheets
        }
        val guards = FakeGuards().also {
            it.active[BARTOK.uuid] = listOf(
                ActiveEffect("effect", "slowness II", NOW.toEpochMilli() + 60_000, harmful = true),
                ActiveEffect("stake", "2x minecraft:emerald staked on C-511: a bet that Mimosa wins", null, harmful = false),
            )
        }
        return TestServices(
            clock = testClock, journal = journal, settings = testSettings(config), players = players, guards = guards, memory = memory,
            mechanics = mechanics(testClock), forge = forge(testClock), style = style(),
        )
    }

    private fun mechanics(clock: TestClock) = FakeMechanics(clock).apply {
        lines = listOf("Challenges: C-511 obtain « Œuf $SCRIPT_TRAP » → all · Mimosa 0/1 · ends in 12m")
        val ends = NOW.toEpochMilli() + 12 * 60_000L
        val spec = ChallengeSpec(
            title = "Œuf $QUOTE_TRAP", statement = "Le Narrateur a défié tout le monde.", goal = GoalKind.OBTAIN, item = "minecraft:turtle_egg", minutes = 15,
        )
        challenges.views += ChallengeView(
            Ref(RefKind.CANON, 511), spec, null, ChallengeStatus.RUNNING, ends,
            listOf(Participant(MIMOSA.uuid.toString(), MIMOSA.name), Participant(BARTOK.uuid.toString(), BARTOK.name, progress = 1)), 1, "C-511 obtain · Mimosa 0/1",
        )
        val rule = RuleSpec(
            RuleKind.LAW, "Le Narrateur a interdit $SCRIPT_TRAP.", null, RuleTrigger.BLOCK_BREAK, RuleConditions(grief = true),
            listOf(RuleEffect("punish", EffectTarget.ACTOR, JsonObject(mapOf("punishment" to JsonPrimitive("tiny"))))), 60,
        )
        rules.views += RuleView(Ref(RefKind.CANON, 512), rule, ends, 2, 0, 0, "C-512 law block_break")
        modules.views += ModuleView(Ref(RefKind.CANON, 513), MODULE, 1, RuleKind.LAW, ends, acts = 3, faults = 0, challenge = Ref(RefKind.CANON, 511), msPerSecond = 0.4, line = "C-513 $MODULE law")
    }

    private fun forge(clock: TestClock) = FakeForge(clock).apply {
        arsenal.add(entry(MODULE, 1, "Tag: the wolf passes on by touch."), "public class TagGame { /* le loup */ }")
        arsenal.add(entry(TRAP_MODULE, 1, "A trap $SCRIPT_TRAP"), "public class Trap { String s = \"</pre>$SCRIPT_TRAP\"; }")
    }

    private fun entry(name: String, version: Int, summary: String) = ArsenalEntry(
        name = name, version = version, status = ModuleStatus.READY, builtIn = false, summary = summary,
        params = listOf(ParamSpec("minutes", ParamType.INTEGER, "How long it lasts $QUOTE_TRAP.", required = false, fallback = "5", min = 2.0, max = 15.0, choices = emptyList())),
        scenarios = listOf("itWorks"), savedAt = at(TODAY, "19:00"), by = "forger T-31", note = "A game.", hash = "sha256:00", sdk = 1, classes = 2, bytes = 900,
        problem = null,
    )

    private fun style() = FakeStyle().apply {
        atRest = AtRest(listOf(RestingOpener("Vingt secondes", 5, listOf("Onze secondes $QUOTE_TRAP"))), listOf("service client céleste"), listOf("céleste"))
        situation = "Style: openers at rest « Vingt secondes » ×5 $SCRIPT_TRAP"
        measured = Diversity(50, 0.8, ComicRegister.DIVINE_BUREAUCRACY, 0.38, 0.71, listOf("céleste" to 6), 3)
        last = listOf(ComicRegister.COURTROOM, ComicRegister.PLAIN)
    }

    override fun close() = fixture.close()

    companion object {
        val YESTERDAY: Instant = Instant.parse("2026-10-20T00:00:00Z")
        val TODAY: Instant = Instant.parse("2026-10-21T00:00:00Z")
        val NOW: Instant = at(TODAY, "21:00")

        const val CHAPTER = "director-aaaa"
        const val MODULE = "tag_game"
        const val TRAP_MODULE = "trap_module"
        const val EXTRA_INTERACTIONS = 55

        const val SCRIPT_TRAP = "<script>alert(1)</script>"
        const val QUOTE_TRAP = "\"><b onmouseover=alert(2)>bold</b>"

        /** In a private message between players only: no page nor query of the dashboard may show it. */
        const val PRIVATE_SECRET = "PRIVATE-7f3a9c"

        /** In a file of the server, next to the Journal. */
        const val FILE_SECRET = "FILESECRET-41d2"

        val BARTOK = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
        val MIMOSA = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
        val TRAPPED = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b3"), "Zed$QUOTE_TRAP")

        private val HOME = Pos("minecraft:overworld", 0, 64, 0)

        fun at(day: Instant, time: String): Instant {
            val (hours, minutes) = time.split(':').map { it.toLong() }
            return day.plusSeconds(hours * 3_600 + minutes * 60)
        }

        fun live(player: PlayerRef) = LivePlayer(
            ref = player, sessionId = 3, joinedAt = at(TODAY, "20:00").toEpochMilli(), isOp = false, gameMode = "survival",
            pos = Pos("minecraft:overworld", 1, 64, 2), biome = "minecraft:plains", structure = null, underground = false,
            health = 18f, maxHealth = 20f, food = 17, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
        )
    }
}

/** The answer of [routes] (on port [DATASET_PORT]) to a signed-in `GET` of [path] (`/memory/ideas?status=open`, its query raw). */
internal fun pageOf(routes: Routes, token: DashboardToken, path: String): WebResponse = routes.respond(
    WebCall("GET", path.substringBefore('?'), path.substringAfter('?', "").ifEmpty { null }, "127.0.0.1:$DATASET_PORT", listOf("${Routes.COOKIE}=${token.value}")),
)

/** The port the [Routes] of the dataset's tests answer on. */
internal const val DATASET_PORT = 8770
