package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryDoc
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryLogRow
import io.github.clemsix6.narrator2.memory.MemoryRun
import io.github.clemsix6.narrator2.memory.LogOp
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.NoteStatus
import io.github.clemsix6.narrator2.memory.PlayerSheet
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Relation
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.RunStatus
import io.github.clemsix6.narrator2.memory.RunTrigger
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.SheetStat
import io.github.clemsix6.narrator2.memory.Skill
import io.github.clemsix6.narrator2.memory.SkillScore
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.Trait
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.TraitDraft
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * One row of each kind of the memory, and one draft of each, for tests (design M3 §3.4): two players,
 * Bartok and Mimosa, an evening of 2026-10-01. Texts are French and in the third person, as the store
 * wants them; they all pass `VoiceGuard`.
 */
object MemorySamples {
    val bartok = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000b471"), "Bartok")
    val mimosa = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000041a0"), "Mimosa")

    val evening: Instant = Instant.parse("2026-10-01T19:40:00Z")

    val director = Author(Source.DIRECTOR, turnId = 42, conversationId = "director-c13")
    val chronicler = Author(Source.CHRONICLER, runId = 7, conversationId = "chronicler-1a2b3c4d")
    val dreamer = Author(Source.DREAMER, runId = 8, conversationId = "dreamer-5e6f7a8b")
    val operator = Author(Source.OPERATOR, operator = "Clement")

    val memory = MemoryEntry(
        ref = Ref(RefKind.MEMORY, 120),
        kind = MemoryKind.EPISODE,
        createdAt = evening.plusSeconds(1_800),
        occurredFrom = evening,
        occurredTo = evening.plusSeconds(60),
        title = "Mort dans la lave du Nether",
        content = "Bartok est mort dans un lac de lave du Nether en creusant vers des débris antiques, devant Mimosa.",
        players = listOf(bartok, mimosa),
        entities = listOf("minecraft:lava", "minecraft:ancient_debris"),
        tags = listOf("death", "lava", "nether"),
        importance = 7,
        valence = -2,
        place = Pos("minecraft:the_nether", -120, 14, 330),
        stabilityHours = 118.0,
        reinforcedPlaySeconds = 36_000,
        recalls = 1,
        lastRecalledAt = evening.plusSeconds(3_600),
        source = Source.CHRONICLER,
        archived = false,
        mergedInto = null,
    )

    val canon = CanonEntry(
        ref = Ref(RefKind.CANON, 45),
        kind = CanonKind.PROMISE,
        createdAt = evening,
        statement = "Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer le lendemain.",
        quote = "je te rends tes bottes demain, promis",
        players = listOf(mimosa),
        stakes = null,
        importance = 6,
        status = CanonStatus.ACTIVE,
        dueAt = evening.plusSeconds(86_400),
        resolvedAt = null,
        resolution = null,
        winners = emptyList(),
        replaces = null,
        place = null,
        uses = 0,
        lastUsedAt = null,
        freshness = null,
        source = Source.DIRECTOR,
        archived = false,
    )

    val gag = canon.copy(
        ref = Ref(RefKind.CANON, 27),
        kind = CanonKind.RUNNING_GAG,
        statement = "Le Narrateur commente chaque chute de Bartok comme un plongeon olympique.",
        quote = null,
        players = listOf(bartok),
        dueAt = null,
        uses = 5,
        lastUsedAt = evening,
        freshness = GagState.WORN,
    )

    val interaction = Interaction(
        ref = Ref(RefKind.INTERACTION, 512),
        ts = evening.plusSeconds(120),
        player = bartok,
        kind = InteractionKind.INSULT,
        valence = -2,
        counts = true,
        disrespect = true,
        summary = "a traité le Narrateur de « nul » dans le chat",
        quote = "narrateur t'es nul",
        playedSeconds = 219_600,
        canon = null,
        memory = null,
        toolCalls = listOf(88),
        source = Source.DIRECTOR,
        archived = false,
    )

    val trait = Trait(
        player = bartok.uuid,
        trait = TraitAxis.BOLDNESS.id,
        side = 1,
        label = null,
        intensity = 4,
        confidence = 0.85,
        evidence = listOf(memory.ref, interaction.ref),
        since = evening.minusSeconds(604_800),
        trend = 1,
        note = "plonge dans la lave pour des débris antiques",
        setAt = evening,
        setPlayedSeconds = 219_000,
        retired = false,
    )

    val skill = SkillScore(bartok.uuid, Skill.MINING, 8, "41 diamants, minerai moyen à y=-38", emptyList(), evening)

    val relation = Relation(bartok.uuid, mimosa, RelationKind.RIVAL, "se disputent chaque coffre des chambres d'épreuve", listOf(memory.ref), evening)

    val doc = MemoryDoc(
        key = DocKey.Sheet(bartok.uuid, SheetSection.RECAP).key,
        version = 3,
        content = "Explorateur tête baissée qui écume les chambres d'épreuve. Provoque le Narrateur, puis revient lui demander de l'aide.",
        evidence = listOf(memory.ref),
        writtenAt = evening,
        source = Source.DREAMER,
    )

