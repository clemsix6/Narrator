package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.memory.stats.PairStats
import io.github.clemsix6.narrator2.memory.stats.SheetStats
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sheet assembled from the database (M3 §3.5), through the real store, on an in-memory Journal. */
class DuckPlayerSheetsTest {
    private val f = StoreFixture()
    private val store = f.store
    private val players = PlayerStates()
    private val sheets = DuckPlayerSheets(f.journal, f.playClock, players, ZoneOffset.UTC)
    private val evening = f.clock.instant()
    private val now = evening.plusSeconds(3_600).toEpochMilli()

    @AfterEach
    fun close() = f.close()

    private fun minutesAgo(minutes: Long): Instant = evening.minusSeconds(minutes * 60)

    @Test
    fun `a player never seen has no sheet`() = runBlocking<Unit> {
        assertNull(sheets.sheet(UUID.randomUUID(), now))
    }

    @Test
    fun `a player just seen has an empty sheet, said empty`() = runBlocking<Unit> {
        val sheet = assertNotNull(sheets.sheet(f.mimosa.uuid, now))
        assertEquals(f.mimosa, sheet.player)
        assertEquals(emptyMap(), sheet.docs)
        assertEquals(emptyList(), sheet.traits)
        assertEquals(emptyMap(), sheet.stats)
        assertNull(sheet.favor)
        assertNull(sheet.respect)
        assertNull(sheet.lastAttentionAgoPlayedSeconds)
        assertFalse(sheet.online)
        assertContains(sheets.render(sheet, SheetDetail.CONDENSED, now), "Recap: (not written yet")
    }

    @Test
    fun `everything known about a player, from every table`() = runBlocking<Unit> {
        val bartok = f.bartok.uuid
        val mimosa = f.mimosa.uuid
        f.sql("INSERT INTO player_names VALUES (?, 'Bartok_', ?), (?, 'Bartok', ?)", bartok, minutesAgo(9_000), bartok, minutesAgo(10))
        f.sql("UPDATE players SET sessions = 23, is_op = true WHERE player_uuid = ?", bartok)
        f.playClock.players[bartok] = 220_000
        players.put(LivePlayer(f.bartok, 1, now, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20, null, null, now, false))

        // Canon: the nickname replaced, a title, an open promise, a law for everyone, a closed threat.
        store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « la Taupe ».", "la Taupe", listOf(bartok)), f.director)
        val nickname = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « le Plongeur ».", "le Plongeur", listOf(bartok)), f.director).row
        val title = store.addCanon(CanonDraft(CanonKind.TITLE, "Bartok porte le titre « Roi des Chutes ».", players = listOf(bartok)), f.director).row
        val promise = store.addCanon(
            CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis une pioche en diamant à Bartok.", players = listOf(bartok), dueAt = evening.plusSeconds(7_200)),
            f.director,
        ).row
        store.addCanon(CanonDraft(CanonKind.LAW, "Le Narrateur interdit de creuser sous le spawn.", players = emptyList()), f.director)
        val threat = store.addCanon(CanonDraft(CanonKind.THREAT, "Le Narrateur a menacé Bartok de foudre.", players = listOf(bartok)), f.director).row
        store.resolveCanon(threat.ref, CanonStatus.FULFILLED, "La foudre est tombée sur Bartok.", emptyList(), f.director)

        // Interactions: an insult, an old provocation archived, a rescue (+3), a punishment, Mimosa's own.
        val insult = store.addInteraction(
            InteractionDraft(bartok, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul »", quote = "t'es nul", at = minutesAgo(5)), f.director,
        ).row
        val provocation = store.addInteraction(InteractionDraft(bartok, InteractionKind.PROVOCATION, -2, "a défié le Narrateur", at = minutesAgo(60)), f.director).row
        val rescue = store.addInteraction(InteractionDraft(bartok, InteractionKind.HELP, 3, "a sauvé Mimosa de la lave", at = minutesAgo(120)), f.director).row
        val punishment = store.addInteraction(InteractionDraft(bartok, InteractionKind.PUNISHMENT, 0, "a reçu une minute de lenteur", at = minutesAgo(4)), f.director).row
        store.addInteraction(InteractionDraft(mimosa, InteractionKind.COMPLIMENT, 1, "a félicité le Narrateur", at = minutesAgo(3)), f.director)

        // Memories, ranked by importance × strength on the world clock: an old strong one has faded below a fresh one.
        f.playClock.world = 0
        val old = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Le sauvetage", "Bartok a tiré Mimosa de la lave.", listOf(bartok, mimosa), importance = 8), f.director).row
        f.playClock.world = 1_000_000
        val fresh = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "La chute", "Bartok est tombé du pont.", listOf(bartok), importance = 5), f.director).row
        val small = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Une torche", "Bartok a posé une torche.", listOf(bartok), importance = 3), f.director).row
        val forgotten = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Rien", "Bartok a mangé une pomme.", listOf(bartok), importance = 9), f.director).row
        store.archive(forgotten.ref, true, "not worth it", f.operator)
        store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Le pont", "Mimosa a fini son pont.", listOf(mimosa), importance = 9), f.director)

