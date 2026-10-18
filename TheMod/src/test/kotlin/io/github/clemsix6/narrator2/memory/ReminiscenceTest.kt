package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.config.MemoryConfig
import io.github.clemsix6.narrator2.director.CueKind
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.Urgency
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeMemoryStore
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ReminiscenceTest {
    // ── The table of M3 §10.5: what each event looks for ───────────────────────────────────────────

    @Test
    fun `a death looks for its cause, its killer and the death words`() {
        val lava = cue(death(bartok, "minecraft:lava"))
        assertEquals("DEATH", lava.kind)
        assertEquals(listOf("death", "lava"), lava.tags)
        assertEquals(listOf("minecraft:lava"), lava.entities)
        assertEquals(listOf(bartok.uuid), lava.players)
        assertTrue("mort" in lava.text && "lave" in lava.text && "minecraft:lava" in lava.text, lava.text)
        assertEquals("DEATH Bartok", lava.label)

        assertEquals(listOf("death", "fall"), cue(death(bartok, "minecraft:fall")).tags)
        assertEquals(listOf("death", "fall"), cue(death(bartok, "minecraft:out_of_world")).tags)
        val drowned = cue(death(bartok, "minecraft:drown"))
        assertEquals(listOf("death", "drowning"), drowned.tags)
        assertTrue("noyé" in drowned.text, drowned.text)

        val creeper = cue(death(bartok, "minecraft:explosion", EntityRef("minecraft:creeper", UUID.randomUUID(), null)))
        assertEquals(listOf("death", "explosion", "mob"), creeper.tags)
        assertEquals(listOf("minecraft:explosion", "minecraft:creeper"), creeper.entities)

        val skeleton = cue(death(bartok, "minecraft:arrow", EntityRef("minecraft:skeleton", UUID.randomUUID(), null)))
        assertEquals(listOf("death", "mob"), skeleton.tags)
        assertTrue("squelette" in skeleton.text && "flèche" in skeleton.text, skeleton.text)

        val byMimosa = cue(death(bartok, "minecraft:player_attack", EntityRef("minecraft:player", mimosa.uuid, mimosa.name, mimosa)))
        assertEquals(listOf("death", "pvp"), byMimosa.tags)
        assertEquals(listOf(bartok.uuid, mimosa.uuid), byMimosa.players)
        assertEquals(listOf("minecraft:player_attack"), byMimosa.entities)
    }

    @Test
    fun `a kill between players looks for duels with both players`() {
        val pvp = cue(kill(mimosa, bartok))
        assertEquals("PVP", pvp.kind)
        assertEquals(listOf("pvp", "duel"), pvp.tags)
        assertEquals(listOf(mimosa.uuid, bartok.uuid), pvp.players)
        assertEquals("PVP Mimosa", pvp.label)
        assertNull(Reminiscence.cueOf(signal(EntityKilled(bartok, home, EntityRef("minecraft:zombie", null, null), null, false, false))))
    }

    @Test
    fun `places and milestones look for their id when they are notable`() {
        val village = cue(StructureEntered(bartok, home, "minecraft:village_plains", firstVisit = true))
        assertEquals("STRUCTURE", village.kind)
        assertEquals(listOf("minecraft:village_plains"), village.entities)
        assertTrue("village" in village.text, village.text)
        assertTrue("épreuve" in cue(StructureEntered(bartok, home, "minecraft:trial_chambers", firstVisit = false)).text)
        assertNull(Reminiscence.cueOf(signal(StructureEntered(bartok, home, "minecraft:village_plains", firstVisit = false))))

        val cherry = cue(BiomeEntered(mimosa, home, "minecraft:cherry_grove", firstVisit = true))
        assertEquals("BIOME", cherry.kind)
        assertEquals(listOf("minecraft:cherry_grove"), cherry.entities)
        assertTrue("cerisiers" in cherry.text)
        assertNull(Reminiscence.cueOf(signal(BiomeEntered(mimosa, home, "minecraft:plains", firstVisit = true))))

        val nether = cue(DimensionChanged(bartok, Pos("minecraft:the_nether", 10, 70, 10), home, "nether_portal"))
        assertEquals("DIMENSION", nether.kind)
        assertEquals(listOf("nether"), nether.tags)
        assertEquals(listOf("minecraft:the_nether"), nether.entities)
        assertNull(Reminiscence.cueOf(signal(DimensionChanged(bartok, home, Pos("minecraft:the_nether", 10, 70, 10), "nether_portal"))))

        val dragon = cue(AdvancementEarned(kelvin, home, "minecraft:end/kill_dragon", "Free the End", "task"))
        assertEquals("ADVANCEMENT", dragon.kind)
        assertEquals(listOf("minecraft:end/kill_dragon"), dragon.entities)
        assertTrue("dragon" in dragon.text && "Free the End" in dragon.text, dragon.text)
        assertEquals(listOf("minecraft:adventure/kill_all_mobs"), cue(AdvancementEarned(kelvin, home, "minecraft:adventure/kill_all_mobs", "Monsters Hunted", "challenge")).entities)
        assertNull(Reminiscence.cueOf(signal(AdvancementEarned(kelvin, home, "minecraft:story/smelt_iron", "Acquire Hardware", "task"))))
    }

    @Test
    fun `a grief looks for griefs and builds of the breaker and the owner`() {
        val grief = cue(BlockBroken(bartok, home, "minecraft:oak_planks", null, false, true, mimosa.uuid), annotation = "broke 12 blocks placed by Mimosa within 10s: oak_planks 12")
        assertEquals("GRIEF", grief.kind)
        assertEquals(listOf("grief", "build"), grief.tags)
        assertEquals(listOf(bartok.uuid, mimosa.uuid), grief.players)
        assertNull(Reminiscence.cueOf(signal(BlockBroken(bartok, home, "minecraft:oak_planks", null, false, true, mimosa.uuid))), "mined, not a grief")
        assertNull(Reminiscence.cueOf(signal(BlockBroken(bartok, home, "minecraft:oak_planks", null, false, true, bartok.uuid), annotation = "x")))
    }

    @Test
    fun `addressed chat and prayers look for their words`() {
        val prayer = cue(chat(mimosa, ChatChannel.PRAY, "Ô Narrateur, fais pleuvoir sur mes champs"), Urgency.IMMEDIATE, "(to you)")
        assertEquals("PRAY", prayer.kind)
        assertEquals(listOf(mimosa.uuid), prayer.players)
        assertTrue("pleuvoir" in prayer.text && "champs" in prayer.text, prayer.text)
        assertTrue("Narrateur" !in prayer.text, "the name of the Narrator says nothing: ${prayer.text}")

        val addressed = cue(chat(bartok, ChatChannel.PUBLIC, "encore cette lave"), Urgency.IMMEDIATE, "(to you 0.91, hostile, provocation)")
        assertEquals("CHAT", addressed.kind)
        assertEquals("lave", addressed.text, "stop words and noise left out")
        assertEquals("CHAT", cue(chat(bartok, ChatChannel.PUBLIC, "encore cette lave"), Urgency.SOON, "(to you: heuristic)").kind, "throttled, still addressed")

        assertNull(Reminiscence.cueOf(signal(chat(bartok, ChatChannel.PUBLIC, "encore cette lave"), Urgency.NEXT, "(between players)")))
        assertNull(Reminiscence.cueOf(signal(chat(bartok, ChatChannel.WHISPER, "encore cette lave"), Urgency.IMMEDIATE)))
        assertNull(Reminiscence.cueOf(signal(chat(bartok, ChatChannel.PUBLIC, "Narrateur, salut ! tu es là ?"), Urgency.IMMEDIATE, "(to you)")))
    }

    @Test
    fun `a join and the other signals bring nothing back`() {
        assertNull(Reminiscence.cueOf(signal(PlayerJoined(bartok, home, 7, firstTime = false, isOp = false))))
        assertNull(Reminiscence.cueOf(DirectorSignal.MemoryDue(Ref(RefKind.CANON, 45), "C-45 promise", 0)))
        assertNull(Reminiscence.cueOf(DirectorSignal.MemoryCue(CueKind.RETURN, Ref(RefKind.MEMORY, 1), bartok, "x", 0)))
    }

    // ── Reminiscences on a real index ──────────────────────────────────────────────────────────────

    @Test
    fun `each event of the table brings back its memory`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans un lac de lave en creusant vers des diamants.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
            val duel = f.memory(2, "Duel au pont", "Mimosa a tué Bartok en duel sur le pont de bois, sous les yeux du village.", listOf(mimosa, bartok), tags = listOf("pvp", "duel"))
            val trial = f.memory(3, "La chambre d'épreuve", "Bartok a pillé sa première chambre d'épreuve et en est ressorti avec une clé.", tags = listOf("exploration"), entities = listOf("minecraft:trial_chambers"))
            val cherry = f.memory(4, "Les cerisiers", "Mimosa a planté un jardin de thé au milieu des cerisiers roses.", listOf(mimosa), tags = listOf("farming"), entities = listOf("minecraft:cherry_grove"))
            val nether = f.memory(5, "Premier pas dans le Nether", "Bartok est entré dans le Nether pour la première fois et a fui devant un ghast.", tags = listOf("nether", "first"), entities = listOf("minecraft:the_nether"))
            val dragon = f.memory(6, "Le dragon vaincu", "Kelvin a vaincu le dragon de l'End avec un arc et beaucoup de chance.", listOf(kelvin), tags = listOf("end", "boss"), entities = listOf("minecraft:ender_dragon"))
            val grief = f.memory(7, "Le toit saccagé", "Bartok a cassé le toit de la maison de Mimosa ; le Narrateur l'a puni.", listOf(bartok, mimosa), tags = listOf("grief", "build", "punishment"))
            val boots = f.memory(8, "Les bottes volées", "Bartok a volé les bottes de fer et a juré de les rendre.", tags = listOf("promise"))
            val rain = f.memory(9, "La prière pour la pluie", "Mimosa a prié le Narrateur pour qu'il fasse pleuvoir sur ses champs de blé.", listOf(mimosa), tags = listOf("prayer", "farming"))
            f.memory(10, "Le cochon Gérard", "Bartok a adopté un cochon nommé Gérard et lui a construit un enclos.")

            val table = listOf(
                signal(death(bartok, "minecraft:lava")) to lava,
                signal(kill(mimosa, bartok)) to duel,
                signal(StructureEntered(bartok, home, "minecraft:trial_chambers", firstVisit = true)) to trial,
                signal(BiomeEntered(mimosa, home, "minecraft:cherry_grove", firstVisit = true)) to cherry,
                signal(DimensionChanged(bartok, Pos("minecraft:the_nether", 10, 70, 10), home, "nether_portal")) to nether,
                signal(AdvancementEarned(kelvin, home, "minecraft:end/kill_dragon", "Free the End", "task")) to dragon,
                signal(BlockBroken(bartok, home, "minecraft:oak_planks", null, false, true, mimosa.uuid), annotation = "broke 12 blocks placed by Mimosa within 10s") to grief,
                signal(chat(bartok, ChatChannel.PUBLIC, "Narrateur, je vais rendre les bottes de fer, promis"), Urgency.IMMEDIATE, "(to you)") to boots,
                signal(chat(mimosa, ChatChannel.PRAY, "Ô grand Narrateur, fais pleuvoir sur mes champs de blé"), Urgency.IMMEDIATE, "(to you)") to rain,
            )
            table.forEach { (signal, expected) ->
                val recalled = f.reminiscence().forWake(listOf(signal), ChapterMemory("director-c1", 1), f.now)
                assertEquals(listOf(expected), recalled.map { it.ref }, Reminiscence.cueOf(signal)!!.label)
            }
            assertTrue(f.reminiscence().forWake(listOf(signal(PlayerJoined(bartok, home, 7, false, false))), ChapterMemory("director-c1", 1), f.now).isEmpty())
        }
    }

    @Test
    fun `a reminiscence is shown, recorded and decided`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans un lac de lave, <engine> brûlé vif.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
            val chapter = ChapterMemory("director-c1", 1)
            val death = signal(death(bartok, "minecraft:lava"))
            val recalled = f.reminiscence().forWake(listOf(death), chapter, f.now).single()

            assertEquals(lava, recalled.ref)
            assertEquals(death.envelope.id, recalled.eventId)
            assertTrue(recalled.line.startsWith("RECALLED for DEATH Bartok: M-1 · "), recalled.line)
            assertTrue("‹engine›" in recalled.line && "<" !in recalled.line, recalled.line)
            assertTrue(lava in chapter.shownRefs)
            assertEquals(emptyList<String>(), f.recalls(lava), "recorded once the request is accepted")
            f.accept(chapter)
            assertEquals(listOf("reminiscence"), f.recalls(lava))
            val decision = f.journal.sink.decisions.single { it.kind == DecisionRecord.Kind.MEMORY && it.decision == "recalled" }
            assertEquals("Bartok", decision.subject)
            assertEquals(death.envelope.id, decision.eventId)
            assertEquals("director-c1", decision.conversationId)
        }
    }

    @Test
    fun `nothing comes back below the thresholds`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.memory(10, "Le cochon Gérard", "Bartok a adopté un cochon nommé Gérard et lui a construit un enclos.", importance = 9)
            assertTrue(f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), ChapterMemory("director-c1", 1), f.now).isEmpty(), "relevance under 0.4")
        }
        Fixture().use { f ->
            val faded = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"), importance = 1, reinforcedPlay = WORLD - 200 * 3_600)
            assertTrue(f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), ChapterMemory("director-c1", 1), f.now).isEmpty(), "score under 0.35")
            f.db.writer.update("UPDATE memories SET reinforced_play_s = ? WHERE id = 1", WORLD)
            assertEquals(listOf(faded), f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), ChapterMemory("director-c1", 1), f.now).map { it.ref })
        }
    }

    @Test
    fun `a memory shown less than 180 minutes of world play ago is not attached`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"), shownPlay = WORLD - 179 * 60)
            assertTrue(f.wake(death(bartok, "minecraft:lava")).isEmpty())
            f.db.writer.update("UPDATE memories SET reinforced_play_s = ? WHERE id = 1", WORLD - 181 * 60)
            assertEquals(listOf(lava), f.wake(death(bartok, "minecraft:lava")).map { it.ref })

            // Its own recall starts the cooldown again, in world play time: wall time does not count.
            f.clock.advance(Duration.ofHours(10))
            f.playClock.world = WORLD + 179 * 60
            assertTrue(f.wake(death(bartok, "minecraft:lava")).isEmpty())
            f.playClock.world = WORLD + 181 * 60
            assertEquals(listOf(lava), f.wake(death(bartok, "minecraft:lava")).map { it.ref })

            f.config = MemoryConfig(recallCooldownPlayMinutes = 30)
            f.playClock.world = WORLD + 212 * 60
            assertEquals(listOf(lava), f.wake(death(bartok, "minecraft:lava")).map { it.ref }, "the setting sets the cooldown")
        }
    }

    @Test
    fun `a memory already shown in the chapter is not attached again`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
            val chapter = ChapterMemory("director-c1", 1).apply { shownRefs += lava }
            assertTrue(f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), chapter, f.now).isEmpty())
        }
    }

    @Test
    fun `a memory the Director saw through a tool in the chapter is not attached in it`() = runBlocking<Unit> {
        Fixture().use { f ->
            // Seen through `recall` long ago in play time: out of its cooldown, but still in the chapter's conversation.
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"), shownPlay = WORLD - 300 * 60)
            f.db.writer.update("INSERT INTO recalls VALUES (?, ?, 'recall_tool', 'director-c1', 42, NULL)", f.clock.instant(), lava.id)
            assertTrue(f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), ChapterMemory("director-c1", 1), f.now).isEmpty())
            assertEquals(listOf(lava), f.reminiscence().forWake(listOf(signal(death(bartok, "minecraft:lava"))), ChapterMemory("director-c2", 2), f.now).map { it.ref }, "another chapter")
        }
    }

    @Test
    fun `at most recallPerWake reminiscences per wake and one per event`() = runBlocking<Unit> {
        Fixture().use { f ->
            (1L..4L).forEach { f.lavaMemory(it) }
            val deaths = (1..4).map { signal(death(bartok, "minecraft:lava")) }
            val two = f.reminiscence().forWake(deaths, ChapterMemory("director-c1", 1), f.now)
            assertEquals(2, two.size)
            assertEquals(2, two.map { it.ref }.distinct().size)
            assertEquals(2, two.map { it.eventId }.distinct().size, "one per event")

            val one = signal(death(bartok, "minecraft:lava"))
            assertEquals(1, f.reminiscence(FakeMemoryStore()).forWake(listOf(one), ChapterMemory("director-c2", 2), f.now).size, "one event, one memory")

            f.config = MemoryConfig(recallPerWake = 1)
            assertEquals(1, f.reminiscence(FakeMemoryStore()).forWake(deaths, ChapterMemory("director-c3", 3), f.now).size)
            f.config = MemoryConfig(recallPerWake = 0)
            val none = FakeMemoryStore()
            assertTrue(f.reminiscence(none).forWake(deaths, ChapterMemory("director-c4", 4), f.now).isEmpty())
            assertTrue(none.recalls.isEmpty())
        }
    }

    @Test
    fun `at most four reminiscences per hour of world play`() = runBlocking<Unit> {
        Fixture().use { f ->
            (1L..10L).forEach { f.lavaMemory(it) }
            val reminiscence = f.reminiscence()
            fun deaths() = (1..2).map { signal(death(bartok, "minecraft:lava")) }
            assertEquals(2, reminiscence.forWake(deaths(), ChapterMemory("director-c1", 1), f.now).size)
            f.playClock.world = WORLD + 1_200
            assertEquals(2, reminiscence.forWake(deaths(), ChapterMemory("director-c2", 2), f.now).size)
            f.playClock.world = WORLD + 2_400
            assertTrue(reminiscence.forWake(deaths(), ChapterMemory("director-c3", 3), f.now).isEmpty())
            f.playClock.world = WORLD + 3_600
            assertEquals(2, reminiscence.forWake(deaths(), ChapterMemory("director-c4", 4), f.now).size, "the first two left the hour")
        }
    }

    @Test
    fun `the draw is controlled and deterministic, and favors the best memory`() = runBlocking<Unit> {
        Fixture().use { f ->
            val best = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave, brûlé vif.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"), importance = 9)
            val other = f.memory(2, "Encore la lave", "Bartok est mort dans la lave du Nether.", tags = listOf("death", "lava"), importance = 3)
            suspend fun draws(): List<Ref> = (1L..60L).map { id ->
                // A store that records nothing: each draw starts from the same state.
                f.reminiscence(FakeMemoryStore()).forWake(listOf(signal(death(bartok, "minecraft:lava"), id = id)), ChapterMemory("director-c1", 1), f.now).single().ref
            }
            val first = draws()
            assertEquals(first, draws(), "the event id seeds the draw")
            val others = first.count { it == other }
            assertTrue(others in 1..29,"the best wins often, not always: $others of 60 for the other one")
            assertEquals(60 - others, first.count { it == best })
        }
    }

    @Test
    fun `a request that was not accepted gets the same reminiscence again`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
            val reminiscence = f.reminiscence()
            val chapter = ChapterMemory("director-c1", 1)
            val death = signal(death(bartok, "minecraft:lava"))
            val mark = chapter.mark()
            val first = reminiscence.forWake(listOf(death), chapter, f.now)
            assertEquals(listOf(lava), first.map { it.ref })

            mark.restore() // the Director restores the chapter after a 529: what it showed is not recorded
            assertEquals(first, reminiscence.forWake(listOf(death), chapter, f.now), "the same memory")
            assertTrue(lava in chapter.shownRefs)
            f.accept(chapter)
            assertEquals(listOf("reminiscence"), f.recalls(lava), "recorded once")
            assertEquals(1, f.journal.sink.decisions.count { it.decision == "recalled" }, "decided once")
            assertTrue(reminiscence.forWake(listOf(death), chapter, f.now).isEmpty(), "shown in the chapter already")
        }
    }

    @Test
    fun `a cue reaching the Director counts as shown and is recorded once`() = runBlocking<Unit> {
        Fixture().use { f ->
            val lava = f.memory(1, "Mort dans la lave", "Bartok est mort dans la lave.", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))
            val reminiscence = f.reminiscence()
            val chapter = ChapterMemory("director-c1", 1)
            val cue = DirectorSignal.MemoryCue(CueKind.RETURN, lava, bartok, "Bartok is where M-1 happened", f.now)
            val mark = chapter.mark()
            assertTrue(reminiscence.forWake(listOf(cue, signal(death(bartok, "minecraft:lava"))), chapter, f.now).isEmpty())
            assertTrue(lava in chapter.shownRefs)
            mark.restore() // not accepted: the cue goes back to the inbox, and comes again
            reminiscence.forWake(listOf(cue), chapter, f.now)
            f.accept(chapter)
            assertEquals(listOf("reminiscence"), f.recalls(lava))
        }
    }

    // ── Place returns ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a player back where a memory happened gets a return cue`() = runBlocking<Unit> {
        Fixture().use { f ->
            val treehouse = f.memory(20, "La cabane", "Bartok est tombé de sa cabane <dans> les arbres.", importance = 7, place = home)
            val reminiscence = f.reminiscence()
            val cue = reminiscence.placeReturn(bartok, Pos(OVERWORLD, 110, 64, 105), listOf(bartok.uuid), f.now) ?: error("no return")
            assertEquals(CueKind.RETURN, cue.kind)
            assertEquals(treehouse, cue.ref)
            assertEquals(bartok, cue.player)
            assertEquals(f.now, cue.at)
            assertEquals("Bartok is where M-20 happened (3d ago, ★7): « Bartok est tombé de sa cabane ‹dans› les arbres. »", cue.line)
            assertTrue(f.journal.sink.decisions.any { it.decision == "cue" && it.subject == "Bartok" && it.reason!!.startsWith("RETURN M-20 at 11 blocks") })

            assertNull(reminiscence.placeReturn(bartok, home, listOf(bartok.uuid), f.now), "once per memory and player")
            f.playClock.world = WORLD + 6 * 3_600 - 1
            assertNull(reminiscence.placeReturn(bartok, home, listOf(bartok.uuid), f.now))
            f.playClock.world = WORLD + 6 * 3_600
            assertEquals(treehouse, reminiscence.placeReturn(bartok, home, listOf(bartok.uuid), f.now)?.ref, "after 6 hours of world play")
        }
    }

    @Test
    fun `a place return needs a near, old, important memory about someone present`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.memory(21, "Le lac", "Bartok a pêché dans le lac.", importance = 7, place = Pos(OVERWORLD, 1_000, 64, 1_000))
            f.memory(22, "La forge", "Bartok a forgé une épée.", importance = 7, place = Pos(OVERWORLD, 2_000, 64, 2_000), at = f.clock.instant().minus(Duration.ofHours(12)))
            f.memory(23, "Le puits", "Bartok est tombé dans le puits.", importance = 5, place = Pos(OVERWORLD, 3_000, 64, 3_000))
            f.memory(24, "Le jardin", "Mimosa a planté des tulipes.", listOf(mimosa), importance = 7, place = Pos(OVERWORLD, 4_000, 64, 4_000))
            f.memory(25, "La tour", "Bartok a bâti une tour.", importance = 7, place = Pos(OVERWORLD, 5_000, 64, 5_000), archived = true)
            f.memory(26, "Le fort", "Bartok a trouvé un fort.", importance = 7, place = Pos("minecraft:the_nether", 6_000, 64, 6_000))
            f.memory(27, "Le spawn", "Bartok est né ici.", importance = 7, place = Pos(OVERWORLD, -3, 64, -3))
            val online = listOf(bartok.uuid)
            fun at(x: Int, dim: String = OVERWORLD) = Pos(dim, x, 64, x)

            assertNull(f.reminiscence().placeReturn(bartok, Pos(OVERWORLD, 1_017, 64, 1_000), online, f.now), "17 blocks away")
            assertNull(f.reminiscence().placeReturn(bartok, at(1_000, "minecraft:the_nether"), online, f.now), "another dimension")
            assertNull(f.reminiscence().placeReturn(bartok, at(2_000), online, f.now), "12 hours old")
            assertNull(f.reminiscence().placeReturn(bartok, at(3_000), online, f.now), "importance 5")
            assertNull(f.reminiscence().placeReturn(bartok, at(4_000), online, f.now), "about Mimosa, offline")
            assertEquals(Ref(RefKind.MEMORY, 24), f.reminiscence().placeReturn(bartok, at(4_000), online + mimosa.uuid, f.now)?.ref, "about Mimosa, online")
            assertNull(f.reminiscence().placeReturn(bartok, at(5_000), online, f.now), "archived")
            assertEquals(Ref(RefKind.MEMORY, 21), f.reminiscence().placeReturn(bartok, Pos(OVERWORLD, 1_016, 64, 1_000), online, f.now)?.ref, "16 blocks")
            assertEquals(Ref(RefKind.MEMORY, 26), f.reminiscence().placeReturn(bartok, at(6_000, "minecraft:the_nether"), online, f.now)?.ref)
            assertEquals(Ref(RefKind.MEMORY, 27), f.reminiscence().placeReturn(bartok, at(6), online, f.now)?.ref, "across the cells of the grid")
            f.config = MemoryConfig(recallPerWake = 0)
            assertNull(f.reminiscence().placeReturn(bartok, at(1_000), online, f.now), "reminiscences off")
        }
    }

    @Test
    fun `the place return of an importance 9 memory comes first, even in its cooldown`() = runBlocking<Unit> {
        Fixture().use { f ->
            val shown = WORLD - 60 * 60
            f.memory(30, "Le pont", "Bartok a construit un pont.", importance = 8, place = Pos(OVERWORLD, 100, 64, 100), shownPlay = shown)
            val legend = f.memory(31, "Le Wither", "Bartok a vaincu le Wither ici.", importance = 9, place = Pos(OVERWORLD, 300, 64, 300), shownPlay = shown)
            val online = listOf(bartok.uuid)
            assertNull(f.reminiscence().placeReturn(bartok, Pos(OVERWORLD, 100, 64, 100), online, f.now), "shown an hour ago")
            assertEquals(legend, f.reminiscence().placeReturn(bartok, Pos(OVERWORLD, 300, 64, 300), online, f.now)?.ref, "importance 9: priority")
        }
        Fixture().use { f ->
            f.memory(32, "La mine", "Bartok a creusé une mine.", importance = 8, place = Pos(OVERWORLD, 100, 64, 100))
            val dragon = f.memory(33, "Le dragon", "Bartok a vaincu le dragon ici.", importance = 10, place = Pos(OVERWORLD, 105, 64, 100))
            val farm = f.memory(34, "La ferme", "Bartok a bâti une ferme.", importance = 7, place = Pos(OVERWORLD, 500, 64, 500))
            val wither = f.memory(35, "Le Wither", "Bartok a vaincu le Wither ici.", importance = 9, place = Pos(OVERWORLD, 900, 64, 900))
            val online = listOf(bartok.uuid)
            val reminiscence = f.reminiscence()
            assertEquals(dragon, reminiscence.placeReturn(bartok, Pos(OVERWORLD, 101, 64, 100), online, f.now)?.ref, "the most important first, though farther")
            assertNull(reminiscence.placeReturn(bartok, Pos(OVERWORLD, 500, 64, 500), online, f.now), "one return per player per hour of play")
            assertEquals(wither, reminiscence.placeReturn(bartok, Pos(OVERWORLD, 900, 64, 900), online, f.now)?.ref, "importance 9 passes the gap")
            f.playClock.world = WORLD + 3_600
            assertEquals(farm, reminiscence.placeReturn(bartok, Pos(OVERWORLD, 500, 64, 500), online, f.now)?.ref)
        }
    }

    // ── Anniversaries ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `anniversaries fall a week, a month or a year later`() {
        val today = LocalDate.parse("2026-10-18")
        fun label(day: String) = Reminiscence.span(LocalDate.parse(day), today)?.label
        assertEquals("one week", label("2026-10-11"))
        assertEquals("2 weeks", label("2026-10-04"))
        assertEquals("3 weeks", label("2026-09-27"))
        assertNull(label("2026-09-20"), "4 weeks: the month takes over")
        assertEquals("one month", label("2026-09-18"))
        assertEquals("3 months", label("2026-07-18"))
        assertEquals("11 months", label("2025-11-18"))
        assertEquals("one year", label("2025-10-18"))
        assertEquals("2 years", label("2024-10-18"))
        assertNull(label("2026-10-08"))
        assertNull(label("2026-10-18"))
        assertNull(label("2026-10-25"))
        assertEquals("one month", Reminiscence.span(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"))?.label)
        assertEquals("one month", Reminiscence.span(LocalDate.parse("2026-01-28"), LocalDate.parse("2026-02-28"))?.label)
        assertNull(Reminiscence.span(LocalDate.parse("2026-01-27"), LocalDate.parse("2026-02-28")))
        assertEquals("one year", Reminiscence.span(LocalDate.parse("2024-02-29"), LocalDate.parse("2025-02-28"))?.label)
    }

    @Test
    fun `one anniversary per day at most, the most important and the oldest`() = runBlocking<Unit> {
        Fixture().use { f ->
            fun day(date: String): Instant = Instant.parse("${date}T15:00:00Z")
            val year = f.memory(40, "Le dragon", "Bartok a vaincu l'Ender Dragon seul.", importance = 9, at = day("2025-10-18"))
            f.memory(41, "Le Wither", "Mimosa a vaincu le Wither.", listOf(mimosa), importance = 10, at = day("2026-09-18"))
            f.memory(42, "La tour", "Bartok a bâti une tour.", importance = 7, at = day("2026-10-11"))
            f.memory(43, "Le temple", "Bartok a pillé un temple.", importance = 10, at = day("2026-10-10"))
            f.memory(44, "La forteresse", "Bartok a trouvé une forteresse.", importance = 8, at = day("2026-10-12"))
            val everyone = f.memory(45, "La grande fête", "Le serveur a fêté son ouverture.", emptyList(), importance = 8, at = day("2026-09-19"))

            assertNull(f.reminiscence().anniversary(f.now, emptyList()), "nobody online: the day is not taken")
            val cue = f.reminiscence().anniversary(f.now, listOf(bartok)) ?: error("no anniversary")
            assertEquals(CueKind.ANNIVERSARY, cue.kind)
            assertEquals(year, cue.ref, "M-41 is about Mimosa, offline")
            assertEquals(bartok, cue.player)
            assertEquals("M-40, one year ago today (★9): « Bartok a vaincu l'Ender Dragon seul. »", cue.line)
            assertNull(f.reminiscence().anniversary(f.now + 3_600_000, listOf(bartok, mimosa)), "one per day, kept in memory_state")
            assertEquals("2026-10-18", f.state.state(Reminiscence.ANNIVERSARY_STATE))

            f.clock.advance(Duration.ofDays(1))
            val next = f.reminiscence().anniversary(f.clock.millis(), listOf(mimosa)) ?: error("no anniversary the next day")
            assertEquals(everyone, next.ref, "M-44 is about Bartok, offline; M-45 about the whole server")
            assertNull(next.player)
            f.clock.advance(Duration.ofDays(1))
            assertNull(f.reminiscence().anniversary(f.clock.millis(), listOf(bartok)), "no memory fits")
        }
    }

    // ── Fixture ────────────────────────────────────────────────────────────────────────────────────

    /** The real store, index and recall on an in-memory database; memories written by hand. */
    private class Fixture : AutoCloseable {
        val clock = TestClock(NOW, ZoneId.of("UTC"))
        val db: DuckDatabase = DuckDatabase.openInMemory(DuckDatabase.Options(memoryLimitMb = 256, threads = 1, timeZone = ZoneId.of("UTC")))
        val journal = DirectJournal(db)
        val playClock = FakeMemory.FakePlayClock().apply { world = WORLD }
        val index = SearchIndex()
        val store = DuckMemoryStore(journal, index, playClock, clock)
        val state = DuckMemoryState(journal, clock)
        val recall = HybridRecall(journal, index, playClock, clock)
        var config = MemoryConfig()
        val now: Long get() = clock.millis()

        init {
            listOf(bartok, mimosa, kelvin).forEach {
                db.writer.update("INSERT INTO players VALUES (?, ?, ?, ?, 1, 0, false)", it.uuid, it.name, clock.instant(), clock.instant())
            }
        }

        fun reminiscence(store: MemoryStore = this.store) = Reminiscence(recall, store, state, journal, playClock, { config }, clock)

        /** One wake-up of a new chapter and a new reminiscence, its request accepted: only the database remembers. */
        suspend fun wake(event: GameEvent): List<Reminiscence.Recalled> {
            val chapter = ChapterMemory("director-c${nextChapter++}", 1)
            return reminiscence().forWake(listOf(signal(event)), chapter, now).also { accept(chapter) }
        }

        /** The model accepted the request that showed what [chapter] holds: it is recorded, as the Director does. */
        suspend fun accept(chapter: ChapterMemory) = Shown.record(store, chapter.takeUnrecorded())

        private var nextChapter = 1

        /**
         * A memory about [players], [at] (3 days ago by default), fully strong unless [reinforcedPlay] says
         * otherwise; [shownPlay]: the world play clock at which it was last recalled.
         */
        fun memory(
            id: Long,
            title: String,
            content: String,
            players: List<PlayerRef> = listOf(bartok),
            tags: List<String> = emptyList(),
            entities: List<String> = emptyList(),
            importance: Int = 7,
            at: Instant = clock.instant().minus(Duration.ofDays(3)),
            place: Pos? = null,
            shownPlay: Long? = null,
            reinforcedPlay: Long = shownPlay ?: WORLD,
            archived: Boolean = false,
        ): Ref {
            db.writer.update(
                """
                INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags,
                    importance, valence, dim, x, y, z, stability_h, reinforced_play_s, recalls, last_recalled_at, source,
                    evidence_events, evidence_tools, archived)
                VALUES (?, 'episode', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, 0, ?, 'chronicler', [], [], ?)
                """.trimIndent(),
                id, at, at, at, title, content, uuidArray(players.map { it.uuid }), stringArray(entities), stringArray(tags), importance,
                place?.dim, place?.x, place?.y, place?.z, Decay.initialStability(importance), reinforcedPlay,
                shownPlay?.let { clock.instant() }, archived,
            )
            val ref = Ref(RefKind.MEMORY, id)
            index.index(db.writer, ref, listOf(title, content, tags.joinToString(" "), entities.joinToString(" ")).joinToString(" "))
            return ref
        }

        fun lavaMemory(id: Long): Ref =
            memory(id, "Mort dans la lave n°$id", "Bartok est mort dans la lave, encore une fois ($id).", tags = listOf("death", "lava"), entities = listOf("minecraft:lava"))

        /** The channels of [ref]'s `recalls` rows. */
        fun recalls(ref: Ref): List<String> = db.writer.query("SELECT channel FROM recalls WHERE ref_id = ? ORDER BY ts", ref.id) { getString(1) }

        override fun close() = db.close()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-18T20:00:00Z")
        const val WORLD = 500 * 3_600L
        const val OVERWORLD = "minecraft:overworld"
        val bartok = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000b471"), "Bartok")
        val mimosa = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000041a0"), "Mimosa")
        val kelvin = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000ce1f"), "Kelvin")
        val home = Pos(OVERWORLD, 100, 64, 100)
        var nextEvent = 10_000L

        fun signal(event: GameEvent, urgency: Urgency = Urgency.SOON, annotation: String? = null, id: Long = nextEvent++) =
            DirectorSignal.Event(Envelope(id, NOW.toEpochMilli(), 0, event), urgency, annotation)

        fun cue(event: GameEvent, urgency: Urgency = Urgency.SOON, annotation: String? = null): Reminiscence.Cue =
            Reminiscence.cueOf(signal(event, urgency, annotation)) ?: error("no cue for $event")

        fun death(player: PlayerRef, cause: String, killer: EntityRef? = null) = PlayerDied(player, home, cause, killer, null, null, 5, false, false)

        fun kill(killer: PlayerRef, victim: PlayerRef) =
            EntityKilled(killer, home, EntityRef("minecraft:player", victim.uuid, victim.name, victim), "minecraft:iron_sword", pvp = true, tamed = false)

        fun chat(player: PlayerRef, channel: ChatChannel, text: String) = ChatMessage(player, channel, text, emptyList())
    }
}
