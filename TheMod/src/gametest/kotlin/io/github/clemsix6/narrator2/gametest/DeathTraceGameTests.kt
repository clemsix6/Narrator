package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the observability fixes of the M2 review (finding 18): every death writes a
 * `death_guard died` decision that ties it to the Narrator's actions on the victim in the minute before,
 * with the guard's state, and `/narrator why <player>` shows it; `guard allowed` decisions are logged at
 * INFO.
 *
 * Owned by the M2 review's lot EF. Shared helpers: `GameTestSupport.kt`.
 */
class DeathTraceGameTests {

    /**
     * The Narrator made a player levitate 20 s ago and the guard is no longer armed: the player's death is
     * an ordinary one, but its decision and `/narrator why` name the levitation.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDeathIsTiedToWhatTheNarratorDidToThePlayerJustBefore(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val trace = runtime.trace
        val victim = TestPlayer(helper, "dt_")
        val name = victim.name
        val turn = trace.nextId(IdKind.TURN)
        try {
            victim.makeVulnerable()
            trace.toolCall(
                ToolCallRecord(
                    trace.nextId(IdKind.TOOL_CALL), runtime.clock.instant().minusSeconds(20), "director-ef-test", turn, null, "toolu_ef", Role.DIRECTOR,
                    "apply_effect", """{"players":["$name"],"effect":"levitation"}""", ToolStatus.OK, "ok: levitation II on $name for 10 s", 30,
                    listOf(name), ToolEffect.WORLD,
                ),
            )
            victim.live.hurtServer(helper.level, helper.level.damageSources().fall(), 1_000f)
            helper.assertTrue(victim.live.isDeadOrDying, "the player died")
        } finally {
            victim.close()
        }

        val reason = AtomicReference<String?>()
        CoroutineScope(Dispatchers.IO).launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (reason.get() == null && System.currentTimeMillis() < deadline) {
                delay(200)
                runtime.journal.barrier()
                reason.set(
                    runtime.journal.read(5_000) { connection ->
                        connection.prepareStatement("SELECT reason FROM engine_decisions WHERE kind = 'death_guard' AND decision = 'died' AND subject = ?").use { statement ->
                            statement.setString(1, name)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                        }
                    },
                )
            }
        }
        val output = CommandOutput()
        helper.startSequence()
            .thenAwait { helper.assertTrue(reason.get() != null, "the death_guard died decision of $name") }
            .thenExecute {
                val sentence = reason.get()!!
                helper.assertTrue(sentence.startsWith("minecraft:fall, not the Narrator's (guard not armed), inventory dropped; "), "the blame and the guard: $sentence")
                helper.assertTrue(sentence.endsWith("the Narrator acted on $name in the last 1m: apply_effect 20s before (turn $turn)"), "the action: $sentence")
                val server = helper.level.server
                server.commands.performPrefixedCommand(server.createCommandSourceStack().withSource(output), "narrator why $name")
            }
            .thenAwait {
                val lines = output.lines
                helper.assertTrue(lines.any { it.startsWith("[Narrator] why: $name's last death · ") }, "why player: $lines")
                helper.assertTrue("Cause: minecraft:fall · not the Narrator's · inventory dropped" in lines, "the cause: $lines")
                helper.assertTrue("  -20 s apply_effect (turn #$turn) ok: ok: levitation II on $name for 10 s" in lines, "the action: $lines")
            }
            .thenSucceed()
    }

    /** A hostile action the guards allow is logged at INFO, like a refusal: the trace shows both. */
    @GameTest
    fun anAllowedHostileActionIsLoggedAtInfo(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val target = TestPlayer(helper, "ga_")
        try {
            val guards = GuardsImpl(TestServices(minecraft = helper.level.server, players = runtime.players))
            val verdict = LogCapture().use { logs ->
                val verdict = guards.hostile(target.live, Caller(Role.DIRECTOR, "guard-test", turnId = 1, taskId = null), System.currentTimeMillis() + 60_000)
                helper.assertTrue(
                    logs.messages.any { it.startsWith("[Guard] allowed ${target.name}: hostile action; the punishment runs until ") },
                    "the allowed action in the log: ${logs.messages}",
                )
                verdict
            }
            helper.assertTrue(verdict !is Verdict.Denied, "allowed: $verdict")
        } finally {
            target.close()
        }
        helper.succeed()
    }
}
