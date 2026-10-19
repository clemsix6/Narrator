package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.testing.MemorySamples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * Sheets for the renderer's tests (M3-07): [reference], Bartok after three weeks of play, every section
 * filled as the Dreamer, the Chronicler and the code would fill it (no reflex model: his signals are missing);
 * [empty], a player just seen, with nothing written or computed; [maximal], every list and text at its cap.
 * [NOW] is the time they are rendered at.
 */
internal object SheetSamples {
    val NOW: Instant = Instant.parse("2026-10-03T20:00:00Z")
    val bartok = MemorySamples.bartok
    val mimosa = MemorySamples.mimosa
    val clemsix6 = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000c1e56"), "clemsix6")

    private fun at(text: String): Instant = Instant.parse(text)

    private fun m(id: Long) = Ref(RefKind.MEMORY, id)

    private fun i(id: Long) = Ref(RefKind.INTERACTION, id)

    private fun c(id: Long) = Ref(RefKind.CANON, id)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val statsAt = at("2026-10-03T19:50:00Z")

    private fun stat(name: String, value: Double?, detail: String?) = name to SheetStat(name, value, detail?.let(::json), statsAt)

    fun doc(player: UUID, section: SheetSection, content: String, writtenAt: Instant, version: Int = 1) =
        section to MemoryDoc(DocKey.Sheet(player, section).key, version, content, emptyList(), writtenAt, Source.DREAMER)

    fun trait(
        trait: String, side: Int, intensity: Int, confidence: Double, setPlayed: Long, evidence: List<Ref>,
        note: String? = null, label: String? = null, trend: Int = 0, since: Instant = at("2026-09-20T21:00:00Z"),
    ) = Trait(bartok.uuid, trait, side, label, intensity, confidence, evidence, since, trend, note, at("2026-10-02T04:10:00Z"), setPlayed, false)

    fun interaction(
        id: Long, ts: String, kind: InteractionKind, valence: Int, summary: String, quote: String? = null,
        canon: Ref? = null, memory: Ref? = null, player: PlayerRef = bartok,
    ) = Interaction(
        i(id), at(ts), player, kind, valence, kind.counts, kind.isDisrespect(valence), summary, quote, 0, canon, memory,
        emptyList(), Source.DIRECTOR, false,
    )

    fun canon(
        id: Long, kind: CanonKind, statement: String, quote: String? = null, players: List<PlayerRef> = listOf(bartok),
        due: Instant? = null, stakes: String? = null, uses: Int = 0, lastUsed: Instant? = null, freshness: GagState? = null,
        status: CanonStatus = CanonStatus.ACTIVE,
    ) = CanonEntry(
        c(id), kind, at("2026-09-28T21:00:00Z"), statement, quote, players, stakes, 6, status, due, null, null, emptyList(), null, null,
        uses, lastUsed, freshness, Source.DIRECTOR, false,
    )

    fun memory(id: Long, from: String, importance: Int, title: String, content: String, players: List<PlayerRef> = listOf(bartok)) = MemoryEntry(
        m(id), MemoryKind.EPISODE, at(from), at(from), at(from), title, content, players, emptyList(), emptyList(), importance, -1, null,
        100.0, 0, 0, null, Source.CHRONICLER, false, null,
    )

    // ── The reference: Bartok ────────────────────────────────────────────────────────────────────────

    private val insult = interaction(
        512, "2026-10-03T19:40:00Z", InteractionKind.INSULT, -2, "a traité le Narrateur de « nul » juste après sa mort contre le Warden",
        quote = "narrateur t'es nul",
    )
    private val provocation = interaction(
        470, "2026-09-30T21:15:00Z", InteractionKind.PROVOCATION, -2, "a défié le Narrateur de le foudroyer devant tout le monde",
        quote = "vas-y foudroie-moi si t'es un dieu",
    )
    private val pacotille = interaction(
        402, "2026-09-25T22:05:00Z", InteractionKind.INSULT, -3, "a traité le Narrateur de dieu de pacotille", quote = "dieu de pacotille",
    )
    private val rescue = interaction(
        301, "2026-09-20T21:30:00Z", InteractionKind.HELP, 3, "a sauvé Mimosa de la lave en la tirant hors du lac", memory = m(77),
    )

