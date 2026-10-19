package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The id `/duel accept|decline` reads (M4 §18.2); the command itself runs in game (`DuelGameTests`). */
class DuelCommandTest {
    @Test
    fun `a duel is named by its canon reference, with or without its prefix`() {
        val c52 = Ref(RefKind.CANON, 52)
        assertEquals(c52, DuelCommand.refOf("C-52"))
        assertEquals(c52, DuelCommand.refOf("c52"))
        assertEquals(c52, DuelCommand.refOf("52"))
        assertNull(DuelCommand.refOf("M-52"), "a memory, not a duel")
        assertNull(DuelCommand.refOf("duel"))
        assertNull(DuelCommand.refOf("0"))
    }
}
