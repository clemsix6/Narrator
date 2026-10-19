package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rules at work, without the world (M4 §8.6; task M4-18): a real rule book on a real canon ([RuleFixture]),
 * the rule engine in the engine's host on a state in memory, facts published on the bus, the engine's clock moved
 * by the test. The effects used need no server: `notify`, and a `teleport` to a player who is not online (an
 * `INVALID`, which counts as a failure). The world's triggers and effects are the GameTests' (`RuleEngineGameTests`).
 */
class RuleEngineTest {
    private val f = RuleFixture()
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val rules = RuleEngine(f.services, f.store, f.book, f.actions, tags = { _, _ -> emptySet() }, clock = f.clock, random = Random(1))
    private val engine = Engine(f.services, f.store, listOf(rules), f.clock)

    init {
        online(bartok)
        online(mimosa)
        engine.begin(f.clock.millis()) {}
    }

    @AfterEach
    fun close() {
        engine.stop()
        f.close()
    }

    @Test
    fun `a fact that matches fires the rule, one act, its notify wakes the Director with what happened, and the rule counts it`() = runBlocking<Unit> {
        val law = create(chatLaw(notify(), words = """["nul"]"""))
        chat(bartok, "Il est nul, ce Narrateur.")
        chat(bartok, "Bonjour à tous.")
        tick()
        awaitSignals(1)
        awaitCounted(law) { it.fired == 1 }

        assertEquals(
            DirectorSignal.Mechanic(MechanicSignal.RULE, "${law.ref} fired: Bartok spoke in the public chat; your note: « on m'insulte encore »", f.clock.millis(), priority = false),
            f.director.signals.single(),
        )
        val fired = decisions("fired").single()
        assertEquals("Bartok", fired.subject)
        assertEquals("${law.ref} Bartok spoke in the public chat", fired.reason)
        assertEquals("mechanics:${law.ref}", fired.conversationId)
        assertEquals("notify ok: ok: you will be woken with: ${law.ref} fired: Bartok spoke in the public chat; your note: « on m'insulte encore »", decisions("action").single().reason)
        assertEquals(listOf(law.ref.toString()), decisions("notified").map { it.subject })
        assertTrue(decisions("fired").none { "nul" in it.reason.orEmpty() }, "the chat's text is never told")
        assertEquals(f.clock.millis(), f.store.get().rule(law.ref.id)!!.lastFiredAt)
    }

    @Test
    fun `an effect waits for its delay on the engine's clock`() = runBlocking<Unit> {
        val law = create(chatLaw(notify(delay = 10)))
        chat(bartok, "nul")
        tick()
        Thread.sleep(100)
        assertEquals(emptyList(), f.director.signals.toList(), "not before its 10 s")
        f.clock.advance(Duration.ofSeconds(10))
        tick()
        awaitSignals(1)
        awaitCounted(law) { it.fired == 1 }
    }

    @Test
    fun `the limits hold a second firing of the same player back within 3 s, counted skipped, told once a minute`() = runBlocking<Unit> {
        val law = create(chatLaw(notify()))
        repeat(3) { chat(bartok, "nul") }
        chat(mimosa, "nul")
        tick()
        second()
        awaitCounted(law) { it.fired == 2 && it.skipped == 2 }
        val throttled = decisions("throttled").single()
        assertEquals(law.ref.toString(), throttled.subject)
        assertTrue(throttled.reason!!.startsWith("${law.ref}: 2 firings held back by the limits since 19:40:00 (once per player every 3s, 30 per rule in 10m"), throttled.reason)

        f.clock.advance(Duration.ofSeconds(1))
        chat(bartok, "nul")
        tick()
        second()
        awaitCounted(law) { it.skipped == 3 }
        assertEquals(1, decisions("throttled").size, "one decision a minute at most")
        f.clock.advance(Duration.ofSeconds(2))
        chat(bartok, "nul")
        tick()
        awaitCounted(law) { it.fired == 3 }
    }

    @Test
    fun `five failed firings in a row disable the rule, a skipped one in between neither counts nor breaks the row`() = runBlocking<Unit> {
        val law = create(chatLaw("""{"tool":"teleport","at":"actor","args":{"to_player":"Nobody"}}"""))
        repeat(4) {
            chat(bartok, "nul")
            tick()
            awaitCounted(law) { rule -> rule.failures == it + 1 }
            f.clock.advance(Duration.ofSeconds(3))
        }
        // Bartok leaves before his firing runs: its effect is skipped, the row goes on.
        chat(bartok, "nul")
        f.services.players.remove(bartok.uuid)
        tick()
        awaitCounted(law) { it.skipped == 1 }
        assertEquals(4, f.store.get().rule(law.ref.id)!!.failures)
        online(bartok)
        f.clock.advance(Duration.ofSeconds(3))
        chat(bartok, "nul")
        tick()
        awaitSignals(1)

        val failure = "teleport: Invalid arguments: Nobody is not online; online: Mimosa, Bartok"
        assertEquals("${law.ref} disabled after 5 failures: $failure", (f.director.signals.single() as DirectorSignal.Mechanic).line)
        val canon = assertNotNull(f.base.store.canon(law.ref))
        assertEquals(CanonStatus.RETIRED, canon.status)
        assertEquals("disabled after 5 failures in a row: $failure", canon.resolution)
        assertEquals(emptyList(), f.store.get().rules, "out of the state")
        assertEquals(5, decisions("failed").size)
        assertEquals("${law.ref} skipped (Bartok spoke in the public chat): teleport skipped: Bartok is not online", decisions("skipped").single().reason)
        assertEquals(1, decisions("disabled").size)
    }

