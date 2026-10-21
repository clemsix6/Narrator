package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.testing.FakeMemoryState
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storyteller's ideas in the store (M6 §6.2) on the real index (BM25, French analyzer): who writes them, their
 * bounds, voice, players, refs, lever and arc; no repeat of a recent idea; one season; twelve open at most; their end in
 * world play (the hygiene); drops; and the cache of the open ideas ([OpenIdeas]).
 */
class IdeasStoreTest {
    private val f = StoreFixture()
    private val store = DuckMemoryStore(f.journal, SearchIndex(), f.playClock, f.clock)
    private val storyteller = Author(Source.STORYTELLER, runId = 21, conversationId = "storyteller-21")
    private val engine = Author(Source.ENGINE, runId = 30)

    private val race = IdeaDraft(
        kind = IdeaKind.CHALLENGE,
        title = "La course au pic",
        pitch = "Une course au sommet du pic enneigé au nord de la base ; le premier arrivé gagne un gâteau.",
        fit = "Bartok et Mimosa en ligne, un soir calme.",
        players = listOf(MemorySamples.bartok.uuid, MemorySamples.mimosa.uuid),
        register = ComicRegister.entries.first(),
        novelty = 4,
        funScore = 4,
        fairness = 5,
    )

    @AfterEach
    fun close() = f.close()

    private fun rejected(expected: String, block: suspend () -> Unit): MemoryRejected {
        val error = assertFailsWith<MemoryRejected> { runBlocking { block() } }
        assertContains(error.message.orEmpty(), expected)
        return error
    }

    private fun decisions(decision: String) =
        f.journal.sink.decisions.filter { it.kind == DecisionRecord.Kind.MEMORY && it.decision == decision }.map { "${it.subject}: ${it.reason}" }

    /** An idea about nothing else: its title and pitch share no word with another's ([WORDS]). */
    private fun distinct(i: Int, kind: IdeaKind = IdeaKind.FAVOR) =
        race.copy(kind = kind, title = WORDS[i].first, pitch = "${WORDS[i].first} et ${WORDS[i].second}.", fit = "N'importe quand.", players = emptyList())

    private suspend fun add(draft: IdeaDraft): Idea = store.addIdea(draft, storyteller).row

    private suspend fun idea(ref: Ref): Idea = store.row(ref) as Idea

