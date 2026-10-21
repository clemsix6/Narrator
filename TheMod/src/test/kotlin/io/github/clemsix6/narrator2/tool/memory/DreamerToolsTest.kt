package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture
import io.github.clemsix6.narrator2.memory.dream.GrimoireFormat
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Dreamer's own tools (M3 §8.3), on the real store: what each writes, and what the store and the tools refuse. */
class DreamerToolsTest {
    private val f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    private fun ok(result: ToolResult): String {
        assertEquals(ToolStatus.OK, result.status, result.text)
        return result.text
    }

    private fun invalid(result: ToolResult, starts: String) {
        assertEquals(ToolStatus.INVALID, result.status, result.text)
        assertTrue(result.text.startsWith("Invalid arguments: $starts"), result.text)
    }

    @Test
    fun `eleven tools of effect engine, the Dreamer's only, shown with the memory`() {
        val tools = dreamerTools(f.services)
        assertEquals(
            listOf(
                "set_trait", "retire_trait", "set_skill", "set_relation", "write_sheet", "dream_hint", "set_gag", "write_review",
                "write_chronicle", "write_grimoire", "merge_memories",
            ),
            tools.map { it.name },
        )
        tools.forEach { tool ->
            assertEquals(ToolEffect.ENGINE, tool.effect, tool.name)
            assertTrue(tool.description.length <= 600, "${tool.name}: a description read at every request stays short")
        }
        val wired = ToolEnv(journalAvailable = true, workerRoles = emptySet(), memoryAvailable = true)
        val dreamer = ToolCatalog.toolSetFor(Role.DREAMER, wired, f.services).names
        assertEquals(15, dreamer.size, "$dreamer")
        assertTrue(dreamer.containsAll(tools.map { it.name }))
        assertTrue("sql" !in dreamer && "player_profile" !in dreamer, "each step's opening holds what it needs: $dreamer")
        assertEquals(emptyList(), ToolCatalog.toolSetFor(Role.DIRECTOR, wired, f.services).names.filter { name -> tools.any { it.name == name } })
        assertEquals(emptyList(), ToolCatalog.toolSetFor(Role.DREAMER, wired.copy(memoryAvailable = false), f.services).names)
    }

    @Test
    fun `the Dreamer never commits the Narrator nor plans an arc, keeps lore, and resolves active arcs only`() {
        val e = f.evening()
        invalid(
            f.call(RememberTool(), """{"kind": "threat", "what": "Le Narrateur a menacé Bartok d'une pluie de slimes.", "players": ["Bartok"]}"""),
            "the Dreamer remembers only lore or an observation, not a threat",
        )
        invalid(
            f.call(RememberTool(), """{"kind": "nickname", "what": "Bartok est surnommé « la Taupe ».", "quote": "la Taupe", "players": ["Bartok"]}"""),
            "the Dreamer remembers only lore or an observation, not a nickname",
        )
        invalid(
            f.call(ResolveTool(), """{"id": "${e.nickname}", "outcome": "retired", "note": "Plus personne ne l'emploie."}"""),
            "the Dreamer resolves only arcs, and ${e.nickname} is a nickname",
        )
        invalid(f.call(ResolveTool(), """{"id": "${e.gag}", "outcome": "retired", "note": "Usé jusqu'à la corde."}"""), "the Dreamer resolves only arcs, and ${e.gag} is a running_gag")
        // M6 §6.5: an arc to come is the storyteller's.
        invalid(
            f.call(RememberTool(), """{"kind": "arc", "what": "Le Narrateur prépare un tournoi de pêche au bord de la lave.", "players": ["Bartok"], "theme": "tournoi_de_peche"}"""),
            "the Dreamer remembers only lore or an observation, not an arc: an arc to come is the storyteller's, who writes the programme after the Grimoire",
        )
        assertEquals(listOf("active", "active"), f.rows("SELECT status FROM canon ORDER BY id") { getString(1) })

        // An arc the play has settled, the Dreamer closes it.
        val ref = f.write {
            addCanon(
                CanonDraft(CanonKind.ARC, "Le Narrateur lance un tournoi de pêche au bord de la lave.", players = listOf(f.bartok.uuid), theme = "tournoi_de_peche"),
                f.director,
            ).row.ref
        }
        assertTrue(ok(f.call(ResolveTool(), """{"id": "$ref", "outcome": "abandoned", "note": "Le tournoi de pêche n'a pas eu lieu."}""")).startsWith("ok: $ref arc → Bartok is now abandoned"))
        assertTrue(ok(f.call(RememberTool(), """{"kind": "lore", "what": "Le lac de lave du Nether porte le nom de Bain de Bartok."}""")).endsWith("(lore)"))

        // M6 review 2, D6: an arc the storyteller planned is its own until the Narrator starts it; the Dreamer touches it neither way.
        val planned = f.write {
            addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare le procès des parieurs.", status = CanonStatus.PLANNED, theme = "proces_des_parieurs"), Author(Source.STORYTELLER, runId = 1)).row.ref
        }
        listOf("active", "abandoned").forEach { outcome ->
            invalid(
                f.call(ResolveTool(), """{"id": "$planned", "outcome": "$outcome", "note": "Le procès des parieurs n'aura pas lieu."}"""),
                "the Dreamer resolves only an active arc, and $planned is planned: a planned arc is the storyteller's, and the Narrator starts it",
            )
        }
        assertEquals(listOf("planned"), f.rows("SELECT status FROM canon WHERE id = ${planned.id}") { getString(1) })
    }