    val reference = PlayerSheet(
        player = bartok,
        formerNames = listOf("Bartok_"),
        isOp = false,
        firstSeen = at("2026-09-12T18:03:00Z"),
        lastSeen = at("2026-10-03T19:02:00Z"),
        sessions = 23,
        playedSeconds = 220_320,
        online = true,
        nickname = canon(
            12, CanonKind.NICKNAME, "Bartok est surnommé « le Plongeur » depuis sa chute dans le lac de lave.", quote = "Salut à toi, le Plongeur !",
        ),
        title = null,
        docs = mapOf(
            doc(
                bartok.uuid, SheetSection.RECAP,
                "Explorateur tête baissée qui écume les chambres d'épreuve avec clemsix6. Provoque le Narrateur dès qu'il perd, puis revient " +
                    "lui demander de l'aide. Équipé en fer avec une épée en diamant ; a vu le Nether, pas encore l'End. Meurt souvent, et de façon " +
                    "spectaculaire.",
                at("2026-10-02T04:10:00Z"), 3,
            ),
            doc(
                bartok.uuid, SheetSection.SINCE_LAST,
                "A trouvé 4 diamants et perdu trois fois contre le Warden dans la même cité antique. A promis de revenir se venger, puis a " +
                    "insulté le Narrateur après sa dernière mort.",
                at("2026-10-01T22:30:00Z"),
            ),
            doc(
                bartok.uuid, SheetSection.PLAYSTYLE,
                "Joue en éclaireur : file droit vers les structures, pille, repart. Construit peu, toujours en pierre brute. Mine en profondeur " +
                    "par à-coups, surtout pour s'équiper.",
                at("2026-10-02T04:10:00Z"),
            ),
            doc(bartok.uuid, SheetSection.ATTITUDE, "provocateur quand il perd, quémandeur quand il a besoin ; respecte les menaces tenues", at("2026-10-02T04:10:00Z")),
            doc(bartok.uuid, SheetSection.LIKES, "le butin rare, les combats de boss ; déteste les téléportations et attendre la nuit", at("2026-10-02T04:10:00Z")),
            doc(bartok.uuid, SheetSection.FEARS, "le Warden, la noyade", at("2026-10-02T04:10:00Z")),
            doc(
                bartok.uuid, SheetSection.HOOKS,
                "sa boucle mort-vengeance est un gag récurrent, à doser ; son duo avec clemsix6 en fait une cible collective",
                at("2026-10-02T04:10:00Z"),
            ),
        ),
        traits = listOf(
            trait(
                "boldness", 1, 4, 0.85, 219_000, listOf(m(120), i(512), m(130), i(498), m(77), i(301), m(140)),
                note = "plonge dans la lave pour des débris antiques, fonce sur le Warden sans armure", trend = 1,
            ),
            trait("deference", 1, 3, 0.6, 200_000, listOf(i(512), i(402), i(470)), note = "insulte le Narrateur après chaque mort"),
            trait("temper", 1, 2, 0.45, 210_000, listOf(m(130)), since = at("2026-10-02T04:10:00Z")),
            trait("free:obsede_par_le_butin", 0, 3, 0.7, 215_000, listOf(m(77), m(140)), label = "obsédé par le butin"),
            // Set at the start and never confirmed: 0.22 × 2^(−61.2 h / 200 h) ≈ 0.18, below 0.2: not shown.
            trait("patience", -1, 2, 0.22, 0, listOf(i(330)), note = "quitte les chambres d'épreuve avant la fin"),
        ),
        skills = listOf(
            SkillScore(bartok.uuid, Skill.PVE, 6, "12 boss et mini-boss tués, mais 24 morts au combat", listOf(m(130)), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.PVP, 4, "3 victoires, 5 défaites, presque toutes contre Mimosa", emptyList(), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.BUILDING, 2, "812 blocs posés en 61 h, surtout de la pierre", emptyList(), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.MINING, 8, "18 400 blocs, 41 diamants, minerai moyen à y=-38", emptyList(), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.EXPLORATION, 8, "34 biomes, quatre sortes de structures, le Nether dès le troisième jour", emptyList(), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.SURVIVAL, 3, "87 morts, dont 40 absurdes", emptyList(), at("2026-10-02T04:10:00Z")),
            SkillScore(bartok.uuid, Skill.LUCK, 2, "indice de chance 23/100, aucun trésor pêché", emptyList(), at("2026-10-02T04:10:00Z")),
        ),
        relations = listOf(
            Relation(bartok.uuid, clemsix6, RelationKind.DUO, "explorent ensemble les chambres d'épreuve et partagent le butin", listOf(m(120)), at("2026-10-02T04:10:00Z")),
            Relation(bartok.uuid, mimosa, RelationKind.RIVAL, "se disputent chaque coffre des chambres d'épreuve", listOf(m(130), i(498)), at("2026-10-02T04:10:00Z")),
        ),
        stats = mapOf(
            stat("playtime", 220_320.0, """{"last7d_s":22500,"sessions":23,"usual_hours":[20,21,22]}"""),
            stat(
                "deaths", 87.0,
                """{"top":[["fall",31],["mob_attack",24],["lava",9]],
                   "last":{"ts":"2026-10-03T19:38:00Z","cause":"mob_attack","killer":"warden","dim":"overworld","x":-1520,"y":-51,"z":3180},
                   "absurd":40}""",
            ),
            stat("kills", 1_204.0, """{"top":[["zombie",412],["skeleton",305],["creeper",121]],"bosses":["elder_guardian"]}"""),
            stat("pvp", 3.0, """{"losses":5,"last":{"ts":"2026-09-28T21:10:00Z","opponent":"Mimosa","won":false}}"""),
            stat("mining", 18_400.0, """{"ores":{"diamond":41,"ancient_debris":3,"emerald":2,"gold":30,"iron":210},"avg_ore_y":-38}"""),
            stat("building", 812.0, """{"top":[["cobblestone",420],["oak_planks",180],["torch",95]]}"""),
            stat("travel", 84_310.0, """{"walk":52100,"sprint":20400,"boat":6200,"swim":3100,"crouch":2510}"""),
            stat(
                "exploration", 34.0,
                """{"structures":["ancient_city","fortress","trial_chambers","village_plains"],"rare_biomes":["cherry_grove","deep_dark"],
                   "nether_at":"2026-09-14T20:12:00Z","end_at":null}""",
            ),
            stat("gear", 4.0, """{"armor":"iron_chestplate","weapon":"diamond_sword","pickaxe":"iron_pickaxe"}"""),
            stat("advancements", 22.0, """{"goals":3,"challenges":1,"last":["Diamonds!","We Need to Go Deeper","Monster Hunter"]}"""),
            stat("economy", 14.0, """{"emeralds_spent":60}"""),
            stat(
                "places", null,
                """{"base":{"dim":"overworld","x":-1232,"y":72,"z":3312,"n":640},"bed":{"dim":"overworld","x":-1236,"y":73,"z":3305,"n":18},
                   "mine":{"dim":"overworld","x":-1392,"y":-40,"z":3104,"n":96}}""",
            ),
            stat("narrator", 143.0, """{"prayers":12,"punishments":7,"blessings":2,"gifts":1,"challenges_won":2,"challenges_lost":2}"""),
            stat("luck", 23.0, """{"rare_per_1k_mined":2.39,"treasure_fish":0.0,"challenges_ratio":0.5,"absurd_deaths_per_h":0.654}"""),
            stat("style", null, """{"explorer":45,"fighter":30,"builder":3,"miner":20,"farmer":0,"social":2}"""),
            stat("signals", 0.0, null),
            stat(
                SheetRenderer.PAIRS, 2.0,
                """{"pairs":[
                   {"uuid":"${clemsix6.uuid}","name":"clemsix6","minutes":1240.0,"minutes_7d":310.0,"pvp_won":0,"pvp_lost":0,"griefs_done":0,"griefs_suffered":0},
                   {"uuid":"${mimosa.uuid}","name":"Mimosa","minutes":420.0,"minutes_7d":95.0,"pvp_won":3,"pvp_lost":5,"griefs_done":2,"griefs_suffered":1}]}""",
            ),
        ),
        favor = -31.4,
        favorTrend = -8.2,
        respect = 35,
        disrespect = listOf(
            insult, provocation, pacotille,
            interaction(350, "2026-09-22T20:40:00Z", InteractionKind.MANIPULATION, -2, "a juré au Narrateur avoir prié la veille pour obtenir un bienfait"),
            interaction(330, "2026-09-21T19:20:00Z", InteractionKind.PROVOCATION, -1, "a ricané au premier avertissement du Narrateur"),
        ),
        lastAttentionAgoPlayedSeconds = 1_500,
        recent = listOf(
            interaction(505, "2026-10-03T19:41:00Z", InteractionKind.PUNISHMENT, 0, "a reçu lenteur II pendant 60 s pour l'insulte (t88)"),
            insult,
            interaction(498, "2026-10-02T21:02:00Z", InteractionKind.CHALLENGE_RESULT, 2, "a gagné la course au Nether contre Mimosa", canon = c(50)),
            interaction(
                480, "2026-10-01T20:30:00Z", InteractionKind.PRAYER, 1, "a prié pour retrouver son équipement perdu dans la lave",
                quote = "rends-moi mon stuff stp",
            ),
            provocation,
            pacotille,
        ),
        marked = listOf(pacotille, rescue),
        openCanon = listOf(
            canon(
                63, CanonKind.PROMISE, "Le Narrateur a promis un coffre de trésor à Bartok et Mimosa s'ils finissent le pont du ravin ensemble.",
                quote = "Finissez ce pont ensemble et je vous couvre d'or.", players = listOf(bartok, mimosa), due = at("2026-10-04T21:00:00Z"),
            ),
            canon(
                61, CanonKind.THREAT, "Le Narrateur a menacé Bartok d'une pluie de slimes s'il insulte encore le ciel.",
                quote = "Encore une insulte et il pleuvra des slimes.",
            ),
            canon(
                27, CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok comme un plongeon olympique.",
                uses = 5, lastUsed = at("2026-10-01T21:44:00Z"), freshness = GagState.WORN,
            ),
        ),
        memories = listOf(
            memory(
                77, "2026-09-20T21:30:00Z", 8, "Le sauvetage de Mimosa",
                "Bartok a plongé dans un lac de lave pour en tirer Mimosa, puis a perdu tout son équipement en ressortant.", listOf(bartok, mimosa),
            ),
            memory(
                120, "2026-10-01T21:40:00Z", 7, "Mort dans la lave du Nether",
                "Bartok est mort dans un lac de lave du Nether en creusant vers des débris antiques, devant Mimosa.", listOf(bartok, mimosa),
            ),
            memory(
                130, "2026-10-02T22:10:00Z", 6, "Trois morts contre le Warden",
                "Bartok est mort trois fois de suite contre le Warden dans la même cité antique, sans armure, en jurant de revenir.",
            ),
        ),
    )

