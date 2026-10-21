package io.github.clemsix6.narrator2.storyteller

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.SheetStat
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.storyteller.InspireKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The deck of `inspire` (M6 §5.3, §5.4): the twists, the levers, the hooks, the draw and its text. */
class DeckTest {
    private val samples = DeckSamples

    // ── The twists ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The bundled deck: about sixty English twists, every section filled, short, said once, and free of what the style
     * watch fights (« céleste », « guichet », « Ministère », « enquête », « Mesdames et messieurs », and their English)
     * and of anything dark.
     */
    @Test
    fun `the bundled twists are about sixty, in every section, short, and neither tics nor dark`() {
        val twists = Deck.twists
        assertTrue(twists.size in 55..70, "${twists.size} twists")
        assertEquals(setOf(null, LeverFamily.CHALLENGE, LeverFamily.PUNISHMENT, LeverFamily.FAVOR), twists.map { it.family }.toSet())
        assertTrue(twists.all { it.text.length <= Deck.TWIST_MAX && it.text.isNotBlank() })
        assertEquals(twists.size, twists.map { it.text.lowercase() }.toSet().size)
        val tics = Regex(
            "c[ée]lest|guichet|counter|ministr|enqu[êe]te|investigat|inquir|mesdames|ladies|gentlemen|bureau|\\bforms?\\b|stamp|paperwork",
            RegexOption.IGNORE_CASE,
        )
        val dark = Regex(
            "\\b(death|dead|die|dies|dying|kill|kills|killed|blood|despair|grave|corpse|forever|doom|hell|suicide|torture|weep|cry)\\b",
            RegexOption.IGNORE_CASE,
        )
        twists.forEach { twist ->
            assertNull(tics.find(twist.text), "a tic in « ${twist.text} »")
            assertNull(dark.find(twist.text), "something dark in « ${twist.text} »")
            assertFalse(twist.text.any { it == '"' || it == '<' || it == '>' }, "« ${twist.text} » would need escaping")
        }
    }

    @Test
    fun `a twist file is read strictly, line by line`() {
        val ok = "[any]\na\n[challenge]\nb\n[punishment]\nc\n[favor]\nd\n"
        assertEquals(listOf("a", "b", "c", "d"), Deck.parseTwists(ok, "t").map { it.text })
        fun refusal(text: String) = assertThrows<IllegalArgumentException> { Deck.parseTwists(text, "t") }.message.orEmpty()
        assertContains(refusal("[other]\na\n$ok"), "t:1: unknown section [other]")
        assertContains(refusal("a\n$ok"), "t:1: twist outside a section")
        assertContains(refusal("$ok\n${"x".repeat(Deck.TWIST_MAX + 1)}"), "90 characters at most")
        assertContains(refusal("$ok\nA"), "« A » appears twice")
        assertContains(refusal("[any]\na\n[challenge]\nb\n[favor]\nd\n"), "no twist in [punishment]")
    }

    // ── The levers ──────────────────────────────────────────────────────────────────────────────────

