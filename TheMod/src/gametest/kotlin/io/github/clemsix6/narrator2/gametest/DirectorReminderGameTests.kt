package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.director.Director
import io.github.clemsix6.narrator2.director.DirectorHarness
import io.github.clemsix6.narrator2.director.GameView
import io.github.clemsix6.narrator2.director.ReminderState
import io.github.clemsix6.narrator2.director.ReminderStore
import io.github.clemsix6.narrator2.director.WorldReminderStore
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.of
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * In-game test of the persistent reminders (M4 §15.1, M4-10): the reminders of `remind_me` live in the world's
 * global attachment `narrator2:reminders`, so that the Director of the next run of the server finds them.
 *
 * It runs in the Director's batch (`narrator2-gametest:director`) with the other tests of the Director. Its
 * Directors are built on the running Narrator's services and never started: they open no chapter and call no
 * model, and the running Director is left alone. The attachment is put back as the test found it.
 *
 * Owned by M4-10. Shared helpers: `GameTestSupport.kt`.
 */
class DirectorReminderGameTests {

    /**
     * A reminder set by a Director is saved in the world at once; a Director created from the world, as when
     * the server starts again, finds it with its note and its time, and the numbers go on.
     */
    @GameTest(environment = DIRECTOR_ENVIRONMENT)
    fun aReminderIsKeptInTheWorldAndTheNextDirectorFindsIt(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val global = helper.level.server.globalAttachments()
        val before = global.getAttached(ReminderState.TYPE)
        try {
            val game = object : GameView by DirectorHarness.FakeGame() {
                override val reminders: ReminderStore = WorldReminderStore(helper.level.server)
            }
            val caller = Caller(Role.DIRECTOR, "gametest-reminders", 1, null)
            val first = Director(runtime, game, { ToolSet.of(emptyList()) })
            val id = first.scheduleReminder(600, "vérifier la statue de Mimosa", caller).getOrThrow()
            val due = first.status().reminders.single { it.id == id }.at

            val saved = global.getAttached(ReminderState.TYPE)?.reminders?.singleOrNull { it.id == id }
            helper.assertEquals("vérifier la statue de Mimosa", saved?.note, "R-$id in the world's attachment")
            helper.assertEquals(due, saved?.at, "its time")

            // The next run of the server: a new Director reads the world when it is created.
            val next = Director(runtime, game, { ToolSet.of(emptyList()) })
            val found = next.status().reminders.singleOrNull { it.id == id }
            helper.assertEquals("vérifier la statue de Mimosa", found?.note, "the next Director finds R-$id")
            helper.assertEquals(due, found?.at, "at the same time")
            helper.assertEquals(id + 1, next.scheduleReminder(60, "plus tard", caller).getOrThrow(), "the numbers go on")
            helper.assertEquals(listOf(id, id + 1), global.getAttached(ReminderState.TYPE)?.reminders?.map { it.id }?.filter { it >= id }?.sorted(), "both in the world")
        } finally {
            if (before == null) global.removeAttached(ReminderState.TYPE) else global.setAttached(ReminderState.TYPE, before)
        }
        helper.succeed()
    }
}
