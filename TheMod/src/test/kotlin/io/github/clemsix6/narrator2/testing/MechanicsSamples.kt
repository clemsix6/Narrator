package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.director.ReminderState
import io.github.clemsix6.narrator2.director.SavedReminder
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ArenaSpec
import io.github.clemsix6.narrator2.mechanics.Bet
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.ForfeitSpec
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.PlaceSpec
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.SavedPosition
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import java.time.Instant
import java.util.UUID

/**
 * The mechanics' samples for tests (design M4): three players (Bartok and Mimosa of [MemorySamples], and
 * Josiane), the stakes, a challenge of each goal, a duel, the seven rules of §8.8 (the JSON the model writes,
 * and decoded), the engine's state of an evening (§1.2) and the Director's mechanic signals (§4.4). French
 * texts are in the third person and pass `VoiceGuard`; quotes are the Narrator's own words.
 */
object MechanicsSamples {
    val bartok: PlayerRef = MemorySamples.bartok
    val mimosa: PlayerRef = MemorySamples.mimosa
    val josiane = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000705a0"), "Josiane")

    /** 2026-10-19 20:00 in Paris, the start of the evening of M4 §1.2. */
    val evening: Long = Instant.parse("2026-10-19T18:00:00Z").toEpochMilli()

    /** The Director's turn 42 of chapter 13. */
    val director = Caller(Role.DIRECTOR, "director-c13", turnId = 42, taskId = null)

    /** An act of the engine: firing [id] of [ref], with its own caller (M4 §4.3). */
    fun firing(id: Long, ref: Ref?): Firing = Firing(id, ref, Caller(Role.DIRECTOR, "mechanics:${ref ?: "engine"}", null, null, firingId = id))

    fun canon(id: Long): Ref = Ref(RefKind.CANON, id)

    // ── Stakes (M4 §5.3) ──

    val goldenApple = RewardSpec(RewardKind.ITEM, item = "golden_apple", count = 1)
    val namedPickaxe = RewardSpec(RewardKind.ITEM, item = "diamond_pickaxe", name = "La Pioche du Pardon")
    val speed = RewardSpec(RewardKind.EFFECT, effect = "speed", seconds = 300, level = 2)
    val grace = RewardSpec(RewardKind.GRACE, minutes = 15)
    val joker = RewardSpec(RewardKind.JOKER)
    val tinyForfeit = ForfeitSpec(PunishmentId.TINY, severity = 2)
    val slowHandsForfeit = ForfeitSpec(PunishmentId.SLOW_HANDS, severity = 1)

    // ── Challenges, one of each goal (M4 §5.1, §5.2) ──

    val turtleEgg = ChallengeSpec(
        title = "Œuf de tortue",
        statement = "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue en quinze minutes.",
        quote = "Le premier qui me rapporte un œuf de tortue aura ma bénédiction.",
        goal = GoalKind.OBTAIN,
        item = "turtle_egg",
        players = listOf("Bartok", "Mimosa"),
        minutes = 15,
        reward = goldenApple,
        penalty = tinyForfeit,
        bets = true,
        importance = 6,
    )
    val nether = ChallengeSpec(
        title = "Porte des Enfers",
        statement = "Le Narrateur a défié les joueurs d'entrer dans le Nether en vingt minutes.",
        goal = GoalKind.REACH,
        place = PlaceSpec(dimension = "the_nether"),
        minutes = 20,
        winners = 0,
        penalty = slowHandsForfeit,
    )
    val spiders = ChallengeSpec(
        title = "Dix araignées",
        statement = "Le Narrateur a défié Mimosa de tuer dix araignées avant l'aube.",
        goal = GoalKind.KILL,
        entity = "spider",
        count = 10,
        players = listOf("Mimosa"),
        minutes = 30,
        reward = speed,
    )
    val naked = ChallengeSpec(
        title = "Survivre nu",
        statement = "Le Narrateur a défié les joueurs de survivre dix minutes sans armure.",
        goal = GoalKind.SURVIVE,
        noArmor = true,
        minutes = 10,
        reward = grace,
    )
    val riddle = ChallengeSpec(
        title = "Énigme",
        statement = "Le Narrateur a posé une énigme dont la réponse est une enclume.",
        quote = "Je tombe sans jamais me blesser et je répare ce que vous cassez. Qui suis-je ?",
        goal = GoalKind.ANSWER,
        answers = listOf("une enclume", "enclume"),
        minutes = 5,
        reward = joker,
    )
    val statue = ChallengeSpec(
        title = "La plus belle statue",
        statement = "Le Narrateur a demandé aux joueurs de bâtir la plus belle statue en quinze minutes.",
        goal = GoalKind.JUDGED,
        minutes = 15,
        reward = namedPickaxe,
    )

