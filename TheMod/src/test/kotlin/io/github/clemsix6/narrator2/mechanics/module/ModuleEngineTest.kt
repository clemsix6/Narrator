package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.host.Fault
import io.github.clemsix6.narrator2.forge.host.Hosts
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The modules at work (M5 §6.5-6.7): facts, timers and callbacks, each call of a module one act of the engine; each end
 * of §6.6 with its canon, its decision and its line to the Director; the budgets of the second and the tick; the notes
 * and log lines; the restart; the Forge stuck; the loaders that leak.
 */
class ModuleEngineTest {
    private val f = ModuleFixture()
    private val ids = AtomicLong(1)

    @AfterEach
    fun close() = f.close()

    private fun chat(text: String, channel: ChatChannel = ChatChannel.PUBLIC) =
        f.engine.onEvent(Envelope(ids.getAndIncrement(), f.clock.millis(), 0, ChatMessage(MechanicsSamples.bartok, channel, text, emptyList())))

    private fun ending(name: String = ModuleEngine.ENDED): List<String> = f.decisions(name).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content }

    /** The canon row of [ref], once the engine resolved it (in a coroutine). */
    private fun resolved(ref: Ref): Pair<CanonStatus, String?> {
        f.await("$ref resolved") { !f.canon(ref).status.open }
        return f.canon(ref).status to f.canon(ref).resolution
    }

    @Test
    fun `facts, timers and callbacks run as calls of the module, each one act of the engine`() {
        f.module(
            "echo",
            ModuleFixture.source(
                "echo",
                body = """
                    m.events().on(GameFact.Chat.class, c -> {
                        m.acts().say("écho : " + c.text());
                        m.acts().sayTo(c.player(), "rien que pour toi");
                    });
                    m.timers().every(20, () -> m.storage().putInt("seconds", m.storage().getInt("seconds", 0) + 1));
                    m.acts().sound(null, "entity.chicken.ambient").then(o -> m.storage().put("sound", o.status()));
                """,
            ),
        )
        f.start()
        val view = f.deploy(f.spec("echo"))

        chat("bonjour")
        chat("rends-moi mes diamants", ChatChannel.PRAY)
        f.tick()
        f.await("the acts of two calls", ticking = false) { f.runner.ran.size == 3 }
        f.ticks(40)
        f.second()

        assertEquals(listOf("play_sound", "say", "say"), f.runner.ran.map { it.second })
        assertEquals(
            listOf(null to "${view.ref}: 1 acts of its module (play_sound)", "Bartok" to "${view.ref}: 2 acts of its module (say, say)"),
            f.runner.opened.toList(),
            "one act of the engine per call; a prayer is never a fact",
        )
        assertEquals("écho : bonjour", f.runner.ran[1].third["text"]!!.jsonPrimitive.content)
        val storage = f.store.get().modules.single().storage
        assertEquals("ok", storage["sound"], "its callback, in a call of its own")
        assertEquals("2", storage["seconds"], "a timer every 20 ticks, over 41 ticks")
        assertEquals(3, f.book.list().single().acts)
    }

    @Test
    fun `a module that ends itself is stopped with ENDED, its canon fulfilled with its words, the Director told`() {
        f.module(
            "finisher",
            ModuleFixture.source(
                "finisher",
                members = """public void stop(ModuleContext m, StopReason why) { m.log("stop " + why); m.acts().say("adieu"); }""",
                body = """m.timers().after(5, () -> m.end("Mimosa a gagné la partie"));""",
            ),
        )
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("finisher", kind = RuleKind.PLAN))
            f.ticks(5)

            assertEquals(CanonStatus.FULFILLED to "Mimosa a gagné la partie", resolved(view.ref))
            assertTrue(logs.messages.any { "${view.ref} finisher: stop ENDED" in it }, logs.messages.toString())
            assertTrue(logs.messages.any { "${view.ref} finisher ended (fulfilled): 1 act, 0 faults, 1 acts dropped" in it }, "its act in stop dropped")
            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertEquals(listOf("fulfilled"), ending())
            assertEquals("${view.ref} finisher plan fulfilled, canon fulfilled: 1 act, 0 faults; « Mimosa a gagné la partie »", f.decisions(ModuleEngine.ENDED).single().reason)
            assertEquals(listOf("${view.ref} finisher ended by itself: « Mimosa a gagné la partie » (1 act, 0 faults)"), f.moduleLines())
            assertEquals(emptyList(), f.store.get().modules)
            assertTrue(f.runner.ran.isEmpty(), "nothing asked in stop is done")
        }
    }

    @Test
    fun `a reason in the first or second person the memory refuses still closes its canon, with the engine's words`() {
        f.module("rude", ModuleFixture.source("rude", body = """m.timers().after(5, () -> m.end("Tu as gagné, je te félicite"));"""))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("rude", kind = RuleKind.PLAN))
            f.ticks(5)

            assertEquals(CanonStatus.FULFILLED to ModuleEngine.ENDED_ITSELF_NOTE, resolved(view.ref), "the line closes all the same (M5-16, defect 2)")
            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertTrue(logs.at(Level.WARN).any { "the canon row of ${view.ref} refused its end" in it }, logs.messages.toString())
            assertEquals(listOf("${view.ref} rude ended by itself: « Tu as gagné, je te félicite » (0 acts, 0 faults)"), f.moduleLines(), "the Director reads its words")
        }
    }

    @Test
    fun `a status the row's kind refuses still closes its canon, with the ending's status for that kind`() {
        f.module("finisher", ModuleFixture.source("finisher", body = """m.timers().after(5, () -> m.end("Mimosa a gagné la partie"));"""))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("finisher", kind = RuleKind.PLAN))
            // A row whose kind is not the deployment's (M5 review, finding 4): a law is never `fulfilled`.
            f.fakeMemory.store.rows[view.ref] = f.canon(view.ref).copy(kind = CanonKind.LAW)
            f.ticks(5)

            assertEquals(CanonStatus.RETIRED to ModuleEngine.ENDED_ITSELF_NOTE, resolved(view.ref), "a row the engine ends never stays open")
            assertTrue(logs.at(Level.WARN).any { "the canon row of ${view.ref} refused its end (a law cannot be fulfilled): resolved retired" in it }, logs.messages.toString())
            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertEquals(listOf("fulfilled"), ending())
        }
    }

    @Test
    fun `a canon row closed before its module ends stays as it was, without a warning`() {
        f.module("waiter", ModuleFixture.source("waiter"))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("waiter", minutes = 5))
            val operator = Author(Source.OPERATOR, operator = "Clement")
            runBlocking { f.fakeMemory.store.resolveCanon(view.ref, CanonStatus.RETIRED, "Clement a retiré la loi à la main.", emptyList(), operator) }
            f.clock.advance(Duration.ofMinutes(5))
            f.second()

            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertEquals(listOf("expired"), ending())
            assertEquals(CanonStatus.RETIRED to "Clement a retiré la loi à la main.", f.canon(view.ref).let { it.status to it.resolution }, "left as the operator closed it")
            assertTrue(logs.messages.any { "the canon row of ${view.ref} refused its end" in it && "already retired, nothing to close" in it }, logs.messages.toString())
            assertEquals(emptyList(), logs.at(Level.WARN).filter { "${view.ref}" in it }, "nothing went wrong")
        }
    }

    @Test
    fun `a module the machine holds back is no fault - its call given up, the calls after it wait, none is lost`() {
        // After its first read, 10 ms of its own work (rounds of a loop measured on this machine): the window in which the
        // chat after it comes while the call given up still runs.
        val rounds = Hosts.rounds(10)
        f.module(
            "reader",
            ModuleFixture.source(
                "reader",
                "long sink;",
                """
                    m.events().on(GameFact.Chat.class, c -> {
                        if (c.text().equals("lent")) {
                            m.world().dayTime();
                            long x = 0; for (long i = 0; i < ${rounds}L; i++) x += i ^ (x >>> 3); sink = x;
                            m.world().dayTime();
                        }
                        m.storage().put(c.text(), "lu");
                    });
                """,
            ),
        )
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("reader"))
            // Its first read holds the server thread 60 ms (a busy machine), past the call's 25 ms: the call is given up,
            // its next read refused; the second chat waits for it to end, then runs.
            f.server.heldMillis = 60
            chat("lent")
            chat("vite")
            f.tick()
            f.server.heldMillis = 0
            // The skipped chat is asked again at the next tick, and again at each tick while a busy machine keeps the
            // module thread from even starting it before its deadline (skipped again, nobody's fault): never lost.
            val storage = { f.store.get().module(view.ref.id)!!.storage }
            var seconds = 0
            do {
                f.second()
            } while (storage().isEmpty() && ++seconds < 20)

            assertEquals(1, f.book.list().size, "it runs on")
            assertEquals(0, f.book.list().single().faults)
            assertEquals(emptyList(), f.decisions(ModuleEngine.FAULT))
            val givenUp = logs.messages.count { "${view.ref} reader: its fact call was given up" in it }
            val settled = logs.messages.count { "${view.ref} reader: its fact call, given up, ended without computing past its budget" in it && "nobody's fault" in it }
            assertEquals(givenUp, settled, "each call given up settled as nobody's fault: ${logs.messages}")
            if (givenUp == 1) {
                assertEquals(mapOf("vite" to "lu"), storage(), "the call given up was cut, the next one ran: ${logs.messages}")
            } else {
                // A busy machine may hold the replayed call back past its deadline once it started (a pause of the whole
                // JVM: measured under load, over 100 ms every 25 s or so): given up and cut too, nobody's fault.
                assertEquals(2, givenUp, logs.messages.toString())
                assertEquals(emptyMap(), storage(), "both calls given up were cut")
            }
        }
    }

    @Test
    fun `a module whose time ran out is stopped with EXPIRED, a law retired, the Director told`() {
        f.module("waiter", ModuleFixture.source("waiter", members = """public void stop(ModuleContext m, StopReason why) { m.log("stop " + why); }"""))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("waiter", minutes = 5))
            f.second()
            assertEquals(1, f.book.list().size)
            f.clock.advance(Duration.ofMinutes(5))
            f.second()

            assertEquals(CanonStatus.RETIRED to ModuleEngine.EXPIRED_NOTE, resolved(view.ref))
            assertTrue(logs.messages.any { "${view.ref} waiter: stop EXPIRED" in it }, logs.messages.toString())
            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertEquals(listOf("expired"), ending())
            assertEquals(listOf("${view.ref} waiter ended: its time is up (0 acts, 0 faults)"), f.moduleLines())
        }
    }

    @Test
    fun `a faulty module is disabled without its stop, each fault decided and noted, the Director told`() {
        f.module(
            "looper",
            ModuleFixture.source(
                "looper",
                members = """public void stop(ModuleContext m, StopReason why) { m.log("stop " + why); }""",
                body = """m.timers().every(1, () -> { while (true) { } });""",
            ),
        )
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("looper", kind = RuleKind.PLAN))
            f.tick()
            assertEquals(1, f.book.list().single().faults, "one limit fault does not disable (a GC pause may cause one)")
            f.tick()

            val (status, resolution) = resolved(view.ref)
            assertEquals(CanonStatus.CANCELLED, status)
            assertTrue(resolution!!.startsWith("disabled: limit in timer: time budget"), resolution)
            assertTrue(logs.messages.none { "looper: stop" in it }, "a disabled module's stop is never called")
            val faults = f.decisions(ModuleEngine.FAULT)
            assertEquals(2, faults.size)
            assertEquals(listOf("false", "true"), faults.map { it.detail!!.getValue("disables").jsonPrimitive.content })
            assertTrue(faults.all { it.reason!!.startsWith("${view.ref} looper: limit in timer: time budget") }, faults.toString())
            assertEquals(listOf(Fault.Kind.LIMIT, Fault.Kind.LIMIT), f.faults.map { it.second.kind }, "noted on its version")
            f.await("its decision, then its line") { ending().isNotEmpty() && f.moduleLines().isNotEmpty() }
            assertEquals(listOf("disabled"), ending())
            val line = f.moduleLines().single()
            assertTrue(line.startsWith("${view.ref} looper disabled: limit in timer: time budget") && line.endsWith("; ask the forger to fix it"), line)
            assertEquals(emptyList(), f.store.get().modules)
        }
    }

    @Test
    fun `the budget of a second, a module that takes over 50 ms of each second five seconds in a row is disabled`() {
        // 4 ms of its own work a tick (rounds of a loop, measured on this machine): 80 ms a second, however long a busy
        // machine makes them on the wall clock (M5-LOAD: the second counts its work).
        val rounds = Hosts.rounds(4)
        f.module(
            "sloth",
            ModuleFixture.source("sloth", "long sink;", """long n = ${rounds}L; m.timers().every(1, () -> { long x = 0; for (long i = 0; i < n; i++) x += i ^ (x >>> 3); sink = x; });"""),
        )
        f.start()
        val view = f.deploy(f.spec("sloth"))
        var seconds = 0
        while (f.book.list().isNotEmpty() && seconds < 8) {
            f.ticks(20)
            f.engine.second(f.clock.millis())
            seconds++
        }

        assertEquals(ForgeLimits.SLOW_SECONDS, seconds, "disabled at its fifth slow second")
        val fault = f.faults.single().second
        assertEquals(Fault.Kind.SLOWNESS, fault.kind)
        assertTrue(fault.cause.startsWith("over 50 ms of its code a second, 5 seconds in a row"), fault.cause)
        assertTrue(resolved(view.ref).second!!.startsWith("disabled: slowness in second: "))
    }

    @Test
    fun `the budget of a tick, all the modules over 10 ms in one tick is a warning, once a minute, naming the slowest`() {
        f.module("heavy", ModuleFixture.source("heavy", body = """m.timers().every(1, () -> { long t = System.nanoTime(); while (System.nanoTime() - t < 12_000_000L) { } });"""))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("heavy"))
            f.ticks(3)

            val warnings = logs.at(Level.WARN).filter { "the modules took" in it }
            assertEquals(1, warnings.size, warnings.toString())
            assertTrue(warnings.single().contains("(at most 10); the slowest: ${view.ref} heavy "), warnings.single())
            assertEquals(1, f.book.list().size, "under its own budget, it runs on")
            f.clock.advance(Duration.ofMinutes(1))
            f.tick()
            assertTrue(logs.at(Level.WARN).last { "the modules took" in it }.endsWith("3 slow ticks since the last warning"))
        }
    }

    @Test
    fun `a note to the Director, one every five minutes, the latest one waiting, the others counted`() {
        f.module("teller", ModuleFixture.source("teller", body = """m.events().on(GameFact.Chat.class, c -> m.tellDirector("note " + c.text()));"""))
        f.start()
        val view = f.deploy(f.spec("teller"))

        chat("un")
        f.tick()
        chat("deux")
        f.tick()
        chat("trois")
        f.tick()
        assertEquals(listOf("${view.ref} teller says: « note un »"), f.moduleLines())
        f.clock.advance(Duration.ofMinutes(5))
        f.second()

        assertEquals(
            listOf("${view.ref} teller says: « note un »", "${view.ref} teller says: « note trois » (1 earlier note dropped: one every 5 minutes)"),
            f.moduleLines(),
        )
        assertEquals(f.moduleLines(), f.decisions(ModuleEngine.NOTIFIED).map { it.reason }, "news for the Chronicler")
    }

    @Test
    fun `log lines, twenty a minute, the others dropped and counted in the next one`() {
        f.module(
            "chatter",
            ModuleFixture.source(
                "chatter",
                body = """
                    for (int i = 0; i < 25; i++) m.log("ligne " + i);
                    m.events().on(GameFact.Chat.class, c -> m.log("encore"));
                """,
            ),
        )
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("chatter"))
            assertEquals(ForgeLimits.LOG_LINES_PER_MINUTE, logs.messages.count { "${view.ref} chatter: ligne" in it })
            f.clock.advance(Duration.ofMinutes(1))
            chat("bonjour")
            f.tick()
            assertTrue(logs.messages.any { it.endsWith("${view.ref} chatter: encore (5 lines dropped before)") }, logs.messages.toString())
        }
    }

    @Test
    fun `facts past the limits are dropped, told once a minute`() {
        f.module("counter", ModuleFixture.source("counter", members = "int n;", body = """m.events().on(GameFact.Chat.class, c -> n++);"""))
        f.start()
        val view = f.deploy(f.spec("counter"))
        repeat(300) { chat("x") }
        f.second()

        val dropped = f.decisions(ModuleEngine.FACTS_DROPPED).single()
        assertEquals("${view.ref} counter: 44 facts dropped since 18:00:01 (256 a tick, 3000 a minute at most)", dropped.reason)
        repeat(300) { chat("y") }
        f.second()
        assertEquals(1, f.decisions(ModuleEngine.FACTS_DROPPED).size, "once a minute at most")
    }

    @Test
    fun `after a restart each deployment is built again and started with restarted() and its storage, one past its end expires, one that no longer builds fails`() {
        val keeper = ModuleFixture.source(
            "keeper",
            members = """public void stop(ModuleContext m, StopReason why) { m.storage().put("stopped", why.name()); }""",
            body = """
                if (m.restarted()) m.log("back " + m.storage().get("k") + " " + m.storage().get("stopped"));
                else m.storage().put("k", "v");
                m.timers().every(1, () -> m.storage().putInt("n", m.storage().getInt("n", 0) + 1));
            """,
        )
        f.module("keeper", keeper)
        val changed = f.module("changed", ModuleFixture.source("changed"))
        f.module("brief", ModuleFixture.source("brief", members = """public void stop(ModuleContext m, StopReason why) { m.log("brief stop " + why); }"""))
        f.start()
        val kept = f.deploy(f.spec("keeper", minutes = 60))
        val broken = f.deploy(f.spec("changed", minutes = 60))
        val brief = f.deploy(f.spec("brief", minutes = 5))
        f.ticks(3)

        f.engine.stopping()
        val skipped = f.decisions(ModuleEngine.SKIPPED).single()
        assertEquals("the server stops: 3 modules stopped until the restart (${kept.ref}, ${broken.ref}, ${brief.ref}), 0 acts in their queues dropped", skipped.reason)
        val saved = MechanicsState.decode(MechanicsState.encode(f.store.get()))
        assertEquals(mapOf("k" to "v", "n" to "3", "stopped" to "SERVER_STOPPING"), saved.module(kept.ref.id)!!.storage, "its storage, saved in its stop")
        f.forge.arsenal.replace(changed.copy(hash = "sha256:edited"))
        f.clock.advance(Duration.ofMinutes(6))

        val g = ModuleFixture(saved, clock = f.clock, forge = f.forge, fakeMemory = f.fakeMemory)
        try {
            LogCapture().use { logs ->
                g.start()
                g.await("the keeper started again") { g.decisions(ModuleEngine.RESTARTED).isNotEmpty() }
                assertTrue(logs.messages.any { "${kept.ref} keeper: back v SERVER_STOPPING" in it }, logs.messages.toString())
                assertEquals(
                    "${kept.ref} keeper v1 started again after a restart: 3 keys in its storage, 0 creatures of its own",
                    g.decisions(ModuleEngine.RESTARTED).single().reason,
                )
                g.ticks(2)
                g.engine.second(g.clock.millis())

                assertTrue(g.store.get().module(kept.ref.id)!!.storage.getValue("n").toInt() > 3, "its timers made again, its storage back")
                assertEquals(listOf(kept.ref.id), g.store.get().modules.map { it.id })
                assertTrue(logs.messages.none { "brief stop" in it }, "one past its end never starts again: no stop")
                g.await("both ended") { g.decisions(ModuleEngine.ENDED).size == 2 }
                assertEquals(
                    setOf("restart_failed", "expired"),
                    g.decisions(ModuleEngine.ENDED).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content }.toSet(),
                )
                g.await("the canon of both") { !g.canon(broken.ref).status.open && !g.canon(brief.ref).status.open }
                assertEquals(CanonStatus.RETIRED to "could not restart: the source of changed v1 no longer matches the one deployed", g.canon(broken.ref).let { it.status to it.resolution })
                assertEquals(CanonStatus.RETIRED to ModuleEngine.EXPIRED_NOTE, g.canon(brief.ref).let { it.status to it.resolution })
                assertTrue("${broken.ref} changed could not restart: the source of changed v1 no longer matches the one deployed" in g.moduleLines(), g.moduleLines().toString())
            }
        } finally {
            g.close()
        }
    }

    @Test
    fun `with the Forge switched off as the server starts, no deployment starts again`() {
        f.module("waiter", ModuleFixture.source("waiter"))
        f.start()
        val view = f.deploy(f.spec("waiter", minutes = 60))
        f.engine.stopping()
        val saved = f.store.get().copy(forgeHalt = ForgeHalt("trop de poules", "Clement", f.clock.millis()))
        val built = f.forge.built.size

        val g = ModuleFixture(saved, clock = f.clock, forge = f.forge, fakeMemory = f.fakeMemory)
        try {
            g.start()
            g.second()
            g.second()
            g.second()

            assertEquals(built, f.forge.built.size, "nothing built")
            assertEquals(emptyList(), g.store.get().modules)
            assertEquals(listOf("the Forge is switched off (by Clement): ${view.ref} did not start again"), g.moduleLines(), "told once, at the first second")
            g.await("its canon") { !g.canon(view.ref).status.open }
            assertEquals(CanonStatus.RETIRED to ModuleEngine.FORGE_OFF_NOTE, g.canon(view.ref).let { it.status to it.resolution })
            g.await("its decision") { g.decisions(ModuleEngine.ENDED).isNotEmpty() }
            assertEquals(listOf("forge_off"), g.decisions(ModuleEngine.ENDED).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content }, "ended once")
        } finally {
            g.close()
        }
    }

    @Test
    fun `the reconciliation's drop is the retrait, without stop nor canon`() {
        f.module("waiter", ModuleFixture.source("waiter", members = """public void stop(ModuleContext m, StopReason why) { m.log("stop " + why); }"""))
        f.start()
        LogCapture().use { logs ->
            val view = f.deploy(f.spec("waiter"))
            f.store.update { it.copy(modules = emptyList()) }

            f.engine.dropped(setOf(view.ref.id))

            assertEquals(emptyList(), f.book.list())
            assertTrue(logs.messages.none { "waiter: stop" in it })
            assertEquals(CanonStatus.ACTIVE, f.canon(view.ref).status, "the reconciliation resolves it")
            assertEquals(emptyList(), f.decisions(ModuleEngine.ENDED))
        }
    }

    @Test
    fun `a stuck Forge disables every module at its next second`() {
        f.module("waiter", ModuleFixture.source("waiter"))
        f.start()
        val view = f.deploy(f.spec("waiter"))
        val sleepers = List(3) { Thread { runCatching { Thread.sleep(60_000) } }.apply { isDaemon = true; start() } }
        try {
            sleepers.forEach(f.threads.lost::add)
            f.second()

            assertEquals(emptyList(), f.book.list())
            assertEquals(CanonStatus.RETIRED to "disabled: stuck in second: ${ModuleEngine.STUCK_CAUSE}", resolved(view.ref))
        } finally {
            sleepers.forEach(Thread::interrupt)
        }
    }

    @Test
    fun `a module whose abandoned call still runs holds its classes, a leak told once`() {
        f.module(
            "hasher",
            ModuleFixture.source(
                "hasher",
                members = "List<List<Integer>> l1;",
                body = """
                    List<Integer> l0 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l0.add(i);
                    l1 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l1.add(l0);
                    // The guard bounds an explicit hashCode now (M5-18); Stream.distinct still hashes it unguarded, so
                    // the host abandons the call and its thread is lost, holding the module's classes (a leak).
                    m.timers().after(1, () -> m.log("" + java.util.stream.Stream.of(l1).distinct().count()));
                """,
            ),
        )
        f.start()
        val view = f.deploy(f.spec("hasher"))
        f.tick()
        assertEquals(emptyList(), f.book.list(), "abandoned past its budget: disabled at once")

        f.clock.advance(Duration.ofMillis(ForgeLimits.LEAK_CHECK_MS))
        f.collections += ForgeLimits.LEAK_GC_CYCLES
        f.engine.second(f.clock.millis())

        val leak = assertNotNull(f.decisions(ModuleEngine.LEAK, DecisionRecord.Kind.FORGE).singleOrNull())
        assertEquals("${view.ref} hasher", leak.subject)
        assertEquals(1, f.book.leaks)
        f.engine.second(f.clock.millis())
        assertEquals(1, f.decisions(ModuleEngine.LEAK, DecisionRecord.Kind.FORGE).size, "told once")
    }
}
