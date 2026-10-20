package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.config.ForgeConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.arsenal.Manifest
import io.github.clemsix6.narrator2.forge.compile.EcjCompiler
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Forge's service (task M5-10, M5 §5.4, §6.1 to §6.3): `test` stops at each step of the pipeline with its report,
 * runs the bench on the real host with the acts checked dry by the real tools; `save` wants a passing test and a
 * scenario and writes a version, `ready` or `pending`; `build` refuses what may not be deployed, takes its cache, and
 * marks `broken` what no longer builds; `review`; a stuck compiler makes the Forge unavailable; nothing heavy runs on
 * the caller's thread. Each test has an arsenal of its own, without the built-in modules (`ArsenalScenariosTest` has
 * them).
 */
class ForgeServiceTest {
    private val made = CopyOnWriteArrayList<ForgeService>()
    private val dir: Path = Files.createTempDirectory("narrator2-forge")

    @AfterEach
    fun stop() = made.forEach { it.stop() }

    /** A Forge on [dir] (its arsenal in `forge/`), chaos 3, the real dry checks; [approval] `auto` or `operator`. */
    private fun forge(
        approval: String = "auto",
        dir: Path = this.dir,
        newCompiler: ((String, java.time.Instant) -> Unit) -> EcjCompiler = { EcjCompiler(onStuck = it) },
    ): Pair<ForgeService, TestServices> {
        val services = TestServices(dataDir = dir, settings = testSettings(NarratorConfig(forge = ForgeConfig(approval = approval))))
        val store = ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), services.clock, builtInNames = emptyList())
        val forge = ForgeService(services, store, ForgeService.engineDryCheck(services), newCompiler)
        made += forge
        return forge to services
    }

    private fun decisions(services: TestServices, what: String): List<DecisionRecord> =
        services.traces.decisions.filter { it.kind == DecisionRecord.Kind.FORGE && it.decision == what }

    // ── test ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a module that passes is compiled, clean, run at the bench and its scenarios pass`() = runBlocking<Unit> {
        val (forge, services) = forge()
        val report = forge.test(BEACON_NAME, BEACON)

        assertTrue(report.ok, report.text)
        assertTrue(Regex("""ok: compiled \(\d+ ms\), guard clean, smoke: start ok, \d+ facts, 1,200 ticks, acts .*\(checked\), .*; scenarios 1/1""").matches(report.text), report.text)
        assertEquals(listOf("itLightsUp"), report.scenariosPassed)
        val manifest = assertNotNull(report.manifest)
        assertEquals(BEACON_NAME, manifest.name)
        assertEquals(Manifest.hash(BEACON), manifest.hash)
        assertTrue(forge.arsenal.list().isEmpty(), "a test saves nothing")
        val tested = decisions(services, ForgeService.TESTED).single()
        assertEquals(BEACON_NAME, tested.subject)
        assertEquals("true", tested.detail?.get("ok").toString())
    }

    @Test
    fun `the witness module of M5-01 passes through the real pipeline, host and bench`() = runBlocking<Unit> {
        val (forge, _) = forge()
        val report = forge.test(ForgeSamples.NAME, ForgeSamples.SOURCE)

        assertTrue(report.ok, report.text)
        assertEquals(ForgeSamples.manifest.scenarios, report.scenariosPassed)
        println("[forge] the witness at the bench: ${report.text}")
    }

    @Test
    fun `each step of the pipeline stops a module with what is wrong and where`() = runBlocking<Unit> {
        val (forge, services) = forge()

        // 1. The name, the size, a package.
        assertEquals(Manifest.nameProblem("Beacon"), forge.test("Beacon", BEACON).text)
        assertEquals("source is empty: write the whole Java file", forge.test(BEACON_NAME, " \n").text)
        assertEquals("line 2: a module has no package: delete this line", forge.test(BEACON_NAME, "// beacon\npackage games;\n$BEACON").text)

        // 2. The compiler: its diagnostics, with the SDK's hints.
        val mistake = forge.test(BEACON_NAME, BEACON.replace("ctx.acts().say(\"Le Narrateur allume une balise.\");", "ctx.acts().spawn(\"chicken\", player);"))
        assertFalse(mistake.ok)
        assertTrue(mistake.text.startsWith("does not compile: 1 error ("), mistake.text)
        assertTrue("[1] error Beacon.java:" in mistake.text && "hint: Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how)" in mistake.text, mistake.text)
        assertNull(mistake.manifest)

        // 3-4. The sandbox.
        val thread = forge.test(BEACON_NAME, BEACON.replace("ctx.acts().say(\"Le Narrateur allume une balise.\");", "new Thread(() -> {}).start();"))
        assertTrue(Regex("""compiled \(\d+ ms\), refused by the sandbox: \d+ problems?\n\[1] refused Beacon\.java:\d+: java\.lang\.Thread.*""", RegexOption.DOT_MATCHES_ALL).matches(thread.text), thread.text)
        assertEquals(1, decisions(services, ForgeService.REFUSED).size, "a refusal of the sandbox is a decision of its own")

        // 5. The manifest.
        val formless = forge.test(BEACON_NAME, BEACON.replace("@ModuleInfo(name = \"beacon\", summary = \"Fireworks over a player, once a minute.\")\n", ""))
        assertTrue(formless.text.startsWith("compiled (") && "guard clean, but the module's form is wrong: 1 problem\n[1] error Beacon.java: " in formless.text, formless.text)
        assertTrue("@ModuleInfo" in formless.text, formless.text)

        // 7. The bench: a scenario that fails, with its line.
        val failing = forge.test(BEACON_NAME, BEACON.replace("a.startsWith(\"fireworks\")", "a.startsWith(\"lightning\")"))
        assertFalse(failing.ok)
        assertTrue(failing.text.startsWith("compiled (") && "; scenarios 0/1\n" in failing.text, failing.text)
        assertTrue("fault scenario itLightsUp: check failed: fireworks over Bartok" in failing.text, failing.text)
        assertTrue(Regex("""\n {2}\d+ \| b\.check\(""").containsMatchIn(failing.text), "the line of the check is quoted: ${failing.text}")
        assertEquals(emptyList(), failing.scenariosPassed)
    }

    @Test
    fun `the acts are checked dry by the real tools at chaos 3`() = runBlocking<Unit> {
        val (forge, _) = forge()
        val harsh = forge.test(BEACON_NAME, BEACON.replace("ctx.acts().say(\"Le Narrateur allume une balise.\");", "ctx.acts().punish(player, \"tiny\", 5, \"a bougé\");"))

        assertFalse(harsh.ok, harsh.text)
        assertTrue("(1 refused by the dry checks)" in harsh.text, harsh.text)
        assertTrue(
            "refused act \"punish Bartok tiny 5\": Invalid arguments: at chaos 3 a punishment has a severity of at most 4 (asked 5)" in harsh.text,
            harsh.text,
        )
    }

    @Test
    fun `a report stays within its budget, the Invalid arguments before it included`() = runBlocking<Unit> {
        val (forge, _) = forge()
        val lines = (1..40).joinToString("\n") { "        ctx.acts().spwan$it(\"chicken\", player, Spawn.of($it));" }
        val report = forge.test(BEACON_NAME, BEACON.replace("ctx.acts().say(\"Le Narrateur allume une balise.\");", lines))

        assertTrue(report.text.startsWith("does not compile: 40 errors"), report.text.take(200))
        assertTrue(report.text.length <= ForgeService.REPORT_CHARS, "${report.text.length}")
        assertTrue("Invalid arguments: ${report.text}".length <= ForgeLimits.TEST_REPORT_CHARS)
        assertTrue(report.text.endsWith("more not shown"), report.text.takeLast(200))
    }

    // ── save, review ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `save wants a passing test and a scenario, then writes the next version, ready`() = runBlocking<Unit> {
        val (forge, services) = forge()

        val noScenario = assertFailsWith<ForgeRejected> { forge.save(BEACON_NAME, BEACON_WITHOUT_SCENARIOS, "Fireworks over a player.", "forger T-31") }
        assertTrue(noScenario.toResult().text.startsWith("Invalid arguments: compiled ("), noScenario.toResult().text)
        assertTrue(noScenario.message!!.endsWith("guard clean, but ${ForgeService.NO_SCENARIO}"), noScenario.message)
        val failing = assertFailsWith<ForgeRejected> { forge.save(BEACON_NAME, BEACON.replace("\"fireworks\"", "\"lightning\""), "note", "forger T-31") }
        assertTrue("fault scenario itLightsUp" in failing.message!!, failing.message)
        assertTrue(forge.arsenal.list().isEmpty(), "nothing saved")
        val untested = forge.test(BEACON_NAME, BEACON_WITHOUT_SCENARIOS)
        assertTrue(untested.ok && untested.text.endsWith("; no scenarios; forge_save needs one scenario at least"), "a test needs no scenario: ${untested.text}")

        val v1 = forge.save(BEACON_NAME, BEACON, "Fireworks over a player, once a minute.", "forger T-31")
        assertEquals(1, v1.version)
        assertEquals(ModuleStatus.READY, v1.status)
        assertEquals("forger T-31", v1.by)
        assertEquals(listOf("itLightsUp"), v1.scenarios)
        assertEquals(Manifest.hash(BEACON), v1.hash)
        assertEquals(BEACON, Files.readString(dir.resolve("forge/beacon/v1.java")))
        assertTrue(Files.exists(dir.resolve("forge/beacon/v1.json")))
        val v2 = forge.save(BEACON_NAME, BEACON.replace("every(200", "every(100"), "Fireworks twice as often.", "forger T-32")
        assertEquals(2, v2.version)
        assertEquals(listOf(2, 1), forge.arsenal.list().map { it.version })
        assertEquals(2, decisions(services, ForgeService.SAVED).size)
    }

    @Test
    fun `with operator approval a saved version waits, build refuses it until an operator approves it`() = runBlocking<Unit> {
        val (forge, services) = forge(approval = "operator")
        val v1 = forge.save(BEACON_NAME, BEACON, "Fireworks over a player.", "forger T-31")
        assertEquals(ModuleStatus.PENDING, v1.status)

        val waiting = assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, null) }
        assertEquals("DENIED (awaiting_approval): beacon v1 waits for an operator's approval (/narrator forge approve beacon 1)", waiting.toResult().text)
        assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, 1) }

        val approved = forge.review(BEACON_NAME, null, approve = true, operator = "Clement")
        assertEquals(ModuleStatus.READY, approved.status)
        assertEquals(BEACON_NAME, forge.build(BEACON_NAME, null).entry.name)
        assertEquals("by Clement", decisions(services, ForgeService.APPROVED).single().reason)

        forge.save(BEACON_NAME, BEACON, "The same again.", "forger T-32")
        val rejected = forge.review(BEACON_NAME, 2, approve = false, operator = "Clement")
        assertEquals(ModuleStatus.REJECTED, rejected.status)
        assertEquals("Invalid arguments: beacon v2 was rejected by an operator: deploy another version", assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, 2) }.toResult().text)
        assertEquals(1, forge.build(BEACON_NAME, null).entry.version, "the latest ready one")
        assertEquals(1, decisions(services, ForgeService.REJECTED).size)
        assertFailsWith<ForgeRejected> { forge.review(BEACON_NAME, 1, approve = true, operator = "Clement") }
    }

    // ── build and its cache ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `build takes the cache, a restart builds again from the source, and a version that no longer builds is broken`() = runBlocking<Unit> {
        val (forge, _) = forge()
        assertEquals("Invalid arguments: no module beacon in the arsenal", assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, null) }.toResult().text)
        forge.save(BEACON_NAME, BEACON, "Fireworks over a player.", "forger T-31")
        val saved = forge.build(BEACON_NAME, null)
        assertSame(saved.classes, forge.build(BEACON_NAME, 1).classes, "a save fills the cache")
        assertEquals("Beacon", saved.mainClass)
        assertTrue(saved.meterHolder in saved.classes, "rewritten: ${saved.classes.keys}")
        assertEquals("Invalid arguments: beacon has no version 3 (its versions: 1)", assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, 3) }.toResult().text)
        forge.stop()

        // A restart: the forged versions are built again from their sources, checked against their hashes, in the background.
        val (again, services) = forge()
        again.start().join()
        val compiled = decisions(services, ForgeService.COMPILED).single()
        assertEquals("beacon v1", compiled.subject)
        val hits = (1..1_000).map {
            val began = System.nanoTime()
            again.build(BEACON_NAME, null)
            System.nanoTime() - began
        }.sorted()
        val first = again.build(BEACON_NAME, null)
        assertSame(first.classes, again.build(BEACON_NAME, null).classes, "from the cache")
        println("[forge] build of beacon v1 after a restart: ${compiled.reason} (a miss); from the cache: median ${hits[hits.size / 2] / 1_000} µs, max ${hits.last() / 1_000} µs")
        again.stop()

        // A source changed on disk: broken at the restart, refused, the version before it untouched.
        Files.writeString(dir.resolve("forge/beacon/v1.java"), BEACON.replace("once a minute", "twice a minute"))
        val (changed, _) = forge()
        val broken = assertFailsWith<ForgeRejected> { changed.build(BEACON_NAME, 1) }
        assertEquals("FAILED: beacon v1 is broken: its source v1.java no longer matches its hash: it was changed on disk", broken.toResult().text)
        assertEquals(ModuleStatus.BROKEN, changed.arsenal.entry(BEACON_NAME, 1)?.status)
    }

    @Test
    fun `a saved version that no longer compiles against the SDK of now is broken, with its first diagnostic`() = runBlocking<Unit> {
        // As the SDK changing would leave it: a version whose source, its hash right, no longer compiles.
        val source = BEACON.replace("ctx.acts().say(", "ctx.acts().shout(")
        val module = dir.resolve("forge/beacon")
        Files.createDirectories(module)
        Files.writeString(module.resolve("v1.java"), source)
        val entry = ForgeSamples.manifest.copy(name = BEACON_NAME, version = 1, hash = Manifest.hash(source), params = emptyList(), scenarios = listOf("itLightsUp"))
        Files.writeString(module.resolve("v1.json"), Manifest.encode(entry))

        val (forge, services) = forge()
        forge.start().join()
        assertEquals(ModuleStatus.BROKEN, forge.arsenal.entry(BEACON_NAME, 1)?.status, "found at the start")
        val problem = assertNotNull(forge.arsenal.entry(BEACON_NAME, 1)?.problem)
        assertTrue(problem.startsWith("it no longer builds against the SDK of now: Beacon.java:") && "shout" in problem, problem)
        assertEquals("FAILED: beacon v1 is broken: $problem", assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, 1) }.toResult().text)
        assertEquals("beacon v1", decisions(services, ForgeService.BROKEN).single().subject)
        assertEquals(BEACON_NAME, forge.test(BEACON_NAME, BEACON).manifest?.name, "the forger may fix it: the next version")
    }

    // ── unavailable, threads ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stuck compiler makes the Forge unavailable until the server restarts`() = runBlocking<Unit> {
        val hold = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val sdk = ForgeModule::class.java.classLoader
        val holding = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? {
                if (armed.get()) hold.await()
                return sdk.getResourceAsStream(name)
            }
        }
        val (forge, services) = forge(newCompiler = { onStuck -> EcjCompiler(holding, budgetMs = 200, stuckMs = 600, onStuck = onStuck) })
        try {
            val tooLong = assertFailsWith<ForgeRejected> { forge.test(BEACON_NAME, BEACON) }
            assertEquals("FAILED: the compilation of beacon took too long (over 10 s): write a simpler source", tooLong.toResult().text)
            withTimeoutOrFail { !forge.available }
            assertTrue(forge.unavailableReason!!.startsWith("the compiler is stuck since "), forge.unavailableReason)
            assertTrue(forge.status().compiler.startsWith("stuck since "), forge.status().compiler)
            val refused = assertFailsWith<ForgeRejected> { forge.test(BEACON_NAME, BEACON) }
            assertTrue(refused.toResult().text.startsWith("DENIED (forge_off): the Forge is unavailable: the compiler is stuck since "), refused.toResult().text)
            assertFailsWith<ForgeRejected> { forge.build(BEACON_NAME, null) }
            assertEquals("compiler", decisions(services, ForgeService.STUCK).single().subject)
        } finally {
            armed.set(false)
            hold.countDown()
        }
    }

    @Test
    fun `nothing heavy runs on the caller's thread - compilation, bench and disk run elsewhere`() {
        val (forge, _) = forge()
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        caller.use {
            runBlocking<Unit>(caller) {
                val gaps = CopyOnWriteArrayList<Long>()
                val ticker = launch {
                    var last = System.nanoTime()
                    while (isActive) {
                        delay(5)
                        val now = System.nanoTime()
                        gaps += now - last
                        last = now
                    }
                }
                val cpu = ManagementFactory.getThreadMXBean()
                val cpuBefore = cpu.currentThreadCpuTime
                val began = System.nanoTime()
                val report = forge.test(ForgeSamples.NAME, ForgeSamples.SOURCE)
                val saved = forge.save(BEACON_NAME, BEACON, "Fireworks over a player.", "forger T-31")
                val took = (System.nanoTime() - began) / 1_000_000
                val callerCpu = (cpu.currentThreadCpuTime - cpuBefore) / 1_000_000
                ticker.cancelAndJoin()
                assertTrue(report.ok, report.text)
                assertEquals(1, saved.version)
                val longest = gaps.max() / 1_000_000
                println("[forge] a test and a save took $took ms, $callerCpu ms of them on the caller's thread (its ticker included); its longest gap $longest ms")
                assertTrue(callerCpu * 5 < took * 2, "the caller's thread worked $callerCpu ms of $took ms")
                assertTrue(longest < 400, "the caller's thread was held $longest ms")
            }
        }
    }

    private suspend fun withTimeoutOrFail(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out" }
            delay(20)
        }
    }

    companion object {
        const val BEACON_NAME = "beacon"

        /** A small module that passes: it speaks once, then fireworks over its player every 10 s; one scenario. */
        val BEACON = """
            import io.github.clemsix6.narrator2.forge.api.*;
            import io.github.clemsix6.narrator2.forge.api.test.Bench;

            @ModuleInfo(name = "beacon", summary = "Fireworks over a player, once a minute.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Over whom.")
            public final class Beacon implements ForgeModule {
                @Override
                public void start(ModuleContext ctx) {
                    String player = ctx.args().player("player");
                    ctx.acts().say("Le Narrateur allume une balise.");
                    ctx.timers().every(200, () -> {
                        PlayerInfo info = ctx.world().player(player);
                        if (info != null) ctx.acts().fireworks(info.at(), 1);
                    });
                }

                public static final class Scenarios {
                    public void itLightsUp(Bench b) {
                        b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
                        b.start("player=Bartok");
                        b.run(220);
                        b.check(b.acts().stream().anyMatch(a -> a.startsWith("fireworks")), "fireworks over Bartok");
                    }
                }
            }
        """.trimIndent() + "\n"

        val BEACON_WITHOUT_SCENARIOS: String = BEACON.substringBefore("    public static final class Scenarios").trimEnd() + "\n}\n"

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