    @Test
    fun `set_trait changes pole on an axis, never stacking both, the confidence capped and the former value kept in the history`() {
        val e = f.evening()
        val set = SetTraitTool()
        assertEquals(
            "ok: Bartok is defiant 4/5 (deference), confidence 0.85, since 10-01",
            ok(f.call(set, """{"player": "Bartok", "trait": "deference", "side": "high", "intensity": 4, "confidence": "high", "evidence": ["${e.insulted}"]}""")),
        )
        f.clock.now = DreamerFixture.at("23:00")
        assertEquals(
            "ok: Bartok is respectful 2/5 (deference), confidence 0.50, since 10-01; the pole changed from defiant: confidence capped at 0.5, the former value stays in the history",
            ok(f.call(set, """{"player": "Bartok", "trait": "deference", "side": "low", "intensity": 2, "confidence": "high", "evidence": ["${e.provoked}"], "note": "s'excuse désormais"}""")),
        )
        val trait = f.write { traits(f.bartok.uuid) }.single()
        assertEquals(listOf(-1, -1), listOf(trait.side, trait.trend))
        assertEquals(DreamerFixture.at("23:00"), trait.since)
        assertEquals(listOf("pole changed"), f.rows("SELECT reason FROM memory_log WHERE table_name = 'player_traits' AND op = 'update'") { getString(1) })
        invalid(
            f.call(set, """{"player": "Bartok", "trait": "deference", "side": "high", "intensity": 2, "confidence": "low", "evidence": ["${e.prayed}"]}"""),
            "evidence: ${e.prayed} is not about Bartok",
        )
        invalid(f.call(set, """{"player": "Bartok", "trait": "boldness", "intensity": 2, "confidence": "low", "evidence": ["${e.lava}"]}"""), "side must be low or high")
        invalid(f.call(set, """{"player": "Bartok", "trait": "boldness", "side": "high", "intensity": 2, "confidence": "low", "evidence": ["e12"]}"""), "evidence: \"e12\" is not M-… nor I-…")
        assertEquals(
            "ok: retired the trait deference of Bartok; it stays in the history",
            ok(f.call(RetireTraitTool(), """{"player": "Bartok", "trait": "deference", "reason": "plus rien ne le montre"}""")),
        )
        assertEquals(emptyList(), f.write { traits(f.bartok.uuid) })
    }

    @Test
    fun `a fourth free trait is refused, and changing one of the three is not`() {
        val e = f.evening()
        val set = SetTraitTool()
        listOf("butin", "lave", "ponts").forEach { slug ->
            ok(f.call(set, """{"player": "Bartok", "trait": "free:$slug", "label": "obsédé par: $slug", "intensity": 3, "confidence": "medium", "evidence": ["${e.lava}"]}"""))
        }
        invalid(
            f.call(set, """{"player": "Bartok", "trait": "free:nuit", "label": "craint la nuit", "intensity": 2, "confidence": "low", "evidence": ["${e.lava}"]}"""),
            "Bartok has 3 free traits already (free:butin, free:lave, free:ponts): retire one or change one",
        )
        assertEquals(
            "ok: Bartok is « obsédé par: lave » 5/5 (free:lave), confidence 0.85, since 10-01, stronger",
            ok(f.call(set, """{"player": "Bartok", "trait": "free:lave", "label": "obsédé par: lave", "intensity": 5, "confidence": "high", "evidence": ["${e.lava}"]}""")),
        )
        invalid(
            f.call(set, """{"player": "Bartok", "trait": "free:nuit", "side": "high", "label": "craint la nuit", "intensity": 2, "confidence": "low", "evidence": ["${e.lava}"]}"""),
            "a free trait has no side",
        )
    }

