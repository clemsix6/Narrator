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
    fun `a model without a price has no cost, nothing guessed from its name`() {
        assertNull(prices.cost("claude-opus-4-8", 1_000, 1_000, 0, 0), "a fallback model the configuration does not price")
        assertNull(prices.cost("claude-opus-5-5-20261001", 1_000, 1_000, 0, 0))
        assertNull(prices.of("claude-haiku"))
        assertEquals("$ ?", Prices.dollars(null))
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