    /** The real catalogue: goals, a duel, the punishments, the favors, a law, a plan, the ready modules, the raw tools. */
    @Test
    fun `the levers come from the catalogue, and what is down is said`() {
        val forge = FakeForge().apply { arsenal.add(samples.tag); arsenal.add(samples.chickens); arsenal.add(samples.tag.copy(name = "statues", status = ModuleStatus.PENDING)) }
        val all = Deck.levers(TestServices(forge = forge))
        val keys = all.levers.map { it.key }
        assertTrue(keys.containsAll(listOf("challenge:obtain", "challenge:judged", "duel", "punish:tiny", "punish:web", "favor:joker", "law", "plan", "item", "book", "prophecy")))
        assertEquals(listOf("module:orbiting_chickens", "module:tag"), keys.filter { it.startsWith("module:") }, "ready modules only")
        val tag = all.levers.single { it.key == "module:tag" }
        assertEquals("module tag (players 2-8; minutes 2-15 =5)", tag.label)
        assertEquals(setOf(InspireKind.ANY, InspireKind.CHALLENGE), tag.kinds, "a game for several players is a challenge")
        assertEquals(setOf(InspireKind.ANY, InspireKind.TWIST), all.levers.single { it.key == "module:orbiting_chickens" }.kinds)
        assertEquals(emptyList(), all.missing)

        val chaos0 = Deck.levers(TestServices(guards = FakeGuards(chaos = 0)))
        assertTrue(chaos0.levers.none { it.key.startsWith("punish:") })
        assertEquals(listOf("punishments (chaos 0)"), chaos0.missing.map { it.text })

        val noMechanics = Deck.levers(TestServices(mechanics = FakeMechanics().apply { available = false; reason = "engine down" }))
        assertEquals(listOf("item", "book", "prophecy"), noMechanics.levers.map { it.key })
        assertContains(noMechanics.missing.single().text, "the mechanics are unavailable: engine down")

        val forgeOff = Deck.levers(TestServices(forge = forge.apply { available = false; reason = "no compiler" }))
        assertTrue(forgeOff.levers.none { it.key.startsWith("module:") })
        assertEquals(listOf("modules (the Forge is unavailable: no compiler)"), forgeOff.missing.map { it.text })
    }

    /** A twist draw takes the mechanics in progress, which no other draw takes. */
    @Test
    fun `a twist draw twists what runs`() {
        val mechanics = FakeMechanics()
        mechanics.challenges.views += ChallengeView(
            Ref(RefKind.CANON, 50), spec = null, duel = null, status = ChallengeStatus.RUNNING, endsAt = 0, participants = emptyList(), bets = 0,
            line = "C-50 obtain « Œuf de tortue » → all · Mimosa 0/1 · ends in 12m",
        )
        val levers = Deck.levers(TestServices(mechanics = mechanics)).levers
        val running = levers.single { it.key == "running:C-50" }
        assertEquals("in progress: C-50 obtain « Œuf de tortue » → all · Mimosa 0/1 · ends in 12m", running.label)
        assertEquals(setOf(InspireKind.TWIST), running.kinds)

        val table = samples.table(levers = levers, kind = InspireKind.TWIST)
        val draws = (1..30).map { Deck.draw(table, Random(it)) }
        assertTrue(draws.all { draw -> draw.seeds.all { InspireKind.TWIST in it.lever.kinds } })
        assertTrue(draws.any { draw -> draw.seeds.any { it.lever.key == "running:C-50" } }, "what runs weighs double")
        val any = samples.table(levers = levers)
        assertTrue((1..30).flatMap { Deck.draw(any, Random(it)).seeds }.none { it.lever.key == "running:C-50" })
    }

    // ── The hooks ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a sheet gives its memories, sure traits, progression, relations, and the attention it lacks`() {
        val sheet = MemorySamples.sheet
        val hooks = Deck.hooks(sheet, playerPlaySeconds = 219_600, now = samples.now)
        assertEquals(
            listOf(
                "M-120 « Mort dans la lave du Nether » (Bartok, 2w ago)",
                "Bartok reckless 4/5",
                "Bartok: 87 deaths",
                "Bartok rival of Mimosa « se disputent chaque coffre des chambres d'épreuve »",
            ),
            hooks.map { it.text },
        )
        assertEquals(listOf("M-120", "trait:${sheet.player.uuid}:boldness", "stat:${sheet.player.uuid}:deaths", "relation:${sheet.player.uuid}:${MemorySamples.mimosa.uuid}"), hooks.map { it.key })
        assertEquals(Ref(RefKind.MEMORY, 120), hooks.first().ref)