    @Test
    fun `write_chronicle writes sessions, rewrites the season in progress, closes it at 12 sessions or 30 days, then the saga`() {
        val start = Instant.parse("2026-10-01T20:00:00Z")
        fun day(n: Long): Instant = start + Duration.ofDays(n)
        val chapters = (0L..13L).map { n -> chapter(n, day(if (n == 13L) 44 else n)) }
        val write = WriteChronicleTool()
        fun ref(text: String): Ref = Ref.parse(Regex("wrote (H-\\d+)").find(text)!!.groupValues[1])!!
        fun sessionOf(chapter: Ref): String = ok(f.call(write, """{"level": "session", "title": "Une soirée", "text": "Bartok a joué.", "covers": ["$chapter"]}"""))
        fun session(chapter: Ref): Ref = ref(sessionOf(chapter))
        fun season(covers: List<Ref>): ToolResult =
            f.call(write, """{"level": "season", "title": "La saison", "text": "Une saison de jeu.", "covers": [${covers.joinToString { "\"$it\"" }}]}""")

        val written = sessionOf(chapters[0])
        val sessions = mutableListOf(ref(written))
        assertEquals("ok: wrote ${sessions[0]}, the session 10-01 20:00–21:00 (1 chapter)", written)
        invalid(f.call(write, """{"level": "session", "title": "Encore", "text": "Bartok a joué.", "covers": ["${chapters[0]}"]}"""), "covers: ${chapters[0]} is in the session ${sessions[0]} already")
        val first = ref(ok(season(sessions)).also { assertTrue(it.endsWith("the season in progress (1 session over 0 days); it closes at 12 sessions or 30 days"), it) })

        (1..4).forEach { sessions += session(chapters[it]) }
        invalid(season(sessions.drop(1)), "covers: the season in progress $first covers ${sessions[0]}; its rewrite keeps them: cite them too")
        val second = ref(ok(season(sessions)).also { assertTrue(it.endsWith("the season in progress (5 sessions over 4 days, rewriting $first); it closes at 12 sessions or 30 days"), it) })
        assertEquals(listOf(false to second.id), f.rows("SELECT open, superseded_by FROM chronicle WHERE id = ${first.id}") { getBoolean(1) to getLong(2) })

        (5..12).forEach { sessions += session(chapters[it]) }
        invalid(season(sessions), "covers: a season covers 12 sessions at most")
        val closed = ok(season(sessions.take(12)))
        val third = ref(closed)
        assertEquals(
            "ok: wrote $third, and the season is closed (12 sessions over 11 days, rewriting $second); now rewrite the saga: write_chronicle level saga, covering $third",
            closed,
        )
        assertEquals(listOf(false), f.rows("SELECT open FROM chronicle WHERE id = ${third.id}") { getBoolean(1) })

        val saga = ref(ok(f.call(write, """{"level": "saga", "title": "La saga", "text": "Toute l'histoire du serveur.", "covers": ["$third"]}""")))
        invalid(f.call(write, """{"level": "saga", "title": "La saga", "text": "Encore.", "covers": ["$third"]}"""), "no season closed since the saga $saga")
        invalid(season(listOf(sessions[11], sessions[12])), "covers: ${sessions[11]} is in the closed season $third")
        val fourth = ref(ok(season(listOf(sessions[12]))))
        invalid(f.call(write, """{"level": "saga", "title": "La saga", "text": "Encore.", "covers": ["$third", "$fourth"]}"""), "covers: $fourth is the season in progress")

        sessions += session(chapters[13])
        val late = ok(season(listOf(sessions[12], sessions[13])))
        assertTrue(late.contains("and the season is closed (2 sessions over 32 days, rewriting $fourth)"), late)
        val fifth = ref(late)
        assertTrue(ok(f.call(write, """{"level": "saga", "title": "La saga", "text": "Toute l'histoire.", "covers": ["$third", "$fifth"]}""")).endsWith("the saga (2 seasons, rewriting $saga)"))
        invalid(f.call(write, """{"level": "saga", "title": "La saga", "text": "Encore.", "covers": ["$fifth"]}"""), "covers: the saga")
        assertEquals(
            listOf("saga" to true),
            f.rows("SELECT level, open FROM chronicle WHERE level = 'saga' AND superseded_by IS NULL") { getString(1) to getBoolean(2) },
        )
    }

    /** A chapter entry of the chronicle, for the Director's chapter [n], from [from] for an hour. */
    private fun chapter(n: Long, from: Instant): Ref = f.write {
        addChronicle(
            ChronicleDraft(ChronicleLevel.CHAPTER, from, from + Duration.ofHours(1), "Chapter $n", "Bartok a joué.", conversationId = "director-c$n"),
            Author(Source.DIRECTOR),
        ).ref
    }

