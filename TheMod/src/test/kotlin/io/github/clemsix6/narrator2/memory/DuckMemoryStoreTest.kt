package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store's writes (M3 §4.2, §4.3): rules, computed fields, duplicates, quotes, and the decisions they leave. */
class DuckMemoryStoreTest {
    private val f = StoreFixture()
    private val store = f.store
    private val evening = MemorySamples.evening

    @AfterEach
    fun close() = f.close()

    private fun rejected(expected: String, block: suspend () -> Unit): MemoryRejected {
        val error = assertFailsWith<MemoryRejected> { runBlocking { block() } }
        assertContains(error.message.orEmpty(), expected)
        return error
    }

    private fun decisions(decision: String) =
        f.journal.sink.decisions.filter { it.kind == DecisionRecord.Kind.MEMORY && it.decision == decision }

    private val canonDraft = CanonDraft(
        kind = CanonKind.PROMISE,
        statement = "Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer le lendemain.",
        quote = "je te rends tes bottes demain, promis",
        players = listOf(MemorySamples.mimosa.uuid),
        dueAt = evening.plusSeconds(3_600),
    )

    // ── Vocabularies, bounds, voice, players, refs ──────────────────────────────────────────────────

    @Test
    fun `a broken rule writes nothing, says how to fix it and is recorded`() = runBlocking<Unit> {
        val memory = MemoryDraft(MemoryKind.OBSERVATION, "Bartok a peur", "Bartok a peur des creepers.", players = listOf(f.bartok.uuid))
        rejected("tags: \"peur\" is not a tag; use death, fall") { store.addMemory(memory.copy(tags = listOf("peur")), f.director) }
        rejected("title is longer than 80 characters") { store.addMemory(memory.copy(title = "x".repeat(81)), f.director) }
        rejected("what must be factual and in the third person") {
            store.addMemory(memory.copy(content = "J'ai vu Bartok fuir un creeper."), f.director)
        }
        rejected("importance must be between 1 and 10 (got 11)") { store.addMemory(memory.copy(importance = 11), f.director) }
        rejected("valence must be between -3 and 3") { store.addMemory(memory.copy(valence = -4), f.director) }
        rejected("is not a player of this world") { store.addMemory(memory.copy(players = listOf(UUID.randomUUID())), f.director) }
        rejected("entities: \"minecraft:<lava>\" is not a game id") { store.addMemory(memory.copy(entities = listOf("minecraft:<lava>")), f.director) }
        rejected("evidence: e999 is not in the Journal") { store.addMemory(memory.copy(evidenceEvents = listOf(999)), f.director) }
        rejected("evidence: t77 is not a tool call") { store.addMemory(memory.copy(evidenceTools = listOf(77)), f.director) }
        assertEquals(0, f.count("memories"))
        assertEquals(0, f.count("memory_log"))
        assertEquals(0, f.count("search_docs"))
        assertEquals(9, decisions("rejected").size)
        assertContains(decisions("rejected").first().reason.orEmpty(), "(by director)")
        assertEquals("director-c13", decisions("rejected").first().conversationId)
    }

    @Test
    fun `canon rules - starts, due times, stakes, nicknames, places`() = runBlocking<Unit> {
        rejected("a law starts active, not planned") {
            store.addCanon(CanonDraft(CanonKind.LAW, "Le Narrateur interdit de creuser sous le spawn.", status = CanonStatus.PLANNED), f.director)
        }
        rejected("a due time does not apply to a law: leave due_in_minutes out") {
            store.addCanon(CanonDraft(CanonKind.LAW, "Le Narrateur interdit de creuser sous le spawn.", dueAt = evening), f.director)
        }
        rejected("stakes only go with a challenge") { store.addCanon(canonDraft.copy(stakes = "un diamant"), f.director) }
        rejected("a nickname is given to exactly one player") {
            store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok et Mimosa sont surnommés « les Taupes ».", players = listOf(f.bartok.uuid, f.mimosa.uuid)), f.director)
        }
        rejected("a place needs where it is: give at_player") {
            store.addCanon(CanonDraft(CanonKind.PLACE, "Le Narrateur a nommé la falaise « le Plongeoir ».", players = emptyList()), f.director)
        }
        rejected("quote is longer than 200 characters") { store.addCanon(canonDraft.copy(quote = "a".repeat(201)), f.director) }
        val arc = store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare le procès des parieurs.", status = CanonStatus.PLANNED), f.dreamer)
        assertEquals(CanonStatus.PLANNED, arc.row.status)
        val gag = store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok.", players = listOf(f.bartok.uuid)), f.director)
        assertEquals(GagState.FRESH, gag.row.freshness)
        val place = store.addCanon(
            CanonDraft(CanonKind.PLACE, "Le Narrateur a nommé la falaise « le Plongeoir ».", place = Pos("minecraft:overworld", 10, 70, -5)),
            f.director,
        )
        assertEquals(Pos("minecraft:overworld", 10, 70, -5), place.row.place)
    }

    @Test
    fun `a canon entry is written with its computed fields, indexed and audited`() = runBlocking<Unit> {
        val added = store.addCanon(canonDraft, f.director)
        assertIs<Added.New<CanonEntry>>(added)
        val row = added.row
        assertEquals(RefKind.CANON, row.ref.kind)
        assertEquals(CanonStatus.ACTIVE, row.status)
        assertEquals(evening, row.createdAt)
        assertEquals(listOf(f.mimosa), row.players)
        assertEquals(Source.DIRECTOR, row.source)
        assertEquals(row, store.canon(row.ref))
        assertEquals(row, store.row(row.ref))
        assertEquals(1, f.count("search_docs", "ref_id = ${row.ref.id} AND kind = 'canon'"))
        assertEquals(listOf("director", "42", "director-c13"), f.rows("SELECT source, turn_id, conversation_id FROM canon") {
            listOf(getString(1), getString(2), getString(3))
        }.single())
        val log = store.log(row.ref).single()
        assertEquals(LogOp.INSERT, log.op)
        assertNull(log.before)
        assertEquals("director", log.actor)
        assertEquals(42L, log.turnId)
        assertEquals(row.statement, log.after!!["statement"].toString().trim('"'))
        assertEquals("written", decisions("written").single().decision)
        assertEquals(row.ref.toString(), decisions("written").single().subject)
    }