    /** An arc the storyteller planned, of [theme]; [resolved]: then closed (by the Dreamer, who settles arcs). */
    private suspend fun arc(theme: String, resolved: Boolean = false): Ref {
        val arc = store.addCanon(CanonDraft(CanonKind.ARC, "Le procès des parieurs se prépare.", status = CanonStatus.PLANNED, theme = theme), storyteller).row
        if (resolved) store.resolveCanon(arc.ref, CanonStatus.RESOLVED, "Le procès a eu lieu.", emptyList(), f.dreamer)
        return arc.ref
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an idea is written open, due its hours of world play from now, indexed and audited as the storyteller's`() = runBlocking<Unit> {
        f.playClock.world = 36_000
        val promise = store.addCanon(CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis un gâteau à Bartok."), f.director).row
        val idea = add(race.copy(refs = listOf(promise.ref), playHours = 6))

        assertEquals(RefKind.IDEA, idea.ref.kind)
        assertEquals(IdeaStatus.OPEN, idea.status)
        assertEquals(36_000L + 6 * 3_600, idea.expiresPlaySeconds)
        assertEquals(listOf(f.bartok, f.mimosa), idea.players)
        assertEquals(listOf(promise.ref), idea.refs)
        assertEquals(Source.STORYTELLER, idea.source)
        assertEquals(f.clock.instant(), idea.createdAt)
        assertEquals(1, f.count("search_docs", "ref_id = ${idea.ref.id} AND kind = 'idea'"))
        assertEquals(listOf("storyteller", "21", "storyteller-21"), f.rows("SELECT source, run_id, conversation_id FROM ideas") {
            listOf(getString(1), getString(2), getString(3))
        }.single())
        val log = store.log(idea.ref).single()
        assertEquals(listOf("storyteller", "21", "insert"), listOf(log.actor, log.runId.toString(), log.op.id))
        assertEquals(listOf("${idea.ref}: idea challenge by storyteller"), decisions("written").filter { it.startsWith("P-") })
    }

    @Test
    fun `only the storyteller writes and drops ideas`() = runBlocking<Unit> {
        rejected("ideas are the storyteller's: the director writes none") { store.addIdea(race, f.director) }
        rejected("ideas are the storyteller's: the dreamer writes none") { store.addIdea(race, f.dreamer) }
        rejected("ideas are the storyteller's: the operator writes none") { store.addIdea(race, f.operator) }
        val idea = add(race)
        rejected("ideas are the storyteller's: the director drops none") { store.dropIdea(idea.ref, "Le moment est passé.", f.director) }
        assertEquals(1, f.count("ideas"))
    }

    @Test
    fun `texts, notes, hours, players and refs are checked`() = runBlocking<Unit> {
        rejected("pitch must be factual and in the third person") { store.addIdea(race.copy(pitch = "Je lance une course au pic."), storyteller) }
        rejected("fit must be factual and in the third person") { store.addIdea(race.copy(fit = "Quand vous êtes deux."), storyteller) }
        rejected("title is longer than 60 characters") { store.addIdea(race.copy(title = "x".repeat(61)), storyteller) }
        rejected("novelty must be between 1 and 5 (got 6)") { store.addIdea(race.copy(novelty = 6), storyteller) }
        rejected("fun must be between 1 and 5 (got 0)") { store.addIdea(race.copy(funScore = 0), storyteller) }
        rejected("hours must be between 1 and 168 (got 169)") { store.addIdea(race.copy(playHours = 169), storyteller) }
        rejected("players: ") { store.addIdea(race.copy(players = listOf(java.util.UUID.randomUUID())), storyteller) }
        rejected("refs: C-4242 does not exist") { store.addIdea(race.copy(refs = listOf(Ref(RefKind.CANON, 4_242))), storyteller) }
        val older = add(distinct(0))
        rejected("refs: ${older.ref} is not one of M-, C-, I-, H-, N-") { store.addIdea(race.copy(refs = listOf(older.ref)), storyteller) }
        rejected("refs: 6 refs at most") { store.addIdea(race.copy(refs = (1L..7L).map { Ref(RefKind.MEMORY, it) }), storyteller) }
        assertEquals(1, f.count("ideas"))
        assertEquals(10, decisions("rejected").size)
    }

    @Test
    fun `a module idea names its module, a forge idea has its brief, no other kind has either`() = runBlocking<Unit> {
        rejected("an idea module names the module of the arsenal it deploys: give module") { store.addIdea(race.copy(kind = IdeaKind.MODULE), storyteller) }
        rejected("module only goes with an idea module: leave it out") { store.addIdea(race.copy(module = "tag_game"), storyteller) }
        rejected("brief only goes with an idea forge: leave it out") { store.addIdea(race.copy(brief = "b".repeat(50)), storyteller) }
        rejected("an idea forge needs its brief for forge_module, 40 characters at least (got 0)") { store.addIdea(race.copy(kind = IdeaKind.FORGE), storyteller) }
        rejected("(got 39)") { store.addIdea(race.copy(kind = IdeaKind.FORGE, brief = "  ${"b".repeat(39)}  "), storyteller) }
        rejected("brief is longer than 1500 characters (got 1501)") { store.addIdea(race.copy(kind = IdeaKind.FORGE, brief = "b".repeat(1_501)), storyteller) }

        val module = add(distinct(1, IdeaKind.MODULE).copy(module = "tag_game"))
        assertEquals("tag_game", module.module)
        val brief = "Les joueurs voient des poules danser autour d'eux pendant deux minutes, puis elles s'envolent ; jamais de dégât."
        val forge = add(distinct(2, IdeaKind.FORGE).copy(brief = "  $brief "))
        assertEquals(brief, forge.brief)
    }

    @Test
    fun `an arc_step builds on a planned or active arc among its refs`() = runBlocking<Unit> {
        val step = distinct(3, IdeaKind.ARC_STEP)
        rejected("an arc_step builds on its arc: cite a planned or active arc (C-…) in refs") { store.addIdea(step, storyteller) }
        val promise = store.addCanon(CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis un gâteau à Bartok."), f.director).row
        rejected("an arc_step builds on its arc") { store.addIdea(step.copy(refs = listOf(promise.ref)), storyteller) }
        val closed = arc("proces_des_parieurs", resolved = true)
        rejected("an arc_step builds on its arc") { store.addIdea(step.copy(refs = listOf(closed)), storyteller) }
        val planned = arc("vengeance_des_moutons")
        assertEquals(listOf(promise.ref, planned), add(step.copy(refs = listOf(promise.ref, planned))).refs)
    }

    // ── No repeat ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an idea too close to one of the last 14 days is refused, whatever became of it`() = runBlocking<Unit> {
        val first = add(race)
        val again = race.copy(pitch = "Une course au sommet du pic enneigé au nord de la base ; le premier arrivé gagne une tarte.")
        val refusal = rejected("too close to ${first.ref} « La course au pic » (open, never dealt): find something else") { store.addIdea(again, storyteller) }
        assertEquals("too close to ${first.ref} « La course au pic » (open, never dealt): find something else", refusal.message)

        store.dealt(listOf(first.ref), RecallChannel.INSPIRE, RecallContext("director-c13", 42, null))
        f.clock.now = f.clock.now.plus(Duration.ofHours(2))
        store.dropIdea(first.ref, "Le pic a fondu.", storyteller)
        f.clock.now = f.clock.now.plus(Duration.ofHours(1))
        rejected("too close to ${first.ref} « La course au pic » (dropped 1h00m ago, dealt once 3h00m ago)") { store.addIdea(again, storyteller) }

        add(distinct(4)) // another idea is not a repeat
        f.clock.now = f.clock.now.plus(Duration.ofDays(14))
        assertEquals(IdeaStatus.OPEN, add(again).status, "14 days later, the same idea is new again")
    }

    // ── One season, twelve open ideas ───────────────────────────────────────────────────────────────

    @Test
    fun `a new season replaces the open one`() = runBlocking<Unit> {
        val manoeuvres = add(distinct(5, IdeaKind.SEASON))
        val other = add(distinct(6))
        val added = store.addIdea(distinct(7, IdeaKind.SEASON), storyteller)
        val siege = added.row

        val replaced = idea(manoeuvres.ref)
        assertEquals(listOf(replaced), added.closed, "what the writing closed, as it is now")
        assertEquals(2, added.open)
        assertEquals(IdeaStatus.DROPPED, replaced.status)
        assertEquals("replaced by the season ${siege.ref}", replaced.closeReason)
        assertEquals(f.clock.instant(), replaced.closedAt)
        assertEquals(setOf(other.ref, siege.ref), store.ideas().map { it.ref }.toSet())
        val change = store.log(manoeuvres.ref).last()
        assertEquals(listOf("storyteller", "update", "replaced by the season ${siege.ref}"), listOf(change.actor, change.op.id, change.reason))
        assertContains(decisions("written"), "${manoeuvres.ref}: idea dropped (replaced by the season ${siege.ref}) by storyteller")
    }

    @Test
    fun `beyond twelve open ideas the oldest never dealt expires, never the season`() = runBlocking<Unit> {
        val season = add(distinct(0, IdeaKind.SEASON))
        val ideas = (1..11).map { i ->
            f.clock.now = f.clock.now.plusSeconds(60)
            add(distinct(i))
        }
        store.dealt(listOf(ideas[0].ref), RecallChannel.HEARTBEAT, RecallContext("director-c13", 42, null))
        assertEquals(12, store.ideas().size)

        val added = store.addIdea(distinct(12), storyteller)
        val newest = added.row

        val open = store.ideas().map { it.ref }
        assertEquals(12, open.size)
        assertEquals(listOf(ideas[1].ref), added.closed.map { it.ref })
        assertEquals(12, added.open)
        assertTrue(season.ref in open && ideas[0].ref in open && newest.ref in open, "$open")
        val superseded = idea(ideas[1].ref)
        assertEquals(IdeaStatus.EXPIRED, superseded.status, "the oldest never dealt")
        assertEquals("superseded by ${newest.ref}: 12 ideas open at most", superseded.closeReason)
    }

    // ── Expiry, drops ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the hygiene expires an idea once its hours of world play are over`() = runBlocking<Unit> {
        f.playClock.world = 1_000
        val short = add(distinct(0).copy(playHours = 1))
        val long = add(distinct(1).copy(playHours = 48))
        val hygiene = Hygiene(store, f.journal, DuckPlayClock(FakeMemoryState(), f.journal, PlayerStates()), SearchIndex(), Decisions(f.journal.sink, f.clock), ZoneOffset.UTC)

        f.playClock.world = 1_000 + 3_599
        assertEquals(emptyList(), hygiene.tidy(f.clock.instant(), 30).expired, "a second of play left")
        assertEquals(0, f.journal.sink.decisions.count { it.decision == "idea_expired" })

        f.playClock.world = 1_000 + 3_600
        assertEquals(listOf(short.ref), hygiene.tidy(f.clock.instant(), 30).expired)
        val expired = idea(short.ref)
        assertEquals(IdeaStatus.EXPIRED, expired.status)
        assertEquals(DuckMemoryStore.IDEA_DUE, expired.closeReason)
        assertEquals(IdeaStatus.OPEN, idea(long.ref).status)
        val change = store.log(short.ref).last()
        assertEquals(listOf("engine", "30"), listOf(change.actor, change.runId.toString()))
        assertEquals(listOf("${short.ref}: favor « ${short.title} »: ${DuckMemoryStore.IDEA_DUE}"), decisions("idea_expired"))
        assertEquals(emptyList(), store.expireIdeas(engine), "nothing more")
    }

