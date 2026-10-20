package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The modules deployed (M5 §6.4, §6.6, §3.7): each refusal of a deployment, its canon and state, stops and the switch. */
class ModuleBookTest {
    private val f = ModuleFixture(maxActive = 2)

    @AfterEach
    fun close() = f.close()

    /** A module that greets its player, and says how it stops. */
    private val greeter = ModuleFixture.source(
        "greeter",
        members = """
            public void stop(ModuleContext m, StopReason why) { m.log("stop " + why); }
        """,
        body = """
            m.acts().say("Bonjour " + m.args().player("player") + " x" + m.args().integer("count"));
            m.storage().put("started", "yes");
        """,
    )

    private val greeterParams = listOf(
        ParamSpec("player", ParamType.PLAYER, "Who is greeted.", required = true, fallback = null, min = null, max = null, choices = emptyList()),
        ParamSpec("count", ParamType.INTEGER, "How many times.", required = false, fallback = "2", min = 1.0, max = 5.0, choices = emptyList()),
    )

    @Test
    fun `a deployment writes its canon row and its state, starts its module and says so`() = runBlocking<Unit> {
        f.module("greeter", greeter, greeterParams)
        f.start()
        val now = f.clock.millis()

        val view = f.deploy(f.spec("greeter", """{"player":"bartok"}"""))

        val ref = view.ref
        assertEquals(listOf("greeter", "1", "LAW", "${now + 10 * 60_000L}"), listOf(view.module, "${view.version}", view.kind.name, "${view.endsAt}"))
        val draft = f.drafts.single()
        assertEquals(CanonKind.LAW to CanonMechanic.MODULE, draft.kind to draft.mechanic)
        assertEquals(listOf(MechanicsSamples.bartok.uuid), draft.players, "the players its parameters name")
        assertEquals(CanonStatus.ACTIVE to "Que le jeu commence !", draft.status to draft.quote)
        assertEquals(CanonStatus.ACTIVE, f.canon(ref).status)
        assertEquals(Source.DIRECTOR, f.canon(ref).source, "written as the Director: the engine's tools write its rows")

        val run = f.store.get().modules.single()
        assertEquals(ref.id, run.id)
        assertEquals(JsonObject(mapOf("player" to JsonPrimitive("Bartok"), "count" to JsonPrimitive(2))), run.params, "its parameters resolved, fallbacks included")
        assertEquals(listOf(MechanicsSamples.bartok.uuid.toString()), run.playerIds)
        assertEquals("sha256:greeter-v1", run.hash)
        assertEquals(42L to "director-c13", run.turnId to run.conversationId)

        val started = f.decisions(ModuleEngine.STARTED).single()
        assertEquals("$ref greeter v1 law until 18:10: player bartok", started.reason)
        assertEquals(42L, started.turnId)
        f.await("its act") { f.runner.ran.isNotEmpty() }
        assertEquals("say" to "Bonjour Bartok x2", f.runner.ran.single().let { it.second to it.third["text"]!!.jsonPrimitive.content })
        f.second()
        assertEquals(mapOf("started" to "yes"), f.store.get().modules.single().storage, "its storage in the state each second")
        assertEquals(listOf("$ref greeter law Bartok (until 18:10)"), f.book.list().map { it.line })
        assertEquals(listOf("Modules: $ref greeter law Bartok (until 18:10)"), f.engine.situation(f.clock.millis()))
    }