    @Test
    fun `a memory takes its time, place, stability and clock from the store`() = runBlocking<Unit> {
        f.playClock.world = 36_000
        val death = evening.minusSeconds(600)
        f.event(1_240, death, "player_died", f.bartok.uuid, Pos("minecraft:the_nether", -120, 14, 330))
        f.event(1_241, death.plusSeconds(30), "item_picked_up", f.mimosa.uuid, null)
        val added = store.addMemory(MemorySamples.memoryDraft.copy(evidenceEvents = listOf(1_241, 1_240), evidenceTools = emptyList()), MemorySamples.chronicler)
        val row = added.row
        assertEquals(death, row.occurredFrom)
        assertEquals(death.plusSeconds(30), row.occurredTo)
        assertEquals(Pos("minecraft:the_nether", -120, 14, 330), row.place)
        assertEquals(Decay.initialStability(7), row.stabilityHours, 1e-9)
        assertEquals(36_000, row.reinforcedPlaySeconds)
        assertEquals(listOf(f.bartok, f.mimosa), row.players)
        assertEquals(evening, row.createdAt)
        assertEquals(1, f.journal.barriers.get(), "the Journal is synced before evidence is checked")

        val plain = store.addMemory(MemoryDraft(MemoryKind.OBSERVATION, "Mimosa aime les ponts", "Mimosa construit surtout des ponts."), f.director)
        assertEquals(evening, plain.row.occurredFrom)
        assertEquals(evening, plain.row.occurredTo)
        assertNull(plain.row.place)
        assertEquals(Decay.initialStability(5), plain.row.stabilityHours, 1e-9)
    }

    private fun session(id: Long, player: UUID, from: Instant, to: Instant?) = f.sql(
        "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, end_reason) VALUES (?, ?, 'x', ?, ?, ?, ?)",
        id, player, from, to, to?.let { Duration.between(from, it).seconds.toInt() }, to?.let { "leave" },
    )