    // ── A player just seen ───────────────────────────────────────────────────────────────────────────

    val kelvin = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000ce17"), "Kelvin")

    val empty = PlayerSheet(
        player = kelvin, formerNames = emptyList(), isOp = false, firstSeen = at("2026-10-03T19:45:00Z"), lastSeen = at("2026-10-03T19:45:00Z"),
        sessions = 1, playedSeconds = 900, online = true, nickname = null, title = null, docs = emptyMap(), traits = emptyList(),
        skills = emptyList(), relations = emptyList(), stats = emptyMap(), favor = null, favorTrend = null, respect = null,
        disrespect = emptyList(), lastAttentionAgoPlayedSeconds = null, recent = emptyList(), marked = emptyList(), openCanon = emptyList(),
        memories = emptyList(),
    )

    // ── Every list and text at its cap ───────────────────────────────────────────────────────────────

    /** A French text of exactly [length] characters, with a quote in the middle and a `<` to escape. */
    fun filler(length: Int, seed: String = "Bartok"): String {
        val words = "$seed a dit « je reviens » puis <a filé> vers la cité antique sans armure ni torche, comme d'habitude, "
        return words.repeat(length / words.length + 1).take(length)
    }

    private val longName = "<x>Wide_Name_16c".take(16)

    val maximal: PlayerSheet = run {
        val player = PlayerRef(bartok.uuid, longName)
        val others = (1..7).map { PlayerRef(UUID(0, 1_000L + it), "<o>Other_Name_${it}x".take(16)) }
        val axes = TraitAxis.entries.take(8)
        val evidence = (1..10L).map { if (it % 2 == 0L) m(it) else i(it) }
        val interactions = (1..20).map {
            interaction(
                900L + it, "2026-10-0${1 + it % 3}T1${it % 10}:00:00Z", InteractionKind.INSULT, -3, filler(MemoryLimits.INTERACTION_SUMMARY, "S$it"),
                quote = filler(MemoryLimits.INTERACTION_QUOTE, "Q$it"), canon = c(it.toLong()), memory = m(it.toLong()), player = player,
            )
        }
        reference.copy(
            player = player,
            formerNames = (1..5).map { "Former_<$it>_name".take(16) },
            nickname = canon(12, CanonKind.NICKNAME, filler(MemoryLimits.CANON_STATEMENT), quote = filler(MemoryLimits.CANON_QUOTE), players = listOf(player)),
            title = canon(13, CanonKind.TITLE, filler(MemoryLimits.CANON_STATEMENT), quote = filler(MemoryLimits.CANON_QUOTE), players = listOf(player)),
            docs = SheetSection.entries.associate { doc(player.uuid, it, filler(it.maxChars, it.id), NOW) },
            traits = axes.map { trait(it.id, 1, 5, 1.0, 220_000, evidence, note = filler(MemoryLimits.TRAIT_NOTE), trend = 1) } +
                (1..3).map { trait("free:libre_$it", 0, 5, 1.0, 220_000, evidence, note = filler(MemoryLimits.TRAIT_NOTE), label = filler(MemoryLimits.TRAIT_LABEL)) },
            skills = Skill.entries.map { SkillScore(player.uuid, it, 10, filler(MemoryLimits.SKILL_RATIONALE), evidence, NOW) },
            relations = others.map { Relation(player.uuid, it, RelationKind.TORMENTOR, filler(MemoryLimits.RELATION_NOTE), evidence, NOW) },
            disrespect = interactions,
            recent = interactions,
            marked = interactions,
            openCanon = (1..12).map {
                canon(
                    100L + it, CanonKind.CHALLENGE, filler(MemoryLimits.CANON_STATEMENT), quote = filler(MemoryLimits.CANON_QUOTE),
                    players = listOf(player) + others, due = NOW.plusSeconds(3_600L * it), stakes = filler(MemoryLimits.CANON_STAKES),
                )
            },
            memories = (1..8).map {
                memory(200L + it, "2026-09-2${it}T20:00:00Z", 10, filler(MemoryLimits.MEMORY_TITLE), filler(MemoryLimits.MEMORY_CONTENT), listOf(player) + others)
            },
        )
    }
}