    @Test
    fun `each refusal of a deployment, in the order of the design, writes nothing`() = runBlocking<Unit> {
        f.module("greeter", greeter, greeterParams)
        f.module("statues", ModuleFixture.source("statues"), greeterParams, version = 2, status = ModuleStatus.PENDING)
        f.module("lava", ModuleFixture.source("lava"), status = ModuleStatus.BROKEN, problem = "error 3:5 World.surface(String) is not applicable")
        f.module("tag", ModuleFixture.source("tag"), version = 1, status = ModuleStatus.REJECTED)
        val bartok = """{"player":"Bartok"}"""

        assertTrue(f.refusal(f.spec("greeter", bartok)).startsWith("DENIED (mechanics_off): "), "the engine does not run yet")
        f.start()
        f.forge.available = false
        assertEquals("DENIED (forge_off): the Forge is unavailable: fake forge off", f.refusal(f.spec("greeter", bartok)))
        f.forge.available = true
        f.store.update { it.copy(forgeHalt = ForgeHalt("trop de poules", "Clement", f.clock.millis())) }
        assertEquals("DENIED (forge_off): the Forge was switched off by Clement: trop de poules", f.refusal(f.spec("greeter", bartok)))
        f.store.update { it.copy(forgeHalt = null) }

        assertEquals("Invalid arguments: no module ducks in your arsenal; ready: greeter", f.refusal(f.spec("ducks")))
        assertEquals("Invalid arguments: greeter has no version 7; its versions: v1 ready", f.refusal(f.spec("greeter", bartok, version = 7)))
        assertEquals(
            "DENIED (awaiting_approval): statues v2 waits for an operator's approval (/narrator forge approve statues 2)",
            f.refusal(f.spec("statues", bartok)),
        )
        assertEquals("FAILED: lava v1 no longer compiles: error 3:5 World.surface(String) is not applicable", f.refusal(f.spec("lava")))
        assertEquals("Invalid arguments: tag v1 was rejected by an operator; deploy another version", f.refusal(f.spec("tag", version = 1)))
        assertEquals("Invalid arguments: params.count must be between 1 and 5 (got 12)", f.refusal(f.spec("greeter", """{"player":"Bartok","count":12}""")))
        assertEquals("Invalid arguments: params.player: no player named Bob has ever played here", f.refusal(f.spec("greeter", """{"player":"Bob"}""")))
        assertEquals("Invalid arguments: C-99 is not a running judged challenge", f.refusal(f.spec("greeter", bartok, challenge = Ref(RefKind.CANON, 99))))
        f.forge.rejection = ForgeRejected(null, "the compilation took too long", failed = true)
        assertEquals("FAILED: the compilation took too long", f.refusal(f.spec("greeter", bartok)))
        f.forge.rejection = null

        assertTrue(f.drafts.isEmpty() && f.store.get().modules.isEmpty(), "nothing written by a refusal")
        val first = f.deploy(f.spec("greeter", bartok))
        val second = f.deploy(f.spec("greeter", """{"player":"Mimosa"}"""))
        assertEquals(
            "DENIED (module_limit): 2 modules run (${first.ref}, ${second.ref}); stop one first",
            f.refusal(f.spec("greeter", """{"player":"Josiane"}""")),
        )
    }

    @Test
    fun `a module that faults as it starts is not deployed, its canon cancelled, its end decided, no MODULE line`() = runBlocking<Unit> {
        f.module("boom", ModuleFixture.source("boom", body = """throw new IllegalStateException("boom");"""))
        f.start()

        val text = f.refusal(f.spec("boom", kind = RuleKind.PLAN))

        assertTrue(text.startsWith("FAILED: C-1 failed to start: exception in start: IllegalStateException: boom at Boom.java:"), text)
        assertEquals(emptyList(), f.store.get().modules)
        val canon = f.canon(Ref(RefKind.CANON, 1))
        assertEquals(CanonStatus.CANCELLED, canon.status)
        assertTrue(canon.resolution!!.startsWith("disabled: it failed to start: exception in start: IllegalStateException: boom"), canon.resolution)
        val ended = f.decisions(ModuleEngine.ENDED).single()
        assertEquals("disabled", ended.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content)
        assertEquals(listOf("fault"), f.decisions(ModuleEngine.FAULT).map { it.decision })
        assertEquals(emptyList(), f.decisions(ModuleEngine.STARTED))
        assertEquals(emptyList(), f.moduleLines(), "the tool's answer tells the Director")
        assertEquals(1, f.faults.size, "its fault noted on its version")
    }

