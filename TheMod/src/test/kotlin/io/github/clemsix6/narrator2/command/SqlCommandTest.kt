package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.journal.SqlOutcome
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    /** The decision lands in `engine_decisions`, which the model reads (M2 review, finding 20). */
    @Test
    fun `the decision keeps a fingerprint, the length and the status, never the query, an error or a count`() {
        val secret = "the base is behind the waterfall"
        val query = "SELECT text FROM private_messages WHERE text LIKE '%$secret%'"
        val outcomes = listOf(
            SqlOutcome.Rows("now 09-25 21:44:10 · 1 row · 3 ms\ntext\n$secret", shown = 1, total = 1, elapsedMs = 3) to "ok",
            SqlOutcome.Rejected("one SELECT per call (got 2 statements)") to "refused",
            SqlOutcome.Failed("Conversion Error: Could not convert string '$secret' to INT32") to "failed",
            SqlOutcome.TimedOut(10) to "timed_out",
        )
        val fingerprints = outcomes.map { (outcome, status) ->
            val detail = SqlCommand.detail(query, outcome)
            val reason = SqlCommand.reason(query, outcome)
            assertEquals(setOf("query_hash", "query_chars", "status"), detail.keys)
            assertEquals(status, detail["status"]!!.jsonPrimitive.content)
            assertEquals(query.length, detail["query_chars"]!!.jsonPrimitive.int)
            val fingerprint = detail["query_hash"]!!.jsonPrimitive.content
            assertTrue(fingerprint.matches(Regex("[0-9a-f]{16}")), fingerprint)
            assertEquals("$status, query $fingerprint (${query.length} chars)", reason)
            listOf(secret, "private_messages", "SELECT", "Conversion", "statements").forEach { word ->
                assertFalse(word in reason || word in detail.toString(), "$word in $reason / $detail")
            }
            fingerprint
        }
        assertEquals(1, fingerprints.toSet().size, "the same query, the same fingerprint")
        assertNotEquals(fingerprints.first(), SqlCommand.detail("SELECT 1", outcomes.first().first)["query_hash"]!!.jsonPrimitive.content)
        // Keyed: the model cannot check its guesses with DuckDB's sha256().
        val sha256 = MessageDigest.getInstance("SHA-256").digest(query.toByteArray()).joinToString("") { "%02x".format(it) }
        assertFalse(sha256.startsWith(fingerprints.first()))
    }
}