        val faded = Deck.hooks(sheet, playerPlaySeconds = 219_000 + 400 * 3_600L, now = samples.now)
        assertTrue(faded.none { it.key.startsWith("trait:") }, "a trait faded below 0.75 is not sure")
        val forgotten = Deck.hooks(samples.mimosaSheet, playerPlaySeconds = 0, now = samples.now)
        assertEquals(
            listOf(
                "M-130 « Troisième mort dans le même lac de lave » (Mimosa, 2h ago)",
                "C-31 place « la Canopée »",
                "Mimosa generous 4/5",
                "Mimosa: never been to the Nether",
                "Mimosa: no armor yet",
                "Mimosa: none of your attention for 2h00m of play",
            ),
            forgotten.map { it.text },
        )
        assertEquals(2.0, forgotten.last().boost, "a forgotten player weighs double")
    }

    @Test
    fun `the world's places and fresh gags are hooks of everyone, or of the players they concern`() {
        val canon = listOf(samples.canopy, samples.gag, samples.gag.copy(ref = Ref(RefKind.CANON, 28), freshness = GagState.WORN), MemorySamples.canon)
        val hooks = Deck.worldHooks(canon, listOf(MemorySamples.bartok))
        assertEquals(listOf("C-31", "C-27"), hooks.map { it.key }, "a worn gag and a promise make no hook")
        assertEquals(listOf(MemorySamples.bartok, MemorySamples.bartok), hooks.map { it.player })
        assertEquals("C-27 running gag « Le Narrateur commente chaque chute de Bartok comme un plongeon olympique. »", hooks[1].text)
        val long = samples.gag.copy(statement = "Le Narrateur commente " + "chaque chute ".repeat(10))
        val cut = Deck.worldHooks(listOf(long), listOf(MemorySamples.bartok)).single().text.removePrefix("C-27 running gag « ").removeSuffix(" »")
        assertTrue(cut.length == 80 && cut.endsWith("…"), cut)
        assertEquals(emptyList(), Deck.worldHooks(listOf(samples.gag), listOf(MemorySamples.mimosa)).map { it.key }, "Bartok's gag is not Mimosa's")
    }

    // ── The draw ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a draw deals the best ideas of the programme first, then seeds, three in all`() {
        val table = samples.table()
        val draw = Deck.draw(table, Random(7))
        assertEquals(listOf("P-41", "P-44"), draw.ideas.map { it.ref.toString() }, "the season, a dealt idea and a past one are left out")
        assertEquals(1, draw.seeds.size)
        val seed = draw.seeds.single()
        assertTrue(seed.hook != null && seed.twist != null && seed.register in listOf(ComicRegister.FAIRY_TALE, ComicRegister.FOOD_CRITIC, ComicRegister.GAME_SHOW))
        val lines = draw.render().lines()
        assertEquals("ok: 3 ideas for Bartok, Mimosa (least used lately: fairy_tale, food_critic, game_show)", lines[0])
        assertTrue(lines[1].startsWith("- P-41 challenge · Bartok, Mimosa · « La course au pic »: Course au sommet du pic enneigé"), lines[1])
        assertTrue(lines[1].endsWith("(fits: les deux en ligne, Bartok s'ennuie) · novelty 4 fun 4 fair 5 · game_show"), lines[1])
        assertTrue(lines[3].startsWith("- seed: "), lines[3])
        assertEquals(draw.keys.take(2), listOf("P-41", "P-44"))
    }

    @Test
    fun `the same table and seed draw the same, another seed otherwise`() {
        val table = samples.table(ideas = emptyList())
        assertEquals(Deck.draw(table, Random(42)).render(), Deck.draw(table, Random(42)).render())
        val texts = (1..10).map { Deck.draw(table, Random(it)).render() }.toSet()
        assertTrue(texts.size > 5, "${texts.size} different draws out of 10")
        val draw = Deck.draw(table, Random(1))
        assertEquals(3, draw.seeds.size)
        assertEquals(3, draw.seeds.map { it.lever.key }.toSet().size, "no lever twice")
        assertEquals(3, draw.seeds.mapNotNull { it.hook?.key }.toSet().size, "no hook twice")
        assertEquals(3, draw.seeds.mapNotNull { it.register }.toSet().size, "the three least used registers")
    }

    /** Thirteen punishments, eight challenges, five gifts, four others: each family is drawn about as often as another. */
    @Test
    fun `each family of levers is drawn about as often as another`() {
        val table = samples.table(ideas = emptyList())
        val seeds = (1..200).flatMap { Deck.draw(table, Random(it)).seeds }
        val shares = seeds.groupingBy { it.lever.family }.eachCount().mapValues { it.value.toDouble() / seeds.size }
        assertEquals(LeverFamily.entries.toSet(), shares.keys)
        assertTrue(shares.values.all { it in 0.15..0.35 }, "$shares")
        val attention = seeds.filter { it.hook?.key?.startsWith("attention:") == true }
        assertTrue(attention.isNotEmpty() && attention.none { it.lever.family == LeverFamily.PUNISHMENT }, "being forgotten is no fault")
    }

    /** M6 §5.4: a lever used on anyone rests 3 h of world play, on the seed's player 6 h. */
    @Test
    fun `a lever used lately rests, on anyone for 3h of play and on the seed's player for 6h`() {
        val levers = Deck.levers(TestServices()).levers.filter { it.key in setOf("punish:tiny", "favor:joker", "challenge:obtain", "item") }
        val history = samples.history(
            Intervention("punish:tiny", samples.now.minus(Duration.ofMinutes(40)), listOf("Mimosa")),
            Intervention("favor:joker", samples.now.minus(Duration.ofHours(4)), listOf("Bartok")),
            Intervention("challenge:obtain", samples.now.minus(Duration.ofHours(7)), listOf("Bartok", "Mimosa")),
        )
        val table = samples.table(levers = levers, ideas = emptyList(), history = history)
        val seeds = (1..40).flatMap { Deck.draw(table, Random(it)).seeds }
        assertTrue(seeds.none { it.lever.key == "punish:tiny" }, "used 40m ago")
        assertTrue(seeds.none { it.lever.key == "favor:joker" && it.hook?.player?.uuid == MemorySamples.bartok.uuid }, "used on Bartok 4h ago")
        assertTrue(seeds.any { it.lever.key == "favor:joker" && it.hook?.player?.uuid == MemorySamples.mimosa.uuid }, "free for Mimosa")
        assertTrue(seeds.any { it.lever.key == "challenge:obtain" }, "7h ago: free again")

        val bartokHooks = samples.hooks().filter { it.player?.uuid != MemorySamples.mimosa.uuid }
        val forBartok = samples.table(levers = levers, hooks = bartokHooks, ideas = emptyList(), history = history, players = listOf(MemorySamples.bartok), named = true)
        assertTrue((1..20).flatMap { Deck.draw(forBartok, Random(it)).seeds }.none { it.lever.key == "favor:joker" }, "a draw for Bartok alone")

        val atRest = Deck.draw(table, Random(1)).render().lines().single { it.startsWith("At rest") }
        assertEquals("At rest (you did them lately): punish tiny (Mimosa, 40m ago) · favor joker (Bartok, 4h00m ago) · divine_bureaucracy · « céleste »", atRest)
    }

    /** M6 §5.4: a hook or a twist dealt within 3 h, an idea within 2 h, an idea built on a resting arc are left out. */
    @Test
    fun `what was dealt lately is left out, and so is an idea built on an arc whose theme rests`() {
        val hooks = samples.hooks()
        val twist = Deck.twists.first()
        val dealt = Dealt(samples.now.minus(Duration.ofMinutes(20)), hooks.drop(1).map { it.key }.toSet() + twist.key)
        val history = History(emptyList(), listOf(dealt), samples.timeline(), restingArcs = setOf(Ref(RefKind.CANON, 60)))
        val table = samples.table(history = history)
        val draws = (1..30).map { Deck.draw(table, Random(it)) }
        assertTrue(draws.flatMap { it.seeds }.all { it.hook == null || it.hook.key == hooks.first().key }, "the only hook not dealt")
        assertTrue(draws.all { draw -> draw.seeds.count { it.hook != null } == 1 }, "once per draw, the others hang on nothing")
        assertTrue(draws.flatMap { it.seeds }.none { it.twist == twist })
        assertTrue(draws.none { draw -> draw.ideas.any { it.ref == Ref(RefKind.IDEA, 41) } }, "P-41 builds on C-60, whose theme rests")
    }

    @Test
    fun `the registers that hold half of the latest lines rest, and the crutch words are said`() {
        val last = List(4) { ComicRegister.GAME_SHOW } + List(4) { ComicRegister.COURTROOM }
        val table = samples.table(ideas = emptyList(), lastRegisters = last, leastUsed = listOf(ComicRegister.GAME_SHOW, ComicRegister.FAIRY_TALE, ComicRegister.HOROSCOPE))
        val draw = Deck.draw(table, Random(3))
        assertTrue(draw.seeds.none { it.register == ComicRegister.GAME_SHOW || it.register == ComicRegister.COURTROOM })
        assertEquals("At rest (you did them lately): game_show · courtroom · « céleste »", draw.render().lines().last())

        val unmeasured = samples.table(ideas = emptyList(), leastUsed = emptyList(), lastRegisters = emptyList())
        val free = Deck.draw(unmeasured, Random(3))
        assertTrue(free.seeds.all { it.register in ComicRegister.VOICE }, "without measures, any register of the voice")
        assertFalse(free.render().lines().first().contains("least used"))
        val unlabeled = samples.table(ideas = emptyList(), leastUsed = ComicRegister.VOICE.take(3), lastRegisters = emptyList())
        assertFalse(Deck.draw(unlabeled, Random(3)).render().lines().first().contains("least used"), "no label yet: every register at 0")
        assertTrue((1..20).flatMap { Deck.draw(unlabeled, Random(it)).seeds }.any { it.register !in ComicRegister.VOICE.take(3) })
    }

    @Test
    fun `a weight grows with the time of play since it was served, and the last resort still draws`() {
        val hour = 3_600L
        assertEquals(listOf(4, 0, 0, 1, 2, 3, 4), listOf(null, 0L, 5 * hour, 6 * hour, 12 * hour, 24 * hour, 48 * hour).map { Deck.weight(it) })
        val dealt = Dealt(samples.now.minus(Duration.ofHours(4)), samples.table().levers.map { it.key }.toSet() + samples.hooks().map { it.key })
        val tired = samples.table(ideas = emptyList(), history = History(emptyList(), listOf(dealt), samples.timeline()))
        assertEquals(3, Deck.draw(tired, Random(5)).seeds.size, "everything dealt 4h ago: drawn anyway")
    }

    @Test
    fun `a draw of one kind deals that kind only`() {
        val table = samples.table(kind = InspireKind.FAVOR)
        val draw = Deck.draw(table, Random(11))
        assertEquals(listOf("P-44"), draw.ideas.map { it.ref.toString() })
        assertTrue(draw.seeds.all { it.lever.family == LeverFamily.FAVOR }, "${draw.seeds.map { it.lever.key }}")
        assertTrue(draw.render().startsWith("ok: 3 favor ideas for Bartok, Mimosa"))
        val nothing = samples.table(kind = InspireKind.PUNISHMENT, levers = emptyList(), ideas = emptyList(), missing = listOf(Missing("punishments (chaos 0)")))
        assertEquals("ok: no punishment idea for Bartok, Mimosa now (least used lately: fairy_tale, food_critic, game_show)", Deck.draw(nothing, Random(1)).render().lines().first())
        assertEquals("Not now: punishments (chaos 0)", Deck.draw(nothing, Random(1)).render().lines().last())
    }

    @Test
    fun `without hooks, seeds hang on nothing, and a duel needs two players`() {
        val table = samples.table(hooks = emptyList(), ideas = emptyList(), players = listOf(MemorySamples.bartok), named = true, online = listOf(MemorySamples.bartok))
        val seeds = (1..40).flatMap { Deck.draw(table, Random(it)).seeds }
        assertTrue(seeds.all { it.hook == null })
        assertTrue(seeds.none { it.lever.key == "duel" }, "Bartok alone online")
        assertTrue(Deck.draw(table, Random(1)).seeds.first().line().matches(Regex("- seed: [^×]+ × \"[^\"]+\" · [a-z_]+")))
    }

    /** M6 §5.2: 1 500 characters at most, the pitches cut first. */
    @Test
    fun `the text holds within 1500 characters`() {
        val long = "Une très longue idée qui n'en finit pas, ".repeat(13).take(500)
        val ideas = samples.ideas().map { it.copy(pitch = long, fit = long.take(200), title = long.take(60)) }
        val hooks = samples.hooks().map { it.copy(text = it.text + " " + "x".repeat(200)) }
        val crowded = samples.history(
            *(1..12).map { Intervention("effect:effect_$it", samples.now.minus(Duration.ofMinutes(it.toLong())), listOf("Bartok", "Mimosa")) }.toTypedArray(),
        )
        val table = samples.table(ideas = ideas, hooks = hooks, history = crowded, crutches = listOf("céleste", "guichet", "décret"))
        val text = Deck.draw(table, Random(9)).render()
        assertTrue(text.length <= Deck.TEXT_MAX, "${text.length} characters:\n$text")
        assertContains(text, "(+")
        val full = Deck.draw(samples.table(ideas = ideas), Random(9)).render()
        assertTrue(full.length <= Deck.TEXT_MAX && full.contains("- P-"), full)
    }

    // ── The card ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a card shows the idea whole, its refs with their lines, its module and its brief`() {
        val brief = "Les joueurs voient " + "un brief précis, ".repeat(80)
        val idea = samples.idea(47, IdeaKind.FORGE, "Les statues", "Un jeu de un, deux, trois, soleil.", "deux joueurs ou plus").copy(
            refs = listOf(Ref(RefKind.MEMORY, 120), Ref(RefKind.CANON, 99)), brief = brief.take(1_500), dealt = 2,
            lastDealtAt = samples.now.minus(Duration.ofMinutes(40)), module = "tag",
        )
        val card = Deck.card(idea, listOf(Ref(RefKind.MEMORY, 120) to MemorySamples.memory, Ref(RefKind.CANON, 99) to null), samples.tag, samples.now, worldPlaySeconds = 10_000)
        val lines = card.lines()
        assertEquals("ok: P-47 forge · open · for everyone · dealt 2× (last 40m ago) · expires in 1d1h of play", lines[0])
        assertEquals("« Les statues » · novelty 4 fun 4 fair 5", lines[1])
        assertEquals("- M-120 « Mort dans la lave du Nether » (Bartok, Mimosa, 2w ago)", lines[5])
        assertEquals("- C-99 (gone)", lines[6])
        assertEquals("Module: tag (players 2-8; minutes 2-15 =5)", lines[7])
        assertEquals("Brief: ${brief.take(1_500)}", lines[8], "the brief whole, ready for forge_module")
        assertTrue(card.length <= Deck.CARD_MAX)
    }
}