    val chronicle = ChronicleEntry(
        ref = Ref(RefKind.CHRONICLE, 88),
        level = ChronicleLevel.SESSION,
        from = evening.minusSeconds(3_600),
        to = evening.plusSeconds(7_200),
        title = "La nuit des slimes",
        summary = "Bartok insulte le ciel, le ciel répond par une pluie de slimes ; Mimosa finit son pont.",
        covers = listOf(Ref(RefKind.CHRONICLE, 85), Ref(RefKind.CHRONICLE, 86)),
        conversationId = null,
        open = false,
        supersededBy = null,
        source = Source.DREAMER,
    )

    val note = Note(
        ref = Ref(RefKind.NOTE, 9),
        createdAt = evening,
        kind = NoteKind.COHERENCE,
        text = "Le Narrateur a dit à Bartok que les lois ne valent pas la nuit ; C-25 ne prévoit pas d'exception.",
        players = listOf(bartok),
        refs = listOf(Ref(RefKind.CANON, 25)),
        status = NoteStatus.OPEN,
        shownCount = 0,
        expiresAt = null,
    )

    val log = MemoryLogRow(
        ts = evening, actor = "chronicler", runId = 7, turnId = null, table = "memories", rowKey = "120", op = LogOp.INSERT,
        before = null, after = buildJsonObject { put("id", 120) }, reason = null,
    )

    val run = MemoryRun(
        runId = 7, kind = RunKind.BATCH, trigger = RunTrigger.TIMER, status = RunStatus.DONE, startedAt = evening,
        endedAt = evening.plusSeconds(40), windowFrom = evening.minusSeconds(600), windowTo = evening, chapter = "director-c13",
        conversations = listOf("chronicler-1a2b3c4d"), writes = 2, rejected = 0, inputTokens = 9_800, outputTokens = 600,
        report = "Une promesse et une insulte enregistrées.", error = null,
    )

    val sheet = PlayerSheet(
        player = bartok,
        formerNames = listOf("Bartok_"),
        isOp = false,
        firstSeen = evening.minusSeconds(1_728_000),
        lastSeen = evening,
        sessions = 23,
        playedSeconds = 219_600,
        online = true,
        nickname = canon.copy(ref = Ref(RefKind.CANON, 12), kind = CanonKind.NICKNAME, statement = "Bartok est surnommé « le Plongeur ».", quote = "le Plongeur", players = listOf(bartok), dueAt = null),
        title = null,
        docs = mapOf(SheetSection.RECAP to doc),
        traits = listOf(trait),
        skills = listOf(skill),
        relations = listOf(relation),
        stats = mapOf("deaths" to SheetStat("deaths", 87.0, buildJsonObject { put("absurd", JsonPrimitive(40)) }, evening)),
        favor = -31.0,
        favorTrend = -8.0,
        respect = 35,
        disrespect = listOf(interaction),
        lastAttentionAgoPlayedSeconds = 1_500,
        recent = listOf(interaction),
        marked = emptyList(),
        openCanon = listOf(canon),
        memories = listOf(memory),
    )

    // ── Drafts ──────────────────────────────────────────────────────────────────────────────────────

    val memoryDraft = MemoryDraft(
        kind = MemoryKind.EPISODE, title = memory.title, content = memory.content, players = listOf(bartok.uuid, mimosa.uuid),
        entities = memory.entities, tags = memory.tags, importance = 7, valence = -2, evidenceEvents = listOf(1_240), evidenceTools = listOf(88),
    )

    val canonDraft = CanonDraft(
        kind = CanonKind.PROMISE, statement = canon.statement, quote = canon.quote, players = listOf(mimosa.uuid), importance = 6,
        dueAt = canon.dueAt, evidenceEvents = listOf(1_253),
    )

    val interactionDraft = InteractionDraft(
        player = bartok.uuid, kind = InteractionKind.INSULT, valence = -2, summary = interaction.summary, quote = interaction.quote,
        toolCalls = listOf(88), evidenceEvents = listOf(1_234),
    )

    val traitDraft = TraitDraft(
        player = bartok.uuid, trait = TraitAxis.BOLDNESS.id, side = 1, intensity = 4, confidence = 0.85,
        evidence = listOf(memory.ref, interaction.ref), note = trait.note,
    )

    val chronicleDraft = ChronicleDraft(
        level = ChronicleLevel.SESSION, from = chronicle.from, to = chronicle.to, title = chronicle.title, summary = chronicle.summary,
        covers = chronicle.covers,
    )

    val noteDraft = NoteDraft(kind = NoteKind.COHERENCE, text = note.text, players = listOf(bartok.uuid), refs = note.refs)

    /** Every text above that a model would write (quotes left out): they must pass `VoiceGuard`. */
    val texts: List<String> = listOf(
        memory.title, memory.content, canon.statement, gag.statement, interaction.summary, trait.note!!, skill.rationale, relation.note,
        doc.content, chronicle.title, chronicle.summary, note.text,
    )
}