    @Test
    fun `the Director's stop and an operator's call stop STOPPED and resolve the canon, only the operator's told to the Director`() = runBlocking<Unit> {
        f.module("greeter", greeter, greeterParams)
        f.start()
        LogCapture().use { logs ->
            val law = f.deploy(f.spec("greeter", """{"player":"Bartok"}"""))
            val plan = f.deploy(f.spec("greeter", """{"player":"Mimosa"}""", kind = RuleKind.PLAN))
            f.await("their acts") { f.runner.ran.size == 2 }

            val director = Author(Source.DIRECTOR, turnId = 43, conversationId = "director-c13")
            val stopped = f.book.stop(law.ref, "Le Narrateur a congédié le saluteur.", director)
            val operator = Author(Source.OPERATOR, operator = "Clement")
            val cancelled = f.book.stop(plan.ref, "Un opérateur a arrêté le saluteur.", operator)

            assertTrue(logs.messages.count { "greeter: stop STOPPED" in it } == 2, logs.messages.toString())
            assertEquals(CanonStatus.RETIRED to "Le Narrateur a congédié le saluteur.", f.canon(law.ref).status to f.canon(law.ref).resolution)
            assertEquals(CanonStatus.CANCELLED, f.canon(plan.ref).status)
            assertEquals(1 to 0, stopped.acts to stopped.faults)
            assertEquals(
                "${law.ref} greeter law removed by the director (turn 43), canon retired: 1 act, 0 faults; « Le Narrateur a congédié le saluteur. »",
                stopped.line,
            )
            assertEquals(
                listOf("removed", "removed"),
                f.decisions(ModuleEngine.ENDED).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content },
            )
            assertEquals(43L, f.decisions(ModuleEngine.ENDED).first().turnId)
            assertEquals(
                listOf("${plan.ref} greeter ended: stopped by operator Clement (1 act, 0 faults); « Un opérateur a arrêté le saluteur. »"),
                f.moduleLines(),
            )
            assertTrue(cancelled.line.startsWith("${plan.ref} greeter plan removed by operator Clement, canon cancelled"), cancelled.line)
            assertEquals(emptyList(), f.store.get().modules)
            assertEquals(emptyList(), f.book.list())
            assertEquals(
                "${law.ref} is not a running module; running: none",
                assertFailsWith<MechanicRejected> { f.book.stop(law.ref, "encore", director) }.message,
            )
        }
    }

    @Test
    fun `the switch stops every module at once, is kept in the state, and only resume lifts it`() = runBlocking<Unit> {
        f.module("greeter", greeter, greeterParams)
        f.start()
        LogCapture().use { logs ->
            val first = f.deploy(f.spec("greeter", """{"player":"Bartok"}"""))
            val second = f.deploy(f.spec("greeter", """{"player":"Mimosa"}""", kind = RuleKind.PLAN))

            val stopped = f.book.halt("trop de poules", "Clement")

            assertEquals(listOf(first.ref, second.ref), stopped.map { it.ref })
            assertEquals(2, logs.messages.count { "greeter: stop FORGE_OFF" in it }, "each module's stop(FORGE_OFF)")
            val halt = f.store.get().forgeHalt
            assertEquals("trop de poules" to "Clement", halt?.reason to halt?.by, "kept in the state before it answers")
            assertEquals(emptyList(), f.store.get().modules)
            assertEquals(CanonStatus.RETIRED to ModuleEngine.FORGE_OFF_NOTE, f.canon(first.ref).status to f.canon(first.ref).resolution)
            assertEquals(CanonStatus.CANCELLED, f.canon(second.ref).status)
            assertEquals(listOf("forge_off", "forge_off"), f.decisions(ModuleEngine.ENDED).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content })
            assertEquals(listOf("the Forge was switched off by an operator: ${first.ref}, ${second.ref} stopped"), f.moduleLines(), "one line for all")
            val halted = f.decisions(ModuleBook.HALTED, DecisionRecord.Kind.FORGE).single()
            assertEquals("Clement", halted.subject)
            assertEquals("the Forge switched off by Clement: « trop de poules »; 2 modules stopped (${first.ref}, ${second.ref})", halted.reason)

            assertEquals("DENIED (forge_off): the Forge was switched off by Clement: trop de poules", f.refusal(f.spec("greeter", """{"player":"Bartok"}""")))
            assertTrue(assertFailsWith<MechanicRejected> { f.book.halt("encore", "Mimosa") }.message!!.startsWith("the Forge is already switched off (by Clement)"))

            f.book.resume("Josiane")
            assertNull(f.book.halted)
            assertTrue(f.decisions(ModuleBook.RESUMED, DecisionRecord.Kind.FORGE).single().reason!!.startsWith("the Forge switched back on by Josiane (off since 10-19 18:00:00 by Clement"))
            assertEquals("the Forge is not switched off: nothing to lift", assertFailsWith<MechanicRejected> { f.book.resume("Josiane") }.message)
            val third = f.deploy(f.spec("greeter", """{"player":"Bartok"}"""))
            assertEquals(1, f.book.list().size, "deployed again once lifted")
            f.book.halt("encore des poules", "Mimosa")
            assertEquals("the Forge switched off by Mimosa: « encore des poules »; 1 module stopped (${third.ref})", f.decisions(ModuleBook.HALTED, DecisionRecord.Kind.FORGE).last().reason)
        }
    }

    @Test
    fun `a module referees a running judged challenge, one module at a time`() = runBlocking<Unit> {
        f.module("greeter", greeter, greeterParams)
        f.start()
        val judged = f.challenges.start(MechanicsSamples.statue, f.caller)

        val referee = f.deploy(f.spec("greeter", """{"player":"Bartok"}""", challenge = judged.ref))

        assertEquals(judged.ref, referee.challenge)
        assertEquals(judged.ref.id, f.store.get().modules.single().challenge)
        assertTrue(f.decisions(ModuleEngine.STARTED).single().reason!!.endsWith("; referees ${judged.ref}"))
        assertEquals(
            "Invalid arguments: ${judged.ref} is refereed by ${referee.ref} (greeter) already",
            f.refusal(f.spec("greeter", """{"player":"Mimosa"}""", challenge = judged.ref)),
        )
        assertEquals(listOf("Modules: ${referee.ref} greeter law Bartok referees ${judged.ref} (until 18:10)"), f.engine.situation(f.clock.millis()))
    }
}