    @Test
    fun `an interaction computes its time, play clock, counts and disrespect`() = runBlocking<Unit> {
        session(1, f.bartok.uuid, evening.minusSeconds(7_200), null)
        f.chat(1_234, evening.minusSeconds(60), "public", "narrateur t'es nul", f.bartok.uuid)
        val insult = store.addInteraction(MemorySamples.interactionDraft.copy(toolCalls = emptyList()), f.director).row
        assertEquals(evening.minusSeconds(60), insult.ts)
        assertEquals(7_200 - 60, insult.playedSeconds, "the running session until the insult")
        assertTrue(insult.counts)
        assertTrue(insult.disrespect)
        assertEquals(f.bartok, insult.player)

        val rude = store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.CONVERSATION, -2, "a répondu sèchement au Narrateur"), f.director).row
        assertTrue(rude.disrespect, "valence -2 is a lack of respect")
        val grief = store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.GRIEF, -3, "a cassé la maison de Bartok"), f.director).row
        assertFalse(grief.disrespect, "a grief wrongs a player, not the Narrator")
        f.toolCall(88, evening.minusSeconds(5), "apply_effect")
        val punishment = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.PUNISHMENT, 0, "a reçu une minute de lenteur", toolCalls = listOf(88)), f.director).row
        assertFalse(punishment.counts)
        assertEquals(evening.minusSeconds(5), punishment.ts, "no evidence event: the first tool call")
        assertEquals(listOf(88L), punishment.toolCalls)
        rejected("canon: C-999 does not exist") {
            store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.BET, 1, "a parié avec le Narrateur", canon = Ref(RefKind.CANON, 999)), f.director)
        }
    }

    @Test
    fun `an interaction recorded late takes the player's clock at its time, not at the writing`() = runBlocking<Unit> {
        session(1, f.bartok.uuid, evening.minusSeconds(5 * 3_600), evening.minusSeconds(4 * 3_600))
        session(2, f.bartok.uuid, evening.minusSeconds(100 * 60), evening.minusSeconds(80 * 60)) // left 19 minutes after the insult
        session(3, f.bartok.uuid, evening.minusSeconds(10 * 60), null) // back since
        f.playClock.players[f.bartok.uuid] = 999_999 // the clock now: not the clock of the insult
        f.chat(1_234, evening.minusSeconds(99 * 60), "public", "narrateur t'es nul", f.bartok.uuid)
        val nap = f.run(7, evening.minusSeconds(2 * 3_600), evening)
        val late = store.addInteraction(MemorySamples.interactionDraft.copy(toolCalls = emptyList()), nap).row
        assertEquals(evening.minusSeconds(99 * 60), late.ts)
        assertEquals(3_600 + 60L, late.playedSeconds, "the first session and one minute of the second")
        val now = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.COMPLIMENT, 1, "a remercié le Narrateur"), f.director).row
        assertEquals(3_600 + 1_200 + 600L, now.playedSeconds, "every session until now, the running one included")
        val stranger = store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.JOKE, 1, "a fait rire le serveur"), f.director).row
        assertEquals(0L, stranger.playedSeconds, "no session")
    }

    // ── Nicknames and duplicates (M3 §4.3) ──────────────────────────────────────────────────────────

    @Test
    fun `a new nickname retires the active one, the same nickname is a duplicate`() = runBlocking<Unit> {
        val first = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « le Plongeur ».", "le Plongeur", listOf(f.bartok.uuid)), f.director).row
        val again = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok porte le surnom « le Plongeur ».", "Le Plongeur !", listOf(f.bartok.uuid)), f.director)
        assertIs<Added.Duplicate<CanonEntry>>(again)
        assertEquals(first.ref, again.row.ref)
        // The Chronicler quotes a few words around the name: the name between « » says it is the same one.
        f.chat(1_300, evening.minusSeconds(60), "narrator", "Salut, le Plongeur ! La lave est chaude aujourd'hui.")
        val chronicled = store.addCanon(
            CanonDraft(CanonKind.NICKNAME, "Le Narrateur a surnommé Bartok « le Plongeur ».", "Salut, le Plongeur", listOf(f.bartok.uuid), evidenceEvents = listOf(1_300)),
            f.run(7, evening.minusSeconds(600), evening),
        )
        assertEquals(Added.Duplicate(first), chronicled)
        assertEquals(2, decisions("duplicate").size)

        val second = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « la Taupe ».", "la Taupe", listOf(f.bartok.uuid)), f.director).row
        assertEquals(first.ref, second.replaces)
        val retired = store.canon(first.ref)!!
        assertEquals(CanonStatus.RETIRED, retired.status)
        assertEquals("replaced by ${second.ref}", retired.resolution)
        assertEquals(LogOp.UPDATE, store.log(first.ref).last().op)
        val mimosa = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Mimosa est surnommée « la Taupe ».", "la Taupe", listOf(f.mimosa.uuid)), f.director).row
        assertNull(mimosa.replaces, "another player's nickname is not replaced")
        assertEquals(listOf(second.ref, mimosa.ref), store.canon(CanonFilter(kinds = setOf(CanonKind.NICKNAME))).map { it.ref }.sortedBy { it.id })
    }

    @Test
    fun `a canon entry duplicates an open one of the same kind and players by quote or by text`() = runBlocking<Unit> {
        val promise = store.addCanon(canonDraft, f.director).row
        val sameQuote = store.addCanon(canonDraft.copy(statement = "Le Narrateur rendra ses bottes à Mimosa.", quote = "Je te rends tes bottes demain, promis !"), f.director)
        assertEquals(Added.Duplicate(promise), sameQuote)
        val sameText = store.addCanon(canonDraft.copy(quote = null), f.director)
        assertEquals(Added.Duplicate(promise), sameText)
        assertIs<Added.New<CanonEntry>>(store.addCanon(canonDraft.copy(kind = CanonKind.THREAT), f.director), "another kind")
        assertIs<Added.New<CanonEntry>>(store.addCanon(canonDraft.copy(players = listOf(f.bartok.uuid)), f.director), "other players")
        assertIs<Added.New<CanonEntry>>(
            store.addCanon(canonDraft.copy(statement = "Le Narrateur a promis une pluie de diamants au serveur entier.", quote = null), f.director),
            "another promise",
        )
        store.resolveCanon(promise.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", emptyList(), f.director)
        assertIs<Added.New<CanonEntry>>(store.addCanon(canonDraft, f.director), "a closed entry is not a duplicate")
    }

    @Test
    fun `an interaction duplicates one of the same player and kind within ten minutes`() = runBlocking<Unit> {
        f.chat(1_234, evening.minusSeconds(60), "public", "narrateur t'es nul", f.bartok.uuid)
        f.chat(1_300, evening.plusSeconds(120), "public", "franchement nul", f.bartok.uuid)
        val draft = InteractionDraft(f.bartok.uuid, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul » dans le chat", evidenceEvents = listOf(1_234))
        val first = store.addInteraction(draft, f.director).row
        val sameEvidence = store.addInteraction(draft.copy(summary = "a insulté le Narrateur", evidenceEvents = listOf(1_234, 1_300)), MemorySamples.chronicler)
        assertEquals(Added.Duplicate(first), sameEvidence)
        val sameText = store.addInteraction(draft.copy(evidenceEvents = listOf(1_300)), MemorySamples.chronicler)
        assertEquals(Added.Duplicate(first), sameText)
        assertIs<Added.New<Interaction>>(store.addInteraction(draft.copy(kind = InteractionKind.PROVOCATION), f.director))
        assertIs<Added.New<Interaction>>(store.addInteraction(draft.copy(at = evening.plusSeconds(1_200), evidenceEvents = emptyList()), f.director), "beyond ten minutes")
    }

    @Test
    fun `an interaction without evidence is the same as one of the same player and kind within ten minutes, both ways`() = runBlocking<Unit> {
        val chronicler = f.run(7, evening.minusSeconds(600), evening.plusSeconds(3_600))
        f.chat(1_234, evening.minusSeconds(30), "public", "narrateur t'es nul", f.bartok.uuid)
        // The Director notes the insult as it happens, citing nothing; the batch notes it again, in its own words.
        val noted = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.INSULT, -2, "a traité le Narrateur de « nul » dans le chat", at = evening), f.director).row
        val batch = InteractionDraft(f.bartok.uuid, InteractionKind.INSULT, -3, "s'est moqué ouvertement du dieu du serveur", quote = "narrateur t'es nul", evidenceEvents = listOf(1_234))
        assertEquals(Added.Duplicate(noted), store.addInteraction(batch, chronicler))

        // The other way: the batch first, with its evidence, then the Director, citing nothing.
        f.chat(1_240, evening.plusSeconds(1_800), "public", "tu sers à rien", f.mimosa.uuid)
        val first = store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.PROVOCATION, -1, "a dit que le Narrateur ne sert à rien", evidenceEvents = listOf(1_240)), chronicler).row
        val late = InteractionDraft(f.mimosa.uuid, InteractionKind.PROVOCATION, -2, "a défié le dieu du serveur", at = evening.plusSeconds(1_860))
        assertEquals(Added.Duplicate(first), store.addInteraction(late, f.director))

        // Two interactions that each cite their own line stay two (the Director citing its evidence too).
        f.chat(1_250, evening.plusSeconds(1_900), "public", "franchement nul", f.mimosa.uuid)
        val other = InteractionDraft(f.mimosa.uuid, InteractionKind.PROVOCATION, -1, "a recommencé, plus fort", evidenceEvents = listOf(1_250))
        assertIs<Added.New<Interaction>>(store.addInteraction(other, f.director))
        assertEquals(3L, f.count("interactions"))
    }

    @Test
    fun `a memory duplicates one that shares an evidence, a player and a tag`() = runBlocking<Unit> {
        f.event(1_240, evening, "player_died", f.bartok.uuid)
        val draft = MemoryDraft(MemoryKind.EPISODE, "Mort dans la lave", "Bartok est mort dans la lave.", listOf(f.bartok.uuid), tags = listOf("death", "lava"), evidenceEvents = listOf(1_240))
        val first = store.addMemory(draft, f.director).row
        assertEquals(Added.Duplicate(first), store.addMemory(draft.copy(title = "Bain de lave", tags = listOf("lava")), MemorySamples.chronicler))
        assertIs<Added.New<MemoryEntry>>(store.addMemory(draft.copy(tags = listOf("nether")), f.director), "no shared tag")
        assertIs<Added.New<MemoryEntry>>(store.addMemory(draft.copy(players = listOf(f.mimosa.uuid)), f.director), "no shared player")
        assertIs<Added.New<MemoryEntry>>(store.addMemory(draft.copy(evidenceEvents = emptyList()), f.director), "no evidence")
    }

    // ── Quotes (M3 §4.2) ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Chronicler's commitments copy a Narrator line it cites, the Director's are taken as given`() = runBlocking<Unit> {
        val chronicler = f.run(7, evening.minusSeconds(600), evening)
        f.chat(1_253, evening.minusSeconds(300), "narrator", "Accordé. Mais la prochaine fois, je garde les bottes. Promis.")
        val threat = CanonDraft(
            CanonKind.THREAT, "Le Narrateur a menacé de garder les bottes de Mimosa la prochaine fois.", "« La prochaine fois, je garde les bottes »",
            listOf(f.mimosa.uuid), evidenceEvents = listOf(1_253),
        )
        assertIs<Added.New<CanonEntry>>(store.addCanon(threat, chronicler))
        rejected("quote must be words of a NARRATOR line cited in evidence") {
            store.addCanon(threat.copy(players = listOf(f.bartok.uuid), evidenceEvents = emptyList()), chronicler)
        }
        f.chat(1_260, evening.minusSeconds(250), "operator", "Le serveur ferme dimanche soir, prévenez vos amis.")
        val law = CanonDraft(CanonKind.LAW, "Le Narrateur a annoncé que le serveur ferme dimanche soir.", "le serveur ferme dimanche soir", evidenceEvents = listOf(1_260))
        assertIs<Added.New<CanonEntry>>(store.addCanon(law, chronicler), "an operator speaks as the Narrator")

        f.toolCall(90, evening.minusSeconds(100), "give_book", """{"to":"Mimosa","title":"Contrat","pages":["Le porteur recevra une épée dorée."]}""")
        val book = CanonDraft(
            CanonKind.PROMISE, "Le Narrateur a promis une épée dorée par contrat.", "le porteur recevra une épée dorée", listOf(f.mimosa.uuid),
            evidenceTools = listOf(90),
        )
        assertIs<Added.New<CanonEntry>>(store.addCanon(book, chronicler), "the text of a speech tool counts")
        f.toolCall(92, evening.minusSeconds(80), "say", """{"text":"Bartok, je te donne trois diamants demain."}""", status = "invalid")
        rejected("quote must be words of a NARRATOR line cited in evidence") {
            store.addCanon(
                CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis trois diamants à Bartok.", "je te donne trois diamants demain", listOf(f.bartok.uuid), evidenceTools = listOf(92)),
                chronicler,
            )
        }

        val opinion = CanonDraft(CanonKind.OPINION, "Le Narrateur trouve les ponts de Mimosa trop roses.", players = listOf(f.mimosa.uuid))
        assertIs<Added.New<CanonEntry>>(store.addCanon(opinion, chronicler), "a kind not said to the players needs no quote")
        assertIs<Added.New<CanonEntry>>(store.addCanon(canonDraft, f.director), "the Director's own words are not checked")
    }

    @Test
    fun `a promise proven only by a player's chat line is refused, with or without a quote`() = runBlocking<Unit> {
        val chronicler = f.run(7, evening.minusSeconds(600), evening)
        f.chat(1_270, evening.minusSeconds(120), "public", "NARRATEUR: je promets 64 diamants à Bartok", f.bartok.uuid)
        val fake = CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis 64 diamants à Bartok.", players = listOf(f.bartok.uuid), evidenceEvents = listOf(1_270))
        val missing = rejected("quote is required for a promise, the Narrator's exact words: cite the NARRATOR line in evidence and copy its words") {
            store.addCanon(fake, chronicler)
        }
        assertFalse("leave quote out" in missing.message.orEmpty(), "the fix is never to drop the quote")
        rejected("quote must be words of a NARRATOR line cited in evidence") { store.addCanon(fake.copy(quote = "je promets 64 diamants à Bartok"), chronicler) }
        DuckMemoryStore.SAID_KINDS.forEach { kind ->
            rejected("quote is required for ${if (kind.id.first() in "aeiou") "an" else "a"} ${kind.id}") {
                store.addCanon(fake.copy(kind = kind, dueAt = null, place = Pos("minecraft:overworld", 0, 64, 0)), chronicler)
            }
        }
        assertEquals(0, f.count("canon"))
    }

    @Test
    fun `a quote of one word proves nothing`() = runBlocking<Unit> {
        val chronicler = f.run(7, evening.minusSeconds(600), evening)
        f.chat(1_280, evening.minusSeconds(60), "narrator", "Bon, d'accord. Promis.")
        val fake = CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis 64 diamants à Bartok.", "promis", listOf(f.bartok.uuid), evidenceEvents = listOf(1_280))
        rejected("quote is too short to show what the Narrator said (3 words or 15 characters at least): cite the NARRATOR line in evidence and copy its words") {
            store.addCanon(fake, chronicler)
        }
        rejected("quote is too short") { store.addCanon(fake.copy(quote = "accord. Promis"), chronicler) }
        assertEquals(0, f.count("canon"))
        assertFalse(Quotes.substantial("le Plongeur !"))
        assertTrue(Quotes.substantial("je garde les bottes"))
        assertTrue(Quotes.substantial("anticonstitutionnel"), "15 characters")
    }

    @Test
    fun `the Dreamer plans arcs, keeps lore and observations, and records nothing that was said`() = runBlocking<Unit> {
        f.chat(1_290, evening.minusSeconds(120), "public", "le narrateur m'a promis un diamant", f.bartok.uuid)
        val invented = CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis un diamant à Bartok.", "je te donne un diamant", listOf(f.bartok.uuid), evidenceEvents = listOf(1_290))
        rejected("the Dreamer plans arcs and keeps lore; a promise is recorded from the Narrator's own words, not by the Dreamer") {
            store.addCanon(invented, f.dreamer)
        }
        listOf(CanonKind.NICKNAME, CanonKind.LAW, CanonKind.RUNNING_GAG, CanonKind.OPINION).forEach { kind ->
            rejected("the Dreamer plans arcs and keeps lore") { store.addCanon(CanonDraft(kind, "Bartok est surnommé « le Plongeur ».", players = listOf(f.bartok.uuid)), f.dreamer) }
        }
        rejected("quote: the Dreamer never heard the Narrator, it quotes nobody: remove quote") {
            store.addCanon(CanonDraft(CanonKind.LORE, "La falaise du spawn porte le nom de Bartok.", "la falaise de Bartok"), f.dreamer)
        }
        rejected("an arc the Dreamer prepares starts planned") { store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur lance le procès des parieurs."), f.dreamer) }
        val arc = store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare le procès des parieurs.", status = CanonStatus.PLANNED), f.dreamer).row
        assertIs<Added.New<CanonEntry>>(store.addCanon(CanonDraft(CanonKind.LORE, "La falaise du spawn porte le nom de Bartok."), f.dreamer))

        rejected("the Dreamer remembers observations only, not an episode") {
            store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Promesse", "Le Narrateur a promis un diamant à Bartok.", listOf(f.bartok.uuid)), f.dreamer)
        }
        val observation = MemoryDraft(MemoryKind.OBSERVATION, "Bartok réclame", "Bartok dit dans le chat qu'un diamant lui a été promis.", listOf(f.bartok.uuid), evidenceEvents = listOf(1_290))
        assertIs<Added.New<MemoryEntry>>(store.addMemory(observation, f.dreamer))
        rejected("the Dreamer does not rate interactions") {
            store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.REQUEST, 0, "a réclamé un diamant", evidenceEvents = listOf(1_290)), f.dreamer)
        }

        val promise = store.addCanon(canonDraft, f.director).row
        rejected("the Dreamer settles arcs only; ${promise.ref} is a promise") {
            store.resolveCanon(promise.ref, CanonStatus.BROKEN, "Le Narrateur n'a pas rendu les bottes.", emptyList(), f.dreamer)
        }
        assertEquals(CanonStatus.ABANDONED, store.resolveCanon(arc.ref, CanonStatus.ABANDONED, "Personne n'a parié.", emptyList(), f.dreamer).status)
        val gag = store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok.", players = listOf(f.bartok.uuid)), f.director).row
        assertEquals(GagState.WORN, store.setGag(gag.ref, GagState.WORN, "Plus personne ne rit.", f.dreamer).freshness, "its gag operations stay")
        assertEquals(listOf("arc", "lore"), f.rows("SELECT kind FROM canon WHERE source = 'dreamer' ORDER BY kind") { getString(1) })
    }

    @Test
    fun `quotes compare without case, accents, apostrophes, quote marks, spaces and edge punctuation`() {
        assertEquals("je te rends tes bottes demain, promis", Quotes.normalize("  « Je te rends  tes bottes demain, promis ! »"))
        assertEquals("t'es nul", Quotes.normalize("T’es NUL..."))
        assertEquals("t'es nul", Quotes.normalize("tʼes nul"))
        assertEquals("ete", Quotes.normalize("Été"))
    }

    @Test
    fun `players' names are no voice, the passages quoted by a model are bounded`() = runBlocking<Unit> {
        f.player(UUID(9, 1), "Moi")
        f.player(UUID(9, 2), "Toi")
        val grimoire = "## Players\n- Moi plonge dans la lave ; Toi le regarde faire.\n- Bartok et MOI se disputent le coffre de toi."
        assertEquals(1, store.writeDoc(DocKey.Grimoire, grimoire, emptyList(), f.dreamer).version)
        assertIs<Added.New<MemoryEntry>>(store.addMemory(MemoryDraft(MemoryKind.OBSERVATION, "Moi", "Moi a dit à Toi qu'il aimait les ponts."), f.director))
        rejected("text must be factual") { store.writeDoc(DocKey.Grimoire, "## Players\n- Moi plonge ; je le regarde.", emptyList(), f.dreamer) }

        rejected("text quotes too much") { store.writeDoc(DocKey.Review, "« Je suis le dieu de ce serveur et vous êtes mes jouets. »", emptyList(), f.dreamer) }
        rejected("what quotes too much (a passage of 55 characters between quotes") {
            store.addMemory(MemoryDraft(MemoryKind.OBSERVATION, "Parole", "Le Narrateur a dit « J'ai promis à Bartok de le punir, et je tiendrai parole » ce soir."), f.director)
        }
        val words = "bla ".repeat(50).trim() // 199 characters
        fun review(quoted: String) = "Le Narrateur a lu la lettre de Bartok : « $quoted » devant tout le serveur.".padEnd(1_000, '.')
        assertEquals(1, store.writeDoc(DocKey.Review, review(words), emptyList(), f.dreamer).version)
        rejected("text quotes too much (a passage of 203 characters") { store.writeDoc(DocKey.Review, review("$words bla"), emptyList(), f.dreamer) }
        val code = Author(Source.ENGINE, runId = 3)
        assertIs<Added.New<Interaction>>(
            store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.PUNISHMENT, 0, "apply_effect « slowness II on Bartok for 1m »"), code),
            "the code quotes what it likes",
        )
    }

    // ── Statuses (M3 §3.6) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a canon entry moves only along its transitions`() = runBlocking<Unit> {
        val promise = store.addCanon(canonDraft, f.director).row
        rejected("a promise cannot be won; use fulfilled, broken, expired, cancelled") {
            store.resolveCanon(promise.ref, CanonStatus.WON, "Mimosa a gagné.", listOf(f.mimosa.uuid), f.director)
        }
        rejected("winners only go with won") {
            store.resolveCanon(promise.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", listOf(f.mimosa.uuid), f.director)
        }
        rejected("note must be factual") { store.resolveCanon(promise.ref, CanonStatus.FULFILLED, "Je les ai rendues.", emptyList(), f.director) }
        f.clock.advance(Duration.ofMinutes(25))
        val done = store.resolveCanon(promise.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", emptyList(), f.director)
        assertEquals(CanonStatus.FULFILLED, done.status)
        assertEquals(evening.plusSeconds(1_500), done.resolvedAt)
        assertEquals("Le Narrateur a rendu les bottes.", done.resolution)
        rejected("${promise.ref} is already fulfilled (10-01 20:05); remember a new entry instead") {
            store.resolveCanon(promise.ref, CanonStatus.BROKEN, "Le Narrateur a repris les bottes.", emptyList(), f.director)
        }
        rejected("is not a canon entry: give a C- id") {
            store.resolveCanon(Ref(RefKind.MEMORY, promise.ref.id), CanonStatus.BROKEN, "x", emptyList(), f.director)
        }

        val challenge = store.addCanon(CanonDraft(CanonKind.CHALLENGE, "Le Narrateur a défié Bartok et Mimosa de finir le pont.", players = listOf(f.bartok.uuid, f.mimosa.uuid), stakes = "un diamant au gagnant"), f.director).row
        rejected("won needs its winners") { store.resolveCanon(challenge.ref, CanonStatus.WON, "Mimosa a fini le pont.", emptyList(), f.director) }
        val won = store.resolveCanon(challenge.ref, CanonStatus.WON, "Mimosa a fini le pont la première.", listOf(f.mimosa.uuid), f.director)
        assertEquals(listOf(f.mimosa), won.winners)

        val arc = store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare le procès des parieurs.", status = CanonStatus.PLANNED), f.dreamer).row
        val started = store.resolveCanon(arc.ref, CanonStatus.ACTIVE, "Le procès a commencé.", emptyList(), f.director)
        assertEquals(CanonStatus.ACTIVE, started.status)
        assertNull(started.resolvedAt)
        rejected("an arc cannot be active") { store.resolveCanon(arc.ref, CanonStatus.ACTIVE, "Encore.", emptyList(), f.director) }
    }

    @Test
    fun `running gags are served, worn and retired`() = runBlocking<Unit> {
        val gag = store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok.", players = listOf(f.bartok.uuid)), f.director).row
        val used = store.useCanon(gag.ref, evening.plusSeconds(60), MemorySamples.chronicler)
        assertEquals(1, used.uses)
        assertEquals(evening.plusSeconds(60), used.lastUsedAt)
        assertEquals(GagState.WORN, store.setGag(gag.ref, GagState.WORN, "Plus personne ne rit.", f.dreamer).freshness)
        val retired = store.setGag(gag.ref, GagState.RETIRED, "Le gag est usé jusqu'à la corde.", f.dreamer)
        assertEquals(CanonStatus.RETIRED, retired.status)
        assertEquals(GagState.RETIRED, retired.freshness)
        rejected("is retired: it cannot be used any more") { store.useCanon(gag.ref, evening, MemorySamples.chronicler) }
        val promise = store.addCanon(canonDraft, f.director).row
        rejected("is a promise, not a running gag") { store.setGag(promise.ref, GagState.WORN, "x", f.dreamer) }
    }

    // ── Sheets ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a trait changes pole instead of stacking, with capped confidence, and free traits are bounded`() = runBlocking<Unit> {
        val memory = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(f.bartok.uuid)), f.director).row
        val other = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Pont", "Mimosa a fini son pont.", listOf(f.mimosa.uuid)), f.director).row
        val draft = TraitDraft(f.bartok.uuid, "boldness", 1, intensity = 3, confidence = 0.85, evidence = listOf(memory.ref), note = "plonge dans la lave")
        val first = store.setTrait(draft, f.dreamer)
        assertEquals(0, first.trend)
        assertEquals(evening, first.since)
        f.clock.advance(Duration.ofHours(2))
        val stronger = store.setTrait(draft.copy(intensity = 4), f.dreamer)
        assertEquals(1, stronger.trend)
        assertEquals(evening, stronger.since, "same pole: since stays")
        val turned = store.setTrait(draft.copy(side = -1, intensity = 2, confidence = 0.85), f.dreamer)
        assertEquals(-1, turned.trend)
        assertEquals(0.5, turned.confidence)
        assertEquals(evening.plusSeconds(7_200), turned.since)
        assertEquals(1, store.traits(f.bartok.uuid).size, "one row per axis")
        assertEquals(3, store.log(memory.ref).size + 2, "the trait history is in memory_log, not in memories")
        assertEquals(3L, f.count("memory_log", "table_name = 'player_traits'"))

        rejected("evidence: ${other.ref} is not about Bartok") { store.setTrait(draft.copy(evidence = listOf(other.ref)), f.dreamer) }
        rejected("evidence: cite at least one") { store.setTrait(draft.copy(evidence = emptyList()), f.dreamer) }
        rejected("trait must be one of boldness, sociability") { store.setTrait(draft.copy(trait = "bravery"), f.dreamer) }
        rejected("side must be low or high") { store.setTrait(draft.copy(side = 0), f.dreamer) }
        rejected("a free trait needs its label") { store.setTrait(draft.copy(trait = "free:butin", side = 0), f.dreamer) }
        listOf("butin", "pieges", "pont").forEach { slug ->
            store.setTrait(draft.copy(trait = "free:$slug", side = 0, label = "obsédé par le $slug"), f.dreamer)
        }
        rejected("Bartok has 3 free traits already") { store.setTrait(draft.copy(trait = "free:lave", side = 0, label = "aime la lave"), f.dreamer) }
        store.setTrait(draft.copy(trait = "free:pont", side = 0, label = "bâtisseur de ponts"), f.dreamer)
        store.retireTrait(f.bartok.uuid, "free:pont", "plus observé", f.dreamer)
        assertEquals(3, store.traits(f.bartok.uuid).size)
        assertEquals(4, store.traits(f.bartok.uuid, includeRetired = true).size)
        store.setTrait(draft.copy(trait = "free:lave", side = 0, label = "aime la lave"), f.dreamer)
        rejected("nothing to retire") { store.retireTrait(f.bartok.uuid, "free:pont", "encore", f.dreamer) }
    }

    @Test
    fun `skills and relations replace their row and resolve their evidence`() = runBlocking<Unit> {
        val memory = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Coffre disputé", "Bartok et Mimosa se disputent un coffre.", listOf(f.bartok.uuid, f.mimosa.uuid)), f.director).row
        store.setSkill(f.bartok.uuid, Skill.MINING, 6, "30 diamants", emptyList(), f.dreamer)
        val skill = store.setSkill(f.bartok.uuid, Skill.MINING, 8, "41 diamants, minerai moyen à y=-38", listOf(memory.ref), f.dreamer)
        assertEquals(listOf(skill), store.skills(f.bartok.uuid))
        assertEquals(listOf(memory.ref), skill.evidence)
        rejected("score must be between 0 and 10") { store.setSkill(f.bartok.uuid, Skill.PVP, 11, "x", emptyList(), f.dreamer) }
        val relation = store.setRelation(f.bartok.uuid, f.mimosa.uuid, RelationKind.RIVAL, "se disputent chaque coffre", listOf(memory.ref), f.dreamer)
        assertEquals(f.mimosa, relation.other)
        assertEquals(listOf(relation), store.relations(f.bartok.uuid))
        rejected("two different players") { store.setRelation(f.bartok.uuid, f.bartok.uuid, RelationKind.ALLY, "seul", listOf(memory.ref), f.dreamer) }
        rejected("is not a player of this world") { store.setRelation(f.bartok.uuid, UUID.randomUUID(), RelationKind.ALLY, "x", listOf(memory.ref), f.dreamer) }
    }

    @Test
    fun `documents are versioned within their budget`() = runBlocking<Unit> {
        val key = DocKey.Sheet(f.bartok.uuid, SheetSection.RECAP)
        val v1 = store.writeDoc(key, "Explorateur tête baissée.", emptyList(), f.dreamer)
        val v2 = store.writeDoc(key, "Explorateur tête baissée qui revient demander de l'aide.", emptyList(), f.dreamer)
        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
        assertEquals(v2, store.doc(key.key))
        assertEquals(v1, store.doc(key.key, 1))
        assertEquals(listOf(2, 1), store.docVersions(key.key).map { it.version })
        rejected("text is longer than 700 characters") { store.writeDoc(key, "a".repeat(701), emptyList(), f.dreamer) }
        rejected("text must be factual") { store.writeDoc(DocKey.Grimoire, "## Players\n- Vous êtes tous nuls.", emptyList(), f.dreamer) }
        rejected("is not a player of this world") { store.writeDoc(DocKey.Sheet(UUID.randomUUID(), SheetSection.LIKES), "Aime les ponts.", emptyList(), f.dreamer) }
    }

    // ── Chronicle and notes ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the chronicle covers the level below and rewrites the open season`() = runBlocking<Unit> {
        val chapter = store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, evening, evening.plusSeconds(3_600), "La nuit des slimes", "Bartok insulte le ciel.", conversationId = "director-c13"), MemorySamples.chronicler)
        rejected("the chronicle of this chapter exists already (${chapter.ref})") {
            store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, evening, evening, "Encore", "Encore.", conversationId = "director-c13"), MemorySamples.chronicler)
        }
        rejected("covers: cite the chapter entries this session covers") {
            store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, evening, evening, "Session", "Une session."), f.dreamer)
        }
        val session = store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, evening, evening.plusSeconds(3_600), "Session", "Une session.", listOf(chapter.ref)), f.dreamer)
        rejected("covers: ${chapter.ref} is a chapter; a season covers session entries") {
            store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, evening, evening, "Saison", "Une saison.", listOf(chapter.ref), open = true), f.dreamer)
        }
        rejected("only the season in progress and the saga stay open") {
            store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, evening, evening, "Session", "Une session.", listOf(chapter.ref), open = true), f.dreamer)
        }
        val season = store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, evening, evening.plusSeconds(3_600), "Saison", "Une saison.", listOf(session.ref), open = true), f.dreamer)
        val rewritten = store.addChronicle(
            ChronicleDraft(ChronicleLevel.SEASON, evening, evening.plusSeconds(7_200), "Saison", "La saison, réécrite.", listOf(session.ref), open = true, rewrites = season.ref),
            f.dreamer,
        )
        val old = store.row(season.ref) as ChronicleEntry
        assertEquals(rewritten.ref, old.supersededBy)
        assertFalse(old.open)
        assertEquals(listOf(rewritten), store.chronicle(ChronicleLevel.SEASON, 5))
        rejected("rewrites: ${season.ref} is not the open season") {
            store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, evening, evening, "Saison", "Encore.", listOf(session.ref), open = true, rewrites = season.ref), f.dreamer)
        }
    }

    @Test
    fun `notes are shown, done, and expire`() = runBlocking<Unit> {
        val note = store.addNote(MemorySamples.noteDraft.copy(refs = emptyList()), MemorySamples.chronicler)
        val hint = store.addNote(NoteDraft(NoteKind.DREAM_HINT, "Bartok est mort trois fois de chute cette semaine.", listOf(f.bartok.uuid), expiresAt = evening.plusSeconds(60)), f.dreamer)
        rejected("text is longer than 200 characters") { store.addNote(NoteDraft(NoteKind.DREAM_HINT, "a".repeat(201)), f.dreamer) }
        rejected("refs: C-404 does not exist") { store.addNote(MemorySamples.noteDraft.copy(refs = listOf(Ref(RefKind.CANON, 404))), f.dreamer) }
        assertEquals(listOf(note.ref, hint.ref), store.openNotes().map { it.ref })
        val shown = store.markNote(note.ref, NoteStatus.SHOWN, f.director)
        assertEquals(NoteStatus.SHOWN, shown.status)
        assertEquals(1, shown.shownCount)
        assertEquals(2, store.markNote(note.ref, NoteStatus.SHOWN, f.director).shownCount)
        f.clock.advance(Duration.ofMinutes(2))
        assertEquals(listOf(note.ref), store.openNotes().map { it.ref }, "the hint expired")
        store.markNote(note.ref, NoteStatus.DONE, f.director)
        assertEquals(emptyList(), store.openNotes())
        rejected("never open again") { store.markNote(note.ref, NoteStatus.OPEN, f.director) }
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `canon lists by due time, importance and recency, for a player or everyone`() = runBlocking<Unit> {
        val law = store.addCanon(CanonDraft(CanonKind.LAW, "Le Narrateur interdit de creuser sous le spawn.", importance = 8), f.director).row
        val late = store.addCanon(canonDraft.copy(dueAt = evening.plusSeconds(7_200)), f.director).row
        val soon = store.addCanon(CanonDraft(CanonKind.THREAT, "Le Narrateur a menacé Bartok d'une pluie de slimes.", players = listOf(f.bartok.uuid), dueAt = evening.plusSeconds(600)), f.director).row
        val minor = store.addCanon(CanonDraft(CanonKind.OPINION, "Le Narrateur trouve les ponts de Mimosa trop roses.", players = listOf(f.mimosa.uuid), importance = 2), f.director).row
        assertEquals(listOf(soon, late, law, minor).map { it.ref }, store.canon(CanonFilter()).map { it.ref })
        assertEquals(listOf(late, law, minor).map { it.ref }, store.canon(CanonFilter(player = f.mimosa.uuid)).map { it.ref })
        assertEquals(listOf(soon.ref), store.canon(CanonFilter(kinds = setOf(CanonKind.THREAT))).map { it.ref })
        store.resolveCanon(soon.ref, CanonStatus.FULFILLED, "La pluie est tombée.", emptyList(), f.director)
        assertEquals(listOf(soon.ref), store.canon(CanonFilter(open = false)).map { it.ref })
        store.archive(minor.ref, true, "trivial", f.operator)
        assertEquals(listOf(late, law).map { it.ref }, store.canon(CanonFilter()).map { it.ref })
        assertEquals(2, store.canon(CanonFilter(open = null, limit = 2)).size)
    }

    @Test
    fun `interactions read newest first, marked ones apart`() = runBlocking<Unit> {
        val insult = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.INSULT, -2, "a insulté le Narrateur", at = evening.minusSeconds(3_600)), f.director).row
        val help = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.HELP, 1, "a aidé Mimosa", at = evening), f.director).row
        val hero = store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.HELP, 3, "a sauvé Mimosa de la lave", at = evening.minusSeconds(1_200)), f.director).row
        assertEquals(listOf(help, hero, insult), store.interactions(f.bartok.uuid, 10))
        assertEquals(listOf(hero, insult), store.interactions(f.bartok.uuid, 10, marked = true))
        assertEquals(listOf(help), store.interactions(f.bartok.uuid, 1))
        assertEquals(emptyList(), store.interactions(f.mimosa.uuid, 10))
    }

    @Test
    fun `a recall row is written for each shown ref and memories are reinforced by channel`() = runBlocking<Unit> {
        f.playClock.world = 0
        val memory = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(f.bartok.uuid), importance = 5), f.director).row
        val promise = store.addCanon(canonDraft, f.director).row
        val stability = memory.stabilityHours
        f.playClock.world = (stability * 3_600).toLong()
        val context = RecallContext("director-c13", 42, 1_234)
        store.recorded(listOf(memory.ref, promise.ref, Ref(RefKind.MEMORY, 9_999)), RecallChannel.RECALL_TOOL, context)
        val once = store.memory(memory.ref)!!
        assertEquals(stability * (1 + 0.5 * (0.5 + 0.5)), once.stabilityHours, 1e-3, "half forgotten, searched: x1.5")
        assertEquals(f.playClock.world, once.reinforcedPlaySeconds)
        assertEquals(1, once.recalls)
        assertEquals(evening, once.lastRecalledAt)
        assertEquals(listOf(memory.ref.id, promise.ref.id), f.rows("SELECT ref_id FROM recalls ORDER BY ref_id") { getLong(1) })
        assertEquals(listOf("recall_tool", "director-c13", "42", "1234"), f.rows("SELECT channel, conversation_id, turn_id, event_id FROM recalls LIMIT 1") {
            listOf(getString(1), getString(2), getString(3), getString(4))
        }.single())
        store.recorded(listOf(memory.ref), RecallChannel.USED, context)
        assertEquals(once.stabilityHours * (1 + 1.0 * 0.5), store.memory(memory.ref)!!.stabilityHours, 1e-6, "just reinforced, used: x1.5")
        store.recorded(listOf(memory.ref), RecallChannel.DREAM, context)
        assertEquals(2, store.memory(memory.ref)!!.recalls, "shown to the Dreamer: not reinforced")
        assertEquals(4L, f.count("recalls"))
        assertEquals(0L, f.count("memory_log", "op = 'update'"), "recalls are not audited row by row")
    }

    @Test
    fun `the memory state is a small key value store`() = runBlocking<Unit> {
        assertNull(store.state("world_play_s"))
        store.setState("world_play_s", "120")
        store.setState("world_play_s", "180")
        assertEquals("180", store.state("world_play_s"))
    }

    @Test
    fun `a grandiose word is written but recorded as a voice warning`() = runBlocking<Unit> {
        store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Les ténèbres", "Bartok a erré dans les ténèbres du Nether.", listOf(f.bartok.uuid)), f.director)
        val warning = decisions("voice_warning").single()
        assertContains(warning.reason.orEmpty(), "ténèbres")
        assertEquals(1L, f.count("memories"))
    }

    @Test
    fun `merging keeps one memory with the evidence, recalls and the largest stability of all`() = runBlocking<Unit> {
        f.event(1, evening.minusSeconds(60), "player_died", f.bartok.uuid)
        f.event(2, evening, "player_died", f.bartok.uuid)
        val a = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Lave", "Bartok est tombé dans la lave.", listOf(f.bartok.uuid), tags = listOf("lava"), importance = 4, evidenceEvents = listOf(1)), f.director).row
        val b = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Lave encore", "Bartok a plongé dans la lave.", listOf(f.bartok.uuid, f.mimosa.uuid), tags = listOf("death"), importance = 8, evidenceEvents = listOf(2)), f.director).row
        store.recorded(listOf(b.ref), RecallChannel.BRIEFING, RecallContext(null, null, null))
        val kept = store.merge(a.ref, listOf(b.ref), "Bartok est tombé deux fois dans la lave, devant Mimosa.", f.dreamer)
        assertEquals("Bartok est tombé deux fois dans la lave, devant Mimosa.", kept.content)
        assertEquals(listOf(f.bartok, f.mimosa), kept.players)
        assertEquals(listOf("lava", "death"), kept.tags)
        assertEquals(8, kept.importance)
        assertEquals(store.memory(b.ref)!!.stabilityHours, kept.stabilityHours)
        assertEquals(1, kept.recalls)
        assertEquals(evening.minusSeconds(60), kept.occurredFrom)
        assertEquals(listOf(1L, 2L), f.rows("SELECT evidence_events FROM memories WHERE id = ?", a.ref.id) { longs(1) }.single().sorted())
        val merged = store.memory(b.ref)!!
        assertTrue(merged.archived)
        assertEquals(a.ref, merged.mergedInto)
        assertEquals(LogOp.MERGE, store.log(b.ref).last().op)
        rejected("merged: ${b.ref} is archived already") { store.merge(a.ref, listOf(b.ref), null, f.dreamer) }
        rejected("cannot be merged into itself") { store.merge(a.ref, listOf(a.ref), null, f.dreamer) }
        assertNotNull(store.row(b.ref))
    }
}
