package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.journal.SqlOutcome
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What an operator reads after `/narrator sql` (M1_M2 §6.6). */
class SqlCommandTest {
    /** Each line's text, with the color of its last part (the message after the `[Narrator]` prefix). */
    private fun rendered(outcome: SqlOutcome) = SqlCommand.lines(outcome).map { line ->
        line.string to (line.siblings.lastOrNull() ?: line).style.color
    }

    @Test
    fun `rows come as gray lines, one per line of the result`() {
        val gray = TextColor.fromLegacyFormat(ChatFormatting.GRAY)
        val text = "now 09-25 21:44:10 · 1 row · 3 ms\nplayer|diamonds\nBartok|41"
        assertEquals(
            listOf("now 09-25 21:44:10 · 1 row · 3 ms" to gray, "player|diamonds" to gray, "Bartok|41" to gray),
            rendered(SqlOutcome.Rows(text, shown = 1, total = 1, elapsedMs = 3)),
        )
    }

    @Test
    fun `errors come in red`() {
        val red = TextColor.fromLegacyFormat(ChatFormatting.RED)
        assertEquals(
            listOf("[Narrator] query refused: one SELECT per call (got 2 statements)" to red),
            rendered(SqlOutcome.Rejected("one SELECT per call (got 2 statements)")),
        )
        assertEquals(
            listOf(
                "[Narrator] query failed: Binder Error: Referenced column \"nope\" not found" to red,
                "LINE 1: SELECT nope FROM chat" to red,
            ),
            rendered(SqlOutcome.Failed("Binder Error: Referenced column \"nope\" not found\n\nLINE 1: SELECT nope FROM chat")),
        )
        assertEquals(
            listOf("[Narrator] the query did not finish within 10 s: aggregate, filter or add LIMIT." to red),
            rendered(SqlOutcome.TimedOut(10)),
        )
    }
}
