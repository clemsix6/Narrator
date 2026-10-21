package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.Urgency
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeMemoryState
import io.github.clemsix6.narrator2.testing.FakeMemoryStore
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Sheets put in [sheets], rendered by the real [SheetRenderer]; the players of [failing] cannot be read. */
internal class MapSheets(zone: ZoneId) : PlayerSheets {
    private val renderer = SheetRenderer(zone)
    val sheets = HashMap<UUID, PlayerSheet>()
    val failing = HashSet<UUID>()

    override suspend fun sheet(player: UUID, now: Long): PlayerSheet? {
        if (player in failing) throw IllegalStateException("the sheet of $player is locked")
        return sheets[player]
    }

    override fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart>): String = renderer.render(sheet, detail, now, parts)
}

/**
 * What the briefing and the joins read (M3-13): a store in memory ([FakeMemoryStore], filled row by row),
 * recall hits set by the test, sheets rendered by the real renderer, a Journal on an in-memory database
 * (sessions, the Narrator's lines). [NOW] is the time of the sample sheets.
 */
internal class BriefingFixture(journal: Journal? = null) : AutoCloseable {
    val clock = TestClock(NOW, UTC)
    val db: DuckDatabase = DuckDatabase.openInMemory(DuckDatabase.Options(memoryLimitMb = 128, threads = 1, timeZone = UTC))
    val direct = DirectJournal(db)
    val journal: Journal = journal ?: direct
    val store = FakeMemoryStore(clock)
    val recall = FakeMemory.FakeRecall()
    val sheets = MapSheets(UTC)
    var online: List<PlayerRef> = emptyList()
    val briefing = MemoryBriefing(store, recall, sheets, this.journal, { online }, UTC)
    val joins = JoinBrief(store, sheets, this.journal, UTC)
    val playClock = FakeMemory.FakePlayClock()
    val reminiscence = Reminiscence(recall, store, FakeMemoryState(), this.journal, playClock, { MemoryConfig() }, clock)
    val attachments = WakeAttachments(joins, reminiscence)
    val now: Long get() = clock.millis()
    private var nextEvent = 50_000L

    fun chapter(id: String = "director-c14") = ChapterMemory(id, 14)

    suspend fun sections(chapter: ChapterMemory = chapter()): Map<String, BriefingSection> =
        briefing.sections(chapter, now).associateBy { it.tag }

    /** The model accepted the request that showed what [chapter] holds: it is recorded, as the Director does ([Memory.recordShown]). */
    suspend fun accept(chapter: ChapterMemory) = Shown.record(store, chapter.takeUnrecorded())

    fun put(row: MemoryRow) {
        store.rows[row.ref] = row
    }

    fun grimoire(content: String, version: Int = 12, at: Instant = NOW.minus(Duration.ofHours(16))) {
        store.docs[DocKey.Grimoire.key] = listOf(MemoryDoc(DocKey.Grimoire.key, version, content, emptyList(), at, Source.DREAMER))
    }

    fun chronicle(
        id: Long, level: ChronicleLevel, from: Instant, to: Instant, title: String, summary: String, open: Boolean = false,
    ) = ChronicleEntry(Ref(RefKind.CHRONICLE, id), level, from, to, title, summary, emptyList(), null, open, null, Source.DREAMER).also(::put)

    fun note(id: Long, kind: NoteKind, text: String, players: List<PlayerRef> = emptyList(), shown: Int = 0, at: Instant = NOW.minus(Duration.ofHours(2))) =
        Note(Ref(RefKind.NOTE, id), at, kind, text, players, emptyList(), if (shown > 0) NoteStatus.SHOWN else NoteStatus.OPEN, shown).also(::put)

    fun hit(id: Long, line: String) = RecallHit(Ref(RefKind.MEMORY, id), 0.8, 0.7, 1.0, line)

    /** A line of the Director in the `chat` table, said in its turn [id] (for the tics). */
    fun said(id: Long, at: Instant, text: String) {
        direct.db.writer.update("INSERT INTO chat (id, ts, channel, text, turn_id) VALUES (?, ?, 'narrator', ?, ?)", id, at, text, id)
    }

    /** [player]'s name in the Journal (`player_names`): the tics read it as a name. */
    fun named(player: PlayerRef, at: Instant = NOW) {
        direct.db.writer.update("INSERT INTO player_names (player_uuid, name, first_seen) VALUES (?, ?, ?)", player.uuid, player.name, at)
    }

    /** A closed session of [player]. */
    fun session(id: Long, player: PlayerRef, joined: Instant, left: Instant) {
        direct.db.writer.update(
            "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, end_reason) VALUES (?, ?, ?, ?, ?, ?, 'leave')",
            id, player.uuid, player.name, joined, left, Duration.between(joined, left).seconds.toInt(),
        )
    }

    fun signal(event: GameEvent, at: Instant = clock.instant(), id: Long = nextEvent++) =
        DirectorSignal.Event(Envelope(id, at.toEpochMilli(), 0, event), Urgency.SOON, null)

    fun join(player: PlayerRef, session: Long = 900, firstTime: Boolean = false, at: Instant = clock.instant()) =
        signal(PlayerJoined(player, HOME, session, firstTime, false), at)

    fun leave(player: PlayerRef, session: Long = 900, at: Instant = clock.instant()) =
        signal(PlayerLeft(player, HOME, session, 60, LeaveReason.LEAVE), at)

    override fun close() = db.close()

    companion object {
        /** UTC by its name: DuckDB does not know the zone `Z`. */
        val UTC: ZoneId = ZoneId.of("UTC")
        val NOW: Instant = SheetSamples.NOW
        val HOME = Pos("minecraft:overworld", 100, 64, 100)
        val bartok = MemorySamples.bartok
        val mimosa = MemorySamples.mimosa
        val kelvin = SheetSamples.kelvin

        /** Mimosa's sheet: the reference sheet's texts, under her name. */
        val mimosaSheet: PlayerSheet = SheetSamples.reference.copy(
            player = mimosa, formerNames = emptyList(), nickname = null, relations = emptyList(), disrespect = emptyList(),
            recent = SheetSamples.reference.recent.map { it.copy(player = mimosa) }, marked = emptyList(), openCanon = emptyList(),
            docs = SheetSamples.reference.docs.filterKeys { it != SheetSection.SINCE_LAST },
        )
    }
}