    @Test
    fun `merge_memories archives the duplicates into the memory kept`() {
        val e = f.evening()
        assertEquals(
            "ok: merged ${e.lavaAgain} into ${e.lava}, archived; ${e.lava}: « Mort dans la lave du Nether »",
            ok(f.call(MergeMemoriesTool(), """{"keep": "${e.lava}", "merged": ["${e.lavaAgain}"], "text": "Bartok est mort dans la lave du Nether, devant Mimosa."}""")),
        )
        val kept = f.write { memory(e.lava) }!!
        assertEquals("Bartok est mort dans la lave du Nether, devant Mimosa.", kept.content)
        assertEquals(6, kept.importance)
        assertEquals(e.lava, f.write { memory(e.lavaAgain) }!!.mergedInto)
        invalid(f.call(MergeMemoriesTool(), """{"keep": "${e.lava}", "merged": ["${e.lavaAgain}"]}"""), "merged: ${e.lavaAgain} is archived already")
        invalid(f.call(MergeMemoriesTool(), """{"keep": "${e.insulted}", "merged": ["${e.fall}"]}"""), "keep: \"${e.insulted}\" is not M-…")
        assertEquals(1L, f.count("memories", "archived"))
    }

    @Test
    fun `the sheet, the hint, the gag, the review and the Grimoire, within their rules`() {
        val e = f.evening()
        assertEquals("ok: wrote the likes of Mimosa (version 1)", ok(f.call(WriteSheetTool(), """{"player": "Mimosa", "section": "likes", "text": "Aime les falaises et les prières exaucées."}""")))
        invalid(f.call(WriteSheetTool(), """{"player": "Mimosa", "section": "likes", "text": "${"a".repeat(201)}"}"""), "text is longer than 200 characters")
        invalid(f.call(WriteSheetTool(), """{"player": "Mimosa", "section": "since_last", "text": "Rien."}"""), "")
        invalid(f.call(WriteSheetTool(), """{"player": "Mimosa", "section": "fears", "text": "Tu as peur du vide."}"""), "text must be factual and in the third person")

        val firstHint = ok(f.call(DreamHintTool(), """{"player": "Mimosa", "text": "Tombée d'une falaise ce soir, puis a prié."}"""))
        val first = Regex("N-\\d+").find(firstHint)!!.value
        val second = ok(f.call(DreamHintTool(), """{"player": "Mimosa", "text": "Une chute, une prière, des bottes rendues."}"""))
        assertTrue(second.endsWith("shown once, at their next join; it replaces $first"), second)
        assertEquals(listOf("done", "open"), f.rows("SELECT status FROM notes WHERE kind = 'dream_hint' ORDER BY id") { getString(1) })

        assertEquals("ok: ${e.gag} running gag is now retired, and closed", ok(f.call(SetGagTool(), """{"id": "${e.gag}", "state": "retired", "note": "usé jusqu'à la corde"}""")))
        invalid(f.call(SetGagTool(), """{"id": "${e.nickname}", "state": "worn", "note": "usé"}"""), "${e.nickname} is a nickname, not a running gag")
        assertEquals("ok: wrote the review (version 1)", ok(f.call(WriteReviewTool(), """{"text": "Le guichet des insultes fait rire Bartok."}""")))

        invalid(f.call(WriteGrimoireTool(), """{"text": "## Players\n- Bartok."}"""), "the Grimoire is not written: sections: ## Running gags, ## Arcs")
        val grimoire = GrimoireFormat.SECTIONS.joinToString("\\n") { "## $it\\n- aucun" }
        assertTrue(ok(f.call(WriteGrimoireTool(), """{"text": "$grimoire"}""")).startsWith("ok: wrote the Grimoire (version 1, "))
        assertEquals(listOf("dreamer"), f.rows("SELECT DISTINCT source FROM memory_docs") { getString(1) })
        assertEquals(listOf(12L), f.rows("SELECT DISTINCT run_id FROM memory_log WHERE actor = 'dreamer'") { getLong(1) })
        ok(f.call(SetSkillTool(), """{"player": "Mimosa", "skill": "survival", "score": 2, "rationale": "1 chute mortelle en une session"}"""))
        ok(f.call(SetRelationTool(), """{"player": "Mimosa", "other": "Bartok", "kind": "rival", "note": "le regarde mourir dans la lave", "evidence": ["${e.lava}"]}"""))
        invalid(f.call(SetRelationTool(), """{"player": "Mimosa", "other": "Mimosa", "kind": "ally", "note": "seule", "evidence": ["${e.lava}"]}"""), "a relation is between two different players")
        val unused = f.write { addMemory(MemoryDraft(MemoryKind.OBSERVATION, "observation", "Mimosa a ri.", listOf(f.mimosa.uuid)), Author(Source.DIRECTOR)) }
        assertEquals(ToolStatus.OK, f.call(SetSkillTool(), """{"player": "Mimosa", "skill": "luck", "score": 5, "rationale": "indice de chance 50/100", "evidence": ["${unused.row.ref}"]}""").status)
    }
}