    /** One challenge of each goal, in the order of [GoalKind]. */
    val challenges: List<ChallengeSpec> = listOf(turtleEgg, nether, spiders, naked, riddle, statue)

    val duel = DuelSpec(
        players = listOf("Bartok", "Mimosa"),
        title = "Duel au soleil",
        statement = "Le Narrateur a proposé un duel à Bartok et Mimosa pour trancher leur querelle.",
        quote = "Réglez ça entre vous, et que le moins ridicule gagne.",
        minutes = 5,
        arena = ArenaSpec(112, 70, -40),
        reward = goldenApple,
        bets = true,
    )

    // ── Rules: the seven of M4 §8.8, as the model writes them ──

    /** The arguments of `create_rule` for the five rules of the specification, the law C-25 and a plan, by name. */
    val ruleExamples: Map<String, String> = linkedMapOf(
        "skeleton_arrows" to """
            {"kind":"law","statement":"Le Narrateur a décrété que les flèches des squelettes explosent quand elles touchent un joueur.",
             "trigger":"projectile_hit","when":{"entity":"skeleton"},
             "effects":[{"tool":"explosion","at":"position","args":{"power":2}}],"minutes":120}
        """.trimIndent(),
        "silverfish" to """
            {"kind":"law","statement":"Le Narrateur a décrété que frapper un poisson d'argent téléporte au hasard.",
             "trigger":"damage_dealt","when":{"entity":"silverfish"},
             "effects":[{"tool":"teleport","at":"actor","args":{"random_radius":300}}],"minutes":180}
        """.trimIndent(),
        "flying_cows" to """
            {"kind":"law","statement":"Le Narrateur a décrété que les vaches frappées s'envolent puis explosent en feu d'artifice.",
             "trigger":"damage_dealt","when":{"entity":"cow"},
             "effects":[{"tool":"mob_effect","at":"subject","args":{"effect":"levitation","seconds":3,"level":2}},
                        {"tool":"explosion","at":"subject","args":{"power":2,"hurts":false},"delay_seconds":3},
                        {"tool":"fireworks","at":"subject","args":{"count":2,"shape":"burst"},"delay_seconds":3}],"minutes":60}
        """.trimIndent(),
        "caffeinated_creepers" to """
            {"kind":"law","statement":"Le Narrateur a mis les creepers sous caféine.",
             "trigger":"mob_spawn","when":{"entity":"creeper"},
             "effects":[{"tool":"mob_attribute","at":"subject","args":{"attribute":"movement_speed","multiply":3}}],"minutes":60}
        """.trimIndent(),
        "explosive_diamonds" to """
            {"kind":"law","statement":"Le Narrateur a décrété que les minerais de diamant explosent quand on les mine.",
             "trigger":"block_break","when":{"block":"#diamond_ores"},
             "effects":[{"tool":"explosion","at":"position","args":{"power":3}}],"minutes":120}
        """.trimIndent(),
        "grief_law" to """
            {"kind":"law","statement":"Le Narrateur a décrété que quiconque casse la construction d'un autre joueur est rapetissé.",
             "quote":"Qui casse chez les autres rapetisse chez soi.",
             "trigger":"block_break","when":{"grief":true},
             "effects":[{"tool":"punish","at":"actor","args":{"punishment":"tiny","severity":2,"reason":"a cassé la construction d'un autre joueur"}},
                        {"tool":"actionbar","at":"actor","args":{"text":"La loi est la loi, {actor}."}}],"minutes":10080}
        """.trimIndent(),
        "welcome_plan" to """
            {"kind":"plan","statement":"Le Narrateur a prévu un comité d'accueil pour Bartok à sa prochaine entrée dans le Nether.",
             "trigger":"dimension_change","when":{"players":["Bartok"],"dimension":"the_nether"},
             "effects":[{"tool":"spawn","at":"actor","args":{"entity":"zombified_piglin","count":3,"name":"Comité d'accueil","lifetime_minutes":2}},
                        {"tool":"notify","at":"actor","args":{"note":"le comité d'accueil de Bartok est arrivé"}}],
             "once":true,"minutes":1440}
        """.trimIndent(),
    )

    /** [ruleExamples] decoded as `create_rule` decodes them ([ToolArgs]). */
    val rules: Map<String, RuleSpec> = ruleExamples.mapValues { (name, json) ->
        when (val decoded = ToolArgs.decode(RuleSpec.serializer(), json)) {
            is ToolArgs.Decoded.Ok -> decoded.args
            is ToolArgs.Decoded.Invalid -> error("the sample rule $name does not decode: ${decoded.problem}")
        }
    }

    // ── The engine's state at 20:05 of the evening (M4 §1.2) ──

    private const val MINUTE = 60_000L