/**
 * A realistic evening for the deck (M6 §5): Bartok and Mimosa online, their sheets, the world's place and gag, the
 * storyteller's programme, the arsenal, a style watch tired of the divine bureaucracy. Shared with `InspireToolTest`.
 */
internal object DeckSamples {
    val now: Instant = Instant.parse("2026-10-21T20:30:00Z")

    val bartok: PlayerRef = MemorySamples.bartok
    val mimosa: PlayerRef = MemorySamples.mimosa

    /** Play every minute of the last 30 days: an age in play is an age on the clock. */
    fun timeline(): PlayTimeline = PlayTimeline(listOf(now.minus(Duration.ofDays(30)).toEpochMilli()..now.toEpochMilli()), now)

    fun history(vararg interventions: Intervention): History = History(interventions.toList(), emptyList(), timeline())

    val tag = ArsenalEntry(
        name = "tag", version = 0, status = ModuleStatus.READY, builtIn = true, summary = "Tag: the wolf passes on by touch.",
        params = listOf(
            ParamSpec("players", ParamType.PLAYERS, "Who plays.", required = true, fallback = null, min = 2.0, max = 8.0, choices = emptyList()),
            ParamSpec("minutes", ParamType.INTEGER, "How long it lasts.", required = false, fallback = "5", min = 2.0, max = 15.0, choices = emptyList()),
        ),
        scenarios = emptyList(), savedAt = Instant.EPOCH, by = "built-in", note = null, hash = "sha256:0", sdk = 1, classes = 0, bytes = 0, problem = null,
    )