    @Test
    fun `a plan fires once, its first firing fulfils it, and the facts that come with it fire nothing`() = runBlocking<Unit> {
        val plan = create(chatLaw(notify(), kind = "plan", once = true))
        chat(bartok, "nul")
        chat(mimosa, "nul")
        tick()
        awaitSignals(1)
        awaitCondition("the plan's end") {
            tick()
            f.base.store.canon(plan.ref)?.status == CanonStatus.FULFILLED
        }
        assertEquals("fired once: Bartok spoke in the public chat", f.base.store.canon(plan.ref)!!.resolution)
        assertEquals(emptyList(), f.store.get().rules)
        chat(mimosa, "nul")
        f.clock.advance(Duration.ofSeconds(5))
        tick()
        second()
        Thread.sleep(100)
        assertEquals(1, f.director.signals.size, "the plan is done")
        assertEquals(1, decisions("fired").size)
        assertEquals(1, decisions("fulfilled").size)
    }

    @Test
    fun `a rule past its time ends at its next second, a law retired and a plan expired, the Director told`() = runBlocking<Unit> {
        val law = create(chatLaw(notify(), minutes = 5))
        val plan = create(chatLaw(notify(), kind = "plan", minutes = 10))
        f.clock.advance(Duration.ofMinutes(5))
        second()
        awaitCondition("the law's end") { f.base.store.canon(law.ref)?.status == CanonStatus.RETIRED }
        assertEquals("its time ran out", f.base.store.canon(law.ref)!!.resolution)
        assertEquals(listOf(plan.ref.id), f.store.get().rules.map { it.id })
        chat(bartok, "nul")
        f.clock.advance(Duration.ofMinutes(5))
        tick()
        second()
        awaitCondition("the plan's end") { f.base.store.canon(plan.ref)?.status == CanonStatus.EXPIRED }
        awaitSignals(2)
        assertEquals(
            listOf("${law.ref} law ended: its time is up (0 fired)", "${plan.ref} plan ended: its time is up (0 fired)"),
            f.director.signals.map { (it as DirectorSignal.Mechanic).line },
        )
        assertEquals(2, decisions("expired").size)
        assertEquals(0, decisions("fired").size, "nothing fires past its time")
    }

    @Test
    fun `the server stops, what waits is dropped and counted in a decision`() = runBlocking<Unit> {
        create(chatLaw(notify()))
        chat(bartok, "nul")
        engine.stop()
        assertEquals("the server stops: 0 firings in progress and 1 waiting dropped", decisions("skipped").single().reason)
        tick()
        Thread.sleep(100)
        assertEquals(emptyList(), f.director.signals.toList())
    }

    @Test
    fun `the Rules line of the situation comes from the state`() = runBlocking<Unit> {
        val law = create(chatLaw(notify()))
        second()
        assertEquals(listOf("Rules: ${law.ref} law chat « nul » → notify (until 20:40)"), engine.situationLines())
    }

    // ── Helpers ──

    private suspend fun create(json: String): RuleView = f.book.create(f.spec(json), f.caller)

    /** A law (or [kind]) set off by a public chat with [words], doing [effect], for [minutes]. */
    private fun chatLaw(effect: String, words: String = """["nul"]""", kind: String = "law", once: Boolean = false, minutes: Int = 60) =
        """{"kind":"$kind","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"chat","when":{"words":$words},""" +
            """"effects":[$effect],"once":$once,"minutes":$minutes}"""

    private fun notify(delay: Int = 0) = """{"tool":"notify","at":"actor","args":{"note":"on m'insulte encore"},"delay_seconds":$delay}"""

    private fun chat(player: PlayerRef, text: String) {
        f.services.bus.publish(ChatMessage(player, ChatChannel.PUBLIC, text, emptyList()))
    }

    private fun online(player: PlayerRef) {
        val now = f.clock.millis()
        f.services.players.put(LivePlayer(player, 1, now, false, "survival", Pos("minecraft:overworld", 3, 64, 3), null, null, false, 20f, 20f, 20, null, null, now, false))
    }

    /** A tick of the engine at the clock's time. */
    private fun tick() = engine.beat(f.clock.millis(), { rules.tick(f.clock.millis()) }, {})

    /** A second of the engine: the clock moves 1 s on. */
    private fun second() {
        f.clock.advance(Duration.ofMillis(1_001))
        engine.beat(f.clock.millis(), { rules.tick(f.clock.millis()) }, { rules.second(null, f.clock.millis()) })
    }

    private fun decisions(name: String): List<DecisionRecord> =
        f.services.traces.decisions.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

    /** Ticks until the firings are counted in [rule]'s state as [counted] says. */
    private suspend fun awaitCounted(rule: RuleView, counted: (io.github.clemsix6.narrator2.mechanics.RuleState) -> Boolean) =
        awaitCondition("the counts of ${rule.ref}") {
            tick()
            f.store.get().rule(rule.ref.id)?.let(counted) == true
        }

    /** Ticks until the Director got [count] signals. */
    private suspend fun awaitSignals(count: Int) = awaitCondition("$count signals") {
        tick()
        f.director.signals.size >= count
    }

    private suspend fun awaitCondition(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }
}