    /** C-50, the turtle egg, started at 20:00 for 15 minutes; Josiane bet 12 iron ingots on Mimosa (escrow 812). */
    val runningChallenge = ChallengeState(
        id = 50,
        spec = turtleEgg,
        status = ChallengeStatus.RUNNING,
        createdAt = evening,
        startedAt = evening,
        endsAt = evening + 15 * MINUTE,
        participants = listOf(
            Participant(bartok.uuid.toString(), "Bartok"),
            Participant(mimosa.uuid.toString(), "Mimosa"),
        ),
        bets = listOf(Bet(josiane.uuid.toString(), "Josiane", mimosa.uuid.toString(), stake = 812, what = "12x iron_ingot")),
        betsCloseAt = evening + 5 * MINUTE,
        turnId = 42,
        conversationId = "director-c13",
    )

    /** C-52, the duel, waiting for its players since 20:04; Bartok accepted. */
    val invitedDuel = ChallengeState(
        id = 52,
        duel = duel,
        status = ChallengeStatus.INVITED,
        createdAt = evening + 4 * MINUTE,
        startedAt = null,
        endsAt = evening + 6 * MINUTE,
        participants = listOf(
            Participant(bartok.uuid.toString(), "Bartok", accepted = true),
            Participant(mimosa.uuid.toString(), "Mimosa"),
        ),
        turnId = 44,
        conversationId = "director-c13",
    )

    /** C-60, the law against grief, for a week from 20:15 of the evening before. */
    val griefLaw = RuleState(
        id = 60,
        spec = rules.getValue("grief_law"),
        createdAt = evening - 1_425 * MINUTE,
        endsAt = evening - 1_425 * MINUTE + 10_080 * MINUTE,
        turnId = 30,
        conversationId = "director-c12",
        fired = 2,
        lastFiredAt = evening - 90 * MINUTE,
    )

    /** C-61, the plan of Bartok's welcome in the Nether, aimed at his UUID. */
    val welcomePlan = RuleState(
        id = 61,
        spec = rules.getValue("welcome_plan"),
        createdAt = evening + 2 * MINUTE,
        endsAt = evening + 2 * MINUTE + 1_440 * MINUTE,
        turnId = 43,
        conversationId = "director-c13",
        playerIds = listOf(bartok.uuid.toString()),
    )

    val state = MechanicsState(
        challenges = listOf(runningChallenge, invitedDuel),
        rules = listOf(griefLaw, welcomePlan),
        lastChallengeAt = evening,
        returns = listOf(SavedPosition(mimosa.uuid.toString(), "minecraft:overworld", 100.5, 64.0, -30.5, 90f, 0f)),
        settlements = listOf(Settlement(owner = josiane.uuid.toString(), stake = 790, to = mimosa.uuid.toString())),
    )

    /** The Director's reminders saved with the world: R-3 due at 20:30, set at 20:00 by turn 42. */
    val reminders = ReminderState(nextId = 4, reminders = listOf(SavedReminder(3, "revenir sur la statue de Mimosa", evening, evening + 30 * MINUTE, 42)))

    // ── The Director's mechanic signals (M4 §4.4) ──

    val challengeWon = DirectorSignal.Mechanic(
        MechanicSignal.CHALLENGE,
        "C-50 won by Mimosa in 9m12s (turtle_egg 1/1) · reward: Mimosa got 1x golden_apple · penalty: Bartok tiny (2) until 20:13 · " +
            "bets: Josiane got back 12x iron_ingot (nobody else bet)",
        evening + 9 * MINUTE,
        priority = true,
    )
    val judge = DirectorSignal.Mechanic(
        MechanicSignal.JUDGE,
        "C-53 « La plus belle statue » ended: judge it now with end_challenge C-53 (winners); participants: Bartok, Mimosa, Josiane; you have 15m",
        evening + 20 * MINUTE,
        priority = true,
    )
    val duelStarted = DirectorSignal.Mechanic(
        MechanicSignal.DUEL, "C-52 started: Bartok and Mimosa accepted; they fight until 21:27 (in 5m)", evening + 82 * MINUTE, priority = false,
    )
    val ruleFired = DirectorSignal.Mechanic(
        MechanicSignal.RULE, "C-61 fired: Bartok entered the_nether (-12 70 40); your note: « comité d'accueil prêt »", evening + 65 * MINUTE, priority = false,
    )
    val offering = DirectorSignal.Mechanic(
        MechanicSignal.OFFERING,
        "O-812 Mimosa offers 10x diamond: take it by granting her a favor (favor, offering O-812) within 9m58s, or let it go back",
        evening + 65 * MINUTE,
        priority = true,
    )
    val jokerPlayed = DirectorSignal.Mechanic(
        MechanicSignal.JOKER, "Bartok played a joker: slowness II and scale ×0.4 lifted, 2 zombies dismissed", evening + 70 * MINUTE, priority = false,
    )
}