    val chickens = tag.copy(
        name = "orbiting_chickens", summary = "Chickens circle a player.",
        params = listOf(
            ParamSpec("player", ParamType.PLAYER, "Who.", required = true, fallback = null, min = null, max = null, choices = emptyList()),
            ParamSpec("chickens", ParamType.INTEGER, "How many.", required = false, fallback = "6", min = 3.0, max = 8.0, choices = emptyList()),
        ),
    )

    val canopy = MemorySamples.canon.copy(
        ref = Ref(RefKind.CANON, 31), kind = CanonKind.PLACE, statement = "Le Narrateur a nommé « la Canopée » la cabane perchée de Bartok et Mimosa.",
        quote = "la Canopée", players = listOf(bartok, mimosa), dueAt = null, status = CanonStatus.ACTIVE,
    )

    val gag = MemorySamples.gag.copy(freshness = GagState.FRESH, status = CanonStatus.ACTIVE)

    val mimosaSheet = MemorySamples.sheet.copy(
        player = mimosa,
        nickname = null,
        traits = listOf(MemorySamples.trait.copy(player = mimosa.uuid, trait = "generosity", side = 1, intensity = 4, confidence = 0.9, setPlayedSeconds = 0)),
        relations = emptyList(),
        stats = mapOf(
            "exploration" to SheetStat("exploration", 9.0, buildJsonObject { put("nether_at", JsonPrimitive(null as String?)) }, now),
            "gear" to SheetStat("gear", 1.0, buildJsonObject { put("weapon", "stone_sword") }, now),
        ),
        lastAttentionAgoPlayedSeconds = 7_200,
        openCanon = listOf(canopy),
        memories = listOf(
            MemorySamples.memory.copy(
                ref = Ref(RefKind.MEMORY, 130), title = "Troisième mort dans le même lac de lave", players = listOf(mimosa), importance = 6,
                occurredFrom = now.minus(Duration.ofHours(2)), occurredTo = now.minus(Duration.ofHours(2)),
            ),
        ),
    )

