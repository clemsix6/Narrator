package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.testing.MemorySamples
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the others note of the mechanics engine's rows is the engine's already (M4 review, findings 32 and 41),
 * through the real store on the fixture's index: the Chronicler does not double a challenge the engine runs for
 * all, nor its results, and two offerings taken within ten minutes stay two.
 */
class DedupTest {
    private val f = StoreFixture()
    private val store = f.store
    private val evening = MemorySamples.evening
    private val engine = Author(Source.ENGINE)

    @AfterEach
    fun close() = f.close()

    /** A challenge as `start_challenge` writes it for everyone: no players. */
    private val forAll = CanonDraft(
        CanonKind.CHALLENGE, "Le Narrateur a défié tout le serveur de lui rapporter un œuf de tortue en quinze minutes.",
        quote = "Le premier qui me rapporte un œuf de tortue gagne une pomme d'or", stakes = "reward 1x golden_apple",
        mechanic = CanonMechanic.CHALLENGE,
    )

    @Test
    fun `the Chronicler's challenge naming the players who played is the engine's challenge for all`() = runBlocking<Unit> {
        val tracked = store.addCanon(forAll, f.director).row
        val chronicler = f.run(7, evening.minusSeconds(600), evening.plusSeconds(600))
        f.chat(1_253, evening.minusSeconds(60), "narrator", "Le premier qui me rapporte un œuf de tortue gagne une pomme d'or. Top départ !")
        val remembered = CanonDraft(
            CanonKind.CHALLENGE, "Le Narrateur a défié Bartok et Mimosa de lui rapporter un œuf de tortue en quinze minutes.",
            quote = "Le premier qui me rapporte un œuf de tortue gagne une pomme d'or", players = listOf(f.bartok.uuid, f.mimosa.uuid),
            dueAt = evening.plusSeconds(900), evidenceEvents = listOf(1_253),
        )

        assertEquals(Added.Duplicate(tracked), store.addCanon(remembered, chronicler), "no second row, and no DUE later")

        // Another challenge of the Director, about the same players, is still its own.
        val other = remembered.copy(statement = "Le Narrateur a défié Bartok et Mimosa de construire la plus haute tour.", quote = null, evidenceEvents = emptyList())
        assertIs<Added.New<CanonEntry>>(store.addCanon(other, f.director))
        assertEquals(2L, f.count("canon"))
    }

    @Test
    fun `a challenge's result noted by the Chronicler or the Director is the engine's, so that the favor moves once`() = runBlocking<Unit> {
        val c50 = store.addCanon(forAll, f.director).row.ref
        val won = store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.CHALLENGE_RESULT, 1, "won the challenge $c50", canon = c50), engine).row
        val chronicler = f.run(7, evening.minusSeconds(600), evening.plusSeconds(600))
        f.chat(1_260, evening.plusSeconds(30), "narrator", "Mimosa gagne l'œuf de tortue, et la pomme d'or avec.")
        f.clock.advance(Duration.ofMinutes(2))

        val noted = InteractionDraft(f.mimosa.uuid, InteractionKind.CHALLENGE_RESULT, 2, "a gagné le défi de l'œuf de tortue", evidenceEvents = listOf(1_260))
        assertEquals(Added.Duplicate(won), store.addInteraction(noted, chronicler))
        assertEquals(Added.Duplicate(won), store.addInteraction(noted.copy(evidenceEvents = emptyList()), f.director))
        assertEquals(1L, f.count("interactions"))

        // The engine's own results stay each their own, and its other kinds swallow nothing they do not share.
        val c51 = store.addCanon(forAll, f.director).row.ref
        assertIs<Added.New<Interaction>>(store.addInteraction(InteractionDraft(f.mimosa.uuid, InteractionKind.CHALLENGE_RESULT, 1, "won the challenge $c51", canon = c51), engine))
        store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.PUNISHMENT, 0, "Bartok punished, tiny (severity 2)", canon = c50), engine)
        assertIs<Added.New<Interaction>>(store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.PUNISHMENT, 1, "a été ralenti pour une insulte"), f.director))
    }

    @Test
    fun `two offerings taken within ten minutes are two, unless their notes say the same`() = runBlocking<Unit> {
        fun offering(note: String) = InteractionDraft(f.mimosa.uuid, InteractionKind.OFFERING, 1, note)
        val first = store.addInteraction(offering("pardon contre dix diamants"), f.director).row
        f.clock.advance(Duration.ofMinutes(4))
        assertIs<Added.New<Interaction>>(store.addInteraction(offering("grâce contre une pomme dorée"), f.director))
        assertEquals(Added.Duplicate(first), store.addInteraction(offering("pardon contre dix diamants"), f.director), "the same offering noted twice")

        // The Chronicler's note, with its evidence, is still the same as the Director's without.
        val chronicler = f.run(7, evening.minusSeconds(600), evening.plusSeconds(900))
        f.chat(1_270, evening.plusSeconds(240), "public", "tiens narrateur, une pomme dorée", f.mimosa.uuid)
        val noted = InteractionDraft(f.mimosa.uuid, InteractionKind.OFFERING, 1, "a offert une pomme dorée au Narrateur", evidenceEvents = listOf(1_270))
        assertIs<Added.Duplicate<Interaction>>(store.addInteraction(noted, chronicler))
        assertEquals(2L, f.count("interactions"))
    }
}