        // The Dreamer: traits (one proved only by the archived provocation, one retired), skills, a relation, documents.
        store.setTrait(TraitDraft(bartok, "boldness", 1, intensity = 4, confidence = 0.8, evidence = listOf(old.ref, provocation.ref)), f.dreamer)
        store.setTrait(TraitDraft(bartok, "deference", 1, intensity = 3, confidence = 0.7, evidence = listOf(provocation.ref)), f.dreamer)
        store.setTrait(TraitDraft(bartok, "humor", 1, intensity = 2, confidence = 0.6, evidence = listOf(insult.ref)), f.dreamer)
        store.retireTrait(bartok, "humor", "plus aucune blague depuis longtemps", f.dreamer)
        store.archive(provocation.ref, true, "noted by mistake", f.operator)
        store.setSkill(bartok, Skill.MINING, 8, "41 diamants", listOf(fresh.ref), f.dreamer)
        store.setRelation(bartok, mimosa, RelationKind.RIVAL, "se disputent chaque coffre", listOf(old.ref), f.dreamer)
        store.writeDoc(DocKey.Sheet(bartok, SheetSection.RECAP), "Explorateur tête baissée.", emptyList(), f.dreamer)
        store.writeDoc(DocKey.Sheet(bartok, SheetSection.RECAP), "Explorateur tête baissée, qui meurt souvent.", emptyList(), f.dreamer)

        // The code: stats and pairs.
        SheetStats.write(f.db.writer, bartok, listOf(SheetStats.Computed("deaths", 3.0, """{"top":[["lava",2],["fall",1]],"absurd":3}""")), evening)
        PairStats.write(f.db.writer, listOf(PairStats.PlayerPair(bartok, mimosa, 90.0, 30.0, 1, 2, 0, 4, evening)))

        val sheet = assertNotNull(sheets.sheet(bartok, now))
        assertEquals("Bartok", sheet.player.name)
        assertEquals(listOf("Bartok_"), sheet.formerNames)
        assertTrue(sheet.isOp)
        assertEquals(23, sheet.sessions)
        assertEquals(220_000L, sheet.playedSeconds)
        assertTrue(sheet.online)
        assertEquals(nickname.ref, sheet.nickname?.ref)
        assertEquals(title.ref, sheet.title?.ref)
        assertEquals(mapOf(SheetSection.RECAP to "Explorateur tête baissée, qui meurt souvent."), sheet.docs.mapValues { it.value.content })
        assertEquals(2, sheet.docs.getValue(SheetSection.RECAP).version)

        // Only the evidence still standing; a trait left with none, or retired, is not on the sheet.
        assertEquals(listOf("boldness"), sheet.traits.map { it.trait })
        assertEquals(listOf(old.ref), sheet.traits.single().evidence)
        assertEquals(listOf(Skill.MINING), sheet.skills.map { it.skill })
        assertEquals(listOf(f.mimosa), sheet.relations.map { it.other })

        assertEquals(setOf("deaths", SheetRenderer.PAIRS), sheet.stats.keys)
        val pair = (sheet.stats.getValue(SheetRenderer.PAIRS).detail!!["pairs"] as JsonArray).single() as JsonObject
        assertEquals("Mimosa", pair["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("1", "2", "0", "4"), listOf("pvp_won", "pvp_lost", "griefs_done", "griefs_suffered").map { pair[it]!!.jsonPrimitive.content })

        // The history: newest first, archived left out; the marked are ±3; every lack of respect.
        assertEquals(listOf(punishment.ref, insult.ref, rescue.ref), sheet.recent.map { it.ref })
        assertEquals(listOf(rescue.ref), sheet.marked.map { it.ref })
        assertEquals(listOf(insult.ref), sheet.disrespect.map { it.ref })
        assertNotNull(sheet.favor)
        assertNull(sheet.respect, "under 5 messages to the Narrator")
        assertNotNull(sheet.lastAttentionAgoPlayedSeconds)
        assertEquals(listOf(promise.ref), sheet.openCanon.map { it.ref }, "the open canon about him: not his names, not a law for everyone, not what is closed")
        // 5 × 1 for the fresh one, 8 × 2^(−278 h / 207 h) ≈ 3.2 for the old one, 3 × 1 for the small one.
        assertEquals(listOf(fresh.ref, old.ref, small.ref), sheet.memories.map { it.ref })

        val full = sheets.render(sheet, SheetDetail.FULL, now)
        assertContains(full, "Bartok (formerly Bartok_) · « le Plongeur » (${nickname.ref}) · title « Roi des Chutes » (${title.ref}) · operator")
        assertContains(full, "Time together: Mimosa 1h30m (30m this week · PvP 1-2 · griefs 0 done, 4 suffered)")
        assertContains(full, "Recap (10-01): Explorateur tête baissée, qui meurt souvent.")
    }

    @Test
    fun `a trait proved by a memory merged into another keeps that proof`() = runBlocking<Unit> {
        val bartok = f.bartok.uuid
        val a = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Chute", "Bartok est tombé du pont.", listOf(bartok)), f.director).row
        val b = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Rechute", "Bartok est retombé du même pont.", listOf(bartok)), f.director).row
        store.setTrait(TraitDraft(bartok, "boldness", 1, intensity = 3, confidence = 0.6, evidence = listOf(b.ref)), f.dreamer)
        store.merge(a.ref, listOf(b.ref), null, f.dreamer)
        val sheet = assertNotNull(sheets.sheet(bartok, now))
        assertEquals(listOf(b.ref), sheet.traits.single().evidence)
    }
}