    val bartokSheet = MemorySamples.sheet.copy(
        stats = MemorySamples.sheet.stats + mapOf(
            "exploration" to SheetStat("exploration", 14.0, buildJsonObject { put("nether_at", "2026-10-02T21:00:00Z") }, now),
            "gear" to SheetStat("gear", 4.0, buildJsonObject { put("armor", "iron_chestplate"); put("weapon", "diamond_sword") }, now),
            "mining" to SheetStat("mining", 5_400.0, buildJsonObject { put("ores", buildJsonObject { put("diamond", 41) }) }, now),
            "kills" to SheetStat("kills", 310.0, buildJsonObject { put("bosses", JsonArray(listOf(JsonPrimitive("elder_guardian")))) }, now),
        ),
        lastAttentionAgoPlayedSeconds = 600,
    )

    /** Bartok's hooks, then Mimosa's, then the world's gag. */
    fun hooks(): List<Hook> = Deck.hooks(bartokSheet, 219_600, now) + Deck.hooks(mimosaSheet, 0, now).filter { it.key != "C-31" } +
        Deck.worldHooks(listOf(canopy, gag), listOf(bartok, mimosa))

    fun idea(id: Long, kind: IdeaKind, title: String, pitch: String, fit: String, players: List<PlayerRef> = emptyList()) = Idea(
        ref = Ref(RefKind.IDEA, id), createdAt = now.minus(Duration.ofHours(20)), kind = kind, title = title, pitch = pitch, fit = fit,
        players = players, register = null, novelty = 4, funScore = 4, fairness = 5, refs = emptyList(), module = null, brief = null,
        status = IdeaStatus.OPEN, dealt = 0, lastDealtAt = null, expiresPlaySeconds = 100_000, closedAt = null, closeReason = null,
        source = Source.STORYTELLER,
    )