    @Test
    fun `dropping closes an open idea with its reason, once`() = runBlocking<Unit> {
        val idea = add(race)
        f.clock.now = f.clock.now.plusSeconds(600)
        rejected("reason must be factual and in the third person") { store.dropIdea(idea.ref, "Je n'en veux plus.", storyteller) }

        val dropped = store.dropIdea(idea.ref, "Le pic a fondu.", storyteller)

        assertEquals(IdeaStatus.DROPPED, dropped.status)
        assertEquals("Le pic a fondu.", dropped.closeReason)
        assertEquals(f.clock.instant(), dropped.closedAt)
        assertEquals(emptyList(), store.ideas())
        assertEquals(listOf(dropped), store.ideas(IdeaStatus.DROPPED))
        assertEquals("Le pic a fondu.", store.log(idea.ref).last().reason)
        rejected("${idea.ref} is dropped already: nothing to drop") { store.dropIdea(idea.ref, "Encore.", storyteller) }
        rejected("P-4242 does not exist") { store.dropIdea(Ref(RefKind.IDEA, 4_242), "Rien.", storyteller) }
        rejected("C-4242 is not an idea: give a P- id") { store.dropIdea(Ref(RefKind.CANON, 4_242), "Rien.", storyteller) }
    }

    // ── The cache of the open ideas ─────────────────────────────────────────────────────────────────

