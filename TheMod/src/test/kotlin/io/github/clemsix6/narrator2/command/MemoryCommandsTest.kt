package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.DisabledMemory
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.DuckPlayerSheets
import io.github.clemsix6.narrator2.memory.IdeaDraft
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryJobsControl
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.PlayerSheet
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.RecallHit
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.RunTrigger
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.SheetPart
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.StoreFixture
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.web.DisabledDashboard
import kotlinx.coroutines.runBlocking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/narrator sheet` and `/narrator memory …` (M3 §14): what each command renders, on the real store over an
 * in-memory DuckDB ([StoreFixture]) or on [FakeMemory]; the changes an operator makes (forget and restore,
 * rollback, revert, notes) and their audit; the jobs; the refusal without a memory; and the command tree.
 */
class MemoryCommandsTest {
    private val f = StoreFixture()
    private val evening = MemorySamples.evening
    private val bartok = MemorySamples.bartok
    private val mimosa = MemorySamples.mimosa

    /** The real store and sheets of [f], the rest of a [FakeMemory] (recall, jobs, clock). */
    private val fake = FakeMemory(f.clock)
    private val memory = object : Memory by fake {
        override val store: MemoryStore = f.store
        override val sheets: PlayerSheets = DuckPlayerSheets(f.journal, f.playClock, PlayerStates(), ZoneOffset.UTC)
    }
    private val services = TestServices(clock = f.clock, journal = f.journal, memory = memory)

    @AfterEach
    fun close() = f.close()

    private fun List<Component>.text() = map { it.string }

    /** The lines as written, before the chat wrapped them: a continuation is indented 4 spaces more than the line it continues. */
    private fun List<Component>.unwrapped(): List<String> = text().fold(mutableListOf()) { lines, line ->
        val previous = lines.lastOrNull()
        val continuation = previous?.let { " ".repeat(it.takeWhile { c -> c == ' ' }.length + 4) }
        if (continuation != null && line.startsWith(continuation) && line[continuation.length] != ' ') {
            lines[lines.lastIndex] = "$previous ${line.trim()}"
        } else {
            lines += line
        }
        lines
    }

    private fun DirectorCommands.Reply.text() = lines.map { it.string }

    private fun operatorDecisions() = services.traces.decisions.filter { it.kind == DecisionRecord.Kind.OPERATOR }.map { "${it.decision} ${it.subject}: ${it.reason}" }

    // ── Without a memory ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `without a memory every command is refused with the reason, and status says it`() {
        val disabled = DisabledMemory("not wired yet")
        assertEquals("[Narrator] the memory is unavailable (not wired yet): nothing to read nor change.", MemoryCommands.refusal(disabled)!!.string)
        assertNull(MemoryCommands.refusal(FakeMemory()), "an available memory refuses nothing")
        val status = AdminCommands.statusLines(TestServices(memory = disabled), Path.of("config"), DisabledDashboard(DisabledDashboard.SWITCHED_OFF))
        assertEquals("Memory: unavailable (not wired yet)", status[10].string)
        assertEquals("Guards: chaos 3 · creatures 0 of 24 · protected online: nobody", status[9].string, "the lines before it stay in place")
    }

    // ── status ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `memory status counts the tables and tells the jobs`() = runBlocking<Unit> {
        val plunge = f.store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(bartok.uuid)), f.director).row
        f.store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Encore", "Bartok a replongé dans la lave.", listOf(bartok.uuid)), f.director)
        f.store.archive(plunge.ref, true, "test", f.operator)
        f.store.addCanon(CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis à Mimosa de lui rendre ses bottes.", players = listOf(mimosa.uuid)), f.director)
        val threat = f.store.addCanon(CanonDraft(CanonKind.THREAT, "Le Narrateur a menacé Bartok de slimes.", players = listOf(bartok.uuid)), f.director).row
        f.store.resolveCanon(threat.ref, CanonStatus.FULFILLED, "Les slimes sont tombés.", emptyList(), f.director)
        f.store.writeDoc(DocKey.Grimoire, "## Players\n- Bartok plonge.", emptyList(), f.dreamer)
        f.store.writeDoc(DocKey.Grimoire, "## Players\n- Bartok creuse.", emptyList(), f.dreamer)
        val now = f.clock.instant()
        fake.jobs.status = fake.jobs.status.copy(
            running = MemorySamples.run.copy(runId = 14, kind = RunKind.NAP, trigger = RunTrigger.CHAPTER_CLOSED, status = RunStatus.RUNNING, startedAt = now.minusSeconds(72), endedAt = null),
            queued = listOf(RunKind.BATCH, RunKind.DREAM),
            lastBatch = MemorySamples.run.copy(runId = 13, startedAt = now.minusSeconds(600), endedAt = now.minusSeconds(535), inputTokens = 12_400, outputTokens = 900, writes = 3),
            lastNap = MemorySamples.run.copy(
                runId = 12, kind = RunKind.NAP, trigger = RunTrigger.CHAPTER_CLOSED, status = RunStatus.FAILED, startedAt = now.minusSeconds(1_200),
                endedAt = now.minusSeconds(1_080), inputTokens = null, outputTokens = null, writes = 0, rejected = 1, error = "budget: the LLMGW budget is spent until 21:00",
            ),
            nextDreamAt = now.plusSeconds(3_600 * 23 + 300),
            worldPlaySeconds = 61 * 3_600 + 12 * 60,
            rejected24h = 3,
            voiceWarnings24h = 1,
        )
        assertEquals(
            listOf(
                "[Narrator] memory status",
                "Memory: available · jobs running · world clock 61h12m of play",
                "Memories: 1 (1 archived) · interactions: 0 · chronicle entries: 0",
                "Canon: 1 open of 2 · notes for the Director: 0 open",
                "Sheets: 0 traits · 0 skills · 0 relations · 1 document (2 versions)",
                "Index: 4 rows · 23 postings · 18 terms · audit: 8 changes",
                "Running: nap run 14 (chapter_closed) for 1m12s",
                "Queued: batch, dream",
                "Last batch: run 13 done 19:31:05 after 1m05s (timer)",
                "  12k tokens in, 900 out · 3 writes, 0 refused",
                "Last nap: run 12 failed 19:22:00 after 2m00s (chapter_closed)",
                "  0 writes, 1 refused · budget: the LLMGW budget is spent until 21:00",
                "Last dream: none yet",
                "Next dream: 10-02 18:45 (in 23h05m)",
                "Last 24 h: 3 writes refused · 1 voice warning",
            ),
            MemoryCommands.status(services).text(),
        )

        fake.jobs.status = fake.jobs.status.copy(paused = true, running = null, queued = emptyList(), lastDream = MemorySamples.run.copy(kind = RunKind.DREAM, startedAt = now.minusSeconds(7_200), endedAt = now.minusSeconds(6_000)))
        assertEquals(
            "Memory: available · jobs paused (/narrator memory resume) · nothing queued · last dream 18:00:00 done",
            AdminCommands.statusLines(services, Path.of("config"), DisabledDashboard(DisabledDashboard.SWITCHED_OFF))[10].string,
        )
        val blocked = fake.jobs.status.copy(paused = false, blockedUntil = now.plusSeconds(600), running = fake.jobs.status.lastBatch, lastDream = null)
        fake.jobs.status = blocked
        assertEquals(
            "Memory: available · jobs blocked until 19:50:00 (LLMGW budget spent) · running: batch run 13 · nothing queued · no dream yet",
            MemoryCommands.statusLine(memory, TraceText(ZoneOffset.UTC, now.toEpochMilli())),
        )
    }

    // ── sheet ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sheet shows the full sheet or one part, found by the current or a former name`() = runBlocking<Unit> {
        val full = MemoryCommands.sheet(services, "bartok", null, 1).unwrapped()
        assertEquals("[Narrator] sheet of Bartok · full", full.first().substringBefore(" · page"))
        assertTrue(full[1].startsWith("Bartok · no nickname · no title · since 10-01 · 1 session"), full.toString())
        assertTrue(full.any { it.startsWith("Identity: uuid ${bartok.uuid}") }, full.toString())

        f.sql("INSERT INTO player_names VALUES (?, 'Bartok_', ?)", bartok.uuid, evening)
        val identity = MemoryCommands.sheet(services, "BARTOK_", "identity", 1).unwrapped()
        assertEquals("[Narrator] sheet of Bartok · identity", identity[0])
        assertEquals(3, identity.size, "the header and the identity line: $identity")

        assertEquals(listOf("[Narrator] no player named Kelvin has ever played here."), MemoryCommands.sheet(services, "Kelvin", null, 1).text())
        assertEquals(
            listOf("[Narrator] unknown part feelings: all, identity, recap, personality, stats, history, memories."),
            MemoryCommands.sheet(services, "Bartok", "feelings", 1).text(),
        )
        val stranger = UUID.fromString("00000000-0000-0000-0000-000000000999")
        assertEquals(listOf("[Narrator] 00000000 was never seen by the memory: no sheet yet."), MemoryCommands.sheet(services, stranger.toString(), null, 1).text())
    }

    @Test
    fun `a long sheet comes in pages with a link to the next one, and an online player is found without the Journal`() = runBlocking<Unit> {
        val players = PlayerStates().apply {
            put(LivePlayer(bartok, 1, 0, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20, null, null, 0, false))
        }
        val long = FakeMemory(f.clock)
        long.sheets.sheets[bartok.uuid] = MemorySamples.sheet
        val sheets = object : PlayerSheets by long.sheets {
            override fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart>): String =
                (1..40).joinToString("\n") { "line $it of ${detail.name.lowercase()} ${parts.joinToString(" ") { part -> part.id }}" }
        }
        val alone = TestServices(clock = f.clock, players = players, memory = object : Memory by long { override val sheets = sheets })
        val first = MemoryCommands.sheet(alone, "Bartok", null, 1).text()
        assertEquals("[Narrator] sheet of Bartok · full · page 1/3", first[0])
        assertEquals("line 1 of full identity recap personality stats history memories", first[1])
        assertEquals(17, first.size, "title, 15 lines, footer: $first")
        assertEquals("page 1/3 · next: /narrator sheet Bartok all 2", first.last())
        val last = MemoryCommands.sheet(alone, "Bartok", "stats", 3).text()
        assertEquals("[Narrator] sheet of Bartok · stats · page 3/3", last[0])
        assertEquals("line 31 of full stats", last[1])
        assertEquals("page 3/3 · previous: /narrator sheet Bartok stats 2", last.last())
    }

    // ── canon ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `canon lists the open entries by default, all of them, or those about a player`() = runBlocking<Unit> {
        val promise = f.store.addCanon(
            CanonDraft(
                CanonKind.PROMISE, "Le Narrateur a promis à Mimosa de lui rendre ses bottes.", "je te rends tes bottes demain",
                listOf(mimosa.uuid), importance = 6, dueAt = evening.plusSeconds(720),
            ),
            f.director,
        ).row
        val law = f.store.addCanon(CanonDraft(CanonKind.LAW, "Nul ne creuse sous le temple.", importance = 8), f.director).row
        val threat = f.store.addCanon(CanonDraft(CanonKind.THREAT, "Le Narrateur a menacé Bartok de slimes.", players = listOf(bartok.uuid)), f.director).row
        f.clock.advance(Duration.ofMinutes(2))
        f.store.resolveCanon(threat.ref, CanonStatus.FULFILLED, "Les slimes sont tombés.", emptyList(), f.director)

        assertEquals(
            listOf(
                "[Narrator] canon · open · 2 entries",
                "${promise.ref} promise → Mimosa · active · due 19:52:00 (in 10m) · ★6: Le Narrateur a promis à Mimosa de lui rendre ses bottes. · said « je te rends tes bottes demain »",
                "${law.ref} law → everyone · active · ★8: Nul ne creuse sous le temple.",
            ),
            MemoryCommands.canon(services, all = false, null, 1).unwrapped(),
        )
        assertEquals(
            listOf(
                "[Narrator] canon about Bartok (and everyone) · all · 2 entries",
                "${law.ref} law → everyone · active · ★8: Nul ne creuse sous le temple.",
                "${threat.ref} threat → Bartok · fulfilled 19:42:00 · ★5: Le Narrateur a menacé Bartok de slimes. · \"Les slimes sont tombés.\"",
            ),
            MemoryCommands.canon(services, all = true, "Bartok", 1).unwrapped(),
        )
        assertEquals(listOf("[Narrator] no player named Kelvin has ever played here."), MemoryCommands.canon(services, all = false, "Kelvin", 1).text())
    }

    // ── recall ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `recall shows what the tool would find, and records nothing`() = runBlocking<Unit> {
        val disposable = FakeMemory(f.clock)
        disposable.recall.hits += RecallHit(Ref(RefKind.MEMORY, 120), 0.8234, 0.9, 0.7, "M-120 · 10-01 19:40 · Bartok · ★7 · Mort dans la lave")
        disposable.recall.hits += RecallHit(Ref(RefKind.CANON, 45), 0.5, 0.6, 1.0, "C-45 · promise → Mimosa · active · ★6 · " + "très long ".repeat(40))
        val alone = TestServices(clock = f.clock, memory = disposable)
        val lines = MemoryCommands.recall(alone, "  lave bottes ").text()
        assertEquals("[Narrator] recall \"lave bottes\" · 2 found", lines[0])
        assertEquals("[0.82] M-120 · 10-01 19:40 · Bartok · ★7 · Mort dans la lave", lines[1])
        assertTrue(lines[2].startsWith("[0.50] C-45 · promise → Mimosa"), lines.toString())
        assertTrue(lines.drop(2).dropLast(1).all { it.length <= WRAP }, "a long hit is cut to two chat lines: $lines")
        assertEquals("Details: /narrator memory show <ref>", lines.last())
        assertEquals(5, lines.size, lines.toString())
        val query = disposable.recall.queries.single()
        assertEquals("lave bottes", query.text)
        assertEquals(RecallPurpose.TOOL, query.purpose)
        assertTrue(disposable.store.recalls.isEmpty(), "an operator's look reinforces nothing")
        assertEquals(
            listOf("[Narrator] recall \"rien\" · nothing found", "Try other words; recall matches French words and game ids."),
            MemoryCommands.recall(TestServices(clock = f.clock), "rien").text(),
        )
    }

    // ── show, forget, restore ───────────────────────────────────────────────────────────────────────

    @Test
    fun `forget archives a row and restore brings it back, both audited as the operator's, and show tells it all`() = runBlocking<Unit> {
        f.event(1_240, evening.minusSeconds(60), "death", bartok.uuid, Pos("minecraft:the_nether", -120, 14, 330))
        f.toolCall(88, evening.minusSeconds(30), "say")
        val run = Author(Source.CHRONICLER, runId = 7, conversationId = "chronicler-7")
        val row = f.store.addMemory(
            MemoryDraft(MemoryKind.EPISODE, "Mort dans la lave", "Bartok est mort dans la lave du Nether.", listOf(bartok.uuid), tags = listOf("death", "lava"), importance = 7, valence = -2, evidenceEvents = listOf(1_240), evidenceTools = listOf(88)),
            run,
        ).row
        val ref = row.ref.toString()
        f.clock.advance(Duration.ofMinutes(5))

        val forgot = MemoryCommands.archive(services, "Clement", ref.lowercase(), archived = true, reason = "  souvenir inventé  ")
        assertTrue(forgot.done)
        assertEquals(
            listOf(
                "[Narrator] $ref forgotten: archived, out of the briefings, reminiscences and recall (even with include_closed); " +
                    "the raw tables stay readable by sql; /narrator memory restore $ref brings it back.",
            ),
            forgot.text(),
        )
        assertTrue(f.store.memory(row.ref)!!.archived)
        assertEquals(listOf("[Narrator] refused: $ref is archived already"), MemoryCommands.archive(services, "Clement", ref, archived = true, reason = null).text())

        f.clock.advance(Duration.ofMinutes(1))
        val restored = MemoryCommands.archive(services, "Clement", ref, archived = false, reason = null)
        assertEquals(listOf("[Narrator] $ref restored: the Narrator may recall it again."), restored.text())
        assertFalse(f.store.memory(row.ref)!!.archived)
        assertEquals(listOf("chronicler", "operator:Clement", "operator:Clement"), f.store.log(row.ref).map { it.actor })
        assertEquals(listOf("memory_forget Clement: $ref: souvenir inventé", "memory_restore Clement: $ref: restored by an operator"), operatorDecisions())

        assertEquals(
            listOf(
                "[Narrator] $ref · memory episode · ★7",
                "Title: Mort dans la lave",
                "Content: Bartok est mort dans la lave du Nether.",
                "When: 19:39:00 → 19:39:30 · players: Bartok · valence -2",
                "Tags: death, lava · entities: none",
                "Place: the_nether -120 14 330",
                "Strength: stability ${ToolText.number(f.store.memory(row.ref)!!.stabilityHours)}h · reinforced at 0s of world play · recalled 0 times",
                "Source: chronicler · run 7 · conversation chronicler-7",
                "Evidence: e1240 · t88",
                "History (3 changes):",
                "  19:40:00 insert by chronicler (run 7)",
                "  19:45:00 archive by operator:Clement: archived false → true · \"souvenir inventé\"",
                "  19:46:00 restore by operator:Clement: archived true → false · \"restored by an operator\"",
            ),
            MemoryCommands.show(services, ref, 1).unwrapped(),
        )
        assertEquals(listOf("[Narrator] refused: N-9 cannot be archived: only memories, canon entries and interactions can"), MemoryCommands.archive(services, "Clement", "N-9", archived = true, reason = null).text())
        assertEquals(listOf("[Narrator] M-9999 does not exist."), MemoryCommands.show(services, "M-9999", 1).text())
        assertEquals(
            listOf("[Narrator] T-3 is not a memory ref: M-, C-, I-, H-, N- or P- and a number, like C-45."),
            MemoryCommands.show(services, "T-3", 1).text(),
        )
    }

    @Test
    fun `show renders every kind of row`() = runBlocking<Unit> {
        val disposable = FakeMemory(f.clock)
        listOf(MemorySamples.canon, MemorySamples.gag, MemorySamples.interaction, MemorySamples.chronicle, MemorySamples.note).forEach { disposable.store.rows[it.ref] = it }
        val alone = TestServices(clock = f.clock, memory = disposable)
        assertEquals(
            listOf(
                "[Narrator] C-45 · canon promise · active",
                "Statement: Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer le lendemain.",
                "Quote: « je te rends tes bottes demain, promis »",
                "To: Mimosa · ★6 · set 19:40:00 · due 10-02 19:40 (in 1d0h)",
                "Source: director",
                "History: no change recorded",
            ),
            MemoryCommands.show(alone, "C-45", 1).unwrapped(),
        )
        assertTrue("Uses: 5, last 19:40:00 · worn" in MemoryCommands.show(alone, "c27", 1).text())
        assertEquals(
            listOf(
                "[Narrator] I-512 · interaction insult -2 · Bartok",
                "When: 19:42:00 · at 61h00m of Bartok's play · counts toward favor · disrespect",
                "Summary: a traité le Narrateur de « nul » dans le chat",
                "Quote: « narrateur t'es nul »",
                "Links: tool calls t88",
                "Source: director",
                "History: no change recorded",
            ),
            MemoryCommands.show(alone, "I-512", 1).unwrapped(),
        )
        val chronicle = MemoryCommands.show(alone, "H-88", 1).text()
        assertEquals("[Narrator] H-88 · chronicle session · 18:40:00 → 21:40:00", chronicle[0])
        assertTrue("Covers: H-85, H-86" in chronicle, chronicle.toString())
        assertEquals(
            listOf(
                "[Narrator] N-9 · note coherence · open",
                "Text: Le Narrateur a dit à Bartok que les lois ne valent pas la nuit ; C-25 ne prévoit pas d'exception.",
                "Created 19:40:00 · shown 0 times",
                "About: Bartok · C-25",
                "History: no change recorded",
            ),
            MemoryCommands.show(alone, "N-9", 1).unwrapped(),
        )
    }

    // ── note ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `note writes an operator note for the Director, refused when it speaks in the Narrator's voice`() = runBlocking<Unit> {
        val written = MemoryCommands.note(services, "Clement", "  Bartok préfère qu'on l'appelle le Plongeur.  ")
        assertTrue(written.done)
        val note = f.store.openNotes().single()
        assertEquals(listOf("[Narrator] ${note.ref} written: the Director reads it at its next chapter's briefing."), written.text())
        assertEquals(NoteKind.OPERATOR, note.kind)
        assertEquals("Bartok préfère qu'on l'appelle le Plongeur.", note.text)
        assertEquals(listOf("operator:Clement"), f.store.log(note.ref).map { it.actor })
        assertEquals(listOf("memory_note Clement: ${note.ref}: \"Bartok préfère qu'on l'appelle le Plongeur.\""), operatorDecisions())

        val refused = MemoryCommands.note(services, "Clement", "Je veux que tu sois plus gentil.")
        assertFalse(refused.done)
        assertTrue(refused.text().single().startsWith("[Narrator] refused: text must be factual and in the third person"), refused.text().toString())
        assertEquals(1, f.store.openNotes().size)
        assertTrue(f.store.row(note.ref) is Note)
    }

    // ── docs, rollback ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `docs lists the versions of a document and shows one, and rollback copies an old one as the newest`() = runBlocking<Unit> {
        val recap = DocKey.Sheet(bartok.uuid, SheetSection.RECAP)
        f.store.writeDoc(recap, "Explorateur prudent.", listOf(), f.dreamer)
        f.clock.advance(Duration.ofMinutes(1))
        f.store.writeDoc(recap, "Explorateur tête baissée.\nProvoque le Narrateur.", listOf(), f.dreamer)
        f.clock.advance(Duration.ofMinutes(1))

        val rolled = MemoryCommands.rollback(services, "Clement", " sheet:bartok:recap   1 ")
        assertTrue(rolled.done)
        assertEquals(listOf("[Narrator] sheet:Bartok:recap: version 1 restored as version 3; /narrator memory docs sheet:Bartok:recap lists them."), rolled.text())
        assertEquals("Explorateur prudent.", f.store.doc(recap.key)!!.content)
        assertEquals(listOf("memory_rollback Clement: sheet:Bartok:recap: version 1 restored as version 3"), operatorDecisions())

        assertEquals(
            listOf(
                "[Narrator] document sheet:Bartok:recap · 3 versions",
                "v3 · 19:42:00 · operator · 20 chars · restored from v1 (current)",
                "v2 · 19:41:00 · dreamer · 48 chars",
                "v1 · 19:40:00 · dreamer · 20 chars",
                "Read one: /narrator memory docs sheet:Bartok:recap <version>",
            ),
            MemoryCommands.docs(services, "sheet:Bartok:recap").text(),
        )
        assertEquals(
            listOf("[Narrator] sheet:Bartok:recap v2 · 19:41:00 · dreamer · 48 chars", "Explorateur tête baissée.", "Provoque le Narrateur."),
            MemoryCommands.docs(services, "sheet:Bartok:recap 2").text(),
        )
        assertEquals(listOf("[Narrator] grimoire has no version yet."), MemoryCommands.docs(services, "grimoire").text())
        assertEquals(listOf("[Narrator] sheet:Bartok:recap has no version 9 (latest: 3)."), MemoryCommands.docs(services, "sheet:Bartok:recap 9").text())
        assertEquals(
            listOf("[Narrator] unknown document notes: grimoire, review or sheet:<player>:<section> (recap, since_last, playstyle, attitude, likes, fears, hooks)."),
            MemoryCommands.docs(services, "notes").text(),
        )
        assertEquals(listOf("[Narrator] refused: sheet:${bartok.uuid}:recap has no version 7 (latest: 3)"), MemoryCommands.rollback(services, "Clement", "sheet:Bartok:recap 7").text())
        assertEquals(
            listOf("[Narrator] rollback takes a document and a version: /narrator memory rollback grimoire 3."),
            MemoryCommands.rollback(services, "Clement", "grimoire").text(),
        )
    }

    // ── revert ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `revert undoes every write of a run, once, and never the running one`() = runBlocking<Unit> {
        val dream = Author(Source.DREAMER, runId = 80)
        val kept = f.store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(bartok.uuid)), f.director).row
        val written = f.store.addMemory(MemoryDraft(MemoryKind.OBSERVATION, "Téméraire", "Bartok saute toujours dans la lave.", listOf(bartok.uuid)), dream).row
        f.store.writeDoc(DocKey.Grimoire, "## Players\n- Bartok plonge.", emptyList(), dream)

        val reverted = MemoryCommands.revert(services, "Clement", 80)
        assertEquals(listOf("[Narrator] run 80 reverted: 2 writes undone, as if it never ran."), reverted.text())
        assertNull(f.store.row(written.ref))
        assertNull(f.store.doc(DocKey.Grimoire.key))
        assertTrue(f.store.row(kept.ref) != null)
        assertEquals(listOf("[Narrator] refused: run 80 was reverted already"), MemoryCommands.revert(services, "Clement", 80).text())
        assertEquals(listOf("[Narrator] run 81 wrote nothing in the memory: nothing to undo."), MemoryCommands.revert(services, "Clement", 81).text())
        assertEquals(listOf("memory_revert Clement: run 80: 2 writes undone", "memory_revert Clement: run 81: 0 writes undone"), operatorDecisions())

        fake.jobs.status = fake.jobs.status.copy(running = MemorySamples.run.copy(runId = 82, status = RunStatus.RUNNING, endedAt = null))
        assertEquals(
            listOf("[Narrator] run 82 is running: wait for its end (/narrator memory pause keeps the next ones from starting)."),
            MemoryCommands.revert(services, "Clement", 82).text(),
        )
    }

    // ── chronicle, dream, pause, resume ─────────────────────────────────────────────────────────────

    @Test
    fun `chronicle and dream queue a job as the operator's, and say why when they cannot`() {
        assertEquals(
            listOf("[Narrator] the Chronicler is queued (run 1): it notes what the Narrator said and did that it has not read yet."),
            MemoryCommands.chronicle(services, "Clement").text(),
        )
        fake.jobs.status = fake.jobs.status.copy(paused = true)
        assertEquals(
            listOf(
                "[Narrator] a deep dream is queued (run 2): it rewrites the sheets, the review of the Narrator, the chronicle and the Grimoire, then the storyteller writes its programme.",
                "The memory jobs are paused: it starts after /narrator memory resume.",
            ),
            MemoryCommands.dream(services, "Clement").text(),
        )
        assertEquals(listOf("chronicle by Clement", "dream by Clement"), fake.jobs.requests)
        assertEquals(listOf("memory_chronicle Clement: run 1", "memory_dream Clement: run 2"), operatorDecisions())

        val busy = FakeMemory(f.clock)
        val jobs = object : MemoryJobsControl by busy.jobs {
            override fun requestDream(by: String) = Result.failure<Long>(IllegalStateException("a dream is already queued (run 12): never two at a time"))
        }
        val refused = MemoryCommands.dream(TestServices(clock = f.clock, memory = object : Memory by busy { override val jobs = jobs }), "Clement")
        assertFalse(refused.done)
        assertEquals(listOf("[Narrator] a dream is already queued (run 12): never two at a time"), refused.text())
    }

    @Test
    fun `pause and resume reach the jobs, and say when nothing changes`() {
        assertEquals(
            listOf(
                "[Narrator] memory jobs paused: no batch, nap nor dream starts until /narrator memory resume (a running one ends first).",
                "The code's work goes on: play clock, due times, stats, hygiene.",
            ),
            MemoryCommands.pause(services, "Clement").text(),
        )
        assertEquals(listOf("[Narrator] the memory jobs were not paused: nothing changes."), MemoryCommands.resume(services, "Clement").text())
        fake.jobs.status = fake.jobs.status.copy(paused = true)
        assertEquals(listOf("[Narrator] the memory jobs are already paused; /narrator memory resume starts them again."), MemoryCommands.pause(services, "Clement").text())
        assertEquals(listOf("[Narrator] memory jobs resumed: what waited starts now."), MemoryCommands.resume(services, "Clement").text())
        assertEquals(listOf("pause by Clement", "resume by Clement"), fake.jobs.requests)
        assertEquals(emptyList<String>(), operatorDecisions(), "the jobs record pause and resume themselves (memory paused, resumed)")
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every documented syntax parses to a command, a number after canon being a page`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(LiteralArgumentBuilder.literal<CommandSourceStack>("narrator").also(MemoryCommands::attach))
        fun parsed(command: String): List<String> {
            val result = dispatcher.parse(command, null)
            assertTrue(!result.reader.canRead() && result.exceptions.isEmpty() && result.context.command != null, "$command does not parse: ${result.exceptions}")
            return result.context.nodes.map { it.node.name }
        }
        assertEquals(listOf("narrator", "sheet", "player"), parsed("narrator sheet Bartok"))
        assertEquals(listOf("narrator", "sheet", "player", "part", "page"), parsed("narrator sheet Bartok stats 2"))
        assertEquals(listOf("narrator", "memory"), parsed("narrator memory"))
        assertEquals(listOf("narrator", "memory", "status"), parsed("narrator memory status"))
        assertEquals(listOf("narrator", "memory", "canon"), parsed("narrator memory canon"))
        assertEquals(listOf("narrator", "memory", "canon", "open", "page"), parsed("narrator memory canon open 2"))
        assertEquals(listOf("narrator", "memory", "canon", "all", "player"), parsed("narrator memory canon all Bartok"))
        assertEquals(listOf("narrator", "memory", "canon", "all", "player", "page"), parsed("narrator memory canon all Bartok 3"))
        assertEquals(listOf("narrator", "memory", "recall", "query"), parsed("narrator memory recall lave du Nether"))
        assertEquals(listOf("narrator", "memory", "show", "ref", "page"), parsed("narrator memory show C-45 2"))
        assertEquals(listOf("narrator", "memory", "forget", "ref"), parsed("narrator memory forget C-45"))
        assertEquals(listOf("narrator", "memory", "forget", "ref", "reason"), parsed("narrator memory forget C-45 souvenir inventé"))
        assertEquals(listOf("narrator", "memory", "restore", "ref"), parsed("narrator memory restore C-45"))
        assertEquals(listOf("narrator", "memory", "note", "text"), parsed("narrator memory note Bartok préfère le Plongeur."))
        assertEquals(listOf("narrator", "memory", "docs", "key"), parsed("narrator memory docs sheet:Bartok:recap 2 1"))
        assertEquals(listOf("narrator", "memory", "rollback", "key_and_version"), parsed("narrator memory rollback grimoire 3"))
        assertEquals(listOf("narrator", "memory", "revert", "run"), parsed("narrator memory revert 7"))
        listOf("chronicle", "dream", "pause", "resume").forEach { assertEquals(listOf("narrator", "memory", it), parsed("narrator memory $it")) }
    }

    // ── ideas (M6 §13) ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `memory ideas lists the open ideas, or all of them, with what became of each`() = runBlocking<Unit> {
        assertEquals(
            listOf("[Narrator] ideas · open · 0 ideas", "No open idea.", "Details: /narrator memory show <ref>"),
            MemoryCommands.ideas(services, all = false, 1).unwrapped(),
        )
        val storyteller = Author(Source.STORYTELLER, runId = 21, conversationId = "storyteller-21")
        f.playClock.world = 10_000
        val race = IdeaDraft(
            IdeaKind.CHALLENGE, "La course au pic", "Une course au sommet du pic enneigé.", "Bartok et Mimosa en ligne.",
            listOf(bartok.uuid, mimosa.uuid), novelty = 4, funScore = 4, fairness = 5, playHours = 30,
        )
        val first = f.store.addIdea(race, storyteller).row
        val gift = f.store.addIdea(IdeaDraft(IdeaKind.FAVOR, "Le gâteau", "Un gâteau tombe du ciel.", "N'importe quand.", novelty = 3, funScore = 5, fairness = 5), storyteller).row
        f.store.dealt(listOf(first.ref), RecallChannel.INSPIRE, RecallContext("director-c13", 42, null))
        f.store.dropIdea(gift.ref, "Le gâteau a déjà servi.", storyteller)
        fake.clock.world = 10_000 + 2 * 3_600

        assertEquals(
            listOf(
                "[Narrator] ideas · open · 1 idea",
                "${first.ref} challenge → Bartok, Mimosa · open · 28h00m of play left · dealt 1 (last 19:40:00) · n4 f4 e5: « La course au pic »",
                "Details: /narrator memory show <ref>",
            ),
            MemoryCommands.ideas(services, all = false, 1).unwrapped(),
        )
        assertEquals(
            listOf(
                "[Narrator] ideas · all · 2 ideas",
                "${gift.ref} favor → everyone · dropped 19:40:00 · never dealt · n3 f5 e5: « Le gâteau » · \"Le gâteau a déjà servi.\"",
                "${first.ref} challenge → Bartok, Mimosa · open · 28h00m of play left · dealt 1 (last 19:40:00) · n4 f4 e5: « La course au pic »",
                "Details: /narrator memory show <ref>",
            ),
            MemoryCommands.ideas(services, all = true, 1).unwrapped(),
        )

        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(LiteralArgumentBuilder.literal<CommandSourceStack>("narrator").also(MemoryCommands::attach))
        fun parsed(command: String): List<String> = dispatcher.parse(command, null).also {
            assertTrue(!it.reader.canRead() && it.exceptions.isEmpty() && it.context.command != null, "$command does not parse: ${it.exceptions}")
        }.context.nodes.map { it.node.name }
        assertEquals(listOf("narrator", "memory", "ideas"), parsed("narrator memory ideas"))
        assertEquals(listOf("narrator", "memory", "ideas", "page"), parsed("narrator memory ideas 2"))
        assertEquals(listOf("narrator", "memory", "ideas", "all"), parsed("narrator memory ideas all"))
        assertEquals(listOf("narrator", "memory", "ideas", "all", "page"), parsed("narrator memory ideas all 3"))
    }

    @Test
    fun `a player is found online, by name or former name in the Journal, or by UUID`() = runBlocking<Unit> {
        f.sql("INSERT INTO player_names VALUES (?, 'Mimi', ?)", mimosa.uuid, evening)
        assertEquals(bartok, MemoryCommands.findPlayer(services, "BARTOK"))
        assertEquals(mimosa, MemoryCommands.findPlayer(services, "mimi"))
        assertEquals(mimosa, MemoryCommands.findPlayer(services, mimosa.uuid.toString()))
        assertNull(MemoryCommands.findPlayer(services, "Kelvin"))
        val online = PlayerStates().apply {
            put(LivePlayer(PlayerRef(UUID.randomUUID(), "Kelvin"), 1, 0, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20, null, null, 0, false))
        }
        assertEquals("Kelvin", MemoryCommands.findPlayer(TestServices(clock = f.clock, players = online), "kelvin")?.name)
    }
}
