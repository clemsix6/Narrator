package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.config.ModelPrice
import io.github.clemsix6.narrator2.config.NarratorConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PricesTest {
    private val prices = Prices.of(NarratorConfig())

    @Test
    fun `a call of Opus costs its tokens at the prices of the configuration`() {
        // 1 M input at 4 $, 1 M output at 20 $, 1 M cache read at 0.20 $, 1 M cache write at 8 $ (1 h).
        assertEquals(4.0, prices.cost("claude-opus-5-5", 1_000_000, 0, 0, 0)!!, 1e-9)
        assertEquals(20.0, prices.cost("claude-opus-5-5", 0, 1_000_000, 0, 0)!!, 1e-9)
        assertEquals(0.20, prices.cost("claude-opus-5-5", 0, 0, 1_000_000, 0)!!, 1e-9)
        assertEquals(8.0, prices.cost("claude-opus-5-5", 0, 0, 0, 1_000_000)!!, 1e-9)
        // A Director's turn: 1 200 input, 60 000 cache read, 300 output.
        assertEquals((1_200 * 4.0 + 60_000 * 0.20 + 300 * 20.0) / 1_000_000, prices.cost("claude-opus-5-5", 1_200, 300, 60_000, 0)!!, 1e-12)
    }

    @Test
    fun `a call of Haiku costs a fraction of a cent`() {
        val cost = prices.cost("claude-haiku-5-5", input = 900, output = 40, cacheRead = 0, cacheWrite = 0)!!
        assertEquals((900 * 0.10 + 40 * 0.50) / 1_000_000, cost, 1e-15)
        assertEquals("\$0.00011", Prices.dollars(cost))
    }

    @Test
    fun `the fallback model has its price by default`() {
        // Opus 4.8 at 5 / 25 $ per million, cache reads at 0.1x and 1 h cache writes at 2x the input.
        assertEquals(ModelPrice(5.0, 25.0, 0.50, 10.0), prices.of("claude-opus-4-8"))
        assertEquals(10.0, prices.cost("claude-opus-4-8", 0, 0, 0, 1_000_000)!!, 1e-9)
        val turn = prices.cost("claude-opus-4-8", input = 1_234, output = 300, cacheRead = 60_000, cacheWrite = 0)
        assertEquals((1_234 * 5.0 + 60_000 * 0.50 + 300 * 25.0) / 1_000_000, turn!!, 1e-12)
        assertEquals("\$0.044", Prices.dollars(turn))
    }

    @Test
    fun `a model without a price has no cost, nothing guessed from its name`() {
        assertNull(prices.cost("claude-opus-4-7", 1_000, 1_000, 0, 0), "a model the configuration does not price")
        assertNull(prices.cost("claude-opus-5-5-20261001", 1_000, 1_000, 0, 0))
        assertNull(prices.of("claude-haiku"))
        assertEquals("$ ?", Prices.dollars(null))
    }

    @Test
    fun `a cost is in dollars, one label for the calls without a price, and nothing is guessed from a name`() {
        val prices = Prices(mapOf("claude-x" to ModelPrice(input = 1.0, output = 2.0, cacheRead = 0.1, cacheWrite = 1.25)))
        val x = ModelTokens("claude-x", 3, 0, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
        val unknown = ModelTokens("claude-x-2", 2, 1, 1_000, 0, 0, 10)
        assertEquals(Cost(4.35, 3, 0), prices.costOf(listOf(x)))
        assertEquals("\$4.35", prices.costOf(listOf(x)).text)
        assertEquals("\$ ?", prices.costOf(listOf(unknown)).text)
        assertEquals("\$4.35 + 2 calls at \$ ?", prices.costOf(listOf(x, unknown)).text)
        assertEquals("\$4.35 + 1 call at \$ ?", prices.costOf(listOf(x, unknown.copy(calls = 1))).text)
        assertEquals("\$0", prices.costOf(emptyList()).text)
        assertEquals("\$0", (Cost.NONE + Cost.NONE).text)
        assertEquals(ModelTokens("all", 5, 1, 1_001_000, 1_000_000, 1_000_000, 1_000_010), ModelTokens.sum("all", listOf(x, unknown)))
        assertEquals(3_000_000, x.read)
    }

    @Test
    fun `dollars show cents from a dollar up, two significant digits below`() {
        assertEquals("$0", Prices.dollars(0.0))
        assertEquals("$12.40", Prices.dollars(12.4))
        assertEquals("$1.92", Prices.dollars(1.919))
        assertEquals("$0.42", Prices.dollars(0.4211))
        assertEquals("$0.042", Prices.dollars(0.0421))
        assertEquals("$0.0042", Prices.dollars(0.00421))
        assertEquals("$0.000040", Prices.dollars(0.00004))
    }

    @Test
    fun `a cost rounded up to a dollar shows its cents`() {
        assertEquals(listOf("$1.00", "$0.99", "$0.042", "$1.00"), listOf(0.996, 0.9949, 0.042, 1.0).map(Prices::dollars))
        assertEquals("$1.00", Prices.dollars(0.995_000_1))
        assertEquals("$10.00", Prices.dollars(9.996))
        assertEquals("$0.10", Prices.dollars(0.0996), "two significant digits below a dollar")
    }

    @Test
    fun `the prices of the configuration file are those applied`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve(ConfigStore.FILE_NAME),
            """{ "prices": { "claude-opus-5-5": { "output": 25 }, "claude-x": { "input": 1, "output": 2, "cacheRead": 0.1, "cacheWrite": 1.25 } } }""",
        )
        val config = ConfigStore.load(dir)
        val prices = Prices.of(config)
        assertEquals(ModelPrice(4.0, 25.0, 0.20, 8.0), prices.of("claude-opus-5-5"), "a default model merged key by key")
        assertEquals(25.0, prices.cost("claude-opus-5-5", 0, 1_000_000, 0, 0)!!, 1e-9)
        assertEquals(1.0 + 2.0, prices.cost("claude-x", 1_000_000, 1_000_000, 0, 0)!!, 1e-9, "a new model with its four prices")
        assertEquals(0.10, prices.cost("claude-haiku-5-5", 1_000_000, 0, 0, 0)!!, 1e-9, "the other default kept")
    }
}