    @Test
    fun `the cache reads the open ideas again after each write through its store, and on its timer`() = runBlocking<Unit> {
        val ideas = OpenIdeas(store)
        val watched = ideas.watching(store)
        assertEquals(emptyList(), ideas.current)

        val first = watched.addIdea(race, storyteller).row
        assertEquals(listOf(first), ideas.current)
        watched.dealt(listOf(first.ref), RecallChannel.INSPIRE, RecallContext("director-c13", 42, null))
        assertEquals(1, ideas.current.single().dealt)
        watched.dropIdea(first.ref, "Le pic a fondu.", storyteller)
        assertEquals(emptyList(), ideas.current)

        val second = add(distinct(0)) // past the cache: the timer finds it
        assertEquals(emptyList(), ideas.current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch { ideas.refreshEvery(20) }
            val end = System.currentTimeMillis() + 5_000
            while (ideas.current.isEmpty() && System.currentTimeMillis() < end) delay(10)
            assertEquals(listOf(second.ref), ideas.current.map { it.ref })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a read of the cache that fails keeps the last list`() = runBlocking<Unit> {
        val idea = add(race)
        var down = false
        val flaky = object : MemoryStore by store {
            override suspend fun ideas(status: IdeaStatus?, limit: Int): List<Idea> = if (down) error("the Journal is closed") else store.ideas(status, limit)
        }
        val ideas = OpenIdeas(flaky)
        ideas.refresh("test")
        assertEquals(listOf(idea), ideas.current)
        down = true
        ideas.refresh("test")
        assertEquals(listOf(idea), ideas.current)
        assertNull(ideas.current.single().closedAt)
    }

    private companion object {
        /** Words no two ideas share: each idea's title and pitch are its own. */
        val WORDS = listOf(
            "Pomme" to "cerise", "Lanterne" to "tambour", "Fromage" to "boussole", "Citrouille" to "parapluie",
            "Accordéon" to "girafe", "Moustache" to "chandelle", "Trompette" to "grenouille", "Pelote" to "sablier",
            "Violon" to "marmite", "Escargot" to "cornichon", "Hamac" to "pingouin", "Biscotte" to "toboggan",
            "Quenouille" to "flamant",
        )
    }
}