    /** The programme: a race and a monument that fit, a punishment dealt 30m ago, a past one, the season. */
    fun ideas(): List<Idea> = listOf(
        idea(
            41, IdeaKind.CHALLENGE, "La course au pic",
            "Course au sommet du pic enneigé au nord de leur base ; enjeu : le premier en haut gagne un joker, le second porte une citrouille.",
            "les deux en ligne, Bartok s'ennuie", listOf(bartok, mimosa),
        ).copy(register = ComicRegister.GAME_SHOW, refs = listOf(Ref(RefKind.CANON, 60))),
        idea(
            44, IdeaKind.FAVOR, "Monument à la nageuse",
            "Un monument de laine orange se dresse au bord du lac de lave où Mimosa s'est noyée trois fois, avec une plaque solennelle.",
            "Mimosa en ligne, après une mort", listOf(mimosa),
        ).copy(novelty = 3, funScore = 4, fairness = 4, register = ComicRegister.FAIRY_TALE),
        idea(45, IdeaKind.PUNISHMENT, "Le plongeur plombé", "Bartok coule à pic.", "Bartok en ligne", listOf(bartok))
            .copy(dealt = 1, lastDealtAt = now.minus(Duration.ofMinutes(30)), novelty = 5, funScore = 5),
        idea(46, IdeaKind.DUEL, "Le duel du pont", "Un duel sur le pont.", "les deux en ligne", listOf(bartok, mimosa)).copy(expiresPlaySeconds = 10),
        idea(40, IdeaKind.SEASON, "Les Grandes Manœuvres", "Une saison de manœuvres.", "toujours").copy(novelty = 5, funScore = 5),
    )

    fun table(
        players: List<PlayerRef> = listOf(bartok, mimosa),
        named: Boolean = false,
        kind: InspireKind = InspireKind.ANY,
        levers: List<Lever> = Deck.levers(TestServices(forge = FakeForge().apply { arsenal.add(tag); arsenal.add(chickens) })).levers,
        hooks: List<Hook> = hooks(),
        ideas: List<Idea> = ideas(),
        online: List<PlayerRef> = listOf(bartok, mimosa),
        history: History = History(emptyList(), emptyList(), timeline()),
        leastUsed: List<ComicRegister> = listOf(ComicRegister.FAIRY_TALE, ComicRegister.FOOD_CRITIC, ComicRegister.GAME_SHOW),
        lastRegisters: List<ComicRegister> = List(5) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.SPORTS_COMMENTARY } + ComicRegister.COURTROOM,
        crutches: List<String> = listOf("céleste"),
        missing: List<Missing> = emptyList(),
    ) = Table(now, players, named, kind, levers, hooks, ideas, worldPlaySeconds = 1_000, online, history, leastUsed, lastRegisters, crutches, missing)

}
